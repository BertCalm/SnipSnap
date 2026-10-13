# Osprey

OSPREY adapts the [XOsprey architecture blueprint](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/xosprey_architecture_blueprint.md)
into a reproducible SnipSnap sample generator. One finite sea gesture excites
pitched resonators; scheduled creature forms answer its activity. Changing SHORE
morphs the resonator, creature and water profiles together.

`OspreyVoice.SWELL` gives each shore an invented material and contact pattern in
model 2. The concept received positive feedback; the original 46-clip study is
marked **Revise for contrast** and remains preserved. Model 2 awaits listening
review in its [private audition](https://snipsnap-osprey-audition.bertcalm.chatgpt.site).
The phone picker, CLI, fifteen dry presets and
sixteen-pad starter use the versioned renderer. CHOP, a dedicated CALL voice
and certified repeating HOLD are later work.

## Controls

All six macros use 0–1. The musical note is a separate recipe field.

| Control | Behavior |
| --- | --- |
| SHORE | Atlantic → Nordic → Mediterranean → Pacific → Southern; continuously morphs adjacent material, creature and water profiles. |
| SEA | Moves from sparse swell contacts to rough chop and storm breaker clusters; Nordic also takes bowed pressure. |
| MOTION | Changes the pace and density of wave movement and creature activity. |
| DEPTH | Darkens upper-mode radiation, extends sympathetic decay and raises creature presence; zero mutes the creature branch. |
| SPACE | Adds harbor distance and diffusion; zero is exactly the dry source. |
| HOLD | Extends the finite excitation gesture within the requested duration. Every setting still renders a one-shot. |

Pure shores lie at SHORE 0, 0.25, 0.5, 0.75 and 1; halfway coastlines lie at
0.125, 0.375, 0.625 and 0.875. The engine interpolates profiles within a render.
SHORE stays fixed during each render; editing it generates a new sample.

## Sound and timeline

The signal path is seeded water activity → material excitation → primary and
sympathetic modal resonators → scheduled creature forms → dry mix → SPACE.
SEA changes how wave pressure and contacts excite the body. Creature forms
answer water activity; their events and envelopes fit the finite gesture.
The terminal tail lets modal energy decay.

| Shore | Model 2 material and articulation |
| --- | --- |
| Atlantic | Wire-like pluck and strum. |
| Nordic | Sustained bowed body. |
| Mediterranean | Woody double pick with a short decay. |
| Pacific | Struck bowl with long ringing upper modes. |
| Southern | Uneven metal strum. |

One primary mode anchors the requested note; upper modal ratios, damping and
radiation give each coast its own body. The materials are invented synthesis
families, and creature names identify invented formant forms. Neither is an
authentic instrument emulation or measured biological model.

Diagnostic taps expose ocean excitation, primary resonators, sympathetic
resonators and creatures. Primary + sympathy + creatures reconstruct the dry
source. The ocean tap monitors the shared exciter; it is not added as a fourth
audible layer. SPACE 0 returns that dry source directly.

## Saved recipe and playback

`OspreyPatch` stores `OSPREY`, `SWELL`, patch `version`, name, macros and:

| Field | Contract |
| --- | --- |
| `midi` | Integer 36–84, default 60. |
| `velocity` | Finite 0–1 excitation velocity, default 1. |
| `seed` | Saved integer variation seed, default 0. |
| `durationSeconds` | Finite 0.25–12 seconds, default 8. |
| `modelVersion` | New patches default to 2; saved models 1 and 2 are supported. |

`OspreyV1` preserves the published model-1 math and original defaults. JSON with
missing or null `modelVersion` still selects 1. Editing a saved model-1 recipe
retains its model; choosing model 2 is an explicit change. The CLI accepts
`--model 1|2`; the common patch `version` remains 1.

Independent renders start with fresh random, modal and spatial state. The same
complete recipe repeats the sample and events. Pads do not share a live ocean.
Output is stereo at 44.1 kHz with the requested finite frame count.

Repitching a WAV changes wave and creature timing together with pitch. HOLD
provides no periodic state or seamless-loop contract; a sustained key cannot
continue an unrendered sea. SnipSnap follows patch → sample → pad playback.

## Audition and checks

Run from the repository root:

```sh
./gradlew --no-daemon :synth:generateOspreyRevisionAudition
./gradlew --no-daemon :synth:test
```

`OspreyRevisionAuditionGenerator` verifies the preserved published model-1
library in `testkit/osprey-audition/` and writes separately to
`testkit/osprey-revision-audition/`. Eleven full R1/R2 pairs compare all five
rough shores and calm/storm Atlantic, Nordic and Mediterranean. Each pair keeps
C4, velocity 1, seed 731, eight seconds and the original macros unchanged.
Two summary pairs use identical 2.4-second excerpts of those full gestures.
The task also includes five DEPTH-0 body pairs: creatures are
muted, but modal radiation and decay also change, so these are full diagnostic
renders rather than isolated taps. This makes 18 paired cards, including the
two excerpt lineups. The listening page begins with audio off.

Full cards include matched/raw 16-bit stereo WAVs and saved patch JSON. Each
A/B pair shares an achievable loudest-200-ms stereo RMS target of at most 0.12,
with peaks at most 0.90. Original raw audio and patches are copied with verified
hashes; matched R1 playback may get a separate lower-target copy. Raw retains
engine amplitude. Summary excerpts inherit each full clip's gain and record
their additional lineup gain; montages have no single saved patch.

The original `:synth:generateOspreyAudition` task stays explicitly on model 1.
Its 46-card shore, weather, morph, anatomy and control study remains accessible
as **Original study**. A new R1 render has new provenance; the revision generator
requires the preserved published baseline. Original anatomy taps keep their
complete dry mix's gain and their patches regenerate the full source.

Manifests record recipes, source provenance, file hashes, amplitude measurements,
call/contact events and decay diagnostics. Tests pin model-1 sample bits and
check model-2 repeatability, audible coast/weather contrast, root audibility,
continuous morphs, tap reconstruction and finite bounded output. These checks
establish the contract; the revised sound still requires listening review.

## Attribution

The shore instrument, creature and fluid profile data in `OspreyShore.kt` is
adapted from XO_OX Designs' [ShoreSystem.h](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Source/DSP/ShoreSystem/ShoreSystem.h),
copyright © 2026 XO_OX Designs, under MIT. The port retains its attribution and
SPDX identifier. The finite renderer, recipes and audition are SnipSnap work.

The [upstream MIT notice](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/LICENSE) applies to the adapted data:

> Copyright (c) 2026 XO_OX Designs
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.
