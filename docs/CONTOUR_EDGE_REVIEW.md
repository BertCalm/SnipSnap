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

## Full-range follow-up, 2026-10-24

The owner clarified that turning EDGE from zero to maximum remained too subtle
on the other seven recipes. Soft Digital and Bright Margin were heard as
responsive. The preceding review's two-preset acceptance guard was too narrow
for that feedback.

### Fix now

- **[A, D] The body masks the changing partials.** The source gain still tops out
  at 0.8 while the body stays present. Raising the saved edge level alone cannot
  change that upper endpoint. Native complete held renders measured only 1.058 dB
  of normalized band movement for Moving Corner and 1.601 dB for Copper Scan.
  These are numerical contrasts, not hearing thresholds.
- **[A, C] More edge power needs a pitch argument.** Reducing only the saved body
  level widened the range, but one Twin Outline trial moved the pitch detector
  up an octave. A partial boost with no additional root support also produced
  octave detections in Rounded Edge and Twin Outline. Those trials were rejected.

### Selected revision

`CONTRAST` is a third saved response, leaving both earlier laws available. With
EDGE `e` and the existing BODY root weight `r`, its shape laws are:

```text
body non-root multiplier = 1 - 0.65e
edge non-root multiplier = 1 + 3e
added body-root weight   = e × (0.5 + 0.4 × max(0, 1 - r))
```

The body retains at least 35% of its overtones. Only the edge's non-root detail
gets the boost; its saved fundamental or interval keeps the prior weighting.
Root support increases when BODY has already reduced the requested root. The
mix budget includes both added contributions before the existing DRIVE stage.
The constants are selected shape values, not a physical calibration. No new
oscillator, table family, filter path, nonlinear stage or loop state is added.

Seven factory recipes select this response. Soft Digital and Bright Margin keep
their complete preceding recipes, including `FOREGROUND`. New voice defaults
select `CONTRAST`. Missing/null fields still mean `LEGACY`; explicit earlier
responses keep their arithmetic. The model number and oscillator phase seed stay
unchanged. The only factory recipe field changed is the saved response identity.

### What was measured, what was heard

The selected native Kotlin prototype rendered 70 complete control cases and
168 additional BODY/register/finite/held corners. The latter pitch detections
stay within 2.594 cents with confidence at least 0.922. All 14 EDGE-zero prototype
WAVs match the previous native recordings byte-for-byte. The final regression
tests cover all seven full curves, each adjacent step, both note shapes and the
pitch failures found during prototyping.

| Held recipe | Previous full range | Selected full range | Selected 25–50% step |
|---|---:|---:|---:|
| Rounded Edge | 2.534 dB | 5.362 dB | 1.396 dB |
| Moving Corner | 1.058 dB | 3.915 dB | 0.902 dB |
| Dark Table | 4.784 dB | 6.132 dB | 0.712 dB |
| Copper Scan | 1.601 dB | 3.663 dB | 0.806 dB |
| Twin Outline | 3.624 dB | 10.491 dB | 2.534 dB |
| Hollow Step | 1.694 dB | 5.609 dB | 1.384 dB |
| Long Contour | 3.672 dB | 14.193 dB | 2.496 dB |

These use the same 40 normalized logarithmic power bands as the earlier study.
The preceding audition's complete FOREGROUND recipes are pinned as test resources
from `47809ba2de9cf042bd77dc7feebffdffefc987b1`. The generator compares those with
current recipes, rather than changing a response field on today's configuration
and calling that the previous version. Both page cards can play 0%, 50% and 100%
in sequence. The output still contains 180 complete cases with raw/matched WAVs,
replay recipes and measured held closures.

The owner's reported weak full range is heard evidence about the preceding
version. The new response has not received listening approval. Stronger partial
contrast can become too forward at high EDGE; numerical pitch checks do not
establish perceived pitch or rounded character for every listener. Physical
Android/MPC playback remains a separate verification step.

### Why the follow-up is built this way

- Trading overtone balance addresses masking while keeping each recipe's table
  pair, scan paths and articulation.
- Additional root support prevents the tested octave failure without removing
  the BODY control's continuous influence.
- Including added contributions in the source budget avoids using extra drive
  as the explanation for greater contrast.
- Keeping the two responsive recipes unchanged makes this round answer the
  remaining seven, while saved response identities preserve earlier sounds.
