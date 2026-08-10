package com.sanhaengii.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnomalyDetectionTest {
    @Test
    fun `심박수 160 초과는 이상 감지`() {
        val payload = payload(heartRate = 161)
        assertEquals("심박수 과부하 (161 bpm)", detectAnomalyLocally(payload))
    }

    @Test
    fun `심박수 40 이상은 정상`() {
        val payload = payload(heartRate = 40)
        assertNull(detectAnomalyLocally(payload))
    }

    @Test
    fun `심박수 0은 측정 실패로 간주해 이상 판정에서 제외`() {
        val payload = payload(heartRate = 0)
        assertNull(detectAnomalyLocally(payload))
    }

    @Test
    fun `SpO2 90 미만은 이상 감지`() {
        val payload = payload(spo2 = 89.0)
        assertEquals("산소포화도 위험 (89%)", detectAnomalyLocally(payload))
    }

    @Test
    fun `체온 39 초과는 이상 감지`() {
        val payload = payload(bodyTemp = 39.5)
        assertEquals("고열 감지 (39.5°C)", detectAnomalyLocally(payload))
    }

    @Test
    fun `parseHealthDataResponse는 is_anomaly 필드를 파싱`() {
        val (isAnomaly, sosId) = parseHealthDataResponse("""{"is_anomaly": true, "sos_request_id": 42}""")
        assertEquals(true, isAnomaly)
        assertEquals(42, sosId)
    }

    @Test
    fun `parseHealthDataResponse는 잘못된 JSON에 대해 false와 null을 반환`() {
        val (isAnomaly, sosId) = parseHealthDataResponse("not json")
        assertEquals(false, isAnomaly)
        assertNull(sosId)
    }

    private fun payload(
        heartRate: Int? = null,
        spo2: Double? = null,
        bodyTemp: Double? = null,
    ) = HealthServicesPayload(
        measuredAt = "2026-08-10T00:00:00+09:00",
        heartRate = heartRate,
        steps = null,
        calories = null,
        spo2 = spo2,
        bodyTemp = bodyTemp,
        bloodPressureSystolic = null,
        bloodPressureDiastolic = null,
    )
}
