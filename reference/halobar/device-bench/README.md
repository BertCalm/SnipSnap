# HALOBAR device diagnostic evidence

This round adds an opt-in Android benchmark and a checked Python runner. It
changes no production DSP, presets, cache budgets or export behavior. The frozen
integration base is `23cde0d1c107039c23fd74ff5c3d2b359a7f6e75`.

## Desktop stage investigation

`profile.py` compiles an external copy of `Halobar.kt` with monotonic timers
around bars, echoes, downsampling, phase, halo convolution and finishing. No
audio expression changes. `inputs.json` identifies the source actually read:
`121949b95929029738b92c7b8a8f0b622e982e89`, SHA-256
`f93bf4f12ce5808c0d63d3bc5a4ba00352b99427a9acb342625e5d88f9fa9261`.
HALOBAR, Dsp, Tide, Fft and Resampler sources are byte-identical between that
snapshot and the integration base. The instrumented copy is not in production.
`dsp-unchanged.json` records the five source hashes.

`StageBench.java` runs six voices × default, long and dry recipes, with six
warmups and twelve measured fresh renders per case. All four audio caches clear
before each call; immutable kernel spectra and runtime/JIT may be warm. All
threads are pinned to the lowest available CPU (CPU 0 for this run), JDK
21.0.13+9, Kotlin 2.0.21/JVM 17, G1, 1 GB heap, four logical processors, one
parallel and one concurrent GC worker. The raw 216 samples are in
`stage-times.tsv`; per-stage medians are in `stage-results.json`.

The downsampling group and halo are the largest default-render stages in this
diagnostic. For vibraphone, their individual medians were 123.3 and 88.9 ms;
for tubular bells, 130.6 and 85.9 ms. The group includes the existing
anti-alias filter and two sinc decimation steps. Improving only the source
cannot reach the 50 ms whole-render target. This points the next investigation
at filtering/decimation and convolution after measuring the phone runtime.

These instrumented desktop samples are stage attribution, not a replacement
for the earlier uninstrumented ABBA benchmarks. Added timers and altered JIT
shape affect cost; scheduling/GC variation is present. Crotales' default
median was 521.8 ms while its longer recipe measured 325.3 ms, so these numbers
do not support a precise voice ranking or a speed-improvement claim. Medians
of individual stages need not sum to the median of whole calls. This round
does not claim a new latency improvement or a phone measurement.

`validate-profile.py` selects four existing published fixture cases per voice.
All 24 frame counts and Float32 SHA-256 values matched with the stage-timed
copy. `profile-parity.tsv` preserves the selection; `profile-validation.json`
records the result. This checks the diagnostic copy; it is not a claim that a
new audio model passed or that a physical device was tested.

The archived scripts retain the actual `/workspace` paths used for this
investigation. Rebuild module classes first and adjust the repo/work/JDK and
runtime-classpath paths for another machine. The Android diagnostic and its
portable runner are documented in
[`docs/HALOBAR_DEVICE_BENCH.md`](../../../docs/HALOBAR_DEVICE_BENCH.md).

## Runner checks

`check-runner.py` exercises the real Python runner against an isolated simulated
adb. A successful 48-case report is accepted; a zero-exit instrumentation
failure, wrong installed APK, stale report, incomplete case set and likely
emulator are rejected. Wrong APKs stop before instrumentation; a rejected
emulator stops before installation. All six scenarios passed, as recorded in
`runner-validation.json`. This is host control-flow validation, not Android
execution, physical playback or listening approval.

`android-validation.json` records successful app/test assembly (actual Gradle
exit zero), APK fingerprints, matching verified v2 signers, package identities,
instrumentation target and packaged DEX markers. The instrumented benchmark
itself was not executed: adb listed no connected device. Full JVM tests were
not rerun for this test/runner/documentation-only patch.

## Acceptance ledger

- Verified: existing HALOBAR production sources are unchanged; external
  diagnostic parity and host runner scenarios pass.
- Measured: instrumented desktop stage times only, with the limits above.
- Proposed: use actual Android render timings to choose the next larger
  filtering/convolution optimization.
- Heard: all six voice banks retain the owner's earlier approval. No new
  performance-example verdict was received in this round.
- Open: physical Android execution/playback, MPC velocity × round robin and
  the 50 ms fresh-render target. Compiling the Android diagnostic does not
  close these gates.
