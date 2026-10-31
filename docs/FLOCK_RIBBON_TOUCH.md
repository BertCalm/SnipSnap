# FLOCK RIBBON: touch selects the disturbance

Luthier CONCEIVE implementation, 2026-10-26. Repository baseline: `00d74a2b`.
This extends the held RIBBON instrument, using the existing three-layer player
and package path. It does not change the source model or finite renders.

The characteristic gesture is **a soft or forceful touch → a correspondingly
disturbed opening → that performance's captured moving body → release on lift**.

## What changes

The existing flock simulation is literal. Source velocity scales its initial
SCATTER disturbance and source amplitude. Three independent renders sample that
law at 0.35, 0.65 and 1.0 times the saved velocity. The saved velocity is the
hard-touch anchor, including when it is below one. The seed, source model,
character macros and played root stay the same across all three captures.

Touch selects an already rendered performance. There is no simulation on note-on,
continuous morph, aftertouch or interaction between notes. With SCATTER at zero,
the source histories can coincide; the implementation does not add artificial
disturbance to force contrast at that setting.

Nearest implemented siblings are CREASE's sampled pressure histories and BELLOWS'
sampled breath strengths. They establish the delivery mechanism; neither supplies
FLOCK's neighbor-driven pitch trajectories. The landing is an optional instrument
builder setting, not another engine, source-model version or macro.

| Direction | Gesture, roster value, risk and cost |
| --- | --- |
| PORT the existing three-layer keygroup path | Touch changes the source history; reuses working selection and export; discrete boundaries need listening; three captures per root. Selected. |
| STACK filtered copies of one capture | Cheap tonal contrast, but no new flock trajectory; duplicates the gain-only limitation in a different form. Rejected for this request. |
| COUPLE live touch to a running flock | Continuous response, but requires realtime synthesis and a new export interpretation; outside the offline instrument contract. Deferred. |

## Signal flow

```text
saved patch, root, seed, model
              |
              v
source velocity × [0.35, 0.65, 1.0]
              |
              v
three serial FlockHeld captures [existing bounded source cache]
              |
              v
match loop loudness to the hard capture -> one shared headroom attenuation
              |
              v
three complete attack + loop WAVs per root
              |
              v
MIDI touch window selects one sample -> existing linear touch gain
              |
              v
existing phone / MPC player -> release from current phase on note-off
```

Each capture keeps its own real two-second opening, selected endpoint and roughly
six-second loop. Layers may have different lengths. Copying the hard layer's loop
metadata onto the other files would play the wrong endpoint.

## Performance contract

| Control | Default / mapping | Model or delivery quantity | Proof |
| --- | --- | --- | --- |
| Saved VELOCITY | Existing patch value | Hard-touch excitation anchor | Layer source-velocity and saved-recipe tests |
| SCATTER | Existing patch value | Initial flock disturbance × source velocity | Gain-matched source comparisons; zero-SCATTER disclosed |
| TOUCH LAYERS | On when opening FLOCK MAKE; off remains available | One or three independently captured performances per root | App toggle, maker and CLI tests |
| Soft touch | MIDI 1–42; source ×0.35 | Select soft capture, then ordinary touch gain | Boundary selection and exported-PCM playback |
| Medium touch | MIDI 43–84; source ×0.65 | Select medium capture, then ordinary touch gain | Boundary selection and exported-PCM playback |
| Hard touch | MIDI 85–127; source ×1.0 | Select saved-anchor capture, then ordinary touch gain | Boundary selection and hard-source identity |
| RELEASE | Existing saved 0.1–1.5 seconds | Player amplitude after note-off | Release tests across layers |

The source renderer already makes softer performances quieter. Leaving that
attenuation in the samples and applying the player's touch gain would create a
second volume change at each layer boundary. Instead, each loop is matched to
the hard capture's measured sustain loudness. One common attenuation then keeps
the largest attack across all three samples within a 0.90 peak ceiling. The hard
capture stays identical when that common attenuation is unnecessary. The onset's
relationship to its own body is preserved by a single scalar per complete file.

This matches sustain level, not waveform or motion. A discrete switch can still
be audible. No overlap or crossfade between velocity windows is claimed. Zero
saved velocity remains silence. Tiny positive anchors use finite gain arithmetic
and do not trigger division by zero.

## App, CLI and replay

In SYNTH, load **FLOCK → RIBBON → Traveling Turn → HELD**. The main hold audition
continues to play the saved anchor. In MAKE INSTRUMENT, TOUCH LAYERS enables the
three-per-note bank and its soft/medium/hard reference-player preview. On the
instrument keyboard, the existing touch-position mapping selects the layers.

The builder's default API and CLI without `--touch` retain the single-layer path:

```sh
./gradlew :cli:run --args='synth FLOCK RIBBON --instrument --touch --release .6 --out /tmp/ribbon-touch'
./gradlew :cli:run --args='synth FLOCK RIBBON --patch source.patch.json --instrument --touch --out /tmp/ribbon-replayed'
./gradlew --no-daemon :shell:generateFlockVelocityInstrument
```

The `.patch.json` remains the unchanged editable source. Touch exports also carry
a versioned `.instrument.recipe.json` with the touch-render version, strengths,
windows and replay flags. Replaying source JSON alone does not imply touch layers;
the instrument replay command includes `--instrument --touch`.

Phone sidecar v2, MPC 3 `.xty`, MPC 2 `.xpm` and each PCM24 WAV's sampler chunk
retain all three independent sample and loop descriptions. No generic player or
export format change is needed. The original finite, sustain-study and single-layer
downloads remain the record of the earlier rounds.

## Cost and risks

There are nine rendered roots and 27 samples. Zones and layers render serially;
note-on only selects PCM. Cold rendering costs three captures per root. The
existing 12-entry / 16 MiB source cache is unchanged, so it does not retain a
complete 27-sample bank. The complete float bank is about 38 MiB before player
copies; actual package size and worker timings belong in the generated manifest.
Release and name changes do not change source PCM.

The hearing gates are source contrast, level steps at MIDI 42/43 and 84/85,
repeated-body audibility, low/high register behavior, chord headroom and release.
Target-phone memory, edit latency and MPC load/playback remain hardware checks.
Soft source histories need not sound brighter, darker or more chaotic monotonically;
the model changes the initial disturbance rather than applying a brightness law.

## Evidence ledger

| Claim | Status | Evidence |
| --- | --- | --- |
| Layer-aware selection, sample loading, independent loops and exports already exist | Verified | `KeyHit`, `InstrumentEngine`, `InstrumentPlayer`, `InstrumentStore`, `OneNote`, `CreaseTouchKeys`, read at `00d74a2b` |
| Source velocity changes the initial SCATTER disturbance | Verified | `Flock.Cloud`, read at `00d74a2b` |
| Original FLOW and the earlier sustain study are accepted | Heard by owner | Earlier audition feedback |
| New touch layers and their boundaries are accepted on hardware | Open | Requires the new audition and physical playing |

## As built

The first Kotlin generator run measured 1,046–1,202 ms per three-layer root on
this worker, including warmup, and 9.03 ms for a cached three-layer root. All nine
roots required no common headroom attenuation, so their hard captures retained
their accepted PCM. The 27 reloaded PCM24 files peaked at 0.6322 or below, with
absolute mean below 0.000020. Loop periods spanned 5.9002–6.0999 seconds; the worst
50 ms blend dip was -3.95 dB and largest crest +2.82 dB. These are capture
measurements, not listening acceptance.

Actual C4 boundary takes differed by -0.54 dB RMS at MIDI 42/43 and +0.25 dB at
84/85. Matching the house sustain meter does not make entire performances have
identical RMS, and a timbral boundary still needs listening. The separate matched
listening takes receive a disclosed gain to a common RMS near the hard take's
level, with common peak headroom if needed. That diagnostic does not change the
instrument's samples or its normal touch response.

The 54 focused FLOCK tests passed, including historical finite golden checks.
The Android APK and device-test code compiled. Device execution and the complete
suite are separate gates, recorded on the pull request; this compile result is
not a hardware playtest.

Measurements, exact source revision, package checksums and test results are recorded
in the generated manifest and implementation pull request. Reference-player WAVs
reload exported PCM24; they are not phone or MPC recordings.

## Why it is built this way

1. Touch changes the flock's arrival, so playing harder selects a different
   performance rather than only turning up the same recording.
2. Three source histories fit the existing phone and MPC players. More layers
   would multiply rendering and memory before the owner has judged these three.
3. Sustain matching leaves touch in charge of volume and makes the boundaries
   easier to judge for character instead of an avoidable level jump.
4. Each layer owns its loop because different disturbances find different useful
   endpoints. Sharing one endpoint would weaken the accepted capture method.
5. The single-layer option and source patch remain available, while a separate
   delivery recipe records exactly how the new sampled instrument was assembled.
