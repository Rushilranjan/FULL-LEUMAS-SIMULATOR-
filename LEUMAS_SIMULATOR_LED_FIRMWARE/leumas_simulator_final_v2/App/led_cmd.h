/**
 * led_cmd.h — the LED command centre, spoken over the ST-Link virtual COM port.
 *
 * Open the ST-Link COM port in PuTTY (or any terminal) at 115200 8N1 — the same
 * USB cable that flashes the board — and type `help`.
 *
 * Commands are plain English: the colour IS the command.
 *
 *      red                 whole strip red
 *      red 7               LED 7 red
 *      red 20 30           LEDs 20 to 30 red
 *      bright 40           whole strip to 40%
 *      bright 7 10         LED 7 to 10%
 *      swipe red 0 89 15   a red point travels 0 -> 89, 15 ms per LED
 *
 * This is the ONLY thing in the firmware that writes a pixel. Nothing runs in
 * the background, so the strip shows exactly what you last asked for and holds
 * it until you ask for something else.
 */
#ifndef APP_LED_CMD_H
#define APP_LED_CMD_H

#include "main.h"       /* UART_HandleTypeDef */

/* `uart` is the console UART (USART3 = ST-Link VCP, 115200 8N1).
 * Pass NULL to build without a console. */
void led_cmd_init(UART_HandleTypeDef *uart);

/* Drain the UART and run any complete line. Call every main-loop pass; it costs
 * almost nothing when no key has been pressed. Never blocks waiting for input. */
void led_cmd_poll(void);

#endif /* APP_LED_CMD_H */
