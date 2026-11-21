"""Find well-isolated note onsets in each reference loop and measure partials relative to the
strongest low peak, plus per-partial decay. Python harness, not Kotlin."""
import sys, glob, os, json
import numpy as np
from scipy import signal
from scipy.io import wavfile

def load(p):
    r, x = wavfile.read(p)
    x = x.astype(np.float64)
    x /= {np.dtype('int16'):32768.0}.get(x.dtype, 1.0) if False else (32768.0 if np.max(np.abs(x))>2 else 1.0)
    if x.ndim == 2: x = x.mean(1)
    if np.max(np.abs(x)) > 1.5: x /= 2**23 if np.max(np.abs(x)) > 70000 else 32768
    return r, x

def onsets(x, r):
    hop = 256
    env = np.sqrt(np.convolve(x*x, np.ones(hop)/hop, 'same'))[::hop]
    db = 20*np.log10(env+1e-9)
    flux = np.diff(db, prepend=db[0])
    peaks, _ = signal.find_peaks(flux, height=6, distance=int(0.12*r/hop))
    return peaks*hop, env, hop

def note_partials(seg, r):
    n = len(seg)
    w = np.hanning(n)
    S = np.abs(np.fft.rfft(seg*w, 1<<18))
    f = np.fft.rfftfreq(1<<18, 1/r)
    band = (f > 60) & (f < 6000)
    Sd = 20*np.log10(S/np.max(S[band])+1e-12)
    pk, pr = signal.find_peaks(np.where(band, Sd, -200), height=-40, prominence=12, distance=40)
    pk = pk[np.argsort(-Sd[pk])][:8]
    return sorted([(float(f[i]), float(Sd[i])) for i in pk])

def decay(x, r, t0, hz):
    # band energy around hz over 1.2 s, slope in dB/s -> T60
    seg = x[t0:t0+int(1.2*r)]
    if len(seg) < r*0.6: return None
    b, a = signal.butter(2, [hz*0.97, hz*1.03], 'band', fs=r)
    y = signal.filtfilt(b, a, seg)
    e = 20*np.log10(np.sqrt(np.convolve(y*y, np.ones(441)/441, 'same'))+1e-9)
    t = np.arange(len(e))/r
    m = (t > 0.05) & (t < 0.6)
    s = np.polyfit(t[m], e[m], 1)[0]
    return float(-60/s) if s < 0 else None

out = {}
for p in sorted(sys.argv[1:]):
    r, x = load(p)
    name = os.path.basename(p).split('-',1)[1]
    ons, env, hop = onsets(x, r)
    gaps = np.diff(np.append(ons, len(x)))
    best = [o for o, g in zip(ons, gaps) if g > 0.35*r][:4]
    notes = []
    for o in best:
        seg = x[o+int(0.015*r):o+int(0.015*r)+int(0.30*r)]
        parts = note_partials(seg, r)
        if not parts: continue
        f0 = min(pk for pk, d in parts if d > -25) if any(d>-25 for _,d in parts) else parts[0][0]
        rat = [(round(pf/f0, 3), round(d,1)) for pf, d in parts]
        t60 = {round(pf/f0,2): decay(x, r, o, pf) for pf, d in parts[:4] if pf < 5000}
        notes.append({"t": round(o/r,2), "f0": round(f0,1), "ratios": rat,
                      "t60": {k: (round(v,2) if v else None) for k, v in t60.items()}})
    out[name] = {"onsets": len(ons), "rate_per_s": round(len(ons)/(len(x)/r),2), "notes": notes}
print(json.dumps(out, indent=1))
