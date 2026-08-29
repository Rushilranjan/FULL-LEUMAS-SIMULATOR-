#include "ble.h"
#include <stdio.h>
#include <string.h>

static UART_HandleTypeDef *s_uart;

void ble_init(UART_HandleTypeDef *huart)
{
    s_uart = huart;
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
