# KERF prototype: synthetic chops

Python harness for the design in
[`docs/superpowers/specs/2026-11-19-kerf-synthetic-chops-design.md`](../../docs/superpowers/specs/2026-11-19-kerf-synthetic-chops-design.md).
KERF is a working name. Nothing here is Kotlin and nothing here is an engine yet.

It chops a drum loop into hits, fits a small synthesis patch to each hit, and renders the
patch back with the two things that make a chop sound chopped: the previous hit bleeding
underneath, and cut points that are a little off.

## What is in this folder

| File | What it does |
|---|---|
| `kit_patches.json` | Four 16-pad kits as numbers only: each pad's patch plus its two neighbours' patches. No audio. |
| `render_kits.py` | Renders those kits to WAV. Needs nothing but this folder. |
| `resynth.py` | The model: `fit`, `refine`, `render`, `chopped`, `compare`. Start reading here. |
| `chop.py` | Onset detection, slicing and hit labelling for a set of loops. |
| `run_fit.py` | Fits every chop and prints the error table. |
| `refine_all.py` | Re-runs only the colour and tick stages on existing fits. |
| `kits.py` | Picks 16 pads per kit and writes the A/B audition page. |

The source loops are not in the repository. `chop.py`, `run_fit.py`, `refine_all.py` and
`kits.py` need them; `render_kits.py` does not.

## Run it

```sh
pip install numpy scipy
python3 prototype/kerf/render_kits.py /absolute/output            # the kits as they were approved
python3 prototype/kerf/render_kits.py /absolute/output --bleed 0 --slop 0   # clean twins
```

The first command prints `sha256 of all PCM a193420423dac404` (numpy 2.5.3, scipy 1.18.1,
2026-11-19). The same patches give the same bytes on every run.

To fit your own loops (24-bit or 16-bit WAV, any channel count):

```sh
python3 prototype/kerf/chop.py OUT loop1.wav loop2.wav ...
python3 prototype/kerf/run_fit.py OUT       # about 0.5 s per hit
python3 prototype/kerf/kits.py OUT          # needs ffmpeg for the audition page
```

## What a patch holds

- `partials`: sine oscillators with a pitch glide, a start time and a decay (the drum's body, and any steady tone).
- `bands`: 19 noise bands. Each has a fast crack, a slower ring, a floor that never dies inside the slice, and `layers` for later hits inside the same slice.
- `colour`: up to 48 narrow resonances the noise is passed through (what makes a cymbal ring instead of hiss).
- `ticks`: bursts of about 1 ms at exact samples (the stick landing).
- `seconds`, `seed`, `events`.

## Status

Heard once by the owner on 2026-11-18: "Seems closer. I think I can live with it." Every
number in the spec is from this harness, not from Kotlin.
