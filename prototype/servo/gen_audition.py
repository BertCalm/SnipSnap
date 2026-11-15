"""Render the SERVO round-0 audition: WAVs per section plus manifest.json with measurements.

Python harness, not Kotlin. Nothing here has been heard by its author. Every one-shot is
peak-matched (0.89), so a pad never wins by being louder; the sampler's velocity gain is applied
only in the phrases and on the page's pad grid, where it is stated.

Three kits (kits.py). For each pad the generator renders three velocity zones x four takes and
writes them as one file of twelve equal slots (soft takes 1-4, medium 1-4, hard 1-4), which the
page's pad grid slices. Each kit's groove, roll and riser are rendered twice: on ONE shared rail
(the machine on one supply, what a baked phrase pad carries) and with every hit on its own rail
(what sixteen one-shot pads played from a sequencer sound like), so the difference can be heard.

Run:  python3 gen_audition.py OUT_DIR      then   python3 build_page.py OUT_DIR
With ffmpeg on the path, MP3 listening copies of the clips are written beside the WAVs.
"""
from __future__ import annotations

import itertools
import json
import math
import os
import shutil
import subprocess
import sys
import time

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import kits as K  # noqa: E402
import measures as ms  # noqa: E402
import servo_proto as e  # noqa: E402

sk = e.sk
OUT = sys.argv[1] if len(sys.argv) > 1 else "servo-audition"
RATE = e.RATE
ZONES = {"s": 0.35, "m": 0.65, "h": 1.0}   # the three velocity zones the house kits use
TAKES = 4
MANIFEST: dict = {"zones": ZONES, "takes": TAKES, "sections": [], "kits": [], "checks": {}}


def save(section, name, y, note="", metrics=None, extra=None):
    d = os.path.join(OUT, section)
    os.makedirs(d, exist_ok=True)
    sk.write_wav(os.path.join(d, name + ".wav"), y)
    item = {"name": name, "file": f"{section}/{name}.wav", "seconds": round(len(y) / RATE, 2), "note": note,
            "metrics": metrics or {}}
    item.update(extra or {})
    sec = next((s for s in MANIFEST["sections"] if s["id"] == section), None)
    if sec is None:
        sec = {"id": section, "clips": []}
        MANIFEST["sections"].append(sec)
    sec["clips"].append(item)
    return item


def chain(parts, pause=0.22):
    out = []
    for p in parts:
        out += [p, np.zeros(int(pause * RATE))]
    return np.concatenate(out[:-1])


def hit(voice, macros=None, velocity=1.0, take=0):
    return e.level(e.render(voice, macros, velocity, take))


def ripple_db(y, lo=35.0, hi=110.0):
    """How much of the hit's loudness contour moves at the rail's ring rate: the envelope's energy
    between 35 and 110 Hz against all of it under 1 kHz, dB. GROWL should raise it."""
    env = np.abs(signal.hilbert(y))[:int(0.25 * RATE)]
    X = np.abs(np.fft.rfft((env - env.mean()) * np.hanning(len(env)))) ** 2
    f = np.fft.rfftfreq(len(env), 1 / RATE)
    return round(float(10 * np.log10(X[(f >= lo) & (f < hi)].sum() / (X[(f > 0) & (f < 1000)].sum() + 1e-18))), 1)


# ------------------------------------------------------------------------------ the voices

timings = []
for v in e.VOICES:
    d: dict = {}
    t0 = time.perf_counter()
    raw = e.render(v, diagnostics=d)
    timings.append((time.perf_counter() - t0) * 1000)
    y = e.level(raw)
    save("voices", v.lower(), y, f"{v} at the defaults, a hard hit.",
         {"centroid_hz": ms.centroid(y), "decay_ms": ms.decay_ms(y), "rail_max": round(d["rail_max"], 2)})

# The rail by itself: the same hit with the rail off, sagging smoothly, and ringing.
RAIL_STEPS = (("rail off", {"SAG": 0.0}), ("sag, damped", {"SAG": 0.65, "GROWL": 0.0}), ("sag, ringing", {"SAG": 0.65, "GROWL": 0.9}))
for v in ("KICK", "SNARE", "HAT", "TOM", "STAB"):
    ys = [hit(v, m) for _, m in RAIL_STEPS]
    save("rail", v.lower(), chain(ys, 0.3), f"{v} three times: rail off (SAG 0), SAG .65 with GROWL 0, SAG .65 with GROWL .9.",
         {"off_vs_sag_db": ms.take_distance_db(ys[0], ys[1]), "sag_vs_ring_db": ms.take_distance_db(ys[1], ys[2]),
          "ripple_damped_db": ripple_db(ys[1]), "ripple_ringing_db": ripple_db(ys[2])})

# Each macro on each voice: 0, 0.5, 1 in one file.
weakest = (99.0, "")
for v in e.VOICES:
    for k in e.DEFAULTS:
        ys = [hit(v, {k: x}) for x in (0.0, 0.5, 1.0)]
        dist = ms.take_distance_db(ys[0], ys[2])
        if dist < weakest[0]:
            weakest = (dist, f"{v} {k}")
        save("macros", f"{v.lower()}-{k.lower()}", chain(ys), f"{k} at 0, 0.5 and 1.",
             {"lo_vs_hi_db": dist, "centroid_lo_hz": ms.centroid(ys[0]), "centroid_hi_hz": ms.centroid(ys[2]),
              "decay_lo_ms": ms.decay_ms(ys[0]), "decay_hi_ms": ms.decay_ms(ys[2])}, {"voice": v, "macro": k})

# Velocity and takes on each voice at the defaults.
for v in e.VOICES:
    ys = [hit(v, None, z) for z in ZONES.values()]
    save("velocity", v.lower(), chain(ys), "Soft, medium, hard, each peak-matched.",
         {"soft_vs_hard_db": ms.take_distance_db(ys[0], ys[2]), "soft_centroid_hz": ms.centroid(ys[0]), "centroid_hz": ms.centroid(ys[2])})
    ts = [hit(v, None, 1.0, t) for t in range(TAKES)]
    dists = [ms.take_distance_db(a, b) for a, b in itertools.combinations(ts, 2)]
    save("takes", v.lower(), chain(ts), "Four takes, all hard: take 1 on a rested rail, 2 to 4 on a rail still moving.",
         {"closest_takes_db": min(dists), "widest_takes_db": max(dists)})

# ------------------------------------------------------------------------------ the kits


def phrase(kit, layers, events, beats, shared, tail=1.0):
    """events: (pad, time in beats, velocity). Each hit is the pad's next take at its own velocity
    zone with the sampler's velocity gain, levelled exactly as the one-shot pad is; shared=True
    puts every hit on one rail. A03 chokes A04 and A07 chokes A08."""
    beat = 60.0 / kit["bpm"]
    pads = {p[0]: p for p in kit["pads"]}
    ordered = sorted(events, key=lambda g: g[1])
    seconds = beats * beat + tail
    evs, dealt = [], {}
    for pad, at, vel in ordered:
        _, _, voice, macros, cls = pads[pad]
        zone = min(ZONES, key=lambda z: abs(ZONES[z] - vel))
        tk = dealt.get(pad, 0) % TAKES
        dealt[pad] = dealt.get(pad, 0) + 1
        own = layers[pad][zone][tk]
        gain = (0.89 / float(np.max(np.abs(own)))) * K.MIX_VOICE.get(voice, K.MIX[cls]) * (0.35 + 0.65 * vel)
        evs.append((at * beat, voice, macros, ZONES[zone], tk, gain, pad))
    out = np.zeros(int(seconds * RATE))
    rails = e.phrase_rails([(at, voice, macros, v) for (at, voice, macros, v, *_x) in evs], seconds, kit["rail_growl"]) if shared else None
    for n, (at, voice, macros, v, tk, gain, pad) in enumerate(evs):
        y = (e.render(voice, macros, v, tk, shared_rail=rails[n]) if shared else layers[pad][min(ZONES, key=lambda z: abs(ZONES[z] - v))][tk]) * gain
        for choker, victim in (("A03", "A04"), ("A07", "A08")):
            if pad == victim:
                nxt = next((a for (a, *_rest, p) in evs[n + 1:] if p == choker), None)
                if nxt is not None:
                    cut, f = int((nxt - at) * RATE), int(0.006 * RATE)
                    y = y[:cut + f].copy()
                    y[cut:] *= np.linspace(1, 0, len(y[cut:]))
        i = int(at * RATE)
        y = y[:len(out) - i]
        out[i:i + len(y)] += y
    return out, (max(float(x.max()) for x in rails) if shared else 0.0)


kit_layers, kit_hard = {}, {}
for kit in K.KITS:
    layers, rows = {}, []
    for pad, label, voice, macros, cls in kit["pads"]:
        zl = {}
        for z, v in ZONES.items():
            zl[z] = []
            for t in range(TAKES):
                t0 = time.perf_counter()
                zl[z].append(e.render(voice, macros, v, t))
                timings.append((time.perf_counter() - t0) * 1000)
        layers[pad] = zl
        lv = {z: [e.level(y) for y in zl[z]] for z in zl}
        hard, soft = lv["h"][0], lv["s"][0]
        dists = [ms.take_distance_db(a, b) for a, b in itertools.combinations(lv["h"], 2)]
        m = {"centroid_hz": ms.centroid(hard), "decay_ms": ms.decay_ms(hard), "soft_vs_hard_db": ms.take_distance_db(soft, hard),
             "closest_takes_db": min(dists), "widest_takes_db": max(dists)}
        slot = max(len(y) for z in lv for y in lv[z]) + int(0.01 * RATE)
        sprite = np.concatenate([np.pad(y, (0, slot - len(y))) for z in ZONES for y in lv[z]])
        os.makedirs(os.path.join(OUT, "pads"), exist_ok=True)
        rel = f"pads/{kit['id']}-{pad.lower()}.wav"
        sk.write_wav(os.path.join(OUT, rel), sprite)
        rows.append({"pad": pad, "label": label, "voice": voice, "class": cls, "macros": macros, "metrics": m,
                     "sprite": rel, "slot_frames": slot})
        kit_hard[(kit["id"], pad)] = hard
    kit_layers[kit["id"]] = layers
    d = os.path.join(OUT, "kits")
    os.makedirs(d, exist_ok=True)
    six = [(p, s / 4.0, v) for p, s, v in kit["groove"]]
    twice = six + [(p, t + 8.0, v) for p, t, v in six]
    files = {}
    sag_peak = 0.0
    for name, events, beats in (("groove", twice, 16), ("roll", kit["roll"], 2), ("riser", kit["riser"], 4.5)):
        a, peak = phrase(kit, layers, events, beats, True)
        b, _ = phrase(kit, layers, events, beats, False)
        norm = 0.95 / max(np.max(np.abs(a)), np.max(np.abs(b)))
        sk.write_wav(os.path.join(d, f"{kit['id']}-{name}-shared.wav"), a * norm)
        sk.write_wav(os.path.join(d, f"{kit['id']}-{name}-own.wav"), b * norm)
        n = min(len(a), len(b))
        A, B = ms.spectro_db(a[:n], ms=10, total=n / RATE), ms.spectro_db(b[:n], ms=10, total=n / RATE)
        files[name] = {"shared": f"kits/{kit['id']}-{name}-shared.wav", "own": f"kits/{kit['id']}-{name}-own.wav",
                       "seconds": round(len(a) / RATE, 1), "shared_vs_own_db": round(float(np.sqrt(np.mean((A - B) ** 2))), 2)}
        sag_peak = max(sag_peak, peak)
    run = chain([kit_hard[(kit["id"], p[0])] for p in kit["pads"]], 0.12)
    sk.write_wav(os.path.join(d, f"{kit['id']}-run.wav"), run)
    hard = {p[0]: kit_hard[(kit["id"], p[0])] for p in kit["pads"]}
    pair = min(((ms.take_distance_db(hard[a], hard[b]), a, b) for a, b in itertools.combinations(hard, 2)))
    MANIFEST["kits"].append({
        "id": kit["id"], "name": kit["name"], "bpm": kit["bpm"], "character": kit["character"], "rail_growl": kit["rail_growl"],
        "pads": rows, "phrases": files, "run": f"kits/{kit['id']}-run.wav", "shared_rail_peak": round(sag_peak, 2),
        "mean_decay_ms": round(float(np.mean([r["metrics"]["decay_ms"] for r in rows]))),
        "mean_centroid_hz": round(float(np.mean([r["metrics"]["centroid_hz"] for r in rows]))),
        "soft_vs_hard_min_db": min(r["metrics"]["soft_vs_hard_db"] for r in rows),
        "closest_takes_min_db": min(r["metrics"]["closest_takes_db"] for r in rows),
        "closest_pads_db": pair[0], "closest_pads": [pair[1], pair[2]]})

between = {}
for a, b in itertools.combinations(K.KITS, 2):
    ds = {p[0]: ms.take_distance_db(kit_hard[(a["id"], p[0])], kit_hard[(b["id"], p[0])]) for p in a["pads"]}
    lo = min(ds, key=ds.get)
    between[f"{a['id']}-{b['id']}"] = {"mean_slot_db": round(float(np.mean(list(ds.values()))), 1), "min_slot_db": ds[lo], "min_slot": lo,
                                       "identical_pads": [p for p, x in ds.items() if x < 0.5]}
MANIFEST["between"] = between

# ------------------------------------------------------------------------------ the checks

C = MANIFEST["checks"]
allr, det, fin, peaks, dcs = 0, True, True, [], []
for v in e.VOICES:
    for m in (None, {k: 0.0 for k in e.DEFAULTS}, {k: 1.0 for k in e.DEFAULTS}, {"SAG": 1.0, "GROWL": 1.0, "HEAT": 1.0, "DECAY": 1.0}):
        a, b = e.render(v, m, 1.0, 1), e.render(v, m, 1.0, 1)
        allr += 1
        det &= bool(np.array_equal(a, b))
        fin &= bool(np.all(np.isfinite(a)))
        peaks.append(float(np.max(np.abs(a))))
        dcs.append(abs(float(np.mean(a))))
C.update(renders=allr + len(timings), deterministic=det, finite=fin, raw_peak_max=round(max(peaks), 3), dc_max=round(max(dcs), 5))
C["weakest_macro_db"], C["weakest_macro"] = weakest
C["unknown_key_ignored"] = bool(np.array_equal(e.render("SNARE", {"BOGUS": 0.9}), e.render("SNARE")))
C["nan_macro_falls_back"] = bool(np.array_equal(e.render("SNARE", {"SAG": float("nan")}), e.render("SNARE")))
C["nan_velocity_falls_back"] = bool(np.array_equal(e.render("SNARE", None, float("nan")), e.render("SNARE")))
C["zero_velocity_silent"] = bool(not np.any(e.render("KICK", None, 0.0)))
C["render_ms_mean"], C["render_ms_max"] = round(float(np.mean(timings)), 1), round(float(max(timings)), 1)

# The root survives the rail: the tail's pitch against the TUNE root at every SAG x GROWL corner.
roots = []
for v, (lo, hi) in {"KICK": (38.0, 78.0), "TOM": (70.0, 260.0)}.items():
    for tune in (0.2, 0.5, 0.8):
        nominal = sk.exp_map(tune, lo, hi)
        for sag, growl in itertools.product((0.0, 0.45, 1.0), (0.0, 0.35, 1.0)):
            y = e.render(v, {"TUNE": tune, "SAG": sag, "GROWL": growl, "DECAY": 0.85, "HEAT": 0.0})
            L = len(y) / RATE
            roots.append(abs(1200 * math.log2(ms.window_hz(y, L * 0.55, L * 0.9, lo=20, hi=700) / nominal)))
C["root_cents_max"] = round(max(roots), 1)
# SAG at 0 is the rail switched off: GROWL must then change nothing at all.
C["sag_zero_ignores_growl"] = all(bool(np.array_equal(e.render(v, {"SAG": 0.0, "GROWL": 0.0}), e.render(v, {"SAG": 0.0, "GROWL": 1.0}))) for v in e.VOICES)
# The rail stays inside its bound at the worst corner.
worst = 0.0
for v in e.VOICES:
    d = {}
    e.render(v, {"SAG": 1.0, "GROWL": 1.0}, 1.0, 2, diagnostics=d)
    worst = max(worst, abs(d["rail_max"]), abs(d["rail_min"]))
C["rail_abs_max"], C["rail_bound"] = round(worst, 3), e.R_MAX
# GROWL raises the ripple that reaches the sound, step by step.
rip = {v: [ripple_db(hit(v, {"SAG": 0.6, "GROWL": g})) for g in (0.0, 0.35, 0.7, 1.0)] for v in ("KICK", "SNARE", "HAT", "TOM")}
C["ripple_db_by_growl"] = rip
# Pair interactions: the effect of one macro must depend on the other.
pairs = {}
for a, b in (("SAG", "GROWL"), ("SAG", "HEAT"), ("ZAP", "SAG")):
    worst_pair = 99.0
    for v in ("KICK", "SNARE", "HAT", "STAB"):
        lo = ms.take_distance_db(hit(v, {a: 0.1, b: 0.0}), hit(v, {a: 0.1, b: 1.0}))
        hi = ms.take_distance_db(hit(v, {a: 0.9, b: 0.0}), hit(v, {a: 0.9, b: 1.0}))
        worst_pair = min(worst_pair, abs(hi - lo))
    pairs[f"{a}x{b}"] = round(worst_pair, 2)
C["pair_interaction_min_db"] = pairs
C["soft_vs_hard_min_db"] = min(k["soft_vs_hard_min_db"] for k in MANIFEST["kits"])
C["closest_takes_min_db"] = min(k["closest_takes_min_db"] for k in MANIFEST["kits"])

json.dump(MANIFEST, open(os.path.join(OUT, "manifest.json"), "w"), indent=1)

# ------------------------------------------------------------------------------ listening copies

if shutil.which("ffmpeg"):
    for root, _, names in os.walk(OUT):
        for name in names:
            if name.endswith(".wav"):
                src = os.path.join(root, name)
                if os.path.basename(root) == "pads":      # the grid slices these by frame: keep the WAV
                    continue
                subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-c:a", "libmp3lame", "-b:a", "160k", src[:-4] + ".mp3"], check=True)

print(json.dumps(C, indent=1))
for k in MANIFEST["kits"]:
    print(k["name"], {x: k[x] for x in ("mean_decay_ms", "mean_centroid_hz", "soft_vs_hard_min_db", "closest_takes_min_db", "closest_pads_db", "closest_pads", "shared_rail_peak")},
          {n: p["shared_vs_own_db"] for n, p in k["phrases"].items()})
print(between)
