# BELLOWS

BELLOWS adapts XOleg's Sacred Bellows into SnipSnap's dry, deterministic
offline synth pipeline. Four oscillator-based instrument approximations
share a saved pressure gesture. This is a first listening prototype; the
owner's dry sonic verdict and phone/MPC checks remain open.

The source reference is [XOleg's engine implementation](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Source/Engines/Oleg/OlegEngine.h).
Chamber and buzz concepts carry their MIT attribution in `Bellows.kt`.
These are original instrument-inspired voices, not acoustic reconstructions.

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
raw comparison files without per-patch limiting. The production path uses the shared melodic level reference
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
control repeats it; a playable attack plus indefinite sustain requires a
keygroup adapter. This prototype adds no new live pressure/aftertouch path
or phone KEYS velocity-layer switching.

WHEEL's embedded drones track the rendered melody note. They transpose
with its sample and retrigger on each hit. A fixed tonic under independent
melody notes should be a separate drone bed; this prototype does not claim
a global drone or bellows reservoir shared across keys.

## First listening session

Run `./gradlew :synth:generateBellowsAudition` to generate the listening
page, raw/matched WAVs, saved patches, first-listen ZIP and measured
manifest under `testkit/bellows-audition/`.

The eight starting sounds are Amber Breath, Silver Ranks, Midnight Pull,
Forward Push, Village Stab, Dancing Reeds, Quiet Wheel and Wrist Sparks.
The comparison set isolates controls, gestures, pitch, velocity, threshold
behavior and repeated held material. Playback starts only on request.

The [private audition](https://bellows-snipsnap-audition.bertcalm.chatgpt.site)
is the listening gate. Numerical checks cover reproducibility, supported
pitch, useful controls, velocity timbre, finite tails, loop wraps and
recipe/export behavior; they do not establish sonic acceptance.
