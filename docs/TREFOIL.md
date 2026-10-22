# TREFOIL — finite three-body resonator study

The first listening prototype adapts the supplied Trefoil engineering spec v1.0
(18 October 2026) to SnipSnap's offline path. Equal-mass Newtonian motion drives
contact between three pitched banks, with actual internal energy exchange and a
feedforward sympathetic trace. WEAVE, HOLLOW, WIRE, TANGLE and TRACE share the
same structure and equal excitation budgets. Every bank has its own root mode;
there is no fourth anchor. Output is dry mono, 4x internal / 44.1 kHz delivery.

Owner listening, HOLD, a validated PAIR departure family, production presets and
phone integration remain later work. COMPLETE means only that a finite simulation
horizon was reached. No trajectory is claimed to escape forever.

## Physics and deterministic recall

`TrefoilTrajectory` uses unsoftened planar G=m1=m2=m3=1 gravity. Apache Commons
Math 3.6.1's `DormandPrince853Integrator` supplies adaptive 8(5,3) stepping and
dense output. Model 1 fixes rtol=1e-10, atol=1e-12, max step=.04, 250,000 RHS
evaluations and 50,000 accepted steps. Work is bounded and interruptible.

Reference family 1 fixes the supplied positions at (.97000436,-.24308753),
their negative and the origin. A/B velocities are
(.46620368406955315,.4323657309174746); C is minus twice that. Period is
6.3259140097821724. `scripts/trefoil_refine_reference.py` reproduces numerical
refinement from the supplied rounded seed using SciPy DOP853/least squares.
The separate double-precision refinement measured 5.20e-14 closure; it is not
certification. Mathematical family: Chenciner & Montgomery (2000),
https://annals.math.princeton.edu/articles/12364 .

NUDGE adds one seeded velocity perturbation, with mean removed and RMS normalized
to .38 * NUDGE^1.35 of reference RMS speed. General perturbations may change E and
Lz; diagnostics use each perturbed initial baseline. NUDGE zero ignores seed.
Pitch and velocity do not alter gravity. An eight-entry cache keys dynamical seed,
nudge, weave and duration; solver and reference constants belong to model 1.

WEAVE is a common clock: reference cycles/second are 0 at zero, 1/16 at .1, 1/8
at .25, .5 at .5, 2 at .75 and 8 at one, with smooth interpolation. Horizon comes
from duration and never wraps. Dense output supplies 1 kHz musical-time readouts.
Six pairwise separation minima per reference period supply contact opportunities;
origin crossings trigger none.

Derivative stages entering separation 1e-3 stop conservatively, including trial
stages that would be rejected. Dense intermediate checks complement that guard.
This prototype does not claim a formally certified detector for every unresolved
encounter. On exclusion or work-cap termination, the last accepted snapshot is
retained for release, flagged, and continuing excitation ends smoothly. That
snapshot is not presented as continued motion.

## Audio model

Each bank has six modes at ratios 1,1,2,3,4,6. A local root mode keeps its tuning
while the second root degree of freedom can exchange strongly. THREAD changes excitation, pickup
and bounded upper-mode inharmonicity within those banks. A/B/C favor lower,
middle and upper modes while retaining roots. Note-relative finite onsets and
geometry-derived accents join a bounded friction-like drive. This is a designed
exciter, not a calibrated bow. Noise is seeded and band-limited.

In (omega*q,v) energy coordinates, free modes are exact damped rotations. Each
bank exposes one normalized velocity port. EXCHANGE applies exp(K), K real skew
3x3, to all three old ports before writes. Replacing the projections preserves
complete core squared-state norm even as orthogonal coefficients change; damping
contracts it. Zero EXCHANGE is exact identity. The local root has zero port weight;
it remains within each participant rather than supplying a fourth oscillator.
Transfer speed scales down in the bass. Positive external work has an equal finite
budget per bank.

This uses an instantaneous modal-port junction instead of the brief's suggested
propagating-wave junction, adapting CHAMBER's energy coordinates and OVERLAP's
scattering approach. MEMORY sends into eight damped harmonic modes with no return
path. Memory energy is reported separately from the passive core. Duration caps at
eight seconds with a delivery tail fade. HOLD must be zero and other values refuse.

`TrefoilPatch` saves explicit MIDI 36–84, velocity, exact signed 64-bit seed as
decimal text, duration, active duration and model within shared patch version 1.
`Patches`, `PadRecipe` and physical `Velocity` replay support it. Prototype recipes
stay outside the factory roster and phone picker until heard. Edits preserve
non-macro state. No hidden solver or DSP state is serialized.

## Reproduce the audition

```sh
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.TrefoilTest
./gradlew --no-daemon :synth:generateTrefoilAudition
./gradlew --no-daemon test
./gradlew --no-daemon :synth:generateTrefoilAudition \
  -PtrefoilReplay=/absolute/path/to/recipes/transfer-on-1.json \
  -PtrefoilReplayWav=/tmp/receiver.wav
```

The generator writes 62 cases / 124 actual PCM16 WAVs, complete diagnostic recipes,
trajectory/energy traces, raw/matched metrics, source hashes and a starter ZIP under
ignored `testkit/trefoil-audition/`. Retained frontend source lives under
`synth/src/test/resources/trefoil-audition/`. Five-step controls, three registers and
three velocities join moving/frozen, exchange-off, memory-off and additive stems.
The single-body impulse disables B/C exciters and memory. Off receivers are silent.

Matched clips target loudest-200ms RMS .03. Causal branches/stems retain their
reference mix's one gain; raw files retain unit pre-leveling gain. Diagnostic
options and matching gain are saved beside each patch. PCM hashes identify files.
The starter ZIP includes first sounds, comparisons, stems, transfer proof and source.

Classifier diagnostics retain both the first-1.2-second result and a whole-gesture
result with only the generic duration-to-LOOP shortcut bypassed. A rising bass
excerpt can read KICK when its latest peak has no following decay interval in that
short excerpt. The whole-gesture guard retains the actual envelope. Neither result
changes routing metadata or claims that a listener has accepted the timbre.

The page supports one audible player at a time, pause/seek/stop, finite-clip repeat,
raw/matched switching, measured trajectories, local listening notes and JSON export.
Notes are never submitted to a server. Publication implies neither listening
acceptance nor phone/MPC hardware validation.

## Comparison review, listening interface revision 2

**Heard:** Josh reports difficulty distinguishing the comparisons. The first
recordings remain unaccepted and are preserved byte for byte. The audio model,
recipes, fixed matching gains and source provenance remain model 1 at
`a637ee4a61edec30441114ae98922383f562a93c`.

**Fix now, sound character:** the five starters put 99.13–99.97% of Hann-windowed
spectral power within +/-5% of the MIDI root. The default ordered memory on/off
residual is -47.27 dB relative to the reference after removing one scalar gain.
The bank colors and motion do not establish useful audible differences simply
because their internal states differ. `scripts/trefoil_comparison_audit.py`
measures the published PCM16 files and records hashes and the exact method in
`comparison-audit.json`. These are signal measurements, not listening thresholds.

**Fix now, playability:** the original list required restarting each recording.
The guided player runs both decoded mono buffers on one Web Audio clock with
complementary 8 ms gain ramps. A/B switches preserve position, pause and seek.
Five-millisecond edge fades on identical focus windows avoid hard loop cuts;
full recordings are untouched. The individual player pauses while the paired
player runs and starting an individual recording releases both paired sources.

The guided sequence compares perturbed moving/frozen geometry, a connected/silent
unstruck receiver, and the wider 0%/100% memory endpoints, then labels the original
ordered exchange and memory pairs as subtle. The receiver retains the complete
mix's gain. Memory endpoints keep their existing per-clip loudness target. Each
policy is stated beside the player. Neither isolation nor endpoint selection
establishes that the production mix has sufficient character.

**Try next, sound character:** a new acoustic model should let geometry change
the audible partial balance and the contact gesture while preserving a useful
root. The present coherent root drive, protected local root modes and similar
pickups are candidate causes of masking. Altering those mechanisms needs a new
model/version comparison, root and energy probes, and Josh's listening verdict.
No acoustic constants were changed in this interface revision.

**Measured:** PCM analysis above, paired-player continuity and actual silent
receiver output in browser QA, and the focused Kotlin Trefoil checks. The earlier
whole-repository run ended with the execution session before a final build result;
it is not recorded as a full-suite pass.
