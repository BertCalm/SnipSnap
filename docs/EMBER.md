# EMBER: design brief and round-0 audition

Status: **round 0, a Python prototype (2026-11-14). Nothing has been heard.** No Kotlin exists. The engine
stays in progress until every applicable owner gate has evidence (see "Gates" below and the gates entry in
[ENGINE_ACCEPTANCE_GATES.md](ENGINE_ACCEPTANCE_GATES.md#ember-round-0-2026-11-14-python-prototype-not-heard)).

Prototype: [`prototype/ember/`](../prototype/ember/) (`ember_proto.py` is the model, `kits.py` the four kits
and their grooves, `measures.py` CLUTTER's house distance measures, `gen_audition.py` renders and measures,
`build_page.py` writes the listening page). Every number below is from that Python harness, not from Kotlin.

Listening page: <https://claude.ai/artifact/Xmcj5t8MYSNCQPoTKHBcdw> (private to the owner; published 2026-11-14). It carries a playable 4×4 pad
grid for all four kits with three velocity layers and four round-robin takes per pad, each kit's groove,
velocity ladders and takes for the EMBER kit, all twenty-six voices, and every control on every voice.

Repository truth: read at `8742c5b8` (2026-11-14). APIs named here were grepped: `Modes.ring`,
`Modes.Mode(ratio, gain, t60, pan)`, `Modes.Friction(a).force(...)`, `Dsp.seedFor`, `Dsp.Noise`, `Room`
(`synth/.../Room.kt`), `BreakerChain.spring`/`Tank`, `PadHit.resolve` (`shell/.../PadHit.kt`), and
`Soot.render(voice, macros, velocity, take, model)` / `Clutter.render(voice, macros, velocity, take)` as the
drum-engine shape to copy. `EMBER` is a working name: the word is already a CIRCUIT preset ("EMBER PULSE"),
an ALLOY preset ("Endless Ember") and a name in `shell/.../Names.kt`. It is not an engine name anywhere.

## What it is

A drum engine for a live jazz and soul kit as it sounded in a warmly lit, smoky club in 1962: creative, a
bit eclectic, a bit rebellious. The owner asked for it on 2026-11-14, with "realistic" and "alive" as the
two words that matter and the owner's acceptance gates as the bar.

**The characteristic gesture is: a stick, a brush or a hand meets calfskin or thin bronze → the head sags
back to pitch while the snare wires and ride rivets answer on their own threshold → the rest of the kit hums
along → a low-ceilinged room and a warm 1962 chain close around it.**

What "alive" means here, as mechanisms rather than adjectives:

- **Calf heads.** Every drum is a circular membrane (Bessel-zero mode ratios, strike-position weights
  `|J_m(z r)|`). Calf's high modes die far faster than its low ones (t60 ∝ (f/f1)^-1.05 to -1.35 by drum,
  shape), a hard hit starts sharp and sags back to pitch (tension modulation, closed-form phase warp), and the
  second head rings the low modes 2.5% higher at a third of the level, so a tom beats as it sustains.
- **Threshold parts.** Snare wires, ride rivets, hi-hat plates and tambourine jingles move only when the
  surface under them moves harder than their own threshold. A soft stroke is clean, a hard one sizzles.
  Velocity changes what you hear, not only how loud it is. This is CLUTTER's loose-parts idea made
  continuous: the wires and rivets follow the body's ringing, not only the strike.
- **The kit as one instrument.** A kick or a tom sets off the snare wires across the kit (BUZZ); the kick
  also stirs the floor tom.
- **Implements.** Sticks (a short Hertz-like contact, longer and darker when soft), brushes (25 to 65 wires
  landing over 2.5 to 7 ms; or dragged in circles as a cloud of micro-slips), felt mallets, hands, a bass
  bow, an elbow leaning into the floor tom.
- **The player.** Four takes per velocity zone move where the stick lands and how it lies, which wires and
  rivets fly, which listeners are early. The grooves sit on a triplet grid with the ride a hair ahead and the
  snare comping behind.
- **The club.** A low ceiling (~2.6 m), a hard bar, a brick back wall, a soft crowd: a short warm room
  (lows ~0.62 s, highs ~0.32 s, shape) balanced by energy from +16 dB direct over the drum to -4 dB at the
  back (DISTANCE). Then the 1962 chain (WARMTH): a ribbon mic's soft top, a valve preamp driven harder by
  harder hits, tape's head bump. WARMTH 0 is an exact bypass.

## Why it earns a pad (sibling check)

The roster is drum-heavy (SOOT, BREAKER, BACKWASH, SCUFF, CLUTTER, GATECRASH, MIRRORBALL, FIRED BRONZE and the
older THUMP, SKIN, ONSET, TERRA). The two nearest:

- **SOOT BRUSHED NOIR** (`SootGenreKits.kt`, 76 BPM) is a boom-bap kit with "brush-weight" snares: SOOT's stick
  snare with its edge turned off, through a dictaphone. It has no brush, no ride, no sweep.
- **BACKWASH SOUL POCKET** is recorded acoustic percussion over procedural core drums, with cross stick,
  cowbell and tambourine. Its drums are not modelled heads and it has no brushes or riveted ride.

Neither can do the three things that make a 1962 jazz kit itself: a **brush sweep** (a continuous friction
gesture on a coated head, not a strike), a **riveted ride** whose sizzle follows the cymbal and dies before
it, and **calf heads that sag and beat** in a small room. Every other drum engine on the roster is a
processed sample-flip sound (dictaphone, surf amp, crushed bus, phone mic); EMBER is the first that aims at
the acoustic kit as it stood on the bandstand.

## The three directions (round 0 took Strong)

| | Direction | Operator | Gesture | Novelty | Risk | Cost |
|---|---|---|---|---|---|---|
| **Strong** | The bandstand: physical kit, threshold parts, the club and the chain, four kits from one night | STACK + PORT (CLUTTER's loose parts onto a drum kit) | clear | high: no brushes, rivets or calf on the roster | medium: many voices | medium |
| Alternate A | The record: a 1962 jazz record sampled, as SOOT and BREAKER do | JUXTAPOSE | clear | low: four engines already do sample-flip | low | low |
| Alternate B | The player as the engine: one "drummer" voice that plays phrases (comping, bombs, fills) | MUTATE | strong | high | high: a phrase engine is a sequencer, not a pad | high |

Strong wins because "realistic" and "alive" are both answered by mechanisms the roster lacks, and its four
kits can be one night (club set, after hours, soul set, the late free set) rather than four effect settings.
Alternate B survives as the grooves' feel and the authored roll and swell.

## Signal flow

```
voice, macros, velocity, take
    │
    ▼
[implement @176.4 kHz: stick | brush tap | brush sweep (micro-slip cloud) | mallet | hand | bow]
    │   width and amount follow velocity; the take moves the strike radius and angle
    ▼
[body: calf membrane (Bessel modes, calf loss, sag, two heads)
       | cymbal: 40 to 226 modes in 3 bands x 4 groups [cache: kind, TUNE, DAMP]; the top band grows in (wash)
       | wood and steel modes (stick, hoop, sticks clicked)]
    │                         ╲
    │                          ──▶ [threshold parts: wires | rivets | hat plates | jingles]
    │                               driven by the body's own motion, one way, scaled to the body
    ▼                                          │
    └──────────────── sum ◀────────────────────┘
    │
    ▼
[DISTANCE: direct + the club's response, by energy [cache: DISTANCE]] ──▶ [WARMTH: ribbon, valve, tape]
    ──▶ band-limit, decimate to 44.1 kHz, DC, cut and fade
```

One-way readouts: the wires and rivets never damp or detune the head or the cymbal. Disclosed; it keeps the
render a fixed cost.

## Controls

Five per voice (four on SNAPS, CLAPS and STICKS), each 0..1, no HOLD (finite hits).

| Control | Default | What it moves | Voices |
|---|---:|---|---|
| TUNE | .50 | head tension or cymbal size, about ±5 semitones (±4 on cymbals, ±6 on kick, toms, conga) | all |
| DAMP | .30 | a felt strip, a wallet on the snare, a finger on a cymbal, the hi-hat pedal pressure | drums, cymbals, hats, tambourine, conga, sticks |
| BUZZ | .50 | how loose the snare wires, rivets, plates and jingles are, and how much the kit hums along. **0 takes the snares off** (exact) | drums, cymbals, hats, tambourine |
| DISTANCE | .30 | the microphone, from over the drum (+16 dB direct) to the back of the club (-4 dB) | all |
| WARMTH | .50 | the 1962 chain: ribbon, valve, tape. **0 is an exact bypass** | all |
| SPEED | .50 | how fast the brush circles, the roll strokes or the swell's mallets come | sweep, rolls, swell |
| BEND | .50 | how far the elbow presses the floor tom: 0.4 to 5 semitones | elbow tom |
| PRESSURE | .50 | how hard the bow bites: more harmonics, more scratch | bowed cymbal |
| CROWD | .40 | one person to a table of six, each their own hands and a little early or late | snaps, claps |
| SLAP | .30 | open palm tone to a cupped, cracking slap | conga |

## The voices (26)

Drums: KICK (18-inch, calf, open, felt beater, "feathered" when soft), SNARE (14-inch, calf, tuned high),
RIMSHOT, CROSS_STICK, TOM_RACK (12-inch), TOM_FLOOR (14-inch), ELBOW_TOM, MALLET_TOM. Brushes: BRUSH_TAP,
BRUSH_SWEEP, BRUSH_TOM, BRUSH_RIDE, BRUSH_ROLL. Cymbals: RIDE (thin, riveted), RIDE_BELL, BOWED_CYMBAL,
CYMBAL_SWELL. Hats: HAT_FOOT (the 2-and-4 chick), HAT_CLOSED (stick), HAT_OPEN. Room and hands: SNAPS (the
1962 crowd snapping instead of clapping), CLAPS, TAMBOURINE, CONGA, STICKS (two sticks clicked: the count-in).
Rolls: PRESS_ROLL.

Long voices are declared PERC as BREAKER declares its riser: the rolls (2.6 s), the swell (3.0 s), the bow
(2.4 s) and the slowest brush sweeps (a sweep is one full circle, 1.9 s at SPEED 0 down to 0.5 s, plus 0.35 s of
room).

## The kits (four, sixteen pads each)

| Kit | BPM | Night | What it is |
|---|---:|---|---|
| EMBER | 138 | the first set | hard bop on sticks: open feathered kick, high crisp snare, riveted ride, hat foot on 2 and 4, cross stick, rimshot, two toms, snaps, sticks, the elbow tom, press roll (A15), mallet swell (A16) |
| EMBER AFTER HOURS | 66 | 3 a.m. | a ballad on brushes: slow and quick sweeps, brush taps, a brush ride and a dark far ride, brushed toms, a mallet tom, two people snapping, brush roll (A15), far swell (A16); more room, more chain |
| EMBER SOUL JAZZ | 104 | the organ-trio set | a shuffle with a fat loose backbeat, tight hats, tambourine, a table clapping, high, low and slap congas, rimshot and cross stick, snare roll (A15), the whole room clapping (A16); close mics, warmest chain |
| EMBER FREE | 80 | the late set | the rebellious one: mallets on toms tuned wide, a bowed cymbal two ways, snares off, an elbow in the floor tom, sticks clicked, loose rattling hats, one person snapping, swell (A15), the floor tom setting the snares off (A16); further back, less chain |

The hi-hat foot closes the open hat on every kit (and the stick hat does too on EMBER). The grooves are two
bars on a triplet grid; the FREE groove is a phrase, not a pattern.

| Pad | EMBER | EMBER After Hours | EMBER Soul Jazz | EMBER Free |
|---|---|---|---|---|
| A01 | Kick (KICK) | Soft Kick (KICK) | Kick (KICK) | Open Kick (KICK) |
| A02 | Snare (SNARE) | Brush Tap (BRUSH_TAP) | Fat Snare (SNARE) | Snares Off (SNARE) |
| A03 | Hat Foot (HAT_FOOT) | Hat Foot (HAT_FOOT) | Hat Tight (HAT_CLOSED) | Mallet Tom High (MALLET_TOM) |
| A04 | Hat Open (HAT_OPEN) | Slow Sweep (BRUSH_SWEEP) | Hat Open (HAT_OPEN) | Mallet Tom Low (MALLET_TOM) |
| A05 | Ride (RIDE) | Brush Ride (BRUSH_RIDE) | Crash Ride (RIDE) | Bowed Cymbal (BOWED_CYMBAL) |
| A06 | Ride Bell (RIDE_BELL) | Dark Ride (RIDE) | Tambourine (TAMBOURINE) | Bowed, Biting (BOWED_CYMBAL) |
| A07 | Cross Stick (CROSS_STICK) | Quick Sweep (BRUSH_SWEEP) | Hat Foot (HAT_FOOT) | Elbow Tom (ELBOW_TOM) |
| A08 | Rimshot (RIMSHOT) | Soft Cross Stick (CROSS_STICK) | Claps (CLAPS) | Hard Rimshot (RIMSHOT) |
| A09 | Rack Tom (TOM_RACK) | Brushed Tom (BRUSH_TOM) | Conga High (CONGA) | Sticks (STICKS) |
| A10 | Floor Tom (TOM_FLOOR) | Brushed Floor (BRUSH_TOM) | Conga Low (CONGA) | Bell, Loose (RIDE_BELL) |
| A11 | Hat Stick (HAT_CLOSED) | Mallet Tom (MALLET_TOM) | Rimshot (RIMSHOT) | Fast Sweep (BRUSH_SWEEP) |
| A12 | Snaps (SNAPS) | Two Snapping (SNAPS) | Cross Stick (CROSS_STICK) | Loose Hats (HAT_OPEN) |
| A13 | Sticks (STICKS) | Hat Open (HAT_OPEN) | Conga Slap (CONGA) | Hat Foot (HAT_FOOT) |
| A14 | Elbow Tom (ELBOW_TOM) | Loose Brush (BRUSH_TAP) | Floor Tom (TOM_FLOOR) | One Snapping (SNAPS) |
| A15 | Press Roll (PRESS_ROLL) | Brush Roll (BRUSH_ROLL) | Snare Roll (PRESS_ROLL) | Swell (CYMBAL_SWELL) |
| A16 | Cymbal Swell (CYMBAL_SWELL) | Far Swell (CYMBAL_SWELL) | The Room Claps (CLAPS) | Floor Tom, Snares On (TOM_FLOOR) |

## Measured (Python, round 0)

All from `gen_audition.py` at round 0 (Python harness, not Kotlin). Distances are the house measure (SCUFF, ETCH,
CLUTTER: the RMS difference of level-matched third-octave spectrograms over the first 150 ms; over the whole render
for the rolls, the swell, the bow and the sweeps). The owner heard ETCH takes at 0.5 to 0.8 dB as one strike.

| Kit | BPM | Soft vs hard, least (dB) | Closest takes (dB) | Closest two pads (dB) |
|---|---:|---:|---:|---|
| EMBER | 138 | 3.03 | 1.29 | 4.53 (A10 / A14) |
| EMBER AFTER HOURS | 66 | 2.16 | 1.65 | 4.1 (A09 / A11) |
| EMBER SOUL JAZZ | 104 | 2.18 | 1.78 | 4.75 (A08 / A16) |
| EMBER FREE | 80 | 1.92 | 1.19 | 8.06 (A03 / A07) |

Between kits, the same pad slot:

| Kits | Same slot, mean (dB) | Same slot, closest (dB) | Identical pads |
|---|---:|---:|---:|
| ember / after-hours | 13.4 | 3.76 (A03) | 0 |
| ember / soul-jazz | 13.4 | 4.24 (A14) | 0 |
| ember / free | 22.4 | 6.67 (A01) | 0 |
| after-hours / soul-jazz | 18.1 | 2.98 (A01) | 0 |
| after-hours / free | 23.5 | 9.96 (A01) | 0 |
| soul-jazz / free | 27.0 | 10.18 (A08) | 0 |

- **Velocity:** on all 64 pads, a soft hit (velocity .35) differs from the hard one at matched level by at least
  1.92 dB. The changes are in the stroke: on the EMBER snare the spectral centroid falls from
  about 2.5 kHz hard to about 1.3 kHz soft because the wires lift less, not because a filter closes.
- **Round robin:** the closest two of any pad's four hard takes are 1.19 dB apart. Round 0
  first measured 0.51 dB on FREE's Snares Off (copies by the house measure); a take now also scatters each head
  mode by 1.5 dB (the stick's angle and bounce), which fixed it.
- **Health:** 768 kit renders, deterministic True, all finite, peak 0.89 (the audition
  match), DC at most 0.00056. An unknown control, a NaN control and a NaN velocity change nothing. WARMTH 0 and
  BUZZ 0 are exact bypasses of their stages.
- **Every control moves every voice:** the weakest are TAMBOURINE.BUZZ 1.83 dB, CONGA.WARMTH 1.99 dB, ELBOW_TOM.WARMTH 2.25 dB, BRUSH_SWEEP.BUZZ 2.37 dB between 0 and 1,
  over the whole render.
- **Render time (Python):** median 154.6 ms a hit, mean 228.5 ms. The slowest,
  3572.8 ms on BOWED_CYMBAL, is the first render of a cymbal at a new TUNE or DAMP building
  its cache. Python is not the phone; the Kotlin budget is set at round 1.
- **Not heard.** None of this says the kit sounds like 1962. That is the owner's listen.

## Gates

| Gate | State | Evidence |
|---|---|---|
| Melodic, keygroup, chord tool | not applicable | a drum engine |
| Articulation (HOLD, STRUM) | not applicable | finite hits; no picked or thumbed strings |
| Percussion: roll or riser | authored, not heard | press roll, brush roll, snare roll and mallet swell on A15/A16 of every kit, rendered in Python |
| Drums: several genre kits on the shelf | authored, not on the shelf | three genre kits beside the EMBER kit, measured in Python; no Kotlin |
| Velocity and round robin: rendering | measured in Python | see "Measured" |
| Velocity and round robin: saving, playback, export | open | no Kotlin; the page's pad grid is a browser stand-in |
| Sound character | open | not heard |

## Render budget

Python medians are in "Measured". The cymbal responses (three bands × four mode groups) are cached by kind,
TUNE and DAMP, so a take or a velocity is a re-mix and one convolution, not a rebuild; the room is cached by
DISTANCE. The first render of a cymbal at a new TUNE or DAMP pays the build (the slowest render in the run).
The Kotlin port should keep both caches, keyed by the quantised control value, and build modes with
`Modes.ring` per mode group rather than one sine per sample per mode.

## Sonic risks (where this would sound bad)

1. **The snare wires as hiss.** If the wires read as noise laid over a drum, the threshold law is wrong or
   too hot. Probe: the velocity ladder (soft should buzz less in proportion, not only in level) and the
   page's A02 against A14 on After Hours (loose wires).
2. **The ride as a crash.** BREAKER's first ride was heard as a crash. Here the ping band leads and the wash
   grows in with velocity squared. Probe: the ride's soft-to-hard ladder; a soft ride should be mostly ping.
3. **The sweep as wind.** A brush sweep without the head and wires under it is filtered noise. Probe: the
   SPEED sweep and the velocity ladder (pressure brings the head up).
4. **The room as reverb.** At DISTANCE .3 the club should be heard as closeness and air, not a tail. Probe:
   the DISTANCE sweep on KICK and SNARE.
5. **WARMTH as mud.** The chain's head bump and top roll-off stack with calf's darkness. Probe: the WARMTH
   sweep on RIDE and SNARE; Soul Jazz is the warmest kit.
6. **Synthetic crowd.** Snaps and claps are cavity-resonance bursts; if they read as electronic claps they
   should go. Probe: CROWD sweep.

## Next rounds

- **Round 0b:** the owner's listen. Which voices convince, which kits are the right four (a Latin jazz kit is
  the obvious swap for FREE if rebellious should mean something else), the WARMTH and DISTANCE defaults.
- **Round 1:** `Ember.kt`, `EmberPatch.kt`, the registration rows (`Patches`, `Velocity`, the roster), the
  house probes, a `DeterminismTest` canary, render-time tests; built from the picks, with Kotlin renders
  compared to the accepted Python renders.
- **Round 2:** the four kits on the shelf through the shared robin, `EmberKitTest` (saving, playback through
  `PadHit.resolve`, MPC 3 export read-back), the picker entry with its on-device test.
- **Round 3:** the owner's phone and MPC 3 checks.

## Why it is built this way

- **Every drum shares one membrane.** A kick, a snare, a tom, a conga and a tambourine are the same equation
  with a different size, tension, loss and strike. That keeps them sounding like one kit in one room, and it
  makes the Kotlin port one membrane function plus small voice recipes.
- **The sizzle has a threshold.** Real snare wires and rivets sit still until the surface pushes them hard
  enough. Modelling that threshold, instead of mixing in noise, is what makes a soft stroke a softer player
  and not the hard stroke turned down: the gate's "timbral velocity change" comes from physics, not a filter.
- **Brushes are their own implement.** A brush is not a soft stick: it lands as a spray of wires and it can
  be dragged. The sweep is the one gesture no stick engine can make, and the reason a 1962 kit needs a new
  engine at all.
- **The room and the chain come after the kit.** Everything the club and the tape do is outside the drum, so
  DISTANCE and WARMTH can go to zero for a dry modern kit, and the drums stay realistic without them.
- **The risky decision:** twenty-six voices in round 0 instead of the house's usual two. They share four
  primitives (membrane, cymbal bands, threshold parts, implements), so the risk is breadth of listening, not
  code. The owner's picks decide which ones survive into Kotlin.
