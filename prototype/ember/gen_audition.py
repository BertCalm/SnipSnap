"""Render the EMBER round-0 audition: WAVs per section plus manifest.json with measurements.

Python harness, not Kotlin. Nothing here has been heard by its author. Every one-shot is peak-matched
(0.89), so a pad never wins by being louder; the sampler's velocity gain (0.35 + 0.65 v) is applied
only in the grooves and on the page's pad grid, where it is stated.

Four kits (kits.py). For each pad of each kit the generator renders three velocity zones x four takes
and writes them as one WAV of twelve equal slots (soft takes 1-4, medium 1-4, hard 1-4), which the
page's pad grid slices (CLUTTER's layout, so the page and its checks carry over).

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
import ember_proto as e  # noqa: E402
import kits as K  # noqa: E402
import measures as ms  # noqa: E402

sk = e.sk
OUT = sys.argv[1] if len(sys.argv) > 1 else "ember-audition"
RATE = e.RATE
ZONES = {"s": 0.35, "m": 0.65, "h": 1.0}   # the three velocity zones the house kits use
TAKES = 4
LONG = {"BRUSH_SWEEP", "BOWED_CYMBAL", "CYMBAL_SWELL", "PRESS_ROLL", "BRUSH_ROLL", "SIZZLE"}
MANIFEST: dict = {"zones": ZONES, "takes": TAKES, "sections": [], "kits": [], "checks": {},
                  "meaning": e.MEANING, "voices": {v: {"class": c, "seconds": s, "macros": list(mm)}
                                                   for v, (c, s, mm) in e.VOICES.items()}}


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
    """The house distance over the whole render, not only the first 150 ms: the rolls, the swell, the bow
    and the sweeps live after the first 150 ms, and DAMP and DISTANCE live in the tail."""
    n = max(len(a), len(b))
    a = np.pad(a, (0, n - len(a)))
    b = np.pad(b, (0, n - len(b)))
    A, B = ms.spectro_db(a, ms=10, total=n / RATE), ms.spectro_db(b, ms=10, total=n / RATE)
    return round(float(np.sqrt(np.mean((A - B) ** 2))), 2)


def dist(voice, a, b):
    return full_distance_db(a, b) if voice in LONG else ms.take_distance_db(a, b)


def place(buf, y, at):
    i = int(round(at * RATE))
    if i >= len(buf) or i < 0:
        return
    n = min(len(y), len(buf) - i)
    buf[i:i + n] += y[:n]


def groove(kit, layers):
    """Two bars of the kit's groove on a triplet grid, played twice. Every hit is the next take at its own
    velocity zone, the sampler's velocity gain applied, the kit's chokes honoured, and each pad sits where
    the kit's FEEL puts it (ms early or late)."""
    trip = 60.0 / kit["bpm"] / 3
    pads = {p[0]: p for p in kit["pads"]}
    pattern = sorted(kit["groove"], key=lambda g: g[1])
    bar2 = 24 * trip
    buf = np.zeros(int((bar2 + 3.2) * RATE))
    dealt: dict = {}
    chokes = K.CHOKES[kit["id"]]
    for pad, step, vel in pattern:
        zone = min(ZONES, key=lambda z: abs(ZONES[z] - vel))
        tk = dealt.get(pad, 0) % TAKES
        dealt[pad] = dealt.get(pad, 0) + 1
        voice = pads[pad][2]
        y = layers[pad][zone][tk] * K.mix_for(voice) * (0.35 + 0.65 * vel)
        at = step * trip + kit["feel"].get(pad, 0) * 1e-3
        for choker, victims in chokes.items():
            if pad in victims:
                nxt = next((s for p, s, _ in pattern if p == choker and s > step), None)
                if nxt is not None:
                    cut = int((nxt - step) * trip * RATE)
                    f = int(0.006 * RATE)
                    y = y[:cut + f].copy()
                    y[cut:cut + f] *= np.linspace(1, 0, len(y[cut:cut + f]))
        place(buf, y, max(0.0, at))
    buf *= 0.95 / np.max(np.abs(buf))
    n2 = int(bar2 * RATE)
    tail = buf[n2:]
    first = buf[:n2].copy()
    first[:len(tail)] += tail[:n2]           # the second pass overlaps the first pass's tail, as a loop would
    out = np.concatenate([buf[:n2], first, tail])
    last = int(np.nonzero(np.abs(out) > 1e-4)[0][-1]) + int(0.05 * RATE)
    out = out[:min(len(out), last)]
    return out * (0.95 / np.max(np.abs(out)))


# ------------------------------------------------------------------------------ every kit

timings = []
kit_layers = {}
for kit in K.ALL:
    layers = {}
    rows = []
    for pad, label, voice, macros in kit["pads"]:
        zl = {}
        for z, v in ZONES.items():
            zl[z] = []
            for t in range(TAKES):
                t0 = time.perf_counter()
                zl[z].append(e.render(voice, macros, v, t))
                timings.append(((time.perf_counter() - t0) * 1000, voice))
        layers[pad] = zl
        hard, soft = zl["h"][0], zl["s"][0]
        dists = [dist(voice, a, b) for a, b in itertools.combinations(zl["h"], 2)]
        m = {"centroid_hz": ms.centroid(hard), "decay_ms": ms.decay_ms(hard), "soft_vs_hard_db": dist(voice, soft, hard),
             "soft_centroid_hz": ms.centroid(soft), "closest_takes_db": min(dists), "widest_takes_db": max(dists)}
        slot = max(len(y) for z in ZONES for y in zl[z])
        sprite = np.concatenate([np.pad(y, (0, slot - len(y))) for z in ZONES for y in zl[z]])
        _, rel = path(f"grid/{kit['id']}", pad.lower())
        sk.write_wav(os.path.join(OUT, rel), sprite)
        rows.append({"pad": pad, "label": label, "voice": voice, "class": K.cls(voice), "macros": e.settled(voice, macros),
                     "metrics": m, "sprite": rel, "slot_frames": slot})
    kit_layers[kit["id"]] = layers
    hard = {r["pad"]: layers[r["pad"]]["h"][0] for r in rows}
    pair = min(((ms.take_distance_db(hard[a], hard[b]), a, b) for a, b in itertools.combinations(hard, 2)))
    g = groove(kit, layers)
    _, grel = path("grooves", kit["id"])
    sk.write_wav(os.path.join(OUT, grel), g)
    run = chain([hard[r["pad"]][:int(1.6 * RATE)] for r in rows], 0.15)
    _, rrel = path("runs", kit["id"])
    sk.write_wav(os.path.join(OUT, rrel), run)
    MANIFEST["kits"].append({
        "id": kit["id"], "name": kit["name"], "bpm": kit["bpm"], "character": kit["character"],
        "pads": rows, "groove": grel, "groove_seconds": round(len(g) / RATE, 1), "run": rrel,
        "closest_pads_db": pair[0], "closest_pads": [pair[1], pair[2]],
        "soft_vs_hard_min_db": min(r["metrics"]["soft_vs_hard_db"] for r in rows),
        "closest_takes_min_db": min(r["metrics"]["closest_takes_db"] for r in rows),
        "mean_centroid_hz": round(float(np.mean([r["metrics"]["centroid_hz"] for r in rows]))),
        "mean_distance": round(float(np.mean([r["macros"]["DISTANCE"] for r in rows])), 2),
        "mean_warmth": round(float(np.mean([r["macros"]["WARMTH"] for r in rows])), 2),
        "chokes": K.CHOKES[kit["id"]],
    })
    print(kit["id"], "closest pads", pair, "soft/hard min", MANIFEST["kits"][-1]["soft_vs_hard_min_db"],
          "takes min", MANIFEST["kits"][-1]["closest_takes_min_db"], flush=True)

# Between the genre kits and the club kit: the same pad slot in two kits.
ids = [k["id"] for k in K.ALL]
between = {}
for a, b in itertools.combinations(ids, 2):
    ka = next(k for k in K.ALL if k["id"] == a)
    kb = next(k for k in K.ALL if k["id"] == b)
    d = [ms.take_distance_db(kit_layers[a][p]["h"][0], kit_layers[b][p]["h"][0]) for p in kit_layers[a]]
    same = [pa[0] for pa, pb in zip(ka["pads"], kb["pads"]) if pa[2] == pb[2] and e.settled(pa[2], pa[3]) == e.settled(pb[2], pb[3])]
    between[f"{a}|{b}"] = {"mean_slot_db": round(float(np.mean(d)), 1), "min_slot_db": round(min(d), 2),
                           "min_slot": f"A{int(np.argmin(d)) + 1:02d}", "identical_pads": same}
MANIFEST["between"] = between
print("between", between, flush=True)

# ------------------------------------------------------------------------------ the club kit's sections

base = MANIFEST["kits"][0]
L0 = kit_layers["ember"]
for r in base["pads"]:
    pad, label, voice = r["pad"], r["label"], r["voice"]
    zl = L0[pad]
    slug = label.lower().replace(" ", "-")
    save("velocity", pad.lower(), chain([zl[z][0] for z in ZONES], 0.3),
         f"{pad} {label}: soft, medium, hard (velocity {ZONES['s']}, {ZONES['m']}, {ZONES['h']}), each peak-matched so only the stroke differs.",
         {k: r["metrics"][k] for k in ("soft_vs_hard_db", "soft_centroid_hz", "centroid_hz")}, {"pad": pad, "label": label})
    save("takes", pad.lower(), chain(zl["h"], 0.25),
         f"{pad} {label}: the four round-robin takes, hard, in the order the pad deals them.",
         {k: r["metrics"][k] for k in ("closest_takes_db", "widest_takes_db")}, {"pad": pad, "label": label})

# Every voice once at its defaults (the kits use 26 voices; some only in one kit).
for voice in e.VOICES:
    y = e.render(voice)
    s = e.render(voice, velocity=ZONES["s"])
    save("voices", voice.lower(), chain([s, y], 0.3),
         f"{voice} ({e.VOICES[voice][0]}): soft then hard, at its defaults.",
         {"centroid_hz": ms.centroid(y), "soft_centroid_hz": ms.centroid(s), "decay_ms": ms.decay_ms(y),
          "soft_vs_hard_db": dist(voice, s, y)}, {"voice": voice})

# ------------------------------------------------------------------------------ cymbals against their recordings
# Round 0b: the owner heard round 0's cymbals as "hitting sheet metal". Each EMBER cymbal, dry (DISTANCE 0,
# WARMTH 0), then the CC0 recording it was fitted to (or a take it was not fitted to). The recordings are
# Virtuosity Drums (CC0 1.0); they go on the listening page only, never into the repository.

REF = os.environ.get("EMBER_REF_WAVS")
MANIFEST["cymbal_refs"] = []
if REF and os.path.isdir(REF):
    from scipy.io import wavfile

    def recording(name, secs):
        _, x = wavfile.read(os.path.join(REF, name + ".wav"))
        x = x.astype(float)
        a = np.abs(x)
        i = max(0, int(np.argmax(a > .05 * a.max())) - 44)
        x = x[i:i + int(secs * RATE)]
        return x / np.max(np.abs(x)) * .89

    dry = {"DISTANCE": 0.0, "WARMTH": 0.0}
    for voice, name, vel, secs, held in (
            ("RIDE", "oh_flatride_ride_vl3_rr2", 1.0, 1.4, True), ("RIDE", "oh_flatride_ride_vl1_rr1", .35, 1.4, False),
            ("RIDE_BELL", "oh_ride_bell_vl3_rr1", 1.0, 1.4, False), ("SIZZLE", "oh_crash_sizzle_vl3_rr1", 1.0, 2.0, False),
            ("HAT_CLOSED", "oh_hh_closed_vl4_rr1", 1.0, .4, False), ("HAT_FOOT", "oh_hh_pedal_vl3_rr1", 1.0, .4, False),
            ("HAT_OPEN", "oh_hh_open_vl4_rr1", 1.0, 1.1, False)):
        y = e.render(voice, dry, vel)[:int(secs * RATE)]
        x = recording(name, secs)
        d150, dall = ms.take_distance_db(y, x), full_distance_db(y, x)
        item = save("cymbals", f"{voice.lower()}-{name.split('_')[-2]}-{name.split('_')[-1]}", chain([y, x], 0.5),
                    f"{voice} at velocity {vel}, dry, then the recording {name} ({'a take the fit never saw' if held else 'the one it was fitted to'}).",
                    {"vs_recording_db": d150, "vs_recording_whole_db": dall, "centroid_hz": ms.centroid(y), "rec_centroid_hz": ms.centroid(x)},
                    {"voice": voice})
        MANIFEST["cymbal_refs"].append({"voice": voice, "recording": name, "first150_db": d150, "whole_db": dall})
    a, b = recording("oh_flatride_ride_vl3_rr1", 1.4), recording("oh_flatride_ride_vl3_rr2", 1.4)
    MANIFEST["cymbal_real_takes_db"] = [ms.take_distance_db(a, b), full_distance_db(a, b)]
    print("cymbal refs", MANIFEST["cymbal_refs"], MANIFEST["cymbal_real_takes_db"], flush=True)

# ------------------------------------------------------------------------------ control sweeps

activity = {}
for voice in e.VOICES:
    for mac in e.macros_for(voice):
        lo, mid, hi = (e.render(voice, {mac: x}) for x in (0.0, 0.5, 1.0))
        d = full_distance_db(lo, hi)
        activity[f"{voice}.{mac}"] = d
        save("macros", f"{voice.lower()}-{mac.lower()}", chain([lo, mid, hi], 0.3),
             f"{voice} {mac} at 0, 0.5, 1: {e.MEANING[mac]}.",
             {"centroid_lo_hz": ms.centroid(lo), "centroid_hi_hz": ms.centroid(hi),
              "decay_lo_ms": ms.decay_ms(lo), "decay_hi_ms": ms.decay_ms(hi), "lo_vs_hi_db": d},
             {"voice": voice, "macro": mac})
    print("swept", voice, flush=True)

# ------------------------------------------------------------------------------ checks

checks = MANIFEST["checks"]
checks["deterministic"] = all(np.array_equal(e.render(v, take=t), e.render(v, take=t)) for v in e.VOICES for t in range(2))
renders = [y for k in kit_layers.values() for zl in k.values() for ys in zl.values() for y in ys]
checks["renders"] = len(renders)
checks["finite"] = all(bool(np.all(np.isfinite(y))) for y in renders)
checks["peak_max"] = round(max(float(np.max(np.abs(y))) for y in renders), 4)
checks["dc_max"] = round(max(abs(float(np.mean(y))) for y in renders), 5)
checks["unknown_key_ignored"] = all(np.array_equal(e.render(v), e.render(v, {"NOPE": .9})) for v in e.VOICES)
checks["nan_macro_falls_back"] = all(np.array_equal(e.render(v), e.render(v, {"TUNE": float("nan")})) for v in e.VOICES)
checks["nan_velocity_falls_back"] = all(np.array_equal(e.render(v), e.render(v, velocity=float("nan"))) for v in e.VOICES)
# WARMTH 0 and BUZZ 0 are exact bypasses of their stages (checked at the stage, not on the leveled output).
xx = np.random.default_rng(1).standard_normal(4000)
checks["warmth_zero_is_bypass"] = bool(np.array_equal(e.warmth_chain(xx, 0.0, 1.0), xx))
checks["buzz_zero_takes_wires_off"] = bool(not np.any(e.wires(xx, 1.0, 0.0, np.random.default_rng(2), len(xx))))
checks["macro_activity_db"] = activity
checks["weakest_macro"] = min(activity, key=activity.get)
checks["weakest_macro_db"] = activity[checks["weakest_macro"]]
tm = [t for t, _ in timings]
checks["render_ms_mean"] = round(float(np.mean(tm)), 1)
checks["render_ms_median"] = round(float(np.median(tm)), 1)
slow = max(timings)
checks["render_ms_max"] = round(slow[0], 1)
checks["render_ms_max_voice"] = slow[1]
by_voice = {}
for t, v in timings:
    by_voice.setdefault(v, []).append(t)
checks["render_ms_median_by_voice"] = {v: round(float(np.median(x)), 1) for v, x in by_voice.items()}
checks["soft_vs_hard_min_db"] = min(k["soft_vs_hard_min_db"] for k in MANIFEST["kits"])
checks["closest_takes_min_db"] = min(k["closest_takes_min_db"] for k in MANIFEST["kits"])
checks["closest_pads_min_db"] = min(k["closest_pads_db"] for k in MANIFEST["kits"])
print(json.dumps({k: v for k, v in checks.items() if k not in ("macro_activity_db",)}, indent=1))

json.dump(MANIFEST, open(os.path.join(OUT, "manifest.json"), "w"), indent=1)
print("wrote", OUT)
