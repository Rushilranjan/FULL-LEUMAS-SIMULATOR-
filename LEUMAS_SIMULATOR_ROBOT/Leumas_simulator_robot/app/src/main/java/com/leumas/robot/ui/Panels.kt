package com.leumas.robot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leumas.robot.ble.BleConnectionState
import com.leumas.robot.ble.DiscoveredDevice
import com.leumas.robot.protocol.SlotMap
import com.leumas.robot.sim.WireTrace
import com.leumas.robot.ui.theme.LeumasColors
import com.leumas.robot.ui.theme.LeumasGlass

/** Small industrial-looking button. */
@Composable
fun ConsoleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = LeumasColors.Info,
    selected: Boolean = false,
) {
    Box(
        modifier = modifier
            // Change 13.4: translucent like the task card, so the controls never block the arena.
            .background(
                if (selected) {
                    Brush.verticalGradient(
                        0f to accent.copy(alpha = 0.34f),
                        1f to accent.copy(alpha = 0.20f),
                    )
                } else {
                    LeumasGlass.button()
                },
                RoundedCornerShape(4.dp),
            )
            .border(1.dp, if (selected) accent else LeumasColors.Outline, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) accent else LeumasColors.OnSurface,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun PanelFrame(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    Column(
        modifier = modifier
            // Change 13.4: the calibration and settings panels used to be effectively opaque and
            // covered the robot. Same glass as the task card now.
            .background(LeumasGlass.panel(), RoundedCornerShape(6.dp))
            .border(1.dp, LeumasColors.Outline.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            color = LeumasColors.OnSurfaceDim,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp,
        )
        content()
    }
}

/** Alias so PanelFrame can accept a plain composable body without importing ColumnScope. */
typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

/**
 * BLE connection panel: scan, pick a device, connect (spec §17).
 *
 * The rig uses an MLT-BT05, which frequently advertises no service UUIDs, so the scan is
 * unfiltered and likely bridges are flagged rather than everything else being hidden.
 */
@Composable
fun ConnectPanel(
    connection: BleConnectionState,
    devices: List<DiscoveredDevice>,
    onScan: () -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelFrame("BLUETOOTH", modifier.width(300.dp)) {
        Text(
            text = when (connection) {
                is BleConnectionState.Connected ->
                    "Connected — ${connection.profile.profileName}"
                is BleConnectionState.Failed ->
                    connection.reason + (connection.status?.let { " (status $it)" } ?: "")
                else -> connection.label
            },
            color = when {
                connection.isConnected -> LeumasColors.Running
                connection is BleConnectionState.Failed -> LeumasColors.Idle
                else -> LeumasColors.OnSurfaceDim
            },
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConsoleButton("SCAN", onScan)
            ConsoleButton("DISCONNECT", onDisconnect, accent = LeumasColors.Idle)
        }

        if (devices.isNotEmpty()) {
            // Bounded height: this LazyColumn sits inside a Column(Modifier.verticalScroll())
            // in MainScreen, and a scrollable inside a scrollable with no height limit is an
            // infinite-constraint crash on real devices (not caught by compilation).
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(devices) { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                LeumasColors.SurfaceRaised.copy(alpha = 0.72f),
                                RoundedCornerShape(4.dp),
                            )
                            .clickable { onConnect(device) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = device.label,
                                color = if (device.looksLikeUartBridge) {
                                    LeumasColors.Running
                                } else {
                                    LeumasColors.OnSurface
                                },
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = device.address,
                                color = LeumasColors.OnSurfaceDim,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Text(
                            text = "${device.rssi}dBm",
                            color = LeumasColors.OnSurfaceDim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Demo panel — replays scripted firmware traces so the whole app can be exercised with no
 * hardware attached. The bytes go through the same assembler and parser the BLE path uses.
 */
@Composable
fun DemoPanel(
    running: Boolean,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelFrame("DEMO TRACES", modifier.width(300.dp)) {
        WireTrace.ALL.keys.forEach { name ->
            ConsoleButton(name.uppercase(), { onStart(name) }, Modifier.fillMaxWidth())
        }
        if (running) {
            ConsoleButton("STOP", onStop, Modifier.fillMaxWidth(), accent = LeumasColors.Idle)
        }
    }
}

/**
 * Calibration panel.
 *
 * Note what this does *not* do: it never places a module graphic. Calibration defines the point on
 * the workspace boundary the arm reaches toward, because the real machine stands outside the
 * screen.
 */
@Composable
fun CalibrationPanel(
    selectedSlot: Int,
    calibratedSlots: Set<Int>,
    onSelect: (Int) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelFrame("CALIBRATION", modifier.width(320.dp)) {
        Text(
            text = "Pick a module, then tap near the screen edge where it physically stands.",
            color = LeumasColors.OnSurfaceDim,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        SlotMap.all.forEach { spec ->
            val done = spec.slot in calibratedSlots
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (spec.slot == selectedSlot) {
                            LeumasColors.Info.copy(alpha = 0.22f)
                        } else {
                            LeumasColors.SurfaceRaised.copy(alpha = 0.72f)
                        },
                        RoundedCornerShape(4.dp),
                    )
                    .clickable { onSelect(spec.slot) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${spec.slot}  ${spec.displayName}",
                    color = if (spec.slot == selectedSlot) {
                        LeumasColors.Info
                    } else {
                        LeumasColors.OnSurface
                    },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = if (spec.isSpecialStation) "FIXED" else if (done) "SET" else "—",
                    color = when {
                        spec.isSpecialStation -> LeumasColors.OnSurfaceDim
                        done -> LeumasColors.Running
                        else -> LeumasColors.Idle
                    },
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConsoleButton("RESET DEFAULTS", onReset, accent = LeumasColors.Idle)
            ConsoleButton("DONE", onDone, accent = LeumasColors.Running)
        }
    }
}

/**
 * Raw traffic log.
 *
 * Shows every received line and how it was decoded, including rejections. On first bring-up this
 * is how you confirm the resolved GATT profile and that CRLF framing is being handled.
 */
@Composable
fun DebugPanel(
    log: List<LogLine>,
    gattTree: List<String>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex)
    }

    PanelFrame("RAW TRAFFIC", modifier.width(340.dp)) {
        if (gattTree.isNotEmpty()) {
            gattTree.take(GATT_PREVIEW_LINES).forEach {
                Text(
                    text = it,
                    color = LeumasColors.OnSurfaceDim,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        // Same bounded-height requirement as the device list above.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp),
        ) {
            items(log) { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = line.raw,
                        color = if (line.accepted) LeumasColors.Running else LeumasColors.Idle,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = line.decoded,
                        color = LeumasColors.OnSurfaceDim,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private const val GATT_PREVIEW_LINES = 6
