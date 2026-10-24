# Contour EDGE: Luthier review and revision

Reviewed the implementation at `937bb05edff19584fa936e04c6c6e2ee4296985f`.
The complete Luthier bundle was read from repository commit `3615b5d74`, including
SnipSnap conventions, the review rubric, DSP catalog section 8 and engine inventory.
This is a control-response revision to the existing two-layer wavetable engine.

## Fix now

- **[A, D] The resting edge can be too quiet for its level control to matter.**
  The old control scales the edge source to at most 0.8, then its envelope
  multiplies it again. HOLD uses the saved `edgeSustain` directly. Bright Margin
  saves 0.05 and Soft Digital 0.16. Native isolated renders put their maximum
  held edges about 21.2 dB below the body. Opening the filter raised that isolated
  edge by only about 2 dB: envelope suppression is the principal issue here.
- **[D] The previous acceptance test missed the complete sound.** It checked
  edge-only RMS with an open filter and an edge sustain of one. The R05 listening
  examples also omitted the two weakest presets and their held forms. A louder
  isolated layer does not prove a useful change after mixing and output leveling.

## Revision

The foreground response keeps the existing source-level law and blends the saved
edge projection toward one in proportion to EDGE:

```text
foreground projection = saved projection + (1 - saved projection) × EDGE
```

At zero, the edge is still muted. As the control rises, its layer becomes both
louder and less extinguished after the attack. This adds no oscillator, filter
bypass, distortion stage or loop state. The body, tables, tuning, scan paths,
shared filter and house output stage retain their mechanisms.

A trial that changed only the upper half preserved the old midpoint but left the
lower half weak. The whole-range response was selected for a more useful middle
range. Its midpoint deliberately differs from the old response.

Fresh voices and starter presets select `FOREGROUND`. The response is a saved
configuration property. Missing or null `edgeResponse` decodes to `LEGACY`, and
explicit legacy recipes follow the original arithmetic. Macro edits preserve that
choice. The render-model number and phase seed remain unchanged. Load a fresh
preset in the updated app to use the new behavior; existing saved drafts retain
their saved response.

## What was measured

The baseline harness rendered 120 complete finite/held control cases and 72
isolated-layer diagnostics. Native Kotlin prototypes used the same tables,
configuration, note, velocity and output stage. Forty normalized logarithmic
power bands distinguish timbral change from a scalar gain change. These are
numerical contrasts, not hearing thresholds.

| Held preset | Previous full EDGE range | Revised full range | Previous 25–50% step | Revised 25–50% step |
|---|---:|---:|---:|---:|
| Bright Margin | 0.269 dB | 5.073 dB | 0.061 dB | 0.949 dB |
| Soft Digital | 0.398 dB | 1.712 dB | 0.077 dB | 0.341 dB |

The reproducible `:synth:generateContourEdgeAudition` task records all nine full
recipes, both finite and held, at five EDGE positions for both responses: 180
clips. It writes native raw and matched WAVs, complete replay recipes, source and
audio hashes, and real preblend loop-closure measurements. The comparison uses
the other controls as authored, rather than resetting them to neutral.

## Disclosed, not fixed

The shared filter still suppresses partials at low cutoff settings. Related table
pairs and their scan positions can also make a particular step subtler. The
revision does not promise equal perceptual spacing or brighter sound at every
step. Increasing an octave-related edge can emphasize upper partials; pitch,
raw stability and loop closure remain acceptance checks.

The user reported weak EDGE response in the previous audition. The revised
recordings require the user's listening verdict. Numerical checks do not establish
sonic approval or physical Android/MPC playback.

## Why it is built this way

- The envelope was hiding the source. Lifting its projection addresses that cause
  while retaining the related wavetable layer.
- Matched playback removes overall loudness as a reason to prefer one recording.
- Keeping the shared filter and drive path avoids adding another brightness or
  distortion mechanism to solve a layer-balance problem.
- Saving the response keeps earlier recipes replayable and makes the comparison
  recipes explicit about which behavior they use.
