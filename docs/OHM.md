# OHM — family strings

OHM is a dry listening prototype inspired by the [OHM synthesis guide](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/ohm_synthesis_guide.md).
One warm plucked string shares a body with two companions: a wavering,
sine-excited octave resonator and a struck third-harmonic resonator with
quiet inharmonic glass coloration. The listening verdict remains open.

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
Five provisional recipes remain outside the factory roster and phone picker.

Every note starts fresh. Sources and resonance run at 4×, followed by the
shared antialias decimator, DC removal and finite endpoint treatment.
Output is mono 44.1 kHz. `renderRaw` uses a fixed reference gain without
per-patch normalization. `render` levels the complete instrument once to
the shared melodic reference and restores velocity dynamics afterward.
There is no per-layer normalization or feedback limiter.

These are finite samples. Separate notes and pads do not share an ensemble.
Bowing, captured exciters, FM elder, grains, freeze and seamless HOLD are
future work. No live plugin, cross-pad coupling or sustain-loop claim is made.

## Reproduce and listen

```sh
./gradlew :synth:test --tests '*Ohm*'
./gradlew :cli:test --tests '*OhmSynthCommandTest'
./gradlew :synth:generateOhmAudition
./gradlew :cli:run --args="synth OHM STRING --all --midi 60 --velocity 0.8 --seed 731 --out /tmp/ohm"
```

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
is the listening test: does Communion sound like one transformed instrument?
Automated reproducibility, causal coupling, pitch, headroom, finite-tail
and recipe checks support that test; they do not establish sonic approval.
Phone and MPC acceptance remain device checks.

The original pack is preserved as a reference, including its octave-dominant
shortest LENGTH example. Full-mix pitch estimates describe the mixture;
foreground-only probes separately check string tuning and causal coupling.
Spectral-band and temporal-envelope checks support the revision's control
authority; listening determines whether those changes are useful.
