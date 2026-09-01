package com.leumas.robot.protocol

/**
 * The physical slot map, mirroring `App/config.c` `MODULE_TYPES[]` in the STM32 firmware.
 *
 * IMPORTANT: the firmware has **11** slots, not the 9 claimed by section 5 of the spec.
 * `MODULE_COUNT` is 11 (`Core/Inc/config.h`). The "9" in the spec is only defensible as
 * *9 user-calibrated modules* — every slot except Packaging(6) and Bottling(11), which
 * section 7 designates special stations. So: size everything 1..11, calibrate 9.
 *
 * The current firmware, **Solid Sequence 1** (`App/solid_seq1.c`), drives only slots 2, 3, 5
 * and 6 (Macro 2, Gravimetric, Blender 2, Packaging). The liquid line (7..11) and the unused
 * solid slots (1, 4) stay dark, but they remain mapped here because the physical rig still has
 * them and a liquid sequence is expected later. If the firmware's slot roles in
 * `App/config.c` ever change, this table and [transferKind] must change with them.
 */
object SlotMap {

    const val MIN_SLOT = 1
    const val MAX_SLOT = 11

    private val SPECS: List<ModuleSpec> = listOf(
        // --- Solid line (top row, slots 1..6) ---
        ModuleSpec(1, "Macro 1", ModuleRole.MACRO, ProductionLine.SOLID),
        ModuleSpec(2, "Macro 2", ModuleRole.MACRO, ProductionLine.SOLID),
        ModuleSpec(3, "Gravimetric", ModuleRole.GRAVIMETRIC, ProductionLine.SOLID),
        ModuleSpec(4, "Blender 1", ModuleRole.BLENDER, ProductionLine.SOLID),
        ModuleSpec(5, "Blender 2", ModuleRole.BLENDER, ProductionLine.SOLID),
        ModuleSpec(6, "Packaging", ModuleRole.PACKAGING, ProductionLine.SOLID),
        // --- Liquid line (bottom row, slots 7..11) ---
        ModuleSpec(7, "Liquid Macro 1", ModuleRole.MACRO, ProductionLine.LIQUID),
        ModuleSpec(8, "Liquid Macro 2", ModuleRole.MACRO, ProductionLine.LIQUID),
        ModuleSpec(9, "Vessel 1", ModuleRole.VESSEL, ProductionLine.LIQUID),
        ModuleSpec(10, "Vessel 2", ModuleRole.VESSEL, ProductionLine.LIQUID),
        ModuleSpec(11, "Bottling", ModuleRole.BOTTLING, ProductionLine.LIQUID),
    )

    /** Indexed by slot; index 0 is unused so callers can use 1-based slot numbers directly. */
    private val BY_SLOT: Array<ModuleSpec?> = arrayOfNulls<ModuleSpec>(MAX_SLOT + 1).also { arr ->
        SPECS.forEach { arr[it.slot] = it }
    }

    val all: List<ModuleSpec> get() = SPECS

    /** The 9 modules the operator calibrates by hand — everything but the special stations. */
    val calibratable: List<ModuleSpec> get() = SPECS.filter { !it.isSpecialStation }

    fun isValidSlot(slot: Int): Boolean = slot in MIN_SLOT..MAX_SLOT

    fun specOrNull(slot: Int): ModuleSpec? = if (isValidSlot(slot)) BY_SLOT[slot] else null

    fun displayName(slot: Int): String = specOrNull(slot)?.displayName ?: "Slot $slot"

    /**
     * Decide whether a `MOVE:a>b` is a real robot-arm trip or a strip/conveyor transfer.
     *
     * Under Solid Sequence 1 the firmware only ever emits a `MOVE` for the two robot carries, and
     * both go to the blender (slot 5): `MOVE:2>5` and `MOVE:3>5`. The board's own material flows
     * (blender→packaging, packaging→output) are drawn on the LED strip and send **no** `MOVE` at
     * all, so the app is never asked to animate them.
     *
     * Destination role stays the discriminator, so this keeps working if a future sequence does
     * send conveyor-style moves:
     *
     *  - dst is a BLENDER or VESSEL   -> animate the arm
     *  - dst is PACKAGING or BOTTLING -> conveyor band, no arm
     *
     * Anything else is unexpected. We return [TransferKind.PROCESS] for it, because spec §29
     * forbids inventing robot movement — a wrong conveyor animation is a far smaller lie than
     * a wrong robot transfer.
     */
    fun transferKind(destinationSlot: Int): TransferKind =
        when (specOrNull(destinationSlot)?.role) {
            ModuleRole.BLENDER, ModuleRole.VESSEL -> TransferKind.ROBOT
            else -> TransferKind.PROCESS
        }
}

enum class ProductionLine { SOLID, LIQUID }

enum class ModuleRole { MACRO, GRAVIMETRIC, BLENDER, PACKAGING, VESSEL, BOTTLING }

/** Whether a transfer is carried by the robot arm or by the LED strip / conveyor. */
enum class TransferKind { ROBOT, PROCESS }

data class ModuleSpec(
    val slot: Int,
    val displayName: String,
    val role: ModuleRole,
    val line: ProductionLine,
) {
    /**
     * Packaging and Bottling are fixed stations (spec §7) rather than one of the 9 modules the
     * operator positions by hand. They still get editable default positions.
     */
    val isSpecialStation: Boolean
        get() = role == ModuleRole.PACKAGING || role == ModuleRole.BOTTLING
}
