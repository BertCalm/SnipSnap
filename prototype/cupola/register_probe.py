"""How do the owner's tubes change with pitch, and do mine follow? Python harness.
For each isolated hit: the loudest partial L (what the pack names the note), the levels at
0.5L, 1.5L, 2L, 3L relative to L, the T60 of L, the share of energy NOT on a harmonic of L/2
(breath, slap, wall noise), and the attack's share of noise in the first 10 ms."""
import os
import sys, runpy, glob
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import refs  # noqa: E402
import numpy as np
from scipy import signal
R = 44100
H = vars(__import__('hits'))

def describe(seg):
    body = seg[int(.008 * R): int(.15 * R)]
    S = np.abs(np.fft.rfft(body * np.hanning(len(body)), 1 << 17)); f = np.fft.rfftfreq(1 << 17, 1 / R)
    band = (f > 100) & (f < 2000)
    L = f[np.argmax(np.where(band, S, 0))]
    Sd = 20 * np.log10(S / S.max() + 1e-12)
    def lv(r):
        m = (f > L * r * .97) & (f < L * r * 1.03); return round(float(Sd[m].max()), 1)
    P = S ** 2; harm = np.zeros_like(f, dtype=bool)
    for k in range(1, 40):
        harm |= np.abs(f - k * L / 2) < max(8, .02 * k * L / 2)
    off = 10 * np.log10(P[(~harm) & (f > 80) & (f < 8000)].sum() / P[(f > 80) & (f < 8000)].sum())
    b, a = signal.butter(2, [L * .96, L * 1.04], 'band', fs=R)
    z = signal.filtfilt(b, a, seg); k = max(8, int(2 * R / L))
    e = 20 * np.log10(np.sqrt(np.convolve(z * z, np.ones(k) / k, 'same')) + 1e-9)
    t = np.arange(len(e)) / R; m = (t > .02) & (t < min(.2, len(seg) / R - .01))
    sl = np.polyfit(t[m], e[m], 1)[0]
    att = seg[: int(.01 * R)]; A = np.abs(np.fft.rfft(att * np.hanning(len(att)), 4096)) ** 2; fa = np.fft.rfftfreq(4096, 1 / R)
    hi = 10 * np.log10(A[fa > 2000].sum() / A.sum())
    return dict(L=round(float(L)), half=lv(.5), x1_5=lv(1.5), x2=lv(2), x3=lv(3), t60=round(float(-60 / sl), 3) if sl < 0 else 9.9,
                off_harmonic_dB=round(float(off), 1), attack_above_2k_dB=round(float(hi), 1))

def reference_hits():
    out = []
    files = refs.find('percussion_tubes_notes') + refs.find('tubes_melodic')
    for p in files:
        x = H['load'](p); on = H['onsets'](x); gaps = np.diff(np.append(on, len(x)))
        for o, g in zip(on, gaps):
            if g < .2 * R: continue
            seg = x[o: o + int(min(g, .3 * R))]
            if len(seg) < int(.15 * R): continue
            out.append(describe(seg))
    return out

def bands(rows):
    edges = [(150, 300, 'L 150-300 Hz (D3-D4)'), (300, 500, 'L 300-500 Hz (D4-B4)'), (500, 1000, 'L 500-1000 Hz (C5-B5)')]
    for lo, hi, name in edges:
        rs = [r for r in rows if lo <= r['L'] < hi]
        if not rs: continue
        med = {k: round(float(np.median([r[k] for r in rs])), 2) for k in ('half', 'x1_5', 'x2', 'x3', 't60', 'off_harmonic_dB', 'attack_above_2k_dB')}
        print(f"  {name}: n={len(rs)} {med}")

if __name__ == '__main__':
    print('owner tubes (isolated hits):'); bands(reference_hits())
    import cupola_proto as cp, tubes_v4
    tubes_v4.install()
    rows = []
    for key in (52, 55, 57, 60, 62, 64, 65, 67, 69, 71, 72, 74, 76, 79):           # mapping B: fundamental = key - 12
        for take in range(3):
            y = cp.finish(cp.render('TUBES', midi=key - 12, take=take, seconds=.4)[0])
            rows.append(describe(y))
    print('v4, mapping B:'); bands(rows)
