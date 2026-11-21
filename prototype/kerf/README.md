# KERF prototype: synthetic chops

Python harness for the design in
[`docs/superpowers/specs/2026-11-19-kerf-synthetic-chops-design.md`](../../docs/superpowers/specs/2026-11-19-kerf-synthetic-chops-design.md).
KERF is a working name. The Kotlin renderer is `synth/.../KerfRender.kt` and the Kotlin fitter is `synth/.../KerfFit.kt`; this folder is the prototype both were ported from and the reference they are checked against.

It chops a drum loop into hits, fits a small synthesis patch to each hit, and renders the
patch back with the two things that make a chop sound chopped: the previous hit bleeding
underneath, and cut points that are a little off.

## What is in this folder

| File | What it does |
|---|---|
| `render_kits.py` | Renders the four kits to WAV from `synth/src/main/resources/kerf/v1/kit_patches.json` (numbers only, no audio; the Kotlin renderer reads the same file). |
| `freeze_reference.py` | Writes the summaries the Kotlin port is tested against (`synth/src/test/resources/kerf/python_reference.json`). |
| `ghost_vs_snare.py` | Compares ghost notes with full snares from the same loop: the evidence for the velocity law. |
| `resynth.py` | The model: `fit`, `refine`, `render`, `chopped`, `compare`. Start reading here. |
| `chop.py` | Onset detection, slicing and hit labelling for a set of loops. |
| `run_fit.py` | Fits every chop and prints the error table. |
| `refine_all.py` | Re-runs only the colour and tick stages on existing fits. |
| `kits.py` | Picks 16 pads per kit and writes the A/B audition page. |
| `genre_bank.py` | Round 5: groups the loops by what they measure and picks four more kits' hits the first bank does not use (`synth/src/main/resources/kerf/v1/genre_patches.json`). |
| `genre_bank_r6.py` | Round 6: eight more kits, five from new loops grouped by measurement and three from the first loops' leftovers (`genre_patches_r6.json`). |

The source loops are not in the repository. `chop.py`, `run_fit.py`, `refine_all.py`,
`kits.py` and `genre_bank.py` need them or their fits; `render_kits.py` does not.

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
