# GRAIN and ANCHOR in SnipSnap

The characteristic gestures remain the ones selected in the voice study:
GRAIN approaches, scatters short surface contacts around a stable root, then
leaves a dry residue. ANCHOR accumulates pressure away from the encounter,
releases an odd-harmonic surge, then carries the remaining resonance forward.

This ports two preferred voices into the existing APSIS engine. PASSAGE keeps
its six presets, three saved renderers and sixteen-pad musical bank. TETHER,
VESSEL and CORONA remain listening prototypes. The engineering base inspected
for this port was `82a1f9c74086d574d4de3eb1b7dc0944069c89d6`.

## Sound and controls

The Kepler trajectories and their shared period law are literal calculations.
The relationship between a moving body and its excitation is a musical analogy;
this does not simulate acoustic radiation from an orbiting physical instrument.
GRAIN creates synthetic contacts, without recorded grains or sample playback.

Both voices reuse `ApsisOrbit` and the existing normalized 27-mode bank.
Each mode freely rotates with a damping radius below one. Center/body exchange
uses the bank's orthogonal Cayley junction. Direct excitation, pressure and noise
state belong to each render; the cache contains only immutable motion fields.

| Voice | Excitation | Body ratios | Body T60, seconds |
| --- | --- | --- | --- |
| GRAIN | Narrow repeated angular contacts, root plus seeded noise; noise drives the upper material modes | 1, 3.17, 6.43, 10.1, with small upper-mode body offsets | .16, .065, .04, .025 |
| ANCHOR | Convex pressure charge/release, root plus velocity-dependent third/fifth/seventh harmonics | 1, 3, 5, 7 | .55, .28, .16, .09 |

These are the selected Python study's musical constants, not measured material
properties. The center keeps its independent root. Upper material ports exchange
through the center; the final observer reads modal velocity. There is no dry
oscillator added after the bank and no per-encounter modal reset.

STRETCH, DISTANCE, BODIES, ALIGN, COUPLE and HOLD keep the existing meanings.
ROOT spans MIDI 36–84. Native VELOCITY changes the excitation spectrum before
output leveling. ANCHOR contains no random source: its seed survives recipes but
does not change the harmonic pressure source. GRAIN uses `Dsp.seedFor` and
`Dsp.Noise`, retaining the complete signed seed context.

GRAIN's approved COUPLE response is subtle. Across the full six-second take,
0→.5 changes its raw waveform by .00520 relative RMS; ANCHOR changes by .05524.
These are engagement measurements, not audibility verdicts. The port retains
that response. A separate recipient-only test proves that a directly unexcited
body answers through the center and becomes silent when coupling is disabled.

## Starter recipes and app behavior

| Voice | Starters | Authored root / velocity |
| --- | --- | --- |
| GRAIN | Grain Passage, Scattered Grains, Held Grains | C4 / .65 |
| ANCHOR | Anchor Bass, Hollow Surge, Held Anchor | C3 / .65 |

Each voice gets the selected identity, one movement variation and one held
version. The shared picker, CLI, native velocity path, drafts, user presets,
SPREAD root metadata, pad recipes and exporters use the same `ApsisPatch`.
No extra tuning macro is added. The original PASSAGE bank remains unchanged.

HOLD ≥.99 fits a common six-inner-period return to the sample/root clocks.
The complete modal and pressure state runs to convergence across independently
rendered cycles. Noise and root forcing repeat on the same clock. Output filters
run through real warmup, preceding, kept and following cycles before the kept
cycle is cut. There is no pasted-copy recurrence proof or loop crossfade.
Long diagnostic captures also restart the forcing clock at each full return.

Held Android previews repeat until STOP. Held pads are gated full-sample loops;
finite notes keep complete one-shot tails. Both existing MPC drum exporters
retain the loop flag. Keyboard multisampling and physical phone/MPC listening
acceptance remain separate work.

## Saved audio and compatibility

Audio identity is `(voice, modelVersion)`. GRAIN and ANCHOR begin at model 3;
they reject models 1/2 because those definitions never existed. Missing/null
model metadata chooses 3 for these new voices. PASSAGE still chooses model 1
when model metadata is missing/null and accepts explicit models 1, 2 and 3.
`ApsisConfig()` and PASSAGE's current model remain 3.

Before/after captures on Java 17 compared PASSAGE at C4, native velocity .65,
seed 17, defaults and HOLD=1 for each saved model. All six final Float32 sample
hashes were unchanged. Their evidence is in
`docs/apsis-population-compatibility.json`.

## Preparation and cost

The new voices share a separate six-entry, 16 MiB motion cache, with at most
4 MiB per dense entry. Keys include exact Float bits for STRETCH/DISTANCE/ALIGN,
held state, duration, internal rate, canonical grid and clock ablation. Finite
notes share geometry across voice, note, native velocity, seed, BODIES and COUPLE.
Held pitch can change the fitted duration and therefore the key. All pressure,
oscillator, noise and resonator state is rebuilt locally. Cancellation is checked
during preparation and synthesis; incomplete preparations are never retained.

The native grid is the study's 128 internal frames at 4×. A diagnostic grid too
large for the entry cap retains only the orbit descriptions and evaluates
canonical nodes on demand. Oversampling probes support 4×, 8× and 16×.

On the session's Java 17 JVM, warmed native six-second renders took roughly
124–215 ms for GRAIN and 145–209 ms for ANCHOR. Held renders were roughly
.73–1.44 seconds. Other verification was running on the same machine, so these
are indicative desktop measurements, not a phone latency guarantee. Geometry
hits in the audition harness took under .3 ms; synthesis dominates. The house
goal of knob edits completing in tens of milliseconds is **not yet met**.

## Reproduce and verify

```sh
./gradlew --no-daemon :synth:generateApsisPopulations
python3 scripts/compare_apsis_populations.py \
  --reference /path/to/voice-study/scripts/apsis_voice_study.py
./gradlew --no-daemon test -x :app:test
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
```

Use the retained study checkout at
`fe897be84044ad9182db2f9f6c820374045e7f61` (the later attack-memory study also
preserves these two voice laws). The comparison needs NumPy, SciPy, Numba and
the study checkout's Luthier helper files. Output goes to the ignored
`testkit/apsis-populations`: native PCM24 WAVs, raw/matched listening PCM16 WAVs,
complete JSON recipes, score events, internal 4× Float32 captures and reports.
The phrase uses C3/G3/E-flat3 at 0/1.8/3.6 seconds, native velocities
.65/.45/.85 and gain .35, retaining each note's complete tail.

Measured core fidelity: twelve internal-radiation comparisons cover both voices
at C2/C4/C6, soft/hard touch and HOLD. The maximum relative RMS error against
the Python laws was `3.035e-8`, with correlation above `.99999999999999`.
The comparison uses the native Float recipe values and effective root, before
the different Python/Kotlin output filters. It does not claim byte identity for
the conditioned audition WAVs. The Kotlin path uses `Tide.bandLimit`, cascaded
2:1 resampling, mean removal, 20 Hz high-pass and the house melodic level target.

Pitch probes at C2/C4/C6 were within .4 cents for both voices. Held seam errors
were below `1e-12`, measured against actual preceding cycles. Tests also cover
zero energy, finite/DC/peak bounds, free-state contraction, native touch, shared
cache isolation, cancellation, stem sums, recipe/model compatibility, cold-cache
determinism and finite/held save/reopen/export through both kit exporters.
Android instrumentation covers picker selection, controls, edited recipe save,
navigation, YOURS, pad delivery and reopening for the new voices.

Verified and measured: the implementation and the checks described above.
Proposed: the two variation presets, their musical usefulness and phone render
budgets. Heard: the owner selected the Python GRAIN/ANCHOR identities; the Kotlin
output-stage comparison still needs the owner's listening decision.

## Why it is built this way

- Separate excitation laws retain the two identities selected by the owner.
- The shared passive modal bank gives earlier resonance somewhere to persist
  while the next encounter arrives, without resetting or adding feedback gain.
- Geometry is cached separately so a note, touch or material edit cannot reuse
  another render's mutable sound state.
- Saved voice/model identities keep the old PASSAGE audio reproducible while
  giving the new voices a fixed first definition.
- The audition compares actual Kotlin output with the preferred studies because
  a close internal numerical match cannot decide whether the output filters
  preserve the character the owner liked.
