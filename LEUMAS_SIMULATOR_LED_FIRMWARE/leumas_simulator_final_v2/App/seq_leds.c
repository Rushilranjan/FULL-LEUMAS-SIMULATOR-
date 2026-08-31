#include "seq_leds.h"
#include "config.h"
#include "module.h"
#include "led_strip.h"
#include "led_fx.h"
#include <stdio.h>

/* ---- the palette --------------------------------------------------------
 * Three colours carry the whole story, and nothing else is ever written to the
 * strip while a sequence runs. */
#define C_RUN_R     0u
#define C_RUN_G     0u
#define C_RUN_B   255u          /* blue  — running                           */
#define C_DONE_R    0u
#define C_DONE_G  255u
#define C_DONE_B    0u          /* green — completed, ready for the robot    */
#define C_STRIP_R 255u
#define C_STRIP_G 255u
#define C_STRIP_B 255u          /* white — recharge and material flow        */

/* ---- state -------------------------------------------------------------- */

static bool     s_active;
static uint8_t  s_seq;
static uint32_t s_mask;                     /* slots this sequence uses       */

/* One flag per strip pixel: lit white at SEQ_STRIP_PCT, or dark. This is the
 * whole of what the strip can show — see the header. */
static bool     s_lit[LED_COUNT_MAX];

/* Repaint is change-driven so the driver keeps its "only send a frame when
 * something actually changed" property. We shadow everything we drew. */
static ModState s_shown[MODULE_COUNT];
static uint32_t s_lit_version;              /* bumped whenever s_lit changes  */
static uint32_t s_shown_version;
static bool     s_force_paint;

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

/* Draw one module's own pixels for its current state. Module pixels are always
 * at full brightness: the strip percentage is deliberately not applied here. */
static void paint_module(uint8_t slot, ModState st)
{
    const SlotLeds *L = &SLOT_LEDS[slot];
    uint8_t r, g, b;

    if (!in_mask(slot)) {
        r = 0u; g = 0u; b = 0u;             /* not in this sequence: dark */
    } else {
        switch (st) {
        case MOD_RUNNING:
            r = C_RUN_R;  g = C_RUN_G;  b = C_RUN_B;
            break;
        case MOD_READY:
            r = C_DONE_R; g = C_DONE_G; b = C_DONE_B;
            break;
        case MOD_OFF:
        default:
            r = 0u; g = 0u; b = 0u;
            break;
        }
    }

    if (!px_ok(L->mod_a) || !px_ok(L->mod_b)) {
        return;                              /* coordinate off the strip */
    }
    led_strip_set_range(L->mod_a, L->mod_b, r, g, b);
    led_strip_set_range_pct(L->mod_a, L->mod_b, (uint8_t)SS1_MODULE_PCT);
}

/* Redraw the whole frame: every module, then every strip pixel. */
static void repaint(void)
{
    uint16_t n = led_strip_count();

    for (uint8_t s = 0; s < MODULE_COUNT; s++) {
        ModState st = module_get_state(s);
        s_shown[s] = st;
        paint_module(s, st);
    }

    /* The strip. Lit pixels white, everything else this file owns off. Module
     * pixels were just written above, so skip them here. */
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
        /* The brightness goes on EVERY strip pixel, lit or not, so a pixel that
         * lights later cannot flare at led_strip_reset()'s default of 100%
         * before its percentage is set. */
        led_strip_set_pixel_pct(i, (uint8_t)SEQ_STRIP_PCT);
        if (s_lit[i]) {
            led_strip_set(i, C_STRIP_R, C_STRIP_G, C_STRIP_B);
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
    s_mask   = SS1_ACTIVE_MASK;

    /* Take the strip from whatever the console left on it. The master goes back
     * to 100 because that is what makes SS1_MODULE_PCT mean "full brightness"
     * and SEQ_STRIP_PCT mean "1% of full". */
    led_fx_stop();
    led_strip_reset();
    led_strip_clear_blink();
    led_strip_set_master_pct(LED_MASTER_PCT_DEFAULT);

    for (uint16_t i = 0; i < LED_COUNT_MAX; i++) {
        s_lit[i] = false;
    }
    s_lit_version++;
    s_force_paint = true;
    repaint();
}

void seq_leds_stop(void)
{
    if (!s_active) {
        return;
    }
    s_active = false;
    s_seq    = 0u;
    led_fx_stop();
    led_strip_reset();
}

bool    seq_leds_active(void) { return s_active; }
uint8_t seq_leds_seq(void)    { return s_seq; }

/* ---- the strip, as seen by the sequence ---------------------------------- */

void seq_leds_strip_set(uint16_t a, uint16_t b, bool on)
{
    if (!s_active || a > b) {
        return;
    }
    if (on) {
        latch(a, b);
    } else {
        unlatch(a, b);
    }
}

void seq_leds_strip_clear(void)
{
    uint16_t n = led_strip_count();
    if (!s_active || n == 0u) {
        return;
    }
    unlatch(0u, (uint16_t)(n - 1u));
}

/* ---- per-pass work ------------------------------------------------------- */

void seq_leds_tick(void)
{
    if (!s_active) {
        return;
    }

    /* Redraw only when a module changed state or a strip pixel moved. */
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
    emit("\r\nslot  name          module px   status run    \r\n");
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
