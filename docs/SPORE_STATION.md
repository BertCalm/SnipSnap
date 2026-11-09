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
