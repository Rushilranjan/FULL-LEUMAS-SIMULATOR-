/**
 * led_fx.h — the two time-based effects: the SWIPE and the BLINK.
 *
 * They are completely independent and have their own speeds, so a swipe can run
 * across pixels that are blinking underneath it without either disturbing the
 * other. Changing one speed never touches the other.
 *
 * SWIPE: one lit point travelling from LED a to LED b.
 *
 * The swipe draws into the strip OVERLAY, never into the pixel buffer, so:
 *   - whatever you painted underneath is untouched and reappears the moment the
 *     swipe stops,
 *   - the animation cannot leave a stray pixel behind when it ends,
 *   - only the head plus its tail are ever different from the painted frame.
 *
 * Speed is given as milliseconds per LED, so it means the same thing whatever
 * the distance: `swipe red 0 89 20` takes 90 * 20 ms = 1.8 s end to end.
 *
 * BLINK: which pixels blink is a per-pixel flag owned by led_strip; what lives
 * here is only the clock that flips the phase. Blink speed is the time each
 * half of the cycle lasts, so `blinkspeed 400` is 400 ms lit, 400 ms dark.
 */
#ifndef APP_LED_FX_H
#define APP_LED_FX_H

#include <stdint.h>
#include <stdbool.h>

#define LED_FX_STEP_MS_DEFAULT   40u   /* ms per LED when none is given       */
#define LED_FX_STEP_MS_MIN        1u
#define LED_FX_STEP_MS_MAX     5000u
#define LED_FX_TAIL_DEFAULT       3u   /* pixels of fading tail behind the head*/
#define LED_FX_TAIL_MAX           8u   /* must stay < LED_OVERLAY_MAX         */

#define LED_FX_BLINK_MS_DEFAULT 400u   /* ms lit, then ms dark                */
#define LED_FX_BLINK_MS_MIN      20u
#define LED_FX_BLINK_MS_MAX    5000u

void led_fx_init(void);

/* Start a swipe from LED `from` to LED `to` (either direction). `step_ms` is the
 * time spent on each LED; `loop` restarts it at `from` when it reaches `to`.
 * Returns false and changes nothing if the indexes are outside the strip. */
bool led_fx_swipe(uint16_t from, uint16_t to,
                  uint8_t r, uint8_t g, uint8_t b,
                  uint16_t step_ms, bool loop);

/* Stop immediately and clear the overlay, revealing the painted frame. */
void led_fx_stop(void);

/* Advance both effects. Called by led_strip_task(); do not call it elsewhere. */
void led_fx_tick(void);

bool     led_fx_active(void);
void     led_fx_set_tail(uint8_t n);    /* 0..LED_FX_TAIL_MAX */
uint8_t  led_fx_get_tail(void);

/* Swipe speed, in ms per LED. Setting it also re-times a swipe already running,
 * so `speed 10` while one is looping takes effect immediately. */
void     led_fx_set_step_ms(uint16_t ms);
uint16_t led_fx_get_step_ms(void);

/* Blink speed, in ms per half-cycle. Entirely separate from the swipe speed. */
void     led_fx_set_blink_ms(uint16_t ms);
uint16_t led_fx_get_blink_ms(void);

/* Restart the blink phase lit, so pixels light the instant you ask them to. */
void     led_fx_blink_restart(void);

#endif /* APP_LED_FX_H */
