# Crease playable release experiment

Status: interactive browser prototype verified; approximation gate failed.
Phone integration remains blocked. No production Kotlin, native audio, schema,
factory preset or exported instrument changed. Job: Luthier CONCEIVE, the one
recommended direction.

Repository baseline: `fde661771`.
Actual circuit capture: `84620e8ea7cd4d95f19eee0402fdf8a99f557b65`, clean.
Corrected measurement/transport implementation: `7e34dca10`.
Later test/documentation changes do not relabel those captures.

The characteristic gesture is **press from rest → actual onset and held motion
→ release at any frame → a cached approximation of that state's unfolding →
silence**.

## What was built

The previous fixed-recording study now has a playable, single-key browser
prototype: Reed Mark, MIDI 60, saved velocity .8, model 3. Its note starts from
the real onset rather than an already-warm loop. The note's onset and settling
prefix are included in the cache. A new press replaces the preceding gesture
through a short fade; it is not a new polyphonic instrument bank.

The Java adapter invokes the actual private Kotlin `Crease.Model.keyStep` and
`Crease.OutputPath`, reusing the merged research adapter. The entire trajectory
from rest matches public `CreaseKeys.probe(normalize=false)` exactly. Every
state fork includes model/detector/filter/DC/FIR state and clocks. No circuit
equation is ported into the Python or JavaScript transport.

The circuit is literal. Paper/material language remains analogy; the carrier
continues behind an external fade, not a passive body's energy decay. GLINT
does not have this deformation memory; BELLOWS uses different pressure-driven
sources. This is an extension of Crease, with no new engine or sound macros.

```text
approved patch → actual 4x Kotlin circuit from rest → exact state captures
                                └───────────────→ onset + recurring held PCM
                  [explicit offline cache: patch + versions + capture grid]
press → held sample reader → key-up → neighbouring cached release tails
                    carrier-phase alignment → bounded 5 ms blend
                              → shared player-style volume fade → output
```

The [protocol](../../../scripts/crease-release/playable-protocol.md) was committed
before measurement. Its waveform, spectral, discontinuity and memory limits
were not relaxed. The cache is measured after PCM16 quantization and delivered
losslessly. The browser performs sample playback, phase alignment and a blend;
it does not run the offline Crease simulation.

## Transport and controls

| Control | Behaviour | Evidence |
|---|---|---|
| Press / Space / Enter | Restart actual onset and continue the held reader | Browser pointer, keyboard, touch and sustained output checks |
| Release | Select the two neighbouring captured states immediately | 201 exact continuation comparisons; no phase-bin wait |
| Fade only / Unfold | Continue held forcing or use approximated pressure withdrawal | Same external gain law, fixed A/B/C reference WAVs |
| Stop / focus loss | Short cleanup fade; no pending press starts after loading | Stop, pointer cancel, blur, hidden-page and loading tests |

The slow gesture and carrier clocks are separate. Each chosen tail is shifted
by at most half a root period, about 1.91 ms here, to match the carrier phase.
Four-point interpolation reads a real held lead-in or tail. Neighbouring tails
are blended linearly in gesture time, then mixed from the held reader over
221 frames (5.011 ms). The Float volume decrement starts at key-up and reaches
zero after 52,925 output frames at 44.1 kHz, matching the reference arithmetic.
There is no deliberate wait for a phase bank boundary.

This alignment also shifts a tail's recovery age. Its contribution to the
remaining short-release error is a hypothesis, not an isolated causal result.
The unaligned nearest-tail canary reaches normalized RMS error 1.993, showing
that simply jumping to an otherwise similar recording is inadequate.

## Results, including failures

The actual Kotlin capture took 41.71 desktop seconds: 338 bank positions and
201 independently captured release positions, with twelve bit-identical
repeat checks. Public-prefix and complete fork errors were zero. Cases include
early taps, non-recurring settling, steady phases, wrap neighbours and holds
up to 22.222 seconds. All 201 positions remain in the results.

| Positions per gesture period | Captured tails, including onset | PCM cache | Failed waveform cases |
|---:|---:|---:|---:|
| 16 | 98 | 10.62 MiB | 10 / 201 |
| 32 | 178 | 18.72 MiB | 3 / 201 |
| 64 | 338 | 34.93 MiB | 2 / 201, also exceeds the 32 MiB cache budget |

No candidate passes all gates. The displayed 32-position candidate is the
closest diagnostic candidate within the memory limit, not an accepted player
mode. Its largest normalized RMS error is .020761, better than the .031623
limit; maximum normalized harmonic-power TV is .00020457, below .02. Its three
peak-error failures are:

| Actual hold | Peak waveform error, full scale | Limit |
|---:|---:|---:|
| 37.959 ms | .0289943 | .025 |
| 137.007 ms | .0502128 | .025 |
| 230.998 ms | .0289946 | .025 |

The early waveform discrepancy can fail while the later spectral distribution
matches closely. The peak test must not be replaced with a loudness or spectral
claim. A plot of the delivered 137 ms PCM and all fifteen fixed reference WAVs
are provided with the audition. These numbers do not establish musical quality.

The 178 tails and held sample occupy 19,633,926 PCM bytes, shared across voices.
Byte-plane/delta coding plus gzip reduces delivery to 1,081,134 bytes with
integer-exact round trip. The manifest names the rate, held/loop frames, real
tail capture positions, root-cycle count, fade/reader versions, saved patch and
source/JAR hashes. Initial decoding also uses temporary buffers; 18.72 MiB is
PCM residency, not total browser heap or a multisample-bank memory prediction.

## Verification and observed cost

- JavaScript's actual streaming reader equals the Python PCM transport at every
  sample across all 201 cases, at the audition's .12 voice gain: maximum error
  zero. Independent constant and linear interpolation invariants also pass.
- Separate container/WAV/FLAC readers verify the original circuit-derived
  integers, fifteen reference-file round trips, silent endings and every entry
  in the WAV ZIP. Corrupt cache data is rejected before playing.
- A Node eight-voice test peaks at .793413 full scale and processes approximately
  one audio second in 12.91 ms after warm-up. Zero live gain is silent; repeated
  presses, cleanup and 48 kHz source clocks/releases pass. These are desktop
  transport measurements, not phone DSP or latency timings.
- Chromium at 1440 px and 390 px exercises the actual AudioWorklet output,
  pointer and keyboard release, touch events, a nine-second hold beyond the
  sustain wrap, rapid retriggers, stop/cancel/blur/hidden-page cleanup, release
  during loading, corrupt-download retry and mutual exclusion with recorded
  playback. One shared PCM buffer is transferred to the audio side.
- Browser PCM checking/decode took approximately 95–97 ms in the initial
  desktop run. The output quantum is 128 frames at 44.1 kHz (2.90 ms).
  Main-thread/processor acknowledgement round trips reached about 17 ms in
  that run; these include scheduling and are not speaker latency.
- Earlier release controls, all 48 earlier release clips and the preserved
  keyboard/touch/preset studies pass regression checks. Existing media remain
  byte-identical. The fifteen new fixed recordings use the existing player.

The first analysis contained a missing coefficient in fractional interpolation,
which attenuated constant signals. Its source, raw provenance, measurements and
cache were preserved before correction. The actual Kotlin captures were not
changed or rerendered: analysis reuse verifies Java/circuit/protocol and JAR
hashes, retains the original render commit, and records the corrected analysis
commit separately. The new invariant checks would reject that coefficient error.

## Reproduction and landing

With a complete JDK, the repository's build dependencies, Python/NumPy/SciPy/
Matplotlib, FFmpeg and Node:

```sh
python3 scripts/crease-release/playable.py
python3 scripts/crease-release/verify_playable.py
python3 scripts/crease-release/package_playable.py
```

Outputs go to the ignored `testkit/crease-playable-release/`. `--analyse-only`
explicitly reuses an existing raw execution after verifying render-source and
runtime hashes; it is not a new sound render. `--skip-build` is only for an
explicitly already-built unchanged synth JAR, and is recorded as such.

The browser modules live in `scripts/crease-release/player/`; the Site uses
byte-identical copies. Cache generation is an explicit offline action, never a
per-knob or audio-callback task. Extending pitch, touch or macro coverage needs
a new cost/accuracy measurement, not multiplication of this one-note claim.

Phone integration was conditional on the gates. It is held back because no
candidate passes. Existing `InstrumentStore.Layer`, `InstrumentEngine.noteOff`,
native `PadEngine` and MPC writers are unchanged. The current instrument format
still describes a loop and amplitude release, not this separately selected
state-dependent tail. The new WAV ZIP contains fixed performances, not a new
keygroup program. MPC format/device support remains a separate proof.

## Verified / measured / proposed / heard

| Claim | Status | Evidence |
|---|---|---|
| Actual held trajectory and complete snapshots are faithful | Verified/measured | Exact Kotlin public parity and zero fork error |
| PCM cache and streaming JS implementation are faithful to the specified shortcut | Measured | Integer identities and 201 sample-exact JS/Python comparisons |
| Shortcut meets the full release-accuracy contract | Failed | Three 32-position peak errors; full results preserved |
| Browser interaction and cleanup work | Measured | Actual Chromium AudioWorklet tests at desktop/mobile widths |
| Cache is ready for phone instruments or MPC export | Not established | Accuracy gate failed; production integrations unchanged |
| Separating recovery age from carrier phase improves the short taps | Proposed | No second prototype or device result yet |
| Cached ending is musically acceptable | Unheard | Owner comparison against the exact 137 ms tap required |

## Why it is built this way

1. Exact circuit continuations make the approximation's error visible; a smooth
   ending alone is not proof that it preserved the note's history.
2. Capturing onset and settling makes short taps a meaningful test, rather than
   always beginning from the easy recurring sustain.
3. Carrier alignment prevents arbitrary waveform cancellation, but it also
   exposes the recovery-age trade that needs further investigation.
4. A shared bounded PCM cache removes simulation from live playing and makes
   the one-note storage cost explicit before changing the phone format.
5. Keeping the failed peak limit stops a mostly matching tail from being
   advertised as a finished release-aware instrument.
