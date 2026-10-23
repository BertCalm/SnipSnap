# Aquarium audition core

This is a host-side listening prototype of XOmnibus Aquarium, extracted into standard C++17 without desktop JUCE. It is **an audition extraction with documented corrections**, not an unchanged desktop plugin or a completed SnipSnap/Android integration.

The original six-stage order remains **Fathom → Drift → Tide → Reef → Surface → Biolume**. All stages bypass exactly when their mix is zero. A single processor runs continuously across the complete input, including silent gaps, which demonstrates a shared performance environment.

## Build and render

```sh
./build.sh
./aquarium-render --input input.f32 --output aquarium.f32 \
  --sample-rate 48000 --preset full --seed 1337 \
  --bpm 110 --tail-seconds 3 --block-size 256
```

Input and output are **little-endian stereo interleaved IEEE float32**, with no header. The parent audio pipeline handles WAV decoding, loudness matching and final WAV writing. `--tail-seconds` appends zero input rather than truncating processor state. The default tail is zero. Output is unclipped; the CLI reports peak and frame count as JSON. Non-finite input or output is rejected.

`--set aqua_surface_gain=-3` overrides a canonical upstream control. Unknown controls and non-finite values are rejected; out-of-range finite values are clamped to their upstream range. `parameters.json` records those ranges. `--list-presets` lists all eleven presets.

## Audition presets

The `Presets.h` settings are deliberately moderate and common to every source, so comparisons do not hide source-dependent tuning.

| Preset | Active processing |
| --- | --- |
| dry | Exact zero-mix bypass |
| fathom | Pressure/weight: mix 40%, depth 28%, darker character |
| drift | Wandering stereo delay voices: mix 25%, 5 ms deviation, light feedback |
| tide | Tempo-synced amplitude and filter breathing: mix 55%, depth 38%, 60° stereo offset |
| reef | Dense enclosed space: mix 24%, modest size/decay, 9 ms predelay |
| surface | Submerged brightness: 80% blend of a −4.5 dB shelf near 3.2 kHz |
| biolume | Experimental feedforward harmonic shimmer: mix 28%, modest drive |
| depth | Fathom + Surface |
| motion | Drift + Tide |
| color | Reef + Biolume |
| full | All six, at reduced blend amounts |

The `full` chain uses stage blends of 32%, 18%, 35%, 18%, 70%, and 18%, respectively. This is a comparison preset, not a final product default.

## Pinned source and rights

Source repository: [BertCalm/XO_OX-XOmnibus](https://github.com/BertCalm/XO_OX-XOmnibus/tree/a9bdc7803d899f0b1688fe0c0abc4ce481a76c3e).
Pinned commit: `a9bdc7803d899f0b1688fe0c0abc4ce481a76c3e`.

Unmodified originals are retained under `upstream/`:

- `Source/DSP/Effects/AquaticFXSuite.h`
- `Source/DSP/Effects/MultibandCompressor.h`
- `Source/DSP/FastMath.h`
- `LICENSE`

The MIT license and original XO_OX copyright headers are preserved. `provenance.json` records upstream file hashes. `extract.py` reproduces the JUCE-free corrected headers under `src/`. The CLI, presets and validation harness also use MIT licensing.

## Corrections made for the audition

1. **Host separation.** JUCE/APVTS wiring is replaced by a plain parameter struct and a validated setter. The upstream layout/cache name mismatch for the Fathom high crossover is normalized to the declared `aqua_fathom_highXover`. `prepare` validates the sample rate and initializes all state; `reset` clears state rather than releasing allocations.
2. **Surface/Tide filters.** The upstream high-pass calculation subtracts an integrator state twice; its neutral shelf colors the input, shelf directions are reversed, and the bell is non-neutral at zero gain. Corrected TPT mixing coefficients restore exact zero-dB identity, the intended shelf directions and center bell gain. Gain-dependent shelf prewarping places the half-dB midpoint at the selected cutoff. Shelf root gain is calculated consistently. Resonance now reduces damping as it increases. Exact tangent prewarping permits cutoffs above one-quarter sample rate; cutoff remains bounded to 45% of sample rate.
3. **Fathom envelope and stereo split.** The upstream detector compares `abs(detectDb)` with a signed dB state, causing release selection to fail. A linear stereo-linked envelope now selects attack/release by signal magnitude before conversion to dB. The right-channel high crossover previously read the left-channel residual; it now reads the right residual. The complementary split topology and upward/downward compression thresholds and ratios are retained.
4. **Drift movement and reproducibility.** The upstream `rate` argument is unused and its per-sample velocity damping changes with sample rate. A first-order Ornstein–Uhlenbeck recurrence gives rate a defined correlation time, with sample-rate-scaled, variance-normalized random innovations. The deterministic xorshift seed is reset explicitly. The original fractional delays, voice summing, feedback filtering and blend remain.
5. **Tide clock.** Right phase is derived from the left phase and stereo offset each sample, avoiding block-dependent phase offset rounding. Sample-and-hold uses a deterministic seeded target instead of sampling almost the same sine phase every cycle. Its held target is shared between channels. The sine, triangle, continuous performance clock, tempo divisions and minimum tremolo gain are retained; no lunar waveform or starts-open pedal TREM behavior is added.
6. **Previously overridden raw controls.** Drift rate/diffusion and Tide rate now scale their existing movement-macro mappings relative to the upstream nominal control values. Reef size/decay similarly scale the space mapping. This leaves the original mapping at default raw settings while allowing exposed raw controls to work. This is audition parameter behavior; Android macro semantics remain a design decision.
7. **Biolume bounded state corrections.** Closed gates now feed zero while the allpass and pitch ring continue clocking, avoiding frozen/replayed contents when the gate reopens. The right-channel decorrelator now returns its computed allpass output. Fold saturation uses a bounded periodic triangular fold, avoiding upstream outputs outside the fold ceiling for high drive. The original harmonic path and unwindowed pitch ring remain; no feedback loop has been added.
8. **Offline math accuracy.** Standard `exp`, `exp2`, and tangent are used where appropriate. The original `fastPow2` implementation gave +5.937 dB for a requested +6 dB and used a negative signed bit shift; standard math avoids both issues. Existing denormal flushing and the original sine/log/saturation approximations remain. This is an offline prototype, so its timings cannot predict mobile callback cost.

## Verified behavior and limits

`validation.txt` records the native behavior-check results. Checks cover bit-exact dry bypass, zero-dB Surface identity, shelf endpoints/direction/midpoint, bell center gain, complementary Fathom neutrality, stereo linking, a loud-to-quiet release step, deterministic reset/reprepare, equal output across fixed and irregular block sizes, operative Drift-rate/Tide-phase controls, and finite, decaying impulse tails at 44.1, 48, 96 and 192 kHz. High-drive sustained inputs exercise all three Biolume saturation choices.

Limitations remain material:

- **Biolume is feedforward harmonic shimmer.** Its source comments mention feedback, but the processing has no connected octave feedback loop. Rectification already creates doubled harmonics; the additional unwindowed 2× ring reader is a rough pitch treatment and retains grain seams about every 100 ms at 48 kHz. Its high-pass path is the inherited input-minus-cascaded-lowpass approximation. This prototype does not validate a polished octave shimmer or a long diffuse tail.
- **Reef preserves the upstream hard 5000-sample ceiling**, so increasing sample rate can clamp long delay choices and change the reverberation character. Sample-rate checks establish finite/stable operation, not identical acoustics.
- **Fathom preserves a complementary cascaded one-pole split**, rather than implementing the textbook Linkwitz–Riley crossover named in upstream comments. Upward compression remains level-sensitive; source matching must occur before rendering.
- The native processor has no final limiter or loudness normalization. Comparison output normalization belongs to the surrounding audio pipeline.
- No Android latency/CPU/power profiling, JNI integration, live bus routing, parameter automation smoothing contract, or listening verdict is established here.
