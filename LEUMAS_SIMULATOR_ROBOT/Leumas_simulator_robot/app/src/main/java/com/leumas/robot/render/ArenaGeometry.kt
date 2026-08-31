package com.leumas.robot.render

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
     * Push an interaction point outward, past the boundary, so the gripper ends up off-screen.
     *
     * Spec is explicit that the robot must not merely stop at the boundary — the end effector may
     * and should partially disappear beyond it.
     *
     * Change 13.2: the push is radial, away from the centre of the arena, rather than along the
     * stored edge's axis. A point calibrated into a corner is therefore driven out through the
     * corner instead of straight up or straight sideways, matching what [Arena3D] does in the 3-D
     * scene. This drives the calibration preview line, so what the operator is shown while
     * calibrating is what the arm will actually do.
     */
    fun overshoot(point: EdgePoint): Vec2 {
        val base = toPx(point)
        val dx = base.x - widthPx / 2f
        val dy = base.y - heightPx / 2f
        val length = kotlin.math.hypot(dx, dy)
        if (length < 1e-3f) return base
        val d = reachOvershoot
        return Vec2(base.x + dx / length * d, base.y + dy / length * d)
    }

    /**
     * Where the carriage should park to service [point].
     *
     * Directly beneath the point, clamped to the rail's travel — the same rule for every edge
     * (change 13.2). Left/right modules simply run the carriage out to its limit because they sit
     * beyond it, which is what leaves the base a diagonal angle to make up.
     */
    fun carriageXFor(point: EdgePoint): Float {
        val (nx, _) = point.toNormalized()
        return (nx * widthPx).coerceIn(railMinX, railMaxX)
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

        /**
         * Rail travel as an inset from each side of the arena.
         *
         * Derived from the 3-D workspace so the two renderers agree on where the carriage can go:
         * `0.5 * (1 - Arena3D.RAIL_HALF / Arena3D.ARENA_HALF_X)` = `0.5 * (1 - 1.45 / 2.20)`. If
         * either of those constants moves, move this with it.
         */
        const val RAIL_INSET = 0.17f

        const val LOWER_ARM = 0.32f
        const val UPPER_ARM = 0.28f
        const val FOREARM = 0.11f
        const val SHOULDER_HEIGHT = 0.17f
        const val BASE_SCALE = 0.16f
        const val REACH_OVERSHOOT = 0.06f
    }
}
