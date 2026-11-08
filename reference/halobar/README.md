# HALOBAR Python reference

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
