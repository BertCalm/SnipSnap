"""Usage: python3 measure_targets.py REFS_DIR reference_jobs.json OUT.json

Hit-level targets from reference loops, measured at known grid steps. Python harness; numpy+scipy.
For each requested (loop, bpm, steps, role) it averages, over every bar, the hit starting at that step:
f0 track in a band (kick/808), -20 dB decay time per band, spectral centroid of the first 60 ms,
the strongest partials. Output JSON + a printed table."""
import os, sys, json, numpy as np
from scipy import signal
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); from measure_groove import load  # noqa: E402
R = 44100
def bp(x, lo, hi):
    sos = signal.butter(4, [lo, hi], 'bandpass', fs=R, output='sos'); return signal.sosfiltfilt(sos, x)
def decay_ms(seg, db=20, hop=0.002):
    h = int(R*hop); m = len(seg)//h; e = np.sqrt((seg[:m*h]**2).reshape(m, h).mean(1)) + 1e-12
    k = np.argmax(e); thr = e[k]*10**(-db/20); after = np.where(e[k:] < thr)[0]
    return float((after[0] if len(after) else m-k)*hop*1000), float(k*hop*1000)
def f0_track(seg, lo, hi, frames=(0, 30, 80, 200, 400)):
    out = []
    for ms in frames:
        a = int(ms/1000*R); w = seg[a:a+4096]
        if len(w) < 4096: out.append(None); continue
        W = np.abs(np.fft.rfft(w*np.hanning(4096), 65536)); f = np.fft.rfftfreq(65536, 1/R); s = (f > lo) & (f < hi)
        out.append(round(float(f[s][np.argmax(W[s])]), 1))
    return out
def centroid(seg):
    S = np.abs(np.fft.rfft(seg)); f = np.fft.rfftfreq(len(seg), 1/R); return float((S*f).sum()/S.sum())
def partials(seg, lo, hi, n=5):
    W = np.abs(np.fft.rfft(seg*np.hanning(len(seg)), 1 << 17)); f = np.fft.rfftfreq(1 << 17, 1/R); s = (f > lo) & (f < hi)
    pk, _ = signal.find_peaks(W[s], distance=40); pk = pk[np.argsort(W[s][pk])[::-1][:n]]
    names = "C C# D D# E F F# G G# A A# B".split(); res = []
    for p in sorted(pk, key=lambda p: f[s][p]):
        hz = f[s][p]; m = 69+12*np.log2(hz/440); res.append(f"{hz:.0f}Hz {names[int(round(m))%12]}{int(round(m))//12-1}{100*(m-round(m)):+.0f}c")
    return res
REFS = sys.argv[1]; JOBS = json.load(open(sys.argv[2])); out = {}
for job in JOBS:
    mono, _, _ = load(os.path.join(REFS, job["file"])); step = 60/job["bpm"]/4; bar = 16*step; nb = int(round(len(mono)/R/bar))
    for role, steps, (lo, hi), length in job["roles"]:
        segs = []
        for b in range(nb):
            for s in steps:
                a = int((b*bar+s*step)*R) - int(0.005*R); a = max(a, 0); segs.append(mono[a:a+int(length*R)])
        L = min(len(x) for x in segs); segs = [x[:L] for x in segs]
        band = [bp(x, lo, hi) for x in segs]
        d = [decay_ms(x) for x in band]; f0 = [f0_track(x, lo, hi) for x in band] if hi <= 300 else None
        rec = {"loop": job["name"], "role": role, "band": [lo, hi], "hits": len(segs),
               "decay20_ms": round(float(np.median([x[0] for x in d])), 1), "peak_at_ms": round(float(np.median([x[1] for x in d])), 1),
               "centroid60_hz": round(float(np.median([centroid(x[:int(.06*R)]) for x in segs]))),
               "partials": partials(np.mean(band, 0), lo, hi)}
        if f0: rec["f0_track_0_30_80_200_400ms"] = [None if any(t[i] is None for t in f0) else round(float(np.median([t[i] for t in f0])), 1) for i in range(5)]
        out[f'{job["name"]}:{role}'] = rec
        print(f'{job["name"]:13s} {role:12s} decay(-20dB) {rec["decay20_ms"]:6.0f} ms  centroid {rec["centroid60_hz"]:5d} Hz  ' +
              (f'f0 {rec.get("f0_track_0_30_80_200_400ms")}  ' if f0 else '') + ' '.join(rec["partials"][:4]))
json.dump(out, open(sys.argv[3], 'w'), indent=1)
