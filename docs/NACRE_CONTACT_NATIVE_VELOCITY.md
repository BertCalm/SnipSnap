# NACRE model-4 native velocity recipe

This is an explicit opt-in source recipe. It adds no starter, bank generator,
download replacement, UI route, new voice or physical-device approval. Existing
model-3 `NacreVelocityRecipe` / `NacreVelocityKits`, their source field and policy,
and every existing renderer/default/factory recipe remain unchanged.

The two CONTACT settings (.35 and .8) already received KEEP at native velocity1,
TOUCH .30, MIDI48 and seed7. Those exact hard source arrays remain the reference.
Soft and Medium below are new native performances that need separate listening.

## Source contract

`NacreContactVelocityRecipes.recipeFor(hardPatch)` accepts a complete dry PEARL
model-4 source, native velocity1, TOUCH .30 and finite HOLD0. Every model-4 control
must be saved, including CONTACT; partial/default-inferred bank sources refuse.
The complete note, signed 64-bit seed, name, model and all controls other than
TOUCH remain identical across layers.

| MIDI velocity | Layer | Native velocity | TOUCH | Fixed sampling gain |
| --- | --- | ---: | ---: | --- |
| 1–41 | Soft | .30 | .05 | unchanged `NacreVelocityKits.samplingGain(.30, .05)` |
| 42–84 | Medium | .65 | .18 | unchanged `NacreVelocityKits.samplingGain(.65, .18)` |
| 85–127 | Hard | 1 | .30 | 1 |

Velocity0 selects no layer. The established native velocity/TOUCH excitation is
reused because the original velocity-only proposal was already known to be too
close to scalar gain. CONTACT remains fixed; it is not a new velocity curve.
The gain cancels only the existing explicit native velocity/TOUCH effort factors.
It is never fitted to peak, RMS, a hash or a rendered comparison.

## Ownership and saved policy

The separate source key is `nacreContactVelocityLayers`. Its policy is
`native-velocity-touch-contact-model4-v1`, `policyVersion=1`, `schemaVersion=1`.
It cannot be decoded as model-3 v1 and does not broaden that old contract.

Each layer owns a copied, unmodifiable macro map and a fresh dry `PadRecipe`.
The bank owns its layer list. JSON requires exact top/layer fields and complete
canonical nested recipes. Unknown fields, mixed sources, fractional windows,
wrong model/velocity/TOUCH/gain and Double changes rounded away by Float decoding
refuse. Use the provided codec to retain the complete source precision.

`renderAllLayers()` replays three complete six-second mono 44.1kHz sources.
Returned arrays are independently owned; Hard is copied with no gain arithmetic.
Wrong shape/rate/length, nonfinite audio or output above the unchanged .95
ceiling refuses. The recipe never normalizes, trims, fades or edits audio to pass.
Ordinary one-source REPLAY remains separate from full native bank replay.

## Verification boundaries

Focused tests cover policy/compatibility, immutable ownership, tamper refusal,
full-buffer replay and native hard equivalence. The native check renders six unique C3 source cases, then explicitly replays Soft
after policy serialization (seven native invocations total). It records fresh
gain-removed residuals against Hard without importing model-3 thresholds or
claiming listening acceptance.

Tests normally emit no files. A fresh Root-granted native gate may set
`snipsnap.nacreContactVelocity.captureDir` and the actual 40-hex
`snipsnap.nacreContactVelocity.sourceCommit`. Only after both hard hashes and all
six native deliveries pass does it create a new capture directory containing
six final Float32LE buffers and `native-velocity-captures.json`. The metadata binds
full PadRecipes, velocities/TOUCH/CONTACT, windows/gains, format/length, native and
delivered hashes, and measured residuals. Actual house PCM24/WAV encodings are hashed in memory,
including the parsed RIFF data chunk, without creating WAV files. Capture reuses
the first six already-tested arrays;
it never rerenders, changes gain or overwrites a previous failed attempt.

Unit/native execution, independent artifact checks, browser publication/listening,
normal App/APK integration and physical acceptance remain distinct gates. This
source increment does not establish any of those results by itself.

## Policy v2: strike shaping (awaiting the owner's listen)

Status: built and measured, **not heard**. It awaits the owner's listen in Listening Desk
round `nacre-strike-1`. v1 above is unchanged: it still decodes, and it still renders
byte-identically (Float hashes of all six v1 layers read at `f3df719d` are pinned in
`NacreContactStrikeRecipeTest`).

### Why

The owner heard v1 Soft and Medium as "the same as Hard". The measurements agree. For PEARL
model 4 at C3, CONTACT .35 and .80, v1 Soft and Medium are 97 to 98% a gain-scaled copy of Hard:
once the best single gain is removed, what is left is -14.4 to -17.1 dB, and the spectral centroid
moves only 3 to 6 Hz. The cause is structural. The fixed sampling gain cancels the exciter's
effort factor, and everything after the exciter is linear, so lower velocity and TOUCH only changed
the level. The terms that survive (loop low-pass, pick low-pass x(.7 + .3 velocity), non-root
participation, strum spacing, the upper strings' pitched-tone share .80 - .12 TOUCH) barely move a
note whose energy sits around 175 Hz.

v2 adds the missing part of a soft pluck: how the string is touched, not only how hard.

### What changes, in plain words

Each v2 layer carries a `NacreStrikeShape`, three numbers that only change the short excitation
burst that starts each string. Strings, bodies, venue, gains and finishing are untouched, so a
shape cannot add feedback, a new oscillator or a level stage.

- **Contact width (`widthMs`)**: how long the finger presses. A pick touches for an instant; a
  fleshy finger presses for about a millisecond. The burst is smoothed by a raised-cosine pulse that
  wide, which is a low-pass filter on the excitation: the first null sits at 2 / width (1 ms puts it
  at 2 kHz). Short, high strings and upper harmonics are barely set moving, so the attack loses its
  sparkle. The burst starts half a pulse early so its centre stays on the scheduled time; a soft
  layer is never late against Hard. This control does nearly all the measured darkening.
- **Pick brightness (`pickBrightness`)**: multiplies the pick filter's cutoff, so the noise that
  starts the string carries fewer high frequencies. Measured alone at Soft (x.5) it lowers the
  high band by only .45 dB, because that noise is already filtered low.
- **Tone shift (`toneShift`)**: in the code each burst mixes contact noise with a pitched sine at the
  string's own frequency (the "foundation"). The shift adds to the pitched share and takes the same
  amount from the noise: less scrape, more note. Measured alone at Soft (+.12) it does not darken
  (the high band moves +.1 dB, because the top strings' own pitches are above 1 kHz) but it raises
  the layer's level by about 1.4 dB, since the fixed sampling gain does not cancel it.

`NacreStrikeShape.DEFAULT` (width 0, brightness 1, shift 0) skips all three: the exciter runs its
built arithmetic, and every existing render is unchanged (the existing NACRE hash and compatibility
tests pass untouched). Hard is always DEFAULT at velocity 1 and TOUCH .30, so v2 Hard is v1 Hard bit
for bit (asserted against the accepted Float witnesses).

The lead's design named this first control an "edge window": lengthen the burst's existing .35 ms
fade-in and fade-out. Measured, that does not low-pass. It cuts the long root bursts more than the
short high ones, so the high-band share **rose** (Soft at 2.2 ms: high band -3.3 dB against Hard,
worse than v1's -4.7) while the residual moved because the root got quieter. It was replaced by the
contact-width smoothing above, which is the low-pass the design described. The .35 ms fade is kept.

### Policy and saved form

Key `nacreContactStrikeLayers`, policy `native-velocity-touch-contact-model4-v2`,
`policyVersion=2`, `schemaVersion=1`. Each layer is exactly v1's layer (name, window, fixed
sampling gain, complete canonical `PadRecipe`) plus `variant` and `strike {widthMs, pickBrightness,
toneShift}`. Constructing v2 also constructs the v1 recipe from the same layers, so every v1 rule
still applies. Every layer must carry the same variant, and the shapes must equal that variant's
fixed table exactly; unknown fields, mixed variants, an off-table shape or a non-DEFAULT Hard
refuse. v1 and v2 refuse each other's documents.

The shape is a render input of the layer, not part of the saved patch. Replaying a layer's
`PadRecipe` alone as an ordinary pad gives the v1 (unshaped) sound; only v2 bank replay plays the
shape. Native velocity, TOUCH, windows and sampling gains are v1's.

| Variant | Layer | Native velocity / TOUCH | Contact width | Pick brightness | Tone shift |
| --- | --- | --- | ---: | ---: | ---: |
| A (moderate) | Soft | .30 / .05 | 0.8 ms | x.50 | +.12 |
| A (moderate) | Medium | .65 / .18 | 0.4 ms | x.75 | +.06 |
| B (strong) | Soft | .30 / .05 | 1.4 ms | x.33 | +.18 |
| B (strong) | Medium | .65 / .18 | 0.7 ms | x.60 | +.10 |
| both | Hard | 1 / .30 | DEFAULT | DEFAULT | DEFAULT |

Pick brightness and tone shift are the lead's proposed values. The contact widths were chosen by
measurement so that A is clearly darker than v1 and B clearly darker than A; the lead's proposed
edge lengths (1.0 to 3.5 ms) applied as contact widths remove the top strings almost entirely
(Soft at 2.2 ms: high band -36 dB against Hard).

### Measured (C3, seed 7, native renders at delivered level)

Kotlin measurements (`NacreStrikeMeasure`, test sources), written by
`./gradlew --no-daemon :synth:generateNacreStrikeAudition`. "High band" is the energy above 1 kHz
as a share of the total in the first 300 ms (Hann window, level-independent). "Residual" is the
whole six-second layer after removing the best single gain of Hard, relative to the layer (more
negative means more like a scaled copy of Hard). Pitch is `Pitch.detect`, in cents against Hard.

| CONTACT | Version | Layer | High band (dB) | vs Hard (dB) | Centroid 0-300 ms (Hz) | Residual vs Hard (dB) | Pitch (cents) | Peak | RMS (dBFS) |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| .35 | all | Hard | -23.43 | 0 | 173.8 | n/a | 0 | .522 | -28.70 |
| .35 | v1 | Medium | -26.79 | -3.36 | 171.9 | -17.06 | -0.41 | .527 | -28.76 |
| .35 | v1 | Soft | -28.17 | -4.73 | 170.7 | -14.77 | -0.71 | .517 | -29.03 |
| .35 | A | Medium | -28.43 | -5.00 | 170.9 | -17.17 | -0.48 | .565 | -28.08 |
| .35 | A | Soft | -32.61 | -9.18 | 168.8 | -14.51 | -0.77 | .587 | -27.76 |
| .35 | B | Medium | -30.98 | -7.55 | 169.6 | -17.36 | -0.53 | .584 | -27.70 |
| .35 | B | Soft | -42.84 | -19.41 | 165.8 | -14.29 | -0.75 | .607 | -27.37 |
| .80 | all | Hard | -21.58 | 0 | 181.2 | n/a | 0 | .488 | -30.96 |
| .80 | v1 | Medium | -24.96 | -3.38 | 177.2 | -16.25 | -0.54 | .493 | -30.99 |
| .80 | v1 | Soft | -26.29 | -4.72 | 175.2 | -14.43 | -0.96 | .484 | -31.19 |
| .80 | A | Medium | -26.70 | -5.12 | 175.6 | -16.38 | -0.65 | .529 | -30.31 |
| .80 | A | Soft | -30.83 | -9.25 | 172.5 | -14.12 | -1.04 | .549 | -29.92 |
| .80 | B | Medium | -29.34 | -7.76 | 173.8 | -16.56 | -0.72 | .547 | -29.93 |
| .80 | B | Soft | -41.06 | -19.49 | 169.0 | -13.84 | -1.03 | .568 | -29.54 |

Against the round's targets:

- High band falls from Hard to Soft by at least 3 dB (A) and 6 dB (B), Medium between: **met**
  (A -9.2/-9.3, B -19.4/-19.5, Medium between at both CONTACTs). Note v1 Soft already fell 4.7 dB,
  so the useful comparison is against v1: A Soft is 4.5 dB darker than v1 Soft, B Soft 14.7 dB.
- Pitch within 10 cents: **met** (all within 1.1 cents).
- All finite and under the unchanged .95 ceiling with no normalising: **met** (highest peak .607).
- Soft's gain-removed residual at least 8 dB above v1's: **missed**. A gains .3 dB, B .5 to .6 dB.
  The band strike shaping can reach is a small part of the energy: above 1 kHz is under 1% of the
  first 300 ms (-21.6 to -23.4 dB), and the residual is set by the 130 and 262 Hz body, which a softer
  contact barely changes. For the record, the literal edge window reached -7.8 / -7.4 dB on B Soft
  (+7.0 dB) only by lowering the root, and edge window plus contact width together reached -8.1 /
  -7.7 dB (+6.7 dB); neither reaches +8 and both make the root weaker, not the touch softer.
  Making Soft less of a scaled copy below 1 kHz needs a different mechanism (for example how the
  root and octave strings are balanced or timed), which this round deliberately does not add.
- Level: the tone shift makes v2 Soft 1.3 to 1.7 dB louder than v1 Soft at the same fixed sampling
  gain (A and B Soft now sit 0.9 to 1.4 dB above Hard). The sampler still sets velocity volume; this is
  disclosed, not fitted away.

### The audition

`./gradlew --no-daemon :synth:generateNacreStrikeAudition` (output `testkit/nacre-strike-audition/`,
gitignored; `-PnacreStrikeAuditionOutput=<dir>` redirects it) writes six ladders,
`nacre-{035,080}-{v1,a,b}-ladder.wav`: Soft, Medium, Hard of C3, each the first 2.0 s of the layer
with a 30 ms fade at the cut, 0.3 s apart, at the recipes' own delivered levels, 16-bit mono at the
house rate; plus `measurements.json` with the numbers above. Listening question for round
`nacre-strike-1`: does Soft now sound like a softer pluck rather than a quieter Hard, and is A or B
the right amount? Nothing here is heard yet.
