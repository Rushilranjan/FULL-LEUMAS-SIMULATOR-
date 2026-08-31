#include "lotmgr.h"
#include "module.h"
#include "seq_leds.h"
#include "ble.h"
#include "transfer.h"
#include "timebase.h"
#include "config.h"

/* ---- Recipe (solid line) ------------------------------------------------ */
typedef enum { XFER_ROBOT, XFER_SWIPE, XFER_EXIT } XferMode;

typedef struct {
    ModType  types[MAX_PARALLEL];   /* module types that run in parallel      */
    uint8_t  type_count;
    XferMode transfer_out;          /* how material leaves this stage         */
} Stage;

typedef struct {
    Stage   stages[MAX_STAGES];
    uint8_t stage_count;
} Recipe;

static const Recipe SOLID_RECIPE = {
    .stages = {
        { .types = { T_MACRO, T_GRAVIMETRIC }, .type_count = 2, .transfer_out = XFER_ROBOT },
        { .types = { T_BLENDER },              .type_count = 1, .transfer_out = XFER_SWIPE },
        { .types = { T_PACKAGING },            .type_count = 1, .transfer_out = XFER_EXIT  },
    },
    .stage_count = 3,
};

/* Liquid line (1.5): one stage shorter — the feed is just a macro (no
 * gravimetric). LMacro -> Vessel -> Bottling -> exit. Structurally identical to
 * the solid recipe, so the same engine drives both; only the module types and
 * the single-vs-two middle module (Seq 4 vs 5) differ. */
static const Recipe LIQUID_RECIPE = {
    .stages = {
        { .types = { T_MACRO },    .type_count = 1, .transfer_out = XFER_ROBOT },
        { .types = { T_VESSEL },   .type_count = 1, .transfer_out = XFER_SWIPE },
        { .types = { T_BOTTLING }, .type_count = 1, .transfer_out = XFER_EXIT  },
    },
    .stage_count = 3,
};

/* ---- Lot state ---------------------------------------------------------- */
typedef enum { LP_FREE = 0, LP_RUN, LP_WAIT, LP_XFER, LP_EXIT } LotPhase;

typedef struct {
    bool      active;
    int8_t    id;
    uint8_t   stage;
    LotPhase  phase;
    SoftTimer timer;
    int8_t    src[MAX_PARALLEL]; uint8_t src_n;  /* running current stage      */
    int8_t    dst[MAX_PARALLEL]; uint8_t dst_n;  /* receiving during transfer  */
    uint8_t   xfer_i;    /* current robot trip = source index being carried    */
    bool      phase_go;  /* has the current wait/exit phase been kicked off?    */
    Mover     mover;     /* mover held for the current transfer                 */
    XferMode  mode;      /* current transfer mode                              */
    uint32_t  trip_ms;   /* per-trip duration                                  */
} Lot;

static const Recipe *s_recipe;
static Lot       s_lots[MAX_LOTS];
static uint8_t   s_pending;      /* lots still to inject (Seq 2 uses >1)         */
static int8_t    s_next_id;
static uint8_t   s_seq;
static SoftTimer s_warm;         /* power-on macro pre-run window                */
static bool      s_warming;      /* macros are still doing their power-on run    */

/* ---- helpers ------------------------------------------------------------ */
static inline uint8_t slot1(int8_t idx) { return (uint8_t)(idx + 1); } /* 1-based */

/* Occupy all modules a stage needs; on partial failure, roll back to each
 * module's PRIOR state (not just READY) so a speculative claim that fails does
 * not disturb a warm macro that is still waiting for its batch. */
static bool alloc_stage(const Stage *st, int8_t out[MAX_PARALLEL], uint8_t *out_n)
{
    uint8_t  n = 0;
    ModState prev[MAX_PARALLEL];
    for (uint8_t i = 0; i < st->type_count; i++) {
        int8_t slot = module_alloc(st->types[i]);
        if (slot < 0) {
            for (uint8_t r = 0; r < n; r++) {
                module_set_state((uint8_t)out[r], prev[r]);
            }
            return false;
        }
        prev[n] = module_get_state((uint8_t)slot);
        module_set_state((uint8_t)slot, MOD_RUNNING);   /* claim it */
        out[n++] = slot;
    }
    *out_n = n;
    return true;
}

/* Which recipe stage uses a module of type `t` (first match), or -1. */
static int stage_using(ModType t)
{
    for (uint8_t s = 0; s < s_recipe->stage_count; s++) {
        for (uint8_t i = 0; i < s_recipe->stages[s].type_count; i++) {
            if (s_recipe->stages[s].types[i] == t) return (int)s;
        }
    }
    return -1;
}

/* Will any remaining work still need a module of type `t` (other than `self`)?
 * Pending lots run the whole recipe so they always will; otherwise an active lot
 * still at/before the stage that uses `t` will need one. Decides when a SHARED
 * unit (the single gravimetric, or Seq-1's one blender) is finally done and its
 * status run may light — and whether a mid->final hand-off is the last one, so
 * its status bar should stay. */
static bool type_needed_again(ModType t, const Lot *self)
{
    if (s_pending > 0) return true;
    int su = stage_using(t);
    if (su < 0) return false;
    for (uint8_t i = 0; i < MAX_LOTS; i++) {
        const Lot *L = &s_lots[i];
        if (L == self || !L->active) continue;
        if ((int)L->stage <= su) return true;
    }
    return false;
}

/* Is `self` the last batch still in play? (Nothing pending, no other lot active.)
 * The final batch is the one that ships the whole lot out at the exit. */
static bool is_last_lot(const Lot *self)
{
    if (s_pending > 0) return false;
    for (uint8_t i = 0; i < MAX_LOTS; i++) {
        const Lot *L = &s_lots[i];
        if (L == self || !L->active) continue;
        return false;
    }
    return true;
}

/* Is this lot's middle-stage ship (blender->packaging, or vessel->bottling) the
 * FINAL use of that shared unit? A consumed middle module (Seq 2/5, two of them)
 * is used once; a single one (Seq 1/4) is final only when no other lot still
 * needs one. Drives whether the hand-off keeps its status bar (final) or clears
 * it for the next batch. The type is taken at runtime from the module being
 * shipped, so this works for both the blender and the vessel. */
static bool mid_ship_final(const Lot *self)
{
    ModType mt = module_type((uint8_t)self->src[0]);   /* blender or vessel */
    return (module_active_count_of_type(mt) > 1) ||
           !type_needed_again(mt, self);
}

/* Nearest still-active middle module of the SAME type downstream of `self`'s on
 * the strip (higher slot), or -1. In Seq 2/5, when unit 1 ships, unit 2 is
 * downstream: unit 1's hand-off keeps the run solid only up to unit 2, and
 * unit 2's own hand-off fills unit2->final. */
static int next_downstream_mid(const Lot *self)
{
    ModType mt        = module_type((uint8_t)self->src[0]);
    uint8_t self_slot = (uint8_t)self->src[0];
    int     best = -1;
    for (uint8_t i = 0; i < MAX_LOTS; i++) {
        const Lot *L = &s_lots[i];
        if (L == self || !L->active || L->stage != 1) continue;   /* holds a mid unit */
        uint8_t bs = (uint8_t)L->src[0];
        if (module_type(bs) == mt && bs > self_slot && (best < 0 || bs < best)) {
            best = bs;
        }
    }
    return best;
}

/* Begin carrying source `xfer_i` to the (single) destination. */
static void start_trip(Lot *lot)
{
    module_set_transfer((uint8_t)lot->src[lot->xfer_i], true);   /* source      */
    module_set_transfer((uint8_t)lot->dst[0],           false);  /* destination */
    ble_move(slot1(lot->src[lot->xfer_i]), slot1(lot->dst[0]));
    if (lot->mode == XFER_SWIPE) {
        /* Decide how much of this mid->final hand-off stays solid when it ends:
         * nothing if it is not the final ship (Seq-1 batch A clears for batch
         * B); up to the next downstream blender/vessel if one is still in play
         * (Seq-2 Blender1 keeps up to Blender2); otherwise all the way to the
         * final module (the last ship). */
        uint8_t keep_slot;
        if (!mid_ship_final(lot)) {
            keep_slot = (uint8_t)lot->src[0];          /* keep nothing */
        } else {
            int nb = next_downstream_mid(lot);
            keep_slot = (nb >= 0) ? (uint8_t)nb : (uint8_t)lot->dst[0];
        }
        seq_leds_swipe((uint8_t)lot->src[0], (uint8_t)lot->dst[0],
                       lot->trip_ms, keep_slot);
    }
    timer_start(&lot->timer, lot->trip_ms);
}

/* ---- lifecycle ---------------------------------------------------------- */
static bool try_inject(void)
{
    if (s_pending == 0) {
        return false;
    }
    Lot *lot = 0;
    for (uint8_t i = 0; i < MAX_LOTS; i++) {
        if (!s_lots[i].active) { lot = &s_lots[i]; break; }
    }
    if (!lot) {
        return false;
    }
    int8_t src[MAX_PARALLEL]; uint8_t n;
    if (!alloc_stage(&s_recipe->stages[0], src, &n)) {
        return false;   /* wait for capacity — the pipeline gate */
    }

    lot->active = true;
    lot->id     = s_next_id++;
    lot->stage  = 0;
    lot->phase  = LP_RUN;
    lot->src_n  = n;
    lot->dst_n  = 0;
    for (uint8_t i = 0; i < n; i++) {
        lot->src[i] = src[i];
        if (module_is_warm((uint8_t)src[i])) {
            /* A pre-run macro joins this batch. Once claimed it is no longer
             * loose. If its power-on run already finished it stays HOLDING
             * (HOLDING, done) and skips re-running; otherwise it keeps
             * running under this lot. */
            module_set_warm((uint8_t)src[i], false);
            if (!s_warming) {
                module_set_state((uint8_t)src[i], MOD_HOLDING);
            } else {
                ble_run(slot1(src[i]));
            }
        } else {
            ble_run(slot1(src[i]));
        }
    }
    timer_start(&lot->timer, RUN_TIME_MS);
    s_pending--;
    return true;
}

static void lot_tick(Lot *lot)
{
    const Stage *st = &s_recipe->stages[lot->stage];

    switch (lot->phase) {
    case LP_RUN:
        if (timer_expired(&lot->timer)) {
            for (uint8_t i = 0; i < lot->src_n; i++) {
                ble_done(slot1(lot->src[i]));
            }
            if (lot->stage + 1 < s_recipe->stage_count) {
                /* Finished modules go HOLDING (waiting to hand off) and stay
                 * unavailable so no other lot grabs them. If the next stage's
                 * module is a busy bottleneck (Seq 1's single blender), they
                 * wait here until it frees — that wait IS the bottleneck the
                 * demo is built around. */
                for (uint8_t i = 0; i < lot->src_n; i++) {
                    module_set_state((uint8_t)lot->src[i], MOD_HOLDING);
                }
                lot->phase   = LP_WAIT;
                lot->dst_n   = 0;      /* not yet allocated */
                lot->xfer_i  = 0;
                lot->phase_go = false;
            } else {
                lot->phase    = LP_EXIT;
                lot->phase_go = false;
            }
        }
        break;

    case LP_WAIT:
        /* Acquire a destination module and the shared mover, then start trips. */
        if (lot->dst_n == 0) {
            if (!alloc_stage(&s_recipe->stages[lot->stage + 1], lot->dst, &lot->dst_n)) {
                break;   /* downstream module busy — retry next tick */
            }
            lot->mode    = st->transfer_out;
            lot->mover   = (lot->mode == XFER_ROBOT) ? MOVER_ROBOT : MOVER_STRIP;
            lot->trip_ms = (lot->mode == XFER_ROBOT) ? TRANSFER_ROBOT_MS : TRANSFER_SWIPE_MS;
            /* Mark the destination as receiving while we queue. */
            for (uint8_t i = 0; i < lot->dst_n; i++) {
                module_set_transfer((uint8_t)lot->dst[i], false);
            }
        }
        if (!transfer_request(lot->mover, lot->id)) {
            break;   /* mover busy with another lot — wait our FIFO turn */
        }
        lot->xfer_i = 0;
        start_trip(lot);
        lot->phase = LP_XFER;
        break;

    case LP_XFER:
        if (timer_expired(&lot->timer)) {
            /* This source just emptied. If several like it are active (e.g. the
             * two macros), it is CONSUMED for the run so the next batch uses the
             * next unit (Macro2, not Macro1 again) — it goes idle. If it is
             * the only unit of its type (the single gravimetric, or Seq-1's one
             * blender), it returns to READY so the next batch can reuse it. */
            uint8_t esrc = (uint8_t)lot->src[lot->xfer_i];
            ModType etype = module_type(esrc);
            bool    consumed = module_active_count_of_type(etype) > 1;
            module_set_state(esrc, consumed ? MOD_INACTIVE : MOD_READY);
            /* Light the run up to this module the instant it is COMPLETELY
             * done — its material has left and it will not be used again. A
             * consumed unit is done at once; a shared unit only on its final
             * use. This is what fills up to Macro1 after batch A. */
            if (consumed || !type_needed_again(etype, lot)) {
                seq_leds_fill_upto(esrc);
            }
            lot->xfer_i++;
            if (lot->xfer_i < lot->src_n) {
                start_trip(lot);                 /* next source's robot trip */
            } else {
                transfer_release(lot->mover, lot->id);
                for (uint8_t i = 0; i < lot->dst_n; i++) {
                    module_set_state((uint8_t)lot->dst[i], MOD_RUNNING);
                    ble_run(slot1(lot->dst[i]));
                }
                /* When the FINAL feed reaches the middle module, light the
                 * line up to that module itself (Seq-1: up to Blender1 after
                 * batch B; Seq-4: up to Vessel1), closing the feed->middle gap.
                 * The middle type is read from the destination, so this works
                 * for both the blender and the vessel. */
                if (lot->stage == 0) {
                    ModType dt = module_type((uint8_t)lot->dst[0]);
                    if (!type_needed_again(dt, lot)) {
                        seq_leds_fill_upto((uint8_t)lot->dst[0]);
                    }
                }
                /* Advance: destinations become the new sources. */
                lot->stage++;
                lot->src_n = lot->dst_n;
                for (uint8_t i = 0; i < lot->dst_n; i++) {
                    lot->src[i] = lot->dst[i];
                }
                lot->dst_n = 0;
                lot->phase = LP_RUN;
                timer_start(&lot->timer, RUN_TIME_MS);
            }
        }
        break;

    case LP_EXIT:
        /* Both batches make ONE lot, so only the LAST batch ships out. Earlier
         * batches hand their material to Packaging and finish quietly — no exit
         * swipe, no LOT:DONE. */
        if (!is_last_lot(lot)) {
            for (uint8_t i = 0; i < lot->src_n; i++) {
                module_set_state((uint8_t)lot->src[i], MOD_READY);   /* packaging -> idle */
            }
            lot->active = false;
            lot->phase  = LP_FREE;
            break;
        }
        if (!lot->phase_go) {
            if (!transfer_request(MOVER_STRIP, lot->id)) {
                break;   /* wait for the shared mover */
            }
            for (uint8_t i = 0; i < lot->src_n; i++) {
                module_set_state((uint8_t)lot->src[i], MOD_READY);   /* packaging done -> idle */
            }
            seq_leds_exit_flow((uint8_t)lot->src[0], EXIT_SWIPE_MS);
            ble_lot_done();
            timer_start(&lot->timer, EXIT_SWIPE_MS);
            lot->phase_go = true;
        } else if (timer_expired(&lot->timer)) {
            transfer_release(MOVER_STRIP, lot->id);
            lot->active = false;
            lot->phase  = LP_FREE;
        }
        break;

    case LP_FREE:
    default:
        break;
    }
}

/* ---- public API --------------------------------------------------------- */
void lotmgr_start(uint8_t seq)
{
    s_seq     = seq;
    s_next_id = 0;

    /* Announce the sequence FIRST, before any RUN cue, so the log (and the
     * Android app) always sees SEQ:n ahead of the warm-macro RUN cues. */
    ble_seq(seq);

    for (uint8_t i = 0; i < MAX_LOTS; i++) {
        s_lots[i].active = false;
        s_lots[i].phase  = LP_FREE;
    }
    transfer_init();
    seq_leds_start(seq);        /* claim the strip and draw the opening frame */

    /* Every sequence pushes SOLID_LOTS_PER_RUN (2) batches. The lesson differs
     * only in which line and how many middle modules are active:
     *   Seq 1 -> solid,  one blender  => batch B waits on it.
     *   Seq 2 -> solid,  two blenders => batches run in parallel (no wait).
     *   Seq 4 -> liquid, one vessel   => batch B waits on it.
     *   Seq 5 -> liquid, two vessels  => batches run in parallel (no wait).
     * Seq 3 (continuous looping) is deferred and skipped by the button cycle;
     * it falls through to the solid default here. */
    switch (seq) {
        case 1:
            s_recipe = &SOLID_RECIPE;
            module_activate_mask(SEQ1_ACTIVE_MASK);
            break;
        case 2:
            s_recipe = &SOLID_RECIPE;
            module_activate_mask(SEQ2_ACTIVE_MASK);
            break;
        case 4:
            s_recipe = &LIQUID_RECIPE;
            module_activate_mask(SEQ4_ACTIVE_MASK);
            break;
        case 5:
            s_recipe = &LIQUID_RECIPE;
            module_activate_mask(SEQ5_ACTIVE_MASK);
            break;
        default:
            s_recipe = &SOLID_RECIPE;
            module_activate_mask(SEQ1_ACTIVE_MASK);
            break;
    }
    s_pending = SOLID_LOTS_PER_RUN;

    /* Power-on: every active macro runs at once (QA/QC + fill), not just the
     * one the first batch grabs. A macro that isn't picked up by the time its
     * run ends goes HOLDING, waiting for its batch. Macros stay allocatable
     * while warm. */
    s_warming = false;
    for (uint8_t i = 0; i < MODULE_COUNT; i++) {
        if (module_type(i) == T_MACRO && module_get_state(i) == MOD_READY) {
            module_set_state(i, MOD_RUNNING);
            module_set_warm(i, true);
            ble_run(slot1((int8_t)i));
            s_warming = true;
        }
    }
    if (s_warming) {
        timer_start(&s_warm, RUN_TIME_MS);
    }
}

void lotmgr_tick(void)
{
    /* End of the power-on macro run: any macro still loose (not yet grabbed by a
     * batch) flips from RUNNING to HOLDING — done, waiting for its turn. */
    if (s_warming && timer_expired(&s_warm)) {
        for (uint8_t i = 0; i < MODULE_COUNT; i++) {
            if (module_is_warm(i) && module_get_state(i) == MOD_RUNNING) {
                module_set_state(i, MOD_HOLDING);
                ble_done(slot1((int8_t)i));
            }
        }
        s_warming = false;
    }

    try_inject();
    for (uint8_t i = 0; i < MAX_LOTS; i++) {
        if (s_lots[i].active) {
            lot_tick(&s_lots[i]);
        }
    }
}

bool lotmgr_running(void)
{
    if (s_pending > 0) {
        return true;
    }
    for (uint8_t i = 0; i < MAX_LOTS; i++) {
        if (s_lots[i].active) {
            return true;
        }
    }
    return false;
}
