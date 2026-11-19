import sys, os, json, time, hashlib, collections
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import chop, resynth as rs
out = sys.argv[1]; R = json.load(open(os.path.join(out, 'chops.json'))); cache = {}
t0 = time.time(); fit_t = 0; ren_t = 0
for r in R:
    if r['path'] not in cache:
        x, sr = chop.read(r['path']); cache[r['path']] = x.mean(1)
    m = cache[r['path']]; x = m[r['a']:r['b']]
    seed = int(hashlib.blake2b(f"{r['loop']}|{r['idx']}".encode(), digest_size=7).hexdigest(), 16)
    t = time.time(); p = rs.fit(x, seed); fit_t += time.time()-t
    t = time.time(); y = rs.render(p); ren_t += time.time()-t
    r['patch'] = p; r['metrics'] = rs.compare(x, y)
    mx = max(q['peak'] for q in R if q['loop'] == r['loop'])
    chop.write(os.path.join(out, r['file'].replace('.wav', '_syn.wav')), y/max(1.0, mx))
json.dump(R, open(os.path.join(out, 'fits.json'), 'w'))
json.dump(R, open(os.path.join(out, 'fits.json'), 'w'))
_prev = os.path.join(out, 'fits_v2.json')   # an earlier fit to compare against; on a first run the fit is compared with itself
OLD = {(r['loop'], r['idx']): r for r in (json.load(open(_prev)) if os.path.exists(_prev) else R)}
print(f"{len(R)} hits, fit {fit_t/len(R)*1000:.0f} ms/hit, render {ren_t/len(R)*1000:.0f} ms/hit (Python harness, not Kotlin)")
for c in ['KICK', 'SNARE', 'STACK', 'GHOST', 'HAT', 'HAT_OPEN', 'PERC', 'ALL']:
    rr = [r for r in R if c == 'ALL' or r['cls'] == c]; g = lambda k: np.array([r['metrics'][k] for r in rr])
    print(f"{c:9s} n={len(rr):3d} band_err {np.median(g('band_err_db')):4.1f} dB (p90 {np.percentile(g('band_err_db'),90):4.1f})  env_corr {np.median(g('env_corr')):.3f} (p10 {np.percentile(g('env_corr'),10):.3f})  rms {np.median(g('rms_db')):+.1f} dB  peak {np.median(g('peak_db')):+.1f} dB  centroid {np.median(g('centroid_syn')/g('centroid_src')):.2f}x  partials {np.mean([len(r['patch']['partials']) for r in rr]):.1f}")

from scipy import signal
def hp(x): return signal.sosfilt(signal.butter(4, 3000, 'high', fs=44100, output='sos'), x)
def peaky(x):
    n = 1 << 15; X = np.abs(np.fft.rfft(x[:n]*np.hanning(min(len(x), n)), n))**2; f = np.fft.rfftfreq(n, 1/44100); X = X[(f > 3000) & (f < 14000)]
    k = max(1, len(X)//50); return float(np.sort(X)[-k:].sum()/X.sum())
rows = []
for r in R:
    x = cache[r['path']][r['a']:r['b']]; hx = hp(x); o = hp(rs.render(OLD[(r['loop'], r['idx'])]['patch'])); y = hp(rs.render(r['patch']))
    c = lambda s: 20*np.log10(np.abs(s).max()/np.sqrt((s**2).mean()))
    rows.append((r['cls'], 20*np.log10(np.abs(o).max()/np.abs(hx).max()), 20*np.log10(np.abs(y).max()/np.abs(hx).max()), peaky(hx), peaky(o), peaky(y), c(hx), c(o), c(y), 10*np.log10((y**2).sum()/(hx**2).sum())))
for grp, f in [('hit with something louder underneath (KICK/SNARE/STACK)', lambda c: c in ('KICK', 'SNARE', 'STACK')), ('hats and ghosts', lambda c: c not in ('KICK', 'SNARE', 'STACK'))]:
    a = np.array([q[1:] for q in rows if f(q[0])]); m = np.median(a, 0)
    print(f"{grp}: top-end peak vs source {m[0]:+.1f} -> {m[1]:+.1f} dB | tonal share src {m[2]:.2f}: {m[3]:.2f} -> {m[4]:.2f} | crest src {m[5]:.1f}: {m[6]:.1f} -> {m[7]:.1f} dB | top-end energy {m[8]:+.1f} dB")
print('metal tones per hit', np.mean([sum(p['hz_end'] > 2000 for p in r['patch']['partials']) for r in R]).round(1), ' ticks per hit', np.mean([len(r['patch']['ticks']) for r in R]).round(2))
