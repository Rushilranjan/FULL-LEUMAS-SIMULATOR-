package com.leumas.robot.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.leumas.robot.ui.theme.LeumasColors
import kotlin.math.cos

/**
 * The screw-driven linear axis the machine rides on (spec §9).
 *
 * The lead screw's helix is drawn as a series of arcs whose phase is offset by the carriage
 * position, so the screw visibly turns as the carriage travels — which is what sells the axis as
 * driven rather than decorative.
 */
object RailArtist {

    fun DrawScope.drawRail(geometry: ArenaGeometry, carriageX: Float) {
        val y = geometry.railY
        val h = geometry.heightPx
        val bodyHeight = h * 0.036f
        val left = geometry.railMinX - h * 0.06f
        val right = geometry.railMaxX + h * 0.06f

        // Rail body.
        drawRect(
            brush = Shade.vertical(
                y - bodyHeight / 2f, y + bodyHeight / 2f,
                LeumasColors.ChromeLo, Color(0xFF3A424B), Color(0xFF20262C),
            ),
            topLeft = Offset(left, y - bodyHeight / 2f),
            size = Size(right - left, bodyHeight),
        )

        // Lead screw running through the body.
        val screwR = h * 0.011f
        drawRect(
            brush = Shade.vertical(
                y - screwR, y + screwR,
                LeumasColors.ChromeHi, LeumasColors.Chrome, LeumasColors.ChromeLo,
            ),
            topLeft = Offset(left, y - screwR),
            size = Size(right - left, screwR * 2f),
        )

        // Helical thread. Phase follows the carriage so the screw appears to rotate.
        val pitch = h * 0.026f
        val phase = (carriageX / pitch) % 1f
        var x = left
        while (x < right) {
            val px = x + phase * pitch
            val lean = screwR * 1.1f
            drawLine(
                color = LeumasColors.ChromeLo.copy(alpha = 0.85f),
                start = Offset(px - lean, y - screwR),
                end = Offset(px + lean, y + screwR),
                strokeWidth = maxOf(1f, h * 0.0022f),
            )
            x += pitch
        }

        // End supports / bearing blocks.
        listOf(left, right).forEach { ex ->
            val bw = h * 0.030f
            val bh = h * 0.060f
            drawRect(
                brush = Shade.vertical(
                    y - bh / 2f, y + bh / 2f,
                    LeumasColors.BlackHi, Color(0xFF2A2F35), LeumasColors.Black,
                ),
                topLeft = Offset(ex - bw / 2f, y - bh / 2f),
                size = Size(bw, bh),
            )
            drawCircle(LeumasColors.ChromeLo, h * 0.008f, Offset(ex, y))
        }
    }

    /** The carriage and its mounting plate, which the robot base bolts onto. */
    fun DrawScope.drawCarriage(geometry: ArenaGeometry, carriageX: Float) {
        val y = geometry.railY
        val h = geometry.heightPx
        val w = h * 0.115f
        val ch = h * 0.052f

        drawRect(
            brush = Shade.vertical(
                y - ch / 2f, y + ch / 2f,
                Color(0xFF5A636D), Color(0xFF3C444D), Color(0xFF23282E),
            ),
            topLeft = Offset(carriageX - w / 2f, y - ch / 2f),
            size = Size(w, ch),
        )
        drawRect(
            color = Color(0xFF171B20),
            topLeft = Offset(carriageX - w / 2f, y - ch / 2f),
            size = Size(w, ch),
            style = Stroke(width = maxOf(1f, h * 0.0018f)),
        )

        // Mounting plate on top.
        val pw = h * 0.135f
        val ph = h * 0.016f
        drawRect(
            brush = Shade.vertical(
                y - ch / 2f - ph, y - ch / 2f,
                LeumasColors.BlackHi, Color(0xFF2C3137), LeumasColors.Black,
            ),
            topLeft = Offset(carriageX - pw / 2f, y - ch / 2f - ph),
            size = Size(pw, ph),
        )
        // Plate fasteners.
        listOf(-0.40f, -0.14f, 0.14f, 0.40f).forEach { f ->
            drawCircle(
                LeumasColors.ChromeLo,
                h * 0.0045f,
                Offset(carriageX + pw * f, y - ch / 2f - ph / 2f),
            )
        }
    }

    /**
     * Faint travel indicator so an audience can see how far along the axis the machine is even
     * when it is stationary.
     */
    fun DrawScope.drawRailTravelHint(geometry: ArenaGeometry, carriageX: Float) {
        val y = geometry.railY
        val h = geometry.heightPx
        val t = ((carriageX - geometry.railMinX) /
            (geometry.railMaxX - geometry.railMinX).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val pulse = 0.25f + 0.10f * cos(t * Math.PI.toFloat() * 2f)
        drawLine(
            color = LeumasColors.Info.copy(alpha = pulse),
            start = Offset(geometry.railMinX, y + h * 0.040f),
            end = Offset(carriageX, y + h * 0.040f),
            strokeWidth = maxOf(1f, h * 0.003f),
        )
    }
}
