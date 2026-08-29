#include "led_strip.h"
#include "led_fx.h"
#include "timebase.h"

/* Wire slots in one frame: lead reset + 24 bits per pixel + trailing reset. */
#define LED_WIRE_MAX  (LED_RESET_LEAD + (LED_COUNT_MAX * 24u) + LED_RESET_TRAIL)

/* Current model. A WS2812B channel at full draws ~20 mA (so ~60 mA for white),
 * plus ~1 mA of quiescent draw per pixel for its controller. */
#define LED_CH_FULL_MA    20u
#define LED_QUIESCENT_MA   1u

/* ---- build-time checks ---------------------------------------------------
 * WS2812B bit timing is the one thing that cannot be debugged by looking at the
 * strip: get it wrong and you see wrong colours at random indexes, which reads
 * exactly like a data or wiring fault. So it is checked here instead. If you
 * change the clock tree or the timer period, the BUILD fails and tells you,
 * rather than the strip glitching on the bench. */
#define LED_NS(duty)   ((uint32_t)(((uint64_t)(duty) * 1000000000ull) / LED_TIM_HZ))

/* The windows below are the INTERSECTION of the 2013 WS2812B datasheet and the
 * 2020+ WS2812B-V5 one, so a frame that passes here is legal on either part:
 *     T0H  250..450 ns     T0L  750..1000 ns
 *     T1H  750..950 ns     T1L  300..450  ns
 * See the timing note in led_strip.h for where those numbers come from. */
_Static_assert((LED_TIM_HZ / (LED_TIM_ARR + 1u)) == 800000u,
               "WS2812B needs an 800 kHz bit rate: fix LED_TIM_HZ / LED_TIM_ARR");
_Static_assert(LED_NS(LED_DUTY_0) >= 250u && LED_NS(LED_DUTY_0) <= 450u,
               "T0H must suit BOTH WS2812B and WS2812B-V5 (250..450 ns)");
_Static_assert(LED_NS(LED_DUTY_1) >= 750u && LED_NS(LED_DUTY_1) <= 950u,
               "T1H must suit BOTH WS2812B and WS2812B-V5 (750..950 ns)");
_Static_assert(LED_NS(LED_TIM_ARR + 1u - LED_DUTY_0) >= 750u,
               "T0L too short for WS2812B-V5");
_Static_assert(LED_NS(LED_TIM_ARR + 1u - LED_DUTY_1) >= 300u
               && LED_NS(LED_TIM_ARR + 1u - LED_DUTY_1) <= 450u,
               "T1L must suit BOTH WS2812B and WS2812B-V5 (300..450 ns)");
_Static_assert(LED_DUTY_0 > 0u && LED_DUTY_1 <= LED_TIM_ARR,
               "duty values must fit inside one timer period");
_Static_assert(LED_NS(LED_RESET_TRAIL * (LED_TIM_ARR + 1u)) >= 280000u,
               "trailing reset under 280us: the last pixel will miss updates");
_Static_assert(LED_COUNT_DEFAULT >= 1u && LED_COUNT_DEFAULT <= LED_COUNT_MAX,
               "LED_COUNT_DEFAULT must fit within LED_COUNT_MAX");
_Static_assert(LED_MASTER_PCT_DEFAULT <= 100u, "master brightness is a percentage");
_Static_assert(LED_WIRE_MAX <= 65535u,
               "frame is longer than the uint16 length HAL_TIM_PWM_Start_DMA takes");

/* ---- state -------------------------------------------------------------- */

static TIM_HandleTypeDef *s_htim;
static uint32_t           s_channel;

static uint8_t  s_r[LED_COUNT_MAX];
static uint8_t  s_g[LED_COUNT_MAX];
static uint8_t  s_b[LED_COUNT_MAX];
static uint8_t  s_pct[LED_COUNT_MAX];       /* per-pixel brightness 0..100    */
static bool     s_blink[LED_COUNT_MAX];     /* this pixel blinks              */
static bool     s_blink_any;                /* does ANY pixel blink?          */
static bool     s_blink_on = true;          /* current phase, driven by led_fx*/

static uint16_t s_count   = LED_COUNT_DEFAULT;
static uint8_t  s_master  = LED_MASTER_PCT_DEFAULT;
static uint16_t s_budget  = LED_BUDGET_MA_DEFAULT;

/* Swipe overlay: drawn on top of the pixel buffer, never into it. */
static uint16_t s_ov_i[LED_OVERLAY_MAX];
static uint8_t  s_ov_r[LED_OVERLAY_MAX];
static uint8_t  s_ov_g[LED_OVERLAY_MAX];
static uint8_t  s_ov_b[LED_OVERLAY_MAX];
static uint8_t  s_ov_n;

/* DMA buffer. 4-byte aligned because HAL_TIM_PWM_Start_DMA takes a uint32_t*
 * even though the stream is configured half-word / half-word. */
static uint16_t s_wire[LED_WIRE_MAX] __attribute__((aligned(4)));

static volatile bool s_busy;        /* a frame is streaming right now         */
static volatile bool s_dirty;       /* something changed, needs sending       */
                                    /* both are touched by the DMA ISR        */
static uint32_t      s_last_frame;  /* millis() of the last frame we started  */

static uint16_t s_last_ma;          /* diagnostics for the status command     */
static uint8_t  s_last_pwr_pct = 100;

/* Consecutive DMA faults, so a permanent one cannot become a retry loop. */
#define LED_MAX_ERR_RETRIES  3u
static uint8_t  s_err_run;

/* Re-blank the strip a few times right after boot: the 5V rail is still ramping
 * while the MCU is already running, and a WS2812B powers up holding whatever
 * happened to be in its shift register.
 *
 * These opening frames are sent at the FULL LED_COUNT_MAX length, not at
 * s_count. If the strip physically has more pixels than `count` says, the extra
 * ones would otherwise never be addressed at all and would sit showing their
 * random power-on colour forever — which reads as "some LEDs are lit and I
 * can't turn them off". Clocking a long run of zeros clears the whole chain
 * whatever its real length; the surplus bits simply fall off the end. */
static uint8_t  s_boot_blanks;
static uint32_t s_boot_next;
static uint16_t s_blank_px;         /* >0: send this many black pixels instead */

/* ---- the data pin -------------------------------------------------------
 * PE9 must NEVER be left floating. TIM1 is configured with OSSR = DISABLE, so
 * the instant HAL_TIM_PWM_Stop_DMA() clears CC1E the pad's output driver is
 * switched off and the pin goes high impedance — and because frames are only
 * sent when something changes, it would stay that way indefinitely. A
 * metres-long data line running alongside the strip's own switching supply
 * picks up more than a ~40k internal pull-down can hold down; the noise is
 * clocked into pixel 0 and cascades, and the strip never settles.
 *
 * So the pin lives as a push-pull GPIO output holding 0, and is lent to the
 * timer only while a frame is streaming. Both handovers are ordered so the pad
 * is driven by one peripheral or the other at every instant — never neither.
 *
 * Everything except MODER (speed, pull, output type, alternate function) is set
 * once in led_strip_init(), so these two only have to flip two bits. */
#define LED_MODER_MASK   (0x3UL << (LED_GPIO_PIN_NO * 2u))
#define LED_MODER_OUT    (0x1UL << (LED_GPIO_PIN_NO * 2u))   /* GPIO output    */
#define LED_MODER_AF     (0x2UL << (LED_GPIO_PIN_NO * 2u))   /* alternate func */

/* Drive the line low from the GPIO peripheral (the idle state). */
static inline void pin_drive_low(void)
{
    LED_GPIO_PORT->BSRR = (uint32_t)(1UL << (LED_GPIO_PIN_NO + 16u));  /* ODR=0 */
    MODIFY_REG(LED_GPIO_PORT->MODER, LED_MODER_MASK, LED_MODER_OUT);
}

/* Hand the line to TIM1_CH1 for the duration of a frame. */
static inline void pin_to_timer(void)
{
    MODIFY_REG(LED_GPIO_PORT->MODER, LED_MODER_MASK, LED_MODER_AF);
}

/* ---- helpers ------------------------------------------------------------ */

static inline bool idx_ok(uint16_t i) { return i < s_count; }

static inline uint8_t clamp_pct(uint8_t pct) { return (pct > 100u) ? 100u : pct; }

/* Scale one 8-bit channel by pixel% and master%, with rounding. Both factors
 * are applied in ONE division so a dim pixel does not lose its colour to two
 * rounds of truncation. */
static inline uint8_t scale_ch(uint8_t v, uint8_t pixel_pct, uint8_t master_pct)
{
    uint32_t n = (uint32_t)v * pixel_pct * master_pct;   /* max 255*100*100   */
    return (uint8_t)((n + 5000u) / 10000u);
}

/* Expand one byte, MSB first, into 8 CCR duty values. */
static uint16_t *encode_byte(uint16_t *p, uint8_t v)
{
    for (uint8_t bit = 0; bit < 8u; bit++) {
        *p++ = (v & 0x80u) ? (uint16_t)LED_DUTY_1 : (uint16_t)LED_DUTY_0;
        v = (uint8_t)(v << 1);
    }
    return p;
}

/* Resolve pixel i: buffer colour, overridden by the overlay if the swipe is
 * currently sitting on it. Brightness is applied afterwards, identically in
 * both cases, so a swiped pixel obeys the same master/pixel brightness rules. */
static void composite(uint16_t i, uint8_t *r, uint8_t *g, uint8_t *b)
{
    uint8_t cr = s_r[i], cg = s_g[i], cb = s_b[i];
    bool    overlaid = false;

    for (uint8_t k = 0; k < s_ov_n; k++) {
        if (s_ov_i[k] == i) {
            cr = s_ov_r[k]; cg = s_ov_g[k]; cb = s_ov_b[k];
            overlaid = true;
            break;
        }
    }
    /* The swipe head wins over a blink, so a swipe stays a solid moving point
     * even when it crosses pixels that are blinking underneath it. */
    if (!overlaid && s_blink[i] && !s_blink_on) {
        cr = 0u; cg = 0u; cb = 0u;
    }
    *r = scale_ch(cr, s_pct[i], s_master);
    *g = scale_ch(cg, s_pct[i], s_master);
    *b = scale_ch(cb, s_pct[i], s_master);
}

/* ---- frame build + send -------------------------------------------------- */

/* Build the whole wire frame and hand it to the DMA. Only ever called with the
 * DMA idle, so the buffer is never rewritten underneath a running transfer. */
static void send_frame(void)
{
    static uint8_t fr[LED_COUNT_MAX], fg[LED_COUNT_MAX], fb[LED_COUNT_MAX];
    uint32_t sum = 0;
    uint16_t i;

    /* A boot-blank frame is a plain run of zeros over the whole possible chain
     * length; a normal frame is s_count pixels of whatever is painted. */
    const bool     blank = (s_blank_px > 0u);
    const uint16_t n     = blank ? s_blank_px : s_count;

    /* 1. Resolve every pixel to its final colour, and total the channels. */
    for (i = 0; i < n; i++) {
        if (blank) {
            fr[i] = 0u; fg[i] = 0u; fb[i] = 0u;
        } else {
            composite(i, &fr[i], &fg[i], &fb[i]);
        }
        sum += (uint32_t)fr[i] + fg[i] + fb[i];
    }

    /* 2. Current budget. Estimate the draw; if it is over, scale the WHOLE
     *    frame by one factor so every colour keeps its hue. */
    uint32_t quiescent = (uint32_t)n * LED_QUIESCENT_MA;
    uint32_t ma  = quiescent + ((sum * LED_CH_FULL_MA) / 255u);
    uint32_t pwr = 256u;                       /* 8.8 fixed point, 256 = x1   */
    if (s_budget > 0u && ma > (uint32_t)s_budget) {
        uint32_t avail   = ((uint32_t)s_budget > quiescent)
                         ? ((uint32_t)s_budget - quiescent) : 0u;
        uint32_t allowed = (avail * 255u) / LED_CH_FULL_MA;    /* channel sum  */
        pwr = (sum > 0u) ? ((allowed * 256u) / sum) : 256u;
        if (pwr > 256u) pwr = 256u;
        sum = (sum * pwr) / 256u;
        ma  = quiescent + ((sum * LED_CH_FULL_MA) / 255u);
    }
    s_last_ma      = (ma > 65535u) ? 65535u : (uint16_t)ma;
    s_last_pwr_pct = (uint8_t)((pwr * 100u) / 256u);

    /* 3. Encode. WS2812B takes its bytes in G, R, B order. */
    uint16_t *p = s_wire;
    for (i = 0; i < LED_RESET_LEAD; i++) {
        *p++ = 0u;
    }
    for (i = 0; i < n; i++) {
        uint8_t r = fr[i], g = fg[i], b = fb[i];
        if (pwr < 256u) {
            r = (uint8_t)(((uint32_t)r * pwr) / 256u);
            g = (uint8_t)(((uint32_t)g * pwr) / 256u);
            b = (uint8_t)(((uint32_t)b * pwr) / 256u);
        }
        p = encode_byte(p, g);
        p = encode_byte(p, r);
        p = encode_byte(p, b);
    }
    for (i = 0; i < LED_RESET_TRAIL; i++) {
        *p++ = 0u;
    }

    uint32_t len = (uint32_t)(p - s_wire);

    /* 4. Start clean: force the compare register to 0 and commit it with an
     *    update event, so the line is low and the counter is at a known point
     *    before the first data bit. Without this the first bit inherits the tail
     *    of whatever the timer was doing and can come out the wrong width. */
    __HAL_TIM_SET_COMPARE(s_htim, s_channel, 0u);
    s_htim->Instance->EGR = TIM_EGR_UG;

    s_busy       = true;
    s_dirty      = false;
    s_blank_px   = 0u;          /* this frame consumes the blank request */
    s_last_frame = millis();

    if (HAL_TIM_PWM_Start_DMA(s_htim, s_channel,
                              (const uint32_t *)(const void *)s_wire,
                              (uint16_t)len) != HAL_OK) {
        /* Could not arm the stream. Release it and retry on the next pass
         * rather than latching s_busy forever, which would freeze the strip.
         * The pin stays a GPIO output driving low — nothing reaches the strip. */
        (void)HAL_TIM_PWM_Stop_DMA(s_htim, s_channel);
        pin_drive_low();
        s_busy  = false;
        s_dirty = true;
        return;
    }

    /* Hand the pad over only now, with the timer already running and MOE on, so
     * it goes straight from GPIO-driven-low to timer-driven — never floating.
     * The microsecond of output lost here falls inside the leading reset gap. */
    pin_to_timer();
}

void led_strip_task(void)
{
    led_fx_tick();          /* may move the overlay, which sets s_dirty */

    if (s_boot_blanks > 0u && (int32_t)(millis() - s_boot_next) >= 0) {
        s_boot_blanks--;
        s_boot_next = millis() + 120u;
        /* Full-length zeros while blanking, then one normal frame to finish. */
        s_blank_px  = (s_boot_blanks > 0u) ? (uint16_t)LED_COUNT_MAX : 0u;
        s_dirty     = true;
    }

    if (!s_dirty || s_busy) {
        return;
    }
    if ((millis() - s_last_frame) < LED_MIN_FRAME_MS) {
        return;             /* give the strip its latch window */
    }
    send_frame();
}

void led_strip_on_dma_complete(TIM_HandleTypeDef *htim)
{
    if (htim == s_htim && s_busy) {
        /* Take the pad back BEFORE stopping the timer. Stop_DMA clears CC1E,
         * which switches the pad driver off; doing it in this order means the
         * line goes from timer-driven straight to GPIO-driven-low, with no
         * floating moment in between. The couple of hundred nanoseconds this
         * clips off the end of a 375us reset gap does not matter. */
        pin_drive_low();
        (void)HAL_TIM_PWM_Stop_DMA(s_htim, s_channel);
        s_busy    = false;
        s_err_run = 0u;         /* a good frame clears the error streak */
    }
}

void led_strip_on_dma_error(TIM_HandleTypeDef *htim)
{
    if (htim == s_htim) {
        pin_drive_low();
        (void)HAL_TIM_PWM_Stop_DMA(s_htim, s_channel);
        s_busy = false;
        /* Redraw the frame that was lost — but only a few times. A permanent
         * fault must not turn into an unbounded retry loop hammering the strip
         * with half-frames, which would look exactly like the flicker this
         * driver exists to avoid. After the limit the strip simply holds what
         * it has until the next command. */
        if (s_err_run < LED_MAX_ERR_RETRIES) {
            s_err_run++;
            s_dirty = true;
        }
    }
}

/* ---- init ---------------------------------------------------------------- */

void led_strip_init(TIM_HandleTypeDef *htim, uint32_t channel)
{
    GPIO_InitTypeDef g = {0};

    s_htim    = htim;
    s_channel = channel;
    s_busy    = false;
    s_count   = LED_COUNT_DEFAULT;
    s_master  = LED_MASTER_PCT_DEFAULT;
    s_budget  = LED_BUDGET_MA_DEFAULT;

    /* Set the data pin up once, here in app code, so a CubeMX regeneration
     * cannot silently undo it. Everything but MODER is fixed from now on:
     * push-pull, very high slew for crisp 800 kHz edges, AF1 = TIM1_CH1 ready
     * for when the timer borrows the pin, and a pull-down that only has to
     * cover the reset window before the GPIO output takes over.
     * pin_drive_low() then flips MODER to GPIO output, and that is the state
     * the line sits in whenever a frame is not streaming. */
    __HAL_RCC_GPIOE_CLK_ENABLE();
    g.Pin       = (uint32_t)(1UL << LED_GPIO_PIN_NO);
    g.Mode      = GPIO_MODE_AF_PP;
    g.Pull      = GPIO_PULLDOWN;
    g.Speed     = GPIO_SPEED_FREQ_VERY_HIGH;
    g.Alternate = LED_GPIO_AF;
    HAL_GPIO_Init(LED_GPIO_PORT, &g);
    pin_drive_low();

    led_strip_reset();
    led_fx_init();

    s_boot_blanks = 4u;          /* 3 full-length blanks + 1 real frame */
    s_boot_next   = millis() + 100u;
    s_blank_px    = (uint16_t)LED_COUNT_MAX;
    s_err_run     = 0u;
    s_dirty       = true;
}

/* ---- pixel buffer -------------------------------------------------------- */

/* Recompute the "is anything blinking" shortcut. Cheap, and only ever runs on
 * a command, so it can never be wrong. */
static void refresh_blink_any(void)
{
    s_blink_any = false;
    for (uint16_t i = 0; i < s_count; i++) {
        if (s_blink[i]) { s_blink_any = true; return; }
    }
}

void led_strip_set(uint16_t i, uint8_t r, uint8_t g, uint8_t b)
{
    if (!idx_ok(i)) return;
    s_r[i] = r; s_g[i] = g; s_b[i] = b;
    s_blink[i] = false;         /* a colour command means "make this solid" */
    refresh_blink_any();
    s_dirty = true;
}

void led_strip_set_range(uint16_t a, uint16_t b, uint8_t r, uint8_t g, uint8_t bl)
{
    if (a > b) { uint16_t t = a; a = b; b = t; }
    for (uint16_t i = a; i <= b && i < s_count; i++) {
        s_r[i] = r; s_g[i] = g; s_b[i] = bl;
        s_blink[i] = false;
    }
    refresh_blink_any();
    s_dirty = true;
}

void led_strip_set_all(uint8_t r, uint8_t g, uint8_t b)
{
    for (uint16_t i = 0; i < s_count; i++) {
        s_r[i] = r; s_g[i] = g; s_b[i] = b;
        s_blink[i] = false;
    }
    s_blink_any = false;
    s_dirty = true;
}

void led_strip_set_pixel_pct(uint16_t i, uint8_t pct)
{
    if (!idx_ok(i)) return;
    s_pct[i] = clamp_pct(pct);
    s_dirty = true;
}

void led_strip_set_range_pct(uint16_t a, uint16_t b, uint8_t pct)
{
    if (a > b) { uint16_t t = a; a = b; b = t; }
    pct = clamp_pct(pct);
    for (uint16_t i = a; i <= b && i < s_count; i++) {
        s_pct[i] = pct;
    }
    s_dirty = true;
}

uint8_t led_strip_get_pixel_pct(uint16_t i)
{
    return idx_ok(i) ? s_pct[i] : 0u;
}

void led_strip_set_master_pct(uint8_t pct)
{
    s_master = clamp_pct(pct);
    s_dirty = true;
}

uint8_t led_strip_get_master_pct(void) { return s_master; }

void led_strip_reset(void)
{
    for (uint16_t i = 0; i < LED_COUNT_MAX; i++) {
        s_r[i] = 0u; s_g[i] = 0u; s_b[i] = 0u;
        s_pct[i]   = 100u;
        s_blink[i] = false;
    }
    s_ov_n      = 0u;
    s_blink_any = false;
    s_blink_on  = true;
    s_dirty     = true;
}

/* ---- blink --------------------------------------------------------------- */

void led_strip_set_blink(uint16_t i, bool on)
{
    if (!idx_ok(i)) return;
    s_blink[i] = on;
    refresh_blink_any();
    s_dirty = true;
}

void led_strip_set_range_blink(uint16_t a, uint16_t b, bool on)
{
    if (a > b) { uint16_t t = a; a = b; b = t; }
    for (uint16_t i = a; i <= b && i < s_count; i++) {
        s_blink[i] = on;
    }
    refresh_blink_any();
    s_dirty = true;
}

void led_strip_clear_blink(void)
{
    for (uint16_t i = 0; i < LED_COUNT_MAX; i++) {
        s_blink[i] = false;
    }
    s_blink_any = false;
    s_blink_on  = true;     /* leave the pixels lit, not stranded dark */
    s_dirty     = true;
}

bool led_strip_any_blink(void) { return s_blink_any; }

uint16_t led_strip_blink_count(void)
{
    uint16_t n = 0;
    for (uint16_t i = 0; i < s_count; i++) {
        if (s_blink[i]) n++;
    }
    return n;
}

void led_strip_set_blink_phase(bool lit)
{
    if (s_blink_on != lit) {
        s_blink_on = lit;
        if (s_blink_any) {
            s_dirty = true;     /* only redraw if it actually shows */
        }
    }
}

/* ---- overlay ------------------------------------------------------------- */

void led_strip_overlay_clear(void)
{
    if (s_ov_n != 0u) {
        s_ov_n  = 0u;
        s_dirty = true;
    }
}

void led_strip_overlay_add(uint16_t i, uint8_t r, uint8_t g, uint8_t b)
{
    if (!idx_ok(i) || s_ov_n >= LED_OVERLAY_MAX) return;
    s_ov_i[s_ov_n] = i;
    s_ov_r[s_ov_n] = r;
    s_ov_g[s_ov_n] = g;
    s_ov_b[s_ov_n] = b;
    s_ov_n++;
    s_dirty = true;
}

/* ---- geometry & power ---------------------------------------------------- */

uint16_t led_strip_count(void) { return s_count; }

bool led_strip_set_count(uint16_t n)
{
    if (n == 0u || n > LED_COUNT_MAX) {
        return false;
    }
    /* Changing the length must not leave pixels lit with stale colours from an
     * earlier, longer setting: blank everything and start clean. */
    s_count = n;
    led_fx_stop();
    led_strip_reset();
    return true;
}

void led_strip_set_budget_ma(uint16_t ma) { s_budget = ma; s_dirty = true; }
uint16_t led_strip_get_budget_ma(void)    { return s_budget; }
uint16_t led_strip_last_ma(void)          { return s_last_ma; }
uint8_t  led_strip_last_power_pct(void)   { return s_last_pwr_pct; }
