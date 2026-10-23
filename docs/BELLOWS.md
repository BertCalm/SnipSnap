# BELLOWS

BELLOWS adapts XOleg's Sacred Bellows into SnipSnap's dry, deterministic
offline synth pipeline. Four oscillator-based instrument approximations
share a saved pressure gesture. The owner has accepted the four voice
identities and audible parameter differences in the dry audition. Playable phrasing and phone/MPC hardware
checks are the next listening gate.

The source reference is [XOleg's engine implementation](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Source/Engines/Oleg/OlegEngine.h).
Chamber and buzz concepts carry their MIT attribution in `Bellows.kt`.
These are original instrument-inspired voices, not acoustic reconstructions.

## Body and breath range

Renderer model 2 gives BODY a true bypass at zero and a much stronger enclosed
response at full. PRESSURE spans rounded, gently excited reeds through richer,
sharper reed harmonics and a wider filter opening. These changes happen before
the shared loudness pass, so matched comparisons retain their tone differences.
The per-voice defaults remain reference points within the expanded mappings.

Model-1 recipes use the frozen `BellowsV1` renderer, retaining the first
audition's sound. New patches and factory presets use model 2. A JSON recipe
without a renderer-model field continues to load as model 1.

## Voices and controls

| Voice | Identity | Family controls |
|---|---|---|
| CHAMBER | Detuned free-reed-like ranks through an absorbed short chamber | REGISTER, BEAT |
| COUNTER | Dark pull and bright push colors at the same requested pitch | REGISTER, DIRECTION |
| FOLK | Fast asymmetric reeds, rank beating and pressure-linked saturation | REGISTER, BEAT |
| WHEEL | Wheel-like melody, tracking octave/fifth bed and pressure-gated bridge chatter | DRONE, BUZZ |

All voices also expose TUNE, PRESSURE, BODY, MOTION and HOLD. TUNE spans
C3-C5 in semitones. PRESSURE changes harmonic response and brightness as
well as source work; BODY changes chamber/formant color; MOTION changes
the pressure contour and pitch/wheel movement. HOLD extends a finite
gesture below its final step; its final step selects settled loop material.

COUNTER's DIRECTION blends contrasting push/pull timbres independently of
velocity. It does not implement the different note maps of an acoustic
bisonoric keyboard. Velocity changes pressure, harmonic response and level.
STEADY, SWELL, ACCENT and PULSE are explicit saved gestures. The WHEEL
buzz is a smooth pressure threshold response around 0.52, rather than a
physical model of wheel acceleration or bridge mechanics.

## Rendering and saved recipes

Each render starts with fresh oscillator phases and filter stores derived
from a deterministic engine/voice/note seed. Sources run at 4x, through
band-limited oscillators, source nonlinearities, chamber/body filtering,
DC conditioning and anti-alias filtering before shared decimation to
44.1 kHz mono. A fixed 0.125 source-reference gain leaves PCM headroom for
raw comparison files without per-patch limiting. The production path uses the
shared melodic level reference
and retains velocity dynamics. `renderRaw` exposes the conditioned source
before that level matching for audition comparisons.

`BellowsPatch` stores all macros, base MIDI, velocity, gesture and renderer
model. TUNE offsets the saved base MIDI; a played note outside C3-C5 is
refused. Patches, pad recipes, saved presets, CLI, synth picker and the dry
sixteen-pad starter kit use the same renderer.

## HOLD and drones

HOLD exports the settled repeating region without the initial bellows
catch. Oscillator, detune and movement cycles fit a roughly 2.8-second
period, with warmed filter stores. Root pitch fitting stays very small;
beat rates and drone pitches can differ slightly from finite counterparts.
The render uses wrapped conditioning rather than a seam fade.

Loop material in an ordinary drum pad plays once. The audition's Repeat
control repeats it. The separate `BellowsKeys` adapter retains a real onset
before a periodic sustain region, with a nonzero loop marker. A phone/MPC
keygroup loops that region while held and applies its release after note-off.
This adds no live pressure/aftertouch path.

`BellowsPadMaker` creates nine pitch zones across C3–C5, each with soft,
medium and hard source timbres. Its velocity windows are 1–42, 43–84 and
85–127; the host supplies velocity gain once. Native-velocity standalone
notes and host-velocity sample layers have separate renderer entry points.
The phone's version-2 instrument sidecar records all layers; version-1
sidecars remain readable. Model-1 BELLOWS recipes keep their frozen sound
and are refused by the new keygroup adapter rather than silently migrated.

In the phone's BELLOWS synth, MAKE INSTRUMENT renders the current model-2
sound into the instrument shelf. It preserves the saved gesture, sets
absolute keyboard pitches independently of TUNE, and substitutes attack plus
sustain for finite HOLD. Rendering runs off the UI thread with progress and
cancellation; RELEASE remains an instrument control.

The MAKE INSTRUMENT preview plays a six-second C4 hold followed by the chosen
release through the JVM sample-player reference. It uses the same layer gain and
sustain-loop rules as the instrument, so RELEASE changes the audible tail.
Native-synthesis musical demos remain available separately from the exported
sample-player comparisons. See [the playtest guide](BELLOWS_PLAYTEST.md) for
reproduction and the remaining phone/MPC checks.

WHEEL's embedded drones track the rendered melody note. They transpose
with its sample and retrigger on each hit. A fixed tonic under independent
melody notes is a separate companion instrument. WHEEL melody keygroups
therefore disable embedded DRONE. The downloadable companion maps a WHEEL
tonic plus its octave/fifth bed to C3 only; play it on its own track beneath
the melody. It does not share a global bellows reservoir across keys.

## Preset library

The factory library has 24 dry recipes, six per family. The original eight
remain first in the global roster, with their names, settings and per-family
indices unchanged. All recipes explicitly use renderer model 2, a C4 audition
note and a finite gesture; the playable instrument path can sustain their
colors separately. Every preset stores its full macro map and player gesture.

| Family | Preset | Playing role |
|---|---|---|
| CHAMBER | Amber Breath | Warm swell through shaded ranks |
| CHAMBER | Silver Ranks | Bright steady ranks with beating |
| CHAMBER | Bare Reed | Soft direct reeds without rank beating |
| CHAMBER | Chest Tone | Deep enclosed steady note |
| CHAMBER | Reed Tap | Bright concise accent |
| CHAMBER | Slow Bloom | Long breathing swell with moving ranks |
| COUNTER | Midnight Pull | Dark pull swell |
| COUNTER | Forward Push | Bright push accent |
| COUNTER | Paper Reed | Soft direct pull shade |
| COUNTER | Round Push | Warm enclosed push color |
| COUNTER | Reed Knock | Short accent with blended push/pull color |
| COUNTER | Reed Tread | Long pulsed phrase with a fixed timbre blend |
| FOLK | Village Stab | Sharp short accent |
| FOLK | Dancing Reeds | Beating pulsed phrase |
| FOLK | Soft Grain | Soft rounded steady reeds |
| FOLK | Wooden Chest | Deep enclosed folk tone |
| FOLK | Reed Reply | Light dry articulated answer |
| FOLK | Harvest Air | Long gently moving swell |
| WHEEL | Quiet Wheel | Quiet sustained tracking drone |
| WHEEL | Wrist Sparks | Bright pulsing bridge chatter |
| WHEEL | Solo Thread | Clear melody with drones and buzz disabled |
| WHEEL | Hollow Bow | Deep enclosed note with a restrained drone |
| WHEEL | Fifth Breath | Long swell over a tracking octave/fifth bed |
| WHEEL | Bridge Tap | Short pressure-triggered buzz accent |

The starter kit deliberately remains sixteen pads: eight CHAMBER notes in
C minor pentatonic followed by the original eight contrast presets. Library
expansion does not alter its accepted pad order or add extra pads.

## Library and listening

Run `./gradlew :synth:generateBellowsAudition` to generate the listening
page, raw/matched WAVs, saved patches, first-listen ZIP and measured
manifest under `testkit/bellows-audition/`.

The library contains 24 dry model-2 presets, six per family. The original
eight remain Amber Breath, Silver Ranks, Midnight Pull, Forward Push,
Village Stab, Dancing Reeds, Quiet Wheel and Wrist Sparks, with their saved
recipes and ordering preserved. The sixteen-pad starter keeps its original
layout. Library previews and phrase/chord demonstrations sit alongside the
original listening collections.

The Body & breath view gives each voice immediate true 0/full endpoint
clips and a joined A-to-B comparison, with quarter-step sweeps available under
More steps. Those comparisons fix the playing gesture and disable motion,
beating, drones and buzz where present. Matched playback removes a simple level
advantage; raw playback preserves the source dynamics. The other collections
isolate controls, gestures, pitch, velocity, threshold behavior and repeated
held material. Playback starts only on request.

The [private audition](https://bellows-snipsnap-audition.bertcalm.chatgpt.site)
contains the expanded listening session and the phone/MPC download.
Numerical checks cover reproducibility, supported pitch, useful controls,
velocity timbre, finite tails, loop wraps and recipe/export behavior. They
support the owner's listening review; they do not establish hardware
acceptance. See [the playtest guide](BELLOWS_PLAYTEST.md) for the remaining
checks.

Generate playable exports with `./gradlew :cli:generateBellowsInstruments`,
or run `snipsnap bellows-keys --out <folder>`. The output contains four
three-layer instruments and a separate one-key tonic companion, in the
existing `.xty` / `.xpm` / WAV / phone-sidecar package layout. The ZIP is
`Bellows-phone-mpc-m2.zip`; the adjacent `playable/` folder retains its
recipes, manifest and checklist. The published page offers a smaller ZIP
per instrument, each with its own source recipes, checksums and guide.
