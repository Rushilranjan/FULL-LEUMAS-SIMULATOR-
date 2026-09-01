package com.leumas.robot.domain

import com.leumas.robot.ble.NoOpTransferAck
import com.leumas.robot.ble.TransferAck
import com.leumas.robot.protocol.McuEvent
import com.leumas.robot.protocol.Timings
import com.leumas.robot.protocol.TransferKind

/**
 * The explicit robot animation state machine required by spec §21.
 *
 * **Phases are declared in milliseconds, and those milliseconds are a contract with the firmware.**
 * The BLE link is transmit-only — nothing comes back, so the MCU cannot ask where the arm is. The
 * only way the physical rig's LEDs and this animation can agree is for both to be driven from the
 * same numbers, so every value here has a twin in the firmware's `Core/Inc/config.h` `SS1_ARM_*`
 * block, in this order. A `_Static_assert` there checks the total against [TOTAL_MS]; change one
 * side without the other and the firmware build fails rather than the demo drifting.
 *
 * [COLLECTING] and [DUMPING] are the dwells — the arm stands still at the module for the full
 * `SS1_COLLECTION_MS` / `SS1_DUMP_MS`. Before they existed the arm did one continuous pass and was
 * long gone by the time the rig marked the module as collected-from, which is exactly the fault the
 * trial run reported. Every phase is visible: spec §11 wants an audience to follow each joint
 * movement, so no phase is allowed to be instantaneous.
 */
enum class RobotPhase(val durationMs: Long) {
    MOVING_TO_SOURCE(3_150),
    POSITIONING_AT_SOURCE(1_300),
    PICKING(1_850),

    /** Stood at the source with the gripper closed — `SS1_COLLECTION_MS`. */
    COLLECTING(20_000),

    LIFTING(1_100),
    MOVING_TO_DESTINATION(3_700),
    POSITIONING_AT_DESTINATION(1_100),
    PLACING(1_850),

    /** Stood at the blender with the gripper open, pouring — `SS1_DUMP_MS`. */
    DUMPING(20_000),

    RETRACTING(1_500),

    /**
     * Post-demo change 10.2: after every transfer the robot travels back to the middle of the rail
     * (its home/default position) and re-stows before going idle.
     */
    RETURNING_HOME(2_950),
    ;

    /**
     * Share of the whole animation. A getter rather than a constructor-computed `val`, because
     * [TOTAL_MS] is a companion property and would still be zero while the entries are being built.
     */
    val fraction: Float get() = durationMs.toFloat() / TOTAL_MS

    companion object {
        val ORDERED: List<RobotPhase> = entries.toList()

        /** 58 500 ms. Must equal `SS1_ARM_TOTAL_MS` in the firmware. */
        val TOTAL_MS: Long = entries.sumOf { it.durationMs }
    }
}

/**
 * Snapshot handed to the renderer each frame. [phase] is null when the robot is idle.
 */
data class RobotAnimationState(
    val phase: RobotPhase?,
    val phaseProgress: Float,
    val overallProgress: Float,
    val transfer: ActiveTransfer?,
    val carryingPayload: Boolean,
    val catchingUp: Boolean,
) {
    val isIdle: Boolean get() = phase == null

    /** Label for the status overlay (spec §23). */
    val label: String get() = phase?.name ?: "IDLE"

    companion object {
        val IDLE = RobotAnimationState(
            phase = null,
            phaseProgress = 0f,
            overallProgress = 0f,
            transfer = null,
            carryingPayload = false,
            catchingUp = false,
        )
    }
}

/**
 * Drives one robot transfer at a time.
 *
 * **Collision policy (spec §21 requires this be documented).** Exactly one transfer animates at a
 * time. A [McuEvent.Move] arriving mid-transfer is held in a single-slot pending queue and begins
 * the instant the current one finishes. If a second arrives while one is already pending, the
 * older pending transfer is *dropped* and counted in [droppedTransfers] — dropping the stale one
 * keeps the visualisation closest to what the MCU is currently doing.
 *
 * **Catch-up.** The firmware paces its carries off the same phase table this class animates, so
 * consecutive `MOVE` cues are never closer than [Timings.TRANSFER_ROBOT_MS] — one full animation
 * plus the firmware's `SS1_ARM_HOME_HOLD_MS` of slack. A backlog therefore should not happen at
 * all; the machinery stays because a dropped BLE notification or a mid-run restart can still
 * bunch cues up. Whenever something is pending, animated time advances up to
 * [Timings.MAX_CATCHUP_FACTOR]× faster so it drains. The factor is capped, so a backlog degrades
 * to "brisk" and never to a teleport — spec §10 forbids the robot jumping.
 *
 * Not thread-safe; drive it from the frame loop only.
 */
class RobotStateMachine(
    var totalDurationMs: Long = Timings.DEFAULT_ROBOT_ANIMATION_MS,
    private val ack: TransferAck = NoOpTransferAck,
) {
    private var active: ActiveTransfer? = null
    private var pending: ActiveTransfer? = null
    private var elapsedMs: Float = 0f
    private var lastUpdateMs: Long = UNSET

    var droppedTransfers: Int = 0
        private set

    val isBusy: Boolean get() = active != null

    /**
     * Queue a transfer. Conveyor transfers are rejected here rather than filtered by the caller,
     * so there is exactly one place that can start an arm animation (spec §14).
     */
    fun submit(move: McuEvent.Move, nowMs: Long) {
        if (move.kind != TransferKind.ROBOT) return
        val transfer = ActiveTransfer(move.from, move.to, move.kind, nowMs)
        if (active == null) {
            active = transfer
            elapsedMs = 0f
            lastUpdateMs = UNSET
        } else {
            if (pending != null) droppedTransfers++
            pending = transfer
        }
    }

    /** Abandon everything. Called on SEQ (a hard reset) and on BLE disconnect. */
    fun abort() {
        active = null
        pending = null
        elapsedMs = 0f
        lastUpdateMs = UNSET
    }

    /**
     * Advance the animation to [nowMs] and return what to draw.
     *
     * Uses accumulated *animated* time rather than wall-clock delta from a start instant, so the
     * catch-up factor can vary mid-transfer without the phase boundaries jumping.
     */
    fun update(nowMs: Long): RobotAnimationState {
        val current = active ?: return RobotAnimationState.IDLE

        if (lastUpdateMs == UNSET) lastUpdateMs = nowMs
        val deltaMs = (nowMs - lastUpdateMs).coerceAtLeast(0L)
        lastUpdateMs = nowMs

        val catchingUp = pending != null
        val factor = if (catchingUp) Timings.MAX_CATCHUP_FACTOR else 1f
        elapsedMs += deltaMs * factor

        if (elapsedMs >= totalDurationMs) {
            // Robot transfers only, no timeout — see TransferAck.
            ack.onRobotTransferComplete(current.from, current.to)
            active = pending
            pending = null
            elapsedMs = 0f
            return if (active == null) RobotAnimationState.IDLE else update(nowMs)
        }

        val overall = (elapsedMs / totalDurationMs).coerceIn(0f, 1f)
        val (phase, phaseProgress) = phaseAt(overall)

        return RobotAnimationState(
            phase = phase,
            phaseProgress = phaseProgress,
            overallProgress = overall,
            transfer = current,
            carryingPayload = phase.isCarrying(phaseProgress),
            catchingUp = catchingUp,
        )
    }

    /** Locate [overall] (0..1 of the whole transfer) within the phase table. */
    private fun phaseAt(overall: Float): Pair<RobotPhase, Float> {
        var cursor = 0f
        for (phase in RobotPhase.ORDERED) {
            val end = cursor + phase.fraction
            if (overall < end || phase == RobotPhase.ORDERED.last()) {
                val progress = ((overall - cursor) / phase.fraction).coerceIn(0f, 1f)
                return phase to progress
            }
            cursor = end
        }
        error("unreachable: phase fractions must sum to 1.0")
    }

    /**
     * The payload is attached from the moment the gripper closes until it opens again — i.e. from
     * the end of PICKING through to the middle of PLACING.
     */
    private fun RobotPhase.isCarrying(progress: Float): Boolean = when (this) {
        RobotPhase.PICKING -> progress > 0.6f
        RobotPhase.COLLECTING,
        RobotPhase.LIFTING,
        RobotPhase.MOVING_TO_DESTINATION,
        RobotPhase.POSITIONING_AT_DESTINATION -> true
        RobotPhase.PLACING -> progress < 0.6f
        // DUMPING: the payload is in the blender by now, so the gripper is empty.
        else -> false
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
    }
}
