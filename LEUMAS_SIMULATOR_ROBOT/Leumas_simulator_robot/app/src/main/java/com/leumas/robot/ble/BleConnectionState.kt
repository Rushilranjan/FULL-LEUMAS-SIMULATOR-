package com.leumas.robot.ble

/** A device seen during a scan. */
data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** True when the advertisement matched one of [BleUuids.PREFERRED_SERVICES]. */
    val looksLikeUartBridge: Boolean,
) {
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: "(unnamed)"
}

/** Where the BLE link currently is. The UI must always reflect this accurately (spec §26). */
sealed interface BleConnectionState {
    data object Idle : BleConnectionState
    data object Scanning : BleConnectionState
    data class Connecting(val device: DiscoveredDevice) : BleConnectionState
    data class DiscoveringServices(val device: DiscoveredDevice) : BleConnectionState
    data class Connected(
        val device: DiscoveredDevice,
        val profile: ResolvedProfile,
    ) : BleConnectionState
    data class Failed(val reason: String, val status: Int? = null) : BleConnectionState
    data object Disconnected : BleConnectionState

    val isConnected: Boolean get() = this is Connected

    val label: String
        get() = when (this) {
            Idle -> "IDLE"
            Scanning -> "SCANNING"
            is Connecting -> "CONNECTING"
            is DiscoveringServices -> "DISCOVERING"
            is Connected -> "CONNECTED"
            is Failed -> "FAILED"
            Disconnected -> "DISCONNECTED"
        }
}
