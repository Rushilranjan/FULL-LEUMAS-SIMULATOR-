package com.leumas.robot.render3d

import com.leumas.robot.calibration.Calibration
import com.leumas.robot.domain.ActiveTransfer
import com.leumas.robot.domain.RobotAnimationState
import com.leumas.robot.domain.RobotPhase
import com.leumas.robot.protocol.ProductionLine
import com.leumas.robot.protocol.SlotMap
import com.leumas.robot.render.Kinematics
import com.leumas.robot.render.Vec2
import com.leumas.robot.render.lerp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow

/** Which kind of material the bucket is handling. Decides powder vs liquid visuals (change 13.8). */
enum class MaterialKind { SOLID, LIQUID }

/**
 * What the bucket on the end effector is doing this frame (change 13.8).
 *
 * [fill] is how full the vessel is, [stream] how strongly material is flowing, and [pouring] which
 * way it flows — into the vessel while collecting at the source, out through the dispenser while
 * discharging at the destination.
 */
data class Payload(
    val kind: MaterialKind = MaterialKind.SOLID,
    val fill: Float = 0f,
    val stream: Float = 0f,
    val pouring: Boolean = false,
    /**
     * Dispenser rotor angle in degrees (change 15.1).
     *
     * Replaces the old `tilt`. The vessel is fed by a pump now and never tips: liquid leaves through
     * a peristaltic head and solids through a screw auger, both driven by this angle. It is
     * integrated over real time while material is actually flowing, so the rotor turns only when it
     * is doing work and never jumps when the flow rate changes.
     */
    val pumpAngle: Float = 0f,
) {
    companion object {
        val EMPTY = Payload()
    }
}

/**
 * The station the arm is working at, or driving on to (change 25.3).
 *
 * The renderer needs a **fixed** anchor to hang the module's pipework off, and the live pose cannot
 * give it one -- the arm is moving. This carries the station's own carriage position, facing and
 * reach target, all of which hold still for as long as the arm is there, so a fixture drawn from
 * them stays put while the machine drives onto it.
 *
 * They are also the exact numbers the poser steers the arm toward, and that is what makes the tool
 * end up *in* the pipe rather than near it: dock and target are the same point by construction
 * rather than by tuning.
 */
data class Dock3D(
    val carriageX: Float,
    val yaw: Float,
    val reach: Vec2,
    /** Faded 0..1, so the fixture never pops in or blinks out from under the tool. */
    val presence: Float,
)

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
    val payload: Payload = Payload.EMPTY,
    val dock: Dock3D? = null,
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
 * transfer phases plus [RobotPhase.RETURNING_HOME] — but emits world-plane joint geometry instead
 * of pixels.
 *
 * **Change 13.3 — smoothing.** The phase table gives each leg its own easing, and every easing
 * starts and ends at rest, but the *targets* still change identity at a phase boundary. Feeding the
 * raw per-phase values straight into the IK therefore put a kink into the motion at each of the
 * nine boundaries, which is what "there is always a small glitch when the robot tries to move and
 * rotate its arm" was describing. The carriage station, base yaw and reach-plane arm target now go
 * through a frame-rate-independent first-order filter before the arm is solved, so the machine
 * rounds every corner instead of stepping through it and the gripper sweeps a clean arc into and
 * out of each module. The time constant ([TAU_MS]) is short enough that the arm still arrives on
 * target well inside its phase.
 */
class Robot3DPoser {

    private var lastCarriageX: Float = Arena3D.HOME_X
    private var activeTransfer: ActiveTransfer? = null
    private var transferStartX: Float = Arena3D.HOME_X
    private var smoothedMotion: Float = 0f

    // Dispenser rotor angle, integrated while material is flowing (change 15.1).
    private var pumpAngle: Float = 0f

    // Filter state. Null until the first frame, so the machine starts exactly where it is asked to
    // rather than easing in from an arbitrary origin.
    private var smoothCarriageX: Float? = null
    private var smoothYaw: Float? = null
    private var smoothTarget: Vec2? = null
    private var lastFrameMs: Long = UNSET

    /**
     * Abandon the current transfer.
     *
     * Deliberately does **not** reset the carriage or the filter state (change 13.3): it used to
     * slam `lastCarriageX` back to [Arena3D.HOME_X], so pressing the button mid-run teleported the
     * machine to rail centre. Now the idle target is home and the filter walks it there.
     */
    fun reset() {
        activeTransfer = null
        smoothedMotion = 0f
        pumpAngle = 0f
    }

    fun pose(state: RobotAnimationState, calibration: Calibration, nowMs: Long): Robot3DPose {
        val dtMs = if (lastFrameMs == UNSET) 0L else (nowMs - lastFrameMs).coerceIn(0L, MAX_DT_MS)
        lastFrameMs = nowMs

        val previousX = lastCarriageX
        val transfer = state.transfer
        val phase = state.phase

        // The dispenser only turns while it is actually moving material, and it advances by real
        // elapsed time rather than by phase progress, so the rotor speed stays constant regardless
        // of how the choreography is retimed.
        val flowing = if (phase == RobotPhase.PLACING) state.let { pouringStream(it) } else 0f
        pumpAngle = (pumpAngle + flowing * PUMP_DEG_PER_SEC * dtMs / 1000f) % 360f

        if (transfer == null || phase == null) {
            activeTransfer = null
            // Idle drifts back to rail centre rather than parking wherever the last cue left it, so
            // an aborted transfer resolves into the home pose instead of freezing off-centre.
            return emit(
                carriageX = smoothCarriage(Arena3D.HOME_X, dtMs),
                yaw = smoothAngle(Arena3D.HOME_YAW, dtMs),
                armTarget = smoothVec(Arena3D.STOW, dtMs),
                gripperOpen = 1f,
                wristRoll = 0f,
                carrying = false,
                payload = Payload.EMPTY,
                previousX = previousX,
            )
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

        // Change 15.3: one jerk-limited profile drives every axis in a phase. The carriage used to
        // run easeInOutCubic while the arm ran easeInOutSine, so the axes neither started nor
        // stopped together; a real controller puts them all on one time base so the machine arrives
        // as a unit. See [sCurve] for why those two easings were the wrong shape in the first place.
        val motion = sCurve(state.phaseProgress)

        val rawCarriageX = when (phase) {
            RobotPhase.MOVING_TO_SOURCE -> lerp(transferStartX, srcX, motion)
            RobotPhase.MOVING_TO_DESTINATION -> lerp(srcX, dstX, motion)
            RobotPhase.POSITIONING_AT_SOURCE, RobotPhase.PICKING, RobotPhase.LIFTING -> srcX
            RobotPhase.RETURNING_HOME -> lerp(dstX, Arena3D.HOME_X, motion)
            else -> dstX
        }

        // Change 11.3: the base turret rotates *during* rail travel, not in a separate spin once
        // parked. Turning toward the destination across the whole MOVING_TO_DESTINATION leg removes
        // the "travel facing backwards, then whip around" glitch. All yaw blends go through
        // [lerpAngle] so they always take the shortest arc.
        val rawYaw = when (phase) {
            RobotPhase.MOVING_TO_SOURCE -> lerpAngle(Arena3D.HOME_YAW, srcYaw, motion)
            RobotPhase.POSITIONING_AT_SOURCE, RobotPhase.PICKING, RobotPhase.LIFTING -> srcYaw
            RobotPhase.MOVING_TO_DESTINATION -> lerpAngle(srcYaw, dstYaw, motion)
            RobotPhase.POSITIONING_AT_DESTINATION -> dstYaw
            RobotPhase.RETURNING_HOME -> lerpAngle(dstYaw, Arena3D.HOME_YAW, motion)
            else -> dstYaw
        }

        // The reach legs use a symmetric ease rather than the old back-ease: overshooting a target
        // that already sits near the edge of the arm envelope drove the solver against full
        // extension, and the arm bounced off its own limit at the end of every reach.
        // Change 15.2: every reach is a three-coordinate move through an approach point, with the
        // arm coming fully to rest at the via point before the final straight-in segment.
        val srcApproach = Arena3D.approachPoint(srcReach)
        val dstApproach = Arena3D.approachPoint(dstReach)

        // Change 25.3: the module's pipework belongs to the STATION, not to the arm, so it is
        // anchored to the station's own carriage, facing and target rather than to the live pose.
        // Faded in across the positioning move and out across the retract, so the fitting is
        // already standing there when the tool arrives.
        val dock = when (phase) {
            RobotPhase.POSITIONING_AT_SOURCE -> Dock3D(srcX, srcYaw, srcReach, motion)
            RobotPhase.PICKING -> Dock3D(srcX, srcYaw, srcReach, 1f)
            RobotPhase.LIFTING -> Dock3D(srcX, srcYaw, srcReach, 1f - motion)
            RobotPhase.POSITIONING_AT_DESTINATION -> Dock3D(dstX, dstYaw, dstReach, motion)
            RobotPhase.PLACING -> Dock3D(dstX, dstYaw, dstReach, 1f)
            RobotPhase.RETRACTING -> Dock3D(dstX, dstYaw, dstReach, 1f - motion)
            else -> null
        }

        val rawTarget = when (phase) {
            RobotPhase.MOVING_TO_SOURCE -> Arena3D.STOW
            // Travel to the approach point during POSITIONING, so the arm is already standing off
            // the module and settled before the pick phase starts.
            RobotPhase.POSITIONING_AT_SOURCE -> arc(Arena3D.STOW, srcApproach, motion)
            RobotPhase.PICKING -> approachPath(srcApproach, srcReach, state.phaseProgress)
            RobotPhase.LIFTING -> retractPath(srcReach, srcApproach, Arena3D.CARRY, state.phaseProgress)
            RobotPhase.MOVING_TO_DESTINATION -> Arena3D.CARRY
            RobotPhase.POSITIONING_AT_DESTINATION -> arc(Arena3D.CARRY, dstApproach, motion)
            RobotPhase.PLACING -> approachPath(dstApproach, dstReach, state.phaseProgress)
            RobotPhase.RETRACTING -> retractPath(dstReach, dstApproach, Arena3D.STOW, state.phaseProgress)
            RobotPhase.RETURNING_HOME -> Arena3D.STOW
        }

        // The bucket is gripped for the whole working part of the trip, so the fingers are not
        // flapping through the long collect and pour.
        val gripperOpen = when (phase) {
            RobotPhase.PICKING -> 1f - smoothstep(state.phaseProgress / 0.12f)
            RobotPhase.LIFTING,
            RobotPhase.MOVING_TO_DESTINATION,
            RobotPhase.POSITIONING_AT_DESTINATION -> 0f
            RobotPhase.PLACING -> smoothstep((state.phaseProgress - 0.88f) / 0.12f)
            else -> 1f
        }

        // A visible wrist roll: twist in to engage while picking, hold through the carry, untwist
        // as it places. This is the A4 axis the reference machine rolls the end effector on.
        val wristRoll = when (phase) {
            RobotPhase.PICKING -> lerp(0f, -WRIST_ROLL, motion)
            RobotPhase.LIFTING,
            RobotPhase.MOVING_TO_DESTINATION,
            RobotPhase.POSITIONING_AT_DESTINATION -> -WRIST_ROLL
            RobotPhase.PLACING -> lerp(-WRIST_ROLL, 0f, motion)
            else -> 0f
        }

        return emit(
            carriageX = smoothCarriage(rawCarriageX, dtMs),
            yaw = smoothAngle(rawYaw, dtMs),
            armTarget = smoothVec(rawTarget, dtMs),
            gripperOpen = gripperOpen,
            wristRoll = wristRoll,
            carrying = state.carryingPayload,
            payload = payloadFor(phase, state.phaseProgress, transfer),
            dock = dock,
            previousX = previousX,
        )
    }

    // -- Payload ---------------------------------------------------------------------------------

    /**
     * Bucket contents for this frame (change 13.8).
     *
     * The material kind comes from the source module production line — the solid line carries
     * powder, the liquid line carries liquid. That is static rig knowledge from [SlotMap], not
     * invented process state: the MCU still decides *when* anything happens.
     */
    private fun payloadFor(phase: RobotPhase, progress: Float, transfer: ActiveTransfer): Payload {
        val kind = when (SlotMap.specOrNull(transfer.from)?.line) {
            ProductionLine.LIQUID -> MaterialKind.LIQUID
            else -> MaterialKind.SOLID
        }
        return when (phase) {
            // Collection runs across the middle of the phase, after the arm has arrived and before
            // it starts to lift, so the bucket fills while the machine is visibly still.
            RobotPhase.PICKING -> Payload(
                kind = kind,
                fill = smoothstep((progress - FLOW_START) / (FLOW_END - FLOW_START)),
                stream = flowEnvelope(progress),
                pouring = false,
            )
            RobotPhase.LIFTING,
            RobotPhase.MOVING_TO_DESTINATION,
            RobotPhase.POSITIONING_AT_DESTINATION -> Payload(kind, fill = 1f)
            RobotPhase.PLACING -> Payload(
                kind = kind,
                fill = 1f - smoothstep((progress - FLOW_START) / (FLOW_END - FLOW_START)),
                stream = flowEnvelope(progress),
                pouring = true,
                pumpAngle = pumpAngle,
            )
            else -> Payload(kind)
        }
    }

    /**
     * Flow rate during a dispense, used to drive the rotor. Zero in every other phase, so the
     * dispenser is stationary whenever it is not discharging.
     */
    private fun pouringStream(state: RobotAnimationState): Float =
        if (state.phase == RobotPhase.PLACING) flowEnvelope(state.phaseProgress) else 0f

    /** Bell over the flowing part of a collect/dispense phase, easing the stream on and off. */
    private fun flowEnvelope(progress: Float): Float {
        val t = ((progress - FLOW_START) / (FLOW_END - FLOW_START)).coerceIn(0f, 1f)
        return smoothstep(t / 0.18f) * smoothstep((1f - t) / 0.18f)
    }

    // -- Smoothing -------------------------------------------------------------------------------

    /**
     * First-order lag with a frame-rate-independent coefficient.
     *
     * `1 - exp(-dt/tau)` rather than a fixed per-frame fraction, so the machine behaves the same on
     * a 60 Hz and a 120 Hz panel and does not lurch when a frame is dropped.
     */
    private fun blend(dtMs: Long): Float =
        if (dtMs <= 0L) 1f else 1f - exp(-dtMs.toFloat() / TAU_MS)

    private fun smoothCarriage(target: Float, dtMs: Long): Float {
        val current = smoothCarriageX
        val next = if (current == null) target else current + (target - current) * blend(dtMs)
        smoothCarriageX = next
        return next
    }

    private fun smoothAngle(target: Float, dtMs: Long): Float {
        val current = smoothYaw
        val next = if (current == null) target else lerpAngle(current, target, blend(dtMs))
        smoothYaw = next
        return next
    }

    private fun smoothVec(target: Vec2, dtMs: Long): Vec2 {
        val current = smoothTarget
        val next = if (current == null) target else lerp(current, target, blend(dtMs))
        smoothTarget = next
        return next
    }

    private fun emit(
        carriageX: Float,
        yaw: Float,
        armTarget: Vec2,
        gripperOpen: Float,
        wristRoll: Float,
        carrying: Boolean,
        payload: Payload,
        previousX: Float,
        dock: Dock3D? = null,
    ): Robot3DPose {
        lastCarriageX = carriageX
        val raw = (abs(carriageX - previousX) / 0.04f).coerceIn(0f, 1f)
        smoothedMotion = if (raw > smoothedMotion) {
            lerp(smoothedMotion, raw, 0.5f)
        } else {
            lerp(smoothedMotion, raw, 0.1f)
        }
        return Robot3DPose(
            carriageX = carriageX,
            baseYaw = yaw,
            // Solve *after* smoothing, so the joints follow a smoothed target rather than being
            // smoothed themselves — the segment lengths stay exactly rigid.
            arm = Robot3DPose.solve(Arena3D.clampToReach(armTarget)),
            gripperOpen = gripperOpen,
            carrying = carrying,
            wristRoll = wristRoll,
            motion = smoothedMotion,
            payload = payload,
            dock = dock,
        )
    }

    /**
     * Interpolate between two angles (radians) along the shortest arc, so a blend never sweeps the
     * long way round when the raw difference exceeds π.
     */
    private fun lerpAngle(from: Float, to: Float, t: Float): Float {
        val twoPi = (2.0 * Math.PI).toFloat()
        var delta = (to - from) % twoPi
        if (delta > Math.PI) delta -= twoPi
        if (delta < -Math.PI) delta += twoPi
        return from + delta * t
    }

    /**
     * Jerk-limited S-curve (quintic smootherstep), the single motion profile for every axis.
     *
     * **Change 15.3 — the physics behind "the movement looks unnatural".** The two easings this
     * replaces are both wrong for a servo-driven machine, measurably so:
     *
     *  - `easeInOutCubic` (was driving the carriage and the base yaw) has acceleration stepping from
     *    +12 to -12 across its midpoint. A step in acceleration is infinite jerk.
     *  - `easeInOutSine` (was driving the arm and wrist) starts and ends with acceleration at
     *    +/-4.93 rather than zero, so the machine slams from rest into full acceleration and back.
     *
     * Neither is something a physical axis can execute; real controllers use a jerk-limited profile
     * precisely to avoid it. This quintic satisfies f(0)=0, f(1)=1, f'(0)=f'(1)=0 **and**
     * f''(0)=f''(1)=0, so velocity and acceleration both ease in and out from rest. That C2
     * continuity is also what lets the three-coordinate path stop at a via point and set off again
     * without a kick. See MotionProfileTest.
     */
    private fun sCurve(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return u * u * u * (u * (u * 6f - 15f) + 10f)
    }

    /**
     * Drive in to a module: one continuous, gently bowed move from the approach point onto the
     * target.
     *
     * **Change 25.4 made this an arc, and gave it more of the phase.** It used to be a straight
     * lerp along the reach axis, justified as how an industrial machine enters a station. That is
     * true of the *axis* and says nothing about the *path*: driving straight in with the tool
     * already at working height is a stab, and it is what "make the descent more graceful" is
     * about. Bowing the segment slightly above its own chord lifts the tool a little off the
     * approach point, carries it over the station and sets it down -- the curve's final tangent
     * points at the target from above, so the last thing the arm does is lower itself onto it.
     *
     * [REACH_RAMP] also grew, so the same move now takes about a third longer.
     *
     * **Change 19.1 removed the hold at the approach point.** 15.2 asked for a pause at each
     * coordinate and got a literal one: a third of the drive-in window was spent standing still,
     * and with the same distance crammed into the rest the move also had to be faster. It read as
     * stutter-then-lunge. Real controllers blend through a via point rather than stopping at it.
     *
     * Progress is scaled by [REACH_RAMP] and clamped, so the arm is fully settled well before the
     * material starts to flow at [FLOW_START] and simply holds for the rest of the phase.
     */
    private fun approachPath(approach: Vec2, target: Vec2, progress: Float): Vec2 =
        reachArc(approach, target, sCurve((progress / REACH_RAMP).coerceIn(0f, 1f)))

    /**
     * The drive-in curve, parameterised 0 at [approach] and 1 at [target].
     *
     * Shared with [retractPath] so the arm leaves a station along exactly the path it arrived on,
     * which is both what a real controller does and one less shape to tune. Reach-plane y grows
     * downward, so subtracting the lift bows the curve *upward* in the world.
     *
     * The bow is a fraction of the segment's own length and capped, so a short reach stays nearly
     * straight instead of swinging.
     */
    private fun reachArc(approach: Vec2, target: Vec2, t: Float): Vec2 {
        val span = kotlin.math.hypot(target.x - approach.x, target.y - approach.y)
        if (span < 1e-4f) return target
        val lift = kotlin.math.min(REACH_ARC, span * REACH_ARC_RATIO)
        val control = Vec2(
            x = (approach.x + target.x) / 2f,
            y = (approach.y + target.y) / 2f - lift,
        )
        val u = t.coerceIn(0f, 1f)
        val inv = 1f - u
        return Vec2(
            x = inv * inv * approach.x + 2f * inv * u * control.x + u * u * target.x,
            y = inv * inv * approach.y + 2f * inv * u * control.y + u * u * target.y,
        )
    }

    /**
     * Back out of a module: straight out along the reach axis to the approach point, then an arc
     * away to the transit pose. The mirror of [approachPath], and the third coordinate of the move.
     *
     * **Change 19.1: one velocity profile across both legs, and no dwell between them.** Each leg
     * used to ease itself, which forces the arm to a dead stop at the via point — an [sCurve] ends
     * at zero velocity — and a further hold was parked on top of that. Easing the whole move once
     * and then splitting it by distance means the corner is taken at speed, which is what a
     * continuous-path move through a via point actually looks like. The arm still comes to rest at
     * each end of the move, because that is where it genuinely stops.
     *
     * The sub-parameters are deliberately linear now. Applying [sCurve] per leg *and* over the
     * whole move would compound the two profiles and spike the speed at each leg's midpoint —
     * which is the bug the old comment here was warning about, from the other direction.
     */
    private fun retractPath(target: Vec2, approach: Vec2, transit: Vec2, progress: Float): Vec2 {
        val t = sCurve(progress.coerceIn(0f, 1f))
        return if (t < RETRACT_OUT) {
            // Change 25.4: back out along the same bowed curve the arm drove in on, reversed.
            reachArc(approach, target, 1f - t / RETRACT_OUT)
        } else {
            arc(approach, transit, (t - RETRACT_OUT) / (1f - RETRACT_OUT))
        }
    }

    /**
     * Sweep the gripper from [a] to [b] along an arc rather than a straight line (change 13.3).
     *
     * Straight-line interpolation between a full extension and the folded stow pose passes close to
     * the shoulder, and an arm folding through its own base swings the elbow far faster than the
     * gripper moves. Lifting the path with a quadratic Bezier keeps the tool clear of the machine
     * and spreads the elbow travel evenly.
     *
     * Change 15.2: the lift now scales with how far the segment actually travels. A fixed lift threw
     * short moves into a disproportionate hop, which was part of what made the approach to the
     * bottom dispensers look wrong.
     */
    private fun arc(a: Vec2, b: Vec2, t: Float): Vec2 {
        val u = t.coerceIn(0f, 1f)
        val span = kotlin.math.hypot(b.x - a.x, b.y - a.y)
        val lift = kotlin.math.min(ARC_LIFT, span * ARC_LIFT_RATIO)
        val control = Vec2(
            x = (a.x + b.x) / 2f * ARC_INSET,
            y = kotlin.math.min(a.y, b.y) - lift,
        )
        val inv = 1f - u
        return Vec2(
            x = inv * inv * a.x + 2f * inv * u * control.x + u * u * b.x,
            y = inv * inv * a.y + 2f * inv * u * control.y + u * u * b.y,
        )
    }

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private companion object {
        const val WRIST_ROLL = 0.9f
        const val UNSET = Long.MIN_VALUE

        /** Filter time constant. Short enough to track, long enough to round off phase corners. */
        const val TAU_MS = 90f

        /** Ignore absurd frame gaps (app resumed, debugger paused) rather than jumping. */
        const val MAX_DT_MS = 100L

        /**
         * Fraction of a collect/dispense phase spent driving the arm in before the flow starts.
         *
         * Change 25.4 raised this from 0.38. The drive-in is the move that was called ungraceful,
         * and the cheapest thing to give it is time: at 0.185 of a 40 s cycle the phase is 7.4 s,
         * so the descent goes from about 2.8 s to 3.8 s over a slightly longer (now bowed) path --
         * roughly a quarter slower. The flow window pays for it, which is why [FLOW_END] moves out
         * to 0.98 to give some of that back.
         */
        const val REACH_RAMP = 0.52f

        /** How far the drive-in is bowed above its own chord, in reach-plane units (change 25.4). */
        const val REACH_ARC = 0.075f

        /** Cap on that bow, as a fraction of the segment's length, so short reaches stay straight. */
        const val REACH_ARC_RATIO = 0.30f

        /**
         * Where the retract stops going straight out and starts arcing away, as a fraction of the
         * move's own eased clock (change 19.1).
         *
         * The dwell that used to sit between the two legs is gone — see [retractPath] — so this is
         * now a split point rather than the first of three. Distance-weighted by eye: the straight
         * segment out of the module is much shorter than the arc to transit, so it gets the
         * smaller share and the two legs run at roughly the same speed through the corner.
         */
        const val RETRACT_OUT = 0.34f

        /**
         * Window inside PICKING/PLACING during which material actually flows.
         *
         * FLOW_START must stay *after* [REACH_RAMP] or the stream starts before the tool has
         * arrived -- and with change 25.3 that would mean pouring before the nozzle is inside the
         * station's funnel. Both moved together in change 25.4, keeping the same margin.
         */
        const val FLOW_START = 0.56f
        const val FLOW_END = 0.98f

        /** Degrees of dispenser rotor rotation per second at full flow (change 15.1). */
        const val PUMP_DEG_PER_SEC = 260f

        /** Height the arm arc is lifted above its endpoints, in reach-plane units. */
        const val ARC_LIFT = 0.20f

        /** Cap on that lift as a fraction of the segment length, so short hops stay flat. */
        const val ARC_LIFT_RATIO = 0.28f

        /** How far the arc is drawn in toward the base at its apex. */
        const val ARC_INSET = 0.88f
    }
}
