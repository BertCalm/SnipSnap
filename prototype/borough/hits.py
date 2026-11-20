"""Fine measurements of a loop's kick and snare hits, for designing drums (round 2). Python harness.
Numbers only: each hit is found on the loop's measured 16th grid, aligned on its onset, and reduced
to band energies in time windows and the kick's pitch track; the median over hits is kept, so the
hats and room that bleed into any one hit mostly fall away. No audio is stored.

    python3 -I hits.py LOOP.wav loop_key targets.json      # prints, and returns a dict
"""
from __future__ import annotations
import json, sys
import numpy as np
import soundfile as sf
from scipy import signal

SR = 44_100
BANDS = dict(sub=(30, 60), low=(60, 120), punch=(120, 250), mud=(250, 500), mid=(500, 1000),
             knock=(1000, 3000), crack=(3000, 6000), air=(6000, 12000))
WINDOWS = [(0, .005), (.005, .02), (.02, .05), (.05, .1), (.1, .2), (.2, .3)]


def _onsets(x, t, role, st, pats, bars):
    """Onset of each kick or backbeat-snare hit near its grid slot (steepest rise, 1-8 kHz for snares,
    40-200 Hz for kicks), as sample indices."""
    lo, hi = (1000, 8000) if role == 'snare' else (40, 200)   # kick_x aligns on its kick band too
    b = signal.sosfiltfilt(signal.butter(4, [lo, hi], btype='band', fs=SR, output='sos'), x)
    e = np.abs(signal.hilbert(b))
    out = []
    for bar in range(bars):
        row = pats[bar % len(pats)]
        for s, c in enumerate(row):
            ok = (c in 'SX' and s % 8 == 4) if role == 'snare' else (c in 'KX') if role == 'kick_x' else (c in 'K')   # X carries a snare: kicks take it only as a fallback
            if not ok:
                continue
            c0 = int(((bar * 16 + s) * st - .04) * SR); c1 = int(((bar * 16 + s) * st + .06) * SR)
            if c0 < 0 or c1 + int(.32 * SR) > len(x):
                continue
            d = np.diff(e[c0:c1]); out.append(c0 + int(np.argmax(d)) - int(.001 * SR))
    if not out and role == 'kick':   # some loops never play a kick alone (keap, moffat): take the kick-and-snare hits
        return _onsets(x, t, 'kick_x', st, pats, bars)
    return out


def measure(path, key, targets):
    t = json.load(open(targets))['loops'][key]
    y, fs = sf.read(path)
    x = signal.resample_poly(y.mean(1), 147, 160) if fs == 48000 else (y.mean(1) if y.ndim > 1 else y)
    st = 60 / t['bpm'] / 4; bars = int(len(x) / SR / (16 * st))
    res = {}
    for role in ('kick', 'snare'):
        ons = _onsets(x, t, role, st, t['pattern'], bars)
        grid = []
        for o in ons:
            seg = x[o:o + int(.3 * SR)]
            row = []
            for a, b in WINDOWS:
                s = seg[int(a * SR):int(b * SR)]
                n = max(256, 1 << int(np.ceil(np.log2(len(s)))))
                P = np.abs(np.fft.rfft(s * np.hanning(len(s)), n)) ** 2; f = np.fft.rfftfreq(n, 1 / SR)
                row.append([P[(f >= lo) & (f < hi)].sum() / max(b - a, 1e-6) for lo, hi in BANDS.values()])
            grid.append(row)
        g = 10 * np.log10(np.median(np.array(grid), 0) + 1e-20); g -= g.max()
        r = dict(hits=len(ons), db={w: dict(zip(BANDS, np.round(g[i], 1).tolist())) for i, w in enumerate(['0-5ms', '5-20', '20-50', '50-100', '100-200', '200-300'])})
        if role == 'kick':   # the head's pitch track: strongest 35-160 Hz peak in 30 ms windows
            track = []
            for c in (.005, .03, .06, .1, .16):
                fr = []
                for o in ons:
                    s = x[o + int(c * SR):o + int((c + .03) * SR)]
                    P = np.abs(np.fft.rfft(s * np.hanning(len(s)), 16384)); f = np.fft.rfftfreq(16384, 1 / SR)
                    m = (f > 35) & (f < 160); fr.append(f[m][np.argmax(P[m])])
                track.append(round(float(np.median(fr)), 1))
            r['pitch_hz'] = dict(zip(['5ms', '30ms', '60ms', '100ms', '160ms'], track))
        if role == 'snare':  # the body's tone: strongest 140-350 Hz peak at 10-40 ms
            fr = []
            for o in ons:
                s = x[o + int(.01 * SR):o + int(.04 * SR)]
                P = np.abs(np.fft.rfft(s * np.hanning(len(s)), 16384)); f = np.fft.rfftfreq(16384, 1 / SR)
                m = (f > 140) & (f < 350); fr.append(f[m][np.argmax(P[m])])
            r['body_hz'] = round(float(np.median(fr)), 1)
        res[role] = r
    return res


if __name__ == '__main__':
    r = measure(*sys.argv[1:4])
    for role, v in r.items():
        print(role, 'hits', v['hits'], {k: v[k] for k in ('pitch_hz', 'body_hz') if k in v})
        print('  window   ' + ' '.join(f'{b:>6s}' for b in BANDS))
        for w, row in v['db'].items():
            print(f'  {w:8s} ' + ' '.join(f'{row[b]:6.1f}' for b in BANDS))


def measure_hit(x):
    """The same windows, bands and pitch track for one isolated hit (a pad), onset at its steepest rise."""
    e = np.abs(signal.hilbert(x)); o = max(0, int(np.argmax(np.diff(e[:int(.05 * SR)]))) - int(.001 * SR))
    seg = np.pad(x[o:o + int(.3 * SR)], (0, max(0, int(.3 * SR) - len(x[o:o + int(.3 * SR)]))))
    row = []
    for a, b in WINDOWS:
        s = seg[int(a * SR):int(b * SR)]; n = max(256, 1 << int(np.ceil(np.log2(len(s)))))
        P = np.abs(np.fft.rfft(s * np.hanning(len(s)), n)) ** 2; f = np.fft.rfftfreq(n, 1 / SR)
        row.append([P[(f >= lo) & (f < hi)].sum() / max(b - a, 1e-6) for lo, hi in BANDS.values()])
    g = 10 * np.log10(np.array(row) + 1e-20); g -= g.max()
    track = []
    for c in (.005, .03, .06, .1, .16):
        s = seg[int(c * SR):int((c + .03) * SR)]
        P = np.abs(np.fft.rfft(s * np.hanning(len(s)), 16384)); f = np.fft.rfftfreq(16384, 1 / SR)
        m = (f > 35) & (f < 160); track.append(round(float(f[m][np.argmax(P[m])]), 1))
    s = seg[int(.01 * SR):int(.04 * SR)]
    P = np.abs(np.fft.rfft(s * np.hanning(len(s)), 16384)); f = np.fft.rfftfreq(16384, 1 / SR)
    m = (f > 140) & (f < 350); body = round(float(f[m][np.argmax(P[m])]), 1)
    return dict(db={w: dict(zip(BANDS, np.round(g[i], 1).tolist())) for i, w in enumerate(['0-5ms', '5-20', '20-50', '50-100', '100-200', '200-300'])},
                pitch_hz=dict(zip(['5ms', '30ms', '60ms', '100ms', '160ms'], track)), body_hz=body)
