# Spore snare reference reset

Status: Python listening study, not a registered engine. The owner rejected the previous prototype's attack, body and tail. This round has not received listening acceptance.

Repository reviewed: `010dac094964e7d21f65b289714637b7e499e55a`; study branch starts at `0b7e105e87fff1ec4ddd43532912f2afe2b9abf3`, which includes merged PR #1039. APIs inspected: `SporeMembrane.render/probe`, `Modes.Bank`, and the Luthier Python `seed_for`, `Noise`, `band_limit`, `decimate4`, `dc_block`, and `fade_tail`. The Python seed and output routines are approximations, not byte-compatible Kotlin ports.

The characteristic gesture is **short broadband crack → dense, pitched body → noisy decay that changes with strike strength**.

## Review findings

- **Fix now [A]: wrong reference proxy.** The prior audition's library snare concentrated about 95% of its first 65 ms energy in 150–900 Hz; the owner's live clip is about 27% in that band. Similarity to the library sample was not evidence of similarity to the requested sound.
- **Fix now [A]: too little upper-band energy.** The rejected synth puts 96.3% in 150–900 Hz and only 3.2% in 900–12,000 Hz. Merely adding a collision mechanism did not establish the necessary radiation spectrum. The new study fits the whole attack/body/tail balance together.
- **Try next [A/B]: fit simple, audible components to the actual reference.** Oscillators and decaying filtered noise are easier to calibrate than unmeasured masses, gaps and microphone gains. They still create every output sample procedurally.
- **Disclosed, not fixed:** the live reference is a mixed, processed recording with possible cymbal bleed. The fit cannot identify an isolated drum, its material or its microphone response. No claim of acoustic equivalence follows from spectral error.

## Reference and method

The supplied `screen-20261109-121121-1794247871859.mp4` shows the live 170 BPM drummer. Analysis uses its mono 44.1 kHz audio. Seven strikes calibrate the model; four separate strikes test the same-clip fit. Exact onset times and the audio hash are in `report.json`. The first 120 ms constrain the fit; the private page uses a 140 ms excerpt for listening, with a 3 ms closing fade. It is labeled as a mixed reference and is excluded from the synthetic download.

The renderer uses 46 analytic components: five candidate body frequencies × two decay times, plus nine noise bands × four decay times. The five frequencies (300, 520, 780, 1285, 1650 Hz) were selected from persistent spectral peaks; they are not claimed membrane eigenfrequencies. Noise-band edges, contact width, pitch recovery, decay dictionary, and velocity laws are voicing hypotheses. Nonnegative fitting determines component amplitudes from coarse spectrotemporal power. A second fit includes cross terms between components sharing a source. It never stores source phase, a waveform, an impulse response, or a sequence of spectral frames.

```
velocity + deterministic strike identity
    → analytic contact envelope and body oscillators
    → seeded noise through nine stable band filters, with analytic decays
    → fitted, fixed component amplitudes
    → sum at 176.4 kHz
    → 20 kHz band limit → two decimation stages → DC removal → 4 ms tail fade
```

Each hit uses fresh deterministic noise, contact width, phase and mode balance. Tuning stays constant across repeats. The noise tail is an audible statistical approximation, not contact-driven wire mechanics. There is no room, distortion or psychedelic processing on this study. No reference file is opened by `render()`.

## Landing and alternatives

The nearest siblings are SKIN (modal drums and noise bands) and ONSET (physical/electronic drum blend). This does not earn a new engine registration: it is a reference-calibrated sound study and potential snare voice. Kotlin integration should follow an accepted listening direction.

1. **Strong, DISTIL:** fit a small procedural basis to the reference. Clear crack/body/tail gesture; modest roster novelty; low numerical risk; one listening-study round. It wins because calibration can directly expose the previous spectral error.
2. **COUPLE:** expand the physical head/wire model and calibrate its radiation. Clear interaction gesture; higher mechanical novelty; high identification and CPU risk; several rounds without a guaranteed audible improvement.
3. **INVERT:** infer a short synthetic exciter for a fixed modal body. Clear strike-to-ring gesture; modest novelty; lower CPU cost but substantial risk that a fixed body cannot reproduce the changing noisy tail; another prototype round.

## Control contract

| Control | Default | Quantity | Interaction and check |
| --- | --- | --- | --- |
| TUNE | .5 | Oscillator and noise-filter frequency scale over eight semitones | Coupled body/noise register; edge waveforms differ after normalization |
| STRIKE | .5 | Contact width and spectral tilt | Velocity also changes width and tilt; timbral velocity test |
| BODY | .5 | Oscillator decay times | Altered body overlap with attack/tail; normalized waveform test |
| WIRES | .5 | Noise decay times, a statistical wire-tail analogy | Alters how long each noise band persists; normalized waveform test |

Finite 450 ms hits are appropriate here; HOLD and string STRUM do not apply. The page's three stem buttons expose additive components, not three voices. The study download contains three velocity levels × three distinct takes. Clip-wide comparison gains preserve candidate velocity and stem balances; raw files receive no per-hit normalization.

## Cost, stability and evidence

Stable second-order Butterworth sections and positive exponential decays have no nonlinear feedback or iterative solver during rendering. The offline fit is separate from rendering, and `model.json` caches its result. A production port can cache coefficients by TUNE/rate and render only active components. The Python renderer allocates the component bank; phone allocation and timing remain unverified.

Measured Python hit timing: median 83.72 ms, maximum 90.83 ms in the recorded six-hit run. This is not Kotlin or phone evidence. Four focused Python tests cover deterministic JSON replay, distinct takes, timbral velocity and amplitude order, silence, finite/peak/DC/tail checks over control edges and 24 takes, component recombination, control activity, and invalid inputs. Asset checks cover 24-bit WAV headers, hashes, download contents, and preservation of the nine generated velocity/take files. DOM mock tests cover source selection, raw/matched switching, stop and error handling; actual browser decoding remains unverified.

On the four held-out hits from the same clip, the mean absolute log-power error fell from 16.80 dB for the rejected model to 4.30 dB for this study, after one global energy alignment. The metric covers two STFT resolutions and active bins in the first 120 ms. It is a diagnostic, not a perceptual similarity percentage or evidence against other recordings. For the first candidate take, 65 ms energy shares are about 29% in 150–900 Hz, 32% in 900–3000 Hz, and 37% in 3–12 kHz. The first held-out reference is about 27%, 32%, 40%. The first 120 ms reaches 90% energy at 52.6 ms for that candidate and 49.6 ms for that reference.

**Heard:** only the owner evaluates sound; acceptance is pending. **Not established:** native saving/playback/export for this new model, MPC behavior, full drum shelf, authored roll/riser, complete kit, spectral convergence, and Android integration. Prior model tests do not transfer to this renderer. Melodic/keygroup/chord-tool gates are inapplicable. No JVM code changes; the full JVM suite was not rerun for the Python study.

## Why it is built this way

- The comparison now uses the supplied live clip, so a dull library sample cannot silently define the target.
- A small oscillator/noise model can reproduce measured spectral balance without pretending the physical drum has been identified.
- Separate analytic decays give the crack, body and tail different jobs while retaining one coherent strike.
- Fixed gains preserve softer hits and repeat differences; audition matching remains outside the renderer.
- The previous prototype stays available so the new round can be judged against the rejected sound directly.
