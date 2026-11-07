# Afterimage prototype and implementation handoff

**Continue from the accepted R2 sustain behavior.** Bass and pluck hold a settled
waveform; pad keeps its captured-history memory. R3 adds optional slow spectral
motion, defaulting to zero. The owner has not approved that new motion by
listening. This commit preserves the reproducible Python prototype and evidence;
it does not implement or register a Kotlin engine.

The characteristic gesture is **clear chorused note → remembered sustain →
independently clocked memory → controlled release**.

## Start here in the next session

1. Read `AGENTS.md` and the repository Luthier skill. For PR/CI work also read
   Steward. Inspect the current branch and local changes before continuing.
2. Run the Python audition and original-audio verification below. Listen to the
   static/moving comparisons with the owner; keep MOTION at zero unless the
   motion candidate is accepted.
3. Port the accepted source and two memory paths into the current SnipSnap
   architecture. Recheck reuse and integration against the latest checkout.
4. Treat seamless HOLD, phone performance, and MPC playback as separate build
   rounds. Do not infer them from a four-second WAV or the current Python tests.

Repository inspected for this handoff:
`ae4bd8d114f8e046cbcdb2f82aa1323324eacdfd`, on
`claude/mobile-mpc-drum-sampler-t58x74`. The handoff lives on the separate
`claude/afterimage-prototype-handoff` branch. Fetch that branch before looking for
these files; it is not a claim of a merge into the default branch.

## Reproduce the audition

Use Python 3.12 with the pinned NumPy/SciPy versions. From the repository root:

```sh
python3 -m venv .venv-afterimage
.venv-afterimage/bin/pip install -r experiments/afterimage/requirements.txt
.venv-afterimage/bin/python experiments/afterimage/audition.py
.venv-afterimage/bin/python experiments/afterimage/verify_audio.py
python3 -m http.server 4173 --bind 127.0.0.1 --directory testkit/afterimage-audition
```

Open <http://localhost:4173/listen.html>. The directory also contains
`quick-tour.wav`, 24 individual comparison WAVs, `report.json`, and `controls.json`.
All WAVs are mono, 44.1 kHz, 16-bit PCM. The internal rate is 176.4 kHz.
Both Python commands accept an optional output directory. Do not use `python -O`,
which disables the audition's assertions.

Quick tour order: bass static, bass MOTION .35, pluck static, pluck MOTION .35,
pad stretch, pluck C-minor chord. Each example lasts four seconds with a
0.35-second gap. Mixed static/motion comparisons share gain; isolated memories
are separately leveled for inspection and are much louder than in the mixture.

Workspace paths and the later ChatGPT Page delivery failed for the owner. Use the
committed source to regenerate locally; successful upload/access checks never
established successful owner downloading or listening. No unavailable attachment
is required by this handoff.

## Listening decisions to preserve

| Round | Owner feedback or status | Consequence |
| --- | --- | --- |
| R0 | “Half speed memory loops an attack sound for bass and pluck. It works for pad.” | Keep pad; investigate bass/pluck repetition. |
| R1 | “I don't think that fixed it.” | Later capture and amplitude flattening did not solve the perceived attack. Do not reinstate that approach as the final fix. |
| R2 | “Seems fine let's continue.” after the frozen-sustain comparison | Accepted baseline for further work. This is not broad approval of all registers, controls, or hardware fidelity. |
| R3 | Optional motion produced; file delivery repeatedly failed | New motion is still a listening candidate. Default remains static. |

No assistant listening claim is made. Numerical checks establish the properties
listed below, not whether an attack is perceived or a preset is musically useful.

## What the instrument borrows

The Juno-family reference contributes one oscillator clock for saw/pulse/PWM,
an octave sub, filter and amplitude envelopes, and chorus movement. The current
filter is a four-pole biquad approximation; the chorus uses two short modulated
delays in mono. Neither is calibrated to the hardware.

The MOOD MKII reference contributes short captured histories, tape/stretch/reverse
behavior, and related pitch/time changes. The bass/pluck waveform freeze is our
adaptation, not a claim about the pedal's implementation. Full wet-channel
interaction, low-clock digital grit, stereo behavior, and live always-listening
capture are absent. There is no memory across independent render calls.

The earlier inventory suggested RESIN and ORPHICA as the nearest siblings.
That is a starting point for a current reuse review, not proof this must become a
new registered engine. Prefer reusable capture/readers over duplicating a complete
engine where an existing source can support the gesture.

## Signal flow

```text
Source + filter/amp envelopes → mono chorus ─────────────────→ dry
                                  │
                                  ├─ bass/pluck: settled waveform → pitch clock
                                  │                                → optional motion
                                  └─ pad: history buffer → tape / stretch / reverse
                                                                  │
                                                      memory entrance + release
                                                                  │
                                                       short bounded diffusion
                                                                  │
dry + MEMORY × memory → band-limit → decimate → DC removal → level → tail fade
```

The three source articulations are `bass`, `pluck`, and `pad`. A chord is a sum
of independently rendered note/capture paths, not a shared chord micro-looper.
All shape constants are in `PROFILES` and the relevant functions in
[engine.py](../../experiments/afterimage/engine.py).

### Bass and pluck memory

`frozen_capture` aligns four periods of the octave-sub waveform to a 4096-sample
phase grid, averages them, removes DC, and bounds retained harmonics to 14 kHz at
the capture pitch. A single attenuation-only budget prevents table peak or RMS
from exceeding the captured segment. It freezes timbre as well as amplitude.

`frozen_read` follows the exact pitch clock:

```text
read increment = table size × (root Hz / 2) × clock ratio / internal rate
```

Capture completion remains approximately 0.83 seconds for bass and 0.47 seconds
for pluck, with exact timing determined by whole-sub-period rounding and the
12 ms splice allowance. The frozen source enters once over 160 ms, then follows
the overall memory decay/release. It has no repeating slow note envelope.

The prototype still calls `capture()` to obtain completion time before forming
the frozen table. A port can calculate that timing directly, but preserve the
rounding and validate parity. Do not accidentally restore R1's long decaying
history as the audible bass/pluck memory.

Tape and stretch selectors are omitted for these voices: a stationary table
makes them equivalent at a matching playback ratio. Half speed also lowers the
sub; very low notes may feel pulsating even without a repeating attack envelope.

### Pad memory

Preserve the original history capture and 12 ms circular splice. Tape links pitch
and duration; reverse reads backward. Stretch separates cursor progression
(0.13×) from within-grain playback speed. Grain positions preserve phase at the
sub period. Simple phase resets at grain boundaries shifted an early pure-tone
test by about 74 cents; retain this regression concern during a port.

Pad has a 45 ms memory entrance and its original diffusion. R3's MOTION operation
is not part of this path.

### Optional R3 motion

`animate_tail` slowly blends the frozen waveform toward a one-pole lowpassed
copy. Cutoff follows four times the shifted root, clamped to 180–2500 Hz.

```text
shade(t) = 0.65 × MOTION × (1 - cos(2π × 0.21 × t)) / 2
output   = (1 - shade) × input + shade × lowpass(input)
```

The nonnegative branch weights sum to one. The one-pole impulse response has
unity L1 gain, so the stage cannot exceed the input's absolute peak. Blend slew
is bounded by `0.65 × MOTION × π × 0.21` per second. There is no recapture or
envelope restart. MOTION .35 and .75 are unapproved audition candidates;
MOTION zero preserves the accepted baseline.

## Proposed control contract

| Control | Default | Implemented Python meaning |
| --- | --- | --- |
| TUNE | .50 | MIDI 36–60, rounded to nearest semitone; .50 = C3 |
| COLOR | .50 | Filter cutoff/excursion multiplier `2^(3*(value-.5))` |
| CHORUS | .45 | Source delay modulation and wet/dry weights |
| MEMORY | .65 | Adds the captured tail; zero equals the dry source |
| CLOCK | 0 bass/pluck; .50 pad | Half/unity/double speed; boundaries .25 and .75 |
| MOTION | 0, bass/pluck only | Slow spectral shading of frozen sustain |
| SPACE | .25, pad only | Short feedforward diffusion; audible range still needs work |

There are six controls per articulation. Bass/pluck retain internal SPACE .25.
Pad's `stretch`, `tape`, or `reverse` is a discrete choice. CLOCK changes only the
memory; it does not retune the dry source. `render_patch` clamps finite macros,
uses defaults for non-finite values, ignores unknown keys, and accepts explicit
finite duration from 2 to 8 seconds. Seamless HOLD is not implemented.

Required listening interactions: COLOR × CHORUS determines captured character;
MEMORY × CLOCK balances shifted memory against the root; MEMORY × MOTION exposes
spectral motion; SPACE × CLOCK changes pad diffusion timing. Velocity changes
filter excursion and amplitude before capture, not gain alone.

## Source and evidence map

All paths below are in [experiments/afterimage](../../experiments/afterimage/README.md).

| File | Role |
| --- | --- |
| `engine.py` | R3 research DSP and normalized control surface |
| `baseline.py` | Preserved R2 DSP used for exact comparisons |
| `audition.py` | Regenerates auditions and runs numerical assertions |
| `verify_audio.py` | Compares all 25 WAV files with original R3 hashes |
| `snipkit.py`, `probes.py` | Preserved helpers from the supplied Luthier toolkit |
| `requirements.txt` | Tested NumPy 2.3.5 and SciPy 1.17.0 versions |
| `evidence/r2-report.json` | Frozen-waveform diagnostic measurements |
| `evidence/r3-report.json` | Original R3 checks, macro distances, render timing |
| `evidence/r3-controls.json` | Proposed defaults |
| `evidence/r3-wav-manifest.json` | Original audio frame counts and SHA-256 hashes |

The generator's output-directory handling was adapted for the repository; DSP
and original evidence remain preserved. Audio and embedded HTML regenerate into
ignored `testkit/afterimage-audition/` rather than being committed as large binaries.

## What was measured

Python harness, not Kotlin or phone validation:

- R3 MOTION zero equals R2 sample-for-sample over 27 articulation/register/clock
  combinations. Pad retains the original memory path.
- R3 checks the motion peak bound, finite/bounded bright corners, determinism,
  unknown-key behavior, and non-finite macro defaults.
- Macro sweeps record full-file and tail-only spectral distance. SPACE is weak,
  and full-file MOTION differences are small. Numerical activity is not a
  substitute for hearing the control at low and middle settings.
- R2 frozen expected-root tracking was within 0.003 cents at the tested notes.
  This tracks a known harmonic, not perceived pitch of the complete sub-rich mix.
- Original R3 four-second render median: about 438 ms full, 111 ms with source
  cached. These are machine-specific Python numbers; the phone target remains
  tens of milliseconds per knob turn.

Handoff validation passed: the generator's assertions completed and all 25 WAV
hashes matched the original R3 manifest. Python compilation and `git diff --check`
also passed. Fresh timing belongs in the regenerated
report. The full Gradle suite is not part of this Python/docs-only change; a
Kotlin implementation must run the repository's required checks.

## Repository integration facts checked for this handoff

The following declarations were located at the base commit above. Re-read their
implementations and the current complete engine examples before using them.

| Concern | Located declaration |
| --- | --- |
| Oversampling and stable seeds | `Dsp.kt`: `OVERSAMPLE = 4`, `seedFor(vararg parts: Any)` |
| Output leveling | `Dsp.kt`: `levelTo(...)`, `MELODIC_LOUDNESS_TARGET = 0.1834f` |
| Decimation and tail fade | `Dsp.kt`: `decimate(...)`, `fadeTail(...)` |
| Pre-decimation band limit | `Tide.kt`: internal `bandLimit(...)` |
| Loop seam metric | `Keys.kt`: `seamError(...)`, `MAX_SEAM_ERROR = 1e-3` |
| Velocity hooks | `Velocity.kt`: internal `macroSpecsFor(...)`, private `touchedRender(...)` |
| Length classification | `Classifier.kt`: private `LOOP_MIN_SECONDS = 1.5f` |

No Afterimage registration was found in `Patches.kt`. This branch adds no engine
registry entry, patch type, kit, presets, Android picker item, or app integration.
The Python helper's seeds, decimator, and RMS leveling are stand-ins; do not claim
sample-exact Kotlin parity by copying their names. A long file being classified
as LOOP is separate from whether it loops seamlessly.

## Implementation sequence and acceptance gates

1. **Preserve the accepted memory split.** Port the source, table capture, exact
   pitch reader, and pad history reader as separate units. Keep the source,
   capture, reader, optional processing, and output conditioning independently
   testable. Reuse existing primitives only where behavior matches.
2. **Establish native behavior.** Add finite output, deterministic seeds, input
   validation, pitch/alias checks, velocity, macro activity, serialization and
   class/duration tests. Audition the native source and tails with the owner;
   do not author final presets from numerical reports alone.
3. **Profile the target device.** Source cache keys must include model version,
   articulation, root, velocity, COLOR, CHORUS, internal rate and duration.
   MEMORY/CLOCK/MOTION/SPACE should reuse the source. A capture cache adds policy
   and capture location. Eviction must not affect samples.
4. **Implement HOLD separately.** Frozen-only output can be period-planned, but
   chorus, motion and diffusion add state. Pad stretch has its own closure
   problem. Validate warm-up, retained cycle and real continuation; concatenating
   a loop with itself is not a seam proof. Reserve/refuse a seamless top step
   until there is a tested strategy.
5. **Complete product integration.** Follow the current Luthier checklist for
   patch decoding/registry, velocity, presets, kit and audition generation,
   classification, documentation, app picker and export. For FRESH TAPE starters,
   recheck the current starter registration/display-group requirements. Run the
   required Gradle gate for Kotlin changes and verify actual phone/MPC playback.

The Python source cache can retain 40 pairs of float64 arrays and is unsuitable
as a phone memory budget. Proposed initial production cap: two float32 source
pairs, about 11.3 MB at four seconds or 22.6 MB at eight seconds, plus working
buffers. Frozen tables are 4096 floats. Consider streaming/ring capture only
after profiling and preserving the audio behavior.

## Open risks and scope boundaries

- R3 motion listening remains open because delivery failed. Keep its default zero.
- Exact Juno filter/chorus fidelity, mono combing, low-register sub pulsation,
  and pluck memory level need listening. The prototype uses no original samples.
- Complete alias residual testing against a higher-rate reference is pending;
  near-Nyquist energy alone does not prove alias suppression.
- Diffusion is finite feedforward processing, not a modeled MOOD reverb or coupled
  wet-channel system. Its bounded gain does not establish a hardware match.
- Full stereo, live capture, cross-note history, clock grit, seamless HOLD,
  production CPU/memory, final presets and MPC validation remain unimplemented.

## Paste into the implementation session

> Continue Afterimage from `docs/handoffs/2026-11-07-afterimage.md`. Fetch the
> `claude/afterimage-prototype-handoff` branch and inspect local changes first.
> Read AGENTS.md and Luthier. Reproduce `experiments/afterimage/audition.py` and
> `verify_audio.py`. Preserve accepted R2 frozen sustain for bass/pluck and
> history memory for pad; R3 MOTION is unapproved and defaults to zero. The owner
> rejected both the original repeated attack and R1's envelope-only correction.
> Begin a native implementation against verified current APIs, keeping the
> memory mechanisms reusable. Treat HOLD and phone performance as separate
> measured rounds. Do not rely on failed cloud/Page download links or mistake
> Python measurements for native listening acceptance.

## Research references

- [Roland Juno-60 description](https://www.roland.com/global/products/rc_juno-60/)
- [Chase Bliss MOOD MKII](https://www.chasebliss.com/mood-mkii)
- [MOOD MKII manual](https://www.chasebliss.com/s/MOOD-MKII_Manual_Pedal_Chase-Bliss.pdf)
- [Repository Luthier skill](../../.claude/skills/luthier/SKILL.md)

## Why it is built this way

- The two memory paths follow the owner's listening result instead of forcing
  one capture algorithm onto every articulation.
- A separate dry path preserves a clear attack while the memory changes pitch
  or sustains the tone.
- Frozen capture removes the slow note trajectory that repeated as an attack;
  optional filtering tests movement without restoring it.
- The preserved baseline and original audio hashes let the next implementer
  distinguish packaging changes from changes to the sound.
