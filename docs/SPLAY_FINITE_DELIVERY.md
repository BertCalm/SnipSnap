# SPLAY finite picker and starter delivery

This additive delivery follows source `0dc5825` and its independently sealed full 537-source synth compile, four pure registration checks and five accepted population PCM replays. It does not alter the original DSP, trajectory bank, SplayPatch, historical SplayPresets, factory/native input identities, CLI positions or any of the 16 accepted resource files.

The SYNTH picker exposes one `TONGUE` voice and its existing five factory settings: 1, 2, 4, 8 and 16 mechanisms. These are population settings, not five voices. Saved model 1 and model 2 recipes keep their own missing-macro defaults and complete root/velocity/signed seed/model when edited, scrambled, recalled, saved or carried through navigation. ROOT uses the existing SynthRoot implementation. HOLD remains 0 and is hidden as an unavailable control. The population slider retains its original linear meaning; no saved control law is reinterpreted.

FRESH TAPE offers `SPLAY POPULATIONS`: a fixed five-pad starter occupying A01–A05, all at the accepted C4 root and native touch. Remaining slots are empty rather than filled with invented performances. Every independent finite one-shot carries its complete PadRecipe and authored-root/population provenance. It has no loop, velocity-layer bank or RR chain and does not masquerade as a keygroup instrument.

## Native level and accepted recordings

The original listening room used `renderDetailed.raw × 2.56811770069021` and saved matched PCM16 recordings. Native SplayPatch.render has always returned the renderer's separate native-level Snip. This delivery uses that existing native Patch.render/PadRecipe contract in both picker and starter so saved recipe replay reconstructs the actual starter WAV; it does not place matched-room audio behind a native recipe that would regenerate at a different level. No accepted recording is replaced or releveled. The original matched/raw canaries remain unchanged in the preceding gate.

## Verification scope

On 2026-11-13 this delivery came back from the recovery backup. Its JVM checks ran
on the port branch: the SPLAY synth, shell and CLI suites, the shared registry
tests and the convention checks. Results are recorded in the SPLAY section of
[the acceptance gates](ENGINE_ACCEPTANCE_GATES.md). The Compose/state wiring in
SynthScreen, `SplayFiniteHeldSpec` and the two Android tests have not compiled or
run here: this environment has no Android SDK. CI's emulator runs them. Broader
six-voice, HOLD/articulation, RR and timbral-velocity work is still open.

The MAKE INSTRUMENT sheet gained two defaulted `HeldSpec` hooks for this finite
instrument: `hasRelease` (false hides the RELEASE slider) and `shelfNote`.
Every other instrument keeps its existing sheet.

## Integration seam

Source `0dc` already contains SplayPatch's authored-root arm in Spread.kt and its SynthRoot mapping, so this delivery reuses both without another production change. When later combining the independent shared-grid/SPREAD candidate `40bcc` with this source family, retain SPLAY's existing arm alongside that candidate's MURMUR arm and saved-pad overload. No shared-grid running checkout is edited by this work. The backup's `AuthoredGridSpread` check that a dry native SPLAY grid cell keeps its saved root belongs to that shared-grid work and is not on this branch.

## Named-only saved preset restoration

The follow-up to source `6a1ffe37c` closes review finding `SPLAY-RESTORE-001`.
A YOURS name without a full draft or reopened pad now keeps controls, ROOT,
SCRAMBLE, SAVE, preview, SEND and SPREAD protected until the shelf read resolves.
The complete saved patch and its model-specific controls enter the same Compose
snapshot. A failed read keeps the gate closed and permits an explicit factory
choice; a late read cannot replace a newer choice.

Three `SplaySynthScreenTest` Android tests exercise the real delayed shelf-reader
seam, queued accessibility callbacks, stale macro settings, model-1 defaults,
signed seed/root/touch preservation, edited navigation, failed reads and a
factory choice made while the read is pending. These tests are authored only.
They require normal app/instrumentation compilation and an Android device or
emulator; screen preview may render SPLAY, so they are not pure metadata tests.
They are excluded from the bounded shell-only proof plan.
