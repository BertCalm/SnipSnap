"""Fit a falling pitch to a recorded hit by analysis by synthesis.

For a candidate glide f(t) = f1 + (f0 - f1) exp(-t / tau), build its phase phi(t) and multiply
the hit by exp(-i phi(t)). If the candidate is right, the drum's fundamental stops rotating and
becomes a slowly varying complex signal that a short moving average keeps; if it is wrong, the
fundamental keeps rotating and averages away. The best candidate keeps the most energy.

Why this and not a spectrogram tracker: a 33 Hz sub needs a window longer than a kick's whole
pitch drop to resolve in a spectrogram, and a zap falls from kHz to its root in 20 ms. This
uses every sample and the same code works for both. The kept signal is also the partial's
loudness over time, so the envelope comes out of the same step.

The moving average is two cycles of the end pitch: its nulls fall on multiples of f1 / 2, so a
harmonic, which sits at least f1 away once demodulated, is rejected.
"""
from __future__ import annotations

import numpy as np
from scipy import optimize, signal


LAWS = ("exp", "logexp")


def freq(law, f0, f1, tau, t):
    """The two glide shapes. 'exp' falls fast in Hz then settles (a kick's body). 'logexp'
    falls in octaves: log f decays exponentially, so a sweep reads as a near-straight line on a
    log-frequency axis (a zap, a laser tom)."""
    e = np.exp(-np.asarray(t) / tau)
    if law == "logexp":
        return f1 * (f0 / f1) ** e
    return f1 + (f0 - f1) * e


def _phase(f0, f1, tau, t, law="exp"):
    if law == "exp":  # closed form of the integral of f1 + (f0 - f1) exp(-t / tau)
        return 2 * np.pi * (f1 * t + (f0 - f1) * tau * (1 - np.exp(-t / tau)))
    dt = t[1] - t[0]
    return 2 * np.pi * np.cumsum(freq(law, f0, f1, tau, t)) * dt


def _kept(y, t, rate, f0, f1, tau, k=1, law="exp"):
    z = y * np.exp(-1j * k * _phase(f0, f1, tau, t, law))
    w = max(3, int(2.0 / f1 * rate))
    # centred moving average; at the edges, the average of the samples that exist (zero
    # padding would read the start of every hit as silence)
    n = len(z)
    c = np.concatenate([[0], np.cumsum(z)])
    i = np.arange(n)
    lo = np.clip(i - w // 2, 0, n)
    hi = np.clip(i - w // 2 + w, 0, n)
    return (c[hi] - c[lo]) / (hi - lo)


def fit(hit: np.ndarray, rate: int, root_guess: float, span_s: float, max_f0: float = 6000.0):
    """Returns dict(f0, f1, tau, captured, rate, t, amp[k]) or None.
    `captured` is the share of the low-passed hit's energy the glide explains."""
    n = int(span_s * rate)
    x = hit[:n]
    if len(x) < int(0.03 * rate):
        return None
    f_max = max_f0
    r = int(min(rate, max(4000, 2.5 * f_max)))
    down = max(1, rate // r)
    r = rate // down
    y = signal.resample_poly(x, 1, down) if down > 1 else x.copy()
    t = np.arange(len(y)) / r
    total = np.sum(y ** 2) / 2 + 1e-18

    def score(f0, f1, tau, law):
        return np.sum(np.abs(_kept(y, t, r, f0, f1, tau, 1, law)) ** 2)

    best = None
    for law in LAWS:
        for f1 in root_guess * np.geomspace(0.7, 1.35, 15):
            for ratio in np.geomspace(1.0, f_max / f1, 22):
                for tau in np.geomspace(0.002, 0.25, 12):
                    s = score(f1 * ratio, f1, tau, law)
                    if best is None or s > best[0]:
                        best = (s, f1 * ratio, f1, tau, law)
    _, f0, f1, tau, law = best

    def neg(p):
        lf1, lratio, ltau = p
        f1_ = np.exp(lf1)
        return -score(f1_ * np.exp(max(lratio, 0)), f1_, np.exp(ltau), law)
    res = optimize.minimize(neg, [np.log(f1), np.log(f0 / f1), np.log(tau)],
                            method="Nelder-Mead", options=dict(xatol=1e-3, fatol=1e-6,
                                                               maxiter=400))
    lf1, lratio, ltau = res.x
    f1 = float(np.exp(lf1))
    if not (20 <= f1 <= 1500):
        return None  # not a drum body: a hat's noise can look "captured" by a high tone
    f0 = float(min(f1 * np.exp(max(lratio, 0)), f_max, r * 0.45))
    tau = float(np.clip(np.exp(ltau), 0.001, 1.0))
    amps = {}
    for k in (1, 2, 3, 4):
        if k * f0 > r * 0.45:
            break
        amps[k] = 2 * np.abs(_kept(y, t, r, f0, f1, tau, k, law))
    captured = float(-res.fun / total)
    return dict(f0=f0, f1=f1, tau=tau, law=law, captured=captured, rate=r, t=t, amp=amps)
