package com.leumas.robot.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Bridge from the Compose-free geometry types into Compose drawing coordinates. */
fun Vec2.toOffset() = Offset(x, y)

/**
 * Shading helpers that give flat vector shapes real depth.
 *
 * The scene assumes a single key light from the upper left. Three things sell the third dimension:
 *
 *  1. **Extrusion** — every part is drawn twice, once offset along [DEPTH] in a dark tone to form
 *     its side wall, then the lit face on top. That is what turns a silhouette into a solid.
 *  2. **Cross-axis gradients** — a bright edge, the body colour, then a dark edge reads as a
 *     cylinder rather than a stripe.
 *  3. **Rim light** — a thin cool highlight on the upper-left edge, separating each part from
 *     whatever sits behind it.
 */
object Shade {

    /** Direction and distance of the extruded side wall, as a fraction of arena height. */
    const val DEPTH_X = 0.0075f
    const val DEPTH_Y = 0.0095f

    /** Cool rim light from the key direction. */
    val RIM = Color(0xFF9FD2FF)

    fun depth(h: Float) = Vec2(h * DEPTH_X, h * DEPTH_Y)

    /** Cross-axis gradient for a limb running from [a] to [b]. */
    fun acrossAxis(
        a: Vec2,
        b: Vec2,
        halfWidth: Float,
        hi: Color,
        base: Color,
        lo: Color,
    ): Brush {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy).coerceAtLeast(0.0001f)
        val nx = -dy / len
        val ny = dx / len
        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        return Brush.linearGradient(
            0.00f to hi,
            0.30f to base,
            0.72f to base,
            1.00f to lo,
            start = Offset(mx + nx * halfWidth, my + ny * halfWidth),
            end = Offset(mx - nx * halfWidth, my - ny * halfWidth),
        )
    }

    /** Top-lit gradient for boxy parts. */
    fun vertical(top: Float, bottom: Float, hi: Color, base: Color, lo: Color): Brush =
        Brush.linearGradient(
            0.00f to hi,
            0.26f to base,
            0.74f to base,
            1.00f to lo,
            start = Offset(0f, top),
            end = Offset(0f, bottom),
        )

    /** Radial shading for a genuinely round part, lit from the upper left. */
    fun sphere(centre: Vec2, radius: Float, hi: Color, base: Color, lo: Color): Brush =
        Brush.radialGradient(
            0.00f to hi,
            0.45f to base,
            1.00f to lo,
            center = Offset(centre.x - radius * 0.38f, centre.y - radius * 0.38f),
            radius = radius * 1.65f,
        )

    fun darken(c: Color, factor: Float) = Color(
        red = c.red * factor,
        green = c.green * factor,
        blue = c.blue * factor,
        alpha = c.alpha,
    )
}

/**
 * A tapered limb with rounded ends, extruded for depth.
 *
 * Drawn as a quad between two circular caps rather than a stroked line, so the segment can taper
 * the way a real casting does.
 */
fun DrawScope.drawTaperedLimb(
    a: Vec2,
    b: Vec2,
    widthA: Float,
    widthB: Float,
    hi: Color,
    base: Color,
    lo: Color,
    arenaHeight: Float,
    rim: Boolean = true,
) {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = hypot(dx, dy)
    if (len < 0.001f) return
    val nx = -dy / len
    val ny = dx / len
    val ha = widthA / 2f
    val hb = widthB / 2f
    val d = Shade.depth(arenaHeight)

    fun body(ox: Float, oy: Float) = Path().apply {
        moveTo(a.x + nx * ha + ox, a.y + ny * ha + oy)
        lineTo(b.x + nx * hb + ox, b.y + ny * hb + oy)
        lineTo(b.x - nx * hb + ox, b.y - ny * hb + oy)
        lineTo(a.x - nx * ha + ox, a.y - ny * ha + oy)
        close()
    }

    // Extruded side wall.
    val side = Shade.darken(lo, 0.55f)
    drawCircle(side, ha, Offset(a.x + d.x, a.y + d.y))
    drawCircle(side, hb, Offset(b.x + d.x, b.y + d.y))
    drawPath(body(d.x, d.y), side)

    // Lit face.
    drawCircle(base, ha, a.toOffset())
    drawCircle(base, hb, b.toOffset())
    drawPath(body(0f, 0f), Shade.acrossAxis(a, b, maxOf(ha, hb), hi, base, lo))

    if (rim) {
        val inset = 0.74f
        val edge = Path().apply {
            moveTo(a.x + nx * ha * inset, a.y + ny * ha * inset)
            lineTo(b.x + nx * hb * inset, b.y + ny * hb * inset)
        }
        drawPath(
            edge,
            Shade.RIM.copy(alpha = 0.30f),
            style = Stroke(width = maxOf(1f, arenaHeight * 0.0022f), cap = StrokeCap.Round),
        )
    }
}

/**
 * A joint housing drawn as a real cylinder: extruded barrel, radially shaded cap, machined rim and
 * a bolt circle whose bolts catch the light individually.
 */
fun DrawScope.drawJointHousing(
    centre: Vec2,
    radius: Float,
    body: Color,
    rim: Color,
    boltColor: Color,
    arenaHeight: Float,
    bolts: Int = 6,
) {
    val d = Shade.depth(arenaHeight)

    // Barrel behind the face.
    drawCircle(Shade.darken(body, 0.45f), radius, Offset(centre.x + d.x, centre.y + d.y))

    // Machined outer rim, then the recessed face.
    drawCircle(Shade.sphere(centre, radius, rim, Shade.darken(rim, 0.72f), Shade.darken(rim, 0.34f)), radius, centre.toOffset())
    drawCircle(
        Shade.sphere(centre, radius * 0.84f, Shade.darken(body, 1.9f), body, Shade.darken(body, 0.5f)),
        radius * 0.84f,
        centre.toOffset(),
    )
    // Hub.
    drawCircle(
        Shade.sphere(centre, radius * 0.34f, rim, Shade.darken(rim, 0.7f), Shade.darken(rim, 0.4f)),
        radius * 0.34f,
        centre.toOffset(),
    )

    if (bolts > 0 && radius > 6f) {
        val r = radius * 0.64f
        val boltR = radius * 0.095f
        repeat(bolts) { i ->
            val t = (i.toFloat() / bolts) * 2f * Math.PI.toFloat()
            val p = Vec2(centre.x + r * cos(t), centre.y + r * sin(t))
            drawCircle(Shade.darken(boltColor, 0.4f), boltR, Offset(p.x + boltR * 0.3f, p.y + boltR * 0.3f))
            drawCircle(
                Shade.sphere(p, boltR, Color.White.copy(alpha = 0.9f), boltColor, Shade.darken(boltColor, 0.5f)),
                boltR,
                p.toOffset(),
            )
        }
    }

    // Rim light along the upper-left arc.
    drawArc(
        color = Shade.RIM.copy(alpha = 0.35f),
        startAngle = 170f,
        sweepAngle = 110f,
        useCenter = false,
        topLeft = Offset(centre.x - radius, centre.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = maxOf(1f, arenaHeight * 0.002f)),
    )
}

/** Soft elliptical contact shadow on the floor. */
fun DrawScope.drawContactShadow(centre: Vec2, halfWidth: Float, halfHeight: Float, strength: Float) {
    drawOval(
        brush = Brush.radialGradient(
            0f to Color.Black.copy(alpha = 0.55f * strength),
            1f to Color.Transparent,
            center = Offset(centre.x, centre.y),
            radius = halfWidth.coerceAtLeast(1f),
        ),
        topLeft = Offset(centre.x - halfWidth, centre.y - halfHeight),
        size = Size(halfWidth * 2f, halfHeight * 2f),
    )
}
