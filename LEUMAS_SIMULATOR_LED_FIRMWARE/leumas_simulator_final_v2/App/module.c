#include "module.h"

static Module s_mod[MODULE_COUNT];

/* How many modules of each type the current sequence activated. Filled by
 * module_activate_mask; used to decide whether an emptied module is reused
 * (a single shared unit) or consumed for the rest of the run (one of several). */
#define TYPE_COUNT  MOD_TYPE_COUNT
static uint8_t s_active_type[TYPE_COUNT];

void module_init(void)
{
    for (uint8_t i = 0; i < MODULE_COUNT; i++) {
        s_mod[i].type   = MODULE_TYPES[i];
        s_mod[i].state  = MOD_INACTIVE;
        s_mod[i].lot_id = -1;
        s_mod[i].stage  = 0;
        s_mod[i].warm   = false;
    }
    for (uint8_t t = 0; t < TYPE_COUNT; t++) {
        s_active_type[t] = 0;
    }
}

void module_activate_mask(uint32_t mask)
{
    for (uint8_t t = 0; t < TYPE_COUNT; t++) {
        s_active_type[t] = 0;
    }
    for (uint8_t i = 0; i < MODULE_COUNT; i++) {
        s_mod[i].lot_id = -1;
        s_mod[i].stage  = 0;
        s_mod[i].warm   = false;
        if (mask & (1u << i)) {
            s_mod[i].state = MOD_READY;
            if (s_mod[i].type < TYPE_COUNT) {
                s_active_type[s_mod[i].type]++;
            }
        } else {
            s_mod[i].state = MOD_INACTIVE;
        }
    }
}

uint8_t module_active_count_of_type(ModType t)
{
    return (t < TYPE_COUNT) ? s_active_type[t] : 0;
}

void module_set_state(uint8_t slot, ModState s)
{
    if (slot < MODULE_COUNT) {
        s_mod[slot].state = s;
    }
}

void module_set_transfer(uint8_t slot, bool out)
{
    if (slot < MODULE_COUNT) {
        s_mod[slot].state = out ? MOD_TRANSFER_OUT : MOD_TRANSFER_IN;
    }
}

ModState module_get_state(uint8_t slot)
{
    return (slot < MODULE_COUNT) ? s_mod[slot].state : MOD_INACTIVE;
}

ModType module_type(uint8_t slot)
{
    return (slot < MODULE_COUNT) ? s_mod[slot].type : T_NONE;
}

int8_t module_alloc(ModType type)
{
    for (uint8_t i = 0; i < MODULE_COUNT; i++) {
        if (s_mod[i].type == type && s_mod[i].state == MOD_READY) {
            return (int8_t)i;
        }
    }
    /* Nothing idle-and-ready: a macro that pre-ran at power-on is still up for
     * grabs by its batch even though it is green (running) or pulsing (holding). */
    for (uint8_t i = 0; i < MODULE_COUNT; i++) {
        if (s_mod[i].type == type && s_mod[i].warm) {
            return (int8_t)i;
        }
    }
    return -1;
}

void module_set_warm(uint8_t slot, bool w)
{
    if (slot < MODULE_COUNT) {
        s_mod[slot].warm = w;
    }
}

bool module_is_warm(uint8_t slot)
{
    return (slot < MODULE_COUNT) ? s_mod[slot].warm : false;
}
