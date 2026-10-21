# Rime — finite first-listen prototype

Rime turns a finite Koch contour into a pitched source, reveals its finer frequency
bands within a note, and drives a two-port modal body. Model 1 is the first listening
prototype developed from the supplied engineering specification. Three provisional
recipes cover LOOP (Round Seed), GLINT (Fine Frost) and VESSEL (Open Vessel).
Owner listening acceptance is pending. The phone picker, HOLD, remaining voices,
36-preset bank and held instrument export are later stages. Nonzero HOLD is refused.

## Render and replay

```sh
./gradlew --no-daemon :synth:generateRimeAudition
./gradlew --no-daemon :synth:generateRimeAudition -PrimeQuick
./gradlew --no-daemon :synth:generateRimeAudition -PrimeReplay=/absolute/recipe.json -PrimeOutput=/absolute/output
```

The page, WAVs, manifest, geometric reference, recipes and downloads are written
under `testkit/rime-audition/`. `-PrimeOutput` changes that destination. The full
bench has 130 clips: three starters, three original six-note phrases, five-step
sweeps of each timbral control on each voice, three path removals and a full
reference per voice, three registers at two velocities, four paired-control
matrices, and an all-high C7 case per voice. Quick mode produces 45 comparisons.

Ordinary patches also use the CLI:

```text
snipsnap synth RIME GLINT --all --out /tmp/rime
snipsnap synth RIME VESSEL --patch /path/vessel-patch.json --out /tmp/replay
snipsnap synth RIME LOOP --midi 48 --velocity .6 --seconds .8 --seed 2718 --macros DETAIL=.7,EDGE=.5 --out /tmp/note
```

`--seconds` is the excitation gate (0.15–4 seconds); the body tail follows it.
MIDI is 24–96, velocity 0–1, and the saved seed is a signed 32-bit integer.
The patch persists model, voice, macros, MIDI, velocity, gate and seed.
`Patches.edited`, `PadRecipe` and native velocity regeneration preserve that state.
Provisional recipes stay outside `Presets` and the phone's factory selector.

Diagnostic recipes include an ordinary patch and explicit contact/reference/body
switches. Phrase recipes retain each note's complete patch, onset and mix gain.
The generator replays either kind. Every clip has 24-bit mono 44.1 kHz WAVs:
raw uses one fixed 0.25 gain on conditioned prelevel samples; matched uses
`Dsp.levelTo` at a loudest-200-ms RMS target of 0.10 and a .90 peak ceiling.
All causal comparisons retain duration, source convention and noise substream.

## Source convention

The fixture starts at the top vertex of a counterclockwise equilateral triangle.
Each edge receives the four-segment right-side/outward Koch construction. Iteration
5 has 3,072 edges. The finite polygon has 16,384 equal arc-length samples without
a duplicated endpoint, is centered by its perimeter-sample centroid, and normalized
to unit maximum sampled radius. The original radius is retained. Every prototype
voice uses this exact reference; there is no hidden asymmetric variant.

Extraction uses `Fft.forward`, its unscaled negative-exponent convention, Float
buffers and Double twiddle recurrence, then divides by sample count. Bins wrap
explicitly to signed indices -512 through +512; DC is removed. Model 1 fixes
precision, ordering, traversal and voicing constants.

The source uses disjoint dyadic bands, each with phase-aligned 8,192-point x/y and
analytic-derivative tables. One advancing root phase survives admission and
unfolding. A unit circular reference blends into the actual first coefficient
below DETAIL .12. Relative contour coefficient amplitudes are preserved.

DETAIL compresses the proposed logarithmic 1/8/32/128/512 progression onto
`min(512, floor(18000 / f0))`. A 2 kHz transition reaches zero by 18 kHz. C4 has
at most 68 harmonics and C6 has 17. This note-aware mapping intentionally adapts
the proposal; geometric diagnostic cutoffs remain absolute. Exact symmetry and
high-register bandwidth still constrain which terms exist.

## Controls and body

EDGE tapers admitted upper coefficients and raises bounded derivative pressure.
One derivative-energy normalization has a floor; partials are not normalized
individually. Smooth pressure saturation and subordinate seeded friction run at 4x.
The position-derived fundamental persists. Friction substream identity is independent
of the macro map and active band count.

ANGLE rotates the first projection over a full turn. A second projection at pi/6
feeds a different modal port at level .36. Its versioned path delay is
`.035 + .075 * BODY` root periods. Differing modal participation and path responses
make coherent pickup combination consequential. ANGLE 0 and 1 are bit-identical.

UNFOLD staggers smooth scale envelopes independently of audio phase, with up to
1.5 seconds of reveal. Fine bands recede before the root. At DETAIL zero there
are no fine bands and UNFOLD produces identical audio at both endpoints.

BODY changes upper modal spacing, T60, path length and direct/body balance.
Eight `Modes.Bank` modes include a root anchor; unsafe upper modes have zero
participation. Two force vectors excite one feedforward bank. Trapezoidal forcing
reduces the rate-dependent phase discrepancy with the direct path. Every render
owns all modal/filter/noise state, with no body-to-source feedback.

Velocity changes source/contact drive, hardness and the final loudness target.
Output uses the standard 4x band limit and decimator, DC removal, a measured tail
and a terminal fade. Shared leveling caps compensation at 12x and peak at .94.
Internal bounds are checked before conditioning.

## Verification and listening limits

`RimeTest` checks convergence, nested reconstruction, signed projection, table
accuracy, complete recipe recall/ownership, input refusal, zero velocity, periodic
ANGLE, unfold timing, unforced modal decay, pitch/root energy, control activity,
finite extremes/DC/tails, and a 4x/8x reference with friction disabled. CLI tests
check exact authored-patch WAV replay and refusal before output creation. Shared
patch/preset tests register RIME as a provisional engine.

The initial GLINT ANGLE 0 versus .5 comparison changed leveled FFT magnitudes by
about 4.9% in normalized L2 distance. Tested bright notes tracked within .1 cent.
The 4x/8x full-wave residual was approximately -36 to -31 dB across C2, C5 and C7.
That includes modal integration and conditioning differences; listening is still
needed to assess alias audibility and the usefulness of every control.

The page distinguishes target contour, geometric reconstruction and rendered audio.
Repeated playback of finite clips is not seamless HOLD. Publication is a first-listen
handoff; sonic acceptance remains pending.
