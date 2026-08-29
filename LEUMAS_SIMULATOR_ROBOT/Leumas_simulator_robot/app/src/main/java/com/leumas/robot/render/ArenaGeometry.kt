package com.leumas.robot.render

import com.leumas.robot.calibration.ArenaEdge
import com.leumas.robot.calibration.EdgePoint

/**
 * Maps the normalised (0..1, 0..1) workspace onto actual pixels, and holds the robot's physical
 * proportions.
 *
 * The arena deliberately fills the **whole** canvas rather than being letterboxed to a fixed
 * aspect. The screen edge *is* the boundary between the visualisation and the real room, so it has
 * to be the real screen edge for the "reaching outside" illusion to work.
 *
 * All robot dimensions are expressed as fractions of arena height, so the machine keeps its
 * proportions on any device.
 */
data class ArenaGeometry(
    val widthPx: Float,
    val heightPx: Float,
) {
    fun toPx(nx: Float, ny: Float) = Vec2(nx * widthPx, ny * heightPx)

    fun toPx(point: EdgePoint): Vec2 {
        val (nx, ny) = point.toNormalized()
        return toPx(nx, ny)
    }

    fun toNormalized(p: Vec2) = Vec2(
        (p.x / widthPx).coerceIn(0f, 1f),
        (p.y / heightPx).coerceIn(0f, 1f),
    )

    // --- Rail -------------------------------------------------------------------------------

    /** Pixel y of the rail centreline. Low enough that the arm has headroom above it. */
    val railY: Float get() = heightPx * RAIL_Y_FRACTION

    /** Travel limits of the carriage, inset so the rail's end supports stay on screen. */
    val railMinX: Float get() = widthPx * RAIL_INSET
    val railMaxX: Float get() = widthPx * (1f - RAIL_INSET)
    val railCentreX: Float get() = (railMinX + railMaxX) / 2f

    // --- Robot proportions ------------------------------------------------------------------

    val lowerArmLength: Float get() = heightPx * LOWER_ARM
    val upperArmLength: Float get() = heightPx * UPPER_ARM
    val forearmLength: Float get() = heightPx * FOREARM
    val shoulderHeight: Float get() = heightPx * SHOULDER_HEIGHT
    val baseScale: Float get() = heightPx * BASE_SCALE

    /** How far past the boundary the end effector reaches, so it visibly leaves the workspace. */
    val reachOvershoot: Float get() = heightPx * REACH_OVERSHOOT

    /** Shoulder pivot for a carriage sitting at [carriageX]. */
    fun shoulderAt(carriageX: Float) = Vec2(carriageX, railY - shoulderHeight)

    /**
     * Push an interaction point outward, past its edge, so the gripper ends up off-screen.
     *
     * Spec is explicit that the robot must not merely stop at the boundary — the end effector may
     * and should partially disappear beyond it.
     */
    fun overshoot(point: EdgePoint): Vec2 {
        val base = toPx(point)
        val d = reachOvershoot
        return when (point.edge) {
            ArenaEdge.LEFT -> Vec2(base.x - d, base.y)
            ArenaEdge.RIGHT -> Vec2(base.x + d, base.y)
            ArenaEdge.TOP -> Vec2(base.x, base.y - d)
            ArenaEdge.BOTTOM -> Vec2(base.x, base.y + d)
        }
    }

    /**
     * Where the carriage should park to service [point].
     *
     * Left/right modules are reached from the corresponding end of the rail; top/bottom modules
     * are reached from directly beneath them, clamped to the rail's travel.
     */
    fun carriageXFor(point: EdgePoint): Float = when (point.edge) {
        ArenaEdge.LEFT -> railMinX
        ArenaEdge.RIGHT -> railMaxX
        ArenaEdge.TOP, ArenaEdge.BOTTOM ->
            (point.position * widthPx).coerceIn(railMinX, railMaxX)
    }

    companion object {
        /*
         * These are not free choices — they are sized so the arm can actually reach every edge.
         *
         * With overshoot, the extreme targets sit at y = -0.06H (past the top) and y = 1.06H
         * (past the bottom). The shoulder is placed near the midpoint of that span so neither
         * extreme dominates, which puts it at roughly 0.49H and the rail at 0.49H + SHOULDER_HEIGHT.
         *
         * Vertical reach comes only from the two-link chain, because the parallelogram holds the
         * forearm horizontal and it therefore contributes nothing vertically. The worst case is a
         * bottom-edge module offset by one forearm length:
         *     hypot(FOREARM, 1.06H - 0.49H) = hypot(0.11H, 0.57H) ~= 0.58H
         * so LOWER_ARM + UPPER_ARM must exceed that, with margin. 0.60H does.
         *
         * ReachDiagnosticTest measures this for every default slot; if these change, run it.
         */
        const val RAIL_Y_FRACTION = 0.66f
        const val RAIL_INSET = 0.10f

        const val LOWER_ARM = 0.32f
        const val UPPER_ARM = 0.28f
        const val FOREARM = 0.11f
        const val SHOULDER_HEIGHT = 0.17f
        const val BASE_SCALE = 0.16f
        const val REACH_OVERSHOOT = 0.06f
    }
}
