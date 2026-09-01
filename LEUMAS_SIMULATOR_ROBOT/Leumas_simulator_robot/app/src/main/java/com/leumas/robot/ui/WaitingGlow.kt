package com.leumas.robot.ui

import androidx.compose.animation.core.LinearEasing
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
import kotlin.math.abs

/**
 * A luminous current running clockwise around the screen while the rig waits for START.
 *
 * The sequence intro's glow is an *announcement*: it plays once and leaves. This is a **state** —
 * the board is selected, dark, and waiting on a person — and it holds for as long as that is true,
 * however long the operator takes.
 *
 * **Change 18.1 made it a current rather than a ring.** The previous version rotated a four-stop
 * green gradient and breathed the whole thing. Every part of the border was lit at all times, so
 * what actually moved was a slight shift in hue, and on the device it read as a static tint no
 * matter how far the alpha was pushed. Brightness was never the problem — *contrast along the
 * border* was.
 *
 * So the border is mostly dim, and a concentrated head of light travels around it, dragging a long
 * tail behind. Because the dim base gives the head something to move against, the motion is
 * unmistakable at a glance, and it reads as current flowing through a circuit rather than as a
 * wheel being spun. The head is short and the tail far longer, which is what makes the direction
 * obvious: a symmetric blob would look the same going either way.
 *
 * **Change 19.3: one current, not two.** A second head on a different period kept the far corners
 * lit, but two smaller lights chasing each other read as decoration — and worse, they crossed, and
 * at the moment they passed there were briefly no corners dark at all and the whole effect
 * flattened. A single head is unambiguous: there is one thing moving, it is large, and you can
 * follow it the whole way round. It is bigger and brighter than either of the two it replaces, and
 * its tail is long enough to keep most of the perimeter alive on its own.
 *
 * Clockwise falls out of the geometry: [Brush.sweepGradient] puts stop 0 at the 3 o'clock position
 * and increases clockwise in screen coordinates, so advancing the head's position advances it
 * clockwise. Nothing is negated anywhere, which is deliberate — a sign flip here would be silently
 * wrong in a way only the eye could catch.
 *
 * Two blur passes, not three. Each is a full-screen RenderEffect re-rasterised every frame, and
 * three of them measurably dropped this device from ~60 fps to ~35 during the intro glow. The head
 * is bright enough that a soft bloom plus a crisp core carries it.
 */
@Composable
fun WaitingEdgeGlow(modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    val shortEdge = minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp

    val transition = rememberInfiniteTransition(label = "waitGlow")

    // One lap of the border, clockwise. Restart, never Reverse: a current does not run backwards,
    // and Reverse here would read as the light sloshing rather than flowing.
    //
    // Change 19.3 slowed the lap from 2.8 s. One large head crossing the screen is far more
    // legible than two small ones were, so it no longer needs speed to be noticed — and a slower,
    // larger sweep is the calmer, more deliberate motion the requirement asks for.
    val head by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3_400, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "waitHead",
    )

    Box(modifier.fillMaxSize()) {
        // Soft bloom: carries the head's light away from the edge so it glows into the screen.
        // Wider than the two-head version was -- one light has to do all the work now, and this is
        // the pass that gives it presence rather than just brightness.
        CurrentPass(
            head = head,
            blur = shortEdge * 0.050f,
            stroke = shortEdge * 0.082f,
            alpha = 0.90f,
        )
        // Crisp core, hugging the border so the outline itself stays sharp.
        CurrentPass(
            head = head,
            blur = shortEdge * 0.004f,
            stroke = shortEdge * 0.019f,
            alpha = 1.0f,
        )
    }
}

@Composable
private fun CurrentPass(head: Float, blur: Dp, stroke: Dp, alpha: Float) {
    Canvas(
        Modifier
            .fillMaxSize()
            .blur(blur)
            .alpha(alpha)
    ) {
        drawCurrent(head, stroke.toPx())
    }
}

private fun DrawScope.drawCurrent(head: Float, strokeWidth: Float) {
    val inset = strokeWidth / 2f
    val brush = Brush.sweepGradient(
        colorStops = Array(CURRENT_SAMPLES + 1) { i ->
            val t = i.toFloat() / CURRENT_SAMPLES
            t to currentColor(comet(t - head))
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
 * Brightness at a point [rel] laps ahead of (positive) or behind (negative) the head.
 *
 * Short ahead, long behind — that asymmetry is what makes the direction of travel readable. Squared
 * falloff rather than linear so the head stays tight and the tail fades out smoothly instead of
 * ending on a visible edge.
 */
private fun comet(rel: Float): Float {
    // Wrap into -0.5..0.5 so the head is continuous across the 1.0 seam.
    var d = ((rel % 1f) + 1.5f) % 1f - 0.5f
    val span = if (d >= 0f) HEAD_SPAN else TAIL_SPAN
    d = abs(d)
    if (d >= span) return 0f
    val k = 1f - d / span
    return k * k
}

/** Dim base green everywhere, rising to a near-white mint at the head of the current. */
private fun currentColor(energy: Float): Color {
    val e = energy.coerceIn(0f, 1f)
    return Color(
        red = BASE_R + (PEAK_R - BASE_R) * e,
        green = BASE_G + (PEAK_G - BASE_G) * e,
        blue = BASE_B + (PEAK_B - BASE_B) * e,
        // The base is faint but never zero: the border stays visible as an outline between heads,
        // which is what stops this looking like a light chasing round an unlit frame.
        alpha = BASE_A + (1f - BASE_A) * e,
    )
}

private const val CURRENT_SAMPLES = 96

/**
 * Head and tail as fractions of the perimeter (change 19.3).
 *
 * Both grew when the second head went away: the head is wider so it carries real weight as it
 * passes a corner, and the tail is long enough to keep the border alive behind it without the
 * second light. Together they cover a little over half the perimeter, which leaves a clear dark
 * stretch ahead of the head — that gap is what the eye reads the direction from, so it must not be
 * closed by making the tail longer still.
 */
private const val HEAD_SPAN = 0.080f
private const val TAIL_SPAN = 0.420f
private const val WAIT_CORNER_DP = 26

private const val BASE_R = 0.043f
private const val BASE_G = 0.400f
private const val BASE_B = 0.278f
private const val BASE_A = 0.24f

private const val PEAK_R = 0.776f
private const val PEAK_G = 1.000f
private const val PEAK_B = 0.878f
