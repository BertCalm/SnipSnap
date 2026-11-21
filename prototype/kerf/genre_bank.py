"""Build KERF's second bank: four genre kits from fitted hits the first bank does not use.

Round 5 of docs/superpowers/specs/2026-11-19-kerf-synthetic-chops-design.md. The owner asked for more genre kits, from
fresh hits, with the loops deciding the genres (2026-11-21). Every loop was fitted in round 2 (`run_fit.py`); the
first bank (`kit_patches.json`) uses 63 of the 462 hits. This script groups the loops by what they measure, never by
name, so no loop or pack name reaches the repository:

- RUSH: the fast, bright loops (100 BPM and up, spectral centre at least 700 Hz).
- MURK: the dark loops (spectral centre under 850 Hz; the fast ones are RUSH's first).
- POCKET: the ghost-note loops (at least a quarter of their hits are ghosts).
- CHATTER: the busy, hat-heavy loops (at least 13 hits a bar, or hats over a third of the hits).

Within a kit, hits are picked as `kits.py` picks them (loudest first, then least like those already chosen), only from
hits the first bank does not hold, that fit well (envelope match at least 0.85, band error at most 5 dB) and that last
0.08 to 1.2 s. Each pad keeps its two neighbours in its own loop, for BLEED and SLOP, and its knife draw.

    python3 prototype/kerf/genre_bank.py FITS_DIR            # FITS_DIR holds fits.json from run_fit.py

Writes synth/src/main/resources/kerf/v1/genre_patches.json (fitted numbers only) and prints each kit's make-up.
"""
import sys, os, re, json
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import resynth as rs

# The first bank's picker, copied from kits.py (which runs its whole build on import).
def feat(r): return np.array([np.log2(r['centroid']+50), 3*r['sub'], 3*r['low'], 3*r['mid'], 3*r['pres'], 3*r['air'], np.log2(r['dur']), 0.5*np.log2(r['t60'])])
def pick(rows, n):
    """Loudest first, then whichever remaining hit is least like the ones already chosen (loops repeat hits; this skips the repeats)."""
    if not rows: return []
    rows = sorted(rows, key=lambda r: -r['peak']); got = [rows[0]]
    while len(got) < min(n, len(rows)):
        d = [min(np.linalg.norm(feat(r)-feat(g)) for g in got) for r in rows]; i = int(np.argmax(d))
        if d[i] < 0.05: break
        got.append(rows[i])
    return got

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
R = json.load(open(os.path.join(sys.argv[1], 'fits.json')))
first = json.load(open(os.path.join(ROOT, 'synth/src/main/resources/kerf/v1/kit_patches.json')))
used = {p['patch']['seed'] for k in first for p in first[k]}
BY = {(r['loop'], r['idx']): r for r in R}

def nb(r):
    idxs = sorted(i for (l, i) in BY if l == r['loop']); k = idxs.index(r['idx'])
    return BY[(r['loop'], idxs[k-1])]['patch'], BY[(r['loop'], idxs[(k+1) % len(idxs)])]['patch']

def bpm(loop):
    nums = [int(n) for n in re.findall(r'(?<![0-9])(\d{2,3})(?![0-9])', loop) if 60 <= int(n) <= 180]
    return nums[-1]

def measure(loop):
    rows = [r for r in R if r['loop'] == loop]
    seconds = max(r['start'] + r['dur'] for r in rows); bars = seconds / (4 * 60 / bpm(loop))
    energy = np.array([r['peak'] ** 2 for r in rows])
    return dict(bpm=bpm(loop), centre=float((np.array([r['centroid'] for r in rows]) * energy).sum() / energy.sum()),
                per_bar=len(rows) / bars, ghosts=sum(r['cls'] == 'GHOST' for r in rows) / len(rows),
                hats=sum(r['cls'] in ('HAT', 'HAT_OPEN') for r in rows) / len(rows))

def genre(m):
    if m['bpm'] >= 100 and m['centre'] >= 700: return 'RUSH'
    if m['centre'] < 850: return 'MURK'
    if m['ghosts'] >= 0.25: return 'POCKET'
    if m['per_bar'] >= 13 or m['hats'] > 1 / 3: return 'CHATTER'
    return None

# 14 hits: three kicks, three snares, two ghosts, three hats, an open hat, two stacked or percussion hits
WANT = [('KICK', 3), ('SNARE', 3), ('GHOST', 2), ('HAT', 3), ('HAT_OPEN', 1), ('STACK', 2)]
FILL = ['HAT_OPEN', 'HAT', 'GHOST', 'STACK', 'PERC', 'SNARE', 'KICK']
ORDER = {c: i for i, c in enumerate(['KICK', 'SNARE', 'GHOST', 'HAT', 'HAT_OPEN', 'STACK', 'PERC'])}

def good(r):
    return (r['patch']['seed'] not in used and 0.08 <= r['dur'] <= 1.2
            and r['metrics']['env_corr'] >= 0.85 and r['metrics']['band_err_db'] <= 5.0)

loops = list(dict.fromkeys(r['loop'] for r in R)); groups = {}
for lp in loops:
    m = measure(lp); g = genre(m)
    print(f"loop {loops.index(lp):2d}: {m['bpm']:3d} BPM, centre {m['centre']:5.0f} Hz, {m['per_bar']:4.1f} hits a bar, "
          f"ghosts {m['ghosts']:.2f}, hats {m['hats']:.2f} -> {g}")
    if g: groups.setdefault(g, []).append(lp)

out = {}
for g in ['RUSH', 'POCKET', 'MURK', 'CHATTER']:
    rows = [r for r in R if r['loop'] in groups[g] and good(r)]; pads = []
    for c, n in WANT: pads += pick([r for r in rows if r['cls'] == c], n)
    for c in FILL:
        if len(pads) >= 14: break
        extra = pick([r for r in rows if r['cls'] == c], 14); pads += [r for r in extra if r not in pads][:14 - len(pads)]
    pads = sorted(pads[:14], key=lambda r: ORDER[r['cls']])
    out[g] = []
    for i, r in enumerate(pads):
        pv, nx = nb(r)
        out[g].append(dict(pad=i + 1, cls=r['cls'], patch=r['patch'], prev=pv, next=nx, bleed=1.0, slop=0.5,
                           draw=rs.slop_draw(r['patch']['seed'])))
    cls = {}
    for r in pads: cls[r['cls']] = cls.get(r['cls'], 0) + 1
    print(f"{g}: {len(groups[g])} loops, {len(rows)} usable hits, picked {cls}; "
          f"band error median {np.median([r['metrics']['band_err_db'] for r in pads]):.1f} dB")
path = os.path.join(ROOT, 'synth/src/main/resources/kerf/v1/genre_patches.json')
json.dump(out, open(path, 'w'), separators=(',', ':'))
print('wrote', path, os.path.getsize(path) // 1024, 'KB')
