# HALOBAR final keygroup export

The production keygroup exporter now retains both touch levels and all three
native strikes per level. Previously it captured all six takes for phone
playback but wrote each velocity window's first take to MPC. HALOBAR now opts
into the matching-window Cycle layout from the owner's passed diagnostic.
The approved sound, model 1, recipes and all production DSP remain unchanged.

The owner accepted all six voice banks, the kit, chords, finite HOLD/releases,
rolls and risers and reported passing the phone/MPC checks on 2026-11-14.
This delivery completes the production export integration. The quantitative
50 ms fresh-render goal remains unmet; it is not certified by that sign-off.

## Export and persistence

| Target | Retained layout |
|---|---|
| Native MPC XTY | Six active layers, Cycle 0; hard takes 1/2/3, then soft takes 1/2/3. |
| Legacy XPM | Six active layers, ZonePlay 0; soft takes 1/2/3, then hard takes 1/2/3. |
| Phone sidecar | Version 5 retains the explicit MPC policy through save/load and voice-mode descriptor rewrites. Phone take selection is unchanged. |
| Existing HALOBAR banks | Descriptor-only upgrade retains the captured PCM, roots, release, voice mode and recipes; no synthesis is repeated. |

Native window groups are reversed to match the tested hard-first layout;
strike order inside each window is retained. Other instruments keep their
previous export behavior unless explicitly opted in. Version-1 through
version-4 sidecars keep their serialization and legacy policy. The new
version-5 field is required and refuses an invalid non-cycling bank. Java
fixture constructors remain available.

The supplied six-bank ZIP contains 71 roots and 426 captures. Every WAV byte
and each of the six recipes matches the preceding approved delivery. Only
18 descriptors changed: each bank's XTY, XPM and phone sidecar. Actual native
and XML read-back checks every active sample, window, root and Cycle setting.
Alternating soft/hard selection is checked against independent window cursors.

Install the updated development APK before importing these banks: older app
versions do not read version-5 phone sidecars. On MPC, keep each XTY beside its
TrackData folder, or load the XPM inside that folder. The existing study-kit
website downloads remain pad kits; the complete updated keygroups accompany
the build in chat.

For another complete saved HALOBAR bank, `HalobarPadMaker.upgradeCycleExport`
rewrites descriptors after checking its recipe, root set and two three-take
windows. Repeating the operation is idempotent. The shared atomic descriptor
writer preserves the captured files. New MAKE/CLI exports use the full layout
automatically.

## Validation

Frozen production and Gradle test source: `96df0db50c54c04da03826a54a674b8563a41231`,
tree `d41d75fab5aa022b69ccc6b11530e667b52eb267`. Validation completed 2026-11-15 UTC.

- Complete local nine-module JVM gate: 10,112 tests, zero failures,
  errors or skips. All 679 synthesis test classes are covered exactly once across
  the three official class-hash shards.
- All 408 published Float32 fixtures and 72 additional take cases replay
  exactly. Dsp, Tide, HALOBAR, FFT and resampler sources match the accepted
  baseline byte for byte.
- The six-voice package test exercises real captured samples, persisted
  selection, both exported formats, voice-mode rewrites and old-bank upgrades.
  The actual supplied 426 WAVs and six recipes remain byte-identical.
- Three independent compiled mutations fail named assertions: omitting the
  HALOBAR opt-in, dropping its persisted policy and reversing native strike
  groups incorrectly. Retained production variants pass. No production file
  was mutated by these checks.
- App and instrumentation APK assembly passed. Matching v2 signatures, package
  targets and packaged code were checked. The discarded filter helper is
  absent. Instrumentation was compiled, not executed here; hardware acceptance
  is the owner's report, without a supplied model/firmware log.
- The private audition page records acceptance. All its audio/download hashes
  remain unchanged. Static links and JavaScript checks passed; browser QA was
  unavailable in the managed environment.

The [validation receipt](../reference/halobar/finish/validation.json) and
[full gate summary](../reference/halobar/finish/full-results.json) preserve the
results. Full JVM XML reports accompany the build's evidence ZIP. Later
source changes for this delivery add documentation/reference evidence only;
no code or test change is inferred from those later commit IDs.

## Disclosed latency limit

A new paired-filter implementation passed exact audio checks but was up to
6% slower in the isolated ABBA comparison, so it was discarded. The original
DSP is retained. That comparison's original-DSP fresh medians were about
139–325 ms on one desktop CPU, above 50 ms. These are not phone or audio-output
latency measurements. There is no new speed-improvement or timing-waiver claim.

See the [rejected trial](HALOBAR_FILTER_TRIAL.md) and the earlier
[control-edit measurements](HALOBAR_PLAYABILITY.md). The owner accepted
playability; further numerical latency work remains separate from the
completed export implementation and listening sign-off.

## Why it is built this way

- A per-instrument opt-in keeps unrelated instruments' export policies stable.
- Persisting that choice prevents a saved-bank or voice-mode edit from silently
  dropping later strikes again.
- Rewriting descriptors preserves the owner's exact captured sound.
- Rejecting the slower DSP trial keeps both sound and existing performance;
  the unmet timing target remains visible.

[Accepted performance page](https://halobar-native-audition.bertcalm.chatgpt.site/tubular-bells/performance/review/) ·
[Owner acceptance](HALOBAR_ACCEPTANCE.md).
