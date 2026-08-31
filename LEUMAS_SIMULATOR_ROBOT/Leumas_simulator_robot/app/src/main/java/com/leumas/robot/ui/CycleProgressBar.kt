package com.leumas.robot.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leumas.robot.domain.CycleProgress
import com.leumas.robot.ui.theme.LeumasColors
import com.leumas.robot.ui.theme.LeumasGlass

/**
 * Run progress, bottom-centre (change 13.7).
 *
 * One segment per cycle, so the bar reads as "1x / 2x / 3x" straight from its shape: the segment
 * count is [CycleProgress.cycleCount], which comes from the firmware's batches-per-run constant.
 * Segments behind the current one are solid, the current one fills, the rest are empty.
 *
 * Everything shown here is derived from MCU cues — the fill advances when a module reports RUN and
 * completes on LOT:DONE. Nothing is timed or predicted (spec §13).
 *
 * The definition of a "cycle" is still being settled against the firmware, so this composable takes
 * whatever [CycleProgress] the reducer produces and draws it; the rule itself lives in one place,
 * [CycleProgress.advance].
 */
@Composable
fun CycleProgressBar(progress: CycleProgress?, modifier: Modifier = Modifier) {
    if (progress == null || progress.cycleCount <= 0) return

    val accent = if (progress.complete) LeumasColors.Running else LeumasColors.Info

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(LeumasGlass.panel())
            .border(1.dp, LeumasColors.Outline.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (progress.complete) "LOT COMPLETE" else "CYCLE",
                color = LeumasColors.OnSurfaceDim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
            )
            Text(
                text = "${progress.currentCycle}x / ${progress.cycleCount}x",
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }

        Row(
            modifier = Modifier.width(SEGMENT_WIDTH.dp * progress.cycleCount + GAP.dp * (progress.cycleCount - 1)),
            horizontalArrangement = Arrangement.spacedBy(GAP.dp),
        ) {
            for (cycle in 1..progress.cycleCount) {
                Segment(
                    fill = when {
                        cycle < progress.currentCycle -> 1f
                        cycle > progress.currentCycle -> 0f
                        else -> progress.fraction
                    },
                    accent = accent,
                    modifier = Modifier
                        .width(SEGMENT_WIDTH.dp)
                        .height(8.dp),
                )
            }
        }
    }
}

/**
 * One cycle's track.
 *
 * The fill animates, so an event-driven jump from one stage boundary to the next glides rather than
 * snapping — the underlying progress is a step function because the MCU only reports milestones.
 */
@Composable
private fun Segment(fill: Float, accent: Color, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = fill.coerceIn(0f, 1f),
        animationSpec = tween(650),
        label = "cycleFill",
    )
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(LeumasColors.OnSurfaceDim.copy(alpha = 0.22f))
    ) {
        if (animated > 0.001f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animated)
                    .background(
                        Brush.horizontalGradient(
                            listOf(accent.copy(alpha = 0.72f), accent)
                        )
                    )
            )
        }
    }
}

private const val SEGMENT_WIDTH = 92
private const val GAP = 6
