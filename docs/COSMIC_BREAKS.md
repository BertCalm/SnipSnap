# Cosmic Breaks

A sixteen-pad, fixed sample starter preserving the owner-approved browser
audition's Low orbit setting. It is a kit, not a registered synth engine.
The characteristic gesture is stick attack → compressed body → drifting bloom
→ saturated decay.

Open FRESH TAPE → skins shelf → COSMIC BREAKS. The kit stores 170 BPM and the
same A01–A16 order as the listening page:

| Pads | Sounds |
|---|---|
| A01–A04 | Kick, Snare, Closed hat, Open hat |
| A05–A08 | Low tom, High tom, Rim, Metal |
| A09–A12 | Data tick, Packet roll, Laser chip, Relay rattle |
| A13–A16 | Ride bell, Ride cymbal, Sub kick, Crack snare |

Hats share the standard choke group; the rides remain independent percussion.
Roles are explicit musical assignments, not classifier predictions. All samples
are four-second finite one-shots with faded endings, not seamless HOLD loops.
Version 2 has three timbral layers (1–41, 42–84, 85–127), each with four deterministic takes. The sampler supplies velocity gain. The starter's
printed timbre has no synth macro editor or dry-source recipe.

## Reproduce and export

```
node reference/cosmic-breaks/freeze-v2.mjs
./gradlew --no-daemon :synth:generateCosmicBreaksKit
```

The generator writes `testkit/cosmic-breaks-kit/cosmic-breaks-kit.zip`, containing
an MPC 3 track, its MPC 2 XPM twin, samples, source manifest and instructions.
Every exported chain slice is compared with its frozen source. ExportReadBack
checks common fields but explicitly skips chain zones. Running
`python3 reference/cosmic-breaks/verify-export.py` separately checks all 48 serialized
layer anchors, cycle fields and MPC 2 windows. The PCM24 writer introduces at most its tested quantization tolerance;
the additional bits do not create additional source resolution.

## Integration and evidence

Repository baseline: `c00edb9970d2967b1716a0ece2cd7146bfbe0b14`.
The source assets come from published audition commit
`10857a750238566cfc3284a0bd788288203624ca`. The reference folder contains the
renderer, regeneration script and provenance explanation.

- Verified in source: `CosmicBreaksKits.build`, `SynthKits.cosmicBreaks`,
  the appended StarterKits entry and its single StarterShelf group.
- Numerical checks live in `CosmicBreaksKitsTest`; starter persistence, order,
  PCM parity and choke behaviour are in `CosmicBreaksStarterTest`.
- Heard: the owner said “Sounds cool, continue” about the 16-pad browser audition.
- Not established: physical MPC loading, Android execution, all parameter
  settings, or a parity-preserving native synth port.

The existing SKIN/ONSET sources cover related drum roles, and BACKWASH has a
related processed drum identity. Substituting any of them would change the
approved audio. The frozen bank is the lightest integration that preserves it.
No engine count or synth registry change is warranted.

## Why it is built this way

- Frozen samples keep the approved sound rather than approximating it again.
- Source and treatment are printed together, preventing accidental double processing.
- Checksums and original source make the accepted version identifiable.
- The remaining trade is per-pad versus shared-bus processing: an MPC sequence
  of printed hits will not duplicate every interaction of the browser groove.

## Original v1 validation (2026-11-09)

The targeted Kotlin run passed two asset/determinism tests, the starter
round-trip test, and three shelf-registration tests. The generator exported
both MPC formats and passed read-back for all sixteen pads, with WAV error
below 2e-7 against the frozen source. All six native C++ suites also passed.
The freeze script regenerated all sixteen WAVs and manifest byte-for-byte.
Full JVM regression is tracked separately; these targeted checks do not
establish Android execution or physical MPC loading.

The original integration landed in [PR #999](https://github.com/BertCalm/SnipSnap/pull/999).
Its full CI checks passed before this velocity expansion. V2 validation is
reported separately below.

[Published audition and kit download](https://cosmic-breaks-lab.bertcalm.chatgpt.site).

## Velocity and round robin (v2)

Every pad now supplies 3 × 4 distinct strikes, including all four glitches and
both rides. `ArrangedPad.velocityTakes` assembles soft-first chains through the
existing `ChainInfo.zones` model; persistence and `PadHit` use the same windows.
The browser adds a velocity slider, cycling toggle and eight-hit comparison.
Hard take 1 remains byte-identical to v1. Softer excitation changes bandwidth,
decay and drive into the effects; new seeds and pitch offsets within 2.4 cents
vary repeated hits. Printed samples are level-calibrated before the sampler
applies velocity gain, avoiding a double velocity curve.

The original v1 assets remain unchanged. V2 variations are requested listening
candidates, not additional listening approval. The bank adds approximately
68 MB of uncompressed PCM16 resources; assembled PCM24 chains use about 102 MB.

MPC 3 receives three zones with four-take cycle fields, but the existing exporter
does not supply a verified embedded WAV slice map. Physical cycling is therefore
unverified and may degrade to the first take per layer. MPC 2 explicitly plays
the first take in each layer. SnipSnap resolves all 12 windows itself. Chain
exports intentionally omit kit.json: the importer cannot reconstruct that
editable history. No claim of complete MPC round-robin compatibility is made.

V2 checks: all 192 assets are finite, DC-bounded and below the peak ceiling;
pairwise correlation rejects gain-only duplicates; all 16 reference hard takes
match v1 exactly. Assembler checks cover unequal slice lengths and persistence.
The generator compares all 192 exported PCM slices within 2e-7 and checks common
program fields; the independent Python inspector checks both formats' grid fields.

The v2 audition was published from Site source commit
`b977c791edc9289a92a93c96c96919b1f955d5e1`. Its 36-render harness passed,
and eight-hit trains at maximum Space, HOLD and Drive stayed below a 0.462 peak.
Browser interaction and physical hardware playback remain unverified.

Final local verification passed 99 targeted JVM tests after merging the current base,
including factory loading, all velocity boundaries and nine-hit cycle selection,
chain assembly/persistence, shelf registration and repository conventions. The
MPC generator and independent grid inspector passed again. A full local JVM run
was attempted without observed failures, then stopped during the long synth
regression; it is not counted as a full-suite pass. Complete regression runs in CI.
