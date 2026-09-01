#include "solid_seq1.h"
#include "config.h"
#include "module.h"
#include "seq_leds.h"
#include "ble.h"
#include "timebase.h"

/* 0-based slot index -> the 1-based number the operator and the robot use. */
#define SLOT1(idx)       ((uint8_t)((idx) + 1u))

/* Change 13.2 / 13.3: ONE cycle is ONE lot — the macro and the gravimetric each
 * dumped into the blender once, blended, shipped to packaging, and sent out on
 * the exit strip. Two carries, one blender batch, one lot, one LOT:DONE, and
 * the cycle counter ticks over. Packaging ships on the FIRST batch it receives;
 * waiting for a second batch was the old two-batch lot, and it is gone — which
 * is why there is no batches-per-cycle knob here set to 1. Odd carries collect
 * the macro, even ones the gravimetric. */
#define SS1_CARRIES_PER_CYCLE   2u
#define SS1_MATERIALS_PER_BATCH 2u

/* Change 13.2: there is no reset and no blank board between cycles. The arm
 * runs every cycle's carries back to back — this many in all — while the plant
 * finishes each lot underneath, running a cycle behind the arm and never
 * making it wait. */
#define SS1_TOTAL_CARRIES       ( SS1_CYCLES * SS1_CARRIES_PER_CYCLE )

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
 * the moment the arm is actually in front of it.
 *
 * The SS1_ARM_* milestones in config.h are offsets from the MOVE cue; the four
 * carry steps need the gaps BETWEEN them, which is all these are. Change a
 * phase in config.h and every step retimes itself. They sum to
 * SS1_ARM_TOTAL_MS + SS1_ARM_HOME_HOLD_MS, asserted below. */
/* Change 17.3: the source goes dark SS1_COLLECT_OFF_LEAD_MS before the arm's
 * collect-end milestone, so the two steps below trade that time between them.
 * Their SUM is untouched, which is what keeps the dump landing exactly on
 * SS1_ARM_AT_DEST_MS and the whole carry tiling to SS1_T_CARRY. */
#define SS1_T_COLLECT_END       ( SS1_ARM_COLLECT_END_MS                   \
                                - SS1_COLLECT_OFF_LEAD_MS )                /* 24300 */
#define SS1_T_CROSS_TO_DEST     ( SS1_ARM_AT_DEST_MS                       \
                                - SS1_ARM_COLLECT_END_MS                   \
                                + SS1_COLLECT_OFF_LEAD_MS )                /*  6800 */
#define SS1_T_DUMP_HOLD         ( SS1_ARM_DUMP_END_MS                      \
                                - SS1_ARM_AT_DEST_MS )                     /* 22950 */
#define SS1_T_GO_HOME           ( SS1_ARM_TOTAL_MS - SS1_ARM_DUMP_END_MS   \
                                + SS1_ARM_HOME_HOLD_MS )                   /*  7450 */

/* One whole carry, cue to cue. Nothing in the plant is allowed to make the arm
 * wait, so this is also the fixed spacing between consecutive MOVE cues. */
#define SS1_T_CARRY             ( SS1_T_COLLECT_END + SS1_T_CROSS_TO_DEST  \
                                + SS1_T_DUMP_HOLD + SS1_T_GO_HOME )        /* 61500 */

/* ---- compile-time guards -------------------------------------------------
 * SLOT_LEDS is a const array, not a constant expression, so the guards below
 * use the span lengths that map currently yields. Keep them in step with
 * SLOT_LEDS[SS1_SLOT_MACRO] / [SS1_SLOT_GRAVIMETRIC] and with the blender span
 * behind SS1_XFER_A..B if the rig is re-measured; seq_leds_print_map()
 * ("seq map") is the check by eye. The exit span 17..21 needs no such care:
 * SOLID_EXIT_A/B are plain literals. */
#define SS1_MACRO_SPAN_LEN   6u
#define SS1_GRAV_SPAN_LEN   10u
#define SS1_XFER_SPAN_LEN    3u

#define SS1_MACRO_CHARGE_MS  SS1_CHARGE_MS(SS1_MACRO_SPAN_LEN, SS1_CHARGE_FILL_MACRO_MS)
#define SS1_GRAV_CHARGE_MS   SS1_CHARGE_MS(SS1_GRAV_SPAN_LEN,  SS1_CHARGE_FILL_GRAV_MS)

/* One flow pass lights its span a pixel at a time, holds the full span for one
 * more step and then blanks; the blank IS the gap before the next pass. So a
 * pass costs len*STEP + BLANK, and the animation is that once per pass. */
#define SS1_OUT_SPAN_LEN     ( SOLID_EXIT_B - SOLID_EXIT_A + 1u )

#define SS1_FLOW_MS(len, passes)                                                  ( (passes) * ( (len) * SS1_FLOW_STEP_MS + SS1_FLOW_BLANK_MS ) )

#define SS1_XFER_FLOW_MS  SS1_FLOW_MS(SS1_XFER_SPAN_LEN, SS1_FLOW_XFER_PASSES)   /* 13200 */
#define SS1_OUT_FLOW_MS   SS1_FLOW_MS(SS1_OUT_SPAN_LEN,  SS1_FLOW_OUTPUT_PASSES) /*  9400 */

/* Change 13.3: one batch IS the lot, so this is the entire plant tail — from
 * the arm finishing the second pour to LOT:DONE going out on the wire. */
#define SS1_LOT_TAIL_MS   ( SS1_BLEND_RUN_MS + SS1_XFER_FLOW_MS                                          + SS1_PACKAGING_FINISH_MS + SS1_OUT_FLOW_MS )        /* 46600 */

/* The board and the Android arm are two open-loop clocks with no handshake
 * between them, so this is the one number that must be identical on both
 * sides. RobotPhase.TOTAL_MS in RobotStateMachine.kt. Editing a phase in
 * config.h without editing the Kotlin (or vice versa) fails the build here
 * rather than drifting silently on the demo table. */
_Static_assert(SS1_ARM_TOTAL_MS == 40000u,
               "SS1_ARM_* must sum to Timings.DEFAULT_ROBOT_ANIMATION_MS in the app");

/* The four carry steps must tile the whole carry exactly. If they did not, a
 * module colour would land at the wrong point of the arm animation — which is
 * the fault this whole design exists to prevent. */
_Static_assert(SS1_T_CARRY == SS1_ARM_TOTAL_MS + SS1_ARM_HOME_HOLD_MS,
               "the carry steps must tile the arm animation plus the home hold");

/* Timings.TRANSFER_ROBOT_MS in the Android app. The app sizes its catch-up
 * logic on consecutive cues never arriving closer together than this. */
_Static_assert(SS1_T_CARRY == 42000u,
               "SS1_T_CARRY must equal Timings.CARRY_MS in the Android app");

/* There is ONE recharge animator because the macro's 0..5 is a SUBSET of the
 * gravimetric's 0..9 — the two recharges are physically incapable of playing at
 * the same time on this LED map, whatever the sequence would like. What keeps
 * them apart is that each is triggered by a carry's dump, and consecutive
 * carries are SS1_T_CARRY apart. Both recharges must therefore fit inside one
 * carry, or a recharge would still be running when the next one starts and the
 * two would scribble over each other. */
_Static_assert(SS1_MACRO_CHARGE_MS < SS1_T_CARRY,
               "the macro recharge must finish within one carry");
_Static_assert(SS1_GRAV_CHARGE_MS < SS1_T_CARRY,
               "the gravimetric recharge must finish within one carry");

/* Requirement: the gravimetric span fills faster per pixel but takes slightly
 * longer overall. Fast enough not to drag over 10 pixels, long enough that the
 * bigger module still reads as the bigger job. */
_Static_assert(SS1_CHARGE_FILL_GRAV_MS < SS1_CHARGE_FILL_MACRO_MS,
               "the gravimetric recharge must fill faster per pixel");
_Static_assert(SS1_GRAV_CHARGE_MS > SS1_MACRO_CHARGE_MS,
               "the gravimetric recharge must still take slightly longer overall");

/* A feeder has to be green again by the time the arm gets back to it, two
 * carries later: recharge, then produce, then ready. The recharge starts at the
 * dump of its own carry (SS1_ARM_AT_DEST_MS into it) and the arm is stood in
 * front of it again SS1_ARM_TO_SOURCE_MS into the carry after next. */
_Static_assert(SS1_MACRO_CHARGE_MS + SS1_INITIAL_PROCESS_MS
                   < 2u * SS1_T_CARRY + SS1_ARM_TO_SOURCE_MS - SS1_ARM_AT_DEST_MS,
               "the macro must be green again before the arm arrives back for it");

/* Change 13.1: the gravimetric no longer greens on its own — it stands blue
 * holding its batch until the arm dumps the MACRO into the blender. That cue
 * lands one carry after the gravimetric's own dump, so its recharge and its
 * produce must both fit inside a single carry. Miss this and the release
 * arrives while the module is still producing, feeder_release() no-ops, and the
 * gravimetric never goes green at all — a silent visual failure that no test on
 * the bench would catch, which is exactly what an assert is for. */
_Static_assert(SS1_GRAV_CHARGE_MS + SS1_INITIAL_PROCESS_MS < SS1_T_CARRY,
               "the gravimetric must be holding before the macro dump releases it");

/* Change 13.3: packaging has no queue and no second slot — one lot has to be
 * out of the door before the next lot's second material lands in the blender,
 * which happens every second carry. */
_Static_assert(SS1_LOT_TAIL_MS < 2u * SS1_T_CARRY,
               "a lot must ship before the next lot's second material lands");

/* The blender likewise has to be empty and dark before the next material lands
 * in it. Change 16.3 moved that landing to the END of the pour, so consecutive
 * ones are exactly one carry apart and the margin is simply SS1_T_CARRY. Blend,
 * hand off on 14..16, and be gone with time to spare. */
_Static_assert(SS1_BLEND_RUN_MS + SS1_XFER_FLOW_MS < SS1_T_CARRY,
               "the blender must be empty again before the next material lands");

/* Change 17.4: the first pour's acknowledgement has to finish long before the
 * second pour arrives, or the blender would still be lit from the wrong thing
 * when it starts actually blending. */
_Static_assert(SS1_RECEIVE_ACK_MS < SS1_T_CARRY,
               "the receive acknowledgement must end before the next material lands");

/* Change 17.3: the lead cannot eat the whole travel-and-grip run-up, or the
 * source would go dark before the arm had even arrived at it. */
_Static_assert(SS1_COLLECT_OFF_LEAD_MS < SS1_ARM_PICK_MS,
               "the source must not go dark before the arm has finished collecting");

/* ======================================================================
 * Animators
 *
 * Both write the strip ONLY through seq_leds_strip_set(), which is a flag per
 * pixel; seq_leds redraws the whole frame from those flags, so an animation
 * can never leave a stray pixel behind. Both keep an ABSOLUTE deadline in
 * millis(), compared the wrap-safe way.
 *
 * There is one recharge animator (see the assert above — the spans overlap) but
 * TWO flow animators, because 14..16 and 17..21 are disjoint and the blender
 * handing off to packaging genuinely can overlap packaging shipping the lot
 * out. seq_leds_strip_set() has no notion of ownership: put two concurrent
 * animators on overlapping spans and they will scribble over each other with no
 * warning at all.
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
 * and stay on. One stage, repeated. Over the macro's 0..5:
 *
 *   0 | 0,1 | 0,1,2 | 0..3 | 0..4 | 0..5 | blank      <- one pass
 *   one pixel per SS1_CHARGE_FILL_*_MS, each one staying lit, then the whole
 *   span blanks and the pass runs again. SS1_CHARGE_REPEATS passes in all.
 *
 * Then the span goes DARK and stays dark. The animation ends on the blank that
 * clears the last pass, so the macro's 0..5 is never still lit when the
 * gravimetric starts its overlapping 0..9. A recharged module announces itself
 * by its own LED going blue; the strip's job is finished. */
typedef enum {
    CH_IDLE = 0,
    CH_FILL,      /* one more pixel latches on each step                     */
    CH_BLANK,     /* span dark, holding between passes                       */
    CH_DONE       /* complete, span already dark                             */
} Ss1ChargePhase;

typedef struct {
    Ss1ChargePhase phase;
    uint8_t  a, b;      /* inclusive strip span                              */
    uint8_t  len;       /* b - a + 1                                         */
    uint8_t  n;         /* pixels lit so far in this pass                    */
    uint8_t  rep;       /* passes completed                                  */
    uint8_t  owner;     /* slot this recharge belongs to                     */
    uint16_t step_ms;   /* per-pixel dwell; macro and grav differ            */
    uint32_t due;
} Ss1Charge;

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
 * Concurrent structure
 *
 * The old design was ONE linear chain: every phase of the plant blocked the
 * next, so the board could only ever be doing a single thing, and the arm sat
 * parked at rail centre while the blender and packaging took their turns. That
 * is the root cause of "the sequence does one task at a time".
 *
 * What replaced it:
 *
 *   ROBOT CHAIN   still strictly serial, because there is exactly one arm. It
 *                 is the master clock: two carries per cycle, SS1_TOTAL_CARRIES
 *                 in all, back to back and SS1_T_CARRY apart from the first to
 *                 the last, never waiting on the plant and never resetting.
 *
 *   FEEDER x2     macro and gravimetric. Recharge, produce, go green — each on
 *                 its own timer. The macro greens as soon as it has produced;
 *                 the gravimetric HOLDS blue and only greens when the arm dumps
 *                 the macro into the blender (change 13.1).
 *
 *   BLENDER       collects two materials, blends, ships to packaging on the
 *                 14..16 flow. Runs entirely underneath the arm's carries.
 *                 Change 16.4: DARK while it holds the first material and
 *                 waits a whole carry for the second — blue is reserved for
 *                 the blend itself, so the module is never lit doing nothing.
 *
 *   PACKAGING     takes ONE batch (change 13.3), finishes the lot, ships it out
 *                 on the 17..21 flow and emits LOT:DONE — one lot per cycle.
 *
 * Each actor owns its own timer and its own state and is polled every tick, so
 * "simultaneously" is the default and serialisation is the thing that has to be
 * argued for. The only two places anything is still forced to be sequential are
 * the arm (one arm) and the two recharges (overlapping LED spans) — both
 * physical constraints, both asserted above rather than assumed.
 *
 * Change 13.2: there is NO reset or blank board between cycles. The arm runs
 * every cycle's carries back to back — SS1_TOTAL_CARRIES of them — and the
 * plant finishes each lot underneath while the arm is already carrying the
 * next cycle's material. A cycle is just one lot; the counter advances when a
 * lot ships, and on the app side that is the only thing driving the cycle
 * display, because the wire has no cycle message.
 * ==================================================================== */

typedef enum {
    S_IDLE = 0,
    S_INIT,          /* both feeders producing; macro will green, gravi holds */
    S_CARRY_MOVE,    /* MOVE cue out; arm travelling, then stood collecting   */
    S_CARRY_CROSS,   /* source dark — the arm has lifted away and is crossing */
    S_CARRY_DUMP,    /* destination blue — the arm is stood there pouring     */
    S_CARRY_HOME,    /* material fully in; arm retracting and going home      */
    S_ARM_DONE,      /* every carry done; waiting on the last lot to ship     */
    S_DONE,          /* all cycles complete; parked                          */
    S_STEP_COUNT
} Ss1Step;

static const char *const SS1_STEP_NAME[S_STEP_COUNT] = {
    "IDLE",       "INIT",       "CARRY_MOVE", "CARRY_CROSS",
    "CARRY_DUMP", "CARRY_HOME", "ARM_DONE",   "COMPLETE"
};

/* ---- feeder actor -------------------------------------------------------- */

typedef enum {
    FD_IDLE = 0,   /* dark, nothing owed                                     */
    FD_CHARGING,   /* the strip recharge is playing for this feeder          */
    FD_PRODUCING,  /* blue, making the next batch                            */
    FD_HELD,       /* blue, batch made, waiting for a release cue to go green */
    FD_READY       /* green, waiting for the arm                             */
} Ss1FeederPhase;

typedef struct {
    Ss1FeederPhase phase;
    uint8_t   slot;
    uint8_t   span_a, span_b;
    uint16_t  fill_ms;
    /* Change 13.1: the gravimetric must not go green alongside the macro — it
     * goes green only once the arm has dumped the macro's material into the
     * blender. A held feeder produces its batch, then waits blue for
     * feeder_release() instead of turning green on its own. The macro does not
     * hold: it greens the moment it has produced. */
    bool      holds;
    SoftTimer t;
} Ss1Feeder;

/* ---- blender actor ------------------------------------------------------- */

typedef enum {
    BL_EMPTY = 0,  /* dark, waiting for the first material of a batch        */
    BL_ACK,        /* blue, acknowledging the first pour -- change 17.4      */
    BL_HOLDING,    /* DARK, holding one material, waiting a whole carry for  */
                   /* the other -- change 16.4, see below                    */
    BL_BLENDING,   /* blue, both materials in, actually mixing              */
    BL_SHIPPING    /* green, handing off to packaging on the 14..16 flow     */
} Ss1BlenderPhase;

/* ---- packaging actor ----------------------------------------------------- */

typedef enum {
    PK_IDLE = 0,   /* dark, nothing received                                 */
    PK_FINISHING,  /* blue, the batch is in, finishing the lot               */
    PK_SHIPPING    /* green, the lot leaving on the 17..21 flow              */
} Ss1PackPhase;

/* ---- state ---------------------------------------------------------------
 * Every actor carries its own phase and its own timer, so there is no shared
 * mutable state between them at all and nothing to reset in the wrong order. */
static Ss1Status s_status;
static Ss1Step   s_step;
static uint8_t   s_cycle;       /* 1..SS1_CYCLES, 0 when idle                */
static uint8_t   s_lots;        /* lots shipped; change 13.2 drives s_cycle  */
static uint8_t   s_carry;       /* 1..SS1_TOTAL_CARRIES, 0 outside one       */
static SoftTimer s_timer;       /* the ROBOT chain's timer, nothing else      */

static Ss1Feeder s_macro;
static Ss1Feeder s_grav;
static Ss1Charge s_charge;      /* shared: the two spans overlap             */

static Ss1BlenderPhase s_blender;
static uint8_t         s_blender_materials;
static SoftTimer       s_blender_timer;
static Ss1Flow         s_flow_xfer;   /* 14..16, blender -> packaging        */

static Ss1PackPhase s_pack;
static SoftTimer    s_pack_timer;
static Ss1Flow      s_flow_out;       /* 17..21, packaging -> out            */

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
    seq_leds_strip_set(a, a, true);     /* the pass opens on the first pixel */
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
            /* The span is full. Blank it; that blank is both the end of this
             * pass and the gap before the next one. */
            seq_leds_strip_set(c->a, c->b, false);
            c->rep++;
            if (c->rep >= SS1_CHARGE_REPEATS) {
                c->phase = CH_DONE;   /* the owning feeder picks this up */
            } else {
                c->phase = CH_BLANK;
                c->due   = millis() + SS1_CHARGE_BLANK_MS;
            }
        }
        break;

    case CH_BLANK:
        /* Next pass starts over from the base of the span. */
        seq_leds_strip_set(c->a, c->a, true);
        c->n     = 1u;
        c->phase = CH_FILL;
        c->due   = millis() + c->step_ms;
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

/* ======================================================================
 * Feeder actor
 * ==================================================================== */

static void feeder_init(Ss1Feeder *f, uint8_t slot, uint8_t a, uint8_t b,
                        uint16_t fill_ms, bool holds)
{
    f->phase   = FD_IDLE;
    f->slot    = slot;
    f->span_a  = a;
    f->span_b  = b;
    f->fill_ms = fill_ms;
    f->holds   = holds;
    timer_stop(&f->t);
}

/* The arm has taken this feeder's material and lifted away: it is empty. */
static void feeder_emptied(Ss1Feeder *f)
{
    set_mod(f->slot, MOD_OFF);
    f->phase = FD_IDLE;
    timer_stop(&f->t);
}

/* Recharged and now producing again. */
static void feeder_producing(Ss1Feeder *f)
{
    set_mod(f->slot, MOD_RUNNING);
    f->phase = FD_PRODUCING;
    timer_start(&f->t, SS1_INITIAL_PROCESS_MS);
}

/* Go green now — the batch is ready for the arm. */
static void feeder_ready(Ss1Feeder *f)
{
    set_mod(f->slot, MOD_READY);
    f->phase = FD_READY;
}

/* Change 13.1: a held feeder (the gravimetric) has finished producing and is
 * standing blue; this is the cue — the macro's material going into the blender —
 * that lets it go green. A no-op unless it is actually holding, so it is safe to
 * call on every macro dump. */
static void feeder_release(Ss1Feeder *f)
{
    if (f->phase == FD_HELD) {
        feeder_ready(f);
    }
}

/* Change 11.4: the recharge starts the moment the blender begins working on
 * what this feeder just supplied, which is exactly when the feeder itself is
 * free to start refilling. Nothing waits for it — it plays on the strip while
 * the arm gets on with the next carry. */
static void feeder_recharge(Ss1Feeder *f)
{
    /* The recharge animator is shared, because the two spans overlap. By the
     * asserts above a previous recharge is always long finished by now, but if
     * a retune ever broke that, taking the animator away from a feeder still
     * waiting on it would strand that feeder dark for the rest of the run.
     * Promote it instead: losing the tail of an animation is a blemish, a
     * module that never comes back is a dead demo. */
    Ss1Feeder *other = (f == &s_macro) ? &s_grav : &s_macro;
    if (other->phase == FD_CHARGING) {
        feeder_producing(other);
    }

    ss1_charge_start(&s_charge, f->span_a, f->span_b, f->slot, f->fill_ms);
    f->phase = FD_CHARGING;
}

static void feeder_tick(Ss1Feeder *f)
{
    switch (f->phase) {
    case FD_CHARGING:
        /* The recharge is shared, so check it is still ours before claiming
         * it finished — a later feeder taking the animator over is not this
         * feeder's completion. The asserts make that impossible by timing, but
         * the check costs nothing and keeps the failure mode obvious. */
        if (ss1_charge_done(&s_charge) && s_charge.owner == f->slot) {
            feeder_producing(f);                   /* recharged, producing */
        }
        break;

    case FD_PRODUCING:
        if (timer_expired(&f->t)) {
            /* Change 13.1: a holding feeder stands blue with its batch made
             * until released; a non-holding one goes green straight away. */
            if (f->holds) {
                f->phase = FD_HELD;                /* blue, awaiting release  */
            } else {
                feeder_ready(f);                   /* green, arm may collect  */
            }
        }
        break;

    default:
        break;
    }
}

/* ======================================================================
 * Packaging actor
 *
 * Change 13.3: ONE batch is the whole lot. Packaging used to sit blue counting
 * batches and only ship when it had two — that is what made the operator wait
 * three recharges to see a single exit strip. It now takes the first batch the
 * blender hands it, finishes it, and puts it out on 17..21.
 *
 * It is defined ahead of the blender because the blender calls into it: the
 * hand-off runs one way only, and keeping callee before caller is what lets
 * this file get away with no forward declarations at all.
 * ==================================================================== */

static void packaging_reset(void)
{
    s_pack = PK_IDLE;
    timer_stop(&s_pack_timer);
}

/* The blender's batch has finished crossing on 14..16 and is now in. Change
 * 12.2: a module that has just been handed material is working, so it goes blue
 * immediately rather than blanking first. */
static void packaging_receive(void)
{
    set_mod(SS1_SLOT_PACKAGING, MOD_RUNNING);
    s_pack = PK_FINISHING;
    timer_start(&s_pack_timer, SS1_PACKAGING_FINISH_MS);
}

static void packaging_tick(void)
{
    switch (s_pack) {
    case PK_FINISHING:
        if (timer_expired(&s_pack_timer)) {
            set_mod(SS1_SLOT_PACKAGING, MOD_READY);
            ss1_flow_start(&s_flow_out, SS1_OUT_A, SS1_OUT_B,
                           SS1_FLOW_OUTPUT_PASSES);
            s_pack = PK_SHIPPING;
        }
        break;

    case PK_SHIPPING:
        if (ss1_flow_done(&s_flow_out)) {
            /* The lot is out of the door, and change 13.2 makes THIS the cycle
             * boundary — not a reset, not a blank board, not anything the arm
             * takes part in. The arm is already two carries into the next lot's
             * material; all that happens here is that the counter ticks and
             * packaging goes dark until the next batch reaches it.
             *
             * LOT:DONE is the only thing on the wire that marks the boundary
             * (there is no CYCLE message and adding one would mean a protocol
             * change on both sides for a number the app can already derive), so
             * the app counts these to drive its cycle display. Keep the emit
             * and the increment adjacent: they are the same event. */
            ble_lot_done();
            s_lots++;
            if (s_cycle < SS1_CYCLES) {
                s_cycle++;              /* lot 1 done -> "cycle 2" on the app */
            }
            set_mod(SS1_SLOT_PACKAGING, MOD_OFF);
            s_pack = PK_IDLE;
        }
        break;

    default:
        break;
    }
}

/* ======================================================================
 * Blender actor
 * ==================================================================== */

static void blender_reset(void)
{
    s_blender           = BL_EMPTY;
    s_blender_materials = 0u;
    timer_stop(&s_blender_timer);
}

/* The pour has finished and that material is now actually in — not when the arm
 * arrives and starts pouring, but when it has finished. Change 16.3: a module
 * turns blue the moment DISPENSING IS DONE, not the moment it begins.
 *
 * Change 16.4, the stale period. The blender used to go blue on the first pour
 * and then stand there blue until the second one arrived a whole carry later:
 * 96 seconds of a lit module doing nothing, which is exactly the dead air the
 * requirement calls out. Blue has to mean "this module is working", so while it
 * holds one material and waits for the other it now stays DARK, and it lights
 * only when it has both and is genuinely mixing. The blue window is therefore
 * SS1_BLEND_RUN_MS and nothing else. */
static void blender_material_in(void)
{
    if (s_blender_materials < SS1_MATERIALS_PER_BATCH) {
        s_blender_materials++;
    } else {
        return;                      /* a full blender cannot take more */
    }

    /* Change 17.4: every pour is acknowledged in blue, so the operator can see
     * the material land. What differs is what follows. On the FIRST it is only
     * an acknowledgement -- there is nothing to mix yet, so it goes dark again
     * after SS1_RECEIVE_ACK_MS and waits. On the SECOND the blue simply stays
     * on, because now it really is working, and it runs until the blend is
     * done. Same colour, two different meanings, and the difference is exactly
     * whether the module has anything to do. */
    set_mod(SS1_SLOT_BLENDER, MOD_RUNNING);

    if (s_blender_materials >= SS1_MATERIALS_PER_BATCH) {
        s_blender = BL_BLENDING;
        timer_start(&s_blender_timer, SS1_BLEND_RUN_MS);
    } else {
        s_blender = BL_ACK;
        timer_start(&s_blender_timer, SS1_RECEIVE_ACK_MS);
    }
}

static void blender_tick(void)
{
    switch (s_blender) {
    /* The acknowledgement is over; there is still only half a batch in there,
     * so it goes dark rather than sitting lit for the carry it has to wait. */
    case BL_ACK:
        if (timer_expired(&s_blender_timer)) {
            set_mod(SS1_SLOT_BLENDER, MOD_OFF);
            s_blender = BL_HOLDING;
        }
        break;

    case BL_BLENDING:
        if (timer_expired(&s_blender_timer)) {
            /* Change 12.2: blue while working, then green when it is done and
             * the batch is ready to ship. */
            set_mod(SS1_SLOT_BLENDER, MOD_READY);
            ss1_flow_start(&s_flow_xfer, SS1_XFER_A, SS1_XFER_B,
                           SS1_FLOW_XFER_PASSES);
            s_blender = BL_SHIPPING;
        }
        break;

    case BL_SHIPPING:
        if (ss1_flow_done(&s_flow_xfer)) {
            /* The batch has physically left; the blender is dark and free for
             * the arm's next delivery, which is already on its way. Change
             * 13.3: that batch IS a lot, so packaging starts on it at once
             * rather than banking it and waiting for a second. The assert on
             * SS1_LOT_TAIL_MS is what guarantees packaging is free to take it. */
            set_mod(SS1_SLOT_BLENDER, MOD_OFF);
            s_blender           = BL_EMPTY;
            s_blender_materials = 0u;
            packaging_receive();
        }
        break;

    default:
        break;
    }
}

/* ======================================================================
 * Robot chain
 * ==================================================================== */

static void ss1_reset_transient(void)
{
    timer_stop(&s_timer);
    ss1_charge_reset(&s_charge);
    ss1_flow_reset(&s_flow_xfer);
    ss1_flow_reset(&s_flow_out);
    /* Change 13.1: the macro greens as soon as it has produced; the
     * gravimetric holds blue until the macro's material goes into the blender.
     * That asymmetry is the whole of 13.1, and it lives here in one bool. */
    feeder_init(&s_macro, SS1_SLOT_MACRO, SS1_MACRO_A, SS1_MACRO_B,
                SS1_CHARGE_FILL_MACRO_MS, false);
    feeder_init(&s_grav, SS1_SLOT_GRAVIMETRIC, SS1_GRAV_A, SS1_GRAV_B,
                SS1_CHARGE_FILL_GRAV_MS, true);
    blender_reset();
    packaging_reset();
    s_carry = 0u;
    seq_leds_strip_clear();
}

/* Odd carries collect the macro, even ones the gravimetric. */
static Ss1Feeder *carry_source(void)
{
    return ((s_carry & 1u) != 0u) ? &s_macro : &s_grav;
}

/* Run a step's entry actions and arm the chain timer. A step with no duration
 * of its own disarms it instead: either it is instantaneous, or it waits on an
 * actor rather than on the clock, and leaving a stale armed timer behind would
 * be a trap for whoever adds a timeout here later. */
static void enter_step(Ss1Step s)
{
    uint32_t ms = 0u;

    s_step = s;

    switch (s) {
    /* Both feeders run at once — they always did, and it is the one piece of
     * the old design that was already concurrent. They are started through
     * feeder_producing() rather than by poking the phase, so each gets its own
     * timer and the chain does not have to hold a duplicate copy of the produce
     * time. Change 13.1: only the macro will go green off the back of this; the
     * gravimetric ends up FD_HELD, blue, waiting for the first macro dump.
     *
     * This step is entered ONCE per run. The old S_FEEDERS_READY, which forced
     * both modules green together, was exactly what 13.1 forbids, and it is
     * gone rather than patched. */
    case S_INIT:
        ss1_reset_transient();
        set_mod(SS1_SLOT_BLENDER,   MOD_OFF);
        set_mod(SS1_SLOT_PACKAGING, MOD_OFF);
        feeder_producing(&s_macro);
        feeder_producing(&s_grav);
        s_carry = 1u;
        break;

    /* One MOVE cue covers the whole carry — travel, grip, the 20 s stood
     * collecting, the crossing, the 20 s stood pouring and the return home are
     * all one animation in the Android app. Do not add a second cue: with no
     * ARRIVED coming back there is nothing to synchronise against.
     *
     * What this side CAN do is peg its own colour changes to the same
     * milestones the arm is animating to, which is what the four steps below
     * are. This one ends the instant the arm stops collecting. */
    case S_CARRY_MOVE:
        ble_move(SLOT1(carry_source()->slot), SLOT1(SS1_SLOT_BLENDER));
        ms = SS1_T_COLLECT_END;
        break;

    /* Change 12.2: the source goes green -> off the moment its material has
     * been collected, which is the moment the arm lifts away with it. */
    case S_CARRY_CROSS:
        feeder_emptied(carry_source());
        ms = SS1_T_CROSS_TO_DEST;
        break;

    /* The arm has arrived and is pouring. Change 11.4: this is the moment the
     * source it came from starts recharging, underneath the arm's next carry
     * rather than stopping it.
     *
     * The blender is deliberately NOT touched here. Change 16.3 moved its
     * colour to the END of the pour (S_CARRY_HOME), because blue is supposed to
     * mean the module is working and a module being poured into has not started
     * working yet. */
    case S_CARRY_DUMP:
        /* A recharge exists so this feeder is green again for the arm's NEXT
         * visit, which is SS1_CARRIES_PER_CYCLE carries away. After the last
         * one there is no next visit, so the feeders wind down dark rather than
         * refilling a batch nobody will collect — which without this test left
         * the macro standing green and the gravimetric standing blue holding an
         * undeliverable batch for the ~100 s the final lot takes to ship, and
         * then blanking. Change 13.2 removed the per-cycle reset that used to
         * hide that ending. */
        if (s_carry + SS1_CARRIES_PER_CYCLE <= SS1_TOTAL_CARRIES) {
            feeder_recharge(carry_source());
        }
        /* Change 13.1: "module 3 turns green once the robot dumps module 2's
         * material into module 5" — this instant, and only on the macro's
         * carries. feeder_release() is a no-op on anything not actually
         * holding, but the test is written out because the macro dump IS the
         * requirement and a bare unconditional call would hide it. */
        if (carry_source() == &s_macro) {
            feeder_release(&s_grav);
        }
        ms = SS1_T_DUMP_HOLD;
        break;

    /* The pour is finished — changes 16.3/16.4 make this the instant the blender
     * reacts, not the start of the pour: the second material landing here is
     * what turns it blue and starts the blend. The arm then retracts and goes
     * home; the wait includes a beat stood at rail centre, so the next MOVE
     * never lands while the previous animation is still playing. */
    case S_CARRY_HOME:
        blender_material_in();
        ms = SS1_T_GO_HOME;
        break;

    /* Every carry of every cycle is done and the arm is parked for good. The
     * plant is still finishing the last lot underneath — this is the only place
     * the chain ever waits on it, and only because there is nothing left for the
     * arm to carry. Change 13.2 deleted the old per-cycle S_PLANT_WAIT and
     * S_CYCLE_GAP: waiting for the plant and blanking the board at every cycle
     * boundary is precisely the stall and the reset the requirement removes. */
    case S_ARM_DONE:
        s_carry = 0u;
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
    s_lots   = 0u;
    enter_step(S_INIT);
}

void solid_seq1_stop(void)
{
    ss1_reset_transient();               /* while seq_leds still owns the strip */
    module_all_off();
    s_status = SS1_IDLE;
    s_cycle  = 0u;
    s_lots   = 0u;
    s_step   = S_IDLE;
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

    /* Everything that is not the arm runs here, every pass, regardless of what
     * step the robot chain is on. This is what "simultaneously" means in this
     * file: the plant is never waiting for its turn. */
    ss1_charge_tick(&s_charge);
    ss1_flow_tick(&s_flow_xfer);
    ss1_flow_tick(&s_flow_out);
    feeder_tick(&s_macro);
    feeder_tick(&s_grav);
    blender_tick();
    packaging_tick();

    switch (s_step) {
    /* The arm leaves for the first carry when the macro is actually green, not
     * when a clock says it ought to be — so the produce time can be retuned in
     * config.h without the first MOVE drifting off the module colour. */
    case S_INIT:
        advance = (s_macro.phase == FD_READY);
        break;

    /* The one place the arm waits on the plant, and only because it has nothing
     * left to carry at all. Every cycle before this one closed underneath it. */
    case S_ARM_DONE:
        advance = (s_lots >= SS1_CYCLES);
        break;

    case S_IDLE:
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

    switch (s_step) {
    case S_INIT:
        enter_step(S_CARRY_MOVE);
        break;

    case S_CARRY_MOVE:
        enter_step(S_CARRY_CROSS);
        break;

    case S_CARRY_CROSS:
        enter_step(S_CARRY_DUMP);
        break;

    case S_CARRY_DUMP:
        enter_step(S_CARRY_HOME);
        break;

    /* Straight into the next carry with no gap at all, and — change 13.2 — that
     * now includes the carries that cross a cycle boundary: the arm counts
     * SS1_TOTAL_CARRIES, not carries-per-cycle, and never returns to S_INIT.
     * The plant catches up underneath, closing one lot per two carries. Only
     * when the very last carry is done does the arm stand down. */
    case S_CARRY_HOME:
        if (s_carry < SS1_TOTAL_CARRIES) {
            s_carry++;
            enter_step(S_CARRY_MOVE);
        } else {
            enter_step(S_ARM_DONE);
        }
        break;

    case S_ARM_DONE:
        enter_step(S_DONE);
        break;

    default:
        break;
    }
}

Ss1Status solid_seq1_state(void) { return s_status; }
uint8_t   solid_seq1_cycle(void) { return s_cycle; }

const char *solid_seq1_step_name(void)
{
    return (s_step < S_STEP_COUNT) ? SS1_STEP_NAME[s_step] : "?";
}
