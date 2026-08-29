#include "led_cmd.h"
#include "led_strip.h"
#include "led_fx.h"
#include "seq_leds.h"
#include "lotmgr.h"
#include <stdio.h>
#include <string.h>

#define LINE_MAX   80
#define ARGV_MAX    8

static UART_HandleTypeDef *s_uart;
static char     s_line[LINE_MAX];
static uint16_t s_len;
static bool     s_saw_cr;          /* so a CR LF pair runs the line once */

/* ---- colours -------------------------------------------------------------
 * The whole palette. Type the name, get exactly this colour — nothing in the
 * firmware mixes, fades or "corrects" it afterwards, apart from the brightness
 * percentages you set yourself. */
typedef struct { const char *name; uint8_t r, g, b; } NamedColor;

static const NamedColor COLORS[] = {
    { "red",     255,   0,   0 },
    { "green",     0, 255,   0 },
    { "blue",      0,   0, 255 },
    { "white",   255, 255, 255 },
    { "yellow",  255, 255,   0 },
    { "orange",  255,  60,   0 },
    { "amber",   255, 120,   0 },
    { "cyan",      0, 255, 255 },
    { "magenta", 255,   0, 255 },
    { "purple",  128,   0, 255 },
    { "pink",    255,  40, 100 },
    { "lime",    128, 255,   0 },
    { "teal",      0, 200, 140 },
    { "off",       0,   0,   0 },
    { "black",     0,   0,   0 },   /* alias for off */
};
#define COLOR_COUNT  (sizeof(COLORS) / sizeof(COLORS[0]))

static const NamedColor *find_color(const char *s)
{
    for (uint8_t i = 0; i < COLOR_COUNT; i++) {
        if (strcmp(s, COLORS[i].name) == 0) {
            return &COLORS[i];
        }
    }
    return NULL;
}

/* ---- serial out ---------------------------------------------------------- */

static void out(const char *s)
{
    if (s_uart && s) {
        HAL_UART_Transmit(s_uart, (uint8_t *)s, (uint16_t)strlen(s), 1000u);
    }
}

static void outc(char c)
{
    if (s_uart) {
        HAL_UART_Transmit(s_uart, (uint8_t *)&c, 1u, 50u);
    }
}

static void prompt(void) { out("led> "); }

/* ---- parsing -------------------------------------------------------------
 * Strict on purpose: a token is a number only if every character is a digit.
 * "red x 5" is rejected outright instead of quietly becoming "red 0 5". */
static bool parse_u16(const char *s, uint16_t *out_v)
{
    uint32_t v = 0;
    if (!s || *s == '\0') {
        return false;
    }
    for (const char *p = s; *p != '\0'; p++) {
        if (*p < '0' || *p > '9') {
            return false;
        }
        v = (v * 10u) + (uint32_t)(*p - '0');
        if (v > 65535u) {
            return false;
        }
    }
    *out_v = (uint16_t)v;
    return true;
}

static void lower(char *s)
{
    for (; *s != '\0'; s++) {
        if (*s >= 'A' && *s <= 'Z') {
            *s = (char)(*s + ('a' - 'A'));
        }
    }
}

/* ---- messages ------------------------------------------------------------ */

static void print_help(void)
{
    char b[80];
    out("\r\n========== LEUMAS LED COMMAND CENTRE ==========\r\n");
    snprintf(b, sizeof(b), "WS2812B strip on PE9   %u LEDs   index 0 to %u\r\n\r\n",
             (unsigned)led_strip_count(), (unsigned)(led_strip_count() - 1u));
    out(b);

    out("PAINT  - just type the colour\r\n");
    out("  red              whole strip red\r\n");
    out("  red 7            LED 7 red\r\n");
    out("  red 20 30        LEDs 20 to 30 red\r\n");
    out("  off              whole strip off\r\n");
    out("  off 7            LED 7 off\r\n");
    out("  off 20 30        LEDs 20 to 30 off\r\n");
    out("  clear            back to the power-on state\r\n\r\n");

    out("COLOURS\r\n");
    out("  red  green  blue  white  yellow  orange  amber\r\n");
    out("  cyan  magenta  purple  pink  lime  teal  off\r\n\r\n");

    out("BRIGHTNESS  - percent, 0 to 100\r\n");
    out("  bright 40        whole strip to 40%\r\n");
    out("  bright 7 10      LED 7 to 10%\r\n");
    out("  bright 20 30 10  LEDs 20 to 30 to 10%\r\n\r\n");

    out("SWIPE  - a lit point travelling along the strip\r\n");
    out("  swipe red 0 89        red point, LED 0 to 89\r\n");
    out("  swipe red 0 89 15     15 ms per LED (lower = faster)\r\n");
    out("  swipe red 89 0 15 loop  backwards, repeating\r\n");
    out("  speed 15              swipe speed on its own, ms per LED\r\n");
    out("  tail 3                pixels trailing the head, 0 to 8\r\n\r\n");

    out("BLINK  - LEDs that flash on and off where they are\r\n");
    out("  blink red 7           LED 7 blinks red\r\n");
    out("  blink red 20 30       LEDs 20 to 30 blink red\r\n");
    out("  blinkspeed 400        blink speed on its own, ms lit / ms dark\r\n");
    out("  blink off             stop blinking, LEDs stay solid\r\n\r\n");

    out("  stop                  stop BOTH the swipe and the blinking\r\n");
    out("  Swipe and blink are independent: they can run together, and\r\n");
    out("  'speed' and 'blinkspeed' never affect one another. Painting an\r\n");
    out("  LED any colour makes it solid again.\r\n\r\n");

    out("SEQUENCES  - the production demo, cued to the robot over BLE\r\n");
    out("  seq 1            solid line, ONE blender  -> batch B waits\r\n");
    out("  seq 2            solid line, TWO blenders -> runs in parallel\r\n");
    out("  seq 4            liquid line, ONE vessel  -> batch B waits\r\n");
    out("  seq 5            liquid line, TWO vessels -> runs in parallel\r\n");
    out("  seq stop         end it and take the strip back\r\n");
    out("  seq map          print the slot -> pixel table\r\n");
    out("  green = running   yellow = transfer   yellow blinking = waiting\r\n");
    out("  red = idle        white = material flow    dark = not in this seq\r\n");
    out("  While a sequence runs it owns the strip, so paint commands are\r\n");
    out("  refused until you type 'seq stop'. The board button also cycles\r\n");
    out("  1 -> 2 -> 4 -> 5.\r\n\r\n");

    out("SETUP\r\n");
    out("  count 90         how many LEDs are on the strip\r\n");
    out("  power 1000       current budget in mA (power off = no cap)\r\n");
    out("  status           show the current settings\r\n");
    out("  help             this list\r\n\r\n");

    out("IF PIXELS FLICKER\r\n");
    out("  Nothing is sent to the strip unless you type a command,\r\n");
    out("  so the firmware cannot be changing them. Check power:\r\n");
    out("  bright 5         if the flicker stops, it is the 5V rail.\r\n");
    out("                   Give the strip its own 2-3A supply, tie its\r\n");
    out("                   ground to the board's, and feed BOTH ends.\r\n");
    out("===============================================\r\n");
}

static void print_status(void)
{
    char b[96];

    snprintf(b, sizeof(b), "strip   : %u LEDs on PE9 (TIM1_CH1 / DMA2_Stream1)\r\n",
             (unsigned)led_strip_count());
    out(b);

    if (seq_leds_active()) {
        snprintf(b, sizeof(b), "owner   : sequence %u — 'seq stop' for manual control\r\n",
                 (unsigned)seq_leds_seq());
    } else {
        snprintf(b, sizeof(b), "owner   : manual (no sequence running)\r\n");
    }
    out(b);

    snprintf(b, sizeof(b), "bright  : master %u%%\r\n",
             (unsigned)led_strip_get_master_pct());
    out(b);

    snprintf(b, sizeof(b), "swipe   : %s, %u ms per LED, tail %u\r\n",
             led_fx_active() ? "running" : "idle",
             (unsigned)led_fx_get_step_ms(), (unsigned)led_fx_get_tail());
    out(b);

    if (led_strip_any_blink()) {
        snprintf(b, sizeof(b), "blink   : %u LEDs, %u ms lit / %u ms dark\r\n",
                 (unsigned)led_strip_blink_count(),
                 (unsigned)led_fx_get_blink_ms(), (unsigned)led_fx_get_blink_ms());
    } else {
        snprintf(b, sizeof(b), "blink   : none, %u ms lit / %u ms dark\r\n",
                 (unsigned)led_fx_get_blink_ms(), (unsigned)led_fx_get_blink_ms());
    }
    out(b);

    uint16_t budget = led_strip_get_budget_ma();
    if (budget == 0u) {
        snprintf(b, sizeof(b), "power   : no cap, last frame ~%u mA\r\n",
                 (unsigned)led_strip_last_ma());
    } else {
        snprintf(b, sizeof(b), "power   : cap %u mA, last frame ~%u mA (scaled to %u%%)\r\n",
                 (unsigned)budget, (unsigned)led_strip_last_ma(),
                 (unsigned)led_strip_last_power_pct());
    }
    out(b);
}

static void err_range(void)
{
    char b[64];
    snprintf(b, sizeof(b), "! index must be 0 to %u\r\n",
             (unsigned)(led_strip_count() - 1u));
    out(b);
}

static bool idx_ok(uint16_t i) { return i < led_strip_count(); }

/* ---- commands ------------------------------------------------------------ */

/* "<colour>" / "<colour> <i>" / "<colour> <a> <b>" */
static void cmd_paint(const NamedColor *c, char **argv, int argc)
{
    char b[64];
    uint16_t a, z;

    if (argc == 1) {
        led_strip_set_all(c->r, c->g, c->b);
        snprintf(b, sizeof(b), "whole strip = %s\r\n", c->name);
        out(b);
        return;
    }
    if (argc == 2) {
        if (!parse_u16(argv[1], &a)) {
            snprintf(b, sizeof(b), "! usage: %s | %s <index> | %s <from> <to>\r\n",
                     c->name, c->name, c->name);
            out(b);
            return;
        }
        if (!idx_ok(a)) { err_range(); return; }
        led_strip_set(a, c->r, c->g, c->b);
        snprintf(b, sizeof(b), "LED %u = %s\r\n", (unsigned)a, c->name);
        out(b);
        return;
    }
    if (argc == 3) {
        if (!parse_u16(argv[1], &a) || !parse_u16(argv[2], &z)) {
            snprintf(b, sizeof(b), "! usage: %s <from> <to>\r\n", c->name);
            out(b);
            return;
        }
        if (!idx_ok(a) || !idx_ok(z)) { err_range(); return; }
        led_strip_set_range(a, z, c->r, c->g, c->b);
        if (a > z) { uint16_t t = a; a = z; z = t; }
        snprintf(b, sizeof(b), "LED %u-%u = %s\r\n",
                 (unsigned)a, (unsigned)z, c->name);
        out(b);
        return;
    }
    out("! too many values — type help\r\n");
}

/* "bright <pct>" / "bright <i> <pct>" / "bright <a> <b> <pct>" */
static void cmd_bright(char **argv, int argc)
{
    char b[64];
    uint16_t a, z, pct;

    if (argc == 2) {
        if (!parse_u16(argv[1], &pct) || pct > 100u) {
            out("! brightness is 0 to 100\r\n"); return;
        }
        led_strip_set_master_pct((uint8_t)pct);
        snprintf(b, sizeof(b), "whole strip brightness = %u%%\r\n", (unsigned)pct);
        out(b);
        return;
    }
    if (argc == 3) {
        if (!parse_u16(argv[1], &a) || !parse_u16(argv[2], &pct)) {
            out("! usage: bright <index> <percent>\r\n"); return;
        }
        if (pct > 100u)  { out("! brightness is 0 to 100\r\n"); return; }
        if (!idx_ok(a))  { err_range(); return; }
        led_strip_set_pixel_pct(a, (uint8_t)pct);
        snprintf(b, sizeof(b), "LED %u brightness = %u%%\r\n",
                 (unsigned)a, (unsigned)pct);
        out(b);
        return;
    }
    if (argc == 4) {
        if (!parse_u16(argv[1], &a) || !parse_u16(argv[2], &z)
            || !parse_u16(argv[3], &pct)) {
            out("! usage: bright <from> <to> <percent>\r\n"); return;
        }
        if (pct > 100u)               { out("! brightness is 0 to 100\r\n"); return; }
        if (!idx_ok(a) || !idx_ok(z)) { err_range(); return; }
        led_strip_set_range_pct(a, z, (uint8_t)pct);
        if (a > z) { uint16_t t = a; a = z; z = t; }
        snprintf(b, sizeof(b), "LED %u-%u brightness = %u%%\r\n",
                 (unsigned)a, (unsigned)z, (unsigned)pct);
        out(b);
        return;
    }
    out("! usage: bright <pct> | bright <i> <pct> | bright <a> <b> <pct>\r\n");
}

/* "swipe <colour> <a> <b> [ms] [loop]" | "swipe stop" */
static void cmd_swipe(char **argv, int argc)
{
    char b[80];
    uint16_t a, z, ms = led_fx_get_step_ms();
    bool loop = false;

    if (argc == 2 && strcmp(argv[1], "stop") == 0) {
        led_fx_stop();
        out("swipe stopped\r\n");
        return;
    }
    if (argc < 4) {
        out("! usage: swipe <colour> <from> <to> [ms per LED] [loop]\r\n");
        return;
    }

    const NamedColor *c = find_color(argv[1]);
    if (!c) {
        out("! unknown colour — type help for the list\r\n");
        return;
    }
    if (!parse_u16(argv[2], &a) || !parse_u16(argv[3], &z)) {
        out("! usage: swipe <colour> <from> <to> [ms per LED] [loop]\r\n");
        return;
    }
    if (!idx_ok(a) || !idx_ok(z)) { err_range(); return; }

    for (int k = 4; k < argc; k++) {
        if (strcmp(argv[k], "loop") == 0) {
            loop = true;
        } else if (!parse_u16(argv[k], &ms)
                   || ms < LED_FX_STEP_MS_MIN || ms > LED_FX_STEP_MS_MAX) {
            snprintf(b, sizeof(b), "! speed is %u to %u ms per LED\r\n",
                     (unsigned)LED_FX_STEP_MS_MIN, (unsigned)LED_FX_STEP_MS_MAX);
            out(b);
            return;
        }
    }

    if (!led_fx_swipe(a, z, c->r, c->g, c->b, ms, loop)) {
        err_range();
        return;
    }
    snprintf(b, sizeof(b), "swipe %s  %u -> %u  %u ms/LED%s\r\n",
             c->name, (unsigned)a, (unsigned)z, (unsigned)ms,
             loop ? "  (looping)" : "");
    out(b);
}

static void cmd_count(char **argv, int argc)
{
    char b[64];
    uint16_t n;

    if (argc == 1) {
        snprintf(b, sizeof(b), "%u LEDs (index 0 to %u)\r\n",
                 (unsigned)led_strip_count(), (unsigned)(led_strip_count() - 1u));
        out(b);
        return;
    }
    if (!parse_u16(argv[1], &n) || !led_strip_set_count(n)) {
        snprintf(b, sizeof(b), "! count must be 1 to %u\r\n", (unsigned)LED_COUNT_MAX);
        out(b);
        return;
    }
    snprintf(b, sizeof(b), "%u LEDs (index 0 to %u) — strip cleared\r\n",
             (unsigned)n, (unsigned)(n - 1u));
    out(b);
}

static void cmd_power(char **argv, int argc)
{
    char b[72];
    uint16_t ma;

    if (argc == 1) {
        print_status();
        return;
    }
    if (strcmp(argv[1], "off") == 0) {
        led_strip_set_budget_ma(0u);
        out("power cap off — mind your 5V supply\r\n");
        return;
    }
    if (!parse_u16(argv[1], &ma) || ma == 0u) {
        out("! usage: power <milliamps> | power off\r\n");
        return;
    }
    led_strip_set_budget_ma(ma);
    snprintf(b, sizeof(b), "power cap = %u mA\r\n", (unsigned)ma);
    out(b);
}

/* "blink <colour> <i>" / "blink <colour> <a> <b>" / "blink off" */
static void cmd_blink(char **argv, int argc)
{
    char b[80];
    uint16_t a, z;

    if (argc == 2 && (strcmp(argv[1], "off") == 0 || strcmp(argv[1], "stop") == 0)) {
        led_strip_clear_blink();
        out("blinking stopped — those LEDs stay solid\r\n");
        return;
    }
    if (argc < 3) {
        out("! usage: blink <colour> <index> | blink <colour> <from> <to> | blink off\r\n");
        return;
    }

    const NamedColor *c = find_color(argv[1]);
    if (!c) {
        out("! unknown colour — type help for the list\r\n");
        return;
    }
    if (!parse_u16(argv[2], &a)) {
        out("! usage: blink <colour> <index> | blink <colour> <from> <to>\r\n");
        return;
    }
    z = a;
    if (argc == 4 && !parse_u16(argv[3], &z)) {
        out("! usage: blink <colour> <from> <to>\r\n");
        return;
    }
    if (argc > 4) {
        out("! too many values — set the rate with 'blinkspeed'\r\n");
        return;
    }
    if (!idx_ok(a) || !idx_ok(z)) { err_range(); return; }

    /* Paint first — which clears any stale blink flag on those pixels — then
     * flag them, so colour and blink state always agree. Restart the phase lit
     * so they come on at once instead of waiting out a dark half-cycle. */
    led_strip_set_range(a, z, c->r, c->g, c->b);
    led_strip_set_range_blink(a, z, true);
    led_fx_blink_restart();

    if (a > z) { uint16_t t = a; a = z; z = t; }
    if (a == z) {
        snprintf(b, sizeof(b), "LED %u blinking %s every %u ms\r\n",
                 (unsigned)a, c->name, (unsigned)led_fx_get_blink_ms());
    } else {
        snprintf(b, sizeof(b), "LED %u-%u blinking %s every %u ms\r\n",
                 (unsigned)a, (unsigned)z, c->name,
                 (unsigned)led_fx_get_blink_ms());
    }
    out(b);
}

/* "seq 1|2|4|5" / "seq stop" / "seq map" / "seq" */
static void cmd_seq(char **argv, int argc)
{
    char b[80];
    uint16_t n;

    if (argc == 1) {
        if (seq_leds_active()) {
            snprintf(b, sizeof(b), "sequence %u running — 'seq stop' to end it\r\n",
                     (unsigned)seq_leds_seq());
        } else {
            snprintf(b, sizeof(b), "no sequence running — try 'seq 1'\r\n");
        }
        out(b);
        return;
    }
    if (strcmp(argv[1], "stop") == 0 || strcmp(argv[1], "off") == 0) {
        if (!seq_leds_active()) {
            out("no sequence running\r\n");
            return;
        }
        seq_leds_stop();
        out("sequence stopped — the strip is yours again\r\n");
        return;
    }
    if (strcmp(argv[1], "map") == 0) {
        seq_leds_print_map(out);
        return;
    }
    if (!parse_u16(argv[1], &n) || (n != 1u && n != 2u && n != 4u && n != 5u)) {
        out("! sequences are 1, 2, 4 and 5 (3 is deferred)\r\n");
        out("  1 solid, one blender   2 solid, two blenders\r\n");
        out("  4 liquid, one vessel   5 liquid, two vessels\r\n");
        return;
    }
    lotmgr_start((uint8_t)n);       /* this calls seq_leds_start() for us */
    snprintf(b, sizeof(b), "sequence %u started — 'seq stop' for manual control\r\n",
             (unsigned)n);
    out(b);
}

/* Manual pixel commands are refused while a sequence owns the strip, rather
 * than half-overwriting its frame. Returns true if the command should stop. */
static bool seq_owns_strip(void)
{
    char b[80];
    if (!seq_leds_active()) {
        return false;
    }
    snprintf(b, sizeof(b), "! sequence %u running — type 'seq stop' for manual control\r\n",
             (unsigned)seq_leds_seq());
    out(b);
    return true;
}

/* "speed <ms>" is the swipe; "blinkspeed <ms>" is the blink. Deliberately two
 * separate commands, so changing one can never disturb the other. */
static void cmd_speed(char **argv, int argc, bool blink)
{
    char b[72];
    uint16_t ms;
    const uint16_t lo = blink ? LED_FX_BLINK_MS_MIN : LED_FX_STEP_MS_MIN;
    const uint16_t hi = blink ? LED_FX_BLINK_MS_MAX : LED_FX_STEP_MS_MAX;

    if (argc == 1) {                    /* no value: just report it */
        if (blink) {
            snprintf(b, sizeof(b), "blink speed = %u ms lit, %u ms dark\r\n",
                     (unsigned)led_fx_get_blink_ms(),
                     (unsigned)led_fx_get_blink_ms());
        } else {
            snprintf(b, sizeof(b), "swipe speed = %u ms per LED\r\n",
                     (unsigned)led_fx_get_step_ms());
        }
        out(b);
        return;
    }
    if (!parse_u16(argv[1], &ms) || ms < lo || ms > hi) {
        snprintf(b, sizeof(b), "! %s speed is %u to %u ms\r\n",
                 blink ? "blink" : "swipe", (unsigned)lo, (unsigned)hi);
        out(b);
        return;
    }
    if (blink) {
        led_fx_set_blink_ms(ms);
        snprintf(b, sizeof(b), "blink speed = %u ms lit, %u ms dark\r\n",
                 (unsigned)ms, (unsigned)ms);
    } else {
        led_fx_set_step_ms(ms);
        snprintf(b, sizeof(b), "swipe speed = %u ms per LED\r\n", (unsigned)ms);
    }
    out(b);
}

/* ---- dispatch ------------------------------------------------------------ */

static void exec(char *line)
{
    char *argv[ARGV_MAX];
    int   argc = 0;

    char *t = strtok(line, " \t");
    while (t != NULL && argc < ARGV_MAX) {
        argv[argc++] = t;
        t = strtok(NULL, " \t");
    }
    if (argc == 0) {
        return;                     /* bare Enter — just re-prompt */
    }
    for (int i = 0; i < argc; i++) {
        lower(argv[i]);
    }

    const char *cmd = argv[0];

    /* The colour name IS the command, so it is tried first. */
    const NamedColor *c = find_color(cmd);
    if (c != NULL) {
        if (seq_owns_strip()) return;
        cmd_paint(c, argv, argc);
        return;
    }

    if (strcmp(cmd, "help") == 0 || strcmp(cmd, "?") == 0) {
        print_help();
    } else if (strcmp(cmd, "clear") == 0) {
        /* Back to the power-on state in one word: nothing lit, no animation,
         * every brightness back where it started. */
        char cb[64];
        if (seq_owns_strip()) return;
        led_fx_stop();
        led_strip_reset();
        led_strip_set_master_pct(LED_MASTER_PCT_DEFAULT);
        snprintf(cb, sizeof(cb), "cleared — all off, brightness back to %u%%\r\n",
                 (unsigned)LED_MASTER_PCT_DEFAULT);
        out(cb);
    } else if (strcmp(cmd, "bright") == 0) {
        if (seq_owns_strip()) return;
        cmd_bright(argv, argc);
    } else if (strcmp(cmd, "swipe") == 0) {
        if (seq_owns_strip()) return;
        cmd_swipe(argv, argc);
    } else if (strcmp(cmd, "blink") == 0) {
        if (seq_owns_strip()) return;
        cmd_blink(argv, argc);
    } else if (strcmp(cmd, "speed") == 0) {
        cmd_speed(argv, argc, false);
    } else if (strcmp(cmd, "blinkspeed") == 0) {
        cmd_speed(argv, argc, true);
    } else if (strcmp(cmd, "seq") == 0) {
        cmd_seq(argv, argc);
    } else if (strcmp(cmd, "stop") == 0) {
        /* The one command that makes everything hold still — including a
         * running sequence, so there is always a way out. */
        if (seq_leds_active()) {
            char sb[56];
            snprintf(sb, sizeof(sb), "sequence %u stopped\r\n",
                     (unsigned)seq_leds_seq());
            seq_leds_stop();
            out(sb);
        }
        led_fx_stop();
        led_strip_clear_blink();
        out("stopped — swipe and blink both off\r\n");
    } else if (strcmp(cmd, "tail") == 0) {
        uint16_t n;
        if (seq_owns_strip()) return;
        if (argc < 2 || !parse_u16(argv[1], &n) || n > LED_FX_TAIL_MAX) {
            char b[48];
            snprintf(b, sizeof(b), "! tail is 0 to %u\r\n", (unsigned)LED_FX_TAIL_MAX);
            out(b);
        } else {
            char b[40];
            led_fx_set_tail((uint8_t)n);
            snprintf(b, sizeof(b), "swipe tail = %u\r\n", (unsigned)n);
            out(b);
        }
    } else if (strcmp(cmd, "count") == 0) {
        if (argc > 1 && seq_owns_strip()) return;   /* reading it is harmless */
        cmd_count(argv, argc);
    } else if (strcmp(cmd, "power") == 0) {
        cmd_power(argv, argc);
    } else if (strcmp(cmd, "status") == 0) {
        print_status();
    } else {
        out("! unknown command — type help\r\n");
    }
}

/* ---- line editing + RX pump ---------------------------------------------- */

static void handle_char(uint8_t ch)
{
    if (ch == '\r' || ch == '\n') {
        if (ch == '\n' && s_saw_cr) {   /* second half of a CR LF pair */
            s_saw_cr = false;
            return;
        }
        s_saw_cr = (ch == '\r');
        out("\r\n");
        s_line[s_len] = '\0';
        exec(s_line);
        s_len = 0;
        prompt();
        return;
    }
    s_saw_cr = false;

    if (ch == 0x08u || ch == 0x7Fu) {           /* backspace / delete */
        if (s_len > 0u) {
            s_len--;
            out("\b \b");
        }
        return;
    }
    if (ch >= 0x20u && ch < 0x7Fu && s_len < (LINE_MAX - 1u)) {
        s_line[s_len++] = (char)ch;
        outc((char)ch);                         /* echo, so typing is visible */
    }
}

void led_cmd_init(UART_HandleTypeDef *uart)
{
    char b[72];

    s_uart   = uart;
    s_len    = 0u;
    s_saw_cr = false;

    out("\r\n\r\n");
    out("========== LEUMAS LED COMMAND CENTRE ==========\r\n");
    snprintf(b, sizeof(b), "WS2812B on PE9   %u LEDs   index 0 to %u\r\n",
             (unsigned)led_strip_count(), (unsigned)(led_strip_count() - 1u));
    out(b);
    out("Type 'help' for the command list.  Try:  red 20 30\r\n");
    snprintf(b, sizeof(b), "Power cap %u mA — give the strip its own 5V supply.\r\n",
             (unsigned)led_strip_get_budget_ma());
    out(b);
    out("Flicker? type 'bright 5' — if it stops, it is the supply.\r\n");
    out("===============================================\r\n");
    prompt();
}

void led_cmd_poll(void)
{
    UART_HandleTypeDef *u = s_uart;
    if (u == NULL) {
        return;
    }
    /* An overrun latches RXNE off; clear it so typing keeps working after the
     * console has been ignored for a while. */
    if (__HAL_UART_GET_FLAG(u, UART_FLAG_ORE)) {
        __HAL_UART_CLEAR_OREFLAG(u);
    }
    /* Drain whatever is waiting. Reading RDR clears RXNE, so this ends. */
    while (__HAL_UART_GET_FLAG(u, UART_FLAG_RXNE)) {
        handle_char((uint8_t)(u->Instance->RDR & 0xFFu));
    }
}
