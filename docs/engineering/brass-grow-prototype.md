# BRASS GROW experimental prototype

BRASS GROW brings the listening study's rough → centered → expressive idea into
SnipSnap's existing render-to-pad workflow. It is an opt-in EMBOUCHURE BRASS
model, not a replacement for existing saved sounds. The published three-take
[audition](https://snipsnap-brass-grow.bertcalm.chatgpt.site) remains the sound
reference. Its C4 result received a positive listening response; the new
single-source interpolation and wider register still need playing on a device.
The original concept came from the
[OTTONI synthesis guide](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/ottoni_synthesis_guide.md).

## Playing the prototype

The experimental debug build installs separately as **SnipSnap Brass GROW**
(`com.snipsnap.app.brassgrow`), with its own files and preferences. It does not
update an installed SnipSnap build. Use the normal import/export workflow to
bring kits into this test app.

In MAKE → SYNTH, choose EMBOUCHURE → BRASS, then **Brass GROW** or
**Brass GROW Held**. GROW moves from Rough at 0 through Centered at 0.5 to
Expressive at 1. TUNE selects pitch, LENGTH shapes the phrase, and HOLD's top
step selects held material without its attack. SYNTH and ordinary drum pads
play one loop-length sample per trigger, following the existing host behavior;
LOOP classification does not add infinite repeat or an attack-plus-sustain
keygroup instrument. Every edit renders and
retriggers a new take, following SYNTH's normal behavior. This is not continuous
GROW automation while an already rendered pad is playing.

SAVE AS PRESET keeps the explicit render model and GROW position. SEND TO PAD
stores the same recipe alongside the rendered sample; later regeneration and
velocity rendering retain that model. Edited GROW settings also survive leaving
and reopening SYNTH. Selecting a legacy BRASS preset restores its old controls.

## Source and saved-sound contract

Ordinary new EMBOUCHURE patches still default to model 2. Missing/null saved
model fields still select model 1. Both legacy renderers and their seven
controls remain unchanged. New GROW patches explicitly save model 3, require
the BRASS voice, and accept only TUNE/GROW/LENGTH/HOLD. The original eight
factory presets and sixteen-pad EMBOUCHURE starter kit remain unchanged.
The two GROW presets are deliberately **experimental**: they are listed by
`EmbouchurePresets.experimental()` and offered in the SYNTH picker, but they are
not part of the 36-preset EMBOUCHURE factory bank (`EmbouchurePresets.all()`),
its MPC banks or the starter kit.
Pre-prototype model-2 BRASS PCM16 fixtures supplement the existing model-1
golden recordings and default-sound compatibility checks.
Model 3 derives its pressure gesture from GROW; the legacy saved `gesture`
field does not override that score.

A GROW position interpolates physical effort, contact, body and grain settings,
and pressure scores before one nonlinear contact/waveguide path. It does not
mix separate rendered takes. The note seed stays fixed across GROW. Finite
notes retain the previous audition's disclosed performance treatment, applied
once to the one source. Held notes extend the actively played finite-calibration
source, then capture its interior phrase with a measured continuation blend.
Held-source texture comes from the pressure/contact behavior; there is no added
held-note output vibrato, EQ or oscillator stack. These edited held captures
are not claims of exact physical-state periodicity. Generation and capture
both honor cancellation from a replacement preview request.

Low, softly played Rough notes need less source grain to keep the intended
fundamental. Model 3 reduces GRAIN by at most 0.30 in that region (Rough 0.75
becomes 0.45), fading the correction from MIDI 57 to C4 and velocity 0.6 to
0.75, weighted by the Rough part of GROW. The pressure score, body and contact
controls remain unchanged. C4 and higher, velocity 0.75 and higher, and
Centered/Expressive receive no correction. This is source voicing, not output
pitch correction or replacement of the rendered fundamental.

## Reproduction and verification

From this branch, with Java 17 or newer:

```sh
./gradlew :synth:test --tests '*Embouchure*' --tests '*BrassGrow*'
./gradlew :synth:generateBrassGrowPrototype
```

The generator writes an ignored `testkit/brass-grow-prototype/` folder with
raw/matched WAVs, recipes, checks and source provenance. Its matrix covers
C3/C4/C5, velocities 0.3/0.9, five GROW points and both finite and held material
(60 configurations). TUNE is zero in these explicit-MIDI checks. Raw-state,
pitch, passive-release, continuation-join and timing checks are collected from
the same renders. It records every failure and returns a failing exit status
when a numerical acceptance gate fails. No numeric gate establishes convincing
brass tone or replaces the device listening pass.

The 2026-10-17 run passed all 60 production configurations and verified all
120 WAV exports. Pitch error stayed below 8.43 cents across the measured
source/capture windows. All 30 held captures passed independent PCM boundary
checks; their weakest measured continuation correlation was 0.953. The
low/soft source correction left the other 56 configurations exactly unchanged
in their float buffers and 112 exported WAVs. An additional 144 source-only
cases covered MIDI 48–59 at host velocities 1/3 and 2/3; these passed without
replacing the production capture checks. Legacy PCM fixtures, preset saving,
regeneration, velocity and kit export checks also passed.

The slowest held render in that run took 0.45 seconds on the build host.
Phone render time and audio quality still need device measurement.

Android builds use the existing SDK 35, NDK 27.2.12479018 and CMake 3.22.1
configuration:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

Both APKs assembled successfully after the final persistence fix. The debug
app includes all four supported Android ABIs. Instrumentation tests compiled
but were not executed because no device or emulator was connected.

An Android device is needed to validate touch interaction, preview latency,
pad gating and perceived sound. The prototype should remain on its experimental
branch until that listening and playing pass is complete.
