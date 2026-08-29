package com.leumas.robot.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leumas.robot.ble.BleConnectionState
import com.leumas.robot.ble.BleController
import com.leumas.robot.ble.DiscoveredDevice
import com.leumas.robot.ble.NoOpTransferAck
import com.leumas.robot.calibration.Calibration
import com.leumas.robot.calibration.CalibrationStore
import com.leumas.robot.calibration.EdgePoint
import com.leumas.robot.domain.PlantState
import com.leumas.robot.domain.PlantStateReducer
import com.leumas.robot.domain.RobotStateMachine
import com.leumas.robot.protocol.LineAssembler
import com.leumas.robot.protocol.McuEvent
import com.leumas.robot.protocol.McuProtocolParser
import com.leumas.robot.protocol.SlotMap
import com.leumas.robot.protocol.Timings
import com.leumas.robot.protocol.TransferKind
import com.leumas.robot.render.ArenaFrame
import com.leumas.robot.render.ArenaGeometry
import com.leumas.robot.render.RobotPoser
import com.leumas.robot.render3d.Robot3DPoser
import com.leumas.robot.sim.SimulatedSource
import com.leumas.robot.sim.TraceStep
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** One line of the developer log. Spec §20 recommends surfacing raw BLE traffic. */
data class LogLine(
    val atMs: Long,
    val raw: String,
    val decoded: String,
    val accepted: Boolean,
)

enum class UiMode { NORMAL, CALIBRATION }

/**
 * Wires the BLE link, protocol parsing, plant state and animation together.
 *
 * The rule that shapes everything here: Android never originates process state. Bytes arrive,
 * become events, and events mutate state. Nothing is predicted or synthesised (spec §13).
 */
class RobotViewModel(app: Application) : AndroidViewModel(app) {

    private val ble = BleController(app)
    private val calibrationStore = CalibrationStore(app)
    private val assembler = LineAssembler()
    private val simulator = SimulatedSource()

    // The ack is a no-op: the firmware has no receive path at all. See TransferAck.
    private val machine = RobotStateMachine(ack = NoOpTransferAck)
    private val poser = RobotPoser()
    private val poser3d = Robot3DPoser()

    private val _plant = MutableStateFlow(PlantState())
    val plant: StateFlow<PlantState> = _plant.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    private val _calibration = MutableStateFlow(Calibration.DEFAULT)
    val calibration: StateFlow<Calibration> = _calibration.asStateFlow()

    private val _uiMode = MutableStateFlow(UiMode.NORMAL)
    val uiMode: StateFlow<UiMode> = _uiMode.asStateFlow()

    private val _selectedSlot = MutableStateFlow(SlotMap.calibratable.first().slot)
    val selectedSlot: StateFlow<Int> = _selectedSlot.asStateFlow()

    private val _demoRunning = MutableStateFlow(false)
    val demoRunning: StateFlow<Boolean> = _demoRunning.asStateFlow()

    val connectionState: StateFlow<BleConnectionState> = ble.connectionState
    val scanResults: StateFlow<List<DiscoveredDevice>> = ble.scanResults
    val gattTree: StateFlow<List<String>> = ble.gattTree

    private val _robotStateLabel = MutableStateFlow("IDLE")
    val robotStateLabel: StateFlow<String> = _robotStateLabel.asStateFlow()

    // Change 12.1: true while the robot is working the left side of the arena and would sit behind
    // the top-left task card. The card fades to translucent when this is set (see [tick]).
    private val _taskCardDimmed = MutableStateFlow(false)
    val taskCardDimmed: StateFlow<Boolean> = _taskCardDimmed.asStateFlow()

    private var demoJob: Job? = null
    private var lotDoneAtMs: Long = 0L

    init {
        viewModelScope.launch {
            calibrationStore.calibration.collectLatest { _calibration.value = it }
        }
        viewModelScope.launch {
            ble.incoming.collectLatest { chunk -> ingest(chunk) }
        }
        viewModelScope.launch {
            ble.connectionState.collectLatest { state ->
                // A dropped link must not leave half a line buffered for the next session.
                if (state is BleConnectionState.Disconnected || state is BleConnectionState.Failed) {
                    assembler.reset()
                }
            }
        }
    }

    // -- Ingest --------------------------------------------------------------------------------

    /** Bytes in, events applied. Shared by BLE and demo mode so both exercise the same code. */
    private fun ingest(chunk: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        for (line in assembler.append(chunk)) {
            val event = McuProtocolParser.parse(line)
            appendLog(now, line, event)
            apply(event, now)
        }
    }

    private fun apply(event: McuEvent, now: Long) {
        if (event is McuEvent.SequenceStarted) {
            // Hard reset: abandon any in-flight animation before the state is wiped, and send the
            // arm home so the next sequence starts from rail centre.
            machine.abort()
            poser3d.reset()
            lotDoneAtMs = 0L
        }
        if (event is McuEvent.LotDone) lotDoneAtMs = now
        if (event is McuEvent.Move && event.kind == TransferKind.ROBOT) {
            machine.submit(event, now)
        }
        _plant.value = PlantStateReducer.reduce(_plant.value, event, now)
    }

    private fun appendLog(now: Long, raw: String, event: McuEvent) {
        val decoded = when (event) {
            is McuEvent.Unknown -> "IGNORED: " + event.reason
            is McuEvent.Move -> "Move " + event.from + " -> " + event.to + " (" + event.kind + ")"
            else -> event.toString()
        }
        _log.value = (_log.value + LogLine(now, raw, decoded, event !is McuEvent.Unknown))
            .takeLast(MAX_LOG_LINES)
    }

    // -- Frame ---------------------------------------------------------------------------------

    /**
     * Advance the animation and produce the frame to draw.
     *
     * Returns a value rather than writing to a StateFlow, so the canvas can read it each frame
     * without recomposing the surrounding UI tree.
     */
    fun tick(nowMs: Long, geometry: ArenaGeometry): ArenaFrame {
        val animation = machine.update(nowMs)
        _robotStateLabel.value = animation.label

        val pose = poser.pose(animation, _calibration.value, geometry)
        val pose3d = poser3d.pose(animation, _calibration.value)

        // Change 12.1: fade the top-left task card only while the robot is actually intruding into
        // that corner — i.e. a transfer is running and the carriage sits under the card's horizontal
        // span (left ~42% of the arena, the card's own width). Idle parks the carriage at rail centre
        // so the card is never dimmed at rest, and a right-side transfer leaves it fully opaque.
        val dim = machine.isBusy && pose.carriageX <= geometry.widthPx * DIM_ZONE_FRACTION
        if (_taskCardDimmed.value != dim) _taskCardDimmed.value = dim

        // Change 11.1: the blender->packaging / vessel->bottling process transfer is no longer drawn
        // on screen. That handoff is shown entirely by the MCU-controlled LED strip on the physical
        // rig, so the app renders no conveyor swipe and never dims the robot for it — the arm simply
        // stays at its home idle (a PROCESS transfer never drives the robot state machine anyway,
        // see [apply]).
        return ArenaFrame(
            pose = pose,
            conveyor = null,
            dimRobot = false,
            robot3d = pose3d,
        )
    }

    /** Progress of the final exit swipe, 0..1, or null when no lot is shipping. */
    fun exitSwipeProgress(nowMs: Long): Float? {
        if (lotDoneAtMs == 0L) return null
        val elapsed = (nowMs - lotDoneAtMs).coerceAtLeast(0L)
        return (elapsed.toFloat() / Timings.EXIT_SWIPE_MS).coerceIn(0f, 1f)
    }

    // -- BLE -----------------------------------------------------------------------------------

    fun startScan() = ble.startScan()
    fun stopScan() = ble.stopScan()
    fun connect(device: DiscoveredDevice) = ble.connect(device)
    fun disconnect() = ble.disconnect()

    // -- Demo mode -----------------------------------------------------------------------------

    /** Replay a scripted trace through the real parser, for development without hardware. */
    fun startDemo(trace: List<TraceStep>, speed: Float = 1f) {
        stopDemo()
        resetVisualisation()
        _demoRunning.value = true
        demoJob = viewModelScope.launch {
            simulator.replay(trace, speed).collectLatest { ingest(it) }
            _demoRunning.value = false
        }
    }

    fun stopDemo() {
        demoJob?.cancel()
        demoJob = null
        _demoRunning.value = false
    }

    fun resetVisualisation() {
        machine.abort()
        poser3d.reset()
        assembler.reset()
        lotDoneAtMs = 0L
        _plant.value = PlantState()
        _log.value = emptyList()
    }

    // -- Calibration ---------------------------------------------------------------------------

    fun enterCalibration() {
        _uiMode.value = UiMode.CALIBRATION
    }

    fun exitCalibration() {
        _uiMode.value = UiMode.NORMAL
    }

    fun selectSlot(slot: Int) {
        if (SlotMap.isValidSlot(slot)) _selectedSlot.value = slot
    }

    fun setCalibrationPoint(point: EdgePoint) {
        val updated = _calibration.value.with(_selectedSlot.value, point)
        _calibration.value = updated
        viewModelScope.launch { calibrationStore.save(updated) }
    }

    fun resetCalibration() {
        _calibration.value = Calibration.DEFAULT
        viewModelScope.launch { calibrationStore.resetToDefaults() }
    }

    override fun onCleared() {
        super.onCleared()
        stopDemo()
        ble.release()
    }

    private companion object {
        const val MAX_LOG_LINES = 300

        /**
         * Left-side fraction of the arena under the top-left task card's horizontal span
         * (`widthIn(max = 340.dp)` ≈ 0.42 W at this density). The card fades while the carriage is
         * within it during a transfer. See change 12.1 in [tick].
         */
        const val DIM_ZONE_FRACTION = 0.42f
    }
}
