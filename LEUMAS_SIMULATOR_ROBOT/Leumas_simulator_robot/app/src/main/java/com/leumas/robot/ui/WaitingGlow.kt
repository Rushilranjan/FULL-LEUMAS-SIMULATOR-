package com.leumas.robot.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The green perimeter glow that runs while the rig is waiting for START (change 16.7).
 *
 * The sequence intro's glow is an *announcement*: it plays once, for seven seconds, and leaves.
 * This is a **state**. It says the board is selected, dark, and waiting on a person — and it has to
 * keep saying that for as long as that is true, however long the operator takes to reach the phone.
 * So it never resolves and never fades out on its own; it is drawn exactly while
 * [StartGate.READY] holds and stops the instant START is pressed.
 *
 * Green because on this rig green already means "ready, waiting to be collected" — it is the colour
 * a module wears when it has finished and is standing by for the robot. A board waiting on the
 * operator is the same idea one level up, so it reads without being taught.
 *
 * The movement is a slow counter-rotating sweep plus a gentle breath on the whole thing. Two
 * different periods, neither a multiple of the other, so the border never visibly repeats and never
 * settles into a spinning wheel — it folds through itself. Both are slow on purpose: this sits
 * under a button somebody is being asked to read, and something urgent in the corner of the eye
 * would fight it.
 *
 * Cost is the same two-pass structure the intro glow uses, for the same measured reason — three
 * full-screen blur passes dropped the test device from ~60 fps to ~35. This one is *cheaper* than
 * the intro's: it runs indefinitely, so its radii are trimmed further again.
 */
@Composable
fun WaitingEdgeGlow(modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    val shortEdge = minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp

    val transition = rememberInfiniteTransition(label = "waitGlow")

    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "waitPhase",
    )
    val counter by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(11_300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "waitCounter",
    )
    // The breath. Kept shallow -- it should read as alive, not as a warning light.
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "waitBreath",
    )

    val level = 0.72f + 0.28f * breath

    Box(modifier.fillMaxSize()) {
        GreenGlowPass(
            phase = phase,
            blur = shortEdge * 0.020f,
            stroke = shortEdge * 0.042f,
            alpha = 0.50f * level,
        )
        GreenGlowPass(
            phase = counter,
            blur = shortEdge * 0.005f,
            stroke = shortEdge * 0.013f,
            alpha = 0.85f * level,
        )
    }
}

@Composable
private fun GreenGlowPass(phase: Float, blur: Dp, stroke: Dp, alpha: Float) {
    Canvas(
        Modifier
            .fillMaxSize()
            .blur(blur)
            .alpha(alpha)
    ) {
        drawWaitingPerimeter(phase, stroke.toPx())
    }
}

/**
 * Stroke the screen's rounded perimeter with the rotating green sweep.
 *
 * Inset by half the stroke so the band hugs the edge, and stroked rather than filled so nothing is
 * ever painted across the middle — the arena and the START button both sit inside it.
 */
private fun DrawScope.drawWaitingPerimeter(phase: Float, strokeWidth: Float) {
    val inset = strokeWidth / 2f
    val brush = Brush.sweepGradient(
        colorStops = Array(WAIT_SAMPLES + 1) { i ->
            val t = i.toFloat() / WAIT_SAMPLES
            t to waitColor(t + phase)
        },
        center = center,
    )
    drawRoundRect(
        brush = brush,
        topLeft = Offset(inset, inset),
        size = Size(size.width - strokeWidth, size.height - strokeWidth),
        cornerRadius = CornerRadius(WAIT_CORNER_DP.dp.toPx()),
        style = Stroke(width = strokeWidth),
    )
}

/**
 * Cyclic green ring, wrapping smoothly at 1.0.
 *
 * Four stops rather than two: a straight light-to-dark green sweep has one bright band chasing the
 * border, which is exactly the spinning wheel this is trying not to be. Passing through a teal and
 * a near-mint gives the band internal structure, so what moves is a fold rather than a dot.
 */
private fun waitColor(t: Float): Color {
    val ring = listOf(
        Color(0xFF2FE08A),
        Color(0xFF14B87A),
        Color(0xFF6BFFB0),
        Color(0xFF1FCF95),
    )
    val x = ((t % 1f) + 1f) % 1f * ring.size
    val i = x.toInt() % ring.size
    val j = (i + 1) % ring.size
    val f = x - x.toInt()
    val a = ring[i]
    val b = ring[j]
    return Color(
        red = a.red + (b.red - a.red) * f,
        green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f,
        alpha = 1f,
    )
}

private const val WAIT_SAMPLES = 24
private const val WAIT_CORNER_DP = 26
