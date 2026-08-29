# Leumas Robot — Android visualisation app

Real-time visualiser for the Leumas factory-line demo rig. The STM32F767ZI firmware in
`../leumas_simulator_final_v2` drives the LED strips and emits ASCII cues over a BLE UART bridge;
this app listens and draws a 4-axis palletising robot performing the corresponding material
transfers.

> **THE MCU CONTROLS THE ROBOT. ANDROID VISUALISES IT.**
> The app never runs its own sequence engine and never predicts what the controller will do. Every
> visual change traces back to a received message.

## The core idea: the modules are outside the screen

The nine physical modules stand around the phone on the real rig. **They are never drawn.** The
screen is a window into the robot's workspace; calibration defines, for each module, a point on the
*screen edge* that the arm reaches toward — and past, so the end effector visibly leaves the
workspace toward the real machine.

This inverts what the earlier sections of `robot_requirements.md.txt` imply, and it is the single
easiest thing to get wrong.

## Building

No Android Studio required.

```bash
export TEMP="C:\\gradletmp" TMP="C:\\gradletmp"   # see "Build environment" below
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the tests with `./gradlew test`.

### Build environment (important on this machine)

Gradle dies with `java.io.IOException: Unable to establish loopback connection` unless `TEMP`/`TMP`
point at `C:\gradletmp`. Gradle's daemon needs `java.nio` `Selector`/`Pipe`, which on JDK 16+ under
Windows is built on an **AF_UNIX socket pair** created inside `java.io.tmpdir`. On this machine
AF_UNIX *bind* succeeds under `%LOCALAPPDATA%\Temp` but *connect* fails with
`SocketException: Invalid argument`. `C:\gradletmp` works. Create it once with `mkdir C:\gradletmp`.

`GRADLE_OPTS=-Djava.io.tmpdir=...` alone is **not** enough — the launcher JVM fails before applying
it. The env vars are what actually fix it.

Toolchain: JDK 17, Android SDK with platform 35 / build-tools 35.0.0, AGP 8.9.2, Gradle 8.11.1,
Kotlin 2.0.21, Compose BOM 2024.10.01. minSdk 31, landscape-locked.

## Where the firmware and the spec disagree

Verified against the firmware source. Three of these fail *silently*:

| Reality | Evidence | The spec claims |
|---|---|---|
| Messages end **`\r\n`** | `App/ble.c` format strings | `\n`. Splitting on `\n` alone leaves a trailing `\r` and every match fails |
| **11 slots**, not 9 | `MODULE_COUNT` in `Core/Inc/config.h` | "exactly 9 modules"; Seq 4/5 emit `RUN:11`, `MOVE:8>10` |
| `MOVE` covers **swipes too** | one unguarded `ble_move()` in `start_trip()` | §14 forbids animating the arm for swipes, but gives no way to tell them apart |
| `RUN:<slot>` **repeats** with no `DONE` | warm-macro path emits it twice | undocumented |
| `READY` is sent **once at boot** | `app_init()` | §30 implies it arrives after connecting |
| `LOT:DONE` fires at the **start** of the exit swipe | `App/lotmgr.c` `LP_EXIT` | undocumented |

Since robot trips and conveyor swipes are byte-identical on the wire, they are separated by
**destination role** (`SlotMap.transferKind`):

- destination is a blender or vessel (4, 5, 9, 10) → **robot arm animation**
- destination is packaging or bottling (6, 11) → **conveyor chevron band, no arm**

If the recipes in `App/lotmgr.c` change, `SlotMap` must change with them.

### Slot map

| 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 |
|---|---|---|---|---|---|---|---|---|----|----|
| Macro1 | Macro2 | Gravimetric | Blender1 | Blender2 | Packaging | LMacro1 | LMacro2 | Vessel1 | Vessel2 | Bottling |

Nine are calibrated by hand. Packaging (6) and Bottling (11) are fixed special stations per §7.

## Architecture

```
BleController ──bytes──> LineAssembler ──lines──> McuProtocolParser ──events──┐
                                                                              v
                                                              PlantStateReducer (pure)
                                                                              │
                                            RobotStateMachine (pure) <────────┤
                                                     │                        │
                                                RobotPoser ──> Kinematics ──> ArenaCanvas
```

Everything from `LineAssembler` through `Kinematics` is pure Kotlin with no Android or Compose
dependency, which is why the majority of the behaviour is covered by fast JVM tests.

- `protocol/` — wire format, slot map, timings mirrored from `config.h`
- `domain/` — plant state, the §21 robot state machine, transfer queue policy
- `render/` — geometry, palletiser IK, Canvas artists
- `ble/` — GATT lifecycle, operation queue, profile resolution, ARRIVED extension point
- `calibration/` — edge points and DataStore persistence
- `sim/` — scripted firmware traces for hardware-free development

## Demo mode

Open the panels and pick a trace. Scripted Seq 1/2/4/5 replays feed the **same** assembler and
parser the BLE path uses, chopped to 20-byte packets to mimic the BT05's MTU, so demo mode
exercises the whole stack rather than bypassing it. A "Malformed input" trace covers §20.

## BLE

The rig uses an **MLT-BT05 / AT-09** (CC2541) — despite the name it is BLE, not Classic SPP:
service `0xFFE0`, characteristic `0xFFE1`. Clone firmware varies, so `ProfileResolver` falls back
through Nordic UART and then "any notifiable characteristic", and the resolved profile plus the
full GATT tree is shown in the debug panel. **Confirm it there on first bring-up rather than
assuming.**

Scanning is deliberately unfiltered: BT05 clones often advertise no service UUIDs, so filtering on
`0xFFE0` would hide the very device you want. Likely bridges are ranked first instead.

## Robot geometry

A 4-axis palletiser with a **parallelogram linkage** (per the reference machine), not a generic
6-axis arm. The linkage holds the forearm and end effector horizontal in every pose — the signature
look, and it also removes wrist pitch as a variable, reducing the solve to 2-link planar IK.

The arm proportions in `ArenaGeometry` are **not** free choices: they are sized so the effector can
cross every screen edge, including the worst case (a bottom-edge module offset by one forearm
length). `ReachDiagnosticTest` asserts this for all 11 slots across three aspect ratios — re-run it
if you touch those constants.

### Depth and motion

The machine is drawn flat but reads as solid, via four things in `DrawUtils.kt` / `RobotArtist.kt`:

- **Extrusion** — every part is drawn twice, a dark side wall offset along the key-light direction
  and then the lit face on top. This is what turns a silhouette into a solid.
- **Cross-axis gradients** on limbs and **radial shading** on round parts, so castings read as
  cylinders rather than stripes. Joint housings get a machined rim, a recessed face and a bolt
  circle whose bolts each catch the light.
- **Grounding** — a contact shadow under the base, a faint mirrored reflection on the floor, and a
  receding perspective grid converging on a horizon at the rail line.
- **Motion** — `RobotPoser` tracks frame-to-frame movement and hands the artist a `motion` value.
  Cables sway and sag with it, the payload lags slightly to suggest mass, and streaks trail the
  carriage while it travels. Travel eases in and out; reaching uses a gentle overshoot-and-settle,
  like a servo arriving on target. That easing returns exactly 1.0 at the end of a phase, so
  full-reach positions — and the reach tests — are unaffected.

### Previewing without a device

`PosePreviewTest` writes `app/build/pose-preview.svg`, a sheet of solved poses.

The **geometry** in it is the real thing — it runs the production `RobotPoser`, `Kinematics` and
`ArenaGeometry`, so a proportion or reach mistake shows up there rather than on the phone. The
**shading** is a deliberate re-implementation of `RobotArtist` in SVG: same part hierarchy, same
palette, same extrusion and gradient treatment, but separate code. It previews the intended look;
it is not proof of what Compose draws.

## Known limitations

- **"Waiting on a bottleneck" cannot be shown.** The rig's LEDs distinguish blocked-and-waiting
  from idle, but the wire protocol carries only `RUN` and `DONE`. The app renders `DONE` neutrally
  rather than guessing — showing it would need a new firmware message, which §29 currently forbids.
- **`ARRIVED` is not implemented**, because the firmware has no receive path at all. The extension
  point (`TransferAck`) is wired to a no-op; adding it later is one binding swap. There must never
  be a timeout on it (§29).
- The Compose artwork and the UI cards have been compiled and their geometry verified, but are
  **not yet seen on a device** — no emulator or phone was attached during development. The SVG
  preview approximates the robot; nothing previews the Compose UI cards at all.
