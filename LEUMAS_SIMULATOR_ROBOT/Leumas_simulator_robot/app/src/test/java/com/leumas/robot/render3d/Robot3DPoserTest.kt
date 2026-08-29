package com.leumas.robot.render3d

import com.leumas.robot.calibration.Calibration
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
 * Unit coverage for the 3-D poser (post-demo changes 10.2 and 10.3).
 *
 * The renderer itself needs a device, but the pose maths are plain JVM, so the two behaviours that
 * matter — the arm returns home after a transfer, and it can actually reach every calibrated edge —
 * are checked here.
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

    @Test
    fun `idle pose parks at rail centre, facing forward, arm stowed`() {
        val pose = Robot3DPoser().pose(RobotAnimationState.IDLE, Calibration.DEFAULT)
        assertEquals(Arena3D.HOME_X, pose.carriageX, 1e-4f)
        assertEquals(0f, pose.baseYaw, 1e-4f)
        assertEquals(1f, pose.gripperOpen, 1e-4f)
        assertFalse(pose.carrying)
    }

    @Test
    fun `returning home drives the carriage back to centre`() {
        val poser = Robot3DPoser()
        // A transfer whose destination is at the far right end of the rail...
        poser.pose(state(RobotPhase.PLACING, 1f, 1, 4), Calibration.DEFAULT)
        // ...at the end of RETURNING_HOME the carriage must be back at rail centre (change 10.2).
        val home = poser.pose(state(RobotPhase.RETURNING_HOME, 1f, 1, 4), Calibration.DEFAULT)
        assertEquals(Arena3D.HOME_X, home.carriageX, 1e-3f)
        assertEquals(0f, home.baseYaw, 1e-3f)
    }

    @Test
    fun `every default slot yields a finite, unclamped reach`() {
        val poser = Robot3DPoser()
        // One representative slot per edge: Macro1 TOP, Blender1 RIGHT, LMacro1 LEFT, Vessel1 BOTTOM.
        listOf(1, 4, 7, 9).forEach { slot ->
            val pose = poser.pose(state(RobotPhase.PICKING, 1f, slot, 4), Calibration.DEFAULT)
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
        val first = poser.pose(state(RobotPhase.MOVING_TO_SOURCE, 0f, 4, 6), Calibration.DEFAULT)
        assertEquals(Arena3D.HOME_X, first.carriageX, 1e-3f)
    }
}
