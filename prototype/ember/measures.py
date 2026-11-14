"""Measures for the CLUTTER audition. Python harness, not Kotlin.

`spectro_db` and `take_distance_db` are SCUFF's (prototype/scuff/gen_audition.py), copied rather
than imported because that module renders its own audition when it is imported. Same floor, same
frames, so a CLUTTER number and a SCUFF number mean the same thing."""
from __future__ import annotations

import math

import numpy as np

RATE = 44_100
THIRD_OCT = 80.0 * 2 ** (np.arange(0, 22) / 3)   # 80 Hz to ~10 kHz


def spectro_db(x, ms=5, total=0.15):
    x = x[:int(total * RATE)]
    x = x / (np.sqrt(np.mean(x ** 2)) + 1e-12)
    n = int(ms * 1e-3 * RATE)
    w = 4 * n
    if len(x) < w + n:
        x = np.concatenate([x, np.zeros(w + n - len(x))])
    f = np.fft.rfftfreq(w, 1 / RATE)
    win = np.hanning(w)
    rows = []
    for i in range(0, max(1, len(x) - w), n):
        X = np.abs(np.fft.rfft(x[i:i + w] * win)) ** 2
        rows.append([10 * math.log10(X[(f >= c / 2 ** (1 / 6)) & (f < c * 2 ** (1 / 6))].sum() + 1e-12) for c in THIRD_OCT])
    rows = np.array(rows)
    return np.maximum(rows, rows.max() - 80.0)


def take_distance_db(a, b):
    """RMS difference of two level-matched third-octave spectrograms over the first 150 ms, dB.
    ETCH round 1: takes the owner heard as one strike measured 0.5 to 0.8 dB (unfloored)."""
    A, B = spectro_db(a), spectro_db(b)
    m = min(len(A), len(B))
    return round(float(np.sqrt(np.mean((A[:m] - B[:m]) ** 2))), 2)


def spectrum(x):
    X = np.abs(np.fft.rfft(x * np.hanning(len(x)))) ** 2
    return np.fft.rfftfreq(len(x), 1 / RATE), X


def centroid(x, lo=60.0, hi=16000.0):
    f, X = spectrum(x)
    X = np.sqrt(X)
    b = (f > lo) & (f < hi)
    return round(float((X[b] * f[b]).sum() / (X[b].sum() + 1e-12)))


def band_db(x, lo, hi, ref_lo=30.0, ref_hi=16000.0):
    f, X = spectrum(x)
    num = X[(f >= lo) & (f < hi)].sum()
    den = X[(f >= ref_lo) & (f < ref_hi)].sum()
    return round(float(10 * math.log10((num + 1e-18) / (den + 1e-18))), 1)


def window_db(x, t0, t1, ref_t1=0.005):
    """Energy in t0..t1 against the first ref_t1 seconds, dB: how loud what comes after the hit
    is next to the hit. The rattle shows up here."""
    a = x[int(t0 * RATE):int(t1 * RATE)]
    r = x[:int(ref_t1 * RATE)]
    ea = float(np.mean(a ** 2)) if len(a) else 0.0
    er = float(np.mean(r ** 2)) if len(r) else 1e-18
    return round(10 * math.log10((ea + 1e-18) / (er + 1e-18)), 1)


def decay_ms(x, floor_db=-60.0, block_ms=5.0):
    n = int(block_ms * 1e-3 * RATE)
    env = np.array([np.sqrt(np.mean(x[i:i + n] ** 2)) for i in range(0, len(x) - n, n)])
    if not len(env) or env.max() <= 0:
        return 0.0
    db = 20 * np.log10(env / env.max() + 1e-12)
    above = np.where(db > floor_db)[0]
    return round(float((above[-1] + 1) * block_ms), 0)


def crest_db(x):
    return round(float(20 * np.log10(np.max(np.abs(x)) / (np.sqrt(np.mean(x ** 2)) + 1e-12))), 1)


def dominant_hz(x, lo=40.0, hi=16000.0):
    f, X = spectrum(x)
    b = (f >= lo) & (f < hi)
    return round(float(f[b][np.argmax(X[b])]))


def window_hz(x, t0, t1, lo=30.0, hi=4000.0):
    a, b = int(t0 * RATE), int(t1 * RATE)
    seg = x[a:b]
    seg = np.concatenate([seg * np.hanning(len(seg)), np.zeros(1 << 15)])
    f = np.fft.rfftfreq(len(seg), 1 / RATE)
    X = np.abs(np.fft.rfft(seg))
    m = (f >= lo) & (f < hi)
    return round(float(f[m][np.argmax(X[m])]), 1)
