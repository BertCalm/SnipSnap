"""SIGIL round 1: the Kotlin port against the approved prototype, pad by pad. Python harness.

    python3 prototype/sigil/compare_kotlin.py PY_OUT KOTLIN_OUT [report.json]

PY_OUT is gen.py's output; KOTLIN_OUT is `./gradlew :synth:generateSigilAudition`'s (testkit/sigil-audition/kotlin).
The two languages draw from different generators, so a Kotlin hit is another take of the approved pad, never the same
samples. The house distance (CALABASH's): each render's third-octave spectrum (40 Hz to 16 kHz, bands within 30 dB
of its loudest), the mean absolute dB difference between two renders. "within" is the median distance between two
takes of one language; "across" the median between a Python take and a Kotlin take. A port that changed the sound
shows as across well over within. Also each zone's peak, length and 20 dB decay, and the soft-to-hard brightness.
"""
from __future__ import annotations

import itertools
import json
import os
import sys
import wave

import numpy as np

RATE = 44100
CENTRES = 1000 * 2 ** (np.arange(-14, 13) / 3)  # 40 Hz .. 16 kHz


def read(path):
    with wave.open(path, "rb") as w:
        sw, n = w.getsampwidth(), w.getnframes()
        raw = w.readframes(n)
    if sw == 2:
        return np.frombuffer(raw, "<i2").astype(float) / 32768
    if sw == 3:
        b = np.frombuffer(raw, np.uint8).reshape(-1, 3)
        v = b[:, 0].astype(np.int32) | (b[:, 1].astype(np.int32) << 8) | (b[:, 2].astype(np.int32) << 16)
        return np.where(v >= 1 << 23, v - (1 << 24), v).astype(float) / (1 << 23)
    if sw == 4:
        return np.frombuffer(raw, "<f4").astype(float)
    raise ValueError(sw)


def third_octaves(x):
    n = 1 << int(np.ceil(np.log2(max(len(x), 4096))))
    sp = np.abs(np.fft.rfft(x * np.hanning(len(x)), n)) ** 2
    f = np.fft.rfftfreq(n, 1 / RATE)
    out = np.array([sp[(f >= c / 2 ** (1 / 6)) & (f < c * 2 ** (1 / 6))].sum() for c in CENTRES])
    return 10 * np.log10(out + 1e-20)


def distance(a, b):
    sa, sb = third_octaves(a), third_octaves(b)
    top = max(sa.max(), sb.max())
    keep = (sa > top - 30) | (sb > top - 30)
    return float(np.mean(np.abs(sa[keep] - sb[keep])))


def decay_ms(x, drop_db=20):
    env = np.sqrt(np.convolve(x ** 2, np.ones(220) / 220, mode="same"))
    pk = int(np.argmax(env))
    after = np.where(env[pk:] < env[pk] * 10 ** (-drop_db / 20))[0]
    return float(after[0] / RATE * 1000) if len(after) else float((len(x) - pk) / RATE * 1000)


def main(py_root, kt_root, out_json=None):
    rows = []
    for kit in sorted(os.listdir(kt_root)):
        kdir = os.path.join(kt_root, kit)
        if not os.path.isdir(kdir):
            continue
        for pad in sorted(os.listdir(kdir)):
            if not os.path.isdir(os.path.join(kdir, pad)):
                continue
            for z in range(3):
                py = [read(os.path.join(py_root, kit, pad, f"z{z}_t{t}.wav")) for t in range(4)]
                kt = [read(os.path.join(kdir, pad, f"z{z}_t{t}.wav")) for t in range(4)]
                within = [distance(a, b) for a, b in itertools.combinations(py, 2)] + \
                         [distance(a, b) for a, b in itertools.combinations(kt, 2)]
                across = [distance(a, b) for a in py for b in kt]
                rows.append({
                    "kit": kit, "pad": pad, "zone": z,
                    "within_db": float(np.median(within)), "across_db": float(np.median(across)),
                    "excess_db": float(np.median(across) - np.median(within)),
                    "peak_py": float(np.median([np.max(np.abs(x)) for x in py])),
                    "peak_kt": float(np.median([np.max(np.abs(x)) for x in kt])),
                    "len_py": len(py[0]), "len_kt": len(kt[0]),
                    "decay_py_ms": float(np.median([decay_ms(x) for x in py])),
                    "decay_kt_ms": float(np.median([decay_ms(x) for x in kt])),
                })
        gp, gk = os.path.join(py_root, kit, "groove.wav"), os.path.join(kdir, "groove.wav")
        if os.path.exists(gp) and os.path.exists(gk):
            rows.append({"kit": kit, "pad": "groove", "zone": 2, "across_db": distance(read(gp), read(gk)),
                         "within_db": None, "excess_db": None, "len_py": len(read(gp)), "len_kt": len(read(gk))})
    ex = [r["excess_db"] for r in rows if r["excess_db"] is not None]
    worst = sorted([r for r in rows if r["excess_db"] is not None], key=lambda r: -r["excess_db"])[:10]
    print(f"{len(ex)} pad zones: across minus within, median {np.median(ex):.2f} dB, largest {max(ex):.2f} dB")
    for r in worst:
        print(f"  {r['kit']:15s} {r['pad']:22s} z{r['zone']} within {r['within_db']:.2f} across {r['across_db']:.2f}"
              f"  decay {r['decay_py_ms']:.0f}/{r['decay_kt_ms']:.0f} ms  peak {r['peak_py']:.2f}/{r['peak_kt']:.2f}")
    lens = [r for r in rows if r["len_py"] != r["len_kt"]]
    print(f"length mismatches: {len(lens)}", [(r['kit'], r['pad'], r['len_py'], r['len_kt']) for r in lens[:5]])
    for r in rows:
        if r["pad"] == "groove":
            print(f"  groove {r['kit']:15s} across {r['across_db']:.2f} dB (one render a side)")
    if out_json:
        json.dump(rows, open(out_json, "w"), indent=1)


if __name__ == "__main__":
    main(*sys.argv[1:4])
