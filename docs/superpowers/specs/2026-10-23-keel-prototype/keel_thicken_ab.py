"""KEEL WEIGHT as thickening, A/B for the owner's ear (2026-10-26).

Two listens heard the sub-octave WEIGHT as nothing: every change sat below 60 Hz. This WEIGHT
works on the note's own overtones 2 to 4, where hearing works, and replaces the sub entirely.

For k = 2, 3, 4 the note's k-th overtone is read as a complex amplitude C_k(t) by synchronous
demodulation on the note's own phase track, averaged over exactly one period (twice), which
nulls every other harmonic while the pitch is steady (a one-pole would leak the neighbours, the
fault found in the sub's envelope follower). Its target amplitude is

    A_1 -> A_1 * boost,   T_k = max(A_k * boost, floor_k * A_1 * boost)  for k = 2, 3, 4
    boost = 10^(9 dB * w / 20),  floor_k = w * (-3, -6, -9 dB)

with w = WEIGHT * (0.5 + 0.5 * (1 - s(t))), so the body fills in as the settle removes the top.
Overtones 1 to 4 rise together, so the note's own balance and pitch are kept (boosting 2 to 4
alone read REDUCE at F#1 an octave high). Only the difference T_k - A_k is added, in the overtone's own phase (or,
where the note has none, in phase with the fundamental's k-th multiple), so nothing cancels.
Applied after GRIT and before the amplitude envelope. Clips are levelled with the house
Loudness measure, as the Kotlin does.
"""
from __future__ import annotations

import json
import math
import os
import sys

import numpy as np

import keel_proto as kp
import keel_sub_ab as ab
import probes as pr
import snipkit as sk

RATE = sk.RATE
INTERNAL = sk.INTERNAL
BOOST_DB = 9.0
FLOOR_DB = {2: -3.0, 3: -6.0, 4: -9.0}


def period_mean(z: np.ndarray, period: int) -> np.ndarray:
    for _ in range(2):
        c = np.concatenate([[0], np.cumsum(z)])
        out = np.empty_like(z)
        out[period:] = (c[period + 1:] - c[1:-period]) / period
        out[:period] = c[1:period + 1] / np.arange(1, period + 1)
        z = out
    return z


def thicken(x: np.ndarray, phase: np.ndarray, hz: float, w: np.ndarray) -> np.ndarray:
    """Raise overtones 1 to 4 together by the same gain, so the note's own balance (and its pitch)
    is kept while its lower body grows against the top; give a missing overtone a floor."""
    per = int(round(INTERNAL / hz))
    c1 = period_mean(x * np.exp(-2j * math.pi * phase), per)
    a1 = 2.0 * np.abs(c1)
    unit1 = c1 / np.maximum(np.abs(c1), 1e-12)
    boost = 10.0 ** (BOOST_DB * w / 20.0)
    added = (boost - 1.0) * a1 * np.real(unit1 * np.exp(2j * math.pi * phase))
    for k, floor_db in FLOOR_DB.items():
        ck = period_mean(x * np.exp(-2j * math.pi * k * phase), per)
        ak = 2.0 * np.abs(ck)
        target = np.maximum(ak * boost, 10.0 ** (floor_db / 20.0) * w * a1 * boost)
        extra = np.maximum(target - ak, 0.0)
        # The overtone's own phase where it exists; the fundamental's k-th multiple where it does not.
        p = ck + 0.02 * np.abs(c1) * unit1 ** k
        uk = p / np.maximum(np.abs(p), 1e-12)
        added += extra * np.real(uk * np.exp(2j * math.pi * k * phase))
    return x + added


def render(voice: str, macros: dict, midi: int) -> np.ndarray:
    m = {**kp.MACROS, **macros}
    hz = kp.hz_for(midi)
    length = kp.length_seconds(m)
    n = int(1.15 * length * INTERNAL)
    s = kp.settle_curve(n, INTERNAL, kp.half_life_seconds(m))
    f, phase = kp.pitch_track(hz, m["SLIDE"], n, INTERNAL)
    src = kp.VOICE_FN[voice](m, hz, n, INTERNAL, s, f, phase, sk.seed_for("KEEL", voice, midi))
    src /= max(1e-9, np.max(np.abs(src))) / 0.8
    src = kp.grit(src, m["GRIT"])
    w = m["WEIGHT"] * (0.5 + 0.5 * (1.0 - s))
    if m["WEIGHT"] > 0:
        src = thicken(src, phase, hz, w)
    return src * kp.amp_env(n, INTERNAL, length)


def band_db(x: np.ndarray, lo: float, hi: float, a: float, b: float) -> float:
    from scipy import signal
    sos = signal.butter(4, [lo, hi], btype="band", fs=RATE, output="sos")
    y = signal.sosfilt(sos, x)[int(a * RATE):int(b * RATE)]
    return 10.0 * math.log10(float(np.mean(y * y)) + 1e-20)


def main(out_dir: str) -> int:
    os.makedirs(out_dir, exist_ok=True)
    notes = [("UPRIGHT", 36, "C2"), ("REDUCE", 42, "Fs2"), ("REDUCE", 30, "Fs1")]
    weights = [0.0, 0.5, 1.0]
    bands = [(60, 120), (120, 400), (400, 4000)]
    windows = [("early", 0.10, 0.45), ("late", 0.45, 0.95)]
    report = []
    for voice, midi, name in notes:
        hz = kp.hz_for(midi)
        ref = None
        for wt in weights:
            one = ab.finish_house(render(voice, {"LENGTH": 0.8, "WEIGHT": wt}, midi))
            gap = np.zeros(int(0.2 * RATE))
            sk.write_wav(os.path.join(out_dir, f"{voice.lower()}_{name}_w{int(wt * 10)}.wav"), np.concatenate([one, gap, one, gap]))
            if ref is None:
                ref = one
            row = {"voice": voice, "note": name, "WEIGHT": wt, "peak": round(float(np.max(np.abs(one))), 3)}
            for wl, a, b in windows:
                for lo, hi in bands:
                    row[f"{wl}_{lo}-{hi}_dB_re_W0"] = round(band_db(one, lo, hi, a, b) - band_db(ref, lo, hi, a, b), 1)
            hz40, _ = pr.pitch(one, RATE, lo_hz=40.0 if hz > 60 else 25.0, hi_hz=1000.0, window_seconds=0.25, start_seconds=0.45)
            row["pitch_cents_late"] = round(pr.cents(hz40, hz), 1) if hz40 > 0 else None
            report.append(row)
            print(json.dumps(row))
    with open(os.path.join(out_dir, "report.json"), "w") as fh:
        json.dump(report, fh, indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "out_thicken_ab"))
