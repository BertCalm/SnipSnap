# MAGNET — the electric string, and VALVE, its amp on any pad

**Status:** design and build record; brainstorm from the 2026-09-28
specification ("COIL: Physical String Excitation × Vacuum Tube / Cabinet
Synthesis Engine"). **Built:** VALVE V1 and V1.1 (the rack section; "V1.1
amendments"), R0 (`Strings.pickup`, no audio change) and **R1, the engine**
(2026-09-30; "R1, as built" below). R1's audition gate was run on 2026-10-01 and
2026-10-02 (items 2 and 3 pass; "R1, as built", "The gate and the second
listen"), no presets exist and the phone (R1.1) is not built. The specification's engine, transcribed and rendered, is flat
by up to 69 cents, its pickup-position control is a delay with no audible
effect, and its amp leaves up to 6 % DC in the WAV ("The specification, as
reviewed"). The corrected model — the house's own string, a real pickup
comb, the amp measured in three placements — was built in a Phase-0 spike (a
throwaway prototype, kept as a record outside the build,
[`../plans/2026-09-29-magnet-phase-0-spike.md`](../plans/2026-09-29-magnet-phase-0-spike.md))
and works: in tune to about a cent, the comb notching what the physics says,
and the amp's aliasing at the 8× reference once it runs at 4×. VALVE, R0 and
R1 rebuilt it from this document and are in the tree; the spike's code is not.
**Date:** 2026-09-29
**Plan:** one per phase, under `docs/superpowers/plans/`:
[`../plans/2026-09-29-valve-v1.md`](../plans/2026-09-29-valve-v1.md),
[`../plans/2026-09-30-valve-v1-1.md`](../plans/2026-09-30-valve-v1-1.md),
[`../plans/2026-09-30-magnet-r0.md`](../plans/2026-09-30-magnet-r0.md),
[`../plans/2026-09-30-magnet-r1.md`](../plans/2026-09-30-magnet-r1.md)
**Related:** [`2026-09-28-bore-woodwind-engine-design.md`](2026-09-28-bore-woodwind-engine-design.md)
and ARCO's design (PR #391) are the model for this document's shape — an
external spec reviewed against the tree, measured, corrected, the fleet
table, claims tests, rounds with gates. [`2026-09-27-silk-string-engine-design.md`](2026-09-27-silk-string-engine-design.md)
built the `Strings` toolkit MAGNET's string is. [`2026-09-24-resin-ladder-engine-design.md`](2026-09-24-resin-ladder-engine-design.md)
paired an engine with a rack section (CONTOUR), the precedent for VALVE.
**Roadmap:** row S20 in [`../../SYNTH_ROADMAP.md`](../../SYNTH_ROADMAP.md),
added with R1 and naming both MAGNET and VALVE (rows are added when
implementation starts, the rule FATHOM, RESIN, GLINT, SILK, FORK and BORE
followed).

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
upsampler. VALVE therefore does not call `Resampler` on the way up: it
zero-stuffs 4× and interpolates with `Tide.bandLimit`'s own eighth-order
low-pass (gain 4). The way down is the melodic engines' `Dsp.decimate` —
`Resampler` twice by halves on its power-of-two fast path — priced in the
band-limit-and-decimate figure above. Target for V1, printed on every run and
gated at the listen: **≤ 20 ms per rendered mono second** on the JVM (the
spike's 4× amp figure was mono). Measured: 16–18 ms per mono second, 62 % of
it the two `Tide.bandLimit` passes; a stereo pad is two channels through the
whole stage and costs about double, 31–35 ms per rendered second on a 4 s
stereo pad. A phone runs two to four times slower (BORE's estimate; no phone
measured).

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
  Strings.pluck(f0, damping(MUTE) [JANGLE: pitch-compensated, f0 over the open string], pickHz(PICK), position 0.085)
    → trimToDecay(0.25 … 4 s) → the string's own end fade: cubed over the last 150 ms if the trim ended it on its decay,
      squared over the last 250 ms (on top of the trim's own 400 ms) if the ring ceiling cut it
    → pickup: neck comb(s) at 0.42 × (1 − BLEND) + bridge comb(s) at 0.12 × BLEND   (humbucker = aligned pair)
    → pickup resonance (TptSvf low-pass, per voice, shape)
    → Tide.bandLimit → Dsp.decimate → mean + 20 Hz high-pass → Dsp.levelTo(MELODIC) → fadeTail → Snip (mono, dry)
  landingChain(voice, macros) = FxChain().withSection("valve", voice's LANDING_VALVE)

VALVE (rack section, the snip's rate, internally 4×)
  normalise by the snip's joint peak (Snip.peak(), every channel together) →
  per channel: zero-stuff ×4 → bandLimit (×4) →
    gain(DRIVE) → grid sag(SAG) → asymmetric tube (the sag bias an offset on its input) → DC blocker (5 Hz) →
    TONE (post-drive) → cabinet(CAB) → bandLimit → decimate → peak match to that same joint peak
```

### MAGNET, the engine

One file shaped like `Silk.kt` over `Strings`, a `MagnetPatch.kt` shaped like
`TerraPatch.kt`, a `MagnetPresets.kt` after the gate. The string is
`Strings.pluck` unchanged: the tuning budget, the exciter, the decay-following
cut are all the toolkit's, which is why the spike measured a cent where the
spec measured seventy. (R1 adds two things: a fade at the end of the string on both paths, so the amp
does not lift an abrupt end, and, on JANGLE only, a pitch compensation of the
loop's damping so that a high note rings as long as the open string does;
"R1, as built", rulings 18, 20 and 22.) Render at `Dsp.RATE * Dsp.OVERSAMPLE`, seeded
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

- **Level-relative drive.** The pad is normalised by its joint peak
  (`Snip.peak()`, every channel together) before the gain law and
  peak-matched to that same peak after, so DRIVE means the same on a
  whispered vocal and a slammed kick and a quieter channel stays quieter. The
  spike's 0.68–1.50 input spread is why.
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
  it stays (decision 3). The same single gain acts at the neutral point on a
  pad whose peak is a click: the 4× round trip spreads a one-sample transient
  and lowers its peak by about 2 dB (0.4–1.7 dB on band-limited clicks and
  noisy onsets), and the match lifts the whole pad by that — so the near-copy
  promise below is for pads whose peak is not a click, and the AMT fade's
  first step carries that rise on clicky material (decision 3's territory).
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
| **DRIVE** | gain into the tube | `expMap(DRIVE, 0.05, 35)` on the normalised input (V1.1: that law up to DRIVE 0.6, gain 2.55, then log-linear to gain 1000 at DRIVE 1) | 0.45 / 0 (V1.1: 0.7 / 0) |
| **SAG** | the supply giving way | a follower of the gained signal's overshoot (`\|v\| − 1` above the rail, 0 under it, `v = x · gain`) that charges toward a rising target with a 5 ms time constant and recovers toward a falling one with 120 ms — keyed on direction, not on the rail (09cceff6); bias `vSag · SAG · 0.45` toward cutoff, a plain offset on the tube's input (`curve(v − bias)`), so it moves the tube's rest point with it (known at V1: "Failure handling"). It acts only once the gained signal crosses the rail, DRIVE ≈ 0.46 on a normalised pad, so at the default DRIVE 0.45 (gain 0.95) the default SAG 0.35 is inert; at DRIVE 1 the overshoot reaches 34 and the bias holds the tube toward cutoff for ~120 ms (a gate item, F2). (V1.1: the bias is replaced by a supply sag after the tube, `y = t / (1 + SAG · 3 · env)`, `env` following the tube's own output level `\|t\|` with a 5 ms attack and a 120 ms release, so the tube's rest point does not move; the supply keys on `\|t\|` at any level, so SAG now acts at every DRIVE, the default included, and V1's "inert at the default DRIVE" no longer holds; see "V1.1 amendments") | 0.35 / 0 |
| **TONE** | the tone after the tube | 0 a mid scoop (−12 dB around 380 Hz) with the top 6 dB back (a shelf above 3 kHz), 0.5 flat and skipped outright, 1 mids (+6 dB around 650 Hz) and top (+6 dB above 3 kHz) forward; the mid centre slides 380 → 650 Hz with the knob and every gain is a signed distance from 0.5, the cut side twice as steep as the boost side | 0.5 / 0.5 |
| **CAB** | the speaker | 0 none (the whole network out of circuit); the network fades in over the first quarter of the knob — every gain and weight scales with CAB/0.25 and the voice coil's corner closes from the one-pole's own cap (0.45 × the work rate) toward 5.8 kHz, so CAB 0+ is transparent — and is fully in at 0.25 as a bright open-back combo: cone thump +6 dB at 102 Hz, the open-back notch −6.8 dB at 470 Hz, cone breakup at 2.6 and 3.75 kHz, the voice coil rolling off from 5.5 kHz; growing to a dark closed wall at 1: thump at 78 Hz, the notch filled in, the coil at 4.5 kHz. (The network's 110 Hz, 500 Hz, −9 dB and 5.8 kHz formula ends are its CAB 0 anchors, where it is bypassed.) (V1.1: at or below CAB 0.6 the speaker is V1's, its coil reaching 5.02 kHz at 0.6; from there to 1 the coil's corner runs linearly to 3.2 kHz and a second identical pole fades in, so CAB 1 is two poles at 3.2 kHz, not one at 4.5 kHz) | 0.6 / 0 |

Landing chains, *shape*, heard at the R1 gate and chosen by ear at its second
listen (2026-10-01/02): JANGLE lands through VALVE's default amp, written out
(DRIVE 0.70, SAG 0.35, TONE 0.50, CAB 0.60); CHUG at DRIVE 0.85, SAG 0.4,
TONE 0.3, CAB 0.95. (V1.1: a DRIVE written on V1's gain law means something
else on the V1.1 law, which keeps V1's up to DRIVE 0.6 and runs steeper above
it: this paragraph's CHUG 0.85 was gain 13 on V1's law and is gain 106 on
V1.1's. The first R1 build landed CHUG at DRIVE 0.71, gain 13, and JANGLE at
DRIVE 0.25, TONE 0.55, CAB 0.35; the owner chose gain 106 for CHUG and VALVE's
default amp for JANGLE by ear, "R1, as built", rulings 19 and 21. CHUG's CAB
0.95 is inside the V1.1 wall region above CAB 0.6, and its SAG 0.4 runs the
supply mechanism, which V1 never did.)

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
  charges toward a finite target, the normalise-then-restore returns a wholly
  silent pad as a copy rather than dividing by zero and a silent channel
  beside a live one passes through as zeros, and the zero-stuffed buffer is
  four times the snip — a 4 s stereo pad is 1.4 M floats. The 5 Hz DC blocker
  takes out the asymmetric curve's own DC; it does not follow the sag.
  **Known at V1, resolved in V1.1** (the bias is replaced by the supply sag, which
  adds no end step; see the V1.1 amendments): the sag bias is a plain offset on the tube's input, so at
  DRIVE above about 0.46 with SAG above 0 it shifts the tube's rest point and
  releases over 120 ms, slower than the 5 Hz blocker follows, and a hot pad
  ends on a decaying DC step. Measured at CAB 0.6 and DRIVE 1: a kick at SAG
  0.35 ends at 0.047 of its peak and at SAG 1 at 0.036, a snare at SAG 0.35 at
  0.031 and at SAG 1 at 0.064; at CAB 0 the kick reaches 0.075; `amped`
  (DRIVE 0.6) ends the kick at 0.0027 (−51 dB). Re-centring the curve about the
  shifted rest point was tried and withdrawn: it removes the end step but
  moves the DC to the onset while the hit clips both rails (a kick's first
  20 ms from +0.20 to +0.52 of its peak). It was an open item for the V1 gate; the
  owner chose the supply mechanism at the V1.1 listen and the bias is gone (F2).
- Every macro is coerced to 0..1 at entry, as everywhere.

## Testing

Tests guard properties; they never author sounds. Thresholds are pinned from
the spike with margin; where the built code disagrees, the repo's rule
applies — print both numbers, record the measurement, set the threshold from
it, never loosen one without writing down why.

### The ones that carry the claims

- **In tune** (`MagnetTest`): every TUNE semitone × MUTE {0, 0.5, 1} × BLEND
  {0, 0.5, 1}, read on the raw pickup buffer at the render rate (176.4 kHz)
  before the output chain, within 5 cents (spike: −0.62 to +1.11). Through VALVE at DRIVE 1, `Pitch.detect` within 10 cents of the dry
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
- **VALVE's aliasing floor**: a steady harmonic probe (harmonics of
  246.94 Hz to 5 kHz at 1/k, 2.0 s so a 65 536-sample window from 0.3 s fits,
  peak 0.99) through the public `process` at DRIVE 1 with SAG 0, TONE 0.5 and
  CAB 0 reads clarity at least 45 dB (spike at 4×: 49.4; built: 51.3; read
  through the default speaker it was 55.1, the coil flattering it by ~4 dB),
  and the same probe through the test-only native-rate path reads at least
  6 dB worse (28.8): the snip rate fails, which is the point. The probe stops
  at 5 kHz: images of brighter content pass the interpolator before the tube
  at −45 dB (10 kHz) and −31.6 dB (15 kHz), printed and not asserted (open
  item F1; the measured image table is in the appendix).
- **VALVE's neutral**: DRIVE 0, SAG 0, TONE 0.5, CAB 0 on a THUMP snare and a
  kick keeps each third-octave's share of the 40 Hz–16 kHz energy within
  0.5 dB of the input's (measured: every band within 0.08 dB; the kick skips
  8 bands above 1.6 kHz it barely has, the snare none) and the RMS within
  0.12 dB on the kick (measured 0.0985: −0.005 the round trip, +0.096 the 5 Hz
  blocker moving which sample is the kick's peak, a quantity that swings
  ±0.05 dB with THUMP's own macros, so the bound is the measurement plus 20 %
  rather than the round 0.1) and 0.44 dB on the snare (measured 0.361: the 4×
  round trip's phase shift near the top of the band lowers the snare's peak
  0.75 dB and its RMS 0.38 dB, and the peak match lifts the RMS back). Band
  share, not absolute level and not a sample residual: the round trip shifts
  phase without changing what is heard, and the single peak-match gain is the
  RMS clause's to judge. The round trip itself is flat within 0.3 dB from
  20 Hz to 16 kHz and −2.4 dB at 18 kHz, −10 dB at 20 kHz at 44.1 kHz (Tide's
  19.5 kHz band-limit twice plus the decimator; the 5 Hz blocker costs
  −0.26 dB at 20 Hz, −0.07 at 40 Hz), at every DRIVE. (The spike's un-neutral
  version: a residual 2.8 dB *above* the dry signal.)
- **The CRUNCH rule**: a kick through VALVE at defaults is still a KICK; a
  snare still a SNARE (spike: both held at DRIVE 0.6).
- **VALVE's cost** printed per rendered second on every run; the V1 listen
  records the phone number beside it.

### The rest

- `FxTest`'s shared contract covers VALVE by construction (deterministic,
  clean, peak-matched, identical stereo channels intact, empty chain a true
  bypass) because it iterates `FxChain.SECTIONS`.
- `FxTest` (beside `contoured`'s): `"amped"` exists, sets only `valve`, is a
  bypass at AMT 0; `TreatmentsTest`'s ordered names list gains `amped`. The
  chain sets DRIVE 0.6, SAG 0.35, CAB 0.6 and leaves TONE at its default,
  which is its neutral — a macro set at its neutral is one AMT could never
  move (`ensembled`'s RATE precedent).
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
| **V1** (built; V1.1 amends it) | `Valve.kt`, its `FxChain` row, `"amped"`, `FxTest` / `TreatmentsTest` entries, README's rack list | a short page: a THUMP kick, a THUMP snare, a VELVET saw stab and a VOX choir, each dry and at DRIVE 0.3 / 0.6 / 1 × CAB 0 / 0.5 / 1, plus the same snare at the snip rate against 4× (can the owner hear the fold-back) |
| **R0** (built) | `Strings.pickup`, no audio change | the frozen grids match |
| **R1** (built; the gate ran on 2026-10-01 and 2026-10-02 and items 2 and 3 pass, "R1, as built") | `Magnet.kt`, `MagnetPatch.kt`, registration, landing chains, `SynthKits.magnet()` and its testkit kit, the tests above, the blocklist terms, `MagnetAuditionGenerator`; presets by ear **after** the gate, eight per voice | the audition (below) |
| **R1.1** | the phone: picker entry and its arms, the chip decision, README's engine count | built where `:app` compiles |
| **R2** | MAKE INSTRUMENT as a decaying one-shot keygroup with PICK layers — `Keys.fork`'s cheap path, since a plucked note needs no loop seam; then the addendum's items, each behind its own listen: pitch droop on a hard pick (`Strings.Loop.retune` from sharp to the note), fret buzz (the jawari's one-sided barrier, already in `Strings.Loop`), sympathetic strings on the open E–A–D–G–B–E set (SITAR's tarab), pick scrape (new, small) | the instrument under two hands |

**The R1 audition**, in order:

1. **The kit as it lands** — A01–A08 CHUG walking a riff's notes through its
   landing VALVE, A09–A14 JANGLE, A15–A16 the LEAD family.
2. **The chug A/B** — a CHUG stab through VALVE against a VELVET saw stab
   through the same VALVE, level-matched, on the bar line beside a THUMP
   snare, in four pairs of bars presented one at a time ("R1, as built",
   ruling 17 and open item 7). For each pair the owner says which bar is the
   guitar, what mainly tells the two apart (how long it rings, how it ends,
   the tone itself, or nothing) and how different they sound. The item passes
   when the owner CAN tell the guitar from the saw by the tone: on the
   cut-matched pair and on the symmetric both-cut pair they pick the guitar
   correctly and name the tone. If they cannot be told apart by the tone, the
   string engine is redundant for this user.
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

**Pass rule.** Items 2 and 3 must pass. Item 2 passes only when the owner can
tell the guitar from the saw by the tone: on the cut-matched pair and on the
symmetric both-cut pair they pick the guitar correctly, and their answer to
"what mainly tells them apart" is "the tone itself". "How long it rings" or
"how it ends" is a confound, not a pass; "nothing" or "cannot tell" is a fail.
The decay-matched pair and the as-played pair are controls for interpretation
and are not part of the pass. Presets are frozen per voice that passes; a voice
that fails gets no roster and no picker entry — GLINT's state, not worse.

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

**VALVE's upsampler, measured on the built code (44.1 kHz snips, 4×).**
Zero-stuff ×4 interpolated by `Tide.bandLimit` rejects a tone's first image
(44 100 − f) by −50.6 dB at 8 kHz, −45.4 at 10 kHz, −40.0 at 12 kHz, −31.6 at
15 kHz and −21.7 dB at 18 kHz (the TPT SVF's pre-warp beats the analog
eighth-order Butterworth prediction by 2.5–7.7 dB). Through the tube those
images intermodulate to in-band products (2f − (44 100 − f): 900 Hz from a
15 kHz partial, at about −37 dB re the partial at DRIVE 1 and −37 to −39 dB at
the default DRIVE where it is the dominant in-band artefact on an isolated
bright tone); on pad material every third-octave from 125 Hz to 20 kHz lands
within 0.71 dB of a linear-phase reference up leg (THUMP), 0.19 dB (VELVET),
under 1 dB on a hat-like 8–18 kHz burst. The dominant artefact on bright
full-scale tones at DRIVE 1 is the tube's own 11th harmonic folding at the 4×
rate (176 400 − 165 000 = 11.4 kHz at −27 dB) — the 4× ceiling, not the
upsampler. A second `Tide.bandLimit` pass on the up leg would take the 15 kHz
image to −63 dB for about +5 ms/s and would not touch the fold; 8× would.

## V1.1 amendments

The V1.1 change followed the owner's round-two listen of 2026-09-30. The plan is [`../plans/2026-09-30-valve-v1-1.md`](../plans/2026-09-30-valve-v1-1.md) and the record of the spike and the gate measurement behind it is [`../plans/2026-09-30-valve-v1-1-spike.md`](../plans/2026-09-30-valve-v1-1-spike.md). Everything above this section still states V1; where V1.1 differs, it is amended here and marked "(V1.1: ...)" in the macro table.

- **The gain law.** V1's `0.05 · 700^DRIVE` (gain 35 at DRIVE 1) up to DRIVE 0.6, where the gain is 2.55, then log-linear to gain 1000 at DRIVE 1. The owner chose gain 1000 on kick, snare and brass and gain 100 on the choir, which sits at DRIVE about 0.845 on the new law. At or below DRIVE 0.6 the gain is V1's (and the sound is V1's at SAG 0 with CAB at or below 0.6). The owner accepted the aliasing cost: the steady-tone clarity at 4x falls from 51.3 dB at gain 35 to 43.4 / 41.3 / 40.6 dB at gains 100 / 300 / 1000 (28.8 to 26.8 dB at the snip rate), against the 45 dB bar, so the test keeps the 45 dB bar at DRIVE 0.6 and pins DRIVE 1 at its measured 40.6 dB less 2 dB.
- **The closed wall.** Two poles at 3.2 kHz at CAB 1, entered from CAB 0.6: at or below CAB 0.6 the speaker is V1's exactly; above it the coil's corner runs linearly from V1's 5.02 kHz to 3.2 kHz and a second identical pole fades in. Measured on seeded white noise at DRIVE 0: the centroid is 2052 Hz and the response is 3 dB down at 2934 Hz, against 4480 Hz and 5088 Hz for V1's 4.5 kHz one-pole wall.
- **The default DRIVE is 0.7** (V1: 0.45). The owner picked 0.7 heard under the V1 law (gain 4.90); 0.65 (gain 5.37) kept that sound and was offered at the confirmation listen against 0.7 on the V1.1 law (gain 11.3), and the owner picked 0.7 again, so the default is 0.7 (gain 11.3).
- **The SAG mechanism.** A supply sag after the tube replaces V1's bias: `y = t / (1 + SAG · K · env)` with K 3, `env` following the tube's own output level with a 5 ms attack and a 120 ms release, and the previous sample's value setting this sample's gain. The bias moved the tube's rest point and released more slowly than the 5 Hz blocker follows, leaving a decaying DC step on hot pads (a kick at DRIVE 1 on gain 35, SAG 1, ended on 3.568 % of its peak); the supply has no rest-point shift (0.079 % on the same clip), so that defect is gone. (The tube's own asymmetry still leaves about 1.7 % on a kick at gain 1000; SAG adds at most 0.2 points to the kick's end step there (1.76 % at SAG 0, 1.94 % at SAG 0.35, 1.25 % at SAG 1). The 1.7 % step the owner heard as clean was the V1 gate's brass at gain 35 with SAG 0.35, recorded in the V1 gate's answers (the owner's "clean" verdict on the V1 listening page's `end_brass_hot`).) The kick's level 60-160 ms after the hit, re its first 20 ms, falls 3.05 dB against SAG 0 at gain 35 (2.19 dB at DRIVE 0.6); the aim was 4-6 dB, which K in 0.8 to 3 did not reach. At gain 1000 (DRIVE 1, CAB 0.6) the same level drops 3.15 dB on the kick and 3.28 dB on the snare at SAG 1, and about 1.6 dB at SAG 0.35, flat from 20 to 160 ms: a steady level offset after the hit's first milliseconds, not a dip and recovery, so it did not shrink from gain 35 (the peak match does not cancel it, because the first few milliseconds pass before the supply charges and they set the peak); the owner heard SUPPLY at gain 35 and below before this was measured. At the confirmation listen the owner heard SAG at DRIVE 1 as doing nothing on the kick and snare (the clips were loudness-matched, which levels away that steady offset), and it is an effect of the lower and middle DRIVE range.
- **The oversampling is unchanged: the 4x round trip stays always on.** The owner's answer "only when driven" was measured and blocked. By the plan's rule (the lowest DRIVE step where the steady probe's clarity at 4x beats 1x by at least 6 dB) the threshold is 0.55 (a 4.8 dB gap at 0.50, 14.0 dB at 0.55), but the cabinet runs at a different rate on the 1x path and the sound changes: a snare through CAB 0.5 steps 1.45 dB in its top third-octave at every DRIVE including 0, the noise centroid at DRIVE 0 rises +25 % at CAB 0.6 and +27 % at CAB 0.8, and CAB 0 to 0.01 reads -0.72 dB in the top band at 1x against -0.10 at 4x. At CAB 0 the snare's render step also exceeds 0.5 dB from DRIVE 0.55 up (0.75 dB). Decision 4 above therefore stands at its default (always 4x). A gate is a follow-up for the owner to order: it needs a cabinet whose 1x response is within 0.5 dB per third-octave of the 4x one, and a threshold chosen by render difference; it would save about 31 ms per rendered second (33.4 to 2.1, measured on a 4 s stereo kick) below the threshold.
- **`amped` stays at DRIVE 0.6.** At the confirmation listen the owner heard `amped` (DRIVE 0.6, gain 2.5) as the same as before on kick and snare, so it stays at 0.6, below the default (DRIVE 0.7, gain 11.3), where V1's sat above it (0.6 against 0.45). The other characters sit a step past their section's default; `amped` is the exception by the owner's ear.

## R1, as built

R1 is the engine: `Magnet.kt`, `MagnetPatch.kt`, the registration, the kit and the audition generator, on R0's `Strings.pickup` and the rack's VALVE. It was built on 2026-09-30 and every test is green at the last commit, which has the base branch merged in (synth 1 239, shell 918, cli 97 tests; none failing, none skipped; `MagnetTest` is 27 of the synth tests; before the merge the counts were synth 1 130 and shell 910). The first build had not been heard. It has been heard twice since ("The gate and the second listen"): items 2 and 3 pass, and the owner's picks (CHUG's landing at gain 106, JANGLE's amp and its pitch-compensated ring) were built as task 8a and are rulings 19 to 22; after it the counts are synth 1 244, shell 918 and cli 97, none failing or skipped, and `MagnetTest` is 32 of the synth tests. The tests prove that the string is in tune, that the comb and the humbucker notch what the physics says, that BLEND, MUTE and PICK move what they claim to, and that the amp does not move the pitch. None of that says the result sounds like a guitar; the audition (`generateMagnetAudition`, then the listening page) is the gate, and what it said is in "The gate and the second listen". Where the plan or the design text above differs from the build, this section states the build. The design text differs in these places: "Data flow and compatibility" promises an arm in `Presets` and "Testing" says `PresetsTest` and `UserPresetsTest` gain the roster, and neither was added because MAGNET has no roster; "Macros" gives CHUG's landing DRIVE as 0.85, which the first build landed at 0.71 (the V1.1 note in that paragraph) and the owner's second listen restored (ruling 19); "Phasing and gates" lists seven audition items, and the generator adds the LAND, PICKDEF and CHUGAB sections (with a cut-matched, a decay-matched and a symmetric both-cut pair of blind bars) and BLIND at both PICK defaults. The design's conditional PICK registration was followed: the sweep passed and PICK is registered. The plan's ruling 3 is the text that differed (ruling 3 below).

### What landed

- `Magnet.kt` (299 lines after task 8a, 246 at the first build: `MagnetVoice { JANGLE, CHUG }`, the four macros, `frequencyFor`, `string`, `pickup`, `finish`, `render`, `landingChain`, `scramble`) and `MagnetPatch.kt` (32 lines).
- Registration: an arm in `Patches.fromJsonValue`, an arm in the exhaustive `Velocity.macroSpecsFor`, and `patch is MagnetPatch -> "PICK"` in `Velocity.brightnessOverride`. No `Presets` arm: MAGNET has no roster, and `MagnetTest` pins both the dispatcher's knowledge of MAGNET and the empty roster.
- `SynthKits.magnet()` and `testkit/SnipSnap Magnet Kit/`; `MagnetKitGenerator` and `generateMagnetKit`.
- `MagnetAuditionGenerator` and `generateMagnetAudition`: 95 clips, 22.3 MB (22 310 936 bytes), all mono 16-bit 44.1 kHz.
- Tests: `MagnetTest` (27 at the first build, 32 after task 8a), `MagnetMeasure` (the measurement helpers, with the ring reads task 8a added), a canary each in `DeterminismTest` and `PadRecipeTest`, the kit's test in `SynthKitTest`, and two MAGNET entries in `VelocityGrooveShuffleTest`.
- The blocklist terms ("The names, as checked"), and row S20 in `docs/SYNTH_ROADMAP.md`.
- No change to `Strings.kt`, `FxChain.kt` or the phone, and none to `Valve.kt`'s behaviour (its KDoc on the native-rate `process` overload now names the MAGNET audition's P1 clips as a second user).
- Size, counted by `git diff --numstat` from `421074ed` (the commit the R1 work started from) over the branch's own commits, before the base branch was merged in (the merge brings the base's changes, so a count taken on the merged tree would include them), as added lines outside the plan, this document, the roadmap and the kit's files: 1 622 in all, 320 of them engine and registration (`Magnet.kt`, `MagnetPatch.kt`, and the arms and kit in `Patches.kt`, `Velocity.kt` and `SynthKits.kt`; `Valve.kt`'s comment lines not counted), 1 279 tests, generators and measures, 23 build wiring and ignore rules, against this document's estimate of about 1 400 less the amp (the tests grew with the review rounds). The listening page is built by hand from the generator's clips and is not in the repository.

### The gate and the second listen

The audition gate ran in two rounds, both by the owner's ear, on listening pages built by hand from the generator's clips and the spikes' (the pages are not in the repository).

**The gate, 2026-10-01** (46 of 46 questions answered). **Item 2 passes, clean.** The cut-matched pair: the guitar picked correctly, told apart by the tone, "night and day"; the symmetric both-cut pair: correct, by the tone, "clearly different"; the two controls (decay-matched, as played) also correct by the tone; all four pairs locked in order. **Item 3 passed on one condition.** The owner called the guitar on six of the nine blind JANGLE clips (E2 and E3, landed at PICK 0.6, landed at PICK 0.8 and dry: all six) and "harp or plucked string" on the three at E4 (landed at 0.6, landed at 0.8, dry), and never called a synth. The specification does not quantify item 3's bar, and the owner passed JANGLE once its landing amp had been examined (the JANGLE amp rated only "ok" on a snare and a choir, and E4 read as a harp both dry and landed). Both voices' verdicts were "keep". The kit: CHUG and JANGLE "usable", the ends "clean" (the tail fade works), the lead pair "usable". CHUG's landing amp: the owner chose DRIVE 0.85 with CAB 0.95 (gain 106, the specification's original number) over the built gain 13. PICK's ends: "not extreme enough" on both voices; MUTE and BLEND "right"; PICK's default: the specification's darker one preferred on both voices (0.6 and 0.55). The placement A/B at B2 and B3: no difference between the amp inside the render (P1) and the recipe's split, so the simpler recipe loses nothing. A snare and a choir through CHUG's amp "good", through JANGLE's "ok".

**Three spikes followed** (the JANGLE amp, PICK's ends, JANGLE's sustain; their code and tables are in the task folder and not in the repository). The JANGLE amp was a linear filter (a gain of 0.257 into a tube that is linear there: ruling 21), and E4's problem was in the dry string, not the amp: the loop loses a fixed amount per round trip, so E4 rings about a third as long as E2 (ruling 20). PICK's range is lost downstream of the exciter (the loop's body corner and the pickup's resonance cap the bright end, the exciter's own slope floors the dark end): that is a later change and no part of this record.

**The second listen, 2026-10-01/02** (one page, 87 clips, 29 questions). The blind E4 clips: **every E4 clip without the long ring was called a harp, and every clip with it a guitar.** Harp: JANGLE's old amp, the three candidate amps (a light crunch, a crunch, VALVE's default) and the dry note, all with the old ring, and also the two laws that changed the ring without both lengthening and brightening it (L1, the feedback alone; L3, half strength). Guitar: law L2 (the long, bright-tailed ring, ruling 20) dry, and L2 through each of the three candidate amps. At E3 the old ring through JANGLE's old amp was a harp, and VALVE's default amp and L2 were each a guitar. The unblinded picks: VALVE's default amp for a snare, the crunch for a choir, L2 at both E4 and E3, and VALVE's default amp with the long ring; the verdict on JANGLE: "pass". The same page's PICK answers (the spike's candidate that opens both ends, picked at both ends of both voices, smooth sweeps, "yes") are the next change and not part of this one.

### The rulings, as built

The plan made twelve rulings before it was executed and the build added six (13 to 18); task 8a added four after the owner's two listens (19 to 22). Each is as planned except where it says it differs.

1. **f0 is `Keys.midiHz(rootMidi + snapped semitones)`** (JANGLE root MIDI 40, CHUG 35), the route FORK and BORE use; `Magnet.frequencyFor` feeds the render, the seed and every tuning test. It differs from the spike's literal root Hz by 0.065 cents (E2) and 0.129 cents (B1).
2. **The string budget** is the spike's: `Strings.pluck(f0, 4.0, ...)`, then `Strings.trimToDecay(raw, rate, 0.25, 4.0)`, then the string's own end fade, by path (rulings 18 and 22; they are not the spike's), with JANGLE's damping compensated for pitch (ruling 20) where the spike's is `Strings.damping`'s as it stands.
3. **PICK is `Dsp.expMap(PICK, 600, 16 000)` on both voices, and it differs from the plan in what was done with it.** The plan printed the 1 % clause, asserted only the monotonic one, and made no velocity registration, projecting that the top one or two tenths would fail. The measurement showed the clause passing at every tenth on both voices (smallest 1.61 % on JANGLE, after the ring law of ruling 20 (1.77 % before it), and 2.03 % on CHUG, at the defaults), so `patch is MagnetPatch -> "PICK"` **is** registered in `Velocity.brightnessOverride` and the clause **is** asserted (at the defaults only; a second test asserts only that the centroid never falls from one tenth to the next at four corners of MUTE, TUNE and BLEND on each voice). The projection was extrapolated from the spike's table (exciter position 0.10, a narrower map, an unaligned humbucker) and was wrong. A landed pad's velocity layers still fall back to `soften`: `canUseAtVelocity` requires `fx == null`, so the override reaches a bare patch only.
4. **BLEND weights are linear, positions fixed.** The neck group sits at 0.42 weighted `1 - BLEND`, the bridge group at 0.12 weighted `BLEND`. Each group is one `Strings.pickup` call; a humbucker group is its two coils (`dp = 0.0278 · f0 / rootHz`, 0.707 each), aligned inside the group and never aligned between neck and bridge. A call whose weight is exactly 0 is skipped. One `Dsp.TptSvf` resonance follows the sum.
5. **The output chain is copied into `Magnet.finish`**: `Tide.bandLimit`, `Dsp.decimate`, mean removal and a 20 Hz one-pole high-pass, `Dsp.levelTo` the melodic target, `Dsp.fadeTail`.
6. **`scramble` is the defaults-only form** (MAGNET has no roster to seed from); all four macros roll, TUNE included.
7. **The landing chain is `Magnet.landingChain(voice)`**, never null, never routed through `Presets.landingFor`. JANGLE: DRIVE 0.70, SAG 0.35, TONE 0.50, CAB 0.60 (written out, ruling 21). CHUG: DRIVE 0.85 (gain 106), SAG 0.4, TONE 0.3, CAB 0.95 (ruling 19). The first build landed JANGLE at DRIVE 0.25, TONE 0.55, CAB 0.35 (SAG left at VALVE's default 0.35) and CHUG at DRIVE 0.71 (gain 13.2): the specification's CHUG DRIVE 0.85 was written on V1's gain law, where it meant gain 13, and on the V1.1 law it is gain 106; the owner's second listen chose gain 106. Every value is *shape*.
8. **Pitch is read two ways.** The dry claim is read on the raw 176.4 kHz buffer with `FineTuning`; the through-VALVE claim is the interpolated `BoreMeasure.cents` on the dry and wet renders, because `Pitch.detect` is integer-lag (one step is up to 12.9 cents at JANGLE E4).
9. **One copy of the measurement helpers**: `MagnetMeasure` (`amplitudeAt`, `relH1`, rounding for the prints); `MagnetTest`'s own `amplitudeAt` delegates to it.
10. **Humbucker parity**: the tests read `Strings.combDelay` at run time and print the parity. CHUG's real cell is even (D2 - D1 = 80).
11. **The kit is built inline** from defaults plus TUNE, all pads `DrumClass.TONAL`, no preset lookups; A15 and A16 are CHUG at BLEND 0.35 with a hotter chain written in the kit.
12. **The audition page is built by hand**; the generator writes WAVs and a flat manifest only.
13. **The DC bound is 1e-4 (about -80 dBFS), and it differs from the plan's reading of it.** The first build set it at the measured worst plus 20 % (5.3e-7), which is float-residue scale and would trip on any later change to a shape value. The pickup comb has no DC gain and the output chain's 20 Hz high-pass removes the rest, so the corner test cannot tell whether the DC stage exists. Two direct `Magnet.finish` tests are the guards (below).
14. **The audition has 95 clips, not 47 or 83** (KIT 16, LAND 6, CHUGAB 18, BLIND 9, CHUG 14, JANGLE 14, PICKDEF 8, PLACE 4, AMPPADS 6). The spec's PICK defaults (0.6 and 0.55) are 0.94 and 0.48 octave darker than the spike's exciter corners, so a dull default could fail item 3 for the wrong reason: PICKDEF and BLIND carry both defaults. And as played the MAGNET chug stab rings 1.98 s against the saw stab's 0.51 s, so the as-played pair can be told apart by duration alone: CHUGAB adds a cut-matched pair (ruling 15), a decay-matched pair (ruling 16) and a symmetric both-cut pair (ruling 17).
15. **The length-matched chug stab is a palm mute measured after the amp, with its release after the amp.** The dry CHUG render at TUNE 0.5 is trimmed to the saw stab's length (22 684 frames, no fade of its own; the engine's 150 ms string fade of ruling 18 begins about 190 frames before the cut), run through the same VALVE chain, and its end level is read on that amped cut: the peak over its last 5 ms against its own peak. MUTE is the lowest step (0.05 from 0.60 to 1.00) at or under 0.03 (-30.5 dB). None reaches it (-14.1 dB at MUTE 0.60 to -29.9 dB, 0.03213, at 1.00), so MUTE is 1.00, the generator prints a WARNING and the clip's label states the residual. A 100 ms release (`Dsp.fadeTail`, linear) follows the amp, then the scale to the saw stab's `Loudness.of`. An earlier build read the end level on the dry render and faded 40 ms before the amp; it chose MUTE 0.85 at -41.7 dB dry, but VALVE normalises to peak 1 and applies gain 13.2, which lifts a quiet tail (by 19 dB at MUTE 0.85: -41.7 dry, -22.7 amped; by 22.5 dB at MUTE 1.00: -52.4 dry, -29.9 amped), so that clip's WAV ended near -20 dB re peak against the saw stab's near -59 dB (the Task 4 and 5 review's reading of it) and sounded cut. The dry end level said nothing about what is heard.
16. **The decay-matched saw stab is a control for the cut, and it is not a blind test.** Item 2 is must-pass, and the cut-matched pair carries a stated residual (the string is still at -18.1 dB re peak where its 100 ms release begins, because VALVE lifts a quiet tail by about 22 dB at CHUG's landing gain), so the comparison is also made the other way, with no cut at all: the saw stab's BRASS `DECAY` is raised (0.35 to 1.00 in 0.05 steps, TUNE 2/24, every other macro as the existing saw stab) until its dry render is as long as the as-played MAGNET stab (87 318 frames, 1.98 s), then run through the same VALVE chain and loudness-matched to the as-played MAGNET stab. VELVET renders 1.4 times its T60 and T60 is `expMap(DECAY, 0.15, 0.9)` s, so the ceiling is 55 566 frames (1.26 s) at DECAY 1.00: 36 % shorter than the MAGNET stab. A rule in the generator uses DECAY 1.00 as it is when it is under 90 % of the target, prints a WARNING and puts the shortfall in the labels; it fired. The price is a longer saw stab than a real stab and a rendered length that still differs by 0.72 s (a ratio of 1.57 where the as-played pair is 3.85), so the pair is presented as a length-confounded control, not as a blind test (the page contract is open item 7).
17. **A symmetric both-cut pair is the pair that can pass item 2.** The cut-matched pair's release begins on the guitar side alone, so every cue it leaves (the cut, and the release starting at -18.1 dB re peak) can only push the owner toward "I can tell them apart", which item 2 once counted as a pass; the decay-matched and as-played pairs carry length cues for the same reason. The fourth pair cuts both as-played stabs (the amped CHUG stab and the loudness-matched saw stab) at the same short length after the amp, gives both the same 100 ms release (`Dsp.fadeTail`, the constant and the call the matched stab uses), and scales the guitar cut to the saw cut's `Loudness.of` after the release, so the ending is on both sides and the length is equal. The cut is the longest of 0.35, 0.30, 0.25 and 0.20 s at which both stabs are still ringing, the peak over the 5 ms before the cut at least -25 dB re each stab's own peak. Measured (CHUG, saw): 0.35 s -5.3 and -38.5 dB, 0.30 s -5.1 and -31.6 dB (the saw is not ringing at either), 0.25 s -5.0 and -20.4 dB (both ringing), 0.20 s -4.8 and -13.4 dB, so the cut is 0.25 s, 11 025 frames on both stabs. `Loudness.of` is 0.2515 on both (ratio 1.0000; the generator checks 1 %), and the two bars are 274 180 frames each (the generator checks both equalities). As heard on the final WAVs in dB re file peak, guitar then saw: the 5 ms before the release starts -4.1 and -7.2; the peak over the last 120 ms -4.1 and -5.5; the last 5 ms -36.7 and -48.0. The clips are `blind_7` (the saw bar) and `blind_8` (the guitar bar), and the named stabs `stab_magnet_short` and `stab_velvet_short`; the page order is `blind_1`/`blind_2`, `blind_7`/`blind_8`, `blind_5`/`blind_6`, `blind_3`/`blind_4`. Item 2 passes only on the tone, on the cut-matched pair and on this one ("Phasing and gates", the pass rule); none of the four new files is byte-identical to another clip.
18. **A string the trim ended on its decay is faded before the amp.** (As first built, at its default-MUTE measurements; ruling 22 re-chose the shape and length across MUTE and added the ring-ceiling path.) `Strings.trimToDecay` cuts a decaying string 60 dB under its peak with no fade (only its ring-ceiling and budget cuts are faded), `Magnet.finish` adds only a 4 ms fade, and VALVE lifts a quiet end by its gain (22 dB at CHUG's landing, 13.2), so every landed pad that ends on its decay ended abruptly: the riff pads A02 to A08 at -33.5 to -37.8 dB under their peak and the lead pads A15 and A16 at -25.4 and -24.7 (the first build's open item 12). A fade applied before the amp survives it. `Magnet.string` then applied a squared fade (`g = 1 - i/n`, squared: the shape of the ring-ceiling fade) over the last 150 ms of a string whose trim returned a shorter copy, and left a string the ceiling or the budget cut bit for bit. 150 ms is the shortest of 50, 100, 150 and 200 ms that puts the peak of a landed note's last 20 ms at least 50 dB under the note's peak at all 25 TUNE steps at the defaults, through CHUG's landing, CHUG at gain 106 (DRIVE 0.85, SAG 0.4, TONE 0.3, CAB 0.95, the specification's original number), the kit's lead amp (BLEND 0.35) and JANGLE's landing. Worst cells at 150 ms: -67.5, -50.7, -57.3 and -97.5 dB (before the fade: -33.0, -16.8, -22.7 and -61.8); 100 ms leaves gain 106 at -43.8 dB and the lead amp at -50.3. The measure is the review's (peak of the last 20 ms against the note's peak, which reproduces its -33.5 to -37.8); on the RMS of the last 20 ms 100 ms would have passed, gain 106 at -55.9 dB. The dry note's length and its first second are unchanged (at most 4.4e-7 of peak before the fade; A01, A09 and A10 are byte-identical). The kit's A02 to A08 and A11 to A16 WAVs were regenerated and now end at -67.5 to -74.6 dB (A02 to A08), -97.8 to -101.6 (A11 to A14) and -62.7 and -59.4 (A15, A16). `a landed CHUG note ends at least 50 dB under its peak at every TUNE step` pinned it at CHUG's landing and at gain 106 (worst -67.5 and -50.7 dB) and failed with the fade removed (worst -33.0 and -16.8 dB, 48 of 50 cells short); ruling 22's grid test replaced it. One case was not fixed then: open item 14, fixed by ruling 22.

19. **CHUG lands at gain 106 (DRIVE 0.85), the owner's pick by ear.** At the gate's LAND section (DRIVE 0.71, 0.78 and 0.85 against CAB 0.6 and 0.95, labelled by their gains 13, 37 and 106) the owner chose `chug_d85_c95`: DRIVE 0.85, CAB 0.95, SAG 0.4, TONE 0.3, the specification's own number on VALVE's present law (ruling 7 had read it as written on V1's law and built gain 13). `Magnet.LANDING_VALVE[CHUG]`'s DRIVE goes from 0.71 to 0.85 and nothing else in it moves. The kit's own lead amp (`SynthKits.LEAD_VALVE`, DRIVE 0.78, pads A15 and A16) is not touched. The cost is not in the pitch (the worst wet-minus-dry at either landing is 0.08 cents, "What R1 measured") but in the end of the note: gain 106 lifts a quiet end by 40 dB, which is why ruling 22 exists.
20. **JANGLE's ring is pitch-compensated (law L2), and CHUG's is not.** The cause, measured by the sustain spike: the loop loses a fixed amount per round trip (0.094 to 0.098 dB for the fundamental on JANGLE), and a note r times above the open string makes r times the round trips per second, so the fundamental loses 7.7, 15.6 and 32.4 dB per second at E2, E3 and E4 (fitted exponent +1.03 against f0, r2 1.000; the loop's own gain predicts each of the 50 notes of both voices to three digits), and E4 is 20 dB down after 0.32 s where E2 takes 1.10 s. The law is `r = f0 / f_ref`, `f_ref` the open string (`Keys.midiHz(rootMidi)`, 82.41 Hz), `fb' = fb^(1/r)` and `loopHz' = loopHz · sqrt(r)`: the feedback keeps the fundamental's loss per second the same at every pitch, and the corner is what brings the upper partials back (they die within the first half second at a fixed corner). The spike's table, JANGLE at the defaults, dry (L0 is the first build, L1 the feedback alone, L3 half strength: `fb' = fb^(r^-0.5)`, `loopHz' = loopHz · r^0.25`):

| | L0 | L1 | **L2** | L3 |
|---|---|---|---|---|
| E4 note length, s | 1.27 | 4.00 (ceiling) | **4.00 (ceiling)** | 2.53 |
| E4 t-20 / t-40, s | 0.32 / 0.73 | 0.59 / 2.22 | **1.20 / 2.80** | 0.62 / 1.43 |
| E4 fundamental's loss, dB per second | 32.4 | 9.3 | **8.1** | 16.2 |
| E4 late h6, h7 re h1, dB | -43.1, -50.9 | -49.2, -58.3 | **-14.8, -13.4** | -26.1, -28.5 |
| E4 share of onset power above 5 kHz, % | 5.8 | 5.3 | **10.1** | 7.5 |
| E3 t-20, s (E2: 1.10) | 0.71 | 1.14 | **1.34** | 0.97 |
| JANGLE cells at the 4 s ceiling, of 75 | 33 | 49 | **50** | 38 |

   L2 is the law the owner heard as a guitar at E4, and L1 and L3 as a harp ("The gate and the second listen"). It costs a brighter E4 attack (5.8 to 10.1 % of the onset's power above 5 kHz) and more notes at the ring ceiling (33 to 50 of the 75 cells), which is ruling 22's problem. TUNE 0 is r = 1, where every operation of the law is exact (`sqrt(1)`, `pow(1)`), so the open string is the uncompensated string bit for bit; `MagnetTest` asserts it at twelve cells (four MUTEs by three PICKs, string and render) and asserts that the law moves every other step it samples. The law sits behind a per-voice flag in the private `Spec` (`compensate`: JANGLE true, CHUG false), so the choice is one visible constant, and `Magnet.string` takes the flag as an internal argument so that a test reads the same chain with the law off. **CHUG's ring is unchanged by the law**: the owner passed CHUG's voice and nothing here proposes compensating it (the spike measured what it would do: B3 from a 0.8 s thud to 1.6 to 3.2 s, and MUTE's range at B2 narrowed). That says the law, not the render: ruling 22's end fades apply to both voices, so CHUG's last 150 ms (decay path) and last 650 ms (ring-ceiling path) differ from the first build's, and `MagnetTest` reads CHUG's string against the law-off chain and against a hand-built string from `Strings.damping` up to those fades.
21. **JANGLE lands through VALVE's default amp, written out: DRIVE 0.70, SAG 0.35, TONE 0.50, CAB 0.60.** The first build's amp (DRIVE 0.25, TONE 0.55, CAB 0.35, SAG at VALVE's default 0.35) was measured by the amp spike as a linear filter: a gain of 0.257 on a pad VALVE normalises to peak 1, where at the note's own peak the tube is 0.19 dB under its small-signal gain and over the body of the note 0.005 dB, so it changed the tone and nothing else. The owner passed JANGLE with VALVE's default amp (the spike's c8, gain 11.3) heard with the long ring of ruling 20. The four numbers are written into `LANDING_VALVE` and not read from VALVE's defaults, so a change to VALVE's defaults cannot move a landing, and `MagnetTest` pins all four. Blind, no amp alone turned E4 into a guitar (ruling 20); with the ring, each of the three tried did.
22. **A string ends on its own fade on both paths, chosen by a grid across MUTE.** The requirement (from the end review and the PICK spike's flag): the peak of the last 20 ms of a landed note at least 50 dB under the note's peak, with 3 dB to spare, for CHUG at DRIVE 0.85, the kit's lead amp (DRIVE 0.78, CHUG at BLEND 0.35) and JANGLE's landing, at all 25 TUNE steps and MUTE 0, the voice's default and 1, at the default PICK: 225 cells, 102 on the ring-ceiling path and 123 on the decay path. A string takes the path its trim chose before the fade, so the two paths' cells are disjoint and each fade is chosen from its own column of one sweep: shapes `(1 - i/n)^p` for p = 2, 3, 4 at 150, 250, 400 and 600 ms, applied after `Strings.trimToDecay` (on the ceiling path on top of the trim's own 400 ms squared fade). Worst cell, dB under the note's peak (more negative is better; the bar with its margin is -53):

| shape and length | ceiling path, worst | decay path, worst | cells over -53 dB (ceiling / decay) |
|---|---|---|---|
| first build (no extra on the ceiling, squared 150 ms on decay) | -28.1 | -47.5 | 47 / 13 |
| squared 150 ms | -47.6 | -47.5 | 1 / 13 |
| squared 250 ms | -56.4 | -56.0 | 0 / 0 |
| squared 400 ms | -66.1 | -60.6 | 0 / 0 |
| squared 600 ms | -75.6 | -60.6 | 0 / 0 |
| cubed 150 ms | -49.8 | -58.5 | 1 / 0 |
| cubed 250 ms | -61.5 | -71.1 | 0 / 0 |
| cubed 400 ms | -75.6 | -71.8 | 0 / 0 |
| cubed 600 ms | -90.5 | -71.8 | 0 / 0 |
| fourth power 150 ms | -50.9 | -59.3 | 1 / 0 |
| fourth power 250 ms | -64.7 | -75.0 | 0 / 0 |
| fourth power 400 ms | -82.2 | -75.8 | 0 / 0 |
| fourth power 600 ms | -101.4 | -75.8 | 0 / 0 |

   The worst ceiling cell is CHUG at gain 106, TUNE step 2, MUTE 0, in every row; the worst decay cells are CHUG at gain 106 at MUTE 1 (TUNE step 3 at squared 150 and 250 ms, step 21 at squared 400 and 600, step 24 from cubed up). Per landing, the first build's worst cells were -28.1 / -47.5 (CHUG at gain 106, ceiling / decay), -32.1 / -50.9 (the lead amp) and -50.6 / -75.8 (JANGLE); the chosen pair gives -56.4 / -58.5, -68.2 / -63.6 and -89.2 / -92.5. The rule: the shortest length at which any shape holds every cell 3 dB inside the bar, then the mildest shape (the smallest exponent), unless a steeper and shorter one removes less of the tail, judged by where its fade reaches -10 and -20 dB before the end. **Ceiling path: a squared fade of 250 ms**, added after the trim: 150 ms leaves one cell short of the margin at every shape (the fourth power's 50.9 dB is inside the bar and 2.1 dB short of the margin), 250 ms is the shortest that passes, and squared is the mildest at it; worst -56.4 dB. The PICK spike's table 2j found that lengthening the squared fade ahead of the amp did not fix the open-string and MUTE 0 cells: it varied the decay path's fade (`TAIL_FADE_MS`), which a string the ring ceiling cut never reaches, so those cells kept only the trim's own 400 ms fade and the new fade, on the ceiling path, is the one that moves them. **Decay path: a cubed fade of 150 ms**, replacing the first build's squared 150 ms (which misses at MUTE 1, 47.5 dB at TUNE step 3: the first build's grid read the default MUTE only): squared 250 ms, cubed 150 ms and fourth power 150 ms all pass (-56.0, -58.5, -59.3); cubed 150 ms is shorter than squared 250 ms and removes less of the tail (its fade is -10 dB 102 ms before the end against 141 ms, and -20 dB 70 ms against 79 ms), and it leaves the front of the shortest strings alone (CHUG's shortest, 260 ms at TUNE step 24 and MUTE 1, would have its squared 250 ms fade begin 10 ms in); worst -58.5 dB (CHUG at gain 106, step 24, MUTE 1). The first build's own fade was -10 dB 84 ms and -20 dB 47 ms before the end, so every decay-ended note is shorter by 18 and 22 ms at those levels.

   The cost to a ring-ceiling note's audible tail (the dry notes, 40 ms RMS in dB under the note's peak, the first build's against the chosen): the extra fade starts 3.75 s into the 4 s note; alone it is -10 dB 141 ms and -20 dB 79 ms before the end, and together with the trim's 400 ms squared fade the note is -10 dB 237 ms and -20 dB 178 ms before its end (225 and 126 ms with the trim's alone). At 3.75 s the first build and the chosen differ by 0.6 dB, at 3.85 s by 9 to 10 dB and at 3.90 s by 16 to 17 dB, so a note that has decayed by 40 dB at 3.5 s (JANGLE at the default MUTE: -40.3 dB at E2) loses nothing audible, and an open string still ringing loses its last quarter second: CHUG's B1 at MUTE 0 (-6.4 dB at 3.5 s) read -24.1 dB at 3.85 s and -31.7 dB at 3.9 s and now reads -33.5 and -47.9 dB (its -60 dB point, which the first build never reached, now falls at 3.93 s). `MagnetTest`'s grid test reads ten of the 25 TUNE steps (0, 1, 2, 3, 4, 7, 12, 18, 21 and 24: the binding cells of the full sweep and the ends of the range) at the three landings, MUTE 0, the default and 1, the PICK values as a list (the default only) a later change extends, 90 cells in about 9 s (the full 25 steps would be about 21 s), and asserts -53 dB at every cell. Mutation checks: with the ceiling fade deleted the worst cell reads -28.1 dB (CHUG, step 2, MUTE 0) and 18 of the 90 cells fail; with the decay fade back to squared 150 ms it reads -47.5 dB (CHUG, step 3, MUTE 1). The kit pads move: every pad's WAV changes on regeneration (CHUG pads A01 to A08 through the new landing and the fades, A15 and A16 through the fades alone, JANGLE pads A09 to A14 through the new amp, the ring law above E2 and the fades; A09's string is the open string, which the ring law leaves, but it is a ring-ceiling note and its end changes), and the committed kit is the first build's until the next task regenerates it once.

### What R1 measured

Each test prints its numbers in one line before it asserts. All of these are from the printed lines on a desktop JVM at the source of the commits named in the task reports. The figures task 8a moved (JANGLE's, by the ring law of ruling 20 and the amp of ruling 21; CHUG's landing by ruling 19) are the after-8a reads, with the first build's beside them where they are named.

**In tune.** 450 cells (2 voices × 25 TUNE steps × MUTE {0, 0.5, 1} × BLEND {0, 0.5, 1}) read on the raw pickup buffer at the render rate (176.4 kHz), before the output chain: JANGLE -1.07 to +1.11 cents, CHUG -1.66 to +1.51. The worst cell is 1.66 cents (CHUG, TUNE step 1, MUTE 0, BLEND 1, -1.6591337687515584), against the 5-cent bar and the specification engine's -69. The spike read -0.62 to +1.11 over 36 cells.

**The single-coil comb** (JANGLE, TUNE 0.5, default MUTE, resonance off, neck alone; read after the ring law of ruling 20). At pickup position 0.5, h2 is 46.4 dB and h4 47.0 dB under their neighbours (h1 to h8 re h1: 0.0, -48.7, -2.3, -49.3, 11.9, -42.6, 8.4, -45.9); at 0.25, h4 is 37.5 dB (0.0, 9.9, -2.3, -39.8, 12.0, 10.1, 8.3, -35.4). The bar is 20 dB. The spike read 52, 54 and 47: **this engine's notches are 5.6, 7.0 and 9.5 dB shallower (10.8, 10.7 and 9.8 before the ring law, at 41.2, 43.3 and 37.2 dB), and the cause is not isolated.** The exciter's position (0.085 here, 0.10 in the spike) was the first guess and was not tested.

**The humbucker** (CHUG, TUNE 0.5, BLEND 1, resonance off). `dp` 0.0556, k 18, D1 171, D2 251, D2 - D1 even (80), string 1.98 s. The aligned pair puts h18 34.1 dB under the single coil re h1; the naive sum puts it 1.4 dB under. The bar is 20 dB aligned and under 20 dB naive; the spike read 34 dB aligned. R0's odd-difference read was 32.2 dB aligned and -3.0 dB unaligned (3.0 dB above the single coil) on a synthetic 100.8 Hz comb. The aligned read is also pinned bit for bit against a direct `Strings.pickup` call. Reverting the alignment alone fails only this test (1.4 dB).

**BLEND** (full pickup with resonance, TUNE 0.5, default MUTE and PICK, neck alone against bridge alone). JANGLE swings 18.0 dB, peaking at h5 (h2 to h8: 11.5, 10.3, 9.9, 18.0, 6.2, 17.2, 8.1), the spike's 18 dB at h5; CHUG swings 14.7 dB, peaking at h2 (14.7, 7.8, 10.8, 7.5, 0.7, 4.9, 1.7). The bar is 6 dB. Setting the neck position equal to the bridge's fails only this test (0.0 dB).

**MUTE** (TUNE 0.5). JANGLE: MUTE 0 renders 4.0 s (176 400 samples), MUTE 1 renders 42 556 samples (0.965 s), a length ratio of 0.241, and the centroid falls from 3083 to 1091 Hz (before the ring law, which lets E3's palm mute ring longer: 21 388 samples, 0.485 s, 0.121, and 2738 to 920 Hz). CHUG: 4.0 s against 29 106 samples (0.66 s), a ratio of 0.165, and the centroid falls from 1261 to 518 Hz. The centroid is `FeatureExtractor`'s onset read (see PICK below). The bar is a ratio under 0.5 and a falling centroid.

**PICK** (the other macros at their defaults: TUNE 0.5 and each voice's default MUTE and BLEND). The centroid is `FeatureExtractor`'s: the power-weighted centroid of a Hann-windowed 4096-point spectrum of the first 4096 samples of the rendered note (about 93 ms of its onset at 44.1 kHz; a shorter note is zero-padded), not of the whole note and not the spike's windowed read over 0.05 to 0.3 s, so these are not comparable with the spike's 1459 to 1618 Hz and 719 to 835 Hz. The corner is `expMap(PICK, 600, 16 000)`. **Ten of ten tenths are at least 1 % on both voices; none is under.**

| PICK | corner Hz | JANGLE centroid Hz | change % | CHUG centroid Hz | change % |
|---|---|---|---|---|---|
| 0.0 | 600.0 | 1378.3502 | | 530.8605 | |
| 0.1 | 833.2 | 1556.8411 | 12.95 | 583.45593 | 9.91 |
| 0.2 | 1157.0 | 1790.5365 | 15.01 | 650.87146 | 11.55 |
| 0.3 | 1606.7 | 2081.0066 | 16.22 | 737.4729 | 13.31 |
| 0.4 | 2231.2 | 2403.5823 | 15.50 | 844.7558 | 14.55 |
| 0.5 | 3098.4 | 2716.0789 | 13.00 | 965.64026 | 14.31 |
| 0.6 | 4302.6 | 2982.6199 | 9.81 | 1083.1447 | 12.17 |
| 0.7 | 5974.9 | 3186.78 | 6.84 | 1179.7838 | 8.92 |
| 0.8 | 8297.1 | 3329.2595 | 4.47 | 1248.3416 | 5.81 |
| 0.9 | 11521.9 | 3420.875 | 2.75 | 1292.0934 | 3.50 |
| 1.0 | 16000.0 | 3475.855 | 1.61 | 1318.3434 | 2.03 |

JANGLE's column is read after the ring law of ruling 20 (the first build's read 1189.8 Hz at PICK 0 to 3069.6 Hz at PICK 1). The smallest step is JANGLE's 0.9 to 1.0 at 1.61 % (1.77 % before the law), then CHUG's at 2.03 %. The centroid spans 2.52× on JANGLE (2.58× before the law) and 2.48× on CHUG end to end. The steps shrink toward the top and the cause was not investigated. The 1 % clause is measured at the defaults of the other macros only, so it is not a proof for every note, mute and blend. The weaker clause, that the centroid never falls from one tenth to the next, was also read at the corners of MUTE, TUNE and BLEND on each voice: all eight corners per voice passed once (16 sweeps, no fall, at the first build; the committed test, run after the ring law of ruling 20, reads its eight sweeps with no fall), and the committed test runs four per voice (MUTE, TUNE and BLEND at 0/0/0, 1/1/1, 0/1/1 and 1/0/0); a soft hit (velocity 0.25) re-rendered at PICK is darker than a hard one (velocity 1) on both voices.

**The pitch through the amp** (interpolated `BoreMeasure.cents`, 0.05 to 0.25 s at `Dsp.RATE`, other macros at their defaults). The landing is `Magnet.LANDING_VALVE`; gain 1000 is DRIVE 1, SAG 0, TONE 0.5, CAB 0 on VALVE's current law (the specification's bar was written on V1's law, where DRIVE 1 was gain 35). Cents are against `Magnet.frequencyFor`.

| voice | TUNE | Hz | dry cents | landing cents | landing wet minus dry | gain-1000 cents | gain-1000 wet minus dry |
|---|---|---|---|---|---|---|---|
| JANGLE | 0.0 | 82.41 | 0.12 | 0.12 | 0.00 | 0.19 | 0.07 |
| JANGLE | 0.5 | 164.81 | 0.08 | 0.06 | -0.02 | -0.06 | -0.14 |
| JANGLE | 1.0 | 329.63 | 0.00 | 0.03 | 0.03 | -0.33 | -0.33 |
| CHUG | 0.0 | 61.74 | 0.22 | 0.15 | -0.07 | 0.28 | 0.06 |
| CHUG | 0.5 | 123.47 | 0.21 | 0.29 | 0.08 | 0.40 | 0.19 |
| CHUG | 1.0 | 246.94 | 0.17 | 0.14 | -0.02 | 0.00 | -0.17 |

The worst wet-minus-dry is **0.08 cents at the landing maps** (asserted, bar 10) and 0.33 cents at gain 1000 (printed only); the first build read 0.14 and 0.27 (the table is after rulings 19 to 21: CHUG at gain 106, JANGLE through VALVE's default amp and with the ring law). A landed pad regenerates bit for bit from its recipe through the JSON round trip: JANGLE 176 400 samples (131 638 at the first build), CHUG 87 318.

**JANGLE's ring** (ruling 20; the dry note at the defaults, the fundamental's loss read between 0.1 and 0.4 s). The fundamental loses 7.7, 7.8 and 8.1 dB per second at E2, E3 and E4 with the law and 7.7, 15.6 and 32.4 without (the sustain spike's figures to the printed digit); t-20 is 1.34 s at E3 and 1.20 s at E4 with the law, 0.71 and 0.32 s without. The loop's feedback `fb'` is largest at E4 and MUTE 0, 0.9995 (the bar is under 1; today's largest is 0.998), over all 25 steps and four MUTEs, and every MUTE 0 string (25 steps) is finite, inside -1 to 1, no longer than the 4 s budget and decaying (the RMS over 3.0 to 3.5 s over the RMS over 0.25 to 0.75 s at most 0.436). The 450-cell tuning test, the comb and humbucker tests and the corners all pass after the law unchanged in their bars (tuning's worst is the same 1.66 cents; the law adds no cell over 1.11 cents on JANGLE). Mutation checks, each run on the whole class: reading the open string as one semitone up fails the open-string test (24 of its 24 comparisons differ); halving the feedback exponent (`fb^(1/sqrt r)`) fails the flat-loss test (7.7, 11.0 and 15.8 dB per second) and the t-20 test (E4 0.79 s); leaving the corner unraised (law L1) fails the t-20 test alone (E4 0.59 s, where the loss test reads 7.7, 7.9 and 9.3 and passes); turning CHUG's flag on fails the CHUG test alone (18 differences over six cells); adding 0.0007 to `fb'` fails the stability test (largest 1.0002) and the open-string test (the open string moves).

**DC.** The corner test (all 16 corners of both voices) reads a worst DC of 5.011e-10 and the TUNE-step test (all 25 steps of both voices at the defaults) 2.516e-10, against the bound of 1e-4 (8.580e-9 and 2.551e-10 with ruling 18's fade alone, before ruling 22's; the first build, before any fade, read 4.382e-7 and 8.303e-8); the 0.25 s length floor holds at every corner and step. The two `Magnet.finish` tests drive the stage directly. A constant offset of 0.1 leaves 1.99e-8 of the output's peak between 0.4 and 0.6 s (bound 0.01). A 2 Hz drift ends 19.87 dB under the same drift on the un-filtered decimation (bound 15 dB). Mutation checks: deleting the mean subtraction and the high-pass fails both tests (0.256 of peak left; 0.0012 dB of attenuation); deleting the high-pass alone fails only the drift test (0.36 dB), since mean subtraction alone removes a constant offset.

**The end of a landed note at ruling 18's choice** (the first build, which ruling 22 re-chose across MUTE; the peak of the last 20 ms against the note's peak, in dB, over the 25 TUNE steps at the defaults, worst cell). Through CHUG's landing: -33.0 dB with no fade, -48.6 at 50 ms, -60.5 at 100 ms, **-67.5 at 150 ms (the chosen length, step 3)**, -72.5 at 200 ms. Through CHUG at gain 106: -16.8, -32.1, -43.8, **-50.7**, -55.5. Through the kit's lead amp (BLEND 0.35): -22.7, -38.3, -50.3, **-57.3**, -62.2. Through JANGLE's landing: -61.8, -78.5, -90.5, **-97.5**, -99.8. The bar is -50 dB, so 150 ms is the shortest length that clears it in every case, and gain 106 binds it by 0.7 dB. On the RMS of the last 20 ms against the peak the same table reads, at 150 ms, -80.0, -62.7, -67.7 and -109.0, and 100 ms would pass (worst -55.9, gain 106). The kit pads at 150 ms (the peak measure, read off the regenerated WAVs): A01 -87.8, A02 -67.5, A03 -73.5, A04 -73.5, A05 -68.7, A06 -72.7, A07 -70.3, A08 -74.6, A09 -104.2, A10 -117.6, A11 -101.6, A12 -97.8, A13 -98.3, A14 -99.5, A15 -62.7, A16 -59.4. Not covered by the fade then: CHUG at MUTE near 0 (open item 14, fixed by ruling 22).

**The end of a landed note across MUTE, after ruling 22.** `MagnetTest`'s grid test (three landings, ten TUNE steps, MUTE 0, the default and 1, 90 cells, 42 of them on the ring-ceiling path) reads, worst cell per landing: CHUG's landing -56.4 dB (TUNE step 2, MUTE 0, ring ceiling), the kit's lead amp -63.6 dB (step 4, MUTE 1, decay path) and JANGLE's landing -89.2 dB (step 2, MUTE 0, ring ceiling), against the bar of -50 dB with 3 dB to spare; the full 25-step sweep of ruling 22 (225 cells) has the same worst cells and its table is there. The kit's sixteen pads as the engine now lands them, read on the float renders (the kit is not regenerated): A02 to A08 end at -66.7 to -76.1 dB, A15 and A16 at -79.5 and -76.8, A01 at -112.6 and A09 to A14, which now all run the full 4 s, at -124.1 to -133.9 dB.

**Cost** (a desktop JVM, not the phone). The dry render reads 11.3, 11.9 and 22.2 ms per rendered second in three runs (4.0 s in 45.0, 47.8 and 88.8 ms; the factor of two is a JIT and load effect on a one-shot timing, which is why nothing asserts on it), and VALVE on it 18.9, 19.8 and 18.8 ms per rendered second. The audition generator's median of three after a warm-up, on a 4 s JANGLE (TUNE 0, MUTE 0, the full budget): the render 47.7 ms (11.9 ms per rendered second) and `Valve.process` on it 72.5 ms (18.1 ms per rendered second), against V1's target of at most 20 ms per rendered mono second. A phone runs two to four times slower on BORE's estimate; the phone timing is audition item 7 and is not measured.

**The kit.** `SynthKits.magnet()` is 16 pads, every pad TONAL, every recipe carrying its `valve` section and regenerating bit for bit: A01 to A08 CHUG at MIDI 35 38 40 42 45 47 50 52 (B1 to E3), A09 to A14 JANGLE at 40 47 52 56 59 64 (E2 to E4), A15 and A16 CHUG at BLEND 0.35 through a hotter chain (DRIVE 0.78, SAG 0.4, TONE 0.5, CAB 0.95) at 54 and 59 (F#3, B3). Pitch is read on the dry patch render, never the landed pad: worst 0.06 cents against the 10-cent bound. `testkit/SnipSnap Magnet Kit/` is 5.02 MiB (5 268 637 bytes: the 16 WAVs 5 131 290 and the program 137 347; BORE's 2.4, FORK's 3.4). At the first build A01, A09 and A10 ran the full 4.0 s budget, so their tails ended at the engine's ceiling fade and not where the decay ended (A02 is 3.475 s), and A02 to A08 and A11 to A16 ended through the 150 ms string fade of ruling 18. After task 8a (rulings 19 to 22) A01 and A09 to A14 run the full 4.0 s (the ring law carries every JANGLE note above E2 to the ceiling at the default MUTE), A02 to A08, A15 and A16 end through the cubed decay fade, and every pad's WAV changes on regeneration; the committed kit is still the first build's, and it is regenerated once, at the end of the next task.

### The audition generator

The generator and its clips are the first build's: task 8a did not run it, so every gain, level and end figure in this subsection and in rulings 14 to 17 was measured with CHUG landed at gain 13. The landing is now gain 106 (the generator compiles against it, its LAND section still names the three DRIVEs by their gains, and its other sections read `Magnet.LANDING_VALVE`), and its CHUGAB section's matched-stab and both-cut searches have not been re-read at gain 106. `generateMagnetAudition` writes 95 clips and a manifest to `testkit/magnet-audition/` (git-ignored). Each manifest row is `{"section","id","file","label","truth","order"}`; `order` is the sequence the page presents a section's clips in (CHUGAB's blind bars are orders 1 to 8, in the order `blind_1`, `blind_2`, `blind_7`, `blind_8`, `blind_5`, `blind_6`, `blind_3`, `blind_4`, then its labelled clips 9 to 18). Every clip is finite and non-silent before and after levelling. The blind rows carry neutral ids and file names (`blind_a1` to `blind_a9` in BLIND, `blind_1` to `blind_8` in CHUGAB: the cut-matched pair `blind_1`/`blind_2`, the symmetric both-cut pair `blind_7`/`blind_8`, the decay-matched pair `blind_5`/`blind_6`, the as-played pair `blind_3`/`blind_4`), and the generator checks that no voice word is in a blind row's id, file name, label or section; the one exemption is the section name CHUGAB, which names the question (is the string engine redundant for a chug) and not which clip is which. The manifest is the answer key: `label` and `truth` name the voices (and, on the decay-matched pair, the length difference), so a page built from it shows neither for blind rows, nor their ids, nor the generator's A and B letters, and never the path (open item 7 is the full page contract). The unblinded copies keep descriptive names (`bar_magnet`, `bar_velvet`, `bar_magnet_matched`, `bar_velvet_long`, and the stabs, including `stab_magnet_short` and `stab_velvet_short`).

| Section | Clips | Serves |
|---|---|---|
| KIT | 16 | item 1: the kit as it lands |
| LAND | 6 | CHUG at DRIVE 0.71, 0.78, 0.85 (gain 13, 37, 106; the landing is now the last) against CAB 0.6, 0.95 |
| CHUGAB | 18 | item 2: six stabs (each voice as played, the cut-matched MAGNET stab, the saw stab with its decay raised, and the two short stabs of the both-cut pair) and four bars on the bar line beside a snare (each voice as played, the cut-matched MAGNET bar, the long saw bar), and four blind pairs of bars: `blind_1`/`blind_2` cut-matched (MAGNET then VELVET), `blind_7`/`blind_8` both cut (VELVET then MAGNET), `blind_5`/`blind_6` decay-matched (the as-played MAGNET bar then the long saw bar), `blind_3`/`blind_4` as played (VELVET then MAGNET); the page order is 1, 2, 7, 8, 5, 6, 3, 4 |
| BLIND | 9 | item 3: JANGLE at three notes, landed, at both PICK defaults (`blind_a1` to `blind_a6`), and dry (`blind_a7` to `blind_a9`) |
| CHUG, JANGLE | 14 each | item 4: default and each of MUTE, PICK and BLEND at both ends, dry and landed |
| PICKDEF | 8 | each voice at the spec default PICK against the spike-brightness default (0.8 and 0.65, the map's values nearest the spike's corners), dry and landed |
| PLACE | 4 | item 5: the amp inside the render against the split, at B2 and B3 |
| AMPPADS | 6 | item 6: a snare and a VOX line, dry and through each voice's landing amp |

The length-matched chug stab. As played, the MAGNET stab rings 1.98 s (87 318 frames) and the saw stab 0.51 s (22 684 frames). The matched stab is CHUG at MUTE 1.00, trimmed (no fade of its own; the engine's 150 ms string fade begins about 190 frames before the cut) to 22 684 frames (0.5144 s), run through the landing VALVE, and measured on the amped cut: the last 5 ms peak over the peak, in dB re peak, at MUTE 0.35 (the kit default) -9.2, 0.60 -14.1, 0.65 -15.6, 0.70 -17.1, 0.75 -18.7, 0.80 -20.6, 0.85 -22.7, 0.90 -25.1, 0.95 -27.5 and 1.00 -29.9 (0.03213). The bar was 3 % (-30.5 dB); no step meets it, so the generator used MUTE 1.00, printed a WARNING and put the residual in the label. The dry render at MUTE 1.00 is 29 106 frames (0.66 s). A 100 ms release follows the amp. As heard, on the final WAV in dB re file peak against the saw stab's: the 5 ms before the release starts (the string where the release begins) -18.1 against -48.0; the peak over the last 120 ms -17.6 against -42.9; the last 5 ms -56.7 against -61.9. The match: a gap of 0 frames and `Loudness.of` 0.2531 on both stabs (ratio 1.0000, exact after a linear scale); the generator checks only the gap (within 4 frames).

The decay-matched saw stab. The cut-matched pair has a stated residual, so the pair is also made the other way, with no cut. The generator renders the BRASS saw stab at TUNE 2/24 with DECAY 0.35 to 1.00 in steps of 0.05 and takes the DECAY whose dry render is closest to the as-played MAGNET stab's 87 318 frames (1.98 s). The renders run from 17 338 frames (0.39 s) at DECAY 0.35 to 55 566 frames (1.26 s) at 1.00 (22 684 at the existing stab's 0.5), so the choice is DECAY 1.00, 36 % (31 752 frames, 0.72 s) shorter than the MAGNET stab; that is under the 90 % bar, so the generator printed a WARNING and the labels of `stab_velvet_long` and `bar_velvet_long` and the blind pair say "rings 36 percent shorter even at DECAY 1.0" or "the rendered lengths differ by 36 percent". The stab then goes through the CHUG landing VALVE and is scaled to the as-played MAGNET stab's `Loudness.of` (0.2531 on both, ratio 1.0000), unlevelled before the snare, and its bar is the same `Groove.render` call as the others. As heard (final WAVs, dB re file peak): the long saw stab's 5 ms before its last 100 ms -57.5, last 120 ms -54.0, last 5 ms -69.6, against the as-played MAGNET stab's -37.4, -35.4 and silence (its last 5 ms are all zero samples: the engine's 150 ms string fade and the writer's end fade leave nothing there). The guitar stab sits about 20 dB above the long saw at the first two reads, and only the abrupt last 5 ms of the first build is gone. The blind pair is `blind_5` (the as-played MAGNET bar) and `blind_6` (the long saw bar).

The symmetric both-cut pair (ruling 17). The generator takes the as-played CHUG stab (87 318 frames) and the loudness-matched saw stab (22 684 frames) and cuts both at the same length after the amp. The candidates are 0.35, 0.30, 0.25 and 0.20 s, taken longest first, and a candidate qualifies when the peak over the 5 ms before the cut is at least -25 dB re each stab's own peak; the printed table is: 0.35 s (15 435 frames) CHUG -5.3 dB, saw -38.5; 0.30 s (13 230) -5.1, -31.6; 0.25 s (11 025) -5.0, -20.4, both ringing; 0.20 s (8 820) -4.8, -13.4, both ringing. The longest qualifying is 0.25 s. Each cut gets the matched stab's 100 ms release (`Dsp.fadeTail`, linear), then the guitar cut is scaled so its `Loudness.of` equals the saw cut's (0.2515 on both, ratio 1.0000). Both stabs are 11 025 frames and both bars (the same `Groove.render` call as the others) 274 180 frames. As heard (final WAVs, dB re file peak, guitar then saw): the 5 ms before the release -4.1 and -7.2, the last 120 ms -4.1 and -5.5, the last 5 ms -36.7 and -48.0. If no candidate qualified the generator stops with an error. The clips are `blind_7` (the saw bar), `blind_8` (the guitar bar), `stab_magnet_short` and `stab_velvet_short`; the labels of the two blind bars read "BOTH STABS CUT TO THE SAME LENGTH WITH THE SAME 100 MS RELEASE" and say nothing of which is which.

### The names, as checked

`PresetTestSupport.trademarkBlocklist` gains decision 6's two alternatives, with a comment citing "The name"; `ThumpPresetsTest`'s near-miss list gains the two near-miss strings of "The name" and its clean list gains JANGLE, CHUG, PALM MUTE and WIRE PICK. A search of `synth/src`, `shell/src` and `app/src` for either term, before the edit, found no shipped name and no other source line that matches, so nothing shipped was renamed. The thirteen per-engine blocklist tests run in `:synth:test`.

### What R1 did not do

- **No presets.** MAGNET has no `Presets` roster; presets are authored by ear after the gate, eight per voice, for each voice that passes.
- **No phone.** No picker entry, no `Engine` arm in `SynthScreen`, no chip decision, no README engine count: that is R1.1. The section is reachable through `treat` and through landing recipes, as CONTOUR was.
- **No PICK defaults chosen at the first build.** The defaults are the spec's 0.6 (JANGLE) and 0.55 (CHUG), and the audition carried both; the gate preferred the spec's darker defaults on both voices.
- **No LEAD preset.** The LEAD family is CHUG at BLEND 0.35 through a chain written in the kit. The two landing chains were heard at the second listen and chosen (rulings 19 and 21); the lead chain, the roots, the resonances, the body corners, the coil spacing and the pick position are *shape*.
- No velocity layers through a landed pad (the `fx == null` fallback above), no MAKE INSTRUMENT keygroup (R2), and no listening beyond the audition's two rounds.

### Open items for the gate

1. **The PICK defaults are 0.94 and 0.48 octave darker than the spike's.** At the spec's 0.6 and 0.55 the exciter corners are 4303 and 3651 Hz; the spike's were 8253 and 5099 Hz. Everything the spike measured at its defaults was measured at a brighter exciter. PICKDEF and BLIND carry the spec's defaults against 0.8 and 0.65, so a dull default cannot fail item 3 unseen.
2. **The exciter at 0.085 trades the h10 hole for a 22 to 31 dB dip at h12.** At the spike's 0.10 the comb holds h10 31 to 82 dB under its own peak in every cell of both voices; at 0.085 the first notch sits at k = 11.71 to 11.89 and the worst harmonic to h20 is h12, 22 to 31 dB under the comb's peak (no cell deeper than 30.9 dB on JANGLE at TUNE 1 or 27.5 dB on CHUG). These are the comb's magnitude at the exciter's delay, computed per cell, not read from a render. A real pick does the same; the fixed artefact is moved, not removed.
3. **The comb notches read 6 to 10 dB shallower than the spike's** (46.4 and 47.0 dB against 52 and 54 at position 0.5, 37.5 against 47 at 0.25; 41.2, 43.3 and 37.2 before the ring law), cause unisolated. The 20 dB bar is met with margin.
4. **A voice that fails the gate cannot lose its enum constant after the kit ships.** `MagnetVoice.JANGLE` and `MagnetVoice.CHUG` are named by `SynthKits.magnet()` and by saved patches (`MagnetPatch` decodes by voice name); removing one breaks both. The gate therefore changes R1.1 (a failed voice gets no picker entry) and the presets (no roster), not R1's enum.
5. **The f0 route differs from the spike's literal Hz** by 0.065 cents (E2) and 0.129 cents (B1); the seed hashes `f0`, so the spike's renders are not reproduced bit for bit.
6. **The matched chug stab is the engine's tightest palm mute (MUTE 1.00), not the kit default (0.35), and it is still heard cut, so the cut-matched pair alone cannot honestly pass item 2.** The string is at -18.1 dB re peak when the 100 ms release begins (the saw stab -48.0) and the amped cut ends 29.9 dB under its peak before the release (item 11). Every residual cue in `blind_1`/`blind_2` (the cut, and a release that begins on the guitar side alone) can only make the owner say the two bars differ, which is the direction item 2 counts as a pass: read alone, the pair can produce a false pass. So item 2 asks what mainly told the bars apart and passes only on "the tone itself" (the pass rule in "Phasing and gates"), and the symmetric both-cut pair (`blind_7`/`blind_8`, ruling 17) cuts both stabs the same way, so the ending and the length are on both sides. The tone is the cue that remains beside one small difference, how far each stab has decayed by the cut in 0.25 s (the saw at -20.4 dB and CHUG at -5.0 dB over the 5 ms before it); that difference leans toward the owner answering "how long it rings", which is a fail, and not toward a false pass, so the pass direction is safe. The decay-matched pair (`blind_5`/`blind_6`) has no cut and a saw stab that rings 36 % shorter than the string at its DECAY ceiling, so its residual cue is the length (1.26 s against 1.98 s); the as-played pair (`blind_3`/`blind_4`) carries a duration cue of about 4 to 1. Both are length-confounded controls for interpreting the first two pairs, not blind tests, and neither is part of the pass.
7. **The page contract, and the blind clips that are byte-identical to others.** Several blind clips are byte-identical to labelled ones and to each other: BLIND `blind_a1`, `blind_a2` and `blind_a3` equal KIT A09, A11 and A14; `blind_1` is `bar_magnet_matched`; `blind_2` = `blind_3` = `bar_velvet`; `blind_4` = `blind_5` = `bar_magnet`; `blind_6` is `bar_velvet_long`; `blind_7`, `blind_8`, `stab_magnet_short` and `stab_velvet_short` are new files identical to no other clip. The manifest is the answer key, so the page built from it MUST: show nothing of a blind row but its place in the pair, that is, no id, no label, no path, none of the generator's A and B letters, and never `truth`; reshuffle each pair independently per browser, the mapping keyed to the neutral ids and each answer recorded against them (the *neutral ids* are the generator's own `blind_N` ids, which name no voice and are what an answer carries; the files the browser loads carry the page's own opaque names, which are not the neutral ids; the page displays neither); ask, for every pair, which bar is the guitar, what mainly tells the bars apart (how long it rings, how it ends, the tone itself, or nothing) and how different they sound; present the pairs one at a time in the manifest's `order` (cut-matched, both-cut, decay-matched, as played) and LOCK a pair's answers before the next pair appears, with no going back, because the byte-identical bars (`blind_2` = `blind_3`, `blind_4` = `blind_5`) would otherwise let a later pair decode an earlier one; and present the decay-matched and as-played pairs as length-confounded controls, not as blind tests (ruling 16). BLIND's clips are played before any labelled section. By id number the first bar of each pair (`blind_1`, `blind_3`, `blind_5`, `blind_7`) is MAGNET, VELVET, MAGNET, VELVET, and the reshuffle makes that irrelevant.
8. **The lead and shape values** (the lead chain's four values, the pickup resonances, the roots, the body corners, the coil spacing, the pick position) are re-derived by ear; the two landing chains were chosen by the owner's ear at the second listen (rulings 19 and 21). The lead chain's four values are a literal in `SynthKitTest` and in `MagnetTest`'s end grid because `LEAD_VALVE` is private in `SynthKits.kt`, so changing it is a one-line change in each.
9. **The kit's full-length tails** (A01 and A09 to A14 after the ring law; A01, A09 and A10 at the first build) end at the ceiling fades.
10. **The phone cost** (audition item 7) is not measured; the JVM numbers are above.
11. **The matched stab is, as heard, cut at its end with a 100 ms release, and the amp is why.** At CHUG's first landing gain (13.2; the landing is now gain 106, and the matched stab is not re-measured at it) VALVE lifts a quiet tail by 19 to 22.5 dB, so the dry render's end level (-41.7 dB at MUTE 0.85, -52.4 at 1.00) said nothing about what is heard (-22.7 and -29.9 dB amped). No MUTE up to 1.00 ends the amped stab within the 3 % bar. The generator prints the residual (MUTE 1.00 ends 29.9 dB under its peak before the release; the release starts at -18.1 dB) and the label of the matched stab and bar states it; `blind_1` and `blind_2` carry it in their truth. The engine's 150 ms string fade (ruling 18) does not change this: the matched stab's cut is a hard trim of the dry render, about 190 frames into the fade.
12. **The amped kit tails ended abruptly, and the engine now fades the string before the amp (ruling 18).** As first built, measured on the audition's KIT clips (the pads as they land, levelled; the peak over the last 20 ms against the file peak, which includes the engine's own 4 ms fade): the riff pads A02 to A08 ended at -33.5 to -37.8 dB, and the lead pads A15 and A16 at -25.4 and -24.7 dB; A01, A09 and A10 run the full 4 s budget and the engine's 400 ms ceiling fade ended them clean (A01 -68.8 dB over its last 50 ms, A09 and A10 silent); JANGLE's A11 to A14 ended at -62.5 to -64.9 dB because JANGLE's landing gain is about 0.26 and the amp lifts nothing. The first build left the fix to the landing; the review moved it into the engine, ahead of the amp, and the numbers above are the first build's. After the fade the kit WAVs end at -67.5 to -74.6 dB (A02 to A08), -62.7 and -59.4 (A15, A16) and -97.8 to -101.6 (A11 to A14), and the audition's KIT clips at -68.6 to -69.6 (A02 to A08) and -61.9 and -61.1 (A15, A16).
13. **Several audition clips are byte-identical to others** (md5 over the 95 files, as generated at the first build; the landing is now DRIVE 0.85, so the landing clip is LAND `chug_d85_c95`, and the audition was not regenerated): KIT A06 = LAND `chug_d71_c95` = CHUGAB `stab_magnet` = CHUG `landed_default` = CHUG `landed_blend_1` = PICKDEF `landed_chug_spec` = PLACE `split_b2` (CHUG's default BLEND is 1.0, so BLEND 1 is the default, and the DRIVE 0.71 and CAB 0.95 corner is the landing); CHUG `default` = CHUG `blend_1` = PICKDEF `chug_spec`; JANGLE `default` = PICKDEF `jangle_spec` = BLIND `blind_a8`; JANGLE `landed_default` = PICKDEF `landed_jangle_spec` = KIT A11 = BLIND `blind_a2`; KIT A09 = `blind_a1`; KIT A14 = `blind_a3`; BLIND `blind_a5` = PICKDEF `landed_jangle_bright`; and the bars named in item 7 (`blind_7`, `blind_8` and the two short stabs are identical to nothing). The page marks them, so a listener is not asked to compare a clip with itself.
14. **CHUG at MUTE near 0 landed with a loud end; ruling 22 fixed it.** (The first build's reading, which left it for the owner's ears:) At MUTE 0 CHUG takes the 4 s ring-ceiling path at all 25 TUNE steps, ends through the ceiling's 400 ms fade only, and through CHUG's landing chain ends up to -44.4 dB under its peak (TUNE step 1; the peak of the last 20 ms against the note's peak); the audition clip CHUG `landed_mute_0` reads -48.9 dB. Away from MUTE 0 the worst cell is -62.6 dB at MUTE 0.15 (13 of 25 steps hit the ceiling), -67.5 at 0.35, -66.9 at 0.6 and -63.6 at 1.0; JANGLE's worst is -71.7 at MUTE 0 (all 25 steps hit the ceiling) and under -95 from MUTE 0.15 up. No kit pad is there (A01 reads -87.8 dB), and the clip is in the page's knob-ends section, where the owner hears the open string landed. A fix (a longer or an extra fade on the ceiling path) would change A01, A09, A10 and the audition's ceiling clips, so the first build did not make it before the gate. Ruling 22 made it (A01, A09 and A10 change with it): after it the worst cell of CHUG at MUTE 0 through gain 106 is -56.4 dB (TUNE step 2, all 25 steps read), JANGLE's is -89.2 dB, and A01 reads -112.6 dB on the float render.

**Pass rule.** Items 2 and 3 must pass. Item 2 tests CHUG and item 3 tests JANGLE, so each voice has one must-pass item. Item 2 passes only when the owner can tell the guitar from the saw by the tone: on the cut-matched pair (`blind_1`/`blind_2`) and on the symmetric both-cut pair (`blind_7`/`blind_8`) they pick the guitar correctly and answer "the tone itself" to "what mainly tells them apart". An answer of "how long it rings" or "how it ends" is a confound, not a pass; "nothing" or "cannot tell" is a fail. The decay-matched pair and the as-played pair are controls for interpretation, not part of the pass. Presets are authored by ear after the gate for each voice that passes; a voice that fails gets no roster and no picker entry. Both items passed ("The gate and the second listen"): item 2 at the gate, item 3 at the second listen once JANGLE's ring and amp were chosen.

### Verification

`./gradlew --no-daemon :synth:test :shell:test :cli:test`, after `./gradlew --no-daemon :shell:cleanTest :cli:cleanTest`: `:shell:test` and `:cli:test` report up to date after a change confined to `:synth`'s sources, because they do not declare those test sources as inputs, so the four source-scanning laws in `:shell` (`Locale.ROOT` on `format`, stranded KDoc, a literal 44 100 in main source, the comment-terminator sequence) run only if forced. Counts at task 8a (rulings 19 to 22), on the tree with the base branch merged in: synth 1 244, shell 918, cli 97, none failing or skipped (`MagnetTest` 32 tests in about 26 s). At the first build's last task: synth 1 239, shell 918, cli 97; before the base branch was merged, synth 1 130 and shell 910.
