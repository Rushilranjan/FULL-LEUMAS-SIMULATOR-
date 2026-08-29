package com.leumas.robot.ble

import java.util.UUID

/**
 * GATT identifiers for the BLE-UART bridges this rig might carry.
 *
 * The rig uses an **MLT-BT05 / AT-09** (CC2541). Despite "BT05" sounding like the Bluetooth
 * Classic HC-05/06 family, it is a BLE module and exposes the HM-10 style transparent-UART
 * profile: service 0xFFE0 with a single 0xFFE1 characteristic that both notifies and accepts
 * writes.
 *
 * Clone firmware varies, so [ProfileResolver] treats these as preferences rather than guarantees.
 */
object BleUuids {

    /** Standard 16-bit UUID expansion. */
    private fun short(id: String): UUID = UUID.fromString("0000$id-0000-1000-8000-00805F9B34FB")

    /** HM-10 / AT-09 / MLT-BT05 transparent UART. */
    val HM10_SERVICE: UUID = short("FFE0")
    val HM10_CHARACTERISTIC: UUID = short("FFE1")

    /** Some clones expose a second bank here. */
    val HM10_ALT_SERVICE: UUID = short("FFF0")
    val HM10_ALT_CHARACTERISTIC: UUID = short("FFF1")

    /** Nordic UART Service, for nRF-based or ESP32 bridges. */
    val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val NUS_TX_NOTIFY: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    val NUS_RX_WRITE: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Client Characteristic Configuration Descriptor — must be written to enable notifications. */
    val CCCD: UUID = short("2902")

    /** Service UUIDs worth filtering a scan on, most likely first. */
    val PREFERRED_SERVICES: List<UUID> = listOf(HM10_SERVICE, NUS_SERVICE, HM10_ALT_SERVICE)
}
