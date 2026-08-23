package com.sanhaengii.app

enum class EmergencyPhase { IDLE, COUNTDOWN, SENDING, SUCCESS, FAILED }
enum class EmergencySource { LOCAL_SENSOR, MANUAL_SOS }
enum class EmergencyTrigger(val apiValue: String) {
    USER_CONFIRM("user_confirm"), AUTO_TIMEOUT("auto_timeout"), MANUAL_LONG_PRESS("manual_watch")
}

data class EmergencyAlert(
    val anomalyType: AnomalyType,
    val message: String,
    val source: EmergencySource,
    val requestId: String? = null,
)

data class EmergencyState(
    val phase: EmergencyPhase = EmergencyPhase.IDLE,
    val alert: EmergencyAlert? = null,
    val countdownSeconds: Int? = null,
    val trigger: EmergencyTrigger? = null,
    val failureMessage: String? = null,
)

class EmergencyStateMachine(private val defaultCountdownSeconds: Int = 30) {
    var state: EmergencyState = EmergencyState()
        private set

    fun startAlert(alert: EmergencyAlert, countdownSeconds: Int = defaultCountdownSeconds): Boolean {
        if (state.phase != EmergencyPhase.IDLE) return false
        state = EmergencyState(EmergencyPhase.COUNTDOWN, alert, countdownSeconds.coerceAtLeast(0))
        return true
    }

    fun tickCountdown(): Boolean {
        if (state.phase != EmergencyPhase.COUNTDOWN) return false
        val current = state.countdownSeconds ?: return false
        if (current <= 0) return false
        val next = current - 1
        state = state.copy(countdownSeconds = next)
        return next == 0
    }

    fun beginSending(trigger: EmergencyTrigger): EmergencyAlert? {
        if (state.phase != EmergencyPhase.COUNTDOWN && state.phase != EmergencyPhase.FAILED) return null
        val alert = state.alert ?: return null
        state = state.copy(phase = EmergencyPhase.SENDING, countdownSeconds = null, trigger = trigger, failureMessage = null)
        return alert
    }

    fun markSucceeded(): Boolean {
        if (state.phase != EmergencyPhase.SENDING) return false
        state = state.copy(phase = EmergencyPhase.SUCCESS, failureMessage = null)
        return true
    }

    fun markFailed(message: String? = null): Boolean {
        if (state.phase != EmergencyPhase.SENDING) return false
        state = state.copy(phase = EmergencyPhase.FAILED, failureMessage = message)
        return true
    }

    fun applyRemoteStatus(value: String): Boolean {
        if (state.phase != EmergencyPhase.SENDING) return false
        return when (value) {
            "sending", "queued" -> true
            "success" -> markSucceeded()
            "failed" -> markFailed("모바일에서 구조 요청을 처리하지 못했습니다")
            else -> false
        }
    }

    fun cancel(): EmergencyAlert? {
        if (state.phase != EmergencyPhase.COUNTDOWN && state.phase != EmergencyPhase.FAILED) return null
        val alert = state.alert
        state = EmergencyState()
        return alert
    }

    fun reset() { state = EmergencyState() }
}
