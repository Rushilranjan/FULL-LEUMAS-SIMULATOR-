package com.leumas.robot.render3d

import com.leumas.robot.calibration.Calibration
import com.leumas.robot.domain.ActiveTransfer
import com.leumas.robot.domain.RobotAnimationState
import com.leumas.robot.domain.RobotPhase
import com.leumas.robot.render.Kinematics
import com.leumas.robot.render.Vec2
import com.leumas.robot.render.lerp
import kotlin.math.abs
import kotlin.math.pow

/**
 * A resolution-independent snapshot of the arm for the 3-D renderer.
 *
 * [arm] is solved in the reach plane (see [Arena3D]); the renderer lifts those planar joints into
 * world space by translating to [carriageX] and rotating by [baseYaw]. Because the joint positions
 * come from a real IK solve, the shoulder and elbow genuinely bend as the target moves.
 */
data class Robot3DPose(
    val carriageX: Float,
    val baseYaw: Float,
    val arm: Kinematics.ArmSolution,
    val gripperOpen: Float,
    val carrying: Boolean,
    val wristRoll: Float,
    val motion: Float,
) {
    companion object {
        /** Idle: parked at rail centre, facing the camera, arm stowed, gripper open. */
        fun idle(carriageX: Float = Arena3D.HOME_X): Robot3DPose = Robot3DPose(
            carriageX = carriageX,
            baseYaw = 0f,
            arm = solve(Arena3D.STOW),
            gripperOpen = 1f,
            carrying = false,
            wristRoll = 0f,
            motion = 0f,
        )

        internal fun solve(target: Vec2): Kinematics.ArmSolution = Kinematics.solve(
            shoulder = Vec2.ZERO,
            target = target,
            lowerArm = Arena3D.LOWER_ARM,
            upperArm = Arena3D.UPPER_ARM,
            forearm = Arena3D.FOREARM,
            facing = 1f,
        )
    }
}

/**
 * Turns the shared [RobotAnimationState] into a [Robot3DPose].
 *
 * Mirrors the phase choreography of the 2-D [com.leumas.robot.render.RobotPoser] — the same eight
 * transfer phases plus the new [RobotPhase.RETURNING_HOME] — but emits world-plane joint geometry
 * instead of pixels. Stateful only for the carriage start position (so a transfer beginning while
 * parked elsewhere travels smoothly rather than snapping — spec §10) and for motion smoothing.
 */
class Robot3DPoser {

    private var lastCarriageX: Float = Arena3D.HOME_X
    private var activeTransfer: ActiveTransfer? = null
    private var transferStartX: Float = Arena3D.HOME_X
    private var smoothedMotion: Float = 0f

    fun reset() {
        lastCarriageX = Arena3D.HOME_X
        activeTransfer = null
        smoothedMotion = 0f
    }

    fun pose(state: RobotAnimationState, calibration: Calibration): Robot3DPose {
        val previousX = lastCarriageX
        val transfer = state.transfer
        val phase = state.phase

        if (transfer == null || phase == null) {
            activeTransfer = null
            return finish(Robot3DPose.idle(lastCarriageX), previousX)
        }

        if (transfer != activeTransfer) {
            activeTransfer = transfer
            transferStartX = lastCarriageX
        }

        val srcPoint = calibration.pointFor(transfer.from)
        val dstPoint = calibration.pointFor(transfer.to)
        val srcX = Arena3D.carriageXFor(srcPoint)
        val dstX = Arena3D.carriageXFor(dstPoint)
        val srcYaw = Arena3D.yawFor(srcPoint)
        val dstYaw = Arena3D.yawFor(dstPoint)
        val srcReach = Arena3D.reachTarget(srcPoint)
        val dstReach = Arena3D.reachTarget(dstPoint)

        val travel = easeInOutCubic(state.phaseProgress)
        val settle = easeOutSettle(state.phaseProgress)

        val carriageX = when (phase) {
            RobotPhase.MOVING_TO_SOURCE -> lerp(transferStartX, srcX, travel)
            RobotPhase.MOVING_TO_DESTINATION -> lerp(srcX, dstX, travel)
            RobotPhase.POSITIONING_AT_SOURCE, RobotPhase.PICKING, RobotPhase.LIFTING -> srcX
            RobotPhase.RETURNING_HOME -> lerp(dstX, Arena3D.HOME_X, travel)
            else -> dstX
        }
        lastCarriageX = carriageX

        // Change 11.3: the base turret now rotates *during* rail travel, not in a separate spin once
        // parked. Turning toward the destination across the whole MOVING_TO_DESTINATION leg (rather
        // than holding srcYaw and snapping 180° at POSITIONING_AT_DESTINATION) removes the "travel
        // facing backwards, then whip around" glitch that was worst on LEFT<->RIGHT transfers. All
        // yaw blends go through [lerpAngle] so they always take the shortest arc.
        val baseYaw = when (phase) {
            RobotPhase.MOVING_TO_SOURCE -> lerpAngle(0f, srcYaw, travel)
            RobotPhase.POSITIONING_AT_SOURCE, RobotPhase.PICKING, RobotPhase.LIFTING -> srcYaw
            RobotPhase.MOVING_TO_DESTINATION -> lerpAngle(srcYaw, dstYaw, travel)
            RobotPhase.POSITIONING_AT_DESTINATION -> dstYaw
            RobotPhase.RETURNING_HOME -> lerpAngle(dstYaw, 0f, travel)
            else -> dstYaw
        }

        val armTarget = when (phase) {
            RobotPhase.MOVING_TO_SOURCE, RobotPhase.POSITIONING_AT_SOURCE -> Arena3D.STOW
            RobotPhase.PICKING -> lerp(Arena3D.STOW, srcReach, settle)
            RobotPhase.LIFTING -> lerp(srcReach, Arena3D.CARRY, travel)
            RobotPhase.MOVING_TO_DESTINATION, RobotPhase.POSITIONING_AT_DESTINATION -> Arena3D.CARRY
            RobotPhase.PLACING -> lerp(Arena3D.CARRY, dstReach, settle)
            RobotPhase.RETRACTING -> lerp(dstReach, Arena3D.STOW, travel)
            RobotPhase.RETURNING_HOME -> Arena3D.STOW
        }

        val gripperOpen = when (phase) {
            RobotPhase.PICKING -> if (state.phaseProgress > 0.6f) 0f else 1f
            RobotPhase.LIFTING,
            RobotPhase.MOVING_TO_DESTINATION,
            RobotPhase.POSITIONING_AT_DESTINATION -> 0f
            RobotPhase.PLACING -> if (state.phaseProgress < 0.6f) 0f else 1f
            else -> 1f
        }

        // A visible wrist roll: twist in to engage while picking, hold through the carry, untwist
        // as it places. This is the A4 axis the reference machine rolls the end effector on.
        val wristRoll = when (phase) {
            RobotPhase.PICKING -> lerp(0f, -WRIST_ROLL, settle)
            RobotPhase.LIFTING,
            RobotPhase.MOVING_TO_DESTINATION,
            RobotPhase.POSITIONING_AT_DESTINATION -> -WRIST_ROLL
            RobotPhase.PLACING -> lerp(-WRIST_ROLL, 0f, settle)
            else -> 0f
        }

        return finish(
            Robot3DPose(
                carriageX = carriageX,
                baseYaw = baseYaw,
                arm = Robot3DPose.solve(armTarget),
                gripperOpen = gripperOpen,
                carrying = state.carryingPayload,
                wristRoll = wristRoll,
                motion = 0f,
            ),
            previousX,
        )
    }

    private fun finish(pose: Robot3DPose, previousX: Float): Robot3DPose {
        val raw = (abs(pose.carriageX - previousX) / 0.04f).coerceIn(0f, 1f)
        smoothedMotion = if (raw > smoothedMotion) lerp(smoothedMotion, raw, 0.5f) else lerp(smoothedMotion, raw, 0.1f)
        return pose.copy(motion = smoothedMotion)
    }

    /**
     * Interpolate between two angles (radians) along the shortest arc, so a blend never sweeps the
     * long way round when the raw difference exceeds π. With the current edge yaws (±π/2, 0) every
     * arc is already ≤ π, but routing through here keeps the turret glitch-free if calibration ever
     * yields other facings.
     */
    private fun lerpAngle(from: Float, to: Float, t: Float): Float {
        val twoPi = (2.0 * Math.PI).toFloat()
        var delta = (to - from) % twoPi
        if (delta > Math.PI) delta -= twoPi
        if (delta < -Math.PI) delta += twoPi
        return from + delta * t
    }

    private fun easeInOutCubic(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f

    private fun easeOutSettle(t: Float): Float {
        val c1 = 0.9f
        val c3 = c1 + 1f
        val u = t - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }

    private companion object {
        const val WRIST_ROLL = 0.9f
    }
}
