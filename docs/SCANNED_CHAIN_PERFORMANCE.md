# Scanned Chain responsiveness

Luthier REVIEW, 28 October 2026. The untouched comparison is SnipSnap commit
`307f125ef2d9ef0c4d6e6c816bb008b12a235392`. The revised implementation keeps the
same saved render response and adds six editable preset candidates.

## What changed

The first profile attributed about 62% of sampled renderer work to projection:
turning 128 moving positions and velocities into harmonic coefficients. Four
sequential sums now stay in local variables during each dot product, rather
than repeatedly reading and writing four result arrays. Each sum starts at
positive zero, visits the masses in the same order, and is scaled afterward.

Each spring bond's force was evaluated twice, once from each neighboring mass.
One private 128-value buffer now retains that identical oriented calculation
for both uses. The nonlinear spring law and the subtraction order are unchanged.
Control-dependent guard bounds and driver prefixes are computed once per render;
the scanner also reuses its array references and repeated time-weight products.

The 128 masses, physics and snapshot rates, independent scanner, Hermite
interpolation, oscillator recurrence, harmonic summation order, energy/work/loss
ledger, runtime guards, oversampling and output stage remain intact. The legacy
renderer is untouched. There is no FFT reduction, approximate oscillator,
reassociation, trajectory clipping, reduced-quality preview or new render model.
All mutable physics and the new bond buffer belong to one render.

The existing SYNTH request pipeline is now a small shared helper. It retains
the 100 ms debounce, 150 ms delayed loading indicator, interruptible background
render and final active-request check. A second active-request check prevents
an ordinary failure from an obsolete worker showing a toast over a newer edit.
Publication, failure callbacks and loading updates stay on the main thread;
obsolete cleanup cannot clear a newer request's indicator.

## Measured cost

Actual production calls, desktop Linux x86-64, JDK 17, five reported processors,
256–768 MiB benchmark heap. Each three-second C4 case at velocity 0.8 used
three warmups and seven measured calls per state. Both before and after runs
used the same JFR `profile` settings. Other local builds were stopped during
these timing runs; this is a shared host without an exclusive-CPU guarantee.

| Voice | Fresh before | Fresh after | Speedup | Changed-control miss after | Exact cache hit after |
| --- | ---: | ---: | ---: | ---: | ---: |
| PLUCK | 506.81 ms | 283.16 ms | 1.79x | 279.46 ms | 0.097 ms |
| METAL | 511.94 ms | 270.91 ms | 1.89x | 270.42 ms | 0.103 ms |
| BLOOM | 500.66 ms | 272.03 ms | 1.84x | 265.21 ms | 0.048 ms |

Values are medians. Fresh calls clear completed audio outside the timer. Cache
hits prime the exact recipe outside the timer and return owned PCM. A changed
control miss primes the original recipe, then changes only MOTION to 0.97.
Hashing, JSON, file writes, buffer preparation and cache reset/priming are outside
the timer. The separate raw-probe and finishing measurements call actual
production functions; no stage estimate is obtained by subtracting timings.

JFR attribution is sampled CPU evidence, not an exact stage timer. After this
pass the scanner dominates the remaining work. The complete accounting ledger
was kept because the initial profile did not identify it as a material cost.

One cancellation observation interrupted a fresh four-second METAL render after
about 10 ms of worker CPU. The worker reported cancellation about 2.95 ms after
interruption and published no completed cache entry. This observation occurred
during other local build work and is not a phone latency guarantee. The
benchmark bounds its wait for a worker but has no performance pass/fail threshold.

## Sound and state preservation

An immutable fixture was captured from the untouched baseline. Its 27 cases
cover all voices at MIDI 36, 60 and 96; frozen, damped, undamped/driven and
low/middle control combinations; the exact custom contour that previously
focused beyond the displacement guard; zero velocity; and a zero source.

Every case retains identical float32 sample hashes for raw and finished audio,
initial/final shape hashes, the complete diagnostic ledger and the complete
headroom plan. Recipe JSON and source-point identities also match. Hashes retain
the raw IEEE-754 bits, including signed zero. Tests clear completed audio before
fresh renders, recheck retained buffers after later work, and run two actual
solvers concurrently to check state isolation. Existing legacy PCM fixtures remain
separate and unchanged.

The fixture at `synth/src/test/resources/scanned-chain-performance/baseline-307f125.json`
includes baseline class identities, patch JSON and method notes. The standalone
benchmark records the loaded classes, including private nested classes, so a
test-side harness loaded ahead of untouched production classes is identified
honestly. Timing rows are removed from the correctness fixture.

Six Android instrumentation cases exercise rapid edits, obsolete success and
failure, loading state, disposal, callback thread ownership and interrupt cleanup.
They use the production preview helper with controlled workers; compiling these
tests alone is not an on-device execution result.

## Reproduce

From a normal configured checkout:

```sh
./gradlew --no-daemon :synth:benchmarkScannedChain \
  -PscannedChainBenchmarkWarmups=3 \
  -PscannedChainBenchmarkSamples=7 \
  -PscannedChainBenchmarkOutput=/tmp/scanned-chain-cost.json

./gradlew --no-daemon :synth:test --tests '*ScannedChain*'
```

`scannedChainBenchmarkCases=preservation` selects the 27-case matrix.
`scannedChainBenchmarkPaths=production`, `scannedChainBenchmarkStates=fresh`,
and `scannedChainBenchmarkCancellation=true` can narrow a manual run or add
cancellation observations. The Java main also accepts these as command-line
arguments for comparisons against separately compiled untouched classes.

## Why it is built this way

- Projection extracts the harmonics already in the moving shape. Keeping the
  same sums in faster local storage saves work without changing that shape.
- A bond joins two masses. Calculating it once avoids duplicate work while both
  masses still receive the original force calculation.
- Each preview request owns its result and its UI callbacks. An old worker
  finishing or failing cannot take control back from a newer edit.
- Presets save the complete source, settings and envelope, so an audition can
  become an editable pad rather than an unexplained WAV.

## Verified, measured and still open

Verified by baseline comparison: the 27 cases retain exact audio and accounting;
the original three presets retain their definitions; the existing starter keeps
its default note layout. Measured: the table and single cancellation observation
above. Proposed: the six presets' musical roles. Heard: the owner accepted the
previous refined engine; these six additions still need his listening judgement.

Fresh renders still take hundreds of milliseconds on this host. The tens-of-ms
playability goal is not achieved by this pass. Actual phone render latency,
audio-output latency and physical MPC acceptance remain device checks.
