"""DRAWL round 1: the Kotlin engine beside the Python prototype the owner kept, clip for clip.

    python3 prototype/drawl/compare_kotlin.py PY_AUDITION_DIR KOTLIN_AUDITION_DIR [OUT_JSON]

PY_AUDITION_DIR is gen_audition.py's output (round 0d); KOTLIN_AUDITION_DIR is
`./gradlew :synth:generateDrawlAudition -PdrawlOutput=DIR`. Both name their clips the same way.
The two never match sample for sample (the noise generators differ), so this compares what a
listener hears: brightness, the 1-4 kHz band, decay, resonance peaks, vibrato depth and rate,
how much a hard pick changes level and brightness, and how far apart the takes sit. Absolute
level is not compared: Kotlin levels to the house loudness target, the prototype to RMS 0.1.
Python harness measuring both.
"""
import itertools
import json
import os
import sys

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import measure  # noqa: E402

VOICES = ["cone", "bottle", "lap", "steel", "flattop", "gut"]
ROOT_MIDI = {"cone": 57, "bottle": 62, "lap": 62, "steel": 64, "flattop": 50, "gut": 52}


def load(d, name):
    x, sr = measure.load(os.path.join(d, name))
    return x, sr


def band_db(x, sr, lo, hi, a, b):
    seg = x[int(a * sr):int(b * sr)]
    f, p = signal.welch(seg, sr, nperseg=4096)
    return float(10 * np.log10(p[(f >= lo) & (f < hi)].sum() / p[f >= 40].sum()))


def onset_centroid(x, sr, ms=80):
    n = int(ms * sr / 1000)
    seg = x[:n] / max(np.max(np.abs(x[:int(.3 * sr)])), 1e-9) * np.hanning(n)
    mag = np.abs(np.fft.rfft(seg, 16384))
    f = np.fft.rfftfreq(16384, 1 / sr)
    return float((f * mag).sum() / mag.sum())


def vibrato(x, sr, target_hz, start=.6, end=2.4):
    f0, dt = measure.pitch_track(x[: int(end * sr)], sr)
    c = 1200 * np.log2(f0 / target_hz)[int(start / dt):]
    best, s0 = (0, 0), None
    for i, ok in enumerate(np.append(np.isfinite(c) & (np.abs(c) < 600), False)):
        if ok and s0 is None:
            s0 = i
        elif not ok and s0 is not None:
            best = max(best, (i - s0, s0))
            s0 = None
    seg = c[best[1]:best[1] + best[0]]
    if len(seg) * dt < .4:
        return None, None
    seg = seg - seg.mean()
    sp = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), 1 << 14))
    f = np.fft.rfftfreq(1 << 14, dt)
    band = (f > 3) & (f < 10)
    return round(float(2 * np.sqrt(2) * np.std(seg))), round(float(f[band][np.argmax(sp[band])]), 2)


def spectral_distance(a, b, sr):
    n = min(len(a), len(b))
    fa, pa = signal.welch(a[:n], sr, nperseg=4096)
    _, pb = signal.welch(b[:n], sr, nperseg=4096)
    m = (fa > 40) & (fa < 12000)
    return float(np.mean(np.abs(10 * np.log10(pa[m] + 1e-14) - 10 * np.log10(pb[m] + 1e-14))))


def side(d, v):
    x, sr = load(d, f"voices/{v}-mid.wav")
    cen, shares, peaks = measure.ltas(x, sr)
    soft, _ = load(d, f"velocity/{v}-soft.wav")
    hard, _ = load(d, f"velocity/{v}-hard.wav")
    rms = lambda y: float(np.sqrt(np.mean(y * y)))
    wide, _ = load(d, f"pairs/{v}-slide1-waver1.wav")
    target = 440 * 2 ** ((ROOT_MIDI[v] - 69) / 12)
    pp_default, rate_default = vibrato(x, sr, target)
    pp_wide, rate_wide = vibrato(wide, sr, target)
    takes = [load(d, f"takes/{v}-take{t}.wav")[0] for t in range(4)]
    spread = [spectral_distance(a, b, sr) for a, b in itertools.combinations(takes, 2)]
    return {
        "centroid_hz": round(cen),
        "band_1_4k_sustain_db": round(band_db(x, sr, 1000, 4000, .5, 1.5), 1),
        "decay_db_per_s": measure.decay(x, sr, np.array([0.0]))[0],
        "ltas_peaks_hz": [p[0] for p in peaks],
        "vibrato_pp_default": pp_default, "vibrato_hz_default": rate_default,
        "vibrato_pp_wide": pp_wide, "vibrato_hz_wide": rate_wide,
        "hard_over_soft_db": round(20 * np.log10(rms(hard) / rms(soft)), 1),
        "onset_centroid_soft_hard": [round(onset_centroid(soft, sr)), round(onset_centroid(hard, sr))],
        "take_spread_db": [round(min(spread), 2), round(max(spread), 2)],
    }


def main():
    py, kt = sys.argv[1], sys.argv[2]
    out = {}
    for v in VOICES:
        a, b = side(py, v), side(kt, v)
        out[v] = {"python_heard": a, "kotlin": b}
        print(f"\n{v.upper()}")
        for k in a:
            print(f"  {k:28s} heard {str(a[k]):>16s}   kotlin {str(b[k]):>16s}")
    if len(sys.argv) > 3:
        json.dump(out, open(sys.argv[3], "w"), indent=1)


if __name__ == "__main__":
    main()
