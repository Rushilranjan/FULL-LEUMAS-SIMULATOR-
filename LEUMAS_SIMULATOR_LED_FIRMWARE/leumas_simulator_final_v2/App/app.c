#include "app.h"
#include "config.h"
#include "led_strip.h"
#include "led_cmd.h"
#include "module.h"
#include "lotmgr.h"
#include "seq_leds.h"
#include "button.h"
#include "ble.h"

static uint8_t s_cycle;     /* index into SEQ_CYCLE for the next button press */

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

    s_cycle = 0u;
}

void app_tick(void)
{
    /* Take any typed command first. */
    led_cmd_poll();

    /* The button walks SEQ_CYCLE (1 -> 2 -> 4 -> 5 -> 1 ...); `seq <n>` on the
     * console starts one directly. Either way lotmgr drives the state machine
     * and the BLE cues, and seq_leds draws whatever state it lands in. */
    if (button_poll()) {
        uint8_t seq = SEQ_CYCLE[s_cycle];
        s_cycle = (uint8_t)((s_cycle + 1u) % SEQ_CYCLE_COUNT);
        lotmgr_start(seq);
    }
    lotmgr_tick();
    seq_leds_tick();

    /* The strip goes out LAST, so a state change made this pass reaches the
     * LEDs in the same pass rather than a frame late. Exactly one of the LED
     * command centre and seq_leds has written to it. */
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
