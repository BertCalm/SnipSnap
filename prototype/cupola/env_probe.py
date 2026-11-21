"""Pitch-adaptive envelope probes, applied the same way to references and models (Python harness).
Window = two periods of the octave below the note, so a cycle is never read as an attack."""
import numpy as np
R = 44100
def env_db(y, f0):
    k = max(8, int(2 * R / (f0 / 2)))
    e = np.sqrt(np.convolve(y * y, np.ones(k) / k, 'same'))
    return 20 * np.log10(e / e.max() + 1e-9)
def probes(y, f0, win=.12):
    e = env_db(y, f0)
    p = int(np.argmax(e[: int(.05 * R)]))
    tail = e[p: p + int(win * R)]
    reattack = float(np.max(tail - np.minimum.accumulate(tail)))
    def t_to(l):
        w = np.where(e[p:] < l)[0]; return round(1000 * w[0] / R, 1) if len(w) else None
    return dict(peak_ms=round(1000 * p / R, 1), to10=t_to(-10), to20=t_to(-20), to30=t_to(-30), reattack_dB=round(reattack, 1))


def common_fate(y, f0, partial_ratios=(.5, 1, 2, 3), t0=.005, t1=.15):
    """How much the partials move together: mean pairwise correlation of their dB envelopes
    between t0 and t1, and the spread of their peak times (ms). A struck tube's partials rise and
    fall together; separate layers (a pop, a sub on its own clock) pull this down."""
    from scipy import signal
    envs, peaks = [], []
    for r in partial_ratios:
        fc = f0 * r
        b, a = signal.butter(2, [fc * .93, fc * 1.07], 'band', fs=R)
        z = signal.filtfilt(b, a, y)
        k = max(8, int(2 * R / fc))
        e = 20 * np.log10(np.sqrt(np.convolve(z * z, np.ones(k) / k, 'same')) + 1e-9)
        seg = e[int(t0 * R): int(t1 * R)]
        if seg.max() < e.max() - 200: continue
        envs.append(seg); peaks.append(1000 * np.argmax(e[: int(.1 * R)]) / R)
    c = np.corrcoef(np.array(envs))
    iu = np.triu_indices(len(envs), 1)
    return round(float(np.mean(c[iu])), 3), round(float(np.ptp(peaks)), 1)
