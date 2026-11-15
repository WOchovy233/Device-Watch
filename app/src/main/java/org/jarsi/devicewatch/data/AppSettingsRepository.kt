package org.jarsi.devicewatch.data

import kotlinx.coroutines.flow.StateFlow
import org.jarsi.devicewatch.overlay.OverlayPosition

const val CHARGE_LIMIT_MIN = 50
const val CHARGE_LIMIT_MAX = 95
const val DEFAULT_CHARGE_LIMIT_PERCENT = 80
const val DATA_QUOTA_MIN_GB = 1.0
const val DATA_QUOTA_MAX_GB = 500.0
const val DATA_QUOTA_SLIDER_MAX_GB = 100.0
const val DEFAULT_DATA_QUOTA_GB = 20.0

interface AppSettingsRepository {
    fun dataCounterMode(): DataCounterMode
    fun setDataCounterMode(mode: DataCounterMode)
    fun cycleStartDay(): Int
    fun setCycleStartDay(day: Int)
    fun chargeLimitPercent(): Int
    fun setChargeLimitPercent(value: Int)
    fun chargeLimitPlaySound(): Boolean
    fun setChargeLimitPlaySound(enabled: Boolean)
    fun chargeLimitVibrate(): Boolean
    fun setChargeLimitVibrate(enabled: Boolean)
    
    // 🎯 新增：主页小组件提示条关闭状态
    fun hideWidgetHint(): Boolean
    fun setHideWidgetHint(hidden: Boolean)

    fun dataQuotaGb(): Double
    fun setDataQuotaGb(value: Double)
    fun dataQuotaNotified(periodStartEpochDay: Long, quotaGb: Double, threshold: Int): Boolean
    fun setDataQuotaNotified(periodStartEpochDay: Long, quotaGb: Double, threshold: Int)
    fun dataSettingsGeneration(): Long
    fun appsOldestFirst(): Boolean
    fun setAppsOldestFirst(oldestFirst: Boolean)
    fun onboardingShown(): Boolean
    fun setOnboardingShown()
    fun classicLook(): Boolean
    fun setClassicLook(enabled: Boolean)
    fun privilegedAccess(): PrivilegedAccess
    fun setPrivilegedAccess(access: PrivilegedAccess)
    val privilegedAccessFlow: StateFlow<PrivilegedAccess>
    fun alertEnabled(alert: HealthAlert): Boolean
    fun setAlertEnabled(alert: HealthAlert, enabled: Boolean)
    fun alertLatched(alert: HealthAlert): Boolean
    fun alertGeneration(alert: HealthAlert): Long
    fun latchAlert(alert: HealthAlert, generation: Long): Boolean
    fun unlatchAlert(alert: HealthAlert)
    fun notificationMetrics(): Set<QuickMetric>
    fun setNotificationMetrics(metrics: Set<QuickMetric>)
    fun tileValueIcons(): Boolean
    fun setTileValueIcons(enabled: Boolean)
    fun overlayShown(): Boolean
    fun setOverlayShown(shown: Boolean)
    val overlayShownFlow: StateFlow<Boolean>
    fun overlayMetrics(): Set<QuickMetric>
    fun setOverlayMetrics(metrics: Set<QuickMetric>)
    fun overlayPosition(landscape: Boolean): OverlayPosition?
    fun setOverlayPosition(landscape: Boolean, position: OverlayPosition)
}
