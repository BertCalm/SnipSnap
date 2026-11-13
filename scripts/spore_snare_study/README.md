# Spore snare listening study

Python-only, sample-free render model. Requires NumPy, SciPy and Matplotlib.

Render and test without reference audio:

```sh
OPENBLAS_NUM_THREADS=1 python3 scripts/spore_snare_study/test_model.py
```

To repeat the calibration, supply the 44.1 kHz audio extracted from the owner's
`screen-20261109-121121-1794247871859.mp4`, and the rejected prototype's raw snare WAV:

```sh
OPENBLAS_NUM_THREADS=1 python3 scripts/spore_snare_study/calibrate.py \
  --reference /path/to/live-clip.wav --previous /path/to/new-1.wav \
  --output /path/to/study-output
OPENBLAS_NUM_THREADS=1 python3 scripts/spore_snare_study/audition.py /path/to/study-output
python3 scripts/spore_snare_study/validate.py /path/to/study-output
node scripts/spore_snare_study/test_page.mjs /path/to/study-output
```

Copy the design spec to `study.md` in the output before validating or staging.
The committed `model.json` is the tested coefficient table. Recalibration writes a
new table in the output; review and replace the committed model deliberately.
The calibration reference is not committed and is never needed by `render()`.
The Site reference excerpt is excluded from the synthetic WAV ZIP.

This is not a native kit or an MPC/SFZ mapping. No existing synthesis engine is
replaced. See the [design and evidence](../../docs/superpowers/specs/2026-11-13-spore-snare-reference-reset.md).
