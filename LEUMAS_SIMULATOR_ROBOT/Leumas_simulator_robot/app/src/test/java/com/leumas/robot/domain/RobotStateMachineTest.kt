package com.leumas.robot.domain

import com.leumas.robot.ble.TransferAck
import com.leumas.robot.protocol.McuEvent
import com.leumas.robot.protocol.TransferKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RobotStateMachineTest {

    private class RecordingAck : TransferAck {
        val completed = mutableListOf<Pair<Int, Int>>()
        override fun onRobotTransferComplete(from: Int, to: Int) {
            completed += from to to
        }
    }

    private fun robotMove(from: Int, to: Int) = McuEvent.Move(from, to, TransferKind.ROBOT)

    @Test
    fun `phase fractions sum to exactly one`() {
        val sum = RobotPhase.entries.map { it.fraction }.sum()
        assertTrue("phases sum to $sum", abs(sum - 1f) < 1e-5)
    }

    @Test
    fun `no phase is instantaneous`() {
        // Spec 11: every joint movement must be visible to an audience.
        RobotPhase.entries.forEach { assertTrue("${it.name} is zero-length", it.fraction > 0f) }
    }

    @Test
    fun `starts idle`() {
        val m = RobotStateMachine()
        assertTrue(m.update(0L).isIdle)
        assertFalse(m.isBusy)
    }

    @Test
    fun `walks every phase in order across the transfer`() {
        val m = RobotStateMachine(totalDurationMs = 1000L)
        m.submit(robotMove(1, 4), 0L)
        m.update(0L)

        val seen = linkedSetOf<RobotPhase>()
        for (t in 0..1000 step 5) {
            m.update(t.toLong()).phase?.let { seen += it }
        }
        assertEquals(RobotPhase.ORDERED, seen.toList())
    }

    @Test
    fun `conveyor transfers never start an arm animation`() {
        // Spec 14: blender -> packaging must not move the robot.
        val m = RobotStateMachine()
        m.submit(McuEvent.Move(4, 6, TransferKind.PROCESS), 0L)
        assertFalse(m.isBusy)
        assertTrue(m.update(0L).isIdle)
    }

    @Test
    fun `a move arriving mid-transfer is queued, not run concurrently`() {
        val m = RobotStateMachine(totalDurationMs = 1000L)
        m.submit(robotMove(1, 4), 0L)
        m.update(0L)
        m.update(500L)
        m.submit(robotMove(3, 4), 500L)

        // Still animating the first transfer.
        assertEquals(1, m.update(600L).transfer?.from)
        assertEquals(0, m.droppedTransfers)
    }

    @Test
    fun `the pending transfer starts as soon as the first completes`() {
        val ack = RecordingAck()
        val m = RobotStateMachine(totalDurationMs = 1000L, ack = ack)
        m.submit(robotMove(1, 4), 0L)
        m.update(0L)
        m.submit(robotMove(3, 4), 100L)
        m.update(100L)

        // Catch-up doubles the rate, so 1000 ms of animation needs ~500 ms of wall clock.
        val state = m.update(1200L)
        assertEquals(listOf(1 to 4), ack.completed)
        assertEquals(3, state.transfer?.from)
    }

    @Test
    fun `a third transfer displaces the stale pending one and is counted`() {
        val m = RobotStateMachine(totalDurationMs = 1000L)
        m.submit(robotMove(1, 4), 0L)
        m.update(0L)
        m.submit(robotMove(2, 4), 100L)
        m.submit(robotMove(3, 4), 200L)
        assertEquals(1, m.droppedTransfers)
    }

    @Test
    fun `catch-up engages only while something is pending`() {
        val m = RobotStateMachine(totalDurationMs = 1000L)
        m.submit(robotMove(1, 4), 0L)
        m.update(0L)
        assertFalse(m.update(100L).catchingUp)
        m.submit(robotMove(3, 4), 100L)
        assertTrue(m.update(200L).catchingUp)
    }

    @Test
    fun `catch-up never exceeds the cap so the robot cannot teleport`() {
        val m = RobotStateMachine(totalDurationMs = 1000L)
        m.submit(robotMove(1, 4), 0L)
        m.update(0L)
        m.submit(robotMove(3, 4), 0L)
        // 400 ms of wall clock at 2x is 800 ms of animation - not yet complete.
        val s = m.update(400L)
        assertEquals(1, s.transfer?.from)
        assertTrue("overall was ${s.overallProgress}", s.overallProgress < 1f)
    }

    @Test
    fun `abort clears everything`() {
        val m = RobotStateMachine(totalDurationMs = 1000L)
        m.submit(robotMove(1, 4), 0L)
        m.update(0L)
        m.submit(robotMove(3, 4), 0L)
        m.abort()
        assertFalse(m.isBusy)
        assertTrue(m.update(500L).isIdle)
    }

    @Test
    fun `payload is held between pickup and release`() {
        val m = RobotStateMachine(totalDurationMs = 1000L)
        m.submit(robotMove(1, 4), 0L)
        m.update(0L)

        fun carryingAt(t: Long): Boolean = m.update(t).carryingPayload

        assertFalse("empty while travelling to source", carryingAt(100L))
        assertTrue("carrying while travelling to destination", carryingAt(550L))
        assertFalse("empty again after release", carryingAt(980L))
    }

    @Test
    fun `acknowledges each completed robot transfer exactly once`() {
        val ack = RecordingAck()
        val m = RobotStateMachine(totalDurationMs = 100L, ack = ack)
        m.submit(robotMove(1, 4), 0L)
        m.update(0L)
        m.update(150L)
        m.update(300L)
        assertEquals(listOf(1 to 4), ack.completed)
    }
}
