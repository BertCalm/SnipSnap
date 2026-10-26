# Skirl render-cost evidence

The current model-3 finite path preserves the model-1 waveform and its complete acoustic diagnostics.
The source baseline is `4ecd6fe1a14ac24a1ee446d87c81b35d5c8b8778`. The final working-tree
source hashes, paired timings and the 106-case replay hashes are recorded in
[`report.json`](report.json).
The earlier model-2 comparison is preserved in [`report-model2.json`](report-model2.json).
The final comparison uses the compiled model-3 source at `2ce893b`, with the exact
source and class hashes recorded in the current report, including `SkirlHold`.

## What changed

- The contact sources are allocated and filtered only through REST. The old code
  filtered a further 2–3.3 seconds and then replaced those samples with zero. The
  causal filters cannot feed that discarded work back into earlier samples. The
  full zero-padded input and modal ringing tail are still retained.
- The shared power-of-two resampler computes the complete interior kernel's
  normalization once. Edge outputs retain their truncated normalization. Its mono
  interior loop groups four consecutive additions without changing tap order,
  multiplication precision or division.

The standard 4× synthesis, six-pole input filter, modal tables, eight-pole output
filter, two-stage decimator and house loudness stage are unchanged. The twelve-entry
physical cache still excludes note, EDGE, PLATE and acoustic velocity.

## Measurements

`report.json` records three alternating baseline/current JVM pairs, each with five
warmup renders and fifteen measured warm-cache renders per voice. The driver also
records a separate diagnostic inspection; those stage timings are not components of
the warm median. The runs use default C3/full velocity, Java 21 on the current Linux
container, and `-Xmx2g`. No benchmark or test process deliberately competed during
these final pairs. Container scheduling and unrelated host load remain uncontrolled.
The baseline explicitly requests model 1 and the candidate explicitly requests
the current model 3; HOLD is zero in every measured finite render.

| Voice | Baseline median of three run medians | Current median of three run medians | Reduction |
|---|---:|---:|---:|
| ROLL | 135.54 ms | 105.33 ms | 22.3% |
| WHIRR | 153.73 ms | 127.54 ms | 17.0% |

The earlier exploratory measurements ran alongside compilation or tests and are not
the final latency comparison. A separate three-pair million-frame resampler experiment
found mono loop unrolling reduced its two-stage median from 22.91/22.37/24.87 ms to
16.95/16.12/17.84 ms, with the same output float hash in all six runs. This component
experiment does not predict complete engine cost.

The 50 ms complete-render target remains open. These are warm desktop/container JVM
measurements, not phone measurements or cold-process startup measurements.

## Replay verification

`cases.tsv` preserves the 106 finite recipes from the original native audition.
The comparison hashes every production float and every prelevel float in little-endian
IEEE-754 form, and hashes the complete acoustic diagnostics string. It also compares
frame counts. All 106 cases match exactly using the final compiled model-3 production classes.
This is stronger than matching PCM16 WAVs. The drivers accept separate requested
models for each classpath, so the original model-1 finite waveform can be compared
with the current model's finite path as well as with explicit legacy replay.
The unversioned archived binary rejects any requested model other than 1; current
APIs enforce their own supported model set. Model-1 HOLD remains refused.

The shared resampler's existing noise/mono/stereo bit-parity cases remain. Added
cases cover tiny edge-only buffers, both interior joins, odd lengths and the wider
8:1 kernel. The final focused test result and replay result are in `report.json`;
the full repository gate is recorded with the HOLD milestone.

## Reproduce

Build `:cli:installDist` separately at the baseline revision and at the current source.
Keep the two library folders separate. Then, from the current repository root:

```sh
python3 prototype/skirl/native/performance/run.py \
  --baseline-classpath '/path/to/baseline/cli/lib/*' \
  --candidate-classpath '/path/to/current/cli/lib/*' \
  --baseline-model 1 \
  --candidate-model 3 \
  --output testkit/skirl-performance \
  --repeats 15
```

The runner needs Python 3 and a Java installation containing `jdk.compiler`. It
compiles its small Java drivers, renders both versions sequentially, writes the
timings and hashes, and returns a failing exit code if any finite replay differs.
Repeat the command in alternating order for a timing comparison; the sound parity
check is independent of scheduling. The driver uses reflection only to bridge the
four-argument model-1 and five-argument versioned render/inspection APIs.

For the component experiment, use the same compiled drivers and run `BenchResampler`
against each audio library. It renders one million source frames through the two
historical 2:1 resampling stages, with ten warmups and thirty-one measured repeats.

## Why it is built this way

- Omitting discarded causal work retains the original physical contacts and samples.
- Reusing fixed kernel data reduces every mono synth's output cost while preserving
  the exact resampling arithmetic.
- Keeping the cache tied to physical controls lets changing a note reuse the same
  rolling history. The remaining modal and output work still needs measurement on
  the Android device before making a phone-latency claim.
