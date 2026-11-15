# Rejected HALOBAR filter-pair trial

The paired anti-alias-filter experiment passed exact-audio checks but did not
improve fresh-render latency in the isolated production comparison. It is
rejected for the final build. The original DSP is retained.

Candidate source: `ba61c470993b9a6d6fb55b159c27ffd45fba781a`.
Baseline DSP source: `6c60fc02e65175de55a172e53821847a54fc350a`.
The exporter changes are independent of this experiment and passed the full
validation gate in the subsequent [final export build](HALOBAR_FINISH.md).

| Voice | Original median / p95, ms | Candidate median / p95, ms | Median change |
|---|---:|---:|---:|
| Vibraphone | 308.7 / 433.8 | 309.0 / 358.8 | +0.1% |
| Marimba | 172.2 / 202.4 | 178.2 / 208.4 | +3.5% |
| Xylophone | 138.7 / 150.3 | 146.5 / 160.4 | +5.6% |
| Glockenspiel | 224.4 / 251.8 | 232.7 / 252.6 | +3.7% |
| Crotales | 292.5 / 314.7 | 302.4 / 497.3 | +3.4% |
| Tubular Bells | 324.6 / 400.5 | 333.3 / 469.6 | +2.7% |

Protocol: before/after/after/before in isolated JVMs, twenty warmups and twenty-five
measurements per voice per round, fifty measured samples per voice per version.
All JVM threads were pinned to CPU 0, JDK 21.0.13+9, G1, 1 GB heap, four logical
processors, one parallel and one concurrent GC worker. Each call clears all
four audio caches; immutable halo spectra and JIT may be warm. No builds,
compression or other validation jobs competed with this comparison. These are
desktop render times, not phone or audio-output latency.

The initial private prototype suggested an improvement, but the complete
shared implementation with reference fallbacks did not confirm it. A strict
corner test found a NaN-sign difference; retaining the original path for
non-finite/enormous diagnostic inputs fixed that test. The candidate then
passed all 408 published Float32 fixtures and 72 additional take cases. A
correct numerical result does not establish a performance improvement.

The separate FIR loop-unrolling trial was also slower and is absent from the
production source. Its final exploratory baseline round overlapped an
independent compilation, so exploratory aggregate values are not used for
performance claims. The isolated comparison above is the decision evidence.

The 50 ms fresh-render target remains unmet. The owner has accepted the sound
and reported passing the listening and device checks. No latency waiver or new
sub-50 ms measurement is inferred. This discarded optimization is not required
to preserve all six strikes in the production MPC exports.

Raw wall/thread-CPU samples and the full protocol are retained in
[the benchmark receipt](../reference/halobar/filter-trial/benchmark-results.json).

The discarded source is preserved as [a zero-context patch](../reference/halobar/filter-trial/paired-filter-candidate.patch). Replay it with `git apply --unidiff-zero` in an isolated checkout with the original DSP; it is not part of the shipping source.
