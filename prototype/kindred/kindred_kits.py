"""KINDRED round 1: four genre kits from the one-voice engine (Python harness, not Kotlin).

Every pad is the same voice from kindred_proto (two VCOs, noise, ladder, three decays) with
its own settings. A pad may strike the voice more than once (a clap is three quick re-strikes,
the way the reference retriggers its envelopes). A15 is the kit's eight-step phrase, a seamless
loop of one voice (HOLD at its top). A16 is the kit's riser: the sequencer accelerating.

Velocity: each pad renders natively at .3, .6 and 1 (three zones). Takes: four fresh strikes
per zone (free-running oscillator phase, noise seed, a few cents of drift, a few % of decay,
and burst spacing for multi-strike pads). Each render is levelled, as the house does; the
listening strips then scale by velocity the way a sampler's velocity-to-level would.
"""
from __future__ import annotations

import json
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kindred_proto as kp  # noqa: E402
sk = kp.sk
RATE, FS = kp.RATE, kp.FS

FAST, SLOW = .0008, .1   # the reference's two VCA attack settings (about 1 ms and 100 ms)

BASE = dict(kp.VOICES)
BASE.update({
    "CLAP":    dict(f1=1000, f2=1500, w1=1, w2=1, pamt1=0, pamt2=0, ptau=.02, fm=0, sync=0,
                    lv1=0, lv2=0, lnoise=1.0, hp=0, cutoff=800, res=.55, famt=2.6, ftau=.03,
                    nmod=.3, atk=FAST, atau=.09, drive=1.6, seconds=.5, velp=.35,
                    burst=[(0, 1), (.010, .8), (.021, .9)]),
    "RIM":     dict(f1=1700, f2=2550, w1=1, w2=1, pamt1=.4, pamt2=.4, ptau=.005, fm=0, sync=0,
                    lv1=.6, lv2=.4, lnoise=.2, hp=0, cutoff=2200, res=.4, famt=2.4, ftau=.012,
                    nmod=0, atk=FAST, atau=.018, drive=1.5, seconds=.25, velp=.5),
    "COWBELL": dict(f1=540, f2=800, w1=1, w2=1, pamt1=0, pamt2=0, ptau=.02, fm=0, sync=0,
                    lv1=.7, lv2=.7, lnoise=0, hp=0, cutoff=850, res=.35, famt=2.8, ftau=.05,
                    nmod=0, atk=FAST, atau=.12, drive=1.4, seconds=.7, velp=.35),
    "SUB":     dict(f1=41, f2=82, w1=0, w2=0, pamt1=.7, pamt2=.7, ptau=.012, fm=0, sync=0,
                    lv1=1, lv2=0, lnoise=0, hp=0, cutoff=180, res=.1, famt=2.4, ftau=.06,
                    nmod=0, atk=FAST, atau=.42, drive=3.2, seconds=1.4, velp=.5, veldrive=.8),
    "BELL":    dict(f1=220, f2=607, w1=0, w2=0, pamt1=.05, pamt2=.05, ptau=.02, fm=1.1, sync=0,
                    lv1=.25, lv2=.9, lnoise=0, hp=0, cutoff=900, res=.2, famt=2.0, ftau=.35,
                    nmod=0, atk=FAST, atau=.5, drive=1.1, seconds=1.45, velp=.35),
    "SWEEP":   dict(f1=200, f2=300, w1=1, w2=1, pamt1=0, pamt2=0, ptau=.02, fm=0, sync=0,
                    lv1=0, lv2=0, lnoise=1.0, hp=0, cutoff=7000, res=.62, famt=-4.5, ftau=.22,
                    nmod=.2, atk=FAST, atau=.33, drive=1.5, seconds=1.1, velp=.35),
    "LASER":   dict(f1=900, f2=1350, w1=1, w2=0, pamt1=-2.8, pamt2=-2.8, ptau=.05, fm=.2, sync=0,
                    lv1=.7, lv2=.4, lnoise=0, hp=0, cutoff=2400, res=.4, famt=1.5, ftau=.08,
                    nmod=0, atk=FAST, atau=.11, drive=1.8, seconds=.6, velp=.5),
    "PING":    dict(f1=100, f2=150, w1=0, w2=0, pamt1=0, pamt2=0, ptau=.02, fm=0, sync=0,
                    lv1=.8, lv2=0, lnoise=.25, hp=0, cutoff=900, res=.93, famt=1.2, ftau=.02,
                    nmod=0, atk=FAST, atau=.004, drive=1.0, seconds=.9, velp=.35),
    "SHAKER":  dict(f1=100, f2=150, w1=0, w2=0, pamt1=0, pamt2=0, ptau=.02, fm=0, sync=0,
                    lv1=0, lv2=0, lnoise=1.0, hp=0, cutoff=2200, res=.3, famt=2.4, ftau=.04,
                    nmod=.3, atk=.012, atau=.05, drive=1.1, seconds=.35, velp=.35),
})


def P(base, **over):
    d = dict(BASE[base]); d.update(over); return d


def pad(name, cls, base, mac=None, mix=1.0, **over):
    return dict(name=name, cls=cls, base=base, params=P(base, **over), mac=dict(kp.DEFAULTS, **(mac or {})), mix=mix)


def M(**k):
    return {key.upper(): v for key, v in k.items()}


# ------------------------------------------------------------------ the four kits
# Sixteen pads each: A01..A14 one-shots, A15 the eight-step phrase, A16 the riser.
# Rows read: name, drum class, base, macros, mix gain for the groove, raw overrides.

KITS = {
 "TECHNO": dict(bpm=132, root="F1 (44 Hz)", blurb="Warehouse techno: a punchy kick with a short dive, tight hats, a rumble, metallic percussion.", pads=[
    pad("Kick", "kick", "KICK", M(length=.42), 1.0, f1=44, drive=2.4, cutoff=300, veldrive=.6, velp=.8),
    pad("Clap", "clap", "CLAP", None, .55),
    pad("Hat", "hatClosed", "HAT", M(length=.35), .35),
    pad("Open Hat", "hatOpen", "HAT", M(length=.85), .3),
    pad("Rim", "perc", "RIM", None, .4),
    pad("Rumble", "perc", "KICK", M(length=.8, tone=.3, noise=.7), .5, f1=44, pamt1=1.2, nmod=.8, lnoise=.08, res=.5, veldrive=.7, famt=4.0),
    pad("Low Tom", "tom", "FMTOM", M(tune=.3, clang=.4), .5),
    pad("High Tom", "tom", "FMTOM", M(tune=.6, clang=.4), .5),
    pad("Zap", "perc", "ZAP", M(length=.35, tune=.6), .45),
    pad("Metal", "perc", "METAL", None, .35),
    pad("Ride", "perc", "METAL", M(length=.95, noise=.7), .25, lnoise=.3, hp=0, cutoff=2500, famt=2.2),
    pad("Cowbell", "perc", "COWBELL", M(length=.4), .35),
    pad("Blip", "perc", "LASER", M(tune=.7, length=.3), .3, pamt1=1.6, pamt2=1.6),
    pad("Sub Hit", "perc", "SUB", M(length=.45), .7, f1=44),
 ], phrase=dict(pad=0, div=4, steps=[(0, 1), (12, .35), (0, .6), (7, .45), (0, 1), (12, .3), (3, .7), (19, .4)]),
    riser=dict(pad=1, start=4, end=48, seconds=2.4, curve=1.3),
    groove={0: "X...X...X...X...X...X...X...X.o.",
            1: "....X.......X.......X.......X...",
            2: "x.o.x.o.x.o.x.o.x.o.x.o.x.o.x.oo",
            3: "..x...x...x...x...x...x...x...x.",
            5: "..o...o...o...o...o...o...o...o.",
            9: ".......x.......o.....x.........x",
            11: "...o.......o.......o.......o...."}),

 "INDUSTRIAL": dict(bpm=100, root="C2 (65 Hz)", blurb="Industrial and EBM: driven square waves, long pitch dives, metal and steam.", pads=[
    pad("Kick", "kick", "KICK", M(dive=.7, length=.5), 1.0, w1=1, f1=52, drive=4.5, cutoff=520, res=.35),
    pad("Snare", "snare", "SNARE", M(noise=.75, tone=.65), .6, drive=3.5, w1=1),
    pad("Hat", "hatClosed", "HAT", M(clang=.85, length=.4), .3, drive=2.5),
    pad("Open Hat", "hatOpen", "HAT", M(clang=.85, length=.9), .28, drive=2.5),
    pad("Anvil", "perc", "METAL", M(tune=.3, length=.75), .35, drive=3.0),
    pad("Pipe", "perc", "FMTOM", M(clang=.85, tune=.55), .4, drive=3.0, w1=1),
    pad("Zap", "perc", "ZAP", M(dive=.7), .5, drive=3.5),
    pad("Low Zap", "perc", "ZAP", M(tune=.3, dive=.75, length=.65), .5, drive=3.5),
    pad("Hammer", "perc", "SNARE", M(clang=.7, length=.25, tone=.4), .5, drive=4.0, w1=1, w2=1, lv1=.9),
    pad("Clank", "perc", "METAL", M(tune=.45, clang=.9, length=.4), .35, drive=3.0),
    pad("Steam", "perc", "SWEEP", M(length=.6), .3),
    pad("Low Tom", "tom", "FMTOM", M(tune=.28, clang=.65), .5, w1=1, drive=3.0),
    pad("High Tom", "tom", "FMTOM", M(tune=.5, clang=.65), .5, w1=1, drive=3.0),
    pad("Grind", "perc", "ZAP", M(clang=1.0, noise=.8, tone=.7, length=.55), .3, res=.7),
 ], phrase=dict(pad=6, div=4, steps=[(0, 1), (-5, .55), (7, .8), (0, 0), (-12, 1), (5, .45), (0, .7), (12, .6)]),
    riser=dict(pad=6, start=8, end=320, seconds=2.4, curve=1.6),
    groove={0: "X..xX...X..xX...X..xX...X..xX.x.",
            1: "....X.......X.......X.......X..x",
            2: "x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.",
            4: "..o.......o.......o.......o.....",
            6: "......x.........x.....x.......x.",
            8: "...........o...............o....",
            10: "X...............................",
            7: "..............x...........x....."}),

 "ELECTRO": dict(bpm=124, root="A1 (55 Hz)", blurb="Electro and breaks: a long round boom, snappy snare and clap, cowbell, toms and lasers.", pads=[
    pad("Boom", "kick", "KICK", M(dive=.35, length=.85), 1.0, f1=50, drive=2.2, cutoff=220, veldrive=.7, velp=.8),
    pad("Snare", "snare", "SNARE", M(tone=.6, length=.45), .6),
    pad("Hat", "hatClosed", "HAT", M(length=.3, tone=.6), .3),
    pad("Open Hat", "hatOpen", "HAT", M(length=.8, tone=.6), .28),
    pad("Clap", "clap", "CLAP", M(length=.6), .55),
    pad("Cowbell", "perc", "COWBELL", None, .35),
    pad("Low Tom", "tom", "KICK", M(tune=.7, dive=.4, length=.6), .5, w1=0, cutoff=700),
    pad("Mid Tom", "tom", "KICK", M(tune=.82, dive=.4, length=.55), .5, cutoff=600, veldrive=.6, velp=.8),
    pad("High Tom", "tom", "KICK", M(tune=.95, dive=.4, length=.5), .5, cutoff=900),
    pad("Laser Up", "perc", "LASER", None, .35),
    pad("Laser Down", "perc", "ZAP", M(tune=.75, length=.4, clang=.3), .35),
    pad("Rim", "perc", "RIM", M(tune=.4), .4),
    pad("Conga", "perc", "FMTOM", M(tune=.7, clang=.3, length=.35), .45, w2=0),
    pad("Bleep", "perc", "METAL", M(tune=.6, clang=.95, length=.3), .3, hp=0, cutoff=3000),
 ], phrase=dict(pad=12, div=4, to2=True, steps=[(0, 1), (0, .4), (10, .75), (0, 0), (5, 1), (17, .5), (0, .65), (12, .8)]),
    riser=dict(pad=7, start=4, end=40, seconds=2.4, curve=1.4, glide=12),
    groove={0: "X.........X..x..X.........X.....",
            1: "....X.......X.......X.......X.o.",
            2: "x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.x.",
            3: "..............o.................",
            5: "..o.......o.......o.......o.x...",
            9: "...............................x",
            12: ".......o.o.............o..o.....",
            6: "........................x.x.x..."}),

 "AMBIENT": dict(bpm=84, root="D3 (147 Hz)", blurb="Ambient and downtempo: soft attacks, bells, plucks and breath, slow and roomy.", pads=[
    pad("Soft Kick", "kick", "KICK", M(dive=.35, tone=.3, length=.6), 1.0, f1=48, drive=1.1),
    pad("Brush", "snare", "SHAKER", M(length=.7, tone=.4), .45, atk=.03, hp=0, cutoff=1500, famt=3.0),
    pad("Tick", "hatClosed", "RIM", M(tone=.3, length=.4), .3, hp=0, cutoff=2000),
    pad("Shaker", "hatOpen", "SHAKER", M(length=.75), .3),
    pad("Low Bell", "perc", "BELL", M(tune=.3), .35),
    pad("Bell", "perc", "BELL", M(tune=.5), .35),
    pad("High Bell", "perc", "BELL", M(tune=.7, length=.4), .3),
    pad("Wood", "perc", "PING", M(tune=.6), .45, cutoff=1400, res=.9),
    pad("Drop", "perc", "LASER", M(tune=.55, tone=.3, length=.3), .35, w1=0, w2=0, pamt1=-1.5, pamt2=-1.5, ptau=.025),
    pad("Pulse", "perc", "SUB", M(tune=.6, length=.6), .6, veldrive=.9),
    pad("Glass", "perc", "METAL", M(tone=.35, length=.7, clang=.6), .25, w1=0, w2=0, hp=0, cutoff=4000),
    pad("Breath", "perc", "SWEEP", M(length=.8, tone=.35), .1, atk=SLOW, famt=-2.5),
    pad("Pluck", "perc", "PING", M(tune=.45, length=.7), .4),
    pad("Swell", "perc", "BELL", M(tune=.4, length=.8), .3, atk=SLOW, fm=.6),
 ], phrase=dict(pad=5, div=2, steps=[(0, 1), (7, .5), (12, .7), (0, 0), (5, .8), (3, .4), (10, .6), (0, 0)]),
    riser=dict(pad=12, start=3, end=160, seconds=2.4, curve=1.8),
    groove={0: "X.........x.....X...............",
            1: "........o...............o.......",
            3: "..o...o...o...o...o...o...o...o.",
            5: "X...............................",
            6: "..........o.....................",
            7: "......o.......o.........o...o...",
            12: "....o..........o....o........o..",
            11: "................X..............."}),
}


# ------------------------------------------------------------------ rendering

MODEL = int(os.environ.get("KINDRED_MODEL", "1"))
MIX = int(os.environ.get("KINDRED_MIX", "1"))


def model2(q, cls):
    """Model 2, after the owner heard round 1 as "a small radio speaker". Measured against the
    references, round 1 put 5.8 dB too much energy under 60 Hz and 5.5 dB too little in
    400 Hz..2.5 kHz, where small speakers play. Fixed at the source, not with an EQ:
    kicks settle near the reference's 60..70 Hz instead of 44..50; low-pass cutoffs sit an
    octave higher so the body stays after the snap; resonance is lower (less nasal honk); the
    mixer drives the ladder harder, as the reference's mixer does, so the waves pick up the
    harmonics a phone speaker can play; a kick gets an octave square under its triangle."""
    q = dict(q)
    # the body sounds only: hats, rides, shakers and resonant pings already matched the references,
    # and opening their filter moved their velocity sweep above where the noise changes
    body = not cls.startswith("hat") and q["res"] < .85 and q["cutoff"] < 1500
    if q["hp"] == 0 and body:
        q["cutoff"] = min(q["cutoff"] * 2.0, 12000.0)
    if body:
        q["res"] *= 0.7
    q["drive"] *= 1.4
    if cls == "kick" or q["f1"] < 56:
        r = max(1.0, 58.0 / q["f1"]); q["f1"] *= r; q["f2"] *= r
    if cls == "kick":
        # fitted to the reference kick's band balance (2.4 dB rms over 60 Hz..16 kHz): leave the
        # ladder open and let the pitch dive sweep the harmonics; the punch is the sweep, not a snap
        q["w2"] = 1; q["f2"] = q["f1"] * 2.0; q["lv2"] = max(q["lv2"], .6)
        q["cutoff"] = min(q["cutoff"] * 4.0, 12000.0); q["famt"] += 2.2; q["ftau"] = max(q["ftau"], .11)
    elif q["f1"] < 120 and q["lv1"] > .5 and q["w1"] == 0:
        # the kick-built toms, rumble and sub hits: the same idea, half as far
        q["w2"] = 1; q["f2"] = q["f1"] * 2.0; q["lv2"] = max(q["lv2"], .35)
        q["cutoff"] = min(q["cutoff"] * 2.0, 12000.0); q["famt"] += 1.0; q["ftau"] = max(q["ftau"], .08)
    return q


def params_for(p):
    q = kp.apply_macros(p["params"], p["mac"])
    return model2(q, p["cls"]) if MODEL >= 2 else q


def render_pad(kit, p, vel=1.0, take=0):
    q = params_for(p)
    burst = q.get("burst") or [(0, 1)]
    rng = np.random.default_rng(sk.seed_for(kit, p["name"], "burst", take))
    trig, t = [], 0.0
    for i, (dt, g) in enumerate(burst):
        jitter = 1.0 if take == 0 else 1 + rng.uniform(-.15, .15)
        t = dt * jitter
        trig.append((t, 0.0, vel * g, 0))
    raw = kp.render_raw(q, trig, q["seconds"], take, (kit, p["name"]))
    return kp.to_output(raw)


def leveled(x):
    return sk.fade_tail(sk.level_to(x, .1, .97), RATE)


def phrase_loop(kit):
    K = KITS[kit]; ph = K["phrase"]; p = K["pads"][ph["pad"]]; q = params_for(p)
    step = 60.0 / K["bpm"] / ph["div"]
    cyc_n = int(round(step * 8 * RATE)) * 4
    trig = []
    for c in range(3):
        for i, (s, v) in enumerate(ph["steps"]):
            if v > 0:
                trig.append((c * cyc_n / FS + i * step, float(s), float(v), 1 if ph.get("to2") else 0))
    at = np.array([int(round(t * FS)) for t, *_ in trig], dtype=np.int64)
    rs = (at % cyc_n == 0).astype(np.int64)
    noise = np.tile(kp._noise(cyc_n, sk.seed_for(kit, "phrase")), 3)
    raw = kp._run(3 * cyc_n, float(FS), at, np.array([s for _, s, _, _ in trig]),
                  np.array([v for *_, v, _ in trig]), np.array([o for *_, o in trig], dtype=np.int64), rs,
                  q["f1"], q["f2"], q["w1"], q["w2"], q["pamt1"], q["pamt2"], q["ptau"], q["fm"], q["sync"],
                  q["lv1"], q["lv2"], q["lnoise"], q["hp"], q["cutoff"], q["res"], q["famt"], q["ftau"],
                  q["nmod"], q["atk"], q["atau"], q["drive"], 0.0, .25, noise, q.get("velp", .35), q.get("veldrive", .3))
    y = kp.to_output(raw); L = cyc_n // 4
    wl = y[L:3 * L]
    return wl[L:], sk.seam_error(wl, L)


def riser(kit, take=0, vel=1.0):
    K = KITS[kit]; R = K["riser"]; p = K["pads"][R["pad"]]; q = params_for(p)
    q = dict(q); q["atau"] = min(q["atau"], .12); q["ftau"] = min(q["ftau"], .1)
    steps = K["phrase"]["steps"]
    rng = np.random.default_rng(sk.seed_for(kit, "riser", take))
    trig, t, i = [], 0.0, 0
    while t < R["seconds"] - .05:
        frac = t / R["seconds"]
        rate = R["start"] * (R["end"] / R["start"]) ** (frac ** R["curve"])
        s = steps[i % 8][0] * (0.5 if R["end"] > 100 else 0) + R.get("glide", 0) * frac
        v = vel * (.45 + .55 * frac) * (1 + (0 if take == 0 else rng.uniform(-.06, .06)))
        trig.append((t, float(s), min(v, 1.0), 0))
        t += 1.0 / rate; i += 1
    return kp.to_output(kp.render_raw(q, trig, R["seconds"] + .3, take, (kit, "riser")))


def groove(kit, bars=2):
    K = KITS[kit]; step = 60.0 / K["bpm"] / 4
    n = int((32 * step + 1.5) * RATE)
    mix = np.zeros(n)
    openhat = np.zeros(n)          # open hats on their own track so a closed hat can choke them
    counters = {}
    vel_of = {"X": 1.0, "x": .6, "o": .3}
    events = sorted((si, pi, vel_of[ch]) for pi, row in K["groove"].items()
                    for si, ch in enumerate(row) if ch in vel_of)
    for si, pi, v in events:
        p = K["pads"][pi]
        take = counters.get((pi, v), 0); counters[(pi, v)] = take + 1
        g = p["mix"]
        if MIX >= 2:   # option C: hats 2.5 dB down, the mid-range drums 2 dB up
            g *= {"hatClosed": .75, "hatOpen": .75, "kick": 1.0}.get(p["cls"], 1.26)
        x = leveled(render_pad(kit, p, v, take % 4)) * (.35 + .65 * v) * g
        a = int(si * step * RATE)
        x = x[:n - a]
        if p["cls"] == "hatClosed":
            f = min(int(.005 * RATE), n - a)
            openhat[a:a + f] *= np.linspace(1, 0, f)
            openhat[a + f:] = 0
        if p["cls"] == "hatOpen":
            openhat[a:a + len(x)] += x
        else:
            mix[a:a + len(x)] += x
    mix += openhat
    return mix[: int(32 * step * RATE + .6 * RATE)]


def kit_walk(kit):
    K = KITS[kit]
    return kp.strip([leveled(render_pad(kit, p)) * p["mix"] ** .5 for p in K["pads"]], gap=.12)


# ------------------------------------------------------------------ main

def main(out):
    os.makedirs(out, exist_ok=True)
    rep = {"note": "Python harness, not Kotlin. Nothing heard.", "kits": {}}
    render_pad("TECHNO", KITS["TECHNO"]["pads"][0])  # compile
    hard_by_slot = {}
    for kit, K in KITS.items():
        kr = {"bpm": K["bpm"], "pads": []}
        t0 = time.perf_counter(); nrender = 0
        for i, p in enumerate(K["pads"]):
            zones = {}
            for v in (.3, .6, 1.0):
                zones[v] = [render_pad(kit, p, v, k) for k in range(4)]; nrender += 4
            hard = zones[1.0][0]
            hard_by_slot.setdefault(i, {})[kit] = hard
            seps = [kp.separation_db(a, b) for z in zones.values() for ai, a in enumerate(z) for b in z[ai + 1:]]
            take_shape = [kp.shape_change_db(a, b) for z in zones.values() for ai, a in enumerate(z) for b in z[ai + 1:]]
            pr = {
                "slot": f"A{i + 1:02d}", "name": p["name"], "cls": p["cls"],
                "seconds": round(len(hard) / RATE, 2),
                "finite": bool(all(np.all(np.isfinite(x)) for z in zones.values() for x in z)),
                "peak_raw": round(float(max(np.max(np.abs(x)) for z in zones.values() for x in z)), 2),
                "soft_vs_hard_shape_db": round(kp.shape_change_db(zones[.3][0], hard), 2),
                "mid_vs_hard_shape_db": round(kp.shape_change_db(zones[.6][0], hard), 2),
                "centroid_soft_hard": [round(kp.centroid(zones[.3][0])), round(kp.centroid(hard))],
                "closest_takes_sep_db": round(max(seps), 1),
                "takes_shape_db_min_max": [round(min(take_shape), 2), round(max(take_shape), 2)],
            }
            kr["pads"].append(pr)
            strip = [leveled(zones[v][0]) * (.35 + .65 * v) for v in (.3, .6, 1.0)] + [leveled(x) for x in zones[1.0][1:]]
            sk.write_wav(f"{out}/{kit.lower()}_a{i + 1:02d}.wav", kp.strip(strip, .15) * .9)
        kr["render_ms_per_hit_avg"] = round((time.perf_counter() - t0) * 1000 / nrender, 1)
        loop, seam = phrase_loop(kit)
        kr["A15"] = {"seconds": round(len(loop) / RATE, 3), "seam_error": float(f"{seam:.2e}")}
        sk.write_wav(f"{out}/{kit.lower()}_a15.wav", kp.level(np.tile(loop, 4), .12))
        rz = riser(kit); rz2 = riser(kit, take=1); rzs = riser(kit, vel=.4)
        kr["A16"] = {"seconds": round(len(rz) / RATE, 2), "take_sep_db": round(kp.separation_db(rz, rz2), 1),
                     "soft_vs_hard_shape_db": round(kp.shape_change_db(rzs, rz), 2)}
        sk.write_wav(f"{out}/{kit.lower()}_a16.wav", kp.level(rz, .11))
        g = groove(kit)
        period = int(32 * 60.0 / K["bpm"] / 4 * RATE)   # two bars; the tail rings into the repeat
        gg = np.zeros(2 * period + len(g) - period)
        gg[:len(g)] += g
        gg[period:period + len(g)] += g
        sk.write_wav(f"{out}/{kit.lower()}_groove.wav", kp.level(gg, .13))
        sk.write_wav(f"{out}/{kit.lower()}_walk.wav", kp.level(kit_walk(kit), .11))
        rep["kits"][kit] = kr
    # distinct kits: for each pair, how many of A01..A14 differ by more than 2 dB of spectral shape
    names = list(KITS)
    pairs = {}
    for a in range(len(names)):
        for b in range(a + 1, len(names)):
            d = [kp.shape_change_db(hard_by_slot[i][names[a]], hard_by_slot[i][names[b]]) for i in range(14)]
            pairs[f"{names[a]}-{names[b]}"] = {"slots_over_2db": int(sum(x > 2 for x in d)), "least_db": round(min(d), 2)}
    rep["kit_pairs"] = pairs
    with open(f"{out}/report.json", "w") as fh:
        json.dump(rep, fh, indent=1)
    for kit, kr in rep["kits"].items():
        print(f"\n{kit} {kr['bpm']} BPM  {kr['render_ms_per_hit_avg']} ms/hit  A15 {kr['A15']}  A16 {kr['A16']}")
        for pr in kr["pads"]:
            print(f"  {pr['slot']} {pr['name']:11s} {pr['cls']:9s} {pr['seconds']:4.2f}s peak {pr['peak_raw']:5.2f} "
                  f"soft/hard {pr['soft_vs_hard_shape_db']:5.2f} dB  cent {pr['centroid_soft_hard']}  "
                  f"takes sep {pr['closest_takes_sep_db']:5.1f} dB shape {pr['takes_shape_db_min_max']}  finite {pr['finite']}")
    print(json.dumps(pairs, indent=1))


if __name__ == "__main__":
    main(sys.argv[1])
