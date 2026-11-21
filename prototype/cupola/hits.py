"""Single-hit anatomy of tube recordings vs prototype renders. Python harness, not Kotlin."""
import sys, os, json
import numpy as np
from scipy import signal
from scipy.io import wavfile
R = 44100
NAMES = 'C C# D D# E F F# G G# A A# B'.split()
def note(f):
    m = 69 + 12*np.log2(f/440); n = int(round(m)); return f"{NAMES[n%12]}{n//12-1}{(m-n)*100:+.0f}"
def load(p):
    r, x = wavfile.read(p); x = x.astype(float)
    if x.ndim == 2: x = x.mean(1)
    return x / (np.max(np.abs(x)) + 1e-12)
def onsets(x):
    hop = 64
    env = np.sqrt(np.convolve(x*x, np.ones(hop)/hop, 'same'))[::hop]; db = 20*np.log10(env+1e-9)
    on, _ = signal.find_peaks(np.diff(db, prepend=db[0]), height=8, distance=int(.08*R/hop))
    return on*hop
def f0_of(seg):
    S = np.abs(np.fft.rfft(seg*np.hanning(len(seg)), 1<<17)); f = np.fft.rfftfreq(1<<17, 1/R)
    band = (f > 60) & (f < 3000); i = np.argmax(np.where(band, S, 0)); return f[i], S, f
def anatomy(x, o):
    seg = x[o:o+int(.25*R)]
    if len(seg) < int(.25*R): return None
    pk = np.argmax(np.abs(seg[:int(.02*R)]))
    env = np.sqrt(np.convolve(seg*seg, np.ones(44)/44, 'same')); db = 20*np.log10(env/env.max()+1e-9)
    p = int(np.argmax(env))
    def t_to(level):
        w = np.where(db[p:] < level)[0]; return round(1000*w[0]/R, 1) if len(w) else None
    # lowest strong peak in the ringing part = the note
    body = seg[int(.01*R):int(.12*R)]
    S = np.abs(np.fft.rfft(body*np.hanning(len(body)), 1<<17)); f = np.fft.rfftfreq(1<<17, 1/R)
    Sd = 20*np.log10(S/S.max()+1e-12)
    pks, _ = signal.find_peaks(np.where((f > 60) & (f < 6000), Sd, -300), height=-30, prominence=10, distance=20)
    strong = sorted(f[pks][Sd[pks] > -12])
    f0 = strong[0] if strong else f[np.argmax(S)]
    harm = []
    for k in range(1, 9):
        m = (f > f0*k*0.95) & (f < f0*k*1.06)
        if m.any():
            j = np.argmax(np.where(m, Sd, -300)); harm.append((k, round(f[j]/f0, 3), round(Sd[j], 1)))
    sub = (f > f0*0.45) & (f < f0*0.55); sub_db = round(float(Sd[sub].max()), 1) if sub.any() else None
    # early pitch track: strongest peak near f0 in 8 ms windows
    track = []
    for t0 in (0.0, 0.005, 0.01, 0.02, 0.04, 0.08):
        w = seg[int(t0*R):int(t0*R)+int(.012*R)]
        if len(w) < 64: break
        Sw = np.abs(np.fft.rfft(w*np.hanning(len(w)), 1<<16)); fw = np.fft.rfftfreq(1<<16, 1/R)
        m = (fw > f0*0.7) & (fw < f0*1.3); track.append(round(1200*np.log2(fw[np.argmax(np.where(m, Sw, 0))]/f0)))
    def cen(a, b):
        w = seg[int(a*R):int(b*R)]; Sw = np.abs(np.fft.rfft(w*np.hanning(len(w)))); fw = np.fft.rfftfreq(len(w), 1/R)
        return round(float((Sw*fw).sum()/Sw.sum()))
    def flat(a, b):
        w = seg[int(a*R):int(b*R)]; Sw = np.abs(np.fft.rfft(w*np.hanning(len(w))))**2 + 1e-20
        return round(float(np.exp(np.mean(np.log(Sw)))/np.mean(Sw)), 4)
    hi = (f > 3000); noise_hi = round(10*np.log10((S[hi]**2).sum()/(S**2).sum()+1e-20), 1)
    return {"f0": round(f0, 1), "note": note(f0), "rise_ms": round(1000*p/R, 1), "to-10dB": t_to(-10), "to-20dB": t_to(-20), "to-30dB": t_to(-30),
            "harm": harm, "sub_octave_dB": sub_db, "pitch_track_cents_0_5_10_20_40_80ms": track,
            "centroid_0_5ms": cen(0, .005), "centroid_5_30ms": cen(.005, .03), "centroid_30_100ms": cen(.03, .1),
            "flatness_0_5ms": flat(0, .005), "energy_above_3k_dB": noise_hi}
if __name__ == '__main__':
    out = {}
    for p in sys.argv[1:]:
        x = load(p); on = onsets(x)
        gaps = np.diff(np.append(on, len(x)))
        iso = [o for o, g in zip(on, gaps) if g > .25*R]
        hits = [a for a in (anatomy(x, o) for o in iso) if a]
        out[os.path.basename(p).split('-', 1)[-1]] = {"hits": len(on), "isolated": len(hits), "sample": hits[:6]}
    print(json.dumps(out, indent=0))
