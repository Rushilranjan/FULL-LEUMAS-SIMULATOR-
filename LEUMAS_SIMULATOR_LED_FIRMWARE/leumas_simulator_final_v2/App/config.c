/**
 * config.c — instances of the editable tables declared in config.h.
 */
#include "config.h"

/* Slot -> role. Index 0 == slot 1.
 * Solid line proposed mapping (tweak the one or two slots that differ):
 *   slot 1 = Macro 1, slot 2 = Macro 2, slot 3 = Gravimetric,
 *   slot 4 = Blender 1, slot 5 = Blender 2, slot 6 = Packaging. */
const ModType MODULE_TYPES[MODULE_COUNT] = {
    /* --- Solid line (top row, slots 1..6) --- */
    T_MACRO,        /* slot 1  Macro1 */
    T_MACRO,        /* slot 2  Macro2 = "Module 2" (Solid Seq 1 macro) */
    T_GRAVIMETRIC,  /* slot 3  Gravimetric */
    T_BLENDER,      /* slot 4  Blender1 */
    T_BLENDER,      /* slot 5  Blender2 = "Module 5" (Solid Seq 1 blender) */
    T_PACKAGING,    /* slot 6  Packaging */
    /* --- Liquid line (bottom row, slots 7..11) --- */
    T_MACRO,        /* slot 7  LMacro1 */
    T_MACRO,        /* slot 8  LMacro2 */
    T_VESSEL,       /* slot 9  Vessel1 */
    T_VESSEL,       /* slot 10 Vessel2 */
    T_BOTTLING      /* slot 11 Bottling */
};

/* Rig pixel mapping — measured off the physical strip. See SlotLeds in config.h.
 *
 *   { mod_a, mod_b,  st_base, st_end }
 *
 * The status columns are CUMULATIVE fills from each line's base, so slot 3's
 * 0..9 already covers slots 1 and 2. The module pixels are in physical order
 * along the strip, which is NOT slot order — the line bends around the display,
 * so e.g. Packaging (slot 6) sits at 76-77, between the liquid modules.
 *
 * Pixels not listed here (44, 48, 52, 56, 59-60, 63-65, 68-69, 72, 75, 78-79,
 * 83-84, 88-89) are the physical gaps between module blocks and stay dark. */
const SlotLeds SLOT_LEDS[MODULE_COUNT] = {
    /* --- Solid line: modules fill status 0..16, exit run 17..21 --- */
    { 45, 47,   0,  1 },   /* slot 1  Macro1       */
    { 49, 51,   0,  5 },   /* slot 2  Macro2       */
    { 53, 55,   0,  9 },   /* slot 3  Gravimetric  */
    { 57, 58,   0, 11 },   /* slot 4  Blender1     */
    { 61, 62,   0, 13 },   /* slot 5  Blender2     */
    { 76, 77,   0, 16 },   /* slot 6  Packaging    */
    /* --- Liquid line: modules fill status 22..38, exit run 39..43 --- */
    { 85, 87,  22, 26 },   /* slot 7  LMacro1      */
    { 80, 82,  22, 31 },   /* slot 8  LMacro2      */
    { 70, 71,  22, 33 },   /* slot 9  Vessel1      */
    { 66, 67,  22, 35 },   /* slot 10 Vessel2      */
    { 73, 74,  22, 38 },   /* slot 11 Bottling     */
};

