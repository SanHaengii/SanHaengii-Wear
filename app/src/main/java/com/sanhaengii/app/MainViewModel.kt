package com.sanhaengii.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    private val emergencyMachine = EmergencyStateMachine()
    var bpm by mutableIntStateOf(0)
    var eta by mutableStateOf("-")
    var distance by mutableStateOf("-")
    var isPaused by mutableStateOf(false)
    var isHikingActive by mutableStateOf(false)
    var emergencyState by mutableStateOf(emergencyMachine.state)
        private set
    var connectedNodeCount by mutableIntStateOf(0)
    var lastReceivedAtMs by mutableStateOf<Long?>(null)

    val isEmergencyVisible: Boolean get() = emergencyState.phase != EmergencyPhase.IDLE
    val anomalyMessage: String get() = emergencyState.alert?.message.orEmpty()
    val anomalyCountdown: Int? get() = emergencyState.countdownSeconds
    val isSosReporting: Boolean get() = emergencyState.phase == EmergencyPhase.SENDING
    val emergencySendState: String get() = when (emergencyState.phase) {
        EmergencyPhase.IDLE, EmergencyPhase.COUNTDOWN -> "idle"
        EmergencyPhase.SENDING -> "sending"
        EmergencyPhase.SUCCESS -> "success"
        EmergencyPhase.FAILED -> "failed"
    }

    fun updateConnectedNodeCount(count: Int) { connectedNodeCount = count }
    fun markDataReceived() { lastReceivedAtMs = System.currentTimeMillis() }
    fun updateHeartRate(newBpm: Int) { bpm = newBpm }
    fun updateEta(newEta: String) { eta = newEta }
    fun updateDistance(newDistance: String) { distance = newDistance }
    fun updatePaused(paused: Boolean) { isPaused = paused }
    fun updateHikingActive(active: Boolean) { isHikingActive = active; if (!active) isPaused = false }

    fun startEmergencyAlert(alert: EmergencyAlert, countdownSeconds: Int = 30): Boolean =
        emergencyMachine.startAlert(alert, countdownSeconds).also { syncEmergencyState() }
    fun tickCountdown(): Boolean = emergencyMachine.tickCountdown().also { syncEmergencyState() }
    fun beginEmergencySend(trigger: EmergencyTrigger): EmergencyAlert? =
        emergencyMachine.beginSending(trigger).also { syncEmergencyState() }
    fun markEmergencySucceeded(): Boolean = emergencyMachine.markSucceeded().also { syncEmergencyState() }
    fun markEmergencyFailed(message: String? = null): Boolean =
        emergencyMachine.markFailed(message).also { syncEmergencyState() }
    fun applyRemoteEmergencyStatus(state: String): Boolean =
        emergencyMachine.applyRemoteStatus(state).also { syncEmergencyState() }
    fun cancelEmergency(): EmergencyAlert? = emergencyMachine.cancel().also { syncEmergencyState() }
    fun resetEmergency() { emergencyMachine.reset(); syncEmergencyState() }

    private fun syncEmergencyState() { emergencyState = emergencyMachine.state }
}
