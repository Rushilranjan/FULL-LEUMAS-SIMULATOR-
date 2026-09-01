package com.leumas.robot.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import com.leumas.robot.calibration.EdgePoint
import com.leumas.robot.ui.theme.LeumasColors
import kotlin.math.hypot

/** A conveyor/strip transfer in progress: blender→packaging or vessel→bottling. */
data class ConveyorFlow(
    val fromSlot: Int,
    val toSlot: Int,
    val from: EdgePoint,
    val to: EdgePoint,
    /** 0..1 through the swipe. The caller owns the duration. */
    val progress: Float,
)

/**
 * Draws non-robot material transfers.
 *
 * Spec §14 is emphatic that blender→packaging and vessel→bottling are *not* robot-arm operations —
 * on the rig they are LED strip swipes. So they get a deliberately different visual language from
 * a robot transfer: no arm involvement at all, just a chevron band flowing between the two
 * interaction points. An audience should never confuse this with the machine carrying something.
 */
object ConveyorArtist {

    fun DrawScope.drawConveyorFlow(flow: ConveyorFlow, geometry: ArenaGeometry) {
        val a = geometry.toPx(flow.from)
        val b = geometry.toPx(flow.to)
        val h = geometry.heightPx

        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len
        val nx = -uy
        val ny = ux
        val bandHalf = h * 0.026f

        // Track.
        val track = Path().apply {
            moveTo(a.x + nx * bandHalf, a.y + ny * bandHalf)
            lineTo(b.x + nx * bandHalf, b.y + ny * bandHalf)
            lineTo(b.x - nx * bandHalf, b.y - ny * bandHalf)
            lineTo(a.x - nx * bandHalf, a.y - ny * bandHalf)
            close()
        }
        drawPath(track, LeumasColors.Surface.copy(alpha = 0.55f))
        drawPath(
            track,
            LeumasColors.Transfer.copy(alpha = 0.30f),
            style = Stroke(width = maxOf(1f, h * 0.002f)),
        )

        // Chevrons marching from source to destination.
        val spacing = h * 0.055f
        val phase = (flow.progress * spacing * 6f) % spacing
        val chevronHalf = bandHalf * 0.62f
        var d = phase
        while (d < len) {
            val px = a.x + ux * d
            val py = a.y + uy * d
            // Leading chevrons are brighter, so direction of travel is unmistakable.
            val reached = d / len <= flow.progress
            val alpha = if (reached) 0.95f else 0.28f
            val tip = h * 0.016f
            val chevron = Path().apply {
                moveTo(px - ux * tip + nx * chevronHalf, py - uy * tip + ny * chevronHalf)
                lineTo(px, py)
                lineTo(px - ux * tip - nx * chevronHalf, py - uy * tip - ny * chevronHalf)
            }
            drawPath(
                chevron,
                LeumasColors.Transfer.copy(alpha = alpha),
                style = Stroke(width = maxOf(1.5f, h * 0.005f), cap = StrokeCap.Round),
            )
            d += spacing
        }

        // Material bead riding the band.
        val bead = Offset(a.x + ux * len * flow.progress, a.y + uy * len * flow.progress)
        drawCircle(LeumasColors.Transfer, h * 0.012f, bead)
        drawCircle(Color.White.copy(alpha = 0.65f), h * 0.005f, bead)

        // Endpoint markers, sized so they read as "off-screen station", not as a module.
        drawCircle(LeumasColors.Transfer.copy(alpha = 0.55f), h * 0.010f, a.toOffset())
        drawCircle(LeumasColors.Transfer.copy(alpha = 0.55f), h * 0.010f, b.toOffset())
    }
}
