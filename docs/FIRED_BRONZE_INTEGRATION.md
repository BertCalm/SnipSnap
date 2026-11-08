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
