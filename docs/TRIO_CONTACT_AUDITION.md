# TRIO approved contact profile

The characteristic gesture is the accepted strum and single companion strike,
followed by upper-TOUCH contact color and a natural finite ring. The owner heard
a difference in the contact experiment on 2026-10-30, preferred it on 2026-11-01,
and authorized promotion. This is an update to the existing TRIO engine.

## Synthesis and compatibility

New patches and factory recipes select model 7. Models 1–6 keep their renderers;
saved edits and velocity variants retain their selected model. Missing or null
model fields still decode as model 1. The shared JSON schema remains version 1.
The sixteen factory WAVs stay exact because their TOUCH values are at or below .8.
Solo voices and DRAMA 0 retain the accepted original sound.

TOUCH above .8 changes each fresh force before its string loop:
`force - alpha * delayed(force)`. The delay is rounded from one third of the
course's physical period, using the actual frequency rather than delay-buffer
length. Alpha rises from 0 at TOUCH .8 to .8 at 1. The complete delayed tail is
retained. Its feedforward gain lies between .2 and 1.8; tuning, feedback paths,
event timing, course gains, strums and one hit per companion remain fixed.
PHIN receives the same profile. This is a contact-color analogy, not a calibrated
finger model. No monitor gain or audition matching enters production rendering.

At DRAMA .5 and 1, model 7 exactly reproduces the accepted experiment. Below .5,
alpha is multiplied by `smoothstep(clamp(2 * DRAMA, 0, 1))`. This avoids adding a sudden
change in contact color just above zero while keeping both approved listening groups
exact. The low-DRAMA easing is a continuity choice, not a separate listening verdict.
TOUCH at/below .8 bypasses exactly in every scene.

The six macros, root/velocity/phrase controls, mono 44.1 kHz output, 4× internal
rate, 3.5-second recording and existing final fades remain the instrument contract.
The existing bounded score cache contains immutable descriptors and keys on the
complete configuration, including model; force buffers and string states are fresh.

## Listening and verification

`./gradlew :synth:generateTrioContactAudition` rebuilds Previous/Approved contact
pairs at TOUCH .8/.9/1, DRAMA .5/1, a previous full-range reference and Tres taps.
Full notes retain 3.5 seconds; quick sweeps use 1.5-second excerpts, 12 ms fades
and .2-second gaps. Matched uses the loudest-200-ms proxy at .03, not LUFS.
Release scenes save ordinary model-7 recipes. The frozen test-only model-6
profile remains an independent reference and the previous hosted experiment is
preserved separately. Source/asset hashes identify each package.

Verified: version-gated routing and unchanged score, seed and output laws.
Release tests cover exact accepted-scene samples/taps/events, published model-6
PCM24 hashes, older historical and factory canaries, model-7 native recipes,
cache isolation, continuous near-zero DRAMA and bounded register corners.
Completed build and package results belong in the release evidence; an interrupted
older full JVM run has no successful exit result.
Measured: the original experiment's gain-fitted adjacent waveform residuals at
DRAMA 1 were .501/.404 in the mix and .399/.338 for Tres. These establish contrast
beyond gain and do not replace listening.
Heard: the owner prefers the contact experiment over Previous. Physical phone and
MPC playback remain separate hardware acceptance checks.

## Why it is built this way

- Shaping the fresh force changes the strike's low/mid character before the strings ring.
- Keeping the original score preserves the accepted strums and single companion contacts.
- The zero-DRAMA easing gives the two controls a continuous meeting point.
- A new saved sound version preserves old recipes while new patches use the approved profile.
- Independent frozen audio and source references keep release fidelity reviewable.
