"""Render the CLUTTER round-0 audition: WAVs per section plus manifest.json with measurements.

Python harness, not Kotlin. Nothing here has been heard by its author. Every one-shot is
peak-matched (0.89), so a pad never wins by being louder; the sampler's velocity gain is not
applied except in the roll and the groove, where it is stated.

Run:  python3 gen_audition.py OUT_DIR      then   python3 build_page.py OUT_DIR
"""
from __future__ import annotations

import itertools
import json
import math
import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import clutter_proto as e  # noqa: E402
import measures as ms  # noqa: E402

sk = e.sk
OUT = sys.argv[1] if len(sys.argv) > 1 else "clutter-audition"
RATE = e.RATE
BPM = 92.0
SIXTEENTH = 60.0 / BPM / 4
ZONES = {"s": 0.35, "m": 0.65, "h": 1.0}   # the three velocity zones the house kits use
TAKES = 4
MANIFEST: dict = {"bpm": BPM, "zones": ZONES, "takes": TAKES, "sections": [], "kit": [], "checks": {}}

# The round-0 kit. Names are plain words for the object, never a product. "shape"
KIT = [
    ("A01", "Bucket", "BUCKET", {}),
    ("A02", "Rice Box", "BOX", {}),
    ("A03", "Rice Jar", "JAR", {}),
    ("A04", "Key Ring", "KEYS", {}),
    ("A05", "Book Slam", "BOOK", {}),
    ("A06", "Knuckle", "TABLE", {}),
    ("A07", "Saucepan", "POT", {}),
    ("A08", "Bottle", "BOTTLE", {}),
    ("A09", "Bucket Side", "BUCKET", {"TUNE": .82, "TOOL": .40, "RATTLE": .20}),
    ("A10", "Empty Box", "BOX", {"TUNE": .30, "RATTLE": 0.0, "DAMP": .55}),
    ("A11", "Stockpot", "POT", {"TUNE": .20, "TOOL": .30, "RATTLE": .90}),
    ("A12", "Full Bottle", "BOTTLE", {"TUNE": .22, "TOOL": .50}),
    ("A13", "Fork Drop", "TABLE", {"TOOL": 1.0, "RATTLE": .85}),
    ("A14", "Coin Jar", "JAR", {"TOOL": .92, "RATTLE": .35}),
]
MIX = {"BUCKET": 1.0, "BOX": .85, "BOOK": .6, "JAR": .42, "KEYS": .38, "TABLE": .5, "POT": .45, "BOTTLE": .4}


def path(section, name):
    d = os.path.join(OUT, section)
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, name + ".wav"), f"{section}/{name}.wav"


def save(section, name, y, note="", metrics=None, extra=None):
    full, rel = path(section, name)
    sk.write_wav(full, y)
    item = {"name": name, "file": rel, "seconds": round(len(y) / RATE, 2), "note": note, "metrics": metrics or {}}
    if extra:
        item.update(extra)
    sec = next((s for s in MANIFEST["sections"] if s["id"] == section), None)
    if sec is None:
        sec = {"id": section, "clips": []}
        MANIFEST["sections"].append(sec)
    sec["clips"].append(item)
    return item


def gap(seconds=0.18):
    return np.zeros(int(seconds * RATE))


def chain(parts, pause=0.18):
    out = []
    for p in parts:
        out += [p, gap(pause)]
    return np.concatenate(out[:-1])


def full_distance_db(a, b):
    """The take distance over the whole render, not only the first 150 ms: DAMP and MIC live in the tail."""
    n = max(len(a), len(b))
    a = np.pad(a, (0, n - len(a)))
    b = np.pad(b, (0, n - len(b)))
    A, B = ms.spectro_db(a, ms=10, total=n / RATE), ms.spectro_db(b, ms=10, total=n / RATE)
    return round(float(np.sqrt(np.mean((A - B) ** 2))), 2)


def place(buf, y, at):
    i = int(round(at * RATE))
    if i >= len(buf):
        return
    n = min(len(y), len(buf) - i)
    buf[i:i + n] += y[:n]


# ------------------------------------------------------------------------------ the kit pads

timings = []
for pad, name, voice, macros in KIT:
    t0 = time.perf_counter()
    hard = e.render(voice, macros, 1.0, 0)
    timings.append((time.perf_counter() - t0) * 1000)
    soft = e.render(voice, macros, ZONES["s"], 0)
    takes = [e.render(voice, macros, 1.0, t) for t in range(TAKES)]
    dists = [ms.take_distance_db(a, b) for a, b in itertools.combinations(takes, 2)]
    m = {"centroid_hz": ms.centroid(hard), "decay_ms": ms.decay_ms(hard), "crest_db": ms.crest_db(hard),
         "soft_vs_hard_db": ms.take_distance_db(soft, hard),
         "soft_centroid_hz": ms.centroid(soft),
         "after_soft_db": ms.window_db(soft, 0.012, 0.08), "after_hard_db": ms.window_db(hard, 0.012, 0.08),
         "closest_takes_db": min(dists), "widest_takes_db": max(dists), "render_ms": round(timings[-1], 1)}
    if voice == "BUCKET":
        m.update({"hz_early_hard": ms.window_hz(hard, 0.004, 0.03, 30, 300), "hz_late_hard": ms.window_hz(hard, 0.1, 0.3, 30, 300),
                  "hz_early_soft": ms.window_hz(soft, 0.004, 0.03, 30, 300)})
    save("kit", f"{pad.lower()}-{name.lower().replace(' ', '-')}", hard,
         f"{pad} {name}: {voice} ({e.DRUM_CLASS[voice]}), hard hit, first take.", m,
         {"pad": pad, "label": name, "voice": voice, "macros": e.settled(voice, macros)})
    MANIFEST["kit"].append({"pad": pad, "label": name, "voice": voice, "class": e.DRUM_CLASS[voice],
                            "macros": e.settled(voice, macros), "metrics": m})
    # Every zone and take for the playable grid.
    for z, v in ZONES.items():
        for t in range(TAKES):
            y = takes[t] if z == "h" else e.render(voice, macros, v, t)
            full, _ = path("grid", f"{pad.lower()}-{z}{t}")
            sk.write_wav(full, y)
    # The velocity ladder and the four takes as sequences.
    ladder = [e.render(voice, macros, v, 0) for v in ZONES.values()]
    save("velocity", pad.lower(), chain(ladder, 0.25),
         f"{pad} {name}: soft, medium, hard (velocity {ZONES['s']}, {ZONES['m']}, {ZONES['h']}), each peak-matched so only the strike differs.",
         {"soft_vs_hard_db": m["soft_vs_hard_db"], "soft_centroid_hz": m["soft_centroid_hz"], "centroid_hz": m["centroid_hz"],
          "after_soft_db": m["after_soft_db"], "after_hard_db": m["after_hard_db"]}, {"pad": pad, "label": name})
    save("takes", pad.lower(), chain(takes, 0.22),
         f"{pad} {name}: the four round-robin takes, hard, in the order the pad deals them.",
         {"closest_takes_db": m["closest_takes_db"], "widest_takes_db": m["widest_takes_db"]}, {"pad": pad, "label": name})
    print(pad, name, m)

# ------------------------------------------------------------------------------ A15: the roll

ROLL = []  # (time, velocity, take): two beats of eighths, one of sixteenths, one of thirty-seconds
t, k = 0.0, 0
for step, count in ((2 * SIXTEENTH, 4), (SIXTEENTH, 4), (SIXTEENTH / 2, 8)):
    for _ in range(count):
        ROLL.append((t, 0.3 + 0.7 * (k / 15) ** 1.3, k % TAKES))
        t += step
        k += 1
bar = 16 * SIXTEENTH
roll = np.zeros(int((bar + 1.6) * RATE))
for at, vel, tk in ROLL:
    place(roll, e.render("POT", {"TOOL": .45, "RATTLE": .55}, vel, tk) * (0.35 + 0.65 * vel), at)
roll *= 0.95 / np.max(np.abs(roll))
roll_metrics = {"hits": len(ROLL), "first_gap_ms": round(2 * SIXTEENTH * 1000), "last_gap_ms": round(SIXTEENTH / 2 * 1000),
                "seconds": round(len(roll) / RATE, 2)}
save("kit", "a15-pot-roll", roll, f"A15 Pot Roll: a wooden spoon on the saucepan, eighths to thirty-seconds over one bar at {BPM:.0f} BPM, "
     "soft to hard, the four takes in turn. The sampler's velocity gain is applied here.", roll_metrics,
     {"pad": "A15", "label": "Pot Roll", "voice": "POT"})
MANIFEST["kit"].append({"pad": "A15", "label": "Pot Roll", "voice": "POT", "class": "PERC (roll)", "metrics": roll_metrics})
sk.write_wav(path("grid", "a15")[0], roll)

# ------------------------------------------------------------------------------ A16: the groove

# Two bars on a sixteenth grid: (voice, step, velocity). "shape": a first idea of a found-sound beat.
PATTERN = []
for b in (0, 16):
    PATTERN += [("BUCKET", b + 0, 1.0), ("BUCKET", b + 7, .5), ("BUCKET", b + 10, .9),
                ("BOX", b + 4, 1.0), ("BOX", b + 12, 1.0), ("BOX", b + 15, .35),
                ("TABLE", b + 5, .45), ("BOTTLE", b + 11, .6), ("KEYS", b + 14, .8)]
    PATTERN += [("JAR", b + s, .9 if s % 4 == 0 else .55) for s in range(0, 16, 2) if s != 14]
    PATTERN += [("JAR", b + 13, .4)]
PATTERN += [("POT", 3, .6), ("POT", 27, .75), ("BOOK", 28, .9), ("TABLE", 30, .35)]
PATTERN.sort(key=lambda p: p[1])
groove = np.zeros(int((32 * SIXTEENTH + 1.2) * RATE))
dealt: dict = {}
hat_open_until = None
for voice, step, vel in PATTERN:
    tk = dealt.get(voice, 0)
    dealt[voice] = tk + 1
    y = e.render(voice, None, vel, tk % TAKES) * MIX[voice] * (0.35 + 0.65 * vel)
    at = step * SIXTEENTH
    if voice == "KEYS":
        # The closed jar chokes the open keys, as A03 and A04 share a mute group in the kit.
        nxt = next((s for v, s, _ in PATTERN if v == "JAR" and s > step), None)
        if nxt is not None:
            cut = int((nxt - step) * SIXTEENTH * RATE)
            f = int(0.006 * RATE)
            y = y[:cut + f].copy()
            y[cut:cut + f] *= np.linspace(1, 0, len(y[cut:cut + f]))
    place(groove, y, at)
groove *= 0.95 / np.max(np.abs(groove))
groove_metrics = {"bars": 2, "hits": len(PATTERN), "seconds": round(len(groove) / RATE, 2)}
save("kit", "a16-kitchen-groove", np.concatenate([groove[:int(32 * SIXTEENTH * RATE)]] * 2 + [groove[int(32 * SIXTEENTH * RATE):]]),
     f"A16 Kitchen Groove: two bars at {BPM:.0f} BPM, played twice. Every hit is a fresh take at its own velocity; the jar chokes the keys.",
     groove_metrics, {"pad": "A16", "label": "Kitchen Groove", "voice": "kit"})
MANIFEST["kit"].append({"pad": "A16", "label": "Kitchen Groove", "voice": "kit", "class": "LOOP", "metrics": groove_metrics})
sk.write_wav(path("grid", "a16")[0], groove)

# ------------------------------------------------------------------------------ macro sweeps

activity = {}
for voice in e.VOICES:
    for mac in e.MACROS:
        lo = e.render(voice, {mac: 0.0})
        mid = e.render(voice, {mac: 0.5})
        hi = e.render(voice, {mac: 1.0})
        d = full_distance_db(lo, hi)
        activity[f"{voice}.{mac}"] = d
        meaning = e.MEANING[voice].get(mac) or {"TUNE": "the object's size", "DAMP": "a hand resting on it",
                                                 "MIC": "the phone, close up to across the kitchen"}[mac]
        save("macros", f"{voice.lower()}-{mac.lower()}", chain([lo, mid, hi], 0.25),
             f"{voice} {mac} at 0, 0.5, 1: {meaning}.",
             {"centroid_lo_hz": ms.centroid(lo), "centroid_hi_hz": ms.centroid(hi),
              "decay_lo_ms": ms.decay_ms(lo), "decay_hi_ms": ms.decay_ms(hi), "lo_vs_hi_db": d},
             {"voice": voice, "macro": mac})

# ------------------------------------------------------------------------------ checks

checks = MANIFEST["checks"]
checks["deterministic"] = all(np.array_equal(e.render(v, take=t), e.render(v, take=t)) for v in e.VOICES for t in range(2))
renders = [e.render(v, velocity=z, take=t) for v in e.VOICES for z in ZONES.values() for t in range(TAKES)]
checks["finite"] = all(bool(np.all(np.isfinite(y))) for y in renders)
checks["peak_max"] = round(max(float(np.max(np.abs(y))) for y in renders), 4)
checks["dc_max"] = round(max(abs(float(np.mean(y))) for y in renders), 5)
checks["unknown_key_ignored"] = all(np.array_equal(e.render(v), e.render(v, {"NOPE": .9})) for v in e.VOICES)
checks["nan_macro_falls_back"] = all(np.array_equal(e.render(v), e.render(v, {"TOOL": float("nan")})) for v in e.VOICES)
checks["nan_velocity_falls_back"] = all(np.array_equal(e.render(v), e.render(v, velocity=float("nan"))) for v in e.VOICES)
checks["macro_activity_db"] = activity
checks["weakest_macro"] = min(activity, key=activity.get)
checks["weakest_macro_db"] = activity[checks["weakest_macro"]]
checks["render_ms_mean"] = round(float(np.mean(timings)), 1)
checks["render_ms_max"] = round(float(np.max(timings)), 1)
t0 = time.perf_counter()
e.mic_ir.cache_clear()
e.mic_ir(300)
checks["mic_ir_build_ms"] = round((time.perf_counter() - t0) * 1000, 1)
kit = [k for k in MANIFEST["kit"] if "soft_vs_hard_db" in k["metrics"]]
checks["soft_vs_hard_min_db"] = min(k["metrics"]["soft_vs_hard_db"] for k in kit)
checks["closest_takes_min_db"] = min(k["metrics"]["closest_takes_db"] for k in kit)
checks["widest_takes_max_db"] = max(k["metrics"]["widest_takes_db"] for k in kit)
print(json.dumps(checks, indent=1)[:1500])

json.dump(MANIFEST, open(os.path.join(OUT, "manifest.json"), "w"), indent=1)
print("wrote", OUT)
