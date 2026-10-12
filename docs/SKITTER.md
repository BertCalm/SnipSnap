# Skitter in SnipSnap

Skitter is a deterministic offline mono pitched engine inspired by the EDP Wasp.
The two oscillators, signal-history memory and agitation drive one nonlinear
multimode filter. History and flutter are new proposals, not a reconstruction of
the historical circuit. The [supplied specification](superpowers/specs/2026-10-11-skitter-engine-engineering-spec.md)
is retained alongside this implementation.

## Rendering and saving

`Skitter.render(voice, macros, midi, velocity, seed)` exports 44.1 kHz mono audio
with 4× internal oversampling and shared `MELODIC_LOUDNESS_TARGET` leveling.
The saved root spans MIDI 24–84, default 48; velocity spans 0–1. Zero velocity
is silent for finite and held notes. Noise depends on voice, root and seed,
independently of macro values, so control comparisons share the same excitation.

BASS, LEAD, HOLLOW, FLUTTER, CHIRP and FRAY expose exactly BUZZ, SKITTER,
AGITATE, VENT, STATIC and HOLD. HOLLOW uses an octave interval. The original
fifth prototype produced a strong suboctave autocorrelation ambiguity and was
replaced during numerical probing. Pitch lives beside the macros in
`SkitterPatch`, together with velocity and seed; JSON edits, recipe replay,
velocity variants and preset promotion preserve those fields.

Thirty-six provisional presets comprise 8 bass, 8 lead, 6 hollow, 6 flutter,
4 chirp and 4 frayed textures, including one held patch per voice.
`SynthKits.skitter()` provides a dry sixteen-pad C minor pentatonic kit.
Patches, presets, FRESH TAPE, the Android synth picker and CLI use the same
renderer. Finite recipes route as TONAL, held recipes as LOOP. The existing
audio classifier separately labels samples longer than 1.5 seconds LOOP.

CLI example:

```text
./gradlew :cli:run --args="synth SKITTER LEAD --preset 3 --midi 60 --velocity .7 --seed 17 --out /tmp/skitter"
```

`--scramble N` creates a repeatable variation around the chosen preset. Scramble
stays below the held-loop step.

## Coupled circuit

BUZZ morphs a four-partial rounded triangle, PolyBLEP saw and PolyBLEP pulse.
Their fundamental polarities agree, avoiding cancellation during the morph;
pulse width stays between .16 and .50. A quiet direct fundamental supports the
filtered signal. Unequal exponential recovery follows bounded departures in
cents; deterministic repeated gestures use hysteresis and an explicit 8 Hz
event ceiling. SKITTER zero removes the departures.

The double-precision trapezoidal state-variable filter has coherent low-, band-
and high-pass outputs. Nonlinear excitation, small signed bias and delayed
band feedback belong inside the filter. Coefficients read the preceding
history/agitation update; no instantaneous unsolved feedback is used. VENT
crossfades those outputs continuously with measured gain budgets.

Energy history uses internal input and band power, normalized against .22;
signed history has a tightly bounded .055 target. An exact discrete damped
second-order control responds to rising energy, recovery gestures and
zero-mean noise deviations. AGITATE uses a square-root response to retain
activity near zero. Its position moves smoothed log cutoff, drive and damping;
it does not modulate final output gain. AGITATE zero removes dynamic memory
modulation. STATIC has a shaped audible feed and a separate behavioral forcing
path. The noise-forcing ablation preserves the source noise gain and sequence.

Finite velocity changes excitation, early brightness and recovery strength.
A modest initial excitation crest supplies provocation before the circuit
settles. Repeated gestures stop before release; excitation and noise then stop,
allowing filter and history states to decay. DC removal and a 48-output-sample
Blackman-windowed sinc filter precede decimation. Internal-state bound failures
raise errors; there is no final clipper repairing a runaway model.

## HOLD approximation

HOLD at or above .999 returns two independently evolved settled periods;
lower values extend finite excitation. A near-one-second period is selected
from whole cycles of the requested root, with finely fitted output frame count.
Oscillator frequency integrals are corrected to whole cycles, while recovery
departures remain audible. Gestures replay an alternating periodic schedule;
seeded noise wraps through interpolation and recursive noise filters.
Stochastic forcing is reduced to 70%, rather than removing STATIC or AGITATE.

Filter integrators, delayed feedback, coefficient smoothing, energy and signed
histories, agitation position/velocity, noise filters and DC state must converge.
Phase, recovery and scheduler periodicity are constructed explicitly. Preroll
has a 16-period cap and state tolerance `2e-5`; a failed search raises an error.
This is a periodic operating regime, not a promise that an arbitrary finite
trajectory loops. No wrap crossfade was needed in the measured cases.
`loopStart` identifies the second period; sampler WAV metadata and exported
PCM are checked with the binding `Keys.seamError < 1e-3` requirement.

## Evidence and listening

Round 3 probes covered 216 finite register/velocity/default/all-high cases,
36 held cases, 144 default/moderate pitch cases, every macro quarter step and
low nonzero setting, all four interaction pairs, and causal ablations.
The finite matrix had no bound hits or nonfinite output. Maximum raw peak was
.531, maximum absolute DC/RMS .00456 and maximum filter state .746.
All 36 held cases converged in three periods; maximum state error was
`5.16e-9`, with measured leveled seam below `1.35e-16`.

The 72 moderate MIDI 36–72 pitch probes measured maximum absolute error
1.50 cents and minimum confidence .819. Experimental departures are broader:
the default FRAY at MIDI 36 and hard velocity reached approximately +39 cents
in the sampled window. This is a listening limit to assess, not evidence of
root accuracy for every extreme. The repository pitch detector's normal range
starts at 40 Hz; the audition's MIDI 24 diagnostic uses a constrained low-note
autocorrelation fallback.

The smallest gain-matched waveform change was .00144 for 0→.02 macro movement
and .0166 over a quarter step. These measurements establish activity, not
audibility or taste. Frozen memory, disabled agitation, equal recovery and
matched noise forcing all change the coupled response. Released control
motion and audio decay rather than persisting without excitation.

Classifier evidence has a practical limit: full samples take its LOOP duration
shortcut, while a cropped opening can label strongly pitched harmonic leads
SNARE, or a late peak in an otherwise steady tone KICK/TOM. Cropped labels are
reported as diagnostics. Independent requested-root, confidence, spectral
flatness and pre-release sustain tests assess the pitched body; classifier
labels alone do not establish sonic acceptance.

Run `./gradlew --no-daemon test` for the JVM gate and
`./gradlew :synth:generateSkitterAudition` for the full listening artifact.
`-Pquick` selects a smaller development pack. The
[published private audition](https://skitter-snipsnap-audition.bertcalm.chatgpt.site)
contains dry raw/native/matched playback, presets, registers and velocities,
macro sweeps, interactions, common-gain causal comparisons, repeated exported
seams, traces, source patches and a bright 4×/8× reference. The reference shares
base-rate noise; waveform distance does not isolate aliasing from discretization.

The full pack contains 319 variants and 975 PCM24 WAVs. All 54 held source WAVs
(raw, native and matched) measured zero exported seam error, and the 18 seam
demonstrations repeat the exact final PCM period four times. The bright FRAY C6
4×/8× comparison measured matched waveform distance .00161, centroids 1334/1344 Hz
and pitch errors −1.14/−1.20 cents; listening still decides whether its high-end
character is acceptable.

Rendering was measured on Linux x86-64, an AMD EPYC 9V74 host with a four-CPU,
16 GiB container and Java 21.0.12.1. The 300 finite 4× renders had median 184 ms,
95th percentile 218 ms and maximum 279 ms; 18 held renders had median 356 ms and
maximum 409 ms. These timings cover the circuit render, excluding analysis,
file export and compilation, during the repository build. The prototype budget
on this environment is a 95th percentile below 500 ms for finite renders and
below one second for held renders. It is an offline-generation budget, not a
phone latency claim.

Sites has a 256 MiB archive input limit. The hosted pack uses lossless PCM24
second differences followed by gzip; the browser restores the original WAV
bytes for playback and downloads, including root and loop metadata. The local
generator continues to emit ordinary WAV files. Reproduce the hosted pack with
`python3 scripts/package_skitter_audition.py testkit/skitter-audition /tmp/skitter-hosted --template synth/src/test/resources/audition/skitter-audition.html`.
The script uses the Python standard library, with optional NumPy acceleration.
All 975 decoded hosted WAVs matched the original SHA256 bytes; both predictor
implementations agreed on representative files. Hosted playback requires a
current browser with `DecompressionStream` support. JavaScript syntax, mock DOM
interactions and the actual decompression/restore path passed runtime checks;
visual browser review and listening remain acceptance work.

The owner must still judge the sound, loop clicks and the identities of the
controls. Android phone/MPC validation and release naming remain open.
