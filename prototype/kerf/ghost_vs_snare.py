"""What does a soft hit change? Compare the fitted patches of ghost notes with the full snares of the SAME loop.

    python3 prototype/kerf/ghost_vs_snare.py OUT        # OUT holds fits.json from run_fit.py

Same drum, same room, same chain, different stick force: the cheapest evidence for a velocity law (spec section 11).
Each figure is a ghost-minus-snare difference inside one loop, then the median across loops."""
import sys, os, json, collections
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import resynth as rs
R = json.load(open(os.path.join(sys.argv[1], 'fits.json')))
centre = np.array([np.sqrt(rs.FB[a]*rs.FB[b-1]) for a, b in rs.BINS])
def feats(r):
    p = r['patch']; B = p['bands']
    snap = np.array([b['snap'] for b in B]); lev = np.array([b['level'] for b in B]); fl = np.array([b['floor'] for b in B]); t60 = np.array([b['t60'] for b in B])
    # energy of each part over the slice: an exponential's area is amplitude * t60 / (6 ln 10)
    k = 1/(6*np.log(10)); e_snap = (snap*np.array([b['snap_t60'] for b in B])).sum()*k; e_ring = (lev*t60).sum()*k; e_floor = fl.sum()*p['seconds']
    body = sum(q['amp']**2*q['t60']/(6*np.log(10))/2 for q in p['partials'] if q['hz_end'] < 1500)*rs.SR/rs.H/ (rs.N/2) if p['partials'] else 0.0
    hit = snap+lev; top = hit[centre > 3000].sum()/(hit.sum()+1e-30)
    low = [q for q in p['partials'] if q['hz_end'] < 600]; first = max(low, key=lambda q: q['amp']) if low else None
    return dict(level_db=20*np.log10(r['peak']), crack_vs_ring_db=10*np.log10((e_snap+1e-30)/(e_ring+1e-30)), top_share_db=10*np.log10(top+1e-30),
                floor_vs_hit_db=10*np.log10((e_floor+1e-30)/(e_snap+e_ring+1e-30)), ring_t60_ms=1000*float((lev*t60).sum()/(lev.sum()+1e-30)),
                tick_vs_peak_db=20*np.log10(max([t['amp'] for t in p['ticks']], default=1e-6)/r['peak']),
                centroid_hz=r['centroid'], body_hz=first['hz_end'] if first else np.nan,
                glide_ratio=(first['glide_hz']/first['hz_end']) if first else np.nan, bleed_db=r['bleed_db'])
by = collections.defaultdict(lambda: collections.defaultdict(list))
for r in R:
    if r['cls'] in ('SNARE', 'GHOST'): by[r['loop']][r['cls']].append(feats(r))
keys = list(feats(next(r for r in R if r['cls'] == 'SNARE')).keys()); rows = []
for lp, d in by.items():
    if len(d['SNARE']) < 2 or len(d['GHOST']) < 2: continue
    med = lambda c, k: float(np.nanmedian([f[k] for f in d[c]]))
    rows.append((lp, len(d['SNARE']), len(d['GHOST']), {k: (med('SNARE', k), med('GHOST', k)) for k in keys}))
print(f"{len(rows)} loops have at least two snares and two ghost notes ({sum(r[1] for r in rows)} snares, {sum(r[2] for r in rows)} ghosts)")
for k in keys:
    ratio = k in ('centroid_hz', 'body_hz', 'ring_t60_ms'); diffs = [(g/s if ratio else g-s) for _, _, _, m in rows for s, g in [m[k]] if np.isfinite(s) and np.isfinite(g)]
    sgn = sum(1 for d in diffs if (d < 1 if ratio else d < 0))
    print(f"  {k:18s} snare {np.median([m[k][0] for *_, m in rows]):8.2f}  ghost {np.median([m[k][1] for *_, m in rows]):8.2f}  ghost {'÷' if ratio else '−'} snare median {np.median(diffs):+7.2f}{'x' if ratio else '  '}  lower in {sgn} of {len(diffs)} loops")
