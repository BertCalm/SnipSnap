"""Render the CLUTTER round-0 audition: WAVs per section plus manifest.json with measurements.

Python harness, not Kotlin. Nothing here has been heard by its author. Every one-shot is peak-matched
(0.89), so a pad never wins by being louder; the sampler's velocity gain is applied only in the
grooves and on the page's pad grid, where it is stated.

Five kits (kits.py): the sixteen-object CLUTTER kit and four genre kits. For each pad of each kit the
generator renders three velocity zones x four takes and writes them as one WAV of twelve equal slots
(soft takes 1-4, medium 1-4, hard 1-4), which the page's pad grid slices; a version of the listening
page can hold 511 files, and 5 x 16 x 12 separate files would not fit.

Run:  python3 gen_audition.py OUT_DIR      then   python3 build_page.py OUT_DIR
"""
from __future__ import annotations

import itertools
import json
import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import clutter_proto as e  # noqa: E402
import kits as K  # noqa: E402
import measures as ms  # noqa: E402

sk = e.sk
OUT = sys.argv[1] if len(sys.argv) > 1 else "clutter-audition"
RATE = e.RATE
ZONES = {"s": 0.35, "m": 0.65, "h": 1.0}   # the three velocity zones the house kits use
TAKES = 4
MANIFEST: dict = {"zones": ZONES, "takes": TAKES, "sections": [], "kits": [], "checks": {}}


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
    """The house distance over the whole render, not only the first 150 ms: DAMP and MIC live in the tail."""
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


def groove(kit, layers):
    """Two bars of the kit's pattern, played twice. Every hit is the next take at its own velocity zone; the
    sampler's velocity gain is applied; A03 chokes A04 and A07 chokes A08."""
    six = 60.0 / kit["bpm"] / 4
    pads = {p[0]: p for p in kit["pads"]}
    pattern = sorted(kit["groove"], key=lambda g: g[1])
    bar2 = 32 * six
    buf = np.zeros(int((bar2 + 2.2) * RATE))
    dealt: dict = {}
    for i, (pad, step, vel) in enumerate(pattern):
        zone = min(ZONES, key=lambda z: abs(ZONES[z] - vel))
        tk = dealt.get(pad, 0) % TAKES
        dealt[pad] = dealt.get(pad, 0) + 1
        y = layers[pad][zone][tk] * K.MIX[pads[pad][4]] * (0.35 + 0.65 * vel)
        at = step * six + (six * (kit["swing"] - 0.5) * 2 if step % 2 == 1 else 0.0)
        for choker, victims in K.CHOKES.items():
            if pad in victims:
                nxt = next((s for p, s, _ in pattern if p == choker and s > step), None)
                if nxt is not None:
                    cut = int((nxt - step) * six * RATE)
                    f = int(0.006 * RATE)
                    y = y[:cut + f].copy()
                    y[cut:cut + f] *= np.linspace(1, 0, len(y[cut:cut + f]))
        place(buf, y, at)
    buf *= 0.95 / np.max(np.abs(buf))
    n2 = int(bar2 * RATE)
    return np.concatenate([buf[:n2], buf[:n2], buf[n2:]])


# ------------------------------------------------------------------------------ every kit

timings = []
kit_layers = {}
for kit in K.ALL:
    layers = {}
    rows = []
    for pad, label, voice, macros, cls in kit["pads"]:
        zl = {}
        for z, v in ZONES.items():
            zl[z] = []
            for t in range(TAKES):
                t0 = time.perf_counter()
                zl[z].append(e.render(voice, macros, v, t))
                timings.append((time.perf_counter() - t0) * 1000)
        layers[pad] = zl
        hard, soft = zl["h"][0], zl["s"][0]
        dists = [ms.take_distance_db(a, b) for a, b in itertools.combinations(zl["h"], 2)]
        m = {"centroid_hz": ms.centroid(hard), "decay_ms": ms.decay_ms(hard), "soft_vs_hard_db": ms.take_distance_db(soft, hard),
             "soft_centroid_hz": ms.centroid(soft), "closest_takes_db": min(dists), "widest_takes_db": max(dists)}
        # One WAV per pad: twelve equal slots, soft takes 1-4, medium 1-4, hard 1-4.
        slot = max(len(y) for z in ZONES for y in zl[z])
        sprite = np.concatenate([np.pad(y, (0, slot - len(y))) for z in ZONES for y in zl[z]])
        _, rel = path(f"grid/{kit['id']}", pad.lower())
        sk.write_wav(os.path.join(OUT, rel), sprite)
        rows.append({"pad": pad, "label": label, "voice": voice, "class": cls, "macros": e.settled(voice, macros),
                     "metrics": m, "sprite": rel, "slot_frames": slot})
    kit_layers[kit["id"]] = layers
    # Within the kit: how far apart are its sixteen pads (hard, first take)?
    hard = {r["pad"]: layers[r["pad"]]["h"][0] for r in rows}
    pair = min(((ms.take_distance_db(hard[a], hard[b]), a, b) for a, b in itertools.combinations(hard, 2)))
    g = groove(kit, layers)
    _, grel = path("grooves", kit["id"])
    sk.write_wav(os.path.join(OUT, grel), g)
    run = chain([hard[r["pad"]] for r in rows], 0.15)
    _, rrel = path("runs", kit["id"])
    sk.write_wav(os.path.join(OUT, rrel), run)
    MANIFEST["kits"].append({
        "id": kit["id"], "name": kit["name"], "bpm": kit["bpm"], "swing": kit["swing"], "character": kit["character"],
        "pads": rows, "groove": grel, "groove_seconds": round(len(g) / RATE, 1), "run": rrel,
        "closest_pads_db": pair[0], "closest_pads": [pair[1], pair[2]],
        "soft_vs_hard_min_db": min(r["metrics"]["soft_vs_hard_db"] for r in rows),
        "closest_takes_min_db": min(r["metrics"]["closest_takes_db"] for r in rows),
        "mean_centroid_hz": round(float(np.mean([r["metrics"]["centroid_hz"] for r in rows]))),
        "mean_decay_ms": round(float(np.mean([r["metrics"]["decay_ms"] for r in rows]))),
        "mean_mic": round(float(np.mean([r["macros"]["MIC"] for r in rows])), 2),
    })
    print(kit["id"], "closest pads", pair, "soft/hard min", MANIFEST["kits"][-1]["soft_vs_hard_min_db"],
          "takes min", MANIFEST["kits"][-1]["closest_takes_min_db"])

# Between the genre kits: the same pad slot in two kits, and the kits as a whole.
ids = [k["id"] for k in K.GENRE]
between = {}
for a, b in itertools.combinations(ids, 2):
    d = [ms.take_distance_db(kit_layers[a][p]["h"][0], kit_layers[b][p]["h"][0]) for p in kit_layers[a]]
    same = [p for p in kit_layers[a]
            if next(r for r in K.ALL if r["id"] == a)["pads"][int(p[1:]) - 1][2:4] == next(r for r in K.ALL if r["id"] == b)["pads"][int(p[1:]) - 1][2:4]]
    between[f"{a}-{b}"] = {"mean_slot_db": round(float(np.mean(d)), 1), "min_slot_db": round(min(d), 2),
                           "min_slot": f"A{int(np.argmin(d)) + 1:02d}", "identical_pads": same}
MANIFEST["between"] = between
print("between", between)

# ------------------------------------------------------------------------------ the CLUTTER kit's sections

base = MANIFEST["kits"][0]
L0 = kit_layers["clutter"]
for r in base["pads"]:
    pad, label = r["pad"], r["label"]
    zl = L0[pad]
    save("kit", f"{pad.lower()}-{label.lower().replace(' ', '-')}", zl["h"][0],
         f"{pad} {label}: {r['voice']} ({r['class']}), hard hit, first take.", r["metrics"], {"pad": pad, "label": label})
    save("velocity", pad.lower(), chain([zl[z][0] for z in ZONES], 0.25),
         f"{pad} {label}: soft, medium, hard (velocity {ZONES['s']}, {ZONES['m']}, {ZONES['h']}), each peak-matched so only the strike differs.",
         {k: r["metrics"][k] for k in ("soft_vs_hard_db", "soft_centroid_hz", "centroid_hz")}, {"pad": pad, "label": label})
    save("takes", pad.lower(), chain(zl["h"], 0.22),
         f"{pad} {label}: the four round-robin takes, hard, in the order the pad deals them.",
         {k: r["metrics"][k] for k in ("closest_takes_db", "widest_takes_db")}, {"pad": pad, "label": label})

# Bank B: B01 is a one-shot now (owner, 2026-11-13), B02 the CLUTTER kit's groove.
b_pad, b_label, b_voice, b_macros, b_cls = K.B01
b_hard = e.render(b_voice, b_macros, 1.0, 0)
b_soft = e.render(b_voice, b_macros, ZONES["s"], 0)
b_takes = [e.render(b_voice, b_macros, 1.0, t) for t in range(TAKES)]
b_m = {"centroid_hz": ms.centroid(b_hard), "decay_ms": ms.decay_ms(b_hard), "soft_vs_hard_db": ms.take_distance_db(b_soft, b_hard),
       "soft_centroid_hz": ms.centroid(b_soft),
       "closest_takes_db": min(ms.take_distance_db(a, b) for a, b in itertools.combinations(b_takes, 2))}
save("extras", "b01-bin-lid", chain([b_soft, b_hard] + b_takes[1:], 0.3),
     "B01 Bin Lid: a corrugated metal lid struck with a drumstick, soft then hard, then three more hard takes. A one-shot in place of the round-0 roll.",
     b_m, {"pad": "B01", "label": b_label})
save("extras", "b02-kitchen-groove", sk.read_wav(os.path.join(OUT, base["groove"]))[0],
     f"B02 Kitchen Groove: the CLUTTER kit, two bars at {K.BASE['bpm']:.0f} BPM, played twice.",
     {}, {"pad": "B02", "label": "Kitchen Groove"})

# ------------------------------------------------------------------------------ macro sweeps

activity = {}
for voice in e.VOICES:
    for mac in e.MACROS:
        lo, mid, hi = (e.render(voice, {mac: x}) for x in (0.0, 0.5, 1.0))
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
renders = [y for k in kit_layers.values() for zl in k.values() for ys in zl.values() for y in ys]
checks["renders"] = len(renders)
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
checks["soft_vs_hard_min_db"] = min(k["soft_vs_hard_min_db"] for k in MANIFEST["kits"])
checks["closest_takes_min_db"] = min(k["closest_takes_min_db"] for k in MANIFEST["kits"])
checks["closest_pads_min_db"] = min(k["closest_pads_db"] for k in MANIFEST["kits"])
print(json.dumps({k: v for k, v in checks.items() if k != "macro_activity_db"}, indent=1))

json.dump(MANIFEST, open(os.path.join(OUT, "manifest.json"), "w"), indent=1)
print("wrote", OUT)
