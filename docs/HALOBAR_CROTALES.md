# HALOBAR crotales

Status: native implementation; measurements and listening gate in progress.
Repository baseline: `49d3f3013b8e0a0cb1d9fa5fd2e37e7d4eff9d53`, the
reviewable glockenspiel follow-up in #1003. This addition depends on that work.

The characteristic gesture is a fine bronze strike → a long disc ring →
dark rhythmic answers → a moving halo.

CROTALES is the fifth HALOBAR voice. Its sounding range is C6–C8, with C7 at
TUNE .5. A separate audition and sixteen-pad study kit hold it apart from the
twelve accepted vibraphone, marimba and xylophone recipes. Glockenspiel remains
available with its existing sound. Tubular bells are the remaining planned voice.

## Source and scope

Deutsch, Ramirez and Moore's [crotales investigation](https://scholarship.rollins.edu/stud_fac/3/)
describes center-weighted tuned discs and finds simple thin-plate and clamped
annular-plate models insufficient. The implementation therefore makes no claim
that a circular-plate eigenvalue table simulates a real crotale. Only the paper's
abstract was accessible; its numerical mode tables were not read or copied.

Five modal ratios **1:2.01:3.94:6.10:8.60**, gains **1/.38/.23/.13/.07**, base
T60 values **8/4.8/2.8/1.6/.9 seconds**, and contact **1800→250 microseconds** are
proposed musical shapes. They give the root a near-octave companion, retain
upper ringing after a hit and separate this source from glockenspiel's
inharmonic steel-bar family. They are not measured bronze constants. HOLD,
register and velocity apply the existing shared decay/contact laws, including
a 94 microsecond contact floor for this voice and the existing finite source
cap. The older four voices retain their 65 microsecond floor.

The modal recurrence and damping are literal DSP. Bronze, center mass and disc
geometry are design analogies. No finite-element model, water loading, bow,
mode splitting, mallet collision geometry or new coupling is simulated.
Each mode above .44 times the delivery sample rate is omitted per note, so C8
retains fewer upper modes than C6. The root remains at the requested MIDI note.

```
note + velocity + TOUCH + HOLD
        |
        v
band-limited contact -> five damped disc-inspired modes @ 176.4 kHz
        |                         |
        v                         v
       dry                 root-reduced send
                                  |
                                  v
                       existing three-head dub return
                                  |
                                  v
                       phase motion -> finite FIR halo
        |                         |
        +---------- SPACE mix ----+
                       |
                       v
               DC removal -> house level -> finite fade
```

The shared return never re-excites the source. Its existing contraction proof
still applies: feedback at most .84, non-expanding saturation and a unity-bounded
low-pass. No limiter is used to conceal growing energy. The source has positive
modal decay and no continuous energy supply after the contact.

## Landing and alternatives

This is a voice, rather than an engine: HALOBAR already owns the full gesture.
The nearest inventory siblings are FORK's pickup-colored modal bars and TINES'
FM bells. Neither supplies HALOBAR's root-reduced send, three rhythmic heads and
finite halo as a shared material family.

| Direction | Gesture clarity / roster value | Risk / cost |
|---|---|---|
| DISTIL: sustained disc spectrum in HALOBAR | Strong / distinct fifth source in one gesture | Low / five recurrences; chosen |
| SPLICE: FM disc attack | Clear / overlaps TINES | Medium / extra modulation to validate |
| COUPLE: returns strike the disc again | Strong / different evolving instrument | High / changes the accepted feedback architecture |

## Macro contract

| Control | Default | Quantity changed | Required check |
|---|---:|---|---|
| TUNE | .50 | Semitone root, C6–C8 | Every note JSON round trip; root/confidence at three registers |
| TOUCH | .40 | Contact width and short strike noise | Each range half moves audio; TOUCH×SPACE interaction |
| RATE | .45 | Six delay divisions at 72 BPM | Shared clock and RATE×RETURN corners |
| RETURN | .45 | Feedback, saturation and return loss | Bounded loop; RETURN×SPACE and HOLD×RETURN |
| SPACE | .35 | Echo/halo gains; zero bypasses return | Prepared/fresh equality and private cache ownership |
| MOTION | .25 | Return phase movement | No source motor; MOTION×RETURN corners |
| HOLD | .30 | Finite modal decay | Reserved .98–1 maximum; capped ending and no seamless-loop claim |

TUNE's bounds and inverse live in `Halobar.rootMidi`, `minimumMidi`,
`maximumMidi` and `tuneForMidi`. Existing enum-based picker, patch decoding and
velocity routing discover CROTALES; the CLI study name follows the selected
voice. A root-stepper test exercises the new range. The accepted three-voice
starter and its factory bank stay fixed. Four crotales studies come from
`HalobarPresets.auditionFor`, outside `Presets.all()`.

The separate MPC study kit walks C6, Eb6, F6, G6, Bb6, C7, Eb7 and F7 twice,
once dry and once with dub. Sixteen finite one-shot pads carry complete source
recipes. Hardware loading is a separate listening and integration step.

## Cost and numerical gate

The existing 8 MiB prepared-stem cache keys voice, velocity and every control
except positive SPACE. Positive SPACE changes can reuse source, echo and halo;
SPACE zero changes duration and bypasses preparation reuse. A separate 6 MiB
completed-audio cache owns copies. Other controls require fresh calculation.
There is no new geometry solver or unbounded mode count.

The desktop target is 50 ms per edit; the preceding voice already exceeds it
for fresh calculations. The native audition measures thirty full preparations
and thirty distinct positive SPACE edits after eight warm-ups. Fresh probes
bypass both caches. Desktop measurements do not establish phone performance.

The generic HALOBAR suite covers every new enum voice for determinism, velocity,
contact brightness, macro movement, finiteness, DC, output ceiling, classifier,
4x/8x residual, cache ownership and finite HOLD. New tests compare the dry modal
spectrum and root decay against glockenspiel at C6, check all saved note steps,
measure roots at C6/C7/C8, check required source/return interactions and replay
all sixteen study-pad recipes.

Above 1.1 kHz, the existing pitch probe now doubles its analysis clock divisor
until the same PCM fits the house detector's 1.2 kHz range. It rescales the
frequency estimate and analysis window, with no resampling or expected-pitch
search. C8 uses divisor four. This preserves the physical sample span and
records the analysis rate and confidence in the audition manifest.

The first native prototype failed upper-half TOUCH movement at C7 and the C8
4x/8x residual check (0.157 against a 0.1 ceiling). An 80 microsecond candidate
restored TOUCH but still measured 0.112. The final crotales-only 94 microsecond
floor and wider contact range preserve variation at C6/C7/C8 and measure
0.06935 on the same C8 bright corner. These changes leave the older four
voices' contact law unchanged. The checks and thresholds were retained.

## Reproduce and listen

```sh
./gradlew :synth:generateHalobarCrotalesAudition
./gradlew :cli:run --args='synth HALOBAR CROTALES --midi 96 --velocity .7 --macros SPACE=.6,RETURN=.7 --out /tmp/crotales'
```

Start with `testkit/halobar-audition/crotales/START-HERE.wav`, dry then dub at a
common gain. The page compares C6/C7/C8 and the preceding four voices at C6.
Seven control sweeps, five interacting pairs, full-range edges, five velocities
and four provisional studies expose the important corners. Every clip has a
source recipe; velocity is recorded separately in the manifest for replay.

Sonic risks: a thin highest register after mode omission; a near octave that
reads as a doubled bell; lost attack contrast at the contact floor; finite
source truncation at long HOLD; and fresh-render cost. The register clips,
velocity/macros, long endings, raw/pitch diagnostics and timing distributions
make those risks reviewable. No sound has been heard by the implementing tool.

## Verified / proposed / heard

- Verified: baseline enum, modal recurrence, scoped cache, factory exemption,
  CLI, JSON, picker and root-stepper integration paths were read in the repo.
- Proposed: ratios, weights, losses, contact and register are musical design
  choices pending listening.
- Measured: see the completed as-built record below when validation finishes.
- Heard: original three voices have prior owner approval. Crotales listening,
  phone performance and physical MPC loading remain open.

## Why it is built this way

- A separate disc spectrum makes the voice distinct before any effects.
- Longer losses give the strike time to bloom into the same rhythmic return.
- The shared return preserves HALOBAR's gesture and its bounded feedback.
- High modes drop per note to keep the upper register safe from aliasing.
- Separate studies preserve the approved presets while the owner judges the sound.
