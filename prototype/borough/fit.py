"""Fit voices.py parameters to the measured descriptors in targets.json. Python harness.

Reads only numbers (targets.json, written by extract.py); renders candidates from oscillators
and seeded noise; scores them with the same third-octave band-envelope descriptor. Writes
fitted.json: per loop and role, the parameters and the residual in dB RMS.

    python3 fit.py targets.json fitted.json [loop ...]
"""
from __future__ import annotations
import json, sys, time
from multiprocessing import Pool
import numpy as np
from scipy.optimize import differential_evolution
import extract as X
import voices as V

FLOOR = -50.0


def masks(target, role):
    t = np.array(target['db'])
    bands = np.array(target['bands'])
    times = np.array(X.TIMES)
    tmax = float(np.clip(target['gap_s'], 0.06, 0.6))
    tm = times <= tmax + 1e-9
    return t, bands, tm, tmax


def score(db_c, t, bands, tm):
    a = t[bands][:, tm]; b = db_c[bands][:, tm]
    a = np.maximum(a - a.max(), FLOOR); b = np.maximum(b - b.max(), FLOOR)
    return float(np.sqrt(np.mean((a - b) ** 2)))


def render_db(role, params, seconds):
    x = V.RENDER[role](params, 1.0, V.rng_for('fit', role), seconds=seconds)
    return X.to_db(X.bank_env(x), floor=-80)


def fit_one(job):
    loop, role, target = job
    t, bands, tm, tmax = masks(target, role)
    space = V.SPACES[role]
    keys = list(space)
    lo = np.array([space[k][0] for k in keys]); hi = np.array([space[k][1] for k in keys])
    # log-scale frequency and time parameters so the search spends equal effort per octave
    logk = np.array([k.endswith(('_hz', '_ms')) or k in ('f0', 'f1', 'lp') for k in keys])
    def decode(u):
        v = np.where(logk, np.exp(np.log(lo) + u * (np.log(hi) - np.log(lo))), lo + u * (hi - lo))
        return dict(zip(keys, v.tolist()))
    def loss(u):
        try:
            return score(render_db(role, decode(u), tmax + .06), t, bands, tm)
        except Exception:
            return 99.0
    t0 = time.time()
    r = differential_evolution(loss, [(0, 1)] * len(keys), popsize=6, maxiter=36, tol=1e-4, seed=7,
                               polish=False, init='sobol', updating='immediate')
    p = decode(r.x)
    return loop, role, dict(params={k: round(v, 4) for k, v in p.items()}, residual_db=round(float(r.fun), 3),
                            evals=int(r.nfev), seconds=round(time.time() - t0, 1), occurrences=target['n'])


if __name__ == '__main__':
    targets = json.load(open(sys.argv[1]))
    out_path = sys.argv[2]
    only = set(sys.argv[3:])
    try:
        fitted = json.load(open(out_path))
    except FileNotFoundError:
        fitted = {}
    jobs = []
    for loop, r in targets['loops'].items():
        if only and loop not in only:
            continue
        for role in ('kick', 'snare', 'hat'):
            if r.get(role):
                jobs.append((loop, role, r[role]))
    with Pool(4) as pool:
        for loop, role, res in pool.imap_unordered(fit_one, jobs):
            fitted.setdefault(loop, {})[role] = res
            print(f'{loop:26s} {role:6s} residual {res["residual_db"]:5.2f} dB  {res["seconds"]}s', flush=True)
            json.dump(fitted, open(out_path, 'w'), indent=1)
