# OHM — family strings

OHM is a dry family-string instrument inspired by the [OHM synthesis guide](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/ohm_synthesis_guide.md).
One warm plucked string shares a body with two companions: a wavering,
sine-excited octave resonator and a struck third-harmonic resonator with
quiet inharmonic glass coloration. Model 2 is the working sound after the
owner heard the revised control variation and asked to continue integration.

The source is an original SnipSnap implementation using its existing
`Strings.Loop`, `Strings.Membrane` and `Strings.Bridge` primitives. It does
not copy the upstream single-sample freeze, sine-burst grain helper, or
amplitude-modulation interpretation of COMMUNE. Those features are absent
from this prototype.

Model 2 responds to the first audition's weak control variation with companions
active. The original whistle dominated the sustained spectrum, masking changes
to the pluck. The revised string includes a stable fundamental displacement,
retains a longer natural decay, and sits beside a quieter whistle. TOUCH and BODY
shape the ensemble; MEDDLING stages successive replies across its range. This
is a new renderer model, so saved model-1 recipes still use the frozen original.

## The relationship

MEDDLING scales the companions' finite breath and strike excitation and opens
successive replies smoothly.
COMMUNE reduces their pitch wander, reduces their independent readout to
20% at full, and opens the reciprocal bridge. Each sample's returning
waves are scattered through one fixed membrane and written back into
all three resonators. Companions therefore change the foreground string's
future sound. The causal test silences their external excitation while
retaining the ports, tuning and readouts, then measures that foreground
change. With COMMUNE zero the same comparison leaves the foreground
unchanged. MEDDLING zero bypasses coupling exactly for every COMMUNE value.

Model-2 coupling is bounded to `0.10 × MEDDLING × COMMUNE`, with a lower
per-note ceiling when necessary to limit the bridge's additional fundamental
loss to 20% of the string's intrinsic round-trip logarithmic loss. The
membrane's lowest mode stays above 1.6 times the requested note. Its poles
and weights stay fixed during each encounter, each loop has loss, and bridge
phase compensation keeps the requested foreground note in tune. Slowly moving coupling is
checked numerically before output leveling; fixed-coefficient passivity
does not by itself prove the moving case.

| Control | Behavior |
| --- | --- |
| TOUCH | Pick position and brightness, whistle articulation and glass excitation; velocity also acts on excitation |
| BODY | Opposed cavity/bridge radiation formants and the shared membrane's resonance |
| LENGTH | String decay, companion release and the finite encounter's duration, 1.8–6 seconds |
| MEDDLING | Graded companion balance and successive replies; zero is the unaccompanied string |
| COMMUNE | Independent companions through shared resonant energy and closer tuning |

STEADY holds the selected relationship. GATHER authors a saved performance:
the first 10% is solo, MEDDLING rises over 10–28%, argument lasts to 43%,
and COMMUNE rises over 43–72%. Saved macro values are maxima. Excitation
stops early enough to leave a natural tail; a final 120 ms fade guards the
file endpoint. GATHER is a generated performance rather than recorded
automation. STEADY uses fixed reply onsets in seconds; LENGTH changes releases
and the source horizon instead of stretching every onset across the file.

## Rendering and recipes

`OhmPatch` saves the five controls, requested MIDI note (48–72), velocity,
integer seed, gesture and renderer model. The patch dispatcher, edits,
`PadRecipe` replay, native velocity rendering and CLI preserve those fields.
New patches use model 2. JSON without a model, or with a null model, retains
model 1; explicit model-1 recipes remain byte-identical at PCM16 export.
The factory roster and phone synth picker expose five complete model-2 recipes:
Solo, Argument, Communion, Quiet Company and Gather. They use the published
audition's TOUCH .5, BODY .5, LENGTH .65, C4, velocity .8 and seed 731. Only the
relationship and gesture differ. Quiet Company is the audition's Quiet influence.

On SYNTH, choose OHM / STRING and a preset. NOTE moves through MIDI 48–72;
VELOCITY sets playing strength; STEADY/GATHER selects the finite performance.
The five macros, native note, velocity, seed, gesture and renderer model survive
preview, editing, SAVE PRESET, SEND TO PAD and a return to the bench. SPREAD uses
the saved native note as its reference. Legacy model-1 recipes retain their
renderer when opened or edited.

Every note starts fresh. Sources and resonance run at 4×, followed by the
shared antialias decimator, DC removal and finite endpoint treatment.
Output is mono 44.1 kHz. `renderRaw` uses a fixed reference gain without
per-patch normalization. `render` levels the complete instrument once to
the shared melodic reference and restores velocity dynamics afterward.
There is no per-layer normalization or feedback limiter.

Models 1 and 2 are finite samples. Separate notes and pads start fresh ensembles.
The optional model-3 extension adds bowed and captured excitation, FM elder,
source-audio grains, captured-buffer freeze and HOLD. It is explicitly selected
through EXTEND; the accepted factory recipes remain model 2. See
[the extension notes](OHM_EXPANDED.md) for its source, loop and instrument contracts.

In model 3, HOLD below .99 extends the finite driven duration; at .99 or above,
ordinary pads contain just the repeating region. MAKE INSTRUMENT preserves the
arrival once and then loops the sustain until note-off. Bowed HOLD captures and
joins a stretch of physical bowing; plucked/captured HOLD uses periodic driven
excitation with independently checked physical continuation. The new audition
labels that distinction and retains raw source/continuation evidence.

The new source options are provisional listening studies, not additional factory
presets. This is still offline synthesis: different notes do not exchange live
audio, and source-buffer freeze is not a claim of FFT spectral freeze.

## Render responsiveness

Model 2 computes its fixed string/output/DC filter coefficients once per
render. The coefficient formula and Float state update are identical to the
original shared one-pole filter. The string helper's fixed-filter path is
opt-in; existing callers retain their original path. OHM also skips a retune
when its target is unchanged, and computes repeated excitation constants at
the note or control tick where they actually change. No resonator state or
rendered sample buffer is shared between notes.

`OhmPerformanceCompatibilityTest` checks 134 saved model-2 recipes against
the clean `b437cafc` baseline: the five factory encounters, quarter control
sweeps, relationship grid, note/velocity/gesture cases, all 32 macro corners
and 24 seeded interiors. Raw Float bits, final Float bits and both complete
PCM16 WAV files match exactly on the recorded JDK 17 host. The existing five
published factory PCM16 pins and frozen model-1 tests remain unchanged.
This is arithmetic and file-format evidence; phone timing is a separate check.

Run `./gradlew -q :synth:benchmarkOhm -PohmBenchmarkPath=final` for render-only
timing JSON. The default path `both` measures raw and final rendering;
`raw` measures only the raw path. Each case has 12 warmup renders and 45 timed
renders. Encoding, hashing, disk I/O, the bench's 100 ms debounce and playback
are outside the timer. The house goal is a rerender in tens of milliseconds;
device measurements are still required before claiming that phone response.

On the Linux amd64 host with Temurin 17.0.20.1 and a 512 MiB heap, warm
final-render medians changed as follows. The baseline and candidate used
the same probe and audio/JSON dependencies; only the synth JAR changed.

| Note length | Solo, before → after | Active, before → after | GATHER, before → after |
| --- | --- | --- | --- |
| 1.8 s | 47.5 → 26.7 ms | 49.5 → 30.7 ms | 50.2 → 32.5 ms |
| 4.53 s (default) | 110.6 → 63.5 ms | 126.4 → 79.0 ms | 124.7 → 78.5 ms |
| 6 s | 146.2 → 80.3 ms | 169.4 → 102.6 ms | 167.8 → 102.7 ms |

These nine cases reduced median render cost by 35.2–45.1%. They do not
establish phone latency or a worst-case bound: the longest active notes
still exceed 100 ms on this host, and the benchmark retains every timing
sample so scheduling/GC outliers remain visible.

## Sixteen-pad starter kit

NEW KIT → STARTERS offers a fixed OHM starter. A01–A08 play Solo in C minor
pentatonic: C3, Eb3, F3, G3, Bb3, C4, Eb4 and F4. A09–A12 keep Argument,
Communion, Quiet Company and Gather at their auditioned C4. A13–A15 put
Argument, Communion and Quiet Company at C3; A16 puts Gather at C5.
The five factory macro maps, velocity .8, seed 731 and renderer model 2
remain intact. Transposition changes the explicit native note, rather than
resampling a C4 recording. Every pad retains its complete editable recipe.

All sixteen pads are dry, unchoked TONAL one-shots. The generic classifier's
duration-based LOOP bucket does not make these finite performances held
instruments. Both exported programs play the whole note once, including its
natural tail, with no loop region or effects.

`./gradlew :synth:generateOhmKit` writes `testkit/SnipSnap Ohm Kit/`, containing
the native MPC 3 `.xtd`, an MPC 2 `.xpm` twin and shared mono 44.1 kHz PCM24
WAVs, editable `kit.json`, individual pad recipes and a manifest with audio
hashes. `testkit/SnipSnap-Ohm-Kit.zip` keeps the program and its
`_[TrackData]` folder together. `-PohmKitOutput=/path/to/output` changes the
destination root.

The export test checks both programs' actual one-shot, loop and frame-end
fields, every saved recipe and the complete PCM24 WAV bytes. It imports the
generated ZIP through the phone's combined-package importer and checks all
sixteen recipes again. Changing a WAV byte makes import refuse the stale
history without landing a partial kit.

On the phone, choose NEW KIT → STARTERS → OHM, then open a pad in SYNTH, edit a
control, save, leave and reopen it. Confirm the note, relationship, gesture
and seed survive. On MPC 3, load the `.xtd` with its adjacent data folder;
on MPC 2, load the `.xpm` inside that data folder. Play the rising first
eight pads, compare the three low companion relationships, and let Gather
finish. Check clean endings and that releasing a pad does not truncate the
one-shot. These are physical-device acceptance checks, separate from the
automated export and replay checks.
The complete ZIP also imports back into SnipSnap with editable recipes:
keep `kit.json` beside the programs and WAVs in the data folder. Its sample
fingerprints guard against attaching a recipe to a changed recording.

## Reproduce and listen

```sh
./gradlew :synth:test --tests '*Ohm*'
./gradlew :cli:test --tests '*OhmSynthCommandTest'
./gradlew :synth:generateOhmAudition
./gradlew :synth:generateOhmKit
./gradlew :synth:generateOhmExpandedAudition
./gradlew :shell:generateOhmExpandedHandoff
./gradlew :cli:run --args="synth OHM STRING --all --midi 60 --velocity 0.8 --seed 731 --out /tmp/ohm"
./gradlew :cli:run --args="synth OHM STRING --model 3 --macros HOLD=1 --instrument --release 0.3 --out /tmp/ohm-keys"
```

The extended audition writes `testkit/ohm-expanded-audition/`, preserving the
original listening pack. Its isolated OFF/ON studies, phrases and held examples
carry full model-3 recipes, including the original authored capture when used.
The handoff task writes three provisional C3–C5 instruments with MPC 3 `.xty`,
MPC 2 keygroup `.xpm`, shared PCM24 WAVs, instrument metadata and ordinary
`.patch.json` recipes under `testkit/ohm-expanded-handoff/`. The source choices
and saved release replay through CLI `--patch`; named controls can be edited
through `--macros`. Native note, velocity and seed overrides remain available.

The generator writes `testkit/ohm-audition/`: a listening page, 79 dry
raw/matched PCM16 clips, recipes, measurements, audio hashes and a focused
18-comparison audio/recipe ZIP. The ZIP includes the full manifest for
provenance; its README explains that the separate sweep files and listening
UI remain on the hosted page.
Its first five examples fix note, velocity, seed, touch, body and length:
Solo, Argument, Communion, Quiet influence and Gather. The remaining
collections compare a 3×3 relationship grid, the other controls, three
registers and velocities, and the same six-note melody in three relationships.
The revision study adds five positions for each control, solo and active
contexts for TOUCH/BODY/LENGTH, joined 25→75% comparisons, and four-part
original/revised comparisons. The unchanged original audition is linked at
`original/index.html`.

Matched playback applies `AuditionLevel` to the complete raw note or phrase.
Joined comparisons level each constituent separately before concatenation,
with silent gaps and exact segment timings in the manifest. Raw comparisons
retain each constituent's source level. Their recipes record complete patches,
renderer models, frame offsets and export processing.
Raw files retain relative source dynamics, with any common PCM headroom
gain stated in the manifest. A single-note JSON is an ordinary `PadRecipe`;
phrase JSON records six complete patches, frame offsets and mix gains.
Standard pad replay yields engine-leveled audio, while replaying the
audition adds the documented raw/matched export processing.

The [private audition](https://ohm-snipsnap-audition.bertcalm.chatgpt.site)
preserves the listening evidence and original/revised comparisons.
Automated reproducibility, causal coupling, pitch, headroom, finite-tail
and recipe checks support the owner's listening decision. Phone and MPC
playability and handoff remain device checks.

The original pack is preserved as a reference, including its octave-dominant
shortest LENGTH example. Full-mix pitch estimates describe the mixture;
foreground-only probes separately check string tuning and causal coupling.
Spectral-band and temporal-envelope checks support the revision's control
authority; listening determines whether those changes are useful.
