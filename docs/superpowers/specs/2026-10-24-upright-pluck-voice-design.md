# UPRIGHT: a finite PLUCK bass voice

Status: Kotlin port of the approved first listening proof. Implementation and
validation are recorded in the As built section. Native listening and physical
phone/MPC acceptance remain separate gates.

Repository truth: `5e953da5493d08112f10fcd1bd2b000bd7946b92`.
Source proof: `upright-python-ks-1`, SHA-256
`19f6d0a46ac03bc7ddf5d11e5ada5f5d915968a118268dcbf9e24ce4b8bf093c`.

The characteristic gesture is **finger release → string ring → wooden
response → damped tail**.

## Scope and landing

Add `PluckVoice.UPRIGHT`, with ROUND and MUTED as presets of one voice. The
nearest siblings are PLUCK's existing guitar/harp/world-string voices and
MAGNET's pickup-based electric guitar; FATHOM already covers electronic bass.
This work adds a low, finger-plucked articulation. It does not add an engine,
another oscillator-bass family, or a collision/bowing simulation.

The three directions considered were SPLICE (a shaped string release into a
low-register string/body voice), MUTATE (the same string with stronger loss),
and COUPLE (a string exchanging energy with bridge/body/contact). SPLICE is
the smallest clear bass gesture; MUTATE supplies its muted endpoint. COUPLE
needs a separate passivity and contact study and is outside this port.

## Model and signal flow

```text
note + saved strength + TOUCH
  → bounded released displacement + initial seeded texture
  → lossy fractional delay, compensated at the requested fundamental
  → one-way three-filter wooden readout, controlled by WOOD
  → 4× band limit → decimate → subtract mean → 20 Hz high-pass
  → reference-excitation gain → output peak ceiling → finite tail
```

The string is a literal reduced linear waveguide. "Finger release" describes
its initial condition, not an animated finger or a contact-force solver.
WOOD is a filter/readout law, not measured body geometry or two-way loading.
All voicing constants below are heard design choices, not measured upright
bass parameters.

```text
f0 = 440 * 2^((midi - 69)/12), midi = 28..52
t60 = 3.5 * 0.19^DAMP seconds
lossHz = 3600 * 0.24^DAMP
gainPerTrip = exp(-ln(1000)/(f0*t60)) < 1
initialSmoothingHz = 180 * 18^TOUCH * (0.25 + 1.8*strength²)
initialAmplitude = 0.65 * strength^0.85
bodyScale = 1.15 - 0.3*WOOD
bodyCenters = [130, 360, 1050] * bodyScale Hz
bodyQ = [1.2, 2.0, 2.8], bodyWeights = [0.20, 0.18, 0.12]
output = string + WOOD*bodyReadout
```

Initialization projects a displaced string at positions 0.18 and 0.11 with
harmonic weights proportional to `sin(kπ*0.18)*sin(kπ*0.11)/k²`. At most
96 harmonics below 10 kHz are included. A filtered `Dsp.Noise` perturbation
is initialized once; it supplies no ongoing power. The seed depends on the
voice/model/note, retaining its identity while TOUCH, DAMP, WOOD or strength
changes. The loop is linear interpolation, a convex one-pole loss filter,
and gain below one. Its unforced amplitude cannot exceed the initialized
peak. The independent diagnostic test deliberately makes the gain unstable
to establish that its growth check can fail.

## Controls and interactions

| Control | Default | Model quantity | Required verification |
| --- | ---: | --- | --- |
| TUNE | 5/24 | Semitone from E1 through E3 | Pitch across the range and at DAMP/WOOD corners. |
| TOUCH | 0.50 | Initial displacement smoothing and initial texture | Attack spectrum changes after level matching; interaction with saved strength. |
| DAMP | 0.18 | Loop lifetime and low-pass loss | Shorter/darker tail; pitch retained as the loss filter changes. |
| WOOD | 0.55 | Readout balance and filter centers | More than gain alone; root retained across notes and damping. |

Strength is a finite saved value in 0..1, separate from the four macros.
The reference excitation at strength one is conditioned and leveled with
`Dsp.levelTo`. Its gain is reused by softer renders, avoiding per-note
normalization that would erase dynamics. Zero strength is silent. An
eight-entry owned cache keys all four settled macros; its maximum reference
PCM footprint is approximately 4.31 MiB. Copies protect cached audio from
caller mutation. There is no HOLD macro or driven sustain adaptation.

## Compatibility and integration

`Pluck.renderWith` branches to the new renderer before the existing DSP.
The original NYLON, HARP, KOTO, BANJO and SITAR code paths and twenty pinned
PCM expectations stay unchanged. Existing PLUCK JSON retains its shape.
UPRIGHT alone saves excitation velocity beside the macros. `Velocity`
dispatches this voice to its native excitation path.

The existing PLUCK picker uses the expanded voice list. Preset/edit/save/
reopen/SEND paths must retain the full patch. The CLI renders explicit notes
and strengths. `UprightKits.classic()` supplies sixteen dry finite TONAL pads,
eight ROUND followed by eight MUTED. Export uses the existing MPC 3 and MPC 2
writers and verifies their read-back. There are no new engine-registry arms,
keygroups, sustain markers or C++ changes.

## Render budget and risks

The production target is a rerender in tens of milliseconds on the target
phone; it is a target, not an established phone measurement. Measure cold
and repeated host renders separately. Reference caching avoids a second
reference render during repeated strength edits; parameter changes can
require a fresh reference. The model has fixed duration, bounded delay
length and bounded harmonic count, with no trajectory solver.

The first risks are root loss beneath body coloration, pitch drift from
uncompensated loop-filter phase, damping that only gates the finished output,
velocity normalization that erases touch, and accidental changes to the old
PLUCK voices. Each has a focused numerical/replay test and an isolated
listening comparison. House noise and output filtering differ from Python;
the Kotlin audition is a fresh listening gate, not an asserted byte-identical
port of the approved files.

## Evidence ledger

- Verified: the named repository, existing PLUCK/patch/velocity/output APIs,
  the archived Python source and complete recipes.
- Heard: the owner approved the Python bass study on 2026-10-24.
- Proposed until measured: target-phone timing and acoustic realism.
- Not yet heard by the owner: the final Kotlin recordings and exported kit.

## Why it is built this way

One string keeps pitch and control changes easy to judge. The body follows
the string instead of adding an unrelated sound. Actual loop loss makes
muting part of the instrument. One reference gain preserves softer playing.
An isolated voice path protects existing saved music.

## As built

The new voice, two preset endpoints, saved excitation velocity, phone/CLI
paths, sixteen-pad starter, native audition and export generators implement
the scope above. Native test/build/audition results are recorded alongside
the delivered artifacts; a successful numeric check is not listening approval.
