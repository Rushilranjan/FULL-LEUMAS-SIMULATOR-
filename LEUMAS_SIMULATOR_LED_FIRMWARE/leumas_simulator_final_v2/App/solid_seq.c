#include "solid_seq.h"
#include "config.h"
#include "module.h"
#include "seq_leds.h"
#include "ble.h"
#include "timebase.h"

/* 0-based slot index -> the 1-based number the operator and the robot use. */
#define SLOT1(idx)       ((uint8_t)((idx) + 1u))

/* Ceilings for the per-run arrays. Raising one costs a few bytes of BSS and
 * nothing else; the programs below are what actually decide the sizes. */
#define SS1_MAX_FEEDERS   3u
#define SS1_MAX_BLENDERS  2u
#define SS1_MAX_CARRIES   4u   /* per cycle */

/* ---- strip spans ---------------------------------------------------------
 * Every span comes straight out of SLOT_LEDS, so the requirement's LED indices
 * and the rig's pixel map are the same numbers and can never drift apart. A
 * feeder fills its own status run; a blender's hand-off runs from its own last
 * pixel to its destination's, which is how the cumulative map encodes "material
 * moved further along the line".
 *
 * Change 22: the hand-off used to start one pixel PAST the source, so blender 1
 * swept 12..16 while its own fill ended at 11 -- the material appeared to start
 * one pixel clear of the module it was leaving, with a dark gap between them.
 * Starting on the source's own end pixel makes the sweep visibly leave the
 * module. Blender 1 now runs 11..16 and blender 2 runs 13..16. */
#define SS1_SPAN_A(slot)   (SLOT_LEDS[slot].st_base)
#define SS1_SPAN_B(slot)   (SLOT_LEDS[slot].st_end)
#define SS1_FLOW_A(from)   (SLOT_LEDS[from].st_end)
#define SS1_FLOW_B(to)     (SLOT_LEDS[to].st_end)

/* The exit run a lot leaves on. Program data since change 23: the solid line
 * ships out on 17..21 and the liquid line on 39..43, so this cannot be a
 * constant any more. */

/* ---- carry timing --------------------------------------------------------
 * A carry is one MOVE cue and then a fixed sequence of moments: the arm
 * arrives, grips, collects, lifts away, crosses to its destination, pours, and
 * goes home. The board's job is to change each module's colour at the moment
 * the arm is actually in front of it.
 *
 * The SS1_ARM_* milestones in config.h are offsets from the MOVE cue; the four
 * carry steps need the gaps BETWEEN them, which is all these are. Change a
 * phase in config.h and every step retimes itself.
 *
 * Change 17.3: the source goes dark SS1_COLLECT_OFF_LEAD_MS before the arm's
 * collect-end milestone, so the first two steps trade that time between them.
 * Their SUM is untouched, which is what keeps the dump landing exactly on
 * SS1_ARM_AT_DEST_MS and the whole carry tiling to SS1_T_CARRY. */
#define SS1_T_COLLECT_END       ( SS1_ARM_COLLECT_END_MS                   \
                                - SS1_COLLECT_OFF_LEAD_MS )
#define SS1_T_CROSS_TO_DEST     ( SS1_ARM_AT_DEST_MS                       \
                                - SS1_ARM_COLLECT_END_MS                   \
                                + SS1_COLLECT_OFF_LEAD_MS )
#define SS1_T_DUMP_HOLD         ( SS1_ARM_DUMP_END_MS                      \
                                - SS1_ARM_AT_DEST_MS )
#define SS1_T_GO_HOME           ( SS1_ARM_TOTAL_MS - SS1_ARM_DUMP_END_MS   \
                                + SS1_ARM_HOME_HOLD_MS )

/* One whole carry, cue to cue. Nothing in the plant is allowed to make the arm
 * wait, so this is also the fixed spacing between consecutive MOVE cues. */
#define SS1_T_CARRY             ( SS1_T_COLLECT_END + SS1_T_CROSS_TO_DEST  \
                                + SS1_T_DUMP_HOLD + SS1_T_GO_HOME )

/* ---- compile-time guards -------------------------------------------------
 * SLOT_LEDS is a const array, not a constant expression, so the guards below
 * use the span lengths that map currently yields. Keep them in step with
 * SLOT_LEDS if the rig is re-measured; seq_leds_print_map() ("seq map") is the
 * check by eye. The exit span needs no such care: SOLID_EXIT_A/B are literals. */
#define SS1_MACRO1_SPAN_LEN  2u
#define SS1_MACRO_SPAN_LEN   6u
#define SS1_GRAV_SPAN_LEN   10u

#define SS1_MACRO1_CHARGE_MS SS1_CHARGE_MS(SS1_MACRO1_SPAN_LEN, SS1_CHARGE_FILL_MACRO1_MS)
#define SS1_MACRO_CHARGE_MS  SS1_CHARGE_MS(SS1_MACRO_SPAN_LEN, SS1_CHARGE_FILL_MACRO_MS)
#define SS1_GRAV_CHARGE_MS   SS1_CHARGE_MS(SS1_GRAV_SPAN_LEN,  SS1_CHARGE_FILL_GRAV_MS)

/* One flow pass lights its span a pixel at a time, holds the full span for one
 * more step and then blanks; the blank IS the gap before the next pass. So a
 * pass costs len*STEP + BLANK, and the animation is that once per pass. */
#define SS1_FLOW_MS(len, passes)                                              \
    ( (passes) * ( (len) * SS1_FLOW_STEP_MS + SS1_FLOW_BLANK_MS ) )

/* The board and the Android arm are two open-loop clocks with no handshake
 * between them, so these are the numbers that must be identical on both sides.
 * Editing a phase in config.h without editing the Kotlin (or vice versa) fails
 * the build here rather than drifting silently on the demo table. */
_Static_assert(SS1_ARM_TOTAL_MS == 40000u,
               "SS1_ARM_* must sum to Timings.DEFAULT_ROBOT_ANIMATION_MS in the app");
_Static_assert(SS1_T_CARRY == 42000u,
               "SS1_T_CARRY must equal Timings.CARRY_MS in the Android app");

/* The four carry steps must tile the whole carry exactly. If they did not, a
 * module colour would land at the wrong point of the arm animation — which is
 * the fault this whole design exists to prevent. */
_Static_assert(SS1_T_CARRY == SS1_ARM_TOTAL_MS + SS1_ARM_HOME_HOLD_MS,
               "the carry steps must tile the arm animation plus the home hold");

/* Change 17.3: the lead cannot eat the run-up, or the source would go dark
 * before the arm had finished collecting from it. */
_Static_assert(SS1_COLLECT_OFF_LEAD_MS < SS1_ARM_PICK_MS,
               "the source must not go dark before the arm has finished collecting");

/* There is ONE recharge animator because every feeder's status run starts at
 * the line's base — macro 1's 0..1 is a prefix of macro 2's 0..5, which is a
 * prefix of the gravimetric's 0..9. They are physically incapable of playing at
 * the same time on this LED map, whatever a sequence would like. What keeps
 * them apart is that each is triggered by a carry's dump, and consecutive
 * carries are SS1_T_CARRY apart, so every recharge must fit inside one carry. */
_Static_assert(SS1_MACRO1_CHARGE_MS < SS1_T_CARRY,
               "macro 1's recharge must finish within one carry");
_Static_assert(SS1_MACRO_CHARGE_MS < SS1_T_CARRY,
               "macro 2's recharge must finish within one carry");
_Static_assert(SS1_GRAV_CHARGE_MS < SS1_T_CARRY,
               "the gravimetric recharge must finish within one carry");

/* Requirement: the gravimetric span fills faster per pixel but takes longer
 * overall. Fast enough not to drag over 10 pixels, long enough that the bigger
 * module still reads as the bigger job. */
_Static_assert(SS1_CHARGE_FILL_GRAV_MS < SS1_CHARGE_FILL_MACRO_MS,
               "the gravimetric recharge must fill faster per pixel");
_Static_assert(SS1_GRAV_CHARGE_MS > SS1_MACRO_CHARGE_MS,
               "the gravimetric recharge must still take slightly longer overall");

/* Changes 13.1 / 18.5: no feeder greens on its own. Each stands blue holding its
 * batch until the PREVIOUS carry's pour finishes. In the worst case — the
 * gravimetric in sequence 2, collected twice a cycle — that cue lands one whole
 * carry after the feeder started its own recharge, plus the run from the arm
 * arriving to pour-complete.
 *
 * Miss this and the release arrives while the module is still producing,
 * feeder_release() no-ops, and that feeder never goes green at all: the arm
 * collects from a blue module and the batch is invisible. A silent visual
 * failure no bench test would catch, which is exactly what an assert is for. */
#define SS1_RELEASE_WINDOW_MS  ( SS1_T_CARRY + SS1_ARM_DUMP_END_MS              \
                               - SS1_ARM_AT_DEST_MS )

_Static_assert(SS1_GRAV_CHARGE_MS + SS1_INITIAL_PROCESS_MS < SS1_RELEASE_WINDOW_MS,
               "the gravimetric must be holding before the release cue arrives");
_Static_assert(SS1_MACRO_CHARGE_MS + SS1_INITIAL_PROCESS_MS < SS1_RELEASE_WINDOW_MS,
               "macro 2 must be holding before the release cue arrives");
_Static_assert(SS1_MACRO1_CHARGE_MS + SS1_INITIAL_PROCESS_MS < SS1_RELEASE_WINDOW_MS,
               "macro 1 must be holding before the release cue arrives");

/* A released feeder must be green before the arm is stood in front of it. The
 * release lands at the previous carry's pour-complete and the arm arrives
 * SS1_ARM_TO_SOURCE_MS into the next one. */
_Static_assert(SS1_ARM_DUMP_END_MS < SS1_T_CARRY + SS1_ARM_TO_SOURCE_MS,
               "a feeder must be released before the arm arrives to collect it");

/* Change 17.4: the acknowledgement a module shows for one material must be over
 * long before the next one lands, or it would still be lit from the wrong
 * thing when it starts actually working. */
_Static_assert(SS1_RECEIVE_ACK_MS < SS1_T_CARRY,
               "the receive acknowledgement must end before the next material lands");

/* ======================================================================
 * Animators
 *
 * Both write the strip ONLY through seq_leds_strip_set(), which is a flag per
 * pixel; seq_leds redraws the whole frame from those flags, so an animation
 * can never leave a stray pixel behind. Both keep an ABSOLUTE deadline in
 * millis(), compared the wrap-safe way.
 *
 * seq_leds_strip_set() has no notion of ownership: put two concurrent animators
 * on overlapping spans and they will scribble over each other with no warning
 * at all. That is why there is one recharge animator, and why the blenders
 * queue for a single hand-off animator (their spans overlap too — sequence 2's
 * blender 1 runs 12..16 and blender 2 runs 14..16).
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
 * and stay on. One stage, repeated:
 *
 *   0 | 0,1 | 0,1,2 | ... | blank      <- one pass
 *   one pixel per fill step, each staying lit, then the whole span blanks and
 *   the pass runs again. SS1_CHARGE_REPEATS passes in all.
 *
 * Then the span goes DARK and stays dark. The animation ends on the blank that
 * clears the last pass, so a short span is never still lit when a longer,
 * overlapping one starts. A recharged module announces itself by its own LED
 * going blue; the strip's job is finished. */
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
    uint16_t step_ms;   /* per-pixel dwell; every feeder differs             */
    uint32_t due;
} Ss1Charge;

/* ---- material flow -------------------------------------------------------
 * The span fills one pixel at a time, holds full for one step, blanks, and
 * repeats. The all-dark hold IS the gap between passes — there is deliberately
 * no third timing knob for it. */
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
 * The programs
 *
 * A sequence is this table and nothing else. Spans are NOT stored: they are
 * derived from the slot at init, so the rig's pixel map stays the single place
 * the geometry lives and a re-measure cannot leave a stale copy behind here.
 * ==================================================================== */

typedef struct {
    uint8_t  slot;
    uint16_t fill_ms;      /* per-pixel recharge dwell                       */
} Ss1FeederSpec;

typedef struct {
    uint8_t slot;
    uint8_t needs;         /* materials per batch                           */
} Ss1BlenderSpec;

typedef struct {
    uint8_t feeder;        /* index into the program's feeders              */
    uint8_t blender;       /* index into the program's blenders             */
} Ss1CarrySpec;

typedef struct {
    uint8_t               number;
    const Ss1FeederSpec  *feeders;
    uint8_t               feeder_count;
    const Ss1BlenderSpec *blenders;
    uint8_t               blender_count;
    const Ss1CarrySpec   *carries;
    uint8_t               carries_per_cycle;
    uint8_t               pack_slot;
    uint8_t               pack_needs;   /* blender batches per lot           */
    uint8_t               out_a, out_b; /* the exit run a lot leaves on      */
} Ss1Program;

/* ---- sequence 1: macro + gravimetric -> blender 2 -> packaging ----------- */

static const Ss1FeederSpec SEQ1_FEEDERS[] = {
    { SS1_SLOT_MACRO,       SS1_CHARGE_FILL_MACRO_MS },
    { SS1_SLOT_GRAVIMETRIC, SS1_CHARGE_FILL_GRAV_MS  },
};
static const Ss1BlenderSpec SEQ1_BLENDERS[] = {
    { SS1_SLOT_BLENDER, 2u },
};
static const Ss1CarrySpec SEQ1_CARRIES[] = {
    { 0u, 0u },   /* macro       -> blender */
    { 1u, 0u },   /* gravimetric -> blender */
};

/* ---- sequence 2: two blend lines sharing one gravimetric -----------------
 * Straight off the original design: macro 1 and the gravimetric make blender
 * 1's batch, macro 2 and the gravimetric make blender 2's, and packaging waits
 * for both before it ships a lot. The gravimetric is therefore collected TWICE
 * a cycle and must recharge in between — the tightest timing in the demo, and
 * what SS1_RELEASE_WINDOW_MS above is really guarding.
 *
 * The carry order matters: it interleaves the two lines rather than finishing
 * one and then the other, so the gravimetric's two collections are as far apart
 * as the cycle allows. */
static const Ss1FeederSpec SEQ2_FEEDERS[] = {
    { SS1_SLOT_MACRO1,      SS1_CHARGE_FILL_MACRO1_MS },
    { SS1_SLOT_MACRO,       SS1_CHARGE_FILL_MACRO_MS  },
    { SS1_SLOT_GRAVIMETRIC, SS1_CHARGE_FILL_GRAV_MS   },
};
static const Ss1BlenderSpec SEQ2_BLENDERS[] = {
    { SS1_SLOT_BLENDER1, 2u },
    { SS1_SLOT_BLENDER,  2u },
};
static const Ss1CarrySpec SEQ2_CARRIES[] = {
    { 0u, 0u },   /* macro 1     -> blender 1 */
    { 2u, 0u },   /* gravimetric -> blender 1 */
    { 1u, 1u },   /* macro 2     -> blender 2 */
    { 2u, 1u },   /* gravimetric -> blender 2 */
};

/* ---- sequence 3: the liquid line ----------------------------------------
 * One macro, one vessel, one bottling module, out on the liquid exit run.
 *
 * ONE carry per cycle, which makes this the only sequence where a feeder is
 * collected on EVERY carry -- and that is what exposed two gaps the solid
 * sequences never could. Its release cue for the next carry arrives while it is
 * still refilling for that same carry (hence Ss1Feeder.release_pending), and it
 * has one carry rather than two to recharge and produce in (hence the recharge
 * moving to the moment the arm lifts away, change 23). */
static const Ss1FeederSpec SEQ3_FEEDERS[] = {
    { SS1_SLOT_LMACRO1, SS1_CHARGE_FILL_LIQUID_MS },
};
static const Ss1BlenderSpec SEQ3_BLENDERS[] = {
    { SS1_SLOT_VESSEL1, 1u },   /* one dose is a batch */
};
static const Ss1CarrySpec SEQ3_CARRIES[] = {
    { 0u, 0u },   /* liquid macro -> vessel */
};

static const Ss1Program SS_PROGRAMS[SOLID_SEQ_COUNT] = {
    {
        .number = 1u,
        .feeders = SEQ1_FEEDERS,   .feeder_count  = 2u,
        .blenders = SEQ1_BLENDERS, .blender_count = 1u,
        .carries = SEQ1_CARRIES,   .carries_per_cycle = 2u,
        .pack_slot = SS1_SLOT_PACKAGING, .pack_needs = 1u,
        .out_a = SOLID_EXIT_A, .out_b = SOLID_EXIT_B,
    },
    {
        .number = 2u,
        .feeders = SEQ2_FEEDERS,   .feeder_count  = 3u,
        .blenders = SEQ2_BLENDERS, .blender_count = 2u,
        .carries = SEQ2_CARRIES,   .carries_per_cycle = 4u,
        .pack_slot = SS1_SLOT_PACKAGING, .pack_needs = 2u,
        .out_a = SOLID_EXIT_A, .out_b = SOLID_EXIT_B,
    },
    {
        .number = 3u,
        .feeders = SEQ3_FEEDERS,   .feeder_count  = 1u,
        .blenders = SEQ3_BLENDERS, .blender_count = 1u,
        .carries = SEQ3_CARRIES,   .carries_per_cycle = 1u,
        .pack_slot = SS1_SLOT_BOTTLING, .pack_needs = 1u,
        .out_a = LIQUID_EXIT_A, .out_b = LIQUID_EXIT_B,
    },
};

/* ======================================================================
 * Actors
 * ==================================================================== */

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
    /* Change 23: its release cue arrived while it was still recharging or
     * producing, so it goes green the moment it is ready instead of losing the
     * cue. A sequence with ONE feeder collects it every carry, so the cue for
     * the next carry always lands before the feeder has finished refilling for
     * it -- see feeder_release(). */
    bool      release_pending;
    SoftTimer t;
} Ss1Feeder;

typedef enum {
    BL_EMPTY = 0,  /* dark, waiting for the first material of a batch        */
    BL_ACK,        /* blue, acknowledging a pour that does not complete it   */
    BL_HOLDING,    /* DARK, holding material, waiting for the rest of it     */
    BL_BLENDING,   /* blue, all materials in, actually mixing               */
    BL_READY,      /* green, batch made, queued for the hand-off strip       */
    BL_SHIPPING    /* green, its batch crossing to packaging                 */
} Ss1BlenderPhase;

typedef struct {
    Ss1BlenderPhase phase;
    uint8_t   slot;
    uint8_t   needs;
    uint8_t   materials;
    uint8_t   flow_a, flow_b;   /* its hand-off span, derived at init        */
    SoftTimer t;
} Ss1Blender;

typedef enum {
    PK_IDLE = 0,   /* dark, nothing received                                 */
    PK_ACK,        /* blue, acknowledging a batch that does not finish a lot */
    PK_HOLDING,    /* DARK, holding batches, waiting for the rest            */
    PK_FINISHING,  /* blue, every batch in, finishing the lot                */
    PK_SHIPPING    /* green, the lot leaving on the exit run                 */
} Ss1PackPhase;

/* ======================================================================
 * Run state
 *
 * Every actor carries its own phase and its own timer, so there is no shared
 * mutable state between them at all and nothing to reset in the wrong order.
 * ==================================================================== */

typedef enum {
    S_IDLE = 0,
    S_PRIME,         /* change 18.2: strip fills toward each feeder in turn   */
    S_INIT,          /* all feeders producing; the first carry's source greens */
    S_CARRY_MOVE,    /* MOVE cue out; arm travelling, then stood collecting   */
    S_CARRY_CROSS,   /* source dark — the arm has lifted away and is crossing */
    S_CARRY_DUMP,    /* the arm is stood at its destination pouring           */
    S_CARRY_HOME,    /* material fully in; arm retracting and going home      */
    S_ARM_DONE,      /* every carry done; waiting on the last lot to ship     */
    S_DONE,          /* all cycles complete; parked                          */
    S_STEP_COUNT
} Ss1Step;

static const char *const SS1_STEP_NAME[S_STEP_COUNT] = {
    "IDLE",       "PRIME",       "INIT",       "CARRY_MOVE",
    "CARRY_CROSS", "CARRY_DUMP", "CARRY_HOME", "ARM_DONE",
    "COMPLETE"
};

static const Ss1Program *s_prog;        /* the running program, NULL when idle */
static uint8_t   s_selected = 1u;       /* what the button has chosen          */

static Ss1Status s_status;
static Ss1Step   s_step;
static uint8_t   s_cycle;       /* 1..SS1_CYCLES, 0 when idle                */
static uint8_t   s_lots;        /* lots shipped; change 13.2 drives s_cycle  */
static uint8_t   s_carry;       /* 1..total carries, 0 outside one           */
static uint8_t   s_prime;       /* feeder index being primed                 */
static SoftTimer s_timer;       /* the ROBOT chain's timer, nothing else     */

static Ss1Feeder  s_feeder[SS1_MAX_FEEDERS];
static Ss1Blender s_blender[SS1_MAX_BLENDERS];
static Ss1Charge  s_charge;     /* shared: every feeder's span overlaps      */

/* Shared blender -> packaging animator. The blenders' spans overlap, so only
 * one may play at a time and the others queue in BL_READY. NONE when free. */
#define SS1_NO_OWNER  0xFFu
static Ss1Flow s_flow_xfer;
static uint8_t s_flow_xfer_owner = SS1_NO_OWNER;

static Ss1PackPhase s_pack;
static uint8_t      s_pack_batches;
static SoftTimer    s_pack_timer;
static Ss1Flow      s_flow_out;       /* packaging -> exit run               */

static uint8_t total_carries(void)
{
    return (uint8_t)(SS1_CYCLES * s_prog->carries_per_cycle);
}

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

static void feeder_init(Ss1Feeder *f, const Ss1FeederSpec *spec)
{
    f->phase   = FD_IDLE;
    f->slot    = spec->slot;
    f->span_a  = SS1_SPAN_A(spec->slot);
    f->span_b  = SS1_SPAN_B(spec->slot);
    f->fill_ms = spec->fill_ms;
    f->release_pending = false;
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

/* Changes 13.1 / 18.5: a feeder that has finished producing stands blue holding
 * its batch. This is the cue — the previous carry's material finishing its pour
 * — that lets it go green. A no-op unless it is actually holding, so the first
 * carry of a run, whose source is released by S_INIT instead, needs no special
 * case. */
static void feeder_release(Ss1Feeder *f)
{
    if (f->phase == FD_HELD) {
        feeder_ready(f);
        return;
    }
    /* Change 23: not ready yet, so remember the cue instead of dropping it.
     *
     * The solid sequences never needed this -- a feeder there is collected every
     * second or fourth carry, so it has always finished producing by the time
     * its cue arrives. Sequence 3 has one feeder and one carry per cycle, so the
     * cue for the next carry lands while it is still refilling FOR that carry,
     * and a one-shot release would be lost: the module would sit blue for the
     * rest of the run and the arm would collect from it dark. */
    f->release_pending = true;
}

/* Change 11.4: the recharge starts the moment the material this feeder supplied
 * lands in its blender, which is exactly when the feeder itself is free to
 * refill. Nothing waits for it — it plays on the strip while the arm gets on
 * with the next carry. */
static void feeder_recharge(Ss1Feeder *f)
{
    /* The recharge animator is shared, because every feeder's span starts at
     * the line's base. By the asserts above a previous recharge is always long
     * finished by now, but if a retune ever broke that, taking the animator
     * away from a feeder still waiting on it would strand that feeder dark for
     * the rest of the run. Promote it instead: losing the tail of an animation
     * is a blemish, a module that never comes back is a dead demo. */
    for (uint8_t i = 0u; i < s_prog->feeder_count; i++) {
        if (&s_feeder[i] != f && s_feeder[i].phase == FD_CHARGING) {
            feeder_producing(&s_feeder[i]);
        }
    }

    ss1_charge_start(&s_charge, f->span_a, f->span_b, f->slot, f->fill_ms);
    f->phase = FD_CHARGING;
}

static void feeder_tick(Ss1Feeder *f)
{
    switch (f->phase) {
    case FD_CHARGING:
        /* The recharge is shared, so check it is still ours before claiming it
         * finished — a later feeder taking the animator over is not this
         * feeder's completion. The asserts make that impossible by timing, but
         * the check costs nothing and keeps the failure mode obvious. */
        if (ss1_charge_done(&s_charge) && s_charge.owner == f->slot) {
            feeder_producing(f);
        }
        break;

    case FD_PRODUCING:
        if (timer_expired(&f->t)) {
            f->phase = FD_HELD;      /* blue, awaiting its release cue */
            if (f->release_pending) {
                f->release_pending = false;
                feeder_ready(f);     /* the cue got here first (change 23) */
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
 * Takes the program's batches — one for sequence 1, both blenders' for
 * sequence 2 — finishes the lot and puts it out on the exit run.
 *
 * Structurally the same as a blender, and for the same reasons: every arrival
 * is acknowledged in blue (change 17.4), an arrival that does not complete the
 * lot goes dark again rather than sitting lit (change 16.4), and the one that
 * does completes it and stays lit while it works.
 *
 * Defined ahead of the blender because the blender calls into it: the hand-off
 * runs one way only, and keeping callee before caller is what lets this file
 * get away with no forward declarations at all.
 * ==================================================================== */

static void packaging_reset(void)
{
    s_pack         = PK_IDLE;
    s_pack_batches = 0u;
    timer_stop(&s_pack_timer);
}

/* A blender's batch has finished crossing and is now in. */
static void packaging_receive(void)
{
    if (s_pack_batches < s_prog->pack_needs) {
        s_pack_batches++;
    } else {
        return;                      /* a full lot cannot take more */
    }

    set_mod(s_prog->pack_slot, MOD_RUNNING);

    if (s_pack_batches >= s_prog->pack_needs) {
        s_pack = PK_FINISHING;
        timer_start(&s_pack_timer, SS1_PACKAGING_FINISH_MS);
    } else {
        s_pack = PK_ACK;
        timer_start(&s_pack_timer, SS1_RECEIVE_ACK_MS);
    }
}

static void packaging_tick(void)
{
    switch (s_pack) {
    /* Acknowledged; there is still only part of a lot in there, so it goes
     * dark rather than sitting lit for the carries it has to wait. */
    case PK_ACK:
        if (timer_expired(&s_pack_timer)) {
            set_mod(s_prog->pack_slot, MOD_OFF);
            s_pack = PK_HOLDING;
        }
        break;

    case PK_FINISHING:
        if (timer_expired(&s_pack_timer)) {
            set_mod(s_prog->pack_slot, MOD_READY);
            ss1_flow_start(&s_flow_out, s_prog->out_a, s_prog->out_b,
                           SS1_FLOW_OUTPUT_PASSES);
            s_pack = PK_SHIPPING;
        }
        break;

    case PK_SHIPPING:
        if (ss1_flow_done(&s_flow_out)) {
            /* The lot is out of the door, and change 13.2 makes THIS the cycle
             * boundary — not a reset, not a blank board, not anything the arm
             * takes part in. The arm is already well into the next lot's
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
            set_mod(s_prog->pack_slot, MOD_OFF);
            s_pack         = PK_IDLE;
            s_pack_batches = 0u;
        }
        break;

    default:
        break;
    }
}

/* ======================================================================
 * Blender actor
 * ==================================================================== */

static void blender_init(Ss1Blender *b, const Ss1BlenderSpec *spec)
{
    b->phase     = BL_EMPTY;
    b->slot      = spec->slot;
    b->needs     = spec->needs;
    b->materials = 0u;
    b->flow_a    = SS1_FLOW_A(spec->slot);
    b->flow_b    = SS1_FLOW_B(s_prog->pack_slot);
    timer_stop(&b->t);
}

/* The pour has finished and that material is now actually in — not when the arm
 * arrives and starts pouring, but when it has finished. Change 16.3: a module
 * turns blue the moment DISPENSING IS DONE, not the moment it begins.
 *
 * Change 16.4, the stale period. A blender used to go blue on the first pour
 * and stand there until the next arrived a whole carry later — a lit module
 * doing nothing, which is exactly the dead air the requirement calls out. Blue
 * has to mean "this module is working", so while it holds a partial batch it
 * stays DARK, and lights only when it is genuinely mixing.
 *
 * Change 17.4: every pour is still acknowledged in blue so the operator can see
 * the material land — it is just brief, and followed by dark, when there is
 * nothing yet to do with it. */
static void blender_material_in(Ss1Blender *b)
{
    if (b->materials < b->needs) {
        b->materials++;
    } else {
        return;                      /* a full blender cannot take more */
    }

    set_mod(b->slot, MOD_RUNNING);

    if (b->materials >= b->needs) {
        b->phase = BL_BLENDING;
        timer_start(&b->t, SS1_BLEND_RUN_MS);
    } else {
        b->phase = BL_ACK;
        timer_start(&b->t, SS1_RECEIVE_ACK_MS);
    }
}

static void blender_tick(Ss1Blender *b, uint8_t index)
{
    switch (b->phase) {
    case BL_ACK:
        if (timer_expired(&b->t)) {
            set_mod(b->slot, MOD_OFF);
            b->phase = BL_HOLDING;
        }
        break;

    case BL_BLENDING:
        if (timer_expired(&b->t)) {
            /* Change 12.2: blue while working, then green when it is done and
             * the batch is ready to ship. */
            set_mod(b->slot, MOD_READY);
            b->phase = BL_READY;
        }
        break;

    /* Green, batch made, waiting for the hand-off strip. In sequence 1 this
     * lasts a single tick because nothing else wants it; in sequence 2 the two
     * blenders' spans overlap (12..16 and 14..16) so they must take turns, and
     * this is the queue. Waiting GREEN is honest: the batch really is finished
     * and really is waiting to move. */
    case BL_READY:
        if (s_flow_xfer_owner == SS1_NO_OWNER) {
            s_flow_xfer_owner = index;
            ss1_flow_start(&s_flow_xfer, b->flow_a, b->flow_b,
                           SS1_FLOW_XFER_PASSES);
            b->phase = BL_SHIPPING;
        }
        break;

    case BL_SHIPPING:
        if (s_flow_xfer_owner == index && ss1_flow_done(&s_flow_xfer)) {
            /* The batch has physically left; the blender is dark and free for
             * the arm's next delivery, which may already be on its way. */
            set_mod(b->slot, MOD_OFF);
            b->phase     = BL_EMPTY;
            b->materials = 0u;
            s_flow_xfer_owner = SS1_NO_OWNER;
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
    s_flow_xfer_owner = SS1_NO_OWNER;

    for (uint8_t i = 0u; i < s_prog->feeder_count; i++) {
        feeder_init(&s_feeder[i], &s_prog->feeders[i]);
    }
    for (uint8_t i = 0u; i < s_prog->blender_count; i++) {
        blender_init(&s_blender[i], &s_prog->blenders[i]);
    }
    packaging_reset();
    s_carry = 0u;
    s_prime = 0u;
    seq_leds_strip_clear();
}

/* The carry table repeats every cycle, so carry N uses entry (N-1) mod its
 * length. Carries are 1-based; 0 means "not in one". */
static const Ss1CarrySpec *carry_at(uint8_t carry)
{
    return &s_prog->carries[(carry - 1u) % s_prog->carries_per_cycle];
}

static Ss1Feeder  *carry_source(uint8_t carry)
{
    return &s_feeder[carry_at(carry)->feeder];
}

static Ss1Blender *carry_dest(uint8_t carry)
{
    return &s_blender[carry_at(carry)->blender];
}

/* Does the arm come back to this feeder after `carry`? A recharge exists to
 * have a feeder green again for its NEXT visit, so after the last one there is
 * nothing to recharge for — and refilling anyway leaves the module standing
 * blue holding a batch nobody will ever collect, right through the end of the
 * run. Only the next cycle's worth of carries has to be checked: the table
 * repeats, so if a feeder is not used again within one cycle it is not used
 * again at all. */
static bool feeder_used_after(uint8_t feeder_index, uint8_t carry)
{
    uint8_t last = total_carries();
    for (uint8_t c = (uint8_t)(carry + 1u);
         c <= last && c <= (uint8_t)(carry + s_prog->carries_per_cycle); c++) {
        if (carry_at(c)->feeder == feeder_index) {
            return true;
        }
    }
    return false;
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
    /* Change 18.2: the run opens by charging the feeders on the strip, one
     * after another, over a completely dark board. The modules light only once
     * EVERY fill has finished — so the operator sees the plant being primed and
     * then coming alive together, rather than modules simply being on when the
     * demo starts.
     *
     * Sequential because it has to be: every feeder's span starts at the line's
     * base, so they all overlap and there is one animator. In program order,
     * which is the order the sequence itself first collects them. */
    case S_PRIME:
        ss1_charge_start(&s_charge,
                         s_feeder[s_prime].span_a, s_feeder[s_prime].span_b,
                         s_feeder[s_prime].slot,   s_feeder[s_prime].fill_ms);
        break;

    /* Every feeder primed: now they light, together, and start producing. */
    case S_INIT:
        for (uint8_t i = 0u; i < s_prog->feeder_count; i++) {
            feeder_producing(&s_feeder[i]);
        }
        s_carry = 1u;
        break;

    /* One MOVE cue covers the whole carry — travel, grip, collect, cross, pour
     * and the return home are all one animation in the Android app. Do not add
     * a second cue: with no ARRIVED coming back there is nothing to
     * synchronise against.
     *
     * What this side CAN do is peg its own colour changes to the same
     * milestones the arm is animating to, which is what the four steps below
     * are. This one ends the instant the arm stops collecting. */
    case S_CARRY_MOVE:
        ble_move(SLOT1(carry_source(s_carry)->slot),
                 SLOT1(carry_dest(s_carry)->slot));
        ms = SS1_T_COLLECT_END;
        break;

    /* Change 12.2: the source goes green -> off the moment its material has
     * been collected, which is the moment the arm lifts away with it. */
    case S_CARRY_CROSS:
        feeder_emptied(carry_source(s_carry));
        /* Change 23: the recharge starts HERE, the instant the arm lifts away
         * with the material, rather than later when the arm pours it.
         *
         * The module is empty from this moment, so it is free to refill from
         * this moment -- waiting left it sitting dark and idle for the ~8 s of
         * the crossing, which is the dead time this demo keeps having to remove.
         *
         * It is also what makes sequence 3 possible at all. With one feeder and
         * one carry per cycle it has a single carry to recharge AND produce in;
         * starting at the pour left it 3 s short and the arm would have arrived
         * at a module that was not green yet.
         *
         * This supersedes change 11.4, which tied the recharge to "the blender
         * starts working". That coupling had already been broken by change 16.3
         * moving the blender's blue to the END of the pour, so the recharge was
         * no longer coinciding with anything in particular. */
        if (feeder_used_after(carry_at(s_carry)->feeder, s_carry)) {
            feeder_recharge(carry_source(s_carry));
        }
        ms = SS1_T_CROSS_TO_DEST;
        break;

    /* The arm has arrived and is pouring. Change 11.4: this is the moment the
     * source it came from starts recharging, underneath the arm's next carry
     * rather than stopping it.
     *
     * The destination is deliberately NOT touched here. Change 16.3 moved its
     * colour to the END of the pour, because blue is supposed to mean the
     * module is working and a module being poured into has not started working
     * yet. */
    case S_CARRY_DUMP:
        ms = SS1_T_DUMP_HOLD;
        break;

    /* The pour is finished — changes 16.3/16.4 make this the instant the
     * destination reacts. The arm then retracts and goes home; the wait
     * includes a beat stood at rail centre, so the next MOVE never lands while
     * the previous animation is still playing. */
    case S_CARRY_HOME:
        blender_material_in(carry_dest(s_carry));
        /* Changes 13.1 / 18.4 / 18.5: the NEXT carry's source goes green now.
         * A module lights exactly when the plant is ready for it to be
         * collected, and never out of turn. In sequence 1 that reads as the two
         * feeders alternating; in sequence 2 it walks the carry table, which is
         * the same rule and not a second one. */
        if (s_carry < total_carries()) {
            feeder_release(carry_source((uint8_t)(s_carry + 1u)));
        }
        ms = SS1_T_GO_HOME;
        break;

    /* Every carry of every cycle is done and the arm is parked for good. The
     * plant is still finishing the last lot underneath — this is the only place
     * the chain ever waits on it, and only because there is nothing left for the
     * arm to carry. Change 13.2 deleted the old per-cycle wait and gap: waiting
     * for the plant and blanking the board at every cycle boundary is precisely
     * the stall and the reset the requirement removes. */
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

void solid_seq_start(uint8_t seq)
{
    if (seq == 0u || seq > SOLID_SEQ_COUNT) {
        return;                     /* unknown: ignore rather than guess */
    }
    s_prog     = &SS_PROGRAMS[seq - 1u];
    s_selected = seq;

    ss1_reset_transient();
    module_all_off();

    /* Announce the sequence FIRST, so the log and the Android app always see
     * SEQ:<n> ahead of any RUN or MOVE cue. */
    ble_seq(s_prog->number);
    seq_leds_start(s_prog->number);      /* takes the strip and blanks it */

    s_status = SS1_RUNNING;
    s_cycle  = 1u;
    s_lots   = 0u;
    enter_step(S_PRIME);
}

void solid_seq_stop(void)
{
    if (s_prog != NULL) {
        ss1_reset_transient();           /* while seq_leds still owns the strip */
    }
    module_all_off();
    s_status = SS1_IDLE;
    s_cycle  = 0u;
    s_lots   = 0u;
    s_step   = S_IDLE;
    s_prog   = NULL;
    seq_leds_stop();
}

void solid_seq_select_next(void)
{
    /* Selecting is also how the operator switches away from a running demo, so
     * stop first. Leaving the old sequence running underneath a recipe card for
     * the new one would be a lie about what the board is doing — and the run
     * would keep cueing the robot for modules the operator is no longer
     * watching. */
    if (s_status == SS1_RUNNING) {
        solid_seq_stop();
    }
    s_selected = (uint8_t)((s_selected % SOLID_SEQ_COUNT) + 1u);
}

uint8_t solid_seq_selected(void) { return s_selected; }

void solid_seq_tick(void)
{
    bool advance = false;

    if (s_status != SS1_RUNNING || s_prog == NULL) {
        return;
    }
    /* If anything took the strip out from under us, stop properly rather than
     * carrying on invisibly — otherwise the module states and the BLE cues
     * would keep running and the robot would keep receiving MOVE commands. */
    if (!seq_leds_active()) {
        solid_seq_stop();
        return;
    }

    /* Everything that is not the arm runs here, every pass, regardless of what
     * step the robot chain is on. This is what "simultaneously" means in this
     * file: the plant is never waiting for its turn. */
    ss1_charge_tick(&s_charge);
    ss1_flow_tick(&s_flow_xfer);
    ss1_flow_tick(&s_flow_out);
    for (uint8_t i = 0u; i < s_prog->feeder_count; i++) {
        feeder_tick(&s_feeder[i]);
    }
    for (uint8_t i = 0u; i < s_prog->blender_count; i++) {
        blender_tick(&s_blender[i], i);
    }
    packaging_tick();

    switch (s_step) {
    /* The priming fills, each waiting on the shared animator rather than on a
     * clock, so retuning SS1_CHARGE_FILL_* cannot desync them. */
    case S_PRIME:
        advance = ss1_charge_done(&s_charge);
        break;

    /* The arm leaves for the first carry when its source has finished
     * producing, not when a clock says it ought to be — so the produce time can
     * be retuned in config.h without the first MOVE drifting off the module
     * colour. Every feeder holds, so this waits on FD_HELD and the release is
     * granted in the transition below. */
    case S_INIT:
        advance = (carry_source(1u)->phase == FD_HELD);
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
    case S_PRIME:
        s_prime++;
        if (s_prime < s_prog->feeder_count) {
            enter_step(S_PRIME);
        } else {
            enter_step(S_INIT);
        }
        break;

    case S_INIT:
        /* The one green nobody dispensed for: the run has to start somewhere,
         * and the first carry's source is where. Every green after this is
         * released by the previous carry's pour finishing. */
        feeder_release(carry_source(1u));
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
     * includes the carries that cross a cycle boundary: the arm counts every
     * carry of the run and never returns to S_INIT. The plant catches up
     * underneath, closing one lot per cycle. Only when the very last carry is
     * done does the arm stand down. */
    case S_CARRY_HOME:
        if (s_carry < total_carries()) {
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

Ss1Status solid_seq_state(void)   { return s_status; }
uint8_t   solid_seq_cycle(void)   { return s_cycle; }
uint8_t   solid_seq_running(void) { return (s_prog != NULL) ? s_prog->number : 0u; }

const char *solid_seq_step_name(void)
{
    return (s_step < S_STEP_COUNT) ? SS1_STEP_NAME[s_step] : "?";
}
