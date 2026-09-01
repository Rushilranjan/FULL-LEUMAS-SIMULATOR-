#include "app.h"
#include "config.h"
#include "led_strip.h"
#include "led_cmd.h"
#include "module.h"
#include "solid_seq1.h"
#include "seq_leds.h"
#include "button.h"
#include "ble.h"

void app_init(TIM_HandleTypeDef *led_tim, uint32_t led_ch,
              UART_HandleTypeDef *ble_uart,
              UART_HandleTypeDef *cmd_uart)
{
    /* The strip first, so it is blanked before anything else can run. */
    led_strip_init(led_tim, led_ch);
    led_cmd_init(cmd_uart);

    ble_init(ble_uart);
    button_init();
    module_init();
    ble_ready();
}

void app_tick(void)
{
    /* Take any typed command first, then anything the app has sent. */
    led_cmd_poll();
    ble_poll();

    /* Change 16.1/16.7: NOTHING lights up on its own, and the board button does
     * not start anything either -- it SELECTS.
     *
     * The demo opens in two beats, and they are deliberately different events:
     *
     *   button press  ->  RECIPE:1 goes out, and nothing else happens at all.
     *                     The rig stays dark. The app shows the recipe and
     *                     offers its START button.
     *   START arrives ->  the run actually begins, and SEQ:1 announces it.
     *
     * 16.1 had the button starting the run outright, which lit the feeders the
     * instant it was pressed and left the operator watching a demo that had
     * already begun while the recipe card was still playing. Selecting and
     * starting are now separate, so the recipe is read over a dark board and
     * the run begins when -- and only when -- the operator says so.
     *
     * `seq 1` on the console still starts directly, through led_cmd_poll()
     * above: it is the bench path for working without a phone, and there is no
     * app there to press anything.
     *
     * Starting from the app also pins t=0 on both sides, so its arm animation
     * and the board's colour changes begin from the same instant instead of
     * the app inferring the start from whichever cue it caught first. */
    if (button_poll()) {
        ble_recipe(SS1_SEQ_NUMBER);
    }
    if (ble_take_start()) {
        solid_seq1_start();
    }

    /* State machine, then renderer, then driver — in that order, so a module
     * state or a strip pixel set this pass is drawn this pass rather than a
     * frame late. */
    solid_seq1_tick();
    seq_leds_tick();

    /* The strip goes out LAST. Exactly one of the LED command centre and
     * seq_leds has written to it. */
    led_strip_task();
}

void app_on_button_exti(uint16_t gpio_pin)
{
    button_on_exti(gpio_pin);
}

void app_on_ble_uart_irq(void)
{
    ble_on_irq();
}

void app_on_pwm_complete(TIM_HandleTypeDef *htim)
{
    led_strip_on_dma_complete(htim);
}

void app_on_tim_error(TIM_HandleTypeDef *htim)
{
    led_strip_on_dma_error(htim);
}
