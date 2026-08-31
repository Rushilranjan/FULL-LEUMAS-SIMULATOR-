#include "module.h"

static Module s_mod[MODULE_COUNT];

void module_init(void)
{
    for (uint8_t i = 0; i < MODULE_COUNT; i++) {
        s_mod[i].type  = MODULE_TYPES[i];
        s_mod[i].state = MOD_OFF;
    }
}

void module_all_off(void)
{
    for (uint8_t i = 0; i < MODULE_COUNT; i++) {
        s_mod[i].state = MOD_OFF;
    }
}

void module_set_state(uint8_t slot, ModState s)
{
    if (slot < MODULE_COUNT) {
        s_mod[slot].state = s;
    }
}

ModState module_get_state(uint8_t slot)
{
    return (slot < MODULE_COUNT) ? s_mod[slot].state : MOD_OFF;
}

ModType module_type(uint8_t slot)
{
    return (slot < MODULE_COUNT) ? s_mod[slot].type : T_NONE;
}
