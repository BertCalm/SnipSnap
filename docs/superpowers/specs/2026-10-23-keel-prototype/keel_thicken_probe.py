"""Measure the note's own overtones 2-4 relative to its fundamental, early and late, before WEIGHT."""
import math, numpy as np
import keel_proto as kp, snipkit as sk
INTERNAL = sk.INTERNAL

def period_mean(z: np.ndarray, period: int) -> np.ndarray:
    """Two cascaded moving averages exactly one period long: nulls every other harmonic while the pitch is steady."""
    for _ in range(2):
        c = np.concatenate([[0], np.cumsum(z)])
        out = np.empty_like(z)
        out[period:] = (c[period + 1:] - c[1:-period]) / period
        out[:period] = c[1:period + 1] / np.arange(1, period + 1)
        z = out
    return z

def source(voice, midi, macros):
    m = {**kp.MACROS, **macros}
    hz = kp.hz_for(midi)
    length = kp.length_seconds(m)
    n = int(1.15 * length * INTERNAL)
    s = kp.settle_curve(n, INTERNAL, kp.half_life_seconds(m))
    f, phase = kp.pitch_track(hz, m["SLIDE"], n, INTERNAL)
    src = kp.VOICE_FN[voice](m, hz, n, INTERNAL, s, f, phase, sk.seed_for("KEEL", voice, midi))
    src /= max(1e-9, np.max(np.abs(src))) / 0.8
    src = kp.grit(src, m["GRIT"])
    return src * kp.amp_env(n, INTERNAL, length), phase, hz, s

for voice, midi, name in [("UPRIGHT", 36, "C2"), ("REDUCE", 42, "F#2"), ("REDUCE", 30, "F#1")]:
    x, phase, hz, s = source(voice, midi, {"LENGTH": 0.8, "WEIGHT": 0.0})
    per = int(round(INTERNAL / hz))
    amps = {k: 2 * np.abs(period_mean(x * np.exp(-2j * math.pi * k * phase), per)) for k in range(1, 5)}
    for lab, a, b in [("early 0.15-0.40 s", 0.15, 0.40), ("late 0.50-0.90 s", 0.50, 0.90)]:
        i, j = int(a * INTERNAL), int(b * INTERNAL)
        a1 = amps[1][i:j].mean()
        rel = "  ".join(f"h{k} {20*math.log10(amps[k][i:j].mean()/a1+1e-12):6.1f} dB" for k in range(2, 5))
        print(f"{voice} {name} {lab}: {rel}   (settle s from {s[i]:.2f} to {s[j-1]:.2f})")
