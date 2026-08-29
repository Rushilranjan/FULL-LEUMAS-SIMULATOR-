#include "timebase.h"
#include "main.h"   /* pulls in HAL for HAL_GetTick() */

uint32_t millis(void)
{
    return HAL_GetTick();
}

void timer_start(SoftTimer *t, uint32_t period_ms)
{
    t->start   = millis();
    t->period  = period_ms;
    t->running = true;
}

void timer_stop(SoftTimer *t)
{
    t->running = false;
}

bool timer_expired(SoftTimer *t)
{
    if (!t->running) {
        return false;
    }
    if ((millis() - t->start) >= t->period) {
        t->running = false;     /* one-shot: fires once */
        return true;
    }
    return false;
}

uint32_t timer_elapsed(const SoftTimer *t)
{
    return millis() - t->start;
}
