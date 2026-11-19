"""The Kotlin port against the approved prototype, voice by voice (Python harness).

    python3 riddim_proto.py PY && ./gradlew :synth:generateRiddimAudition -PriddimOutput=KT
    python3 compare_kotlin.py PY KT

Takes are different random strikes on each side (Kotlin draws from its own generator, the same laws), so a
sample-by-sample comparison means nothing. Instead, per voice and velocity, both sides' takes are averaged as
power spectra in third-octave bands (25 Hz to 16 kHz) over the first 300 ms and over the whole hit, and the
band-by-band difference is reported: its RMS over the bands that hold the sound (within 40 dB of the loudest
band), and its worst band. The -20 dB decay is averaged the same way.
"""
import glob
import os
import sys
import wave

import numpy as np

RATE = 44100
BANDS = [25 * 2 ** (k / 3) for k in range(30) if 25 * 2 ** (k / 3) <= 16000]


def rd(p):
    with wave.open(p) as w:
        return np.frombuffer(w.readframes(w.getnframes()), "<i2") / 32768


def band_db(x):
    S = np.abs(np.fft.rfft(x, 1 << 17)) ** 2
    f = np.fft.rfftfreq(1 << 17, 1 / RATE)
    out = []
    for fc in BANDS:
        sel = (f >= fc / 2 ** (1 / 6)) & (f < fc * 2 ** (1 / 6))
        out.append(S[sel].sum())
    return np.array(out)


def decay_ms(x, hop=0.002):
    h = int(RATE * hop); m = len(x) // h
    e = np.sqrt((x[: m * h] ** 2).reshape(m, h).mean(1)) + 1e-12
    k = int(np.argmax(e)); a = np.where(e[k:] < e[k] * 0.1)[0]
    return (a[0] if len(a) else m - k) * hop * 1000


def side(root, voice, vel):
    files = sorted(glob.glob(os.path.join(root, "voices", f"{voice}_v{vel}_t*.wav")))
    xs = [rd(p) for p in files]
    early = np.mean([band_db(x[: int(.3 * RATE)]) for x in xs], 0)
    whole = np.mean([band_db(x) for x in xs], 0)
    return early, whole, float(np.mean([decay_ms(x) for x in xs])), len(xs)


def dist(a, b):
    da, db = 10 * np.log10(a / a.sum() + 1e-20), 10 * np.log10(b / b.sum() + 1e-20)
    keep = (da > da.max() - 40) | (db > db.max() - 40)
    d = (db - da)[keep]
    return float(np.sqrt(np.mean(d ** 2))), float(np.max(np.abs(d)))


def main(py, kt):
    voices = sorted({os.path.basename(p).split("_v")[0] for p in glob.glob(os.path.join(kt, "voices", "*.wav"))})
    print(f"{'voice':12s} {'vel':>4s}  {'first 300 ms: rms / worst band':>30s}  {'whole: rms / worst':>20s}  decay py / kt (ms)")
    rows = []
    for v in voices:
        for vel in ("040", "100"):
            if not glob.glob(os.path.join(py, "voices", f"{v}_v{vel}_t*.wav")):
                continue
            pe, pw, pd, _ = side(py, v, vel)
            ke, kw, kd, _ = side(kt, v, vel)
            e, w = dist(pe, ke), dist(pw, kw)
            rows.append((v, vel, e, w, pd, kd))
            print(f"{v:12s} {vel:>4s}  {e[0]:14.1f} / {e[1]:5.1f} dB    {w[0]:8.1f} / {w[1]:5.1f} dB   {pd:6.0f} / {kd:6.0f}")
    worst = max(r[2][0] for r in rows)
    print(f"\nworst voice, first 300 ms band RMS: {worst:.1f} dB")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
