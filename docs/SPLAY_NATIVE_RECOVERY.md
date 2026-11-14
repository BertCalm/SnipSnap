# SPLAY native recovery, models 1 and 2

This section records the original 2026-11-09 recovery packet. The later
[accepted population registration](SPLAY_PRODUCTION_INTAKE.md) reconciles it
onto green source 591d, files only the five KEEP populations in `Presets`, and
retains the historical provisional bank separately. Its validation remains
pending; the earlier recovery claims below are not a fresh gate result.

As built 2026-11-09: source-only candidate on exact base
`6d2e954dda540cc763a418a6785fdf404fb6a4c2`. Compilation, tests, numeric
replay, phone and MPC behavior are not yet verified. The sole heavy validation
slot belongs to the ongoing integration gate; this recovery does not run a
compiler or renderer while that gate owns the budget.

The gesture remains common release → coordinated catches → separate catches →
bounded decay. TONGUE is one pitched finite instrument. Its 1/2/4/8/16 internal
mechanism settings are not five separate voices.

## Recovered identity and preserved behavior

The source comes from the published working-tree ZIP, SHA-256
`d09c5018ca50debd98f37f5842e545a72be7db69512034911e5cced5e06dca11`,
over old base `9801adecfec0269c2935a148e437156125f525f4`. Splay,
SplayTrajectoryBank, SplayPatch and SplayPresets, four original test files and
SplayDiagnosticReplay are copied unchanged. Entry SHA-256 and Git blob identities
are recorded in `synth/src/test/resources/splay-accepted/manifest.json`.

This archive is not the later `splay-handoff.bundle`; implementation
`a4491eb1c742c3549d7f2fc433ccfd86ce036fea` is still unavailable. The new candidate
has its own history and validation obligation. Historical suite/timing evidence
does not transfer to it.

Models 1/2 retain their own defaults, exact signed Int acoustic seed, saved
MIDI/velocity/macros and eight-second finite gesture. Positive HOLD remains
refused. POPULATION still means `N = 1 + 99p`, with a fractional final member.
`Dsp.seedFor("SPLAY", model, seed, "shared-contact")` is unchanged. A geometric
count dial or versioned population interpretation is P1, after replay; this
packet does not advance the acoustic model or change its mathematics.

## Current integration

The existing current registries receive contextual additions: Patches decoding,
model-aware Velocity macro specifications/native rendering, SynthRoot's MIDI
field, authored-root Spread detection, full-JSON UserPresets constructor output,
and CLI discovery/input/output. CLI supports complete native `--patch` replay,
MIDI, velocity, signed Int seed, explicit acoustic model 1/2 and strict macro
edits; sidecars preserve the complete source. Old partial macro maps still use
their saved model's defaults. A diagnostic wrapper with `patch` and `render`
objects is a different format from a native `--patch` file.

SPLAY stays in the prototype exemption and out of Presets, the phone catalog,
Android picker and starter shelf. Five recovered provisional presets remain
unchanged. `SplayAcceptedBank` separately identifies the five kept Wave 3
population performances without promoting them into factory recipes.

## Five exact accepted anchors

Wave 3 manifest SHA-256:
`0b4efb577a80c616e1752ef802b39fb3cafa9678c4606af8c7e670d06a060312`.
All five decisions are KEEP; none is inferred as the preferred count.

| Bank ID | Members | Saved native POPULATION |
| --- | --- | --- |
| pc-1 | 1 | 0 |
| pc-2 | 2 | 1/99 |
| pc-4 | 4 | 3/99 |
| pc-8 | 8 | 7/99 |
| pc-16 | 16 | 15/99 |

Every entry retains TONGUE/model 2/MIDI 60/velocity .8/seed 41001 and the
published TRACE/SEED GAP/ENERGY/CONTACT/HOLD. Resources contain byte-identical
original diagnostic recipes plus directly usable extracted native `.patch.json`
files. The original PCM16 WAVs are not in this public repository; their SHA-256
values stay pinned in `SplayAcceptedBank` and the manifest. Hashes, source
descriptor and verdict binding are immutable inputs to the new tests.

The room uses `Splay.renderDetailed(...).raw` times its fixed matched gain
`2.56811770069021`, then the original PCM16 writer. Native `SplayPatch.render()`
uses its normal native loudness stage; comparing that output directly with the
matched room WAV would compare different output paths. New tests preserve
that distinction and compare original raw-PCM hashes as well as matched WAVs.
No WAV was regenerated for this packet.

## Validation plan and limits

Source-only checks verify the copied files and immutable assets, inspect the
actual APIs, confirm no shared Dsp/Tide/build overwrite, and review the current
diff. Commons Math 3.6.1 already exists. Archived/current Tide is byte-identical;
Dsp differences are additive helpers; the PCM writer is unchanged from the old
base. This supports compatibility, but does not prove compilation or replay.

After the integration owner grants budget, compile current synth/shell/CLI
main and test sources. Run the SPLAY core, patch, trajectory, revision and bank
contracts; the SPLAY determinism case; shell native/root/save contracts; CLI
input/replay contracts; and shared registry tests. The archived model-1 raw-PCM
anchor and all five Wave 3 model-2 raw/matched PCM anchors must match first.
Then run broader required gates once, under the integration owner's schedule.

Original raw Float fixtures were not present in the recovered archive. They
cannot be reconstructed from quantized PCM. Before P1, use the explicit
`SplayRecoveryFloatProbe` in two separate, source-pinned JVM executions:

1. Reconstruct the archived source on its old base in isolation, retaining exact
   recovered core bytes; add only the same probe and accepted-bank inputs.
2. Capture model-1 First Seams plus the five accepted model-2 raw/native Float
   arrays as little-endian f32 and their raw PCM. Preserve both output manifests.
3. Run the same probe on this candidate and compare the entire Float buffers,
   source recipes and PCM hashes. Record code/compiled-class fingerprints,
   exact Java/Kotlin/runtime identities and process exits beside the outputs.
4. The archived room records Java 21.0.12.1; the production gate uses Java 17.
   Establish old-source replay first, then distinguish runtime effects from
   source differences. Do not alter mathematics or golden hashes to hide a
   mismatch. A same-runtime old/current match is still useful, with its scope
   stated. No original-to-current Float identity is claimed before this runs.

The probe requires a new destination and refuses overwrite. Its outputs must
be paired with the execution/source-hash receipt; Java version and working
directory in the probe manifest alone are not full source provenance.
SplayDiagnosticReplay also remains available for original wrapper recipes.
Neither replay entry point is invoked by source preparation.

One melodic TONGUE does not meet the full melodic voice standard. No positive
HOLD, factory kit, native alternate-contact RR, keygroup RR, new native timbral
velocity or finished chord/keygroup performance is delivered here. Existing
velocity is gain-only. Those are separate design/delivery packets; mechanism
counts, preset labels and generic sample variation do not close them.

## Why it is built this way

- Exact source and heard recordings make recovery independently reviewable.
- Current registry grafts retain newer engines and source formats.
- Acoustic and control versions stay separate so old saves keep their identity.
- Native and presentation output paths stay explicit so replay compares like
  with like.
- Deferred numeric checks are stated as open, with an executable probe and
  immutable references ready for the available build slot.
