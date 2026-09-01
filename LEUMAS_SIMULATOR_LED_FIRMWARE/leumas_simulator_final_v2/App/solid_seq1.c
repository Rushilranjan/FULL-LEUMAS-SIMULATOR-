#include "solid_seq1.h"
#include "config.h"
#include "module.h"
#include "seq_leds.h"
#include "ble.h"
#include "timebase.h"

/* The number this sequence answers to on the console and on the BLE wire. */
#define SS1_SEQ_NUMBER   1u

/* 0-based slot index -> the 1-based number the operator and the robot use. */
#define SLOT1(idx)       ((uint8_t)((idx) + 1u))

/* ---- strip spans ---------------------------------------------------------
 * Every span comes straight out of SLOT_LEDS, so the requirement's LED indices
 * and the rig's pixel map are the same numbers and can never drift apart. The
 * values in the comments are what today's map yields; re-measure the rig and
 * they follow automatically. */
#define SS1_MACRO_A   (SLOT_LEDS[SS1_SLOT_MACRO].st_base)                 /*  0 */
#define SS1_MACRO_B   (SLOT_LEDS[SS1_SLOT_MACRO].st_end)                  /*  5 */
#define SS1_GRAV_A    (SLOT_LEDS[SS1_SLOT_GRAVIMETRIC].st_base)           /*  0 */
#define SS1_GRAV_B    (SLOT_LEDS[SS1_SLOT_GRAVIMETRIC].st_end)            /*  9 */
#define SS1_XFER_A    ((uint8_t)(SLOT_LEDS[SS1_SLOT_BLENDER].st_end + 1u))/* 14 */
#define SS1_XFER_B    (SLOT_LEDS[SS1_SLOT_PACKAGING].st_end)              /* 16 */
#define SS1_OUT_A     ((uint8_t)SOLID_EXIT_A)                             /* 17 */
#define SS1_OUT_B     ((uint8_t)SOLID_EXIT_B)                             /* 21 */

/* ---- carry timing --------------------------------------------------------
 * A carry is one MOVE cue and then a fixed sequence of moments: the arm
 * arrives, grips, stands there collecting, lifts away, crosses to the blender,
 * pours, and goes home. The board's job is to change each module's colour at
 * the moment the arm is actually in front of it — a module going dark while the
 * arm is still standing at it was the single most obvious fault in the trial
 * run.
 *
 * The SS1_ARM_* milestones in config.h are offsets from the MOVE cue; the steps
 * below need the gaps BETWEEN them, which is all these are. Change a phase in
 * config.h and every step retimes itself. */
#define SS1_T_GRIP_AND_COLLECT  ( SS1_ARM_TO_SOURCE_MS + SS1_ARM_POS_SOURCE_MS \
                                + SS1_ARM_PICK_MS )                        /*  6300 */
#define SS1_T_COLLECT_END       ( SS1_ARM_COLLECT_END_MS )                 /* 26300 */
#define SS1_T_CROSS_TO_DEST     ( SS1_ARM_AT_DEST_MS                       \
                                - SS1_ARM_COLLECT_END_MS )                 /*  4800 */
#define SS1_T_DUMP_HOLD         ( SS1_ARM_DUMP_END_MS                      \
                                - SS1_ARM_AT_DEST_MS )                     /* 22950 */
#define SS1_T_GO_HOME           ( SS1_ARM_TOTAL_MS - SS1_ARM_DUMP_END_MS   \
                                + SS1_ARM_HOME_HOLD_MS )                   /*  7450 */
#define SS1_T_DUMP_AFTER_GRAV   ( SS1_T_DUMP_HOLD - SS1_GRAV_READY_AT_MS ) /*  9950 */

/* ---- compile-time guards -------------------------------------------------
 * SLOT_LEDS is a const array, not a constant expression, so the guards below
 * use the span lengths that map currently yields. Keep them in step with
 * SLOT_LEDS[SS1_SLOT_MACRO] / [SS1_SLOT_GRAVIMETRIC] if the rig is
 * re-measured; seq_leds_print_map() ("seq map") is the check by eye. */
#define SS1_MACRO_SPAN_LEN   6u
#define SS1_GRAV_SPAN_LEN   10u

#define SS1_MACRO_CHARGE_MS  SS1_CHARGE_MS(SS1_MACRO_SPAN_LEN, SS1_CHARGE_FILL_MACRO_MS)
#define SS1_GRAV_CHARGE_MS   SS1_CHARGE_MS(SS1_GRAV_SPAN_LEN,  SS1_CHARGE_FILL_GRAV_MS)

/* The board and the Android arm are two open-loop clocks with no handshake
 * between them, so this is the one number that must be identical on both
 * sides. RobotPhase.TOTAL_MS in RobotStateMachine.kt. Editing a phase in
 * config.h without editing the Kotlin (or vice versa) fails the build here
 * rather than drifting silently on the demo table. */
_Static_assert(SS1_ARM_TOTAL_MS == 58500u,
               "SS1_ARM_* must sum to RobotPhase.TOTAL_MS in the Android app");

/* The gravimetric goes GREEN partway through the blender dump. Measured from
 * the arm reaching the blender, so it must land inside the dump hold — if it
 * did not, the step timer below would underflow to ~49 days. */
_Static_assert(SS1_GRAV_READY_AT_MS < SS1_ARM_DUMP_END_MS - SS1_ARM_AT_DEST_MS,
               "the gravimetric READY point must fall inside the dump hold");

/* Each recharge has to finish inside the window it plays in, because there is
 * ONE Ss1Charge instance and the macro's 0..5 is a subset of the gravimetric's
 * 0..9 — two live at once would scribble over each other. */
_Static_assert(SS1_MACRO_CHARGE_MS <= SS1_COLLECTION_MS,
               "the macro recharge must finish before the arm leaves module 3");
_Static_assert(SS1_GRAV_CHARGE_MS <= SS1_BLEND_RUN_MS,
               "the gravimetric recharge must finish before the blender hands off");

/* ss1_block_len() encodes "first pixel / up to the middle / all of them". Raise
 * SS1_CHARGE_BLOCKS and the middle case would simply repeat itself rather than
 * subdividing further, which would look like a stutter rather than a mistake —
 * so make it a build error instead of a puzzle on the demo table. */
_Static_assert(SS1_CHARGE_BLOCKS == 3u,
               "the block-extent rule only makes sense for three blocks");

/* Requirement: the gravimetric span fills faster per pixel but takes slightly
 * longer overall. Fast enough not to drag over 10 pixels, long enough that the
 * bigger module still reads as the bigger job. */
_Static_assert(SS1_CHARGE_FILL_GRAV_MS < SS1_CHARGE_FILL_MACRO_MS,
               "the gravimetric recharge must fill faster per pixel");
_Static_assert(SS1_GRAV_CHARGE_MS > SS1_MACRO_CHARGE_MS,
               "the gravimetric recharge must still take slightly longer overall");

/* ======================================================================
 * Animators
 *
 * Both write the strip ONLY through seq_leds_strip_set(), which is a flag per
 * pixel; seq_leds redraws the whole frame from those flags, so an animation
 * can never leave a stray pixel behind. Both keep an ABSOLUTE deadline in
 * millis(), compared the wrap-safe way.
 *
 * There is exactly ONE instance of each, deliberately. The two recharges never
 * overlap (the sequence gates on the first finishing) and neither do the two
 * flows, so reusing one instance makes it impossible for two animations to
 * fight over the same pixels. seq_leds_strip_set() has no notion of ownership:
 * add a second concurrent animator on an overlapping span and it will scribble
 * over the first with no warning at all.
 * ==================================================================== */

static inline bool due_now(uint32_t due)
{
    return (int32_t)(millis() - due) >= 0;
}

/* Advance an absolute deadline by one step. If we were starved for longer than
 * a whole step — a long serial print, say — re-base instead, so the animation
 * resyncs rather than firing a burst of pixels on consecutive main-loop passes,
 * which would read as a jump rather than a swipe. */
static void bump_due(uint32_t *due, uint32_t step_ms)
{
    *due += step_ms;
    if ((int32_t)(millis() - *due) > (int32_t)step_ms) {
        *due = millis() + step_ms;
    }
}

/* ---- progressive recharge ------------------------------------------------
 * Deliberately NOT a swipe. A travelling head reads as something moving past
 * the module; charging is a level filling up, so the pixels latch on in place
 * and stay on. Two stages, over the macro's 0..5:
 *
 *   STAGE 1   0 | 0,1 | 0,1,2 | 0..3 | 0..4 | 0..5   then the span blanks
 *             one pixel per SS1_CHARGE_FILL_*_MS, each one staying lit.
 *
 *   STAGE 2   0 | blank | 0,1,2 | blank | 0..5 | blank
 *             three growing blocks, the group repeated
 *             SS1_CHARGE_BLOCK_REPEATS times.
 *
 * Then the span goes DARK and stays dark. The old animation left the charged
 * pixels lit, which meant the macro's 0..5 was still on screen when the
 * gravimetric restarted on the overlapping 0..9 — the two looked like one
 * animation glitching. A recharged module announces itself by its own LED
 * going blue; the strip's job is finished.
 *
 * Block extents come from one rule, chosen so it reproduces the requirement's
 * "1st LED / up to the 3rd / all" over six pixels exactly, and generalises:
 *   1 / (len+1)/2 / len   ->  1,3,6 over 0..5   and   1,5,10 over 0..9 */
typedef enum {
    CH_IDLE = 0,
    CH_FILL,      /* stage 1: one more pixel latches on each step            */
    CH_FILL_GAP,  /* stage 1 done, span blanked, short pause before stage 2  */
    CH_BLOCK_ON,  /* stage 2: a block is lit, holding                        */
    CH_BLOCK_OFF, /* stage 2: span dark, holding between blocks              */
    CH_DONE       /* complete, span already dark                             */
} Ss1ChargePhase;

typedef struct {
    Ss1ChargePhase phase;
    uint8_t  a, b;      /* inclusive strip span                              */
    uint8_t  len;       /* b - a + 1                                         */
    uint8_t  n;         /* stage 1: pixels lit so far                        */
    uint8_t  block;     /* stage 2: which block, 0..SS1_CHARGE_BLOCKS-1      */
    uint8_t  rep;       /* stage 2: groups completed                         */
    uint8_t  owner;     /* slot that goes BLUE when this completes           */
    uint16_t step_ms;   /* stage 1 per-pixel dwell; macro and grav differ    */
    uint32_t due;
} Ss1Charge;

/* Pixels lit by block k of a span of `len`: 1, then up to the middle, then all. */
static uint8_t ss1_block_len(uint8_t k, uint8_t len)
{
    if (k == 0u) {
        return 1u;
    }
    if (k + 1u >= SS1_CHARGE_BLOCKS) {
        return len;
    }
    return (uint8_t)((len + 1u) / 2u);
}

/* ---- material flow -------------------------------------------------------
 * The span fills one pixel at a time, holds full for one step, blanks, and
 * repeats. Over 14..16 that is the requirement's 14 / 14,15 / 14,15,16 / dark,
 * four times over; the same code runs the packaging output over 17..21.
 *
 * The all-dark hold IS the gap between passes — there is deliberately no third
 * timing knob for it. */
typedef enum {
    FL_IDLE = 0,
    FL_BUILD,   /* adding one pixel at a time, a -> b                        */
    FL_FULL,    /* whole span lit, holding for one step                      */
    FL_BLANK,   /* whole span dark, holding between passes                   */
    FL_DONE
} Ss1FlowPhase;

typedef struct {
    Ss1FlowPhase phase;
    uint8_t  a, b;
    uint8_t  head;      /* last pixel lit so far                             */
    uint8_t  pass;      /* passes completed                                  */
    uint8_t  passes;    /* passes to run                                     */
    uint32_t due;
} Ss1Flow;

/* ======================================================================
 * Step chain
 * ==================================================================== */

typedef enum {
    S_INIT = 0,      /* M2,M3 blue; M5,M6 dark; strip clear                   */
    S_MACRO_READY,   /* M2 green — macro done, robot may collect              */
    S_COLLECT_1,     /* MOVE:2>5, arm travels and then collects for 20 s      */
    S_LEAVE_M2_1,    /* M2 dark — the arm is lifting away                     */
    S_DUMP_1A,       /* M5 dark — the arm has arrived, blender receiving      */
    S_DUMP_1B,       /* M3 green — gravimetric finished mid-dump              */
    S_BLENDER_RUN_1, /* M5 blue — the arm is retracting; blending             */
    S_COLLECT_2,     /* MOVE:3>5, arm travelling to the gravimetric           */
    S_RECHARGE_MACRO,/* the arm is now stood collecting; macro recharges 0..5 */
    S_LEAVE_M3_1,    /* M3 dark, M5 green — blender ready to receive          */
    S_DUMP_2,        /* gravimetric material going into the blender           */
    S_BLEND_GATE,    /* M5 blue; hold here until the macro recharge is done   */
    S_BLEND_RUN,     /* gravimetric recharge starts; blender runs             */
    S_XFER,          /* M5 green; material flows 14..16 to packaging          */
    S_PACK_RUN,      /* M6 blue; hold until the gravimetric recharge is done  */
    S_MACRO_READY_2, /* M2 green — macro available again                      */
    S_COLLECT_3,     /* MOVE:2>5                                              */
    S_LEAVE_M2_2,    /* M2 dark                                               */
    S_DUMP_3A,       /* M5 dark                                               */
    S_DUMP_3B,       /* M3 green                                              */
    S_BLENDER_RUN_2, /* M5 blue                                               */
    S_COLLECT_4,     /* MOVE:3>5                                              */
    S_LEAVE_M3_2,    /* M3 dark, M5 green                                     */
    S_DUMP_4,        /* last material of the cycle into the blender           */
    S_PACK_FINISH,   /* M5 blue; packaging finishing off                      */
    S_OUTPUT,        /* M6 green; material flows 17..21 out of the system     */
    S_CYCLE_END,     /* LOT:DONE, everything reset, board blank               */
    S_DONE,          /* four cycles complete; parked                          */
    S_STEP_COUNT
} Ss1Step;

static const char *const SS1_STEP_NAME[S_STEP_COUNT] = {
    "INIT",           "MACRO_READY",  "COLLECT_1",  "LEAVE_M2_1",
    "DUMP_1A",        "DUMP_1B",      "BLENDER_RUN_1",
    "COLLECT_2",      "RECHARGE_MACRO","LEAVE_M3_1","DUMP_2",
    "BLEND_GATE",     "BLEND_RUN",    "XFER",       "PACK_RUN",
    "MACRO_READY_2",  "COLLECT_3",    "LEAVE_M2_2", "DUMP_3A",
    "DUMP_3B",        "BLENDER_RUN_2","COLLECT_4",  "LEAVE_M3_2",
    "DUMP_4",         "PACK_FINISH",  "OUTPUT",     "CYCLE_END",
    "COMPLETE"
};

/* ---- state ---------------------------------------------------------------
 * This is the whole of it. Everything the requirement asks to be reset between
 * cycles lives in these six variables: the step, the chain timer, and the two
 * animators (which carry their own progress and their own deadlines). There is
 * deliberately no loose flag anywhere — the recharge-completion side effect
 * lives in Ss1Charge.owner, so there is nothing to forget to clear. */
static Ss1Status s_status;
static Ss1Step   s_step;
static uint8_t   s_cycle;       /* 1..SS1_CYCLES, 0 when idle                */
static SoftTimer s_timer;       /* the chain's ONE timer                     */
static Ss1Charge s_charge;
static Ss1Flow   s_flow;

/* ---- module state, with the robot kept in the loop ----------------------- */

/* The single funnel for every module colour change: dedupes, so a step that
 * re-asserts a state costs nothing, and emits the matching cue to the Android
 * app. Nothing else in this file may call module_set_state(). */
static void set_mod(uint8_t slot, ModState st)
{
    if (module_get_state(slot) == st) {
        return;
    }
    module_set_state(slot, st);
#if SS1_EMIT_RUN_DONE
    if (st == MOD_RUNNING) {
        ble_run(SLOT1(slot));
    } else if (st == MOD_READY) {
        ble_done(SLOT1(slot));
    }
#endif
}

/* ---- charge animator ----------------------------------------------------- */

static void ss1_charge_reset(Ss1Charge *c)
{
    if (c->phase != CH_IDLE) {
        seq_leds_strip_set(c->a, c->b, false);
    }
    c->phase = CH_IDLE;
    c->n     = 0u;
    c->block = 0u;
    c->rep   = 0u;
}

static void ss1_charge_start(Ss1Charge *c, uint8_t a, uint8_t b,
                             uint8_t owner, uint16_t step_ms)
{
    ss1_charge_reset(c);
    c->a       = a;
    c->b       = b;
    c->len     = (uint8_t)((b >= a) ? (b - a + 1u) : 0u);
    c->owner   = owner;
    c->step_ms = step_ms;
    if (c->len == 0u) {
        c->phase = CH_DONE;
        return;
    }
    seq_leds_strip_set(a, a, true);     /* stage 1 opens on the first pixel */
    c->n     = 1u;
    c->phase = CH_FILL;
    c->due   = millis() + step_ms;
}

static inline bool ss1_charge_done(const Ss1Charge *c) { return c->phase == CH_DONE; }

static void ss1_charge_tick(Ss1Charge *c)
{
    if (c->phase == CH_IDLE || c->phase == CH_DONE || !due_now(c->due)) {
        return;
    }

    switch (c->phase) {
    case CH_FILL:
        if (c->n < c->len) {
            /* One more pixel joins the ones already lit — nothing is cleared. */
            seq_leds_strip_set((uint8_t)(c->a + c->n), (uint8_t)(c->a + c->n), true);
            c->n++;
            bump_due(&c->due, c->step_ms);
        } else {
            seq_leds_strip_set(c->a, c->b, false);
            c->phase = CH_FILL_GAP;
            c->due   = millis() + SS1_CHARGE_BLANK_MS;
        }
        break;

    case CH_FILL_GAP:
        c->block = 0u;
        c->rep   = 0u;
        seq_leds_strip_set(c->a, (uint8_t)(c->a + ss1_block_len(0u, c->len) - 1u), true);
        c->phase = CH_BLOCK_ON;
        c->due   = millis() + SS1_CHARGE_BLOCK_ON_MS;
        break;

    case CH_BLOCK_ON:
        seq_leds_strip_set(c->a, c->b, false);
        c->phase = CH_BLOCK_OFF;
        c->due   = millis() + SS1_CHARGE_BLOCK_OFF_MS;
        break;

    case CH_BLOCK_OFF:
        c->block++;
        if (c->block >= SS1_CHARGE_BLOCKS) {
            c->block = 0u;
            c->rep++;
        }
        if (c->rep >= SS1_CHARGE_BLOCK_REPEATS) {
            /* Recharged. The span is already dark — a recharged module is
             * signalled by its own LED going blue, not by leaving the strip
             * lit. This is where "macro is available again" and "gravimetric is
             * available again" actually happen, driven by the animation
             * finishing rather than by a separate clock. */
            c->phase = CH_DONE;
            set_mod(c->owner, MOD_RUNNING);
        } else {
            seq_leds_strip_set(c->a,
                               (uint8_t)(c->a + ss1_block_len(c->block, c->len) - 1u),
                               true);
            c->phase = CH_BLOCK_ON;
            c->due   = millis() + SS1_CHARGE_BLOCK_ON_MS;
        }
        break;

    default:
        break;
    }
}

/* ---- flow animator ------------------------------------------------------- */

static void ss1_flow_reset(Ss1Flow *f)
{
    if (f->phase != FL_IDLE) {
        seq_leds_strip_set(f->a, f->b, false);
    }
    f->phase = FL_IDLE;
    f->pass  = 0u;
    f->head  = 0u;
}

static void ss1_flow_start(Ss1Flow *f, uint8_t a, uint8_t b, uint8_t passes)
{
    ss1_flow_reset(f);
    f->a      = a;
    f->b      = b;
    f->head   = a;
    f->pass   = 0u;
    f->passes = passes;
    if (b < a || passes == 0u) {
        f->phase = FL_DONE;
        return;
    }
    seq_leds_strip_set(a, a, true);
    f->phase = (a >= b) ? FL_FULL : FL_BUILD;
    f->due   = millis() + SS1_FLOW_STEP_MS;
}

static inline bool ss1_flow_done(const Ss1Flow *f) { return f->phase == FL_DONE; }

static void ss1_flow_tick(Ss1Flow *f)
{
    if (f->phase == FL_IDLE || f->phase == FL_DONE || !due_now(f->due)) {
        return;
    }

    switch (f->phase) {
    case FL_BUILD:
        if (f->head < f->b) {
            f->head++;
            seq_leds_strip_set(f->head, f->head, true);
        }
        bump_due(&f->due, SS1_FLOW_STEP_MS);
        if (f->head >= f->b) {
            f->phase = FL_FULL;      /* whole span lit: hold it one more step */
        }
        break;

    case FL_FULL:
        seq_leds_strip_set(f->a, f->b, false);
        f->phase = FL_BLANK;
        f->due   = millis() + SS1_FLOW_BLANK_MS;
        break;

    case FL_BLANK:
        f->pass++;
        if (f->pass >= f->passes) {
            f->phase = FL_DONE;      /* span already dark */
        } else {
            f->head  = f->a;
            seq_leds_strip_set(f->head, f->head, true);
            f->phase = (f->a >= f->b) ? FL_FULL : FL_BUILD;
            f->due   = millis() + SS1_FLOW_STEP_MS;
        }
        break;

    default:
        break;
    }
}

/* ---- reset --------------------------------------------------------------- */

/* Everything temporary, back to a known state: both animators stopped and their
 * pixels cleared, the chain timer disarmed, the strip blank. Called at the end
 * of every cycle, on start, and on stop, so no cycle ever inherits anything
 * from the one before it.
 *
 * There is no robot state to reset: the MOVE cue is fire-and-forget and the app
 * parks the arm itself at the end of every carry. */
static void ss1_reset_transient(void)
{
    timer_stop(&s_timer);
    ss1_charge_reset(&s_charge);
    ss1_flow_reset(&s_flow);
    seq_leds_strip_clear();
}

/* ---- the chain ----------------------------------------------------------- */

/* Run a step's entry actions and arm the chain timer. A step with no duration
 * of its own disarms it instead: either it is instantaneous, or it waits on an
 * animator rather than on the clock, and leaving a stale armed timer behind
 * would be a trap for whoever adds a timeout here later. */
static void enter_step(Ss1Step s)
{
    uint32_t ms = 0u;

    s_step = s;

    switch (s) {
    /* ---- Phase 0: both feeders running ---------------------------------- */
    case S_INIT:
        ss1_reset_transient();
        set_mod(SS1_SLOT_MACRO,       MOD_RUNNING);
        set_mod(SS1_SLOT_GRAVIMETRIC, MOD_RUNNING);
        set_mod(SS1_SLOT_BLENDER,     MOD_OFF);
        set_mod(SS1_SLOT_PACKAGING,   MOD_OFF);
        ms = SS1_INITIAL_PROCESS_MS;
        break;

    case S_MACRO_READY:
        set_mod(SS1_SLOT_MACRO, MOD_READY);
        break;

    /* ---- Phase 1: robot collects from the macro -------------------------
     * One MOVE cue covers the whole carry — travel, grip, the 20 s stood
     * collecting, the crossing, the 20 s stood pouring and the return home are
     * all one animation in the Android app. Do not add a second cue: with no
     * ARRIVED coming back there is nothing to synchronise against.
     *
     * What this side CAN do is peg its own colour changes to the same
     * milestones the arm is animating to. SS1_T_COLLECT_END is the moment the
     * arm finishes collecting and starts to lift, so the macro stays green for
     * the whole time the arm is stood at it. */
    case S_COLLECT_1:
    case S_COLLECT_3:
        ble_move(SLOT1(SS1_SLOT_MACRO), SLOT1(SS1_SLOT_BLENDER));
        ms = SS1_T_COLLECT_END;
        break;

    case S_LEAVE_M2_1:
    case S_LEAVE_M2_2:
        set_mod(SS1_SLOT_MACRO, MOD_OFF);   /* the arm is lifting away now */
        ms = SS1_T_CROSS_TO_DEST;
        break;

    /* ---- Phase 2: macro material into the blender ------------------------
     * Entered as the arm reaches the blender, so the blender goes dark to
     * receive at the moment there is something to receive. */
    case S_DUMP_1A:
    case S_DUMP_3A:
        set_mod(SS1_SLOT_BLENDER, MOD_OFF);
        ms = SS1_GRAV_READY_AT_MS;
        break;

    case S_DUMP_1B:
    case S_DUMP_3B:
        set_mod(SS1_SLOT_GRAVIMETRIC, MOD_READY);
        ms = SS1_T_DUMP_AFTER_GRAV;
        break;

    /* The pour is finished and the arm is retracting: the blender is running.
     * The wait is the arm's trip home plus a beat stood at rail centre, so the
     * next MOVE never lands while the previous animation is still playing. */
    case S_BLENDER_RUN_1:
    case S_BLENDER_RUN_2:
        set_mod(SS1_SLOT_BLENDER, MOD_RUNNING);
        ms = SS1_T_GO_HOME;
        break;

    /* ---- Phase 3 + 3A: collect the gravimetric while the macro recharges --
     * This carry is split across two steps, unlike the others, so the recharge
     * starts when the arm has actually settled into collecting rather than
     * while it is still crossing the rig. The two waits add up to the same
     * SS1_T_COLLECT_END the other carries use in one go. */
    case S_COLLECT_2:
        ble_move(SLOT1(SS1_SLOT_GRAVIMETRIC), SLOT1(SS1_SLOT_BLENDER));
        ms = SS1_T_GRIP_AND_COLLECT;
        break;

    case S_RECHARGE_MACRO:
        ss1_charge_start(&s_charge, SS1_MACRO_A, SS1_MACRO_B,
                         SS1_SLOT_MACRO, SS1_CHARGE_FILL_MACRO_MS);
        ms = SS1_COLLECTION_MS;
        break;

    /* The second gravimetric carry recharges nothing, so it needs no split. */
    case S_COLLECT_4:
        ble_move(SLOT1(SS1_SLOT_GRAVIMETRIC), SLOT1(SS1_SLOT_BLENDER));
        ms = SS1_T_COLLECT_END;
        break;

    /* ---- Phase 4: robot leaves the gravimetric --------------------------- */
    case S_LEAVE_M3_1:
    case S_LEAVE_M3_2:
        set_mod(SS1_SLOT_GRAVIMETRIC, MOD_OFF);
        set_mod(SS1_SLOT_BLENDER,     MOD_READY);   /* ready to receive */
        ms = SS1_T_CROSS_TO_DEST;
        break;

    /* ---- Phase 5: gravimetric material into the blender ------------------ */
    case S_DUMP_2:
    case S_DUMP_4:
        ms = SS1_T_DUMP_HOLD;
        break;

    /* ---- Phase 6: blender runs; gravimetric recharge may now start -------
     * The gate is the requirement's hard rule: the gravimetric recharge does
     * not begin until the macro one has actually finished. With the shipped
     * constants it is already done by the time we get here — that is what the
     * _Static_assert above enforces — so this costs nothing and stays correct
     * if somebody slows the animation right down. */
    case S_BLEND_GATE:
        set_mod(SS1_SLOT_BLENDER, MOD_RUNNING);
        break;

    case S_BLEND_RUN:
        ss1_charge_start(&s_charge, SS1_GRAV_A, SS1_GRAV_B,
                         SS1_SLOT_GRAVIMETRIC, SS1_CHARGE_FILL_GRAV_MS);
        ms = SS1_BLEND_RUN_MS;
        break;

    /* ---- Phase 7: blender hands off to packaging. No robot movement. ----- */
    case S_XFER:
        set_mod(SS1_SLOT_BLENDER, MOD_READY);
        ss1_flow_start(&s_flow, SS1_XFER_A, SS1_XFER_B, SS1_FLOW_XFER_PASSES);
        break;

    case S_PACK_RUN:
        set_mod(SS1_SLOT_PACKAGING, MOD_RUNNING);
        break;

    /* ---- Phases 8 + 9: the second macro and gravimetric carries ---------- */
    case S_MACRO_READY_2:
        set_mod(SS1_SLOT_MACRO, MOD_READY);
        break;

    case S_PACK_FINISH:
        set_mod(SS1_SLOT_BLENDER, MOD_RUNNING);
        ms = SS1_PACKAGING_FINISH_MS;
        break;

    /* ---- Phase 10: packaging sends material out. No robot movement. ------ */
    case S_OUTPUT:
        set_mod(SS1_SLOT_PACKAGING, MOD_READY);
        ss1_flow_start(&s_flow, SS1_OUT_A, SS1_OUT_B, SS1_FLOW_OUTPUT_PASSES);
        break;

    /* ---- End of cycle: blank the board so the reset is something the
     * audience watches happen, rather than a jump cut into the next cycle. -- */
    case S_CYCLE_END:
        ble_lot_done();
        ss1_reset_transient();
        module_all_off();
        ms = SS1_CYCLE_GAP_MS;
        break;

    case S_DONE:
        ss1_reset_transient();
        module_all_off();
        s_status = SS1_COMPLETE;
        break;

    default:
        break;
    }

    if (ms > 0u) {
        timer_start(&s_timer, ms);
    } else {
        timer_stop(&s_timer);
    }
}

/* ---- public API ---------------------------------------------------------- */

void solid_seq1_start(void)
{
    ss1_reset_transient();
    module_all_off();

    /* Announce the sequence FIRST, so the log and the Android app always see
     * SEQ:1 ahead of any RUN or MOVE cue. */
    ble_seq(SS1_SEQ_NUMBER);
    seq_leds_start(SS1_SEQ_NUMBER);      /* takes the strip and blanks it */

    s_status = SS1_RUNNING;
    s_cycle  = 1u;
    enter_step(S_INIT);
}

void solid_seq1_stop(void)
{
    ss1_reset_transient();               /* while seq_leds still owns the strip */
    module_all_off();
    s_status = SS1_IDLE;
    s_cycle  = 0u;
    s_step   = S_INIT;
    seq_leds_stop();
}

void solid_seq1_tick(void)
{
    bool advance = false;

    if (s_status != SS1_RUNNING) {
        return;
    }
    /* If anything took the strip out from under us, stop properly rather than
     * carrying on invisibly — otherwise the module states and the BLE cues
     * would keep running and the robot would keep receiving MOVE commands. */
    if (!seq_leds_active()) {
        solid_seq1_stop();
        return;
    }

    /* The animators run on their own clocks, independently of the chain and of
     * each other. This is what lets the macro recharge play while the robot is
     * collecting from the gravimetric. */
    ss1_charge_tick(&s_charge);
    ss1_flow_tick(&s_flow);

    switch (s_step) {
    /* Instant: a state change with no dwell of its own. The module goes green
     * and the MOVE cue that collects it goes out on the next pass — a gap here
     * would be a module sitting ready with the arm ignoring it. Every other
     * step now waits for something real, which is the point of the rework. */
    case S_MACRO_READY:
    case S_MACRO_READY_2:
        advance = true;
        break;

    /* Waiting on a recharge to actually finish. */
    case S_BLEND_GATE:
    case S_PACK_RUN:
        advance = ss1_charge_done(&s_charge);
        break;

    /* Waiting on a material-flow animation to play out. */
    case S_XFER:
    case S_OUTPUT:
        advance = ss1_flow_done(&s_flow);
        break;

    case S_DONE:
        break;

    /* Everything else runs on the chain timer. It is polled from here and
     * nowhere else: timer_expired() is one-shot and clears itself when it
     * fires, so a second reader would silently swallow the event. */
    default:
        advance = timer_expired(&s_timer);
        break;
    }

    if (!advance) {
        return;
    }

    if (s_step == S_CYCLE_END) {
        if (s_cycle >= SS1_CYCLES) {
            enter_step(S_DONE);
        } else {
            s_cycle++;
            enter_step(S_INIT);
        }
    } else {
        enter_step((Ss1Step)(s_step + 1));
    }
}

Ss1Status solid_seq1_state(void) { return s_status; }
uint8_t   solid_seq1_cycle(void) { return s_cycle; }

const char *solid_seq1_step_name(void)
{
    return (s_step < S_STEP_COUNT) ? SS1_STEP_NAME[s_step] : "?";
}
