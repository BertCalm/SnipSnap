# Trefoil ordered HOLD: as built

The characteristic gesture is **contact → circulating energy → power withdrawal
→ natural decay**. The owner accepted the revised Python study and authorized
the Kotlin port on 2026-11-07. Native Kotlin and held MPC listening are a new gate.

`TrefoilHeld.renderNote` implements that complete causal DSP gesture. The phone's
held key and exported keygroup use a prepared sample and the sample player's
release envelope. Both behaviors have separate audition recordings.

## Saved mode and phone controls

`TrefoilPatch` retains finite models 1 and 2 and adds `playback=HELD`,
`heldVersion=1` and `releaseSeconds`. Old recipes omit these fields and retain
their existing defaults and JSON shape. Choosing FINITE restores the previous
finite timbre/model while retaining current root and velocity. YOURS, drafts,
root editing and pad recipes retain the full held recipe.

Held playback fixes NUDGE at zero. WEAVE is either zero (stationary geometry)
or .34–1, keeping the maximum motion period below six seconds. The phone offers
MOVING/STILL explicitly and bounds the moving WEAVE slider. EXCHANGE, THREAD,
MEMORY and authored velocity retain their model quantities. No new engine or
preset roster is introduced.

## Periodic state and output

The core applies orthogonal rotations to both energy coordinates, followed by
positive modal loss. For short periods, a 6-by-6 affine period solve per partial
finds the repeating state. For sufficiently contractive long periods, bounded
iteration reaches the same residual without constructing that dense map. The
seven feedforward memory modes are then solved independently. Geometry is one
cached Newtonian reference orbit with periodic cubic interpolation; changing
pitch never rebuilds that orbit. Cancellation is checked during preparation,
solve, evolution and cache publication.

Output uses the shared band limiter and decimator with genuine preceding and
following audio, then a continuous 16 Hz DC blocker. No wrap crossfade or
loop-end fade establishes recurrence. A 20 ms onset ramp affects only the first
delivered period; complete subsequent periods form the sampler loop. Fast
motion may deliver several whole periods so the sample remains longer than
1.5 seconds. SEND/SPREAD make finite pad copies with a saved terminal fade.
MAKE INSTRUMENT retains the original sustain and three octave-spaced native roots.

## Reference and measured limits

The independent Python bridge checks six cases. With Kotlin's exact contact
force supplied to Python, relative error energy is below 6.8e-16 and maximum
sample error below 3.8e-9. Native state residuals are below 1.5e-12, with zero
preceding/following seam error at Float precision in these six cases. Tests
also cover register/material/memory corners, injected unforced growth,
one-body receiver transfer, causal note-off prefixes, determinism, strict
recipe decoding, cache ownership and cancellation.

The approved Python source used PCG64 and prototype filters. Held version 1
uses `Dsp.Noise` with an explicit seed law and the shared Kotlin output stage.
After one gain/delay fit, correlation with those six original loops is at least
.999984. These are signal measurements, not a claim of perceptual identity.
Finite models keep their old noise streams and arithmetic.

Host measurements on Temurin 17: 608–649 ms per uncached capture after JIT warmup,
0.08–0.23 ms per caller-owned cache hit, and about 1.4 seconds for the first
observed reference capture. A miss still exceeds the tens-of-ms knob-turn
target. Preparation runs off the UI thread and cancels stale work; prepared
playback uses the existing sample player. The audio LRU is bounded to 12 entries
and 8 MiB, plus the fixed reference descriptor table.

No physical phone was connected during this work. Run the included Android
benchmark on the target device before claiming phone performance:

```sh
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.ui.TrefoilHeldBenchmark
adb shell run-as com.snipsnap.app cat files/trefoil-held-benchmark.json
```

The report records Android model/runtime, cache-cold captures and owned cache
hits. Geometry/JIT can already be warm; record whether the target is an emulator.
Android compilation is separate evidence from executing device tests.

## Reproduction and MPC listening

```sh
./gradlew --no-daemon :synth:generateTrefoilHeldAudition
python3 experiments/trefoil-hold/native_reference_check.py testkit/trefoil-held-native
./gradlew --no-daemon :shell:generateTrefoilHeldMpc
```

The trial has three native roots with complete loop metadata. Open its `.xty`
on MPC 3 or the `.xpm` in its TrackData folder on MPC 2; retain the whole folder.
The phone and reference sampler express release in seconds. A matching MPC
envelope value is not a measured hardware release time. Key velocity scales the
trial's one authored sample per root; this round does not add touch layers.
Physical MPC validation of this held trial remains distinct from the accepted
finite bank. Its source patch and the loop start/end frames remain in the export.

## Why it is built this way

- Separate playback state protects saved finite sounds and their old model laws.
- Solving the periodic state preserves real circulating energy across a repeat.
- Natural DSP release and sampler release are exposed separately, so a source
  model check cannot silently stand in for MPC behavior.
- A bounded cache makes replay quick without retaining large simulation plans
  on the phone. Cold preparation remains a measured performance limit.
