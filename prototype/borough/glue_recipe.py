"""BOROUGH round 3.1: each kit's live glue, set so the app's native bus (app/src/main/cpp/GlueBus.h) plays what the
owner picked. Python harness, not Kotlin.

Owner: "C for all except rides and rims. It blows out the snare and chokes the ride" (2026-11-21). C (fuller.py) is
the round 2 glue with tape-style saturation in front: tanh(g x / m) / tanh(g), m the groove's peak, g driven until
the glued groove's crest met its loop's (at most 6 dB). RIDE AND RIMS keeps the round 2 glue, no saturation.

The preview works relative to each groove's own peak (the compressor's threshold 10 dB under it, the output back to
0.89). The native bus works in absolute dBFS on the live mix, so this pins, per kit, on that kit's demo groove at
the pads' own level (build.groove raw, the app's exported pads with round 3.1's stacks):
  input_trim_db   : the groove's peak to 1.0 (0 dBFS) before the saturator: tanh(g x) / tanh(g) is then the
                    preview's curve, and the compressor's -10 dBFS threshold is the preview's 10 dB under the peak.
  saturation_db   : g in dB, as the owner heard it (fuller.json); null is no saturator.
  limiter_drive_db: the preview's limiter drive, plus its normalisation after the compressor (glue_bus divides by
                    its own peak), minus the preview's final scaling to 0.89 (the native ceiling is -1 dBFS and the
                    bus does not normalise).
The live mix's peak depends on how the pads are played, so the trim is the demo groove's: a calibration, stated.

model() is the native chain's arithmetic in Python (GlueBus.h: one-pole peak detector 10/120 ms, gain from the
curve, makeup x drive, then the limiter: sliding minimum over the 3 ms lookahead + 1 frames, 50 ms release, a 3 ms
box average, the signal delayed 3 ms). The script reports its residual against the preview the owner heard.

    python3 glue_recipe.py <app export dir> [--write]
"""
from __future__ import annotations
import json, math, sys
from pathlib import Path
import numpy as np

import build as B
import listen_app as L
import fuller as F
import extract as X

SATURATION_DB = {'borough-stoop': 2.7, 'borough-cellar': 0.0, 'borough-cranked': 6.0, 'borough-wax': 6.0,
                 'borough-dry': 6.0, 'borough-rims': None}   # fuller.json, rounded as heard; RIMS: none (owner)
CEILING_DBFS = -1.0


def preview(x89, sat_db, drive_db):
    """The chain the owner heard, on a groove at peak 0.89 (build.groove)."""
    if sat_db is not None:
        g = 10 ** (sat_db / 20); m = np.max(np.abs(x89))
        x89 = np.tanh(g * x89 / m) / math.tanh(g)
    return B.bus_preview(x89, drive_db)


def model(x, trim_db, sat_db, thr_db=-10.0, ratio=4.0, makeup_db=0.0, drive_db=0.0, ceiling_dbfs=CEILING_DBFS):
    """GlueBus.h's arithmetic, mono, on the live mix x, with the input trim and saturator round 3.1 adds in front."""
    sr = B.SR
    x = x * 10 ** (trim_db / 20)
    if sat_db is not None:
        g = 10 ** (sat_db / 20); x = np.tanh(g * x) / math.tanh(g)
    a_at = math.exp(-1 / (sr * .010)); a_re = math.exp(-1 / (sr * .120))
    thr = 10 ** (thr_db / 20); k = 10 ** ((makeup_db + drive_db) / 20); ceil = 10 ** (ceiling_dbfs / 20)
    env = 0.0; y = np.empty_like(x)
    for i, l in enumerate(np.abs(x)):
        env = a_at * env + (1 - a_at) * l if l > env else a_re * env + (1 - a_re) * l
        y[i] = x[i] * ((env / thr) ** (1 / ratio - 1) if env > thr else 1.0) * k
    from scipy.ndimage import minimum_filter1d
    La = max(1, round(sr * .003))
    need = np.minimum(1.0, ceil / np.maximum(np.abs(y), 1e-30))
    # sliding minimum over the last La + 1 frames (causal)
    wmin = minimum_filter1d(need, La + 1, origin=(La + 1) // 2 - (0 if (La + 1) % 2 else 1))
    rel = 1 - math.exp(-1 / (sr * .050)); held = 1.0; h = np.empty_like(need)
    for i, w in enumerate(wmin):
        held = min(w, held + (1 - held) * rel); h[i] = held
    box = np.convolve(h, np.ones(La) / La)[:len(h)]
    d = np.concatenate([np.zeros(La), y[:-La]])
    return np.clip(d * box, -ceil, ceil)


def main(app_dir, write=False):
    app_dir = Path(app_dir)
    path = Path(L.RECIPES); recipes = json.load(open(path))
    stacks = {s['id']: s for s in B.STACKS}
    out = {}
    for kit in B.K.KITS:
        r = next(k for k in recipes['kits'] if k['id'] == kit['id'])
        st = stacks[r['default_stack']]
        bank = [L.app_pad(app_dir / kit['id'], s) for s in range(1, 13)]
        sbank = [L.app_pad(app_dir / kit['id'], s) for s in range(13, 17)]
        raw = B.groove(kit, bank, st, sbank, raw=True)
        peak = float(np.max(np.abs(raw)))
        sat = SATURATION_DB[kit['id']]
        drive = r['glue_limiter_drive_db']
        x89 = raw / peak * .89
        heard = preview(x89, sat, drive)
        # the preview's normalisation after its compressor (glue_bus divides by its own peak)
        s_in = x89 if sat is None else np.tanh(10 ** (sat / 20) * x89 / .89) / math.tanh(10 ** (sat / 20))
        thr = np.max(np.abs(s_in)) * 10 ** (-10 / 20)
        a_at = math.exp(-1 / (B.SR * .010)); a_re = math.exp(-1 / (B.SR * .120)); e = 0.0; c = np.empty_like(s_in)
        for i, l in enumerate(np.abs(s_in)):
            e = a_at * e + (1 - a_at) * l if l > e else a_re * e + (1 - a_re) * l
            c[i] = s_in[i] * max(e / thr, 1.0) ** (1 / 4 - 1)
        norm_db = -20 * math.log10(np.max(np.abs(c)) / np.max(np.abs(s_in)))
        trim_db = -20 * math.log10(peak)
        limiter_db = drive + norm_db + CEILING_DBFS          # the ceiling: limit at 0.891 what the preview limited at 1 then scaled
        live = model(raw, trim_db, sat, drive_db=limiter_db)
        n = min(len(live) - round(B.SR * .003), len(heard))
        lv = live[round(B.SR * .003):][:n]; hv = heard[:n]
        g = float(np.dot(hv, lv) / np.dot(lv, lv))
        resid = 20 * math.log10(np.sqrt(np.mean((hv - g * lv) ** 2)) / np.sqrt(np.mean(hv ** 2)))
        out[kit['id']] = dict(input_trim_db=round(trim_db, 2), saturation_db=sat, threshold_dbfs=-10.0, ratio=4.0, makeup_db=0.0,
                              limiter_drive_db=round(limiter_db, 2), ceiling_dbfs=CEILING_DBFS)
        print(kit['id'], out[kit['id']], f"| demo peak {peak:.3f}, preview normalisation {norm_db:.2f} dB | model vs heard: "
              f"residual {resid:.1f} dB, level {20*math.log10(g):+.2f} dB, crest {X.crest_db(lv[:int(10*B.SR)]):.1f} vs {X.crest_db(hv[:int(10*B.SR)]):.1f}", flush=True)
    if write:
        for k in recipes['kits']:
            k['glue'] = out[k['id']]
        recipes['rules']['glue']['live'] = ('per kit: input trim -> saturation tanh(g x)/tanh(g), g = 10^(saturation_db/20), '
                                            'null for none -> the compressor and limiter above, makeup x limiter drive (glue_recipe.py)')
        json.dump(recipes, open(path, 'w'), indent=1)
        print('wrote', path)


if __name__ == '__main__':
    main(sys.argv[1], '--write' in sys.argv)
