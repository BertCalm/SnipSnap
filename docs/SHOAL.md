# Shoal — voice contrast revision

Shoal implements GLASS and RIPPLE from the retained
[engineering proposal](superpowers/specs/2026-10-16-shoal-engine-engineering-spec.md).
The other four voices and the 36-preset roster remain future work. Eight dry
recipes and a 16-pad starter establish the first two voices. The owner heard
model 1 and found GLASS and RIPPLE too similar. Model 2 revises their contrast;
its sonic acceptance awaits the next listening verdict.

The renderer produces deterministic mono 44.1 kHz samples at 4× internally.
Up to twenty-four moving harmonics, beginning at harmonic 2, exchange nonnegative
energy using the previous complete population snapshot. A separate fundamental
anchors pitch. CURRENT biases neighbor transfers, COHESION includes local
occupancy attraction, STRAY coordinates bounded frequency departures, and GLOW
changes internal regional damping and supply. DENSITY redistributes a normalized
excitation budget. Velocity changes excitation concentration and attack effort.
The finite source release terminates both the root and moving bank before output
conditioning. Raw diagnostics precede the shared melodic leveling process.

Model 2 puts GLASS energy in separated harmonic groups, with a sharp onset,
brief finite excitation and different regional decay rates. Its graph includes
four-harmonic jumps as well as adjacent exchanges, and moves more slowly.
RIPPLE starts with a broader lower spectrum and receives a longer supply whose
injection point travels through the bank. Its adjacent and two-harmonic links
transport energy faster. Both voices start with the same finite moving-energy
budget. The moving bank has more power relative to the protected root than in
model 1; upper notes receive extra root support. HOLD retains each voice's
profile, graph and supply behavior within the captured periodic approximation.

ROOT (MIDI 24–84), velocity and model version are saved beside the six macros.
Macro edits and native velocity variants retain those fields. The phone exposes
ROOT and VELOCITY independently; CLI synthesis accepts `--midi` and `--velocity`.
New patches and factory recipes default to model 2. Explicit model-1 recipes,
including recipes without a `model` field, retain the original renderer. Both
models remain readable, and edits, velocity variants and preset promotion keep
the saved model. The shared patch JSON version remains 1; the `model` field
selects the sound revision.

HOLD at .975 or above exports only a moving sustained region. It uses the
proposal's **captured periodic control trajectory approximation**, rather than
claiming convergence of the free nonlinear transport model. A smooth convex
overlap closes the energy and detune trajectory. A frame-fitted harmonic root
and zero-mean frequency departures in Hz close each oscillator's integrated
phase. Three independently synthesized periods and conditioning guards provide
the preceding-cycle evidence for seam checks. The spectrum keeps moving.

The NO_TRANSPORT diagnostic replaces the full model's evolving distribution
with its energy-weighted temporal mean, preserving each node's total energy and
its supply, center and detune controls. It isolates distribution movement while
holding the average partial-energy profile. It differs from CURRENT = 0, which
still allows cohesion-driven settling. NO_LOCAL_COHESION retains center motion;
NO_STRAY retains the complete energy trajectory.

Generate the dry audition with:

```sh
./gradlew --no-daemon :synth:generateShoalAudition
```

The generated `testkit/shoal-audition/` contains 94 cases: the 88-case matrix
using model 2, two same-root model-2 identity clips, and four model-1 comparisons
at C3/C4. It includes raw and matched PCM24 WAVs, replayable recipes, measurements,
actual control-state traces, and a compact listening ZIP. The heatmaps show
moving-partial energy, not an audio FFT.
The pack includes five-point macro sweeps, the four required interaction pairs,
low/middle/high notes at soft/hard velocity, extremes, mechanism comparisons and
held loops. Recipe annotations identify diagnostic ablations that ordinary patch
playback does not apply.
When the original pack is available, its first-listen ZIP is copied unchanged
with its model, source revision and checksum provenance. Model-1 C3 baselines
are checked against the original PCM hashes during generation.

The listening page is published privately through Sites. Automated tests check
mechanism and integration contracts; they do not establish musical character.
Phone compilation/playback and MPC hardware acceptance remain separate checks.
