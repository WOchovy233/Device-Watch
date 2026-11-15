package org.jarsi.devicewatch.system

import android.app.Service
import android.app.usage.NetworkStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import org.jarsi.devicewatch.R
import android.os.BatteryManager
import java.util.Locale
import org.jarsi.devicewatch.data.HealthAlert
import org.jarsi.devicewatch.data.AlertStep
import org.jarsi.devicewatch.data.AlertLogic
import org.jarsi.devicewatch.data.storageUsedBytes
import org.jarsi.devicewatch.data.AppSettingsRepository
import org.jarsi.devicewatch.data.AppUsageRepository
import org.jarsi.devicewatch.data.BatteryHistory
import org.jarsi.devicewatch.data.BatterySample
import org.jarsi.devicewatch.data.BatteryStatusReader
import org.jarsi.devicewatch.data.ChargeAnchorLogic
import org.jarsi.devicewatch.data.ChargeAnchorStore
import org.jarsi.devicewatch.data.CpuProfiles
import org.jarsi.devicewatch.data.DataQuotaLogic
import org.jarsi.devicewatch.data.QuotaReadingAction
import org.jarsi.devicewatch.data.QuotaSettingsStamp
import org.jarsi.devicewatch.data.SystemStats
import org.jarsi.devicewatch.data.SystemStatsRepository
import org.jarsi.devicewatch.data.UsageHistory
import org.jarsi.devicewatch.data.PrivilegedAccess
import org.jarsi.devicewatch.data.QuickMetric
import org.jarsi.devicewatch.data.recordTotals
import org.jarsi.devicewatch.overlay.FloatingFigureController
import org.jarsi.devicewatch.quick.QuickMetricFormatter
import org.jarsi.devicewatch.quick.QuickStatsSource
import org.jarsi.devicewatch.quick.quickMetricLabels
import org.jarsi.devicewatch.presentation.ui.durationText
import org.jarsi.devicewatch.widget.WidgetStateUpdater
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@AndroidEntryPoint
class SystemMonitorService : Service() {

    @Inject lateinit var repository: SystemStatsRepository
    @Inject lateinit var appUsageRepository: AppUsageRepository
    @Inject lateinit var usageHistory: UsageHistory
    @Inject lateinit var chargeAnchorStore: ChargeAnchorStore
    @Inject lateinit var batteryHistory: BatteryHistory
    @Inject lateinit var appSettings: AppSettingsRepository
    @Inject lateinit var alertNotifications: AlertNotifications
    private val alertController by lazy { HealthAlertController(appSettings, alertNotifications) }
    @Inject lateinit var batteryStatus: BatteryStatusReader
    @Inject lateinit var chargeStartScreensaver: ChargeStartScreensaver
    @Inject lateinit var cpuProfiles: CpuProfiles
    @Inject lateinit var quickStats: QuickStatsSource

    private val notifications by lazy { MonitorNotifications(this) }
    private val live by lazy {
        LiveNotificationController(
            chosen = { appSettings.notificationMetrics() },
            read = ::liveText,
            postLive = { text -> notifications.post(NOTIFICATION_ID, notifications.live(text)) },
            postQuiet = { notifications.post(NOTIFICATION_ID, notifications.quiet()) },
            removeLive = ::replaceWithSilentForeground,
            isShowing = { notifications.isShowing(NOTIFICATION_ID) },
        )
    }

    private val overlay by lazy {
        FloatingFigureController(
            context = this,
            appSettings = appSettings,
            quickStats = quickStats,
            mainHandler = Handler(Looper.getMainLooper()),
            isScreenOn = { isScreenOn },
            isDreaming = { MonitorDreamService.isDreaming },
        )
    }

    private var lastUsageRefreshMs = 0L

    @Volatile private var lastBatteryLevel = -1

    private var chargeLimitState = ChargeLimitLogic.State()

    @Volatile private var usageCallback: NetworkStatsManager.UsageCallback? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)
    private var batteryReceiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var configurationReceiver: BroadcastReceiver? = null
    private var updateJob: Job? = null
    @Volatile private var isScreenOn = true

    override fun onCreate() {
        super.onCreate()
        startNotification()
        registerBatteryTracker()
        registerScreenTracker()
        registerConfigurationTracker()
        isScreenOn = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        if (isScreenOn) startUpdateLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CHARGE_LIMIT_CHANGED -> onChargeLimitChanged()
            ACTION_DATA_QUOTA_CHANGED -> onDataQuotaChanged()
            ACTION_NOTIFICATION_METRICS_CHANGED -> onNotificationMetricsChanged()
            ACTION_LIVE_NOTIFICATION_DISMISSED -> live.onDismissed()
            ACTION_OVERLAY_CHANGED -> onOverlayChanged()
        }
        return START_STICKY
    }

    private fun onOverlayChanged() {
        serviceScope.launch {
            try {
                overlay.onSettingsChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
            updateWidgetStats(applicationContext)
        }
    }

    private fun onDataQuotaChanged() {
        onDataSettingsChanged(applicationContext) {
            serviceScope.launch {
                val stats = updateWidgetStats(applicationContext)
                if (stats != null) maybeNotifyDataQuota(stats)
            }
        }
    }

    private fun onNotificationMetricsChanged() {
        serviceScope.launch {
            if (live.onSelectionChanged()) updateWidgetStats(applicationContext)
        }
    }

    private fun onChargeLimitChanged() {
        chargeLimitState = ChargeLimitLogic.onLimitChanged(chargeLimitState)
        ChargeLimitNotifier.cancel(applicationContext)
        val level = batteryStatus.currentLevel() ?: return
        evaluateChargeLimit(level, plugged = batteryStatus.isCharging())
    }

    private fun evaluateChargeLimit(level: Int, plugged: Boolean) {
        val decision = ChargeLimitLogic.onBatteryChanged(
            chargeLimitState,
            limitPercent = appSettings.chargeLimitPercent(),
            level = level,
            isPlugged = plugged,
        )
        if (!decision.notify) return
        
        val playSound = appSettings.chargeLimitPlaySound()
        val vibrate = appSettings.chargeLimitVibrate()
        
        if (ChargeLimitNotifier.show(applicationContext, level.coerceIn(0, 100), playSound, vibrate)) {
            chargeLimitState = decision.state
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disarmUsageCallback()
        batteryReceiver?.let {
            unregisterReceiver(it)
        }
        screenReceiver?.let {
            unregisterReceiver(it)
        }
        configurationReceiver?.let {
            unregisterReceiver(it)
        }
        stopUpdateLoop()
        serviceJob.cancel()
        overlay.destroy()
    }

    private fun registerScreenTracker() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOn = true
                        live.onScreenOn()
                        overlay.onScreenChanged(true)
                        startUpdateLoop()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOn = false
                        stopUpdateLoop()
                        overlay.onScreenChanged(false)
                    }
                    Intent.ACTION_DREAMING_STARTED -> overlay.onDreamingChanged(true)
                    Intent.ACTION_DREAMING_STOPPED -> {
                        overlay.onDreamingChanged(false)
                        serviceScope.launch { updateWidgetStats(applicationContext) }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun registerConfigurationTracker() {
        configurationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
                    overlay.onConfigurationChanged()
                    serviceScope.launch {
                        updateWidgetStats(context)
                    }
                }
            }
        }
        registerReceiver(configurationReceiver, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED))
    }

    private fun startUpdateLoop() {
        if (updateJob?.isActive == true) return
        
        updateJob = serviceScope.launch {
            while (isActive && isScreenOn) {
                val stats = updateWidgetStats(this@SystemMonitorService)
                maybeRefreshUsage(stats)
                enforceCpuProfile()
                delay(5000)
            }
        }
    }

    private suspend fun enforceCpuProfile() {
        try {
            cpuProfiles.enforce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun maybeRefreshUsage(stats: SystemStats?) {
        val now = SystemClock.elapsedRealtime()
        if (lastUsageRefreshMs != 0L && now - lastUsageRefreshMs < USAGE_REFRESH_INTERVAL_MS) return
        lastUsageRefreshMs = now
        if (stats != null) {
            maybeNotifyDataQuota(stats)
            storageUsedBytes(stats.usedStorageGb)?.let {
                usageHistory.recordStorageUsed(LocalDate.now(), it, stats.readAtElapsedMillis)
            }
            evaluateStorageAlert(stats)
        }
        try {
            val totals = appUsageRepository.usageTotalsToday() ?: return
            usageHistory.recordTotals(LocalDate.now(), totals)
            WidgetStateUpdater.updateScreenTimeText(
                applicationContext,
                durationText(applicationContext, totals.screenTimeMillis)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopUpdateLoop() {
        updateJob?.cancel()
        updateJob = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startNotification() {
        notifications.ensureChannels()
        val notification = if (appSettings.notificationMetrics().isEmpty()) {
            notifications.quiet()
        } else {
            notifications.live(null)
        }
        startForegroundWith(notification)
    }

    private fun startForegroundWith(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun replaceWithSilentForeground() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            startForegroundWith(notifications.quiet())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun registerBatteryTracker() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = batteryPercent(intent)
                        if (level >= 0) lastBatteryLevel = level
                        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        chargeAnchorStore.save(
                            ChargeAnchorLogic.onBatteryChanged(
                                chargeAnchorStore.load(),
                                level = level.coerceAtLeast(0),
                                isPlugged = plugged,
                                isFull = status == BatteryManager.BATTERY_STATUS_FULL || level >= 100,
                                nowMillis = System.currentTimeMillis(),
                            )
                        )
                        if (level >= 0) {
                            val sample = BatterySample(
                                timeMillis = System.currentTimeMillis(),
                                level = level.coerceIn(0, 100),
                                charging = plugged,
                                temperatureDeciC = intent
                                    .getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                                    .takeIf { it != Int.MIN_VALUE },
                            )
                            serviceScope.launch {
                                recordBatterySample(sample)
                                evaluateBatteryAlerts(sample)
                            }
                        }
                        evaluateChargeLimit(level, plugged)
                    }
                    Intent.ACTION_POWER_CONNECTED -> {
                        usageHistory.incrementCharge(LocalDate.now())
                        chargeAnchorStore.save(
                            ChargeAnchorLogic.onPowerConnected(chargeAnchorStore.load())
                        )
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        chargeLimitState = ChargeLimitLogic.onPowerDisconnected(chargeLimitState)
                        ChargeLimitNotifier.cancel(applicationContext)
                        chargeAnchorStore.save(
                            ChargeAnchorLogic.onPowerDisconnected(
                                chargeAnchorStore.load(),
                                level = lastBatteryLevel.coerceAtLeast(0),
                                nowMillis = System.currentTimeMillis(),
                            )
                        )
                    }
                }
                serviceScope.launch {
                    val stats = updateWidgetStats(context)
                    if (stats != null) maybeNotifyDataQuota(stats)
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, filter)
    }

    private suspend fun updateWidgetStats(context: Context): SystemStats? {
        return try {
            val stats = repository.getStats()
            WidgetStateUpdater.updateAll(context.applicationContext, stats)
            refreshLiveNotification(stats)
            refreshOverlay(stats)
            stats
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun refreshLiveNotification(stats: SystemStats) {
        try {
            live.refresh(stats)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun refreshOverlay(stats: SystemStats) {
        try {
            overlay.refresh(stats)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun liveText(chosen: Set<QuickMetric>, stats: SystemStats): LiveNotificationText? {
        // 🎯 核心修改：这里不再调 quickStats.dataToday()，而是用周期数据直接拼装
        val dataToday = if (QuickMetric.DATA_TODAY in chosen) {
            val used = stats.mobileDataUsedGb
            val total = stats.mobileDataTotalGb
            if (total > 0.0) {
                val remain = (total - used).coerceAtLeast(0.0)
                // 直接显示 "已用70.0G/剩50.0G"
                String.format(Locale.getDefault(), "已用%.1fG/剩%.1fG", used, remain)
            } else {
                // 如果没设置套餐上限，就显示已用
                String.format(Locale.getDefault(), "%.1fG", used)
            }
        } else null

        val labels = quickMetricLabels(applicationContext)
        val extendedAccess = appSettings.privilegedAccess() != PrivilegedAccess.OFF
        val readings = QuickMetric.entries.filter { it in chosen }
            .map { QuickMetricFormatter.read(it, stats, dataToday, extendedAccess, labels) }
        return liveNotificationText(readings, labels)
    }

    @Synchronized
    private fun maybeNotifyDataQuota(stats: SystemStats) {
        try {
            val quotaGb = stats.mobileDataTotalGb
            val action = DataQuotaLogic.classifyReading(
                reading = QuotaSettingsStamp(quotaGb, stats.dataSettingsGeneration),
                current = QuotaSettingsStamp(appSettings.dataQuotaGb(), appSettings.dataSettingsGeneration()),
            )
            when (action) {
                QuotaReadingAction.RELEASE_WATCH -> {
                    disarmUsageCallback()
                    return
                }
                QuotaReadingAction.IGNORE -> return
                QuotaReadingAction.CHECK -> Unit
            }
            val periodStartEpochDay = stats.dataPeriodStartEpochDay
            val pending = DataQuotaLogic.pendingThresholds(
                quotaGb = quotaGb,
                usedGb = stats.mobileDataUsedGb,
                notified80 = appSettings.dataQuotaNotified(periodStartEpochDay, quotaGb, DataQuotaLogic.WARNING_PERCENT),
                notified100 = appSettings.dataQuotaNotified(periodStartEpochDay, quotaGb, DataQuotaLogic.REACHED_PERCENT),
            )
            pending.forEach { threshold ->
                val shown = DataQuotaNotifier.show(
                    applicationContext, threshold, stats.mobileDataUsedGb, quotaGb
                )
                if (shown) appSettings.setDataQuotaNotified(periodStartEpochDay, quotaGb, threshold)
            }
            armUsageCallback(
                quotaGb = quotaGb,
                usedGb = stats.mobileDataUsedGb,
                notified80 = appSettings.dataQuotaNotified(periodStartEpochDay, quotaGb, DataQuotaLogic.WARNING_PERCENT),
                notified100 = appSettings.dataQuotaNotified(periodStartEpochDay, quotaGb, DataQuotaLogic.REACHED_PERCENT),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun armUsageCallback(
        quotaGb: Double,
        usedGb: Double,
        notified80: Boolean,
        notified100: Boolean,
    ) {
        disarmUsageCallback()
        val bytes = DataQuotaLogic.bytesToNextCheck(quotaGb, usedGb, notified80, notified100)
            ?: return
        val manager = getSystemService(NetworkStatsManager::class.java) ?: return
        val callback = object : NetworkStatsManager.UsageCallback() {
            override fun onThresholdReached(networkType: Int, subscriberId: String?) {
                serviceScope.launch {
                    val fresh = updateWidgetStats(applicationContext)
                    if (fresh != null) maybeNotifyDataQuota(fresh)
                }
            }
        }
        try {
            @Suppress("DEPRECATION")
            manager.registerUsageCallback(
                ConnectivityManager.TYPE_MOBILE,
                null,
                bytes,
                callback,
                Handler(Looper.getMainLooper()),
            )
            usageCallback = callback
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun disarmUsageCallback() {
        val callback = usageCallback ?: return
        usageCallback = null
        try {
            getSystemService(NetworkStatsManager::class.java)?.unregisterUsageCallback(callback)
        } catch (_: Exception) {
        }
    }

    private fun evaluateBatteryAlerts(sample: BatterySample) {
        try {
            applyAlertStep(HealthAlert.HOT_BATTERY, { latched -> AlertLogic.hotBattery(sample.temperatureDeciC, latched) }) {
                val temperature = String.format(Locale.getDefault(), "%.1f °C", (sample.temperatureDeciC ?: 0) / 10.0)
                HealthAlertNotifier.show(
                    applicationContext,
                    HealthAlert.HOT_BATTERY,
                    getString(R.string.alert_hot_title, temperature),
                    getString(R.string.alert_hot_text),
                )
            }
            if (!appSettings.alertEnabled(HealthAlert.FAST_DRAIN)) return
            val drain = AlertLogic.drainPercentPerHour(
                batteryHistory.samplesSince(sample.timeMillis - DRAIN_LOOKBACK_MS),
                sample.timeMillis,
            )
            applyAlertStep(HealthAlert.FAST_DRAIN, { latched -> AlertLogic.fastDrain(drain, sample.charging, latched) }) {
                HealthAlertNotifier.show(
                    applicationContext,
                    HealthAlert.FAST_DRAIN,
                    getString(R.string.alert_drain_title, drain ?: 0),
                    getString(R.string.alert_drain_text),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun evaluateStorageAlert(stats: SystemStats) {
        val free = AlertLogic.freePercent(stats.totalStorageGb, stats.usedStorageGb)
        applyAlertStep(HealthAlert.LOW_STORAGE, { latched -> AlertLogic.lowStorage(free, latched) }) {
            HealthAlertNotifier.show(
                applicationContext,
                HealthAlert.LOW_STORAGE,
                getString(R.string.alert_storage_title, free ?: 0),
                getString(
                    R.string.alert_storage_text,
                    String.format(Locale.getDefault(), "%.1f GB", stats.totalStorageGb - stats.usedStorageGb),
                ),
            )
        }
    }

    private fun applyAlertStep(alert: HealthAlert, step: (latched: Boolean) -> AlertStep, post: () -> Boolean) =
        alertController.apply(alert, step, post)

    private fun recordBatterySample(sample: BatterySample) {
        try {
            batteryHistory.record(sample)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun batteryPercent(intent: Intent): Int {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return -1
        return level * 100 / scale
    }

    companion object {
        private const val DRAIN_LOOKBACK_MS = 61L * 60 * 1000
        private const val NOTIFICATION_ID = 1001
        private const val USAGE_REFRESH_INTERVAL_MS = 60_000L

        const val ACTION_CHARGE_LIMIT_CHANGED = "org.jarsi.devicewatch.action.CHARGE_LIMIT_CHANGED"
        const val ACTION_DATA_QUOTA_CHANGED = "org.jarsi.devicewatch.action.DATA_QUOTA_CHANGED"
        const val ACTION_NOTIFICATION_METRICS_CHANGED = "org.jarsi.devicewatch.action.NOTIFICATION_METRICS_CHANGED"
        const val ACTION_OVERLAY_CHANGED = "org.jarsi.devicewatch.action.OVERLAY_CHANGED"
        const val ACTION_LIVE_NOTIFICATION_DISMISSED = "org.jarsi.devicewatch.action.LIVE_NOTIFICATION_DISMISSED"
    }
}
