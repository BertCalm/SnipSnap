# Tremor ROLL listening snapshots

Start with the [handoff](../../docs/handoffs/2026-11-07-tremor-roll.md).
R3 is a listening prototype awaiting Josh's verdict. These sources are outside
the production Gradle source sets and do not replace any saved app sound.

With JDK 17 selected and NumPy available:

```bash
python3 experiments/tremor-roll/reproduce.py --baseline .
python3 experiments/tremor-roll/verify.py
```

The renderer checks the pinned baseline and all six original source hashes,
builds the baseline JARs, compiles the prototypes with Kotlin 2.0.21, and renders
15 comparison/reference clips plus 54 numerical cases. The verifier requires
exact published WAV/native-float hashes and checks pitch, levels, rails,
determinism, mechanical traces and the held regions.

Use `--baseline PATH` for a detached checkout of the recorded baseline when
production sources have moved. `--java-home`, `--gradle-user-home` and
`--skip-baseline-build` support existing toolchains and caches. Generated output
defaults to the ignored `build/reproduced/` directory.

`evidence/` contains the original verdicts, manifests and archived reports.
Historical workspace paths in those reports are provenance, not build inputs.
The scripts do not rerun the archived browser or independent grain-identity audit.
The complete published Site is committed separately; its identity and source
commit are recorded in the handoff.
