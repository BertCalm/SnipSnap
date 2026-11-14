"""Measure the CLUTTER Kotlin port against the accepted round-0c Python prototype, object by object.

Reads the clips `./gradlew :synth:generateClutterAudition` wrote (every object hard, soft and at its four
takes), renders the same clips from `clutter_proto.py`, and prints and writes `kotlin.json` with:

- the distance from the Kotlin hard hit to the Python hard hit, beside the spread of Python's own four takes
  (the two models draw their random numbers differently, so the strike point and the loose parts land
  elsewhere: a port that is right sits about as far from the prototype as one take sits from another);
- the waveform correlation after aligning by cross-correlation;
- centroids and decays of both, and soft against hard in each model;
- the closest two of Kotlin's four takes.

Python harness measurements. Run:  python3 compare_kotlin.py KOTLIN_DIR [OUT_JSON]
"""
from __future__ import annotations

import itertools
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import clutter_proto as e  # noqa: E402
import measures as ms  # noqa: E402

KOTLIN = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "..", "testkit", "clutter-audition", "kotlin")
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(KOTLIN, "kotlin.json")


def read(section, name):
    y, rate = e.sk.read_wav(os.path.join(KOTLIN, section, name + ".wav"))
    assert rate == e.RATE, rate
    return y


def aligned_corr(a, b):
    n = min(len(a), len(b))
    a, b = a[:n], b[:n]
    c = np.correlate(a[: n // 2], b[: n // 2], mode="full")
    lag = int(np.argmax(np.abs(c))) - (n // 2 - 1)
    if lag > 0:
        a = a[lag:]
    elif lag < 0:
        b = b[-lag:]
    m = min(len(a), len(b))
    return round(float(np.corrcoef(a[:m], b[:m])[0, 1]), 3), lag


rows = []
for v in e.VOICES:
    tag = v.lower()
    kh, ks = read("hard", tag), read("soft", tag)
    kt = [read("takes", f"{tag}-take{t}") for t in range(4)]
    ph, ps = e.render(v), e.render(v, velocity=0.35)
    pt = [e.render(v, take=t) for t in range(4)]
    p_spread = [ms.take_distance_db(a, b) for a, b in itertools.combinations(pt, 2)]
    k_spread = [ms.take_distance_db(a, b) for a, b in itertools.combinations(kt, 2)]
    to_py = min(ms.take_distance_db(kh, p) for p in pt)
    corr, lag = aligned_corr(kh, ph)
    row = {
        "object": v,
        "kotlin_to_nearest_python_take_db": to_py,
        "python_takes_db": [min(p_spread), max(p_spread)],
        "within_python_spread": bool(to_py <= max(p_spread) * 1.25),
        "correlation": corr, "lag_samples": lag,
        "centroid_hz": [ms.centroid(kh), ms.centroid(ph)],
        "decay_ms": [ms.decay_ms(kh), ms.decay_ms(ph)],
        "soft_vs_hard_db": [ms.take_distance_db(ks, kh), ms.take_distance_db(ps, ph)],
        "kotlin_closest_takes_db": min(k_spread),
        "seconds": [round(len(kh) / e.RATE, 3), round(len(ph) / e.RATE, 3)],
    }
    rows.append(row)
    print(f"{v:9s} K→P {to_py:5.1f} dB (P takes {min(p_spread):4.1f}..{max(p_spread):4.1f}) "
          f"{'ok ' if row['within_python_spread'] else 'WIDE'} corr {corr:6.3f}  "
          f"centroid {row['centroid_hz'][0]:5d}/{row['centroid_hz'][1]:5d}  decay {row['decay_ms'][0]:5.0f}/{row['decay_ms'][1]:5.0f}  "
          f"soft/hard {row['soft_vs_hard_db'][0]:5.1f}/{row['soft_vs_hard_db'][1]:5.1f}  K takes ≥ {row['kotlin_closest_takes_db']:4.1f}")

json.dump(rows, open(OUT, "w"), indent=1)
wide = [r["object"] for r in rows if not r["within_python_spread"]]
print("wider than Python's own takes:", wide or "none")
print("wrote", OUT)
