# HALOBAR render speed

The owner heard and approved the glockenspiel, crotales and tubular-bell banks
on 2026-11-12, joining the original three voices. This follow-up reduces work
while retaining the approved model-1 audio. All 408 published Float32 fixtures
and 72 additional take/register/velocity cases replay exactly on the same JVM.
The factory registry and all existing preset recipes are unchanged.

Measured source: `9a5017c42a6a86e18691489127d5c826f068baa1`, based on `0b7e105e87fff1ec4ddd43532912f2afe2b9abf3`. The shared FFT, resampler,
filters and output-stage sources are identical between those snapshots.
Experiments with precomputed FFT plans and batching were slower and were
discarded; they are absent from the production diff.

Fresh render timings use the default macro recipe at full velocity, model 1,
and each voice's default note:

| Voice | Before median / p95, ms | After median / p95, ms | Median change |
|---|---:|---:|---:|
| Vibraphone | 286.4 / 308.1 | 278.1 / 321.8 | -2.9% |
| Marimba | 163.4 / 178.9 | 152.4 / 170.9 | -6.8% |
| Xylophone | 132.3 / 146.6 | 124.2 / 142.7 | -6.1% |
| Glockenspiel | 219.6 / 265.4 | 201.4 / 215.4 | -8.3% |
| Crotales | 276.9 / 313.3 | 260.4 / 278.3 | -6.0% |
| Tubular bells | 312.7 / 340.5 | 298.2 / 380.2 | -4.6% |

These are desktop measurements, not phone latency. Fresh rendering still
exceeds the 50 ms target. The confirmation alternates before/after/after/before
in isolated JVMs, uses 20 warmups and 25 measurements per voice per round,
clears completed/prepared audio caches before every fresh render, and retains
only the normal immutable halo-kernel spectra. There are 50 measurements per
voice per version. Both versions use Java 21.0.13+9, a 1 GB heap, G1, four
logical processors, one GC worker and one concurrent GC worker; all JVM threads
are pinned to CPU 0. Baseline sources are compiled with the same
Kotlin 2.0.21/JVM-17 target and use identical shared DSP dependencies. Raw wall
and thread-CPU samples are in `reference/halobar/render-speed/benchmark.json`.
The recorded confirmation drivers are `ConfirmBench.java` and `ConfirmSpace.java`;
the initial unpinned drivers are retained as `SpeedBench.java` and `SpaceBench.java`.

Prepared positive-SPACE edits are measured separately, with eight warmups and
45 measurements per voice per round, so each version has 90 measurements:

| Voice | Prepared SPACE median / p95 after, ms |
|---|---:|
| Vibraphone | 12.9 / 18.7 |
| Marimba | 8.6 / 15.1 |
| Xylophone | 7.2 / 12.9 |
| Glockenspiel | 10.4 / 16.3 |
| Crotales | 13.0 / 23.2 |
| Tubular bells | 12.6 / 21.3 |

The initial unpinned comparison is retained beside the confirmation, including
its cached-SPACE outlier. Neither dataset establishes device playability.

The echo ring reads the same sample positions using one conditional wrap in
place of integer remainder. The source opening envelope stops updating only
when `1 - closed` already rounds to exactly one; its subsequent values cannot
affect the send. A zero-depth motor stops advancing its unused oscillator.

For small feedback arguments, a ninth-degree tanh Taylor candidate is enclosed
in a conservative error interval. From `y' = 1 - y*y`, its coefficients are
1, -1/3, 2/15, -17/315 and 62/2835. At `abs(x) <= 1/16`, the first omitted
alternating term, -1382/155925 times x^11, is below 8.062e-15 times abs(x).
The chosen output bound, 2e-12 times (abs(fed) + abs(x)), is wider than this
remainder plus Double coefficient/evaluation/reference-tanh and final
gain/division/addition roundoff in the guarded domain. The lower cutoff
`abs(x) >= 1e-100` keeps the polynomial calculation in the normal Double range.
Gain is restricted to 0..1 and drive to 1..4 for this shortcut.

A candidate is accepted only if both interval endpoints round to the same
stored Float and neither endpoint could raise the current Double peak. Every
uncertain case uses the original tanh calculation. The stored feedback sample
is therefore unchanged, and a potential peak increase is always evaluated by
the reference. Energy traces always use reference Doubles. The original
feedback stability argument and model number remain applicable.

The four focused feedback tests compare against independent reference tanh,
cover 300,000 randomized cases, Float midpoints, zero cancellation, possible
peak changes and out-of-domain values. Standalone oracle checks pass 25,000
rounding and 25,000 peak cases. Removing only the rounding guard in an external
compiled copy produces a Float mismatch; removing only the peak guard produces
a Double-peak mismatch. The production source is not changed by those checks.

The complete nine-module local repository CI gate passed 9,526
tests, with zero failures, errors or skips. All 626
synthesis test classes are covered exactly once across the three CI shards.
Android assembly and device-test compilation passed; APK v2 signature verified.
No physical Android device or emulator was connected, and instrumentation was
not executed. MPC loading and combined keygroup velocity/round robin remain
unverified. This is a speed follow-up, not engine acceptance completion.

Why it is built this way:

- Exact wrapping and unused-state removal avoid work without changing the instrument.
- The Float-interval test preserves the existing feedback latch, with reference fallbacks for uncertainty.
- The peak guard and strict energy path retain the numerical diagnostics as well as the audio.
- The 50 ms target and hardware gates stay open because these desktop checks do not establish them.

Replay the additional cases by compiling `RenderSpeedReplay.java` with the synth
runtime classpath and passing `take-parity.tsv`. Earlier reference tables replay
with the existing `NativeReplay` helper. The validation receipt and benchmark
drivers are in `reference/halobar/render-speed/`.
