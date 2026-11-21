"""Score the steel voices against one reference loop each, by resynthesis (resynth.py).
Lower is closer. Python harness, not Kotlin."""
import os, sys, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
import resynth as rs

PICK = {"HANDPAN": 0, "TONGUE": 2, "TANK": 5}          # rows of resynth.CASES

def score(voice, seconds=4.0, verbose=True):
    pat, v, root, mode, band, _ = rs.CASES[PICK[voice]]
    ex, mix, notes, det, layout = rs.render_case(pat, v, root, mode, band, seconds)
    t = [n['t'] for n in notes]
    a, b = rs.measures(ex, t), rs.measures(mix, t)
    c, La = rs.third_octave_db(ex); _, Lb = rs.third_octave_db(mix)
    sel = (c >= 150) & (c <= 6500)
    spec = float(np.sqrt(np.mean((Lb - La)[sel] ** 2)))
    out = {"spectrum_rms_dB": round(spec, 1)}
    for k in ("attack_above_2k_dB", "ring_300ms_dB", "level_before_next_hit_dB", "centroid_Hz"):
        if a[k] is not None and b[k] is not None:
            out[k] = (round(a[k], 1), round(b[k], 1))
    if verbose:
        print(voice, out, '| third-octave diff', ' '.join(f"{int(x)}:{y:+.0f}" for x, y in zip(c, Lb - La) if x in c[::2]), flush=True)
    return out
