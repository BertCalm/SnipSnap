"""Build KERF's pitched-pad bank: struck notes from the owner's melodic loops, laid out as scales (2026-11-21).

The owner asked for bells, struck bars (marimba, vibraphone), tuning forks and the like, from melodic loops they
cleared for commercial use, as pitched pads laid out as a scale in each kit's key ("b").

For every fitted note (`chop.py` and `run_fit.py`, unchanged):

1. Its root: the lowest partial at least 30% as loud as the strongest. Fractional MIDI: a loop's tuning is rarely
   exactly on the semitone, and the pad is retuned exactly.
   Loops whose names give no key are left out: without a key the pitch cannot be checked, and the ones here (sleigh
   bells, chimes, tingsha) are barely pitched.
2. A check against the loop's key, read from its file name (`_Dmaj`, `_Amin`, `_Cm`, `_Eb`; a bare key is major): a
   root outside that key's scale is tried against the next strong partial up, then dropped.
3. Its family, from what its loop measures, never its name: wooden bars ring short; metal bars ring longer; bells and
   chimes ring longest or brightest; gongs are low and long. The family rules are printed with the numbers they read.

Each family becomes one kit of 16 pads: of every key its loops are in, at every octave, the 16-note scale run whose
pads need the least retuning, each pad the nearest recorded note retuned (never more than an octave; ties go to the
better fit). A family with fewer than 12 clean notes is left out: it cannot fill 16 pitched pads well.

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
    """The lowest partial at least 30% as loud as the strongest, in the loop's key; else the next one up that is.
    The strongest partial was tried first and was wrong for bells: their loudest partial is a shimmer far above the
    note heard (a first pass put a bell kit at MIDI 98 to 124)."""
    ps = [p for p in r['patch']['partials'] if 20 < p['hz_end'] < 8000]
    if not ps or key is None: return None
    top = max(p['amp'] for p in ps)
    for cand in sorted((p for p in ps if p['amp'] >= 0.3 * top), key=lambda p: p['hz_end']):
        m = midi(cand['hz_end'])
        if in_scale(m, key): return m
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
    # above MIDI 100 (about 2.6 kHz) a "root" is a bell's shimmer, not a note a pad should be tuned from
    if m is None or m > 100: continue
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
    keys = sorted({n['key'] for n in ns if n['key']})
    if len(ns) < 12: print(f"{f}: {len(lps)} loops, only {len(ns)} clean notes: too few for 16 pitched pads, left out"); continue
    def run(base, steps):
        out, o, d = [], 0, 0
        while len(out) < 16:
            out.append(base + 12 * o + steps[d]); d += 1
            if d == 7: d = 0; o += 1
        return out
    def cost(targets):
        shifts = [min(abs(n['root'] - t) for n in ns) for t in targets]
        return (sum(sh > 12 for sh in shifts), sum(shifts))
    # every key the family's loops are in, every octave: the layout whose 16 pads need the least retuning
    best = None
    for tonic, mode in keys:
        steps = MINOR if mode == 'minor' else MAJOR
        for base in (tonic + 12 * o for o in range(1, 9)):
            c = cost(run(base, steps))
            if best is None or c < best[0]: best = (c, tonic, mode, run(base, steps))
    _, tonic, mode, targets = best
    pads = []
    for t in targets:
        cands = [n for n in ns if abs(n['root'] - t) <= 12]
        if not cands: continue
        n = min(cands, key=lambda n: (round(abs(n['root'] - t) * 2) / 2, n['r']['metrics']['band_err_db']))
        pads.append(dict(target=t, root=n['root'], patch=n['r']['patch']))
    shifts = [abs(p['target'] - p['root']) for p in pads]
    print(f"{f}: {len(lps)} loops, {len(ns)} notes, key {NAMES[tonic]} {mode}, {len(pads)} pads from MIDI {targets[0]} to "
          f"{targets[-1]}, retune median {np.median(shifts):.1f} semitones, worst {max(shifts):.1f}")
    def bpm(path):
        nums = [int(x) for x in re.findall(r'(?<![0-9])(\d{2,3})(?![0-9])', os.path.basename(path)) if 60 <= int(x) <= 180]
        return nums[-1] if nums else None
    tempos = [b for b in (bpm(next(n['r']['path'] for n in ns if n['r']['loop'] == lp)) for lp in lps) if b]
    sources = len({id(p['patch']) for p in pads})
    print(f"   {sources} different recorded notes across the 16 pads; tempo {int(np.median(tempos))} BPM (the loops' median)")
    out[f] = dict(tonic=tonic, mode=mode, bpm=int(np.median(tempos)), pads=pads)
json.dump(out, open(OUT, 'w'), separators=(',', ':'))
print('wrote', OUT, os.path.getsize(OUT) // 1024, 'KB')

# ---- KERF BELLS (2026-11-21): the bright metal loops as a percussion kit, each hit at its own pitch
# The owner asked for bells first; the pitched bell family had six clean notes. Bells need not be pitched pads: the
# loops whose clean notes centre at 4 kHz or brighter (sleigh and Tibetan bells, glockenspiel, gamelan metals) give
# fourteen strikes, picked as kits.py picks (loudest, then least alike), ordered low to high.
def feat(r): return np.array([np.log2(r['centroid']+50), 3*r['sub'], 3*r['low'], 3*r['mid'], 3*r['pres'], 3*r['air'], np.log2(r['dur']), 0.5*np.log2(r['t60'])])
def pick(rows, n):
    rows = sorted(rows, key=lambda r: -r['peak']); got = [rows[0]]
    while len(got) < min(n, len(rows)):
        d = [min(np.linalg.norm(feat(r)-feat(g)) for g in got) for r in rows]; i = int(np.argmax(d))
        if d[i] < 0.05: break
        got.append(rows[i])
    return got
clean = [r for r in R if good(r)]
bright = [lp for lp in sorted({r['loop'] for r in clean})
          if np.median([r['centroid'] for r in clean if r['loop'] == lp]) >= 4000 and sum(r['loop'] == lp for r in clean) >= 10]
def own_root(r):
    ps = [p for p in r['patch']['partials'] if 20 < p['hz_end'] < 12000]
    top = max(p['amp'] for p in ps)
    return float(midi(min(p['hz_end'] for p in ps if p['amp'] >= 0.3 * top)))
hits = sorted(pick([r for r in clean if r['loop'] in bright], 14), key=own_root)
tempos = [int(x) for lp in bright for x in re.findall(r'(?<![0-9])(\d{2,3})(?![0-9])', os.path.basename(next(r['path'] for r in clean if r['loop'] == lp))) if 60 <= int(x) <= 180]
bells = dict(bpm=int(np.median(tempos)), pads=[dict(root=own_root(r), patch=r['patch']) for r in hits])
print(f"BELLS: {len(bright)} bright loops, {sum(r['loop'] in bright for r in clean)} clean notes, {len(hits)} strikes picked, "
      f"MIDI {bells['pads'][0]['root']:.1f} to {bells['pads'][-1]['root']:.1f}, tempo {bells['bpm']} BPM")
BELL_OUT = os.path.join(os.path.dirname(OUT), 'bell_patches.json')
json.dump(bells, open(BELL_OUT, 'w'), separators=(',', ':'))
print('wrote', BELL_OUT, os.path.getsize(BELL_OUT) // 1024, 'KB')
