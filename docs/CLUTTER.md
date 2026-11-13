# CLUTTER: design brief and round-0 audition

Status: **round 0, a Python prototype audition; nothing heard.** No Kotlin exists. The engine stays in
progress until every applicable owner gate has evidence (see "Gates" below).

Prototype: [`prototype/clutter/`](../prototype/clutter/) (`clutter_proto.py` is the model, `measures.py` the
house distance measures, `gen_audition.py` renders and measures, `build_page.py` writes the audition page).
Every number below is from that Python harness, not from Kotlin.

Listening page: <https://claude.ai/artifact/6dkeY2rrjNGUvUYvobs3v5> (private to the owner; published
2026-11-13). It carries a playable 4×4 pad grid with three velocity layers and four round-robin takes per
pad, the kit, velocity ladders, takes, and every control on every object.

Repository truth: read at `523aacfb` (2026-11-13). APIs named here were grepped: `Modes.ring`,
`Modes.Mode(ratio, gain, t60, pan)`, `Dsp.seedFor`, `Dsp.Noise`, `Room` (`synth/.../Room.kt`: SIZE, WALLS,
DISTANCE), `PadHit.resolve` (`shell/.../PadHit.kt`), `Soot.render(voice, macros, velocity, take, model)` as
the drum-engine shape to copy. `CLUTTER` is not used as a name anywhere in `synth/`, `shell/` or `docs/`
(it appears once in prose in `docs/UAT_2026_09.md`).

## What it is

A drum engine whose voices are everyday objects, modelled rather than sampled: the found-sound kit that
street bucket drummers, kitchen-table beatmakers and junk percussion bands build by hand. The owner asked
for "a found sound style drum kit" on 2026-11-13 and chose synthesized objects over recorded ones.

**The characteristic gesture is: a hand or a spoon hits an everyday object → its body rings → whatever is
loose in it or on it rattles a moment later → the kitchen answers, then it is still.**

Working name CLUTTER (the stuff on the counter). No product or maker is named anywhere.

| Pad | Object | Class | What rings | What is loose |
|---|---|---|---|---|
| A01 BUCKET | upturned plastic bucket, palm on the base | KICK | trapped air (a falling boom) + clamped plate | the wire handle bouncing on the floor |
| A02 BOX | cardboard box with dried rice inside, slapped | SNARE | rectangular panel, hit at (x, y) + box air | the rice, thrown up and landing |
| A05 BOOK | hardback slammed shut | CLAP | board cover; pages meeting in 2 to 6 taps | page flutter |
| A03 JAR | glass jar of rice, one flick | HAT_CLOSED | the glass wall | the contents (sand, rice or coins) |
| A04 KEYS | a ring of keys shaken | HAT_OPEN | 3 to 9 keys as free-free bars + the split ring | the keys themselves |
| A06 TABLE | knuckle on a wooden table | PERC | dense wooden plate + the knuckle's bone | a fork and a spoon jumping |
| A07 POT | saucepan struck on its side | PERC | thin-ring modes in beating pairs + the base | the lid chattering |
| A08 BOTTLE | glass bottle tapped with a key | PERC | thin-ring glass modes + the air inside | the bottle rocking on its base |

## Why it earns a pad (sibling check)

The roster has 95 registered engines at `523aacfb` (`Patches.kt`), and the drum side has grown fast:
THUMP, SKIN, TERRA, ONSET, RIVET, SOOT, SCUFF, BREAKER, FIRED BRONZE, plus the TRIAD and boom-bap kits.

- **RIVET** (industrial drums in a building) is the nearest: metal, drive and a room. Its voices are drum
  roles played on drums. It has no loose parts and no household materials (cardboard, glass, paper).
- **SOOT** (boom bap from physical models: skin knock, papery crack, coil clatter) models real drums and
  a dictaphone. Its coil is a snare's wires, a fixed part of a drum.
- **TERRA BOX** is a cajón: a wooden box with wires. The rice box is cardboard (far lossier) whose buzz
  comes from grains in flight, so it arrives later and only above a threshold.

What none of them do: **parts that only move once the strike is hard enough to lift them.** Rice, a lid, a
handle and cutlery stay put on a soft hit and fly on a hard one, land after their flight time (2 v / g)
and bounce. That turns velocity into a change of what happens, which is exactly the owner's
velocity-as-timbre gate, and it is the sound people mean by "found": things that are not built to be
instruments, and so rattle.

## Signal flow

```
note, velocity, take, macros
        │
        ▼
[contact pulse] area = impulse J (velocity^1.2), width = TOOL (palm 7 ms .. metal 0.2 ms) × velocity^-0.2
        │
        ├──(force @176.4 kHz)──▶ [object body: modal bank, strike-position weights per take] ──────────┐
        │                                                                                              │
        └──▶ lift = (J − threshold) / (1 − threshold), threshold fixed, RATTLE loosens it               │
                 │                                                                                     │
                 ▼                                                                                     │
          [loose parts] grains: land at 2 v0 / g, bounce once │ parts: v(k+1) = e·v(k), rocking pairs   │
                 │                                                                                     │
                 ├──▶ their own modes (grain clicks, lid, handle, keys, fork)  ───────────────────────────┤
                 └──▶ a small kick back into the body (one-way, never re-thrown) ─────────────────────────┤
                                                                                                       ▼
                     bandLimit → decimate ─▶ [MIC: phone tone + kitchen response, cache: MIC] ─▶ DC → level → fade
```

The loose parts are a one-way readout: they are scheduled from the strike and then re-excite the body;
the body never throws them a second time. That keeps the cost fixed. Disclosed, not fixed.

## Macro contract

Five controls plus the strike's velocity and the take. No HOLD: every hit is finite (the SOOT, SCUFF and
RIVET position). TOOL and RATTLE are read per object, because what you hit a jar with and what is loose in
a pot are different things.

| Control | Default | What it changes in the model | Required interaction | Measured 0 vs 1 (weakest object) |
|---|---:|---|---|---|
| TUNE | .50 | the object's size: every mode × 2^(±7/12) | none | 4.3 dB (TABLE) |
| TOOL | per object | contact width: palm, wood, metal (jar: sand, rice, coins inside); a metal tool adds its own ring | TOOL × velocity: both shorten the contact | 3.3 dB (BOOK) |
| RATTLE | per object | how much is loose and how loosely: the lift threshold and the part count | RATTLE × velocity: the threshold is crossed by the strike | 3.7 dB (JAR) |
| DAMP | per object | a hand on the object: every t60 × 1 .. 0.15, higher modes faster | DAMP × MIC: a damped hit leaves less for the room | 3.1 dB (TABLE) |
| MIC | per object | the phone: 12 cm (proximity lift) to 2.8 m across a 3.6 × 3.0 × 2.5 m tiled kitchen (image sources, first and second order; a 0.32 s tail; capsule band and presence) | MIC × DAMP | 4.8 dB (POT) |

The distance is the house measure over the whole render (10 ms frames, third octaves, floored 80 dB under
the loudest). Every control moves every object by at least 3.1 dB.

## Three directions

1. **Strong (SPLICE + COUPLE): objects with loose parts.** The design above. Each object is a modal body
   with a sourced mode table where one exists (thin-ring flexure n(n²−1)/√(n²+1) for the pot and the
   bottle; clamped and free circular plates for the bucket base and the lid; free-free bars for keys and
   cutlery; a simply supported rectangle for the box), and every object shares one loose-parts
   mechanism. Gesture clarity high; novelty against the roster high (no engine has a lift threshold);
   risk moderate (the rattle can switch on too abruptly); cost one prototype round plus the usual Kotlin
   rounds.
2. **Alternate A (MUTATE presets): a found-sound kit over existing engines.** RIVET at its smallest SIZE,
   TERRA BOX, TINES BLOCK, SKIN hats, through CRUNCH and ROOM. Cheapest, no engine. It cannot do rice,
   glass and air, or anything loose, so velocity stays a brightness change. Novelty low.
3. **Alternate B (COUPLE, riskier): the kitchen as one instrument.** Every object sits on one table, a
   `Modes.Bank` that couples them, so hitting the pot makes the bottle ring and the cutlery jump: each pad
   carries a faint halo of the others. The most "third sound", and the most likely to make sixteen pads
   sound alike (the kit's pads must stay apart). Worth trying later as a TABLE control on Strong, not as
   the engine.

Strong wins because its one new mechanism serves the gate the owner cares about most (velocity changes the
strike) and keeps every pad a different object.

## Round 0: what was built and measured

The audition kit, A01 to A16 (A09 to A14 are the same eight objects at other settings): Bucket, Rice Box,
Rice Jar, Key Ring, Book Slam, Knuckle, Saucepan, Bottle, Bucket Side (TUNE .82: a bucket tom), Empty Box
(no rice, damped), Stockpot (big, the lid loose), Full Bottle, Fork Drop (a fork on the table, hard),
Coin Jar, A15 Pot Roll (a wooden spoon, eighths to thirty-seconds over one bar at 92 BPM, soft to hard) and
A16 Kitchen Groove (two bars at 92 BPM; every hit a fresh take; the jar chokes the keys).

Measured (Python harness, `gen_audition.py` at round 0):

- Deterministic, finite, peak 0.89, DC 0.0 at every zone and take; an unknown control, a NaN control and
  a NaN velocity all fall back to the default render.
- **Velocity changes the strike.** Soft (0.35) against hard (1.0) at matched level: 3.1 dB (Empty Box, the
  one pad with nothing loose) to 25.4 dB (Rice Box). The rice box's centroid goes 554 Hz soft to 4.0 kHz
  hard; the knuckle's 317 Hz to 1.6 kHz as the cutlery joins in.
- **The bucket's boom falls.** Hard: 78 Hz in the first 30 ms, 64 Hz by 100 to 300 ms. Soft starts at 72 Hz.
- **Takes are distinct strikes.** Closest pair 1.9 dB (Empty Box), widest 10.5 dB (Key Ring). ETCH's takes
  that the owner heard as one strike measured 0.5 to 0.8 dB on the unfloored version of this measure.
- **Render time.** Python: 43 ms mean per hit, 103 ms the slowest (the stockpot: 16 beating ring modes for
  1.4 s at 176.4 kHz). The MIC response builds in 2 ms and is cached by MIC alone.

Two defects the probes caught during the round, both fixed before publishing: RATTLE did nothing on the
bottle (0.0 dB between 0 and 1: the rocking was never scaled by the control), and the key ring took 160 ms
per hit (a full-length buffer per collision, now one convolution).

## Risks (where it could sound bad)

| Risk | What would show it | Probe or question |
|---|---|---|
| The rattle switches on like a gate, not a physics | a medium hit that is a clean knock and a hard hit that is a different instrument | the velocity ladder; a fourth zone between .65 and 1 |
| Modal objects read as "generic bell" | the pot and the bottle sound like synth bells | the owner's listen; the mode tables are sourced, the damping is shape |
| The kit sounds like a sound-effects library, not drums | pads that are convincing alone and useless in a beat | A16 groove; genre kits with roles |
| The kick is too light for a phone or an MPC | 64 Hz boom with little above it | centroid 273 Hz hard; BUCKET TOOL sweep |
| The jar's wall rings like a pitch | a closed hat with a note | JAR TUNE and DAMP sweeps |
| The room is the same on every pad | the kitchen as set dressing | MIC is per object; the Kotlin port should call the shared `Room` |

## Gates (owner, `docs/ENGINE_ACCEPTANCE_GATES.md`)

- **Melodic, keygroup and chord tool:** not applicable. A drum engine.
- **Articulation:** finite hits, no HOLD. Not a plucked string.
- **Percussion (roll or riser):** A15 Pot Roll authored in the prototype. Not heard.
- **Drums (several genre kits):** open. One audition kit. Proposed after the owner's verdict on the objects:
  KITCHEN TABLE (bedroom lo-fi, around 82 BPM, close mic, soft tools), STREET BUCKET (busker, around
  120 BPM, hard tools, bright, the room far), MICROBEAT (tiny objects, high TUNE, dry, glitch-adjacent).
- **Velocity and round robin:** rendering measured in Python (above). Saving, playback and export: open; no
  Kotlin yet. The page's pad grid is a browser stand-in for playback, not evidence for the app.

## Next rounds

- **R0b**: the owner's listen; adjust objects, defaults and the rattle curve. Keep every number in the ledger.
- **R1 Kotlin**: `Clutter.kt` (an `enum class ClutterVoice(val drumClass: DrumClass)`, `render(voice, macros,
  velocity, take, model)` as `Soot.render` has it, `MODEL_VERSION`), `ClutterPatch.kt` in the
  `SootPatch` shape, the `Patches.fromJsonValue` arm, the two `Velocity.kt` arms, a `DeterminismTest`
  canary, and `ClutterTest` with the probes above plus "soft leaves the rice still" and "a take replays
  byte for byte". The modal banks go through `Modes.ring` and are peak-normalised per body before mixing
  (the Kotlin gains are not frequency-compensated; the Python is). MIC calls the shared `Room`.
- **R2 kits**: the genre kits with three zones of four takes each, mute group for A03/A04, saved and
  exported through `PadHit.resolve` and the MPC 3 writer, as SOOT and SCUFF did.

## Ledger

| Claim | Status | Evidence |
|---|---|---|
| `Modes.ring`, `Modes.Mode`, `Dsp.seedFor`, `Room`, `PadHit.resolve` exist | verified | grep at `523aacfb` |
| CLUTTER is free as an engine name | verified | grep of `synth/`, `shell/`, `docs/` |
| Every render deterministic, finite, bounded | measured | `gen_audition.py` checks |
| Every control moves every object by at least 3.1 dB | measured | `macro_activity_db` in the manifest |
| Soft and hard differ on every pad; nothing rattles below the threshold | measured; by construction | velocity section |
| Thin-ring, plate and bar ratios are the textbook ones | proposed (sourced formulas, shape damping) | `clutter_proto.py` constants |
| The objects sound like the objects | not heard | the owner's listen |

## Why it is built this way

- **Every object is a body plus its loose parts.** The body is what rings when you hit it; the loose parts
  are what make it sound found rather than manufactured. Take the loose parts away (Empty Box, RATTLE 0)
  and you hear how much of the character they carry.
- **The rattle has a threshold, not a volume knob.** Rice only leaves the panel when the panel accelerates
  harder than gravity holds it down, so soft hits stay clean. That is why a soft and a hard hit differ by
  up to 25 dB in shape at the same level, which is the owner's velocity gate met by physics rather than by
  a filter.
- **TOOL is a contact width with the impulse held constant.** A palm and a spoon that deliver the same push
  ring the low modes the same; only the short contact reaches the high ones. So TOOL brightens without
  getting louder, and a harder hit shortens the contact too (Hertz contact, time ∝ v^−1/5).
- **A take is the hand landing somewhere else.** Each take moves the strike point (which modes it reaches)
  and reshuffles how the loose parts fall, so round robin is four strikes, not four copies.
- **The risky decision** is the one-way coupling: the loose parts never throw the body back into the air.
  It keeps every hit a fixed cost (tens of milliseconds), and the price is that a very hard hit cannot
  start a second chain of rattles. If the listen says the hard hits feel too tidy, that is where to look.
