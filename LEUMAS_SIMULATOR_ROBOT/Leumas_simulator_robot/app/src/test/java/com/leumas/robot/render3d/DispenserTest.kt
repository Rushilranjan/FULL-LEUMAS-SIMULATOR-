package com.leumas.robot.render3d

import com.leumas.robot.calibration.Calibration
import com.leumas.robot.domain.RobotStateMachine
import com.leumas.robot.protocol.McuEvent
import com.leumas.robot.protocol.TransferKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the vessel and its dispensers (change 15.1).
 *
 * These assert on the payload the poser publishes rather than on pixels, because the visual result
 * is easy to misread on a screenshot: the vessel is small, the material stream is brief, and the
 * capture window for a 40 s trip is narrow. If the data is right and the renderer consumes it, the
 * animation is right.
 */
class DispenserTest {

    /** Run one whole transfer and collect the payload published on every frame. */
    private fun runTransfer(from: Int, to: Int): List<Pair<Float, Payload>> {
        val machine = RobotStateMachine()
        val poser = Robot3DPoser()
        machine.submit(McuEvent.Move(from, to, TransferKind.ROBOT), 0L)
        val out = mutableListOf<Pair<Float, Payload>>()
        var now = 0L
        repeat(2_600) {
            now += FRAME_MS
            val state = machine.update(now)
            val pose = poser.pose(state, Calibration.DEFAULT, now)
            if (state.phase != null) out += state.overallProgress to pose.payload
        }
        return out
    }

    @Test
    fun `material flows in at the source and out at the destination`() {
        val frames = runTransfer(1, 4)
        val collecting = frames.filter { !it.second.pouring && it.second.stream > 0.5f }
        val dispensing = frames.filter { it.second.pouring && it.second.stream > 0.5f }

        assertTrue("never collected", collecting.isNotEmpty())
        assertTrue("never dispensed", dispensing.isNotEmpty())
        // Both windows must be long enough to read as an animation, not a flicker.
        assertTrue("collect window too short: ${collecting.size} frames", collecting.size > 120)
        assertTrue("dispense window too short: ${dispensing.size} frames", dispensing.size > 120)
    }

    @Test
    fun `the vessel fills at the source and empties at the destination`() {
        val frames = runTransfer(1, 4)
        assertEquals("vessel should start empty", 0f, frames.first().second.fill, 0.02f)
        assertTrue("vessel never filled", frames.any { it.second.fill > 0.97f })
        // ...and is empty again by the end of the dispense.
        val lastDispense = frames.last { it.second.pouring }
        assertTrue("vessel not emptied, fill=${lastDispense.second.fill}", lastDispense.second.fill < 0.1f)
    }

    /**
     * The dispenser rotor must turn while discharging and be stationary otherwise — it is driven by
     * elapsed time, not phase progress, so it reads as doing work.
     */
    @Test
    fun `the dispenser rotor turns only while discharging`() {
        val frames = runTransfer(1, 4)
        val dispensing = frames.filter { it.second.pouring && it.second.stream > 0.5f }
        val angles = dispensing.map { it.second.pumpAngle }
        assertTrue("rotor never turned", angles.distinct().size > 20)

        // Before any material moves, the rotor has not been driven at all.
        val beforeFlow = frames.takeWhile { it.second.stream <= 0.01f }
        assertTrue("rotor turned before any flow", beforeFlow.all { it.second.pumpAngle == 0f })
    }

    /** Material kind follows the source module's production line, which picks the mechanism. */
    @Test
    fun `solid and liquid lines select different material`() {
        assertEquals(MaterialKind.SOLID, runTransfer(1, 4).first { it.second.stream > 0.5f }.second.kind)
        assertEquals(MaterialKind.LIQUID, runTransfer(7, 9).first { it.second.stream > 0.5f }.second.kind)
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
