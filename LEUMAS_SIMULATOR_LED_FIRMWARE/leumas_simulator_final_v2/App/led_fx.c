#include "led_fx.h"
#include "led_strip.h"
#include "timebase.h"

/* The head plus its tail must fit in the overlay, or the far end of the tail
 * would be silently dropped and the comet would look shorter than asked for. */
_Static_assert(LED_FX_TAIL_MAX + 1u <= LED_OVERLAY_MAX,
               "LED_FX_TAIL_MAX + 1 must fit inside LED_OVERLAY_MAX");
_Static_assert(LED_FX_TAIL_DEFAULT <= LED_FX_TAIL_MAX,
               "default tail is longer than the maximum");

static bool     s_active;
static bool     s_loop;
static uint16_t s_from, s_to;      /* inclusive endpoints                     */
static int32_t  s_pos;             /* index of the head right now             */
static int32_t  s_dir;             /* +1 or -1                                */
static uint8_t  s_r, s_g, s_b;
static uint16_t s_step_ms = LED_FX_STEP_MS_DEFAULT;
static uint32_t s_next_ms;
static uint8_t  s_tail = LED_FX_TAIL_DEFAULT;

/* The blink clock. Which pixels blink is led_strip's business; all that lives
 * here is the phase and its own, separate period. */
static uint16_t s_blink_ms = LED_FX_BLINK_MS_DEFAULT;
static bool     s_blink_on = true;
static uint32_t s_blink_next;

/* Paint the head plus its tail into the overlay. The tail is the SAME colour,
 * just progressively dimmer, so a swipe never introduces a second hue: head at
 * full, then (tail-k)/(tail+1) of it going backwards. */
static void draw(void)
{
    led_strip_overlay_clear();
    for (uint8_t k = 0; k <= s_tail; k++) {
        int32_t px = s_pos - ((int32_t)k * s_dir);
        if (px < 0 || px >= (int32_t)led_strip_count()) {
            continue;
        }
        uint32_t num = (uint32_t)(s_tail + 1u - k);
        uint32_t den = (uint32_t)(s_tail + 1u);
        led_strip_overlay_add((uint16_t)px,
                              (uint8_t)(((uint32_t)s_r * num) / den),
                              (uint8_t)(((uint32_t)s_g * num) / den),
                              (uint8_t)(((uint32_t)s_b * num) / den));
    }
}

void led_fx_init(void)
{
    s_active     = false;
    s_loop       = false;
    s_step_ms    = LED_FX_STEP_MS_DEFAULT;
    s_tail       = LED_FX_TAIL_DEFAULT;
    s_blink_ms   = LED_FX_BLINK_MS_DEFAULT;
    s_blink_on   = true;
    s_blink_next = millis() + s_blink_ms;
}

void led_fx_blink_restart(void)
{
    s_blink_on   = true;
    s_blink_next = millis() + s_blink_ms;
    led_strip_set_blink_phase(true);
}

bool led_fx_swipe(uint16_t from, uint16_t to,
                  uint8_t r, uint8_t g, uint8_t b,
                  uint16_t step_ms, bool loop)
{
    uint16_t n = led_strip_count();
    if (from >= n || to >= n) {
        return false;
    }
    if (step_ms < LED_FX_STEP_MS_MIN) step_ms = LED_FX_STEP_MS_MIN;
    if (step_ms > LED_FX_STEP_MS_MAX) step_ms = LED_FX_STEP_MS_MAX;

    s_from    = from;
    s_to      = to;
    s_dir     = (to >= from) ? 1 : -1;
    s_pos     = (int32_t)from;
    s_r = r; s_g = g; s_b = b;
    s_step_ms = step_ms;
    s_loop    = loop;
    s_active  = true;
    s_next_ms = millis() + step_ms;
    draw();
    return true;
}

void led_fx_stop(void)
{
    if (s_active) {
        s_active = false;
        led_strip_overlay_clear();
    }
}

/* Flip the blink phase on schedule. Runs whether or not a swipe is going, and
 * only marks the frame dirty when a pixel is actually flagged to blink. */
static void blink_tick(void)
{
    if (!led_strip_any_blink()) {
        return;
    }
    if ((int32_t)(millis() - s_blink_next) < 0) {
        return;
    }
    s_blink_on   = !s_blink_on;
    s_blink_next = millis() + s_blink_ms;   /* resync, never chase a backlog */
    led_strip_set_blink_phase(s_blink_on);
}

void led_fx_tick(void)
{
    blink_tick();

    if (!s_active) {
        return;
    }
    /* Signed compare so the 49-day millis() wrap cannot stall the animation. */
    if ((int32_t)(millis() - s_next_ms) < 0) {
        return;
    }
    s_next_ms += s_step_ms;
    /* If the loop was starved (a long serial print, say), do not try to catch up
     * with a burst of steps — resync instead, so the swipe stays smooth. */
    if ((int32_t)(millis() - s_next_ms) > (int32_t)s_step_ms) {
        s_next_ms = millis() + s_step_ms;
    }

    if (s_pos == (int32_t)s_to) {              /* reached the far end */
        if (!s_loop) {
            led_fx_stop();
            return;
        }
        s_pos = (int32_t)s_from;               /* restart from the near end */
    } else {
        s_pos += s_dir;
    }
    draw();
}

bool led_fx_active(void) { return s_active; }

void led_fx_set_tail(uint8_t n)
{
    s_tail = (n > LED_FX_TAIL_MAX) ? LED_FX_TAIL_MAX : n;
    if (s_active) {
        draw();
    }
}

uint8_t  led_fx_get_tail(void)    { return s_tail; }

void led_fx_set_step_ms(uint16_t ms)
{
    if (ms < LED_FX_STEP_MS_MIN) ms = LED_FX_STEP_MS_MIN;
    if (ms > LED_FX_STEP_MS_MAX) ms = LED_FX_STEP_MS_MAX;
    s_step_ms = ms;
    if (s_active) {
        /* Re-time a swipe that is already running, from now, so a speed change
         * takes effect on the very next step instead of after the current one. */
        s_next_ms = millis() + ms;
    }
}

uint16_t led_fx_get_step_ms(void) { return s_step_ms; }

void led_fx_set_blink_ms(uint16_t ms)
{
    if (ms < LED_FX_BLINK_MS_MIN) ms = LED_FX_BLINK_MS_MIN;
    if (ms > LED_FX_BLINK_MS_MAX) ms = LED_FX_BLINK_MS_MAX;
    s_blink_ms   = ms;
    s_blink_next = millis() + ms;
}

uint16_t led_fx_get_blink_ms(void) { return s_blink_ms; }
