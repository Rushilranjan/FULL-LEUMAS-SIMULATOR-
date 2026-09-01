package com.leumas.robot.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The run has to be asked for (change 16.1).
 *
 * The rig used to light up the moment the board button was pressed, with the recipe card playing
 * over a demo that had already begun. The requirement inverts that: the recipe is shown, this
 * button appears, and **nothing on the rig lights until it is pressed**. Tapping it writes `START`
 * to the MCU, which is the only inbound command the firmware accepts.
 *
 * So this is not decoration — it is the gate. It is deliberately the brightest thing on screen
 * while it is up, because at that moment it is the only thing the operator is meant to do.
 *
 * [state] drives all three looks: offered (breathing, tappable), waiting for the board to answer,
 * and gone. The caller does not render it at all in the other gate states.
 */
@Composable
fun StartButton(
    state: StartButtonState,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = rememberInfiniteTransition(label = "startPulse")

    // One slow breath drives the halo and the ring together, so they read as a single object
    // rather than two effects that happen to overlap.
    val breath by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Matched to WaitingEdgeGlow's surge so the border and the button breathe together
            // rather than beating against each other (change 17.2).
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    val waiting = state == StartButtonState.WAITING
    val alpha by animateFloatAsState(
        targetValue = if (waiting) 0.55f else 1f,
        animationSpec = tween(240),
        label = "startAlpha",
    )

    val accent = if (waiting) WAIT_ACCENT else START_ACCENT
    val glow = (0.30f + 0.50f * breath) * alpha

    // Change 17.2: the button bulges as it breathes. A pulsing halo alone was too easy to miss --
    // the control itself has to move, because that is what makes the eye treat it as the thing
    // waiting to be pressed rather than as decoration. Small enough that it never looks unstable
    // and never shifts the text baseline enough to be hard to read. Frozen while WAITING: once
    // START is sent, the button is no longer asking for anything.
    val bulge = if (waiting) 1f else 1f + 0.045f * breath

    Box(modifier = modifier, contentAlignment = Alignment.Center) {

        // Soft halo behind the pill. Drawn on a Canvas rather than as a blurred shadow because
        // Modifier.blur needs API 31 and this build supports older devices.
        Canvas(Modifier.size(width = HALO_W.dp, height = HALO_H.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to accent.copy(alpha = glow * 0.55f),
                    0.55f to accent.copy(alpha = glow * 0.18f),
                    1.0f to Color.Transparent,
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.minDimension * 0.92f,
                ),
                radius = size.minDimension * 0.92f,
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }

        val interaction = remember { MutableInteractionSource() }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .scale(bulge)
                .clip(RoundedCornerShape(PILL_CORNER.dp))
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xFF16202B).copy(alpha = 0.95f),
                        1f to Color(0xFF0A0E13).copy(alpha = 0.95f),
                    )
                )
                .border(
                    width = 1.6.dp,
                    brush = Brush.verticalGradient(
                        0f to accent.copy(alpha = (0.55f + 0.45f * breath) * alpha),
                        1f to accent.copy(alpha = (0.22f + 0.20f * breath) * alpha),
                    ),
                    shape = RoundedCornerShape(PILL_CORNER.dp),
                )
                // Only tappable when it is actually offered: a second tap while the board is still
                // answering would send a second START and restart the run from cycle 1.
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = state == StartButtonState.OFFERED,
                    onClick = onStart,
                )
                .padding(horizontal = 46.dp, vertical = 16.dp),
        ) {
            Text(
                text = if (waiting) "STARTING" else "START",
                color = accent.copy(alpha = alpha),
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 7.sp,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = if (waiting) "waiting for the controller" else "tap to begin the run",
                color = Color(0xFF8A96A3).copy(alpha = alpha),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.6.sp,
            )
        }
    }
}

/** What the gate is showing. Anything else and the caller draws nothing. */
enum class StartButtonState {
    /** Recipe has been shown; the run is waiting on the operator. */
    OFFERED,

    /** START has gone out; waiting for the board to answer with `SEQ:<n>`. */
    WAITING,
}

private val START_ACCENT = Color(0xFF4FE3C1)
private val WAIT_ACCENT = Color(0xFF8A96A3)
private const val PILL_CORNER = 20
private const val HALO_W = 340
private const val HALO_H = 200
