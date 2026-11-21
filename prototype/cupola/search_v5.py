"""Fit v5 across the register, mapping B (fundamental = key - 12), to the owner's isolated hits
banded by loudest partial. Also penalise a render whose loudest partial is not the key."""
import os
import sys, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
import cupola_proto as cp, tubes_v5 as tv, register_probe as rp
tv.install()
T = {'lo': dict(half=-19.2, x1_5=-15.9, x2=-16.7, x3=-34.1, off_harmonic_dB=-6.85, attack_above_2k_dB=-33.55),
     'mid': dict(half=-28.0, x1_5=-17.65, x2=-19.25, x3=-35.25, off_harmonic_dB=-5.35, attack_above_2k_dB=-29.05),
     'hi': dict(half=-15.4, x1_5=-13.6, x2=-13.1, x3=-28.5, off_harmonic_dB=-3.5, attack_above_2k_dB=-23.0)}
KEYS = {'lo': (55, 60), 'mid': (64, 69), 'hi': (74, 79)}
W = dict(half=1.0, x1_5=1.0, x2=1.0, x3=.6, off_harmonic_dB=.8, attack_above_2k_dB=.6)
SPACE = dict(END_C=(0.0, .9), X=(.02, .3), CONTACT_MS=(.3, 6.0), RISE_MS=(.05, 1.5), BREATH=(0.0, 1.2), RAD=(.5, 2.5), RADC_HZ=(300.0, 6000.0), DK=(.3, 1.2))
def score(p):
    tv.P.update(p); err = 0.0; rows = {}
    for band, keys in KEYS.items():
        for key in keys:
            y = cp.finish(cp.render('TUBES', midi=key - 12, take=0, seconds=.35)[0])
            d = rp.describe(y)
            octave = abs(1200 * np.log2(d['L'] / cp.hz(key)))
            err += 400.0 if octave > 100 else 0.0                    # the key must be the loudest partial
            for k, w in W.items():
                err += w * (max(-60.0, d[k]) - T[band][k]) ** 2
            rows[key] = {k: d[k] for k in ('L', *W)}
    return err / 6, rows
rng = np.random.default_rng(17); best = (1e18, None, None)
for i in range(100):
    p = {k: float(rng.uniform(*r)) for k, r in SPACE.items()} if best[1] is None or rng.uniform() < .35 else \
        {k: float(np.clip(best[1][k] + rng.normal(0, .1 * (r[1] - r[0])), *r)) for k, r in SPACE.items()}
    e, rows = score(p)
    if e < best[0]:
        best = (e, p, rows); print(i, round(e, 1), flush=True)
print(json.dumps({'rms_err': round(float(np.sqrt(best[0] / sum(W.values()))), 2), 'params': best[1], 'rows': best[2]}, default=float))
