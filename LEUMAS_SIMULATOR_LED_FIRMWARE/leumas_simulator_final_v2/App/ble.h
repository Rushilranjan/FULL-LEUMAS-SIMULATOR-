/**
 * ble.h — the link to the Android "robot" over a UART BLE module.
 *
 * OUTBOUND (the bulk of it): simple ASCII cues, newline-terminated, trivial for
 * the app to parse:
 *
 *   READY            controller powered / idle
 *   SEQ:<n>          sequence <n> started
 *   RUN:<slot>       module <slot> started running
 *   DONE:<slot>      module <slot> finished
 *   MOVE:<a>><b>     transfer material from slot <a> to slot <b>
 *   LOT:DONE         one lot completed
 *   RECIPE:<n>       sequence <n> selected -- NOT started (change 16.7)
 *
 * Slot numbers on the wire are 1-based (match the requirements).
 *
 * INBOUND (change 16.1): exactly one command, START.
 *
 * The link used to be transmit-only, and the sequence could only be started by
 * the board button. The requirement is now that NOTHING on the rig lights up
 * until the operator presses START in the app, once the recipe has been shown —
 * so the board has to listen. USART6 was already configured UART_MODE_TX_RX, so
 * this costs no hardware change; only the polling drain below.
 *
 * Deliberately kept to one word. This is not a command channel and must not
 * grow into one: the demo's whole design is that the board is the source of
 * truth and the app reacts to it. START is the single exception, because "when
 * does the show begin" is genuinely the operator's call, not the board's.
 */
#ifndef APP_BLE_H
#define APP_BLE_H

#include <stdint.h>
#include <stdbool.h>
#include "main.h"   /* UART_HandleTypeDef */

void ble_init(UART_HandleTypeDef *huart);
void ble_ready(void);
void ble_seq(uint8_t seq);
void ble_run(uint8_t slot1based);
void ble_done(uint8_t slot1based);
void ble_move(uint8_t from1based, uint8_t to1based);
void ble_lot_done(void);

/* Change 16.7: the board button selects a sequence and announces it. Nothing
 * lights up and no cycle begins -- this exists purely so the app can show the
 * recipe and offer START. The run itself is announced later, by SEQ, when the
 * operator actually presses it. Keeping the two separate is the whole point:
 * SEQ means "running", RECIPE means "chosen, waiting for you". */
void ble_recipe(uint8_t seq);

/* Called from USART6_IRQHandler. Moves received bytes into the ring buffer and
 * does nothing else -- all parsing happens later, in ble_poll().
 *
 * Change 17.8: this exists because polling RXNE from the main loop DROPS BYTES,
 * observed on the rig as `BLE rx: 6 bytes, last line: "STAT"` for a START that
 * is seven bytes and spells START. At 9600 baud a byte lands every ~1.04 ms,
 * and a single main-loop pass drives the WS2812B DMA, the sequence tick and a
 * blocking HAL_UART_Transmit for every BLE cue -- any one of which can outlast
 * a byte time. The receiver has to be an interrupt; there is no polling rate
 * that makes this safe. */
void ble_on_irq(void);

/* Drain whatever the app has sent. Call every main-loop pass; never blocks. */
void ble_poll(void);

/* True exactly once per START received, the same one-shot contract as
 * button_poll(). Anything else on the line is ignored. */
bool ble_take_start(void);

/* ---- bench diagnostics ---------------------------------------------------
 * The `ble` console command reports these. They answer the one question this
 * link will keep raising now that it is no longer transmit-only: did anything
 * the app sent actually reach the board?
 *
 * A zero byte count with the app showing "STARTING" means nothing arrived at
 * USART6 at all, which is a wiring or module-configuration fault, not a
 * firmware one -- the BT05's TX has to be on PC7 for any of this to work, and
 * the original link only ever needed PC6. A non-zero count with no line, or a
 * line that is not START, is a framing or content problem and is ours. */
uint32_t    ble_rx_bytes(void);
uint32_t    ble_rx_dropped(void); /* ring overflows; must stay 0 */
const char *ble_rx_last(void);   /* last complete line, "" if none yet */

#endif /* APP_BLE_H */
