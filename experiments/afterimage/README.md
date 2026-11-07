# Afterimage research prototype

Reproducible Python reference for a chorused subtractive synth with frozen
bass/pluck sustain and captured-history pad memory. R2's memory split is the
accepted baseline; optional R3 MOTION remains unapproved by listening.

Read the [implementation handoff](../../docs/handoffs/2026-11-07-afterimage.md)
before changing the DSP or interpreting the evidence.

From the repository root, using Python 3.12:

```sh
python3 -m venv .venv-afterimage
.venv-afterimage/bin/pip install -r experiments/afterimage/requirements.txt
.venv-afterimage/bin/python experiments/afterimage/audition.py
.venv-afterimage/bin/python experiments/afterimage/verify_audio.py
python3 -m http.server 4173 --bind 127.0.0.1 --directory testkit/afterimage-audition
```

Open <http://localhost:4173/listen.html>. For a quick listen, open
`testkit/afterimage-audition/quick-tour.wav`. A custom output directory can be
passed as the sole argument to both Python commands. Do not run with `python -O`:
the audition uses assertions as its validation gate.

The generator produces 24 four-second comparisons, a six-clip quick tour,
`listen.html` with embedded audio, fresh measurements, and control defaults.
Outputs are ignored by Git. No remote Page, temporary download, original cloud
workspace, Android SDK, or Kotlin build is required to reproduce them.

| File | Purpose |
| --- | --- |
| `engine.py` | R3 source, memory readers, optional motion, normalized patch controls |
| `baseline.py` | Preserved R2 DSP for exact comparisons; do not silently update |
| `audition.py` | Renders audio and runs numerical/behavioral assertions |
| `verify_audio.py` | Checks regenerated WAV bytes against the original R3 manifest |
| `snipkit.py`, `probes.py` | Preserved helpers from the supplied Luthier prototype toolkit |
| `evidence/r2-report.json` | Frozen-memory diagnostic measurements |
| `evidence/r3-report.json` | Original R3 numerical results and machine-specific timings |
| `evidence/r3-controls.json` | Proposed macro defaults |
| `evidence/r3-wav-manifest.json` | Original PCM hashes and frame counts |

`render_patch(...)` returns **176.4 kHz internal audio**. Apply `condition()` and
then `level()` before writing a 44.1 kHz audition WAV. The level operation is an
RMS proxy, not SnipSnap's perceived loudness measurement.

All current outputs are finite one-shots. Seamless HOLD, hardware fidelity,
complete alias validation, phone performance, and production integration remain
open. The copied helpers are research stand-ins, not Kotlin implementations.
