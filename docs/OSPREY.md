# Osprey

OSPREY adapts the [XOsprey architecture blueprint](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/xosprey_architecture_blueprint.md)
into a reproducible SnipSnap sample generator. One finite sea gesture excites
pitched resonators; scheduled creature forms answer its activity. Changing SHORE
morphs the resonator, creature and water profiles together.

The initial voice is `OspreyVoice.SWELL`. The published
[private audition](https://snipsnap-osprey-audition.bertcalm.chatgpt.site)
contains 46 actual-engine comparisons. Sonic acceptance remains open.
The phone synth picker, CLI, saved recipes and a dry sixteen-pad starter kit
share this renderer. CHOP, a dedicated CALL voice and certified repeating HOLD
are later work.

## Controls

All six macros use the range 0–1. The requested musical note is a separate recipe
field rather than another macro.

| Control | Behavior |
| --- | --- |
| SHORE | Atlantic → Nordic → Mediterranean → Pacific → Southern; interpolates adjacent numerical profiles continuously. |
| SEA | Raises turbulence and rough excitation, changing the resonators' timbre and the activity available to calls. Calm settings retain pitched excitation. |
| MOTION | Changes the pace and density of wave movement and creature activity. |
| DEPTH | Darkens upper-mode radiation, extends sympathetic decay and raises creature presence; zero mutes the creature branch. |
| SPACE | Adds harbor distance and diffusion; zero is exactly the dry source. |
| HOLD | Extends the finite excitation gesture within the requested duration. Every setting still renders a one-shot. |

Pure shores lie at SHORE 0, 0.25, 0.5, 0.75 and 1. The four halfway coastlines
lie at 0.125, 0.375, 0.625 and 0.875. These are interpolated engine profiles,
not crossfades between rendered endpoint WAVs. SHORE is fixed during each render;
editing it generates a new sample.

## Sound and timeline

The signal path is seeded excitation → coherent swell/chop/turbulence → primary
and sympathetic modal resonators → scheduled creature forms → dry mix → SPACE.
The water history drives both the resonators and activity-sensitive call
scheduling. Call envelopes and event times fit the finite gesture, while the
terminal tail lets the modal energy decay.
Shore wave periods are compressed to sample-scale movement in this proof.

Each primary instrument family anchors its first mode to the requested note.
The remaining musical modal ratios and shore spectral colors create the
coastline character. Instrument names are synthesis inspiration, and creature
names identify invented formant forms. These are neither sampled instruments
nor measured biological models.

Diagnostic taps expose ocean excitation, primary resonators, sympathetic
resonators and creatures. Primary + sympathy + creatures reconstruct the dry
source. The ocean tap monitors the shared exciter; it is not added as a fourth
audible layer. SPACE 0 returns that dry source directly.

## Saved recipe and playback

`OspreyPatch` stores engine `OSPREY`, voice `SWELL`, the common patch `version`,
name and macros, plus these render fields:

| Field | Contract |
| --- | --- |
| `midi` | Integer 36–84, default 60. |
| `velocity` | Finite 0–1 excitation velocity, default 1. |
| `seed` | Saved integer variation seed, default 0. |
| `durationSeconds` | Finite 0.25–12 seconds, default 8. |
| `modelVersion` | Render model 1; unsupported models are rejected. |

Every independent render starts with fresh deterministic random, modal and
spatial state. The same complete recipe regenerates the same sample and event
schedule. Notes do not share a continuously running ocean across pads. Output
is interleaved stereo at 44.1 kHz with the requested finite frame count.

```json
{
  "engine": "OSPREY", "version": 1, "name": "ATLANTIC CALM",
  "voice": "SWELL", "modelVersion": 1,
  "midi": 60, "velocity": 1, "seed": 731, "durationSeconds": 8,
  "macros": {
    "SHORE": 0, "SEA": 0.1, "MOTION": 0.35,
    "DEPTH": 0.35, "SPACE": 0, "HOLD": 0
  }
}
```

Sample playback preserves the rendered history. Repitching a WAV changes wave
and creature timing together with pitch. HOLD currently provides no periodic
state contract or seamless-loop claim; sustaining a key cannot continue an
unrendered sea. The adaptation targets SnipSnap's patch → sample → pad workflow.

## Audition and checks

Run from the repository root:

```sh
./gradlew --no-daemon :synth:generateOspreyAudition
./gradlew --no-daemon :synth:test
```

`OspreyAuditionGenerator` writes engine-rendered assets into
`testkit/osprey-audition/`. The separate audition page consumes `manifest.json`;
the generator preserves the page. The initial matrix contains all five dry
shores at calm, rough and storm SEA, the four rough-sea midpoints, a complete
dry sea and its four component taps, three-point MOTION/DEPTH/SPACE comparisons,
C3/C4/C5, creature-presence comparisons, finite HOLD and velocity comparisons.
The baseline uses seed 731 and eight-second renders. The listening page begins
with audio off.

Each card has matched and raw 16-bit stereo WAVs plus a saved patch JSON.
Matched full mixes target the loudest 200 ms stereo RMS at 0.12, subject to a
0.90 peak ceiling. Anatomy taps inherit the complete dry mix's gain so their
actual balance remains audible. Raw files retain the engine's amplitude; use
them to judge velocity response. A tap's saved patch regenerates the complete
source; its manifest `component` selects the diagnostic audio.

The manifest records recipes, source provenance, SHA-256 file hashes, amplitude
measurements, call events and decay/recovery diagnostics. Automated checks cover
repeatability, root tuning and transposition, continuous shore boundaries,
audible macro and shore differences, dry/tap reconstruction, finite decay and
bounded output. These checks establish the render contract; listening decides
whether the coastlines and creature responses belong to one convincing sea.

## Attribution

The shore instrument, creature and fluid profile data in `OspreyShore.kt` is
adapted from XO_OX Designs' [ShoreSystem.h](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Source/DSP/ShoreSystem/ShoreSystem.h),
copyright © 2026 XO_OX Designs, under the MIT license. The source attribution
and SPDX identifier are retained in the port. OSPREY's finite gesture renderer,
recipe contract and audition generator are SnipSnap adaptations.

The [upstream MIT notice](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/LICENSE)
applies to the adapted profile data:

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
