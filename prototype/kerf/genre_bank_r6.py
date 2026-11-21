"""Build KERF's third bank: eight more genre kits (round 6, 2026-11-21).

The owner asked for all three: more kits from the first loops' leftovers, kits from 31 new loops (cleared for
commercial use, the loops deciding the genres), and later a bigger engine. This script makes the kits' bank:

From the new loops, grouped by what they measure, never by name (no loop or pack name reaches the repository):

- JUNGLE: 160 BPM and up.
- CLANG: percussion loops (kicks under 13% of the hits) at 100 BPM and up. Their bright metal hits label as snares,
  so CLANG plays them as PERC.
- HOUSE: 118 to 132 BPM, kicks at least 20% of the hits, bright (centre 800 Hz and up).
- DEEP: the same tempos and kicks, dark (centre under 800 Hz).
- SWAY: 70 to 115 BPM, the swung loops.

From the first loops' leftovers (fitted in round 2, used by no kit), grouped by the hits themselves:

- CASSETTE: the leftovers of the loops round 5 filed as CHATTER (the busy, hat-heavy cassette breaks).
- BOOM: of what remains, per kind, the third that rings longest.
- TIGHT: of what remains after BOOM, per kind, the shortest third.

Every kit picks 14 hits the way `kits.py` does (loudest first, then least like those already chosen), only from hits
no other kit holds that fit well (envelope match at least 0.85, band error at most 5 dB) and last 0.08 to 1.2 s.

    python3 prototype/kerf/genre_bank_r6.py OLD_FITS_DIR NEW_FITS_DIR

Writes synth/src/main/resources/kerf/v1/genre_patches_r6.json (fitted numbers only; with each pad the voice it plays).
"""
import sys, os, re, json
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import resynth as rs

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OLD = json.load(open(os.path.join(sys.argv[1], 'fits.json')))
NEW = json.load(open(os.path.join(sys.argv[2], 'fits.json')))
RES = os.path.join(ROOT, 'synth/src/main/resources/kerf/v1')
used = set()
for f in ('kit_patches.json', 'genre_patches.json'):
    b = json.load(open(os.path.join(RES, f))); used |= {p['patch']['seed'] for k in b for p in b[k]}
assert not ({r['patch']['seed'] for r in OLD} & {r['patch']['seed'] for r in NEW}), 'seed collision between fit sets'

def feat(r): return np.array([np.log2(r['centroid']+50), 3*r['sub'], 3*r['low'], 3*r['mid'], 3*r['pres'], 3*r['air'], np.log2(r['dur']), 0.5*np.log2(r['t60'])])
def pick(rows, n, key=lambda r: -r['peak']):
    """`kits.py`'s picker: first by [key] (loudest, unless told otherwise), then whichever is least like those chosen."""
    if not rows: return []
    rows = sorted(rows, key=key); got = [rows[0]]
    while len(got) < min(n, len(rows)):
        d = [min(np.linalg.norm(feat(r)-feat(g)) for g in got) for r in rows]; i = int(np.argmax(d))
        if d[i] < 0.05: break
        got.append(rows[i])
    return got

def good(r):
    return (r['patch']['seed'] not in used and 0.08 <= r['dur'] <= 1.2
            and r['metrics']['env_corr'] >= 0.85 and r['metrics']['band_err_db'] <= 5.0)

def bpm_of(path, rows):
    name = os.path.basename(path).replace('bpm', ' ')
    nums = [int(n) for n in re.findall(r'(?<![0-9])(\d{2,3})(?![0-9])', name) if 60 <= int(n) <= 180]
    if nums: return nums[-1], False
    # no tempo in the name: the tempo whose sixteenth grid the louder onsets sit on best
    t = np.array([r['start'] for r in rows]); w = np.array([r['peak'] for r in rows])
    cands = np.arange(70, 180.5, 0.5)
    score = [float((w * np.cos(2 * np.pi * t / (60 / b / 4))).sum()) for b in cands]
    return float(cands[int(np.argmax(score))]), True

def measure(R, loop):
    rows = [r for r in R if r['loop'] == loop]
    bpm, guessed = bpm_of(rows[0]['path'], rows)
    e = np.array([r['peak'] ** 2 for r in rows])
    share = lambda *c: sum(r['cls'] in c for r in rows) / len(rows)
    return dict(bpm=bpm, guessed=guessed, centre=float((np.array([r['centroid'] for r in rows]) * e).sum() / e.sum()),
                kicks=share('KICK'), ghosts=share('GHOST'), hats=share('HAT', 'HAT_OPEN'))

def genre_new(m):
    if m['bpm'] >= 160: return 'JUNGLE'
    if m['kicks'] < 0.13 and m['bpm'] >= 100: return 'CLANG'
    if 118 <= m['bpm'] <= 132 and m['kicks'] >= 0.2: return 'HOUSE' if m['centre'] >= 800 else 'DEEP'
    if 70 <= m['bpm'] <= 115: return 'SWAY'
    return None

# round 5's CHATTER rule, to find its loops' leftovers
def chatter(R, loop):
    rows = [r for r in R if r['loop'] == loop]
    seconds = max(r['start'] + r['dur'] for r in rows); b, _ = bpm_of(rows[0]['path'], rows)
    per_bar = len(rows) / (seconds / (4 * 60 / b)); m = measure(R, loop)
    fast_bright = b >= 100 and m['centre'] >= 700
    return (not fast_bright and m['centre'] >= 850 and m['ghosts'] < 0.25
            and (per_bar >= 13 or m['hats'] > 1 / 3))

VOICE = {'KICK': 'KICK', 'SNARE': 'SNARE', 'GHOST': 'GHOST', 'HAT': 'HAT', 'HAT_OPEN': 'HAT_OPEN', 'STACK': 'PERC', 'PERC': 'PERC'}
WANT = [('KICK', 3), ('SNARE', 3), ('GHOST', 2), ('HAT', 3), ('HAT_OPEN', 1), ('STACK', 2)]
FILL = ['HAT_OPEN', 'HAT', 'GHOST', 'STACK', 'PERC', 'SNARE', 'KICK']
ORDER = {c: i for i, c in enumerate(['KICK', 'SNARE', 'GHOST', 'HAT', 'HAT_OPEN', 'STACK', 'PERC'])}
taken = set()

def kit(rows, key=lambda r: -r['peak'], want=WANT):
    rows = [r for r in rows if r['patch']['seed'] not in taken]; pads = []
    for c, n in want: pads += pick([r for r in rows if r['cls'] == c], n, key)
    for c in FILL:
        if len(pads) >= 14: break
        extra = pick([r for r in rows if r['cls'] == c and r not in pads], 14, key); pads += extra[:14 - len(pads)]
    pads = sorted(pads[:14], key=lambda r: ORDER[r['cls']])
    taken.update(r['patch']['seed'] for r in pads)
    return pads

def neighbours(R):
    BY = {(r['loop'], r['idx']): r for r in R}
    def nb(r):
        idxs = sorted(i for (l, i) in BY if l == r['loop']); k = idxs.index(r['idx'])
        return BY[(r['loop'], idxs[k-1])]['patch'], BY[(r['loop'], idxs[(k+1) % len(idxs)])]['patch']
    return nb

groups = {}
loops = list(dict.fromkeys(r['loop'] for r in NEW))
for i, lp in enumerate(loops):
    m = measure(NEW, lp); g = genre_new(m)
    print(f"new loop {i:2d}: {m['bpm']:5.1f} BPM{' (estimated)' if m['guessed'] else ''}, centre {m['centre']:5.0f} Hz, "
          f"kicks {m['kicks']:.2f}, ghosts {m['ghosts']:.2f}, hats {m['hats']:.2f} -> {g}")
    if g: groups.setdefault(g, []).append(lp)

out = {}; source = {}
for g in ['JUNGLE', 'CLANG', 'HOUSE', 'DEEP', 'SWAY']:
    rows = [r for r in NEW if r['loop'] in groups.get(g, []) and good(r)]
    out[g] = kit(rows); source[g] = (NEW, len(groups.get(g, [])), len(rows))

old_loops = list(dict.fromkeys(r['loop'] for r in OLD))
cassette = [lp for lp in old_loops if chatter(OLD, lp)]
left = [r for r in OLD if good(r)]
out['CASSETTE'] = kit([r for r in left if r['loop'] in cassette]); source['CASSETTE'] = (OLD, len(cassette), len([r for r in left if r['loop'] in cassette]))
def third(rows, key, longest):
    """Per kind, the third of [rows] with the highest (or lowest) [key]: the picker's spread then stays inside it."""
    out = []
    for c in set(r['cls'] for r in rows):
        rr = sorted((r for r in rows if r['cls'] == c), key=key, reverse=longest); out += rr[:max(1, len(rr) // 3)]
    return out
rest = [r for r in left if r['patch']['seed'] not in taken]
boom = third(rest, lambda r: r['t60'], True); out['BOOM'] = kit(boom); source['BOOM'] = (OLD, len(old_loops), len(boom))
rest = [r for r in left if r['patch']['seed'] not in taken]
tight = third(rest, lambda r: r['dur'], False); out['TIGHT'] = kit(tight); source['TIGHT'] = (OLD, len(old_loops), len(tight))

bank = {}
for g, pads in out.items():
    R = source[g][0]; nb = neighbours(R); bank[g] = []
    for i, r in enumerate(pads):
        pv, nx = nb(r)
        voice = 'PERC' if g == 'CLANG' and r['cls'] != 'KICK' else VOICE[r['cls']]
        bank[g].append(dict(pad=i + 1, cls=r['cls'], voice=voice, patch=r['patch'], prev=pv, next=nx, bleed=1.0,
                            slop=0.5, draw=rs.slop_draw(r['patch']['seed'])))
    cls = {}
    for p in bank[g]: cls[p['voice']] = cls.get(p['voice'], 0) + 1
    e = [r['metrics']['band_err_db'] for r in pads]
    print(f"{g}: {source[g][1]} loops, {source[g][2]} usable hits, {len(pads)} picked {cls}; "
          f"band error median {np.median(e):.1f} dB; length {np.median([r['dur'] for r in pads]):.2f} s median")
path = os.path.join(RES, 'genre_patches_r6.json')
json.dump(bank, open(path, 'w'), separators=(',', ':'))
print('wrote', path, os.path.getsize(path) // 1024, 'KB')
