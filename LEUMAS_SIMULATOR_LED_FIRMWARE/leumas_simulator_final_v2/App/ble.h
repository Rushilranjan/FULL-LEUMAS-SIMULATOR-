/**
 * ble.h — emits simple ASCII cues to the Android "robot" over a UART BLE
 * module. Human-readable, newline-terminated, trivial for the app to parse:
 *
 *   READY            controller powered / idle
 *   SEQ:<n>          sequence <n> started
 *   RUN:<slot>       module <slot> started running
 *   DONE:<slot>      module <slot> finished
 *   MOVE:<a>><b>     transfer material from slot <a> to slot <b>
 *   LOT:DONE         one lot completed
 *
 * Slot numbers on the wire are 1-based (match the requirements).
 */
#ifndef APP_BLE_H
#define APP_BLE_H

#include <stdint.h>
#include "main.h"   /* UART_HandleTypeDef */

void ble_init(UART_HandleTypeDef *huart);
void ble_ready(void);
void ble_seq(uint8_t seq);
void ble_run(uint8_t slot1based);
void ble_done(uint8_t slot1based);
void ble_move(uint8_t from1based, uint8_t to1based);
void ble_lot_done(void);

#endif /* APP_BLE_H */
