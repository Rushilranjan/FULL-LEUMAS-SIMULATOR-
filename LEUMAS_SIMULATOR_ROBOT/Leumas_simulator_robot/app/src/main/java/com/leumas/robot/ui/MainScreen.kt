package com.leumas.robot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.leumas.robot.render.ArenaFrame
import com.leumas.robot.render.ArenaGeometry
import com.leumas.robot.render.ArenaOverlay
import com.leumas.robot.render.CalibrationOverlay
import com.leumas.robot.render3d.RobotGlView
import com.leumas.robot.sim.WireTrace
import com.leumas.robot.ui.theme.LeumasGlass

/**
 * The main visualisation screen.
 *
 * Layout follows spec §22: the arena is the whole surface and stays unobstructed, with controls
 * tucked into a collapsible side panel rather than covering the machine.
 */
@Composable
fun MainScreen(viewModel: RobotViewModel) {

    val plant by viewModel.plant.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val devices by viewModel.scanResults.collectAsState()
    val calibration by viewModel.calibration.collectAsState()
    val uiMode by viewModel.uiMode.collectAsState()
    val selectedSlot by viewModel.selectedSlot.collectAsState()
    val demoRunning by viewModel.demoRunning.collectAsState()
    val log by viewModel.log.collectAsState()
    val gattTree by viewModel.gattTree.collectAsState()
    val taskCardDimmed by viewModel.taskCardDimmed.collectAsState()
    val sequenceIntro by viewModel.sequenceIntro.collectAsState()
    val startGate by viewModel.startGate.collectAsState()
    val panelsDimmed by viewModel.panelsDimmed.collectAsState()

    var panelsOpen by rememberSaveable { mutableStateOf(true) }

    // Change 13.4: the controls fade with the same envelope as the task card while the arm is
    // working behind them, so nothing on the HUD ever competes with the animation. Driven by the
    // *right*-side flag — these panels sit opposite the task card.
    val controlsAlpha by animateFloatAsState(
        targetValue = if (panelsDimmed) LeumasGlass.GHOST_ALPHA else 1f,
        animationSpec = tween(320),
        label = "controlsAlpha",
    )

    // The frame holder. Written by the frame loop, read only inside the Canvas draw lambda, so a
    // repaint does not recompose this whole tree.
    val frame = remember { mutableStateOf(ArenaFrame()) }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val geometry = remember(configuration, density) {
        with(density) {
            ArenaGeometry(
                widthPx = configuration.screenWidthDp.dp.toPx(),
                heightPx = configuration.screenHeightDp.dp.toPx(),
            )
        }
    }

    // Drive the animation from the display refresh rather than a timer, so it stays in step with
    // vsync and never busy-waits.
    LaunchedEffect(geometry) {
        while (true) {
            withFrameNanos { nanos ->
                frame.value = viewModel.tick(nanos / 1_000_000L, geometry)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {

        // The robot, rail and floor are drawn by the real 3-D engine (change 10.3)...
        RobotGlView(
            poseProvider = { frame.value.robot3d },
            modifier = Modifier.fillMaxSize(),
        )

        // ...with the screen-space extras (conveyor swipe, calibration markers, vignette, and the
        // calibration tap) composited on top as a transparent overlay.
        ArenaOverlay(
            frame = frame,
            calibrationOverlay = if (uiMode == UiMode.CALIBRATION) {
                CalibrationOverlay(calibration, selectedSlot)
            } else {
                null
            },
            modifier = Modifier.fillMaxSize(),
            onCalibrationTap = if (uiMode == UiMode.CALIBRATION) {
                { point -> viewModel.setCalibrationPoint(point) }
            } else {
                null
            },
        )

        // Top-left (change 12.1): the current task sits back in the top-left corner, but auto-fades
        // to translucent while the robot is servicing a left-side module and would otherwise be
        // hidden behind it (RobotViewModel.taskCardDimmed). This keeps the readout visible almost
        // always while never disturbing the robot animation — in the final rig Modules 1 and 2 sit at
        // this corner, and the arm reaching up for them is exactly when the card ghosts out. The
        // earlier full-height vertical rail was dropped because it stood on the rail band constantly.
        TaskCard(
            plant = plant,
            dimmed = taskCardDimmed,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
        )

        // Bottom-left, as its own standalone box, as the requirements explicitly ask.
        SequenceCard(
            sequence = plant.currentSequence,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        )

        // Bottom-centre run progress (change 13.7).
        CycleProgressBar(
            progress = plant.cycleProgress,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .alpha(controlsAlpha)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConsoleButton(
                text = if (uiMode == UiMode.CALIBRATION) "EXIT CAL" else "CALIBRATE",
                onClick = {
                    if (uiMode == UiMode.CALIBRATION) {
                        viewModel.exitCalibration()
                    } else {
                        viewModel.enterCalibration()
                    }
                },
                selected = uiMode == UiMode.CALIBRATION,
            )
            ConsoleButton(
                text = if (panelsOpen) "HIDE" else "PANELS",
                onClick = { panelsOpen = !panelsOpen },
            )
        }

        AnimatedVisibility(
            visible = panelsOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .alpha(controlsAlpha)
                .padding(top = 56.dp, bottom = 12.dp, end = 12.dp),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (uiMode == UiMode.CALIBRATION) {
                    CalibrationPanel(
                        selectedSlot = selectedSlot,
                        calibratedSlots = calibration.points.keys,
                        onSelect = viewModel::selectSlot,
                        onReset = viewModel::resetCalibration,
                        onDone = viewModel::exitCalibration,
                    )
                } else {
                    ConnectPanel(
                        connection = connection,
                        devices = devices,
                        onScan = viewModel::startScan,
                        onConnect = viewModel::connect,
                        onDisconnect = viewModel::disconnect,
                    )
                    DemoPanel(
                        running = demoRunning,
                        onStart = { name ->
                            WireTrace.ALL[name]?.let { viewModel.startDemo(it) }
                        },
                        onStop = viewModel::stopDemo,
                    )
                    DebugPanel(log = log, gattTree = gattTree)
                }
            }
        }

        // Change 13.6: the sequence-start glow and recipe card sit above the whole HUD. Drawn last
        // so nothing overlaps them, and non-interactive — the arena keeps running underneath.
        sequenceIntro?.let { intro ->
            SequenceIntroOverlay(intro = intro, modifier = Modifier.fillMaxSize())
        }

        // Change 16.1: the gate. Drawn above the intro because it succeeds it -- the recipe card
        // fades, this arrives, and nothing on the rig has lit up yet. It is the only control on
        // screen at that moment, so it sits dead centre rather than in a corner with the HUD.
        // Change 16.7: while the board is selected, dark, and waiting on a person, the whole
        // border glows green and keeps moving. It is a state, not an announcement -- it holds for
        // as long as the operator takes and stops the instant START is pressed, which is why it is
        // tied to READY alone and not to STARTING.
        if (startGate == StartGate.READY) {
            WaitingEdgeGlow(modifier = Modifier.fillMaxSize())
        }

        val startState = when (startGate) {
            StartGate.READY -> StartButtonState.OFFERED
            StartGate.STARTING -> StartButtonState.WAITING
            else -> null
        }
        startState?.let { state ->
            StartButton(
                state = state,
                onStart = viewModel::requestStart,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
