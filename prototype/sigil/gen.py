"""SIGIL round 0: render the three kits (16 pads x 3 zones x 4 takes, rolls, grooves), check them, and measure
them against the owner's references. Python harness, not Kotlin; nothing heard.

    python3 gen.py OUT

Writes OUT/<kit>/<NN>_<pad>/z<zone>_t<take>.wav, OUT/<kit>/groove.wav, and OUT/report.json.
"""
from __future__ import annotations

import functools
import json
import math
import os
import sys

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import sigil_proto as sp  # noqa: E402
import measure as ms  # noqa: E402
from kits import KITS, ZONES, TAKES  # noqa: E402

RATE = sp.RATE


def touch(v):
    """The house touch law (CypherKits.touch): a soft hit keeps .35 of the level, so velocity is heard as
    character first and level second."""
    return 0.35 + 0.65 * v


def kit_by_id(kid):
    return next(k for k in KITS if k["id"] == kid)


def raw_hit(kit, idx, v, rng, extra=None):
    p = dict(kit["pads"][idx]["params"])
    if extra:
        p.update(extra)
    return sp.VOICES[kit["pads"][idx]["voice"]](p, v, rng)


def printed(kit, raw, amount, v=1.0):
    """The kit's print (speaker, slam or tape). A soft hit goes into it softer, so it saturates less: the print is
    part of the velocity law, not a fixed colour after it."""
    return sp.PRINTS[kit["print"]](sp.norm(raw, 0.9 * touch(v)), amount)


@functools.lru_cache(maxsize=None)
def render_pad(kid, idx, v, take):
    """One hit: the voice, the kit's print, the house output stage, levelled to the pad's gain times touch."""
    kit = kit_by_id(kid)
    pd = kit["pads"][idx]
    rng = sp.rng_for(kid, idx, v, take)
    amount = pd["params"].get("print_amount", kit["print_amount"])
    y = sp.finish(printed(kit, sp.touch_tone(raw_hit(kit, idx, v, rng), v), amount, v), pd["params"]["len"])
    return sp.norm(y, pd["gain"] * touch(v)).astype(np.float32)


@functools.lru_cache(maxsize=None)
def render_roll(kid, v, take):
    """A15: strokes accelerating from `from_div` to `to_div` per beat, each a fresh strike, louder as it goes."""
    kit = kit_by_id(kid)
    ro = kit["roll"]
    beat = 60.0 / kit["bpm"]
    total = sp.n_of(ro["seconds"] + 0.8)
    out = np.zeros(total)
    t, k = 0.0, 0
    while t < ro["seconds"]:
        prog = t / ro["seconds"]
        div = ro["from_div"] * (ro["to_div"] / ro["from_div"]) ** prog
        vel = (ro["vel_from"] + (ro["vel_to"] - ro["vel_from"]) * prog) * v
        idx = ro["pad"] if ("alt_pad" not in ro or k % 2 == 0) else ro["alt_pad"]
        extra = {}
        if "rise" in ro:
            extra["ratio"] = kit["pads"][idx]["params"]["ratio"] * (1 + ro["rise"] * prog)
        h = sp.touch_tone(raw_hit(kit, idx, max(vel, 0.05), sp.rng_for(kid, "roll", v, take, k), extra), vel)
        at = sp.n_of(t)
        h = h[: total - at] * (0.35 + 0.65 * vel)
        out[at: at + len(h)] += h
        t += beat / div
        k += 1
    y = sp.finish(printed(kit, out, kit["print_amount"], v))
    src = kit["pads"][ro["pad"]]
    return sp.norm(y, src["gain"] * touch(v)).astype(np.float32)


def zone_of(ch):
    return {"X": 1.0, "x": 0.7, "o": 0.4}.get(ch)


def groove_notes(kit):
    """(time_s, pad, velocity) for every written note: swing on the off sixteenths, a 2 ms human jitter."""
    g = kit["groove"]
    s16 = 60.0 / kit["bpm"] / 4
    swing_delay = s16 * (2 * kit["swing"] / 100 - 1)
    rng = sp.rng_for(kit["id"], "groove-jitter")
    notes = []
    for idx, line in g["steps"].items():
        steps = line.replace(" ", "")
        for i, ch in enumerate(steps):
            if ch == ".":
                continue
            t0 = i * s16 + (swing_delay if i % 2 else 0.0)
            pd = kit["pads"][idx]
            if pd["voice"] == "shaker":
                t0 -= sp.shaker_lead(pd["params"])
            if ch in "rt":
                n = 2 if ch == "r" else 3
                for j in range(n):
                    notes.append((t0 + j * s16 / n, idx, (0.7, 0.4, 0.7)[j]))
            else:
                notes.append((t0 + rng.normal(0, 0.002), idx, zone_of(ch)))
    return sorted(notes)


def groove_stems(kid):
    """Each pad's part of the groove on its own, before the bus: the direct measure of the balance (the crude
    onset filing in measure.py misreads a dense groove; STAGGER learned that the hard way)."""
    kit = kit_by_id(kid)
    g = kit["groove"]
    seconds = g["bars"] * 4 * 60.0 / kit["bpm"] + 1.2
    stems, turn = {}, {}
    for t, idx, v in groove_notes(kit):
        out = stems.setdefault(idx, np.zeros(int(seconds * RATE)))
        zv = min(ZONES, key=lambda z: abs(z - v))
        take = turn.get(idx, 0)
        turn[idx] = take + 1
        h = render_pad(kid, idx, zv, take % TAKES) * g["mix"].get(idx, 0.5)
        at = max(0, int(t * RATE))
        h = h[: len(out) - at]
        out[at: at + len(h)] += h
    return stems


def balance(kid):
    """Peak of each role's stem against the main kick's, in dB (the references: snare within about 3 dB of the
    kick, hats 10 to 20 dB under it). A drumless kit has no kick: its loudest stem is the reference."""
    kit = kit_by_id(kid)
    stems = groove_stems(kid)
    kicks = [i for i, p in enumerate(kit["pads"]) if p["voice"] == "kick" and i in stems]
    k = np.max(np.abs(stems[kicks[0]])) if kicks else max(np.max(np.abs(s)) for s in stems.values())
    out = {}
    for idx, s in stems.items():
        out[kit["pads"][idx]["name"]] = 20 * math.log10(max(np.max(np.abs(s)), 1e-9) / k)
    return out


@functools.lru_cache(maxsize=None)
def render_groove(kid):
    """A16: the written groove played on the kit's own pads (zone by velocity, takes dealt in turn), summed, then
    the kit's bus: IRON a lighter blown speaker, NEON the slam, ASH tape with its wow, flutter, hiss and crackle."""
    kit = kit_by_id(kid)
    g = kit["groove"]
    beat = 60.0 / kit["bpm"]
    seconds = g["bars"] * 4 * beat + 1.2
    out = np.zeros(int(seconds * RATE))
    turn = {}
    for t, idx, v in groove_notes(kit):
        zv = min(ZONES, key=lambda z: abs(z - v))
        take = turn.get(idx, 0)
        turn[idx] = take + 1
        h = render_pad(kid, idx, zv, take % TAKES) * g["mix"].get(idx, 0.5)
        at = max(0, int(t * RATE))
        h = h[: len(out) - at]
        out[at: at + len(h)] += h
    up = signal.resample_poly(out, 4, 1)
    if kit["print"] == "blown":
        up = sp.blown_speaker(up, 0.25)
    elif kit["print"] == "slam":
        up = sp.slam(up, 0.45)
    elif kit["print"] == "sampler":
        up = sp.sampler(up, 0.3)
    else:
        up = sp.tape(up, 0.3)
    y = sp.finish(up, seconds)
    if kit["print"] == "tape":
        y = sp.tape_motion(y, sp.sk.seed_for("SIGIL", kid, "motion"))
    elif kit["print"] == "sampler":
        # the record the sampler was fed from: its warp and its crackle, no tape hiss beyond a trace
        y = sp.tape_motion(y, sp.sk.seed_for("SIGIL", kid, "motion"), depth=0.6, crackle_per_s=40)
    return sp.norm(y, 0.97).astype(np.float32)


# ------------------------------------------------------------------ checks

def closeness_db(a, b):
    """The house distance between two takes: how far under the take the difference sits. A copy reads > 40 dB."""
    n = min(len(a), len(b))
    a, b = a[:n].astype(float), b[:n].astype(float)
    return 20 * math.log10(np.sqrt(np.mean(a * a)) / max(np.sqrt(np.mean((a - b) ** 2)), 1e-12))


def centroid_head(x, ms_=120):
    return ms.centroid(x[: int(ms_ * 1e-3 * RATE)].astype(float))


def hf_share(x, above_hz, ms_=120):
    """Share of the first 120 ms above `above_hz` (the pad's own hard-hit centroid): the strike's brightness,
    relative to the pad. A plain centroid misreads a sub-heavy hit (a hand drum reads its fundamental whatever
    the strike does) and a fixed 1 kHz split cannot see a hat or a shaker darken (all of it is above 1 kHz)."""
    return ms.band_share(x[: int(ms_ * 1e-3 * RATE)].astype(float), above_hz, 20000)


def pad_checks(kid, idx, render, window_ms=120):
    """window_ms: how much of each hit the brightness reads (a roll is read whole; its first strokes are soft in
    every zone)."""
    # twice the hard hit's centroid: at the centroid itself the split sat on ASH's rim's fundamental (629 Hz against
    # 610), so each take's hair of retuning moved the reading; an octave up it reads the overtones a strike brightens
    split = 2 * float(np.median([centroid_head(render(kid, idx, ZONES[-1], t), window_ms) for t in range(TAKES)]))
    # a hat already near the top of the band has nothing an octave up: read it above its centroid instead
    if np.median([hf_share(render(kid, idx, ZONES[-1], t), split, window_ms) for t in range(TAKES)]) < 1e-4:
        split /= 2
    zones = {}
    for v in ZONES:
        takes = [render(kid, idx, v, t) for t in range(TAKES)]
        close = max(closeness_db(takes[i], takes[j]) for i in range(TAKES) for j in range(i + 1, TAKES))
        zones[v] = {
            "peak": float(max(np.max(np.abs(x)) for x in takes)),
            "dc": float(max(abs(np.mean(x)) for x in takes)),
            "finite": bool(all(np.all(np.isfinite(x)) for x in takes)),
            "centroid_hz": float(np.median([centroid_head(x, window_ms) for x in takes])),
            "hf_share": float(np.median([hf_share(x, split, window_ms) for x in takes])),
            "closest_takes_db": float(close),
        }
    soft, hard = zones[ZONES[0]], zones[ZONES[-1]]
    return {
        "zones": {str(k): v for k, v in zones.items()},
        "soft_darker": soft["hf_share"] < hard["hf_share"],
        "soft_over_hard_centroid": soft["centroid_hz"] / hard["centroid_hz"],
        "soft_over_hard_hf_db": 10 * math.log10(max(soft["hf_share"], 1e-9) / max(hard["hf_share"], 1e-9)),
        "worst_closest_takes_db": max(z["closest_takes_db"] for z in zones.values()),
        "ok": all(z["finite"] and z["peak"] <= 1.0 and z["dc"] < 0.005 for z in zones.values()),
    }


def drum_stats(x, kind):
    x = x.astype(float)
    d = {"to_m20db_ms": ms.decay_ms(x)}
    if kind == "kick":
        d["f0_attack_hz"] = ms.f0_at(x[:2048])
        d["f0_body_hz"] = ms.f0_at(x[int(0.08 * RATE):])
        d["harmonics_over_fund_db"] = ms.harm_ratio(x, ms.f0_at(x))
        d["share_150_400"] = ms.band_share(x[: int(0.3 * RATE)], 150, 400)
    else:
        d["centroid_120ms_hz"] = centroid_head(x)
    return d


def main(out):
    os.makedirs(out, exist_ok=True)
    report = {"kits": {}}
    for kit in KITS:
        kid = kit["id"]
        kr = {"title": kit["title"], "bpm": kit["bpm"], "pads": []}
        for idx, pd in enumerate(kit["pads"]):
            folder = os.path.join(out, kid, f"{idx + 1:02d}_{pd['name'].replace(' ', '_')}")
            os.makedirs(folder, exist_ok=True)
            for zi, v in enumerate(ZONES):
                for t in range(TAKES):
                    sp.sk.write_wav(os.path.join(folder, f"z{zi}_t{t}.wav"), render_pad(kid, idx, v, t))
            c = pad_checks(kid, idx, render_pad)
            c["name"] = pd["name"]
            c["voice"] = pd["voice"]
            if pd["voice"] in ("kick", "snare"):
                c["stats"] = drum_stats(render_pad(kid, idx, 1.0, 0), pd["voice"])
            kr["pads"].append(c)
        # A15
        ro = kit["roll"]
        folder = os.path.join(out, kid, f"15_{ro['name'].replace(' ', '_')}")
        os.makedirs(folder, exist_ok=True)
        for zi, v in enumerate(ZONES):
            for t in range(TAKES):
                sp.sk.write_wav(os.path.join(folder, f"z{zi}_t{t}.wav"), render_roll(kid, v, t))
        c = pad_checks(kid, None, lambda k, _i, v, t: render_roll(k, v, t), window_ms=4000)
        c["name"] = ro["name"]
        c["voice"] = "roll"
        c["seconds"] = len(render_roll(kid, 1.0, 0)) / RATE
        kr["pads"].append(c)
        # A16
        g = render_groove(kid)
        sp.sk.write_wav(os.path.join(out, kid, "groove.wav"), g)
        kr["groove"] = ms.analyse_signal(kid + "_groove", kit["bpm"], g)
        kr["groove"]["hits"] = ms.hit_summary(g.astype(float))
        kr["groove_seconds"] = len(g) / RATE
        kr["groove_balance_db"] = balance(kid)
        report["kits"][kid] = kr
        print(f"{kit['title']}: rendered", flush=True)
    json.dump(report, open(os.path.join(out, "report.json"), "w"), indent=1, default=float)
    summarize(report)


def summarize(report):
    for kid, kr in report["kits"].items():
        print(f"\n== {kr['title']} ({kr['bpm']} BPM)")
        for i, p in enumerate(kr["pads"]):
            st = p.get("stats", {})
            extra = " ".join(f"{k}={v:.3g}" for k, v in st.items())
            print(f" A{i + 1:02d} {p['name']:13s} ok={p['ok']} soft vs hard bright {p['soft_over_hard_hf_db']:6.1f} dB"
                  f" closest takes {p['worst_closest_takes_db']:5.1f} dB  {extra}")
        g = kr["groove"]
        b = list(g["bands"].values())
        print(f" groove: rms {g['rms_dbfs']} dBFS crest {g['crest_db']} 95% under {g['roll95_hz']} Hz "
              f"bands {b} swing {g['groove_all'].get('swing_pct_16th')}")
        print("  balance vs kick (dB):", {k: round(v, 1) for k, v in kr["groove_balance_db"].items()})
        for k, d in g["hits"].items():
            print("   ", k, {a: (round(b_, 1) if isinstance(b_, float) else b_) for a, b_ in d.items()})


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "out"))
