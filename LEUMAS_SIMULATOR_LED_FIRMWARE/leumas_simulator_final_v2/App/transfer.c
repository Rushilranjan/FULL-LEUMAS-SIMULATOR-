#include "transfer.h"
#include "config.h"

typedef struct {
    int8_t  holder;             /* lot id holding the mover, -1 = free      */
    int8_t  q[MAX_LOTS];        /* FIFO of waiting lot ids                  */
    uint8_t head, count;
} MoverState;

static MoverState s_mv[MOVER_COUNT];

void transfer_init(void)
{
    for (uint8_t m = 0; m < MOVER_COUNT; m++) {
        s_mv[m].holder = -1;
        s_mv[m].head   = 0;
        s_mv[m].count  = 0;
    }
}

static bool in_queue(MoverState *mv, int8_t lot)
{
    for (uint8_t i = 0; i < mv->count; i++) {
        if (mv->q[(mv->head + i) % MAX_LOTS] == lot) {
            return true;
        }
    }
    return false;
}

static void push(MoverState *mv, int8_t lot)
{
    if (mv->count < MAX_LOTS) {
        mv->q[(mv->head + mv->count) % MAX_LOTS] = lot;
        mv->count++;
    }
}

static int8_t pop(MoverState *mv)
{
    if (mv->count == 0) {
        return -1;
    }
    int8_t v = mv->q[mv->head];
    mv->head = (uint8_t)((mv->head + 1) % MAX_LOTS);
    mv->count--;
    return v;
}

static void grant(MoverState *mv)
{
    if (mv->holder < 0 && mv->count > 0) {
        mv->holder = pop(mv);
    }
}

bool transfer_request(Mover m, int8_t lot_id)
{
    MoverState *mv = &s_mv[m];
    if (mv->holder == lot_id) {
        return true;
    }
    if (!in_queue(mv, lot_id)) {
        push(mv, lot_id);
    }
    grant(mv);
    return mv->holder == lot_id;
}

bool transfer_held(Mover m, int8_t lot_id)
{
    return s_mv[m].holder == lot_id;
}

void transfer_release(Mover m, int8_t lot_id)
{
    MoverState *mv = &s_mv[m];
    if (mv->holder == lot_id) {
        mv->holder = -1;
        grant(mv);
    }
}
