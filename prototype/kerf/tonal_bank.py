"""Build KERF's pitched-pad bank: struck notes from the owner's melodic loops, laid out as scales (2026-11-21).

The owner asked for bells, struck bars (marimba, vibraphone), tuning forks and the like, from melodic loops they
cleared for commercial use, as pitched pads laid out as a scale in each kit's key ("b").

For every fitted note (`chop.py` and `run_fit.py`, unchanged):

1. Its root: the strongest partial, or one a half or a third of it below when that partial is there and at least a
   third as loud (the fundamental of a bar whose overtone rings louder). Fractional MIDI: a loop's tuning is rarely
   exactly on the semitone, and the pad is retuned exactly.
2. A check against the loop's key, read from its file name (`_Dmaj`, `_Amin`, `_Cm`, `_Eb`; a bare key is major): a
   root outside that key's scale is tried against the next strongest partial, then dropped.
3. Its family, from what its loop measures, never its name: wooden bars ring short; metal bars ring longer; bells and
   chimes ring longest or brightest; gongs are low and long. The family rules are printed with the numbers they read.

Each family becomes one kit of 16 pads: the scale of the family's most common key and mode, from the octave that
centres the 16 notes on the family's notes, each pad the nearest recorded note retuned (never more than an octave;
ties go to the better fit).

    python3 prototype/kerf/tonal_bank.py FITS_DIR [OUT_JSON]

Writes synth/src/main/resources/kerf/v1/tonal_patches.json by default (fitted numbers only).
"""
import sys, os, re, json
import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
R = json.load(open(os.path.join(sys.argv[1], 'fits.json')))
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, 'synth/src/main/resources/kerf/v1/tonal_patches.json')
NOTES = {'C': 0, 'C#': 1, 'Db': 1, 'D': 2, 'D#': 3, 'Eb': 3, 'E': 4, 'F': 5, 'F#': 6, 'Gb': 6, 'G': 7, 'G#': 8,
         'Ab': 8, 'A': 9, 'A#': 10, 'Bb': 10, 'B': 11}
MAJOR = [0, 2, 4, 5, 7, 9, 11]; MINOR = [0, 2, 3, 5, 7, 8, 10]
NAMES = ['C', 'Db', 'D', 'Eb', 'E', 'F', 'Gb', 'G', 'Ab', 'A', 'Bb', 'B']

def key_of(path):
    """(tonic pitch class, 'major' | 'minor') from a file name, or None when it names no key."""
    name = os.path.basename(path).rsplit('.', 1)[0]
    for part in reversed(re.split(r'[_ ]', name)):
        m = re.fullmatch(r'([A-G](?:#|b)?)(maj|min|m)?', part)
        if m and m.group(1) in NOTES:
            return NOTES[m.group(1)], ('minor' if m.group(2) in ('min', 'm') else 'major')
    return None

def midi(hz): return 69 + 12 * np.log2(hz / 440.0)

def in_scale(m, key):
    tonic, mode = key
    return round(m - tonic) % 12 in (MINOR if mode == 'minor' else MAJOR)

def root_of(r, key):
    ps = sorted((p for p in r['patch']['partials'] if 20 < p['hz_end'] < 8000), key=lambda p: -p['amp'])
    if not ps: return None
    top = ps[0]['amp']
    for cand in ps[:4]:
        if cand['amp'] < top * 0.2: break
        hz = cand['hz_end']
        for k in (2, 3):   # a fundamental below an overtone that rings louder
            low = [p for p in ps if abs(p['hz_end'] * k - hz) < hz * 0.015 and p['amp'] >= cand['amp'] / 3]
            if low: hz = low[0]['hz_end']; break
        m = midi(hz)
        if key is None or in_scale(m, key): return m
    return None

def ring_of(r):
    ps = r['patch']['partials']
    if not ps: return 0.0
    top = max(p['amp'] for p in ps)
    return max(p['attack_s'] + p['t60'] for p in ps if p['amp'] >= top * 0.01)

def inharmonic(r, root):
    """How far the partials sit from whole multiples of the root, in cents, loudness-weighted (bars and bells: far)."""
    ps = [p for p in r['patch']['partials'] if p['amp'] > 0]
    if not ps: return 0.0
    f0 = 440 * 2 ** ((root - 69) / 12); w = np.array([p['amp'] for p in ps])
    dev = [abs(1200 * np.log2((p['hz_end'] / f0) / max(1, round(p['hz_end'] / f0)))) for p in ps]
    return float((np.array(dev) * w).sum() / w.sum())

def good(r):
    return r['metrics']['env_corr'] >= 0.85 and r['metrics']['band_err_db'] <= 5.5 and r['patch']['partials']

notes = []
for r in R:
    key = key_of(r['path'])
    if not good(r): continue
    m = root_of(r, key)
    if m is None: continue
    notes.append(dict(r=r, root=float(m), key=key, ring=ring_of(r), centre=r['centroid'], inh=inharmonic(r, m)))
loops = sorted({n['r']['loop'] for n in notes})
per_loop = {}
for lp in loops:
    ns = [n for n in notes if n['r']['loop'] == lp]
    per_loop[lp] = dict(n=len(ns), ring=float(np.median([n['ring'] for n in ns])), centre=float(np.median([n['centre'] for n in ns])),
                        root=float(np.median([n['root'] for n in ns])), inh=float(np.median([n['inh'] for n in ns])), key=ns[0]['key'])

def family(m):
    if m['root'] < 60 and m['ring'] >= 1.0: return 'GONG'
    if m['ring'] >= 2.5 or m['centre'] >= 4000: return 'BELL'
    if m['ring'] < 0.6: return 'WOOD'
    return 'METAL'

fam = {}
for i, lp in enumerate(loops):
    m = per_loop[lp]; f = family(m) if m['n'] >= 3 else None
    k = m['key']; kname = (NAMES[k[0]] + (' minor' if k[1] == 'minor' else ' major')) if k else 'no key'
    print(f"loop {i:2d}: {m['n']:2d} notes, ring {m['ring']:4.2f} s, centre {m['centre']:5.0f} Hz, root {m['root']:5.1f}, "
          f"inharmonic {m['inh']:4.0f} c, {kname} -> {f}")
    if f: fam.setdefault(f, []).append(lp)

out = {}
for f, lps in sorted(fam.items()):
    ns = [n for n in notes if n['r']['loop'] in lps]
    keys = [n['key'] for n in ns if n['key']]
    if not keys: continue
    tonic, mode = max(set(keys), key=keys.count)
    steps = MINOR if mode == 'minor' else MAJOR
    centre = float(np.median([n['root'] for n in ns]))
    # 16 scale notes: a run up the scale whose middle sits on the family's middle note
    def run(base):
        out, o, d = [], 0, 0
        while len(out) < 16:
            out.append(base + 12 * o + steps[d]); d += 1
            if d == 7: d = 0; o += 1
        return out
    base = min((tonic + 12 * o for o in range(-2, 11)), key=lambda b: abs(np.mean(run(b)) - centre))
    targets = run(base)
    pads = []
    for t in targets:
        cands = [n for n in ns if abs(n['root'] - t) <= 12]
        if not cands: continue
        n = min(cands, key=lambda n: (round(abs(n['root'] - t) * 2) / 2, n['r']['metrics']['band_err_db']))
        pads.append(dict(target=t, root=n['root'], patch=n['r']['patch']))
    shifts = [abs(p['target'] - p['root']) for p in pads]
    print(f"{f}: {len(lps)} loops, {len(ns)} notes, key {NAMES[tonic]} {mode}, {len(pads)} pads from MIDI {targets[0]} to "
          f"{targets[-1]}, retune median {np.median(shifts):.1f} semitones, worst {max(shifts):.1f}")
    out[f] = dict(tonic=tonic, mode=mode, pads=pads)
json.dump(out, open(OUT, 'w'), separators=(',', ':'))
print('wrote', OUT, os.path.getsize(OUT) // 1024, 'KB')
