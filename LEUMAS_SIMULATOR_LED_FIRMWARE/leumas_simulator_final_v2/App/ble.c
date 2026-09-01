#include "ble.h"
#include <stdio.h>
#include <string.h>

#define BLE_LINE_MAX   16u   /* only START has to fit; anything longer is junk */

static UART_HandleTypeDef *s_uart;

/* ---- inbound (change 16.1) ---------------------------------------------- */
static char     s_line[BLE_LINE_MAX];
static uint8_t  s_len;
static bool     s_overflow;      /* this line is already too long to be START  */
static bool     s_start_pending; /* consumed once by ble_take_start()          */

void ble_init(UART_HandleTypeDef *huart)
{
    s_uart          = huart;
    s_len           = 0u;
    s_overflow      = false;
    s_start_pending = false;
}

static void ble_send(const char *msg)
{
    if (s_uart) {
        HAL_UART_Transmit(s_uart, (uint8_t *)msg, (uint16_t)strlen(msg), 50);
    }
}

void ble_ready(void)                      { ble_send("READY\r\n"); }

void ble_seq(uint8_t seq)
{
    char b[16];
    snprintf(b, sizeof(b), "SEQ:%u\r\n", (unsigned)seq);
    ble_send(b);
}

void ble_run(uint8_t slot1based)
{
    char b[16];
    snprintf(b, sizeof(b), "RUN:%u\r\n", (unsigned)slot1based);
    ble_send(b);
}

void ble_done(uint8_t slot1based)
{
    char b[16];
    snprintf(b, sizeof(b), "DONE:%u\r\n", (unsigned)slot1based);
    ble_send(b);
}

void ble_move(uint8_t from1based, uint8_t to1based)
{
    char b[20];
    snprintf(b, sizeof(b), "MOVE:%u>%u\r\n", (unsigned)from1based, (unsigned)to1based);
    ble_send(b);
}

void ble_lot_done(void)                   { ble_send("LOT:DONE\r\n"); }

/* ---- inbound ------------------------------------------------------------
 * One line at a time, terminated by CR or LF. A BT05 splits a write across
 * notifications and the app terminates with CRLF, so an empty line is normal
 * and is ignored rather than treated as a command.
 *
 * A line longer than START is marked overflowed and discarded at its
 * terminator instead of wrapping the buffer — otherwise a long burst of junk
 * could leave "START" sitting in the tail of the buffer and fire the demo. */
static void ble_handle_line(void)
{
    if (!s_overflow && s_len == 5u && memcmp(s_line, "START", 5u) == 0) {
        s_start_pending = true;
    }
    s_len      = 0u;
    s_overflow = false;
}

static void ble_handle_char(uint8_t c)
{
    if (c == '\r' || c == '\n') {
        if (s_len > 0u || s_overflow) {
            ble_handle_line();
        }
        return;
    }
    if (s_len < BLE_LINE_MAX) {
        s_line[s_len++] = (char)c;
    } else {
        s_overflow = true;
    }
}

void ble_poll(void)
{
    UART_HandleTypeDef *u = s_uart;
    if (u == NULL) {
        return;
    }
    /* An overrun latches RXNE off; clear it so a START still lands after the
     * line has been ignored for a while. Same treatment as the console. */
    if (__HAL_UART_GET_FLAG(u, UART_FLAG_ORE)) {
        __HAL_UART_CLEAR_OREFLAG(u);
    }
    /* Drain whatever is waiting. Reading RDR clears RXNE, so this ends. */
    while (__HAL_UART_GET_FLAG(u, UART_FLAG_RXNE)) {
        ble_handle_char((uint8_t)(u->Instance->RDR & 0xFFu));
    }
}

bool ble_take_start(void)
{
    if (s_start_pending) {
        s_start_pending = false;
        return true;
    }
    return false;
}
