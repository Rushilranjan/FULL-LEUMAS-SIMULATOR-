#include "button.h"
#include "config.h"
#include "timebase.h"
#include "main.h"   /* for BTN_Pin, if defined in CubeIDE; see note below */

/*
 * The EXTI line/pin is defined by the CubeIDE .ioc. Set a user label of "BTN"
 * on the external button pin so BTN_Pin is generated in main.h. If you use a
 * different label, change BUTTON_PIN below to match your generated macro.
 */
#ifndef BUTTON_PIN
#  ifdef BTN_Pin
#    define BUTTON_PIN   BTN_Pin
#  elif defined(USER_Btn_Pin)
#    define BUTTON_PIN   USER_Btn_Pin   /* CubeMX label for PC13 on NUCLEO-F767ZI */
#  else
#    define BUTTON_PIN   GPIO_PIN_13    /* fallback: Nucleo user button PC13 */
#  endif
#endif

static volatile uint32_t s_last_edge;
static volatile bool     s_pending;

void button_init(void)
{
    s_last_edge = 0;
    s_pending   = false;
    /* Change 16.1: the rig must be dark until somebody presses START, so a
     * press latched before we booted -- or an edge thrown by the reset itself,
     * which is the usual way a demo "starts on its own" -- must not come back
     * as a start the moment the main loop opens. Drop any pending EXTI for our
     * pin before the first button_poll() can see it. */
    __HAL_GPIO_EXTI_CLEAR_IT(BUTTON_PIN);
}

void button_on_exti(uint16_t gpio_pin)
{
    if (gpio_pin != BUTTON_PIN) {
        return;
    }
    uint32_t now = millis();
    if ((now - s_last_edge) >= BUTTON_DEBOUNCE_MS) {
        s_last_edge = now;
        s_pending   = true;     /* consumed by button_poll() in the main loop */
    }
}

bool button_poll(void)
{
    if (s_pending) {
        s_pending = false;
        return true;
    }
    return false;
}
