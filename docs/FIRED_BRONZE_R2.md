> Listening update: Josh described this dry revision as “Way better.” Its renderer is
> retained unchanged in the [new dry kit round](FIRED_BRONZE_DRY_KIT.md); new kit drums still await listening.

# Fired Bronze R2: dry reference study

Status: replaces the *approach*, not the saved R1 renderer. R1 was rejected by
Josh as sounding like a basic drum machine. R2 must earn acceptance dry.
Repository baseline: `fd66d18b6bc3f4ddb37b98b83e65d6925e7557ce`.

The characteristic gesture is **specific contact → articulation-dependent
partial evolution → pressure or cavity motion → a natural-length study tail**.

## Landing and evidence

A separate analysis/resynthesis study, not a registered engine or finished kit.
`FiredBronze` remains model 1 and replays old recipes. `FiredBronzeR2` uses its
own model-2 schema. No first-build mode table is silently upgraded.
SKIN remains the drum-kit foundation; TERRA remains the nearest physical body
sibling. Neither supplies the five measured articulation profiles here.

Chosen direction: DISTIL recorded spectral behavior into additive/stochastic
parameters. Strong measurable articulation contrast; moderate fit risk and low
runtime state cost. A fully coupled membrane solver would provide more physical
control but lacks calibration data and costs more. A sample-layer replacement
would match these recordings more directly but would not test synthesis.

Five HQ previews by ajaysm (CompMusic/Freesound) were obtained under CC BY 4.0.
The downloaded bytes are hashed, decoded, and fitted by the scripts. They are
short lossy reference excerpts, not uncompressed originals or complete natural
decays. Dataset names denote the uploader's stroke labels, not a universal
mapping across playing schools. No author was contacted or endorsement implied.

| Candidate | Evidence | What is still proposed |
|---|---|---|
| Edge Ring / Tin | 17 measured spectral tracks, strongest peak 587.79 Hz | residual noise phase, velocity/tuning/damping edits |
| Center Ring / Tun | 19 tracks, strongest peak 321.65 Hz | same limits |
| Rim Ring / Na | 20 tracks, strongest peak 882.52 Hz | same limits |
| Pressed Tap / Te | 20 tracks, diffuse short hit | same limits; short-window peaks are not modal eigenfrequencies |
| Bass Stroke / Ga | 20 tracks, strongest peak 120.79 Hz | same limits; 121 ms excerpt cannot establish pressure motion |
| Moving Bass | Ga-informed frequency region; literature links pressure and decay | six-partial model, bend and mode-specific loss law |
| Open/Covered Clay | separate shell and Helmholtz cavity mechanisms | all mode values, coverage motion, losses and contact noise |
| Measured Kettle | Sethares specimen: 595/904/2030/2326 Hz | gains/T60; no measured recording fit or nonlinear bloom |
| Long Gong | nine frequencies from the 56 cm brass kempul study | gains/T60, smooth initial pitch rise; no validated nonlinear coupling |

The strongest spectral peak is not necessarily the perceived fundamental.
For example, Tin and Na retain different balances above the tabla tonic.

## Source architecture

```
credited reference preview → offline spectral fit → immutable parameter JSON
                                               ↓
                          complex-envelope oscillators + shaped residual noise

published gong frequencies → independent decays + bounded pitch trajectory
pot mechanism hypothesis   → shell modes + separately moving cavity + contact
pressure hypothesis        → phase-continuous modes + pressure-dependent loss
                                               ↓
                   4× band limit → decimate → DC cleanup → house level → velocity
```

The fit retains up to 20 nonharmonically constrained peaks, with complex
amplitude envelopes sampled every 2.5 ms. Band-filtered analytical signals are
smoothed before sampling. Six broad residual bands carry RMS envelopes; their
noise realization is new and seeded. No recorded PCM is played by Kotlin.
The residual filter is an approximation, not the exact Python bandpass. Partial
phases/envelopes retain a specific recording's microphone and codec imprint;
this is data-driven synthesis, not a physical reconstruction.

Profiles are loaded lazily into a fixed five-entry immutable map. Oscillator
rotation uses double precision, unity-radius coefficients, finite envelopes and
no feedback gain. Pressure only adds loss. The eight-second gong is an audition
file, not mislabeled as a factory short-hit or seamless loop.

## Controls and contracts

| Control | Default | Effect |
|---|---:|---|
| TUNE | .5 | ±12 semitones, partials above 17 kHz omitted |
| TOUCH | .5 | frequency-dependent spectral tilt; velocity also softens touch |
| DAMP | 0 | additional exponential loss, never negative damping |
| MOTION | .5 | only pressure/pot/gong hypotheses: bend, opening or initial rise |

Velocity is applied once after house leveling. Off-reference controls are
hypotheses. No HOLD or factory UI is registered in this source-study round.
Target: tens of milliseconds for short sources; report long-gong timing
separately. There is no solver to precompute. Future kit integration requires
explicit handling of long tails and an accepted listening pass.

## Reproduction

```sh
python3 scripts/fired_bronze_fetch_references.py
python3 scripts/fired_bronze_fit.py
./gradlew --no-daemon :synth:test --tests '*FiredBronzeR2Test'
./gradlew --no-daemon :synth:generateFiredBronzeR2Audition
```

Python requires NumPy/SciPy and ffmpeg. The committed profiles let Kotlin run
without network or Python. Reference downloads can fail independently of builds.
Full credits and exact source hashes are in `synth/src/main/resources/fired-bronze-r2/`.
Fitting statistics are in `analysis.json`; residual-energy fractions describe
the decomposition, not perceptual similarity or proof of authenticity.

## Why it is built this way

1. Separate measured stroke profiles preserve spectral distinctions that a
   generic ring plus a shorter envelope erased.
2. Residual noise carries unpitched contact energy that pure sinusoids omit.
3. Pressure changes loss as well as pitch; moving only pitch missed that gesture.
4. Long gong rendering exposes the slow evolution before kit-length decisions.
5. Ghatam stays visibly uncalibrated; replacing one invented mode table with
   another must not be called a reference match.

## Validation

Native Kotlin checks on 2026-11-08, Temurin JDK21 / Linux Xeon host:

- `:synth:test --tests '*FiredBronzeTest' --tests '*FiredBronzeR2Test'
  --tests '*DeterminismTest.FIRED*'`: exit 0, **16 tests**, no failures/errors.
  Includes original recipe behavior, R2 replay, parameter extremes, measured
  spectral emphasis, pressure-dependent decay, cavity pitch separation, and
  meaningful gong energy beyond the old cutoff.
- `:synth:generateFiredBronzeR2Audition`: exit 0, 36 dry candidates, native and
  matched WAVs and complete recipes. Five references are matched by the same
  Kotlin `AuditionLevel` function as the synthesis.
- Python/SciPy measurements of exported Kotlin WAVs versus the acquired preview:

| Stroke | Reference / Kotlin dominant peak | Spectral cosine, 65 Hz–8 kHz | Normalized envelope MAE |
|---|---:|---:|---:|
| Tin | 587.79 / 587.79 Hz | .9997 | .0387 |
| Tun | 321.65 / 321.65 Hz | .9999 | .1286 |
| Na | 882.52 / 882.52 Hz | .9997 | .0269 |
| Te | 257.05 / 256.38 Hz | .9123 | .0184 |
| Ga | 120.79 / 120.79 Hz | .9996 | .0363 |

Cosine on a magnitude spectrum is dominated by strong peaks and is not a
perceptual score. These recordings were also the fitting inputs: there is no
held-out generalization result. Tun's envelope mismatch and Te's spectral
mismatch remain disclosed. The source is high-passed for fitting; comparisons
use the decoded reference, retaining its low-frequency and codec differences.

Warmed three-run medians after one warmup: Tin 106.9 ms, Tun 55.9 ms, Na 79.8 ms,
Te 14.0 ms, Ga 21.4 ms; pressure 181.8 ms; open/covered clay 64.0/51.0 ms;
3.5-second kettle 125.8 ms; 8-second gong 806.5 ms. These are host measurements,
not phone figures. Interactive phone performance is not established, especially
for the long study tails. Metrics CSV retains all variants.

The repository-wide `test --fail-fast` attempt reached its 180-second limit
(exit 124) without completing; no full-suite pass is claimed. Android SDK is absent. No owner listening acceptance,
higher-rate alias reference, or traditional-instrument fidelity is claimed.
