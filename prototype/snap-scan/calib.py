"""Calibration on all of Josh's photos. Python harness, not Kotlin. Derived numbers only."""
import sys, os, glob, json
import numpy as np
from PIL import Image
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scan_proto as sp, real_photos as rp
UP = sys.argv[1] if __name__ == "__main__" else None
RATE = 44100
EDGES = 125 * 2 ** (np.arange(0, 22) / 3)          # third-octave bands, 125 Hz .. ~12 kHz

def bands_db(x, frame=2048):
    """Energy per third-octave band, frame by frame over the first 400 ms: a timbre picture
    that ignores exact partial positions, closer to how ears judge tone colour."""
    f = np.fft.rfftfreq(frame, 1/RATE); out = []
    for s in range(0, min(len(x), int(.4*RATE)) - frame, frame//2):
        p = np.abs(np.fft.rfft(x[s:s+frame]*np.hanning(frame)))**2
        e = np.array([p[(f >= lo) & (f < hi)].sum() for lo, hi in zip(EDGES[:-1], EDGES[1:])])
        d = 10*np.log10(e/e.sum() + 1e-9); out.append(np.maximum(d, -60))
    return np.array(out)

def tdist(a, b):
    A, B = bands_db(a), bands_db(b); n = min(len(A), len(B))
    return float(np.sqrt(np.mean((A[:n]-B[:n])**2)))

def snd(l, seed=1, fp=1.0, scan=.6):
    return sp.render(sp.align(sp.bands(l)), scan=scan, direction=sp.light_dir(l), seed=seed, fp=fp)
def today(l): return sp.render([sp.cycle(l.mean(0))]*16, scan=0)

def main():
    files = sorted(glob.glob(UP + "/*"))
    L = {os.path.basename(f)[:8]: rp.lum(rp.load(f)) for f in files}
    keys = sorted(L)
    # times, to find real re-shot pairs (same scene seconds apart)
    times = {}
    for f in files:
        ex = Image.open(f).getexif()
        if 306 in ex: times[os.path.basename(f)[:8]] = ex[306]

    rep = {"photos": len(keys), "with_capture_time": len(times)}
    rep["reshot_pairs_seconds_apart"] = []
    import datetime as dt
    tl = sorted((dt.datetime.strptime(v, "%Y:%m:%d %H:%M:%S"), k) for k, v in times.items())
    pairs = [(a[1], b[1]) for a, b in zip(tl, tl[1:]) if (b[0]-a[0]).total_seconds() <= 10]
    rep["reshot_pairs_seconds_apart"] = len(pairs)
    rnd = np.random.default_rng(3); diff_pairs = [tuple(rnd.choice(keys, 2, replace=False)) for _ in range(30)]
    def med(xs): return round(float(np.median(xs)), 2)
    rep["today"] = {"reshot": med([tdist(today(L[a]), today(L[b])) for a, b in pairs]),
                    "different_photos": med([tdist(today(L[a]), today(L[b])) for a, b in diff_pairs])}
    for fp in (0.0, 0.5, 1.0):
        rep[f"scan_fingerprint_{fp}"] = {
            "round_robin": med([tdist(snd(L[k], 1, fp), snd(L[k], 2, fp)) for k in keys[:12]]),
            "reshot": med([tdist(snd(L[a], 1, fp), snd(L[b], 7, fp)) for a, b in pairs]),
            "different_photos": med([tdist(snd(L[a], 1, fp), snd(L[b], 1, fp)) for a, b in diff_pairs])}
    dips = [sp.blend_dip(sp.bands(l)) for l in L.values()]; dipa = [sp.blend_dip(sp.align(sp.bands(l))) for l in L.values()]
    rep["blend_dip_db"] = {"unaligned_worst": round(min(dips), 2), "aligned_worst": round(min(dipa), 2),
                           "photos_unaligned_below_-3dB": int(sum(d < -3 for d in dips))}
    print(json.dumps(rep, indent=1))


if __name__ == '__main__':
    main()
