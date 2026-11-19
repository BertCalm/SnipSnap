"""RIDDIM round 3: the wider kits (Python harness, not Kotlin).

    python3 kits_wide.py OUT

The owner chose the wider collection in round 0 and asked for all three of its styles in one round
(2026-11-18). Each kit is built to numbers measured from the owner's reference loops (`reference_targets.json`,
round-3 entries); no reference audio is copied, sliced or fitted.

  * Baile Funk, 130 BPM, 58% swing. Brazilian funk: one short, tight kick a bar, the tamborzao hand-drum
    figure (3+3+2) on a tumba and quinto, a clap, a high "ting" bell and a ratchet. A16 is a ratchet riser.
  * Sabar Swing, 103 BPM, 61% swing. A slow, dark West African groove: a deep sub kick that lands just after
    each beat, CALABASH's approved sabar drums (mbeng, nder, col), a dundun and a talking-drum call over
    swung hats. A16 is a sabar roll. It is not CALABASH MBALAX (126 BPM, straight and bright).
  * Wood and Bronze, 96 BPM, 55% swing. A quiet, pitched kit in D minor: five tuned woods and four bronze
    pairs over a soft kick and hats. A16 is a wood tremolo.

Swing is the groove's, not the kit's: the app's pads hold sounds, and the MPC or the app's own sequencer
sets swing. The listening page plays each groove swung so it can be judged as the references were.

Velocity and round robin work as in round 1: three zones (0.35, 0.65, 1.0) of four takes per pad, dealt in turn
per zone; `main` checks every pad for near-copies and for darker-when-softer, as `kits.py` does.
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kits as K  # noqa: E402  (import before riddim_proto: CALABASH's folder, which has a kits.py too, joins the path then)
import riddim_proto as rp  # noqa: E402

RATE = K.RATE
ZONES, TAKES, PADS = K.ZONES, K.TAKES, K.PADS
SPAN = dict(K.SPAN, TING=12, RATCHET=12)
K.SPAN.update(SPAN)                 # `kits.st` reads SPAN for the new voices too
st, pad = K.st, K.pad


def roll(label, voice, macros, kind):
    return {"label": label, "voice": voice, "macros": macros, "choke": 0, "roll": kind}


# The Wood and Bronze kit's mallet. 0.5 is the wood of rounds 1 and 2; the owner heard round 3's woods and asked
# for more attack (2026-11-18), heard 0.5, 0.85 and 1.0 side by side, and chose the hardest (2026-11-19).
# `riddim_proto.wood` says what BEATER changes.
WOOD_BEATER = 1.0

# Pitches below are written in semitones from each voice's default note: WOOD F#3, METAL D5, BOOM F1,
# DEMBOW_KICK about 27 Hz, KICK about 40 Hz, TING 3234 Hz.
KITS = {
    "baile_funk": {
        "title": "Baile Funk", "bpm": 130, "swing": 0.58, "key": "",
        "about": "Brazilian funk: a short tight kick, the tamborzao on tumba and quinto, a clap, a high ting bell and a ratchet that scrapes the off-beats.",
        "pads": [
            pad("Tight kick", "KICK", st("KICK", 6, DECAY=.2, DRIVE=.7)), pad("Clap", "CLAP", st("CLAP", 2, DECAY=.35)),
            pad("Closed hat", "HAT_CLOSED", st("HAT_CLOSED", 2, DECAY=.25), 1), pad("Open hat", "HAT_OPEN", st("HAT_OPEN", 2, DECAY=.35), 1),
            pad("Short boom", "BOOM", st("BOOM", 5, DECAY=.25)),
            pad("Tambor low", "TUMBA", {"STROKE": 1 / 3}), pad("Tambor slap", "TUMBA", {"STROKE": 1.0}),
            pad("Tambor high", "QUINTO", {"STROKE": 1 / 3}),
            pad("Ting", "TING"), pad("Ting low", "TING", st("TING", -5)),
            pad("Ratchet", "RATCHET"), pad("Long ratchet", "RATCHET", {"DECAY": .85, "WAVE": .35}),
            pad("Agogo", "AGOGO_HIGH"), pad("Shaker", "SHAKER"), pad("Rimshot", "RIMSHOT", st("RIMSHOT", 3)),
            roll("Ratchet riser", "RATCHET", {"DECAY": .3}, "ratchet"),
        ],
    },
    "sabar_swing": {
        "title": "Sabar Swing", "bpm": 103, "swing": 0.61, "key": "G minor",
        "about": "Slow, dark and swung: a deep sub kick just after each beat, the sabar family, a dundun and a talking-drum call over late hats.",
        "pads": [
            pad("Sub kick", "DEMBOW_KICK", st("DEMBOW_KICK", 4, DECAY=.4)), pad("Snare", "SNARE", st("SNARE", -3, DECAY=.4)),
            pad("Closed hat", "HAT_CLOSED", st("HAT_CLOSED", -3, DECAY=.3), 1), pad("Open hat", "HAT_OPEN", st("HAT_OPEN", -3), 1),
            pad("Low rim", "RIMSHOT", st("RIMSHOT", -3)),
            pad("Mbeng", "MBENG"), pad("Mbeng slap", "MBENG", {"STROKE": 1 / 3}),
            pad("Nder", "NDER"), pad("Col", "COL"), pad("Dundun", "DUNDUN"),
            pad("Talking call", "TALKING_CALL"), pad("Shekere", "SHEKERE"), pad("Tama", "TAMA"),
            pad("Clap", "CLAP", st("CLAP", -3, DECAY=.6)), pad("Boom G1", "BOOM", st("BOOM", 2, DECAY=.4)),
            roll("Sabar roll", "NDER", {}, "sabar"),
        ],
    },
    "wood_bronze": {
        "title": "Wood and Bronze", "bpm": 96, "swing": 0.55, "key": "D minor",
        "about": "Quiet and pitched: five hard-mallet woods (D3 to C4) and four bronze pairs (F5 to D6) in D minor over a soft kick, sticks and hats. Melodic answers on every pad.",
        "pads": [
            pad("Soft kick", "KICK", st("KICK", -2, DECAY=.45, DRIVE=.25)), pad("Sticks", "STICKS"),
            pad("Closed hat", "HAT_CLOSED", st("HAT_CLOSED", -4, DECAY=.2), 1), pad("Open hat", "HAT_OPEN", st("HAT_OPEN", -4, DECAY=.4), 1),
            pad("Claves", "CLAVE"),
            # A harder mallet than rounds 1 and 2 (BEATER 0.5): the owner heard round 3's woods as needing more attack.
            pad("Wood D3", "WOOD", st("WOOD", -4, BEATER=WOOD_BEATER)), pad("Wood F3", "WOOD", st("WOOD", -1, BEATER=WOOD_BEATER)),
            pad("Wood G3", "WOOD", st("WOOD", 1, BEATER=WOOD_BEATER)), pad("Wood A3", "WOOD", st("WOOD", 3, BEATER=WOOD_BEATER)),
            pad("Wood C4", "WOOD", st("WOOD", 6, BEATER=WOOD_BEATER)),
            # METAL at its default D5 does not darken from mid to soft (disclosed in round 2), so the bronze
            # pairs skip it and take D an octave up: F5, A5, C6, D6.
            pad("Bronze F5", "METAL", st("METAL", 3, WAVE=.35)), pad("Bronze A5", "METAL", st("METAL", 7, WAVE=.35)),
            pad("Bronze C6", "METAL", st("METAL", 10, WAVE=.35)), pad("Bronze D6", "METAL", st("METAL", 12, WAVE=.35)),
            pad("Rimshot", "RIMSHOT", st("RIMSHOT", -5, DECAY=.6)),
            roll("Wood tremolo", "WOOD", st("WOOD", -4, BEATER=WOOD_BEATER), "tremolo"),
        ],
    },
}


# ------------------------------------------------------------------ rolls and risers (one bar at the kit's tempo)

def roll_hits(kind, bpm):
    """(time_s, velocity_scale, tune_semitones) for each stroke of a one-bar roll, straight time."""
    beat = 60 / bpm
    if kind == "ratchet":      # eighths, sixteenths, then 32nds: the drag speeds up as the bar ends
        grid = [(0, 2, .5), (2, 3, .25), (3, 4, .125)]
    elif kind == "sabar":      # sixteenths, then a run of 32nds into the downbeat
        grid = [(0, 2, .25), (2, 4, .125)]
    else:                      # tremolo: 32nds the whole bar, a marimba player's roll
        grid = [(0, 4, .125)]
    hits = []
    for a, b, step in grid:
        x = a
        while x < b - 1e-9:
            hits.append(x)
            x += step
    out = []
    for x in hits:
        frac = x / 4
        vel = (0.3 + 0.7 * frac ** 1.3) if kind != "tremolo" else (0.25 + 0.75 * frac)   # crescendo
        semis = {"ratchet": 5 * frac, "sabar": 0.0, "tremolo": 0.0}[kind]                # the ratchet climbs
        out.append((x * beat, vel, semis))
    return out


def render_roll(p, bpm, v, take):
    n = int((4 * 60 / bpm + 0.6) * RATE)
    out = np.zeros(n)
    for i, (t, vs, semis) in enumerate(roll_hits(p["roll"], bpm)):
        m = dict(p["macros"])
        if semis:
            base = (m.get("TUNE", .5) - .5) * 2 * SPAN[p["voice"]]
            m["TUNE"] = 0.5 + (base + semis) / (2 * SPAN[p["voice"]])
        y = rp.render(p["voice"], m, max(.05, v * vs), take * 64 + i)
        a = int(t * RATE)
        out[a:a + len(y)] += y[: n - a]
    return out / np.max(np.abs(out)) * 0.89


def render_pad(p, bpm, v, take):
    return render_roll(p, bpm, v, take) if "roll" in p else rp.render(p["voice"], p["macros"], v, take)


# ------------------------------------------------------------------ grooves (authored sketches, not transcriptions)

GROOVES = {
    # (pad, step, velocity, bars): bars is a set of bar indices (0..3), or None for every bar.
    # Baile Funk: the kick once a bar; the tamborzao 3+3+2 (steps 0, 3, 6, 8, 12 measured on the tambor loop);
    # the clap on 3, 8 and 12 as the full loop measured; the ting on the 3+3+2 tops; ratchet on the off-beats.
    "baile_funk": [("A01", 0, 1.0, None), ("A05", 10, .8, {1, 3})]
    + [("A06", s, 1.0 if s in (0, 6) else .7, None) for s in (0, 3, 6, 8, 12)]
    + [("A07", s, .9, None) for s in (10, 14)] + [("A08", 13, .6, {0, 2})]
    + [("A02", s, 1.0 if s == 8 else .7, None) for s in (3, 8, 12)]
    + [("A09", s, .9 if s in (0, 8) else .55, None) for s in (0, 3, 4, 8, 10, 12, 14)]
    + [("A10", 6, .6, {1, 3})]
    + [("A11", s, .7, {0, 1, 2}) for s in (2, 6, 10, 14)] + [("A12", 14, .8, {1})]
    + [("A03", s, .45, {0, 1, 2}) for s in (1, 5, 9, 13)] + [("A04", 14, .7, {0, 2})]
    + [("A16", 0, 1.0, {3})],
    # Sabar Swing: the sub kick on step 1 of each beat (just after it), the snare on the backbeat, the mbeng
    # on every beat, the nder and col answering, the talking call once in four bars, swung hats.
    "sabar_swing": [("A01", s, 1.0, None) for s in (1, 5, 9, 13)] + [("A01", 0, .5, {0, 2})]
    + [("A02", s, 1.0, None) for s in (4, 12)] + [("A14", 12, .6, {1, 3})]
    + [("A06", s, .9, None) for s in (0, 4, 8, 12)] + [("A07", s, .6, None) for s in (7, 15)]
    + [("A08", s, .7, {0, 2}) for s in (6, 10, 11)] + [("A09", 14, .9, {1}), ("A10", 3, .7, {0, 2}), ("A10", 11, .6, {1})]
    + [("A11", 8, .8, {2})] + [("A12", s, .45, None) for s in (2, 6, 10, 14)]
    + [("A03", s, .9 if s in (3, 11) else .5, None) for s in (0, 3, 6, 8, 11, 14)] + [("A04", 14, .7, {1})]
    + [("A15", 0, .8, {1, 2}), ("A13", 9, .6, {2})] + [("A16", 0, 1.0, {3})],
    # Wood and Bronze: a sparse kick and sticks, a wood figure in D minor, bronze answers, quiet hats.
    "wood_bronze": [("A01", s, 1.0, None) for s in (0, 10)] + [("A02", s, .7, None) for s in (4, 12)]
    + [("A03", s, .5, None) for s in range(2, 16, 4)] + [("A04", 14, .6, {1, 3}), ("A05", 7, .6, {0, 2})]
    + [("A06", 0, .9, None), ("A08", 3, .7, None), ("A09", 6, .8, None), ("A07", 9, .6, {0, 2}), ("A10", 9, .7, {1}),
       ("A08", 12, .6, None), ("A06", 14, .5, {0, 1, 2})]
    + [("A11", 0, .9, {0, 2}), ("A12", 0, .9, {1}), ("A13", 8, .7, {0}), ("A14", 8, .7, {2}), ("A11", 8, .6, {1})]
    + [("A15", 12, .5, {1})] + [("A16", 0, 1.0, {3})],
}


def play_groove(kit, bars=4):
    """`kits.play_groove` with swing: odd sixteenths land `swing` of the way from straight (0.5) to a triplet
    feel (0.75) of each eighth, the way an MPC's swing percentage reads. Takes are dealt per pad per zone,
    and hats in one choke group cut each other with a 5 ms fade, as in round 1."""
    k = KITS[kit]
    pads = dict(zip(PADS, k["pads"]))
    step = 60 / k["bpm"] / 4
    late = (k.get("swing", 0.5) - 0.5) * 2 * step          # 58% at 130 BPM: 18 ms late
    total = int((bars * 16 * step + 2.5) * RATE)
    out = np.zeros(total)
    events = sorted((b * 16 + s, pid, vel) for pid, s, vel, which in GROOVES[kit] for b in range(bars)
                    if which is None or b in which)
    dealt, sounding = {}, {}
    for at_step, pid, vel in events:
        p = pads[pid]
        z = K.zone_of(vel)
        take = dealt.get((pid, z), 0) % TAKES
        dealt[(pid, z)] = take + 1
        y = render_pad(p, k["bpm"], ZONES[z], take) * (0.45 + 0.55 * vel)
        a = int((at_step * step + (late if at_step % 2 else 0)) * RATE)
        if p["choke"]:
            prev = sounding.get(p["choke"])
            if prev is not None:
                prev_buf, prev_at = prev
                cut = a - prev_at
                if 0 < cut < len(prev_buf):
                    f = int(0.005 * RATE)
                    out[a:prev_at + len(prev_buf)] -= prev_buf[cut:][: total - a]
                    seg = prev_buf[cut:cut + f]
                    out[a:a + len(seg)] += seg * np.linspace(1, 0, f)[: len(seg)]
            sounding[p["choke"]] = (y, a)
        out[a:a + len(y)] += y[: total - a]
    out = out[: int(bars * 16 * step * RATE) + int(0.4 * RATE)]
    return out / np.max(np.abs(out)) * 0.89


# ------------------------------------------------------------------ checks (Python harness)

# WOOD at 400 Hz, as RiddimTest measures it: a low wood has nothing above 2 kHz, so that split reads the noise floor.
SPLIT = dict(K.SPLIT, WOOD=400, TING=5000, RATCHET=2000, TUMBA=1000, QUINTO=1000, MBENG=1000, NDER=2000, COL=800,
             DUNDUN=1000, SHEKERE=6000, AGOGO_HIGH=3000, TALKING_CALL=1000)


def upper_share_db(y, voice):
    x = y[: int(.3 * RATE)]
    S = np.abs(np.fft.rfft(x)) ** 2
    f = np.fft.rfftfreq(len(x), 1 / RATE)
    return float(10 * np.log10(max(1e-20, S[f >= SPLIT.get(voice, 2000)].sum()) / S.sum()))


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
                    K.write_wav(os.path.join(out, kit, "pads", f"{pid}_z{zi}_t{t}.wav"), y)
            closest = max(K.take_distance_db(row[i], row[j]) for row in grid for i in range(TAKES) for j in range(i + 1, TAKES))
            bright = [float(np.mean([upper_share_db(y, p["voice"]) for y in row])) for row in grid]
            checks[kit][pid] = {"label": p["label"], "voice": p["voice"], "closest_takes_db": round(closest, 1),
                                "brightness_soft_mid_hard_db": [round(b, 1) for b in bright],
                                "darker_when_softer": bool(bright[0] < bright[1] < bright[2])}
            print(f"{kit:12s} {pid} {p['label']:14s} closest takes {closest:6.1f} dB  brightness {[round(b, 1) for b in bright]}"
                  f"  {'ok' if checks[kit][pid]['darker_when_softer'] else 'NOT MONOTONE'}")
        K.write_wav(os.path.join(out, kit, "groove.wav"), play_groove(kit))
    json.dump(checks, open(os.path.join(out, "checks.json"), "w"), indent=1)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "riddim-wide")
