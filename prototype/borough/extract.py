"""Round 0: measure the reference loops into numbers. Python harness, not Kotlin.

Reads the owner's reference WAVs (path given on the command line, never committed) and writes
`targets.json`: per loop, the hit map on the 16th grid, microtiming, and for each drum role a
band-envelope descriptor averaged over every clean occurrence. Only these numbers leave this
script; the synthesizer (`fit.py`, `voices.py`) never reads reference PCM.
"""
from __future__ import annotations
import glob, json, os, sys, hashlib
import numpy as np, soundfile as sf
from scipy import signal

SR = 44_100
EDGES = 25.0 * 2 ** (np.arange(0, 30) / 3)          # third-octave edges 25 Hz .. 16 kHz (29 bands)
TIMES = np.array([0, 2, 4, 7, 10, 15, 20, 28, 38, 50, 65, 85, 110, 140, 180, 230, 290, 360, 440, 540, 660]) / 1000
ROLES = {  # band limits each role is judged on (others bleed in from neighbours)
    'kick': (25, 5000), 'snare': (100, 16000), 'hat': (1500, 16000), 'perc': (150, 16000)}


def bank_env(x: np.ndarray, times=TIMES) -> np.ndarray:
    """dB envelope per third-octave band sampled at TIMES (seconds from x[0]). Band-appropriate
    smoothing: a 2nd-order band-pass then an RMS window of max(2 ms, 1.5 periods of the low edge)."""
    out = np.zeros((len(EDGES) - 1, len(times)))
    for i, (lo, hi) in enumerate(zip(EDGES[:-1], EDGES[1:])):
        sos = signal.butter(2, [lo, min(hi, SR * .49)], fs=SR, btype='band', output='sos')
        y = signal.sosfilt(sos, x)
        w = max(int(.002 * SR), int(1.5 * SR / lo))
        e = np.sqrt(np.maximum(np.convolve(y * y, np.ones(w) / w)[:len(y)], 0))
        # convolve is causal-trailing; shift by half a window so the envelope is centred
        e = np.concatenate([e[w // 2:], np.zeros(w // 2)])
        idx = np.minimum((times * SR).astype(int), len(e) - 1)
        out[i] = e[idx]
    return out


def to_db(env: np.ndarray, floor=-60.0) -> np.ndarray:
    d = 20 * np.log10(env + 1e-12)
    d -= d.max()
    return np.maximum(d, floor)


def flux(x, lo, hi):
    sos = signal.butter(4, [lo, min(hi, SR * .49)], fs=SR, btype='band', output='sos')
    y = signal.sosfilt(sos, x)
    n = int(.003 * SR)
    e = np.sqrt(np.convolve(y * y, np.ones(n) / n, 'same')) + 1e-7
    return e


def analyse(path: str) -> dict:
    y, fs = sf.read(path)
    stereo = y.T
    m = signal.resample_poly(y.mean(1), 147, 160)  # 48 k -> 44.1 k
    side = signal.resample_poly((y[:, 0] - y[:, 1]) / 2, 147, 160)
    base = os.path.basename(path)
    bpm = int(base.split('_')[2])
    step = 60 / bpm / 4
    nsteps = int(round(len(m) / SR / step))
    eK, eS, eH = flux(m, 30, 110), flux(m, 180, 2500), flux(m, 6000, 16000)
    def jump(e, t, pre=.012, post=.035):
        a = int(t * SR)
        p0 = e[max(0, a - int(pre * SR)):max(1, a - int(.002 * SR))]
        p1 = e[a:a + int(post * SR)]
        return 20 * np.log10(p1.max() / (np.median(p0) if len(p0) else 1e-7)), 20 * np.log10(p1.max())
    # precise onset near each slot from broadband log flux
    bb = flux(m, 30, 16000)
    lf = np.diff(np.log(bb), prepend=0)
    slots = []
    for k in range(nsteps):
        t = k * step
        a, b = int((t - .03) * SR), int((t + .045) * SR)
        a = max(a, 0)
        seg = lf[a:b]
        on = (a + int(np.argmax(seg))) / SR if len(seg) else t
        jk, lk = jump(eK, on); js, ls = jump(eS, on); jh, lh = jump(eH, on)
        slots.append(dict(k=k, t=t, on=on, dt_ms=(on - t) * 1000, jk=jk, lk=lk, js=js, ls=ls, jh=jh, lh=lh))
    # classify by band jump relative to each band's loudest hit
    LK = max(s['lk'] for s in slots); LS = max(s['ls'] for s in slots); LH = max(s['lh'] for s in slots)
    # A snare body also moves the kick band, so the kick call uses the low-to-mid ratio
    # measured at the backbeat (slots 4 and 12) as its reference.
    bb_r = [s['lk'] - s['ls'] for s in slots if s['k'] % 16 in (4, 12) and s['ls'] > LS - 8]
    r_s = float(np.median(bb_r)) if bb_r else 0.0
    for s in slots:
        r = s['lk'] - s['ls']
        s['S'] = bool(s['js'] > 6 and s['ls'] > LS - 8)
        s['K'] = bool(s['jk'] > 5 and s['lk'] > LK - 14 and r > r_s + (6 if s['S'] else 4))
        s['g'] = bool((not s['S']) and s['js'] > 4 and s['ls'] > LS - 22)   # ghost-level mid hit
        s['H'] = bool(s['jh'] > 4 and s['lh'] > LH - 20)
    pattern = []
    for bar in range(nsteps // 16):
        row = ''
        for s in slots[bar * 16:(bar + 1) * 16]:
            row += 'X' if s['K'] and s['S'] else 'K' if s['K'] else 'S' if s['S'] else 'g' if s['g'] else 'h' if s['H'] else '.'
        pattern.append(row)
    # descriptors: average power envelope over clean occurrences of each role
    def gap_after(i):
        nxt = [s for s in slots[i + 1:] if s['K'] or s['S'] or s['g'] or s['H']]
        return (nxt[0]['on'] - slots[i]['on']) if nxt else 0.66
    def describe(sel, band):
        if not sel:
            return None
        envs, gaps = [], []
        for i in sel:
            on = slots[i]['on'] - .001
            seg = m[int(on * SR):int((on + .7) * SR)]
            if len(seg) < int(.1 * SR):
                continue
            envs.append(bank_env(seg) ** 2); gaps.append(gap_after(i))
        if not envs:
            return None
        P = np.mean(envs, 0)
        lo, hi = ROLES[band]
        bands = [bool(EDGES[j] >= lo / 1.26) and (EDGES[j + 1] <= hi * 1.26) for j in range(len(EDGES) - 1)]
        return dict(n=len(envs), gap_s=float(np.median(gaps)), db=to_db(np.sqrt(P)).round(2).tolist(), bands=bands)
    idx = range(len(slots))
    # Boom-bap prior: the backbeat (16ths 4 and 12) is the snare; a kick-band hit anywhere
    # else is a kick even when its beater click also lit the mid band.
    back = lambda i: slots[i]['k'] % 8 == 4
    kick = [i for i in idx if slots[i]['K'] and not back(i)]
    snare = [i for i in idx if slots[i]['S'] and back(i) and not slots[i]['K']] or \
            [i for i in idx if slots[i]['S'] and back(i)] or [i for i in idx if slots[i]['S']]
    ghost = [i for i in idx if slots[i]['g'] and not slots[i]['K']]
    hat = [i for i in idx if slots[i]['H'] and not (slots[i]['K'] or slots[i]['S'] or slots[i]['g'])]
    # microtiming: median offset by 16th position class
    def med(cond):
        v = [s['dt_ms'] for s in slots if cond(s)]
        return round(float(np.median(v)), 1) if v else None
    timing = dict(on_beat=med(lambda s: s['k'] % 4 == 0 and (s['K'] or s['S'])),
                  eighth_off=med(lambda s: s['k'] % 4 == 2 and (s['H'] or s['K'] or s['S'] or s['g'])),
                  sixteenth_off=med(lambda s: s['k'] % 2 == 1 and (s['H'] or s['K'] or s['S'] or s['g'])),
                  backbeat=med(lambda s: s['k'] % 8 == 4 and s['S']))
    vel = dict(kick=[round(slots[i]['lk'] - LK, 1) for i in kick][:16], snare=[round(slots[i]['ls'] - LS, 1) for i in snare][:16],
               ghost=[round(slots[i]['ls'] - LS, 1) for i in ghost][:16], hat=[round(slots[i]['lh'] - LH, 1) for i in hat][:16])
    f, P = signal.welch(m, SR, nperseg=8192)
    tot = P.sum()
    return dict(snare_low_mid_ratio_db=r_s, file_sha256=hashlib.sha256(open(path, 'rb').read()).hexdigest(), bpm=bpm, seconds=len(m) / SR,
                pattern=pattern, timing_ms=timing, levels_db=vel,
                stereo_side_db=float(10 * np.log10(np.mean(side ** 2) / np.mean(m ** 2) + 1e-12)),
                rolloff99_hz=float(f[np.searchsorted(np.cumsum(P) / tot, .99)]),
                floor_db=float(20 * np.log10(np.percentile(flux(m, 30, 16000), 2) / flux(m, 30, 16000).max())),
                kick=describe(kick, 'kick'), snare=describe(snare, 'snare'), ghost=describe(ghost, 'snare'),
                hat=describe(hat, 'hat'))


if __name__ == '__main__':
    src, out = sys.argv[1], sys.argv[2]
    res = {'note': 'Numbers measured from the owner-supplied reference loops; no PCM.',
           'edges_hz': EDGES.round(2).tolist(), 'times_s': TIMES.tolist(), 'loops': {}}
    for p in sorted(glob.glob(os.path.join(src, '*.wav'))):
        key = os.path.basename(p)[:-4].split('_', 3)[3]
        r = analyse(p)
        res['loops'][key] = r
        print(f"{key:26s} {r['bpm']} " + ' '.join(r['pattern'][:2]), r['timing_ms'],
              {k: (r[k] or {}).get('n') for k in ('kick', 'snare', 'ghost', 'hat')})
    json.dump(res, open(out, 'w'), default=lambda o: o.item() if hasattr(o, 'item') else str(o))
