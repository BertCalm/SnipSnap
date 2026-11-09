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
There is one velocity layer; the sampler supplies velocity gain. The starter's
printed timbre has no synth macro editor or dry-source recipe.

## Reproduce and export

```
node reference/cosmic-breaks/freeze.mjs
./gradlew --no-daemon :synth:generateCosmicBreaksKit
```

The generator writes `testkit/cosmic-breaks-kit/cosmic-breaks-kit.zip`, containing
an MPC 3 track, its MPC 2 XPM twin, samples, source manifest and instructions.
Every exported WAV is compared with the frozen source, and both program formats
run through ExportReadBack. The PCM24 writer introduces at most its tested quantization tolerance;
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

## As built validation (2026-11-09)

The targeted Kotlin run passed two asset/determinism tests, the starter
round-trip test, and three shelf-registration tests. The generator exported
both MPC formats and passed read-back for all sixteen pads, with WAV error
below 2e-7 against the frozen source. All six native C++ suites also passed.
The freeze script regenerated all sixteen WAVs and manifest byte-for-byte.
Full JVM regression is tracked separately; these targeted checks do not
establish Android execution or physical MPC loading.

The final targeted run passed 77 tests (70 repository convention checks,
three starter-shelf checks, the stable starter-ID check, the new starter
round-trip check, and two frozen-bank checks). The full JVM run was attempted,
found the now-corrected 79→80 starter-count drift in both READMEs, and was
interrupted before completion to rerun the affected checks. It is **not** a
full-suite pass; full CI remains required before merging the draft change.

[Published audition and kit download](https://cosmic-breaks-lab.bertcalm.chatgpt.site).
