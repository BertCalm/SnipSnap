# Caustic completion: design brief

2026-10-31. Status: selected implementation contract, validation pending.
The four finite native voices are implemented and measured. The owner selected
their earlier study directions; the native finite ports still await explicit
listening approval. New sustain, motion and held-key behaviour are unheard.

Repository baseline: `d7c2379c16520df2679457679844b56d32777712`.
Read the canonical Luthier conventions and existing Caustic adaptation. The
original `36-Caustic-Engine-Engineering-Spec.md` attachment is no longer present
in this workspace; this completion uses the current adaptation and explicit
model-2 contracts. It does not certify every feature of that earlier proposal.

The characteristic gesture is **contact → singing body → recurring moving
concentration → played release**.

## What is being completed

Extend ALLOY, HOLLOW_MALLET, RUBBED_RING and TRAVELLING_CATCHES within the
existing CAUSTIC engine. Native model 2 adds real HOLD, SOURCE/FOCUS motion
before resonant excitation, and playable onset-plus-loop keygroups. The
eleven model-1 factory recipes, missing-model JSON interpretation and archived
`CausticRecipe` models 1–4 remain unchanged. Model 2 at MOTION=0/HOLD=0 must
retain the earlier finite PCM exactly.

The circular single-bounce reflection equations and delayed signed reception
run literally. Emission-window interpolation is a musical approximation for
moving geometry. Material tables, pressure shapes and trajectory timing are
sound-design choices. This is not a full moving-wave, collision, friction or
three-dimensional acoustic solver.

## Landing and nearest siblings

The lightest landing is a versioned completion of CAUSTIC, plus its existing
phone/CLI and instrument-export paths. MERCURY already sustains struck/rubbed
modal objects with water loading. REVEL already moves microphones around
scheduled skins. Neither replaces Caustic's coherent circular source-to-pickup
excitation map. The current task extends that map while keeping the four
existing voices; it does not justify another engine or a rack section.

## Three directions

- **Selected, PORT:** move prepared reflection geometry and sustain the same
  voice bodies. Gesture fit: strong. Roster distinction: retains Caustic's
  source/pickup phase map. Risk: moderate, concentrated in interpolation and
  periodic-state proof. Cost: one shared moving-path and HOLD implementation
  plus four bounded voice sources and keygroup integration.
- **Alternate, SPLICE:** replace each exciter with a sibling-style bidirectional
  friction driver. Gesture fit: strongest for Rubbed, weaker for the selected
  strike/catch voices. Roster distinction: smaller beside MERCURY. Risk: higher
  due to feedback solving and pitch capture. Cost: another contact model and
  a new listening round for the replaced finite articulation.
- **Alternate, COUPLE:** feed vessel response back into the source so focus
  controls self-sustain. Gesture fit: potentially strong. Roster distinction:
  greater feedback interaction. Risk: high, because cancellation, loop gain and
  tuning now interact. Cost: new stability bounds and a larger probe matrix.

PORT wins because it completes the chosen instrument while preserving its
measured finite starting point and feed-forward stability model.

## Signal flow

```text
saved model + voice + MIDI + velocity + six controls
  → bounded continuous/recurrent source and optional periodic trajectory
  → source emission windows [prepared geometry cache]
  → signed causal reflection paths and modal reception
  → passive modal bank
  → continuous band limit / decimation / DC conditioning
  ├─ finite output or verified pure loop Snip for drum pads
  └─ real zero-state arrival + settled loop region as KeyNote for held keys
```

The movement changes the drive before the modal sum. A final scalar envelope
cannot stand in for path motion. At MOTION=0 the geometry is exactly stationary;
oscillators, source texture and modal state can continue normally.

## Macro contract

| Control | Default | Model quantity and required interaction |
| --- | --- | --- |
| SOURCE | .5 | Existing inverse-distance illumination, around which the motion trajectory moves. SOURCE × FOCUS changes which paths are collected. |
| FOCUS | .19 | Finite pickup-band location. FOCUS × MOTION changes concentration timing and spectral order. |
| BODY | ALLOY 1; Hollow/Rubbed .5; Catches 0 | Existing voice-specific contact, pressure or metal fullness. BODY × HOLD changes the sustained excitation/body response. |
| LENGTH | 1 | Finite gesture/release scale or full-HOLD loop planning. LENGTH × HOLD changes duration before the looping top step. |
| MOTION | 0 | Smooth movement of the actual SOURCE/FOCUS path states, with exact stop at zero. |
| HOLD | 0 | `0 <= H < 1`: effective LENGTH adds `4*H/7.5`; H=1 selects sustained periodic rendering. |

ROOT and VELOCITY remain separate saved fields. Supported roots stay MIDI
36–84. Finite model-2 duration is `.5 + 7.5*LENGTH + 4*HOLD` seconds below
the top step. Full HOLD uses its own pitch-preserving loop plan; it cannot be
implemented by repeatedly playing the tail of the finite recording.

## HOLD and held-key contract

Each voice needs bounded ongoing excitation appropriate to its gesture.
Alloy's metallic body, Hollow's short contact, Rubbed's pressure/noise and
Catches' ordered spatial contacts must retain their distinction at full HOLD.
The final source shapes and schedules await implementation measurements and
listening.

Render the real zero-state onset and settling trajectory. Obtain the passive
bank's periodic state from a bounded solve or verified convergence, then render
three real cycles. Source phase, path/window phase, modal state and output
filter history must recur before selecting a region. Filter before cropping.
Measure the kept cycle against an independent preceding/continuing cycle;
playing one crop twice is not a state-continuation proof. `Keys.seamError`
must remain below `Keys.MAX_SEAM_ERROR` (`1e-3`), alongside measured pitch and
state residuals. Do not claim these checks have run yet.

Drum-pad HOLD output is a pure loop `Snip`. `CausticInstrument.keyNote` supplies
an actual onset followed by sustain and a positive `loopStartFrame` for
keygroups. `CausticPadMaker` captures up to nine roots spaced by minor thirds
over the saved note's ±12-semitone neighbourhood, clipped to 36–84 with
boundary roots included. One velocity layer per zone preserves the requested
excitation. The phone/MPC player supplies live gain and .1–1.5-second note-off
release, default .6 seconds. MPC 2 and MPC 3 must preserve those loop bounds.

## Compatibility and integration

`CausticInstrument` and `CausticPatch` distinguish native models 1 and 2.
Missing/null saved native model remains 1; new constructors use 2. The strict
model-1 core rejects positive HOLD and unsupported MOTION fields. Its earlier
recipe interpretation and eleven presets remain frozen.

`CausticEditing` can promote an editor working copy on an explicit HOLD,
MOTION or INSTRUMENT request. It must not silently promote ordinary replay,
reuse a stale positive control bag as authorization, or overwrite the saved
source patch merely to render a held zone. Export retains the source recipe
and the actual onset/loop samples. Preview must exercise the instrument player
and release, not substitute a drum-pad loop audition.

## Render cost and proof sequence

Prepare a bounded set of static transfer states outside the sample loop.
Interpolate emission windows through those states; avoid tracing every
ray/pickup pair at every 4× sample. Cache only reusable prepared data, keyed by
model, geometry, trajectory and resolution. Render state remains note-local;
any complete-render cache must also include voice, note, velocity and output
settings. Preparation, held render, filter preroll and all keygroup zones need
separate cost measurements. The house goal is short-note rerenders in tens of
milliseconds; no model-2 timing has been established.

The cheapest proof begins with one stationary full-HOLD voice, independently
checked periodic state and a real onset. Then prove one moving SOURCE/FOCUS
trajectory changes normalized spectra before the bank, extend the same checks
to all four voices, and finally read back exported zone bounds/release. Required
checks include legacy PCM preservation, zero-extension model-2 equivalence,
determinism, source/root phase, bounded raw state, independent continuation,
4×/higher-rate comparisons, control interactions, cancellation and register
edges. Run the current complete repository gate only after the implementation
is ready. Build, seam, aliasing, timing and export results remain pending.

## Verified, proposed and heard

- **Verified:** canonical conventions, the baseline Caustic/Keys interfaces and
  current model-2/editing/pad-maker contracts were read at the baseline above.
  The baseline `:synth:classes` build passed before completion validation.
- **Proposed:** the new held sources, geometry motion, loop/state and keygroup
  contracts above. Implementation constants and listening acceptance are open.
- **Measured:** no completion pitch, seam, state, aliasing, readback or timing
  results are claimed here. Historical finite-pack measurements remain in
  [CAUSTIC.md](../../CAUSTIC.md).
- **Heard:** the owner preferred Alloy/Fuller Body and selected the three
  character-study directions. Native finite ports still await a listening
  verdict; new moving and sustained sounds and held-key behaviour are unheard.

## Why it is built this way

- Extending the existing four voices keeps the selected studies as the anchor
  and avoids another engine with the same path/body architecture.
- Moving reflection drive before resonance can change which parts of the body
  sing. A final gain sweep would only make an unchanged body louder or quieter.
- A real arrival before the loop preserves a played onset. A pure loop remains
  useful separately for a drum pad that should start directly in sustain.
- Independent state and filter continuation checks protect against a loop that
  has a quiet splice but changes its motion or ringing at every repetition.
- Model versions and explicit working-copy promotion keep earlier saved notes
  reproducible while exposing new controls when the owner requests them.
