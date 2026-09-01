package com.leumas.robot.domain

import com.leumas.robot.protocol.ProductionLine
import com.leumas.robot.protocol.SlotMap

/** One stage of a recipe: the modules that run together before material moves on. */
data class RecipeStage(
    val label: String,
    val slots: List<Int>,
) {
    val moduleNames: List<String> get() = slots.map { SlotMap.displayName(it) }
}

/**
 * What a sequence actually makes, mirrored from the firmware.
 *
 * Change 13.6 asks for a "recipe received" card whenever the board button selects a new sequence.
 * The MCU only ever sends `SEQ:<n>` - it has no recipe message and the spec forbids inventing one -
 * so the recipe is held here, keyed by sequence number. This is the same pattern
 * [com.leumas.robot.protocol.SlotMap] and [com.leumas.robot.protocol.Timings] already use: static
 * rig knowledge mirrored from the firmware so the app can describe what it is watching, while the
 * MCU stays the sole source of truth for *when* anything happens.
 *
 * Mirrors `App/solid_seq.c` and the `SS1_*` constants in `Core/Inc/config.h`. Change 20 turned
 * that file into a small engine driven by a per-sequence table, so there are two solid sequences
 * again and the board button cycles between them. The liquid line still has none: its slot map is
 * in the firmware config awaiting one, and its recipe belongs here when it lands.
 */
data class Recipe(
    val sequence: Int,
    val name: String,
    val lesson: String,
    val line: ProductionLine,
    val stages: List<RecipeStage>,
    val activeSlots: List<Int>,
) {
    /** "MACRO + GRAVIMETRIC  ->  BLENDER  ->  PACKAGING", for the recipe card. */
    val chain: String get() = stages.joinToString("  ->  ") { it.label }

    companion object {

        /**
         * Complete production cycles per run, mirroring `SS1_CYCLES`.
         *
         * This is the segment count the progress bar renders (change 13.7). Solid Sequence 1 runs
         * four cycles and then parks; it never rolls into a fifth on its own.
         */
        const val CYCLES_PER_RUN = 4

        /**
         * Solid Sequence 1 drives slots 2, 3, 5 and 6 only - `SS1_SLOT_*` in `Core/Inc/config.h`,
         * which are 0-based there and land on these 1-based wire slots. Slot 1 (Macro 1) and slot 4
         * (Blender 1) are deliberately not used by this sequence.
         */
        private val SS1_STAGES = listOf(
            RecipeStage("MACRO + GRAVIMETRIC", listOf(2, 3)),
            RecipeStage("BLENDER", listOf(5)),
            RecipeStage("PACKAGING", listOf(6)),
        )

        /**
         * Solid Sequence 2 drives the whole solid line: both macros, the gravimetric, both
         * blenders and packaging.
         *
         * Two blend lines running in parallel, sharing one gravimetric - macro 1 and the
         * gravimetric make blender 1's batch, macro 2 and the gravimetric make blender 2's, and
         * packaging waits for both before it ships a lot. Written as three stages rather than the
         * five the arm actually performs, because the two lines are concurrent: showing them
         * sequentially would misdescribe the process and would make the progress bar step twice
         * for what is really one stage.
         */
        private val SS2_STAGES = listOf(
            RecipeStage("MACRO 1 + 2 + GRAVIMETRIC", listOf(1, 2, 3)),
            RecipeStage("BLENDER 1 + 2", listOf(4, 5)),
            RecipeStage("PACKAGING", listOf(6)),
        )

        val ALL: Map<Int, Recipe> = linkedMapOf(
            1 to Recipe(
                sequence = 1,
                name = "SOLID SEQUENCE 1",
                lesson = "Four cycles: macro and gravimetric are blended, packaged and shipped, " +
                    "with the feeders recharging in the gaps.",
                line = ProductionLine.SOLID,
                stages = SS1_STAGES,
                activeSlots = listOf(2, 3, 5, 6),
            ),
            2 to Recipe(
                sequence = 2,
                name = "SOLID SEQUENCE 2",
                lesson = "Four cycles on the whole solid line: two blend lines run in parallel " +
                    "sharing one gravimetric, and packaging ships a lot once both are in.",
                line = ProductionLine.SOLID,
                stages = SS2_STAGES,
                activeSlots = listOf(1, 2, 3, 4, 5, 6),
            ),
        )

        /**
         * Recipe for a sequence number, or null if the MCU announced one we do not know.
         *
         * Sequences 1 and 2 exist in the firmware; the board button cycles between them and
         * announces the choice with `RECIPE:<n>`. An unknown number is shown as-is elsewhere
         * rather than discarded, but there is no card to draw for it.
         */
        fun forSequence(sequence: Int): Recipe? = ALL[sequence]
    }
}

/**
 * How far through the run the plant is, for the bottom-centre progress bar (change 13.7).
 *
 * [cycleCount] segments are drawn; [currentCycle] is 1-based. Everything here is derived from
 * observed MCU cues - no clock, no prediction (spec 13).
 */
data class CycleProgress(
    val cycleCount: Int,
    val currentCycle: Int,
    val fraction: Float,
    val complete: Boolean = false,
) {
    /** Overall 0..1 across every cycle, for a single continuous fill. */
    val overall: Float
        get() = if (cycleCount <= 0) {
            0f
        } else {
            (((currentCycle - 1).coerceAtLeast(0) + fraction) / cycleCount).coerceIn(0f, 1f)
        }

    companion object {

        fun start(recipe: Recipe?): CycleProgress = CycleProgress(
            cycleCount = if (recipe == null) 0 else Recipe.CYCLES_PER_RUN,
            currentCycle = 1,
            fraction = 0f,
        )

        /**
         * Advance the fill *within* the current cycle on a `RUN:<slot>` cue.
         *
         * Deliberately does NOT decide when a cycle ends. An earlier version inferred that from the
         * stage order wrapping backwards, which Solid Sequence 1 breaks: `S_PACK_FINISH` re-runs the
         * blender (stage 1) *after* packaging has already started (stage 2), so every cycle contains
         * a legitimate backwards step and the heuristic counted two cycles for each real one.
         * `LOT:DONE` is an exact, unambiguous per-cycle delimiter, so [completeCycle] owns that.
         *
         * The fraction is monotonic within a cycle for the same reason - it takes the furthest stage
         * reached, so the bar never slides backwards when the blender picks up again.
         */
        fun advance(
            current: CycleProgress?,
            recipe: Recipe?,
            slot: Int,
            previousStage: Int,
        ): Pair<CycleProgress, Int> {
            if (recipe == null || current == null) {
                return (current ?: CycleProgress(0, 1, 0f)) to previousStage
            }
            val stage = recipe.stages.indexOfFirst { slot in it.slots }
            if (stage < 0) return current to previousStage

            val fraction = (stage + 1).toFloat() / recipe.stages.size
            return current.copy(
                fraction = maxOf(current.fraction, fraction),
                complete = false,
            ) to maxOf(stage, previousStage)
        }

        /**
         * One cycle shipped, on `LOT:DONE`.
         *
         * The firmware sends this once per cycle - four times per run - not once per run, and sends
         * nothing at all when the fourth finishes. So the last cycle completing is the only signal
         * that the whole run is over.
         */
        fun completeCycle(current: CycleProgress?): CycleProgress? {
            if (current == null) return null
            val last = current.currentCycle >= current.cycleCount
            return if (last) {
                current.copy(fraction = 1f, complete = true)
            } else {
                current.copy(currentCycle = current.currentCycle + 1, fraction = 0f, complete = false)
            }
        }
    }
}
