package com.leumas.robot.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leumas.robot.domain.Recipe
import com.leumas.robot.protocol.SlotMap
import com.leumas.robot.ui.theme.LeumasColors
import com.leumas.robot.ui.theme.LeumasGlass

/**
 * One sequence-start announcement (change 13.6).
 *
 * [id] is a monotonic counter rather than the sequence number, so pressing the button twice for the
 * same sequence still replays the intro.
 */
data class SequenceIntro(
    val id: Long,
    val sequence: Int,
    val recipe: Recipe?,
)

/**
 * Total time the intro is on screen. The animation controller is untouched underneath it.
 *
 * Change 14.6 asks for the border glow to hold "for atleast 5 seconds". With the envelope below
 * (fade in by 0.07, out from 0.93) this leaves the glow at full strength for ~6.0 s of the 7 s.
 * There is room: the first robot MOVE of a sequence does not arrive until ~20 s in.
 */
const val SEQUENCE_INTRO_MS = 7_000L

/**
 * The full-screen intro played whenever the board button selects a new sequence (change 13.6).
 *
 * Two layers: a luminous perimeter glow with a completely transparent centre, and — once the glow
 * has established itself — the "recipe received" card. Both are purely decorative overlays: the MCU
 * sequence starts underneath them at the instant `SEQ:<n>` arrives, exactly as before.
 */
@Composable
fun SequenceIntroOverlay(intro: SequenceIntro, modifier: Modifier = Modifier) {

    // A single 0..1 clock for the whole intro, so the glow and the card cannot drift apart.
    val progress = remember(intro.id) { Animatable(0f) }
    LaunchedEffect(intro.id) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(SEQUENCE_INTRO_MS.toInt(), easing = LinearEasing))
    }
    val t = progress.value

    Box(modifier.fillMaxSize()) {

        SiriEdgeGlow(
            alpha = envelope(t, fadeIn = GLOW_IN, fadeOut = GLOW_OUT),
            modifier = Modifier.fillMaxSize(),
        )

        intro.recipe?.let { recipe ->
            val cardAlpha = envelope(t, fadeIn = CARD_IN, fadeOut = CARD_OUT)
            if (cardAlpha > 0.001f) {
                RecipeCard(
                    recipe = recipe,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .alpha(cardAlpha)
                        // A whisper of scale on entry, so it arrives rather than blinks on.
                        .scale(0.94f + 0.06f * cardAlpha),
                )
            }
        }
    }
}

/**
 * Ramp up over [fadeIn], hold, ramp down over [fadeOut]. All four values are fractions of the
 * intro's own 0..1 clock.
 */
private fun envelope(t: Float, fadeIn: ClosedFloatingPointRange<Float>, fadeOut: ClosedFloatingPointRange<Float>): Float {
    val rise = smoothstep((t - fadeIn.start) / (fadeIn.endInclusive - fadeIn.start))
    val fall = 1f - smoothstep((t - fadeOut.start) / (fadeOut.endInclusive - fadeOut.start))
    return (rise * fall).coerceIn(0f, 1f)
}

private fun smoothstep(x: Float): Float {
    val v = x.coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
}

/**
 * The iOS-18-Siri style perimeter glow.
 *
 * Flowing blue / purple / pink / cyan around the screen edges with the centre left **completely
 * transparent**, as requirement 13.6 asks. The colour ring is a sweep gradient whose stops are
 * resampled from a rotating phase — rotating the *stops* rather than the canvas keeps the rounded
 * rectangle square to the screen while the colour still travels around it.
 *
 * Three passes build the bloom: a wide, heavily blurred halo, a mid band, and a tight bright core.
 *
 * The band is sized as a **fraction of the shorter screen dimension**, not in fixed dp. The first
 * on-device run used fixed sizes (a 78dp band under a 46dp blur) which, on a 360dp-tall landscape
 * phone, reached more than a third of the way in from each edge — the two sides met and the glow
 * flooded the whole screen instead of framing it. Proportional sizing keeps roughly the middle
 * three-quarters clear on any display. Blur is a RenderEffect, which needs API 31 — minSdk is 31.
 */
@Composable
private fun SiriEdgeGlow(alpha: Float, modifier: Modifier = Modifier) {
    if (alpha <= 0.001f) return

    val configuration = LocalConfiguration.current
    val shortEdge = minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp

    val transition = rememberInfiniteTransition(label = "siriGlow")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(5_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "siriPhase",
    )
    // A second, slower counter-rotating phase keeps the flow from looking like a single spinning
    // wheel — the colours fold through each other instead.
    val counter by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(8_900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "siriCounter",
    )

    // Two passes, not three. Each pass is a full-screen RenderEffect blur re-rasterised every
    // frame, and on the test device (TECNO KL4h, Android 14) three of them dropped the app from a
    // steady 59.7 fps to ~35 for the whole intro — measured, not guessed. Dropping the middle pass
    // and trimming the largest radius costs nothing visible: the halo still reads as a soft bloom,
    // the core still gives it a bright edge. Blur cost scales with both area and radius, so the
    // radii are the first thing to cut if this ever needs to get cheaper again.
    // Change 14.6: the luminance has to stay as close to the border as possible, so both the band
    // and its blur are roughly halved. This also *reduces* GPU cost, so the two-pass frame-rate fix
    // from the previous round is not put at risk by the longer duration.
    Box(modifier.alpha(alpha)) {
        GlowPass(phase, blur = shortEdge * 0.022f, stroke = shortEdge * 0.048f, alpha = 0.58f)
        GlowPass(counter, blur = shortEdge * 0.006f, stroke = shortEdge * 0.016f, alpha = 0.88f)
    }
}

@Composable
private fun GlowPass(phase: Float, blur: Dp, stroke: Dp, alpha: Float) {
    Canvas(
        Modifier
            .fillMaxSize()
            .blur(blur)
            .alpha(alpha)
    ) {
        drawPerimeter(phase, stroke.toPx())
    }
}

/**
 * Stroke the screen's rounded perimeter with the rotating sweep gradient.
 *
 * Inset by half the stroke width so the band hugs the edge, and drawn as a stroke rather than a
 * fill so nothing is ever painted over the middle of the screen.
 */
private fun DrawScope.drawPerimeter(phase: Float, strokeWidth: Float) {
    val inset = strokeWidth / 2f
    val brush = Brush.sweepGradient(
        colorStops = Array(SWEEP_SAMPLES + 1) { i ->
            val t = i.toFloat() / SWEEP_SAMPLES
            t to ringColor(t + phase)
        },
        center = center,
    )
    drawRoundRect(
        brush = brush,
        topLeft = Offset(inset, inset),
        size = Size(size.width - strokeWidth, size.height - strokeWidth),
        cornerRadius = CornerRadius(CORNER_DP.dp.toPx()),
        style = Stroke(width = strokeWidth),
    )
}

/** Cyclic blue -> azure -> cyan -> indigo ring, wrapping smoothly at 1.0 (change 14.6). */
private fun ringColor(t: Float): Color {
    val ring = listOf(
        LeumasColors.GlowBlue,
        LeumasColors.GlowAzure,
        LeumasColors.GlowCyan,
        LeumasColors.GlowIndigo,
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

/**
 * "Recipe received" — the luminous card that follows the glow.
 *
 * **Change 14.6 stripped this back to two things**, because the previous version crowded the middle
 * of the screen: the headline, and the modules the sequence uses joined by arrows in a small font.
 * The sequence name, the lesson sentence and the separate ACTIVE list are all gone — the sequence
 * number already has its own permanent card in the bottom-left corner, so repeating it here was
 * noise on top of the animation.
 *
 * The recipe itself is hard-coded app-side and looked up from the sequence number; see
 * [com.leumas.robot.domain.Recipe]. The MCU never sends recipe text.
 */
@Composable
private fun RecipeCard(recipe: Recipe, modifier: Modifier = Modifier) {
    Box(modifier) {
        // Soft coloured bloom behind the card, so it reads as luminous rather than as a dialog.
        Box(
            Modifier
                .matchParentSize()
                .blur(30.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            LeumasColors.GlowBlue.copy(alpha = 0.50f),
                            LeumasColors.GlowAzure.copy(alpha = 0.50f),
                            LeumasColors.GlowCyan.copy(alpha = 0.42f),
                        )
                    ),
                    RoundedCornerShape(22.dp),
                )
        )

        Column(
            Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(LeumasGlass.panel(0.90f))
                .border(
                    width = 1.5.dp,
                    brush = Brush.horizontalGradient(
                        listOf(
                            LeumasColors.GlowCyan.copy(alpha = 0.85f),
                            LeumasColors.GlowAzure.copy(alpha = 0.85f),
                            LeumasColors.GlowIndigo.copy(alpha = 0.85f),
                        )
                    ),
                    shape = RoundedCornerShape(20.dp),
                )
                .padding(horizontal = 30.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Change 33: the spacing is per-child now rather than one uniform gap. The name
            // belongs to the heading above it and the chain is a separate thought, so an even
            // 14 dp between all three would have grouped them wrongly.
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = "RECIPE RECEIVED",
                color = LeumasColors.OnSurface,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
            )

            // The recipe's NAME, which the card never showed before change 33 -- it went straight
            // from the heading to the process chain, so the thing the operator selected with the
            // button was the one thing not on screen.
            //
            // Slightly smaller than the heading and equally bold, with the SAME letterSpacing:
            // monospace centres on advance width, so two centred lines at different tracking sit
            // visibly off each other's axis. Matching it is what makes them line up.
            Text(
                text = recipe.name,
                color = LeumasColors.OnSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )

        }
    }
}

private const val SWEEP_SAMPLES = 32
private const val CORNER_DP = 46

// Fractions of the intro clock. The glow opens the announcement and outlives the card slightly.
private val GLOW_IN = 0.00f..0.07f
private val GLOW_OUT = 0.93f..1.00f
private val CARD_IN = 0.10f..0.20f
private val CARD_OUT = 0.82f..0.94f
