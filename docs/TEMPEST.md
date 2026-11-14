# TEMPEST: accepted model 2 and retained R1 record

**Current, 2026-11-07:** all six voices are owner-accepted, with 22 factory presets and a
full sixteen-pad starter. The original two-voice tuned kit remains available separately.
See [TEMPEST_FULL_KIT.md](TEMPEST_FULL_KIT.md) for accepted integration, validation and limits.
Model-1 and model-2 recipes retain their sound. The sections below preserve the first-build
findings and are superseded by the current six-voice integration record.

## Retained R1 record

Status: finite Kotlin listening prototype. The characteristic gesture is a pitched digital
strike → driven resonant bite → degrading short repeats → finite landing.

Two original voices, KICK and METAL, render mono 44.1 kHz for 1.20 seconds. This is not a
commercial circuit emulation. Source, contour, drive, filter and memory parameters are
shape values pending the owner's listen. Two default recipes are held in `TempestPresets` for audition; no factory presets or kit have been promoted.

## Controls

| Control | Behavior |
| --- | --- |
| TUNE | Semitone steps C1–C4, shared by both voices |
| SOURCE | Linear adjacent blend: sine, decaying harmonic PM, inharmonic cluster, rooted noise |
| FILTER | 80 Hz–10 kHz base cutoff, with a falling strike contour |
| DRIVE | 1–10 gain into a bounded asymmetric tanh curve before filtering |
| SPACE | Repeat send, feedback and wet level, with fixed delay time |
| HOLD | Reserved R1 control; every position produces the same finite sound |

KICK has a short pitch fall, lowpass and 12 ms memory. METAL uses bandpass and 35 ms memory.
The names describe synthesis timbres. PERC is the synthesis filing policy; the independent
capture classifier may identify a low strike as a drum. Its actual answers are recorded in
`metrics.csv`, not hidden by a classifier override or by changing the audio.

## Implementation

Read against repository base `df675d0`. FATHOM provides drive/filter bass but no integrated
memory tail. THUMP/SPARK provides a metallic falling strike but no adjacent four-source
morph or lossy feedback tail. The new engine is provisional until the owner's sibling listen.

`Tempest.Source` is bounded by 1 through convex blending. `Tempest.drive` is bounded by
1 + tanh(.2). The inspected `Dsp.TptSvf` uses positive damping (k = 1/Q), with normalized
band output `k*band` for METAL. Asymmetric drive is DC-blocked before that filter.
`Tempest.Memory` keeps both drive and resonance outside its feedback loop. A fixed integer
delay and convex one-pole loss filter have sup-norm gain at most 1; feedback at most .75
therefore contracts. At maximum SPACE, delay amplitude is bounded by the filter output
amplitude and mixed amplitude by 1.6 times that. This is conditional on a bounded filter
input/output, not a claim that every sample or short-time energy window falls monotonically.
The energy test uses separate attack/late windows and catches a deliberate 1.05 feedback
mutation. No final limiter supplies the loop stability argument.

Four-times processing uses `Tide.bandLimit`, `Dsp.decimate`, mean subtraction, fixed 20 Hz
highpass, `Dsp.levelTo`, and tail fade. Fresh buffers and filter state belong to each render.
Parameters, decay coefficients and fixed pole coefficients are computed once. There is no
simulation or mutable cross-note cache. The 1.2-second path processes 211,680 internal frames.
Source phase stays unwrapped so irrational partials do not reset at carrier wraps. Noise is
from `Dsp.Noise(Dsp.seedFor(...))`; unknown keys and HOLD do not affect its seed.

## Reproduce

```sh
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.TempestTest
./gradlew --no-daemon :synth:generateTempestAudition
./gradlew --no-daemon test
```

The audition writes native and matched PCM24 WAVs, an HTML player, exact saved patch JSON,
raw peak/prefade tail telemetry, capture classifications, and a 100-render warmed JVM timing
report into gitignored `testkit/tempest-audition/`. Open `index.html` locally. There is no
HOLD loop audition because no loop is implemented. Separate 4×/16× raw tonal-anchor files
support higher-rate-reference comparisons; noise at different sample rates is not the same
continuous signal and cannot be compared sample for sample as an alias measurement.

## Integration and remaining gates

Patch JSON registry, both velocity dispatches, prototype exemption, hand-built patch and
recipe roster fixtures, determinism canary, focused tests and audition task are included.
Velocity uses the existing peak-matched softening fallback, not an unproven monotonic
brightness override. Factory presets, full kit, app picker and HOLD remain separate rounds.
No app picker was edited without an Android SDK. HOLD R2 needs sustained excitation,
bounded warmup/cut search and a seam against actual preceding/continuing material.

Before promotion: judge the matched sweeps and pairs, compare siblings, inspect capture-class
mismatches and root ambiguity, measure cold/warm phone performance, and decide presets. The
original <20 ms target is a target, not a phone measurement or a test assertion.

## Why it is built this way

- Falling pitch, amplitude and filter contours give the strike a beginning and ending.
- Only a lossy filter lives in the delay loop, so the tail can degrade without runaway gain.
- Linear source blends retain a known amplitude bound without normalizing events.
- Reserved HOLD avoids presenting a decaying buffer as a sustained drone.
- Prototype registration keeps unlistened voices out of the factory preset roster.

## First Kotlin measurements (2026-10-28)

The focused run passed 42 tests: ten `TempestTest` probes, the TEMPEST canary,
three patch registry tests, twenty recipe tests and eight factory/prototype registry tests.
The subsequent audition wrote 72 exact recipes with native and matched WAVs. The numbers
below came from the R1 evidence records, which did not come back with this port; none was
heard at the time.

| Measurement | Result |
| --- | --- |
| Native sample peaks across 72 cases | 0.4486–0.9901 |
| Maximum absolute output mean | 2.41e-7 |
| Raw peaks across audition cases | 0.00861–1.48494 |
| Worst pre-fade last-10-ms RMS relative to raw peak | -83.34 dB |
| METAL default, 100 warmed production renders, JDK 17.0.21 amd64 | median 41.558 ms, p95 46.748 ms |
| Tonal 4×/16× independently decimated comparison | -55.12 to -34.08 dB aligned residual |

The 20 ms target is **not met on this desktop measurement**; no phone timing is claimed.
Profile the source/filter path on the target phone before optimizing or revising that target.
The high-rate comparison is an investigation result, not a clean alias-power measurement:
it includes sample-rate-dependent phase integration and TPT-filter response. Four of six
cases exceed the proposed -50 dB investigation threshold, worst at METAL's harmonic PM
anchor with maximum drive/filter. Do not claim the alias gate passed; isolate those stages
and compare their folded components before promoting the voicing. Raw comparison WAVs
use the same quarter-scale headroom factor to avoid PCM clipping and opt in to non-MPC
sample rates; production WAVs stay at 44.1 kHz.

Independent capture classes were 31 KICK, 8 TOM, 29 PERC, 1 CLAP and 3 SNARE. The proposed
“keep every render outside drum classes” gate therefore **does not pass**. PERC remains
explicit synthesis pad metadata. This prototype needs an owner decision on whether such a
gate is appropriate for an engine designed to make percussion. No classifier rules or audio
were changed to make that test appear green.

Short low kicks can return no raw autocorrelation estimate because their exponential
amplitude loss per period exceeds the detector's near-zero-lag comparison rule. The settled
root test removes the known decay slope from an actual 120–270 ms output window, then
measures frequency and confidence; this passes the 30-cent/0.3 gate for tested sine and
harmonic-PM anchors at TUNE .25/.5/1, FILTER .8, SPACE 0. It is **not raw detector confidence**
and is no promise for C1, noisy/inharmonic corners or strongly obscuring filters. Raw pitch
results, including empty estimates, are retained independently in the audition CSV.

Full-suite outcome is recorded below. The R1 listening pack is not
an accepted factory instrument; HOLD, alias investigation, target timing, classification
policy and the owner's audition remain open gates.

An additional Python house Welch-distance probe on the matched Kotlin sweeps is saved as
`macro-spectral-activity.json`. All adjacent steps register a spectral change. SPACE is
subtle in that metric (0.074–1.241 dB RMS across adjacent steps); listen specifically for its
late repeats rather than treating numerical activity as proof of useful control travel.
KICK's top FILTER steps are similarly small once the lowpass is open. These are voicing
questions for the first audition, not reasons to alter the model silently before it is heard.

## Completed JVM gate (2026-10-29)

The full JVM gate completed across an environment restart, with 7041 unique passing test methods in the saved-pass union and 0 skipped cases in the final reports. The final continuation exited 0. The first full run was interrupted by environment restart; the continuation excluded 1298 exact synth methods already recorded as passing on unchanged source and reused successful cached project tasks. This was a temporary command-line filter, not a repository test exclusion. Future `./gradlew --no-daemon test` runs remain unfiltered. The validation record and its logs stayed in the recovery backup.
