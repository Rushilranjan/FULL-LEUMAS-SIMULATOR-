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
 * The MCU only ever sends `SEQ:<n>` — it has no recipe message and the spec forbids inventing one —
 * so the recipe is held here, keyed by sequence number. This is the same pattern
 * [com.leumas.robot.protocol.SlotMap] and [com.leumas.robot.protocol.Timings] already use: static
 * rig knowledge mirrored from the firmware so the app can describe what it is watching, while the
 * MCU stays the sole source of truth for *when* anything happens.
 *
 * Mirrors `SOLID_RECIPE` / `LIQUID_RECIPE` in `App/lotmgr.c` and the `SEQ*_ACTIVE_MASK` /
 * `SOLID_LOTS_PER_RUN` constants in `Core/Inc/config.h`. If those change, change this.
 */
data class Recipe(
    val sequence: Int,
    val name: String,
    val lesson: String,
    val line: ProductionLine,
    val stages: List<RecipeStage>,
    val activeSlots: List<Int>,
) {
    /** "MACRO 1 + MACRO 2 + GRAVIMETRIC -> BLENDER 1 -> PACKAGING", for the recipe card. */
    val chain: String get() = stages.joinToString("  ->  ") { it.label }

    companion object {

        /**
         * Batches the firmware pushes per button press (`SOLID_LOTS_PER_RUN`).
         *
         * This is the cycle count the progress bar renders (change 13.7). The definition of a
         * "cycle" is still to be settled against the firmware, so it is deliberately one constant
         * and one function ([CycleProgress.advance]) rather than being spread through the reducer.
         */
        const val BATCHES_PER_RUN = 2

        private val SOLID_STAGES = listOf(
            RecipeStage("MACRO + GRAVIMETRIC", listOf(1, 2, 3)),
            RecipeStage("BLENDER", listOf(4, 5)),
            RecipeStage("PACKAGING", listOf(6)),
        )

        private val LIQUID_STAGES = listOf(
            RecipeStage("LIQUID MACRO", listOf(7, 8)),
            RecipeStage("VESSEL", listOf(9, 10)),
            RecipeStage("BOTTLING", listOf(11)),
        )

        val ALL: Map<Int, Recipe> = linkedMapOf(
            1 to Recipe(
                sequence = 1,
                name = "SOLID LINE - SINGLE BLENDER",
                lesson = "One blender serves both batches, so the second waits.",
                line = ProductionLine.SOLID,
                stages = SOLID_STAGES,
                activeSlots = listOf(1, 2, 3, 4, 6),
            ),
            2 to Recipe(
                sequence = 2,
                name = "SOLID LINE - TWO BLENDERS",
                lesson = "A second blender removes the wait; both batches run in parallel.",
                line = ProductionLine.SOLID,
                stages = SOLID_STAGES,
                activeSlots = listOf(1, 2, 3, 4, 5, 6),
            ),
            4 to Recipe(
                sequence = 4,
                name = "LIQUID LINE - SINGLE VESSEL",
                lesson = "One vessel serves both batches, so the second waits.",
                line = ProductionLine.LIQUID,
                stages = LIQUID_STAGES,
                activeSlots = listOf(7, 8, 9, 11),
            ),
            5 to Recipe(
                sequence = 5,
                name = "LIQUID LINE - TWO VESSELS",
                lesson = "A second vessel removes the wait; both batches run in parallel.",
                line = ProductionLine.LIQUID,
                stages = LIQUID_STAGES,
                activeSlots = listOf(7, 8, 9, 10, 11),
            ),
        )

        /**
         * Recipe for a sequence number, or null if the MCU announced one we do not know.
         *
         * `SEQ_CYCLE` walks 1 -> 2 -> 4 -> 5, and sequence 3 (continuous looping) is deferred in the
         * firmware, so anything else is genuinely unexpected and the card is simply not shown.
         */
        fun forSequence(sequence: Int): Recipe? = ALL[sequence]
    }
}

/**
 * How far through the run the plant is, for the bottom-centre progress bar (change 13.7).
 *
 * [cycleCount] segments are drawn; [currentCycle] is 1-based. Everything here is derived from
 * observed MCU cues — no clock, no prediction (spec §13).
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
            cycleCount = if (recipe == null) 0 else Recipe.BATCHES_PER_RUN,
            currentCycle = 1,
            fraction = 0f,
        )

        /**
         * Advance on a `RUN:<slot>` cue.
         *
         * A cycle here is one pass down the recipe stage chain: entering a stage moves the fill to
         * that stage boundary, and entering a stage at or before the current one means the next
         * batch has started down the chain, so the segment advances.
         *
         * **This rule is provisional** — the user is settling the definition of a cycle against the
         * firmware. It is intentionally the only place that decides, so changing it to "one full
         * sequence repeat" or "one recipe stage" is a local edit.
         */
        fun advance(
            current: CycleProgress?,
            recipe: Recipe?,
            slot: Int,
            previousStage: Int,
        ): Pair<CycleProgress, Int> {
            if (recipe == null || current == null) return (current ?: CycleProgress(0, 1, 0f)) to previousStage
            val stage = recipe.stages.indexOfFirst { slot in it.slots }
            if (stage < 0) return current to previousStage

            // Strictly *earlier*, not "same or earlier": a stage can hold several modules running
            // in parallel (Macro 1 + Macro 2 + Gravimetric all report RUN for stage 0), and treating
            // the second of those as a wrap jumped the bar straight to the last cycle the moment a
            // sequence started.
            val wrapped = previousStage >= 0 && stage < previousStage
            val cycle = if (wrapped) {
                (current.currentCycle + 1).coerceAtMost(current.cycleCount.coerceAtLeast(1))
            } else {
                current.currentCycle
            }
            val fraction = (stage + 1).toFloat() / recipe.stages.size
            return current.copy(currentCycle = cycle, fraction = fraction) to stage
        }

        /** The lot shipped: everything is full. */
        fun finish(current: CycleProgress?): CycleProgress? = current?.copy(
            currentCycle = current.cycleCount.coerceAtLeast(1),
            fraction = 1f,
            complete = true,
        )
    }
}
