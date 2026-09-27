# PLUCK Phase 3a — SITAR: the jawari, the sympathetic strings, the gourd, and stiffness in the loop

**Status:** implementing on claude/pluck-depth-phase-3 (plan 2026-09-26-pluck-sitar.md).
**Date:** 2026-09-26
**Parent:** [`2026-09-25-pluck-depth-design.md`](2026-09-25-pluck-depth-design.md) — this is
the "Phase 3 (outline)" row of that spec, made concrete after Phase 2's
four listening gates. Phase 2's other leftovers (a fixed per-voice body
calibration, a pitch feature in the audio module's classifier, the KALIMBA
preset pass, koto's remaining body modes) are out of scope here and get
their own specs.
**Plan:** docs/superpowers/plans/2026-09-26-pluck-sitar.md

## Why a sitar, and why now

Josh asked for it in Phase 2 ("Could we replace with another string for
pluck? Sitar?") and chose "banjo first, sitar later". Banjo is in. A sitar
is the one plucked string whose identity lives in things the engine does
not have yet: a bridge that buzzes, a bed of strings that ring in sympathy,
and long steel strings whose partials sit sharp of harmonic. Each of those
is a small addition to the Karplus-Strong path, and the two that are
generic (stiffness, sympathetic loops) are reusable by KOTO and HARP once
they exist.

Three decisions from the brainstorm bind this design:

1. **SITAR is a voice with presets, like BANJO.** It has no kit pads in this
   phase. Any single note stands on its own, so the sympathetic strings tune
   to the played note's own series, not to a scale.
2. **The jawari is a property of the voice, driven by velocity.** Every
   sitar note buzzes; harder plucks buzz more; the buzz fades with the note.
   The six PLUCK macros keep their meanings on every voice. No seventh macro.
3. **All four approaches as recommended:** the jawari inside the loop (with
   an output-side buzz as the fallback if it pulls the pitch too far), the
   sympathetic strings as short Karplus-Strong loops under DOUBLE, a sourced
   gourd body, and dispersion as a per-voice allpass that KOTO and HARP carry
   at zero until a gate hears it on.

## Architecture

SITAR is the fifth member of `PluckVoice`. Every `when` over the voices
gains a branch — `Pluck.kt` (constants, defaults, root, body table),
`PluckPresets.kt` (twelve presets), `Patches.kt`, `Presets.kt`, `Keys.kt`,
`SynthKits.kt` helpers where they enumerate, and the app's `SynthScreen`
class mapping (TONAL, like the others).

The string path is Phase 2's path with two stages added inside the loop
and one layer added beside it:

```
burst ─(PICK low-pass)─(STRIKE comb)─▶ ┌─ delay ─ two-tap average ─ tuning allpass ─ stiffness allpass ─ loop low-pass ─ jawari ─ DC blocker ─┐
                                        └────────────────────────────────── feedback ◀───────────────────────────────────────────────────────┘
                                                              │ string
                                   sympathetic loops (DOUBLE) ◀┤
                                     first difference ─▶ gourd body (BODY) ─▶ +
```

Nothing before the loop changes. STRIKE's comb, PICK's low-pass and the
seeded burst are as Phase 1 and 2 left them. The render/decimate/level/fade
tail, the decay-following length and the 4 s ceiling are untouched.

### Voice constants (starting values; the gate moves them)

| Constant | SITAR | Why |
|---|---|---|
| root | 139 Hz (C#3) | the common tonic of the playing string; TUNE's default 0.5 lands on C#4 |
| loop low-pass | 7000 Hz | steel strings under a wire plectrum: brighter than KOTO (4200), darker than BANJO (9000) |
| pick band | 2500–12000 Hz | the mizrab is a wire pick |
| ring | 1.4 | a sitar sustains longer than a koto; HARP is 1.3 |
| STRIKE default | 0.30 | plucked nearer the bridge than the guitar's quarter |
| DOUBLE default | 0.40 | the sympathetic strings are present by default |
| DAMP default | 0.50 | a 1.3 s budget: the sympathetic strings ring to the end of it, and a longer default reads as a loop to the classifier (Task 4 ruling) |
| PICK default | 0.65 | bright |
| BODY default | placeholder until the gate, written as such in code |
| stiffness | chosen at the gate between two computed candidates (see Dispersion) |
| jawari drive | chosen at the gate among three (see The jawari) |

### The jawari

The bridge of a sitar is a flat, slightly curved ivory or bone surface.
As the string swings toward it, the string wraps on the curve, which
shortens the vibrating length for that half of the cycle. The effect is
asymmetric (one side only), grows with the swing (amplitude-dependent), and
fades as the note decays — which is why a sitar note "opens" into its buzz
and then closes. In the loop, after the low-pass and before feedback:

```
z = y − k · max(0, y)² / p0
```

where `y` is the low-passed loop signal, `p0` the exciter's peak (so the
term is a fraction of the string's own level, not an absolute), and `k`
the drive. The sign matters: the bridge is a barrier, so it can only
*limit* the string's swing toward it, never add to it — a one-sided
limiter loses a little energy on each positive half-cycle and generates
the even harmonics of the buzz, and because `|z| ≤ |y|` it can never raise
the loop's gain above one. (A term that added to `y` would be a positive
feedback on half of every cycle and would run away at feedback near one.)
Because the term is quadratic in `y`, it is loudest at the onset and
vanishes on its own as the note decays; no envelope is needed. `k` is
the voice's drive constant times a velocity map `lin(velocity, 0.3, 1.0)`:
a soft note buzzes a little, a hard one buzzes fully. The drive constant
starts at 0.3; the audition hears 0.15, 0.3 and 0.6 and the chips choose.

A one-sided term adds offset. A DC blocker follows it inside the loop —
the signal minus its own 20 Hz one-pole low-pass — so the loop cannot
accumulate the offset over hundreds of cycles.

The nonlinearity sits in feedback, so two guards are part of the design,
not afterthoughts: the stability test (below) and the fallback. **Fallback:**
if the tuning sweep at full drive pulls any note past a quarter tone, or
the stability test fails at any DAMP, the jawari moves outside the loop as
an amplitude-gated one-sided waveshaper on the string's output (the same
shape, applied once, no feedback). That version cannot "open" the tone the
same way, and the spec records the choice if it is taken.

**Velocity reaches the render as a number.** Today `Velocity.atVelocity`
moves the brightness macro (PICK, for PLUCK) and re-renders. Patch
validation rejects any macro key `macrosFor` does not list, so velocity
cannot travel in the macro map; instead `Pluck.render` gains a `velocity`
parameter (default 1.0) and `atVelocity` calls it directly for a
`PluckPatch`, passing the scaled PICK map and the velocity. Velocity is not
a `MacroSpec`: it has no knob, no preset carries it, no pad recipe saves it,
and `macrosFor` does not list it. The other four voices ignore it.

### Sympathetic strings under DOUBLE

A sitar carries eleven to thirteen tarab strings under the frets, tuned to
the raga, that ring in sympathy with whatever is played. With no scale to
tune to (decision 1), they tune to the played note's own series. When
DOUBLE is above zero, four short loops ring beside the main string:

| Loop | Ratio to the note | Why |
|---|---|---|
| 1 | 0.5 | the octave below: the drone |
| 2 | 1.5 | the fifth |
| 3 | 2.0 | the octave |
| 4 | 3.0 | the octave and a fifth |

Each is a Karplus-Strong loop with feedback 0.999 and a darker low-pass at
4 kHz, **fed continuously by the main string's output** at a coupling of
0.05, the way the bridge transmits vibration to the tarab, rather than by
its own burst. Their sum enters the output at `0.5 · DOUBLE`, so DOUBLE 1 is
a drone and is the ugly end on purpose. At DOUBLE 0 they are not rendered
and cost nothing. On SITAR, DOUBLE therefore stops meaning the twelve-string
detune it means on the other four voices; the macro's KDoc says so.

Cost: five loops instead of one. The Phase 2 profile put PLUCK at a quarter
of the fleet average, so this is affordable without a budget change.

### Dispersion

A stiff string's partials sit sharp of harmonic by a factor that grows
with the partial number. In the loop, one first-order allpass per voice
carries that stiffness: its coefficient comes from a per-voice constant,
and its phase delay at the fundamental is added to the loop's tuning budget
the same way the low-pass's delay already is, so the fundamental stays in
tune while the upper partials stretch.

| Voice | Stiffness | Why |
|---|---|---|
| SITAR | one of two candidates, chosen at the gate: the allpass coefficient that puts the tenth partial 1.0% sharp (the stiff-string law `n·√(1 + B·n²)` with B ≈ 2e-4) and the one that puts it 3.0% sharp (B ≈ 6e-4), both found by a measuring probe in the plan, not tuned by hand | long steel strings: the inharmonicity is audible |
| KOTO, HARP | 0, with a dispersion-on candidate in the audition | both passed a gate; they do not change unheard |
| NYLON, BANJO | 0 | not offered |

The allpass phase at the fundamental is computed from the coefficient the
same way `filterDelay` is computed from the low-pass pole, and the
`MIN_LOOP_SAMPLES` guard covers the combined delay.

### The gourd body

The sitar's tumba (gourd) and tabli (soundboard) are its body. The Phase 2
rule stands: **every Hz in the body table comes from a source the verifier
opened.** The research ran before this section was final
(`2026-09-26-pluck-sitar-body-research.md`, 2026-09-26) and came back with
**zero body modes**: six sources opened, all about the string, the bridge
or the sympathetic strings; the four candidates that plausibly hold a
measured body mode — above all Limkar & Chandekar's 2022 modal analysis in
the *Journal of Vibration and Control* — sit behind paywalls no
policy-compliant route opened. No opened source gives the gourd's
dimensions either, so a Helmholtz frequency cannot be derived from a cited
geometry.

So SITAR starts **without a body table.** `bodyFor(SITAR)` is empty and
`withBody` returns the string unchanged; the BODY macro is inert on this
voice and its KDoc says why. The jawari, the sympathetic strings and the
stiffness carry the identity at the first gate, and the audition renders
the voice at BODY 0 only. The body arrives in a follow-up inside this
phase the moment a source is read — the paywalled paper, if Josh can open
it through an institution or a purchase, is the single most likely source
of a full modal table — and then the rows enter exactly as Phase 2's did:
driven by the first difference, RMS-matched over the string, zero-padded
tail, BODY's reach 0–3× the string. The classification and BODY tests
enumerate voices with a table, so an empty table is skipped, not failed.

### Presets

Twelve SITAR presets, disposable like every other preset file, authored
for the audition rather than by ear: a plain ALAAP, a bright JHALA, a
drone-heavy one, a dry one with DOUBLE 0, a muted one, a high one, two with
STRIKE at its ends, and four spread over DAMP. `PluckPresetsTest` keeps
them rendering clean; the parent spec's by-ear pass re-authors them later.

## Data flow

- `Pluck.render(voice, macros, velocity = 1f)` gains the velocity
  parameter; `synthesize` takes it too and passes the jawari drive into
  `ks`. `PluckPatch.render()` keeps calling it with the default.
- `ks` gains three optional parameters: a stiffness coefficient (default 0,
  no allpass), a jawari drive (default 0, no nonlinearity, no DC blocker),
  and — for the sympathetic loops — an optional external input array read
  sample by sample into the loop at the coupling gain. With every default,
  `ks` produces the Phase 2 output byte for byte; the test asserts it.
- `Velocity.atVelocity` adds `VELOCITY` to the map for `PluckPatch` beside
  the PICK move it already makes. `Velocity.brightnessOverride` is
  unchanged (PICK for every PLUCK voice).
- The audition generator renders the SITAR set; the listening page adds a
  SITAR card; the artifact's database keys carry a phase prefix (`p3a_*`).

## Error handling

- The `MIN_LOOP_SAMPLES` guard in `ks` includes the stiffness allpass's
  delay in `exact`, so a note too high for the combined delay fails loudly
  with the existing message.
- The jawari's DC blocker and the stability test guard the loop; the
  fallback is written into the spec (above) so the plan can take it without
  a new design round.
- A sympathetic loop at ratio 3 on the highest TUNE (two octaves above
  C#3 → C#5 → 1662 Hz at ratio 3) is still above `MIN_LOOP_SAMPLES` at the
  4× render rate; the test sweeps every note with DOUBLE 1 to prove it.

## Testing

Every test is a measurement on the render, in the pattern of `PluckTest`
and `TuningAccuracyTest`:

- **Tuning.** All 25 notes at the defaults (jawari and sympathetic strings
  on) within five cents. All 25 at full jawari drive within a quarter tone,
  every note over five cents printed for the gate. All 25 at DOUBLE 1
  within five cents (the sympathetic loops must not pull the note).
- **Stability.** At every DAMP tenth and full drive, the note decays under
  its DAMP budget (the −60 dB cut lands before the ceiling for DAMP > 0)
  and never clips.
- **The buzz follows velocity.** The high band's share of energy over the
  first 200 ms rises monotonically with VELOCITY at 0.3, 0.65 and 1.0.
- **No offset.** The output's mean over the note is under 1e-4 of its peak.
- **DOUBLE 0 is the string, byte for byte.** DOUBLE 1 raises the energy at
  twice the note by at least 6 dB over DOUBLE 0.
- **Dispersion is real.** With the stiffness candidate on, the tenth
  partial sits above ten times the fundamental by the amount the
  coefficient predicts, within a tolerance; with stiffness 0 it sits on it.
- **The defaults are the Phase 2 path.** `ks` with every new parameter at
  its default matches the Phase 2 render byte for byte on every voice.
- **Classification.** The classification test states what `Classifier`
  may call SITAR once measured, the way it does for BANJO, and asserts it.
- **Everything else as today:** export regression renders a SITAR preset,
  presets render clean, the melodic kit is unchanged, `SynthKitTest` and
  `VelocityGrooveShuffleTest` pass.

## Audition

The gate is Josh's chips on the listening page, before merge. Candidates
per axis, each at the default note and the root:

- the jawari drive at three levels (soft, the constant, hard);
- the sympathetic level at two (the default and DOUBLE 1);
- dispersion on and off, and on KOTO and HARP the same pair;
- BODY at 0 only, until a source gives the voice a table.

The constants the chips choose ship. A second round, if needed, follows
the Phase 2 pattern: last time's clip beside the new one.

## Sequence

1. Research note: done 2026-09-26, zero body modes reachable; the body
   waits on a source (see The gourd body).
2. Plan: written from this spec and the note, with no body table.
3. Implementation in tasks, each gated by review; the audition set last.
4. Josh's gate; a second round if the chips ask for one.
5. Merge.

## Out of scope

Kit pads for SITAR; the classifier's pitch feature; the KALIMBA preset
pass; koto's remaining body modes; a fixed per-voice body calibration;
meend (the pitch bend that pulls the string sideways along a fret), which
needs a control the pad has no gesture for.
