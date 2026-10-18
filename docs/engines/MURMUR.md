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
drive those same bands. BODY moves from a clearer, thinner FM/formant balance to
pitched chest support in the second and third harmonics, broader low/mid formants
and warmer upper damping. Its filtering acts within the voice's useful spectrum;
shared leveling keeps the contrast from being only a volume change.

MOTION zero freezes the MOUTH-selected target. Low values make a small travelling
contour; higher values span most of the original target path, with MOUTH retaining
positioning influence. Keys and plucks articulate a longer attack-to-target journey,
while leads, choir and held notes travel through slower repeating shapes. A voiced
upper-harmonic exciter gives the bands energy to shape even at low EDGE. Motion
changes the formants and their voiced/breath balance, rather than applying output
tremolo. BODY and MOTION were strengthened after the first listening review found
their dry, level-matched effect too subtle.

The internal sample rate is 176.4 kHz; standard decimation produces 44.1 kHz
audio. Index is reduced with increasing operator frequency, bandwidth and Q are
bounded, coefficients interpolate at the internal sample rate, and DC is removed.
High notes have fewer harmonics under the formants, so vowel realism is a listening
limitation even when the requested musical root remains intact.

`MurmurPatch` saves the render model, routing, four ratios, spectral-target roster,
trajectory, direction, reference duration, amplitude-envelope values, macros, root
and velocity. New patches and presets use model 2, the six-voice identity revision.
Saved configurations without a `model` field decode as model 1 so earlier recipes
retain their original rendering behavior. JSON edits and `PadRecipe` replay retain
the selected model and complete configuration. Seeds are derived with `Dsp.seedFor`;
there is no shared state between pads. This is a resonant-bank simplification, not
an FS1R emulation, and uses no hardware assets or sampled vowels.

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

## Compare the six voices

The role names describe musical intent, which still needs listening review:

| Voice | Intended role |
|---|---|
| BASS | Rounded talking bass |
| KEY | Vocal color behind a key strike |
| PLUCK | Short articulated spectral gesture |
| LEAD | Nasal/open expressive tone |
| CHOIR | Restrained humming sustain |
| METAL | FM bite softened by mouth shape |

Begin with the six dry defaults at the same C4 root (MIDI 60) and velocity 1.
Each voice retains its own defaults; this comparison assesses the complete voice
recipe. Use matched level to compare timbre and articulation, then native level
to hear excitation energy. Listen through the full gesture, including the onset,
developing contour and release.

The explicit before/after cases compare model 1 with model 2 for every voice at
C4 and velocity 1. Review both complete finite gestures and settled HOLD buffers.
Repeat held material through several wraps to compare sustained character and
seams; those buffers omit the original onset, so they cannot establish attack
differences. The saved recipe identifies the model and settings behind each clip.
These comparisons support owner review; their presence and measured differences
do not establish useful audible separation.

The owner accepted the other five roles and requested a little less air in CHOIR.
Its provisional model-2 unvoiced-source gain is reduced from 4.8 to 3.3 (31.25%),
retaining the voiced source, spectral targets and BREATH mapping. Model 1 and the
other five model-2 voices retain their existing rendering behavior.

## Reproduce the evidence

```sh
./gradlew --no-daemon test
./gradlew :synth:generateMurmurAudition -PmurmurFull
./gradlew :synth:generateMurmurKit
./gradlew :cli:run --args='synth MURMUR BASS --all --midi 48 --velocity 0.7 --out /tmp/murmur-bass'
```

The audition task writes `testkit/murmur-audition/`: raw and level-matched WAVs,
recipes, measurements and an accessible listening page. The current case list
produces 219 cases and 438 WAVs in full mode, or 175 cases and 350 WAVs in compact
mode. Those totals include 24 explicit model 1/model 2 comparison cases: six voices,
two models, and both finite and settled held material. Counts describe generated
coverage, not acceptance limits; the generated manifest records the actual count,
source revision and clip settings.

The wider matrix covers all voices at low/middle/high roots and velocities,
five-point BODY and MOTION sweeps on every voice, KEY sweeps for MOUTH, EDGE and
BREATH, the four required interaction grids, all-high cases and held loops. Tests
also compare level-independent harmonic profiles and developing spectral contours
through useful note frames, rather than only checking endpoint sample differences.
Metrics are mechanical evidence; useful dry identity and audible loop quality still
require listening. The twelve presets remain provisional, and expansion to 36
awaits owner approval.

The kit task writes `testkit/SnipSnap Murmur Kit/`, including the native XPM,
sixteen mono 24-bit WAVs and the recipe-bearing `kit.json` sidecar. Generated audio
stays out of Git. Neither hardware reconstruction nor speech, text input,
automatic chords, stereo or live polyphony is included.
