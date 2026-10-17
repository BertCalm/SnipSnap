# OHM — family strings

OHM is a first dry listening prototype inspired by the [OHM synthesis guide](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/ohm_synthesis_guide.md).
One warm plucked string shares a body with two companions: a wavering,
sine-excited octave resonator and a struck third-harmonic resonator with
quiet inharmonic glass coloration. The listening verdict remains open.

The source is an original SnipSnap implementation using its existing
`Strings.Loop`, `Strings.Membrane` and `Strings.Bridge` primitives. It does
not copy the upstream single-sample freeze, sine-burst grain helper, or
amplitude-modulation interpretation of COMMUNE. Those features are absent
from this first prototype.

## The relationship

MEDDLING scales the companions' finite breath and strike excitation.
COMMUNE reduces their pitch wander, reduces their independent readout to
20% at full, and opens the reciprocal bridge. Each sample's returning
waves are scattered through one fixed membrane and written back into
all three resonators. Companions therefore change the foreground string's
future sound. The causal test silences their external excitation while
retaining the ports, tuning and readouts, then measures that foreground
change. With COMMUNE zero the same comparison leaves the foreground
unchanged. MEDDLING zero bypasses coupling exactly for every COMMUNE value.

Coupling is bounded to `0.22 × MEDDLING × COMMUNE`. The membrane's poles
and weights stay fixed, each loop has loss, and bridge phase compensation
keeps the requested foreground note in tune. Slowly moving coupling is
checked numerically before output leveling; fixed-coefficient passivity
does not by itself prove the moving case.

| Control | Behavior |
| --- | --- |
| TOUCH | Softer/darker through brighter pick contact, with velocity also acting on excitation |
| BODY | Radiation spectrum and the shared membrane's size and resonance |
| LENGTH | String decay and the finite encounter's duration, 1.8–6 seconds |
| MEDDLING | Companion breath/strike activity; zero is the unaccompanied string |
| COMMUNE | Independent companions through shared resonant energy and closer tuning |

STEADY holds the selected relationship. GATHER authors a saved performance:
the first 10% is solo, MEDDLING rises over 10–28%, argument lasts to 43%,
and COMMUNE rises over 43–72%. Saved macro values are maxima. Excitation
stops early enough to leave a natural tail; a final 120 ms fade guards the
file endpoint. GATHER is a generated performance rather than recorded
automation.

## Rendering and recipes

`OhmPatch` saves the five controls, requested MIDI note (48–72), velocity,
integer seed, gesture and renderer model. The patch dispatcher, edits,
`PadRecipe` replay, native velocity rendering and CLI preserve those fields.
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

The generator writes `testkit/ohm-audition/`: a listening page, 35 dry
raw/matched PCM16 clips, recipes, measurements, audio hashes and a ZIP.
Its first five examples fix note, velocity, seed, touch, body and length:
Solo, Argument, Communion, Quiet influence and Gather. The remaining
collections compare a 3×3 relationship grid, the other controls, three
registers and velocities, and the same six-note melody in three relationships.

Matched playback applies `AuditionLevel` to the complete raw note or phrase.
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
