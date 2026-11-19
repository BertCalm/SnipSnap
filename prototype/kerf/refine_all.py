"""Add COLOUR and TICK to the existing fits (cheap: no re-fit of body or bands) and report against the previous version."""
import sys, os, json, time
import numpy as np
from scipy import signal
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import chop, resynth as rs
out = sys.argv[1]; _src = os.path.join(out, 'fits_v3.json')   # the fit to refine; falls back to fits.json
R = json.load(open(_src if os.path.exists(_src) else os.path.join(out, 'fits.json'))); cache = {}; rows = []; t0 = time.time()
def hp(x): return signal.sosfilt(signal.butter(4, 3000, 'high', fs=44100, output='sos'), x)
def grain(x, y):
    """Does the render ring where the source rings? Correlation of the two fine-grain curves (log, 2-14 kHz). 0 = unrelated hiss, 1 = same resonances."""
    f, P, C = rs.fine_spectrum(x); _, Q, E = rs.fine_spectrum(y); m = (f > 2000) & (f < 14000)
    return float(np.corrcoef(np.log(P[m]/C[m]), np.log(Q[m]/E[m]))[0, 1])
for r in R:
    if r['path'] not in cache: cache[r['path']] = chop.read(r['path'])[0].mean(1)
    x = cache[r['path']][r['a']:r['b']]; old = rs.render(r['patch']); rs.refine(x, r['patch']); new = rs.render(r['patch'])
    r['metrics'] = rs.compare(x, new); hx, ho, hn = hp(x), hp(old), hp(new); pk = lambda s: 20*np.log10(np.abs(s).max()/np.abs(hx).max())
    k = int(.008*44100); e = lambda s: 10*np.log10((s[:k]**2).sum()/(hx[:k]**2).sum())
    rows.append((r['cls'], grain(x, old), grain(x, new), pk(ho), pk(hn), e(ho), e(hn), len(r['patch']['colour']['res']), len(r['patch']['ticks']), r['metrics']['band_err_db'], r['metrics']['env_corr'], r['loop'][:2]))
json.dump(R, open(os.path.join(out, 'fits.json'), 'w'))
print(f"{len(R)} hits refined in {time.time()-t0:.0f} s")
def show(name, sel):
    a = np.array([q[1:11] for q in rows if sel(q)], float)
    print(f"{name:34s} n={len(a):3d} ring match {np.median(a[:,0]):.2f} -> {np.median(a[:,1]):.2f} | top-end peak {np.median(a[:,2]):+.1f} -> {np.median(a[:,3]):+.1f} dB (worst under {np.percentile(a[:,2],5):+.1f} -> {np.percentile(a[:,3],5):+.1f}, worst over {np.percentile(a[:,2],95):+.1f} -> {np.percentile(a[:,3],95):+.1f}) | resonances {a[:,6].mean():.0f} ticks {a[:,7].mean():.1f} | band_err {np.median(a[:,8]):.1f} env {np.median(a[:,9]):.2f}")
show('all', lambda q: True)
show('kick / snare / stack', lambda q: q[0] in ('KICK', 'SNARE', 'STACK'))
show('hats / ghosts', lambda q: q[0] not in ('KICK', 'SNARE', 'STACK'))
for pre, kid in [('jh', 'pack A'), ('dh', 'pack B'), ('ah', 'pack C'), ('SO', 'pack D')]: show(kid, lambda q, pre=pre: q[11] == pre)
a = [rs.render(R[i]['patch']) for i in range(0, len(R), 23)]; b = [rs.render(dict(R[i]['patch'], seed=R[i]['patch']['seed']+1)) for i in range(0, len(R), 23)]
print('ring-match ceiling (same patch, different noise seed):', round(float(np.median([grain(u, v) for u, v in zip(a, b)])), 2))
