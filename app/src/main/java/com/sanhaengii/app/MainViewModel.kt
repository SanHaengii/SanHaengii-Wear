package com.sanhaengii.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    var bpm by mutableIntStateOf(0)
    var eta by mutableStateOf("-")
    var distance by mutableStateOf("-")
    var isPaused by mutableStateOf(false)
    var isHikingActive by mutableStateOf(false)
    var isSosReporting by mutableStateOf(false)

    // 생체 이상 감지 상태
    var isAnomalyDetected by mutableStateOf(false)
    var anomalyMessage by mutableStateOf("")
    var anomalyCountdown by mutableStateOf<Int?>(null)
    var anomalySosRequestId by mutableStateOf<Int?>(null)
    // 모바일 앱에서 감지된 이상징후 여부 (UI에서 "괜찮아요" 레이블 표시용)
    var isMobileAnomalySource by mutableStateOf(false)

    // 신고 전송 진행 상태 ("idle" / "sending" / "success" / "failed")
    var emergencySendState by mutableStateOf("idle")

    // 디버그 진단용: Data Layer 연결 노드 수 / 마지막 인바운드 수신 시각
    // (패키지명·서명키 불일치 시 메시지가 조용히 유실되므로, "페어링 안 됨"과
    // "페어링됐지만 응답 없음"을 구분하는 유일한 저비용 수단)
    var connectedNodeCount by mutableIntStateOf(0)
    var lastReceivedAtMs by mutableStateOf<Long?>(null)

    fun updateConnectedNodeCount(count: Int) {
        connectedNodeCount = count
    }

    fun markDataReceived() {
        lastReceivedAtMs = System.currentTimeMillis()
    }

    fun resetSosReporting() {
        isSosReporting = false
    }

    fun updateHeartRate(newBpm: Int) {
        bpm = newBpm
    }

    fun updateEta(newEta: String) {
        eta = newEta
    }

    fun updateDistance(newDistance: String) {
        distance = newDistance
    }

    fun togglePause() {
        isPaused = !isPaused
    }

    fun updatePaused(paused: Boolean) {
        isPaused = paused
    }

    fun updateHikingActive(active: Boolean) {
        isHikingActive = active
        if (!active) isPaused = false
    }

    fun triggerAnomaly(message: String, sosRequestId: Int? = null) {
        isAnomalyDetected = true
        anomalyMessage = message
        anomalyCountdown = 30
        anomalySosRequestId = sosRequestId
    }

    // 카운트다운 1초 감소. 정확히 1→0이 될 때만 true 반환 (이미 0이면 false로 중복 방지)
    fun tickCountdown(): Boolean {
        val current = anomalyCountdown ?: return false
        if (current <= 0) return false
        return if (current == 1) {
            anomalyCountdown = 0
            true
        } else {
            anomalyCountdown = current - 1
            false
        }
    }

    fun resetAnomaly() {
        isAnomalyDetected = false
        anomalyMessage = ""
        anomalyCountdown = null
        anomalySosRequestId = null
        isMobileAnomalySource = false
        emergencySendState = "idle"
    }
}
