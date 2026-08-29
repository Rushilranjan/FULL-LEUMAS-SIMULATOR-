/**
 * transfer.h — shared transfer arbiter.
 *
 * The rig has ONE robot arm / screen and ONE LED strip, so transfers are shared
 * serial resources. Each is a single-slot "mover" with a FIFO wait queue. A lot
 * that needs to hand material off requests the mover, waits until it holds it,
 * runs its transfer, then releases it — so overlapping lots (Seq 2) serialize
 * correctly instead of firing two moves at one robot. The two movers are
 * independent: a robot trip and a strip swipe may run at the same time.
 *
 * Seq 1 has a single lot, so there is never contention — behavior is unchanged.
 */
#ifndef APP_TRANSFER_H
#define APP_TRANSFER_H

#include <stdint.h>
#include <stdbool.h>

typedef enum { MOVER_ROBOT = 0, MOVER_STRIP = 1, MOVER_COUNT } Mover;

void transfer_init(void);

/* Ask for the mover on behalf of lot_id (idempotent). Returns true if this lot
 * now holds it (either already held or granted this call). */
bool transfer_request(Mover m, int8_t lot_id);

/* True if lot_id currently holds the mover. */
bool transfer_held(Mover m, int8_t lot_id);

/* Release the mover if held by lot_id; the next FIFO waiter is granted it. */
void transfer_release(Mover m, int8_t lot_id);

#endif /* APP_TRANSFER_H */
