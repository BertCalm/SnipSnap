# Spool — original recordings on a returning machine

Spool ports the [supplied engineering specification](SPOOL_SPEC.md) into SnipSnap's deterministic
offline melodic pipeline. STRING, CHOIR, FLUTE and BRASS use original synthesized performances;
PALIMPSEST and CUT let those performances cross, return and inherit filtered traces. The sources
are generated for the requested note, with evolving articulation. They are not imported tapes.

This is the first dry listening prototype. Six presets cover the six voices. The planned
36-preset library remains deferred until the owner approves the dry sonic identity. A published
audition presents the sounds for that decision; passing numerical checks alone is not sonic approval.

## Controls and finite playback

TUNE selects C3–C5. Five timbral macros shape the recording machine:

| Control | Audible role |
|---|---|
| HEAD | Lateral pickup weights and longitudinal recorded-time travel |
| STRAIN | Shared motor loading and different strip hesitation |
| RETURN | The contact and level of the backward answer |
| IMPRINT | Delayed, softened rerecording into neighboring strips |
| SPLICE | A fixed schedule of overlapping reordered recording sections |
| HOLD | A repeating forward/return cycle at its top setting |

Every independent render starts with immutable original recordings and fresh mutable overlays.
The serialized gate releases forward transport; the backward answer is baked into the WAV.
The machine can make at most one quieter finite re-engagement so the inherited trace is heard
within the same one-shot. A pad does not accumulate memory across independently exported notes.
External pitch transposition also changes gesture timing. This port does not add playback-time
release samples or attack-plus-loop regions.

Splices reorder the forward and return pickup windows. The physical write head keeps its
unspliced strip coordinate; a later spliced read can meet that locally written trace in a new order.
This deliberate write-path exemption keeps inherited material tied to an inspectable tape location.

HOLD records `FROZEN_SNAPSHOT` in its machine recipe: a finite deterministic imprint is prepared
before writes are frozen for the exported repeating cycle. IMPRINT controls that snapshot.
The exported loop does not continuously rewrite its history. Return events remain part of its cycle.
During the silent rest, transport and pickup state restart at the reference state for the next
powered cycle. This is a repeating machine schedule; it does not claim live write-state convergence.

## Saved machine and integration

`SpoolPatch` saves its model version, velocity, seven host macros, and `SpoolMachine`. The machine
saves source/gate/return duration, bounded motor torque, three source strips, strip gain/tension/drag/
contact/doubling, three longitudinal head offsets, up to four normalized splice boundaries and
displacements, sparse delayed write routes, seed, write delay, finite re-engagement and HOLD mode.
Default patches save an explicit dominant source plus two quiet companions; an empty machine roster
means the versioned voice roster. Audio buffers and transient motor/spring state are rebuilt.

The recipe rejects invalid recording lengths, gate/head geometry, unsorted or crowded splices,
unknown source families/HOLD modes, duplicate routes, and unsupported model versions. Each
destination's incoming route gain sum is bounded by 0.55. Positive interpolation and filtering,
matched splice windows and the bounded nonlinear path preserve that routed feedback bound.
At the start of a write generation, the DSP retains 0.40 of any prior overlay; causal convex
local updates preserve a complete generation-map contraction bound of 0.40, including cells
the write head does not visit. IMPRINT=0 bypasses retention and writes exactly. HOLD prepares
one finite imprint snapshot and then freezes writes, so it does not claim live periodic
write-process convergence.

The standard patch dispatcher, factory preset lookup, velocity renderer, phone SYNTH picker,
FRESH TAPE menu and command-line renderer share the same machine. Macro edits retain the saved
machine geometry; promoting a user preset to Kotlin also retains every machine field.
`SynthKits.spool()` makes sixteen dry pads: eight C minor pentatonic string notes, all six voices,
and two HOLD snapshot loops. Each pad saves its complete `PadRecipe`; optional `FxChain`
processing remains outside the engine and its write feedback. Shared melodic loudness processing
follows the recording machine. Finite-note recipes use TONAL and HOLD exports use LOOP.
The waveform classifier can also identify a long finite forward/return gesture as LOOP
because of its sustained duration; the recordings retain melodic identity and avoid drum classes.

## Audition and validation

Published listening page: [Spool · SnipSnap Audition](https://snipsnap-spool-audition.bertcalm.chatgpt.site).
The audition is private to its owner by default and presents prototype sounds for review.

Generate the listening pack with:

```sh
./gradlew :synth:generateSpoolAudition
python scripts/prepare-spool-audition.py
```

The renderer writes `testkit/spool-audition/`. The assembly script copies its actual WAVs, patch
recipes and kit into `testkit/spool-audition-site/`, adds the listening interface and download
bundle, and records the DSP source hash in its manifest. It includes the six prototype presets,
dry/level-matched audio and machine probes for the owner's review. The static site directory is
the publishable audition; publishing does not change the rendering or imply sonic approval.
Individual sounds can also be rendered with:

```sh
./gradlew :cli:run --args="synth SPOOL STRING --all --out /tmp/spool"
./gradlew :cli:run --args="synth SPOOL CHOIR --midi 60 --velocity .35 --out /tmp/spool-soft"
```

The first measured pack contains 150 listening clips: 300 native and matched WAVs, plus
16 samples in the MPC kit. All WAV headers, frame counts, asset paths and sixteen XPM sample
references were checked. The listening WAVs are mono 44.1 kHz PCM16. Every HOLD cycle measured
`Keys.seamError` of zero against an independently rendered preceding cycle; the separate
normalized boundary step/slope error was at most `1.79e-28`. Independent renders reset their
overlays, and HOLD repeats a frozen snapshot rather than accumulating live memory.

The fifteen Spool acceptance probes passed, including forward pitch within ten cents for
all six voices at C3, C4 and C5 with mild transport and SPLICE=0. The manifest's wider forward
window can include diagonal travel and a splice overlap: ordinary default gestures reached
17.1 cents in that measurement, while experimental bends reached 92.9 cents. Those figures
remain visible alongside each clip instead of being treated as steady source tuning.

Reported core-array allocation peaked at 20.34 MiB; this is renderer buffer accounting,
not peak JVM memory. Median rendering time was 1.17 seconds per clip and the maximum was
4.10 seconds while repository tests ran concurrently. These cloud measurements do not
estimate phone or MPC performance.

The required baseline is `./gradlew --no-daemon test`. Acceptance also requires listening to the
forward identity, backward answers, diagonal encounters, finite inherited traces and repeated
HOLD wraps. Phone/MPC validation, final release naming and the owner's listening verdict remain open.
The published audition and generated probe results are the evidence for reviewing this prototype,
not a claim of hardware reconstruction or acoustic recording realism.
