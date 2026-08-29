package com.leumas.robot.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leumas.robot.ble.BleConnectionState
import com.leumas.robot.domain.PlantState
import com.leumas.robot.protocol.TransferKind
import com.leumas.robot.ui.theme.LeumasColors

/** Shared card look: dark glass panel, hairline border, left accent bar. */
@Composable
private fun AccentCard(
    accent: Color,
    modifier: Modifier = Modifier,
    corner: Int = 12,
    pulse: Boolean = false,
    fillHeight: Boolean = false,
    content: @Composable () -> Unit,
) {
    val glow by if (pulse) {
        val transition = rememberInfiniteTransition(label = "cardGlow")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glowAlpha",
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0.55f) }
    }

    Row(
        modifier = modifier
            // IntrinsicSize.Min: the enclosing Box(Modifier.fillMaxSize()) passes loose
            // constraints whose max height is the whole screen, not this card's content. Without
            // pinning the Row's height to its children's intrinsic (natural) height first, the
            // accent bar's fillMaxHeight() below would fill that screen height instead of the
            // card's own height, blowing the card up to fill the entire vertical space — which is
            // exactly what happened on-device before this fix.
            //
            // The vertical task rail (change 12.1) is the deliberate exception: it *wants* to fill
            // the height its caller pins with fillMaxHeight(), so the accent bar runs the whole
            // left edge. There we skip the intrinsic pin and let the passed height stand.
            .then(if (fillHeight) Modifier else Modifier.height(IntrinsicSize.Min))
            .clip(RoundedCornerShape(corner.dp))
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF1A2028).copy(alpha = 0.94f),
                    1f to Color(0xFF0D1116).copy(alpha = 0.94f),
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    0f to accent.copy(alpha = 0.45f),
                    1f to LeumasColors.Outline.copy(alpha = 0.65f),
                ),
                shape = RoundedCornerShape(corner.dp),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Accent bar.
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(
                    Brush.verticalGradient(
                        0f to accent.copy(alpha = glow),
                        1f to accent.copy(alpha = glow * 0.45f),
                    )
                )
        )
        content()
    }
}

/**
 * The current task, top-left (change 12.1).
 *
 * This is the primary readout: it is the *only* indication of which physical modules are involved,
 * because the modules themselves stand outside the screen and are never drawn.
 *
 * It sits back in the top-left corner but **auto-fades** to a faint ghost while the robot is
 * servicing a left-side module and would otherwise be hidden behind it ([dimmed], driven by
 * `RobotViewModel.taskCardDimmed`). So the readout is legible almost always and disappears only in
 * the moments the arm reaches up-left toward Modules 1 & 2 — the animation is never disturbed. The
 * earlier vertical full-height rail was dropped because it stood on the rail band the whole time.
 */
@Composable
fun TaskCard(plant: PlantState, dimmed: Boolean = false, modifier: Modifier = Modifier) {
    val active = plant.currentTransfer != null || plant.runningSlots.isNotEmpty()
    val accent = when {
        plant.lotComplete -> LeumasColors.Running
        plant.currentTransfer?.kind == TransferKind.ROBOT -> LeumasColors.Transfer
        plant.currentTransfer != null -> LeumasColors.Info
        plant.runningSlots.isNotEmpty() -> LeumasColors.Running
        else -> LeumasColors.OnSurfaceDim
    }

    // Smooth both directions: ghost out as the robot enters the corner, fade back as it leaves.
    val alpha by animateFloatAsState(
        targetValue = if (dimmed) 0.14f else 1f,
        animationSpec = tween(320),
        label = "taskCardAlpha",
    )

    // Compact and single-line: the operation text must never wrap to a second line (a second line
    // dropped into Module 1's area and cluttered the arena). widthIn caps it so even the longest
    // string can't grow across the screen toward the robot.
    AccentCard(
        accent = accent,
        modifier = modifier
            .alpha(alpha)
            .widthIn(max = 340.dp),
        corner = 10,
        pulse = active,
    ) {
        Column(Modifier.padding(start = 11.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(accent, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "TASK",
                    color = LeumasColors.OnSurfaceDim,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = plant.operationText,
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Sequence number, bottom-left.
 *
 * Deliberately just the digit in its own standalone card — the requirements ask for the sequence
 * number in the bottom-left corner, and the user wants it kept as its own box (not merged into the
 * task readout).
 */
@Composable
fun SequenceCard(sequence: Int?, modifier: Modifier = Modifier) {
    val accent = if (sequence != null) LeumasColors.Info else LeumasColors.OnSurfaceDim

    AccentCard(accent = accent, modifier = modifier, corner = 10) {
        Text(
            text = sequence?.toString() ?: "—",
            color = accent,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 14.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        )
    }
}

// The LINK / CTRL status chips were removed at the user's request: BLE link state already lives in
// the Bluetooth panel, and controller readiness is implied by the task text ("WAITING FOR
// CONTROLLER" until the first cue arrives). The arena is cleaner without them.
