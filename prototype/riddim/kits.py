"""RIDDIM round 1: three 16-pad kits built from the round-0 palette (Python harness, not Kotlin).

    python3 prototype/riddim/kits.py OUT     # every pad: 3 velocity zones x 4 takes, the grooves, checks.json

The owner heard round 0 on 2026-11-18 ("the sounds are pretty good so far"), chose the wider collection, and
agreed to three kits in the first round. These are the dancehall core; the wider kits (swung Brazilian funk,
swung West African, wood and metal) are the next round.

Layout follows docs/KIT_BEST_PRACTICES.md: kick A01, snare-family A02, closed hat A03, open hat A04 (one choke
group), the rest filling upward, and A16 the kit's roll or riser (the percussion gate). The three kits differ in
voices, tuning, decay, pad roles and tempo, not only in level.

Velocity and round robin, as the app deals them: each pad has three zones (soft, mid, hard; rendered at
velocity 0.35, 0.65 and 1.0) and four takes per zone, each a fresh seeded strike.
"""
from __future__ import annotations

import json
import os
import sys
import wave

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import riddim_proto as rp  # noqa: E402

RATE = rp.RATE
ZONES = (0.35, 0.65, 1.0)
TAKES = 4

# TUNE spans, in semitones each way, so a pad can be written in semitones (`st`).
SPAN = {"KICK": 6, "DEMBOW_KICK": 6, "BOOM": 12, "SLIDE": 12, "RIMSHOT": 6, "CLAP": 6, "SNARE": 6,
        "HAT_CLOSED": 6, "HAT_OPEN": 6, "WOOD": 12, "METAL": 12}


def st(voice, semis, **extra):
    """Macros for `voice` moved `semis` semitones from its default note."""
    m = dict(extra)
    if semis:
        m["TUNE"] = 0.5 + semis / (2 * SPAN[voice])
    return m


def pad(label, voice, macros=None, choke=0):
    return {"label": label, "voice": voice, "macros": macros or {}, "choke": choke}


def roll(label, voice, macros, kind):
    return {"label": label, "voice": voice, "macros": macros, "choke": 0, "roll": kind}


PADS = ["A%02d" % i for i in range(1, 17)]

KITS = {
    "clave_dancehall": {
        "title": "Clave Dancehall", "bpm": 102, "key": "F# minor",
        "about": "Bright and tight. Punch kick and rimshot on the clave, CC0 shaker, claves and cowbell, and four tuned woods for melodic answers.",
        "pads": [
            pad("Kick", "KICK"), pad("Rimshot", "RIMSHOT"), pad("Closed hat", "HAT_CLOSED", {"DECAY": .35}, 1),
            pad("Open hat", "HAT_OPEN", None, 1), pad("Clap", "CLAP"), pad("High kick", "KICK", st("KICK", 4, DECAY=.3)),
            pad("Tight snare", "SNARE", st("SNARE", 2, DECAY=.3)), pad("Shaker", "SHAKER"), pad("Claves", "CLAVE"),
            pad("Sticks", "STICKS"), pad("Cowbell", "COWBELL"), pad("Wood F#3", "WOOD"),
            pad("Wood A3", "WOOD", st("WOOD", 3)), pad("Wood C#4", "WOOD", st("WOOD", 7)), pad("Wood F#4", "WOOD", st("WOOD", 12)),
            roll("Rim roll", "RIMSHOT", {}, "rim"),
        ],
    },
    "dembow": {
        "title": "Dembow", "bpm": 95, "key": "",
        "about": "Deep and heavier. The sub-heavy dembow kick with more drive, a lower snare and clap, darker hats, hand drums and a talking drum.",
        "pads": [
            pad("Dembow kick", "DEMBOW_KICK", {"DRIVE": .7}), pad("Snare", "SNARE", st("SNARE", -2, DECAY=.55)),
            pad("Closed hat", "HAT_CLOSED", st("HAT_CLOSED", -2, DECAY=.4), 1), pad("Open hat", "HAT_OPEN", st("HAT_OPEN", -2), 1),
            pad("Wide clap", "CLAP", st("CLAP", -2, DECAY=.7)), pad("Low rim", "RIMSHOT", st("RIMSHOT", -4)),
            pad("Punch kick", "KICK"), pad("Shaker", "SHAKER"), pad("Conga", "CONGA"), pad("High bongo", "BONGO_HIGH"),
            pad("Cowbell", "COWBELL"), pad("Claves", "CLAVE"), pad("Talking drum", "TAMA"), pad("Sticks", "STICKS"),
            pad("Low metal G4", "METAL", st("METAL", -7, WAVE=.3)),
            roll("Snare roll", "SNARE", st("SNARE", -2, DECAY=.4), "snare"),
        ],
    },
    "boom_riddim": {
        "title": "Boom Riddim", "bpm": 100, "key": "F minor",
        "about": "The kick is the bass. Six tuned booms (F1 up to Eb2, one a slow slide) cut each other off like one bass voice; bronze pairs answer in F minor.",
        "pads": [
            pad("Boom F1", "BOOM", None, 2), pad("Ringing rim", "RIMSHOT", st("RIMSHOT", 2, DECAY=.7)),
            pad("Closed hat", "HAT_CLOSED", {"DECAY": .3}, 1), pad("Open hat", "HAT_OPEN", None, 1), pad("Clap", "CLAP"),
            pad("Slide F1", "SLIDE", None, 2), pad("Boom Ab1", "BOOM", st("BOOM", 3), 2), pad("Boom Bb1", "BOOM", st("BOOM", 5), 2),
            pad("Boom C2", "BOOM", st("BOOM", 7), 2), pad("Boom Eb2", "BOOM", st("BOOM", 10), 2),
            pad("Metal F5", "METAL", st("METAL", 3)), pad("Metal Ab5", "METAL", st("METAL", 6)), pad("Metal C6", "METAL", st("METAL", 10)),
            pad("Snare", "SNARE"), pad("Sticks", "STICKS"),
            roll("Hat riser", "HAT_CLOSED", {"DECAY": .3}, "hat"),
        ],
    },
}


# ------------------------------------------------------------------ rolls and risers (one bar at the kit's tempo)

def roll_hits(kind, bpm):
    """(time_s, velocity_scale, tune_semitones) for each stroke of a one-bar roll."""
    beat = 60 / bpm
    hits = []
    if kind == "rim":          # eighths, then sixteenths, then 32nds: the dancehall rim build
        grid = [(0, 2, .5), (2, 3, .25), (3, 4, .125)]
    elif kind == "snare":      # two beats of sixteenths, two of 32nds: the dembow snare fill
        grid = [(0, 2, .25), (2, 4, .125)]
    else:                      # hat riser: sixteenths, 32nds, then sextuplet 32nds (six per eighth)
        grid = [(0, 2, .25), (2, 3, .125), (3, 4, 1 / 12)]
    for a, b, step in grid:
        x = a
        while x < b - 1e-9:
            hits.append(x)
            x += step
    out = []
    for i, x in enumerate(hits):
        frac = x / 4
        vel = 0.3 + 0.7 * frac ** 1.3            # crescendo
        semis = {"rim": 3 * frac, "snare": 0.0, "hat": 9 * frac}[kind]   # rim and hats rise in pitch
        out.append((x * beat, vel, semis))
    return out


def render_roll(p, bpm, v, take):
    beat = 60 / bpm
    n = int((4 * beat + 0.6) * RATE)
    out = np.zeros(n)
    for i, (t, vs, semis) in enumerate(roll_hits(p["roll"], bpm)):
        m = dict(p["macros"])
        if semis:
            base = (m.get("TUNE", .5) - .5) * 2 * SPAN[p["voice"]]
            m = st(p["voice"], 0, **{k: val for k, val in m.items() if k != "TUNE"})
            m["TUNE"] = 0.5 + (base + semis) / (2 * SPAN[p["voice"]])
        y = rp.render(p["voice"], m, max(.05, v * vs), take * 64 + i)
        a = int(t * RATE)
        out[a:a + len(y)] += y[: n - a]
    return out / np.max(np.abs(out)) * 0.89


def render_pad(p, bpm, v, take):
    if "roll" in p:
        return render_roll(p, bpm, v, take)
    return rp.render(p["voice"], p["macros"], v, take)


# ------------------------------------------------------------------ grooves, played the way the app plays a kit

def zone_of(vel):
    return 0 if vel < .45 else 1 if vel < .8 else 2


GROOVES = {
    # (pad, step, velocity, bars) with bars a set of bar indices (0..3) or None for every bar.
    "clave_dancehall": [("A01", s, 1.0, None) for s in (0, 3, 6, 10, 12)]
    + [("A02", s, .6, None) for s in (3, 6, 10)] + [("A05", s, 1.0, None) for s in (4, 12)]
    + [("A03", s, .9 if s % 4 == 2 else .4, None) for s in range(0, 16, 2)] + [("A08", s, .6, None) for s in (1, 5, 9, 13)]
    + [("A12", 7, .9, {1}), ("A13", 9, .6, {1}), ("A14", 13, .9, {3}), ("A13", 15, .6, {3}), ("A09", 14, .7, {0, 2}),
       ("A06", 15, .6, {2}), ("A04", 14, .9, {1})],
    "dembow": [("A01", s, 1.0, None) for s in (0, 4, 8, 12)]
    + [("A02", s, 1.0 if s in (3, 11) else .7, None) for s in (3, 6, 11, 14)]
    + [("A03", s, .7, None) for s in (2, 6, 10, 14)] + [("A04", 14, .9, {3})]
    + [("A09", s, .6, None) for s in (7, 15)] + [("A11", 0, .9, {0}), ("A12", 10, .7, {2}), ("A13", 13, .9, {3}),
                                                 ("A15", 14, .7, {1}), ("A08", 2, .5, None), ("A08", 10, .5, None)],
    "boom_riddim": [("A01", 0, 1.0, {0, 2}), ("A01", 10, .7, {0}), ("A07", 0, 1.0, {1}), ("A06", 10, .9, {1}),
                    ("A10", 10, .7, {2}), ("A08", 0, 1.0, {3}), ("A09", 10, .9, {3})]
    + [("A02", s, 1.0, None) for s in (4, 12)] + [("A05", 12, .7, {1, 3})]
    + [("A03", s, .9 if s % 4 == 2 else .4, {0, 1, 2}) for s in range(16)]
    + [("A11", 14, .7, {0}), ("A12", 14, .7, {1}), ("A13", 14, .9, {2}), ("A15", 7, .5, {2})]
    + [("A16", 0, 1.0, {3})],      # the riser replaces the hats on bar 4
}


def play_groove(kit, bars=4):
    k = KITS[kit]
    pads = dict(zip(PADS, k["pads"]))
    step = 60 / k["bpm"] / 4
    total = int((bars * 16 * step + 2.5) * RATE)
    out = np.zeros(total)
    events = []
    for pid, s, vel, which in GROOVES[kit]:
        for b in range(bars):
            if which is None or b in which:
                events.append((b * 16 + s, pid, vel))
    events.sort()
    dealt = {}
    sounding = {}                                    # choke group -> (start, end) of the sound it may cut
    for at_step, pid, vel in events:
        p = pads[pid]
        z = zone_of(vel)
        take = dealt.get((pid, z), 0) % TAKES        # round robin: takes dealt in turn, per zone
        dealt[(pid, z)] = take + 1
        y = render_pad(p, k["bpm"], ZONES[z], take) * (0.45 + 0.55 * vel)
        a = int(at_step * step * RATE)
        if p["choke"]:
            prev = sounding.get(p["choke"])
            if prev is not None:                     # the new hit cuts the old one with a 5 ms fade
                f = int(0.005 * RATE)
                prev_buf, prev_at = prev
                cut = a - prev_at
                if 0 < cut < len(prev_buf):
                    out[a:prev_at + len(prev_buf)] -= prev_buf[cut:][: total - a]
                    ramp = np.linspace(1, 0, f)
                    seg = prev_buf[cut:cut + f]
                    out[a:a + len(seg)] += seg * ramp[: len(seg)]
            sounding[p["choke"]] = (y, a)
        out[a:a + len(y)] += y[: total - a]
    out = out[: int(bars * 16 * step * RATE) + int(0.4 * RATE)]
    return out / np.max(np.abs(out)) * 0.89


# ------------------------------------------------------------------ checks (Python harness)

def take_distance_db(a, b):
    n = min(len(a), len(b))
    a, b = a[:n], b[:n]
    return float(20 * np.log10(np.sqrt(np.mean(a * a)) / max(1e-12, np.sqrt(np.mean((a - b) ** 2)))))


SPLIT = {"KICK": 150, "DEMBOW_KICK": 150, "BOOM": 150, "SLIDE": 150, "CONGA": 1000, "BONGO_HIGH": 1500, "TAMA": 1000,
         "HAT_CLOSED": 9000, "HAT_OPEN": 9000, "SHAKER": 6000}


def upper_share_db(y, voice):
    """Brightness: the share of the first 300 ms above a split that suits the voice (dB)."""
    x = y[: int(.3 * RATE)]
    S = np.abs(np.fft.rfft(x)) ** 2
    f = np.fft.rfftfreq(len(x), 1 / RATE)
    split = SPLIT.get(voice, 2000)
    return float(10 * np.log10(max(1e-20, S[f >= split].sum()) / S.sum()))


def write_wav(path, y):
    pcm = (np.clip(y, -1, 1) * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())


def main(out):
    checks = {}
    for kit, k in KITS.items():
        os.makedirs(os.path.join(out, kit, "pads"), exist_ok=True)
        checks[kit] = {}
        for pid, p in zip(PADS, k["pads"]):
            grid = [[render_pad(p, k["bpm"], v, t) for t in range(TAKES)] for v in ZONES]
            for zi, row in enumerate(grid):
                for t, y in enumerate(row):
                    assert np.all(np.isfinite(y)) and np.max(np.abs(y)) <= 1.0, (kit, pid)
                    write_wav(os.path.join(out, kit, "pads", f"{pid}_z{zi}_t{t}.wav"), y)
            closest = max(take_distance_db(row[i], row[j]) for row in grid for i in range(TAKES) for j in range(i + 1, TAKES))
            bright = [float(np.mean([upper_share_db(y, p["voice"]) for y in row])) for row in grid]
            checks[kit][pid] = {"label": p["label"], "voice": p["voice"], "closest_takes_db": round(closest, 1),
                                "brightness_soft_mid_hard_db": [round(b, 1) for b in bright],
                                "darker_when_softer": bool(bright[0] < bright[1] < bright[2])}
            print(f"{kit:16s} {pid} {p['label']:14s} closest takes {closest:6.1f} dB  brightness {[round(b, 1) for b in bright]}"
                  f"  {'ok' if checks[kit][pid]['darker_when_softer'] else 'NOT MONOTONE'}")
        write_wav(os.path.join(out, kit, "groove.wav"), play_groove(kit))
    json.dump(checks, open(os.path.join(out, "checks.json"), "w"), indent=1)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "riddim-kits")
