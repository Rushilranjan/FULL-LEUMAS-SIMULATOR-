package com.leumas.robot.render

import com.leumas.robot.calibration.Calibration
import com.leumas.robot.domain.ActiveTransfer
import com.leumas.robot.domain.RobotAnimationState
import com.leumas.robot.domain.RobotPhase
import com.leumas.robot.protocol.SlotMap
import com.leumas.robot.protocol.TransferKind
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Can the arm actually reach every calibrated interaction point?
 *
 * The premise of the whole visualisation is that the machine extends *past* the workspace boundary
 * toward a module standing outside it. If a slot is out of range the arm stops short and the
 * illusion collapses, so this measures every default slot rather than trusting a screenshot.
 *
 * It is also the regression test for the arm proportions in [ArenaGeometry]: those constants are
 * chosen to satisfy exactly this, and changing them without re-running this is how the bottom row
 * silently becomes unreachable again.
 */
class ReachDiagnosticTest {

    /** Deliberately awkward aspect ratios — reach must not depend on screen shape. */
    private val geometries = listOf(
        "phone landscape 1600x720" to ArenaGeometry(1600f, 720f),
        "tall 1280x1080" to ArenaGeometry(1280f, 1080f),
        "wide 2400x900" to ArenaGeometry(2400f, 900f),
    )

    private fun gripperFor(slot: Int, geometry: ArenaGeometry): Kinematics.ArmSolution {
        val state = RobotAnimationState(
            phase = RobotPhase.PICKING,
            phaseProgress = 1f,
            overallProgress = 0.5f,
            transfer = ActiveTransfer(slot, 4, TransferKind.ROBOT, 0L),
            carryingPayload = false,
            catchingUp = false,
        )
        return RobotPoser().pose(state, Calibration.DEFAULT, geometry).arm
    }

    @Test
    fun `every default slot is reachable on every aspect ratio`() {
        val failures = mutableListOf<String>()

        geometries.forEach { (label, geometry) ->
            SlotMap.all.forEach { spec ->
                val point = Calibration.DEFAULT.pointFor(spec.slot)
                val target = geometry.overshoot(point)
                val arm = gripperFor(spec.slot, geometry)
                val error = arm.gripper.distanceTo(target)
                if (error > TOLERANCE_PX || arm.reachClamped) {
                    failures += "%s / %-16s %-6s target=(%.0f,%.0f) gripper=(%.0f,%.0f) err=%.1f clamped=%s"
                        .format(
                            label, spec.displayName, point.edge,
                            target.x, target.y, arm.gripper.x, arm.gripper.y,
                            error, arm.reachClamped,
                        )
                }
            }
        }

        assertTrue(
            "slots the arm cannot reach:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `the end effector genuinely crosses the workspace boundary`() {
        // Spec is explicit that the robot must not merely stop at the edge — the effector should
        // partially disappear beyond it.
        val geometry = ArenaGeometry(1600f, 720f)
        SlotMap.all.forEach { spec ->
            val point = Calibration.DEFAULT.pointFor(spec.slot)
            val g = gripperFor(spec.slot, geometry).gripper
            val outside = g.x < 0f || g.x > geometry.widthPx ||
                g.y < 0f || g.y > geometry.heightPx
            assertTrue(
                "${spec.displayName} (${point.edge}) stopped inside the arena at (${g.x}, ${g.y})",
                outside,
            )
        }
    }

    private companion object {
        const val TOLERANCE_PX = 2f
    }
}
