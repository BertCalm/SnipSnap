# Native parade render speed and alias audit

Baseline source: `6e76ec3b3`. This audit covers the Field/Street DRUMLINE renderer (21 voices/figures), Samba (16 voices), and Second Line (18 voices including both triangle articulations). Shared-host measurements are not phone acceptance.

## Reproduction

Run the tasks separately from parallel test workers when timing:

```
./gradlew --no-daemon :synth:benchmarkParade -PparadeAuditOutput=/absolute/speed.csv
./gradlew --no-daemon :synth:benchmarkParadeSecondLine -PparadeSecondLineSpeedOutput=/absolute/secondline-speed.csv
./gradlew --no-daemon :synth:benchmarkParadeTuned -PparadeTunedOutput=/absolute/tuned.csv
./gradlew --no-daemon :synth:benchmarkParadeKits -PparadeKitSpeedOutput=/absolute/kit-speed.csv
./gradlew --no-daemon :synth:auditParadeOutput -PparadeAuditOutput=/absolute/output-reference.csv
./gradlew --no-daemon :synth:auditParadeKits -PparadeKitAliasOutput=/absolute/kit-reference.csv
```

Each benchmark case records an initial render and the median of three following renders, plus a raw Float PCM SHA256. Initial Second Line cases clear the crash-source cache; JVM/coefficient caches are not reset, so this is not a full app cold start. The base matrix has 110 cases: defaults at velocity 1/take 0 and damped soft contact at velocity 0.3/take 2 for each voice. Source/filter state is local to each rendering. Kit tasks use the actual pinned kit recipes, including Street's settings, rather than substituting engine defaults. The additional tuned pass uses approximately 1.1× transposition on Second Line.

## Speed and unchanged audio

All 110 base-matrix Float fingerprints are identical before/after the optimization. Existing Field/Street and Samba arithmetic is unchanged. Second Line's expensive measured crash source is independent of tuning/strike/damping, allowing its recomputation to be avoided during subsequent control edits. A synchronized two-entry LRU stores only the source keyed by velocity/take, returns a copy for all callers and holds at most 12,700,800 bytes of Double payload (excluding object overhead and per-render working buffers). There is no full-output cache. Different velocity/take values can miss this cache.

Neutral-tuning warm cymbal cases improved from approximately 763–818 ms to 17–40 ms on this host. Initial renders after source-cache clear still cost approximately 767–920 ms. These figures are steady-process measurements, not a phone budget pass. Field/Street rolls remain around 3.7–3.9 seconds, and some Samba section renders remain around 0.4 seconds. Their cost still needs further work. Tuning interpolation has its own cost; do not generalize neutral-tuning cache results to every control position.

The cache test compares fresh and cached source PCM, mutates a returned buffer, checks eviction and confirms the two-entry bound. Removing only the return-copy guard made it fail at the named cache-mutation assertion, with sample 100 changed to 12345. The guard was restored and the positive tests rerun. This proves the copy guard, not just an exception type.

## A detected internal tuning fold

Second Line's fitted-source tuning previously used linear interpolation. A coherent 15 kHz synthetic tone shifted 1.1× should land at 16.5 kHz; linear interpolation also created a 1.14 kHz fold. Measured through the production band limit and decimator, that fold was -40.70 dB relative to the intended tone.

Native model 4 uses a 64-tap Blackman-Harris sinc sampler with 1024 linearly blended phases and at most two immutable coefficient banks. The same diagnostic measures -142.09 dB at the fold frequency, below its -95 dB diagnostic requirement. The linear path is retained as a negative control and must expose a fold above -65 dB. Unity-rate input is copied exactly and DC preservation is checked away from the finite end. This is a coherent-tone interpolation test, not an 8× reference for the full stochastic synthesizer.

Models 1–3 retain the prior tuning path. Model 4's unity-tuning path retains the original arithmetic, which preserves the benchmark PCM. Native kit recipes pin model 4. Non-default fitted-source tuning intentionally changes; its waveform need not match the earlier interpolation artifact. Two coefficient banks hold 1,049,600 bytes of Double payload, plus object overhead. Cache/bank sizes are bounded; their payloads are not total app-memory measurements.

## Output-stage reference

The existing `DrumlineEdgeReference` method is reused across all three renderer families. The base matrix contains 165 cases: default settings, maximum tuning/strike with minimum damping, and maximum tuning/strike/damping at velocity 1, using take 0 for defaults and take 2 for bright cases. DRUMLINE's bright cases use maximum players/tightness. Samba/Second Line retain their fixed instrumentation. This is a targeted stress set, not every velocity/take/control combination.

The method starts after the production 176.4 kHz, 19.5 kHz eighth-order Butterworth band limit. It spectrally removes energy above output Nyquist in a padded FFT reference and passes both paths through the same production decimator. Actual unpadded output is compared against the aligned reference window. Separate columns report buffer-edge error, projected out-of-band contribution and the residual of reconstructing total error from those components. Thus total reference error is not isolated alias power; ordinary edge behavior is included. The 20 Hz–18 kHz column isolates a useful output sub-band but is not a complete audible-band acceptance rule.

All 165 cases have finite results and decomposition residual below -110 dB; the worst residual is below -140.02 dB. The largest whole-band total error is -33.18 dB for bright/damped muted triangle; most of that contribution lies above 18 kHz. The largest 20 Hz–18 kHz total error is -66.59 dB for bright/damped Samba high agogo. Those residuals remain engineering/listening considerations, not an inaudibility claim. Existing smooth-tone, high-tone, boundary-impulse and doubled-window controls exercise the method. Additional doubled-window checks target Samba and Second Line sources.

## Gate boundaries

The measured interpolation defect is corrected, and the current output-stage reference is broader than the earlier DRUMLINE-only audit. Full internal-rate synthesis alias acceptance still needs a higher-rate/analytic reference for the complete source mechanisms; the synthetic tuning control does not supply that proof. Near-Nyquist output contributions also remain visible. Speed acceptance remains open for cold sources and long/section figures, and phone/MPC validation remains open.

The preceding integration's JVM-other failures involved missing temporary kit directories, not failed sound assertions. Locally, 19 old generated `jcard`/`kitart`/`starters` fixture directories occupied most free space; removing only these stale fixtures restored approximately 19 GB free. The serial rerun was invalidated by rebuilding a shared JAR while its test worker was still active: a later starter raised `NoClassDefFoundError` although the rebuilt JAR contains the named class. That run was stopped (exit 143) and is not a valid pass or a diagnosis of the earlier CI failures. An unfiltered rerun against fixed build artifacts follows the final focused tests. Full regression and CI results must be reported independently.

## Why it is built this way

- Bounded source reuse improves real cymbal control edits without replacing deterministic excitation or caching the whole sound.
- Exact output hashes distinguish a performance change from a sound change.
- A coherent synthetic tone exposes a tuning fold that broad noise or envelope comparisons can hide.
- The same-passband output reference separates filter/buffer effects from the interpolation diagnostic.
- Explicit gate boundaries prevent one successful diagnostic from becoming an unsupported whole-instrument pass.

## Actual kit recipe measurements

The final isolated pass covered all 80 configured pad recipes across the five layouts, plus 18 approximately 1.1× tuned Second Line cases. The long unrelated kit-art regression worker was temporarily paused for this timing pass and resumed afterward; no tests were discarded. The three benchmark/reference tasks completed with exit 0 in 2m44s.

| Kit layout | Slowest warm median | Voice/figure |
| --- | ---: | --- |
| Field Cadence | 3464 ms | Roll |
| Street Cadence | 2264 ms | Riser |
| Samba Bateria | 476 ms | Repinique call |
| Second Line Classic | 1132 ms | Snare roll |
| Second Line Triangle | 1072 ms | Press buzz |

The cache does not make all tuned cymbal edits take 17–40 ms. With approximately 1.1× tuning, warm Second Line crash/choke/touch measured 143/60/33 ms, while initial source-cache-cleared renders measured 1059/831/728 ms. This distinguishes source reuse from the cost of band-limited tuning itself.

The additional 80 configured-pad output references all passed finite/decomposition checks. Their largest whole-band error was -42.50 dB for Street flam; the largest 20 Hz–18 kHz error was -69.50 dB for Second Line Classic rimshot. Combined with the stress matrix, there are 245 output-reference cases. Reference errors include buffer effects and are not universal perceptual acceptance scores. Complete CSVs and input-scope/summary JSON are in `docs/evidence/drumline/parade-audit`.

## Cold-source refinement and final validation

A further optimization precomputes the measured crash envelope interpolation addresses and weights once, preserving the original expression and its operation order. The hard and soft measurements share their time grid, so the immutable lookup uses 2,381,400 bytes of array payload, excluding object overhead. If future data supplies distinct time grids, the implementation uses two grids. This replaces four binary searches per band/sample while retaining deterministic noise, modal synthesis and diffusion.

The final Second Line-only pass contains 36 cases, all with exact Float hashes matching the original baseline. The earlier 110-case comparison still covers the other families, whose arithmetic was not changed by this refinement. Initial cymbal renders after clearing source reuse measured 559–573 ms after grid initialization; the first measured crash took 884 ms. Warm neutral cymbals measured 16–38 ms. These are host measurements with a continuing JVM, not cold app startup or phone acceptance. The cold source and long figures remain above the target. The final CSV is `parade-speed-cold-refined.csv`.

The coherent-tone tuning, cache isolation/eviction, legacy PCM and macro-corner checks passed before this refinement. Five Samba/Second Line doubled-FFT-window convergence checks also passed. The final focused run completed with exit 0 in 1m36s: eight tests across `SecondLineTest`, `ParadeCacheTest`, `ParadeRetuneTest` and `ParadeOutputAuditTest`. This includes the 144 binary macro corners, legacy triangle PCM, controls and progressive muted release. The full unfiltered `./gradlew --no-daemon test` run is started before push and remains in progress at this checkpoint; it is not a passing gate. Android compilation requires CI because this host has no SDK. Device performance requires its own validation.
