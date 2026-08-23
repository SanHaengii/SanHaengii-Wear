package com.sanhaengii.app

enum class AnomalyType(val wireValue: String) {
    HEART_RATE_HIGH("hr_high"),
    HEART_RATE_LOW("hr_low"),
    SPO2_LOW("spo2_low"),
    BODY_TEMPERATURE_HIGH("temp_high"),
    BODY_TEMPERATURE_LOW("temp_low"),
    MANUAL_SOS("manual_sos"),
}

data class DetectedAnomaly(val type: AnomalyType, val message: String)

fun detectAnomaly(payload: HealthServicesPayload): DetectedAnomaly? {
    payload.heartRate?.let { heartRate ->
        if (heartRate > 160) return DetectedAnomaly(AnomalyType.HEART_RATE_HIGH, "심박수 과부하 ($heartRate bpm)")
        if (heartRate in 1..39) return DetectedAnomaly(AnomalyType.HEART_RATE_LOW, "심박수 이상 저하 ($heartRate bpm)")
    }
    payload.spo2?.takeIf { it > 0.0 }?.let { spo2 ->
        if (spo2 < 90.0) return DetectedAnomaly(AnomalyType.SPO2_LOW, "산소포화도 위험 (${spo2.toInt()}%)")
    }
    payload.bodyTemp?.takeIf { it > 0.0 }?.let { bodyTemp ->
        if (bodyTemp > 39.0) return DetectedAnomaly(AnomalyType.BODY_TEMPERATURE_HIGH, "고열 감지 (${bodyTemp}°C)")
        if (bodyTemp < 35.0) return DetectedAnomaly(AnomalyType.BODY_TEMPERATURE_LOW, "저체온 위험 (${bodyTemp}°C)")
    }
    return null
}

fun detectAnomalyLocally(payload: HealthServicesPayload): String? = detectAnomaly(payload)?.message

enum class AnomalySampleRejection { STALE, FUTURE, DUPLICATE }

sealed interface AnomalySampleDecision {
    data class Accepted(val anomaly: DetectedAnomaly) : AnomalySampleDecision
    data class Rejected(val reason: AnomalySampleRejection) : AnomalySampleDecision
    data object Normal : AnomalySampleDecision
}

/** 같은 이상 유형은 정상 표본이 관측된 뒤에만 새 에피소드로 인정한다. */
class AnomalyEpisodeGate(
    private val maxAgeMillis: Long = 90_000L,
    private val futureToleranceMillis: Long = 5_000L,
) {
    private var activeType: AnomalyType? = null

    fun evaluate(
        anomaly: DetectedAnomaly?,
        measuredAtEpochMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AnomalySampleDecision {
        if (measuredAtEpochMs > nowEpochMs + futureToleranceMillis) {
            return AnomalySampleDecision.Rejected(AnomalySampleRejection.FUTURE)
        }
        if (nowEpochMs - measuredAtEpochMs > maxAgeMillis) {
            return AnomalySampleDecision.Rejected(AnomalySampleRejection.STALE)
        }
        if (anomaly == null) {
            activeType = null
            return AnomalySampleDecision.Normal
        }
        if (activeType == anomaly.type) {
            return AnomalySampleDecision.Rejected(AnomalySampleRejection.DUPLICATE)
        }
        activeType = anomaly.type
        return AnomalySampleDecision.Accepted(anomaly)
    }

    fun reset() { activeType = null }
}
