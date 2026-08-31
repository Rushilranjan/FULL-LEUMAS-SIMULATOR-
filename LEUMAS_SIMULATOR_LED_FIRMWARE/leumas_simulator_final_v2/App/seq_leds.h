/**
 * seq_leds.h — paints the running sequence onto the WS2812B strip.
 *
 * This file draws; it decides nothing. solid_seq1.c owns the state machine, the
 * timings and the BLE cues to the Android robot. seq_leds READS module state
 * back and paints it, so the lights are always in step with what the robot is
 * animating.
 *
 * ---------------------------------------------------------------------------
 * ONE WRITER, ALWAYS
 * ---------------------------------------------------------------------------
 * The strip has exactly one owner at a time. Normally that is the LED command
 * centre. seq_leds_start() takes ownership for the duration of a sequence and
 * seq_leds_stop() hands it back; while a sequence is running, led_cmd refuses
 * manual paint commands instead of half-overwriting the frame.
 *
 * The same rule applies inside the firmware: solid_seq1.c must never call
 * led_strip_* directly. It goes through seq_leds_strip_set() and module state,
 * both of which repaint() can see. A direct led_strip_set() would survive
 * exactly until the next repaint and the bug would present as flicker.
 *
 * ---------------------------------------------------------------------------
 * WHAT YOU SEE — three colours, no more
 * ---------------------------------------------------------------------------
 *   MOD_RUNNING       BLUE,  100%   the module is processing
 *   MOD_READY         GREEN, 100%   finished, ready for the robot to collect
 *   MOD_OFF           dark          not processing, or the robot has left
 *   not in the mask   dark          not part of this sequence at all
 *
 * Nothing blinks and nothing fades. A module is one of three things and it says
 * so plainly from across the room.
 *
 * ---------------------------------------------------------------------------
 * TWO BRIGHTNESSES, ONE STRIP
 * ---------------------------------------------------------------------------
 * Module pixels run at SS1_MODULE_PCT (100%) and the strip at SEQ_STRIP_PCT
 * (1%), both as PER-PIXEL percentages over a master of 100. The modules are the
 * subject; the strip is the line drawn between them, and at equal brightness it
 * would compete with them for attention. Lowering the master instead would dim
 * both together and lose the distinction — so the master stays at 100.
 *
 * ---------------------------------------------------------------------------
 * THE STRIP IS A BITMAP, NOT AN EFFECT
 * ---------------------------------------------------------------------------
 * Everything the strip shows is one flag per pixel: lit white at 1%, or dark.
 * The recharge and material-flow animations in solid_seq1.c are nothing more
 * than that flag changing over time — a moving head is a pixel that lights as
 * the one behind it goes out. There is no overlay, no tail and no led_fx in the
 * sequence path, which is why an animation can never leave a stray pixel behind
 * it: repaint() draws the whole frame from the flags every time.
 */
#ifndef APP_SEQ_LEDS_H
#define APP_SEQ_LEDS_H

#include <stdint.h>
#include <stdbool.h>

/* Take the strip, clear every lit pixel, and draw the opening frame. Called
 * from solid_seq1_start(); `seq` is always 1 and is kept only so the console
 * can name what is running. */
void seq_leds_start(uint8_t seq);

/* Hand the strip back to the LED command centre and blank it. Do not call this
 * directly to end a demo — call solid_seq1_stop(), which calls this. Stopping
 * the renderer alone would leave the state machine ticking and still cueing the
 * robot over BLE. */
void seq_leds_stop(void);

/* True while the sequence owns the strip — led_cmd guard on manual commands. */
bool seq_leds_active(void);

/* Which sequence is running (0 = none), for status lines and error messages. */
uint8_t seq_leds_seq(void);

/* Redraw if anything changed. Call every main-loop pass, AFTER
 * solid_seq1_tick(), so a pixel set this pass is drawn this pass. */
void seq_leds_tick(void);

/* ---- the strip, as seen by the sequence ---------------------------------- */

/* Light or clear the inclusive pixel span [a,b]. Lit pixels are white at
 * SEQ_STRIP_PCT. Out-of-range pixels are ignored, so a mistyped coordinate can
 * never scribble past the end of the strip. */
void seq_leds_strip_set(uint16_t a, uint16_t b, bool on);

/* Every strip pixel dark. Module pixels are unaffected. */
void seq_leds_strip_clear(void);

/* Print the slot -> pixel table over the console, flagging anything that falls
 * outside the strip. Used by the `seq map` command to check the wiring by eye. */
void seq_leds_print_map(void (*emit)(const char *));

#endif /* APP_SEQ_LEDS_H */
