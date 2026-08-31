#include "seq_leds.h"
#include "config.h"
#include "module.h"
#include "led_strip.h"
#include "led_fx.h"
#include "timebase.h"
#include <stdio.h>

/* ---- the palette --------------------------------------------------------
 * Deliberately tiny. Four colours carry the whole story, and nothing else is
 * ever written to the strip while a sequence runs. */
#define C_RUN_R     0u
#define C_RUN_G   255u
#define C_RUN_B     0u          /* green  — running                          */
#define C_XFR_R   255u
#define C_XFR_G   200u
#define C_XFR_B     0u          /* yellow — transferring, or waiting (blink) */
#define C_IDLE_R  255u
#define C_IDLE_G    0u
#define C_IDLE_B    0u          /* red    — idle                             */
#define C_FLOW_R    0u
#define C_FLOW_G    0u
#define C_FLOW_B  255u          /* blue   — status run / material flow       */

/* How long each pixel takes is derived from the transfer's own duration, so the
 * animation always fits the hand-off exactly. There is deliberately NO clamp:
 * these spans are only 3-5 pixels, and a ceiling here would finish the whole
 * animation in the first couple of seconds and leave the rest of the window
 * dead — which is exactly what a 400 ms ceiling used to do to the 10 s swipe. */

/* ---- state -------------------------------------------------------------- */

static bool     s_active;
static uint8_t  s_seq;
static uint32_t s_mask;                     /* slots this sequence uses       */
static bool     s_liquid;                   /* which line is running          */

/* Status pixels latched solid blue. Cumulative and monotonic within a run. */
static bool     s_lit[LED_COUNT_MAX];

/* Repaint is change-driven so the driver keeps its "only send a frame when
 * something actually changed" property. We shadow everything we drew. */
static ModState s_shown[MODULE_COUNT];
static uint32_t s_lit_version;              /* bumped whenever s_lit changes  */
static uint32_t s_shown_version;
static bool     s_force_paint;

/* The pending end-of-fill latch: when the hand-off fill reaches the end of its
 * span, fill up to this slot. -1 = no fill of ours is running. */
static int8_t   s_swipe_keep = -1;

/* The hand-off fill. Status is a READOUT, not an effect: it advances exactly one
 * pixel at a time, evenly across the transfer, so what the audience sees is
 * material creeping along the line rather than an animation being played. That
 * is why this does not use led_fx (whose head-plus-tail comet lights several
 * pixels at once and latches nothing until it stops) — led_fx is kept for the
 * exit, which IS a flow animation. */
static bool     s_fill_run;         /* a hand-off fill is advancing         */
static uint16_t s_fill_next;        /* pixel to light on the next step      */
static uint16_t s_fill_end;         /* last pixel of the span, inclusive    */
static uint32_t s_fill_step_ms;     /* dwell on each pixel                  */
static uint32_t s_fill_due;         /* millis() when the next pixel lights  */

/* The exit flow is a looping swipe plus a stopwatch; when it runs out the run
 * latches solid blue and stays lit. */
static bool      s_exit_run;
static SoftTimer s_exit_timer;
static uint8_t   s_exit_a, s_exit_b;

/* ---- helpers ------------------------------------------------------------ */

static inline bool slot_ok(uint8_t slot) { return slot < MODULE_COUNT; }

static inline bool in_mask(uint8_t slot)
{
    return slot_ok(slot) && ((s_mask & (1UL << slot)) != 0UL);
}

/* Clamp a pixel index to the strip actually fitted, so a mis-typed coordinate
 * can never scribble past the end. */
static inline bool px_ok(uint16_t px) { return px < led_strip_count(); }

static void latch(uint16_t a, uint16_t b)
{
    bool changed = false;
    for (uint16_t i = a; i <= b; i++) {
        if (px_ok(i) && !s_lit[i]) { s_lit[i] = true; changed = true; }
    }
    if (changed) {
        s_lit_version++;
    }
}

static void unlatch(uint16_t a, uint16_t b)
{
    bool changed = false;
    for (uint16_t i = a; i <= b; i++) {
        if (px_ok(i) && s_lit[i]) { s_lit[i] = false; changed = true; }
    }
    if (changed) {
        s_lit_version++;
    }
}

/* ---- painting ------------------------------------------------------------ */

/* Draw one module's own pixels for its current state. */
static void paint_module(uint8_t slot, ModState st)
{
    const SlotLeds *L = &SLOT_LEDS[slot];
    uint8_t r, g, b;
    uint8_t pct   = 100u;
    bool    blink = false;

    if (!in_mask(slot)) {
        r = 0u; g = 0u; b = 0u;             /* not in this sequence: dark */
    } else {
        switch (st) {
        case MOD_RUNNING:
            r = C_RUN_R; g = C_RUN_G; b = C_RUN_B;
            break;
        case MOD_TRANSFER_OUT:
        case MOD_TRANSFER_IN:
            r = C_XFR_R; g = C_XFR_G; b = C_XFR_B;
            break;
        case MOD_HOLDING:                    /* waiting on the bottleneck */
            r = C_XFR_R; g = C_XFR_G; b = C_XFR_B;
            blink = true;
            break;
        case MOD_READY:
        case MOD_INACTIVE:
        default:
            r = C_IDLE_R; g = C_IDLE_G; b = C_IDLE_B;
            pct = (uint8_t)SEQ_IDLE_PCT;     /* only the idle ones are dimmable */
            break;
        }
    }

    if (!px_ok(L->mod_a) || !px_ok(L->mod_b)) {
        return;                              /* coordinate off the strip */
    }
    /* Colour first: led_strip_set_range() clears the blink flag by design, so
     * the flag has to go back on afterwards or a waiting module would go solid. */
    led_strip_set_range(L->mod_a, L->mod_b, r, g, b);
    led_strip_set_range_pct(L->mod_a, L->mod_b, pct);
    if (blink) {
        led_strip_set_range_blink(L->mod_a, L->mod_b, true);
    }
}

/* Redraw the whole frame: every module, then the status run. */
static void repaint(void)
{
    uint16_t n = led_strip_count();

    for (uint8_t s = 0; s < MODULE_COUNT; s++) {
        ModState st = module_get_state(s);
        s_shown[s] = st;
        paint_module(s, st);
    }

    /* The status run. Latched pixels blue, everything else this file owns off.
     * Module pixels were just written above, so skip them here. */
    for (uint16_t i = 0; i < n; i++) {
        bool is_module = false;
        for (uint8_t s = 0; s < MODULE_COUNT; s++) {
            if (i >= SLOT_LEDS[s].mod_a && i <= SLOT_LEDS[s].mod_b) {
                is_module = true;
                break;
            }
        }
        if (is_module) {
            continue;
        }
        /* The brightness goes on EVERY status pixel, lit or not. The exit head
         * is drawn into the overlay, and led_strip's composite() applies the
         * per-pixel percentage to overlay pixels too — so a dark pixel left at
         * led_strip_reset()'s default of 100% would flare to full brightness the
         * moment the head crossed it, while the trail behind it stayed at 10%. */
        led_strip_set_pixel_pct(i, (uint8_t)SEQ_STATUS_PCT);
        if (s_lit[i]) {
            led_strip_set(i, C_FLOW_R, C_FLOW_G, C_FLOW_B);
        } else {
            led_strip_set(i, 0u, 0u, 0u);
        }
    }

    s_shown_version = s_lit_version;
    s_force_paint   = false;
}

/* ---- lifecycle ----------------------------------------------------------- */

void seq_leds_start(uint8_t seq)
{
    s_seq    = seq;
    s_active = true;
    s_liquid = (seq == 4u || seq == 5u);

    switch (seq) {
    case 1:  s_mask = SEQ1_ACTIVE_MASK; break;
    case 2:  s_mask = SEQ2_ACTIVE_MASK; break;
    case 4:  s_mask = SEQ4_ACTIVE_MASK; break;
    case 5:  s_mask = SEQ5_ACTIVE_MASK; break;
    default: s_mask = SEQ1_ACTIVE_MASK; break;
    }

    if (s_liquid) {
        s_exit_a = (uint8_t)LIQUID_EXIT_A;
        s_exit_b = (uint8_t)LIQUID_EXIT_B;
    } else {
        s_exit_a = (uint8_t)SOLID_EXIT_A;
        s_exit_b = (uint8_t)SOLID_EXIT_B;
    }

    /* Take the strip from whatever the console left on it. */
    led_fx_stop();
    led_strip_reset();
    led_strip_set_master_pct(LED_MASTER_PCT_DEFAULT);

    for (uint16_t i = 0; i < LED_COUNT_MAX; i++) {
        s_lit[i] = false;
    }
    s_lit_version++;
    s_swipe_keep = -1;
    s_fill_run   = false;
    s_exit_run   = false;
    s_force_paint = true;
    repaint();
}

void seq_leds_stop(void)
{
    if (!s_active) {
        return;
    }
    s_active     = false;
    s_seq        = 0u;
    s_swipe_keep = -1;
    s_fill_run   = false;
    s_exit_run   = false;
    led_fx_stop();
    /* The tail is global led_fx state shared with the console's `swipe`, so put
     * it back however the exit left it. */
    led_fx_set_tail(LED_FX_TAIL_DEFAULT);
    led_strip_reset();
}

bool    seq_leds_active(void) { return s_active; }
uint8_t seq_leds_seq(void)    { return s_seq; }

/* ---- hooks --------------------------------------------------------------- */

void seq_leds_fill_upto(uint8_t slot)
{
    if (!s_active || !slot_ok(slot)) {
        return;
    }
    latch(SLOT_LEDS[slot].st_base, SLOT_LEDS[slot].st_end);
}

void seq_leds_swipe(uint8_t from_slot, uint8_t to_slot, uint32_t ms, uint8_t keep_slot)
{
    if (!s_active || !slot_ok(from_slot) || !slot_ok(to_slot) || !slot_ok(keep_slot)) {
        return;
    }
    /* The span is what this hand-off newly covers: just past the source's fill,
     * through to the destination's. */
    uint16_t a = (uint16_t)(SLOT_LEDS[from_slot].st_end + 1u);
    uint16_t b = SLOT_LEDS[to_slot].st_end;
    if (a > b || !px_ok(a) || !px_ok(b)) {
        return;
    }

    uint32_t len = (uint32_t)(b - a + 1u);

    /* Spread the span evenly over the whole hand-off, one pixel per step. With
     * a 5-pixel span and a 15 s transfer that is 3 s per pixel. */
    s_fill_step_ms = (len > 0u) ? (ms / len) : ms;
    if (s_fill_step_ms == 0u) s_fill_step_ms = 1u;
    s_fill_next    = a;
    s_fill_end     = b;
    s_fill_due     = millis();          /* the first pixel lights at once */
    s_fill_run     = true;
    s_swipe_keep   = (int8_t)keep_slot;
}

void seq_leds_exit_flow(uint8_t from_slot, uint32_t ms)
{
    (void)from_slot;
    if (!s_active || !px_ok(s_exit_a) || !px_ok(s_exit_b)) {
        return;
    }
    uint32_t len  = (uint32_t)(s_exit_b - s_exit_a + 1u);
    uint32_t step = (len > 0u) ? (ms / (len * EXIT_FLOW_PASSES)) : ms;

    s_swipe_keep = -1;                /* this one is the exit, not a hand-off */
    s_fill_run   = false;             /* nothing of ours may still be filling */
    s_exit_run   = true;
    timer_start(&s_exit_timer, ms);
    /* A long tail over a short run turns the whole exit into one gradient
     * sliding outwards, which reads as material leaving rather than as a dot
     * crossing five LEDs. */
    led_fx_set_tail((uint8_t)EXIT_FLOW_TAIL);
    (void)led_fx_swipe(s_exit_a, s_exit_b,
                       C_FLOW_R, C_FLOW_G, C_FLOW_B, (uint16_t)step, true);
}

/* ---- per-pass work ------------------------------------------------------- */

void seq_leds_tick(void)
{
    if (!s_active) {
        return;
    }

    /* The exit flow has run its time: stop looping and leave the run lit, so a
     * shipped lot keeps a visible bar at the end of the line. */
    if (s_exit_run && timer_expired(&s_exit_timer)) {
        s_exit_run = false;
        led_fx_stop();
        led_fx_set_tail(LED_FX_TAIL_DEFAULT);   /* shared with the console */
        latch(s_exit_a, s_exit_b);
    }

    /* Advance the hand-off fill by exactly ONE pixel when its dwell is up. The
     * signed compare (as in led_fx.c) keeps the 49-day millis() wrap from
     * stalling it.
     *
     * The run ends one dwell AFTER the last pixel lights, not on the same pass.
     * Otherwise the keep/unlatch rule below would fire in the very frame that
     * pixel appeared, and a non-final hand-off (Seq-1 batch A, which clears its
     * span for batch B) would light its last pixel and erase it again before
     * anyone saw it. Landing on `ms` also keeps the fill exactly in step with
     * the lot timer lotmgr armed for the same transfer. */
    if (s_fill_run && (int32_t)(millis() - s_fill_due) >= 0) {
        if (s_fill_next > s_fill_end) {
            s_fill_run = false;                 /* span complete and held */
        } else {
            latch(s_fill_next, s_fill_next);
            s_fill_next++;
            s_fill_due += s_fill_step_ms;
            /* Starved for longer than a whole dwell (a long serial print, say):
             * resync rather than firing a burst of pixels on consecutive
             * passes, which would look like a jump. */
            if ((int32_t)(millis() - s_fill_due) > (int32_t)s_fill_step_ms) {
                s_fill_due = millis() + s_fill_step_ms;
            }
        }
    }

    /* A hand-off fill just finished: latch as far as it was told to keep, and
     * clear the rest of its span. */
    if (s_swipe_keep >= 0 && !s_fill_run) {
        uint8_t  keep = (uint8_t)s_swipe_keep;
        uint16_t base = SLOT_LEDS[keep].st_base;
        uint16_t end  = SLOT_LEDS[keep].st_end;
        s_swipe_keep  = -1;
        latch(base, end);
        /* Anything past keep_slot on this line goes back to unlit, so a
         * not-final hand-off leaves the span clear for the next batch. */
        unlatch((uint16_t)(end + 1u), s_liquid ? (uint16_t)(LIQUID_EXIT_A - 1u)
                                               : (uint16_t)(SOLID_EXIT_A - 1u));
    }

    /* Redraw only when a module changed state or a fill moved. */
    bool changed = s_force_paint || (s_shown_version != s_lit_version);
    if (!changed) {
        for (uint8_t s = 0; s < MODULE_COUNT; s++) {
            if (s_shown[s] != module_get_state(s)) { changed = true; break; }
        }
    }
    if (changed) {
        repaint();
    }
}

/* ---- map printout -------------------------------------------------------- */

static const char *SLOT_NAME[MODULE_COUNT] = {
    "Macro1", "Macro2", "Gravimetric", "Blender1", "Blender2", "Packaging",
    "LMacro1", "LMacro2", "Vessel1",    "Vessel2",  "Bottling"
};

void seq_leds_print_map(void (*emit)(const char *))
{
    char b[80];
    uint16_t n = led_strip_count();

    if (!emit) {
        return;
    }
    emit("\r\nslot  name          module px   status fill   \r\n");
    for (uint8_t s = 0; s < MODULE_COUNT; s++) {
        const SlotLeds *L = &SLOT_LEDS[s];
        bool ok = (L->mod_a < n) && (L->mod_b < n) &&
                  (L->st_base < n) && (L->st_end < n) &&
                  (L->mod_a <= L->mod_b) && (L->st_base <= L->st_end);
        snprintf(b, sizeof(b), " %2u   %-12s  %3u-%-3u    %3u-%-3u     %s\r\n",
                 (unsigned)(s + 1u), SLOT_NAME[s],
                 (unsigned)L->mod_a, (unsigned)L->mod_b,
                 (unsigned)L->st_base, (unsigned)L->st_end,
                 ok ? "" : "<-- OFF THE STRIP");
        emit(b);
    }
    snprintf(b, sizeof(b), "exit runs: packaging %u-%u   bottling %u-%u\r\n",
             (unsigned)SOLID_EXIT_A, (unsigned)SOLID_EXIT_B,
             (unsigned)LIQUID_EXIT_A, (unsigned)LIQUID_EXIT_B);
    emit(b);
    snprintf(b, sizeof(b), "strip has %u LEDs (0 to %u)\r\n",
             (unsigned)n, (unsigned)(n - 1u));
    emit(b);
}
