package org.jarsi.devicewatch.system

/**
 * Pure rules for the adjustable charge-limit reminder.
 * 只要插上电，达到阈值就必须提醒。
 */
object ChargeLimitLogic {

    data class State(val notifiedThisPlug: Boolean = false)

    data class Decision(val state: State, val notify: Boolean)

    /**
     * [limitPercent] <= 0 disables the reminder. 
     * 只要达到设定电量且插着电，就直接触发通知。
     */
    fun onBatteryChanged(
        state: State,
        limitPercent: Int,
        level: Int,
        isPlugged: Boolean,
    ): Decision {
        if (limitPercent <= 0 || limitPercent >= 100) return Decision(state, notify = false)
        
        // 如果没插电，或者电量还没到，就不通知
        if (!isPlugged || level < limitPercent) {
            return Decision(state, notify = false)
        }
        
        // 如果这次插电已经通知过了，就不再骚扰（拔掉充电器会重置这个状态）
        if (state.notifiedThisPlug) {
            return Decision(state, notify = false)
        }
        
        return Decision(State(notifiedThisPlug = true), notify = true)
    }

    /** Resets the once-per-plug-session latch. */
    fun onPowerDisconnected(state: State): State = state.copy(notifiedThisPlug = false)

    /** A new limit is a new reminder: the latch from the old one no longer applies. */
    fun onLimitChanged(state: State): State = state.copy(notifiedThisPlug = false)
}
