package com.sanhaengii.app

// HR>160 | HR<40 | SpO2<90% | 체온>39°C | 체온<35°C (모바일 앱 기준과 동일)
// 값이 0이면 "측정 실패"로 보고 이상 판정에서 제외(오탐·오신고 방지).
fun detectAnomalyLocally(payload: HealthServicesPayload): String? {
    val hr = payload.heartRate
    if (hr != null) {
        if (hr > 160) return "심박수 과부하 ($hr bpm)"
        if (hr in 1..39) return "심박수 이상 저하 ($hr bpm)"
    }
    val spo2 = payload.spo2
    if (spo2 != null && spo2 > 0.0 && spo2 < 90.0) return "산소포화도 위험 (${spo2.toInt()}%)"
    val temp = payload.bodyTemp
    if (temp != null) {
        if (temp > 39.0) return "고열 감지 (${temp}°C)"
        if (temp > 0.0 && temp < 35.0) return "저체온 위험 (${temp}°C)"
    }
    return null
}
