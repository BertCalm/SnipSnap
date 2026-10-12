# Spool — original recordings on a returning machine

Spool ports the [supplied engineering specification](SPOOL_SPEC.md) into SnipSnap's deterministic
offline melodic pipeline. STRING, CHOIR, FLUTE and BRASS use original synthesized performances;
PALIMPSEST and CUT let those performances cross, return and inherit filtered traces. The sources
are generated for the requested note, with evolving articulation. They are not imported tapes.

This dry listening prototype now includes revised forward, return and repeat transitions.
Six presets cover the six voices. The planned
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
During a short rest, transport and pickup state restart at the reference state for the next
powered cycle. This is a repeating machine schedule; it does not claim live write-state convergence.

## Connecting forward, return and repeat

The revised calibration connects the phrases without making the RETURN answer as loud as
the forward recording. Forward transport coasts for 25 ms before the gate. A 120 ms contact
handoff carries the recording into a return whose velocity launches over 35 ms, then decelerates
toward rest. Head displacement and splice geometry ease toward their neutral path over 200 ms
at the turn, so a smooth transport change does not encounter an abrupt read-coordinate change.
Low SPLICE amounts blend continuous and reordered windows smoothly; the deterministic splice
schedule and recorded material remain the same.
RETURN=0 still makes the reset inaudible; values up to .1 ease smoothly from that muted
behavior into the connected handoff. The measured .2 and .65 settings use the full handoff.

The return stops within the smaller of 12 ms of recorded travel or 1% of its actual travel,
with the small remaining spring energy dissipated. A finite re-engagement starts immediately
after that return at 38% contact level with a 60 ms contact ramp. HOLD reduces the scheduled
rest from 160 ms to 25 ms. These remain bounded journeys through the original recordings and
their inherited overlays; the update does not add a separate effect or a new preset library.
It is a calibration revision within the prototype's existing model and recipe schema.

The transition probes compare STRING, CHOIR, FLUTE and BRASS at C4 with HEAD=.2, STRAIN=.1,
SPLICE=0 and IMPRINT=0, at RETURN=.2 and .65. They use 25 ms RMS windows at 5 ms hops.
The quiet-run measurement counts consecutive windows below 3% of each case's peak phrase RMS;
the finite measurement excludes the intentional terminal fade. The gate measurement compares
the lowest handoff window with the quieter adjacent phrase, preserving a deliberately quiet return.

| Measured transition | Original calibration | Revised calibration |
|---|---:|---:|
| Minimum gate RMS / quieter adjacent phrase RMS | .202–.298 | 1.157–1.439 |
| Quiet run between return and finite re-engagement | 305–425 ms | 5–115 ms |
| Quiet run across a repeated HOLD wrap | 400–505 ms | 20–130 ms |

The acceptance probes require a gate ratio of at least .40 and a quiet run of at most 150 ms.
These measures establish audible continuity under those settings; the published before/after
clips let the owner judge whether the connected phrases have the intended fluid character.

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

To include a preserved earlier pack for transition comparisons, assemble with:

```sh
python scripts/prepare-spool-audition.py --compare-renders /path/to/preserved-spool-audition
```

The renderer writes `testkit/spool-audition/`. The assembly script copies its actual WAVs, patch
recipes and kit into `testkit/spool-audition-site/`, adds the listening interface and download
bundle, and records the DSP source hash in its manifest. It includes the six prototype presets,
dry/level-matched audio and machine probes for the owner's review. The static site directory is
the publishable audition; publishing does not change the rendering or imply sonic approval.
The optional comparison flag adds labelled before/after pairs for each voice's finite note,
HOLD cycle and three repeated cycles, retaining the earlier pack's source provenance.
Individual sounds can also be rendered with:

```sh
./gradlew :cli:run --args="synth SPOOL STRING --all --out /tmp/spool"
./gradlew :cli:run --args="synth SPOOL CHOIR --midi 60 --velocity .35 --out /tmp/spool-soft"
```

The revised measured pack contains 150 listening clips: 300 native and matched WAVs, plus
16 samples in the MPC kit. All WAV headers, frame counts, asset paths and sixteen XPM sample
references were checked. The listening WAVs are mono 44.1 kHz PCM16. Every HOLD cycle measured
`Keys.seamError` of zero against an independently rendered preceding cycle; the separate
normalized boundary step/slope error was at most `9.23e-9`. Independent renders reset their
overlays, and HOLD repeats a frozen snapshot rather than accumulating live memory.

All eighteen Spool acceptance probes passed, including forward pitch within ten cents for
all six voices at C3, C4 and C5 with mild transport and SPLICE=0. The manifest's wider forward
window can include diagonal travel and a splice overlap: the four clean families across their
measured default, register and velocity cases stayed within 5.11 cents in that window,
while experimental bends reached 75.4 cents. Those figures
remain visible alongside each clip instead of being treated as steady source tuning.

Reported core-array allocation peaked at 19.84 MiB; this is renderer buffer accounting,
not peak JVM memory. Median rendering time was 0.966 seconds per clip and the maximum was
2.647 seconds during the final cloud render. These cloud measurements do not
estimate phone or MPC performance.

The required baseline is `./gradlew --no-daemon test`. Acceptance also requires listening to the
forward identity, backward answers, diagonal encounters, finite inherited traces and repeated
HOLD wraps. Phone/MPC validation, final release naming and the owner's listening verdict remain open.
The published audition and generated probe results are the evidence for reviewing this prototype,
not a claim of hardware reconstruction or acoustic recording realism.
