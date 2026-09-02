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
     * The dispenser rotor must turn whenever material is moving, be stationary otherwise, and run
     * the **other way** while drawing in. It is driven by elapsed time rather than phase progress,
     * so it reads as doing work regardless of how the choreography is retimed.
     *
     * **Change 26 made this bidirectional.** It used to assert the rotor turned *only* while
     * discharging, which matched a renderer that poured collected material into the top of the
     * vessel. The vessel has had no open top since change 16.6 closed the mount into one continuous
     * body, so the intake now goes through the nozzle — the head is a peristaltic pump and a
     * peristaltic pump is reversible. A rotor sitting still through a collection would be the one
     * part of the machine visibly not doing the work.
     *
     * The idle assertion is on `stream == 0f` rather than the old `<= 0.01f`. The rotor is driven by
     * the same flow envelope that sets `stream`, so across the handful of frames where the envelope
     * is ramping but still under 0.01 the rotor has genuinely, correctly begun to creep. The old
     * threshold only passed because PICKING used to publish a hardcoded zero angle, hiding it.
     */
    @Test
    fun `the rotor runs to discharge, and idles while powder is collected`() {
        val frames = runTransfer(1, 4)          // solid: macro 1 -> blender 2
        val dispensing = frames.filter { it.second.pouring && it.second.stream > 0.5f }
        val collecting = frames.filter { !it.second.pouring && it.second.stream > 0.5f }
        assertTrue("never dispensed", dispensing.isNotEmpty())
        assertTrue("never collected", collecting.isNotEmpty())

        assertTrue("rotor never turned while dispensing",
            dispensing.map { it.second.pumpAngle }.distinct().size > 20)
        assertTrue("dispensing should wind the rotor on, got ${netTurn(dispensing)}",
            netTurn(dispensing) > 20f)

        // **Change 37: the head does NOT run while POWDER is collected.** The macro discharges
        // into the vessel under gravity and the vessel is only receiving -- nothing passes through
        // the pump, so a turning rotor would be the machine lying about what it is doing.
        assertEquals(
            "rotor should be still while collecting powder",
            0f, netTurn(collecting), 1e-3f,
        )

        // The rotor is never driven while nothing at all is moving.
        val beforeFlow = frames.takeWhile { it.second.stream == 0f }
        assertTrue("beforeFlow window is empty", beforeFlow.isNotEmpty())
        assertTrue("rotor turned before any flow", beforeFlow.all { it.second.pumpAngle == 0f })
    }

    /**
     * Liquid is the exception: the head genuinely draws it in, so it still runs -- backwards.
     *
     * This is what stops the change above being over-applied. A peristaltic head is reversible and
     * the vessel has no open top (change 16.6), so a liquid intake really does pass through the
     * pump; only powder, which falls in under gravity, does not.
     */
    @Test
    fun `the rotor reverses to draw liquid in`() {
        val frames = runTransfer(7, 9)          // liquid: macro -> vessel
        val collecting = frames.filter { !it.second.pouring && it.second.stream > 0.5f }
        assertTrue("never collected", collecting.isNotEmpty())
        assertTrue("rotor never turned drawing liquid in",
            collecting.map { it.second.pumpAngle }.distinct().size > 20)
        assertTrue("drawing in should unwind the rotor, got ${netTurn(collecting)}",
            netTurn(collecting) < -20f)
    }

    /** Net rotation across a window, unwrapping the 0..360 fold so the sign survives. */
    private fun netTurn(frames: List<Pair<Float, Payload>>): Float {
        var total = 0f
        for (i in 1 until frames.size) {
            var d = frames[i].second.pumpAngle - frames[i - 1].second.pumpAngle
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            total += d
        }
        return total
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
