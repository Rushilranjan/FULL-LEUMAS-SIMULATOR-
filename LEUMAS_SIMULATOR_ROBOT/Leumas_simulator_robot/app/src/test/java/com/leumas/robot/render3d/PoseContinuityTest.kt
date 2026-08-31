package com.leumas.robot.render3d

import com.leumas.robot.calibration.ArenaEdge
import com.leumas.robot.calibration.Calibration
import com.leumas.robot.calibration.EdgePoint
import com.leumas.robot.domain.RobotStateMachine
import com.leumas.robot.protocol.McuEvent
import com.leumas.robot.protocol.TransferKind
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The regression test for requirement 13.3 — "there is always a small glitch when the robot tries to
 * move and rotate its arm".
 *
 * Rounds 11 and 12 both tried to fix this and both shipped without a test, so the glitch survived.
 * This replays a complete transfer through the real state machine and poser at 60 fps and measures
 * the frame-to-frame movement of every joint. A glitch *is* a large single-frame delta: the elbow
 * mirroring across the base axis, the turret snapping between fixed facings, the carriage jumping
 * home. All of those show up here as a spike far above the smooth motion around them.
 *
 * It runs over a calibration whose modules sit in the screen corners as well as the default one,
 * because corner points are where the old edge-keyed mapping was most degenerate (change 13.2).
 */
class PoseContinuityTest {

    /** Every module driven into a corner or hard against an edge — the awkward cases. */
    private val cornerCalibration = Calibration(
        mapOf(
            1 to EdgePoint(ArenaEdge.TOP, 0.0f),
            2 to EdgePoint(ArenaEdge.TOP, 1.0f),
            3 to EdgePoint(ArenaEdge.LEFT, 0.0f),
            4 to EdgePoint(ArenaEdge.RIGHT, 1.0f),
            5 to EdgePoint(ArenaEdge.BOTTOM, 0.0f),
            6 to EdgePoint(ArenaEdge.BOTTOM, 1.0f),
            7 to EdgePoint(ArenaEdge.LEFT, 0.5f),
            8 to EdgePoint(ArenaEdge.RIGHT, 0.5f),
            9 to EdgePoint(ArenaEdge.TOP, 0.5f),
            10 to EdgePoint(ArenaEdge.BOTTOM, 0.5f),
            11 to EdgePoint(ArenaEdge.LEFT, 1.0f),
        )
    )

    @Test
    fun `default calibration transfers are smooth end to end`() {
        // Slot pairs covering every edge combination the default layout produces.
        listOf(1 to 4, 3 to 5, 7 to 9, 2 to 5, 8 to 10).forEach { (from, to) ->
            assertSmooth(Calibration.DEFAULT, from, to, "default")
        }
    }

    @Test
    fun `corner calibration transfers are smooth end to end`() {
        // The diagonal cases: corner to opposite corner, and straight across the arena.
        listOf(1 to 6, 2 to 5, 3 to 4, 1 to 4, 5 to 2).forEach { (from, to) ->
            assertSmooth(cornerCalibration, from, to, "corner")
        }
    }

    /**
     * Back-to-back transfers must not glitch at the seam either.
     *
     * The second transfer starts from wherever the first left the carriage, which is the path that
     * used to snap when the poser reset its remembered position.
     */
    @Test
    fun `a second transfer starting immediately does not jump`() {
        val machine = RobotStateMachine()
        val poser = Robot3DPoser()
        var now = 0L
        machine.submit(McuEvent.Move(1, 4, TransferKind.ROBOT), now)
        machine.submit(McuEvent.Move(5, 2, TransferKind.ROBOT), now)

        val worst = run(machine, poser, cornerCalibration, frames = 5_000) { now += FRAME_MS; now }
        worst.assertWithin("back-to-back")
    }

    // -- harness ---------------------------------------------------------------------------------

    private fun assertSmooth(calibration: Calibration, from: Int, to: Int, label: String) {
        val machine = RobotStateMachine()
        val poser = Robot3DPoser()
        var now = 0L
        machine.submit(McuEvent.Move(from, to, TransferKind.ROBOT), now)
        val worst = run(machine, poser, calibration, frames = 2_400) { now += FRAME_MS; now }
        worst.assertWithin("$label $from>$to")
    }

    private fun run(
        machine: RobotStateMachine,
        poser: Robot3DPoser,
        calibration: Calibration,
        frames: Int,
        clock: () -> Long,
    ): Worst {
        val worst = Worst()
        var previous: Robot3DPose? = null
        repeat(frames) {
            val now = clock()
            val pose = poser.pose(machine.update(now), calibration, now)
            previous?.let { worst.observe(it, pose) }
            previous = pose
        }
        return worst
    }

    /** Largest single-frame movement seen, per quantity, with where it happened. */
    private class Worst {
        var carriage = 0f; var carriageAt = ""
        var yaw = 0f; var yawAt = ""
        var elbow = 0f; var elbowAt = ""
        var gripper = 0f; var gripperAt = ""
        private var frame = 0

        fun observe(a: Robot3DPose, b: Robot3DPose) {
            frame++
            track(abs(b.carriageX - a.carriageX), { carriage }, { v, s -> carriage = v; carriageAt = s })
            track(abs(shortestArc(a.baseYaw, b.baseYaw)), { yaw }, { v, s -> yaw = v; yawAt = s })
            track(dist(world(a) { it.elbow }, world(b) { it.elbow }), { elbow }, { v, s -> elbow = v; elbowAt = s })
            track(dist(world(a) { it.gripper }, world(b) { it.gripper }), { gripper }, { v, s -> gripper = v; gripperAt = s })
        }

        private inline fun track(value: Float, current: () -> Float, set: (Float, String) -> Unit) {
            if (value > current()) set(value, "frame $frame")
        }

        fun assertWithin(label: String) {
            assertTrue("$label: carriage jumped $carriage at $carriageAt", carriage <= MAX_CARRIAGE)
            assertTrue("$label: yaw jumped $yaw rad at $yawAt", yaw <= MAX_YAW)
            assertTrue("$label: elbow jumped $elbow at $elbowAt", elbow <= MAX_JOINT)
            assertTrue("$label: gripper jumped $gripper at $gripperAt", gripper <= MAX_JOINT)
        }

        /** Lift a reach-plane joint into world space, the same way [SceneRenderer] does. */
        private inline fun world(
            pose: Robot3DPose,
            joint: (com.leumas.robot.render.Kinematics.ArmSolution) -> com.leumas.robot.render.Vec2,
        ): FloatArray {
            val p = joint(pose.arm)
            return floatArrayOf(
                pose.carriageX + p.x * sin(pose.baseYaw),
                Arena3D.SHOULDER_Y - p.y,
                p.x * cos(pose.baseYaw),
            )
        }

        private fun dist(a: FloatArray, b: FloatArray): Float =
            hypot(hypot(a[0] - b[0], a[1] - b[1]), a[2] - b[2])

        private fun shortestArc(from: Float, to: Float): Float {
            val twoPi = (2.0 * Math.PI).toFloat()
            var d = (to - from) % twoPi
            if (d > Math.PI) d -= twoPi
            if (d < -Math.PI) d += twoPi
            return d
        }
    }

    private companion object {
        const val FRAME_MS = 16L

        /*
         * Ceilings on one frame of movement at 60 fps.
         *
         * Sized a little above the fastest legitimate motion in the choreography — the carriage
         * crosses the full 2.9-unit rail in ~4 s, so ~0.024 units/frame at the peak of the ease —
         * and far below a glitch. An elbow mirroring across the base axis moves roughly a whole
         * lower-arm length (0.5) in a single frame, so it fails these by an order of magnitude.
         */
        const val MAX_CARRIAGE = 0.045f
        const val MAX_YAW = 0.060f
        const val MAX_JOINT = 0.090f
    }
}
