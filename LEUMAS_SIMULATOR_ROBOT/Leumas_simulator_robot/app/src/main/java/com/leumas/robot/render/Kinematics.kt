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
        // Elbow-up for this facing. See solveTwoLink: the sign is fixed rather than chosen, which
        // is what makes the pose continuous.
        val chain = solveTwoLink(shoulder, wristTarget, lowerArm, upperArm, elbowSign = -facing)
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
     * Classic 2-link planar IK.
     *
     * [elbowSign] selects which of the two mirror solutions to use, and is **fixed by the caller**
     * rather than picked per-frame. The elbow's offset from the shoulder-to-target line works out to
     * `-l1 * elbowSign * sin(alpha)`, so a constant sign puts the elbow consistently on one side of
     * that line no matter where the target moves — the definition of "elbow up" for this machine,
     * and continuous by construction.
     *
     * **Change 13.3 — the arm-rotation glitch.** The previous implementation instead solved both
     * branches and kept whichever elbow sat higher on screen. That is the same answer almost
     * everywhere, but it is a comparison against a world axis rather than against the arm's own
     * line, and it works out to `sign = -sign(dx)`: the branch flipped the instant the wrist target
     * crossed the base axis, mirroring the elbow from one side to the other in a single frame. That
     * was not a theoretical worry — the old stow pose put the gripper offset and the forearm length
     * at the same value, so the wrist target sat *exactly* on the axis and the elbow snapped every
     * time the arm unfolded. Fixing the sign removes the flip entirely; the pose stays continuous
     * through the axis because `atan2` is.
     */
    private fun solveTwoLink(
        shoulder: Vec2,
        target: Vec2,
        l1: Float,
        l2: Float,
        elbowSign: Float,
    ): Chain {
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

        // The elbow bend must rotate the OPPOSITE way to the shoulder offset, otherwise the
        // mirrored branch puts the elbow in the right place but throws the wrist off the target.
        val sign = if (elbowSign >= 0f) 1f else -1f
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

    /**
     * Horizontal squash used to render base yaw (A1) in a side elevation.
     *
     * `cos(yaw)` passes through zero at 90°, so the turret and its counterweight visually sweep
     * edge-on and then round again — which reads as rotation without needing a true 3D projection.
     */
    fun yawSquash(yawRadians: Float): Float = cos(yawRadians)
}
