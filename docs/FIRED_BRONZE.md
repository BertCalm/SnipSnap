# Fired Bronze first build

Owner listening rejected this version as sounding like a basic drum machine.
It remains a reproducible archive. Continue with [the R2 dry study](FIRED_BRONZE_R2.md).

Sixteen finite hybrid percussion candidates with three velocity timbres, an
explicit processor-before-amp treatment, complete study recipes, and an MPC
sample export. Not factory-approved; no traditional-instrument fidelity claim.

Read the [engineering specification](superpowers/specs/2026-11-07-fired-bronze-design.md)
for the instrument research, signal flow, shape constants and outstanding work.

```sh
./gradlew --no-daemon :synth:test --tests '*FiredBronzeTest'
./gradlew --no-daemon :synth:generateFiredBronzeAudition
./gradlew --no-daemon test
```

The generator writes `testkit/fired-bronze-audition/index.html`, native and
matched WAVs, JSON recipes, measurements, a three-zone sixteen-pad MPC program
folder, and `testkit/fired-bronze-audition.zip`. Start with the dry and processed
96 BPM phrases, then compare dry/processor/amp on each part. The phrase is an
authored hybrid, not a traditional performance.

Replay an exact candidate:

```sh
./gradlew --no-daemon :synth:generateFiredBronzeAudition \
  -PfiredBronzeReplay=testkit/fired-bronze-audition/recipes/bayan_amp.json \
  -PfiredBronzeReplayOutput=testkit/fired-bronze-replay.wav
```

Study recipes have their own versioned schema. They do not deserialize through
the phone's Patch registry; exported kit pads are sample-based, with recipe
sidecars in the audition pack. HOLD, factory promotion, phone picker and real
MPC acceptance remain later work.

## Validation

Native Kotlin validation on 2026-11-07, Temurin JDK 21.0.13, Linux x86-64,
Intel Xeon Platinum 8573C; JVM processor count capped at four:

- Focused suite: **10 tests passed**, exit 0, including the `DeterminismTest`
  canary. Command: `./gradlew --no-daemon :synth:test --tests '*FiredBronzeTest'
  --tests '*DeterminismTest.FIRED*'`.
- Generator: exit 0; 102 native/matched PCM24 comparison pairs, 100 recipe
  sidecars, and a three-layer sixteen-pad XPM kit. XML/WAV read-back verified
  all 48 sample references and frame counts, velocity zones 1–41 / 42–84 /
  85–127, and hat pads A03/A04 in mute group 1.
- Recipe replay through the Gradle task: exit 0. The exported bayan AMP WAV
  and replay have identical SHA-256:
  `2a57e0ec141925f1efbedccbb50bebc94d1202a003a07a8a3a41ddbbb5949ccf`.
- Across all 102 native clips, maximum absolute mean was 0.00031988 and
  maximum peak was 0.9900001 (Float rounding).

| Stage (includes source render) | Median of pad medians | Range of pad medians |
|---|---:|---:|
| Dry | 171.0 ms | 6.9–509.6 ms |
| Processor | 214.9 ms | 26.8–307.0 ms |
| Amp last | 294.8 ms | 68.0–808.8 ms |

Each pad/stage has two warmups and five measured runs. The broader Gradle
suite ran concurrently on this shared host, so these are loaded-host timings,
not isolated benchmarks or phone measurements. **The interactive render budget
is not demonstrated.** Profile source conditioning and denormal tails, then
measure isolated renders before deciding on the bounded dry cache in the spec.

Python house probes analyzing the exported native Kotlin WAVs measured the
open/damped dayan candidates at 220.13/441.02 Hz estimated pitch, with 0.998/0.973
autocorrelation confidence, and decay to −60 dB at approximately 0.55/0.07 s.
The damped stroke's dominant pitch estimate follows its suppressed fundamental;
these are implementation measurements, not traditional-instrument validation.

Full JVM regression was started with `./gradlew --no-daemon test --fail-fast`.
At this first-build handoff it was still running, with more than 1,200 reported
passes and no reported failure; **there is no full-suite exit result or green
claim**. Its live log is `/tmp/fired-bronze-regression.log` in this workspace.
Android was excluded by the repository because no Android SDK is installed.

Still open: owner listening, reference calibration, higher-rate alias comparison,
isolated and phone timing, editable factory integration, MPC3 export validation,
and actual MPC loading. Numerical checks do not establish any of these.
