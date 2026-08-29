package com.leumas.robot.render

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Inverse kinematics for the 4-axis palletiser.
 *
 * The reference machine is a palletiser with a **parallelogram linkage**, not a general 6-axis
 * arm. The linkage mechanically holds the forearm and end effector horizontal in every pose. That
 * is the machine's signature look, and it also removes wrist pitch as a free variable: the arm
 * reduces to a 2-link planar chain whose wrist must land one forearm-length horizontally short of
 * the target.
 *
 * Axes: A1 base yaw (rendered as a turret squash, since a side elevation cannot show in-plane
 * rotation), A2 shoulder pitch, A3 elbow pitch, A4 wrist roll.
 */
object Kinematics {

    /** Keeps the solver off the exact singularities at full extension and full fold. */
    private const val EPSILON = 1e-3f

    /**
     * Solved arm geometry. Positions are absolute pixels so the renderer can draw straight from it.
     *
     * [reachClamped] is true when the requested target was outside the arm's annulus and the
     * solution was pulled to the nearest reachable point. For an off-screen module that is the
     * desired behaviour: the arm ends up fully extended, straining toward it.
     */
    data class ArmSolution(
        val shoulder: Vec2,
        val elbow: Vec2,
        val wrist: Vec2,
        val gripper: Vec2,
        val shoulderAngle: Float,
        val elbowInteriorAngle: Float,
        val reachClamped: Boolean,
    )

    /**
     * Solve the whole arm so the gripper lands on [target].
     *
     * [facing] is +1 when the machine works to its right, -1 to its left. Because the forearm is
     * held horizontal, the wrist target is simply the gripper target pulled back along X by one
     * forearm length.
     */
    fun solve(
        shoulder: Vec2,
        target: Vec2,
        lowerArm: Float,
        upperArm: Float,
        forearm: Float,
        facing: Float,
    ): ArmSolution {
        val wristTarget = Vec2(target.x - facing * forearm, target.y)
        val chain = solveTwoLink(shoulder, wristTarget, lowerArm, upperArm)
        val gripper = Vec2(chain.wrist.x + facing * forearm, chain.wrist.y)
        return ArmSolution(
            shoulder = shoulder,
            elbow = chain.elbow,
            wrist = chain.wrist,
            gripper = gripper,
            shoulderAngle = chain.shoulderAngle,
            elbowInteriorAngle = chain.elbowAngle,
            reachClamped = chain.clamped,
        )
    }

    private data class Chain(
        val elbow: Vec2,
        val wrist: Vec2,
        val shoulderAngle: Float,
        val elbowAngle: Float,
        val clamped: Boolean,
    )

    /**
     * Classic 2-link planar IK, elbow-up.
     *
     * Rather than reason about rotation sign in a y-down screen space (a reliable source of
     * mirror-image bugs), both elbow solutions are evaluated and the one sitting higher on screen
     * — smaller y — is chosen. That is unambiguously "elbow up" whatever the coordinate handedness.
     */
    private fun solveTwoLink(shoulder: Vec2, target: Vec2, l1: Float, l2: Float): Chain {
        val dx = target.x - shoulder.x
        val dy = target.y - shoulder.y
        val rawDistance = kotlin.math.hypot(dx, dy)

        val minReach = abs(l1 - l2) + EPSILON
        val maxReach = l1 + l2 - EPSILON
        val distance = rawDistance.coerceIn(minReach, maxReach)
        val clamped = rawDistance > maxReach || rawDistance < minReach

        val base = atan2(dy, dx)
        val cosAlpha = ((l1 * l1 + distance * distance - l2 * l2) / (2f * l1 * distance))
            .coerceIn(-1f, 1f)
        val alpha = acos(cosAlpha)
        val cosElbow = ((l1 * l1 + l2 * l2 - distance * distance) / (2f * l1 * l2))
            .coerceIn(-1f, 1f)
        val elbowInterior = acos(cosElbow)

        // The two solutions mirror about the shoulder-target line. The elbow bend must rotate
        // the OPPOSITE way to the shoulder offset, otherwise the mirrored branch puts the elbow
        // in the right place but throws the wrist off the target entirely.
        fun chainFor(sign: Float): Chain {
            val shoulderAngle = base + sign * alpha
            val elbow = Vec2(
                shoulder.x + cos(shoulderAngle) * l1,
                shoulder.y + sin(shoulderAngle) * l1,
            )
            val wristAngle = shoulderAngle - sign * (Math.PI.toFloat() - elbowInterior)
            val wrist = Vec2(
                elbow.x + cos(wristAngle) * l2,
                elbow.y + sin(wristAngle) * l2,
            )
            return Chain(elbow, wrist, shoulderAngle, elbowInterior, clamped)
        }

        val a = chainFor(-1f)
        val b = chainFor(1f)
        // Smaller y is higher on screen.
        return if (a.elbow.y <= b.elbow.y) a else b
    }

    /**
     * Horizontal squash used to render base yaw (A1) in a side elevation.
     *
     * `cos(yaw)` passes through zero at 90°, so the turret and its counterweight visually sweep
     * edge-on and then round again — which reads as rotation without needing a true 3D projection.
     */
    fun yawSquash(yawRadians: Float): Float = cos(yawRadians)
}
