# LORENTZ engineering handoff and implementation

The owner approved the third listening prototype after extending IMPACT and GAP. Implement the finite model-3 source without changing that accepted gesture: falling sweep → collision → permanent capture → steady sub.

The authoritative implementation contract and As built record are in [LORENTZ](../../engines/LORENTZ.md). The independent source equations and sparse reference generator are retained in `scripts/lorentz_reference.py` and `scripts/generate_lorentz_reference.py`; Kotlin tests compare the raw core rather than equating the Python RMS finisher with SnipSnap's actual output stage.

V1 ships FUSE and LORENTZ, five timbral controls plus finite HOLD, explicit saved model/velocity, actual-PCM classification, six source anchors per voice, a sixteen-pad dry kit, and reproducible Kotlin auditions. No samples of original products, real welding simulation, feedback solver, held loop or Android picker integration are included. The picker needs an SDK-backed session.

## As built

The raw source follows Audition 03. The added velocity response reduces contact severity and scales level after conditioning; at velocity one it preserves the reference equations. Recipe model 3 is explicit. Numerical and integration evidence is recorded in the engine document and generator outputs; the owner's approval applies to the Python source, not yet to these Kotlin WAVs.
