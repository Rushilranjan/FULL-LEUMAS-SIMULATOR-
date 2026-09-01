package com.leumas.robot.render3d

import com.leumas.robot.calibration.ArenaEdge
import com.leumas.robot.calibration.Calibration
import com.leumas.robot.calibration.EdgePoint
import com.leumas.robot.domain.ActiveTransfer
import com.leumas.robot.domain.RobotAnimationState
import com.leumas.robot.domain.RobotPhase
import com.leumas.robot.protocol.TransferKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Unit coverage for the 3-D poser (post-demo changes 10.2, 10.3 and 13.2/13.3).
 *
 * The renderer itself needs a device, but the pose maths are plain JVM, so the behaviours that
 * matter — the arm returns home after a transfer, it can reach every calibrated point including the
 * corners, and it never teleports — are checked here.
 */
class Robot3DPoserTest {

    private fun state(phase: RobotPhase, progress: Float, from: Int, to: Int) = RobotAnimationState(
        phase = phase,
        phaseProgress = progress,
        overallProgress = 0.5f,
        transfer = ActiveTransfer(from, to, TransferKind.ROBOT, 0L),
        carryingPayload = false,
        catchingUp = false,
    )

    /**
     * Hold a state until the poser's smoothing filter has converged.
     *
     * The pose is filtered over time (change 13.3), so a single call returns a point on the way to
     * the target rather than the target itself. Steady-state assertions have to settle first.
     */
    private fun settle(
        poser: Robot3DPoser,
        state: RobotAnimationState,
        calibration: Calibration = Calibration.DEFAULT,
        frames: Int = 240,
        startMs: Long = 0L,
    ): Robot3DPose {
        var pose = poser.pose(state, calibration, startMs)
        for (i in 1..frames) {
            pose = poser.pose(state, calibration, startMs + i * FRAME_MS)
        }
        return pose
    }

    @Test
    fun `idle pose parks at rail centre, at the home facing, arm stowed`() {
        val pose = settle(Robot3DPoser(), RobotAnimationState.IDLE)
        assertEquals(Arena3D.HOME_X, pose.carriageX, 1e-3f)
        // Not 0: the resting facing is turned slightly off-axis so the arm is not foreshortened
        // into a column pointing straight at the camera. See Arena3D.HOME_YAW.
        assertEquals(Arena3D.HOME_YAW, pose.baseYaw, 1e-3f)
        assertEquals(1f, pose.gripperOpen, 1e-4f)
        assertFalse(pose.carrying)
    }

    @Test
    fun `returning home drives the carriage back to centre`() {
        val poser = Robot3DPoser()
        // A transfer whose destination is at the far right end of the rail...
        settle(poser, state(RobotPhase.PLACING, 1f, 1, 4))
        // ...at the end of RETURNING_HOME the carriage must be back at rail centre (change 10.2).
        val home = settle(poser, state(RobotPhase.RETURNING_HOME, 1f, 1, 4), startMs = 100_000L)
        assertEquals(Arena3D.HOME_X, home.carriageX, 1e-3f)
        assertEquals(Arena3D.HOME_YAW, home.baseYaw, 1e-3f)
    }

    @Test
    fun `every default slot yields a finite, unclamped reach`() {
        // One representative slot per edge: Macro1 TOP, Blender1 RIGHT, LMacro1 LEFT, Vessel1 BOTTOM.
        listOf(1, 4, 7, 9).forEach { slot ->
            val pose = settle(Robot3DPoser(), state(RobotPhase.PICKING, 1f, slot, 4))
            val g = pose.arm.gripper
            assertTrue("slot $slot produced a non-finite gripper", g.x.isFinite() && g.y.isFinite())
            assertFalse("slot $slot was out of reach (clamped)", pose.arm.reachClamped)
            // The gripper must extend well clear of the shoulder, not sit folded at the base.
            assertTrue("slot $slot barely extended", hypot(g.x, abs(g.y)) > 0.6f)
        }
    }

    @Test
    fun `a transfer beginning while parked does not snap the carriage`() {
        val poser = Robot3DPoser()
        // Parked at home, then the very first frame of a move to a RIGHT-edge source: the carriage
        // must start from home and travel, never teleport to the source (spec §10).
        val first = poser.pose(state(RobotPhase.MOVING_TO_SOURCE, 0f, 4, 6), Calibration.DEFAULT, 0L)
        assertEquals(Arena3D.HOME_X, first.carriageX, 1e-3f)
    }

    /**
     * Change 13.2: a module calibrated into a corner must be reached diagonally.
     *
     * The old edge-keyed mapping had exactly three facings — hard left, hard right, and straight at
     * the camera — so a corner point yawed to 0 and the arm reached forward instead of into the
     * corner ("it can only go up or sideways, nothing in between those angles"). Every corner must
     * now produce a yaw that is neither of those.
     */
    @Test
    fun `corner calibration points produce diagonal facings`() {
        val corners = mapOf(
            "top-left" to EdgePoint(ArenaEdge.TOP, 0.0f),
            "top-right" to EdgePoint(ArenaEdge.TOP, 1.0f),
            "bottom-left" to EdgePoint(ArenaEdge.BOTTOM, 0.0f),
            "bottom-right" to EdgePoint(ArenaEdge.BOTTOM, 1.0f),
        )
        corners.forEach { (label, point) ->
            val yaw = Arena3D.yawFor(point)
            assertTrue("$label yawed straight ahead ($yaw)", abs(yaw) > 0.20f)
            assertTrue("$label yawed fully sideways ($yaw)", abs(yaw) < HALF_PI - 0.05f)
        }
        // ...and the two sides must be genuinely mirrored, not both collapsed onto one facing.
        assertTrue(Arena3D.yawFor(corners.getValue("top-left")) < 0f)
        assertTrue(Arena3D.yawFor(corners.getValue("top-right")) > 0f)
    }

    /**
     * The machine is never seen face-on, anywhere along an edge.
     *
     * This test used to demand smooth continuity in the calibration point, and change 23 had to
     * give that up. The two properties are incompatible: yaw must change sign along an edge -- the
     * two ends mirror each other -- and if it may never be small it cannot pass through zero, so it
     * has to jump. Change 16.5 kept continuity and paid for it with a zero crossing at
     * targetX = -1.45, where a module was serviced dead face-on. The requirement is the angle, so
     * the angle is what is asserted now.
     *
     * Continuity still holds either side of rail centre, and that is checked: the jump is ONE step,
     * in one place, not a turret that snaps about wherever the point moves.
     */
    @Test
    fun `the base is never near face-on anywhere along an edge`() {
        var previous: Float? = null
        var jumps = 0
        for (i in 0..200) {
            val yaw = Arena3D.yawFor(EdgePoint(ArenaEdge.TOP, i / 200f))
            assertTrue(
                "face-on at $i: $yaw",
                abs(yaw) >= Arena3D.MIN_SERVICE_YAW - 1e-3f,
            )
            previous?.let { if (abs(yaw - it) > 0.10f) jumps++ }
            previous = yaw
        }
        assertEquals("yaw should change facing exactly once", 1, jumps)

        // Left end turns one way, right end the other, and both are full profile.
        assertEquals(-Arena3D.MIN_SERVICE_YAW, Arena3D.yawFor(EdgePoint(ArenaEdge.TOP, 0f)), 1e-3f)
        assertEquals(Arena3D.MIN_SERVICE_YAW, Arena3D.yawFor(EdgePoint(ArenaEdge.TOP, 1f)), 1e-3f)
    }

    /** Every calibrated point must stay inside the arm's envelope, corners included. */
    @Test
    fun `no calibrated point drives the arm past full extension`() {
        listOf(ArenaEdge.LEFT, ArenaEdge.TOP, ArenaEdge.RIGHT, ArenaEdge.BOTTOM).forEach { edge ->
            for (i in 0..20) {
                val point = EdgePoint(edge, i / 20f)
                val solution = Robot3DPose.solve(Arena3D.reachTarget(point))
                assertFalse("$edge @ ${i / 20f} was clamped", solution.reachClamped)
            }
        }
    }

    private companion object {
        const val FRAME_MS = 16L
        const val HALF_PI = (Math.PI / 2.0).toFloat()
    }
}
