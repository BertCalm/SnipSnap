# Spore approved snare port and kick study

Status: the owner approved the Python snare on 2026-11-14 (“Ok, sound good”) and authorized its port, workflow checks, and a reference-based kick study. Preserve that accepted snare; the kick remains unapproved. The complete psychedelic jungle kit remains in progress.

Repository read: `dd508058`, integrating default `23cde0d1` and the prior snare study. APIs grepped before use: `Dsp.seedFor`, `Snip`, `KitAssembler.assembleArranged`, `KitStore.load`, `PadHit.resolve`, `KitPreview.render`, `SfzWriter.write`, `SfzImporter.import`, `Mpc3Exporter.exportTrack`, `ExportReadBack.verify`, and `Mpc3Project.read`.

The snare gesture remains **broadband crack → pitched, dense body → noisy decay**. The new kick gesture is **beater strike → falling head pitch → low body decay**.

## Preserve the approved source

`SporeSnare` ports the same 46 analytic components, fitted amplitudes, envelopes, velocity laws and four controls from `scripts/spore_snare_study/model.py`. It reads no recordings or PCM at runtime. The model JSON SHA is compiled into the numeric coefficient snapshot and persisted with the native kit.

The first 32 take seeds preserve the Python study's BLAKE2-derived seed mapping and 64-bit LCG stream. Further take identities use `Dsp.seedFor`; only the canonical 0–2 grid is delivered. A documented seed-stream exception prevents a house RNG substitution from changing the sound the owner approved. `SporeSnareData` stores numbers, not audio. Mutable arrays remain internal to the source implementation; each render creates its own filter state.

The filter implementation uses a bilinear-transformed, second-order Butterworth bandpass prototype, with two stable conjugate pole pairs and zeros at ±1. Coefficients are computed once per render/control setting; no iterative solve runs per sample. Body and noise decays use positive exponential factors. A fixed output gain preserves velocity dynamics; no limiter or per-hit normalization hides overload.

The Double-precision conditioning reproduces the Python 20 kHz filter, two 63-tap decimation stages, DC removal and 4 ms fade. It deliberately does not substitute the house Float implementations into this accepted source. This is an exact-source preservation decision, not a change to shared DSP. Seventeen compressed Float32 waveform oracles live in test resources only: the nine accepted velocity/take cells and eight single-control edges. The runtime never loads them.

The initial parity run's largest absolute sample error was 9.32e-10 across all 17 cases, less than 0.01 PCM24 step. The nine delivered cells differed by at most 5.83e-11. Regression limits require maximum error below 1e-7 and relative RMS error below 1e-5. Negative zero is accepted as silence; it carries no audio energy.

## Control and saved-source contract

| Control | Default | Preserved effect |
| --- | --- | --- |
| TUNE | .5 | Oscillator and noise-band register, eight semitones across range |
| STRIKE | .5 | Contact width and spectral tilt, interacting with velocity |
| BODY | .5 | Body-mode decay times |
| WIRES | .5 | Noise-band decay times; statistical tail analogy, not a wire simulation |

Velocity changes force, contact width and brightness. Round robin changes noise, contact width, oscillator phase and balance without retuning the drum. Hits remain 450 ms and finite. HOLD and string STRUM are inapplicable to this source.

`SporeSnare.Recipe` stores model version, controls, velocity and take, rejecting unknown models and invalid values. `SporeSnareBank.arranged()` supplies one native pad with a 3 × 3 grid at velocities .3/.65/1.0 and takes 0/1/2. The native pad's source metadata carries the base recipe and exact grid convention. It is an importable instrument and callable Kotlin source, not a new one-voice engine or app-editable `PadRecipe` registration.

The export task checks recipe replay against all saved PCM windows, actual `PadHit` velocity/take selection and wrap, full saved-kit preview versus a noncycling counterexample, SFZ export/import and replay windows, and MPC 3's three cycle-bearing velocity layers plus packed PCM. It intentionally omits MPC 2, whose fallback cannot preserve the complete repeat grid. File read-back does not prove hardware behavior.

## Kick: a separate hypothesis

The kick is a Python study using five candidate body frequencies (62, 96, 148, 187, 240 Hz), two body decays and eight noise bands with four decays each. It has its own dictionary, beater envelope, and downward pitch relaxation. None of its fitting changes the approved snare.

Seven kick strikes from the supplied live 170 BPM recording calibrate the amplitudes. Four separate strikes from the same recording check the result. Measurements target the first 120 ms using 512- and 2048-sample STFT frames. A 140 ms mixed-source excerpt is supplied for private comparison; it is excluded from both downloads.

Measured held-out log-power error changed from 32.09 dB for the rejected kick to 4.46 dB for this candidate, with one global energy alignment. This is not a listening score. In the first 65 ms, the first candidate's broad energy shares are 65.3% below 150 Hz, 29.2% in 150–900 Hz, 2.1% in 900–3000 Hz and 2.5% in 3–12 kHz; the first held-out reference is 62.1%, 32.0%, 2.1%, 2.8%. The candidate reaches 90% of the first-window energy at 71.1 ms versus 88.0 ms in that reference, so tail agreement remains imperfect. Microphone coloration, processing and other drum bleed are not separated.

Nearest siblings remain SKIN and ONSET. This is a source study and bank, not evidence that a new engine is needed. The chosen direction is **PORT** for the approved snare and **DISTIL** for the kick. A larger coupled kick simulation would increase identification and CPU risk without an established sonic benefit; a pure swept sine would be cheaper but omit the measured shell/beater bands. The compact procedural dictionary wins this listening round.

## Verification and remaining gates

- **Owner-heard:** Python snare accepted on 2026-11-14. Kick pending. The assistant makes no listening claim.
- **Measured:** Kotlin/Python snare waveform parity, deterministic recipe replay, timbral velocity, distinct takes, bounded/finite output, DC and tail; Python kick has equivalent focused rendering tests.
- **Workflow:** native save/load and actual offline playback, SFZ grids/PCM/replay and MPC 3 grid/PCM/read-back are checked by `SporeSnareDelivery` and its integration test. The delivery report records measured JVM render times.
- **Not established:** Android audio, physical MPC playback, editable app synth picker, full kit/cymbals, multiple genre kits, authored roll/riser and complete engine gates. Melodic/keygroup/chord-tool requirements do not apply to these drum sources.
- **Performance:** the kick's six-hit Python run measured 96.06 ms median and 99.52 ms maximum. Kotlin snare timing was 22.03 ms median in the delivery run; the maximum and exact values are recorded in `snare-delivery.json`. Neither is a phone benchmark. Render state is per-hit; coefficient design is bounded and outside the sample loop. A production cache may key band coefficients by TUNE and model version if phone profiling justifies it.
- **Scope of regression:** focused source, determinism and delivery tests plus artifact/page checks. No existing engine signal path changes; the full JVM suite and Android build are not claimed here.

## Why it is built this way

- The accepted snare is treated as a source contract, so the port cannot quietly become another sound-design round.
- Waveform comparisons catch seed, filter and envelope drift that broad spectral measurements would miss.
- The saved instrument keeps every velocity/take cell rather than exporting only a good-sounding first hit.
- The kick gets its own reference fit and approval gate, while the existing snare remains available unchanged.
- The dry combined phrase exposes how the two cores work together before hats, space or effects can obscure them.
