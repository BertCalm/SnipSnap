"""Render the CALABASH round-0 audition: WAVs per section plus manifest.json with measurements.

Python harness, not Kotlin. Nothing here has been heard by its author. Every one-shot is peak-matched (0.89), so a
pad never wins by being louder; the sampler's velocity gain (0.35 + 0.65 v) is applied only in the grooves and on the
page's pad grid, where it is stated.

Four kits (kits.py). For each pad the generator renders three velocity zones x four takes and writes them as one
sprite of twelve equal slots (soft takes 1-4, medium 1-4, hard 1-4) that the page's pad grid slices (EMBER's and
CLUTTER's layout, so the page and its checks carry over). The measures are EMBER's and CLUTTER's (`measures.py`,
imported from prototype/ember so a number here means what it means there).

Run:  python3 gen_audition.py OUT_DIR      then   python3 build_page.py OUT_DIR
      CALABASH_REF_WAVS=<dir of the decoded VCSL WAVs> adds each fitted voice beside its recording (page only).
"""
from __future__ import annotations

import itertools
import json
import multiprocessing as mp
import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import calabash_proto as c  # noqa: E402
import kits as K  # noqa: E402
import measures as ms  # noqa: E402  (prototype/ember/measures.py, through calabash_proto's path)

sk = c.sk
OUT = sys.argv[1] if len(sys.argv) > 1 else "calabash-audition"
RATE = c.RATE
ZONES = {"s": 0.35, "m": 0.65, "h": 1.0}   # the three velocity zones the house kits use
TAKES = 4
LONG = {"RIDE", "CRASH", "SHEKERE_RUSH", "TALKING_CALL", "SABAR_ROLL", "ABANICO"}
MANIFEST: dict = {"zones": ZONES, "takes": TAKES, "sections": [], "kits": [], "checks": {},
                  "meaning": c.MEANING, "strokes": {k: [[round(a, 3), b] for a, b in v] for k, v in c.STROKES.items()},
                  "stroke_family": c.STROKE_FAMILY,
                  "voices": {v: {"class": cl, "seconds": s, "macros": list(mm)} for v, (cl, s, mm) in c.VOICES.items()}}


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
    """The house distance over the whole render (the rolls and cymbals live after the first 150 ms)."""
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
    """Two bars of the kit's groove on a sixteenth grid with the kit's swing, played twice. Every hit is the next take
    at its own velocity zone, the sampler's velocity gain applied, the kit's chokes honoured, and each pad sits where
    the kit's FEEL puts it."""
    six = 60.0 / kit["bpm"] / 4
    pads = {p[0]: p for p in kit["pads"]}
    pattern = sorted(kit["groove"], key=lambda g: g[1])
    bar2 = 32 * six
    buf = np.zeros(int((bar2 + 3.2) * RATE))
    dealt: dict = {}
    chokes = K.CHOKES[kit["id"]]

    def when(step):
        return (step + (kit["swing"] if int(step) % 2 == 1 else 0.0)) * six

    for pad, step, vel in pattern:
        zone = min(ZONES, key=lambda z: abs(ZONES[z] - vel))
        tk = dealt.get(pad, 0) % TAKES
        dealt[pad] = dealt.get(pad, 0) + 1
        voice = pads[pad][2]
        y = layers[pad][zone][tk] * K.mix_for(voice) * (0.35 + 0.65 * vel)
        at = when(step) + kit["feel"].get(pad, 0) * 1e-3
        for choker, victims in chokes.items():
            if pad in victims:
                nxt = next((s for p, s, _ in pattern if p == choker and s > step), None)
                if nxt is not None:
                    cut = int((when(nxt) - when(step)) * RATE)
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


def _job(args):
    voice, macros, v, t = args
    t0 = time.perf_counter()
    y = c.render(voice, macros, v, t)
    return y, (time.perf_counter() - t0) * 1000


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    pool = mp.Pool(min(4, os.cpu_count() or 1))

    # -------------------------------------------------------------------------- every kit, every zone and take
    jobs = [(k["id"], pad, z, t, voice, macros) for k in K.ALL for pad, _, voice, macros in k["pads"]
            for z in ZONES for t in range(TAKES)]
    t0 = time.perf_counter()
    results = pool.map(_job, [(voice, macros, ZONES[z], t) for _, _, z, t, voice, macros in jobs], chunksize=4)
    print(f"rendered {len(jobs)} kit hits in {time.perf_counter() - t0:.0f} s", flush=True)
    timings = [(dt, j[4]) for (_, dt), j in zip(results, jobs)]
    kit_layers: dict = {}
    for (kid, pad, z, t, _, _), (y, _) in zip(jobs, results):
        kit_layers.setdefault(kid, {}).setdefault(pad, {}).setdefault(z, [None] * TAKES)[t] = y

    for kit in K.ALL:
        layers = kit_layers[kit["id"]]
        rows = []
        for pad, label, voice, macros in kit["pads"]:
            zl = layers[pad]
            hard, soft = zl["h"][0], zl["s"][0]
            dists = [dist(voice, a, b) for a, b in itertools.combinations(zl["h"], 2)]
            soft_dists = [dist(voice, a, b) for a, b in itertools.combinations(zl["s"], 2)]
            m = {"centroid_hz": ms.centroid(hard), "decay_ms": ms.decay_ms(hard), "soft_vs_hard_db": dist(voice, soft, hard),
                 "soft_centroid_hz": ms.centroid(soft), "closest_takes_db": min(dists + soft_dists),
                 "widest_takes_db": max(dists)}
            slot = max(len(y) for z in ZONES for y in zl[z])
            sprite = np.concatenate([np.pad(y, (0, slot - len(y))) for z in ZONES for y in zl[z]])
            _, rel = path(f"grid/{kit['id']}", pad.lower())
            sk.write_wav(os.path.join(OUT, rel), sprite)
            rows.append({"pad": pad, "label": label, "voice": voice, "class": K.cls(voice), "macros": c.settled(voice, macros),
                         "metrics": m, "sprite": rel, "slot_frames": slot})
        hard = {r["pad"]: layers[r["pad"]]["h"][0] for r in rows}
        pair = min(((ms.take_distance_db(hard[a], hard[b]), a, b) for a, b in itertools.combinations(hard, 2)))
        g = groove(kit, layers)
        _, grel = path("grooves", kit["id"])
        sk.write_wav(os.path.join(OUT, grel), g)
        run = chain([hard[r["pad"]][:int(1.6 * RATE)] for r in rows], 0.15)
        _, rrel = path("runs", kit["id"])
        sk.write_wav(os.path.join(OUT, rrel), run)
        MANIFEST["kits"].append({
            "id": kit["id"], "name": kit["name"], "bpm": kit["bpm"], "swing": kit["swing"], "character": kit["character"],
            "pads": rows, "groove": grel, "groove_seconds": round(len(g) / RATE, 1), "run": rrel,
            "closest_pads_db": pair[0], "closest_pads": [pair[1], pair[2]],
            "soft_vs_hard_min_db": min(r["metrics"]["soft_vs_hard_db"] for r in rows),
            "closest_takes_min_db": min(r["metrics"]["closest_takes_db"] for r in rows),
            "mean_centroid_hz": round(float(np.mean([r["metrics"]["centroid_hz"] for r in rows]))),
            "room": round(float(np.mean([r["macros"]["ROOM"] for r in rows])), 2),
            "age": round(float(np.mean([r["macros"]["AGE"] for r in rows])), 2),
            "chokes": K.CHOKES[kit["id"]],
        })
        for r in rows:
            zl = layers[r["pad"]]
            save(f"velocity/{kit['id']}", r["pad"].lower(), chain([zl[z][0] for z in ZONES], 0.3),
                 f"{r['pad']} {r['label']}: soft, medium, hard (velocity {ZONES['s']}, {ZONES['m']}, {ZONES['h']}), "
                 f"each peak-matched so only the stroke differs.",
                 {k: r["metrics"][k] for k in ("soft_vs_hard_db", "soft_centroid_hz", "centroid_hz")},
                 {"pad": r["pad"], "label": r["label"], "kit": kit["id"]})
            save(f"takes/{kit['id']}", r["pad"].lower(), chain(zl["h"], 0.25),
                 f"{r['pad']} {r['label']}: the four round-robin takes, hard, in the order the pad deals them.",
                 {k: r["metrics"][k] for k in ("closest_takes_db", "widest_takes_db")},
                 {"pad": r["pad"], "label": r["label"], "kit": kit["id"]})
        k = MANIFEST["kits"][-1]
        print(kit["id"], "closest pads", pair, "soft/hard min", k["soft_vs_hard_min_db"], "takes min",
              k["closest_takes_min_db"], flush=True)

    # Between kits: the same pad slot in two kits, and any pad that is the same voice at the same settings.
    between = {}
    for a, b in itertools.combinations([k["id"] for k in K.ALL], 2):
        ka = next(k for k in K.ALL if k["id"] == a)
        kb = next(k for k in K.ALL if k["id"] == b)
        d = [ms.take_distance_db(kit_layers[a][p]["h"][0], kit_layers[b][p]["h"][0]) for p in kit_layers[a]]
        same = [(pa[0], pb[0]) for pa in ka["pads"] for pb in kb["pads"]
                if pa[2] == pb[2] and c.settled(pa[2], pa[3]) == c.settled(pb[2], pb[3])]
        shared_voices = sorted({pa[2] for pa in ka["pads"]} & {pb[2] for pb in kb["pads"]})
        between[f"{a}|{b}"] = {"mean_slot_db": round(float(np.mean(d)), 1), "min_slot_db": round(min(d), 2),
                               "min_slot": f"A{int(np.argmin(d)) + 1:02d}", "identical_pads": same,
                               "shared_voices": shared_voices}
    MANIFEST["between"] = between
    print("between", json.dumps(between), flush=True)

    # -------------------------------------------------------------------------- every voice, every stroke
    voice_jobs = [(v, {}, s, 0) for v in c.VOICES for s in (ZONES["s"], 1.0)]
    vr = pool.map(_job, voice_jobs, chunksize=2)
    for i, voice in enumerate(c.VOICES):
        s, y = vr[2 * i][0], vr[2 * i + 1][0]
        save("voices", voice.lower(), chain([s, y], 0.3), f"{voice} ({c.VOICES[voice][0]}): soft then hard, at its defaults.",
             {"centroid_hz": ms.centroid(y), "soft_centroid_hz": ms.centroid(s), "decay_ms": ms.decay_ms(y),
              "soft_vs_hard_db": dist(voice, s, y)}, {"voice": voice})

    stroke_specs = []
    for voice in ("QUINTO", "CONGA", "TUMBA", "BONGO_HIGH", "BONGO_LOW", "NDER", "MBENG", "COL", "TIMBALE", "COWBELL",
                  "BIG_BELL", "SHEKERE", "SHAKER"):
        for x, name in c.STROKES[c.STROKE_FAMILY[voice]]:
            stroke_specs.append((voice, {"STROKE": x}, name))
    for voice in ("DUNDUN", "TAMA"):
        for x, name in ((0.0, "press up"), (0.5, "let go"), (1.0, "up and down")):
            stroke_specs.append((voice, {"CONTOUR": x, "SQUEEZE": .7}, name))
    sr = pool.map(_job, [(v, mm, 1.0, 0) for v, mm, _ in stroke_specs], chunksize=2)
    by_voice: dict = {}
    for (voice, mm, name), (y, _) in zip(stroke_specs, sr):
        by_voice.setdefault(voice, []).append((name, y))
    for voice, items in by_voice.items():
        ys = [y for _, y in items]
        pairs = [ms.take_distance_db(a, b) for a, b in zip(ys, ys[1:])]
        save("strokes", voice.lower(), chain(ys, 0.35),
             f"{voice}: " + ", then ".join(n for n, _ in items) + ". Hard, first take, each peak-matched.",
             {"centroid_hz": ms.centroid(ys[0]), "closest_strokes_db": min(pairs)}, {"voice": voice,
                                                                                    "strokes": [n for n, _ in items]})

    # -------------------------------------------------------------------------- fitted voices against their recordings
    REF = os.environ.get("CALABASH_REF_WAVS")
    MANIFEST["refs"] = []
    if REF and os.path.isdir(REF):
        from scipy.io import wavfile
        from scipy import signal as sg
        import fitted as F

        def rec(name, secs, floor):
            _, x = wavfile.read(os.path.join(REF, name + ".wav"))
            x = x.astype(float)
            a = np.abs(x)
            i = max(0, int(np.argmax(a > .05 * a.max())) - 22)
            x = x[i:i + int(secs * RATE)]
            x = np.pad(x, (0, max(0, int(secs * RATE) - len(x))))
            x = sg.sosfiltfilt(sg.butter(4, max(floor, 30) / (RATE / 2), "high", output="sos"), x)
            return x / np.max(np.abs(x)) * .89

        dry = {"ROOM": 0.0, "AGE": 0.0}
        refs = [("QUINTO", {"STROKE": c.STROKES["HAND"][1][0]}, "quinto_open"), ("QUINTO", {"STROKE": c.STROKES["HAND"][2][0]}, "quinto_muff"),
                ("CONGA", {"STROKE": c.STROKES["HAND"][1][0]}, "conga_open"), ("CONGA", {"STROKE": c.STROKES["HAND"][2][0]}, "conga_muff"),
                ("TUMBA", {"STROKE": c.STROKES["HAND"][1][0]}, "tumba_open"), ("TUMBA", {"STROKE": c.STROKES["HAND"][2][0]}, "tumba_muff"),
                ("BONGO_HIGH", {"STROKE": 1 / 3}, "bongo_hi_open"), ("BONGO_HIGH", {"STROKE": 2 / 3}, "bongo_hi_muff"),
                ("BONGO_LOW", {"STROKE": 1 / 3}, "bongo_lo_open"), ("BONGO_LOW", {"STROKE": 2 / 3}, "bongo_lo_muff"),
                ("COWBELL", {"STROKE": 0.0}, "bell1_mouth"), ("COWBELL", {"STROKE": .5}, "bell1_body"),
                ("COWBELL", {"STROKE": 1.0}, "bell1_muted"), ("BIG_BELL", {"STROKE": 0.0}, "bell2_open"),
                ("BIG_BELL", {"STROKE": 1.0}, "bell2_muted"), ("AGOGO_HIGH", {}, "agogo_hi"), ("AGOGO_LOW", {}, "agogo_lo"),
                ("CLAVE", {}, "clave2"), ("STICKS", {}, "clave1"), ("SHAKER", {"STROKE": 0.0}, "shaker_down"),
                ("SHAKER", {"STROKE": .5}, "shaker_up"), ("SHAKER", {"STROKE": 1.0}, "shaker_hit"),
                ("CLAP", {"CROWD": 0.0}, "clap_solo"), ("CLAP", {"CROWD": 1.0}, "clap_group")]
        rr = pool.map(_job, [(v, dict(dry, **mm), 1.0, 0) for v, mm, _ in refs], chunksize=2)
        for (voice, mm, fit), (y, _) in zip(refs, rr):
            d = F.data()["voices"][fit]
            secs = min(1.0, len(y) / RATE)
            y = y[:int(secs * RATE)]
            x = rec(d["files"][0], secs, d.get("floor", 40))
            d150, dall = ms.take_distance_db(y, x), full_distance_db(y, x)
            save("refs", fit, chain([y, x], 0.5),
                 f"{voice} ({fit.replace('_', ' ')}) dry, then the VCSL recording it was fitted to ({d['files'][0]}).",
                 {"vs_recording_db": d150, "vs_recording_whole_db": dall, "centroid_hz": ms.centroid(y),
                  "rec_centroid_hz": ms.centroid(x)}, {"voice": voice})
            MANIFEST["refs"].append({"voice": voice, "fit": fit, "recording": d["files"][0], "first150_db": d150,
                                     "whole_db": dall})
        print("refs", [(r["fit"], r["first150_db"]) for r in MANIFEST["refs"]], flush=True)

    # -------------------------------------------------------------------------- control sweeps
    sweep = [(v, mac, x) for v in c.VOICES for mac in c.macros_for(v) for x in (0.0, 0.5, 1.0)]
    swr = pool.map(_job, [(v, {mac: x}, 1.0, 0) for v, mac, x in sweep], chunksize=3)
    activity = {}
    for i in range(0, len(sweep), 3):
        voice, mac, _ = sweep[i]
        lo, mid, hi = swr[i][0], swr[i + 1][0], swr[i + 2][0]
        d = full_distance_db(lo, hi)
        activity[f"{voice}.{mac}"] = d
        save("macros", f"{voice.lower()}-{mac.lower()}", chain([lo, mid, hi], 0.3),
             f"{voice} {mac} at 0, 0.5, 1: {c.MEANING[mac]}.",
             {"centroid_lo_hz": ms.centroid(lo), "centroid_hi_hz": ms.centroid(hi),
              "decay_lo_ms": ms.decay_ms(lo), "decay_hi_ms": ms.decay_ms(hi), "lo_vs_hi_db": d},
             {"voice": voice, "macro": mac})
    print("swept", len(sweep) // 3, "controls", flush=True)

    # -------------------------------------------------------------------------- checks
    checks = MANIFEST["checks"]
    checks["deterministic"] = all(np.array_equal(c.render(v, take=t), c.render(v, take=t))
                                  for v in c.VOICES if v not in LONG for t in range(2))
    renders = [y for k in kit_layers.values() for zl in k.values() for ys in zl.values() for y in ys]
    checks["renders"] = len(renders)
    checks["finite"] = all(bool(np.all(np.isfinite(y))) for y in renders)
    checks["peak_max"] = round(max(float(np.max(np.abs(y))) for y in renders), 4)
    checks["dc_max"] = round(max(abs(float(np.mean(y))) for y in renders), 5)
    quick = [v for v in c.VOICES if v not in LONG]
    checks["unknown_key_ignored"] = all(np.array_equal(c.render(v), c.render(v, {"NOPE": .9})) for v in quick)
    checks["nan_macro_falls_back"] = all(np.array_equal(c.render(v), c.render(v, {"TUNE": float("nan")})) for v in quick)
    checks["nan_velocity_falls_back"] = all(np.array_equal(c.render(v), c.render(v, velocity=float("nan"))) for v in quick)
    xx = np.random.default_rng(1).standard_normal(4000)
    checks["age_zero_is_bypass"] = bool(np.array_equal(c.e.warmth_chain(xx, 0.0, 1.0), xx))
    checks["macro_activity_db"] = activity
    checks["weakest_macro"] = min(activity, key=activity.get)
    checks["weakest_macro_db"] = activity[checks["weakest_macro"]]
    tm = [t for t, _ in timings]
    checks["render_ms_mean"] = round(float(np.mean(tm)), 1)
    checks["render_ms_median"] = round(float(np.median(tm)), 1)
    slow = max(timings)
    checks["render_ms_max"] = round(slow[0], 1)
    checks["render_ms_max_voice"] = slow[1]
    by_v: dict = {}
    for t, v in timings:
        by_v.setdefault(v, []).append(t)
    checks["render_ms_median_by_voice"] = {v: round(float(np.median(x)), 1) for v, x in by_v.items()}
    checks["soft_vs_hard_min_db"] = min(k["soft_vs_hard_min_db"] for k in MANIFEST["kits"])
    checks["closest_takes_min_db"] = min(k["closest_takes_min_db"] for k in MANIFEST["kits"])
    checks["closest_pads_min_db"] = min(k["closest_pads_db"] for k in MANIFEST["kits"])
    checks["identical_pads_between_kits"] = sum(len(v["identical_pads"]) for v in MANIFEST["between"].values())
    print(json.dumps({k: v for k, v in checks.items() if k not in ("macro_activity_db", "render_ms_median_by_voice")}, indent=1))

    json.dump(MANIFEST, open(os.path.join(OUT, "manifest.json"), "w"), indent=1)
    pool.close()
    print("wrote", OUT)
