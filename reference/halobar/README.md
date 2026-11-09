# HALOBAR reference and native acceptance evidence

This is the frozen R1 sketch that the owner approved before native implementation.
It supplies the material/contact shapes and listening comparison, not application
code. `halobar.py` renders the reference; `make_proof.py` creates its audition.
Install the pinned requirements in an isolated Python environment first.

`python-report.json` summarizes the Python harness checks and records the full
report hash; per-clip evidence regenerates with the audition task;
`python-house-probes.json` records the independent house probes on its WAVs.
`baseline-r0-wav-hashes.json` preserves the earlier two-voice reference identity.
No native timing or Kotlin correctness claim follows from those reports.

The native engine is `synth/.../Halobar.kt`, model 1. It uses house noise and
output processing, finite source/tail planning, a real low-marimba decay floor,
and an A3 lower limit for the short xylophone. Consequently the historical low
xylophone F3 reference is not a supported native register. Native source noise,
halo seed and filter arithmetic differ; it is not a bit-exact Python port.

See [HALOBAR](../../docs/HALOBAR.md) for native render commands and boundaries.

`native-validation.json` records the integrated Kotlin test/build results and
measured native timing, separately from the historical Python evidence.

The owner accepted the published native audition on 2026-11-08.
`native-acceptance.json` records the later factory promotion, bounded preparation
cache, current-base checks and separate SPACE/fresh timing. The old validation
file above preserves the initial implementation snapshot.

`native-parity.tsv` freezes 195 Float32 little-endian hashes captured from
`c54f01b44f8295757a78a4de8f44f9c00acb4473` on Temurin 21.0.13. It covers the
153 audition recipes, defaults, 36 seeded control/velocity combinations and
three 8x diagnostic cases. This is same-runtime replay evidence, not a claim
that transcendental implementations are identical on every device.
`NativeReplay.java` reads that table and fails on any sample or length change.
Compile it against the built synth runtime classpath, then run with the table
as its first argument. Add `render` as the second argument to exercise normal
rendering and cache reuse for the 192 4x cases; the three 8x cases always use
the diagnostic entry point. `generateHalobarAudition` independently rebuilds
the 153 native audition recipes, WAVs, phrases and both timing measurements.

## Provisional glockenspiel expansion

`glockenspiel-native-validation.json` records the fourth voice's complete
nine-module JVM gate, Android compilation, measured native audition and
compatibility checks. It does not confer a listening verdict on the new voice.
`glockenspiel-native-parity.tsv` contains the 69 provisional clip fingerprints;
the original `native-parity.tsv` retains the 195 approved reference cases.
Run `NativeReplay <glockenspiel TSV path> render "provisional glockenspiel audition"`
with the synth runtime classpath to replay the new set without labelling it
accepted. See [the glockenspiel guide](../../docs/HALOBAR_GLOCKENSPIEL.md).
