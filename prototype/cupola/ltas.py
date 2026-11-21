"""Per file: strongest long-term spectral peaks (with note names), median time for a hit to fall
20 dB, and the attack's spectral centroid vs the body's. Python harness, not Kotlin."""
import sys, os
import numpy as np
from scipy import signal
from scipy.io import wavfile
NAMES = 'C C# D D# E F F# G G# A A# B'.split()
def note(f):
    m = 69 + 12*np.log2(f/440); n = int(round(m)); return f"{NAMES[n%12]}{n//12-1}{(m-n)*100:+.0f}c"
for p in sys.argv[1:]:
    r, x = wavfile.read(p); x = x.astype(float)
    if x.ndim == 2: x = x.mean(1)
    x /= np.max(np.abs(x))
    f, P = signal.welch(x, r, nperseg=1<<15)
    Pd = 10*np.log10(P/P.max()+1e-15)
    band = (f > 50) & (f < 5000)
    pk, _ = signal.find_peaks(np.where(band, Pd, -300), prominence=8, distance=8)
    pk = pk[np.argsort(-Pd[pk])][:10]
    peaks = sorted(pk, key=lambda i: f[i])
    hop = 128
    env = np.sqrt(np.convolve(x*x, np.ones(hop)/hop, 'same'))[::hop]; db = 20*np.log10(env+1e-9)
    on, _ = signal.find_peaks(np.diff(db, prepend=db[0]), height=6, distance=int(.1*r/hop))
    falls, ac, bc = [], [], []
    for o in on:
        pkf = o + np.argmax(db[o:o+20]); lvl = db[pkf]
        nxt = on[on > o]; end = nxt[0] if len(nxt) else len(db)
        below = np.where(db[pkf:end] < lvl - 20)[0]
        if len(below): falls.append(below[0]*hop/r)
        def cen(seg):
            S = np.abs(np.fft.rfft(seg*np.hanning(len(seg)))); ff = np.fft.rfftfreq(len(seg), 1/r)
            return float((S*ff).sum()/(S.sum()+1e-12))
        s0 = o*hop
        if s0 + int(.25*r) < len(x):
            ac.append(cen(x[s0:s0+int(.02*r)])); bc.append(cen(x[s0+int(.1*r):s0+int(.25*r)]))
    nm = os.path.basename(p).split('-',1)[-1].replace('RARE_percussion_','')
    print(f"{nm[:46]:46s} hits={len(on):3d} fall20dB med={np.median(falls) if falls else float('nan'):.2f}s ({len(falls)} measured) "
          f"centroid attack/body={np.median(ac):.0f}/{np.median(bc):.0f} Hz")
    print("    peaks:", ', '.join(f"{f[i]:.0f}Hz {note(f[i])} ({Pd[i]:.0f}dB)" for i in peaks))
