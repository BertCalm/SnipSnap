# Osprey

OSPREY adapts the [XOsprey architecture blueprint](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/xosprey_architecture_blueprint.md)
into a reproducible SnipSnap sample generator. One finite sea gesture excites
pitched resonators; scheduled creature forms answer its activity. Changing SHORE
morphs the resonator, creature and water profiles together.

The original sound received positive feedback, with a request for the voices to
be a little more distinct. The owner rejected R2 as harsh and for losing the
original character. Model 3 returns to the original sea, calls, envelopes and
defaults, making only a small adjustment to the balance of existing modal bodies.
After hearing the [private R1/R3 audition](https://snipsnap-osprey-audition.bertcalm.chatgpt.site),
the owner said, “We are in a better spot audio wise.” R3 is the working audio
baseline. On 2026-10-18 the owner confirmed, “Sounds good in app, let's proceed.”
This confirms in-app listening. Save/reopen, recipe replay and MPC playback
remain open; the build and device identifiers were not supplied. The phone
picker, CLI, fifteen dry presets and sixteen-pad starter
use the versioned renderer. `OspreyVoice.SWELL` is the finite
articulation; CHOP, a dedicated CALL voice and certified repeating HOLD are later
work.

## Controls

All six macros use 0–1. The musical note is a separate recipe field.

| Control | Behavior |
| --- | --- |
| SHORE | Atlantic → Nordic → Mediterranean → Pacific → Southern; continuously morphs adjacent resonator, creature and water profiles. |
| SEA | Changes the original water activity and turbulent forcing through calm, rough and storm settings. |
| MOTION | Changes the pace and density of wave movement and creature activity. |
| DEPTH | Darkens upper-mode radiation, extends sympathetic decay and raises creature presence; zero mutes the creature branch. |
| SPACE | Adds harbor distance and diffusion; zero is exactly the dry source. |
| HOLD | Extends the finite excitation gesture within the requested duration. Every setting still renders a one-shot. |

Pure shores lie at SHORE 0, 0.25, 0.5, 0.75 and 1; halfway coastlines lie at
0.125, 0.375, 0.625 and 0.875. The engine interpolates profiles within a render.
SHORE stays fixed during each render; editing it generates a new sample.

## Sound and timeline

The signal path is seeded water activity → original modal excitation → primary
and sympathetic resonators → scheduled creature forms → dry mix → SPACE.
The original initiating contact, warm pitched pressure, modal frequencies and
decays are retained in model 3. Creature forms answer water activity, and their
events and envelopes are unchanged from model 1 for the same recipe. The terminal
tail lets modal energy decay.

Model 3 gently shades the radiation of the three existing bodies per coast.
It adds no new exciter or articulation and leaves their shared fundamental
balance anchored to the original. Its body accents favor guitarra in Atlantic,
langspil in Nordic, oud in Mediterranean, singing bowl in Pacific and cavaquinho
in Southern, while softening higher bands. These are spectral inspirations from
the original tables, not authentic instrument emulations. Creature names likewise
identify invented formant forms rather than measured biological models.

Diagnostic taps expose ocean excitation, primary resonators, sympathetic
resonators and creatures. Primary + sympathy + creatures reconstruct the dry
source. The ocean tap monitors the shared exciter; it is not added as a fourth
audible layer. SPACE 0 returns that dry source directly. Ocean and creature taps
are preserved from model 1; only primary-body radiation changes in model 3.

## Saved recipe and playback

`OspreyPatch` stores `OSPREY`, `SWELL`, patch `version`, name, macros and:

| Field | Contract |
| --- | --- |
| `midi` | Integer 36–84, default 60. |
| `velocity` | Finite 0–1 excitation velocity, default 1. |
| `seed` | Saved integer variation seed, default 0. |
| `durationSeconds` | Finite 0.25–12 seconds, default 8. |
| `modelVersion` | New patches default to 3; saved models 1, 2 and 3 are supported. |

`OspreyV1` preserves the published model-1 math and original defaults.
`OspreyV2` preserves R2 recipes even though that sound direction was rejected.
JSON with missing or null `modelVersion` still selects 1. Editing a saved recipe
retains its model; changing the model is explicit. The CLI accepts `--model 1|2|3`;
the common patch `version` remains 1. Model 3 uses the original model-1 defaults.

Independent renders start with fresh random, modal and spatial state. The same
complete recipe repeats the sample and events. Pads do not share a live ocean.
Output is stereo at 44.1 kHz with the requested finite frame count.

Repitching a WAV changes wave and creature timing together with pitch. HOLD
provides no periodic state or seamless-loop contract; a sustained key cannot
continue an unrendered sea. SnipSnap follows patch → sample → pad playback.

## Sixteen-pad handoff

Keep R3 fixed while trying it as a kit. From the repository root:

```sh
./gradlew --no-daemon :shell:generateOspreyHandoff
```

The task writes `testkit/osprey-handoff/osprey-r3-kit.zip` from the existing
OSPREY starter: fifteen coast × sea presets followed by
**BETWEEN SHORES**. Every pad keeps its model-3 recipe, C4 (MIDI 60), velocity 1,
seed 731 and eight-second finite render. SPACE is zero. These are raw engine
renders; the audition's matched comparison gains are not applied to the kit.
The archive has a single `SnipSnap Osprey R3/` root:

| Path | Contents |
| --- | --- |
| `mpc/` | `SnipSnap Osprey R3.xtd` and its `SnipSnap Osprey R3_[TrackData]/` folder. The compatibility XPM and editable SnipSnap `kit.json` inside that folder share the same sixteen raw PCM24 WAVs. |
| `recipes/` | `A01.json` through `A16.json`, preserving each complete recipe. |
| `PAD-MAP.txt`, `README.txt` | Pad order and loading instructions. |
| `manifest.json`, `validation.json` | File hashes, recipe identity and artifact checks. |

The shared `[TrackData]` folder containing `kit.json` is an editable kit for
`KitStore` and desktop tooling. Its sample filenames are mapped to the native
export filenames; recipes, pad settings and all WAV bytes are unchanged. On a
phone build containing these OSPREY changes, choose **NEW KIT ▸ STARTERS →
OSPREY**, or preview OSPREY in SYNTH and send it to a pad. Android's XPM ZIP
import loads playable samples but does not load these saved recipes. Phone
save/reopen, recipe replay and MPC loading remain unverified. In-app listening
has been confirmed by the owner.

The adjacent `handoff-manifest.json` identifies the complete ZIP by SHA-256 and
byte count. Checks cover export read-back, recipe regeneration and comparison
with historical R3 raw audio where an identical published patch exists. They do
not establish hardware playback. HOLD remains finite even on **BETWEEN SHORES**.

Follow the [MPC listening pass](OSPREY_MPC_PASS.md) to load the unchanged published
kit on the Live III. The published ZIP and its build-time validation manifest
remain unchanged; the new listening report does not rewrite artifact provenance.

The remaining workflow checks are:

1. In SYNTH, select OSPREY, preview a preset and send it to a pad. Compare its
   onset, water movement and creature-call timing with the preview.
2. Save and reopen the kit, reload the pad's recipe, and render it again. Check
   that model 3, note, velocity, seed, duration and macros survive, and that
   the resulting gesture is unchanged.
3. Export the kit, then load the native `.xtd` and compatibility `.xpm` on the
   Live III with their sample folders intact. Compare pad order, stereo image,
   pitch, water and call timing, and the complete one-shot endings.
4. At the highest comfortable listening volume, check single hits and overlaps
   for harshness, clicks or unexpectedly cut tails. A held key does not turn
   these finite samples into loops.

Record the phone/build and MPC firmware, any loading error verbatim, and the
pad that differs in [the Osprey bench entry](BENCH.md#b8--osprey-r3-handoff).
Android SDK and browser visual checks were unavailable in the build workspace;
source and artifact checks do not replace the phone or hardware session.

## Audition and checks

Run from the repository root:

```sh
./gradlew --no-daemon :synth:generateOspreyGentleAudition
./gradlew --no-daemon :synth:test
```

`OspreyGentleAuditionGenerator` verifies the preserved published model-1
library in `testkit/osprey-audition/` and writes separately to
`testkit/osprey-gentle-audition/`. Eleven full R1/R3 pairs compare all five
rough shores and calm/storm Atlantic, Nordic and Mediterranean. Each pair keeps
C4, velocity 1, seed 731, eight seconds and the original macros unchanged.
The water forcing, calls and timing also remain the same; the comparison concerns
the small body-balance adjustment. Two summary pairs use identical 2.4-second
excerpts of those full gestures. The task includes five DEPTH-0 body pairs:
creatures are muted, but modal radiation and decay also change, so these are full
diagnostic renders rather than isolated taps. There are 18 paired cards,
including the two excerpt lineups. The listening page begins with audio off.

Full cards include matched/raw 16-bit stereo WAVs and saved patch JSON. Each
A/B pair shares an achievable loudest-200-ms stereo RMS target of at most 0.12,
with peaks at most 0.90. Original raw audio and patches are copied with verified
hashes; matched R1 playback may get a separate lower-target copy. Raw retains
engine amplitude. Summary excerpts inherit each full clip's gain and record
their additional lineup gain; montages have no single saved patch.

The original `:synth:generateOspreyAudition` task stays explicitly on model 1.
Its 46-card shore, weather, morph, anatomy and control study remains accessible
as **Original study**. A new R1 render has new provenance; the gentle generator
requires the preserved published baseline. Original anatomy taps keep their
complete dry mix's gain and their patches regenerate the full source.
The separate `:synth:generateOspreyRevisionAudition` task retains the rejected
R1/R2 comparison with model 2 selected explicitly; R2 is not the current sound.

Manifests record recipes, source provenance, file hashes, amplitude measurements,
call events and decay diagnostics. Tests pin model-1 sample bits and model-2
compatibility, then check model-3 repeatability, preservation of the original
scene, continuous morphs, tap reconstruction and finite bounded output.
Numerical checks do not establish a listening verdict. The owner's positive
R3 feedback establishes the working baseline and confirms in-app listening.
Save/reopen, recipe replay and MPC playback acceptance remain open.

## A dry musical sketch

[Between shores](OSPREY_FIELD_SKETCH.md) arranges twelve complete hits from the
published R3 kit into a sixteen-bar field sketch at 90 BPM, followed by three
bars for the tails. The downloadable MIDI uses the exported pad notes. The
reference mix adds no FX or pitch changes; the engine and kit stay unchanged.
This listening context can proceed alongside the remaining device checks.

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
