# Alloy MATERIAL handoff

Updated 2026-11-07. Start here when continuing the Alloy work.

## Current decision and scope

The owner approved the **stronger body-linked response** for **Rounded Plug
(BASS), Long Prism (KEY), and Velvet Rise (SOFT)**: “New body is good.”

The next task is to make that response the actual MATERIAL behaviour in
SnipSnap, preserve old saved patches, then extend the approach to the other
15 authored roles. Publish a full-bank comparison, rebuild the MPC kits, and
finish phone performance and physical MPC acceptance.

**The body mapping is not implemented in the production Kotlin engine or patch
format yet.** The published page plays complete, independently rendered
snapshots. Importing one snapshot does not install the proposed response in the
current MATERIAL knob. This handoff commit preserves the approved experiment;
it does not claim to finish that integration.

## Listen and obtain the evidence

- [Approved stronger body comparison](https://snipsnap-alloy-factory-bank.bertcalm.chatgpt.site/material-body/).
  Choose a role and finite/held mode, then **Play body A/B**. The edited examples
  alternate New body (0%) and Original tone (50%) twice at matched body levels.
- [Released 24-preset bank and MPC downloads](https://snipsnap-alloy-factory-bank.bertcalm.chatgpt.site/factory/).
- [Rejected first MATERIAL study](https://snipsnap-alloy-factory-bank.bertcalm.chatgpt.site/material/).
  This excitation-only attempt was too subtle: the owner heard no useful change
  in Prism or Velvet Rise and barely heard it in Plug. Do not promote it.
- [Earlier role/voice/original history](https://snipsnap-alloy-audition.bertcalm.chatgpt.site/).

The stronger page's Downloads section contains full listening WAVs, native
PCM24 WAVs, snapshot JSON, verification reports, and the captured mapping source.
The Sites audience is owner-private; preserve it.

The portable continuation material is committed under
[experiments/alloy-material-body](../experiments/alloy-material-body/README.md):
the mapping, three original inputs, all 36 snapshot inputs, 12 native golden
WAVs, hashes and compact evidence. Follow that README for validation/replay
commands. Bulk measurement arrays, compiled classes and deployment archives
are not needed to recover the approved configuration.

The quick portable check is:

```sh
python3 experiments/alloy-material-body/validate_fixtures.py
```

## Repository and publication state

| Item | Verified value |
| --- | --- |
| Repository | `BertCalm/SnipSnap` |
| Current handoff branch | `claude/alloy-distinct-factory-bank` |
| Engine/source baseline used for the audition | `b04496ece7770903549c23ea913bad90bb57f45e` |
| Existing factory-bank integration | [PR #719](https://github.com/BertCalm/SnipSnap/pull/719), merged; JVM, Android and native CI passed |
| Default branch at handoff | `claude/mobile-mpc-drum-sampler-t58x74`; confirm from the remote before starting |
| Body-comparison Site version | **3**, deployment succeeded on 2026-11-06 |
| Site source commit, in the separate Site repository | `a06d0b6999c0d80aeea19811e0a2c090b5e77407` |
| Site project | `appgprj_6ae15a5255dc8191a079d09b6a9cb788` |
| Site deployment | `appgdep_6aee2bcb941c819193b4c49eec6dbdf2` |

The handoff branch is based on the audition's pinned October source, **not a
claim that it contains today's default branch**. Before production work, fetch
the current default and bring this handoff commit into the implementation branch.
For example, from a clean checkout:

```sh
git fetch origin
git switch -c claude/alloy-material-control origin/claude/mobile-mpc-drum-sampler-t58x74
git cherry-pick <handoff-commit>
```

Read [AGENTS.md](../AGENTS.md), [Luthier](../.claude/skills/luthier/SKILL.md),
its conventions/rubric, and [Steward](../.claude/skills/steward/SKILL.md).
Inspect current APIs again after updating the base. Merge an advancing base
into an open PR branch; do not rebase/force-push an open PR, approve, or merge it.

## Preserve these owner choices

- Original BASS and SOFT voice foundations; revised KEY, PLUCK, BRASS and STRING.
- The 24-role factory bank, four presets per voice: six first-preset references
  and 18 authored roles. Only three authored roles have an approved new body
  endpoint so far. The other 15 still need authoring and a full-bank listen.
- The complete original sound at **MATERIAL 50%** for the three accepted trials.
  Their old saved MATERIAL positions were .05, .5 and .2 respectively; the new
  mapped presets must explicitly load at .5. Old saves retain their old values.
- The existing upper half, including air/breath, at and above .5.
- All old complete saves, model-1 saves with missing/null config, the six held
  references, and the existing starter's Held Filament/Endless Ember streams.
- The released bank and previous audition assets remain available. Do not
  replace historical renders with the new candidate renders.

## Exact response to implement

The portable `model.py` and committed snapshot fixtures are the authoritative
authoring reference. The following is a description, not an existing Kotlin API.

1. Keep the original complete configuration as an immutable **anchor**.
2. For `MATERIAL >= .5`, return that original configuration exactly. Do not
   reconstruct it by interpolation: route order, absent edges and floating-point
   values must stay unchanged. The engine's existing source weighting supplies
   the original air/breath behaviour above .5.
3. Below .5, let the original weight be `t = (2 * MATERIAL)^power`.
   `power = 2` for Rounded Plug, `1` for Long Prism and Velvet Rise.
4. Blend each selected numeric parameter as `left + (anchor - left) * t`.
5. Blend operator **gain, decay and sustain**, wood **losses and gains**, route
   depths and `cutoffRatio`. For routes, use the sorted union of `(from, to)`
   pairs; an absent route has depth zero. At .5 return the exact anchor route
   list rather than that union.
6. Keep operator ratios, material-source ratios, tine/breath projections, the
   outer attack/duration/release envelope, held period/drive/roughness, note,
   velocity, and other macro settings unchanged.

The endpoint definitions are retained in the package. In short:

| Role | Left endpoint intent | Lower-half curve |
| --- | --- | --- |
| Rounded Plug | Woody harmonic bass instead of the nearly pure rounded body; stronger sustained second/third operators and more bandwidth | Original weight `(2M)^2` |
| Long Prism | Fundamental-led wooden key with a hollow sustained third instead of the glassy tine body | Original weight `2M` |
| Velvet Rise | Hollow/reedier sustained body, retaining the original slow swell | Original weight `2M` |

Do not substitute a wood-projection-only change. In HOLD, `Source.tick` ignores
projection losses and uses sustained gains; shortening those losses cannot
repair a held dead zone. Even changed source ratios/gains were masked by these
presets' existing carrier balance. The accepted revision changes the sustained
body's operators and routing as well.

### Arithmetic and ownership pitfalls

- The reference authoring calculation uses Python double precision, followed
  by the existing JSON decoder's Float conversion. A naive Kotlin Float lerp,
  or pre-rounding endpoint numbers before blending, can produce different
  intermediate samples. Compare all 36 snapshot values and native fingerprints;
  do not update goldens merely to make a different port pass.
  For example, Long Prism operator 0 gain at MATERIAL .25 is Float32
  `0.4000000059604645` under the accepted calculation; pre-rounding its .65
  endpoint and lerping in Float gives `0.3999999761581421` instead.
- Preserve the exact .5 branch. Inactive wood still contributes to the held
  convergence state, so removing its audible weight alone is not a loop proof.
- Store the anchor and optional response once. Never use an already-resolved
  intermediate configuration as the next edit's anchor; that creates cumulative
  morph drift. Resolve from immutable saved state on every macro edit/render.
- Resolve configuration once per render. This proposal adds no per-sample
  solver and should not duplicate the oscillator/output pipeline.

## Production integration checklist

Suggested first landing: an **optional, versioned response descriptor** carried
by mapped patches, with absent/null preserving existing behaviour. This is a
proposal to implement; no such production field/class exists in this commit.
Do not retrofit it by preset name or change every legacy Alloy patch globally.

These entry points were verified on the pinned source:

| Surface | Required continuation work |
| --- | --- |
| `synth/.../AlloyPatch.kt` | Central `render()`, `withMacros()`, `toJsonValue()` and `fromJsonValue()` path. Retain any new descriptor through edits and serialization; use one effective-configuration resolver for rendering. |
| `synth/.../AlloyConfig.kt` | Existing complete configuration and bounds. Prefer reusing it; do not change old configurations' meaning to encode the new response implicitly. |
| `synth/.../Alloy.kt` | Existing render/probe/loop kernel and original `configFor` versus selected `defaultConfigFor`. Keep old arithmetic, defaults, phase/alias limits and interruption checks unless an identified change is necessary. |
| `synth/.../AlloyPresets.kt` / `AlloyFactoryRoles.kt` | Opt the three approved roles into the new response with explicit MATERIAL .5. Keep first-preset references and `heldReferences()` stable. Add the remaining 15 only as authored candidates. |
| `synth/.../Patches.kt` | `Patches.edited()` calls `withMacros()` then serializes/decodes; test that mapping state survives this complete editing path. |
| `synth/.../PadRecipe.kt` | `render()` and `fromJsonValue()` delegate through the patch path. Test native recipe save/replay, fingerprints, and regenerated samples with the descriptor present. |
| `synth/.../Velocity.kt` | `touchedRender` uses `AlloyPatch.copy(velocity=...)`. Preserve the response and anchor across velocity changes; do not accidentally replace them with voice defaults. |
| `cli/.../SynthCommand.kt` | ALLOY supports saved `--patch` and note/velocity overrides via copy. Preserve response state. ALLOY is **not** currently in the `--macros` allowlist; do not assume that command exists. |
| `shell/.../UserPresets.kt` | `rosterLine()` hand-builds Alloy promotion code containing only midi/velocity/model/config today. A new patch-level descriptor would be silently dropped unless this path is updated. Extend `shell/.../AlloyIntegrationTest.kt`. |
| `app/.../ui/SynthScreen.kt` | Loaded presets pass through `buildCurrentPatch()` / `Patches.edited()` and preview via `patch.render()`. Keep these paths intact, including edit cancellation. Unloaded new-voice defaults must retain existing semantics. |
| `synth/src/test/.../AlloyAuditionGenerator.kt` | Currently reconstructs patches from `patch?.config` and calls `renderProbe(config=recipe.config)`. Update it to retain/resolve response state; otherwise audio, metadata and downloaded recipes can disagree. |
| `SynthKits.kt`, kit/audition generators, docs | Rebuild starter and three eight-pad programs from the same complete mapped recipes; verify MIDI roots, classes, held-loop references and native JSON replay. |

Do not blindly increment global `Alloy.MODEL_VERSION`: current init/decode accept
only the exact global model value, and the model also participates in seeding.
That would reject old model-1 saves and risk changing their audio. Whatever
version strategy is chosen must explicitly preserve those saves.

Missing/null `config` must still decode to **`Alloy.configFor(voice)`**, not
`defaultConfigFor(voice)` or a factory preset lookup. Missing/null response
metadata must mean the old response. New mapped factories should explicitly
carry their intended configuration, descriptor and MATERIAL .5.

## Verification already completed

The committed compact reports are evidence of the prototype, not proof that a
future Kotlin control implementation passes. They predate the owner's approval;
their historical `approval=false` / `installedInEngine=false` fields are retained.

| Check | Recorded result |
| --- | --- |
| Render input ledger | 264 unique inputs; two initial Java workers exited 0; 14 bass intermediates rerendered after the curve refinement, exit 0 |
| Renderer hard checks | 3,027 passed; no hard failures |
| Independent artifacts | 5,290 checks passed |
| Independent edited A/B reconstruction | 567 checks passed; all declared segments reconstruct exactly; level imbalance at most .0072 dB |
| Independent fresh JSON replays | 18 inputs; 45 whole-WAV byte-exact comparisons, including changed endpoints and genuine held predecessors |
| Midpoint preservation | All six finite/held listening PCM16 and public PCM24 references exact; three finite originals also match the published MPC samples |
| Upper half | All 18 comparisons at .5/.75/1 exact |
| Encoded held cuts | All 18 seam errors zero under the existing Keys metric |
| Browser | 1,222 checks, actual exit 0; all 36 snapshots and six edited comparisons actually played; decoded sample-code identity, cancellation, one-source limit, cue alignment and rate fallbacks checked |
| Preservation | 6,196 checks; all old assets retained except two HTML navigation edits |

For this handoff packaging, the portable validator regenerated all 36 snapshot
JSON files byte-for-byte and verified the 12 committed native goldens. A fresh
Java-17 replay through the existing public renderer reproduced all 12 golden
WAV files byte-for-byte. The unfiltered nine-module Gradle test graph also
exited 0 in about 20 seconds: all 42 tasks were up-to-date because production
sources were unchanged. That is a successful cached gate, not a claim of 6,982
fresh test executions in this handoff turn.

Maximum measured root error was 4.514 cents, raw-state peak 1.024, mean magnitude
1.79e-5 and held-state error 4.75e-11. MIDI-84 4x/8x comparisons retained the
existing .12 waveform / .035 spectral / .02 high-band limits. The stronger
endpoint screens (.08 spectral variation and .15 matched waveform distance)
measured the isolated sustained body beyond the attack. **The owner's Nov-7
listening approval, not those thresholds, selects this direction.**

### Test details that previously caused confusion

- Use the existing `Keys.seamError` definition: normalized difference **power**
  over the final 256 samples of the real predecessor and kept cycle. Comparing
  the entire discarded predecessor includes decimator startup. Do not loosen
  the existing 1e-3 limit, copy a cycle, or crossfade a failing native seam.
- Edited A/B files have declared crops, gain, 4 ms boundary fades and silent
  gaps. They are comparison aids, not native patches or seamless sampler loops.
  Use full native/held files for engine and export assertions.
- Chromium's PCM16 float normalization was asymmetric in the tested build.
  Browser identity was checked by losslessly recovering the original PCM16
  sample codes, rather than assuming uniform division by 32768.
- The browser requests 44.1 kHz; real 48 kHz unsupported/ignored-options
  fallbacks were tested and disclosed. Physical device output bit identity and
  private hosted authentication were not browser-tested.
- Cloud render timings are not phone timings or proof of the tens-of-ms
  playability target. Physical phone profiling and MPC playback remain open.

## Acceptance for the next implementation

1. New optional mapping renders the accepted three roles at 0/.25/.5/.75/1
   through the ordinary patch/edit/preview/export path, matching the snapshots
   and golden audio. Repeated edits always start from the saved anchor.
2. Complete old saves, missing/null-config saves, unmapped patches, held
   references and original/selected voice defaults keep their prior audio.
3. Save/load, promotion, native PadRecipe replay, MIDI/velocity overrides and
   factory reconstruction retain the same mapping state. Test invalid or
   unsupported descriptor data explicitly.
4. Finite and held root, class, headroom, DC, aliasing, actual predecessor seam,
   deterministic replay, macro interactions and cancellation guards pass.
   Do not use LOOP duration to hide a drum classifier result.
5. Author and audition the remaining 15 roles. Preserve each chosen sound at
   .5 and the six baseline presets. Do not mark these 15 as already approved.
6. Rebuild kits and publish the complete comparison. Check phone responsiveness
   and physical MPC import/playback, including whole-sample looping where needed.

Retain the existing explicit classifier limits documented in [ALLOY.md](ALLOY.md):
Picked Fibre at C2 can receive a generic TOM label despite its stable root and
TONAL export; the earlier all-low PLUCK C2 diagnostic also remains. Do not
silently generalize those exceptions to new body endpoints or remove the old
frozen-input guards.

Before every source push, follow Steward's full JVM gate. Use Java 17, as CI does.
Without an Android SDK, run `./gradlew --no-daemon test`; do not add
`-x :app:test` when `:app` is absent. Native CMake tests are required for changes
under `app/src/main/cpp`. Android app compilation/device tests need their own
CI or SDK/device path; pure JVM tests do not prove them.

## Sites continuation

The existing Site contains the released bank and both MATERIAL studies. Its
source repository is separate from SnipSnap. Current checkout in the original
workspace: `/workspace/sites/alloy-factory-bank`; it is not a required path for
using this committed handoff package.

Reuse the exact project ID above, preserve audience, and use the Sites source
workflow before publishing. Source credentials expire quickly; keep them in
memory/hidden stdin, never commit them. Do not create another Site accidentally.
The host limit is **256 MiB expanded**, not compressed; this version contains
about 174.5 MB of assets. A full 18-role matrix may need curation or another
companion, while historical media remain intact. Verify source/archives before
deployment and wait for the existing deployment's terminal result.

## Suggested first instruction to the next agent

> Read this handoff and the committed experiment README. Treat the three
> stronger body directions as owner-approved. Implement an opt-in MATERIAL body
> response for those three through the real Alloy patch/edit/save/export path,
> preserving exact midpoint audio and every legacy save. Use the portable model,
> snapshots and native goldens as references, including Rounded Plug's square
> curve. Keep the original/selected voice foundations and held references. Then
> extend to the other 15 authored roles, publish a full-bank comparison, rebuild
> the MPC kits, and report separate JVM, Android, phone and MPC acceptance.
