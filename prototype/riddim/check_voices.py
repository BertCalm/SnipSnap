"""Usage: python3 check_voices.py OUT (after riddim_proto.py OUT). Measures each new voice: pitch track, decay,
take distance (dB; a copy is above 40) and the soft and hard hits' share above 2 kHz. Python harness, not Kotlin."""
import sys, wave, json, numpy as np
from scipy import signal
D = sys.argv[1] + '/voices/'; R = 44100
def rd(p):
    w = wave.open(p); return np.frombuffer(w.readframes(w.getnframes()), '<i2')/32768
def bp(x, lo, hi): return signal.sosfiltfilt(signal.butter(4, [lo, hi], 'bandpass', fs=R, output='sos'), x)
def decay(seg, hop=.002):
    h = int(R*hop); m = len(seg)//h; e = np.sqrt((seg[:m*h]**2).reshape(m, h).mean(1))+1e-12; k = np.argmax(e)
    a = np.where(e[k:] < e[k]*.1)[0]; return float((a[0] if len(a) else m-k)*hop*1000)
def f0(seg, lo, hi):
    out = []
    for ms in (0, 30, 80, 200):
        a = int(ms/1000*R); w = seg[a:a+4096]
        W = np.abs(np.fft.rfft(w*np.hanning(4096), 65536)); f = np.fft.rfftfreq(65536, 1/R); s = (f > lo) & (f < hi); out.append(round(float(f[s][np.argmax(W[s])]), 1))
    return out
def hi_share(x):
    x = x[:int(.1*R)]; S = np.abs(np.fft.rfft(x))**2; f = np.fft.rfftfreq(len(x), 1/R); return float(10*np.log10(S[f >= 2000].sum()/S.sum()))
def dist(a, b):
    n = min(len(a), len(b)); a, b = a[:n], b[:n]; return float(20*np.log10(np.sqrt(np.mean(a*a))/max(1e-12, np.sqrt(np.mean((a-b)**2)))))
bands = {'KICK': (30, 250), 'DEMBOW_KICK': (20, 250), 'BOOM': (25, 200), 'SLIDE': (25, 200), 'RIMSHOT': (800, 8000), 'CLAP': (800, 8000), 'SNARE': (200, 8000),
         'HAT_CLOSED': (6000, 16000), 'HAT_OPEN': (6000, 16000), 'WOOD': (150, 5000), 'METAL': (300, 8000)}
res = {}
for v, (lo, hi) in bands.items():
    h0 = rd(D+f'{v}_v100_t0.wav'); h1 = rd(D+f'{v}_v100_t1.wav'); s0 = rd(D+f'{v}_v040_t0.wav'); b = bp(h0, lo, hi)
    res[v] = {"decay20_ms": round(decay(b)), "f0_0_30_80_200": f0(b, lo, hi) if hi <= 300 else None,
              "take_distance_db": round(dist(h0, h1), 1), "above2k_soft_db": round(hi_share(s0), 1), "above2k_hard_db": round(hi_share(h0), 1)}
    r = res[v]; print(f"{v:12s} decay {r['decay20_ms']:4d} ms  f0 {str(r['f0_0_30_80_200'] or ''):28s} takes {r['take_distance_db']:5.1f} dB apart   >2kHz soft {r['above2k_soft_db']:6.1f} / hard {r['above2k_hard_db']:6.1f} dB")
json.dump(res, open(sys.argv[1]+'/measured.json', 'w'), indent=1)
