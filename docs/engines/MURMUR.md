# Murmur in SnipSnap

Murmur is an original pitched FM/formant engine with six musical roles: BASS,
KEY, PLUCK, LEAD, CHOIR and METAL. This implementation follows the supplied
[engineering proposal](MURMUR_SPEC.md). The twelve dry presets are first-listen
proposals. Their sonic identity needs owner review before expanding to 36.

The panel exposes MOUTH, EDGE, BREATH, MOTION, BODY and HOLD. Requested root
(MIDI 36–96) and excitation velocity (0–1) are separate saved patch fields.
The Android panel includes ROOT and VELOCITY controls; the CLI accepts the same
fields. Zero velocity is silent. Normal output uses the shared melodic loudness
target and retains playing dynamics after excitation changes.

## Synthesis and saved identity

Each offline mono render creates a fresh voice. Four operators use phase
modulation with indices in radians and acyclic routing; no feedback is enabled.
The band-limited voiced excitation and noise feed five TPT bandpasses, whose
output is mixed with a direct tonal anchor. MOUTH interpolates original spectral
targets in logarithmic frequency and bandwidth. BREATH and finite onset noise
drive those same bands. BODY changes
direct support, lower-band width and upper damping. MOTION changes the mouth
trajectory and its source balance, rather than applying output tremolo.

The internal sample rate is 176.4 kHz; standard decimation produces 44.1 kHz
audio. Index is reduced with increasing operator frequency, bandwidth and Q are
bounded, coefficients interpolate at the internal sample rate, and DC is removed.
High notes have fewer harmonics under the formants, so vowel realism is a listening
limitation even when the requested musical root remains intact.

`MurmurPatch` saves the routing, four ratios, spectral-target roster, trajectory,
direction, reference duration, amplitude-envelope values, macros, root and velocity.
JSON edits and `PadRecipe` replay retain all of them. Seeds are derived with
`Dsp.seedFor`; there is no shared state between pads. This is a resonant-bank
simplification, not an FS1R emulation, and uses no hardware assets or sampled vowels.

## HOLD and exports

HOLD below 0.99 is a finite gesture with a bounded sustain interval and release.
At 0.99 and above, the output is a settled sustain region without the original
onset. The loop planner searches long whole-carrier periods while keeping root
tuning accurate, adjusts subordinate ratios for compatibility, repeats seeded
noise, and locks mouth motion and control updates to the loop period. Continuous
preroll settles filter and source history. Loop probes compare independently
simulated adjacent periods using `Keys.seamError`.

`SynthKits.murmur()` supplies sixteen dry pads with regenerable recipes: a key
scale and eight contrasting pads covering all voices and one held choir. The MPC
drum program plays complete samples once, including the held buffer; the audition
page repeats that buffer with Web Audio for seam listening.

## Reproduce the evidence

```sh
./gradlew --no-daemon test
./gradlew :synth:generateMurmurAudition -PmurmurFull
./gradlew :synth:generateMurmurKit
./gradlew :cli:run --args='synth MURMUR BASS --all --midi 48 --velocity 0.7 --out /tmp/murmur-bass'
```

The audition task writes `testkit/murmur-audition/`: raw and level-matched WAVs,
recipes, measurements and an accessible listening page. Full mode covers all
voices at low/middle/high roots and velocities, five-point macro sweeps, the four
required interaction grids, all-high cases and held loops. Metrics are mechanical
evidence; useful dry identity and audible loop quality still require listening.

The kit task writes `testkit/SnipSnap Murmur Kit/`, including the native XPM,
sixteen mono 24-bit WAVs and the recipe-bearing `kit.json` sidecar. Generated audio
stays out of Git. Neither hardware reconstruction nor speech, text input,
automatic chords, stereo or live polyphony is included.
