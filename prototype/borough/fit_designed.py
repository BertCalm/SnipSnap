"""Fit designed.py's drums to a loop's fine hit measurements (hits.json, from hits.py). Python harness.

Score (dB): RMS over the time-window x band grid, both sides floored at -40 dB under their own peak
(bleed under that does not count); the kick's sub and low bands in 0-5 ms are skipped (too short a
window to read them); the snare's sub and low bands are skipped everywhere (the kick bleeds there).
Plus, for the kick, 1.5 dB per semitone of pitch error at 30, 60 and 100 ms; for the snare, 1.5 dB per
semitone of body pitch error.

    python3 fit_designed.py hits.json designed_fit.json loop:role [loop:role ...]
"""
from __future__ import annotations
import json, math, os, sys, time
import numpy as np
from scipy.optimize import differential_evolution
import designed as D
import hits as H

FLOOR = -40.0
W = ['0-5ms', '5-20', '20-50', '50-100', '100-200', '200-300']
B = list(H.BANDS)


def grid(m):
    return np.array([[m['db'][w][b] for b in B] for w in W])


def score(target, pad, role):
    t = np.maximum(grid(target), FLOOR); p = np.maximum(grid(pad), FLOOR)
    mask = np.ones_like(t, bool)
    if role == 'kick':
        mask[0, :2] = False
    else:
        mask[:, :2] = False
    err = math.sqrt(float(np.mean((t - p)[mask] ** 2)))
    semis = lambda a, b: abs(12 * math.log2(max(a, 1) / max(b, 1)))
    if role == 'kick':
        err += 1.5 * np.mean([semis(target['pitch_hz'][k], pad['pitch_hz'][k]) for k in ('30ms', '60ms', '100ms')])
    else:
        err += 1.5 * semis(target['body_hz'], pad['body_hz'])
    return err


class Loss:
    def __init__(self, role, target):
        sp = D.SPACES[role]; self.role, self.target, self.keys = role, target, list(sp)
        self.lo = np.array([sp[k][0] for k in self.keys], float); self.hi = np.array([sp[k][1] for k in self.keys], float)
        self.logk = np.array([k in D.LOGK for k in self.keys])

    def decode(self, u):
        lo, hi = self.lo, self.hi
        v = np.where(self.logk, np.exp(np.log(np.maximum(lo, 1e-9)) + u * (np.log(hi) - np.log(np.maximum(lo, 1e-9)))), lo + u * (hi - lo))
        return dict(zip(self.keys, v.tolist()))

    def __call__(self, u):
        try:
            x = D.RENDER[self.role](self.decode(u), 1.0, np.random.default_rng(5))
            return score(self.target, H.measure_hit(x), self.role)
        except Exception:
            return 99.0


def fit(role, target, iters, workers):
    loss = Loss(role, target)
    t0 = time.time()
    r = differential_evolution(loss, [(0, 1)] * len(loss.keys), popsize=8, maxiter=iters, tol=1e-4, seed=11,
                               polish=False, init='sobol', updating='deferred', workers=workers)
    return dict(params={k: round(v, 4) for k, v in loss.decode(r.x).items()}, score_db=round(float(r.fun), 2),
                evals=int(r.nfev), seconds=round(time.time() - t0, 1))


if __name__ == '__main__':
    hits_path, out_path, jobs = sys.argv[1], sys.argv[2], sys.argv[3:]
    hits = json.load(open(hits_path))
    try:
        out = json.load(open(out_path))
    except FileNotFoundError:
        out = {}
    for job in jobs:
        loop, role = job.split(':')
        if role in out.get(loop, {}):
            continue
        res = fit(role, hits[loop][role], int(os.environ.get('BOROUGH_ITER', 30)), int(os.environ.get('BOROUGH_WORKERS', 4)))
        out.setdefault(loop, {})[role] = res
        print(f'{loop:24s} {role:5s} score {res["score_db"]:6.2f} dB  {res["seconds"]}s', flush=True)
        json.dump(out, open(out_path, 'w'), indent=1)
