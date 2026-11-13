# ALLOY approved MATERIAL response

The implementation adds an optional version-1 `materialResponse` to a complete
`AlloyPatch`. `config` is always the original anchor. `effectiveConfig()` resolves
MATERIAL once before rendering; it never replaces the stored anchor. Missing or
null response metadata keeps the old behavior and old JSON output. The engine
kernel, model number, seed, selected defaults and existing factory bank are unchanged.

`AlloyMaterialPresets.all()` provides only Rounded Plug, Long Prism and Velvet
Rise as explicitly opted-in candidates, at MATERIAL .5. It is separate from
`AlloyPresets.all()` while integration is verified. The picker and CLI offer them
under their own labels, Rounded Body, Prism Body and Velvet Body
(`AlloyMaterialPresets.pickerAdditions()`), after each voice's four factory presets,
so no factory name or saved recipe changes. The three new endpoints have
owner approval (2026-11-07, recorded as "New body is good"); the other fifteen authored roles have not been changed. This
package does not promote a new full bank or claim phone/MPC acceptance.

## Exact authoring reference

Portable evidence was recovered from `claude/alloy-distinct-factory-bank` at
`d9ad2a859b40e387f94383d2de933ddf07de1294`, path
`experiments/alloy-material-body`. Its accepted engine baseline is
`b04496ece7770903549c23ea913bad90bb57f45e`. The implementation starts from
`fb5f315cc76adb5372f20f0c6f0e2120f9791382`. Alloy.kt and AlloyConfig.kt are identical
between that base and the accepted engine baseline. The portable validator checks
36 exact snapshot JSON files and twelve native PCM24 golden files.

In this repository the snapshot JSON, the three baselines, the authored endpoints
and the fixture index (with each native render's SHA-256) live in
`synth/src/test/resources/alloy-material-body/`. `AlloyMaterialResponseTest` replays
all 36 snapshots and checks their PCM24 fingerprints. The golden WAVs, captured
scripts, reports and browser study stayed in the recovery backup.

The response stores authored endpoints as Double. Below .5 it interpolates those
Double decimals against the anchor's Float values promoted to Double, then narrows
once into AlloyConfig. Rounded Plug uses a square weight; the other two use a
linear weight. The route union is sorted by source and destination, with missing
edges represented by zero. At .5 and above the exact anchor object is returned,
including its original route order and absent edges.

## Integration surfaces

- `AlloyPatch.render` resolves the descriptor. Editing, JSON decoding, native
  PadRecipe replay and velocity/root copies retain it.
- User preset promotion emits complete anchor and response JSON, retaining the
  endpoint Double values. A user save still needs a distinct user preset name.
- `AlloyAuditionGenerator` retains the descriptor when it constructs a diagnostic
  recipe and passes its resolved config to the probe. Metadata includes both
  anchor and effective configuration, plus the complete replay patch.
- Source inspection of `SynthScreen.buildCurrentPatch` shows loaded ALLOY patches
  follow `Patches.edited`; selected preview uses that patch's render. Complete
  draft/save paths serialize it. Fresh defaults intentionally stay unmapped.
  This is source inspection, not Android UI execution.
- CLI patch overrides use copy, preserving the new field. ALLOY still has no
  generic `--macros` support. No new command is claimed.

## Why it is built this way

1. The body-linked operator/routing changes carry the accepted contrast through
   HOLD, where projection decay changes alone had little effect.
2. Fixed pitch ratios preserve the existing note and phase behavior.
3. Double endpoint arithmetic reproduces the approved intermediate snapshots;
   pre-rounding endpoints to Float does not.
4. The original config is stored once, so repeated macro edits cannot accumulate
   morph drift and the upper half remains exact.
5. A separate opt-in bank makes the new behavior available for verification
   without silently rewriting historical factory identities or complete saves.

## Verification and remaining delivery

The ALLOY, preset, user-preset and CLI suites run in the Gradle build. Full integration, Android checks, the
remaining fifteen roles, rebuilt candidate kits, phone profiling and physical
MPC playback remain separate work. No source push or publication is performed.
