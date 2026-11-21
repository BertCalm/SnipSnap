"""Fit the open tube (v4) to the clean one-shot, measured the same way (Python harness)."""
import os
import sys, json, runpy
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import refs  # noqa: E402
import numpy as np
from scipy import signal
import cupola_proto as cp, env_probe as ep, tubes_v4 as tv
tv.install()
R = cp.RATE
H = vars(__import__('hits'))

def anatomy(y, F, K=6):
    seg = y[int(.01 * R): int(.2 * R)]
    S = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), 1 << 18)); f = np.fft.rfftfreq(1 << 18, 1 / R)
    lv = {}
    for k in range(1, K + 1):
        mk = (f > F * k * .97) & (f < F * k * 1.03); lv[k] = 20 * np.log10(S[mk].max() + 1e-12)
    ref = lv[2]
    levels = {k: round(float(lv[k] - ref), 1) for k in lv}
    t60 = {}
    for k in range(1, K + 1):
        b, a = signal.butter(2, [F * k * .96, F * k * 1.04], 'band', fs=R)
        z = signal.filtfilt(b, a, y); kk = max(8, int(2 * R / (F * k)))
        e = 20 * np.log10(np.sqrt(np.convolve(z * z, np.ones(kk) / kk, 'same')) + 1e-9)
        t = np.arange(len(e)) / R; mk = (t > .04) & (t < min(.3, len(y) / R - .02))
        sl = np.polyfit(t[mk], e[mk], 1)[0]; t60[k] = round(float(-60 / sl), 2) if sl < 0 else 9.9
    env = ep.probes(y, 2 * F)
    return levels, t60, env

ref = H['load'](refs.one('RARE_percussionTUBE_oneshot8.wav'))
ref = ref[int(np.argmax(np.abs(ref) > .02)):]
F_REF = 131.9 / 2
MIDI = 69 + 12 * np.log2(F_REF / 440)
RL, RT, RE = anatomy(ref, F_REF)

def model(seconds=.6, **kw):
    raw, d = cp.render('TUBES', kw.get('mac', {}), midi=kw.get('midi', MIDI), take=kw.get('take', 0), vel=kw.get('vel', .8), seconds=seconds)
    return cp.finish(raw)

if __name__ == '__main__':
    print('reference oneshot8, F =', round(F_REF, 2), 'Hz; levels re 2nd harmonic', RL, 'T60', RT, 'env', RE)
    SPACE = dict(T1=(.4, 2.0), DK=(.3, 1.6), RAD=(1.0, 3.0), W_HARM2=(.2, 2.2), X=(.12, .21), RADC=(2.0, 12.0))
    rng = np.random.default_rng(5); best = (1e18, None)
    for i in range(110):
        p = {k: float(rng.uniform(*r)) for k, r in SPACE.items()} if best[1] is None or rng.uniform() < .35 else \
            {k: float(np.clip(best[1][k] + rng.normal(0, .12 * (r[1] - r[0])), *r)) for k, r in SPACE.items()}
        tv.P.update(p)
        errs = []
        for take in (0, 2):
            L, T, E = anatomy(model(take=take), F_REF)
            errs += [(L[k] - RL[k]) for k in (1, 3, 4, 5, 6)]
            errs += [8 * np.log2(max(.02, T[k]) / RT[k]) for k in (1, 2, 3, 4)]     # 8 dB per doubling of T60
            errs += [.15 * (E['to10'] - RE['to10']), .1 * (E['to20'] - RE['to20'])]
        e = float(np.mean(np.square(errs)))
        if e < best[0]: best = (e, p)
    tv.P.update(best[1])
    L, T, E = anatomy(model(), F_REF)
    print(json.dumps({'rms_err': round(best[0] ** .5, 2), 'params': best[1], 'levels': L, 't60': T, 'env': E}, default=float))
