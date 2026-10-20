# Murmur in SnipSnap

Murmur is an original pitched FM/formant engine with six musical roles: BASS,
KEY, PLUCK, LEAD, CHOIR and METAL. This implementation follows the supplied
[engineering proposal](MURMUR_SPEC.md). The owner approved all six dry voices after
the CHOIR air trim; the accepted DSP is frozen at `bb2b9c9a`. The factory bank now
contains 36 presets, including 24 additions that await owner listening.

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
drum program plays complete samples once, including the held buffer. Its A16
delivery recipe adds an 8 ms terminal fade to avoid a hard stop at the buffer's
non-zero final sample. SEND TO PAD applies the same saved fade to held Murmur
sources placed on one-shot pads. Gated pads and SURFACE retain the unfaded source.
The approved preset and raw HOLD loop remain unchanged; the audition page repeats
that seamless buffer with Web Audio for seam listening.

The phone's NEW KIT starter chooser scrolls so the complete roster, including
MURMUR, remains reachable at phone height. Automated Android checks cover this
reachability and the menu's outside-tap dismissal. Physical phone and MPC listening
remain a separate handoff check.

## Factory presets and voice comparisons

The audition page opens on the 36-preset bank with this voice allocation:

| Voice | Presets | Intended role |
|---|---:|---|
| BASS | 8 | Rounded talking bass |
| KEY | 4 | Vocal color behind a key strike |
| PLUCK | 4 | Short articulated spectral gesture |
| LEAD | 8 | Nasal/open expressive tone |
| CHOIR | 6 | Restrained humming sustain |
| METAL | 6 | FM bite softened by mouth shape |

The original twelve preset recipes remain, with 24 new recipes completing the bank.
The six-voice engine approval is recorded separately from preset review: the 24
additions still need owner listening before the entire bank can be called approved.

The six dry defaults remain available at the same C4 root (MIDI 60) and velocity 1.
Each voice retains its own defaults; this comparison assesses the complete voice
recipe. Use matched level to compare timbre and articulation, then native level
to hear excitation energy. Listen through the full gesture, including the onset,
developing contour and release.

The explicit before/after cases compare model 1 with model 2 for every voice at
C4 and velocity 1. Review both complete finite gestures and settled HOLD buffers.
Repeat held material through several wraps to compare sustained character and
seams; those buffers omit the original onset, so they cannot establish attack
differences. The saved recipe identifies the model and settings behind each clip.
These comparisons retain the evidence behind the accepted voices; measured
differences alone do not establish useful audible separation.

Before the six-voice approval, CHOIR's model-2 unvoiced-source gain was reduced
from 4.8 to 3.3 (31.25%), retaining the voiced source, spectral targets and BREATH
mapping. Model 1 and the other five model-2 voices retain their existing rendering
behavior.

## Reproduce the evidence

```sh
./gradlew --no-daemon test
./gradlew :synth:generateMurmurAudition -PmurmurFull
./gradlew :synth:generateMurmurKit
./gradlew :cli:run --args='synth MURMUR BASS --all --midi 48 --velocity 0.7 --out /tmp/murmur-bass'
```

The audition task writes `testkit/murmur-audition/`: raw and level-matched WAVs,
recipes, measurements and an accessible listening page. The current case list
produces 243 cases and 486 WAVs in full mode, or 199 cases and 398 WAVs in compact
mode. Those totals include 24 explicit model 1/model 2 comparison cases: six voices,
two models, and both finite and settled held material. Counts describe generated
coverage, not acceptance limits; the generated manifest records the actual count,
source revision and clip settings. The preceding 219 full-mode cases retain their
IDs and settings; the 24 new presets extend that evidence set.

The wider matrix covers all voices at low/middle/high roots and velocities,
five-point BODY and MOTION sweeps on every voice, KEY sweeps for MOUTH, EDGE and
BREATH, the four required interaction grids, all-high cases and held loops. Tests
also compare level-independent harmonic profiles and developing spectral contours
through useful note frames, rather than only checking endpoint sample differences.
Metrics are mechanical evidence; useful dry identity and audible loop quality
require listening. Owner approval covers the six dry voices after the CHOIR trim.
The 24 added presets remain pending owner listening.

The kit task writes `testkit/SnipSnap Murmur Kit/`, including the native XPM,
sixteen mono 24-bit WAVs and the recipe-bearing `kit.json` sidecar. Generated audio
stays out of Git. Neither hardware reconstruction nor speech, text input,
automatic chords, stereo or live polyphony is included.
