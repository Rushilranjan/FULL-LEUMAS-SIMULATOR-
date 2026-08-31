/**
 * seq_leds.h — paints the running sequence onto the WS2812B strip.
 *
 * The sequence engine (lotmgr/module) stays the source of truth: it owns the
 * state machine, the timings and the BLE cues to the Android robot. This file
 * only READS that state and draws it, so the lights are always in step with
 * what the robot is animating.
 *
 * ---------------------------------------------------------------------------
 * ONE WRITER, ALWAYS
 * ---------------------------------------------------------------------------
 * The strip has exactly one owner at a time. Normally that is the LED command
 * centre. `seq_leds_start()` takes ownership for the duration of a sequence and
 * `seq_leds_stop()` hands it back; while a sequence is running, led_cmd refuses
 * manual paint commands instead of half-overwriting the frame. That is the same
 * rule that keeps the strip from ever showing a colour nobody asked for.
 *
 * ---------------------------------------------------------------------------
 * WHAT YOU SEE
 * ---------------------------------------------------------------------------
 *   RUNNING            green,  solid
 *   TRANSFER in/out    yellow, solid
 *   HOLDING (waiting)  yellow, BLINKING  <- the bottleneck the demo is about
 *   idle, in the mask  red,    solid
 *   not in the mask    dark
 *
 * One rule decides what is lit at all: a module the active sequence uses gets a
 * colour, anything else is dark. That covers the line that is not running AND
 * the deliberately-missing unit (Blender2 in Seq 1, Vessel2 in Seq 4) — so
 * "adding a module" in Seq 2/5 literally shows up as a pixel that was dark
 * lighting up.
 *
 * The status run fills solid BLUE up to a module the moment that module is
 * completely done with the lot. Fills are cumulative and monotonic: a pixel that
 * has been latched never goes dark again until the sequence restarts.
 *
 * ---------------------------------------------------------------------------
 * TWO BRIGHTNESSES, ONE STRIP
 * ---------------------------------------------------------------------------
 * The in-module pixels run at 100% and the status run at SEQ_STATUS_PCT (10%),
 * both as PER-PIXEL percentages over a master of 100. The modules are the
 * subject; the status run is the line drawn between them, and at equal
 * brightness it competes with them for attention. Lowering the master instead
 * would dim both together and lose the distinction.
 *
 * ---------------------------------------------------------------------------
 * STATUS IS A READOUT, THE EXIT IS AN ANIMATION
 * ---------------------------------------------------------------------------
 * These are deliberately different things and are drawn differently:
 *
 *   status fill  ONE pixel at a time, evenly across the whole transfer. It says
 *                "the line is complete this far", so it must be readable at a
 *                glance and must never light a pixel that is not yet true.
 *   exit flow    a moving head with a fading tail, looping. It says "the lot is
 *                leaving" and carries no per-pixel meaning, so it is free to be
 *                a flow animation. It uses led_fx; the status fill does not.
 */
#ifndef APP_SEQ_LEDS_H
#define APP_SEQ_LEDS_H

#include <stdint.h>
#include <stdbool.h>

/* Take the strip, clear every fill, and draw the sequence's opening frame.
 * Called from lotmgr_start(); `seq` is 1, 2, 4 or 5. */
void seq_leds_start(uint8_t seq);

/* Hand the strip back to the LED command centre and blank it. */
void seq_leds_stop(void);

/* True while the sequence owns the strip — led_cmd's guard on manual commands. */
bool seq_leds_active(void);

/* Which sequence is running (0 = none), for status lines and error messages. */
uint8_t seq_leds_seq(void);

/* Redraw if anything changed, and finish off any animation that has run out.
 * Call every main-loop pass, after lotmgr_tick(). */
void seq_leds_tick(void);

/* ---- hooks, called by lotmgr at the transfer-completion points ----------- */

/* Latch the status run solid blue up to the end of `slot`'s segment. Called
 * when that module is COMPLETELY done — its material has left and it will not
 * be used again this run. Cumulative; only ever adds pixels. */
void seq_leds_fill_upto(uint8_t slot);

/* Fill the hand-off span from `from_slot` to `to_slot` over `ms` (blender ->
 * packaging, vessel -> bottling), lighting exactly ONE pixel at a time so the
 * span takes the whole of `ms` to complete. When it ends the run is left solid
 * blue only as far as `keep_slot`:
 *   keep_slot == from_slot  -> clear the span again (another batch follows)
 *   keep_slot == to_slot    -> keep all of it (this was the final ship)
 * Anything in between keeps up to that module. */
void seq_leds_swipe(uint8_t from_slot, uint8_t to_slot, uint32_t ms, uint8_t keep_slot);

/* The "lot shipped" flow: a continuous looping animation along the line's exit
 * run for `ms`, which then latches solid blue and stays lit. Unlike the fill
 * above this IS an animation — a head with a fading tail, EXIT_FLOW_PASSES
 * sweeps over the run. */
void seq_leds_exit_flow(uint8_t from_slot, uint32_t ms);

/* Print the slot -> pixel table over the console, flagging anything that falls
 * outside the strip. Used by the `seq map` command to check the wiring by eye. */
void seq_leds_print_map(void (*emit)(const char *));

#endif /* APP_SEQ_LEDS_H */
