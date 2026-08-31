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
    /* Take any typed command first. */
    led_cmd_poll();

    /* There is one sequence and one way in: the board button starts Solid
     * Sequence 1 from cycle 1, and so does `seq 1` on the console. Pressing it
     * again mid-demo restarts cleanly. */
    if (button_poll()) {
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

void app_on_pwm_complete(TIM_HandleTypeDef *htim)
{
    led_strip_on_dma_complete(htim);
}

void app_on_tim_error(TIM_HandleTypeDef *htim)
{
    led_strip_on_dma_error(htim);
}
