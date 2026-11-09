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
- Android factory UI registration is deferred until the sound-design audition is accepted.

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
