# MAGNET — the electric string, and VALVE, its amp on any pad

**Status:** design; brainstorm from the 2026-09-28 specification ("COIL:
Physical String Excitation × Vacuum Tube / Cabinet Synthesis Engine"); not
implemented. The specification's engine, transcribed and rendered, is flat
by up to 69 cents, its pickup-position control is a delay with no audible
effect, and its amp leaves up to 6 % DC in the WAV ("The specification, as
reviewed"). The corrected model — the house's own string, a real pickup
comb, the amp measured in three placements — was built in a Phase-0 spike (a
throwaway prototype, kept as a record outside the build,
[`../plans/2026-09-29-magnet-phase-0-spike.md`](../plans/2026-09-29-magnet-phase-0-spike.md))
and works: in tune to about a cent, the comb notching what the physics says,
and the amp's aliasing at the 8× reference once it runs at 4×. No engine code
is committed; V1 and R1 rebuild from this document. It lands as a docs-only
PR (zero check runs by design, as FORK, BORE and ARCO did).
**Date:** 2026-09-29
**Plan:** to be written per phase (`docs/superpowers/plans/2026-09-29-valve-v1.md`, then `…-magnet-round-N.md`)
**Related:** [`2026-09-28-bore-woodwind-engine-design.md`](2026-09-28-bore-woodwind-engine-design.md)
and ARCO's design (PR #391) are the model for this document's shape — an
external spec reviewed against the tree, measured, corrected, the fleet
table, claims tests, rounds with gates. [`2026-09-27-silk-string-engine-design.md`](2026-09-27-silk-string-engine-design.md)
built the `Strings` toolkit MAGNET's string is. [`2026-09-24-resin-ladder-engine-design.md`](2026-09-24-resin-ladder-engine-design.md)
paired an engine with a rack section (CONTOUR), the precedent for VALVE.
**Roadmap:** rows are added when implementation starts, not now — the rule
FATHOM, RESIN, GLINT, SILK, FORK and BORE followed.

## Why MAGNET, and why VALVE first

The ask arrived as one document describing "the complete electric guitar
signal chain as an integrated synthesis voice": a plectrum, a dispersive
string, an electromagnetic pickup, a triode preamp with grid sag, a passive
tone stack and a reactive speaker cabinet, five voices (CHUG, JANGLE, LEAD,
MUTED, FUZZBOX), six macros (TUNE, DRIVE, TONE, CAB, MUTE, SAG), twelve
presets, and an addendum of five "aliveness" mechanisms for COIL, ARCO and
BORE. The owner's words: *"I want to brainstorm adding this."*

Asked what COIL is for first, the owner chose **both, the amp first**. The
amp, tone stack and cabinet run over a finished buffer — the rack's shape
exactly — and the CRUNCH rule ("an effect that improves more than one engine
belongs in the rack, where it works on captured snips exactly as on
synthesized ones", `README.md`) has moved that shape into the rack four
times: WOBBLE for FORK, ECHO for SIREN, TAPE for BORE, ENSEMBLE for ARCO. A
tube amp and a cabinet on a captured snare or a vocal chop is the most useful
section the rack does not have. So the work splits in two:

- **VALVE**, a rack section: the tube, its sag, a post-drive tone, the
  cabinet. Every pad gets it the day it lands.
- **MAGNET**, a `:synth` engine: a dry electric string with pickups. Its
  identity is the pluck and the pickup comb, not the amp; every preset lands
  through VALVE by recipe (`landingChain`, SIREN's ECHO door and BORE's TAPE
  door), so the pad's rack carries a `valve` section the user can turn or
  remove.

### Against the fleet

| Engine or effect | Why MAGNET / VALVE is not it |
|---|---|
| **PLUCK / SILK** (`Strings`) | Acoustic strings into acoustic bodies. MAGNET *is* `Strings.pluck`; what it adds is the electric instrument's two identities — a pickup that reads the string at a place (a comb whose notches move with position) and a coil pair that cancels hum by summing two places. Neither engine has a macro for where the string is heard from; that is BLEND. |
| **FORK** (magnetic pickup) | FORK reads a *bar* through a reluctance nonlinearity, `v / (1 − x)²`, its bark. A guitar pickup's position comb is linear and spatial. FORK's curvature is a candidate for later (a hot pickup's bark), not round one. |
| **TAPE, CRUNCH, CONTOUR** (the rack's drives) | TAPE's drive is a mild tanh with a touch of asymmetry, run at the snip rate; CRUNCH is a converter's damage; CONTOUR's `tanh` lives in a filter loop. None has gain into a tube curve, a supply that sags, or a speaker. At high gain the snip rate aliases where TAPE's mild drive does not — measured below — so VALVE is the first section that oversamples. |
| **EQ** (the rack) | EQ sits before the amp in the fixed order and shapes what saturates. VALVE's TONE shapes what comes out: the post-drive mid scoop that defines a high-gain sound cannot come from a pre-drive EQ. |
| **OUTSIDE** (`audio/Outside.kt`) | Plays a pad out through the phone into a real amp and records it back. VALVE is the amp that needs no cable, no room and no second device; the two coexist. |

### The name

**COIL is taken** three times in the engine's own categories: Heavyocity's
Coil (a mono synthesizer in their C-Tools bundle), Puremagnetik's Coil (a
distortion effect) and Airwindows' Coils (transformer saturation) — the
situation RESIN's AMBER was renamed for. **PICKUP**, the exact word, would be
a second meaning beside ORBIT and CHOP, where "pickup" is the beat before the
downbeat. **TWANG** and **STEEL** are shipped preset names (a TINES kalimba,
a THUMP snare). The engine is **MAGNET** — the thing a pickup is, one plain
mechanism word in FORK's and BORE's family; a web check found only tape
plugins with longer names (Magnetite, Magnetism, Magnetic II). The section is
**VALVE** rather than AMP because the pad card carries an AMT stepper, and
AMP beside AMT on one small screen is a misreading waiting to happen; TUBE is
a shipped TINES preset name. Owner's choice on all three, 2026-09-28.

Off every product surface: the tone-stack prose names three amplifier makers
(one, `fender`, already in `PresetTestSupport.trademarkBlocklist`); the
presets DROP RECTIFIER (a near-miss of an amplifier line) and OXFORD 2X12 (a
speaker maker). R1 checks `rectifier` and `oxford` against the shipped preset
names before adding them to the blocklist.

## The specification, as reviewed

Transcribed into a worktree with six compile-driven changes (the patch class
omitted because `Patch` is sealed and `Velocity.macroSpecsFor` is exhaustive;
the missing MUTED and FUZZBOX preset lists left empty; two override
parameters and a stage tap for measurement; nothing that touches a number)
and rendered at 176.4 kHz across 65 macro corners and a 45-cell tuning grid
(record, part A). Nothing went non-finite. Almost nothing else holds:

| Spec section | Measured | Verdict |
|---|---|---|
| §4 waveguide: delay `renderRate / f0`, a stiffness allpass, a nut one-pole, a lerped read | flat on the raw string at **every** one of 110 readings, −1.1 to −68.9 cents; the error grows with pitch and with MUTE — JANGLE at TUNE 1 is −8.4 / −19.7 / −52.0 cents at MUTE 0 / 0.5 / 1, LEAD at TUNE 1 MUTE 1 −68.9 | the loop's filters are not charged to the period — the defect `Strings.tune` exists for (inference from the pattern; the spike did not isolate it). Rebuilt on `Strings.pluck` |
| §2.1 "electromagnetic pickup spatial combing": the pickup reads forward and backward waves at `x_p` | the code reads **one** tap of one delay line. Sweeping the single-coil position over 0.05 → 0.5 changes harmonics 1–12 by **0.00 dB**: one tap on one ring is a delay, not a comb. The humbucker's second tap sits a fixed 0.45 ms behind, so its only notch is at 1111 Hz for every note and every position | the headline mechanism is prose. Rebuilt as Jaffe & Smith's position comb on the string's output ("The pickup") |
| §2.2 asymmetric triode, grid sag | the curve is soft on the negative side, so high gain goes negative; nothing blocks DC: final mean −0.0157 at CHUG defaults, −0.0596 (6.3 % of peak) at CHUG DRIVE 1, +0.047 of peak on FUZZBOX MUTE 0. SAG does nothing measurable on JANGLE (its gain rarely crosses 1) | kept, with a DC blocker after it and the gain law re-based on the input's level (VALVE) |
| §2.3 cabinet; §4 tone stack | prose says a "bilinear FMV tone stack" and a "third-order Butterworth" voice-coil roll-off; code has three RBJ biquads and a one-pole. Cabinet output peaks at 1.6 before normalising | the network is kept as VALVE's cabinet, every Hz labelled *shape*; TONE re-centred so 0.5 is flat |
| §4 output: `normalizeByFold → Punch.applyOversampled → limitPeak` | the drum chain with a hidden PUNCH; loudness 0.22× to 1.55× the melodic target | the melodic chain: `Tide.bandLimit → Dsp.decimate → mean + 20 Hz high-pass → Dsp.levelTo → Dsp.fadeTail` |
| §3 `drumClassFor` (TONAL unless MUTE > 0.65) | the classifier files 60 of 65 renders LOOP (the 1.5 s length rule) and the five MUTE 1 renders PERC | MAGNET's voices are statically TONAL, PLUCK's and SILK's rule: pitched notes, never judged from a render |
| §3 voices | MUTED is a voice *and* MUTE a macro; FUZZBOX is a pedal, not an instrument | two rigs ("Voices") |
| §4 aliasing | `Tide.bandLimit` changed nothing (clarity 19.1 / 19.1 dB CHUG, 3.3 / 3.3 FUZZBOX); the flat tuning confounds the metric, so the spec's own render cannot say whether it aliases | measured on the corrected model ("VALVE's placement") |

### What the spec got right

Every API its engine calls exists with the signature it assumes —
`Dsp.expMap`, `Dsp.OnePole`, `Dsp.Biquad`, `Tide.bandLimit`, `Punch`,
`MacroSpec(name, default, neutral)`, `Patches.decode` — so six edits stood
between the transcription and a compile. The signal path is the right one,
in the right order. Rendering at 4× before the tube is exactly right, and the
spike confirms it is necessary. The seeding (`Dsp.seedFor("COIL", voice, f0)`)
is the house route. The sag model (5 ms charge, 120 ms recovery) is a sound
starting point. And the product idea — a chug beside a snare, a clean jangle
stab, a tube amp on anything — is a sound this user reaches for that no
engine or section makes.

## The physics, measured

Part B of the spike built the corrected model on the house's primitives and
measured it (record, part B). Five findings shape the design.

**1. The string is in tune.** `Strings.pluck` with MUTE on `Strings.damping`
and the pickup after it: −0.62 to +1.11 cents across 36 cells (two voices ×
TUNE 0/0.5/1 × MUTE 0/0.5/1 × BLEND 0/1), against the 5-cent bar and the
spec's −69. Through an amp, an FFT-peak pitch read wanders by up to 37 cents,
but it moves with the analysis window (15.9 → 43.8 cents on one cell) while
autocorrelation reads 82.43 Hz against 82.41: a measurement artefact on a
saturated, decaying line, not a pitch shift. Tuning claims are therefore
measured on the dry string, and through VALVE by autocorrelation.

**2. The single-coil comb notches what it should.** At position 0.5 the even
harmonics sit 52.1 / 54.0 / 36.6 / 37.2 dB under their quieter neighbours; at
0.25, h4 and h8 sit 47.4 and 28.7 dB under — all past a 20 dB bar. BLEND
moves the pattern audibly (JANGLE's h5 is +1.6 dB re h1 at the neck and
+19.6 at the bridge).

**3. A humbucker is two combs aligned in time, not two combs summed.** The
naive sum `0.707 · (c(p) + c(p + dp))`, each comb `y[n] − y[n − D]`, has no
coil-spacing notch anywhere in h1–h22, because the two combs carry different
linear-phase terms. Delaying each comb so their centres coincide gives
`sin(πkp₁) + sin(πkp₂)`, whose first spacing notch is at `k = 1/dp`: measured
−49.4 dB at h18 for CHUG, matching the algebra to 0.1 dB on almost every
harmonic. That is the humbucker's physics (two coils reading two places of
one string at once), and it is what R0 builds.

**4. PICK is monotonic but its top is spent.** Across PICK 0 → 1 the exciter's
corner moves 4.6× while the centroid moves 1459 → 1618 Hz (JANGLE) and
719 → 835 Hz (CHUG); the last three steps together move it 1.0 % and 1.7 %.
It passes the velocity rule (no falling step in ten) but fails the ugly-end
rule (`snipsnap-audition-gate-findings`): the extremes are not extreme.
MAGNET widens the map ("Macros").

**5. The pick's own position comb notches h10 on every render.** The spike
plucked at 0.10 of the string, so the exciter's comb puts a 40–47 dB hole at
h10 whatever BLEND does. It is physics — a real pick notches the same way —
but at a round reciprocal it is a fixed hole in every note. MAGNET plucks at
0.085, whose first notch (`k ≈ 11.8`) falls between harmonics, as *shape* for
the gate.

### VALVE's placement

The amp stage was measured in three places on the same dry string: **P1**
inside the render at 176.4 kHz, **P2** as a rack pass at 44.1 kHz, **P3** as a
rack pass that upsamples 4× internally. On a steady, exactly harmonic input —
the only measure the decaying strings do not confound — 4× reaches the 8×
reference and the snip rate does not:

| f0 | DRIVE | 1× (P2) clarity | 4× (P3) clarity | 8× clarity | 1× error vs 8× | 4× error vs 8× |
|---|---|---|---|---|---|---|
| 123.5 Hz | 1.0 | 38.5 dB | 51.0 dB | 50.5 dB | −18.9 dB | −20.0 dB |
| 123.5 Hz | 0.45 | 46.8 | 51.3 | 50.4 | −19.1 | −20.0 |
| 247.0 Hz | 1.0 | 31.9 | 49.4 | 50.9 | −12.9 | −30.7 |
| 164.8 Hz | 0.5 | 42.4 | 50.8 | 49.6 | −17.2 | −24.5 |

On the string renders the residual against 8× is −2.4 to −7.5 dB at 1× and
−27 to −32 dB at 4×. `harmonicClarity` on the string renders ranks P2 *best*
(67.4 dB against P3's 60.6), contradicting both measures above; the spike did
not find why (a candidate: a decaying string's lines are wider than the 8 Hz
on-harmonic window). **The aliasing claims test therefore uses the steady
harmonic probe, never `harmonicClarity` on a string.**

Cost, milliseconds per rendered second on the JVM (single machine, min of 3):

| Stage | ms/s |
|---|---|
| string + pickup at 176.4 kHz | 4.9 (CHUG) – 6.2 (JANGLE) |
| the amp at 1× | 1.3–1.4 |
| the amp at 4× | 5.3 |
| the melodic output chain (band-limit, decimate, level) | ~8.5 |
| `Resampler.resample` 44.1 → 176.4 kHz | **72.2** |

P3 as built costs 84–86 ms/s, almost all of it the general windowed-sinc
upsampler. VALVE therefore does not call `Resampler`: it zero-stuffs 4× and
interpolates with `Tide.bandLimit`'s own eighth-order low-pass (gain 4),
which the band-limit-and-decimate figure above prices at a few ms/s. Target
for V1, asserted as a printed number and gated at the listen: **≤ 20 ms per
rendered second** on the JVM. A phone runs two to four times slower (BORE's
estimate; no phone measured).

The CRUNCH rule's identity test passed on the spike: a THUMP kick and snare
through P3 at DRIVE 0.6 still classify KICK and SNARE (the snare's centroid
falls from 11 568 to 6 598 Hz — a tube amp is not subtle). Two things did not
pass, and VALVE's design answers both: the "near-transparent" corner (DRIVE 0,
SAG 0, TONE 0.5, CAB 0) left a residual 2.8 dB *above* the dry signal,
because the gain floor was 2, the shelves were always in circuit and the
cabinet never left; and the raw string's peak spans 0.68 to 1.50 across
corners, so a gain law on absolute level means different things on
different inputs.

## Architecture

```
MAGNET (engine, 176.4 kHz)
  Strings.pluck(f0, damping(MUTE), pickHz(PICK), position 0.085)
    → trimToDecay(0.25 … 4 s)
    → pickup: neck comb(s) at 0.42 × (1 − BLEND) + bridge comb(s) at 0.12 × BLEND   (humbucker = aligned pair)
    → pickup resonance (TptSvf low-pass, per voice, shape)
    → Tide.bandLimit → Dsp.decimate → mean + 20 Hz high-pass → Dsp.levelTo(MELODIC) → fadeTail → Snip (mono, dry)
  landingChain(voice, macros) = FxChain().withSection("valve", voice's LANDING_VALVE)

VALVE (rack section, the snip's rate, internally 4×)
  per channel: normalise to peak 1 → zero-stuff ×4 → bandLimit (×4) →
    gain(DRIVE) → grid sag(SAG) → asymmetric tube → DC blocker →
    TONE (post-drive) → cabinet(CAB) → bandLimit → decimate → peak match to the input
```

### MAGNET, the engine

One file shaped like `Silk.kt` over `Strings`, a `MagnetPatch.kt` shaped like
`TerraPatch.kt`, a `MagnetPresets.kt` after the gate. The string is
`Strings.pluck` unchanged: the tuning budget, the exciter, the decay-following
cut are all the toolkit's, which is why the spike measured a cent where the
spec measured seventy. Render at `Dsp.RATE * Dsp.OVERSAMPLE`, seeded
`Dsp.seedFor("MAGNET", voice.name, f0)`, no `seed` render argument (a recipe
cannot store one).

**The pickup.** Jaffe & Smith's position comb, `c(y, p)[n] = y[n] − y[n − D]`
with `D = round(p · rate / f0)` over the *physical* period (the rule
`Strings.positionComb`'s KDoc already records), applied to the string's
output — the loop is linear, so the comb commutes to where a pickup sits.
A humbucker is two combs at `p` and `p + dp`, time-aligned so their centres
coincide, summed at 0.707; `dp = 0.0278 · f0 / root` (18 mm of coil spacing
over a 648 mm open string, growing as the fretted string shortens; *shape*).
BLEND weights two pickups, neck at 0.42 and bridge at 0.12 of the string from
the bridge (*shape*). A second-order resonant low-pass follows — the pickup's
inductance against the cable's capacitance, the single-coil's bright peak and
the humbucker's darker one — at the voice's Hz and damping (*shape* until
sourced; the physics is standard, the numbers are the spike's).

### R0, the toolkit change

`Strings.positionComb` is private. R0 adds `Strings.pickup(y, positions,
weights, freq, rate)` — the aligned multi-tap comb above — and routes
`positionComb` through the same delay arithmetic so the two cannot drift
(the repo's named defect: one quantity computed in two places). No audio
change: `StringsTest`'s frozen grids (PLUCK's loop at every rate, pitch and
damping, and Phase 3a's stiffness and jawari) must match sample for sample,
SILK Phase 1a's and BORE R0's shape.

### VALVE, the section

`Valve.kt` shaped like `Contour.kt`; `Section("valve", …)` between `squash`
and `crunch` in `FxChain.SECTIONS` (a compressor feeds an amp; the sampler's
damage happens to a sound that already existed); the `valve` field appended
at the end of `FxChain`'s constructor; the order KDoc gains
`→ SQUASH → VALVE → CRUNCH →`; `"amped"` appended to `Treatments.EXTRA`
(never to `Shuffle.TREATMENTS`, which a seeded bank indexes).

- **Level-relative drive.** Each channel is normalised to peak 1 before the
  gain law and the output peak-matched to the input after, so DRIVE means the
  same on a whispered vocal and a slammed kick. The spike's 0.68–1.50 input
  spread is why.
- **4× inside.** Zero-stuff, `Tide.bandLimit` as the interpolator, the tube
  and everything after it at 4×, `Tide.bandLimit` and `Dsp.decimate` back
  down. VALVE calls `Tide.bandLimit`; it does not copy it.
- **A transparent neutral.** `neutral` values DRIVE 0, SAG 0, TONE 0.5,
  CAB 0 must be a near-copy, so the pad sheet's AMT fade lands on a bypass
  the way CONTOUR's open cutoff does: DRIVE 0 is a gain of 0.05 into the
  tube (where `tanh` is linear to 0.1 %), TONE 0.5 is flat by construction
  (every tone band's gain is a signed distance from 0.5), CAB 0 takes the
  cabinet out. Pinned by a test ("Testing").
- **Peak-matched** — the rack's contract (`FxTest`: every section is
  peak-matched). A saturated sound at equal peak is louder: the spike's rack
  placements landed a levelled string at 2.4× the melodic loudness target.
  That is character, as CRUNCH's and TAPE's is, and the owner decides whether
  it stays (decision 3).
- **Mono in, mono out; stereo stays stereo** with identical channels intact,
  one sag state per channel.

## Voices

Two rigs for round one, FORK's and BORE's count. With the amp in the rack,
what makes one electric voice another is the pickups and the strings:

| Voice | Pickups | Root | Resonance (shape) | BLEND default | Character |
|---|---|---|---|---|---|
| **JANGLE** | single coils | E2 (82.41 Hz) | 4500 Hz, k 0.40 | 0.5 (both) | clean rhythm, stabs, the in-between chime |
| **CHUG** | humbuckers | B1 (61.74 Hz) | 2800 Hz, k 0.55 | 1.0 (bridge) | the seven-string low B at the bottom of TUNE, chugs in the middle of the knob |

The spec's other three become cheaper things: **LEAD** is CHUG with BLEND
toward the neck and a hotter landing chain — a preset family; **MUTED** is
the MUTE macro; **FUZZBOX** is a VALVE preset (a fuzz law is VALVE's later
question, not the string's). Roots are re-heard at the gate, as BORE's were.
Both voices are statically `DrumClass.TONAL`.

## Macros

Four, plain words, inside the 3–6 budget (`docs/SYNTH_ROADMAP.md`, rule 1).

| Macro | Moves | Mapping | Default JANGLE / CHUG |
|---|---|---|---|
| **TUNE** | the note | 24 semitones from the root, snapped; `neutral` 0.5 | 0.5 / 0.5 |
| **MUTE** | palm muting | `Strings.damping(MUTE, bodyLoopHz)` — loop brightness and feedback together, so length and darkness move as one; bodyLoopHz 7000 / 5500 (shape) | 0.15 / 0.35 |
| **PICK** | the pick's brightness | exciter corner `expMap(PICK, 600, 16 000)` Hz for both voices, wider than the spike's 4.6× so the bottom is a thumb and the top is a wire (the ugly-end rule); the velocity macro once a sweep proves every step moves the centroid | 0.6 / 0.55 |
| **BLEND** | the pickup selector as a knob | neck alone at 0, both at 0.5, bridge alone at 1 | 0.5 / 1.0 |

Adding a macro later is compatible (`settled` fills a missing key from
defaults); removing one is not, so the fifth and sixth slots stay open for
the gate — the addendum's items are the candidates ("Phasing").

VALVE's four, each `neutral` at its transparent point:

| Macro | Moves | Mapping (shape until the V1 listen) | Default / neutral |
|---|---|---|---|
| **DRIVE** | gain into the tube | `expMap(DRIVE, 0.05, 35)` on the normalised input | 0.45 / 0 |
| **SAG** | the supply giving way | `vSag` charges toward `|x| − 1` at 5 ms above the rail, recovers at 120 ms; bias `vSag · SAG · 0.45` | 0.35 / 0 |
| **TONE** | the tone after the tube | 0 a mid scoop (−12 dB at 380 Hz), 0.5 flat, 1 mids and top forward; every band's gain a signed distance from 0.5 | 0.5 / 0.5 |
| **CAB** | the speaker | 0 none; rising, the spec's network fades in and grows from a bright open-back combo to a dark closed wall (thump 110 → 78 Hz, the open-back notch filling in, breakup at 2.6 and 3.75 kHz, voice-coil roll-off 5.8 → 4.5 kHz) | 0.6 / 0 |

Landing chains, *shape*, heard at the R1 gate: JANGLE lands at DRIVE 0.25,
TONE 0.55, CAB 0.35; CHUG at DRIVE 0.85, SAG 0.4, TONE 0.3, CAB 0.95.

## Data flow and compatibility

Unchanged from every engine: macros → `Magnet.render` → `Snip` →
`MagnetPatch` → `kit.json`, beside the recipe's rack with its `valve` section;
a kit regenerates from the sidecar bit for bit. All additive:

- `FxChain` gains a field at the end of its constructor; an old recipe without
  it decodes as a bypass, the clause `FxTest` already pins ("an absent section
  keeps old recipes byte-stable").
- `MagnetPatch` gets one arm each in `Patches.fromJsonValue`, `Presets`, and
  the exhaustive `when` in `Velocity.macroSpecsFor`; `soften` until PICK's
  sweep passes, then `brightnessOverride → "PICK"`, PLUCK's and SILK's line.
- `PadRecipe.VERSION` is not bumped (pre-launch; the owner's standing call).
- Exports stay mono WAVs through `Cleanup`, `WavWriter`, `Preflight`.
- The phone (`SynthScreen`'s `Engine` enum and its arms; the pad-sheet chip)
  is R1.1. The CHARACTER row is at its six-chip ceiling, so the chip is a
  layout decision (decision 5); the section is reachable through `treat` and
  through landing recipes the day it lands, as CONTOUR was.

## Failure handling

- **The loop** is `Strings.tune`'s; its `require` names the cause if a note is
  too short to budget. MAGNET's highest note (E4, 329.6 Hz) leaves the loop
  over 500 samples long, so it is unreachable and stays a loud check.
- **The combs** are bounded fractions of a period at every legal TUNE and
  BLEND; `dp` at TUNE 1 is 0.11, far inside 0.5.
- **VALVE** is bounded by construction: the tube curve never exceeds 1, sag
  charges toward a finite target, the DC blocker follows the asymmetry, the
  normalise-then-restore skips a silent channel rather than dividing by zero,
  and the zero-stuffed buffer is four times the snip — a 4 s stereo pad is
  1.4 M floats.
- Every macro is coerced to 0..1 at entry, as everywhere.

## Testing

Tests guard properties; they never author sounds. Thresholds are pinned from
the spike with margin; where the built code disagrees, the repo's rule
applies — print both numbers, record the measurement, set the threshold from
it, never loosen one without writing down why.

### The ones that carry the claims

- **In tune** (`MagnetTest`): every TUNE semitone × MUTE {0, 0.5, 1} × BLEND
  {0, 0.5, 1}, measured on the dry render, within 5 cents (spike: −0.62 to
  +1.11). Through VALVE at DRIVE 1, `Pitch.detect` within 10 cents of the dry
  read — autocorrelation, because the FFT peak read is the artefact above.
- **The comb**: at pickup position 0.5 (a test hook), h2 and h4 at least 20 dB
  under their quieter neighbours (spike: 52, 54); at 0.25, h4 (spike: 47).
  **The humbucker**: CHUG's aligned pair puts h ≈ 1/dp at least 20 dB under
  the single coil's level there (spike: 34 dB), and the unaligned sum fails
  that test — the guard is proven by reverting the alignment alone.
- **BLEND** moves the spectrum: BLEND 0 and 1 differ by at least 6 dB at some
  harmonic in h2–h8 on both voices (spike: 18 dB at JANGLE's h5).
- **PICK**: centroid non-decreasing across eleven steps, **and** each tenth of
  travel moves it at least 1 % (the spike's map failed the second clause at
  its top — the new map is what passes it). Registered as the velocity macro
  only then.
- **MUTE** shortens (render length at MUTE 1 under half MUTE 0's) and darkens.
- **VALVE's aliasing floor**: a steady harmonic probe (harmonics of 247 Hz to
  5 kHz at 1/k, 1.6 s, peak 0.99) through VALVE at DRIVE 1 reads clarity at
  least 45 dB (spike at 4×: 49.4; at the snip rate: 31.9 — the test fails the
  native-rate version, which is the point).
- **VALVE's neutral**: DRIVE 0, SAG 0, TONE 0.5, CAB 0 on a THUMP snare and a
  kick keeps the magnitude spectrum within 0.5 dB of the input from 40 Hz to
  16 kHz and the RMS within 0.1 dB (the spike's un-neutral version: a residual
  2.8 dB *above* the dry signal). Magnitude, not a sample residual: the 4×
  round trip's filters shift phase near the top of the band without changing
  what is heard, and a sample residual would fail on that alone.
- **The CRUNCH rule**: a kick through VALVE at defaults is still a KICK; a
  snare still a SNARE (spike: both held at DRIVE 0.6).
- **VALVE's cost** printed per rendered second on every run; the V1 listen
  records the phone number beside it.

### The rest

- `FxTest`'s shared contract covers VALVE by construction (deterministic,
  clean, peak-matched, identical stereo channels intact, empty chain a true
  bypass) because it iterates `FxChain.SECTIONS`.
- `TreatmentsTest`: `"amped"` exists, sets `valve`, is a bypass at AMT 0.
- MAGNET: deterministic, JSON round trip, unknown macro refused, every corner
  finite and in range, SCRAMBLE bounded, every preset's landing chain carries
  a `valve` section; `DeterminismTest` gains a canary; `PresetsTest` and
  `UserPresetsTest` gain the roster.
- R0: `StringsTest`'s frozen grids unchanged; the new helper's delay
  arithmetic shared with the exciter's comb.

## Phasing and gates

Every phase ends the house way: stop and listen.

| Phase | Ships | Gate |
|---|---|---|
| **0** (done) | the spike, recorded in [`../plans/2026-09-29-magnet-phase-0-spike.md`](../plans/2026-09-29-magnet-phase-0-spike.md) | in tune, the comb works, the placement numbers — all met |
| **V1** | `Valve.kt`, its `FxChain` row, `"amped"`, `FxTest` / `TreatmentsTest` entries, README's rack list | a short page: a THUMP kick, a THUMP snare, a VELVET saw stab and a VOX choir, each dry and at DRIVE 0.3 / 0.6 / 1 × CAB 0 / 0.5 / 1, plus the same snare at the snip rate against 4× (can the owner hear the fold-back) |
| **R0** | `Strings.pickup`, no audio change | the frozen grids match |
| **R1** | `Magnet.kt`, `MagnetPatch.kt`, registration, landing chains, `SynthKits.magnet()` and its testkit kit, the tests above, the blocklist terms, `MagnetAuditionGenerator`; presets by ear **after** the gate, eight per voice | the audition (below) |
| **R1.1** | the phone: picker entry and its arms, the chip decision, README's engine count | built where `:app` compiles |
| **R2** | MAKE INSTRUMENT as a decaying one-shot keygroup with PICK layers — `Keys.fork`'s cheap path, since a plucked note needs no loop seam; then the addendum's items, each behind its own listen: pitch droop on a hard pick (`Strings.Loop.retune` from sharp to the note), fret buzz (the jawari's one-sided barrier, already in `Strings.Loop`), sympathetic strings on the open E–A–D–G–B–E set (SITAR's tarab), pick scrape (new, small) | the instrument under two hands |

**The R1 audition**, in order:

1. **The kit as it lands** — A01–A08 CHUG walking a riff's notes through its
   landing VALVE, A09–A14 JANGLE, A15–A16 the LEAD family.
2. **The chug A/B** — a CHUG stab through VALVE against a VELVET saw stab
   through the same VALVE, level-matched, on the bar line beside a THUMP
   snare. If they cannot be told apart, the string engine is redundant for
   this user.
3. **Blind identity** — JANGLE at defaults at three notes, unlabelled:
   guitar, harp, or synth.
4. **Each voice** — defaults, then MUTE, PICK and BLEND at both ends with a
   plain-words line for each end.
5. **The placement A/B** — the same CHUG with the amp inside the render (P1)
   against the dry engine through the VALVE recipe, level-matched: does the
   split lose anything.
6. **VALVE on captured-like pads** — a snare and a VOX line at the landing
   settings.
7. **The phone** — a 4 s JANGLE and a VALVE pass timed on the device against
   the 150 ms shimmer.

**Pass rule.** Items 2 and 3 must pass. Presets are frozen per voice that
passes; a voice that fails gets no roster and no picker entry — GLINT's
state, not worse.

**Effort**, from footprints: CONTOUR, the newest section, was 5 files and
+132/−2 (`298a759`); VALVE is about 300 hand-written lines with its
upsampler and tests. R0 about 80 (BORE's R0 was ~100). R1 about 1 400
hand-written — FORK R1 was 1 538 — less the amp, plus the audition page.
R2's keygroup about 250 on FORK's path.

## Out of scope

- A fuzz law, a noise gate, a tuner, pedals — VALVE presets or later sections.
- FORK's reluctance bark on a hot pickup — a candidate sixth macro after R1.
- Stereo in the engine (VALVE keeps a stereo pad stereo; MAGNET renders mono).
- Real-time or native voices; an amp that feeds back into the string.
- A `seed` render argument; PUNCH on a melodic engine.
- The spec's twelve presets as written (lengths, names, and blind authorship).

## Decisions already taken

| Question | Decision | By |
|---|---|---|
| What COIL is for first | both, VALVE first | owner, 2026-09-28 |
| Where the amp lives | a rack section, internally 4×; the engine renders dry and lands through it by recipe | owner (approach A), the CRUNCH rule; the spike's placement numbers |
| Rack position | after SQUASH, before CRUNCH | owner |
| VALVE's macros | DRIVE · SAG · TONE · CAB, CAB 0 = no cabinet | owner |
| Voices, macros | JANGLE and CHUG; TUNE · MUTE · PICK · BLEND | owner |
| Names | MAGNET, VALVE | owner |
| Compatibility, tests, phasing | as above; presets by ear after the gate | owner |
| String | `Strings.pluck`, the melodic output chain, statically TONAL | the spike; PLUCK and SILK |
| Humbucker | the time-aligned comb pair | the spike's Extra C |

## Decisions for the owner

Each with the default this document takes; a default stands until the owner
or a gate overturns it.

1. **The pick position.** 0.085, so the exciter's first comb notch falls
   between harmonics (default), or 0.10 as the spike ran it, a fixed hole at
   h10 on every note that a real pick also makes.
2. **PICK's range.** `600 → 16 000 Hz` for both voices (default), or the
   spike's per-voice ranges with a steeper curve.
3. **VALVE's level.** Peak-matched, the rack contract, with the loudness rise
   heard as character (default); or loudness-matched, which needs `FxTest`'s
   peak clause excused for VALVE with its measured divergence recorded, SWELL's
   form.
4. **VALVE's oversampling.** Always 4× (default); or 4× only above DRIVE 0.3,
   where the snip rate measurably folds.
5. **The chip.** VALVE's chip replaces one on the full CHARACTER row, or a
   seventh row, decided at R1.1 with the design pass; `treat` and the landing
   recipes reach it until then (default).
6. **Blocklist terms.** `rectifier` and `oxford`, if they collide with no
   shipped preset name (default); or left to review.

## Appendix — where the numbers live

Every number above is copied from the Phase-0 record,
[`../plans/2026-09-29-magnet-phase-0-spike.md`](../plans/2026-09-29-magnet-phase-0-spike.md):
part A (the specification transcribed as `CoilProbe` and measured — four
grids, 110 pitch readings) and part B (the corrected model as `CoilSpike` —
seven grids and three extra probes), each with its complete Kotlin so it can
be re-run. The spike code is not in the build.
