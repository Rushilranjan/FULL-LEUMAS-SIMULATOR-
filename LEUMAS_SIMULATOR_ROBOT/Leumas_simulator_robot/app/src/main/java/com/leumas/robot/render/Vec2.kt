package com.leumas.robot.render

import kotlin.math.hypot

/**
 * A plain 2D point.
 *
 * Deliberately not `androidx.compose.ui.geometry.Offset`: keeping the geometry and kinematics free
 * of any Compose type means they run in plain JVM unit tests with no Android stubbing. Conversion
 * happens once, at draw time.
 */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)

    fun distanceTo(o: Vec2): Float = hypot(o.x - x, o.y - y)

    companion object {
        val ZERO = Vec2(0f, 0f)
    }
}

/** Linear interpolation between two points. */
fun lerp(a: Vec2, b: Vec2, t: Float) = Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
