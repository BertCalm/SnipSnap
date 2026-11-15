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


## Genre family and acceptance gates (2026-11-10)

Baseline inspected: `a90049ce7`. The earlier velocity implementation landed in
PR #1008 with all CI checks passing. This follow-up adds three shelf arrangements:

| Shelf kit | BPM | Authored behavior |
|---|---:|---|
| Cosmic Breaks | 170 | Original Low Orbit DnB bank A, unchanged |
| Cosmic Jungle | 174 | Crack-snare substitution, higher tuning, tight decays and breakbeat chatter |
| Cosmic Dubstep | 140 | Sub-kick substitution, lowered tuning, darker snares and dotted echoes |

Each kit has the same 16 bank-A roles plus B01 Orbit Roll and B02 Relay Ascent.
These are finite one-bar gestures with release tails, not HOLD loops. All 18 pads
have three velocity zones and four distinct takes, including the gestures.
Individual hits remain finite. Melodic keygroups/chord tools and picked-string
STRUM do not apply to this drum-kit family.

The new arrangements reuse the frozen v2 sources, so there is no new embedded
sample bank. Genre-specific substitutions, pitch, envelope and echo decisions
change the musical behavior. Source grids are released as processed grids replace
them to avoid retaining two complete banks. Transposition uses the existing cubic
sampler read with an eighth-order 9 kHz prefilter. A 20 kHz probe at the maximum
+4-semitone shift is rejected by at least 60 dB relative to a 3 kHz probe. The
original arrangement bypasses this processing completely.

### Export and playback evidence

Run `./gradlew :synth:generateCosmicBreaksFamily`. The three full-grid ZIPs contain
native kit.json, SFZ and DecentSampler programs sharing one set of WAVs. Their
PCM16 delivery depth matches the source bank depth; numerical comparisons allow
one 16-bit quantization step. The application's ordinary export remains PCM24.
The 16-pad MPC compatibility generator remains available separately; it is not
presented as a full-grid export.

- 81 focused JVM tests passed, covering original-source preservation, distinct
  finite strikes, determinism, anti-alias rejection, shelf registration and
  conventions, plus the save/export/import/playback path for every genre.
- `CosmicBreaksRoundTripTest` checks every MIDI velocity 1–127 and nine successive
  hits per pad after native saving and SFZ export/import. PCM files remain exact
  through SFZ copying; selection windows, gains and pitch agree with PadHit.
- The family generator verifies all 648 delivered strike windows against rendered
  PCM and checks every DecentSampler velocity range, sequence position and group
  cycle. These checks validate files, not an external sampler application.
- The published player reads the actual ZIP contents and native grid metadata.
  Its decoder harness checks all 648 strikes and 2,916 boundary/cycle selections.
- Browser interaction, phone execution and external sampler/device playback are
  not established by these software checks. Full JVM/CI results are tracked
  separately; no pending run is counted as green.

### Gate ledger

| Gate | Evidence/status |
|---|---|
| Multiple genre drum kits | Three registered shelf arrangements; new sounds await owner listening |
| Appropriate percussion articulation | Authored roll and density/brightness riser on B01/B02; finite hits retained |
| Velocity/takes through rendering and saving | Three timbres × four takes, distinct PCM, native persistence checks |
| Velocity/takes through playback | PadHit selection/gain checks across all velocities, including after SFZ import |
| Full-grid export | SFZ round-trip verified; DecentSampler program fields verified |
| MPC export acceptance | **Parked by owner, 2026-11-14**: MPC 3 slice-map/hardware acceptance is unverified. MPC 2 combined velocity/RR is exempt as a format limit; velocity windows remain checked |

The repository already contains the owner's MPC reference corpus and shared
velocity/round-robin implementations. A new reference upload is not a prerequisite
for Cosmic integration. The existing PSK program establishes the layer anchors
and cycle descriptors; it does not establish playback of our exported slice
boundaries. Physical MPC playback remains a separate acceptance check. The owner parked
the MPC 3 capture and exempted MPC 2 combined velocity/RR on 2026-11-14; see
[the recorded decision](ENGINE_ACCEPTANCE_GATES.md#mpc-2-and-round-robin-owner-decision-2026-11-14).
There is no outstanding request for the owner to supply files for this work. A
limitation disclosure does not turn that gate green.

### Shared-export reconciliation (2026-11-14)

Reviewed against default-branch source `23cde0d1c`. The distinct shared paths are:

- `KitExporter` / `RobinSplit` / `XpmWriter`: unzoned pads with Robin settings
  become up to four separate take WAVs with `ZonePlay=0`. This is the cycling-layer
  implementation landed in `56e8c90c7`; it does not require a chain WAV slice map.
- Cosmic's pads have three explicit velocity zones, each with four takes. They
  intentionally do not satisfy `KitPad.isRobinChain`. Routing them through the
  unzoned exporter would lose the authored velocity dimension.
- `Mpc3Exporter` preserves their complete chain WAVs and emits three layer
  descriptors with base slices 0/4/8, increment 1, and cycle length 4.
  `Mpc3TrackWriter` still identifies the embedded slice-map work as HH1.4;
  this is a shared export limitation, not an absent Cosmic RR implementation.
- XPM exports preserve the full WAV but select the first take of each velocity
  zone. A complete audio file is not proof that all of its takes are selectable.
- Native, SFZ and DecentSampler preserve the complete grid using the existing
  shared model/writers. Their verification does not establish MPC playback.

`CosmicBreaksRoundTripTest` now reuses Triad's `verifyTrack` check on actual MPC 3
exports for all three Cosmic genres, compares all 54 exported WAVs per format
byte-for-byte with the saved originals, and checks all 162 XPM velocity/anchor
windows and non-cycling mode. The existing SFZ save/import/playback checks remain.
Validation: the Cosmic round-trip test and seven shared chain tests passed,
alongside the existing `KitExporterTest` and `XpmWriterTest` suites (43 focused
tests in total). A full JVM regression run was started for delivery; its result
is pending and is not counted as a pass here.
These are serialized-file checks, not a hardware playback claim; the common
`ExportReadBack` chain-zone SKIP is not counted as success.

The original integration PR #1029 merged on 2026-11-11. Its JVM aggregate,
Android build and native checks passed; its emulator job failed. Those historical
results do not claim physical MPC acceptance or an entirely green CI run.

### Why it is built this way

- The original bank bypass preserves the sound already approved.
- Genre arrangements change drum voicing and articulation as well as treatment.
- Bank-B gestures add performance tools without replacing the requested 16 hits.
- Shared WAVs keep native, SFZ and DecentSampler strikes identical within each package.
- Auditioning the packages themselves makes missing layers or takes testable at the delivery boundary.

The genre-kit audition source is Site commit
`f3bf9a8a751375f3954f00a3ad4379c22eb0c3fb`. Its control smoke test uses a
mock DOM/Web Audio and passed layer changes, take stepping, cycling off/on,
both new genre grooves and stop. This is not a browser/device playback claim.
The original macro sandbox remains available separately from the package player.
