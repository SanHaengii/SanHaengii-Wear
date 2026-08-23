package com.sanhaengii.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyRepositoryTest {
    @Test
    fun `긴급 payload는 request id와 null 위치를 안전하게 담는다`() {
        val body = buildEmergencyRequestBody(
            userId = 7,
            eventType = "이상_징후",
            latitude = null,
            longitude = null,
            triggeredBy = "auto_timeout",
            reason = "산소포화도 위험",
            requestId = "req-7",
            timestamp = "2026-08-24T00:00:00Z",
        )
        val json = JSONObject(body)
        assertEquals(7L, json.getLong("userId"))
        assertEquals("req-7", json.getString("requestId"))
        assertTrue(json.getJSONObject("location").isNull("lat"))
    }

    @Test
    fun `2xx queued 응답을 대기 상태로 파싱한다`() {
        assertEquals(
            EmergencyResponseStatus.QUEUED,
            parseEmergencyResponse(ApiResponse(202, "{\"state\":\"queued\"}")),
        )
    }

    @Test
    fun `오류 HTTP 응답은 body와 무관하게 실패다`() {
        assertEquals(
            EmergencyResponseStatus.FAILED,
            parseEmergencyResponse(ApiResponse(401, "{\"state\":\"success\"}")),
        )
    }
}
