package com.leumas.robot.ble

/**
 * Extension point for the future `ARRIVED` acknowledgement (spec §16).
 *
 * `App/ble.c` gained a receive path in change 16.1, but it accepts exactly one command -- START,
 * the run gate -- and there is still no ARRIVED. So this stays bound to [NoOpTransferAck] and
 * nothing is written back per transfer. Spec §29 forbids faking a channel the MCU does not have,
 * and a receive path that ignores ARRIVED is still, for this purpose, a channel it does not have.
 *
 * If the firmware ever answers ARRIVED, only the binding changes: implement this over the same
 * characteristic write [com.leumas.robot.ui.RobotViewModel] already uses for START. Two rules
 * survive that change —
 *
 *  - it fires for **robot transfers only**; conveyor swipes keep using the MCU's internal timers
 *  - there is **no timeout**. If the MCU waits for ARRIVED it may wait indefinitely (spec §29).
 */
interface TransferAck {
    fun onRobotTransferComplete(from: Int, to: Int)
}

object NoOpTransferAck : TransferAck {
    override fun onRobotTransferComplete(from: Int, to: Int) = Unit
}
