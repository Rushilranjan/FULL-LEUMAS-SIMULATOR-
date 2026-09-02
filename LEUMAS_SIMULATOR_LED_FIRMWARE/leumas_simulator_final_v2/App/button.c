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

#ifndef BUTTON_PORT
#  ifdef BTN_GPIO_Port
#    define BUTTON_PORT  BTN_GPIO_Port
#  elif defined(USER_Btn_GPIO_Port)
#    define BUTTON_PORT  USER_Btn_GPIO_Port
#  else
#    define BUTTON_PORT  GPIOC
#  endif
#endif

#if BUTTON_ACTIVE_HIGH
#  define BUTTON_EXTI_MODE  GPIO_MODE_IT_RISING
#  define BUTTON_PULL       GPIO_PULLDOWN
#  define BUTTON_PRESSED    GPIO_PIN_SET
#else
#  define BUTTON_EXTI_MODE  GPIO_MODE_IT_FALLING
#  define BUTTON_PULL       GPIO_PULLUP
#  define BUTTON_PRESSED    GPIO_PIN_RESET
#endif

static volatile uint32_t s_last_edge;    /* extended by EVERY edge, accepted or not */
static volatile bool     s_armed;        /* an edge is waiting to be qualified      */
static uint32_t          s_accepted_at;  /* when we last reported a press           */
static bool              s_have_accepted;

/*
 * Change 29: an EXTERNAL button bounces far worse than the board's own, and
 * this debounce was written for the board's own.
 *
 * Three separate faults, all of which produce "one press, two selects":
 *
 *  1. THE PIN FLOATED. CubeMX configures PC13 as GPIO_NOPULL because on a
 *     NUCLEO-144 the blue button already has an external pull-down fitted next
 *     to it. Wire a bare button to the same pin and there is no pull anywhere:
 *     the input floats whenever the button is open, picks up whatever is
 *     coupling into the flying lead, and throws rising edges with nobody
 *     touching it. That is the "super sensitive" part, and no amount of
 *     software debouncing fixes a floating input -- the edges are real. So the
 *     pin is re-configured here with an internal pull, in App code rather than
 *     in main.c, because main.c is CubeMX-generated and this would be lost the
 *     next time the .ioc is regenerated.
 *
 *  2. THE QUIET WINDOW DID NOT EXTEND. The old test was "has it been
 *     BUTTON_DEBOUNCE_MS since the last ACCEPTED press", so the window started
 *     at the first edge and ran out while the contact was still ringing;
 *     anything still bouncing after it was a fresh press. The window now
 *     restarts on every edge, so it measures what it should: how long the line
 *     has been QUIET.
 *
 *  3. RELEASE WAS NOT CONSIDERED. Opening a contact rings just like closing
 *     one, and on a long press that ring lands well after any plausible
 *     lockout. Qualifying a press on the pin's LEVEL once the line goes quiet
 *     discards it: by then a released button reads inactive.
 *
 * The cost of (3) is that a press has to outlast BUTTON_DEBOUNCE_MS to count,
 * so that constant is a floor on how brief a tap can be. At 35 ms it is well
 * under a deliberate press (50-150 ms) and well over contact bounce.
 */
void button_init(void)
{
    s_last_edge     = 0u;
    s_armed         = false;
    s_accepted_at   = 0u;
    s_have_accepted = false;

    GPIO_InitTypeDef init = {0};
    init.Pin   = BUTTON_PIN;
    init.Mode  = BUTTON_EXTI_MODE;
    init.Pull  = BUTTON_PULL;
    init.Speed = GPIO_SPEED_FREQ_LOW;
    HAL_GPIO_Init(BUTTON_PORT, &init);

    /* Change 16.1: the rig must be dark until somebody presses START, so a
     * press latched before we booted -- or an edge thrown by the reset itself,
     * which is the usual way a demo "starts on its own" -- must not come back
     * as a start the moment the main loop opens. Drop any pending EXTI for our
     * pin before the first button_poll() can see it. Re-configuring the pin
     * above can itself latch one, so this must stay last. */
    __HAL_GPIO_EXTI_CLEAR_IT(BUTTON_PIN);
}

void button_on_exti(uint16_t gpio_pin)
{
    if (gpio_pin != BUTTON_PIN) {
        return;
    }
    /* Every edge restarts the quiet window, including the ones that will be
     * rejected -- that is the whole point of fault (2) above. Qualifying is
     * left to button_poll() in the main loop; an ISR should not be reading
     * pin levels and running timers. */
    s_last_edge = millis();
    s_armed     = true;
}

bool button_poll(void)
{
    if (!s_armed) {
        return false;
    }

    uint32_t now = millis();
    if ((now - s_last_edge) < BUTTON_DEBOUNCE_MS) {
        return false;               /* still ringing; wait for the line to settle */
    }

    s_armed = false;

    /* The line is quiet. If the button is not still held, this was bounce off
     * the release of a press already reported, or noise -- either way it is
     * not a new press. */
    if (HAL_GPIO_ReadPin(BUTTON_PORT, BUTTON_PIN) != BUTTON_PRESSED) {
        return false;
    }

    if (s_have_accepted && (now - s_accepted_at) < BUTTON_LOCKOUT_MS) {
        return false;               /* the tail of a press we already reported */
    }

    s_accepted_at   = now;
    s_have_accepted = true;
    return true;
}
