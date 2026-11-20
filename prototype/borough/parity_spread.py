"""BOROUGH round 3: how far Python's own takes of each accepted pad lie from the parity reference.
Python harness, numbers only.

The Kotlin port draws its noise from Kotlin's Random, so a Kotlin pad is another realisation of the same
recipe, never a copy of the Python one (EMBER's port, round 1). The fair yardstick for "close enough" is
therefore how far Python's own realisations of the same pad lie from the one measured in
`borough-parity.json`. This renders each pad's hardest zone, the recipe itself (take 0, no jitter), with
fresh seeds, scores each against the reference with fit_designed's score (grid RMS, -40 dB floor, the
kick and snare masks; pitch counted separately here) and writes the spread.

    BOROUGH_MODEL=acoustic python3 parity_spread.py ../../synth/src/main/resources/borough/v1/recipes.json \
        ../../synth/src/test/resources/borough-parity.json ../../synth/src/test/resources/borough-parity-spread.json 8   # ~1 min, 4 cores

It first re-renders the reference take itself (seed and all) and checks it reproduces the parity file,
so the spread is measured on the same pipeline the owner heard.
"""
from __future__ import annotations
import json, math, os, sys
from concurrent.futures import ProcessPoolExecutor
import numpy as np
from scipy import signal

os.environ.setdefault('BOROUGH_MODEL', 'acoustic')
import acoustic as A   # noqa: E402
import build as BLD    # noqa: E402
import hits as H       # noqa: E402
import voices as V     # noqa: E402

SR = A.SR
W = ['0-5ms', '5-20', '20-50', '50-100', '100-200', '200-300']
B = list(H.BANDS)


def grid_of(m):
    return np.array([[m['db'][w][b] for b in B] for w in W])


def grid_rms(ref, pad, role):
    t = np.maximum(grid_of(ref), -40.0); p = np.maximum(grid_of(pad), -40.0)
    mask = np.ones_like(t, bool)
    if role == 'snare':
        mask[:, :2] = False
    else:
        mask[0, :2] = False
    return math.sqrt(float(np.mean((t - p)[mask] ** 2)))


def semis(a, b):
    return 12 * math.log2(max(a, 1) / max(b, 1))


def role_of(kind):
    return 'kick' if kind == 'dkick' else 'snare' if kind in ('dsnare', 'dghost') else 'other'


def render_drum(kind, p, extra, rng):
    return BLD.render_pad(kind, p, 1.0, rng, extra)


def render_stack(pad, rng):
    take = rng.integers(1 << 20)
    m = dict(pad['macros'], TUNE=pad['tune'], DISTANCE=A.STACK_CHAIN['distance'], WARMTH=0)
    y = A.E.render(pad['voice'], m, 1.0, int(take))
    if pad['resample']:
        y = signal.resample(y, max(64, int(len(y) / pad['resample_ratio'])))
    return A.end(A.record(y, A.STACK_CHAIN, 1.0), len(y) / SR)


def job(args):
    key, label, kind, p, extra, stack_pad, gain, ref, seeds = args
    def one(rng):
        y = render_stack(stack_pad, rng) if stack_pad else render_drum(kind, p, extra, rng)
        return y * .89 * gain
    y0 = one(V.rng_for(label, kind, 2, 0))
    m0 = H.measure_hit(y0)
    role = role_of(kind)
    same = dict(grid_db=round(grid_rms(ref, m0, role), 3), seconds=round(len(y0) / SR, 3), peak=round(float(np.max(np.abs(y0))), 4))
    grid, k30, body = [], [], []
    for s in range(seeds):
        m = H.measure_hit(one(V.rng_for(label, kind, 2, 'spread', s)))
        grid.append(round(grid_rms(ref, m, role), 3)); k30.append(m['pitch_hz']['30ms']); body.append(m['body_hz'])
    return key, dict(role=role, reproduced_grid_db=same['grid_db'], grid_db=grid, kick30_hz=k30, body_hz=body)


def main(recipes_path, parity_path, out_path, seeds=4):
    rec = json.load(open(recipes_path)); par = json.load(open(parity_path))['pads']
    jobs = []
    slug = lambda s: s.lower().replace(' ', '-')
    for kit in rec['kits']:
        for pad in kit['pads']:
            key = f"{kit['id']}/A{pad['pad']:02d}-{slug(pad['label'])}"
            gain = 10 ** ((kit['kick_trim_db'] if pad['pad'] in (1, 5) else kit['rest_trim_db']) / 20)
            jobs.append((key, f"{kit['id']}-{pad['label']}", pad['kind'], pad['params'], pad['extra'], None, gain, par[key], seeds))
    for st in rec['stacks']:
        for pad in st['pads']:
            key = f"{st['id']}/A{pad['pad']:02d}-{slug(pad['label'])}"
            jobs.append((key, f"{st['id']}-{pad['label']}", 'stack', None, {}, pad, 1.0, par[key], seeds))
    out = {}
    with ProcessPoolExecutor(int(os.environ.get('BOROUGH_WORKERS', 4))) as ex:
        for key, r in ex.map(job, jobs):
            out[key] = r
            print(key, r['reproduced_grid_db'], r['grid_db'], flush=True)
    note = ('Python harness (prototype/borough/parity_spread.py): each pad\'s hardest-zone recipe re-rendered with fresh '
            'seeds and scored against borough-parity.json (grid RMS dB, -40 dB floor, fit_designed masks), with the raw '
            'kick pitch at 30 ms and snare body pitch of each. reproduced_grid_db: the reference take itself re-rendered. '
            'Numbers only.')
    with open(out_path, 'w') as f:   # one pad per line: small, and diffable
        f.write('{"note": %s, "seeds": %d, "pads": {\n' % (json.dumps(note), seeds))
        f.write(',\n'.join(f' {json.dumps(k)}: {json.dumps(v)}' for k, v in sorted(out.items())))
        f.write('\n}}\n')


if __name__ == '__main__':
    main(*sys.argv[1:4], *(int(a) for a in sys.argv[4:5]))
