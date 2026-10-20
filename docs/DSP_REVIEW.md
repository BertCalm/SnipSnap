# DSP review — 2026-10

A deep read of the DSP surface (`:audio`, `:synth`'s shared `Dsp` toolbox
and FX rack, and the native engines under `app/src/main/cpp`), scored on
quality, reliability, fidelity, efficiency, extensibility, dynamism and
expandability. Everything below is either measured in this pass (numbers
quoted, tests named) or explicitly marked deferred with the reason.
The codebase's own discipline — measure never guess, bit-identity where
goldens depend on it, taste changes wait for an audition gate — decided
what was fixed in code and what was only written down.

## Fixed in this pass

### Fidelity: far-ratio resampling drooped (measured, `:audio`)
`Resampler` documented its own hole: a fixed 16-source-tap kernel loses
bandwidth as the ratio falls. Probed before the change: 44.1k → 8k read
−2.2 dB at 3 kHz and −5.0 dB at 3.8 kHz. The kernel now widens below
0.5× (`Resampler.halfTapsFor`), holding support constant in *output*
samples (8, then 16 below 0.25×). After: flat to 3.4 kHz, −1.9 dB at
3.8 kHz (95 % of Nyquist, inside any windowed kernel's transition band),
6 kHz alias probe at 3×10⁻⁵. Ratios ≥ 0.5× — including both of
`Dsp.decimate`'s 2:1 steps, so every oversampled synth render — keep
their exact historical weights; the fast power-of-two path and the
general loop still agree to the bit (`ResamplerTest`).

### Fidelity: FFT twiddle drift (measured, `:audio`)
`Fft.forward` ran its twiddle recurrence in `Float`; each stage rounded
the phasor again. Against a naive double DFT the relative error was
2.0×10⁻⁵ at 4096 and 3.6×10⁻⁵ at 8192. The recurrence (and the
butterfly's twiddle multiply) now runs in `Double`: 1×10⁻⁷ at both
sizes, ~300× tighter, at offline-analysis cost only. Every spectral
consumer — classifier, `Spectral`, `Pghi`, pitch refinement — inherits
it. `FftTest` pins the DFT agreement. Also: `magnitudeSpectrum(size=1)`
divided by `size − 1`; it now passes the sample through.

### Reliability: one non-finite sample could poison a whole render
`Snip.peak()` and `WavWriter` already scrub NaN/Inf, but the gain
staging between them did not: `Dsp.normalize`/`limitPeak` took an Inf
peak, computed gain 0, and `Inf × 0` spread NaN across the buffer;
`Loudness.of` let one NaN poison its low-cut state, and `levelTo` then
multiplied the NaN into every sample; `Cleanup.normalize` passed NaN
straight through `coerceIn`. Now: peaks are finite-only
(`Dsp.finitePeak`), every scale scrubs its result to silence, the fold
normalizers treat a non-finite channel sample as 0, and `Loudness`
reads non-finite as silence. Finite buffers are bit-identical to
before; degenerate buffers now get a hole where the bad sample was
instead of a dead render. Covered in `DspTest`/`TruePeakTest`.

### Fidelity/expandability: true peak exists now (`:audio`)
Sample-peak ceilings (e.g. `levelTo`'s 0.99) cannot see inter-sample
overshoot — a quarter-rate sine phased 45° between samples peaks
0.64 in samples and 0.90 reconstructed (+3 dB). New `TruePeak` object:
band-limited (windowed-sinc) inter-sample peak at 4× oversampling,
worst-channel, non-finite-as-silence, plus `limit()` that only ever
scales down to a ceiling (default −1 dB). Deliberately **not** wired
into export or `levelTo` by default: that moves shipped loudness and
is an audition decision. The measured door now exists for it.

### Quality/expandability: cubic varispeed read (opt-in, `:synth`)
`Dsp.readAt` — the shared head under SPEED and MOTION — was linear-only:
a triangle kernel, ~4 dB down at a quarter of the sample rate, imaging
on pitch-downs. It now takes `cubic = true` for Catmull-Rom reading
(edge frames replicated); measured RMS error on a quarter-rate sine at
half speed is ~0.4× the linear read's (`DspResampleTest`).
Default stays linear: both shipped stages were voiced against it, so
adoption is a per-stage audition, not a silent retune.

### Reliability: `FxChain.capTail` faded per sample, not per frame
The chain's tail cap ramped gain sample-by-sample across an interleaved
buffer, giving a stereo frame's two channels different gains (the exact
bug `Dsp.fadeTail`'s doc describes fixing elsewhere). It now truncates
and calls `Dsp.fadeTail` per frame.

### Efficiency: `levelTo` copied every buffer to measure it
`Loudness.of(Snip(buf.copyOf(), …))` allocated a full copy per render
call across every melodic engine. `Loudness` now has a raw-buffer
overload (`of(samples, channels, sampleRate)`); `levelTo` measures in
place. Bit-identical — Loudness never mutated its input.

## Deferred, with reasons

- **Native playback interpolation** (`PadEngine`/`SurfaceEngine` read
  linearly at up to 64× speed). Real aliasing/imaging cost on pitch-up,
  but the engines are documented and host-tested as linear reads and
  the change is audible on device — it wants the cubic/sinc prototype
  measured on the host harness and an on-device listen, in that order.
  The JVM-side cubic in `Dsp.readAt` is the prototype's first half.
- **`Dsp.Noise` is an LCG.** Its low bits and serial correlation are
  poor noise by modern standards, but every engine's grain/noise is
  seeded from it and goldens/classifications pin the exact sequence.
  A xorshift/PCG swap is a re-voicing of every noise-bearing preset:
  audition-gated, not a correctness fix.
- **`Dsp.Biquad` is direct-form I in `Float` — probed, attempted, and
  deliberately NOT landed.** The follow-up pass measured the expected
  accuracy gain (Float DF-I off by up to 0.25 dB at a 60 Hz/+9 dB shelf
  at 176.4 kHz; Double coefficients + DF-II transposed state holds
  <0.05 dB), implemented it, and ran the synth suite: exactly four
  assertions failed, all character goldens in resonant Biquad users —
  and one is not bit drift. Gyre's BODY band movement, a threshold the
  owner set by listening ("Body doesn't make an impact"), fell from
  the measured 8.1–14.4 dB range to 4.25 dB: the correctly-rounded
  coefficients re-voice the box bands the owner tuned. Terra's cavity
  golden moved 1.1 dB and Valve's V1-equivalence RMS (tolerance 1e-5)
  moved with them. Conclusion: this is an audition-gated re-baseline
  of at least Gyre, Terra and Valve, not a correctness fix that can
  land silently — the conversion patch is the easy part; it should
  arrive with re-listened BODY/TOUCH thresholds and re-measured
  goldens, in that order. Landed instead in the follow-up:
  `WavWriter` gained an opt-in `truePeakCeiling` (null = the
  historical bytes), the export-level door for `TruePeak`.
- **`Pitch.detect` autocorrelation is O(n·lag).** Fine at its 250 ms
  window (~10⁷ ops); an FFT/NSDF rewrite only pays off if windows
  grow, and risks re-opening the octave-error behaviour its local-peak
  rule was built to close.
- **`Pghi.integrate` rescans all frame×bins per disconnected island.**
  Worst case quadratic on long, sparse spectrograms. The stretches it
  feeds are seconds-long and dense; a bucketed candidate list is the
  fix if profiles ever show it.
- **Denormal tails in the IIR filters** (`Svf`, `TptSvf`, `Ladder`,
  `Biquad`, `OnePole`): states decay through the denormal range.
  Modern ARMv8/JVM costs are small and a flush-to-zero at ~−360 dB
  still moves low-order bits in pinned renders; left alone until a
  profile on device says otherwise.
- **`MELODIC_LOUDNESS_TARGET` is a stale measurement** (its own doc
  says so, pre key-tracking/oversampling). Re-deriving it is a
  listening-pass task already flagged in the source; this review
  confirms nothing about it changed.

## Verification

`:audio:test` green including the new `TruePeakTest`, the far-ratio
`ResamplerTest` cases and the DFT-accuracy `FftTest` case; before/after
probe numbers are quoted above from temporary measurement tests run
against the unmodified code (and removed). Full JVM suite: 5,153 tests,
the only failures on first run being two of this pass's own assertions
(an over-strong cubic bound, and the pre-existing cascade-vs-direct
decimate test whose premise the resampler widening deliberately
obsoleted — both updated to the measured reality, with the reasoning
recorded at the test). One side-effect worth knowing: the widening
also closed the gap `Dsp.decimate`'s cascade was built for; the cascade
is retained for render bit-identity, and its doc now says so.
