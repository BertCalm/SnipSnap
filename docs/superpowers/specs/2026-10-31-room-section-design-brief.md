# ROOM: a drum-room rack section. Design brief

**Status:** built in Kotlin (`Room.kt`, a rack section after SPRING). The Python prototype's gate passed on 2026-10-31 ("Heard at the prototype gate" below); the Kotlin gate (`./gradlew :synth:generateRoomAudition`) passed on 2026-11-01 ("Heard at the Kotlin gate" below), and ROOM is a pedal, the `roomed` character. Prototyped first in `2026-10-31-room-section-prototype.py`, beside this file, which now holds round 2.
**Source:** the owner picked ROOM from a Luthier gap scan on 2026-10-30. The scan found that the rack's only synthetic space is SPRING, a 1962 Schroeder network. No reference sound was named, so this brief aims at a versatile drum room, tight to medium.
**Repository truth:** commit `bb19dc0f` (2026-10-30). Grepped and read:
- `Spring.kt`: four combs, two all-passes, `MAX_TAIL_SECONDS = 1.2`.
- `FxChain.kt`: `SECTIONS` at lines 220 to 241, `MAX_CHAIN_TAIL_SECONDS = 1.0` at line 168.
- `ChamberRoom.kt`: first-order image sources for the CHAMBER engine only.
- `OpenSky.kt`: a private 8-line Hadamard `Room` class, around line 490.
- `Circuit.kt:618`, `Murk.kt` and `Chamber.kt`: Householder scatterings.
- `audio/Body.kt`: the keyed `bodied` treatment.
- Where SPRING is used: `Shuffle.kt:90,93` (`reversed`, `washed`), `StrikeDemoKit.kt:17`, `Wide.kt`, `shell/SurfaceStore.kt:93-94`.
- `MacroSpec` in `Thump.kt:40`, with its `neutral`.

Nothing in `synth/` computes early reflections for the rack. No shared delay-network helper exists either: each engine carries its own.

**The characteristic gesture is: hit → the walls answer (2 to 20 ms) → the air blooms dense → it settles darker.**

## What it is

ROOM is a per-pad processing stage that puts a hit in a room. The direct sound comes first. Then come the walls' first answers: real image-source reflections from a box-shaped room, up to two bounces, each one darker than the last. A dense tail from a 16-line delay network takes over from the second bounce. Three plain knobs set the room's size, its walls and the mic's distance. Like every rack section, the output is peak-matched to the input, and DISTANCE 0 is a hard bypass.

## Why it earns a slot (sibling check)

| Sibling | What it does | What it cannot do |
|---|---|---|
| SPRING | Schroeder reverb: SIZE, TONE, MIX | No early reflections. Measured: it rings and flutters (see below). It never becomes dense at SIZE 0.4 and up. |
| ECHO | Tape delay | Discrete repeats, not a room |
| OUTSIDE ROOM (capture) | A real room through the phone's speaker and mic | Needs the phone and a real room |
| CHAMBER (engine) | Modal instruments in a room with material boundaries | It's an instrument, not a pad treatment, and CLI only |
| WIDE (board) | tape → EQ → ensemble → SPRING | Inherits SPRING's tail |

Changing SPRING itself would change every shipped sound built on it: `reversed`, `washed`, WIDE, the strike demo kit and the two SPRING surface corners. So ROOM is a new section, and SPRING stays as it is.

## Landing: a rack section, placed after SPRING

The order is **ECHO → SPRING → ROOM → MOTION**. The rack's own rule is "space always last: echoes belong in the room". A spring tank is a device, and the room is where everything, the tank included, is heard. MOTION still comes last, because the tape stops with the room on it.

## Signal flow

```
 pad ──┬───────────────────────────── × direct (1/r against a 0.4 m mic) ─────────────┐
       │                                                                              │
       ├─► image sources, 1 and 2 bounces (24 taps, jittered ±0.4 ms per bounce)      │
       │      each bounce: one-pole low-pass at the wall's corner (WALLS)  ──► early ─┤
       │                                                                              ├─► + ─► 20 Hz HP ─► peak match ─► trim at -60 dB
       └─► at the first 2-bounce arrival: 2 short all-pass diffusers (142, 107)       │
              ─► 16-line delay network: orthonormal Hadamard feedback,                │
                 lines 15 to 80 ms scaled by SIZE, Jot's damping per line ─► late ────┘
                 (RT60 from Sabine: 0.161 V / (S α), held under 0.9 s)
```

Everything that depends on the macros (the 24 tap times and gains, the line lengths, the gains and the damping coefficients) is a pure function of SIZE, WALLS and DISTANCE. It's computed once per render.

## Macro contract

| Control | Default | What it changes | Neutral | Proven by (proposed test) |
|---|---|---|---|---|
| SIZE | 0.4 | Room dimensions on a log scale, from 2.4 × 2.0 × 2.2 m to 14 × 10 × 6 m. That sets reflection times, line lengths and the Sabine volume. | any | The first-reflection delay and the measured RT60 both rise monotonically |
| WALLS | 0.5 | Absorption from 0.55 (soft) to 0.06 (hard). Wall corner from 3.5 to 14 kHz. High/low decay ratio from 0.25 to 0.75. | any | RT60 rises and the tail's spectral centroid rises, both monotonically |
| DISTANCE | 0.5 | Mic distance from 0.4 m to the far corner. 0 means no room. The direct sound falls as 1/r while the room doesn't. | 0 | Bypass at 0; the direct-to-room ratio falls monotonically |

Required interaction: SIZE × WALLS together set the decay through Sabine. A big room with soft walls and a small room with hard walls can have the same RT60 but different reflection timing. That pair is what the gate's SIZE and WALLS questions listen for.

## What the prototype found (Python harness, not Kotlin)

The Python SPRING port matches the Kotlin render of a THUMP snare to a maximum error of 1.3e-7, at the same length. So the comparison below is SPRING itself, not an imitation.

Meters run on impulse responses with the house 20 to 40 Hz high-pass. A reference tail of decaying Gaussian noise (RT60 0.55 s) reads:
- dense after 5 ms (the meter's floor),
- flutter 0.036,
- late peak 11.3 dB.

| | Dense after | Flutter | Late peak | RT60 |
|---|---|---|---|---|
| SPRING SIZE 0.1 / 0.35 (default) / 0.4 / 0.8 | 173 / 398 / never / never ms | 0.17 / 0.20 / 0.25 / 0.15 | 11.8 / 17.2 / 15.6 / 15.1 dB | 0.22 / 0.32 / 0.36 / 0.69 s |
| ROOM booth / default / live / big | 71 / 82 / 113 / 189 ms | 0.15 / 0.11 / 0.16 / 0.08 | 14.2 / 16.8 / 12.3 / 12.1 dB | 0.14 / 0.43 / 0.73 / 0.70 s |
| ROOM soft / hard walls (SIZE 0.4) | 107 / 102 ms | 0.23 / 0.21 | 14.7 / 13.5 dB | 0.15 / 0.80 s |

**What the numbers say:**
- **Density:** ROOM turns dense two to five times sooner than SPRING.
- **Flutter:** ROOM flutters less at most settings, but soft and hard walls flutter about as much as SPRING.
- **Tonal peaks:** about equal. Neither reaches diffuse.

The structural difference has no meter: ROOM has real wall reflections and a mic distance, and SPRING has neither. The ear has to judge that.

**What the prototype got wrong first, kept because each one shapes the Kotlin:**
1. **Short lines sang.** Eight delay lines scaled down to the room's mean free path (a few ms in a small room) gave flutter of 0.27 to 0.69 and strong tonal peaks.
2. **A long input diffuser fluttered.** A 379-sample all-pass made a regular 8.6 ms flutter, so only the short diffusers stayed.
3. **One damping filter for all lines** lets modes in the same band decay at different rates. Jot's per-line filters fix that. They need a clamp (coefficient ≤ 0.9), because soft walls with long lines pushed the coefficient past 1 and diverged.
4. **Lines 7 to 38 ms** still gave tonal peaks of 14 to 21 dB. Lines 23 to 110 ms reached diffuse levels (9.5 to 12.7 dB) but filled in after 154 to 387 ms. Lines 15 to 80 ms are the compromise, with the reflections covering the first 30 ms.
5. **The level trap.** At the first wet level, ROOM made a snare 5.7 dB louder and a clap 6.7 dB louder at the same peak, against SPRING's +0.1 dB. Louder wins listening tests. `WET_TRIM 0.35` brings the default to +0.5 dB (snare) and +1.6 dB (clap). The far mic and hard walls still play louder (+2 to +3.5 dB), as a farther mic in a harder room should. The gate also has a same-loudness section.
6. **The meter itself.** The first late-peak meter counted bands 100 to 200 dB down, which is numerical dust. It now counts only bands within 30 dB of the loudest.

**Disclosed:**
- The measured RT60 runs below Sabine's: 0.43 s against 0.57 at the default. That's the effect of Jot's damping plus the high-pass. Calibrate it in Kotlin if the owner hears the decay as short.
- At WET_TRIM 0.35 the booth and soft-wall settings are nearly dry, with tails under 0.1 s. The gate asks whether SIZE's small end is still useful.

## Three directions

| | Direction | Operator | Gesture clarity | Novelty | Risk | Cost |
|---|---|---|---|---|---|---|
| **Strong** | **ROOM**: geometric reflections plus a Jot/Hadamard tail, as above | DISTIL | high: walls, then air | the rack has no early reflections | tonal peaks are no better than SPRING's | one section, about 300 lines |
| A | **PLATE**: the delay network alone, no geometry. Dense from the first millisecond (the 80s snare). | PORT (OPENSKY's Room) | medium | a second "reverb" beside SPRING | competes with SPRING for the same job | smaller |
| B | **SPRING 2**: more combs and diffusion inside SPRING | MUTATE | low | none | changes every shipped sound built on SPRING | small, but it breaks recipes |

ROOM wins because it adds the one thing no part of SnipSnap has, a room's first reflections, without touching a shipped sound. PLATE could come later as a WALLS extreme, or as its own voice, if the gate asks for dense-from-the-start.

## Risks

- **Tail budget.** ROOM bounds its own tail to 1.0 s (Sabine is held under 0.9 s, then a 50 ms fade). The rack's `MAX_CHAIN_TAIL_SECONDS` still trims a stack of ECHO, SPRING and ROOM.
- **Stability.** The Hadamard matrix is orthonormal. Every line gain is under 1, and Jot's filter has DC gain g and |H| ≤ g. The coefficient clamp is a stability condition, so a test must prove the clamp: remove it and the soft-wall render must fail a finite or never-grows probe.
- **Render time** (proposed, not measured). In Kotlin, ROOM runs on the signal directly, since it's linear: 24 sparse taps plus 16 lines (a read, a filter, a write and a 64-add Hadamard). That's about 200 operations per sample, roughly 13 M for a 1.5 s pad, so tens of ms on a phone, within the instant-loop rule. Measure it with the section's test.
- **Stereo.** The rack runs sections per channel. ROOM's jitter is seeded with a fixed `Dsp.seedFor("ROOM", "walls")`, so identical channels stay identical (FxTest's contract). Real stereo (two mic positions) is a later, separate decision.
- **Determinism.** No noise at render time beyond the seeded jitter, computed once.

## Cheapest proof (the step in progress)

The prototype gate (an artifact) asks seven questions:
1. Does SPRING ring?
2. ROOM or SPRING, at the same loudness?
3. Is SIZE's range right?
4. Can you hear WALLS?
5. Does DISTANCE sound like the mic moved?
6. Does ROOM help a recorded chop?
7. What happens to SPRING?

The answers decide whether Kotlin is built and with what changes.

## Integration (each row greps at `bb19dc0f`)

| Where | What changes |
|---|---|
| `synth/.../Room.kt` | New: `object Room` with `MACROS` (SIZE 0.4, WALLS 0.5, DISTANCE 0.5 with neutral 0), `process`, `MAX_TAIL_SECONDS = 1.0` |
| `FxChain.kt:240-241` | A `Section("room", Room.MACROS, …)` row between spring and motion. An appended `room` field. The order KDoc gets ROOM, and its line 11 order arrow gets FORGE, which it is missing today. |
| `FxTest.kt` | Covered by the shared contract through `SECTIONS`. The order assertion names ROOM. |
| `RoomTest.kt` | New: bypass at DISTANCE 0, each macro's monotonic claim, the clamp guard, tail ≤ 1.0 s, never grows, determinism, render time |
| `Treatments.kt`, `Pedals.kt` | Nothing until the gate decides whether ROOM gets a character or a chip |
| A shared helper | Optional: lift the orthonormal Hadamard step into `Dsp`. OPENSKY, CIRCUIT, MURK and CHAMBER each carry their own scattering. |

## Ledger

- **Verified** (read on `bb19dc0f`): the rows under "Repository truth" above; the Python SPRING port against the Kotlin render (1.3e-7).
- **Measured** (Python harness): every number in "What the prototype found". The merged prototype file reproduces the scratch renders to 16-bit rounding (3e-5).
- **Proposed:** every ROOM constant: room dimensions, absorption range, wall corners, line spread, WET_TRIM, the 0.4 m close mic. Also the placement after SPRING and the render-time estimate.
- **Heard:** nothing.

## Why it is built this way

- **Two parts, because a room has two parts.** The first 20 ms of a real room are a few distinct reflections off the nearest walls, and that's what tells your ear the size and the distance. After that the reflections pile up into a smooth wash. ROOM computes the first part from geometry and the second with a delay network. SPRING has only the second kind, made from four combs, which is why it buzzes.
- **Sixteen lines, 15 to 80 ms.** Short lines ring like a tube. Long lines take a while to fill in. The measurements found the middle, and the wall reflections cover the gap at the start.
- **Each line gets its own damping.** If every line lost its highs at the same rate per pass, longer lines would lose more per second, and some notes in the tail would outlive their neighbours and sing. Jot's method sizes each line's damping to its length, so the whole tail darkens together.
- **DISTANCE, not MIX.** A mix knob only turns the room up. Moving the mic back changes how loud the drum is against the room and how soon the walls arrive, which is what a far mic sounds like. At 0 there's no room at all, so turning ROOM off by fading it is natural.
- **The risky decision is the level.** Peak-matching makes any reverb play louder at the same peak, and louder sounds better. ROOM's level is set so its default barely raises loudness, and the gate has a same-loudness section so the room has to win on its own.

## Prompt notes for the next round

A named reference ("the snare on <song> at 0:12", "a tight 90s boom-bap room", "a big 80s hall") would steer SIZE and WALLS more than any meter here. If the gate's SIZE answer asks for smaller or bigger rooms, say which drum it was on.

## Heard at the prototype gate (2026-10-31)

| Question | Owner's answer | What it settles |
|---|---|---|
| 1. One click through each: does SPRING ring or flutter? | Neither rings | The meters' flutter in SPRING (0.15 to 0.25 against diffuse 0.04) is not what the ear hears as ringing. ROOM's case does not rest on a SPRING defect. |
| 2. At the same loudness: which sounds more like a real room? | ROOM, "but spring has a cool sound as well" | ROOM is built. Its case is that it sounds like a room. |
| 3. SIZE, booth to big: is the range right? | Want bigger rooms | SIZE reaches a hall (round 2) |
| 4. WALLS, soft against hard: is the difference clear? | Clear | WALLS stays as designed |
| 5. DISTANCE: does far sound like the mic moved back? | Just more reverb | DISTANCE reworked (round 2) |
| 6. On the chop: does ROOM help or muddy it? | Helps | ROOM is not drums-only |
| 7. If ROOM ships, what happens to SPRING? | Keep both | A new section; SPRING and every recipe built on it are untouched |

## Round 2: what the answers changed (Python, then Kotlin)

**DISTANCE was "just more reverb", and the meters agreed.** Round 1's DISTANCE changed one thing, the drum-over-room ratio (12 down to 1 dB). The first-reflection gap barely moved (3.7 to 2.3 ms), the drum's own tone never changed, and the early-to-late balance stayed flat (0.5 to 0.7 dB). Round 2 adds what a mic moving back actually does to the drum:
- a close mic lifts the bass (proximity: up to +5 dB under 150 Hz at 0.4 m, gone by 2 m);
- a far mic hears less top (−3 dB above 3.5 kHz per doubling of distance past 0.4 m). That's exaggerated beyond air absorption, as a cue for the drum turning away.

The log walk put round 1's default mic closer (1.1 m at DISTANCE 0.5, against 1.67 m), so the default DISTANCE moves from 0.5 to 0.6 (1.35 m). The default room is a little drier than round 1's. Disclosed for the gate's default-room question.

**Bigger rooms.** SIZE now reaches 28 × 20 × 11 m, up from 14 × 10 × 6. The default moves from 0.4 to 0.3, close to the room heard at the gate (4.9 × 3.8 × 3.3 m then, 5.0 × 4.0 × 3.6 m now). Making SIZE bigger exposed three round-1 faults:
1. **The mic walked across the floor linearly**, so a hall's default mic stood more than 10 m out. There the floor bounce lands with the drum, and the first reflection came sooner in bigger rooms (3.5 ms down to 0.5 ms). The mic now walks out on a log scale, from 0.4 m to the far corner.
2. **Sabine's diffuse level falls as a room grows,** so the hall came out drier than the default (drum-over-room +13.8 against +3.8 dB). The late level now follows the decay (energy in proportion to RT60), anchored to round 1's default room so its level stays as heard. Drum-over-room now falls with SIZE, at DISTANCE 0.5: +10.9 dB (booth), +8.9 (SIZE 0.3), +5.5, +3.3, +1.1 dB (hall).
3. **The network's measured decay ran 0.75 to 0.78 of Sabine's.** `RT_CALIBRATION` 1.3 brings small and middle rooms onto it (Kotlin: 0.377 against 0.375 s, 0.626 against 0.607 s, 0.894 against 0.9 s with hard walls). Halls land at about 0.7 s against their 0.9 s target (disclosed).

The rack's 1-second tail budget still holds. A hall's decay stays under it, so "bigger" comes from later reflections, longer lines and more room in the mix, not from a longer tail. If the owner still wants bigger, the next step is raising `FxChain.MAX_CHAIN_TAIL_SECONDS`, which is the owner's decision.

## As built (Kotlin, 2026-10-31)

`Room.kt`: an `object Room` rack section with SIZE (default 0.3), WALLS (0.5) and DISTANCE (0.6). Every neutral is 0, so a fade takes the room away, and DISTANCE 0 is a hard bypass. `Room.plan` computes everything the knobs decide, once per render:
- the room, the mic and the 24 jittered taps (jitter from `Dsp.Noise(Dsp.seedFor("ROOM", "walls"))`);
- the bloom's start, the 16 prime line lengths, their gains, and Jot's coefficients, clamped at 0.9;
- the direct sound's shelves (`Dsp.Biquad.lowShelf` / `highShelf`).

`Room.process` runs on the signal directly, since the section is linear: the direct sound with its tone, the taps into one-bounce and two-bounce buffers (darkened once and twice by a `Dsp.FixedOnePole` at the wall's corner), and the bloom. The bloom's level comes from its response to one click, measured once per render and scaled to the diffuse level the decay calls for. Channels are peak-matched together. The tail stops 60 dB under the input's peak, with a 10 ms fade, never past `MAX_TAIL_SECONDS` (1.0 s).

**Departures from the prototype**, none heard yet:
- the jitter values come from `Dsp.Noise`, not NumPy;
- the 40 Hz and 20 Hz high-passes are one-pole pairs, not Butterworth sections;
- direct processing replaces convolution.

**`FxChain`:** a `room` row between spring and motion, and an appended `room` field. The order KDoc gains ROOM and a placement paragraph. Its order arrow also gains FORGE, which had been missing since #680.

**Tests.** `RoomTest`, with every bar from a measurement:
- **Contract:** the macros and their neutrals; the DISTANCE 0 bypass; unknown and non-finite keys; the tail inside the budget, ending faded.
- **Never grows:** the tail's last 100 ms peak under half its first 100 ms.
- **SIZE:** the bloom starts later (416, 722, 1273, 2114 samples), the room's share of the energy rises (0.0013, 0.0084, 0.0219, 0.0302), and tails lengthen.
- **WALLS:** measured RT60 of 0.134, 0.626 and 0.894 s.
- **Calibration:** the decay lands within 10% of Sabine's in small and middle rooms.
- **DISTANCE:** the mic moves back, the bass lift falls, the top falls, and the room's share rises. On a hat, the top share in the first 10 ms falls from 0.882 to 0.715.
- **The clamp:** a soft hall is finite and decays with it, and diverges to NaN without it.
- **Classes:** a snare stays SNARE and a kick KICK.
- **Speed:** a snare in a hall renders in 48 ms.
- **Edges:** silent and empty pads pass through.

Two of these were proven able to fail. Flipping the decay's sign (lines that grow) fails the never-grows test, the clamp test and the calibration test. Raising the clamp to 10 makes the soft hall's network reach NaN.

`FxTest` adds "ROOM sits after SPRING and before MOTION", and the shared contract covers the rest: determinism, finite output within 0.05 of the peak at six random settings, and identical stereo channels.

**The audition:** `RoomAuditionGenerator` (`./gradlew :synth:generateRoomAudition`, 61 clips) with its page, `room-audition.html`, a copy of FORGE's with ROOM's legend and its own answer store.

**Disclosed, not fixed:**
- Halls decay at about 0.7 s against 0.9.
- SIZE 0 and soft walls are nearly dry: tails of 0.02 s at SIZE 0 and with WALLS 0.
- At the same peak, the hall plays 7.0 dB louder over the hit on a snare and 6.4 dB on a clap. The gate's same-loudness section exists for this.
- ROOM had no character and no chip. The gate asked, and the owner said yes (below).

## Heard at the Kotlin gate (2026-11-01)

The owner answered all five questions "yes", read against each question's first option:

| Question | Answer | What it decided |
|---|---|---|
| SIZE now reaches a hall. Big enough? | Big enough | SIZE stays as built. The rack's 1 s tail cap stays; nothing asks for a longer fade. |
| Does far DISTANCE sound like the mic moved back? | Yes | Round 2's log walk of the mic did what "just more reverb" asked. DISTANCE stays. |
| At the same loudness, does the hall still sound bigger? | Bigger | The hall is the room, not the level. The 7 dB the hall gains at the same peak is a disclosure, not a fault. |
| The default room on the kit: a good starting point? | Good | The defaults stay: SIZE 0.3, WALLS 0.5, DISTANCE 0.6 (1.35 m). |
| A one-tap character on the pad sheet? | Yes | ROOM becomes a pedal (below). |

**The chip.** `roomed` joins `Treatments.EXTRA`, and `Pedals` gains ROOM: after the amp, last, not movable (a room in front of an amp is not a place), gated. The slate draws it with no shell code; it sits behind MORE, because the default row is the owner's five (DRIVE, SUSTAIN, WAH, FORGE, DELAY), and any user can put it on their row.

**One departure from FORGE's pattern.** FORGE's chip sets every macro at its default. ROOM's first did too, and its test caught the sheet's tap (AMT 0.7) fading all three macros at once: a smaller room, softer walls and a closer mic, which multiply. The snare's room share fell from 0.0013 at AMT 1 to 0.000008 at AMT 0.7, a tap next to dry. So `roomed` sets only DISTANCE, at its default, and leaves SIZE and WALLS to ROOM's defaults, as `ensembled` leaves out RATE. AMT 1 is the default room exactly; lower AMT moves the mic closer in the same room (share 0.0006 at AMT 0.7, about half); AMT 0 is ROOM's bypass.

**Tests.** `RoomTest` pins the chip at AMT 1 to `Room.process` at its defaults, sample for sample, on a kick and a snare; the one restated number (DISTANCE) to ROOM's own default; and the AMT 0.7 tap as less room than full but over 0.3 of it. `PedalsTest` and `TreatmentsTest` pin the new lists, and ROOM's side as POST only.

**Not heard yet.** Nobody has heard the chip at AMT 0.7, the sound a first tap gives. It is the default room with the mic about 0.94 m back (DISTANCE 0.42), against 1.35 m at full.
