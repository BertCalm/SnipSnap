# Spool — original recordings on a returning machine

Spool ports the [supplied engineering specification](SPOOL_SPEC.md) into SnipSnap's deterministic
offline melodic pipeline. STRING, CHOIR, FLUTE and BRASS use original synthesized performances;
PALIMPSEST and CUT let those performances cross, return and inherit filtered traces. The sources
are generated for the requested note, with evolving articulation. They are not imported tapes.

This dry listening prototype uses overlapping forward and backward pickups with audible reversed
articulation and gentle shared tape motion.
Six presets cover the six voices. The planned
36-preset library remains deferred until the owner approves the dry sonic identity. A published
audition presents the sounds for that decision; passing numerical checks alone is not sonic approval.

## Controls and finite playback

TUNE selects C3–C5. Five timbral macros shape the recording machine:

| Control | Audible role |
|---|---|
| HEAD | Lateral pickup weights and longitudinal recorded-time travel |
| STRAIN | Gentle shared wow/flutter, then stronger motor loading and strip hesitation |
| RETURN | Level of reversed recorded decays swelling under forward playback |
| IMPRINT | Delayed, softened rerecording into neighboring strips |
| SPLICE | A fixed schedule of overlapping reordered recording sections |
| HOLD | A repeating forward/return cycle at its top setting |

Every independent render starts with immutable original recordings and fresh mutable overlays.
The serialized gate releases forward transport; the backward answer is baked into the WAV.
Default presets use one attack and overlapping pickup layers. Forward sustain continues through
the physical return, so inherited traces are heard without a third attacked pass. An explicitly
saved `reengage=true` recipe can still request one optional quieter finite encounter. A pad does not accumulate memory across independently exported notes.
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

## Overlapping pickups and clean harmony

The motor and spring still move three physical strip coordinates. Two independent virtual
pickup paths now hear the recordings concurrently: the backward preview opens at 45% of the
scheduled gate, while forward playback continues. It uses only the prefix already traversed.
After release, backward anchors follow the actual spring journey; the forward pickup sustains
captured material and fades through the return. The result is layered playback rather than a
forward/rewind/forward sequence. This changes the prototype's audible timing to follow the
owner's listening feedback while retaining bounded physical transport and causal memory.

The original performances now have a bright attack bloom that decays into sustain. The backward
preview traverses 94% to 8% of its captured prefix, so it hears that real recorded decay as a
rising, brightening swell. The physical return later revisits the full traversed performance.
The two backward phrases overlap their grain windows at release. Clean preset RETURN defaults
are .40–.45, making this layer easier to hear without adding another attack.

At STRAIN up to .35, both heads read phase-aligned recorded windows in opposite directions.
The window centers follow transport and diagonal HEAD position, so the recorded articulation
and tone still move in time. Both carriers follow one integrated playback clock with 0.73 Hz wow
and 6.7/12.1 Hz flutter. The components have 5/3/1-cent depth, scaled by `1 + .75 * STRAIN`.
Their combined excursion remains below the reserved 16-cent bound. The layers move together
around the requested note instead of being independently detuned by head travel or spring speed.
Immutable-source fundamental phase aligns the
backward carrier with the forward one; positive overlapping windows preserve the pickup gain
bound. Higher STRAIN smoothly blends physical varispeed for stronger experimental bends.
The pickup trace distinguishes signed carrier rates, the common tape rate, physical rates and
the varispeed blend. Grain footprints reserve their complete maximum variable-speed travel.

Default one-shots disable finite re-engagement for all six voices. Saved recipes that explicitly
request it remain supported. RETURN=0 removes the backward audio stem exactly and approaches
mute continuously. IMPRINT writes a delayed, normalized backward pickup at physical strip
coordinates, and the continuing forward and backward heads can hear that inherited trace.
The write matrix never receives an unbounded sum of the two audible layers.

Separate precolor stems make the overlap measurable and directly auditionable. Twelve checks
cover the four clean families at C3, C4 and C5, with HEAD=.2, STRAIN=.1, SPLICE=0,
IMPRINT=.15 and RETURN=.65. The checks require substantial simultaneous audibility across the
return, average per-stem pitch within 15 cents, agreement within 20 cents and at least 85%
harmonic-band power. Separate audio-derived meters on a single STRING strip at C3/C4/C5 show
3.94–4.07 cents RMS of pitch motion, including 2.07–2.24 cents RMS of faster flutter. Mean tuning
error stays within .044 cents and forward/backward disagreement within 1.01 cents RMS.
The forward recording decays and darkens; its backward pickup swells by 28–30% and its
upper-harmonic/root energy ratio grows by 27–28%. Across six clean release cases, the largest
adjacent 5 ms RMS step is .52 dB and the backward layer remains at least 42% of forward RMS.
These measurements use 25 ms RMS windows at 5 ms hops and the longest active overlap window
for pitch/spectral analysis. The mixed overlap is also checked against a deliberately
semitone-detuned reference as a calibrated roughness counterexample; that reference is never
exported. Spectral measurements support the listening review and do not replace it.

The reverse grain footprint includes interpolation/filter guards and stays inside its captured
past support. Tests cover short gates, extreme HEAD offsets and SPLICE alongside audible
concurrency, independent-stem pitch, RETURN mute, causal imprint, bounded feedback and HOLD.
The published Diagnostics category includes twelve isolated forward/backward pickup clips
from the same default renders, before shared contact coloration. Native files retain their
actual contribution level; matched files are levelled for inspection.

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

The final renderer produces 162 listening clips, including twelve separate pickup diagnostics,
as 324 native and level-matched WAVs, plus the sixteen-pad MPC kit. The assembly adds eighteen
pairs of the previous coherent overlap and current reverse/flutter revision, for 198 page
entries and 384 listening WAVs, including both versions' default pickup stems. Its source patch
targets the current upstream baseline and records a
clean source commit. Provenance hashes cover `Spool.kt`, `SpoolPatch.kt` and `SpoolPresets.kt`;
changes to DSP or recipe defaults require fresh rendering.

Acceptance checks cover independent layer pitch and harmonic agreement, meaningful simultaneous
audibility before and after release, actual captured support, deterministic source/memory,
causal inherited traces, complete feedback bounds, serialization, repeated HOLD waveform
continuity and seam metrics. There are 22 Spool checks, including audio-derived wow/flutter,
reverse swell/brightness and connected release handoff. Buffer accounting and cloud timings are prototype
measurements, not peak JVM memory or phone/MPC performance estimates.
The final pack's clean default/register cases overlap for at least 1.825 seconds, covering at
least 88.57% of the physical return. Mean-window pitch error is below .67 cents and harmonic-band
power remains above 99.30%. Repeated-cycle seam error is zero; maximum boundary difference is
1.97e-13. The largest counted core buffer allocation is 31.66 MiB. Cloud render time is 1.73 seconds
at the median and 7.39 seconds at the maximum, measured while validation was also running.

The required baseline is `./gradlew --no-daemon test`. Acceptance also requires listening to the
forward identity, backward answers, diagonal encounters, finite inherited traces and repeated
HOLD wraps. Phone/MPC validation, final release naming and the owner's listening verdict remain open.
The published audition and generated probe results are the evidence for reviewing this prototype,
not a claim of hardware reconstruction or acoustic recording realism.
