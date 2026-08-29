package com.leumas.robot.render

import com.leumas.robot.calibration.Calibration
import com.leumas.robot.domain.ActiveTransfer
import com.leumas.robot.domain.RobotAnimationState
import com.leumas.robot.domain.RobotPhase
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

/** Everything the artist needs to draw one frame of the machine. */
data class RobotPose(
    val carriageX: Float,
    val yaw: Float,
    /** `cos(yaw)`: horizontal squash of the turret group; sign gives the facing direction. */
    val yawSquash: Float,
    val arm: Kinematics.ArmSolution,
    /** 0 = fully closed on a payload, 1 = fully open. */
    val gripperOpen: Float,
    val carrying: Boolean,
    /** Signed carriage travel this frame, in pixels. Drives motion streaks. */
    val carriageVelocity: Float = 0f,
    /** 0..1 overall movement intensity. Drives cable sway and blur. */
    val motion: Float = 0f,
) {
    val facing: Float get() = if (yawSquash >= 0f) 1f else -1f
}

private fun easeInOutCubic(t: Float): Float =
    if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f

/**
 * Gentle overshoot-and-settle, like a servo arriving on target.
 *
 * Returns exactly 1.0 at t = 1, so full-reach positions are unaffected — the overshoot happens
 * *during* the move, never at its end.
 */
private fun easeOutSettle(t: Float): Float {
    val c1 = 0.9f
    val c3 = c1 + 1f
    val u = t - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}

/**
 * Turns an [RobotAnimationState] into concrete arm geometry.
 *
 * Stateful in two respects: it remembers where the carriage was, so a transfer beginning while the
 * machine is parked elsewhere travels smoothly from where it actually is (without that, chained
 * transfers would snap back to centre — a teleport, which spec §10 forbids); and it tracks
 * frame-to-frame movement so the artist can add motion cues.
 *
 * Stow and carry poses are expressed **relative to the shoulder** so the arm rides along with the
 * carriage; reach targets are absolute, because they are fixed points in the room.
 */
class RobotPoser {

    private var lastCarriageX: Float = Float.NaN
    private var lastGripper: Vec2? = null
    private var activeTransfer: ActiveTransfer? = null
    private var transferStartX: Float = Float.NaN
    private var smoothedMotion: Float = 0f

    fun pose(
        state: RobotAnimationState,
        calibration: Calibration,
        geometry: ArenaGeometry,
    ): RobotPose {
        if (lastCarriageX.isNaN()) lastCarriageX = geometry.railCentreX
        val previousCarriageX = lastCarriageX

        val transfer = state.transfer
        val phase = state.phase

        if (transfer == null || phase == null) {
            activeTransfer = null
            return finish(
                stowedPose(lastCarriageX, yaw = 0f, geometry, gripperOpen = 1f, carrying = false),
                previousCarriageX,
                geometry,
            )
        }

        if (transfer != activeTransfer) {
            activeTransfer = transfer
            transferStartX = lastCarriageX
        }

        val srcPoint = calibration.pointFor(transfer.from)
        val dstPoint = calibration.pointFor(transfer.to)
        val srcX = geometry.carriageXFor(srcPoint)
        val dstX = geometry.carriageXFor(dstPoint)
        val srcReach = geometry.overshoot(srcPoint)
        val dstReach = geometry.overshoot(dstPoint)

        // Travel eases in and out; reaching settles onto target like a servo.
        val travel = easeInOutCubic(state.phaseProgress)
        val settle = easeOutSettle(state.phaseProgress)

        val carriageX = when (phase) {
            RobotPhase.MOVING_TO_SOURCE -> lerp(transferStartX, srcX, travel)
            RobotPhase.MOVING_TO_DESTINATION -> lerp(srcX, dstX, travel)
            RobotPhase.POSITIONING_AT_SOURCE, RobotPhase.PICKING, RobotPhase.LIFTING -> srcX
            // Change 10.2: travel back to the middle of the rail once the payload is placed.
            RobotPhase.RETURNING_HOME -> lerp(dstX, geometry.railCentreX, travel)
            else -> dstX
        }
        lastCarriageX = carriageX

        val srcYaw = yawFacing(srcReach.x - srcX)
        val dstYaw = yawFacing(dstReach.x - dstX)
        val yaw = when (phase) {
            RobotPhase.MOVING_TO_SOURCE -> lerp(0f, srcYaw, travel)
            RobotPhase.POSITIONING_AT_SOURCE -> srcYaw
            RobotPhase.MOVING_TO_DESTINATION -> srcYaw
            RobotPhase.POSITIONING_AT_DESTINATION -> lerp(srcYaw, dstYaw, travel)
            RobotPhase.PICKING, RobotPhase.LIFTING -> srcYaw
            // Unwind the turret back to its neutral facing as it homes.
            RobotPhase.RETURNING_HOME -> lerp(dstYaw, 0f, travel)
            else -> dstYaw
        }

        val shoulder = geometry.shoulderAt(carriageX)
        val squash = cos(yaw)
        val facing = if (squash >= 0f) 1f else -1f
        val stow = stowTarget(shoulder, facing, geometry)
        val carry = carryTarget(shoulder, facing, geometry)

        val armTarget = when (phase) {
            RobotPhase.MOVING_TO_SOURCE, RobotPhase.POSITIONING_AT_SOURCE -> stow
            RobotPhase.PICKING -> lerp(stow, srcReach, settle)
            RobotPhase.LIFTING -> lerp(srcReach, carry, travel)
            RobotPhase.MOVING_TO_DESTINATION, RobotPhase.POSITIONING_AT_DESTINATION -> carry
            RobotPhase.PLACING -> lerp(carry, dstReach, settle)
            RobotPhase.RETRACTING -> lerp(dstReach, stow, travel)
            // Already stowed; the arm simply rides the carriage back to centre.
            RobotPhase.RETURNING_HOME -> stow
        }

        // The gripper closes at the end of PICKING and opens partway through PLACING, matching
        // RobotStateMachine's payload window.
        val gripperOpen = when (phase) {
            RobotPhase.PICKING -> if (state.phaseProgress > 0.6f) 0f else 1f
            RobotPhase.LIFTING,
            RobotPhase.MOVING_TO_DESTINATION,
            RobotPhase.POSITIONING_AT_DESTINATION -> 0f
            RobotPhase.PLACING -> if (state.phaseProgress < 0.6f) 0f else 1f
            else -> 1f
        }

        return finish(
            RobotPose(
                carriageX = carriageX,
                yaw = yaw,
                yawSquash = squash,
                arm = solveArm(shoulder, armTarget, facing, geometry),
                gripperOpen = gripperOpen,
                carrying = state.carryingPayload,
            ),
            previousCarriageX,
            geometry,
        )
    }

    /** Attach frame-to-frame motion, smoothed so streaks fade rather than flicker. */
    private fun finish(pose: RobotPose, previousCarriageX: Float, geometry: ArenaGeometry): RobotPose {
        val carriageDelta = pose.carriageX - previousCarriageX
        val gripperDelta = lastGripper?.let { pose.arm.gripper.distanceTo(it) } ?: 0f
        lastGripper = pose.arm.gripper

        val raw = ((abs(carriageDelta) + gripperDelta) / (geometry.heightPx * 0.020f))
            .coerceIn(0f, 1f)
        // Asymmetric smoothing: rise quickly, decay slowly, so trails linger a little.
        smoothedMotion = if (raw > smoothedMotion) {
            lerp(smoothedMotion, raw, 0.55f)
        } else {
            lerp(smoothedMotion, raw, 0.12f)
        }

        return pose.copy(carriageVelocity = carriageDelta, motion = smoothedMotion)
    }

    /** Reset on a hard sequence change so the next transfer starts from a known place. */
    fun reset(geometry: ArenaGeometry) {
        lastCarriageX = geometry.railCentreX
        lastGripper = null
        smoothedMotion = 0f
        activeTransfer = null
    }

    private fun stowedPose(
        carriageX: Float,
        yaw: Float,
        geometry: ArenaGeometry,
        gripperOpen: Float,
        carrying: Boolean,
    ): RobotPose {
        val shoulder = geometry.shoulderAt(carriageX)
        val squash = cos(yaw)
        val facing = if (squash >= 0f) 1f else -1f
        return RobotPose(
            carriageX = carriageX,
            yaw = yaw,
            yawSquash = squash,
            arm = solveArm(shoulder, stowTarget(shoulder, facing, geometry), facing, geometry),
            gripperOpen = gripperOpen,
            carrying = carrying,
        )
    }

    private fun solveArm(
        shoulder: Vec2,
        target: Vec2,
        facing: Float,
        geometry: ArenaGeometry,
    ) = Kinematics.solve(
        shoulder = shoulder,
        target = target,
        lowerArm = geometry.lowerArmLength,
        upperArm = geometry.upperArmLength,
        forearm = geometry.forearmLength,
        facing = facing,
    )

    private fun yawFacing(dx: Float): Float = if (dx >= 0f) 0f else Math.PI.toFloat()

    /** Folded, upright parking pose. */
    private fun stowTarget(shoulder: Vec2, facing: Float, g: ArenaGeometry) = Vec2(
        shoulder.x + facing * g.heightPx * 0.11f,
        shoulder.y - g.heightPx * 0.21f,
    )

    /** Arm out and slightly lowered, holding a payload clear of the base. */
    private fun carryTarget(shoulder: Vec2, facing: Float, g: ArenaGeometry) = Vec2(
        shoulder.x + facing * g.heightPx * 0.20f,
        shoulder.y - g.heightPx * 0.15f,
    )
}
