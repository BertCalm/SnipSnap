# Sunroom factory collection

Three 16-pad studio-pop kits preserve the approved R10 audition, including the R9 brush correction. The owner accepted the assembled sound on 2026-11-18: “Sounds much better, continue.”

This is a frozen factory collection, not a new editable Kotlin synthesis engine. The sources combine procedural synthesis and magnitude-spectrum resynthesis of CC0 acoustic references. Version 1 preserves all 432 approved mono 44.1 kHz PCM files exactly. Version 2 changes only the six soft/medium Psych A12 crescendo files; its three hard takes and the other 423 sounds are unchanged. There is no live phase solver, claim of independent physical modeling, or audio from the commercial reference songs.

## Kits and articulation

| Shelf kit | Character | Distinct percussion |
| --- | --- | --- |
| SUNROOM EARLY POP | Stick kit, toms and hats | Ride, crash, wooden block; finite snare roll/riser |
| SUNROOM ORCHESTRAL POP | Felt-style kick and revised brush snare | Slit block, castanet, triangle; revised brush roll/riser |
| SUNROOM PSYCH POP | Resonant kick and rim-led backbeat | Bongos, handclap, egg shaker, cabasa and full eight-second cymbal crescendo |

All occupy SKINS, use the audition's 116 BPM and preserve pad level 0.65. Only Early Pop A03/A04/A10 share a hat choke group. Every pad remains a finite one-shot, including the long crescendo. Melodic voices, keys, chord tools, HOLD and STRUM gates do not apply to these finite drum banks.

Each pad holds three velocity zones (1–41, 42–84, 85–127), each with three alternate outputs. Brush layers use separate recorded source strikes; other pads combine procedural and reconstructed-phase variations. These are not all nine independently recorded performances. Version 2 adds authored upper-mode damping at soft/medium touch over that measured crescendo. It retains timing and matches each original layer's RMS. These six revised files await listening; the previous hardware pass applies to version 1.

## Reproduction and source ownership

- Approved audition: https://sunroom-drums.bertcalm.chatgpt.site/ensembles.html
- Site source commit: `290b9a2f6a2057f33e1664e07af93269836ae5f9`.
- Native integration base: `df1a970793cc3f15b0b7feff0ade7d105ea1714f`.
- `scripts/import_sunroom_bank.py <site-checkout>` checks that revision and every WAV hash before compressing the assets. Gzip is lossless; each call to `SunroomKits.build` decodes independent buffers.
- Resource manifest pins every source WAV's hash, frames, role, kit, velocity and take. `NOTICE.txt` and three provenance files retain the CC0 source credits and exact paths/commits.
- `./gradlew --no-daemon :synth:generateSunroomKits` produces native `kit.json`, SFZ, DecentSampler, MPC 3 `.xtd`, and the MPC 2 twin under `testkit/sunroom-native`.

## Acceptance and verification

| Gate | Status |
| --- | --- |
| Full audition sound | Owner-approved, R10 |
| Multiple distinct genre kits | Three authored lineups registered on SKINS |
| Roll/riser | Finite authored gestures in all three kits; brush gestures use corrected contacts |
| Rendered asset identity | `SunroomKitsTest` checks all 432 decoded WAVs against the approved hashes and frame counts |
| Velocity / round robin software | `SunroomRoundTripTest` checks save/reload, all 127 MIDI velocities over three cycles, SFZ import, actual saved and MPC 3 PCM, and exported slice/velocity metadata |
| Timbral velocity | v2 crescendo has authored energy-dependent upper-mode damping at soft/medium touch; measured and software-tested, listening pending |
| MPC 2 | Preserves first take per velocity zone under the owner's combined velocity/RR format exception; XML boundaries checked |
| Hardware playback | Owner-reported pass, 2026-11-19: “Hardware passes- proceed.” Device models and individual format results were not specified |
| Editable engine / source controls | Not supplied by this frozen bank |
| Targeted checks | Sunroom source-bank, save/playback/export round-trip and convention tests passed again after the hardware-note update, 2026-11-19. All three packages regenerated; all 96 exported chain WAVs match the previously delivered packages exactly. Native C++ 8/8 passed |
| Full regression | GitHub Actions [tests](https://github.com/BertCalm/SnipSnap/actions/runs/44451954755) and [emulator-tests](https://github.com/BertCalm/SnipSnap/actions/runs/44451954874) passed on `a16dd8da8`, including all JVM shards, Android build and native tests. The interrupted local full run is not counted as a pass |

The shared MPC 3 chain-WAV slice-map limitation remains unchanged; see `ENGINE_ACCEPTANCE_GATES.md`. No exporter repair is claimed. This collection is not declared fully gate-complete.

## Why it is built this way

- Frozen renders keep the sound the owner accepted, including the corrected brush phase reconstruction.
- A saved per-pad chain carries all nine variants without collapsing round robin to a single hit.
- Independent decoded buffers allow pad edits without changing another kit or the source assets.
- Finite long gestures retain their complete tails; the eight-second swell is not silently shortened for export.
- The provenance distinguishes recorded-profile dynamics, authored dynamics and phase variants so software success does not overstate musical or hardware acceptance.

## Crescendo velocity refinement, 2026-11-21

[Compare original and revised](https://sunroom-drums.bertcalm.chatgpt.site/crescendo.html), including an optional loudness-matched three-touch sweep. The full-kit audition remains the approved R10 version.

`prototype/sunroom/crescendo_velocity.py` builds the six v2 overrides from the immutable v1 bank. A smooth frequency weighting above a 2.8 kHz knee reduces upper-mode excitation at lighter touches; the damping relaxes with the measured envelope. The depths (11 / 4.5 / 0 dB) are authored values, not independently measured acoustic parameters. Original phase, length and gesture timing are retained. Each modified layer is matched to its original energy; all hard WAV bytes remain unchanged. No new phase solver or pitch modulation is introduced.

Python harness measurements: mean power spectral centers are 2581 / 3837 / 4732 Hz, and energy above 6 kHz is 12.93 / 24.10 / 33.05 percent, soft through hard. Every layer stays within 0.2 percent of its previous RMS and has envelope correlation above 0.99. Three phase variants remain per zone. This is authored spectral shaping of reference-derived resynthesis, not separate acoustic performances or an independent cymbal simulation.

Version 2 resolves overrides explicitly and reads all unaffected assets from v1. The manifest pins every output. Tests reject gain-only dynamics, altered hard takes, changes outside the intended six files, collapsed round robin or changed lengths. The existing end-to-end test carries the revised grid through saving, SFZ playback and MPC export.

Why this change: upper-mode damping gives the light touch less high-frequency wash; retaining phase avoids introducing reconstruction warping; matching layer energy isolates timbre in the comparison. Loading remains a WAV decode, with the expensive analysis precomputed. Numerical success does not establish that the new touches are musically right; listening acceptance is pending.

Validation on the v2 branch: `SunroomKitsTest`, `SunroomRoundTripTest` and `generateSunroomKits` completed successfully (Gradle exit 0). The deployed audition FLAC decodes exactly to all nine native crescendo WAVs. Browser interaction was not independently automated because the managed browser helper was unavailable.
