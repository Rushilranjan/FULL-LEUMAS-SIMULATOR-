/**
 * timebase.h — millisecond clock + tiny non-blocking soft timers.
 *
 * Uses the HAL SysTick tick (HAL_GetTick) so no extra peripheral is needed.
 * Nothing in the sequence path ever calls HAL_Delay — everything is polled
 * against millis() so LED animation, flashing, transfers and BLE run at once.
 */
#ifndef APP_TIMEBASE_H
#define APP_TIMEBASE_H

#include <stdint.h>
#include <stdbool.h>

uint32_t millis(void);

/* Soft timer: one-shot, polled. */
typedef struct {
    uint32_t start;
    uint32_t period;
    bool     running;
} SoftTimer;

void timer_start(SoftTimer *t, uint32_t period_ms);
void timer_stop(SoftTimer *t);
bool timer_expired(SoftTimer *t);       /* true once, when period elapses    */
uint32_t timer_elapsed(const SoftTimer *t);

#endif /* APP_TIMEBASE_H */
