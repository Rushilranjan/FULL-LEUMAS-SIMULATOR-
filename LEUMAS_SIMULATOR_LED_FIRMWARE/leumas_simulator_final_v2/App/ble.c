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

/* Bench diagnostics; see ble.h. */
static uint32_t s_rx_bytes;
static char     s_rx_last[BLE_LINE_MAX + 1];

/* Change 17.8: the ISR writes here and ble_poll() reads, so the main loop can
 * take as long as it likes without losing a byte. A power-of-two size makes the
 * wrap a mask. 64 bytes is ~66 ms of runway at 9600 baud, far longer than any
 * main-loop pass, and the only traffic inbound is single short commands.
 *
 * head is written only by the ISR and tail only by the main loop, both are
 * single words, and each side reads the other's index once -- so this needs no
 * critical section on Cortex-M. volatile keeps the compiler from caching them
 * across the loop in ble_poll(). */
#define BLE_RX_RING   64u
#define BLE_RX_MASK   (BLE_RX_RING - 1u)

static volatile uint8_t  s_ring[BLE_RX_RING];
static volatile uint16_t s_ring_head;
static volatile uint16_t s_ring_tail;
static volatile uint32_t s_rx_dropped;   /* ring full: should stay 0 forever */

void ble_init(UART_HandleTypeDef *huart)
{
    s_uart          = huart;
    s_len           = 0u;
    s_overflow      = false;
    s_start_pending = false;
    s_rx_bytes      = 0u;
    s_rx_last[0]    = '\0';
    s_ring_head     = 0u;
    s_ring_tail     = 0u;
    s_rx_dropped    = 0u;

    /* Change 17.8: take receive off the main loop entirely. The NVIC line is
     * not enabled by CubeMX -- the link was transmit-only when the .ioc was
     * generated -- so it is enabled here, next to the only code that needs it.
     * Priority is deliberately below the LED DMA: a late byte is recoverable,
     * a disturbed WS2812B frame is a visible glitch on the strip. */
    if (s_uart != NULL) {
        __HAL_UART_ENABLE_IT(s_uart, UART_IT_RXNE);
        HAL_NVIC_SetPriority(USART6_IRQn, 6, 0);
        HAL_NVIC_EnableIRQ(USART6_IRQn);
    }
}

void ble_on_irq(void)
{
    UART_HandleTypeDef *u = s_uart;
    if (u == NULL) {
        return;
    }
    /* An overrun latches RXNE off; clearing it here keeps the receiver alive
     * even if one byte was already lost. */
    if (__HAL_UART_GET_FLAG(u, UART_FLAG_ORE)) {
        __HAL_UART_CLEAR_OREFLAG(u);
    }
    while (__HAL_UART_GET_FLAG(u, UART_FLAG_RXNE)) {
        uint8_t  c    = (uint8_t)(u->Instance->RDR & 0xFFu);
        uint16_t head = s_ring_head;
        uint16_t next = (uint16_t)((head + 1u) & BLE_RX_MASK);
        if (next == s_ring_tail) {
            s_rx_dropped++;      /* full: drop the newest, keep the oldest line */
        } else {
            s_ring[head]  = c;
            s_ring_head   = next;
        }
    }
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

void ble_recipe(uint8_t seq)
{
    char b[16];
    snprintf(b, sizeof(b), "RECIPE:%u\r\n", (unsigned)seq);
    ble_send(b);
}

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
    /* Keep the line for the `ble` console command before it is cleared, so a
     * mis-framed or unexpected command can be read off the board rather than
     * guessed at. Truncated lines are recorded as far as they got. */
    uint8_t keep = (s_len < BLE_LINE_MAX) ? s_len : (uint8_t)BLE_LINE_MAX;
    memcpy(s_rx_last, s_line, keep);
    s_rx_last[keep] = '\0';

    if (!s_overflow && s_len == 5u && memcmp(s_line, "START", 5u) == 0) {
        s_start_pending = true;
    }
    s_len      = 0u;
    s_overflow = false;
}

static void ble_handle_char(uint8_t c)
{
    s_rx_bytes++;
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
    /* Change 17.8: the UART is not touched here any more -- the ISR has already
     * taken the bytes off it. This just parses whatever the ring holds, which
     * can take as long as it needs to without costing a byte. */
    while (s_ring_tail != s_ring_head) {
        uint8_t c   = s_ring[s_ring_tail];
        s_ring_tail = (uint16_t)((s_ring_tail + 1u) & BLE_RX_MASK);
        ble_handle_char(c);
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

uint32_t    ble_rx_bytes(void) { return s_rx_bytes; }
uint32_t    ble_rx_dropped(void) { return s_rx_dropped; }
const char *ble_rx_last(void)  { return s_rx_last; }
