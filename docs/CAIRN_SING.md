# CAIRN: SING

Status: production listening milestone, 2026-11-04. All 8,439 tests in the complete nine-module JVM gate pass,
with no failures, errors or skips. The 35 focused tests also pass. No new sound has been heard.
Repository read: `482c55a146b1569ba9ccad27e4ebd274ad398df4`.

The characteristic gesture is **soft contact → rubbing catches the note →
smooth pitched bloom → contact lifts → the stone rings away**. HOLD maintains
the rubbing continuously, with a gentle pressure stroke and no repeated knock.

## Scope and choice

SING is Cairn's fourth voice, C3–C5, retaining TUNE, WEIGHT, SLOPE, GROOVE,
FRICTION, BODY and HOLD. Models 1–3 retain their audio. Model 4 introduces SING;
older recipes cannot select it. Factory recipes, the remaining GRIND/VESSEL
voices, phone controls and hardware acceptance follow the listening gate.

This is an **averaged friction-feedback analogy**, not a microscopic stick/slip
or elastic contact simulation. The regenerative part of smooth rubbing is a
bounded normal form with an exact energy update. Its pressure, saturation and
loss determine an amplitude trajectory. That trajectory drives Cairn-family
stone modes through a smooth periodic contact force. Audio does not feed back
into this cached trajectory. All material constants are design shapes.

Nearest siblings are MERCURY's implicit glass rubbing and THAW's rubbed ice.
They already cover full friction contact and material evolution. SING takes
the lighter landing as a Cairn voice: the transition from one soft touch to
continuous traction, alongside this engine's other three gestures.

| Direction | Gesture / novelty | Risk / cost |
| --- | --- | --- |
| **Selected: PORT** smooth traction into a bounded amplitude-energy normal form | Clear bloom and lift; a continuous Cairn role | Approximation must be disclosed; cheap cached control trajectory |
| SPLICE the existing implicit friction contact into Cairn's bank | Literal slip feedback and natural locking | Pitch capture, iterative cost and held closure; overlaps MERCURY and GRIND's intended catches |
| JUXTAPOSE filtered rubbing noise with a struck stone | Immediate attack plus sustained texture | Cheap, but weaker causal connection and a less definite sung pitch |

## Model and signal flow

```
controls, saved velocity, model
  → pressure stroke and bounded regenerative energy @ 2 kHz [cached, no pitch]
  → note-phase smooth contact force + one finite soft strike @ 4×
  → six uncoupled stone/body modes (Modes.Bank)
  → Tide.bandLimit → Dsp.decimate → DC/high-pass → Dsp.levelTo
  → finite release or real preceding cycle + continuing HOLD cycle
```

Let E be the normal form's nonnegative energy proxy:

```
dE/dt = 2 (P(t) - gamma) E - 4 beta E²
driver power = 2 P(t) E
loss power = 2 gamma E + 4 beta E²
```

The piecewise-constant-pressure step solves this logistic equation exactly.
Positive Gaussian quadrature independently integrates driver and loss power.
When pressure is removed, energy decreases. For bounded P and beta > 0,
E cannot grow beyond max(initial E, (max P - gamma)/(2 beta)). The acoustic
bank is a separate passive, damped readout; there is no stability limiter.

Finite pressure has a delayed smooth rise, a dwell, and a smooth lift. HOLD
continues a two-second pressure stroke until the energy state repeats, capped
at eight cycles. No energy coordinate is reset at the loop boundary. The
initial strike is absent from the maintained held cycle.

HOLD's excitation phase fits an integer number of periods into two seconds.
The maximum frequency-fit error over C3–C5 is bounded by 3.32 cents. Finite
notes use the exact requested frequency. This is a free-running gesture with
no host-tempo API. The acoustic periodic state is solved with the existing
`Modes.Bank.closeUncoupledCycle`; an independently warmed-bank path checks it.
Exported seams use actual preceding audio, with no tiling or crossfade.

## Macro contract

| Control | Default | Quantity / interaction |
| --- | ---: | --- |
| TUNE | .50 | C3–C5 note; reuses cached energy/pressure trajectory |
| WEIGHT | .55 | Available contact energy, pressure rise/lift time and strike width; interacts with SLOPE |
| SLOPE | .45 | Regenerative drive above loss; changes catch/bloom time |
| GROOVE | .25 | Pressure-stroke depth, dwell and smooth second-harmonic traction |
| FRICTION | .60 | Linear/nonlinear rubbing loss and third-harmonic traction; interacts with BODY |
| BODY | .65 | Stone loss and modal ring times, with the root fixed |
| HOLD | 0 | Finite dwell below .99; maintained rubbing at the top step |

The 24-entry trajectory cache must include model, all trajectory controls,
velocity, finite HOLD value and the maintained regime. Raw PCM is not cached.
The proposed warmed server target is 50 ms; actual timing must be measured
on an idle runner, and phone timing remains a separate gate.

## Probes and listening risks

First prototype the energy law, root, smooth bloom and maintained seam using
the Luthier Python harness. Production checks must cover the independent
energy/RK4 reference, saturation and pressure-off decay, macro activity and
pairs, velocity/silence, real PCM classifier, JSON/version compatibility,
three-register pitch, 4×/8× comparison and a warmed-bank HOLD reference.
Reproduce the prior published Kotlin WAVs before claiming preservation.

The listening risks are a generic organ tone, too much buzz, a strike that
swamps the bloom, and held breathing that sounds like tremolo. Compare all
four roles at the same note and controls; include a dry phrase with SING
carrying the melodic line. Only the owner supplies the listening verdict.

## Why it is built this way

- Continuous traction gives Cairn a sustained role after its three contact gestures.
- The initial touch and later rubbing share the same resonant stone.
- Averaging the friction feedback keeps the note stable and the trajectory reusable when pitch changes.
- Pressure lift lets the actual resonators supply the ending.
- GRIND keeps the irregular catching/scraping territory; SING favors a smooth pitched bloom.

## Evidence ledger

| Claim | Status | Evidence |
| --- | --- | --- |
| Modes.Bank and its uncoupled periodic solver exist | Read | Modes.kt at the repository revision above |
| Normal-form energy is bounded for positive beta | Analytic | Logistic equation and bound above |
| New SING is musically distinct and useful | Proposed | Awaiting production audition and owner listening |
| 243 controls in finite and maintained modes | Measured in Kotlin | Bounded energy, maximum work residual 2.1011e-14; maintained state settles in three cycles |
| C3–C5 roots through tested BODY/WEIGHT settings | Measured in Kotlin | Maximum 3.3227 cents, confidence at least .9745 |
| Real held continuation and separately warmed bank | Measured in Kotlin | Default three-register seam and relative RMS both zero |
| C5 4×/8× relative waveform difference | Measured in Kotlin | 1.495% finite, 1.359% held; not a proof of zero aliasing at every corner |
| Full nine-module JVM gate | Verified | Exit 0; 8,439 tests; no failures, errors or skips |
| Server render time | Measured | 33.60 ms finite / 54.84 ms HOLD medians; cold HOLD 292.66 ms |
| Startup, phone/MPC playback and new SING listening | Open | Separate acceptance gates |

## As built

`CairnSing.kt` owns the cached 2 kHz normal-form trajectory and six-mode acoustic
readout. The closed-form flow agrees with an independent RK4 integration for
positive, zero and negative growth. Driver and loss work use independent positive
quadrature. All 64 C3 endpoint/velocity renders remain finite and classify LOOP;
the finite notes are deliberately longer than 1.5 seconds. Every control changes
normalized audio, and BODY × FRICTION changes the work response jointly.

The default initial energy proxy is split into .0024 × velocity² of contact work
and .0001 × velocity² of seed vibration. Pressure rises after 80 ms, continues for
a macro-controlled dwell, then lifts smoothly. Velocity changes the traction
growth, rise time, strike width and harmonic detail. The cubic saturation term
keeps the regenerative energy bounded without an output limiter.

SING's modes are `[1, 2, 3, 2.71, 5.8, 1.47]` times the requested root. These
normalized design ratios give the smooth traction its note and the initial touch
its stone color. Each contact uses the same fixed work-to-impulse calibration;
no event is leveled separately. The maintained input is phase-planned into two
seconds. Its preceding and kept traction are built from separate continuing
energy traces, checked for relative convergence below 1e-9.

SING uses a full real preceding cycle. The linear modal bank starts from its
analytically closed state; the reference instead advances twelve complete forced
cycles. The output filter, decimation and level stage then process the real
preceding and kept audio together. A changed nonzero continuation is rejected.
No output waveform is tiled or crossfaded to supply the seam evidence.

The existing Cairn contact-mechanics suites explicitly retain their three-voice
scope because SING receives continued drive. Its separate suite covers the
regenerative work law, output/classifier, pitch, cache, cue, silence/velocity,
JSON/version guards, control activity, warm reference and rate comparison.
The general determinism canary still iterates every voice. The old three-voice
generator is pinned to model 3 and its original voice list.

The idle native audition renders 81 comparison cases and two dry phrases, with
39 held cases. All prior 219 comparison recipes replay unchanged: 276 original
model-1 cued/full/raw WAVs, 96 two-voice HOLD WAVs and 108 three-voice WAVs,
including their stored continuations. Those 480 WAVs match byte for byte; 124
model-2/3 cases also match through the model-4 path. Earlier historical six-voice
recordings remain preserved assets, rather than regenerated source claims.

Idle server medians across twelve warmed renders with new controls per regime
are **33.60 ms finite and 54.84 ms HOLD**. The cold first HOLD, including
initialization, is **292.66 ms**. The warmed held path narrowly misses the
proposed 50 ms target, and cold startup remains a larger performance gate.
These are server measurements; phone latency is unverified. The full nine-module
JVM gate completed successfully: 8,439 tests, no failures, errors or skips.
The run used the source checkpoint `a286e6b4220817a0b8edd19052fce2c7147921f9`; later source changes are
verification prose, packaging and frontend text. Android compilation/device
tests were unavailable because there is no Android SDK. No native C++ files change.

## Reproduction

With JDK 21 in the SnipSnap checkout:

```sh
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.Cairn*Test' --tests com.snipsnap.synth.ModesPeriodicTest
./gradlew --no-daemon :synth:generateCairnSingAudition
./gradlew --no-daemon test
```

The native generator writes `testkit/cairn-sing/`: mono 44.1 kHz, 24-bit WAVs,
recipes, events, energy/pressure traces, true continuations and a manifest.
Optional `-PcairnSingLegacyRoots='/path/to/reconstruction;/path/to/hold;/path/to/tumble'`
replays retained native fixtures. The earlier generations' own tasks can
regenerate their versioned fixture sets; the historical six-voice recordings
are preserved recordings rather than executable recipes.

Packaging uses the retained Cairn Site checkout and requires Python with NumPy,
SciPy and FFmpeg:

```sh
python3 scripts/prepare-cairn-sing-site.py --site /absolute/path/to/cairn-site
```

It leaves all prior audition directories intact, verifies exact 24-bit PCM and
continuation suffixes, and restores the complete patch in a fresh Git index.
The source ZIP records its GitHub base and restored tree. `--source-only`
refreshes prose/source without re-encoding unchanged audio. The `isolated`
manifest field supplies `Cairn.Options(disableCoupling=true)` for the touch-alone
diagnostic; the other cases use the normal patch render.

`python3 scripts/prototype-cairn-sing.py /path/to/proof` runs the separate Python
proof and writes its WAVs/report. Its 16-bit files are prototype evidence, not
the production audition's 24-bit Kotlin audio.
