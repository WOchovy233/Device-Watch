package org.jarsi.devicewatch.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jarsi.devicewatch.overlay.OverlayPosition
import org.jarsi.devicewatch.overlay.overlayPositionOf
import org.jarsi.devicewatch.overlay.overlayPositionToStored
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppSettingsRepository {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val alertState = context.getSharedPreferences(ALERT_STATE_PREFS_NAME, Context.MODE_PRIVATE)

    init {
        val legacyLatches = prefs.all.keys.filter {
            it.startsWith("$KEY_ALERT_LATCHED_PREFIX:") ||
                it.startsWith("$KEY_DATA_QUOTA_NOTIFIED_PREFIX:")
        }
        if (legacyLatches.isNotEmpty()) {
            prefs.edit().apply { legacyLatches.forEach(::remove) }.apply()
        }
    }

    override fun dataCounterMode(): DataCounterMode {
        val stored = prefs.getString(KEY_DATA_COUNTER_MODE, null) ?: return DataCounterMode.DAY
        return try { DataCounterMode.valueOf(stored) } catch (_: IllegalArgumentException) { DataCounterMode.DAY }
    }

    override fun setDataCounterMode(mode: DataCounterMode) {
        prefs.edit().putString(KEY_DATA_COUNTER_MODE, mode.name).nextDataSettingsGeneration().apply()
    }

    override fun cycleStartDay(): Int = prefs.getInt(KEY_CYCLE_START_DAY, DEFAULT_CYCLE_START_DAY).coerceIn(1, 31)

    override fun setCycleStartDay(day: Int) {
        prefs.edit().putInt(KEY_CYCLE_START_DAY, day.coerceIn(1, 31)).nextDataSettingsGeneration().apply()
    }

    override fun chargeLimitPercent(): Int = coerceChargeLimit(prefs.getInt(KEY_CHARGE_LIMIT_PERCENT, 0))

    override fun setChargeLimitPercent(value: Int) {
        prefs.edit().putInt(KEY_CHARGE_LIMIT_PERCENT, coerceChargeLimit(value)).apply()
    }

    override fun chargeLimitPlaySound(): Boolean = prefs.getBoolean(KEY_CHARGE_LIMIT_PLAY_SOUND, true)
    override fun setChargeLimitPlaySound(enabled: Boolean) { prefs.edit().putBoolean(KEY_CHARGE_LIMIT_PLAY_SOUND, enabled).apply() }
    override fun chargeLimitVibrate(): Boolean = prefs.getBoolean(KEY_CHARGE_LIMIT_VIBRATE, true)
    override fun setChargeLimitVibrate(enabled: Boolean) { prefs.edit().putBoolean(KEY_CHARGE_LIMIT_VIBRATE, enabled).apply() }
    
    // 🎯 实现
    override fun hideWidgetHint(): Boolean = prefs.getBoolean(KEY_HIDE_WIDGET_HINT, false)
    override fun setHideWidgetHint(hidden: Boolean) { prefs.edit().putBoolean(KEY_HIDE_WIDGET_HINT, hidden).apply() }

    private fun coerceChargeLimit(value: Int): Int {
        if (value <= 0) return 0
        val bounded = value.coerceIn(CHARGE_LIMIT_MIN, CHARGE_LIMIT_MAX)
        return ((bounded + 2) / 5) * 5
    }

    override fun dataQuotaGb(): Double = coerceDataQuota(prefs.getFloat(KEY_DATA_QUOTA_GB, 0f).toDouble())

    @Synchronized
    override fun setDataQuotaGb(value: Double) {
        val coerced = coerceDataQuota(value)
        val editor = prefs.edit().putFloat(KEY_DATA_QUOTA_GB, coerced.toFloat()).nextDataSettingsGeneration()
        if (coerced != dataQuotaGb()) {
            val latchEditor = alertState.edit()
            alertState.all.keys.filter { it.startsWith("$KEY_DATA_QUOTA_NOTIFIED_PREFIX:") }.forEach(latchEditor::remove)
            latchEditor.apply()
        }
        editor.apply()
    }

    private fun coerceDataQuota(value: Double): Double = if (value <= 0.0) 0.0 else value.coerceIn(DATA_QUOTA_MIN_GB, DATA_QUOTA_MAX_GB)

    @Synchronized
    override fun dataQuotaNotified(periodStartEpochDay: Long, quotaGb: Double, threshold: Int): Boolean = alertState.getBoolean(quotaNotifiedKey(periodStartEpochDay, quotaGb, threshold), false)

    @Synchronized
    override fun setDataQuotaNotified(periodStartEpochDay: Long, quotaGb: Double, threshold: Int) {
        val currentPrefix = quotaNotifiedPrefix(periodStartEpochDay, quotaGb)
        val stale = alertState.all.keys.filter { it.startsWith("$KEY_DATA_QUOTA_NOTIFIED_PREFIX:") && !it.startsWith(currentPrefix) }
        val editor = alertState.edit()
        stale.forEach { editor.remove(it) }
        editor.putBoolean(quotaNotifiedKey(periodStartEpochDay, quotaGb, threshold), true).apply()
    }

    private fun quotaNotifiedPrefix(periodStartEpochDay: Long, quotaGb: Double): String = "$KEY_DATA_QUOTA_NOTIFIED_PREFIX:$periodStartEpochDay:$quotaGb:"
    private fun quotaNotifiedKey(periodStartEpochDay: Long, quotaGb: Double, threshold: Int): String = quotaNotifiedPrefix(periodStartEpochDay, quotaGb) + threshold

    override fun dataSettingsGeneration(): Long = prefs.getLong(KEY_DATA_SETTINGS_GENERATION, 0L)

    private fun SharedPreferences.Editor.nextDataSettingsGeneration(): SharedPreferences.Editor = putLong(KEY_DATA_SETTINGS_GENERATION, dataSettingsGeneration() + 1)

    override fun appsOldestFirst(): Boolean = prefs.getBoolean(KEY_APPS_OLDEST_FIRST, true)
    override fun setAppsOldestFirst(oldestFirst: Boolean) { prefs.edit().putBoolean(KEY_APPS_OLDEST_FIRST, oldestFirst).apply() }

    override fun onboardingShown(): Boolean = DeviceLocalFlags.prefs(context).getBoolean(KEY_ONBOARDING_SHOWN, false)
    override fun setOnboardingShown() { DeviceLocalFlags.prefs(context).edit().putBoolean(KEY_ONBOARDING_SHOWN, true).apply() }

    override fun classicLook(): Boolean = prefs.getBoolean(KEY_CLASSIC_LOOK, false)
    override fun setClassicLook(enabled: Boolean) { prefs.edit().putBoolean(KEY_CLASSIC_LOOK, enabled).apply() }

    override fun privilegedAccess(): PrivilegedAccess {
        val stored = DeviceLocalFlags.prefs(context).getString(KEY_PRIVILEGED_ACCESS, null)
        return PrivilegedAccess.entries.firstOrNull { it.name == stored } ?: PrivilegedAccess.OFF
    }

    private val access = MutableStateFlow(privilegedAccess())
    private val accessLock = Any()
    override val privilegedAccessFlow: StateFlow<PrivilegedAccess> = access.asStateFlow()

    override fun setPrivilegedAccess(access: PrivilegedAccess) {
        synchronized(accessLock) {
            DeviceLocalFlags.prefs(context).edit().putString(KEY_PRIVILEGED_ACCESS, access.name).apply()
            this.access.value = access
        }
    }

    override fun alertEnabled(alert: HealthAlert): Boolean = prefs.getBoolean(alertKey(KEY_ALERT_ENABLED_PREFIX, alert), false)

    @Synchronized
    override fun setAlertEnabled(alert: HealthAlert, enabled: Boolean) {
        val editor = prefs.edit()
            .putBoolean(alertKey(KEY_ALERT_ENABLED_PREFIX, alert), enabled)
            .putLong(alertKey(KEY_ALERT_GENERATION_PREFIX, alert), alertGeneration(alert) + 1)
        if (!enabled) alertState.edit().remove(alertKey(KEY_ALERT_LATCHED_PREFIX, alert)).apply()
        editor.apply()
    }

    override fun alertLatched(alert: HealthAlert): Boolean = alertState.getBoolean(alertKey(KEY_ALERT_LATCHED_PREFIX, alert), false)
    override fun alertGeneration(alert: HealthAlert): Long = prefs.getLong(alertKey(KEY_ALERT_GENERATION_PREFIX, alert), 0L)

    @Synchronized
    override fun latchAlert(alert: HealthAlert, generation: Long): Boolean {
        if (!alertEnabled(alert) || alertGeneration(alert) != generation) return false
        alertState.edit().putBoolean(alertKey(KEY_ALERT_LATCHED_PREFIX, alert), true).apply()
        return true
    }

    @Synchronized
    override fun unlatchAlert(alert: HealthAlert) { alertState.edit().remove(alertKey(KEY_ALERT_LATCHED_PREFIX, alert)).apply() }

    override fun notificationMetrics(): Set<QuickMetric> = quickMetricsOf(prefs.getString(KEY_NOTIFICATION_METRICS, null))
    override fun setNotificationMetrics(metrics: Set<QuickMetric>) { prefs.edit().putString(KEY_NOTIFICATION_METRICS, quickMetricsToStored(metrics)).apply() }

    override fun tileValueIcons(): Boolean = prefs.getBoolean(KEY_TILE_VALUE_ICONS, true)
    override fun setTileValueIcons(enabled: Boolean) { prefs.edit().putBoolean(KEY_TILE_VALUE_ICONS, enabled).apply() }

    override fun overlayShown(): Boolean = prefs.getBoolean(KEY_OVERLAY_SHOWN, false)
    override fun setOverlayShown(shown: Boolean) { prefs.edit().putBoolean(KEY_OVERLAY_SHOWN, shown).apply(); overlayShownState.value = shown }

    private val overlayShownState = MutableStateFlow(overlayShown())
    override val overlayShownFlow: StateFlow<Boolean> = overlayShownState.asStateFlow()

    override fun overlayMetrics(): Set<QuickMetric> = quickMetricsOf(prefs.getString(KEY_OVERLAY_METRICS, null))
    override fun setOverlayMetrics(metrics: Set<QuickMetric>) { prefs.edit().putString(KEY_OVERLAY_METRICS, quickMetricsToStored(metrics)).apply() }

    override fun overlayPosition(landscape: Boolean): OverlayPosition? = overlayPositionOf(prefs.getString(overlayPositionKey(landscape), null))
    override fun setOverlayPosition(landscape: Boolean, position: OverlayPosition) { prefs.edit().putString(overlayPositionKey(landscape), overlayPositionToStored(position)).apply() }

    private fun overlayPositionKey(landscape: Boolean): String = if (landscape) KEY_OVERLAY_POSITION_LANDSCAPE else KEY_OVERLAY_POSITION_PORTRAIT
    private fun alertKey(prefix: String, alert: HealthAlert): String = "$prefix:${alert.name.lowercase()}"

    companion object {
        const val PREFS_NAME = "app_settings"
        private const val ALERT_STATE_PREFS_NAME = "alert_delivery"
        const val KEY_DATA_COUNTER_MODE = "data_counter_mode"
        const val KEY_CYCLE_START_DAY = "cycle_start_day"
        const val KEY_APPS_OLDEST_FIRST = "apps_oldest_first"
        const val KEY_CHARGE_LIMIT_PERCENT = "charge_limit_percent"
        const val KEY_CHARGE_LIMIT_PLAY_SOUND = "charge_limit_play_sound"
        const val KEY_CHARGE_LIMIT_VIBRATE = "charge_limit_vibrate"
        const val KEY_HIDE_WIDGET_HINT = "hide_widget_hint" // 🎯 新增 Key
        const val KEY_DATA_QUOTA_GB = "data_quota_gb"
        const val KEY_DATA_QUOTA_NOTIFIED_PREFIX = "data_quota_notified"
        const val KEY_DATA_SETTINGS_GENERATION = "data_settings_generation"
        const val KEY_ONBOARDING_SHOWN = "onboarding_shown"
        const val KEY_RUNTIME_PERMISSIONS_REQUESTED = "runtime_permissions_requested"
        const val KEY_CLASSIC_LOOK = "classic_look"
        const val KEY_PRIVILEGED_ACCESS = "privileged_access"
        const val KEY_ALERT_ENABLED_PREFIX = "alert_enabled"
        const val KEY_ALERT_LATCHED_PREFIX = "alert_latched"
        const val KEY_ALERT_GENERATION_PREFIX = "alert_generation"
        const val KEY_NOTIFICATION_METRICS = "notification_metrics"
        const val KEY_TILE_VALUE_ICONS = "tile_value_icons"
        const val KEY_OVERLAY_SHOWN = "overlay_shown"
        const val KEY_OVERLAY_METRICS = "overlay_metrics"
        const val KEY_OVERLAY_POSITION_PORTRAIT = "overlay_position_portrait"
        const val KEY_OVERLAY_POSITION_LANDSCAPE = "overlay_position_landscape"
        const val DEFAULT_CYCLE_START_DAY = 1
    }
}
