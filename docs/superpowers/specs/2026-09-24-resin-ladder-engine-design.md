# RESIN — the ladder engine, and CONTOUR, its filter on a captured pad

**Status:** design, approved. Not implemented.
**Date:** 2026-09-24
**Plan:** [`docs/superpowers/plans/2026-09-24-resin-ladder-engine.md`](../plans/2026-09-24-resin-ladder-engine.md)
**Roadmap:** the phasing row (S8) gets added when implementation starts, not
now — an approved design is not a shipped engine, same rule FATHOM followed.

## Why a new engine and not a VELVET preset

Most of what "that classic mono-synth sound" means already exists in `:synth`:

| Sound | Where it already lives |
|---|---|
| Subtractive stab, filter swept by its own envelope | `Velvet` — saw/pulse unison pair over a sub, `Dsp.TptSvf` swept by `SQUEEZE` |
| Glide between notes | `Fathom` — `GLIDE` on every voice |
| Drive before the filter | `Fathom` — its own `tanh` stage ahead of the SVF, for the reason its KDoc gives |
| Filter cutoff that tracks the note | `Dsp.keyTrack`, wired into VELVET and FATHOM |
| Oversampled render, decimated back to `RATE` | every engine, `Dsp.OVERSAMPLE` (U6) |

So RESIN is only worth building for what none of that can do. Two things
qualify, and the first is the engine's whole reason to exist:

1. **The transistor-ladder low-pass.** Four one-pole stages in series (24 dB
   per octave), the fourth stage fed back to the input and soft-clipped
   inside the loop. That topology is why resonance *thins the bass* (the
   passband drops as the feedback rises — measured below, −13 dB at the
   top), why it self-oscillates into a rounded sine rather than a scream,
   and why pushing it sounds warm instead of loud. `Dsp.TptSvf` is a
   12 dB-per-octave state-variable filter with symmetric resonance. It is a
   fine filter and it is not this one.
2. **A three-oscillator stack at octave footages.** VELVET's `FAT` detunes
   two copies of one waveform over a fixed sub. The classic panel mixes a
   saw at the note, a saw an octave below, and a detuned square — and the
   thing people turn first is the mixer, not the waveform switch. `STACK`
   walks that mixer on one knob.

A third thing is a signal-path fact rather than a feature: **the drive *is*
the filter.** There is no separate saturator. The `tanh` on the ladder's
input and at every stage is the whole nonlinearity, so how hard the
oscillators hit the filter sets the character, and there is nothing to route
wrong.

Everything else — TUNE snapping to semitones, the amp envelope, key tracking,
loudness levelling, presets, SCRAMBLE, the patch JSON, the `Engine` picker —
is the codebase's existing engine pattern. The engine wrapper is mechanical
once the filter is proven, which is why the plan builds and measures the
filter first.

## Naming

`docs/SYNTH_ROADMAP.md` sets the guardrail: *sound yes, names never.* No
trademarked names, model numbers, or obvious near-misses of them, on any
product surface — engine and voice names, presets, descriptions, commit
messages. The circuit this filter models was patented in 1966 and the patent
is long expired; the *sound* is fair game, the *name* of the company that
sold it is not, and neither is anything that winks at it.

The engine is **RESIN**: what amber literally is before it fossilizes —
warm, thick, and golden, the same character this filter has — and a plain
word in the same register as VELVET and FATHOM. Voices are `BASS`, `LEAD`,
`BRASS`. The filter class is `Dsp.Ladder` — the published technical term
(Stilson & Smith 1996, Huovilainen 2004 both call it that) for a
DSP-internal class nobody outside `:synth` can see (`Dsp` is `internal`).

Names considered and rejected, so nobody re-litigates them:

- **AMBER**, the original name for this engine, through this document's own
  first approved draft. A design-review pass on 2026-09-24 found that
  FXpansion shipped a subtractive software synthesizer called exactly
  `Amber` — not a generic sound-category word like the BASS/LEAD/BRASS
  voice names above, but a real product in this engine's own category.
  RESIN keeps the amber association (it is literally amber's precursor)
  without the collision.
- **LADDER** as the *engine* name — `com.snipsnap.shell.Ladder` is already
  the CHOP screen's ZOOM LADDER, a product word with a different meaning.
  A chip on the pad sheet reading LADDER next to RING and PHASE would be a
  second meaning for a word the app already uses.
- **TIMBER** — `ThumpPresets` already ships a snare called `DRY TIMBER`; an
  engine and a preset sharing a word in the same listbox is a trap.
- **CASCADE** — accurate (four stages in cascade), but a near-miss of a real
  semi-modular synth's name, and the rule forbids near-misses.

The captured-pad half is a rack section called **`contour`** (chip CONTOUR,
character `contoured`): the classic panel's own word for the filter envelope,
generic English, and it says what the section does — a filter contour on a
hit that was never a synth. The engine's own `CONTOUR` macro shares the word
on purpose, the way `PUNCH` is both a THUMP/SKIN macro and a rack character.

Preset names describe the sound (`DEEP CREAM`, `WAH STAB`, `LASER ZAP`),
never the machine. `PresetTestSupport.trademarkBlocklist` enforces the
letter of the rule; the reviewer enforces the spirit.

## The filter — `Dsp.Ladder`

### The model

The simplified Huovilainen ladder, the form most open-source ports use: four
one-pole low-passes in series, `tanh` at the input and at every stage
output, feedback from the fourth stage's *previous* output scaled by a
resonance `r` in `0..4.3`. The linear model self-oscillates at `r = 4`; the
`tanh` pushes that a little higher and bounds the amplitude when it does.

```kotlin
/** Four cascaded one-poles, tanh in the loop, r = 0..MAX_RESONANCE. */
class Ladder(private val rate: Int = RATE) {
    private val y = FloatArray(4)
    var out = 0f
        private set

    fun process(input: Float, freqHz: Float, resonance: Float): Float {
        val fc = freqHz.coerceIn(10f, rate * 0.45f)
        val g = (1.0 - exp(-2.0 * PI * fc / rate)).toFloat()
        val r = resonance.coerceIn(0f, MAX_RESONANCE)
        var u = tanh(input - r * y[3])
        for (i in 0 until 4) {
            y[i] += g * (u - tanh(y[i]))
            u = tanh(y[i])
        }
        out = y[3]
        return out
    }

    fun reset() { y.fill(0f); out = 0f }

    companion object { const val MAX_RESONANCE = 4.3f }
}
```

Nine `tanh` calls per sample. At `RATE * OVERSAMPLE` a 1.7 s note is ~300k
samples, a few milliseconds on the JVM — the same order as FATHOM's drive
plus SVF. Do not "optimise" the per-stage `tanh` away: it is what makes the
DC gain of each stage exactly 1 (`tanh(in) == tanh(y)` at rest), which is
what keeps the passband flat at `r = 0`.

### Measured, not guessed

A pure-Python prototype of exactly this loop (appendix) was run at
`44_100 * 4 = 176_400` Hz, the rate every engine synthesises at. These are
the numbers the Kotlin port's tests pin, each with margin:

| What | Setting | Measured | Test pins |
|---|---|---|---|
| Passband | `fc = 1 kHz`, `r = 0`, 125 Hz tone | −0.24 dB | ≥ −1 dB |
| At the cutoff | same, 1 kHz tone | −12.04 dB | (four −3 dB poles at `r = 0`, where there is no feedback to couple them; informational) |
| Slope | 4 kHz → 8 kHz tones | −49.2 → −72.4 dB (23.2 dB/oct) | ≥ 20 dB apart; 8 kHz ≤ −60 dB |
| Resonant peak | `r = 3.5`, swept 600–1400 Hz | +6.85 dB at 975 Hz | peak within 5 % of `fc`, ≥ 12 dB above the `r = 0` gain at `fc` |
| Bass loss with resonance | 100 Hz tone, `r = 0 → 3.5` | −0.17 → −12.98 dB | 10–16 dB lower at `r = 3.5` |
| DC gain | 0.5 DC in | 0.500 at `r = 0`; 0.111 at `r = 3.5` (input × 1/(1+r): 0.5 × 1 = 0.5; 0.5 × 1/4.5 = 0.111) | within 1 % of both |
| Self-oscillation | `r = 4.3`, zero input after a 1e-3 tick, 1 s | 498 Hz at `fc = 500`; 1009 Hz at `fc = 1000`; 4217 Hz at `fc = 4000`; peak 0.114/0.093/0.000 | tail RMS > 0.01, frequency within 3 % at 500 Hz and 1 kHz |
| No self-oscillation below threshold | `r = 3.5`, same tick | decays | tail RMS < 1e-3 |
| Stability | full-scale 55 Hz saw, `fc` swept 20 Hz → 16 kHz over 1 s, `r = 4.3` | finite, peak 0.42 | finite, peak < 1 |

Two things the numbers decided:

- **No tuning compensation.** The unit delay in the feedback path pulls the
  resonant peak slightly flat (975 Hz for 1000) and the self-oscillation
  slightly sharp (+0.9 % at 1 kHz, +5.4 % at 4 kHz). At the oversampled
  rate that error is under a quarter-tone everywhere a CUTOFF knob with no
  Hz readout will put it, and the published polynomial corrections are
  exactly the kind of tuned constant MEASURE-NEVER-GUESS says not to ship
  unmeasured. If a later listening pass wants the peak dead on, that is a
  one-line `fc` pre-scale, and the test table above is where its effect
  gets recorded.
- **No passband compensation.** The filter loses `1/(1+r)` of its passband
  as resonance rises. That loss is the character — it is why a resonant
  sweep on this topology sounds hollow rather than merely peaky — and every
  engine ends in `Dsp.levelTo`, so loudness is restored after the fact
  without touching the balance the filter chose.

One limit, recorded rather than hidden: at `fc = 8 kHz` (normalised 0.045)
the tick did *not* start self-oscillation within a second. The loop's
unit-delay phase shift and the `tanh` together drop the effective loop gain
as the normalised cutoff climbs. This matters for the rack section, which
runs at the snip's own 44.1 kHz — see [CONTOUR](#contour--the-rack-section).

## Architecture

One new engine file, shaped exactly like `Velvet.kt`, one new filter class
in `Dsp.kt` beside `TptSvf`, one new rack section file, and the registry
touchpoints every engine has:

| File | Change |
|---|---|
| `synth/.../Dsp.kt` | **Add** `class Ladder`; update `OVERSAMPLE`'s KDoc engine count |
| `synth/.../Resin.kt` | **Create.** `enum class ResinVoice`, `object Resin` |
| `synth/.../ResinPresets.kt` | **Create.** Twelve presets per voice |
| `synth/.../Patches.kt` | **Add** `ResinPatch`, one decoder branch |
| `synth/.../Presets.kt` | **Add** a `forVoice` branch, `all()` term |
| `synth/.../Velocity.kt` | **Add** `is ResinPatch ->` (sealed `when`), KDoc line |
| `synth/.../Contour.kt` | **Create.** The rack section |
| `synth/.../FxChain.kt` | **Add** field `contour`, `Section("contour", …)` after `eq`, order KDoc |
| `synth/.../Treatments.kt` | **Add** `"contoured"` to `EXTRA` |
| `shell/.../PadSheet.kt` | **Add** `CONTOUR` to `CHARACTER_SEGMENTS` and `CHARACTER_FOR` |
| `app/.../SynthScreen.kt` | **Add** `RESIN` to the private `Engine` enum and every `when` on it |
| tests | `LadderTest`, `ResinTest`, `ResinPresetsTest`; additions to `DeterminismTest`, `PresetsTest`, `FxTest`, `TreatmentsTest`, `UserPresetsTest`, `PadSheetTest` |
| docs | `README.md` `:synth`, `SYNTH_ROADMAP.md` S8 row |

### Signal path

```
osc 1: saw at the note ────────┐
osc 2: saw one octave down ────┼─ mix (STACK) ─→ Dsp.Ladder ─→ amp env ─→ Snip
osc 3: square, detuned ────────┘                    ↑    ↑
                                            fc ← CUTOFF (key-tracked)
                                                   × CONTOUR envelope (octaves)
                                             r ← CREAM
```

Rendered at `RATE * Dsp.OVERSAMPLE` in `synthesize(voice, macros, rate)`,
decimated in `render` — the U6 contract, verified by the same "dispatches
through the oversampled path" test every engine carries. Oscillator phases
come from `Dsp.phases(3, Dsp.seedFor("RESIN", voice.name))`, so the stack
never opens phase-locked and `DeterminismTest` gets a ninth canary.

## Macros

Six, shared by every voice. Every macro is a `Float` in `0..1`, every value
in that range is a sound, and `Patches.validateMacros` refuses the rest.

| Macro | Default (BASS/LEAD/BRASS) | What it does underneath |
|---|---|---|
| `TUNE` | 0.3 / 0.5 / 0.4 | Snaps to semitones over `TUNE_SEMITONES = 24` from the voice's root, exactly as `Velvet.frequencyFor` |
| `STACK` | 0.5 / 0.8 / 0.6 | The mixer. Osc 1 always at 1. Osc 2 (saw, an octave down) rises over the bottom half: `g2 = 0.9 * min(1, stack * 2)`. Osc 3 (square at the note, detuned `lin(stack, 3, 14)` cents sharp) rises over the top half: `g3 = ((stack − 0.4) / 0.6).coerceIn(0, 1)`. The sum is scaled by `1 / (1 + g2 + g3)` so how hard the stack hits the filter does not change with STACK. Thin → deep → fat on one knob |
| `CUTOFF` | 0.35 / 0.55 / 0.4 | `floorHz = keyTrack(expMap(cutoff, lo, hi), base, frequencyFor(voice, 0.5), 0.6)`, per-voice `lo..hi` below. The 0.6 is VELVET's own placeholder constant, same reasoning, same caveat |
| `CREAM` | 0.35 / 0.5 / 0.3 | `r = lin(cream, 0, Dsp.Ladder.MAX_RESONANCE)`. The top of the knob sings: past ≈ 0.93 the filter self-oscillates at the cutoff, bounded by its own `tanh` |
| `CONTOUR` | 0.4 / 0.5 / 0.75 | Filter envelope amount *and* speed together, the same one-knob device as VELVET's SQUEEZE: `octavesUp = lin(contour, 0, 4)`, `contourT60 = t60 * lin(contour, 0.6, 0.2)`, `fc = min(floorHz * 2^(octavesUp * envAt(t, contourT60)), 16_000)`. More contour is a bigger sweep that falls faster — the wah, then the snap. Log-domain (octaves), not VELVET's linear Hz, because a ladder sweep is heard in octaves |
| `DECAY` | 0.5 / 0.45 / 0.5 | `t60 = expMap(decay, 0.15, 1.2)`; `Dsp.Env(attackSeconds = 0.003, decay2T60 = t60)`; buffer `t60 * 1.4 * rate` |

No `GLIDE`. It is FATHOM's headline and a seventh macro would break the
roadmap's 3–6 rule; a sliding RESIN lead is a FATHOM-shaped follow-up, not
this engine's job. No beat floor on osc 3's detune either: `STACK`'s detune
is width, and VELVET's `minBeatDetune` exists for a macro (`FAT`) that
promises movement.

## The voices

| Voice | Root | `CUTOFF` range (Hz) | Character |
|---|---|---|---|
| `BASS` | 55 (A1) | 60 – 5 000 | The sub-octave saw carries it; `CREAM` up is the hollow, thinned bass this topology is known for |
| `LEAD` | 220 (A3) | 200 – 14 000 | The fat detuned stack with the filter open; `CREAM` at the top is a whistle that follows `CONTOUR` — the laser |
| `BRASS` | 110 (A2) | 120 – 9 000 | `CONTOUR` is the instrument: a big fast sweep on a saw stack is a horn section, a slow one is a swell |

Three voices, not four. The roadmap says YAGNI louder than it says symmetry,
and a self-oscillating "WHISTLE" voice is `LEAD` with `STACK 0`, `CREAM 1`
— a preset, not a voice.

Every voice is statically `DrumClass.TONAL` in `SynthScreen`'s mapping, like
VELVET, PLUCK, TONEWHEEL and VOX: pitched notes, never judged from a render.
`ResinPresetsTest` therefore carries no classifier identity check, exactly
as `VelvetPresetsTest` explains.

## CONTOUR — the rack section

The engine's filter on a pad that was never a synth: a ripped snare or a
vocal chop swept through the ladder from its onset. This is the app's stated
moat — the synth's job is to sit with what was captured — and it needs no
new DSP once `Dsp.Ladder` exists.

**Not `PadFilter`.** `PadFilter` previews the pad *shape* the MPC itself
renders from metadata; a ladder there would make the phone sound different
from the hardware. The FX rack bakes into the WAV, which is where a sound
the hardware cannot make has to live.

| | |
|---|---|
| Section id | `contour` (field `contour` on `FxChain`, appended at the end of the constructor like every later arrival) |
| Rack position | immediately after `eq`, before `squash`: tone before dynamics, so SQUASH tames the resonant peak rather than the peak riding over the squash. The order KDoc gains `→ EQ → CONTOUR → SQUASH →` |
| Macros | `CUTOFF` (default 0.35, **neutral 1** — fully open is where it does nothing), `CREAM` (0.5, neutral 0), `SWEEP` (0.6, neutral 0) |
| `CUTOFF` | `expMap(cutoff, 80, 16_000)`, capped at `0.4 * rate` |
| `CREAM` | `lin(cream, 0, 4.0)` — **4.0, not 4.3**: the section runs at the snip's own rate, where the loop does not reliably self-oscillate above ~1 kHz (the 8 kHz limit measured at 176.4 kHz scales down four-fold). The section is a resonant sweep, not an oscillator, and promises nothing it cannot keep |
| `SWEEP` | `octaves = lin(sweep, 0, 4)`, `sweepT60 = lin(sweep, 0.25, 0.06)` s from frame 0 — a captured pad is already cut to its attack by `Cleanup`, so the onset is the start |
| Per channel | a fresh `Dsp.Ladder(snip.sampleRate)`; identical channels stay identical (the `FxTest` stereo clause) |
| Level | peak matched both ways, `Ring` style, so the contract's `|out.peak − in.peak| < 0.05` holds at any roll |
| Seed | none; deterministic |
| Character | `"contoured" to FxChain(contour = mapOf("CUTOFF" to 0.3f, "CREAM" to 0.6f, "SWEEP" to 0.7f))`, appended to `Treatments.EXTRA` — never to `Shuffle.TREATMENTS`, which a seeded bank indexes |
| Chip | `CONTOUR`, sixth on `PadSheet.CHARACTER_SEGMENTS` (the row ceiling is six, row one already uses it); `CHARACTER_FOR["CONTOUR"] = "contoured"` |

Native-rate honesty: the `tanh` stages generate harmonics above Nyquist at
44.1 kHz, and this section does not oversample — `Ring`, `Wobble` and
`PadFilter` do not either, and lo-fi is on-brand for a rack that ships
CRUNCH. Recorded here so nobody mistakes it for an oversight.

## Data flow

Unchanged from every other engine:

```
macros ─→ Resin.render() ─→ Snip ─→ ResinPatch ─→ kit.json recipe
                                                     ↓
                                 KitAssembler → Preflight → export
```

A kit folder still rebuilds its WAVs bit for bit from the sidecar, because
`ResinPatch` goes through `Patches.fromJsonValue` like the other nine.

## Failure handling

Mostly inherited: `Patches.validateMacros` refuses unknown macros and
out-of-range values against `macrosFor`; `FxChain.init` does the same for
the section.

RESIN's own:

- **Finite, in range, no DC** under any macro combination. The `tanh` loop
  is bounded by construction (measured: peak 0.42 with a full-scale saw
  at `r = 4.3`), and the stack has zero mean. The corner test proves it.
- **The contour lands inside the note.** `contourT60 ≤ 0.6 * t60`, so the
  sweep is always over before the amp envelope is; a sweep still rising when
  the sound ends is the one way this could sound broken, and it is cheap to
  make impossible.
- **`fc` capped at 16 kHz** in the engine and `0.4 * rate` in the section:
  not a stability need (`g < 1` for every finite `fc`), a taste ceiling, and
  it keeps the tuning error inside the measured range.

## Testing

Following `VelvetTest` and `FathomTest` beat for beat, plus the filter's own
measured suite and the section's rack contract.

### `LadderTest` — the filter, at `Dsp.RATE * Dsp.OVERSAMPLE`

Every row of the measured table above becomes one assertion with the margin
that table gives. These are the tests that matter: they pin the topology's
signature (24 dB/oct, `1/(1+r)` bass loss, bounded self-oscillation) rather
than a proxy for it, so a future "improvement" that quietly turns the ladder
into a generic resonant low-pass fails loudly.

### `ResinTest` — the engine

| Test | What it protects |
|---|---|
| every voice renders clean audio at defaults and both corners | finite, in `−1..1`, peak > 0.5, under 2 s |
| render dispatches through the oversampled path | U6, the same mean-abs-diff proof VELVET carries |
| scrambles are reproducible, in range, honour temperature and `near` | SCRAMBLE stays a safe roll |
| every voice declares exactly the six macros | the contract |
| `TUNE` snaps and tunes | 25 distinct frequencies; `TestPitch` reads one octave from 0.5 → 1.0 on LEAD |
| `CUTOFF` opens | centroid ratio > 1.5, `CREAM 0`, `CONTOUR 0` |
| `CREAM` thins the bass | **the ladder's signature, measured in the render:** BASS at `CUTOFF 0.5`, `lowRatio` 0.773 at `CREAM 0` → 0.364 at `CREAM 0.85` (measured); pinned < 0.6× |
| `CONTOUR` sweeps, then lands | on LEAD, `STACK 0`, `CREAM 0.3`, `CUTOFF 0.5`: first-10 ms centroid 951 Hz against a 596 Hz tail at `CONTOUR 1` (1.60×, pinned > 1.3×); 630 Hz at `CONTOUR 0` (1.06×, pinned < 1.2×); the tail reads 595.8 Hz at every `CONTOUR` (pinned within 5 %) — the sweep has landed. Ten milliseconds, because `FeatureExtractor` measures the first 4096 samples of what it is handed and the sweep's T60 at `CONTOUR 1` is 85 ms |
| `STACK` thickens | the sub-octave saw takes the detected pitch down an octave: LEAD 441 → 220.5 Hz, BASS 110 → 55 Hz from `STACK 0` to `STACK 0.5` (measured; pinned 1.8–2.2×). The power-weighted centroid barely moves (652 → 604 Hz), so it is not the instrument |
| `DECAY` lengthens | `decayMs` ratio > 1.5 |
| defaults are harmonic, not noise | `flatness < 0.2`, no `DrumClass` predicted |
| deterministic; JSON round-trip; unknown macro refused | the patch contract |

Thresholds are pinned from the prototype's numbers with margin. Where the
Kotlin render disagrees with a threshold, the rule is the repo's: print both
numbers, record the measurement in the test's comment, and set the threshold
from the measurement with ~20 % margin — never loosen a threshold to pass
without writing down what was measured.

### `ResinPresetsTest`

A copy of `VelvetPresetsTest`: clean and non-silent, JSON round-trip byte
for byte, twelve names per voice ≤ 14 chars uppercase unique, blocklist
clean.

### Rack and registry

- `FxTest`: the shared contract (`every effect is deterministic, clean and
  peak-matched everywhere`, the stereo clause) covers `contour` by
  construction because it iterates `FxChain.SECTIONS`. Add: `CUTOFF` low
  darkens (snare head centroid 9971 → 180 Hz measured; pinned ≤ 0.6×),
  `SWEEP` brightens the first 10 ms (558 → 1197 Hz at `CUTOFF 0.6`,
  `CREAM 0.4`; pinned > 1.5×), a gently contoured kick is still a KICK
  (measured: centroid 45.7 Hz, `lowRatio` 0.978), and `contoured` exists,
  sets `contour`, and is a bypass at AMT 0.
- `TreatmentsTest`: the ordered names list gains `"contoured"` last.
- `PadSheetTest`: the row, the inventory set, and the count (28 → 29).
- `DeterminismTest`: `RESIN is byte-identical across renders`.
- `PresetsTest`, `UserPresetsTest`: RESIN joins the rosters.

## Out of scope

- **Keygroup rendering.** The S5 instrument suite converts engines to
  keygroups by its own table; a sustained RESIN key patch is a follow-up.
- **`GLIDE`.** FATHOM's, for the reasons above.
- **A fourth voice**, a `SynthKits` kit, a CLI verb: none needed; `treat`
  is generic over `Treatments.names`, so `contoured` is CLI-reachable the
  moment it exists.
- **Oversampling the rack section.** Recorded above; revisit only if a
  listening pass hears the aliasing.
- **Tuning compensation in `Dsp.Ladder`.** Measured small; a one-line
  change if the audition gate asks for it.

## Open questions

None blocking. Three placeholders, all flagged in code with the same
"PLACEHOLDER awaiting the audition gate" KDoc VELVET's key-tracking constant
carries:

- `CUTOFF_KEY_TRACK_AMOUNT = 0.6` — inherited from VELVET; same measurement
  caveat.
- `STACK`'s gain curves and osc 3's 3–14 cent detune — authored from the
  DSP, never listened to.
- `CONTOUR`'s `0.6 → 0.2` speed range — the "wah then snap" shape is a taste
  call.

## Appendix — the prototype

Pure Python, no dependencies, about five seconds. Re-run it before changing
any threshold in `LadderTest`; if the Kotlin numbers and these disagree by
more than the table's margins, the port is wrong, not the table. This is the
exact script that produced the numbers in this document.

```python
"""Prototype of the four-pole transistor-ladder low-pass, measured, not guessed.

Pure Python (no numpy in this container). Runs at the synth module's
oversampled rate (44100 * 4) so the numbers here are the numbers a Kotlin
port at Dsp.RATE * Dsp.OVERSAMPLE should reproduce.
"""
import math

FS = 44100 * 4


class Ladder:
    """Huovilainen-style ladder: four one-poles with tanh at every stage,
    feedback from the fourth stage's previous output, scaled by r (0..4).
    r = 4 is the linear model's self-oscillation threshold."""

    def __init__(self, fs=FS):
        self.fs = fs
        self.y = [0.0, 0.0, 0.0, 0.0]

    def process(self, x, fc, r):
        g = 1.0 - math.exp(-2.0 * math.pi * fc / self.fs)
        y = self.y
        u = math.tanh(x - r * y[3])
        for i in range(4):
            y[i] += g * (u - math.tanh(y[i]))
            u = math.tanh(y[i])
        return y[3]


def rms(xs):
    return math.sqrt(sum(v * v for v in xs) / len(xs)) if xs else 0.0


def db(v):
    return 20 * math.log10(max(v, 1e-12))


def steady_gain(fc, r, f, amp=0.02, settle=0.15, measure=0.15):
    lad = Ladder()
    n_settle = int(settle * FS)
    n_meas = int(measure * FS)
    out = []
    for n in range(n_settle + n_meas):
        x = amp * math.sin(2 * math.pi * f * n / FS)
        y = lad.process(x, fc, r)
        if n >= n_settle:
            out.append(y)
    return rms(out) / (amp / math.sqrt(2))


def zero_cross_freq(xs, fs):
    crossings = 0
    for a, b in zip(xs, xs[1:]):
        if (a < 0) != (b < 0):
            crossings += 1
    return crossings / 2.0 / (len(xs) / fs)


print("== (a) response at r=0, fc=1000 Hz (dB re input) ==")
for f in [125, 250, 500, 1000, 2000, 4000, 8000, 16000]:
    print(f"  {f:6d} Hz  {db(steady_gain(1000, 0.0, f)):7.2f} dB")

print("== (b) passband loss vs r at 100 Hz, fc=1000 Hz ==")
for r in [0.0, 1.0, 2.0, 3.0, 3.5, 3.9]:
    print(f"  r={r:3.1f}  {db(steady_gain(1000, r, 100)):7.2f} dB")

print("== (c) resonance peak location, r=3.5, fc=1000 Hz ==")
best = (0, -1e9)
for f in range(600, 1401, 25):
    gdb = db(steady_gain(1000, 3.5, f))
    if gdb > best[1]:
        best = (f, gdb)
print(f"  peak at ~{best[0]} Hz, {best[1]:.2f} dB")

print("== (d) self-oscillation, zero input after a 1e-3 tick, r=4.3 ==")
for fc in [100, 500, 1000, 4000, 8000]:
    lad = Ladder()
    out = []
    for n in range(int(1.0 * FS)):
        x = 1e-3 if n == 0 else 0.0
        out.append(lad.process(x, fc, 4.3))
    tail = out[int(0.5 * FS):]
    print(f"  fc={fc:5d}  osc={zero_cross_freq(tail, FS):8.1f} Hz  ratio={zero_cross_freq(tail, FS)/fc:6.3f}  peak={max(abs(v) for v in tail):.3f}")

print("== (e) stability: full-scale 55 Hz saw, fc swept 20 Hz -> 16 kHz over 1 s, r=4.3 ==")
lad = Ladder()
peak = 0.0
finite = True
ph = 0.0
for n in range(int(1.0 * FS)):
    ph += 55.0 / FS
    saw = 2.0 * (ph - math.floor(ph)) - 1.0
    fc = 20.0 * (800.0 ** (n / FS))
    y = lad.process(saw, fc, 4.3)
    if not math.isfinite(y):
        finite = False
        break
    peak = max(peak, abs(y))
print(f"  finite={finite} peak={peak:.3f}")

print("== (f) DC: 0.5 DC in, r=0 and r=3.5, fc=1000 (steady output) ==")
for r in [0.0, 3.5]:
    lad = Ladder()
    y = 0.0
    for n in range(int(0.3 * FS)):
        y = lad.process(0.5, 1000, r)
    print(f"  r={r}  out={y:.4f}")

print("== (g) r=4.3 with a 0.3-amplitude 110 Hz saw in, fc=880: sings and passes? ==")
lad = Ladder()
ph = 0.0
out = []
for n in range(int(0.6 * FS)):
    ph += 110.0 / FS
    saw = 0.3 * (2.0 * (ph - math.floor(ph)) - 1.0)
    out.append(lad.process(saw, 880, 4.3))
tail = out[int(0.3 * FS):]
print(f"  peak={max(abs(v) for v in tail):.3f}  rms={rms(tail):.3f}")
```

Output on 2026-09-24 (`python3 ladder_proto.py`, 4.8 s):

```
== (a) response at r=0, fc=1000 Hz (dB re input) ==
     125 Hz    -0.24 dB
     250 Hz    -1.05 dB
     500 Hz    -3.88 dB
    1000 Hz   -12.04 dB
    2000 Hz   -27.95 dB
    4000 Hz   -49.19 dB
    8000 Hz   -72.40 dB
   16000 Hz   -95.93 dB
== (b) passband loss vs r at 100 Hz, fc=1000 Hz ==
  r=0.0    -0.17 dB
  r=1.0    -5.94 dB
  r=2.0    -9.45 dB
  r=3.0   -11.96 dB
  r=3.5   -12.98 dB
  r=3.9   -13.73 dB
== (c) resonance peak location, r=3.5, fc=1000 Hz ==
  peak at ~975 Hz, 6.85 dB
== (d) self-oscillation, zero input after a 1e-3 tick, r=4.3 ==
  fc=  100  osc=   102.0 Hz  ratio= 1.020  peak=0.031
  fc=  500  osc=   498.0 Hz  ratio= 0.996  peak=0.114
  fc= 1000  osc=  1009.0 Hz  ratio= 1.009  peak=0.093
  fc= 4000  osc=  4217.0 Hz  ratio= 1.054  peak=0.000
  fc= 8000  osc=     0.0 Hz  ratio= 0.000  peak=0.000
== (e) stability: full-scale 55 Hz saw, fc swept 20 Hz -> 16 kHz over 1 s, r=4.3 ==
  finite=True peak=0.419
== (f) DC: 0.5 DC in, r=0 and r=3.5, fc=1000 (steady output) ==
  r=0.0  out=0.5000
  r=3.5  out=0.1111
== (g) r=4.3 with a 0.3-amplitude 110 Hz saw in, fc=880: sings and passes? ==
  peak=0.206  rms=0.098
```

Reading the 4 kHz row of (d): the zero-crossing count still reads a tone
(4217 Hz) but the tail's peak rounds to 0.000, so the oscillation is
marginal — starting from a 1e-3 tick it neither grows nor dies within the
second. At 8 kHz nothing starts. Both are the loop-gain limit the body of
this document describes.
