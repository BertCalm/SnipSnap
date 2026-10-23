# Skirl Python reference and calibration

These portable sources preserve the Python proofs used before the two-voice Kotlin
milestone. Historical reports retain the state recorded when generated;
[`listening_feedback.json`](listening_feedback.json) records the later owner feedback
without assigning a favorite clip or treating Python approval as Kotlin approval.

`reference/mechanics.py` is the constraint-eliminated cylinder ODE, with its independent
audit, contact-budget proof, modal/audio helpers and audition generator beside it.
`safe_domain.py` calibrates the smaller ROLL/WHIRR domains without modifying that original
reference. `reference_sources.json` records original and copied SHA-256 hashes; only
source-helper import paths and redundant terminal blank lines were adapted for portability
and repository formatting.

Install `requirements.txt` in a suitable Python environment, then run from this directory:

```sh
python3 safe_domain.py --validate
python3 safe_domain.py --augment
python3 safe_domain.py --spot-checks
python3 reference/steady_family_fixture.py
python3 reference/verify_mechanics.py
python3 reference/audit_mechanics.py
python3 reference/contact_budget.py
python3 reference/render_audition.py
```

The final command regenerates earlier Python comparison WAVs and reports under
`reference/`; it uses historical curated settings rather than revised Kotlin defaults.
No WAVs, NPZ histories or generated plots are committed. Measurements may contain
execution-dependent timings. Python audio/output conditioning is not a bit-identical
substitute for Kotlin's house DSP and perceived-loudness stage.

The safe-domain report contains 84 full rolling trajectories and 420 terminal exits.
Finite grids and between-grid sampling do not mathematically prove a continuous control
cube. Kotlin retains validity guards and needs its own output tests and listening review.

The later [coherence experiment](coherence/README.md) records the acoustic revision
that protects low-note tuning. [Native evidence](native/README.md) records the Kotlin
acceptance and additional listening-window measurements separately from these Python proofs.
