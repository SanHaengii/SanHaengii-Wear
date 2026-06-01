package com.sanhaengii.wearhealthsender

import org.json.JSONObject

// HR>160 | HR<40 | SpO2<90% | 체온>39°C | 체온<35°C (모바일 앱 기준과 동일)
fun detectAnomalyLocally(payload: HealthServicesPayload): String? {
    val hr = payload.heartRate
    if (hr != null) {
        if (hr > 160) return "심박수 과부하 ($hr bpm)"
        if (hr < 40) return "심박수 이상 저하 ($hr bpm)"
    }
    val spo2 = payload.spo2
    if (spo2 != null && spo2 < 90.0) return "산소포화도 위험 (${spo2.toInt()}%)"
    val temp = payload.bodyTemp
    if (temp != null) {
        if (temp > 39.0) return "고열 감지 (${temp}°C)"
        if (temp < 35.0) return "저체온 위험 (${temp}°C)"
    }
    return null
}

// /health/data 백엔드 응답 파싱 → (이상 감지 여부, sos_request_id)
// Railway 백엔드: {is_anomaly, message, anomaly_type}
// sos_alerts.py 로컬: {anomaly, sos_request_id}
// 두 형식 모두 지원한다.
fun parseHealthDataResponse(responseBody: String): Pair<Boolean, Int?> {
    return try {
        val json = JSONObject(responseBody)
        val isAnomaly = json.optBoolean("is_anomaly", false) || json.optBoolean("anomaly", false)
        val sosRequestId = if (json.has("sos_request_id") && !json.isNull("sos_request_id")) {
            json.getInt("sos_request_id")
        } else null
        Pair(isAnomaly, sosRequestId)
    } catch (_: Exception) {
        Pair(false, null)
    }
}
