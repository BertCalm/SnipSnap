"""Resynthesis A/B: CUPOLA plays what a reference loop plays, then both are measured the same way.

1. transcribe.py finds onsets, notes and accents.
2. The file's global tuning offset is removed (a circular mean of the cents offsets), each note
   snaps to the scale its file name gives, and the offset is put back, so CUPOLA plays in the
   reference's own tuning.
3. The shell's layout is the set of notes the reference uses (its own instrument), so the
   sympathetic answer comes from the same neighbours.
4. Both excerpts are measured identically: third-octave long-term spectrum, attack brightness,
   how much rings 300 ms after each hit, and the level between hits.
Python harness, not Kotlin.
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402
import refs  # noqa: E402
import transcribe as tr  # noqa: E402
import cupola_proto as cp  # noqa: E402

R = cp.RATE
sk = cp.sk
NAMES = 'C C# D D# E F F# G G# A A# B'.split()
MAJOR, MINOR = [0, 2, 4, 5, 7, 9, 11], [0, 2, 3, 5, 7, 8, 10]

# reference pattern, voice, key root pitch class, mode, transcription range (Hz), excerpt (s)
CASES = [
    ("handpan_Fmin_loop4_120", "HANDPAN", 5, MINOR, (70, 900), 8.0),
    ("handpan_Fmin_loop_108", "HANDPAN", 5, MINOR, (70, 900), 8.0),
    ("SC_RV_95_arpeggios_delays_dry_Gbm", "TONGUE", 6, MINOR, (100, 1200), 8.0),
    ("SC_RV_120_calm_melody_D", "TONGUE", 2, MAJOR, (100, 1200), 8.0),
    ("sundrum_melody_Gmin_loop_100", "TANK", 7, MINOR, (50, 500), 8.0),
    ("sundrum_melody_Amin_loop2_80", "TANK", 9, MINOR, (50, 500), 8.0),
]


def snap(notes, root, mode):
    cents = np.array([100 * (n['midi'] - round(n['midi'])) for n in notes])
    w = np.array([n['vel'] for n in notes])
    ang = np.angle(np.sum(w * np.exp(2j * np.pi * cents / 100)))
    detune = 100 * ang / (2 * np.pi)                                  # cents, in (-50, 50]
    allowed = np.array([o * 12 + root + s for o in range(0, 10) for s in mode])
    out = []
    for n in notes:
        m = n['midi'] - detune / 100
        k = int(allowed[np.argmin(np.abs(allowed - m))])
        out.append(dict(n, note=k))
    return out, detune


def third_octave_db(x):
    c = 80.0 * 2.0 ** (np.arange(21) / 3.0)
    S = np.abs(np.fft.rfft(x * np.hanning(len(x)))) ** 2
    f = np.fft.rfftfreq(len(x), 1 / R)
    tot = S[(f > 60) & (f < 9000)].sum()
    return c, np.array([10 * np.log10(S[(f >= a / 2 ** (1 / 6)) & (f < a * 2 ** (1 / 6))].sum() / tot + 1e-15) for a in c])


def measures(x, times):
    """Measured identically on the reference and the render, over the same onset schedule."""
    att, ring300, floor = [], [], []
    k = int(.005 * R)
    env = np.sqrt(np.convolve(x * x, np.ones(k) / k, 'same'))
    for i, t in enumerate(times):
        o = int(t * R)
        nxt = int(times[i + 1] * R) if i + 1 < len(times) else len(x)
        a = x[o: o + int(.01 * R)]
        b = x[o + int(.03 * R): o + int(.13 * R)]
        if len(b) < int(.09 * R):
            continue
        A = np.abs(np.fft.rfft(a * np.hanning(len(a)), 4096)) ** 2; fa = np.fft.rfftfreq(4096, 1 / R)
        att.append(10 * np.log10(A[fa > 2000].sum() / (A.sum() + 1e-20) + 1e-12))
        pk = env[o: o + int(.03 * R)].max() + 1e-12
        if nxt - o > int(.32 * R):
            ring300.append(20 * np.log10(env[o + int(.3 * R)] / pk + 1e-12))
        if nxt - o > int(.12 * R):
            floor.append(20 * np.log10(env[nxt - int(.01 * R)] / pk + 1e-12))
    S = np.abs(np.fft.rfft(x * np.hanning(len(x)))); f = np.fft.rfftfreq(len(x), 1 / R)
    centroid = float((S * f)[(f > 40) & (f < 10000)].sum() / S[(f > 40) & (f < 10000)].sum())
    return dict(attack_above_2k_dB=float(np.median(att)), ring_300ms_dB=float(np.median(ring300)) if ring300 else None,
                level_before_next_hit_dB=float(np.median(floor)) if floor else None, centroid_Hz=centroid)


def render_case(pattern, voice, root, mode, band, seconds, macros=None, layout_extra=0):
    x = tr.load(refs.one(pattern))
    ex = x[: int(seconds * R)]
    notes, detune = snap(tr.transcribe(ex, *band), root, mode)
    layout = sorted(set(n['note'] for n in notes))
    m = {**cp.defaults(voice), **(macros or {})}
    orig = cp.fields_for
    cp.fields_for = lambda v, mm, _l=layout: [k + detune / 100 for k in _l]     # the reference's own instrument
    try:
        tail = min(cp.VOICES[voice]["seconds"], 4.0)
        mix = np.zeros(int((seconds + tail) * R))
        for i, n in enumerate(notes):
            raw, _ = cp.render(voice, m, midi=n['note'] + detune / 100, vel=n['vel'], take=i % 4, seconds=tail)
            y = sk.fade_tail(sk.decimate4(sk.band_limit(raw, cp.INTERNAL)), R, 150.0)
            o = int(n['t'] * R); mix[o:o + len(y)] += y[: len(mix) - o]
    finally:
        cp.fields_for = orig
    mix = sk.dc_block(mix)[: len(ex)]
    return ex, mix, notes, detune, layout


def level(x):
    return x * (.12 / (np.sqrt(np.mean(x ** 2)) + 1e-12))
