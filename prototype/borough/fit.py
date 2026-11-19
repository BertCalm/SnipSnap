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
import importlib, os
V = importlib.import_module(os.environ.get('BOROUGH_MODEL', 'voices'))   # 'acoustic' from round 1

FLOOR = -50.0
# A role fitted against another role's descriptor: the ride is scored on the loop's cymbal ('hat')
# envelope. BOROUGH_ROLES picks the roles to fit (default kick, snare, hat).
ROLE_TARGET = {'ride': 'hat'}
ROLES = tuple(os.environ.get('BOROUGH_ROLES', 'kick,snare,hat').split(','))


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


class _Loss:
    """The fit's loss as a picklable object, so one slow fit can spread over cores (BOROUGH_WORKERS)."""
    def __init__(self, role, keys, lo, hi, logk, t, bands, tm, tmax):
        self.__dict__.update(role=role, keys=keys, lo=lo, hi=hi, logk=logk, t=t, bands=bands, tm=tm, tmax=tmax)

    def decode(self, u):
        lo, hi = self.lo, self.hi
        v = np.where(self.logk, np.exp(np.log(lo) + u * (np.log(hi) - np.log(lo))), lo + u * (hi - lo))
        return dict(zip(self.keys, v.tolist()))

    def __call__(self, u):
        try:
            return score(render_db(self.role, self.decode(u), self.tmax + .06), self.t, self.bands, self.tm)
        except Exception:
            return 99.0


def fit_one(job):
    loop, role, target = job
    t, bands, tm, tmax = masks(target, role)
    space = V.SPACES[role]
    keys = list(space)
    lo = np.array([space[k][0] for k in keys]); hi = np.array([space[k][1] for k in keys])
    # log-scale frequency and time parameters so the search spends equal effort per octave
    logk = np.array([k in getattr(V, 'LOGK', set()) or k.endswith(('_hz', '_ms')) or k in ('f0', 'f1', 'lp') for k in keys])
    loss = _Loss(role, keys, lo, hi, logk, t, bands, tm, tmax)
    workers = int(os.environ.get('BOROUGH_WORKERS', 1))
    t0 = time.time()
    r = differential_evolution(loss, [(0, 1)] * len(keys), popsize=6, maxiter=int(os.environ.get('BOROUGH_ITER', 36)), tol=1e-4, seed=7,
                               polish=False, init='sobol', updating='immediate' if workers == 1 else 'deferred', workers=workers)
    p = loss.decode(r.x)
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
        for role in ROLES:
            tgt = r.get(ROLE_TARGET.get(role, role))
            if tgt and role not in fitted.get(loop, {}):   # resume: skip finished fits
                jobs.append((loop, role, tgt))
    # BOROUGH_WORKERS > 1: jobs one at a time, each spread over that many cores (one slow fit)
    serial = int(os.environ.get('BOROUGH_WORKERS', 1)) > 1
    with Pool(1 if serial else 4) as pool:
        for loop, role, res in (map(fit_one, jobs) if serial else pool.imap_unordered(fit_one, jobs)):
            fitted.setdefault(loop, {})[role] = res
            print(f'{loop:26s} {role:6s} residual {res["residual_db"]:5.2f} dB  {res["seconds"]}s', flush=True)
            json.dump(fitted, open(out_path, 'w'), indent=1)
