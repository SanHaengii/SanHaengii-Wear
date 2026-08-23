package com.sanhaengii.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnomalyEpisodeGateTest {
    private val anomaly = DetectedAnomaly(AnomalyType.HEART_RATE_HIGH, "high")

    @Test
    fun `오래된 표본은 거부한다`() {
        val gate = AnomalyEpisodeGate(maxAgeMillis = 1_000)
        val decision = gate.evaluate(anomaly, measuredAtEpochMs = 8_000, nowEpochMs = 10_000)
        assertEquals(AnomalySampleRejection.STALE, (decision as AnomalySampleDecision.Rejected).reason)
    }

    @Test
    fun `허용 범위를 넘은 미래 표본은 거부한다`() {
        val gate = AnomalyEpisodeGate(futureToleranceMillis = 100)
        val decision = gate.evaluate(anomaly, measuredAtEpochMs = 10_101, nowEpochMs = 10_000)
        assertEquals(AnomalySampleRejection.FUTURE, (decision as AnomalySampleDecision.Rejected).reason)
    }

    @Test
    fun `같은 이상 에피소드는 한 번만 허용한다`() {
        val gate = AnomalyEpisodeGate()
        assertTrue(gate.evaluate(anomaly, 10_000, 10_000) is AnomalySampleDecision.Accepted)
        val duplicate = gate.evaluate(anomaly, 11_000, 11_000)
        assertEquals(AnomalySampleRejection.DUPLICATE, (duplicate as AnomalySampleDecision.Rejected).reason)
    }

    @Test
    fun `정상 표본 뒤 같은 유형을 새 에피소드로 허용한다`() {
        val gate = AnomalyEpisodeGate()
        gate.evaluate(anomaly, 10_000, 10_000)
        assertTrue(gate.evaluate(null, 11_000, 11_000) is AnomalySampleDecision.Normal)
        assertTrue(gate.evaluate(anomaly, 12_000, 12_000) is AnomalySampleDecision.Accepted)
    }
}
