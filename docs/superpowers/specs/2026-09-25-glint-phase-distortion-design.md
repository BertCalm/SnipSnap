# GLINT — phase distortion, and the window as a slot

**Status:** design, approved in conversation 2026-09-25. Not implemented.
**Date:** 2026-09-25
**Plan:** to be written (`docs/superpowers/plans/2026-09-25-glint-phase-1.md`)
**Roadmap:** the `SYNTH_ROADMAP.md` phasing row is added when implementation
starts, not now — the rule FATHOM, RESIN and PLUCK followed.

## Why GLINT

The fleet has eleven engines and no phase distortion. That is a real gap and
not a fashionable one: PD is the 1984 digital technique, era-correct for a
cassette-deck instrument, and it does something none of the others do.

Offered three readings of what PD is *for* — a filterless sweep, the brittle
1984 digital character, or the resonant waveforms — Josh chose the resonant
waveforms. That choice is the spec. GLINT is not "a filter you can't blow
up" and it is not a character pack; it is **one sharp formant that is the
oscillator**, swept under envelope, with the pitch nailed down while it
moves.

### Against the fleet

| Engine | Why GLINT is not it |
|---|---|
| **TINES** (2-op FM) | PD is FM's near-cousin and this is the live confusion risk. The difference is measurable, not rhetorical: FM's index changes the *perceived pitch* as sidebands crowd the fundamental; GLINT's peak sweeps with `TestPitch.estimate` returning the same `f0` throughout. That invariance is a required test (below), not a claim. |
| **RESIN** (ladder), **VELVET** (SVF) | Both sweep a resonance by filtering a source. GLINT has no filter in the signal path at all — the peak is generated, not carved. No feedback path means no self-oscillation, no instability, and no resonance-vs-level tuning. |
| **VOX** (formant) | VOX is *three* formants shaping a source: vowel identity, morphing between held targets. GLINT is *one* formant that **is** the source. Different gesture, different job. This distinction is the one a reviewer will ask about; it is load-bearing. |
| **SNAP** (photo wavetable) | SNAP reads a photo into a *waveform* — bipolar around the midpoint, where 128 is silence. TRACE reads material into a *window* — unipolar, descending to zero. They share the 256-value **format** deliberately and share none of its **semantics**; the conditioning step below is what bridges them. |

### The name

`Phase.kt` is the four-stage allpass phaser in the FX rack, so PHASE is
taken. GLINT is a highlight that slides across a surface as it turns, which
is what a formant peak sweeping over a fixed pitch sounds like. `CHROME` and
`QUARTZ` were the runners-up and lead with the character rather than the
motion; the motion is what Josh picked, so GLINT wins.

## The synthesis

Per output sample, with `φ` advancing at `f0` and wrapping to 0 each cycle:

```
out = w(φ) · sin(2π · k · φ)  +  body(t) · (w(φ) − mean(w))
```

`w` is the window — a shape that is 1-ish at the cycle's start and **exactly
zero at its end**. `k` is the formant ratio. Four properties make this work,
and each has a consequence the implementation must respect.

**1. `w(1) = 0` is what frees `k`.** Every cycle ends on silence, so `k` need
not be an integer and can slide continuously without a click. The output is
continuous across the wrap for any `k` whatsoever — the window goes to zero
on one side and `sin(0) = 0` on the other. Only the *slope* jumps, and that
slope discontinuity is the buzz the engine is made of. Do not smooth it.

**2. The inner sine resets to zero at every wrap.** `k` moves on BLOOM's
envelope timescale, which is orders of magnitude slower than one cycle, so
within a cycle `k` is effectively constant and `innerPhase ≈ k·φ`. Resetting
at the wrap is what keeps the waveform strictly periodic as `k` drifts; a
free-running inner accumulator would drift out of phase with the window and
produce sidebands instead of a formant.

**3. `k` is clamped to `[2, 40]`.** The floor is the one that matters: below
two burst cycles inside the window there is no recognizable peak, only a dull
windowed fragment. The ceiling is generous rather than necessary — see
failure handling for why aliasing is not the binding constraint.

**4. The window is mean-removed before BODY mixes it.** `w` is unipolar; the
raw window carries DC straight into `Dsp.normalize` and out to the WAV. The
mean is subtracted at render time, not stored pre-removed.

### Sample rate

`synthesize(voice, macros, rate)` runs at `Dsp.RATE * Dsp.OVERSAMPLE`
followed by `Dsp.decimate(raw, Dsp.RATE)` — the U6 contract every engine
follows. No rate is hardcoded anywhere; `rate` threads through as it does in
`Resin.synthesize`.

## The voices

A voice **is** a window. That is the whole of the enum's meaning, and it is
what makes the fourth voice coherent rather than bolted on.

```kotlin
enum class GlintVoice { REED, BOTTLE, KAZOO, TRACE }
```

| Voice | Window | Body waveform it implies | Character |
|---|---|---|---|
| **REED** | linear decay, `1 − φ` | sawtooth | buzzy, bright; resonant leads and basses |
| **BOTTLE** | triangle, `tri(φ)` | triangle | hollow, woody, vocal-ish |
| **KAZOO** | trapezoid: flat, then a fast ramp out | pulse | nasal, aggressive, zappy |
| **TRACE** | supplied — 256 values from your own material | whatever you gave it | the slot; see below |

The family reads as one thing on purpose: three objects that buzz with a
resonance, and a fourth that takes its buzz from something you captured.

That the bare window doubles as the body waveform is not a coincidence to be
grateful for — it is why BODY costs one macro instead of a second
oscillator. A linear-decay window *is* a sawtooth. Mixing it back in gives
"a saw with a resonant peak riding on it" with no second phase accumulator,
no detune decision, and perfect phase coherence by construction.

## Macros

Six, which is the top of the roadmap's 3–6 budget and precedented —
`ResinVoice.BASS` also carries six.

| Macro | Range / mapping | Notes |
|---|---|---|
| **TUNE** | `TUNE_SEMITONES = 24`, snapped to semitones | same contract as RESIN and PLUCK |
| **PEAK** | sets `k` — see harmonic snap below | the star knob; also the brightness macro |
| **FOLLOW** | `Dsp.keyTrack(...)` amount, 0..1 | 0 = peak parked in absolute Hz; 1 = peak rides the note |
| **BODY** | mix of the mean-removed window, with its own envelope | 0 = pure formant, 1 = the body dominates |
| **BLOOM** | amount *and* speed of `k`'s envelope on one knob | the DCW envelope; RESIN's CONTOUR is the precedent for one-knob-two-jobs |
| **DECAY** | `Dsp.expMap` → amp `t60`, ~0.12–1.4 s | buffer length `t60 * 1.35 * rate`, floor 64, as every engine does |

`PEAK` is registered in `Velocity.kt`'s `BRIGHTNESS_MACROS` so `atVelocity`
produces physically-honest soft and hard renders — hit a reed harder and the
formant rises. Without that registration GLINT falls back to
`Velocity.soften`, which is the bug PLUCK's PICK had.

### FOLLOW, and the clamp that bites later

`FOLLOW` is a thin wrapper over `Dsp.keyTrack(cutoffHz, baseHz,
referenceHz, amount)`, which already exists and is already tested. No new
math.

At `FOLLOW = 0` the peak sits at an absolute frequency, so `k = f_peak / f0`
falls as the note rises. Across a pad voice's two-octave `TUNE` range this
is comfortable — a 700 Hz peak gives `k` = 12.7 at A1 and 3.2 at A3, both
well clear of the floor. **The `k ≥ 2` clamp starts to bite only across a
four-octave range**, where a 700 Hz peak reaches `k = 0.8` by A5 and FOLLOW's
bottom half silently collapses toward ratio behaviour. That is a keygroup
problem, not a pad problem, and it is written down here so the follow-on
phase inherits it rather than rediscovering it in an audition.

### Harmonic snap on PEAK

`k` is a ratio, so integers land the peak exactly on a harmonic: `k = 2` is
the octave, `k = 3` the octave-and-a-fifth, `k = 5` two octaves and a major
third.

> **PEAK snaps to integers across `k = 2…12`, and runs continuous from 12 to 40.**

Snapping matters precisely where the ear reads the peak as harmonically
related to the note and becomes inaudible above that, so the knob is musical
in its lower travel and smooth in its upper travel — with no switch and no
seventh macro. The alternative (a snap toggle) was rejected on macro budget.

**Snapping applies to PEAK's base value only.** BLOOM modulates continuously
on top of the snapped base. The knob is steppy and musical; the sweep is
smooth. A snapped BLOOM would step through the harmonic series as timbre,
which is an interesting sound and not this engine's default.

### The glass tail

`BODY` gets its own envelope at a fixed fraction of the amp `t60` — start at
`0.45×`, per-voice tunable. The note opens as a saw (or triangle, or pulse)
with a peak riding it and **decays into pure whistling resonance** as the
body burns off.

One extra `Dsp.envAt` call where there was one. No macro. This is baked into
the voice's character rather than exposed, because it is what makes GLINT
identifiable in a single note and a knob that could turn it off would only
ever make the engine less itself.

## TRACE — the window as a slot

The only requirement on `w` is that it reaches zero. There is no instability
to provoke (no feedback path), no detuning to get wrong (the window does not
set pitch — `f0` does), and no new aliasing beyond the `k` clamp. **Any
256-point shape is a valid window.** Feed it garbage and the result is a
timbre, not a failure.

So TRACE takes its window from your own material: a captured snip's
amplitude contour, or a photo read through SNAP's existing readers.

### It bakes. It does not reference.

This is settled by precedent, and the repo contains both the precedent and
the cautionary tale.

`SnapPatch` (`Patches.kt:259-350`) stores `table: IntArray` — 256 values,
0..255 — and nothing else. `Snap`'s own KDoc: *"What a pad stores is the 256
numbers themselves… not the photo."* `PhotoKitTest.kt:51` proves the
consequence: build a photo kit, discard the `Photo`, reload the recipe from
JSON, re-render, assert `contentEquals` on the samples. Byte-identical.

`GrainsPatch` **does not exist.** `Grains.render(source: Snip, …)` takes a
live `Snip`, and `SynthKits.cloud()` builds its pads without a recipe, with
a comment admitting why: *"a cloud's recipe needs a source-file reference,
which is the app layer's kit-folder job."* That reference was never built,
so `SynthKits.cloud()` is absent from `PadRecipeTest`'s kit list and GRAINS
pads are neither editable nor regenerable.

The rule that follows: **a patch that points at external material falls out
of the regeneration guarantee and stays out.** TRACE bakes.

### The patch

```kotlin
data class GlintPatch(
    override val name: String,
    val voice: GlintVoice,
    override val macros: Map<String, Float>,
    val window: IntArray? = null,   // 256 values, 0..255; required for TRACE, null otherwise
) : Patch
```

Matching SnapPatch's format exactly buys three things:

- **`Snap.table(photo, voice)` already returns this type and range.** It is
  public and already called cross-engine by `PhotoField.build` and
  `PhotoPath.render`. A photo window needs no glue code.
- **`Snap.isFlat(table)` is the validation.** A flat window is silence or
  DC, and SNAP already rejects it. Reuse rather than rewrite.
- **`PhotoKitTest`'s regeneration test is the template** for GLINT's.

Serialization follows `SnapPatch` exactly: extend `Patches.toJsonValue(this)`
with a `window` array; `fromJsonValue` validates size 256, each value 0..255,
and `!Snap.isFlat(window)` before constructing.

### Conditioning — one step, in the engine, for every source

A stored window is raw 256 values from wherever they came from. **All
conditioning happens at render time, inside `Glint`:**

1. **Min/max normalize** the table to 0..1 across its own range, so a
   low-contrast source uses the full window depth rather than rendering
   quiet and shapeless.
2. **Force the last sample to zero.** The one hard requirement on `w`.
3. **Mean-remove** the result before BODY mixes it (see synthesis property 4).

Putting all three in the engine is what makes "any 256-point table is a legal
window" literally true instead of nearly true, and it means no caller — not
`Snap.table`, not `windowFrom`, not the app — needs conditioning glue.

It matters most for photos. `Snap.table` was built to be read as a waveform
around a 128 midpoint; nothing about it starts high or ends at zero. Step 1
turns the picture's brightness into the cycle's loudness (bright regions
loud, dark regions quiet) and step 2 closes the cycle. A skyline silhouette
becomes a window without the caller knowing a window is what it became.

The rejected alternative was conditioning at ingest, storing an
already-windowed table. It would have made the stored format diverge from
`SnapPatch`'s for no gain, and it would have baked the conditioning rule into
old patches where a later fix could not reach them.

### The one new primitive

There is no "amplitude envelope from a `Snip`" function anywhere in
`:synth`. `Snap.envelopeAt` reads a *drawn* 0..255 shape from `Draw.kt`; it
does not derive one from audio.

```kotlin
fun windowFrom(snip: Snip): IntArray   // rectify, bucket into 256, normalize to 0..255
```

Lives in `Glint.kt` rather than `Dsp.kt` — `Dsp` is for primitives with
several callers, and this has one. `Snip` is an interleaved `FloatArray`
with `channels` and `sampleRate`; bucketing is over frames, not samples.

### TRACE ships no presets

A factory preset is a map of macro values. A TRACE patch is a map of macro
values **plus 256 numbers that came from somebody's material** — and a
factory window is material nobody captured.

The precedent is unambiguous: every engine with a `*Presets.kt` file is a
pure-parameter engine, and **SNAP — the one other material-dependent engine
— has no preset file at all.** GLINT follows. `GlintPresets` covers REED,
BOTTLE and KAZOO at twelve each; `Presets.forVoice(ENGINE, "TRACE")` returns
`emptyList()`, and the preset test asserts that rather than treating it as a
gap.

TRACE is reached by making a pad from your own material, which is the only
way it means anything.

### Frozen at capture

A TRACE window is fixed at the moment the pad is made. Re-trim the source
snip afterwards and the pad does not change. That is the same contract SNAP
has and it is the correct one — the alternative is GRAINS' contract, which
is no contract.

## Data flow and compatibility

Registration points, as known when this table was written — not exhaustive.
Building the engine turned up two more (see the note below the table). The
reliable method for a future phase is to grep for every exhaustive `when`
over `Patch` and every hardcoded engine list, not to trust this table as a
checklist. The shape of each entry here is established by RESIN and TINES.

**New files**

| File | Contents |
|---|---|
| `synth/.../Glint.kt` | `enum class GlintVoice`, `object Glint`, `windowFrom` |
| `synth/.../GlintPatch.kt` | the patch (own file, TINES/THUMP/SKIN precedent) |
| `synth/.../GlintPresets.kt` | 12 presets × 3 voices = 36 — REED, BOTTLE, KAZOO only |
| `synth/src/test/.../GlintTest.kt` | engine tests |
| `synth/src/test/.../GlintPresetsTest.kt` | preset tests |

**Edited**

| File | Change |
|---|---|
| `Patches.kt` | one branch in `fromJsonValue`'s `when(engine)` |
| `Presets.kt` | a branch in `forVoice` and an entry in `all()` |
| `Velocity.kt` | `PEAK` into `BRIGHTNESS_MACROS` |
| `app/.../SynthScreen.kt` | `Engine` enum entry, one line in each of the seven parallel `when(this)` dispatchers (`voices`, `macrosFor`, `defaults`, `scramble`, `render`, `drumClass`, `buildPatch`), plus a `GlintVoice.drumClass` extension — all four voices map to `DrumClass.TONAL` |
| `DeterminismTest`, `PresetsTest`, `PadRecipeTest`, `VelocityGrooveShuffleTest` | these already enumerate engines; add GLINT |
| `SYNTH_ROADMAP.md` | an S9 row, when implementation starts |

**Untouched:** the CLI (`SynthCommand` resolves through `Presets` generically),
`SynthKits.kt`, `Keys.kt`, `Modes.kt`, `PadRecipe.kt`.

**Missed by this table, found while building GLINT, not while planning it:**
`Velocity.kt`'s `macroSpecsFor` carries its own exhaustive `when (patch)`
over the sealed `Patch` interface, mapping each patch type to its macro
list — skip a branch there and `:synth` does not compile. `shell`'s
`UserPresetsTest` also hardcodes an "every voice of every engine" roster;
missing GLINT there is harmless today, but it is a second place the table
above did not name. Neither turned up until implementation forced the
compiler's or the test's hand.

## Failure handling

| Risk | Handling |
|---|---|
| **`k` below the floor** | hard clamp `k ≥ 2`. Consequence documented under FOLLOW above; it is a keygroup concern, not a pad one. |
| **Aliasing at high `k`** | not the binding constraint. The burst is generated at `Dsp.RATE * Dsp.OVERSAMPLE`, so aliasing begins only above `k·f0 ≈ 88 kHz` — `k ≈ 1443` at A1, `≈ 361` at A3, `≈ 76` at C6. `Dsp.decimate` then removes everything above the output Nyquist cleanly. The musical ceiling of `k = 40` sits far below all of these. The clamp is belt-and-braces, not load-bearing. |
| **DC from the window** | mean removed at render time; a `abs(mean(out)) < threshold` assertion guards it. |
| **A flat or degenerate TRACE window** | `Snap.isFlat` rejects at `fromJsonValue`, same as SNAP. |
| **A TRACE window that does not end at zero** | force-tapered at render time. Not an error. |
| **A TRACE patch with `window == null`** | rejected in `init`, alongside the existing `Patches.validateMacros(...)` call: `require(voice != GlintVoice.TRACE || window != null)`. Constructing an invalid patch must throw, not render silence. |
| **Non-TRACE voice carrying a window** | rejected the same way; the field must be null. Prevents a patch whose JSON implies a behaviour the render ignores. |

## Testing

The standard battery, plus two tests that carry the engine's whole claim.

### The two that matter

```kotlin
// 1. The formant sweeps and the PITCH DOES NOT MOVE.
//    This is the empirical line between GLINT and TINES.
for (peak in listOf(0.1f, 0.5f, 0.9f)) {
    val snip = Glint.render(voice, base + ("PEAK" to peak))
    assertEquals(expectedF0, TestPitch.estimate(snip), tolerance)
}

// 2. FOLLOW does what its name says.
assertTrue(centroid(tune = 0.9f, follow = 1f) > centroid(tune = 0.1f, follow = 1f) * 1.8f)
assertTrue(centroid(tune = 0.9f, follow = 0f) within 15.pct of centroid(tune = 0.1f, follow = 0f))
```

### The rest

| Test | Assertion |
|---|---|
| clean render | every voice at defaults and both macro corners: `peak() > 0.5f`, `durationSeconds < 2f` |
| PEAK opens | `centroidHz` at PEAK 0.95 exceeds PEAK 0.05 by ≥ 1.5× |
| no click | sweep PEAK across deliberately non-integer `k`; no sample-to-sample discontinuity above threshold. This is the window's whole promise and must be asserted, not assumed. |
| harmonic snap | PEAK values within a snap zone render identically; across a zone boundary they do not |
| glass tail | differential, not absolute: the centroid's rise from first third to final third must be materially larger at high BODY than at BODY ≈ 0. An absolute rise also passes on the amp envelope alone, which proves nothing. |
| BODY | raises low-frequency energy as it opens, at every voice |
| DC | `abs(mean(samples))` below threshold for all voices and corners |
| TRACE round-trip | `GlintPatch.fromJsonText(toJsonText())` re-renders byte-identically, window included |
| TRACE regeneration | the `PhotoKitTest.kt:51` pattern: discard the source, reload the recipe, `contentEquals` |
| TRACE from a photo | `Snap.table(photo, HORIZON)` is accepted with no caller-side conditioning and renders clean; assert the rendered window starts non-trivial and ends at exactly zero, since a raw brightness trace guarantees neither |
| determinism | shared `DeterminismTest`; GLINT has no RNG outside SCRAMBLE, so this should be free |
| presets | clean render, JSON round-trip, names uppercase ≤ 14 chars, unique per voice, `PresetTestSupport.trademarkBlocklist`; and `forVoice(ENGINE, "TRACE")` returns empty by design |

Assertions are measured and spectral via `FeatureExtractor` and
`TestPitch` — no golden files, matching RESIN, FATHOM and TINES. No
`Classifier` gate: GLINT claims no drum identity.

## Phasing and gates

| Phase | Ships | Gate |
|---|---|---|
| **1** | REED, BOTTLE, KAZOO; all six macros; glass tail; harmonic snap; registration and tests | audition before presets are authored |
| **2** | TRACE: `windowFrom`, the patch field, serialization, the photo path, regeneration test | Phase 1 audition passed |
| **3** | 36 presets (REED, BOTTLE, KAZOO), authored by ear | Phases 1–2 landed |

The audition gate between Phase 1 and preset authoring is deliberate and
follows the standing decision recorded in the PLUCK work: presets authored
blind from the DSP are disposable. Phase 3 is by-ear or it does not happen.

Phase 1's audition should include BLOOM at its extremes. The audition-gate
finding from 2026-09-08 stands: a macro's range must include the ugly end,
and because GLINT has no feedback path there is no safety reason to keep
BLOOM polite.

## Out of scope

- **Keygroups / `Keys.kt` multisampling.** PLUCK and TINES both shipped
  one-shots first and reached `Keys.kt` at S5. Same path. The `k ≥ 2` clamp's
  consequence for FOLLOW is documented above so that work inherits it.
- **A second formant.** Two bursts at different `k` in one window would give
  vowel-ish motion cheaply, but it treads on VOX and costs a macro.
- **A SHAPE macro morphing between windows.** Rejected: the voice enum
  already gives that free, and it would blur SCRAMBLE's three distinct
  characters into one continuous smear.
- **`SynthKits` placement.** GLINT earns a factory-kit slot after the
  audition, not before.

## What comes after

**The peak tuned to your kit.** At `FOLLOW = 0` the formant sits at a
knowable absolute frequency, and `FeatureExtractor` already reports
`centroidHz` for any snip. GLINT could park its peak on the dominant
resonance of a sample already on the kit, so the synth pad rings in sympathy
with the captured material beside it.

This is the shape of the roadmap's MATCH idea — but **MATCH does not exist**.
There is no `*match*` file in `:synth`, and nothing anywhere turns a
capture's features into synth macro values; `FeatureExtractor` and
`Classifier` produce a `DrumClass` for kit-slotting, not parameters. So this
is net-new work, not a hand-off to something already queued. It is a
workflow action rather than engine DSP and belongs in its own spec.

## Decisions taken in conversation — 2026-09-25

| Question | Decision |
|---|---|
| What gap does PD fill? | the resonant waveforms — a sliding formant, not a filterless sweep and not a character pack |
| Does the peak track the note? | one knob between the two: FOLLOW, over `Dsp.keyTrack` |
| What carries the body? | the window itself, mean-removed, under a BODY macro — not a second oscillator |
| How are voices cut? | one per window shape; a voice *is* a window |
| Which enhancements? | the window slot (TRACE), the glass tail, harmonic snap. The kit-tuned peak was held back. |
| Reference or bake the TRACE window? | bake, on the `SnapPatch` precedent and the `GrainsPatch` counter-example |
| Scope | one-shot pads; keygroups a named follow-on |
