/**
 * app.h — top-level glue between the CubeIDE-generated HAL and the application.
 *
 * Integration (in Core/Src/main.c):
 *   1. #include "app.h"
 *   2. After MX_*_Init(), before the while(1):
 *        app_init(&htim1, TIM_CHANNEL_1,   // WS2812B strip on PE9
 *                 &huart6,                 // BLE cue link to the robot
 *                 &huart3);                // LED command centre (ST-Link VCP)
 *   3. Inside while(1): app_tick();
 *   4. Forward the three HAL callbacks at the bottom of main.c:
 *        HAL_GPIO_EXTI_Callback            -> app_on_button_exti
 *        HAL_TIM_PWM_PulseFinishedCallback -> app_on_pwm_complete
 *        HAL_TIM_ErrorCallback             -> app_on_tim_error
 *
 * The strip has one owner at a time. Normally that is the LED command centre,
 * so it can only ever show what you typed. Starting Solid Sequence 1 — with the
 * board button or `seq 1` — hands the strip to seq_leds for the duration of the
 * demo, and manual paint commands are refused until it is stopped.
 */
#ifndef APP_APP_H
#define APP_APP_H

#include <stdint.h>
#include "main.h"   /* TIM_HandleTypeDef, UART_HandleTypeDef */

/* `led_tim`/`led_ch` drive the WS2812B strip: TIM1 CH1 -> PE9 -> DMA2_Stream1.
 * `cmd_uart` is the LED command centre's serial port (USART3 = the ST-Link
 * Virtual COM Port, 115200 8N1); pass NULL to build without it. */
void app_init(TIM_HandleTypeDef *led_tim, uint32_t led_ch,
              UART_HandleTypeDef *ble_uart,
              UART_HandleTypeDef *cmd_uart);

void app_tick(void);

/* Forward these from the matching HAL weak callbacks. */
void app_on_button_exti(uint16_t gpio_pin);
void app_on_pwm_complete(TIM_HandleTypeDef *htim);
void app_on_tim_error(TIM_HandleTypeDef *htim);

#endif /* APP_APP_H */
