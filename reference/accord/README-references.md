# Portable Python references

These are frozen research references for the Accord handoff. They are not a Kotlin implementation or a claim about the current SnipSnap APIs. The earlier repository inspection used commit `4e36a2bf8cf8bfb07dba35379558b54429b5d73d`; implementation must inspect its actual checkout again.

`SOURCE_MANIFEST.json` records every copied source path, original SHA-256, bundled SHA-256 and exact portability edit. The original files were not modified. Historical JSON reports remain verbatim, so `/workspace/...` strings inside them describe their original run and are not runtime dependencies. Their recorded wall times describe those historical Python runs.

## Contents

- `reference/mechanics/`: the solver and validation harness, shared Python DSP/probes, the selected gathered trajectory and exact crossing events, and its numerical report.
- `reference/musicality/`: the three-voice prototype, its report, and the three original WAV references, stored once as `brush.wav`, `wood.wav` and `tine.wav`.
- `reference/refinement/`: continuous note/velocity controls, the 36-case renderer and historical control/audio/pitch reports. The 36 individual refinement WAVs are not shipped here; that renderer can regenerate them in a scratch copy.
- `reference/tine-options/`: the one-axis TINE renderer, its report and independent QA, plus `soft_attack.wav`, `bright_edge.wav` and `long_ring.wav`.

The separate TINE `reference.wav` is deliberately omitted: it was byte-identical to `reference/musicality/tine.wav`. Bundled TINE QA resolves that missing duplicate to the original and checks its recorded SHA. Running the TINE renderer in a scratch copy creates `reference.wav` there as an output.

The original WAVs are mono, 44.1 kHz, 16-bit PCM, 8.9 seconds. Each new TINE option is 10.2 seconds: eight seconds of excitation plus 2.2 seconds of release. They retain the original C3, normal-touch mechanical event history, with four seconds of mechanical preroll and zero acoustic preroll. No HOLD loop is provided.

## Environment and quick verification

Run commands from the extracted bundle root. Python 3.12 is recommended; collection and portability checks used Python 3.12.14. `requirements.txt` pins the installed numerical library versions used for collection. Those pins do not guarantee byte-identical regeneration across every platform.

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements.txt
python verify-references.py
```

The quick verifier checks snapshot hashes, all six WAV hashes and headers, portable imports, actual relative fixture loading, the 528 selected exact contact events, and the deduplicated TINE reference. It does not integrate mechanics or render audio. It works independently of the original `/workspace` directories.

## Run an experiment without changing the frozen references

Every renderer writes beside its script. First make a fresh scratch copy. The following commands create a new scratch directory and intentionally fail if it already exists:

```bash
python -c 'import shutil; shutil.copytree("reference", "scratch/reference")'
```

Regenerate the three one-axis TINE options and run their compact independent audio checks:

```bash
python scratch/reference/tine-options/render.py
python scratch/reference/tine-options/qa.py
```

Other optional commands, each run against a fresh scratch copy when preserving a previous result matters:

```bash
python scratch/reference/musicality/render.py
python scratch/reference/refinement/controls.py
python scratch/reference/refinement/render.py
python scratch/reference/mechanics/validate.py
```

The musicality command regenerates three C3 voices and BRUSH C2/C4 examples. The refinement renderer generates 36 samples under its own `samples/` directory. The controls command checks the proposed continuous laws. The mechanical validation command performs isolated, fixed/mobile, energy/work, momentum and convergence probes and regenerates trajectory/report files; it is substantially more work than the quick verifier. Running `mechanics.py` directly starts the exploratory parameter scout, so it is not needed for normal audition reproduction.

## Interpretation constraints

- Mechanics use the selected NumPy-seeded sinusoidal initialization, not production SnipSnap RNG or measured limit-cycle phase initialization. One curated seed does not establish robustness across all patches.
- The Python modal/output filters and RMS leveling approximate the project pipeline. Regeneration here does not replace Kotlin tests, runtime profiling or listening.
- The musicality files use whole-file RMS matching. Refinement fixes a calibration gain per voice/note across its three velocities. The later TINE options instead match one constant gain over the active first eight seconds; their release does not influence that match. None uses per-hit normalization or fast gain control.
- The TINE options keep the original `5.96` upper-mode ratio. Soft attack changes contact width, bright edge changes upper-mode gains, and long ring triples modal decay times. Their reports establish numerical contrasts, not a listener's preference or perceived-loudness equivalence.
- Root mode centers and active-contact pitch estimates are different measurements. Short-window pitch excursions remain disclosed in the reports. No audio was heard by the analysis tools.

These scripts read local bundled data and write local experiment outputs; they do not contact external services.
