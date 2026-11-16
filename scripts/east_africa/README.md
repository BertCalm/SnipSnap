# Fitted synthesis reference and transfer tools

The accepted Python renderer is `model.py` + `synth.py`, `models.json` and
`calibration.json`. It generates oscillators and noise, without recorded input.
Install `requirements.txt` in a Python environment, then run
`python3 scripts/east_africa/render_reference.py` to replay all 990 variants
and approved grooves into `scripts/east_africa/audio`. These files
preserve the accepted study; do not overwrite them when changing native noise.

`prepare_native.py` copies the authoritative model/calibration into runtime
resources and exports scalar take offsets and filter coefficients. Its output
contains no sampled attack, recorded residual, or waveform table. The JVM uses
its own deterministic noise, so Python and Kotlin PCM are not identical.

`audition_native.py` rebuilds the authored scores from native exported-chain
windows, creates per-kit delivery ZIPs, and optionally measures the port against
the retained Python WAV bank. It preserves the approved score gains, before
additional shelf-pad/playback gain, to isolate the renderer change.

`verify_native.py` audits all 990 exported native PCM windows for peak, DC and
tail behavior and estimates stationary noise-filter alias energy. The JVM
workflow tests separately cover persistence and actual playback/export selection.

`python-checks.json`, `python-evaluation.json` and `PYTHON_DESIGN.txt` are frozen
historical evidence, not evidence for the Kotlin port. Paths in those reports
identify the original workspace study. `calibration-provenance.json` retains
calibration and held-out recording hashes. Re-fitting requires those CC0 source
recordings; ordinary rendering and coefficient preparation do not.

Licenses: `VSCO-LICENSE.txt` and `backwash-VIRTUOSITY-LICENSE.txt`. The fitted
source credits are also documented in `docs/EAST_AFRICA.md`. User-provided music
clips were references for arrangement, never runtime kit sources.
