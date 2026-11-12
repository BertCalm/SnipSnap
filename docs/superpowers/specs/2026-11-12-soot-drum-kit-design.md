# SOOT drum kit: design brief and round 0

Status: round 0 (Python harness, not Kotlin) published for the owner's listening gate on
2026-11-12. Nothing heard yet. No Kotlin, no kit, no presets. Every constant below is a shape
until the owner picks.
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
