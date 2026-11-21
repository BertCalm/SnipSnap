"""Transcribe a reference loop of a struck tuned instrument: onsets, the note each onset starts,
and how hard. Python harness, not Kotlin.

A new note is found by what the onset ADDS: the spectrum 15..75 ms after the onset minus the
spectrum just before it, so ringing earlier notes cancel. The strongest added peak in the
instrument's range is taken as the note (its fundamental, for these instruments, after an octave
check against a peak half its frequency). Velocity comes from the onset's rise in level.
"""
from __future__ import annotations

import numpy as np
from scipy import signal
from scipy.io import wavfile

R = 44100


def load(path: str) -> np.ndarray:
    r, x = wavfile.read(path)
    x = x.astype(float)
    if x.ndim == 2:
        x = x.mean(1)
    return x / (np.max(np.abs(x)) + 1e-12)


def onsets(x: np.ndarray, min_gap: float = .09, rise_db: float = 6.0) -> np.ndarray:
    hop = 128
    env = np.sqrt(np.convolve(x * x, np.ones(hop) / hop, 'same'))[::hop]
    db = 20 * np.log10(env + 1e-9)
    pk, _ = signal.find_peaks(np.diff(db, prepend=db[0]), height=rise_db, distance=int(min_gap * R / hop))
    return pk * hop


def spectrum(seg: np.ndarray):
    S = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), 1 << 16))
    return S, np.fft.rfftfreq(1 << 16, 1 / R)


def transcribe(x: np.ndarray, lo_hz: float, hi_hz: float):
    notes = []
    for o in onsets(x):
        pre = x[max(0, o - int(.065 * R)): max(0, o - int(.005 * R))]
        post = x[o + int(.015 * R): o + int(.075 * R)]
        if len(post) < int(.05 * R):
            continue
        Sp, f = spectrum(post)
        Sb = spectrum(np.pad(pre, (0, max(0, len(post) - len(pre)))))[0] if len(pre) > 64 else np.zeros_like(Sp)
        added = np.clip(Sp - 1.0 * Sb, 0, None)
        band = (f >= lo_hz) & (f <= hi_hz)
        i = int(np.argmax(np.where(band, added, 0)))
        f0 = f[i]
        half = (f > f0 / 2 * .97) & (f < f0 / 2 * 1.03) & band
        if half.any() and added[half].max() > .35 * added[i]:
            f0 = f[half][np.argmax(added[half])]                     # the octave below is the note
        midi = 69 + 12 * np.log2(f0 / 440)
        e_pre = np.sqrt(np.mean(pre ** 2)) if len(pre) else 1e-6
        e_post = np.sqrt(np.mean(x[o: o + int(.03 * R)] ** 2))
        notes.append(dict(t=o / R, midi=float(midi), rise_db=float(20 * np.log10((e_post + 1e-9) / (e_pre + 1e-9))),
                          level=float(e_post)))
    if notes:
        lv = np.array([n['level'] for n in notes])
        ref = np.percentile(lv, 90)
        for n in notes:
            n['vel'] = float(np.clip(.35 + .6 * (n['level'] / ref), .3, 1.0))      # shape: level -> velocity
    return notes
