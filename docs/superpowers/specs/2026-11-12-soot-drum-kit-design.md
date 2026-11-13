# SOOT drum kit: design brief and round 0

Status: the sound approved by the owner at round 2 (2026-11-13): "All sounds fine". The 16-pad kit
is on the FRESH TAPE shelf (`SootKits.VERSION` 3), its grids proven in software through save,
playback and MPC 3 export. Open: two sibling genre kits, phone and MPC hardware, MPC 2's round robin. The
overview is [SOOT](../../SOOT.md).
Repository truth: `1d8f24ec` (main after #1037). Read: `BreakerChain.kt` (the dispersive spring
tank, `Tank`, `spring`, `tankMix`, `crush`), `Terra.kt` (DROOP: a sliding membrane fundamental,
`DROOP_TAU_SECONDS = 0.020f`), `docs/BOOM_BAP.md` (the accepted Backroom kit),
`docs/GATECRASH.md`, `docs/BREAKER.md`, `docs/ENGINE_ACCEPTANCE_GATES.md`,
`docs/INSTRUMENT_ACCEPTANCE_GATES.md`. "Soot" appears nowhere in `synth/src/main`.

The characteristic gesture is **dry skin knock that sags → papery crack → tight coil clatter →
the kit breathing through a dictaphone**.

Listening page: https://claude.ai/artifact/NcS9CrhdUvGR7mP9fZKz4m (private to the owner).

## What it is

A grimy East Coast boom bap kit built only from physical models, after the owner's sonic
profile ("Sub-Bass Driven Kicks with Physical Modeling of Membranophones & Strings"). No
sampled breaks. A modal membrane kick whose skin sags in pitch under the hit, a tuned sub under
it; a papery snare whose tail is a tight helical spring; hats and percussion from struck metal
partials with no noise in them; and a bus where the kick drives the spring and pumps a
dictaphone-grade compressor, so the kit breathes against itself.

SOOT is a working name. The profile names a record label; label, artist and product names
cannot name an engine, kit, voice or preset (the house trademark rule). Add the label's name to
`PresetTestSupport.trademarkBlocklist` when SOOT's presets land.

## The profile, distilled

Each paragraph of the profile, reduced to one mechanism, and whether it runs literally or as an
analogy:

| Profile section | Mechanism | Literal or analogy |
|---|---|---|
| 1. Kick: modal synthesis on a table or frame drum | ten ideal circular-membrane modes (1, 1.594, 2.136 ...), fast-dying overtones, a short wood frame knock above the boxy band | literal (modal) |
| 1. Tension modulation, "micro-fraction of a semitone" | every mode's frequency follows one curve: sharp by `sag × v^1.5` on impact, then settling half that flat over about 90 ms | analogy: tension grows with amplitude squared (real), the buckling flat settle is a shape |
| 1. Sub, "tight exponential", not 808 | sine an octave under the knock, falling from +7 semitones in 18 ms, t60 0.32 s, driven lightly so it has a third harmonic | literal oscillator |
| 2. Snare: papery crack, stick on snare or workbench | a sub-millisecond high-passed stick snap, two paper bands at 2.6 and 5.2 kHz, a taut head, wires (the one place noise is allowed), a short steel plate | modal plus filtered excitation |
| 2. Helical spring tail | BREAKER's tank algorithm with a new, tighter tank (below) | literal dispersive allpass model |
| 2. Cassette saturation, bit-crushed edge | asymmetric tanh at 4x, a high-frequency loss, 12-bit at the top | analogy |
| 3. Tension rods, plates, small idiophones; detuned inharmonic partials, not noise | six struck rods (free-free bar ratios 1, 2.756) each with a detuned twin, plus 88 seeded metal partials from 2.5 to 15 kHz; a threaded rod beating against its 11-cent twin; a 30-mode rectangular plate; a five-mode cup | literal modal |
| 3. Dragging swing, hats behind the beat | 58% sixteenth swing, hats 14 ms late, snare 6 ms late, ±2 to 3 ms seeded humanising | a groove, kit-side (render receives no tempo) |
| 4. Kick excites the spring, the kit breathes | one shared coil fed by every hit, the kick's send opened down to 90 Hz; a compressor keyed by the kick on the whole bus | literal coupling in a phrase; see the limit below |
| 4. Dictaphone, corrupted ROM | an AGC (3 ms attack, 180 ms release), a small speaker's 2.7 kHz bump and low-pass, a ROM reader's zero-order hold (22 to 11 kHz) and bit loss (12 to 8 bits); lows under 100 Hz bypass the grit | analogy |

What is not simulated: a calfskin's nonlinear stiffness, the snare's wire-to-head contact, a
spring's transducers, tape's hysteresis and wow, any real dictaphone.

## Sibling check

- **BREAKER** (nearest): SKIN sources → grit → tube → crush → a dispersive surf spring → slap →
  crush after the spring → bright amp. It cannot sag a membrane's pitch, build hats from metal
  partials, couple hits through one spring, or end in a dark dictaphone (its last stage is a
  bright combo amp, the opposite gesture).
- **Backroom boom bap** (`BoomBapKits`, SKIN through CRUNCH, TAPE and ROOM; accepted 2026-11-07):
  the accepted pocket. SKIN's hats are resonant noise banks, which the profile rules out, and the
  rack's SPRING is a Schroeder room with no dispersion.
- **TERRA** has the sag (DROOP) on its membranes, but no sub, no spring and no bus.

What SOOT adds that none of them do: the sag on a kick with a sub under it, noise-free metal
hats, and a bus where the kick drives the spring. The spring model itself is reused, not new.

## The three directions

- **Strong: SOOT, a drum engine of its own, built like BREAKER.** STACK plus COUPLE. New voices
  (sagging membrane kick, coil snare, metal hats and percussion), BREAKER's tank with a new
  constant, a bus stage printed per pad and run for real in a phrase pad. Gesture clear;
  novelty against the roster medium to high (the metal hats and the coupled bus are new);
  risk medium (the bus, and the sub on phone speakers); cost three Kotlin rounds plus kits.
- **Alternate A: a BREAKER genre kit.** SPLICE. BREAKER's voices with a fourth tank and a darker
  ending. Cheapest, but it keeps SKIN's noise hats and BREAKER's bright amp, and it loses the
  sag and the coupling: it would be a preset of BREAKER, not the profile.
- **Alternate B: the bus is the instrument.** INVERT. Render only four-bar phrases through one
  shared coil and dictaphone; no single-hit pads. Truest to section 4, useless on a pad grid.
  Kept as one pad (A16) inside Strong.

Strong wins because it is the only one that delivers all four profile sections on pads.

## Signal flow

```
voice, velocity, take
   │
   ├─ KICK:  membrane modes ──(tension curve: every mode)──┐
   │         wood frame knock + beater click ──────────────┤
   │         sub: +7 st fall, t60 .32 s → tanh at 4x ──────┴─▶ kick
   ├─ SNARE: stick snap + paper bands + head + wires + bench plate
   │            → COIL (BreakerChain.spring, tank COIL) → CASSETTE at 4x ─▶ snare
   ├─ METAL: rods with detuned twins + metal cluster (hats), rod, plate, cup ─▶ metal
   ▼
[each pad: finish: mean out, 20 Hz high-pass, cap 1.45 s, 4 ms fade, peak 0.89]
   │
   ├─▶ PAD PRINT (what a sampler holds): own coil send → DICTAPHONE → finish
   │
   └─▶ PHRASE BUS (A16 and the audition grooves only):
         Σ hits ─┬──────────────────────────────────────────────┐
                 └─ sends (kick high-passed at 90 Hz) → shared COIL ┤
                                                                ▼
                 DICTAPHONE: split at 100 Hz; highs → AGC keyed by kick → 2.7 kHz bump
                 → low-pass → ROM hold and bit loss; lows → gentle 2:1 → sum
```

The coil tank (shape): fc 2.2 kHz (k 10), coefficient 0.62, 70 stages, two springs of 19 and
24 ms, loop t60 0.35 + 0.55 × DRIP s, return toned at 3 kHz. Stability is BREAKER's argument
unchanged: the chain is allpass, the loop gain is g < 1 times a low-pass, so it cannot grow.

## Controls (proposed for Kotlin round 1)

| Control | Default | Changes | Interaction | Test |
|---|---:|---|---|---|
| TUNE | per voice | the source's pitch law | | knock fundamental moves monotonically |
| SAG (skins) / DETUNE (metal) | .35 st / 9 cents | the tension curve's depth; the twins' spread | SAG × velocity: `v^1.5` | early-vs-late cents rise with SAG (round 0: +10 / −12 cents at .35, +30 / −30 at 1) |
| COIL | .55 | the spring's dwell and loop t60 | COIL × GRIME: the pump lifts the coil | 1 to 3 kHz energy 100 to 500 ms after the hit rises |
| EDGE | .5 | the cassette drive, 12-bit past half | | harmonic share rises |
| GRIME | .45 | the dictaphone (AGC depth, band, ROM hold and bits) | | energy over 6 kHz falls (round 0: −10 dB at .45 on the groove) |
| DECAY | per voice | the mode t60s, the sub's t60 | | |

No HOLD: finite hits under 1.5 s, as BREAKER and BACKWASH.

## The kit (proposed, owner approval asked on the page)

| | | | |
|---|---|---|---|
| A13 Wide closed hat (25 cents) | A14 Wide open hat (chokes A13) | A15 Coil roll riser (not built) | A16 Four-bar phrase through the shared coil (not built) |
| A09 Steel plate | A10 The coil kicked alone | A11 Bench snare | A12 Hot snare (12-bit) |
| A05 Heavy kick (longer sub) | A06 Dry crack | A07 Tension rod | A08 Small metal cup |
| A01 Kick | A02 Snare (coil) | A03 Closed hat | A04 Open hat (chokes A03) |

## Risks

1. **The sub on a phone.** A clean 43.7 Hz sine is weight a phone does not play (OCTAVER's first
   listen). The sub is driven for a third harmonic at 131 Hz; the owner should judge on a phone
   and on monitors.
2. **The coupling does not survive a sampler.** Pads play independently, so a kick on pad A01
   cannot excite the coil on another pad's tail. Each pad prints its own glue; only phrase pads
   carry real coupling. Round 0 makes the difference audible (`g_full` against `g_uncoupled`).
   Measured: the per-pad print is much bassier (89% of energy under 100 Hz against 26%),
   because each pad's own compressor lifts the kick's sub. The Kotlin pad print needs its low
   compressor revisited if the owner hears that.
3. **A tight coil near its transition rings.** Group delay per pass is 50 ms at 2 kHz against
   3.8 ms at 200 Hz, so energy near fc lingers as a narrow band. Measured short enough here (74 dB
   down at 1.3 s) but it is the first thing to check if the owner hears a whine.
4. **Noise-free hats can read as thin or as a synth.** The 25-cent and 0-cent controls are on the
   page for that reason.
5. **Classes.** A dark, crushed chain moved BACKWASH's and BREAKER's classifications before.
   Measure the Kotlin renders, then declare honestly.
6. **Trademarks.** The label's name and any artist name go on the blocklist.

## Render budget

Python round 0 (this host, not a phone): kick 26 to 37 ms, coil snare 170 to 215 ms (the
vectorised tank), hats 31 to 97 ms, metal 3 to 28 ms, a four-bar phrase 1.2 to 3.6 s (the
compressor's per-sample loop). BREAKER's Kotlin tank runs a 100-stage chain inside its per-pad
budget; the coil is 70 stages, so it should fit the same budget. No solver, nothing to cache.

## R0 (2026-11-12, Python harness, not Kotlin)

`scripts/soot_r0/soot_r0.py` renders 38 clips to `testkit/soot-r0/` (ignored) with
`manifest.json` and `metrics.csv`; `scripts/soot_r0/build_page.py` builds the listening page
(every clip matched to −23 dB gated RMS, peak-limited at −1 dBFS; four pad prints play 0.9 to
4.2 dB under because of the limit, and the page says so). The Backroom references are Kotlin
renders from `:synth:generateBoomBapAudition`.

| Measured | Result |
|---|---|
| Kick sag, fundamental against the no-sag kick at 4 to 12 / 30 to 50 / 150 to 250 ms | SAG .35: +10.2 / −0.6 / −12.4 cents; SAG 1: +30.1 / −1.5 / −30.0 cents; SAG .35 at 30% velocity: +0.8 / −0.1 / −1.8 cents |
| Kick velocity, first 80 ms, share at 0.3 / 0.6 / 1.0 | 1.5 to 6 kHz (the beater): −43.3 / −37.2 / −32.3 dB; 120 to 400 Hz (upper modes): −12.1 / −10.9 / −10.5 dB |
| Snare onset centroid (60 ms) at 0.3 / 0.6 / 1.0 | 2681 / 3569 / 4178 Hz |
| Hat onset centroid at 0.3 / 0.6 / 1.0 | 4791 / 5132 / 5450 Hz |
| Take differences at full strength (difference energy against the signal) | kick −24.9, −23.0 dB (the weakest: the sub is most of a kick); snare −1.7, −0.7 dB; hat +4.4, −0.4 dB (fresh phases, the same metal) |
| Coil one-pass group delay at 200 Hz / 1 kHz / 2 kHz | 3.8 / 6.2 / 49.6 ms (BREAKER's surf tank: 2.8 / 3.6 / 8.9 ms) |
| Coil energy, 100 ms blocks after its peak | −13.4, −23.3, −29.7, −33.6, −41.9, −48.4, −54.5, −60.8 dB: falling every block |
| Grooves, energy under 100 Hz / over 6 kHz | coupled −5.9 / −25.8 dB; per-pad print −0.5 / −33.5; dry −4.6 / −15.8; sub through the grit −11.2 / −24.6 |
| The pump: hat band 20 to 80 ms after a downbeat kick against the 60 ms before it | coupled −2.0 dB; per-pad print +3.2 dB |
| Finite, peak, DC | every clip finite, peak 0.89, |DC| under 3e-5 |

## Completion gates (docs/ENGINE_ACCEPTANCE_GATES.md)

| Gate | Status | Evidence or plan |
|---|---|---|
| Sound character | not yet verified | the round-0 page; the owner's picks set round 1's defaults |
| Melodic: six voices, keygroups, chords | not applicable | a drum kit |
| Articulation: HOLD, STRUM | not applicable | finite hits under 1.5 s |
| Percussion: authored roll or riser | not yet verified | A15, the coil roll, after the snare pick |
| Drums: multiple distinct genre kits | not yet verified | two authored sibling kits, chosen by the owner at a later gate; different effect strengths on one kit do not count |
| Velocity: rendering | not yet verified (Python only) | measured as strike character above; Kotlin round 1 |
| Round robin: rendering | not yet verified (Python only) | each take a fresh strike; the kick's takes are the weakest |
| Velocity and round robin: saving, playback, export | not yet verified | the shared kit, `PadHit.resolve` and MPC 3 export paths GATECRASH proved; MPC 2 keeps one take per zone and will not pass |

## Ledger

| Claim | Status | Evidence |
|---|---|---|
| BREAKER's tank is allpass inside a g < 1 loop | verified | `BreakerChain.kt` at `1d8f24ec` |
| The vectorised tank equals BREAKER's per-sample loop | measured | against a direct Python port of `BreakerChain.spring`'s loop on the coil tank, 0.35 s of a dry snare: largest difference 1.1e-13 on a peak of 1.28 (−262 dB) |
| TERRA's DROOP slides the membrane's fundamental | verified | `Terra.kt` KDoc |
| The numbers in R0 | measured | Python harness, `manifest.json` |
| Anything sounds like the profile | not heard | the round-0 page |

## Why it is built this way

1. **The sag is one curve on every mode.** A real head under a hard hit is tighter, so all its
   modes go sharp together, then relax. Bending only the fundamental would sound like a pitch
   envelope, not a skin. It scales with velocity to the power 1.5, so a ghost note barely moves.
2. **The sub is a separate oscillator, tuned an octave under the knock.** The knock gives the
   attack and the room-free wood; the sub gives the weight. Keeping them apart lets the sub stay
   short (t60 0.32 s), which is what keeps it from turning into an 808.
3. **The hats have no noise in them on purpose.** The profile asks for detuned metal partials.
   The detuned twins beat against each other, which is where the "eerie, clinical" edge comes
   from. The 0-cent control lets you hear what the twins add.
4. **The spring is BREAKER's, with a tighter tank.** It was already proven stable and heard by
   the owner. Shorter springs bounce faster (clatter), a lower transition bends lower (rubbery),
   and a shorter loop decay closes the space (claustrophobic).
5. **The one risky decision is the coupling.** A sampler plays pads independently, so the
   breathing can only be printed into each pad or into a phrase pad. The page plays both so the
   owner can decide whether the difference matters.

## Questions at the round-0 gate (on the page)

1. Which kick (sag 0, .35, 1; a longer sub; knock only)?
2. Which snare (coil, dry, the surf tank, more bench, no cassette, hot cassette)?
3. Which hats (9 cents, 25 cents, 0 cents)?
4. Keep, change or drop the rod, the plate, the cup and the coil?
5. Do the soft hits sound like a softer hand on the same drum?
6. Which glue (coupled, per-pad, dry, straight, heavy dictaphone, sub through the grit)?
7. Does any pad print get ruined by the glue?
8. Approve the 16-pad layout?

## Round 0, heard (2026-11-12)

Page: https://claude.ai/artifact/NcS9CrhdUvGR7mP9fZKz4m. The owner's answers, as pasted:

| Question | Picked |
|---|---|
| 1. The kick | k1_long: sag .35, the longer sub (t60 0.6 s) |
| 2. The snare | s_hot: the coil, cassette 1.0 (12-bit) |
| 3. The hats | h_closed_wide: 25 cents |
| 4. Metal percussion | rod, plate, cup and coil: all kept |
| 5. Touch | kick, snare and hat: "softer hand" |
| 6. The glue | g_heavy: coupled, dictaphone 0.9 |
| 7. Pads as they would land | "No" (no pad ruined, heard at GRIME .45) |
| 8. The layout | approved |

Round 1's defaults follow. Three picks moved what the layout's alternates should be: the heavy kick
(A05) goes longer still (sub t60 0.9 s), A12 becomes the round-0 coil snare at cassette .5, and
A13/A14 become the round-0 narrow hats (9 cents). The confirming page asks whether those swaps are right.
One pick was heard in a different setting than it will be used: GRIME .9 was picked on the coupled
groove, while the pad prints were heard at .45. Round 1 defaults the pads to .9 and plays both.

## As built: round 1 (2026-11-12, renderer model 1)

- `Soot.kt`, `SootPatch.kt` (with a renderer `model`, 1), `SootPresets.kt` (outside `Presets`, the
  layout's alternates), `SootTables.kt` (the round-0 hat partials and plate gains, generated from the
  harness so the picked metal is struck again; re-rolling would make a different hat).
- Registered as BREAKER is: `Patches.fromJsonValue`, `Velocity.macroSpecsFor` and `touchedRender`,
  `Restrike` (takes are fresh strikes), `SynthRoot`'s no-root arm, `PatchTypes.PROTOTYPES`, the
  hand-built lists in `PatchesTest` and `PadRecipeTest`, and a `DeterminismTest` canary.
- **The coil reuses BREAKER's spring.** `BreakerChain.Tank` gained springs, loop decay, send
  high-pass and tone, with defaults written as BREAKER's own numbers, and `spring` takes a per-call
  send high-pass. BREAKER is byte-identical: a SHA-256 over every BREAKER voice at full strength and
  at 60% on take 2 read `699d7574…` before and after the change.
- **Controls** (defaults are the picks): KICK TUNE SAG SUB COIL GRIME; SNARE TUNE COIL EDGE BENCH
  GRIME; hats TUNE DETUNE DECAY GRIME; ROD, PLATE, CUP TUNE DECAY COIL GRIME; COIL TUNE COIL GRIME.
  The print's dictaphone is the round-0 one; GRIME 0 bypasses it.
- **Changed against round 0:**
  - the coil hit now answers velocity (the tank normalises its send, so round 0's velocity did
    nothing; it now sets the dwell);
  - its takes vary the thump's pitch within 3% and the dwell within 5% (round 0's takes measured as
    near copies);
  - band-passes are a 2nd-order high-pass and low-pass in cascade rather than scipy's band design;
  - the noise is the house generator.
  So "the same drum" is a listening question, and the page asks it beside the Python picks.

`SootTest` (all pass) measured:

| Measured | Result |
|---|---|
| Velocity, dry (GRIME 0): share above each drum's split, 0.3 → 1.0 | kick +10.6 dB (1.5 kHz) · snare +4.0 (3 kHz) · closed hat +5.5, open +5.0 (9 kHz) · rod +6.1, cup +3.4 (5 kHz) · plate +1.5 (3 kHz) · coil +1.4 (1.5 kHz) |
| The same through the print (GRIME .9) | kick +13.2 · snare +3.2 · closed hat +0.1 · open hat +0.3 · rod +1.8 · plate +0.7 · cup +0.9 · coil +0.8 dB |
| Soft against hard, peak-normalised difference energy | -7.5 (snare) to -20.2 dB (cup): never the hard hit scaled |
| Takes at full strength, difference energy | kick -18.5 to -22.7 dB (closest); coil -8.2 to -26.2; snare, hats and metal -0.3 to +5.2 |
| Coil energy per 100 ms after its peak | 0, -5.4, -13.4, -20.6, -27.5, -33.9, -40.3, -46.8, -52.9, -59.1 ... -74.8 dB |
| Coil first echo | 300 Hz at 23.7 ms, 1.8 kHz at 49.1 ms: the chirp rises |
| GRIME 0 → .9, share above 6 kHz | snare -17.4 → -22.9 · closed hat -8.6 → -14.3 · open hat -7.8 → -13.7 dB; the plate -51 → -25 (the ROM hold's aliasing adds grit to a dark drum) |
| Kick energy under 100 Hz, printed | -1.2 dB re the whole |
| Warm render per hit, this host (median of 5) | kick 46 · snare 73 · closed hat 12 · open hat 72 · rod 23 · plate 30 · cup 11 · coil 23 ms |

Two findings went to the page instead of being tuned away: through GRIME .9 the hats keep almost
none of their velocity brightness, and the plate gains aliased grit rather than losing its top.

| Gate | Status |
|---|---|
| Sound character | round 0 picked; the Kotlin renders not yet heard |
| Velocity, rendering | verified in Kotlin (above); the hats' through the print is the open question |
| Round robin, rendering | verified in Kotlin; the kick's takes are the closest |
| Velocity and round robin: saving, playback, export | not yet verified: the kit round |
| Roll or riser; genre kits | not yet built |
| Melodic, articulation | not applicable |

## Round 1, heard (2026-11-12)

Page: https://claude.ai/artifact/6DPRBQwsJLHre4kFp2UtVc. The owner's answers, as pasted:

| Question | Heard |
|---|---|
| 1. Same drums? | kick: same; snare: same; hat: **different**, "There is an effect sound on the Kotlin one" |
| 2. The pads | no notes |
| 3. The alternates | unanswered |
| 4. Touch, through the print | kick, snare, closed hat, rod: "softer hand" |
| 5. Four bars from the pads | **GRIME .45** |
| 6. Layout swaps | right |

The touch answer settles the round-1 worry that the hats lose their velocity brightness in the print:
by ear, a soft hat is still a softer hand at GRIME .9.

**The hat's effect sound, traced.** The "same drum" hat was rendered at GRIME 0, which bypassed
the dictaphone but not the hat's own send into the coil (.05). Measured on the files, against the
Python hat they picked: at 100 ms the Kotlin hat was -29.2 dB under its loudest 20 ms, the pick
-76.2. That is the coil ringing on after the metal has died. Below 2.4 kHz the Kotlin hat also sat
about 10 dB over the pick, but that is not the cause. Two things were ruled out by experiment:
the stick tick (removing it changed nothing) and the attack ramp (identical code). The pick's own
eight Python takes spread 15 dB in that band on their random phases alone, and the Kotlin hat sits
inside that spread (take 4 measures within 2 dB of it).

## As built: round 1b (2026-11-12, renderer model 2)

- **The hats print with no coil send.** Closed hat at 100 ms: -76.2 dB, as the pick. The open
  hat's own 0.55 s ring covers most of its coil; at 700 ms it moves from -62.5 to -71.7 dB.
- **GRIME defaults to .45 on every voice** (the four-bar pick).
- **Model 1 is kept.** `Soot.macrosFor`, `defaults` and `settled` take the model. A model-1 save
  keeps GRIME .9 and the hats' sends. `SootTest` replays one byte for byte, and checks both changes.
- The alternates were left unanswered, and the round-1b page asks again.
- Page: https://claude.ai/artifact/4rgHxAF39Z9nuztgjtEgwB (the hats beside the pick, with model 1
  for contrast; the alternates; the pads at .45; four bars).

## Round 1b, heard (2026-11-12)

Page: https://claude.ai/artifact/4rgHxAF39Z9nuztgjtEgwB. The owner's answers:
- **The hats:** left unanswered.
- **The thirteen alternates:** "Keep", all of them.
- **The pads:** no notes.
- **"Is this the kit?":** "Yes", on the four bars with the new hats.

The hats are read as approved through the kit; their side-by-side question was not answered.

## As built: round 2 (2026-11-12, `SootKits.VERSION` 1)

**`SootKits.kt`** is the 16-pad kit in the approved layout. Every pad holds three velocity zones
(.30, .65, 1) of four takes, GATECRASH's grid.
- **A01 to A14:** the hits. Each is a `SootPresets` patch struck by `Soot` at the zone's velocity and
  re-struck per take.
- **Chokes:** the wide hats choke each other (group 1), the narrow hats each other (group 3), and the
  roll and the four bars each other (group 2).
- **A15, the coil roll:**
  - sixteen fresh strikes of the hot snare, the gap shrinking along a 1.6 power curve;
  - each strike stronger than the last, all into one coil, then the cassette and the print;
  - the dictaphone's pump evened the build out (it rose 1.9 dB), so the rise is a fader push after
    it, -12 dB to 0: BREAKER's riser, as the owner approved it there. Now +11.9 dB;
  - declared PERC at 3.4 s, never classified.
- **A16, four bars:**
  - the round-0 groove, every hit dry (`Soot.dry`), with the 58% swing and the dragged hats;
  - the sends and the kick (opened to 90 Hz) go into one coil, then the dictaphone at GRIME .45,
    keyed by the kick;
  - the hats send nothing to the coil, as the owner picked them;
  - exactly four bars at 84 BPM, the tail wrapped onto the start. The loop point's step is 0.0074
    against the music's own 99.9th-percentile step of 0.1168;
  - a LOOP pad.
- **A rendered bank.** The pads carry their grids, not recipes: GATECRASH's proven path. The hits
  stay editable as `SootPresets` patches in the engine. A pad on the kit cannot be re-edited in
  place.

**The refactor.** `Soot` exposes `dry`, `print`, `output`, `snareStrike`, `snareTail` and a keyed
`dictaphone` for the kit. Every SOOT render is byte-identical before and after: a SHA-256 over both
models, all eight voices, at 1.0/take 0, .6/take 2 and .3/take 1, read `410078ff…` both times.

**The shelf.** `StarterKits` "soot" goes in the SKINS group. Both READMEs now count 95 starters.

**The proof.** `:shell:generateSootKit` (`SootKitGenerator`) and `SootKitTest` run GATECRASH's
proof:
- the saved kit reloads equal;
- the saved PCM and then the exported PCM match the renders, through `PadHit.resolve`, over two
  full cycles of every zone;
- the exported MPC 3 track reads back with its velocity ranges, base slices, cycle lengths and
  frame windows.

All 16 pads and 192 slices pass. `verification.csv` lists it per pad.

**The listening page** (https://claude.ai/artifact/3zmAc3qu32W7vEpJ6Yb6C2) plays every pad read back
from the export:
- the roll at each zone;
- the four bars at each zone, and at GRIME .9 to steer by;
- the four bars looped twice;
- four touch strips;
- two bars from the single pads, for contrast.

`SootKitsTest` (all pass) measured:

| Measured | Result |
|---|---|
| Roll: last 400 ms of the build against the first | +11.9 dB; 200 to 400 ms after it, the coil is still -5.7 dB re the build's end |
| Four bars, loop point against the 99.9th-percentile step | take 0: 0.0074 / 0.1168; take 3: 0.0012 / 0.1233 |
| Soft against hard zone, share above 3 kHz | roll -21.9 / -18.6 dB; four bars -19.9 / -18.8 dB |
| Takes, difference energy | roll -2.0 dB; four bars +2.0 dB |
| Kit build, this host, 4 cores in parallel | 27.6 s. The four bars are 2.0 s a render x 12; a hit 10 to 225 ms; the roll 0.3 s |

**Disclosed, not fixed.** The kit takes 27.6 s to build on this host, against GATECRASH's 8 to
11 s. The four bars are 63% of it. Starters are built once, when made. A phone will be slower.
If the wait is too long, the four bars can share strikes between zones, or keep fewer takes.

| Gate | Status |
|---|---|
| Sound | the hits and the kit approved; the roll and the four bars not yet heard |
| Melodic, articulation | not applicable |
| Percussion roll or riser | built (A15); not yet heard |
| Multiple genre kits | one of three; two sibling kits, the owner's choice, still to come |
| Velocity and round robin, rendering | verified |
| Saving and playback | verified in software (`KitStore`, `PadHit.resolve`); no phone has played it |
| Export | MPC 3 verified by read-back; MPC 2 keeps one take per zone and does not pass; no MPC has loaded it; SFZ and DecentSampler not generated |

## Round 2, heard in part: the snares (2026-11-12, `SootKits.VERSION` 2)

The owner's words: "A2,11,12 sound the same". Measured on the files the page played, as a
log-spectral distance (24 geometric bands, 60 Hz to 16 kHz, over the first 0.6 s, each normalised to
its loudest band):
- A02 to A12: 0.4 dB;
- A02 to A11: 2.0 dB;
- A11 to A12: 1.9 dB;
- for scale, the dry crack (A06), which they did not flag, sits 3.8 dB from A02, and the kick and
  the rod 26.3 dB apart.

**The cause.** A12 differed from A02 only in its cassette edge (.5 against 1), and A11 only in more
bench (.9 against .35). The dictaphone and the levelling covered both.

**The change.** Both alternates now differ in mechanism, not in grit:
- **Bench Slap (A11):** BENCH 1, TUNE 1 (six semitones up), no coil, no cassette, no dictaphone.
  A short, clean, high metal slap.
- **Deep Coil Snare (A12):** COIL 1, TUNE 0 (six down), no bench, no cassette, GRIME .9. A low snare
  drowned in the spring.

They are now A02/A11 8.7 dB, A02/A12 8.8 dB and A11/A12 11.7 dB apart. Of the candidates rendered,
this pair was the most separated. The page offers the next pair beneath them (5.3 and 6.6 dB from
A02). `SootKitsTest` holds the three snares at least 6 dB apart; the old voicing would fail it.
`SootPresets`' "Bench" and "Coil" carry the new settings. Saved patches keep their own macros. The
kit regenerated and passed the whole proof again: 16 pads, 192 slices, saved and exported PCM,
playback, the MPC 3 track.

## Round 2, heard (2026-11-13, `SootKits.VERSION` 3)

Page: https://claude.ai/artifact/3zmAc3qu32W7vEpJ6Yb6C2. The owner's answers:

| Question | Heard |
|---|---|
| 0. The three snares, re-voiced | A02, A11, A12: "Different" |
| 1. The coil roll (A15) | all three zones: "Yes" |
| 2. Four bars (A16), which dictaphone | **.9** (the "not in the kit" version) |
| 3. The loop point | "Clean" |
| 4. The sixteen pads | no notes |
| 5. Touch and takes | unanswered |
| 6. Two bars from the single pads | no notes |
| Overall | "All sounds fine" |

**The change.** `SootKits.PHRASE_GRIME` goes from .45 to .9. The single pads keep the .45 picked at
round 1. The kit's hard first take of A16 is now exactly the render the owner picked:
`phrase(1, 0, grime = .9)`.

**Re-checked.** The loop point still clean: take 0, 0.0071 against the 99.9th-percentile 0.1292;
take 3, 0.0033 against 0.1332. The full kit proof passes again: 16 pads, 192 slices, saved and
exported PCM, playback, the MPC 3 track.

**The sound gate is closed for SOOT.** Round 2's touch question went unanswered. The owner's
"All sounds fine" is read as covering it. Their round-0 and round-1 touch answers were "softer hand"
throughout.

## Genre candidates (2026-11-13)

The drum gate needs multiple distinct genre kits on the shelf. Asked for two directions, the owner
chose all four candidates offered: drumless, basement dub, brushed noir and warehouse industrial.

`SootGenreSketchGenerator` (test-side, like the round-0 harness, `:synth:generateSootGenreSketches`)
renders each one in the SOOT engine: four bars through SOOT's A16 bus (one coil, a keyed dictaphone,
wrapped to loop), plus signature pads. Each candidate has its own tempo, tuning, pad roles, groove,
coil wetness and dictaphone:

| Candidate | BPM | What makes it | Distance from SOOT's four bars |
|---|---:|---|---:|
| Drumless | 80 | no kick; rods, cups and a plate over a muted snare | 10.2 dB |
| Basement dub | 72 | one-drop; heavy kick and a high rim thrown into the coil | 6.8 dB |
| Brushed noir | 76 | brush-weight snare taps, damp kick, pure hats, light dictaphone | 7.3 dB |
| Warehouse industrial | 96 | straight; driven kick, bench snare, struck plates, pushed dictaphone | 3.5 dB |

The distances are log-spectral, 24 bands. They ignore rhythm. Industrial is closest to SOOT in tone,
and its difference is mostly tempo and pattern; the page says so. The candidates are 7.6 to 13.9 dB
from each other, except industrial, at 8.0 to 9.5 dB from the others.

Page: https://claude.ai/artifact/Uoh1uk5NztKLXFWSUQ9Sj4. The owner picks which become authored
16-pad kits, at least two. Each kit kept gets its own layout approval and listening rounds, and then
the same save, playback and export proof as SOOT. Nothing here changes `SootKits.VERSION` 3.

## The four sibling kits, built (2026-11-13, `SootGenreKits.VERSION` 1)

The owner marked all four sketches "Build it". **`SootGenreKits.kt`** makes them authored 16-pad
kits from the SOOT engine, each with three zones of four takes. SOOT's own kit (`SootKits` 3) is
untouched.

| Kit (starter id) | BPM | A01 to A14 | A15 | A16 |
|---|---:|---|---|---|
| SOOT DRUMLESS (`soot-drumless`) | 80 | no kick: lead, high and low rods; three cups; two plates; muted and sprung snares; a bench tap; narrow hats; the coil | rod roll | the sketch |
| SOOT BASEMENT DUB (`soot-dub`) | 72 | drop and deep kicks; thrown and dry rims; thrown and low snares; hats; two plates; two coils; a rod and a cup in the spring | rim roll, coil .9 | the sketch |
| SOOT BRUSHED NOIR (`soot-noir`) | 76 | a damp kick and a high knock; brush, dark brush, rim and sprung snares; pure, open and shimmer hats; soft cups, a rod, a plate swell, the coil | brush roll | the sketch |
| SOOT WAREHOUSE INDUSTRIAL (`soot-industrial`) | 96 | driven and low kicks; bench, clang and dry snares; wide hats; struck and low plates; two rods; a cup; two coils | snare roll | the sketch |

**A16 is the sketch the owner chose.** The pads it plays carry the sketch's parts exactly. When
the sketch generator was switched to render through `SootGenreKits.phrase`, all 20 sketch files
matched the heard ones byte for byte. That needed one fix first: the genre's dictaphone is a
Double, as the sketch's was. .6f and .9f are not .6 and .9, and that alone had changed the
drumless and industrial four bars. Read back from each kit's MPC 3 export, A16 matches its sketch
to within 16-bit rounding.

**Pads within a kit are different drums.** `SootGenreKitsTest` holds every two hits on the same
voice in a kit at least 4 dB apart, on the measure under which SOOT's three snares were 0.4 to
2.0 dB. The first authoring failed in four places:

| Kit | Pair | Before | Change | After |
|---|---|---:|---|---:|
| Drumless | the two muted snares | 3.6 dB | a sprung snare | 5.1 dB |
| Noir | the two kicks | 3.0 dB | a high, coiled knock | 5.2 dB |
| Noir | the two hats | 1.4 dB | the shimmer hat higher and longer | 4.6 dB |
| Noir | the rim and the hot snare | 3.7 dB | a sprung snare | 4.9 dB |

The closest pair now is industrial's kicks, at 4.7 dB.

**Proof.** `SootGenreKitTest` and `:shell:generateSootGenreKits` pass: all four kits, 64 pads, 768
slices, through save, reload, `PadHit.resolve`, and MPC 3 export and read-back.
- **The rolls rise** 11.0 to 12.2 dB over the build.
- **The four bars loop cleanly.** The seam step is 0.002 to 0.036, against the music's own
  0.050 to 0.139.
- **Build time on this host:** 11.8 to 21.5 s per kit.

The pads were authored unheard, apart from A16 (the sketch) and the four signature pads. Page:
https://claude.ai/artifact/J6S61MGPeNuErNGJq9v4xa. It plays all 64 pads read back from the
exports, with Keep or Change on each. Both READMEs now count 100 starters.

## The sibling kits, heard (2026-11-13)

Page: https://claude.ai/artifact/J6S61MGPeNuErNGJq9v4xa. The owner kept 63 of the 64 pads:
- **Drumless, Brushed Noir and Warehouse Industrial:** all sixteen kept.
- **Basement Dub:** fifteen kept. A16 (four bars) is marked "Change", with the note "Beat is
  incoherent".

**Read from the groove.**
- **No downbeat.** The kick and the rim land only on beat 3, and nothing marks beat 1.
- **Thin hats.** They play only the offbeats.
- **A double throw.** The rim is thrown into the coil twice: its own spring (COIL .8), then a .85
  send into a bus at .7 wet.

**Measured.** As an onset autocorrelation at the beat (0.83 s):
- the current four bars: 0.20;
- A, a steady one-drop (hats on every eighth, the rim's send .4, wet .45): 0.35;
- B, steppers (a kick on every beat): 0.58.

All three repeat at the bar alike (0.45 to 0.46).

**The candidates** render through an internal `SootGenreKits.phrase` overload that takes a groove,
the wetness and sends; `:synth:generateSootDubCandidates` writes them. The kits' own four bars are
byte-identical through the change (a hash over all four, `6412613f…`, before and after).

Page: https://claude.ai/artifact/Gqu8thscT25ga3iWQrG2S7. The owner picks; the pick replaces A16's
groove, and `SootGenreKits.VERSION` goes to 2.

## Basement Dub's four bars, picked (2026-11-13, `SootGenreKits.VERSION` 2)

The owner picked B, steppers, for SOOT BASEMENT DUB's A16. The kit now uses:
- **The groove:** a kick on every beat, the rim on 3, offbeat hats, and the rim thrown at the turn.
- **The coil's wetness:** .5.
- **The rim's send:** .4.

These are the candidate's exact parameters. Checked by hash:
- the kit's A16 is byte-identical to the clip the owner picked;
- the other three kits' four bars are unchanged;
- the candidate generator still reproduces the version-1 one-drop for its comparison.

Re-checked:
- **The loop seam:** 0.0045, against the music's 0.056.
- **The roll:** it still rises 11.1 dB.
- **The proof:** all four kits again (768 slices through save, playback and MPC 3 read-back).

With this pick the owner has approved every pad of the four sibling kits: 63 kept as built, and
Dub's four bars by choice.
