# FATHOM — the bass engine

**Status:** design, approved. Not implemented.
**Roadmap:** sits after VELVET (S3.6) and the VOX/GRAINS pair (S3.7). The
`SYNTH_ROADMAP.md` phasing row gets added when implementation starts, not now —
an approved design is not a shipped engine.

## Why a new engine and not a VELVET preset

Most of what "bass engine" usually means already exists:

| Sound | Where it already lives |
|---|---|
| Acid | `VelvetVoice.SQUELCH` — root 82.4 Hz, `SQUEEZE 0.85`, which VELVET's own KDoc calls "one knob, always acid" |
| Sub | `VelvetVoice.BASS` — saw/pulse stack **plus a sub oscillator**, resonant SVF |
| Boom | `ThumpVoice.KICK` with a long `DECAY` — on the classic machines the kick and the bass are the same circuit |

So FATHOM is only worth building for what VELVET structurally cannot do. Three
things qualify, and they are the engine's whole reason to exist:

1. **Glide** — a pitch envelope travelling *between* notes. VELVET has no
   pitch envelope at all.
2. **Beating** — two detuned oscillators left to interfere over a long tail.
   VELVET's `FAT` detunes a unison pair, but inside a stab too short for the
   beat to be heard as movement.
3. **FM at the bottom** — TINES is 2-op FM but deliberately short and bright,
   "the bite baked in". Low-tuned FM is a different instrument.

Plus a fourth that is a signal-path decision rather than a sound: **drive
belongs in the engine, before the filter.** Saturation generates harmonics and
the filter has to be downstream to shape them. Routing bass through the
`CRUNCH`/`SQUASH` FX rack puts them in the wrong order, which is why that
combination sounds like a blanket.

## Naming

`SYNTH_ROADMAP.md` sets the guardrail: *sound yes, names never.* No model
numbers, no near-misses, no naming a voice after a person. The engine is
**FATHOM**; the voices are `DEEP`, `GRIND`, `GLASS`. Preset descriptions say
"sliding sub bass", never the machine it evokes.

## Architecture

One new file, `synth/src/main/kotlin/com/snipsnap/synth/Fathom.kt`, shaped
exactly like `Velvet.kt`:

```kotlin
enum class FathomVoice { DEEP, GRIND, GLASS }

object Fathom {
    const val TUNE_SEMITONES = 24
    fun macrosFor(voice: FathomVoice): List<MacroSpec>
    fun scramble(voice: FathomVoice, random: Random): Map<String, Float>
    fun render(voice: FathomVoice, macros: Map<String, Float> = emptyMap()): Snip
}
```

Two touchpoints outside it, both small additions to existing files:

- **`Patches.kt`** — a `FathomPatch` data class (`ENGINE = "FATHOM"`) beside
  `VelvetPatch`, plus one branch in the decoder's `when`. This is what lets a
  FATHOM pad survive `kit.json` round-tripping and regenerate from its recipe.
  The repo already promises a kit folder rebuilds its WAVs bit-for-bit from the
  sidecar; an engine that could not do that would break the promise.
- **`SynthKits.kt`** — nothing now. A curated bass kit is a separate follow-on
  once the voices sound right.

### Signal path

Shared by all three voices:

```
source ──→ DRIVE ──→ TptSvf (low-pass) ──→ amp env ──→ Snip
  │          ↑
  │       pre-filter, so folded harmonics get shaped
  │
  └─ pitch ← GLIDE envelope (all voices)
             SWEEP envelope (DEEP only)
```

`TptSvf`, not the older Chamberlin `Svf`. Bass patches sit low and resonant,
which is exactly where the Chamberlin topology's frequency warping is worst.
`TptSvf` is already in `Dsp.kt`.

## Macros

Six per voice, matching VELVET's count. Five shared, one voice-specific.

| | DEEP | GRIND | GLASS |
|---|---|---|---|
| shared | `TUNE` `GLIDE` `DRIVE` `CUTOFF` `DECAY` | ← | ← |
| own | `SWEEP` | `SPREAD` | `RATIO` |

**`GLIDE`** — the engine's headline gesture, available on every voice, because
on a real machine a slide is a performance move rather than a timbre. `0` = no
glide; increasing values start further below the target (up to an octave) and
slide into it. Unipolar and upward-only: bipolar would put "sounds like a
mistake" inside the knob's travel, which the roadmap's playability rules
forbid. Glide time scales with `DECAY` so the slide always lands before the
sound ends — a slide still travelling at the end is the one way this can sound
broken, and it is cheap to make impossible.

**`SWEEP`** (DEEP only) — a separate, much faster downward pitch blip in the
first few milliseconds. That is the thump. `SWEEP` is the attack, `GLIDE` is
the journey. The word is borrowed from `ThumpVoice.KICK`, where it means the
same thing.

**`DRIVE`** — "more harmonics" on every voice, achieved differently by each:
saturation on DEEP and GRIND; on GLASS the FM modulation index *and* output
saturation moving together. That pairing is the same device as VELVET's
`SQUEEZE`, which ties resonance and envelope amount to one knob precisely so it
cannot be set into an ugly corner.

### The voices

**DEEP** — sine, `SWEEP` blip at the attack, `DRIVE` folding it toward a shaped
square. Long `DECAY` with `DRIVE` up is the boomy sub; short and hard, it is a
kick.

**GRIND** — two saws detuned by `SPREAD`, long tail. The hollowness *is* the
beating between them; no comb or notch stage. `SPREAD` reads as a beat-rate
knob — slow throb at the bottom, growl at the top.

**GLASS** — 2-op FM, `RATIO` snapped to a small set of intervals chosen for low
end, the way TINES snaps its seven characters so the knob cannot land on a
mistuning. The modulation index rides the amp envelope, so the metallic edge
decays faster than the fundamental. That is what real FM basses do, and it is
what keeps the voice from sounding like a static preset buzz.

Three voices rather than four: `DEEP` with `DRIVE` up already covers the
territory a dedicated fourth would have, and the roadmap says YAGNI louder than
it says symmetry.

## Data flow

Unchanged from every other engine:

```
macros ─→ Fathom.render() ─→ Snip ─→ FathomPatch ─→ kit.json recipe
                                                       ↓
                                   KitAssembler → Preflight → export
```

## Failure handling

Mostly inherited. `Patches.validateMacros` already rejects unknown macro names
and out-of-range values against `macrosFor`, so FATHOM gets that by declaring
its specs honestly.

Two are FATHOM's own:

- **No NaN and no DC offset**, under any macro combination. `DRIVE` folding a
  sine is where either would come from.
- **`GLIDE` clamped inside `DECAY`.** A clamp, not an error — the macro range
  should not contain a broken setting in the first place.

## Testing

Following `VelvetTest` beat for beat, plus three specific to this engine.

| Test | What it protects |
|---|---|
| every voice renders clean audio at defaults and both corners | no NaN, no silence |
| scrambles are reproducible and never garbage | SCRAMBLE stays a safe roll |
| is deterministic | same macros → same bytes |
| defaults are **harmonic, not noise** (`flatness < 0.2`) | measures what matters. Deliberately *not* asserting a `DrumClass` up front: `VelvetTest` found the classifier files harmonic stabs under `PERC` — "the classifier's honest shelf for a harmonic hit" — so the label is observed after the voices exist and pinned then, never predicted and tuned toward |
| `CUTOFF` opens / `DECAY` lengthens / `TUNE` snaps and tunes | the shared macros |
| **`GLIDE` actually glides** | detected pitch in the first analysis window is below the last; with `GLIDE = 0` they match. `TestPitch` is the existing helper. |
| **`SPREAD` beats** | amplitude-envelope modulation rate rises with the knob — the beating is the sound, so assert it directly |
| **`RATIO` snaps** | sweeping the knob end to end yields exactly as many distinct renders as there are entries in the ratio set, and no more |

The `GLIDE` test is the one that matters: pitch-at-start versus pitch-at-end is
a direct measurement of the headline feature rather than a proxy for it.

## Out of scope

- **Keygroup rendering.** S5 converts every engine's key patches to keygroup
  instruments at once; FATHOM should not fork that path for itself.
- **New FX.** The drive stage is in-engine and is not a rack effect.
- **A `SynthKits` bass kit.** Cheap follow-on, separate change.
- **Any voice beyond the three.**

## Open questions

None blocking. Two judgement calls to make while implementing, both cheap to
revise because they are internal:

- The exact `RATIO` set for GLASS — pick by ear from a handful of low-end
  intervals, the way TINES' seven were chosen.
- Whether GRIND wants a third oscillator at unison under the detuned pair.
  Start with two; add the third only if the low end feels thin.
