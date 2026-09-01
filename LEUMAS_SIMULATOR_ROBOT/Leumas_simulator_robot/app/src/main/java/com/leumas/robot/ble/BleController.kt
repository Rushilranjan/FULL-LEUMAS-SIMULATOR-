package com.leumas.robot.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the BLE link to the MCU's UART bridge.
 *
 * Design notes for the parts of Android BLE that are genuinely hostile:
 *
 *  - **Every GATT operation goes through [GattQueue].** Issuing a second one while the first is
 *    outstanding makes the stack drop it silently.
 *  - **`close()`, not just `disconnect()`.** A `BluetoothGatt` that is disconnected but not closed
 *    leaks the client interface; after a handful of them the stack starts returning status 133 for
 *    every subsequent connection attempt, on every app.
 *  - **`autoConnect = false` plus an explicit `TRANSPORT_LE`.** autoConnect defers the connection
 *    indefinitely with no feedback, which reads as a hang.
 *  - **Status 133 is retried with backoff.** It is the stack's generic failure and is frequently
 *    transient, especially on the first connection after boot.
 *  - **Callbacks arrive on a binder thread.** Nothing here touches UI state directly; everything
 *    leaves via Flows.
 *
 * The MCU is transmit-only today, so [write] exists solely for the future ARRIVED handshake and is
 * unused. See [TransferAck].
 */
@SuppressLint("MissingPermission") // Permissions are checked before any of this is reachable.
class BleController(private val context: Context) {

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _scanResults = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val scanResults: StateFlow<List<DiscoveredDevice>> = _scanResults.asStateFlow()

    private val _gattTree = MutableStateFlow<List<String>>(emptyList())
    val gattTree: StateFlow<List<String>> = _gattTree.asStateFlow()

    /**
     * Raw notification payloads. Replay 0 and a generous buffer: dropping bytes would corrupt a
     * partially-assembled line, so we would rather buffer than lose one.
     */
    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val queue = GattQueue()

    private var gatt: BluetoothGatt? = null
    private var target: DiscoveredDevice? = null

    /**
     * True while the link is down because *we* took it down (change 19.2).
     *
     * Everything else that drops the link is treated as recoverable and reconnected. Without this
     * flag a user-requested disconnect and a board reset look identical from
     * `onConnectionStateChange`, and reconnecting after the former would make the DISCONNECT button
     * do nothing.
     */
    private var userDisconnected = false
    private var profile: ResolvedProfile? = null
    private var scanning = false
    private var retries = 0

    // -- Scanning ------------------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val advertised = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
            val device = DiscoveredDevice(
                address = result.device.address,
                name = runCatching { result.device.name }.getOrNull()
                    ?: result.scanRecord?.deviceName,
                rssi = result.rssi,
                looksLikeUartBridge = advertised.any { it in BleUuids.PREFERRED_SERVICES },
            )
            _scanResults.value = (_scanResults.value.filterNot { it.address == device.address } + device)
                // Likely bridges first, then by signal strength.
                .sortedWith(compareByDescending<DiscoveredDevice> { it.looksLikeUartBridge }
                    .thenByDescending { it.rssi })
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = BleConnectionState.Failed("Scan failed", errorCode)
            scanning = false
        }
    }

    fun startScan() {
        if (scanning) return
        val scanner = BlePermissions.adapter(context)?.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = BleConnectionState.Failed("Bluetooth is off")
            return
        }
        _scanResults.value = emptyList()
        _connectionState.value = BleConnectionState.Scanning
        scanning = true

        // Unfiltered: BT05 clones frequently advertise no service UUIDs at all, so filtering on
        // FFE0 would hide the very device we are looking for. Likely bridges are ranked instead.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(emptyList<ScanFilter>(), settings, scanCallback)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { BlePermissions.adapter(context)?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_connectionState.value is BleConnectionState.Scanning) {
            _connectionState.value = BleConnectionState.Idle
        }
    }

    // -- Connection ----------------------------------------------------------------------------

    fun connect(device: DiscoveredDevice) {
        stopScan()
        closeGatt()
        target = device
        retries = 0
        openGatt(device)
    }

    private fun openGatt(device: DiscoveredDevice) {
        val remote: BluetoothDevice = runCatching {
            BlePermissions.adapter(context)?.getRemoteDevice(device.address)
        }.getOrNull() ?: run {
            _connectionState.value = BleConnectionState.Failed("Unknown device ${device.address}")
            return
        }
        userDisconnected = false
        _connectionState.value = BleConnectionState.Connecting(device)
        queue.clear()
        gatt = remote.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        userDisconnected = true
        queue.clear()
        gatt?.let { runCatching { it.disconnect() } }
        closeGatt()
        profile = null
        _gattTree.value = emptyList()
        _connectionState.value = BleConnectionState.Disconnected
    }

    /** Release everything. Call from the owning lifecycle's teardown so no GATT client leaks. */
    fun release() {
        stopScan()
        disconnect()
    }

    private fun closeGatt() {
        gatt?.let { runCatching { it.close() } }
        gatt = null
    }

    // -- GATT callbacks ------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val device = target ?: return
            when {
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED -> {
                    retries = 0
                    _connectionState.value = BleConnectionState.DiscoveringServices(device)
                    // Some OEM stacks are unreliable discovering from the callback thread.
                    mainHandler.post {
                        queue.enqueue("discoverServices") { g.discoverServices() }
                    }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    queue.clear()
                    closeGatt()
                    profile = null
                    when {
                        // We asked for this. Stay down.
                        userDisconnected -> {
                            _connectionState.value = BleConnectionState.Disconnected
                        }

                        /*
                         * Change 19.2: anything else is a link we still want, so reconnect.
                         *
                         * Pressing RESET on the board drops the peripheral, and that arrives here
                         * as a peer-terminated or timeout status -- not the 133 the old code was
                         * the only case for. So a reset ended the session: the app fell to
                         * "Disconnected unexpectedly" and sat there until somebody re-scanned and
                         * re-tapped the device. Resetting the board is a completely normal thing to
                         * do mid-demo, and the operator should be able to press it, watch the app
                         * come back on its own, and then select a recipe with the board button.
                         *
                         * Retries are capped and backed off so a board that is genuinely off does
                         * not leave the radio scanning forever, but the cap is generous: a reset
                         * takes a moment to come back up and advertise again.
                         */
                        retries < MAX_RETRIES -> {
                            retries++
                            mainHandler.postDelayed({ openGatt(device) }, RETRY_DELAY_MS * retries)
                            _connectionState.value = BleConnectionState.Connecting(device)
                        }

                        status != BluetoothGatt.GATT_SUCCESS ->
                            _connectionState.value =
                                BleConnectionState.Failed("Disconnected unexpectedly", status)

                        else -> _connectionState.value = BleConnectionState.Disconnected
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            queue.completeCurrent()
            val device = target ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.Failed("Service discovery failed", status)
                return
            }

            _gattTree.value = ProfileResolver.describe(g)
            val resolved = ProfileResolver.resolve(g)
            if (resolved == null) {
                _connectionState.value =
                    BleConnectionState.Failed("No notifiable characteristic on this device")
                return
            }
            profile = resolved

            // A larger MTU is a nicety, not a requirement — the line assembler copes either way.
            queue.enqueue("requestMtu") { g.requestMtu(DESIRED_MTU) }
            enableNotifications(g, resolved)
            _connectionState.value = BleConnectionState.Connected(device, resolved)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            queue.completeCurrent()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            queue.completeCurrent()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "CCCD write failed with status $status; notifications may not arrive")
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            queue.completeCurrent()
        }

        // API 33+ delivers the value directly.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            deliver(value)
        }

        // Below API 33 the value is read off the characteristic. Guarded so 33+ never double-delivers.
        @Deprecated("Deprecated in API 33", ReplaceWith(""))
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            characteristic.value?.let { deliver(it) }
        }
    }

    private fun deliver(value: ByteArray) {
        if (value.isEmpty()) return
        // tryEmit is safe here: the buffer is large, and a suspend from a binder thread would
        // stall the BLE stack.
        if (!_incoming.tryEmit(value.copyOf())) {
            Log.w(TAG, "dropped ${value.size} bytes — consumer is not keeping up")
        }
    }

    private fun enableNotifications(g: BluetoothGatt, resolved: ResolvedProfile) {
        val characteristic = g.getService(resolved.serviceUuid)
            ?.getCharacteristic(resolved.notifyUuid) ?: return

        // Local enable first, then the remote CCCD write. Doing only one of the two is the classic
        // "connects fine but no data ever arrives" bug.
        g.setCharacteristicNotification(characteristic, true)

        val cccd = characteristic.getDescriptor(BleUuids.CCCD) ?: run {
            Log.w(TAG, "characteristic has no CCCD; relying on local notification enable")
            return
        }
        val useIndicate = characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
        val value = if (useIndicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        queue.enqueue("writeCccd") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = value
                    g.writeDescriptor(cccd)
                }
            }
        }
    }

    /**
     * Write back to the MCU. Unused today — the firmware has no receive path at all — and present
     * only so the future ARRIVED handshake is a one-line change.
     */
    fun write(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val resolved = profile ?: return false
        val writeUuid = resolved.writeUuid ?: return false
        val characteristic = g.getService(resolved.serviceUuid)
            ?.getCharacteristic(writeUuid) ?: return false

        queue.enqueue("write") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(
                    characteristic,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    characteristic.value = bytes
                    g.writeCharacteristic(characteristic)
                }
            }
        }
        return true
    }

    private companion object {
        const val TAG = "BleController"
        const val STATUS_133 = 133
        /**
         * Reconnect attempts after an unexpected drop (change 19.2).
         *
         * Raised from 3: it now covers a board reset, not just a transient 133, and a Nucleo takes
         * a second or two to boot and start advertising again. With the linear backoff below the
         * eight attempts span roughly 22 s, which comfortably outlasts a reset without leaving the
         * radio retrying a board that has actually been switched off.
         */
        const val MAX_RETRIES = 8
        const val RETRY_DELAY_MS = 600L
        const val DESIRED_MTU = 185
    }
}
