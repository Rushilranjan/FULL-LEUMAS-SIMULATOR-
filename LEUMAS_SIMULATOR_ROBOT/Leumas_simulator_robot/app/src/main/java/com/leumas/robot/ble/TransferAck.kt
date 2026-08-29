package com.leumas.robot.ble

/**
 * Extension point for the future `ARRIVED` acknowledgement (spec §16).
 *
 * The firmware is transmit-only today — `App/ble.c` contains only `HAL_UART_Transmit`, and no
 * USART6 receive handler or IRQ exists anywhere in the project. So this is bound to
 * [NoOpTransferAck] and nothing is written back. Spec §29 forbids faking a channel the MCU does
 * not have.
 *
 * When the firmware gains an RX path, only the binding changes: implement this over a BLE
 * characteristic write. Two rules survive that change —
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
