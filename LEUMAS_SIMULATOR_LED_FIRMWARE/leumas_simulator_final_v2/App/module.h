/**
 * module.h — per-module state, and nothing else.
 *
 * Each physical slot holds exactly ONE state. This file is a plain store: it
 * decides nothing and draws nothing. solid_seq1.c writes the states, seq_leds.c
 * reads them back and paints, and the two never talk to each other directly.
 *
 * The three states ARE the three colours the demo is allowed to show. Adding a
 * fourth here means adding a fourth colour to the board, which the requirement
 * forbids — so if you find yourself wanting one, the answer is almost certainly
 * a sequence step, not a state.
 */
#ifndef APP_MODULE_H
#define APP_MODULE_H

#include <stdint.h>
#include <stdbool.h>
#include "config.h"

typedef enum {
    MOD_OFF = 0,   /* dark  — inactive, not processing, or the robot has left */
    MOD_RUNNING,   /* BLUE  — processing                                      */
    MOD_READY      /* GREEN — completed, ready for the robot to collect       */
} ModState;

typedef struct {
    ModType  type;
    ModState state;
} Module;

/* Types from config, every module dark. */
void     module_init(void);

/* Every module back to dark. The sequence lights what it needs, step by step,
 * so there is never a frame where the board shows a state nobody asked for. */
void     module_all_off(void);

void     module_set_state(uint8_t slot, ModState s);
ModState module_get_state(uint8_t slot);
ModType  module_type(uint8_t slot);

#endif /* APP_MODULE_H */
