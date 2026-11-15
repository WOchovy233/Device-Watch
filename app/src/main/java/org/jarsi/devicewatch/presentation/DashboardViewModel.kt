package org.jarsi.devicewatch.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.jarsi.devicewatch.data.HealthAlert
import org.jarsi.devicewatch.data.QuickMetric
import org.jarsi.devicewatch.data.storageUsedBytes
import org.jarsi.devicewatch.data.AppSettingsRepository
import org.jarsi.devicewatch.data.AppUsageRepository
import org.jarsi.devicewatch.data.CHARGE_LIMIT_MAX
import org.jarsi.devicewatch.data.CHARGE_LIMIT_MIN
import org.jarsi.devicewatch.data.DATA_QUOTA_MAX_GB
import org.jarsi.devicewatch.data.DATA_QUOTA_MIN_GB
import org.jarsi.devicewatch.data.DataBreakdown
import org.jarsi.devicewatch.data.StorageBreakdown
import org.jarsi.devicewatch.data.DeviceState
import org.jarsi.devicewatch.data.DataCounterMode
import org.jarsi.devicewatch.data.CpuProfileNotice
import org.jarsi.devicewatch.data.CpuProfiles
import org.jarsi.devicewatch.data.DataPeriodCalculator
import org.jarsi.devicewatch.data.DeviceInfo
import org.jarsi.devicewatch.data.NotificationStats
import org.jarsi.devicewatch.data.PrivilegedAccess
import org.jarsi.devicewatch.data.RootAccess
import org.jarsi.devicewatch.data.RootShell
import org.jarsi.devicewatch.data.ShizukuAccess
import org.jarsi.devicewatch.data.ShizukuShell
import org.jarsi.devicewatch.data.SystemStats
import org.jarsi.devicewatch.data.SystemStatsRepository
import org.jarsi.devicewatch.data.UNAVAILABLE_INT
import org.jarsi.devicewatch.data.UsageHistory
import org.jarsi.devicewatch.data.recordTotals
import org.jarsi.devicewatch.overlay.limitOverlaySelection
import org.jarsi.devicewatch.overlay.overlayMetricsOrDefault
import org.jarsi.devicewatch.system.AlertNotifications
import org.jarsi.devicewatch.system.MonitorServiceRelay
import java.time.LocalDate
import java.time.ZoneId
import org.jarsi.devicewatch.widget.WidgetController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

const val DEFAULT_WIDGET_OPACITY = 0.86f
internal const val MIN_PULL_INDICATOR_MS = 800L

internal suspend fun delayForPullIndicator(pullStartMillis: Long) {
    val elapsed = System.currentTimeMillis() - pullStartMillis
    if (elapsed < MIN_PULL_INDICATOR_MS) delay(MIN_PULL_INDICATOR_MS - elapsed)
}

data class DashboardUiState(
    val stats: SystemStats? = null,
    val deviceInfo: DeviceInfo? = null,
    val isRefreshing: Boolean = false,
    val isWidgetInstalled: Boolean = false,
    val hideWidgetHint: Boolean = false, // 🎯 新增：控制主页牛皮癣
    val lastUpdated: String = "--:--",
    val widgetOpacity: Float = DEFAULT_WIDGET_OPACITY,
    val widgetBlackBackground: Boolean = false,
    val dataCounterMode: DataCounterMode = DataCounterMode.DAY,
    val cycleStartDay: Int = 1,
    val chargeLimitPercent: Int = 0,
    val chargeLimitPlaySound: Boolean = true,
    val chargeLimitVibrate: Boolean = true,
    val dataQuotaGb: Double = 0.0,
    val screenTimeMillis: Long = -1L,
    val screenOnMillis: Long = -1L,
    val screenOnTrackingSupported: Boolean = true,
    val unlockCount: Int = UNAVAILABLE_INT,
    val notificationCount: Int = UNAVAILABLE_INT,
    val bootCount: Int = 0,
    val chargeCount: Int = 0,
    val unlockCountingSupported: Boolean = true,
    val periodComparison: PeriodComparisonData? = null,
    val usageAccessEnabled: Boolean = false,
    val notificationAccessEnabled: Boolean = false,
    val onboardingCompleted: Boolean? = null,
    val classicLook: Boolean = false,
    val rootStatus: RootStatus = RootStatus.OFF,
    val shizukuStatus: ShizukuStatus = ShizukuStatus.OFF,
    val cpuCapsKeptUntilRestart: Boolean = false,
    val enabledAlerts: Set<HealthAlert> = emptySet(),
    val notificationMetrics: Set<QuickMetric> = emptySet(),
    val tileValueIcons: Boolean = true,
    val overlayShown: Boolean = false,
    val overlayMetrics: Set<QuickMetric> = emptySet(),
    val overlayPermission: Boolean = false,
    val dataBreakdown: DataBreakdown? = null,
    val storageBreakdown: StorageBreakdown? = null,
    val deviceState: DeviceState? = null,
    val exemptAppsList: List<String> = emptyList(),
    val isExemptAppsLoading: Boolean = false
)

enum class RootStatus { OFF, REQUESTING, ON, DENIED, NO_SU, RESTORING }
enum class ShizukuStatus { OFF, REQUESTING, ON, WAITING, DENIED, NOT_RUNNING }

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: SystemStatsRepository,
    private val widgetController: WidgetController,
    private val settings: AppSettingsRepository,
    private val appUsageRepository: AppUsageRepository,
    private val notificationStats: NotificationStats,
    private val usageHistory: UsageHistory,
    private val monitorRelay: MonitorServiceRelay,
    private val alertNotifications: AlertNotifications,
    private val rootShell: RootShell,
    private val shizukuShell: ShizukuShell,
    private val cpuProfiles: CpuProfiles,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        val access = settings.privilegedAccess()
        _uiState.update {
            it.copy(
                onboardingCompleted = settings.onboardingShown(),
                classicLook = settings.classicLook(),
                hideWidgetHint = settings.hideWidgetHint(), // 🎯 初始化读取
                rootStatus = if (access == PrivilegedAccess.ROOT) RootStatus.ON else RootStatus.OFF,
                shizukuStatus = if (access == PrivilegedAccess.SHIZUKU) ShizukuStatus.ON else ShizukuStatus.OFF,
                enabledAlerts = HealthAlert.entries.filter(settings::alertEnabled).toSet(),
                notificationMetrics = settings.notificationMetrics(),
                tileValueIcons = settings.tileValueIcons(),
                overlayShown = settings.overlayShown(),
                overlayMetrics = overlayMetricsOrDefault(settings.overlayMetrics()),
            )
        }
        viewModelScope.launch {
            if (!shizukuShell.alive.value) delay(2_000L)
            shizukuShell.alive.collect(::onShizukuAlive)
        }
        viewModelScope.launch { rootShell.lost.collect { lost -> if (lost) onRootLost() } }
        viewModelScope.launch { settings.privilegedAccessFlow.collect(::onPrivilegedAccess) }
        viewModelScope.launch { settings.overlayShownFlow.collect { shown -> _uiState.update { it.copy(overlayShown = shown) } } }
        viewModelScope.launch {
            cpuProfiles.status.collect { status ->
                _uiState.update { it.copy(cpuCapsKeptUntilRestart = status.notice == CpuProfileNotice.KEPT_UNTIL_RESTART) }
            }
        }
        viewModelScope.launch { repository.latestStats.filterNotNull().collect(::publishStats) }
    }

    // 🎯 新增：用户点击关闭
    fun onHideWidgetHint() {
        settings.setHideWidgetHint(true)
        _uiState.update { it.copy(hideWidgetHint = true) }
    }

    fun loadExemptApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isExemptAppsLoading = true) }
            try {
                // 修复：拆开 if-else，让编译器能正确识别 exec 方法
                val output = withContext(Dispatchers.IO) {
                    if (settings.privilegedAccess() == PrivilegedAccess.ROOT) {
                        rootShell.exec("dumpsys deviceidle whitelist")
                    } else {
                        shizukuShell.exec("dumpsys deviceidle whitelist")
                    }
                }
                val list = output.lines().map { it.trim() }.filter { it.isNotEmpty() && it.contains(",") }.map { line ->
                    val parts = line.split(",")
                    if (parts.size >= 3) parts[2] else line
                }.distinct()
                _uiState.update { it.copy(exemptAppsList = list, isExemptAppsLoading = false) }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(isExemptAppsLoading = false) }
            }
        }
    }

    fun removeExemptApp(packageName: String) {
        viewModelScope.launch {
            try {
                // 修复：拆开 if-else，让编译器能正确识别 exec 方法
                withContext(Dispatchers.IO) {
                    if (settings.privilegedAccess() == PrivilegedAccess.ROOT) {
                        rootShell.exec("dumpsys deviceidle whitelist -$packageName")
                    } else {
                        shizukuShell.exec("dumpsys deviceidle whitelist -$packageName")
                    }
                }
                loadExemptApps()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun publishStats(stats: SystemStats) {
        if (stats.dataSettingsGeneration != settings.dataSettingsGeneration()) return
        _uiState.update { state ->
            if (stats.readAtElapsedMillis < (state.stats?.readAtElapsedMillis ?: -1L)) state
            else state.copy(stats = stats, lastUpdated = currentTime())
        }
    }

    fun completeOnboarding() { settings.setOnboardingShown(); _uiState.update { it.copy(onboardingCompleted = true) } }
    fun onClassicLookChange(enabled: Boolean) { settings.setClassicLook(enabled); _uiState.update { it.copy(classicLook = enabled) } }
    private var rootRequest = 0
    private var shizukuRequest = 0

    fun onRootModeChange(enabled: Boolean) {
        if (_uiState.value.rootStatus == RootStatus.RESTORING) return
        if (!enabled) {
            if (settings.privilegedAccess() == PrivilegedAccess.ROOT && profileNeedsRoot()) {
                _uiState.update { it.copy(rootStatus = RootStatus.RESTORING) }
                viewModelScope.launch { withContext(NonCancellable) { restoreStockQuietly(); switchRootOff() } }
                return
            }
            switchRootOff(); return
        }
        if (_uiState.value.rootStatus == RootStatus.REQUESTING) return
        _uiState.update { it.copy(rootStatus = RootStatus.REQUESTING) }
        val request = ++rootRequest
        viewModelScope.launch {
            val status = when (rootShell.requestAccess()) {
                RootAccess.GRANTED -> RootStatus.ON
                RootAccess.DENIED -> RootStatus.DENIED
                RootAccess.NO_SU -> RootStatus.NO_SU
            }
            if (request != rootRequest) return@launch
            if (_uiState.value.rootStatus != RootStatus.REQUESTING) { rootShell.close(); return@launch }
            if (status != RootStatus.ON) { _uiState.update { it.copy(rootStatus = status) }; return@launch }
            settings.setPrivilegedAccess(PrivilegedAccess.ROOT)
            shizukuShell.close()
            _uiState.update { it.copy(rootStatus = RootStatus.ON, shizukuStatus = ShizukuStatus.OFF) }
        }
    }

    private fun profileNeedsRoot(): Boolean = cpuProfiles.status.value.let { it.holdsCaps || it.waitingAfterRestart }
    private fun rootStatusNow(): RootStatus = when { settings.privilegedAccess() == PrivilegedAccess.ROOT -> RootStatus.ON; rootShell.lost.value -> RootStatus.DENIED; else -> RootStatus.OFF }
    private suspend fun restoreStockQuietly() { try { cpuProfiles.restoreStockBeforeRootOff() } catch (e: Exception) { e.printStackTrace() } }
    private fun switchRootOff() { if (settings.privilegedAccess() == PrivilegedAccess.ROOT) settings.setPrivilegedAccess(PrivilegedAccess.OFF); rootShell.close(); _uiState.update { it.copy(rootStatus = RootStatus.OFF) } }

    fun onShizukuModeChange(enabled: Boolean) {
        if (!enabled) {
            if (settings.privilegedAccess() == PrivilegedAccess.SHIZUKU) settings.setPrivilegedAccess(PrivilegedAccess.OFF)
            shizukuShell.close(); _uiState.update { it.copy(shizukuStatus = ShizukuStatus.OFF) }; return
        }
        if (_uiState.value.shizukuStatus == ShizukuStatus.REQUESTING) return
        _uiState.update { it.copy(shizukuStatus = ShizukuStatus.REQUESTING) }
        val request = ++shizukuRequest
        viewModelScope.launch {
            val status = when (shizukuShell.requestAccess()) {
                ShizukuAccess.GRANTED -> ShizukuStatus.ON
                ShizukuAccess.DENIED -> ShizukuStatus.DENIED
                ShizukuAccess.NOT_RUNNING -> ShizukuStatus.NOT_RUNNING
            }
            if (request != shizukuRequest) return@launch
            if (_uiState.value.shizukuStatus != ShizukuStatus.REQUESTING) return@launch
            if (status != ShizukuStatus.ON) { _uiState.update { it.copy(shizukuStatus = status) }; return@launch }
            withContext(NonCancellable) {
                val restoring = settings.privilegedAccess() == PrivilegedAccess.ROOT && profileNeedsRoot()
                if (restoring) { _uiState.update { it.copy(rootStatus = RootStatus.RESTORING) }; restoreStockQuietly() }
                if (request != shizukuRequest || _uiState.value.shizukuStatus != ShizukuStatus.REQUESTING) {
                    if (restoring) _uiState.update { it.copy(rootStatus = rootStatusNow()) }; return@withContext
                }
                settings.setPrivilegedAccess(PrivilegedAccess.SHIZUKU)
                rootShell.close()
                _uiState.update { it.copy(shizukuStatus = ShizukuStatus.ON, rootStatus = RootStatus.OFF) }
            }
        }
    }

    private fun onPrivilegedAccess(access: PrivilegedAccess) {
        _uiState.update { state ->
            state.copy(
                rootStatus = when {
                    access == PrivilegedAccess.ROOT -> if (state.rootStatus == RootStatus.RESTORING) RootStatus.RESTORING else RootStatus.ON
                    state.rootStatus == RootStatus.ON || state.rootStatus == RootStatus.RESTORING -> RootStatus.OFF
                    else -> state.rootStatus
                },
                shizukuStatus = when {
                    access == PrivilegedAccess.SHIZUKU -> if (state.shizukuStatus == ShizukuStatus.WAITING) ShizukuStatus.WAITING else ShizukuStatus.ON
                    state.shizukuStatus == ShizukuStatus.ON || state.shizukuStatus == ShizukuStatus.WAITING -> ShizukuStatus.OFF
                    else -> state.shizukuStatus
                },
            )
        }
        if (deviceInfoRequested) loadDeviceInfo()
        if (deviceStateRequested) reloadDeviceState()
    }

    private fun onRootLost() { viewModelScope.launch { cpuProfiles.onRootGone() }; if (_uiState.value.rootStatus != RootStatus.ON) return; rootShell.close(); _uiState.update { it.copy(rootStatus = RootStatus.DENIED) } }
    override fun onCleared() { if (_uiState.value.rootStatus == RootStatus.REQUESTING) rootShell.close(); super.onCleared() }
    private fun onShizukuAlive(alive: Boolean) { _uiState.update { state -> when { state.shizukuStatus == ShizukuStatus.ON && !alive -> state.copy(shizukuStatus = ShizukuStatus.WAITING); state.shizukuStatus == ShizukuStatus.WAITING && alive -> state.copy(shizukuStatus = ShizukuStatus.ON); else -> state } } }

    fun onAlertToggle(alert: HealthAlert, enabled: Boolean) {
        settings.setAlertEnabled(alert, enabled)
        if (!enabled) alertNotifications.cancel(alert)
        _uiState.update { it.copy(enabledAlerts = if (enabled) it.enabledAlerts + alert else it.enabledAlerts - alert) }
    }

    fun onNotificationMetricToggle(metric: QuickMetric, enabled: Boolean) {
        val chosen = settings.notificationMetrics().let { if (enabled) it + metric else it - metric }
        settings.setNotificationMetrics(chosen); monitorRelay.notificationMetricsChanged()
        _uiState.update { it.copy(notificationMetrics = chosen) }
    }

    fun onTileValueIconsChange(enabled: Boolean) { settings.setTileValueIcons(enabled); _uiState.update { it.copy(tileValueIcons = enabled) } }
    private var pendingOverlayEnable = false
    fun onOverlayShownChange(enabled: Boolean, permission: Boolean) {
        if (enabled && !permission) { pendingOverlayEnable = true; _uiState.update { it.copy(overlayShown = false, overlayPermission = false) }; return }
        pendingOverlayEnable = false; setOverlayShown(enabled)
    }
    private fun setOverlayShown(enabled: Boolean) {
        val chosen = overlayMetricsOrDefault(settings.overlayMetrics())
        if (enabled && chosen != settings.overlayMetrics()) settings.setOverlayMetrics(chosen)
        settings.setOverlayShown(enabled); monitorRelay.overlayChanged()
        _uiState.update { it.copy(overlayShown = enabled, overlayMetrics = chosen) }
    }
    fun onOverlayMetricToggle(metric: QuickMetric, enabled: Boolean) {
        val current = overlayMetricsOrDefault(settings.overlayMetrics())
        val chosen = limitOverlaySelection(current, metric, enabled)
        if (chosen == current) return
        settings.setOverlayMetrics(chosen); monitorRelay.overlayChanged()
        _uiState.update { it.copy(overlayMetrics = chosen) }
    }
    fun onOverlayPermissionChecked(granted: Boolean) {
        val pending = pendingOverlayEnable; pendingOverlayEnable = false
        _uiState.update { it.copy(overlayPermission = granted, overlayShown = settings.overlayShown()) }
        if (granted && pending && !settings.overlayShown()) setOverlayShown(true)
    }

    fun refresh() { viewModelScope.launch { refreshInternal() } }
    fun pullRefresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            val startMillis = System.currentTimeMillis()
            try { refreshInternal() } finally { delayForPullIndicator(startMillis); _uiState.update { it.copy(isRefreshing = false) } }
        }
    }

    private var refreshGeneration = 0
    private suspend fun refreshInternal() {
        val generation = ++refreshGeneration
        val stats = repository.getStats()
        publishStats(stats)
        val widgetInstalled = widgetController.pushStats(stats)
        val today = LocalDate.now()
        val periodStart = DataPeriodCalculator.periodStart(settings.dataCounterMode(), settings.cycleStartDay(), today)
        val hasUsageAccess = appUsageRepository.hasUsageAccess()
        val supportsUnlocks = appUsageRepository.supportsUnlockCounting()
        val supportsScreenOn = appUsageRepository.supportsScreenOnTracking()
        if (hasUsageAccess) {
            appUsageRepository.screenTimeByDay(7).forEach { (day, millis) -> usageHistory.recordScreenTime(day, millis) }
            appUsageRepository.unlockCountsByDay(7).forEach { (day, count) -> usageHistory.recordUnlocks(day, count) }
            appUsageRepository.screenOnByDay(7).forEach { (day, millis) -> usageHistory.recordScreenOn(day, millis) }
            appUsageRepository.usageTotalsToday()?.let { totals -> usageHistory.recordTotals(today, totals) }
        }
        storageUsedBytes(stats.usedStorageGb)?.let { usageHistory.recordStorageUsed(today, it, stats.readAtElapsedMillis) }
        usageHistory.purge(today)

        val notificationAccess = notificationStats.isListenerEnabled()
        val comparison = PeriodComparison.windows(settings.dataCounterMode(), settings.cycleStartDay(), today)?.let { w ->
            val oldest = PeriodComparison.oldestRetainedDay(today)
            val screenObserved = usageHistory.screenTimeBetween(oldest, w.previousStart) > 0L
            val unlocksObserved = usageHistory.unlocksBetween(oldest, w.previousStart) > 0
            val notificationsObserved = notificationStats.totalBetween(oldest, w.previousStart) > 0
            PeriodComparisonData(
                screenTimeNowMillis = usageHistory.screenTimeBetween(w.currentStart, w.currentEnd),
                screenTimePrevMillis = if (screenObserved) usageHistory.screenTimeBetween(w.previousStart, w.previousEnd) else null,
                unlocksNow = usageHistory.unlocksBetween(w.currentStart, w.currentEnd),
                unlocksPrev = if (unlocksObserved) usageHistory.unlocksBetween(w.previousStart, w.previousEnd) else null,
                notificationsNow = notificationStats.totalBetween(w.currentStart, w.currentEnd),
                notificationsPrev = if (notificationsObserved) notificationStats.totalBetween(w.previousStart, w.previousEnd) else null,
                daysCompared = w.daysCompared,
            )
        }
        if (generation != refreshGeneration) return
        _uiState.update {
            it.copy(
                isWidgetInstalled = widgetInstalled,
                usageAccessEnabled = hasUsageAccess,
                unlockCountingSupported = supportsUnlocks,
                screenTimeMillis = if (hasUsageAccess) usageHistory.screenTimeBetween(periodStart, today) else -1L,
                screenOnMillis = if (hasUsageAccess && supportsScreenOn) usageHistory.screenOnBetween(periodStart, today) else -1L,
                screenOnTrackingSupported = supportsScreenOn,
                unlockCount = if (hasUsageAccess && supportsUnlocks) usageHistory.unlocksBetween(periodStart, today) else UNAVAILABLE_INT,
                bootCount = usageHistory.bootsBetween(periodStart, today),
                chargeCount = usageHistory.chargesBetween(periodStart, today),
                periodComparison = comparison,
                notificationAccessEnabled = notificationAccess,
                notificationCount = if (notificationAccess) notificationStats.totalBetween(periodStart, today) else UNAVAILABLE_INT,
            )
        }

        val breakdown = repository.dataBreakdown(periodStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        val storage = repository.storageBreakdown()
        if (generation != refreshGeneration) return
        deviceStateRequested = true
        val stateGeneration = ++deviceStateGeneration
        val deviceState = repository.deviceState()
        if (generation != refreshGeneration) return
        _uiState.update {
            it.copy(
                dataBreakdown = breakdown,
                storageBreakdown = storage,
                deviceState = if (stateGeneration == deviceStateGeneration) deviceState else it.deviceState,
            )
        }
    }

    private var deviceStateRequested = false
    private var deviceStateGeneration = 0
    private fun reloadDeviceState() { val generation = ++deviceStateGeneration; viewModelScope.launch { val state = repository.deviceState(); if (generation == deviceStateGeneration) _uiState.update { it.copy(deviceState = state) } } }
    private var deviceInfoRequested = false
    private var deviceInfoGeneration = 0
    fun loadDeviceInfo() { deviceInfoRequested = true; val generation = ++deviceInfoGeneration; viewModelScope.launch { val info = repository.getDeviceInfo(); if (generation == deviceInfoGeneration) _uiState.update { it.copy(deviceInfo = info) } } }
    fun loadWidgetAppearance() { viewModelScope.launch { widgetController.currentOpacity()?.let { saved -> _uiState.update { it.copy(widgetOpacity = saved) } }; widgetController.currentBlackBackground()?.let { saved -> _uiState.update { it.copy(widgetBlackBackground = saved) } } } }
    fun onWidgetBlackBackgroundChange(black: Boolean) { _uiState.update { it.copy(widgetBlackBackground = black) }; viewModelScope.launch { widgetController.setBlackBackground(black) } }
    fun onWidgetOpacityChange(value: Float) { _uiState.update { it.copy(widgetOpacity = value) } }
    fun commitWidgetOpacity() { val opacity = _uiState.value.widgetOpacity; viewModelScope.launch { widgetController.setOpacity(opacity) } }

    fun loadDataCounterSettings() {
        _uiState.update {
            it.copy(
                dataCounterMode = settings.dataCounterMode(),
                cycleStartDay = settings.cycleStartDay(),
                chargeLimitPercent = settings.chargeLimitPercent(),
                dataQuotaGb = settings.dataQuotaGb(),
                chargeLimitPlaySound = settings.chargeLimitPlaySound(),
                chargeLimitVibrate = settings.chargeLimitVibrate(),
            )
        }
    }

    fun onDataCounterModeSelected(mode: DataCounterMode) { settings.setDataCounterMode(mode); _uiState.update { it.copy(dataCounterMode = mode) }; monitorRelay.dataQuotaChanged(); refresh() }
    fun onCycleStartDayChange(day: Int) { _uiState.update { it.copy(cycleStartDay = day.coerceIn(1, 31)) } }
    fun commitCycleStartDay() { settings.setCycleStartDay(_uiState.value.cycleStartDay); monitorRelay.dataQuotaChanged(); refresh() }
    fun onChargeLimitChange(percent: Int) { val value = if (percent <= 0) 0 else percent.coerceIn(CHARGE_LIMIT_MIN, CHARGE_LIMIT_MAX); _uiState.update { it.copy(chargeLimitPercent = value) } }
    fun onCommitChargeLimit() { settings.setChargeLimitPercent(_uiState.value.chargeLimitPercent); monitorRelay.chargeLimitChanged() }
    fun onChargeLimitPlaySoundToggle(enabled: Boolean) { settings.setChargeLimitPlaySound(enabled); _uiState.update { it.copy(chargeLimitPlaySound = enabled) } }
    fun onChargeLimitVibrateToggle(enabled: Boolean) { settings.setChargeLimitVibrate(enabled); _uiState.update { it.copy(chargeLimitVibrate = enabled) } }
    fun onDataQuotaChange(quotaGb: Double) { val value = if (quotaGb <= 0.0) 0.0 else quotaGb.coerceIn(DATA_QUOTA_MIN_GB, DATA_QUOTA_MAX_GB); _uiState.update { it.copy(dataQuotaGb = value) } }
    fun onCommitDataQuota() { settings.setDataQuotaGb(_uiState.value.dataQuotaGb); monitorRelay.dataQuotaChanged(); refresh() }

    private fun currentTime(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}
