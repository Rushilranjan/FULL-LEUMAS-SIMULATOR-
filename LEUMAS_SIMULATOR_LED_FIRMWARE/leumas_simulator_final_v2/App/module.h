/**
 * module.h — per-module actor state for the sequence engine.
 *
 * Each physical slot is an independent actor holding exactly ONE state. The
 * states used to be painted onto the LED strip; they no longer are — the strip
 * belongs to the LED command centre alone. What remains here is the bookkeeping
 * the lot manager needs, and the BLE cues it sends to the Android robot.
 */
#ifndef APP_MODULE_H
#define APP_MODULE_H

#include <stdint.h>
#include <stdbool.h>
#include "config.h"

typedef enum {
    MOD_INACTIVE = 0,   /* not part of the active recipe                   */
    MOD_READY,          /* idle & AVAILABLE to the engine                  */
    MOD_RUNNING,        /* processing a lot                                */
    MOD_TRANSFER_OUT,   /* handing material downstream                     */
    MOD_TRANSFER_IN,    /* receiving material                              */
    MOD_HOLDING         /* finished, holding, WAITING to hand off          */
} ModState;

typedef struct {
    ModType  type;
    ModState state;
    int8_t   lot_id;        /* lot currently held, -1 = none               */
    uint8_t  stage;         /* stage index within that lot's recipe        */
    bool     warm;          /* power-on pre-run feeder, not yet owned by a lot */
} Module;

void     module_init(void);                       /* types from config, all red */
void     module_activate_mask(uint32_t mask);     /* masked -> ready, rest red   */
void     module_set_state(uint8_t slot, ModState s);

/* Put a module into a transfer state.
 * `out` true = TRANSFER_OUT (source), false = TRANSFER_IN (destination). */
void     module_set_transfer(uint8_t slot, bool out);
ModState module_get_state(uint8_t slot);
ModType  module_type(uint8_t slot);

/* Find a free module of the given type; returns slot index or -1. Prefers a
 * READY module; failing that, claims a WARM one (a macro that pre-ran at
 * power-on and is waiting to be picked up by its batch). */
int8_t   module_alloc(ModType type);

/* How many modules of this type the active sequence has. 1 => single shared
 * unit (reuse it across batches); >1 => each batch consumes its own. */
uint8_t  module_active_count_of_type(ModType type);

/* Power-on pre-run bookkeeping: a WARM module has already run and is waiting
 * for its batch; it stays allocatable via module_alloc. */
void     module_set_warm(uint8_t slot, bool w);
bool     module_is_warm(uint8_t slot);

#endif /* APP_MODULE_H */
