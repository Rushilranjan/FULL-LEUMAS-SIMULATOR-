/**
 * led_strip.h — WS2812B addressable RGB strip driver.
 *
 *   Strip    : WS2812B 5V, 30 LED/m black flex strip (BTF-5V-30L-W)
 *   Data pin : PE9  ->  TIM1_CH1  ->  DMA2_Stream1  (already set up by CubeMX)
 *   Wire fmt : 24 bit per pixel, GRB order, MSB first, 800 kHz
 *
 * ---------------------------------------------------------------------------
 * WHY THIS DRIVER DOES NOT GLITCH
 * ---------------------------------------------------------------------------
 *  1. ONE writer. Nothing in this firmware touches a pixel except led_cmd /
 *     led_fx. No background animation, no demo engine, no "status" overlay.
 *  2. The DMA buffer is NEVER rebuilt while DMA is reading it. A change that
 *     lands mid-frame just sets a dirty flag and is sent on the next frame.
 *  3. Every frame carries its own reset gap at BOTH ends (lead + trail), so a
 *     frame can never be mis-parsed as a continuation of the previous one.
 *  4. THE DATA LINE IS NEVER LEFT FLOATING. This is the big one. TIM1 is an
 *     advanced timer configured with OSSR = TIM_OSSR_DISABLE, so the moment
 *     HAL_TIM_PWM_Stop_DMA() clears CC1E the pad's output driver is switched
 *     OFF and PE9 goes high impedance. An internal pull-down is ~40k, which is
 *     nowhere near enough to hold a metres-long data line quiet: the strip's own
 *     supply and ground run beside it for its whole length, and every WS2812B is
 *     switching constant-current drivers internally. Coupled noise gets clocked
 *     into the first pixel and cascades down the chain, so LEDs keep changing
 *     colour on their own and never settle.
 *     So between frames PE9 is handed to the GPIO peripheral as a push-pull
 *     output holding 0, and given back to the timer only for the microseconds a
 *     frame is actually streaming. The pin is ACTIVELY DRIVEN at all times; the
 *     pull-down is left configured purely as a belt-and-braces during reset.
 *  5. Frames are sent ONLY when something actually changed. A WS2812B latches
 *     its colour and holds it; re-sending an identical frame 50x a second buys
 *     nothing and only adds chances to be corrupted. This is safe precisely
 *     BECAUSE of point 4 — with the line driven low, nothing can disturb a
 *     latched pixel, so there is nothing to refresh.
 *  6. A current budget scales the whole frame down (hue preserved) before it is
 *     sent, so a full-brightness command can never brown the rail out. A sagging
 *     5V rail makes the far end of the strip flicker and reads exactly like a
 *     data bug.
 */
#ifndef APP_LED_STRIP_H
#define APP_LED_STRIP_H

#include <stdint.h>
#include <stdbool.h>
#include "main.h"       /* TIM_HandleTypeDef */

/* =====================================================================
 *  TUNING — the only numbers you should ever need to change.
 * ===================================================================== */

/* How many pixels are physically on the strip. Also changeable at runtime with
 * the `count <n>` command, so you do not have to reflash to try another strip. */
#define LED_COUNT_DEFAULT     90

/* Hard ceiling: sizes the frame buffer. Raise it if you fit a longer strip.
 * Cost is 2 bytes of RAM per (24 * pixel). */
#define LED_COUNT_MAX         200

/* Master brightness at power-on, in percent (0..100). Applies to every pixel.
 *
 * THIS IS A CURRENT BUDGET, NOT JUST A LOOK. One WS2812B at full white draws
 * ~60 mA, so 90 pixels of white is ~5.4 A — far beyond any USB port. A 90 pixel
 * strip NEEDS ITS OWN 5V SUPPLY (2-3 A), with its ground tied to the Nucleo's
 * GND. Feeding it from the Nucleo's 5V pin browns out the far end of the strip
 * and looks exactly like a data/index bug.
 *
 * Left at 100 so the IN-MODULE LEDs are genuinely at full brightness. The two
 * kinds of pixel are then separated by their PER-PIXEL percentage instead of by
 * this one: modules SS1_MODULE_PCT, the strip SEQ_STRIP_PCT (config.h). Dropping
 * this below 100 dims both of them together and flattens that split. */
#define LED_MASTER_PCT_DEFAULT   100

/* Current budget in milliamps for the whole strip. If a frame would exceed it,
 * the WHOLE frame is scaled down proportionally (colours keep their hue) before
 * being sent. Set to 0, or type `power off`, to disable the cap.
 * 1000 mA suits a modest external 5V supply; raise it if yours is bigger. */
#define LED_BUDGET_MA_DEFAULT    1000

/* The data pin. PE9 is TIM1_CH1 (AF1) and is fed by DMA2_Stream1. */
#define LED_GPIO_PORT         GPIOE
#define LED_GPIO_PIN_NO       9u
#define LED_GPIO_AF           GPIO_AF1_TIM1

/* Bit timing. Counter clock is 96 MHz on this board (TIM1 lives on APB2), and
 * the .ioc sets Period(ARR)=119, giving 96e6/120 = 800 kHz => 1.25 us per bit.
 *
 * The duty values are NOT the obvious 1/3 and 2/3 of the period. There are two
 * generations of this part in circulation and their windows only partly overlap:
 *
 *              WS2812B (2013)        WS2812B-V5 (2020+, what you buy today)
 *     T0H      0.40us +/-150ns       0.30us +/-150ns
 *     T1H      0.80us +/-150ns       0.90us +/-150ns
 *
 * 1/3 and 2/3 (CCR 40 / 80 => 417ns / 833ns) sits only 33ns inside the V5
 * ceiling for T0H and for T1L. That is not enough margin once you add clock
 * tolerance, DMA latency and the edge degradation down a long chain — it shows
 * up as occasional wrong bits at scattered pixels, i.e. flicker.
 *
 * 34 / 82 (354ns / 854ns) is centred in BOTH datasheets, worst-case margin
 * 54ns instead of 33ns, and is what this driver uses. Keep it that way unless
 * you have a scope on the line. */
#define LED_TIM_HZ            96000000u
#define LED_TIM_ARR           ((LED_TIM_HZ / 800000u) - 1u)   /* 119, 1.25us  */
#define LED_DUTY_0            34u                             /* T0H ~354 ns  */
#define LED_DUTY_1            82u                             /* T1H ~854 ns  */

/* Reset ("latch") gaps, in 1.25 us bit slots, sent as part of the frame itself.
 * Original WS2812B latched after 50 us; WS2812B-V5 and most 2023+ stock needs
 * ~280 us. A short trail shows up as the LAST pixel randomly not updating. */
#define LED_RESET_LEAD        40u    /*  50 us of low before the data          */
#define LED_RESET_TRAIL       300u   /* 375 us of low after it — latches       */

/* Floor on the gap between two frames. The trailing reset already covers the
 * latch; this just stops a burst of typed commands from queueing frames back to
 * back faster than the strip can absorb them. */
#define LED_MIN_FRAME_MS      2u

/* =====================================================================
 *  API
 * ===================================================================== */

/* Wire up the driver to the timer channel that owns PE9 (TIM1 / TIM_CHANNEL_1),
 * blank the strip and arm the first frame. Call once, after MX_TIM1_Init(). */
void led_strip_init(TIM_HandleTypeDef *htim, uint32_t channel);

/* Call every main-loop pass. Advances the swipe animation and pushes a frame if
 * anything changed. Never blocks. */
void led_strip_task(void);

/* Forward from HAL_TIM_PWM_PulseFinishedCallback() — releases the DMA. */
void led_strip_on_dma_complete(TIM_HandleTypeDef *htim);
/* Forward from HAL_TIM_ErrorCallback() — un-sticks the driver after a DMA fault. */
void led_strip_on_dma_error(TIM_HandleTypeDef *htim);

/* ---- the pixel buffer ---------------------------------------------------
 * Every pixel holds a colour AND its own brightness percentage. They are
 * independent: setting a colour does not reset that pixel's brightness, and
 * setting a brightness does not disturb its colour. `led_strip_reset()` puts
 * both back to defaults. Out-of-range indexes are ignored, never wrapped. */
void led_strip_set(uint16_t i, uint8_t r, uint8_t g, uint8_t b);
void led_strip_set_range(uint16_t a, uint16_t b, uint8_t r, uint8_t g, uint8_t bl);
void led_strip_set_all(uint8_t r, uint8_t g, uint8_t b);

void led_strip_set_pixel_pct(uint16_t i, uint8_t pct);           /* 0..100 */
void led_strip_set_range_pct(uint16_t a, uint16_t b, uint8_t pct);
uint8_t led_strip_get_pixel_pct(uint16_t i);

/* ---- blink --------------------------------------------------------------
 * Blinking is a per-pixel FLAG, not an animation that owns the strip, so any
 * mix of pixels can blink while a swipe runs over the top of them. A flagged
 * pixel simply shows black on the off half of the blink phase; its colour and
 * brightness are untouched, so it comes back exactly as it was.
 *
 * Painting a pixel any colour clears its blink flag — a colour command always
 * means "make this solid". led_fx owns the clock that drives the phase. */
void led_strip_set_blink(uint16_t i, bool on);
void led_strip_set_range_blink(uint16_t a, uint16_t b, bool on);
void led_strip_clear_blink(void);          /* stop every blinking pixel      */
bool led_strip_any_blink(void);            /* is anything blinking at all?   */
uint16_t led_strip_blink_count(void);
void led_strip_set_blink_phase(bool lit);  /* called by led_fx_tick()        */

void led_strip_set_master_pct(uint8_t pct);                      /* 0..100 */
uint8_t led_strip_get_master_pct(void);

/* Everything off, every per-pixel brightness back to 100%. */
void led_strip_reset(void);

/* ---- overlay ------------------------------------------------------------
 * The swipe draws here instead of into the pixel buffer, so an animation never
 * damages what you painted: stop the swipe and the painted frame is simply
 * there again, untouched. Max LED_OVERLAY_MAX pixels. */
#define LED_OVERLAY_MAX   9
void led_strip_overlay_clear(void);
void led_strip_overlay_add(uint16_t i, uint8_t r, uint8_t g, uint8_t b);

/* ---- strip geometry & power --------------------------------------------- */
uint16_t led_strip_count(void);
bool     led_strip_set_count(uint16_t n);       /* 1..LED_COUNT_MAX          */
void     led_strip_set_budget_ma(uint16_t ma);  /* 0 = no cap                */
uint16_t led_strip_get_budget_ma(void);

/* Estimated draw of the frame as last sent, in mA, and the scale the current
 * budget had to apply to it (100 = untouched). For the `status` command. */
uint16_t led_strip_last_ma(void);
uint8_t  led_strip_last_power_pct(void);

#endif /* APP_LED_STRIP_H */
