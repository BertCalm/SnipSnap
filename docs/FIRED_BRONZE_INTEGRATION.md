# Fired Bronze integration

Approved audio baseline: `350d9a4d795d4c5d4fdc85d9aaf986efa96f2cfa`.
The integration includes current main `22ab69f22f8a0d5f88e151b11059565e4482622c`,
including its Erosion and Rivet registration changes.
Josh accepted the Performance kit on MPC on 2026-11-08: “Good on MPC, we can proceed”.
This freezes the existing source models, Both pushed treatment, sixteen-pad order,
pad mix, hat choke group, four native strikes (.20/.45/.70/1), peak matching and tails.

## As built

`FiredBronzePatch` is an editable adapter over those recipes, tagged `FIRED_BRONZE`
in the existing Patch JSON schema. It introduces no new synthesis model. Six controls:

| Control | Default | Meaning |
|---|---:|---|
| TUNE | .50 | Relative source tuning |
| TOUCH | .50 | Source strike color |
| DAMP | .00 | Source damping |
| HEAT | .72 | Processor drive before the amp |
| SPACE | .45 | Role-scaled echo and spring mix |
| AMP | .70 | Independent amp drive |

Compression stays at the approved .65 baseline, and delay timing at 96 BPM.
Native strike strength still changes excitation and reduces compressor, processor
and amp drive. The adapter shares the Performance response law, with unchanged
arithmetic at default settings. There is no HOLD: these are finite percussion
one-shots, including the long kettle/gong tails, not a sustained instrument.

Sixteen heard factory settings are registered, one per pad voice. Fresh Tape →
skins → FIRED BRONZE builds the four-layer kit with ordinary editable PadRecipes.
The Synth picker can reopen its pads and regenerate the four zones together.
Ordinary transforms remain blocked on layered pads. The exception requires the
native bank's recipe, four distinct sample files, named layers and exact velocity
windows; arbitrary imported banks are not reinterpreted as this instrument.
The existing profile attribution travels in each pad’s sidecar metadata, which MPC exports retain.
Additional ordinary FX can be replayed per strike. Compound boards and layer
recipes retain the guard because their layer semantics are different.

## Why it is built this way

- The adapter calls the accepted source and processing code so integration does not redesign the approved sound.
- Softer strikes regenerate the model and drive response; filtering the hard sample would lose the accepted articulation.
- Each edit rebuilds all four zones before writing files, preventing a render failure from leaving mixed old/new timbres.
- The factory roster contains only approved settings. Further variants need a separate listening decision.
- Separate HEAT and AMP controls retain the distinction that made the pushed version useful.

The [approved WAV fingerprint fixture](../shell/src/test/resources/fired-bronze/approved-wavs.json)
pins all 64 files from the hardware-approved download. The starter test compares
newly generated files to those hashes before testing edits.

## Verification ledger

- **Heard:** Josh accepted the Performance kit on MPC. No automated hardware measurement is claimed.
- **Verified:** 140 focused tests passed after merging current main, exit 0: 14 synth tests (all 64 approved render comparisons, macro edits, JSON replay, velocity response, determinism and registry coverage) and 126 shell tests (all 64 frozen WAV hashes, 96 BPM, four-zone edits/replay/save/reload, menu/catalog coverage, preset promotion, repository conventions and root handling).
- **Verified:** final `:app:assembleDebug` passed after the main-branch merge, exit 0. The APK signature verifies and all five fitted profiles plus attribution are packaged.
- **Full regression status:** the local full-suite attempt exposed four registration failures: catalog/README coverage (three checks) and preset-promotion mapping (one). It was stopped to fix those issues. All four now pass in the focused run. Full-suite completion is not claimed by these local checks; the draft PR's sharded CI records the full JVM, native and emulator results.
- **Not yet measured:** phone render latency and interactive Android listening. Prior host treatment-only timing remains in [the push report](FIRED_BRONZE_PUSH.md); this adapter does not claim a faster DSP path.

## Model 2: round robin (2026-11-13)

The approved kit has four strike layers per pad and one strike in each, so a repeated hit played the
same sample. The owner's gate asks for velocity and round robin on every instrument, and copies of one
strike are not round robin. On 2026-11-13 the owner chose to give the kit round robin.

**What changed:**
- **`FiredBronzePatch.model`:** a save keeps the renderer it was made with. A save from before models has
  no `model` and reads as model 1, the approved kit. Model 1 writes no `model`, so its saves read back
  byte for byte.
- **Model 2's takes:** `FiredBronzeInstrument.render` takes a `take`. On model 2 a later take is a fresh
  strike:
  - The kick, snare, hats and rings re-seed their own contact, rattle and residual noise. Their takes
    already did this underneath; model 1 never passed one.
  - The constructed sources (the Moving Bass, both clay pots, both kettles and the gong) were the same
    strike whatever the take. On model 2 each mode lands a little louder or softer, 1.5 dB rms, as a
    mallet off its last spot would (shape, after BREAKER's cymbal takes).
  - The Moving Bass also presses about 8% deeper or shallower each time, around a third of a semitone
    at the top of the bend. With the mode scatter alone, its soft strikes were still copies (42 dB apart).
  - Take 0 is model 1's, byte for byte, on every pad at every layer.
- **The shelf kit (`fired-bronze`):** each pad is one chain of the same four strike layers (velocity .20,
  .45, .70 and 1), each dealt three fresh strikes. The shared robin builds it (`Robin.firedBronzeGrid`).
  Every strike is peak-matched to the full strike, as the approved layers are. There is no zone level
  grading, so the MPC's velocity curve still sets the level, and no start trim, so no strike loses its
  attack.
- **Editing:** a model-2 pad reopens in the synth as a model-1 bank does. `FiredBronzePads.canEdit` knows
  both shapes, and `KitBuilderModel.replaceFiredBronze` re-renders the whole grid before it writes.
- **`Restrike`:** re-strikes a model-2 pad, so a robin a user deals on one gets fresh strikes too.

**Measured on the saved kit** (`FiredBronzeStarterTest`, `BreakerKitProof`'s measures; a copy is more
than 40 dB apart):

| Pad | Closest two strikes | Least-changed layer |
|---|---:|---:|
| Long Gong | 28.9 dB | 10.9 dB |
| High Kettle | 28.5 dB | 3.1 dB |
| Low Kettle | 27.9 dB | 5.1 dB |
| Covered Clay | 27.5 dB | 3.3 dB |
| Open Bronze | 25.1 dB | 2.4 dB |
| Open Clay | 22.0 dB | 3.4 dB |
| Closed Bronze, Bass Stroke, Bass Skin Kick | 18.6 to 19.4 dB | 2.3 to 3.7 dB |
| Bronze Foot, Center Ring, Rim Ring, Edge Ring | 12.2 to 16.8 dB | 2.8 to 3.7 dB |
| Moving Bass | 5.2 dB | 8.6 dB |
| Pressed Tap, Rim Rattle Snare | 2.7 to 4.6 dB | 1.8 to 2.1 dB |

**Kept as approved:**
- **Model 1 is unchanged:** `FiredBronzeInstrument.kit(model = 1)` still renders all 64 approved files to
  their pinned hashes.
- **The first strikes:** every grid layer's first strike is the approved layer, sample for sample.

**Changed for the player:**
- **Files:** one WAV per pad, sliced, instead of four.
- **Size:** about three times the audio.
- **Layer names:** the LIGHT, MEDIUM, FIRM and FULL names belonged to the four files.
- **MPC export:** MPC 3 carries every layer and strike. MPC 2 plays each layer's first strike only: a
  format limit, recorded as not applicable to the round-robin gate on 2026-11-14 ([ENGINE_ACCEPTANCE_GATES.md](ENGINE_ACCEPTANCE_GATES.md#mpc-2-and-round-robin-owner-decision-2026-11-14)).

**Heard (2026-11-13).** On the published page (repeated hits before and after, and every pad's four
layers of three strikes) the owner answered:
- **Repeated hits:** "Yes, fresh strikes". They sound like the drum played again, not one sample repeated.
- **Every pad:** "Yes". Each layer's three strikes are the same instrument hit again.
- **The Moving Bass's press:** "Natural".
- **The kettles' and gong's new balance per strike:** "Natural".

Preserve model 2 as the accepted round robin.

**Not yet:** a phone or MPC playing the new kit.

