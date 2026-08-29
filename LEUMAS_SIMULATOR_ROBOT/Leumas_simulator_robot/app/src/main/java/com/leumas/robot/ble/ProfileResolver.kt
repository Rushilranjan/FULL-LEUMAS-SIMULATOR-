package com.leumas.robot.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID

/** The characteristics we settled on after inspecting the device's GATT table. */
data class ResolvedProfile(
    val serviceUuid: UUID,
    val notifyUuid: UUID,
    val writeUuid: UUID?,
    val profileName: String,
) {
    val supportsWrite: Boolean get() = writeUuid != null
}

/**
 * Works out which characteristic actually carries the MCU's cues.
 *
 * The rig uses an MLT-BT05, so 0xFFE0/0xFFE1 is tried first. But clone firmware varies — some
 * expose 0xFFF0 instead, some make 0xFFE1 notify-only — and the module could be swapped for an
 * nRF or ESP32 bridge later. Rather than hard-fail on an unexpected table, fall back progressively
 * and finish with "any characteristic that can notify", which is a safe bet for a transparent
 * UART bridge.
 *
 * Whatever is chosen is surfaced on the debug screen, so first bring-up can confirm it rather than
 * assume.
 */
object ProfileResolver {

    fun resolve(gatt: BluetoothGatt): ResolvedProfile? {
        known(gatt, BleUuids.HM10_SERVICE, BleUuids.HM10_CHARACTERISTIC, "HM-10 / BT05 (FFE0)")
            ?.let { return it }
        known(gatt, BleUuids.NUS_SERVICE, BleUuids.NUS_TX_NOTIFY, "Nordic UART", BleUuids.NUS_RX_WRITE)
            ?.let { return it }
        known(gatt, BleUuids.HM10_ALT_SERVICE, BleUuids.HM10_ALT_CHARACTERISTIC, "HM-10 alt (FFF0)")
            ?.let { return it }
        return anyNotifiable(gatt)
    }

    private fun known(
        gatt: BluetoothGatt,
        service: UUID,
        notify: UUID,
        name: String,
        write: UUID? = null,
    ): ResolvedProfile? {
        val svc = gatt.getService(service) ?: return null
        val ch = svc.getCharacteristic(notify) ?: return null
        if (!ch.canNotify()) return null
        val writeUuid = when {
            write != null -> svc.getCharacteristic(write)?.takeIf { it.canWrite() }?.uuid
            ch.canWrite() -> ch.uuid
            else -> svc.characteristics.firstOrNull { it.canWrite() }?.uuid
        }
        return ResolvedProfile(svc.uuid, ch.uuid, writeUuid, name)
    }

    private fun anyNotifiable(gatt: BluetoothGatt): ResolvedProfile? {
        for (service in gatt.services) {
            val ch = service.characteristics.firstOrNull { it.canNotify() } ?: continue
            val writeUuid = service.characteristics.firstOrNull { it.canWrite() }?.uuid
            return ResolvedProfile(service.uuid, ch.uuid, writeUuid, "generic (auto-detected)")
        }
        return null
    }

    /** Human-readable GATT dump for the debug screen. */
    fun describe(gatt: BluetoothGatt): List<String> = buildList {
        for (service in gatt.services) {
            add("SERVICE ${service.uuid}")
            for (ch in service.characteristics) {
                val props = buildList {
                    if (ch.canNotify()) add("NOTIFY")
                    if (ch.canWrite()) add("WRITE")
                    if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
                }.joinToString("/").ifEmpty { "—" }
                add("  CHAR ${ch.uuid}  [$props]")
            }
        }
        if (isEmpty()) add("(no services discovered)")
    }

    private fun BluetoothGattCharacteristic.canNotify(): Boolean =
        properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

    private fun BluetoothGattCharacteristic.canWrite(): Boolean =
        properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
}
