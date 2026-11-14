# Spore Station

A candidate psychedelic jungle kit: two corresponding 16-pad banks at 170 BPM,
with three timbral velocity layers, quiet ghost snares, shared hat choking and
editable source/FX recipes. Uses ONSET, SKIN, TINES and SIREN; adds no new engine.

The characteristic gesture is tight breakbeat → ghost-note chatter → metallic
mutation → dark echo. Core keeps kicks and hats focused; Mutations introduces
converter texture, ring/phase movement, short tape coloration and baked echoes.

[Open the published audition](https://spore-station-jungle-audition.bertcalm.chatgpt.site)
(owner-private). The page includes both MPC kit downloads.

## Build and audition

```
./gradlew --no-daemon :synth:generateSporeStationAudition
```

Validate the generated files and page controls with:

```
python3 scripts/validate_spore_station.py
node scripts/test_spore_station_page.mjs
```

Output: `testkit/spore-station/`. Open the hosted audition or serve that folder
with an HTTP server; its page loads `manifest.json` and the rendered WAVs.
The output includes Core and Mutations MPC kit ZIPs, a listening pack,
raw/matched single hits, four two-bar groove bounces, five control sweeps,
three control-pair corner studies, metrics and native recipes.

Source entry points: `SporeStation.parts`, `SporeStation.recipe` and
`SynthKits.sporeStation(mutation)`. The five recipe-builder controls are BODY,
BITE, DUST, TWIST and SPACE; they are not new Android controls. TWIST/SPACE
are intentionally restricted by pad role to protect the kick and hats.

## Playback and limits

- Raw standalone files retain renderer levels; program mixer levels are separate.
- Matched single hits support timbre comparison and do not preserve ghost loudness.
- Grooves retain program levels, velocity timbre, linear audition velocity gain
  and a 5 ms hat-choke fade. They are software demonstrations, not MPC recordings.
- Echo times are baked for 170 BPM. The rack retains its one-second tail budget.
- Sources and treatments are mono; groove bounces support stereo. No stereo-width claim.
- Owner listening, phone timings and physical MPC loading remain pending.
- Three genre starters are registered in FRESH TAPE; Android device verification remains pending.

See the [full specification](superpowers/specs/2026-11-09-spore-station-jungle-kit.md)
for the pad map, macro contract, risks and evidence ledger.

## Measured first build

The generator produced 79 clips / 158 raw and matched WAVs. All source recipe
replays, finite-output/peak checks, velocity-layer PCM comparisons and both
MPC-format read-backs passed. Asset hashes, archive contents and page-control
smoke checks passed. Page controls were checked with a mocked DOM; actual
browser audio decoding was not verified in this environment.

A short Heel Kick retains a full-file mean of .012343 (dry .012284). This is
reported in the audition metrics; no new DC correction was applied. The
first successful cloud JVM run rendered individual dry sources in 1.19–22.59 ms;
this is not a phone benchmark. Full-kit rendering includes all velocity layers.

Published Site source: `1200e8359b8b48024a7e7b7fe1feb8064ee6f072`.

Full regression: `./gradlew --no-daemon test` exited 0, with 9,239 JVM tests,
zero failures, zero errors and zero skips across nine modules. Android was not
included because this environment has no SDK. The full run took 1h 25m 20s.
The published audio source files are unchanged from that validated build.

After the full regression run, upstream advanced to `18378ec9`. The merge
preserves the incoming Cosmic Breaks, HALOBAR and UI changes alongside Spore
Station. Declaration conflicts were resolved without changing Spore Station's
DSP or recipes. Post-merge validation passed 105 focused synth/shell/CLI tests,
full audition regeneration and both MPC exports. All 158 audition WAVs and all
96 exported velocity WAVs are byte-identical to the published build. The full
9,239-test result above is from before this upstream merge; the complete suite
was not repeated on the merged head. Browser and MPC hardware checks remain pending.

## Version 2 gate update

Three shelf kits: Jungle (170), Dub (85), Electro (130). Each has 16 hits
and B01 roll/B02 riser, with three velocity zones and three takes per zone.
SnipSnap saving/playback and SFZ export preserve the complete grids. Downloads
include a native kit folder. Grids carry provenance rather than a single-hit
recipe that cannot regenerate all their takes.

MPC round robin is **not accepted**: XPM uses the first take of each zone;
XTD has cycle settings but the existing chain-WAV slice-map implementation
is bench-blocked. Hardware loading/listening and browser decoding remain
pending. The earlier validation history above describes v1, not v2.

Published v2 Site source: `ef2a566b569eb02f0071ef83e9966b235f5dbac8`.
The asset validator passed 97 clips / 194 WAVs, all download hashes, and
486 SFZ region windows and sequence positions across three kits. The
original 79 raw clips remain byte-identical to v1. Page controls passed the
mock-DOM smoke test; this does not verify browser audio decoding.

Cloud JVM full-grid build times were 4,674.5 ms (Jungle), 4,985.2 ms (Dub)
and 3,782.3 ms (Electro), each including 18 pads × 9 takes and the authored
phrases. These are full-kit generation costs, not single-knob or phone timings.

The published v2 manifest's source hashes identify the render sources in
SnipSnap commit `de51f10bc9ab096e9611870611401055f3fc81a4`. Subsequent
verification notes, the signed-zero silence assertion and the object-level
provenance comment do not change audio.

V2 verification: 624 kit tests, 439 loop tests, 306 CLI tests and 40 focused
synthesis/articulation tests passed. The complete 83-starter preflight,
shelf/convention checks and Spore save/playback/SFZ integration checks passed
after updating the starter expectations and accepting either signed zero as
silence. The final targeted-plus-CLI command exited 0. The earlier broad shell
run exercised 2,266 tests; its stale registration expectations were corrected
and rerun. The full synthesis suite was not repeated for v2.

## Version 3: dry jungle revision

The Jungle core now has new SKIN model-1 sources on A01–A10, with no finishing
rack: shell/beater kicks, a body-forward main snare, a tight bright alternate,
quiet ghosts and short low-ring cymbals. Jungle's shelf grids and roll use these
sources. Dub and Electro retain their existing sounds. The audition begins with
a same-pattern dry A/B comparison and defaults to the new Jungle core tab; the
previous core remains available. Hearing acceptance and the existing MPC slice-map
blocker remain open.

V3 validation passed eight focused synth tests and the three-kit save/playback/
SFZ round-trip test. All 115 clips / 230 WAVs and 486 SFZ regions passed asset
checks. The previous 79 raw clips and every Dub/Electro exported WAV remain
byte-identical. Dry source differences are tested before FX; the paired grooves
share comparison gain. Main snare render length: 195.1 → 172.3 ms; open hat:
488.1 → 402.0 ms. These are rendered buffer lengths, not T60 measurements.

V3 published Site source: `ba45ba125f3e02339b9d1031efc32727276d5fc0`.
The prior PR head passed JVM, Android build and native CI; its emulator job
failed and the log download returns HTTP 403 in this environment. Device
verification is unresolved. This sound-design revision passed its local
render/replay, grid integration, asset and mocked page-control checks.

## Version 4: bombastic accents

The owner corrected the restrained direction. The new Jungle core adds kick
shell weight, beater/drop, longer and harder snare accents, and bigger cymbal
accents. Ghosts and pickup kicks retain their exact version-3 settings. Core
pads remain dry. The opening shared-gain A/B compares the previous restrained
core against the bombastic revision; a separate tab retains all version-3 hits.

V4 validation: nine synth tests and the three-kit save/playback/SFZ integration
test passed. Asset checks passed for 131 clips / 262 WAVs and all 486 SFZ
regions. The raw kick and main-snare energy in the 30–150 ms body window is
1.90× and 2.80× the restrained version respectively, before pad mixer gains.
All sixteen previous restrained hits and Dub/Electro exported WAVs remain
byte-identical. Owner listening and device verification remain pending.

V4 published Site source: `8d64ab4ecce0a65dc2d5f9e706380def36890ced`.


## V5 — sound-system accents

The dry Jungle core now has a deeper kick sweep, lower and longer main snare, brighter tearing alternate snare, and louder open cymbals. Ghost, pickup kick and closed hat settings remain unchanged. The immediately previous v4 bank is preserved in the audition. Shared-gain A/B clips use the same pattern and velocities.

Nine synth tests and the three-kit save/playback/SFZ integration passed. The 131-clip/262-WAV asset validation and DOM-mock control checks passed. All 16 reference hits exactly match v4, and every Dub/Electro exported WAV remains byte-identical. Measured raw-render energy between 30 and 150 ms is 1.95× for the kick, 4.28× for the main snare and 8.69× for the alternate snare versus v4; these measurements do not establish listening acceptance.

The listening ZIP is 24,845,017 bytes. Browser decoding, owner listening and device checks remain pending. MPC hardware round robin still needs the chain-WAV slice map; the earlier emulator CI failure remains unresolved.

Published audition source: `526d6e2568dbedf72859fd23ed4db1ec48fadebf`; deployment `appgdep_6af20de182248191b21e9fcecdb39ed3` succeeded.


## V6 — articulated jungle breaks

The owner's three screen recordings identify old-school 160 BPM breaks, a live drummer at 170 BPM and a 175 BPM drum loop. These supplied examples inform the direction; the phrases are original, and no reference audio is included. Inspection covered frames and measured envelopes, not a listening judgment.

The v6 core shortens the kick sweep and body, raises and tightens the main snare, shortens the bright alternate snare, and reduces open-cymbal tails. Ghost/pickup sources and kick/snare accent levels stay unchanged. A01–A10 remain dry SKIN model 1. The characteristic gesture is tight kick → cracked backbeat → grace strokes → chopped turnaround.

The page preserves v5 hits exactly and compares both cores on the same new phrase. Four separate performances use the full three-zone/three-take Jungle grid: Cutback (160 BPM), Ghostwork (170), Razor edits (175) and Orbital breaks (170, with colour). Independent zone counters cycle actual alternate strikes. The flam uses a 19 ms grace offset at 170 BPM; the drag uses two quiet strikes before the backbeat. Open/closed hats choke with a 5 ms fade. The Jungle MPC clip contains the Ghostwork event list.

Validation: 11 synth tests, including exact velocity boundaries, independent take cycling and phrase articulation; the three-kit save/playback/SFZ integration; all 262 WAV headers/hashes and 486 SFZ region windows; archive checks and DOM-mock controls passed. The first phrase test exposed a skipped open-hat accent in Cutback; the pattern was corrected and all checks rerun. All 16 previous-bank hits match published v5 exactly, every Dub/Electro exported WAV is unchanged, and generator source fingerprints match. The time containing 90% of raw-hit energy fell from 41.7→19.8 ms (kick), 39.9→17.1 ms (main snare), and 24.3→11.1 ms (alternate). These are envelope measurements, not perceived-quality claims.

The listening ZIP is 23,835,728 bytes. Browser audio decoding, owner listening, phone timing and MPC hardware remain unverified. Native/SFZ preserve complete grids; MPC's missing chain slice map and XPM first-take fallback keep the overall hardware/export gate open. The full JVM suite was not repeated for this preset/audition revision; focused tests and complete three-kit export checks were used.


## V7 — recorded acoustic core

The owner rejected the synthesized core as not live/organic. A01–A11 now use actual acoustic strikes from the CC0 Virtuosity Drums library (Versilian Studios; drummer Austin McMahon), pinned to `9f04cf9a734527edfbb0a4eee1f674e45bbf71bc`. Close kick/snare mics and overhead hats, ride and tom replace the corresponding synthesized sources. A12–A16 retain the psychedelic synthesis. No source EQ, compression, pitch shifting, added reverb or per-hit peak normalization is used. The original microphone ambience remains.

There are 93 distinct recorded files forming 99 zone/take entries. Main and heel kick share calibration, as do center and ghost snare. Kick and cymbals use dedicated recorded round robins; snare, cross-stick and tom use adjacent recorded dynamic hits as alternates. These are distinct recorded strikes, not synthesized copies or claims of identical-force RR. The first ghost selection measured too quiet in context; its groups were moved to recorded dynamics 7–9, 13–15 and 19–21, with the shared snare calibration retained.

The main resource bank is 31,596,216 bytes of PCM audio. A bounded 12-file LRU avoids loading the entire bank into float memory. Final JVM generation measured 3,229 ms for the complete Jungle grid, including roll/riser; this is not phone timing. `scripts/prepare_spore_acoustic.py --cache-dir <directory>` reproduces source selection and conversion with ffmpeg, numpy and scipy. It verifies upstream Git blobs, records original and converted SHA256s and carries CC0 notices. Source WAVs and provenance remain authoritative for recorded pads; asking for a synthetic recipe on those slots fails explicitly. Existing saved synthetic recipes still replay.

Validation passed: 12 synth tests, three-kit save/load/playback/SFZ integration, 262 WAV headers/hashes, all 486 SFZ region windows, ZIP contents, page controls with a DOM mock, all 93 resource hashes and source-code fingerprints. The complete v6 single-hit reference bank is byte-identical to the last publication. Dub/Electro WAVs are unchanged. The same four performance phrases isolate the source change. The Jungle roll now uses recorded snare strikes and records that source provenance.

The Jungle program exceeds the host's individual-file limit, so it is delivered as two ordinary ZIPs that must both be extracted into the same parent folder. Validation combines both archives and checks the complete native/SFZ/MPC program. Raw and matched listening packs are separate ZIPs; each includes acoustic credits, the license and provenance. No audio from the owner's reference videos is redistributed.

Owner listening, browser audio decoding, phone timing and MPC hardware remain unverified. Native/SFZ retain the full grids; XTD still lacks the chain-WAV slice map and XPM uses the first take per velocity zone. The overall export/hardware gate remains open. The full JVM suite was not repeated locally; the focused tests and complete three-kit export checks above passed.

## Sample-free kick/snare prototype — 12 November 2026

[Audition the synthesized prototype](https://spore-station-jungle-audition.bertcalm.chatgpt.site/synthesis/). Coupled heads and impact-driven snare wires replace the recorded source for these two experimental voices. Three velocity zones and three physical restrikes survive native saving, PadHit playback and SFZ export/import. The page compares acoustic references, the earlier synth and this prototype, with mechanism-off clips and a two-pad download. Listening acceptance is pending; this does not complete the genre-kit, roll/riser or app-engine gates. See [the mechanical design and evidence](superpowers/specs/2026-11-12-spore-membrane-prototype.md).

## Snare reference reset — 13 November 2026

The owner rejected the coupled-head prototype across attack, body and tail. A separate Python study now calibrates procedural oscillators and noise envelopes against the supplied live 170 BPM clip, with disjoint calibration/check hits. It does not replace the existing Kotlin engine. See [the review, design and measured limits](superpowers/specs/2026-11-13-spore-snare-reference-reset.md) and [reproduction instructions](../scripts/spore_snare_study/README.md). The prepared audition is intended for `/snare-reset/`; publication is pending because the Sites service returned `codex_auth` without a working local Git credential helper.

## Approved snare port and kick study — 14 November 2026

The owner approved the reference-calibrated Python snare. `SporeSnare` now reproduces that source in Kotlin, with 17 waveform parity cases and an importable one-pad native/SFZ/MPC 3 bank preserving all three velocities and three takes. The saved-source recipe and actual offline playback are checked, alongside SFZ replay and MPC 3 grid/PCM read-back. Android and physical MPC remain unverified. A separate reference-calibrated Python kick is awaiting listening acceptance. See [the port and study ledger](superpowers/specs/2026-11-14-spore-approved-port-and-kick.md). The new audition lives at [approved-core](https://spore-station-jungle-audition.bertcalm.chatgpt.site/approved-core/); it contains direct approved/Python/Kotlin comparisons, a dry combined phrase, and separate downloads.
