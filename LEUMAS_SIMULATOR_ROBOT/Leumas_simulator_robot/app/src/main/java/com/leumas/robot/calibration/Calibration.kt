package com.leumas.robot.calibration

import com.leumas.robot.protocol.SlotMap
import kotlinx.serialization.Serializable

/** Which boundary of the workspace a physical module sits beyond. */
enum class ArenaEdge { LEFT, TOP, RIGHT, BOTTOM }

/**
 * Where the robot reaches to interact with one physical module.
 *
 * This is *not* a module position on screen — the modules are outside the display and are never
 * drawn. It is the point on the workspace boundary the arm reaches toward (and past) to touch the
 * real machine standing there.
 *
 * [position] runs 0..1 along the named edge: left-to-right for TOP/BOTTOM, top-to-bottom for
 * LEFT/RIGHT. Storing edge + offset rather than raw pixels keeps calibration valid across screen
 * sizes, as spec §6 and §24 require.
 */
@Serializable
data class EdgePoint(
    val edge: ArenaEdge,
    val position: Float,
) {
    /** Normalised (0..1, 0..1) location of this point on the arena boundary. */
    fun toNormalized(): Pair<Float, Float> {
        val p = position.coerceIn(0f, 1f)
        return when (edge) {
            ArenaEdge.LEFT -> 0f to p
            ArenaEdge.RIGHT -> 1f to p
            ArenaEdge.TOP -> p to 0f
            ArenaEdge.BOTTOM -> p to 1f
        }
    }

    companion object {
        /**
         * Snap an arbitrary normalised tap to the closest boundary.
         *
         * Calibration is a tap "near or on" an edge (spec, calibration section), so the user never
         * has to hit a 1-pixel line.
         */
        fun snapToNearestEdge(nx: Float, ny: Float): EdgePoint {
            val x = nx.coerceIn(0f, 1f)
            val y = ny.coerceIn(0f, 1f)
            val distances = listOf(
                ArenaEdge.LEFT to x,
                ArenaEdge.RIGHT to (1f - x),
                ArenaEdge.TOP to y,
                ArenaEdge.BOTTOM to (1f - y),
            )
            val (edge, _) = distances.minByOrNull { it.second }!!
            val position = when (edge) {
                ArenaEdge.LEFT, ArenaEdge.RIGHT -> y
                ArenaEdge.TOP, ArenaEdge.BOTTOM -> x
            }
            return EdgePoint(edge, position)
        }
    }
}

/**
 * The full set of interaction points, keyed by 1-based firmware slot.
 *
 * Sized for all 11 slots. Nine of them are calibrated by the operator; Packaging (6) and Bottling
 * (11) are special stations per spec §7 and keep their defaults unless deliberately edited.
 */
@Serializable
data class Calibration(
    val points: Map<Int, EdgePoint> = emptyMap(),
) {
    fun pointFor(slot: Int): EdgePoint =
        points[slot] ?: DEFAULT.points[slot] ?: EdgePoint(ArenaEdge.TOP, 0.5f)

    fun with(slot: Int, point: EdgePoint): Calibration =
        if (!SlotMap.isValidSlot(slot)) this else copy(points = points + (slot to point))

    /** True once every one of the nine operator-calibrated modules has been placed. */
    val isComplete: Boolean
        get() = SlotMap.calibratable.all { points.containsKey(it.slot) }

    companion object {
        /**
         * Starting layout, following the bent physical line described in the firmware README:
         * the solid feed runs left-to-right along the top, turns down the right-hand side to the
         * blenders, and packaging sits along the bottom. The liquid line occupies the left edge
         * and the rest of the bottom.
         *
         * Only one line runs per sequence, so the two lines may share boundary space safely.
         * These are starting points — the rig gets calibrated for real.
         */
        val DEFAULT = Calibration(
            mapOf(
                // Solid line
                1 to EdgePoint(ArenaEdge.TOP, 0.15f),    // Macro 1
                2 to EdgePoint(ArenaEdge.TOP, 0.35f),    // Macro 2
                3 to EdgePoint(ArenaEdge.TOP, 0.55f),    // Gravimetric
                4 to EdgePoint(ArenaEdge.RIGHT, 0.30f),  // Blender 1
                5 to EdgePoint(ArenaEdge.RIGHT, 0.60f),  // Blender 2
                6 to EdgePoint(ArenaEdge.BOTTOM, 0.80f), // Packaging (special station)
                // Liquid line
                7 to EdgePoint(ArenaEdge.LEFT, 0.35f),   // Liquid Macro 1
                8 to EdgePoint(ArenaEdge.LEFT, 0.65f),   // Liquid Macro 2
                9 to EdgePoint(ArenaEdge.BOTTOM, 0.25f), // Vessel 1
                10 to EdgePoint(ArenaEdge.BOTTOM, 0.45f), // Vessel 2
                11 to EdgePoint(ArenaEdge.BOTTOM, 0.62f), // Bottling (special station)
            )
        )
    }
}
