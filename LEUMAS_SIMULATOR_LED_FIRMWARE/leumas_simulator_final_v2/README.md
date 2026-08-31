# Leumas Simulator — STM32 Firmware

Firmware for the Leumas factory-line demo rig, on an **STM32F767ZI Nucleo**. It does two things:

1. **A WS2812B LED strip on PE9**, driven by a serial **LED command centre**. You type a colour, an
   index and a range, and the strip shows exactly that.
2. **Solid Sequence 1**, a hardcoded production demo that cues an Android "robot" display over a BLE
   UART and — via `App/seq_leds.c` — paints itself onto the same strip.

The strip has exactly **one writer at a time**. Normally that is the command centre; `seq 1` hands
ownership to the sequence renderer, and `seq stop` hands it back. While a sequence is running, manual
paint commands are refused rather than half-overwriting its frame.

> **LED strip:** see [LED command centre](#led-command-centre) below — that is what you want if you
> just flashed the board and want to light something up.

The sequence sections that follow describe the production story — the same one the strip shows and the
BLE cues narrate to the robot.

## Solid Sequence 1 — the production demo

A small-scale nutraceutical production process, hardcoded and deterministic. It runs **four complete
cycles** and then stops in a COMPLETE state; it never starts a fifth on its own.

Four modules take part. Everything else on the board — including the whole liquid line — stays dark.

| Requirement name | Slot | Role | Module px |
|---|---|---|---|
| Module 2 | 2 | Macro | 49-51 |
| Module 3 | 3 | Gravimetric | 53-55 |
| Module 5 | 5 | Blender | 61-62 |
| Module 6 | 6 | Packaging | 76-77 |

### One cycle

1. **Macro and Gravimetric both run** for 10 s. Macro finishes and goes green.
2. **Robot collects the macro material** (10 s) and **dumps it into the blender** (10 s). The
   gravimetric finishes partway through the dump and goes green. The blender starts running.
3. **Robot collects the gravimetric material** (10 s) — and *at the same time* the macro **recharges**
   on strip pixels 0-5. When the recharge finishes the macro goes blue: it is running again.
4. **Robot dumps the gravimetric material into the blender** (10 s) and returns home.
5. **The gravimetric recharges** on pixels 0-9 while the blender runs and hands off. When it finishes,
   the gravimetric goes blue.
6. **Blender → Packaging.** No robot movement: material flows along pixels **14-16**, four times.
   Packaging then goes blue.
7. **The second macro and gravimetric carries** repeat steps 2-4 with the same visual logic.
8. **Packaging → output.** Packaging goes green and material flows out along pixels **17-21**.
9. The board blanks, everything resets, and the next cycle begins.

One cycle is about **2 min 10 s**; four cycles about **8 min 40 s**.

### What it looks like on the strip

| Module state | Look |
|---|---|
| running | **blue**, 100 % |
| completed, ready for the robot | **green**, 100 % |
| off — not processing, or the robot has left | **dark** |
| not used by this sequence | **dark** |

Three colours, nothing else, and nothing blinks. The strip itself is only ever **white at
`SEQ_STRIP_PCT` (1 %)** and is used for exactly three things: a module recharging, material moving
between modules, and material leaving the system.

Modules and strip are deliberately **two different brightnesses on the one strip**: modules at
`SS1_MODULE_PCT` (100 %) and the strip at 1 %, both as per-pixel percentages over a master of 100. The
modules are the subject; the strip is the line drawn between them. Lowering the master instead would
dim both together and lose the distinction.

### The two animations

Both are deliberately **slow** — this is a client-facing demo watched from 2-3 m away, not an LED
chase. Everything the strip shows is one flag per pixel, so an animation can never leave a stray pixel
behind it.

**Recharge** (macro 0-5, gravimetric 0-9) is *progressive*. A head crawls from the first uncharged
pixel to the end of the span; when it gets there the next `SS1_CHARGE_CHUNK` (2) pixels latch solid and
the head restarts past them, so the charged section grows and each pass is shorter than the last. Over
0-5 that is three stages — swipe `0→5` keep `0,1`; swipe `2→5` keep `0..3`; swipe `4→5` keep `0..5` —
and the identical code over 0-9 gives five. The head and the charged pixels are the same white at 1 %:
**motion alone** tells them apart, so what the audience sees is charge accumulating rather than an
animation being played.

**Material flow** (blender→packaging 14-16, packaging→output 17-21) fills its span one pixel at a
time, holds it full for one step, blanks, and repeats. Over 14-16 that is `14` → `14,15` → `14,15,16` →
dark, four times over. The all-dark hold **is** the gap between passes.

To retune the whole demo, change `SS1_CHARGE_STEP_MS` and `SS1_FLOW_STEP_MS` in `Core/Inc/config.h` and
nothing else. Every other duration is read in exactly one place.

### Concurrency

The two animators run on their own clocks, independent of the step chain and of each other — that is
what lets the macro recharge play while the robot is busy collecting from the gravimetric, and the
gravimetric recharge play while the blender hands off to packaging. There is no blocking delay
anywhere: every wait is a `SoftTimer` or a predicate, polled from `solid_seq1_tick()`.

### The robot

The BLE link is **transmit-only** — `MOVE:a>b` goes out, nothing comes back. There is no `ARRIVED`
acknowledgement and no `HOME` command, so the firmware cannot know where the arm actually is. One MOVE
cue goes out **per material carry**, when that carry begins, and the Android app plays its own full
travel/pick/place/return-home animation against it. That is also why the robot needs no reset between
cycles: it parks itself. Four MOVE cues per cycle, sixteen per run.

## Starting a sequence
Type `seq 1` on the console, or press the board button — either starts Solid Sequence 1 from cycle 1.
Pressing the button again mid-demo restarts it cleanly. `seq` on its own reports the cycle and step it
is on.

After the fourth cycle the board is parked dark and `seq` reports **COMPLETE**; the sequence still owns
the strip. `seq stop` (or `stop`) hands it back to manual control.

## The LED strip

One WS2812B strip, **90 pixels**, data on **PE9** (`TIM1_CH1` → `DMA2_Stream1`). PE9 rather than PB4
because **PB4 is the JTAG `NJTRST` debug pin** — a poor host for a hard-realtime 800 kHz signal with the
ST-Link attached. TIM3/PB4 is still initialised by CubeMX but is never started and nothing renders to it.

`App/led_strip.c` is the only code that touches the hardware; above it, exactly one of the command
centre and `seq_leds.c` owns the pixels at any moment. What keeps it from glitching:

- **One writer, enforced.** The command centre and the sequence renderer never both draw: `seq <n>`
  transfers ownership and manual paint commands are refused until `seq stop`.
- The DMA buffer is **never rebuilt while DMA is reading it** — a change that arrives mid-frame just
  sets a dirty flag and goes out on the next frame.
- Every frame carries **its own reset gap at both ends** (50 µs lead, 375 µs trail), so one frame can
  never be mis-parsed as a continuation of the last.
- **The data line is never left floating.** TIM1 is configured with `OSSR = TIM_OSSR_DISABLE`, so the
  instant `HAL_TIM_PWM_Stop_DMA()` clears `CC1E` the pad's output driver is switched **off** and PE9
  goes high impedance — and since frames are only sent on change, it would stay that way indefinitely.
  An internal pull-down is ~40 kΩ, nowhere near enough to hold a multi-metre data line quiet next to
  the strip's own switching supply; coupled noise gets clocked into pixel 0 and cascades, and the strip
  never settles. So between frames PE9 is handed to the **GPIO peripheral as a push-pull output driving
  0**, and lent back to the timer only while a frame is streaming. Both handovers are ordered so the pad
  is driven by one peripheral or the other at every instant — never neither.
- Frames go out **only when something changed**. A WS2812B holds its colour; re-sending an identical
  frame 50× a second buys nothing and only adds chances to be corrupted.
- A **current budget** (default 1000 mA) scales the whole frame down — hue preserved — before it is
  sent, so a full-brightness command cannot brown the rail out. A sagging 5 V rail makes the far end of
  the strip flicker and reads exactly like a data bug.
- **Bit timing is centred for both generations of the part.** There are two WS2812B datasheets in
  circulation and their windows only partly overlap (T0H 0.40 µs vs 0.30 µs, T1H 0.80 µs vs 0.90 µs).
  The obvious 1/3 and 2/3 duty split lands only **33 ns** inside the V5 ceiling for T0H and T1L — not
  enough once you add clock tolerance, DMA latency and edge degradation down a long chain, and it shows
  up as occasional wrong bits at scattered pixels. This driver uses **354 ns / 854 ns**, centred in both
  datasheets, worst-case margin **54 ns**. The windows are enforced by `_Static_assert` in
  `led_strip.c`, so changing the clock tree fails the build instead of glitching on the bench.
- **The opening blank frames cover the whole possible chain** (`LED_COUNT_MAX` pixels, not `count`), so
  a strip physically longer than `count` cannot sit showing random power-on colours on its tail.

> **Power.** 90 pixels at full white is ~5.4 A. The strip **needs its own 5 V supply** (2–3 A), with its
> ground tied to the Nucleo's GND. Do not run it off the Nucleo's 5 V pin.

Everything tunable — pixel count, default brightness, current budget, bit timing — is in one block at
the top of `App/led_strip.h`.

## Physical line orientation
The solid line is not straight — it bends around the display:

```
  Macro1 ── Macro2 ── Gravimetric
                          │  (90° right)
                       Blender1
                          │
                       Blender2
                          │  (90° left)
                       Packaging ──┐
                          ▲        │
                          └────────┘  (90° left = product exit)
```

Flow order: Macro1 → Gravimetric → Blender1 → Packaging → exit. This is the path the Android robot
animates from the BLE cues; slot roles are unchanged, only the geometry is.

## Source layout
```
App/led_strip.[ch]    WS2812B driver on PE9: framebuffer, per-pixel + master brightness,
                      per-pixel blink flag, current budget, DMA frames.
                      ← the ONE LED tuning block lives here
App/led_fx.[ch]       the swipe animation + the blink clock, with separate speeds
App/led_cmd.[ch]      the LED command centre: serial parser + help, over the ST-Link VCP
App/seq_leds.[ch]     draws the running sequence onto the strip, and owns it while it runs
App/timebase.[ch]     millis() + soft timers (no blocking delays anywhere)
App/app.[ch]          glue to HAL: app_init / app_tick / callbacks

Core/Inc/config.h     slot map, the SS1_* sequence constants, and the rig's pixel mapping
App/config.c          slot->role table, SLOT_LEDS[] pixel map
App/module.[ch]       per-module state: OFF / RUNNING (blue) / READY (green)
App/solid_seq1.[ch]   Solid Sequence 1: the step chain plus both animators
App/button.[ch]       EXTI + debounce
App/ble.[ch]          ASCII cue protocol over UART
```

`solid_seq1.c` decides; `seq_leds.c` draws; `module.c` stores. They never bypass each other — in
particular `solid_seq1.c` must never call `led_strip_*` directly, because the next repaint would
overwrite it and the bug would present as flicker.

## Rig pixel map

`SLOT_LEDS[]` in `App/config.c` says where each slot lives on the 90-pixel strip. Status ranges are
**cumulative fills from the line's base**, not per-gap segments — slot 3's `0-9` already covers slots 1
and 2. Check it against the hardware with `seq map`.

| Slot | Name | Module px | Status fill | | Slot | Name | Module px | Status fill |
|---|---|---|---|---|---|---|---|---|
| 1 | Macro1 | 45-47 | 0-1 | | 7 | LMacro1 | 85-87 | 22-26 |
| 2 | Macro2 | 49-51 | 0-5 | | 8 | LMacro2 | 80-82 | 22-31 |
| 3 | Gravimetric | 53-55 | 0-9 | | 9 | Vessel1 | 70-71 | 22-33 |
| 4 | Blender1 | 57-58 | 0-11 | | 10 | Vessel2 | 66-67 | 22-35 |
| 5 | Blender2 | 61-62 | 0-13 | | 11 | Bottling | 73-74 | 22-38 |
| 6 | Packaging | 76-77 | 0-16 | | | | | |

Exit runs: **packaging 17-21**, **bottling 39-43**. Pixels `44, 48, 52, 56, 59-60, 63-65, 68-69, 72, 75,
78-79, 83-84, 88-89` are the physical gaps between module blocks and stay dark.

## BLE protocol (ASCII, `\n`-terminated, slot numbers 1-based)
`READY` · `SEQ:<n>` · `RUN:<slot>` · `DONE:<slot>` · `MOVE:<a>><b>` · `LOT:DONE`

<a id="led-command-centre"></a>
## LED command centre

Open the **ST-Link Virtual COM Port** — the same USB cable that flashes the board — in **PuTTY →
Serial, 115200, 8-N-1**. You get a `led>` prompt. Type `help` for the list below.

The colour **is** the command:

```
red                 whole strip red
red 7               LED 7 red
red 20 30           LEDs 20 to 30 red
off                 whole strip off
off 7               LED 7 off
off 20 30           LEDs 20 to 30 off
clear               back to the power-on state
```

Colours: `red green blue white yellow orange amber cyan magenta purple pink lime teal off`.

**Brightness** — percent, 0 to 100. Master brightness and per-LED brightness are independent, and a
pixel keeps its brightness when you change its colour:

```
bright 40           whole strip to 40%
bright 7 10         LED 7 to 10%
bright 20 30 10     LEDs 20 to 30 to 10%
```

**Swipe** — a lit point travelling along the strip. Speed is **milliseconds per LED**, so it means the
same thing whatever the distance (lower = faster). The swipe draws to an overlay, so whatever you
painted underneath is untouched and reappears the moment it stops:

```
swipe red 0 89          red point, LED 0 → 89, at the current speed
swipe red 0 89 15       …15 ms per LED (90 × 15 ms = 1.35 s end to end)
swipe red 89 0 15 loop  …backwards, repeating until you stop it
speed 15                swipe speed on its own (applies to a running swipe too)
tail 3                  pixels trailing the head, 0 to 8 (same hue, fading)
swipe stop              stop the swipe
```

**Blink** — LEDs that flash on and off where they are. Blinking is a per-pixel flag, not an animation
that owns the strip, so any mix of pixels can blink while a swipe runs over the top of them. A flagged
pixel just shows black on the dark half of the cycle; its colour and brightness are untouched:

```
blink red 7             LED 7 blinks red
blink red 20 30         LEDs 20 to 30 blink red
blinkspeed 400          blink speed on its own — 400 ms lit, 400 ms dark
blink off               stop blinking; those LEDs stay solid
```

**`stop`** halts **both** the swipe and the blinking. `speed` and `blinkspeed` are separate commands on
purpose — changing one never disturbs the other. Painting an LED any colour makes it solid again, so
`red 7` is how you cancel `blink red 7`.

**Sequences** — the production demo, cued to the robot over BLE. While one runs it owns the strip, so
paint commands are refused until you stop it:

```
seq 1               Solid Sequence 1 - 4 production cycles, then it stops
seq stop            end it and take the strip back
seq map             print the slot -> pixel table, flagging bad coordinates
seq                 which cycle and step it is on, or COMPLETE
```

**Setup:**

```
count 90            how many LEDs are on the strip (1–200), clears it
power 1000          current budget in mA;  power off = no cap
status              owner, LED count, brightness, swipe/blink state, last frame's draw
help                the command list
```

Indexes are the electrical order on the chain: **0 is the LED nearest the data pin**, and the last is
`count - 1`. An index outside the strip is rejected with a message and **nothing changes** — the strip
is never left half-painted.

> If a frame would exceed the current budget the whole frame is scaled down proportionally, keeping
> every hue. `status` tells you when that happened (`scaled to 55%`). Raise it with `power 3000` once
> the strip is on its own supply.

### If pixels flicker

Nothing is sent to the strip unless you type a command — the firmware cannot be changing them. So work
through it in this order:

1. **`bright 5`.** If the flicker stops, it is the **5 V rail**, not the data line. 90 pixels is up to
   5.4 A; give the strip its own 2–3 A supply, tie its ground to the board's GND, and inject 5 V at
   **both** ends of the strip. Feeding it from the Nucleo's 5 V pin will not work.
2. **Check `status`.** If the last frame is drawing near your supply's limit, lower `power` or
   `bright`.
3. **Logic level.** A WS2812B at 5 V wants V<sub>IH</sub> ≈ 3.5 V and the STM32 only drives 3.3 V. It
   usually works, but on a long run it is marginal — keep the wire from PE9 to the first pixel short
   (< 10 cm), or add a 3.3 → 5 V level shifter, or power the strip at ~4.5 V.
4. **Ground.** The strip's ground and the Nucleo's GND must be joined, with a short, fat wire.

## STM32CubeIDE setup

Create an STM32F767ZI project (or open your `.ioc`) and configure:

### Clock
The `.ioc` already sets this up: HSE bypass → PLL → **SYSCLK 192 MHz**, APB2 /1, so the **TIM1 counter
clock is 96 MHz**. With **Counter Period (ARR) = 119** that gives 96 MHz / 120 = **800 kHz**, i.e. the
1.25 µs WS2812B bit. If you change the clock tree, update `LED_TIM_HZ` in `App/led_strip.h` — the
build-time assertions there will fail until the numbers are consistent again.

### TIM1 CH1 — the WS2812B strip on PE9
- Mode **PWM Generation** on `CH1`, pin **PE9** (`GPIO_AF1_TIM1`), output speed **Very High**.
- **Counter Period (ARR) = 119**, Prescaler 0, auto-reload preload **enabled**.
- Channel DMA request on **DMA2_Stream1, Channel 6**: Memory→Peripheral, Mode **Normal**,
  **Increment Memory Address** on, Peripheral and Memory data width both **Half Word**,
  Priority **Very High** (WS2812B is hard-realtime; a stream that loses the bus matrix mid-frame emits
  a mistimed bit, which shows up as wrong-coloured pixels at scattered indexes).
- Enable the **DMA2_Stream1 global interrupt** so the transfer-complete callback fires.
- **Counter Period (ARR) = 119**, and the driver drives `CCR1` with **34** for a 0 bit and **82** for a
  1 bit (354 ns / 854 ns highs). See [The LED strip](#the-led-strip) for why those and not 40/80.
- `led_strip_init()` re-applies the whole PE9 configuration in app code, so a CubeMX regeneration cannot
  silently undo it, and then parks the pin as a **GPIO output driving low** — the timer only borrows it
  for the duration of each frame. Level-shift 3.3 V → 5 V if your strip is fussy about logic levels.

### USART (BLE) — e.g. USART3
- Asynchronous, baud to match the BLE module (commonly **9600**), 8N1. TX pin wired to the module RX.

### Push button — external, on EXTI
- Configure the button GPIO as **GPIO_EXTI**, trigger on the active edge, with pull-up/down as wired.
- Give the pin the **User Label `BTN`** (so `BTN_Pin` is generated; else edit `BUTTON_PIN` in `button.c`).
- Enable the matching EXTI NVIC interrupt.

### Add `App/` to the build
Project → Properties → C/C++ Build → Settings → include path: add `App` and `Core/Inc`.
Make sure the `App/*.c` files are in a source folder that gets compiled.

---

## Wiring `main.c` (only USER CODE sections — never edit generated blocks)

Already done in this project; shown here so it survives a CubeMX regeneration.

```c
/* USER CODE BEGIN Includes */
#include "app.h"
/* USER CODE END Includes */

/* ... after MX_GPIO_Init(), MX_DMA_Init(), MX_TIM1_Init(), MX_USART3/6_UART_Init() ... */

/* USER CODE BEGIN 2 */
HAL_NVIC_EnableIRQ(EXTI15_10_IRQn);   /* CubeMX sets the priority but not this */

app_init(&htim1, TIM_CHANNEL_1,   /* WS2812B strip on PE9                   */
         &huart6,                 /* BLE cue link, USART6 (PC6/PC7 @9600)   */
         &huart3);                /* LED command centre, ST-Link VCP @115200 */
/* USER CODE END 2 */

/* while (1) { */
  /* USER CODE BEGIN 3 */
  app_tick();
  /* USER CODE END 3 */
/* } */
```

Plus the three callback forwarders in USER CODE 4:

```c
/* USER CODE BEGIN 4 */
void HAL_GPIO_EXTI_Callback(uint16_t GPIO_Pin)          { app_on_button_exti(GPIO_Pin); }
void HAL_TIM_PWM_PulseFinishedCallback(TIM_HandleTypeDef *htim) { app_on_pwm_complete(htim); }
void HAL_TIM_ErrorCallback(TIM_HandleTypeDef *htim)     { app_on_tim_error(htim); }
/* USER CODE END 4 */
```

## Verification checklist

**Manual control** — open the ST-Link COM port in PuTTY at 115200 8-N-1:

1. **Build/flash.** Clean build, no HAL init fault. On boot the strip is **dark** and the banner
   appears at the `led>` prompt.
2. `green` — the whole strip lights green. This one command confirms the data pin, the GRB byte order,
   and the bit timing all at once. If you get red instead, the byte order is wrong; if scattered pixels
   are the wrong colour, look at power first, not data.
3. `red 20 30` — **only** LEDs 20–30 light, and only red. Nothing else changes.
4. `bright 25 50` then `bright 40` — LED 25 dims on its own; then the whole strip dims, LED 25 staying
   proportionally dimmer. `clear` puts everything back.
5. `swipe white 0 89 15` — one point runs the length of the strip and stops at the far end, leaving the
   strip as it was. `swipe white 0 89 15 loop` repeats it; `stop` ends it cleanly with no stray pixel.
6. **Leave it sitting for a few minutes** with a frame painted. Nothing should blink, drift or change.
   If the far end flickers, it is the 5 V rail: give the strip its own supply, or lower `power`.
7. `status` — check the reported draw against your supply.

**Sequences:**

8. `seq map` — check every row against the physical strip by eye before anything else. Macro2 49-51 /
   0-5, Gravimetric 53-55 / 0-9, Blender2 61-62 / 0-13, Packaging 76-77 / 0-16, exit 17-21.
9. `seq 1`, then time one cycle with a stopwatch. Only slots 2, 3, 5 and 6 ever light; the whole liquid
   line and status 22-43 stay dark throughout.

    | t | What you should see |
    |---|---|
    | 0 s | Module 2 + 3 **blue**, everything else dark, strip dark |
    | 10 s | Module 2 **green**; `MOVE:2>5` on the BLE line |
    | 20 s | Module 2 dark, Module 5 dark |
    | 25 s | Module 3 **green** |
    | 30 s | Module 5 **blue**; `MOVE:3>5`; the **0→5 recharge head starts crawling** |
    | ~40 s | Module 3 dark, Module 5 green; then Module 2 back to **blue** (recharge done) |
    | 50 s | Module 5 **blue**; the **0→9 recharge** starts |
    | 60 s | Module 5 **green**; 14→15→16 fills and blanks **four times** |
    | ~70 s | Module 3 back to **blue** (recharge done) |
    | ~73 s | Module 6 **blue**; Module 2 **green**; `MOVE:2>5` — the second half repeats |
    | ~118 s | Module 6 **green**; 17→18→19→20→21 fills toward the output |
    | ~130 s | board blanks, next cycle starts |

10. **Recharge shape** — watch 0-5 at t=30 s. It must go `0,1,2,3,4,5` one at a time, then settle to
    `0,1` solid; then `2,3,4,5` with `0,1` still lit, settling to `0..3`; then `4,5`, settling to
    `0..5`. If it looks like a fast chase, raise `SS1_CHARGE_STEP_MS`.
11. **Colours** — only blue, green and dark, all at full brightness. Nothing blinks. The strip is
    white and clearly dimmer than the modules.
12. **After cycle 4** (~8 min 40 s) the board is dark, `seq` reports **COMPLETE**, and cycle 5 does
    **not** start.
13. `red 20 30` mid-sequence → refused with the `seq stop` message, strip unchanged. `seq stop` → the
    strip blanks and manual commands work again. Watch the BLE line: **no further `MOVE:` messages**
    after `seq stop`.
14. **Button** — press it mid-run: the demo restarts cleanly from cycle 1.
15. **BLE** — one cycle should emit `SEQ:1` once, then exactly four `MOVE:` cues (`2>5`, `3>5`, `2>5`,
    `3>5`) and one `LOT:DONE`, plus `RUN:`/`DONE:` on each module colour change. Confirm the Android
    arm animates on each MOVE. Set `SS1_EMIT_RUN_DONE` to 0 for MOVE-only if the app would rather not
    see the module cues.

## To tune / finish before the demo
- **`App/led_strip.h`** is the one LED tuning block: `LED_COUNT_DEFAULT` (90), `LED_MASTER_PCT_DEFAULT`
  (30 %), `LED_BUDGET_MA_DEFAULT` (1000 mA). `count` and `power` change the first and last live over
  serial, so you can settle on values before editing anything.
- Confirm all slot roles in `App/config.c`: solid (Macro1/2, Gravimetric, Blender1/2, Packaging) and
  liquid (LMacro1/2, Vessel1/2, Bottling). The liquid line is mapped but unused until its own sequence
  lands.
- **Animation pace** — `SS1_CHARGE_STEP_MS` (400) and `SS1_FLOW_STEP_MS` (700) in `Core/Inc/config.h`
  set the tempo of the whole demo. Slower is better than faster; `600` / `1000` for a slower run,
  `250` / `450` for a faster one. Nothing else needs touching.
- **Fixed durations** — the five 10 s windows (`SS1_INITIAL_PROCESS_MS`, `SS1_COLLECTION_MS`,
  `SS1_DUMP_MS`) are pinned by the requirement and must not be shortened. They account for 80 s of each
  cycle. If the demo needs to be shorter, drop `SS1_FLOW_OUTPUT_PASSES` to 1 and
  `SS1_PACKAGING_FINISH_MS` to 2000 first.
- **Inferred windows** the requirement leaves open, tune freely: `SS1_BLEND_RUN_MS` (10 s of blender
  running between "both materials in" and "ready to ship" — without it that blue frame would never be
  seen), `SS1_PACKAGING_FINISH_MS`, `SS1_GRAV_READY_AT_MS`, `SS1_CYCLE_GAP_MS`.
