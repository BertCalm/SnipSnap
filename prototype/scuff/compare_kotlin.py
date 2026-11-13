"""Measure the SCUFF Kotlin port against the accepted round-0b Python prototype, pad by pad.

Reads the clips `./gradlew :synth:generateScuffAudition` wrote (kit, velocity, takes, chain stages, the bus
pair), renders the same clips from `scuff_proto.py`, and writes `kotlin.json` into the audition folder with
the distance between each pair (the take-distance measure the page already uses), their centroids and
decays, so `build_page.py` can put every pair on the page with its number. Python harness measurements.

Run:  python3 compare_kotlin.py KOTLIN_DIR OUT_DIR
"""
from __future__ import annotations

import json
import os
import shutil
import sys

import numpy as np

import scuff_proto as e
import snipkit as sk

KOTLIN = sys.argv[1] if len(sys.argv) > 1 else "../../testkit/scuff-audition/kotlin"
OUT = sys.argv[2] if len(sys.argv) > 2 else "scuff-audition"
sys.argv = [sys.argv[0], OUT]  # gen_audition reads OUT from argv when imported for its measures
import gen_audition as g  # noqa: E402  (renders the Python audition into OUT as a side effect)

RATE = e.RATE


def python_clip(section, name):
    """The Python render that matches a Kotlin clip's section and name."""
    v = name.split("-")[0].upper()
    voice = v if v in e.VOICES else None
    if section == "kit":
        return e.render_pad(voice)
    if section == "velocity":
        return e.render_pad(voice, vel=float(name.split("-")[1]))
    if section == "round-robin":
        return e.render_pad(voice, vel=0.8, take=int(name.split("take")[1]))
    if section.startswith("chain-"):
        voice = section.split("-")[1].upper()
        stages = {"1": (), "2": ("grit",), "3": ("grit", "locker"), "4": ("grit", "locker", "bark"), "5": ("grit", "locker", "bark", "slam")}[name[0]]
        return e.render_pad(voice, stages=stages)
    if section == "fuse":
        amt = float(name.split("slam-")[1])
        pair = np.zeros(int(0.5 * RATE))
        for vv in ("KICK", "BOOM"):
            y = e.render_pad(vv, stages=("grit", "locker", "bark"), level=False)
            y = y * (0.89 / np.max(np.abs(y))) * e.PAD_GAIN[vv]
            pair[:len(y)] += y[:len(pair)]
        y = e.slam(pair, amt) if amt > 0 else pair
        return e.finish(y[:len(pair)])
    raise ValueError(section)


def aligned(p, k):
    """The Kotlin clip shifted onto the Python one by cross-correlation: the two resamplers differ by a
    constant lag (about 45 samples, 1 ms), which is latency, not sound. Returns the shifted Kotlin clip,
    the lag, the correlation after alignment and the residual in dB."""
    n = min(len(p), len(k))
    c = np.correlate(k[:n], p[:n], mode="full")
    lag = int(np.argmax(c)) - (n - 1)
    ks = k[lag:] if lag > 0 else np.concatenate([np.zeros(-lag), k])
    m = min(len(p), len(ks))
    a = p[:m] / (np.sqrt(np.mean(p[:m] ** 2)) + 1e-12)
    b = ks[:m] / (np.sqrt(np.mean(ks[:m] ** 2)) + 1e-12)
    corr = float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))
    resid = float(20 * np.log10(np.sqrt(np.mean((a - b) ** 2)) / (np.sqrt(np.mean(a ** 2)) + 1e-12) + 1e-12))
    return ks, lag, round(corr, 3), round(resid, 1)


def measures(x):
    return {"centroid_hz": round(g.centroid(x)), "decay_ms": g.decay60(x), "crest_db": g.crest_db(x), "seconds": round(len(x) / RATE, 3)}


manifest = json.load(open(os.path.join(KOTLIN, "manifest.json")))
pairs = []
for rel in manifest["files"]:
    section, name = rel.split("/")
    name = name[:-4]
    k, kr = sk.read_wav(os.path.join(KOTLIN, rel))
    assert kr == RATE, (rel, kr)
    p = python_clip(section, name)
    dst = os.path.join(OUT, "kotlin", section)
    os.makedirs(dst, exist_ok=True)
    shutil.copyfile(os.path.join(KOTLIN, rel), os.path.join(dst, name + ".wav"))
    pdst = os.path.join(OUT, "kotlin-python", section)
    os.makedirs(pdst, exist_ok=True)
    sk.write_wav(os.path.join(pdst, name + ".wav"), p)
    ks, lag, corr, resid = aligned(p, k)
    pairs.append({"section": section, "name": name, "kotlin": f"kotlin/{section}/{name}.wav", "python": f"kotlin-python/{section}/{name}.wav",
                  "distance_db": g.take_distance_db(p, ks), "lag_samples": lag, "correlation": corr, "residual_db": resid,
                  "python_measures": measures(p), "kotlin_measures": measures(k)})
    print(f"{section:12s} {name:18s} distance {pairs[-1]['distance_db']:5.2f} dB  lag {lag:+d}  corr {corr:.3f}  residual {resid:6.1f} dB  centroid py {measures(p)['centroid_hz']} kt {measures(k)['centroid_hz']}")

# Scale for the distances: the take spread and the velocity step on the Python side.
scale = {}
for v in e.VOICES:
    takes = [e.render_pad(v, vel=0.8, take=t) for t in (1, 2, 3, 4)]
    d = [g.take_distance_db(takes[i], takes[j]) for i in range(4) for j in range(i + 1, 4)]
    scale[v] = {"takes_closest_db": min(d), "takes_widest_db": max(d),
                "velocity_step_db": g.take_distance_db(e.render_pad(v, vel=0.4), e.render_pad(v, vel=1.0))}
json.dump({"model": manifest.get("model"), "pairs": pairs, "scale": scale}, open(os.path.join(OUT, "kotlin.json"), "w"), indent=1)
kit = [p for p in pairs if p["section"] == "kit"]
print("kit distances:", {p["name"]: p["distance_db"] for p in kit})
print("wrote", os.path.join(OUT, "kotlin.json"), len(pairs), "pairs")
