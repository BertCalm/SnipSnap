# OBBLIGATO: accepted duet and responsive Fight experiment

Repository baseline: `91384fa189a68c4993c8e359af6b748412bf95c3`.

Model 5 is the accepted sound and remains the default for presets, missing-model
recipes and the sixteen-pad kit. Model 6 is an explicit listening experiment. Its
characteristic gesture is **air asserts → reed hears and readies → reed interrupts
smoothly → air counters → both release**. Each note starts fresh.

The air jet and reed are the existing independently seeded `Bore.Player` sources.
The relationship is an invented behavioural analogy. It does not simulate two
human players, a shared lung or energy transfer between acoustic instruments.

## Why this belongs in the existing engine

BORE supplies one woodwind source per render. FLOCK supplies a social controller
over oscillator agents. OBBLIGATO combines two separately articulating pipes and
lets measured outgoing activity affect the partner's pressure and decisions.
This revision changes its controller; it does not add another engine or voice.

Three directions were considered:

| Direction | Gesture, novelty, risk and cost |
| --- | --- |
| COUPLE, chosen | Actual source activity triggers a partner's interruption. A clear change in the duet's decisions, bounded at control rate, one experimental renderer model. |
| MUTATE | Change each player's personality while retaining the clock. Cheap and reproducible, but it does not establish a consequential response to the opponent. |
| INVERT | Make one player's pressure directly extinguish and restart the other. More forceful competition, with the earlier source-capture and gap risk. |

## Signal flow and boundaries

```text
note, strike, seed, macros, renderer model
                    |
                    v
       [two independently seeded Bore.Player sources @ 176.4 kHz]
                    |                         ^
                    | actual pipe activity    | bounded pressure response
                    v                         |
       [40 ms activity meters] -> [controller @ 176400 / 64 Hz]
                                      |
                                      | actual entry ledger, bounded pitch push
                                      v
       [existing per-source conditioning, band limit and decimation]
                                      |
                                      v
       [audio-rate radiation envelopes from recorded decisions]
                                      |
                                      v
       [fixed source weights -> sum -> house loudness and peak ceiling]
```

The physical sources keep their mouth pressure in the calibrated speaking range.
Prominence changes at the radiation stage. Model 6 reconstructs each actual
entry's 110 ms raised-cosine handoff at audio rate, avoiding a staircase after
decimation. The meters use the preceding source-activity snapshot; one player
cannot read the other player's partly updated state.

Only the Fight region of BOND uses the experimental controller. Neighboring
positions blend its prominence depth. Harmony, Play and Protect have the same
rendered audio under models 5 and 6. The controller is not tempo synchronized.

## Controls and stability contract

| Control | Default | Quantity changed | Required interaction |
| --- | ---: | --- | --- |
| BREATH | .65 | The two bore operating points and prelevel input energy | Changes the actual activity available to readiness and reciprocal pressure. |
| BOND | 0 | The existing eight-stage relationship interpolation | Fight enables activity-driven entries in model 6; interpolation remains bounded. |
| MISCHIEF | .2 | Independent pitch motion and bounded model-6 readiness thresholds | Changes response appetite and entry-relative pitch pushes without an absolute entry schedule. |
| HOLD | .45 | A 2–5 second held gesture followed by .75 second release | Entry decisions stop before the release; this is a finite one-shot, including at 1. |

The played MIDI note is explicit, C4–C5. The strike remains a native source-timbre
parameter; the kit's peak-matched velocity layers leave playback level to the MPC.
No new macros or factory presets are introduced before listening.

Activity is bounded to 0–1. Readiness is clipped to 0–1 and forgets when either
source falls quiet. Hysteretic audibility, a .27–.32 second minimum dwell and
thresholds in .30–.60 prevent chatter and unbounded event counts. There is no
deadline that creates an entry for an absent source. All these threshold and
timing constants are audition choices, not acoustic measurements.

Radiation gains remain between .18 and 1. At the exact Fight position their
squared sum is `1 + .18²` during a handoff. Neighboring BOND positions blend
toward unity and do not retain that constant-power identity. This bounds the
full-Fight envelope's power law; it does not promise constant
mixed RMS because independent pipes can interfere. Existing pitch limits,
source conditioning, common leveling and finite tail fading remain in place.

## Compatibility and render cost

Thirty original recipes cover models 1–5, the four accepted relationships and
additional notes, strikes, seeds and interpolated macro positions. Their raw
Float32 sum/stem hashes and complete PCM24 export hashes are captured separately
from the new implementation in
`synth/src/test/resources/obbligato/models1-5-sha256.json`. The capture utility
refuses changed original DSP sources. Tests read these fixtures; they do not
regenerate or bless a replacement sound. Canonical capture used Temurin 17,
matching the JVM CI major version.

Profiling the original render identified decimation and low-pass filtering as
the largest sampled costs. Production rendering now omits diagnostic traces,
extra mixed-buffer copies and unused RMS scans. Paired phrase calculations share
their common intermediate values. The resampler reuses its complete interior
kernel normalization and retains accumulation order, edge handling and zero-tap
products. These changes are required to pass the original audio fixtures.

Work remains bounded by note duration: at most 5.75 seconds of two oversampled
sources, and at most one interaction entry per minimum dwell. Kernel weights and
normalization are precomputed per resampling call; there is no unbounded solver,
geometry rebuild or hidden cross-note state. A changed note, macro, strike, seed
or model requires its own physical render. Exact whole-render caching would need
all those values as a key and does not solve a stream of new knob positions.

Performance numbers are recorded by the warm-JVM probe; they are not phone
measurements. The house goal of tens of milliseconds is a target, not a claim
that this experiment has achieved it.

Measured on Temurin 17.0.21, C4, strike .8, seed 0, HOLD .45, five warmup
renders and three timed renders per cell:

| Relationship | Original median | Optimized median | Original / optimized allocation |
| --- | ---: | ---: | ---: |
| Harmony | 339.5 ms | 324.4 ms | 15.30 / 13.83 MiB |
| Play | 351.2 ms | 326.0 ms | 15.29 / 13.83 MiB |
| Fight | 386.7 ms | 345.4 ms | 15.30 / 13.83 MiB |
| Protect | 325.5 ms | 307.3 ms | 15.23 / 13.77 MiB |

Allocation is bytes allocated on the render thread, not retained or peak memory.
Three observations per cell are exploratory. Fight's measured median improved
10.7%; the existing physical pipeline still costs hundreds of milliseconds.

## Causal proof and listening pack

`Obbligato.probeWithWeakening` is an internal diagnostic hook. It briefly lowers
one bore's physical mouth pressure with smooth 10 ms edges. It never edits the
other player's signal or gives the controller a privileged intervention flag.

The positive check requires a change in the unweakened player's actual entry
time or behavior. With decisions and reciprocal pressure disabled, that same
unweakened source must remain exactly identical. Merely observing large waveform
distance is insufficient because a small pressure change can cause phase drift.

At the common audition recipe, the .45–.85 second AIR intervention moved the
first REED entry from .579 to 1.147 seconds. The 1.3–1.7 second REED intervention
moved the following AIR entry from 2.227 to 2.821 seconds. Both responses-off
survivor stems remained bit-identical. Relative lead windows compare both stems;
their ratio can change solely because the interrupted opponent becomes quieter.
They are not independent evidence that the survivor changed its behaviour.

Generate the comparison with:

```sh
./gradlew --no-daemon :synth:generateObbligatoResponsiveAudition
```

The pack includes accepted model 5, experimental model 6, decisions disabled,
two physical interventions and their negative controls, additive source taps,
four-note phrases, full recipes and entry/lead-window measurements. Every clip
uses one common reference-based gain. No stem is independently normalized.
The model-6 control-off comparison deliberately freezes its role decisions; it
does not mean the original timed phrase with only a pressure effect removed.

## Why it is built this way

- The accepted model stays available so a new experiment cannot silently replace
  the instrument already approved by listening.
- Stable mouth pressure keeps both bores speaking; smooth radiation trades avoid
  the earlier gap between players.
- Readiness listens to actual source activity so an interruption can wait or
  disappear when the opponent changes.
- Independent seeds and complete recipes make each candidate repeatable.
- Common gains and an exact negative control expose the consequence of the
  interaction without turning loudness differences into evidence of better sound.

## Verified, measured, proposed and heard

The source APIs and bounds above are verified against the repository. Compatibility,
causal and performance results are produced by Kotlin/JVM tests and probes. Model-6
thresholds and musical meaning remain proposed until Josh hears the comparison.
Model 5 was previously heard and accepted. No new listening claim is made here.

As built, the focused audio and OBBLIGATO run passed 475 tests in 64 suites,
including the 30 original audio recipes, all six interaction tests and kit
export/readback. The comparison generator produced 19 mono PCM24 recordings at
44.1 kHz and an 11.85 MiB portable ZIP. Broader repository and Android checks are
separate from this focused evidence.
