package com.sanhaengii.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WearDataLayerBridgeTest {
    @Test
    fun `건강 데이터 페이로드는 파이프 구분 문자열로 직렬화된다`() {
        val bytes = buildHealthLivePayload(hr = 72, spo2 = 97.0, temp = 36.6, steps = 1200)
        val parts = String(bytes).split("|")
        assertEquals(5, parts.size)
        assertEquals("72", parts[0])
        assertEquals("97.0", parts[1])
        assertEquals("36.6", parts[2])
        assertEquals("1200", parts[3])
        assert(parts[4].toLong() > 0) { "5번째 필드는 타임스탬프여야 함" }
    }

    @Test
    fun `이상징후 payload는 type과 message와 타임스탬프를 포함한다`() {
        val map = buildAnomalyPayload("hr_high", "심박수 과부하 (170 bpm)")
        assertEquals("hr_high", map["type"])
        assertEquals("심박수 과부하 (170 bpm)", map["message"])
        assert((map["at"] as Long) > 0)
    }

    @Test
    fun `SOS 요청 payload는 좌표가 없으면 필드를 생략한다`() {
        val map = buildSosRequestPayload(id = "abc", source = "watch_sos", lat = null, lng = null)
        assertEquals("abc", map["id"])
        assertEquals(false, map.containsKey("lat"))
        assertEquals(false, map.containsKey("lng"))
    }

    @Test
    fun `산행 제어 payload는 action과 id를 그대로 담고 타임스탬프를 포함한다`() {
        val map = buildHikeControlPayload(action = "pause", id = "req-1")
        assertEquals("pause", map["action"])
        assertEquals("req-1", map["id"])
        assert((map["at"] as Long) > 0)
    }

    @Test
    fun `산행 제어 payload의 action은 그대로 왕복한다`() {
        for (action in listOf("pause", "resume", "abort", "sos_cancel")) {
            val map = buildHikeControlPayload(action = action, id = "req-2")
            assertEquals(action, map["action"])
        }
    }
}
