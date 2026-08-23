package com.sanhaengii.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyStateMachineTest {
    private val alert = EmergencyAlert(AnomalyType.SPO2_LOW, "low", EmergencySource.LOCAL_SENSOR, "req-1")

    @Test
    fun `카운트다운은 1에서 0이 되는 한 번만 완료된다`() {
        val machine = EmergencyStateMachine(defaultCountdownSeconds = 2)
        machine.startAlert(alert)
        assertFalse(machine.tickCountdown())
        assertTrue(machine.tickCountdown())
        assertFalse(machine.tickCountdown())
        assertEquals(0, machine.state.countdownSeconds)
    }

    @Test
    fun `전송 중에는 중복 전송을 시작할 수 없다`() {
        val machine = EmergencyStateMachine()
        machine.startAlert(alert)
        assertNotNull(machine.beginSending(EmergencyTrigger.USER_CONFIRM))
        assertEquals(null, machine.beginSending(EmergencyTrigger.USER_CONFIRM))
    }

    @Test
    fun `실패 후 재시도하고 성공할 수 있다`() {
        val machine = EmergencyStateMachine()
        machine.startAlert(alert)
        machine.beginSending(EmergencyTrigger.USER_CONFIRM)
        assertTrue(machine.markFailed("timeout"))
        assertNotNull(machine.beginSending(EmergencyTrigger.USER_CONFIRM))
        assertTrue(machine.markSucceeded())
        assertEquals(EmergencyPhase.SUCCESS, machine.state.phase)
    }

    @Test
    fun `전송 중이 아닌 오래된 ACK는 무시한다`() {
        val machine = EmergencyStateMachine()
        assertFalse(machine.applyRemoteStatus("success"))
        assertEquals(EmergencyPhase.IDLE, machine.state.phase)
    }
}
