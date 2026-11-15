"""HICCUP prototype: glitch drums. Python harness, not Kotlin.

The idea in one line: a drum hit is written into a short memory, and a seeded fault script
re-reads that memory (snag -> ratchet -> buzz), each re-read coarser than the last.

Three units, in order:
  1. source(voice, macros, vel, take)  a clean drum hit at the internal 4x rate. TOY makes the
     chip cheaper: envelopes step at a frame rate, the wave squares up, the noise clock drops.
  2. script(voice, macros, vel, take)  the fault script: a list of events drawn ONCE per
     (voice, take). Macros only scale the drawn values, so a knob turn never reshuffles the
     pattern. FAULT admits events by threshold: the events at FAULT a are a subset of those at b > a.
  3. reread(dry, events, macros)       builds one read-position map over the memory and reads it.
     CRUSH is loss per re-read: generation 0 is nearly clean, later generations hold samples
     longer and use fewer levels.
"""
from __future__ import annotations

import json
import math
import os
import sys
import time

import numpy as np
from scipy import signal

LUTHIER = os.path.expanduser(
    "~/.claude/skills/synced/b174018a-c416-4d23-bb42-7cc25605f272_fdde7803-bc69-4eeb-b044-a4db4286539b/luthier/scripts")
sys.path.insert(0, LUTHIER)
import snipkit as sk  # noqa: E402
import probes as pr   # noqa: E402

RATE = sk.INTERNAL
VOICES = ["KICK", "SNARE", "HAT", "CLAP", "BLIP", "SQUEAK"]
MACROS = {"TUNE": 0.5, "DECAY": 0.5, "TOY": 0.35, "FAULT": 0.45, "CHOP": 0.5, "CRUSH": 0.35}
N_SLOTS = 5
MAX_SECONDS = 1.4          # stay under the classifier's 1.5 s LOOP line
EDGE_MS = 0.35             # raised-cosine ramp at every cut (a hard gate clicks; this still ticks)
ROOT_HZ = {"KICK": 49.0, "SNARE": 185.0, "HAT": 8000.0, "CLAP": 1100.0, "BLIP": 880.0, "SQUEAK": 1250.0}
PENTA = np.array([0, 3, 5, 7, 10])


def clamp01(x):
    return min(1.0, max(0.0, x))


# ------------------------------------------------------------------ 1. source

def stepped(t: np.ndarray, toy: float) -> np.ndarray:
    """TOY quantity 1: envelopes are read at a frame clock. 0 = continuous, 1 = 60 frames/s."""
    step = toy * (1.0 / 60.0)
    if step < 1e-5:
        return t
    return np.floor(t / step) * step


def chip_wave(phase: np.ndarray, toy: float) -> np.ndarray:
    """TOY quantity 2: a sine pushed toward a square. tanh keeps the harmonic rolloff steep."""
    k = sk.exp_map(toy, 0.05, 9.0)
    return np.tanh(k * np.sin(phase)) / math.tanh(k)


def clocked_noise(n: int, toy: float, seed: int) -> np.ndarray:
    """TOY quantity 3: noise from a clock. Fast clock = white, slow clock = chip hiss."""
    clock = sk.exp_map(toy, 60000.0, 3500.0)
    count = int(n * clock / RATE) + 2
    vals = sk.Noise(seed).array(count)
    idx = np.minimum((np.arange(n) * clock / RATE).astype(np.int64), count - 1)
    return vals[idx]


def bandpass(x, lo, hi):
    sos = signal.butter(2, [lo / (RATE / 2), min(0.99, hi / (RATE / 2))], btype="band", output="sos")
    return signal.sosfilt(sos, x)


def highpass(x, hz):
    sos = signal.butter(2, hz / (RATE / 2), btype="high", output="sos")
    return signal.sosfilt(sos, x)


def source(voice: str, m: dict, vel: float, take: int) -> np.ndarray:
    tune = 2.0 ** ((m["TUNE"] - 0.5) * 2.0)            # one octave either side
    toy = m["TOY"]
    hard = sk.lin(vel, 0.35, 1.0)                        # velocity = contact hardness, not level
    seed = sk.seed_for("HICCUP", voice, "strike", take)
    jit = sk.Noise(seed)
    wob = lambda amt: 1.0 + amt * jit.next()             # per-take strike variation (round robin)

    if voice == "KICK":
        tau = sk.exp_map(m["DECAY"], 0.06, 0.42)
        n = int(min(MAX_SECONDS, 6.5 * tau) * RATE)
        t = np.arange(n) / RATE
        ts = stepped(t, toy)
        f = ROOT_HZ[voice] * tune * (1.0 + 7.0 * hard * wob(.06) * np.exp(-ts / (0.021 * wob(.08))))
        phase = 2 * np.pi * np.cumsum(f) / RATE
        body = chip_wave(phase, toy * 0.8) * np.exp(-ts / tau)
        click = bandpass(clocked_noise(n, toy, seed + 1), 1500, 9000) * np.exp(-t / 0.0025) * 0.5 * hard
        return body + click
    if voice == "SNARE":
        tau = sk.exp_map(m["DECAY"], 0.05, 0.26)
        n = int(min(MAX_SECONDS, 6.5 * tau) * RATE)
        t = np.arange(n) / RATE
        ts = stepped(t, toy)
        f = ROOT_HZ[voice] * tune * (1.0 + 0.6 * hard * np.exp(-ts / 0.012))
        ph = 2 * np.pi * np.cumsum(f) / RATE
        tone = (chip_wave(ph, toy) + 0.5 * chip_wave(ph * 1.78 * wob(.01), toy)) * np.exp(-ts / (tau * 0.45))
        wires = bandpass(clocked_noise(n, toy, seed + 1), 1400 * wob(.05), 9500) * np.exp(-ts / tau)
        return 0.55 * tone + (0.6 + 0.6 * hard) * wires
    if voice == "HAT":
        tau = sk.exp_map(m["DECAY"], 0.012, 0.30)       # closed to open on one knob
        n = int(min(MAX_SECONDS, 7.0 * tau + 0.01) * RATE)
        t = np.arange(n) / RATE
        ts = stepped(t, toy)
        base = 320.0 * tune * 2
        ring = sum(chip_wave(2 * np.pi * base * r * wob(.004) * t, 0.9)
                   for r in (1.0, 1.342, 1.2312, 1.6532, 1.9523, 2.1523))
        metal = highpass(ring / 6.0, 6000 * (0.8 + 0.4 * hard))
        hiss = highpass(clocked_noise(n, toy, seed + 1), 7000)
        return (0.7 * metal + 0.5 * hiss) * np.exp(-ts / tau)
    if voice == "CLAP":
        tau = sk.exp_map(m["DECAY"], 0.05, 0.28)
        n = int(min(MAX_SECONDS, 6.0 * tau + 0.05) * RATE)
        t = np.arange(n) / RATE
        env = np.zeros(n)
        for k in range(3):                               # three hands, then the room
            t0 = k * 0.011 * wob(.12)
            env += (t >= t0) * np.exp(-np.maximum(t - t0, 0) / 0.006) * (0.7 + 0.1 * k)
        t0 = 0.034
        env += (t >= t0) * np.exp(-np.maximum(stepped(t, toy) - t0, 0) / tau)
        return bandpass(clocked_noise(n, toy, seed + 1), 800 * tune * (0.8 + 0.4 * hard), 3600 * tune) * env
    if voice == "BLIP":
        tau = sk.exp_map(m["DECAY"], 0.035, 0.30)
        n = int(min(MAX_SECONDS, 6.0 * tau) * RATE)
        t = np.arange(n) / RATE
        ts = stepped(t, max(toy, 0.15))                  # a blip is always a little stepped
        semis = -(12.0 + 12.0 * hard) * (1.0 - np.exp(-ts / (tau * 0.5)))
        f = ROOT_HZ[voice] * tune * 2.0 ** (np.round(semis) / 12.0 if toy > 0.02 else semis / 12.0)
        ph = 2 * np.pi * np.cumsum(f) / RATE
        return chip_wave(ph, 0.3 + 0.7 * toy) * np.exp(-ts / tau)
    if voice == "SQUEAK":
        dur = sk.exp_map(m["DECAY"], 0.07, 0.32)
        n = int((dur + 0.03) * RATE)
        t = np.arange(n) / RATE
        ts = stepped(t, toy)
        x = np.clip(ts / dur, 0, 1)
        f = ROOT_HZ[voice] * tune * (0.75 + (0.9 + 0.4 * hard) * np.sin(np.pi * x) ** 0.7) \
            * (1.0 + 0.03 * np.sin(2 * np.pi * 37 * wob(.1) * t))
        ph = 2 * np.pi * np.cumsum(f) / RATE
        env = np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 0.6
        reed = chip_wave(ph, 0.25 + 0.6 * toy) + 0.35 * chip_wave(2 * ph, 0.2)
        return reed * env
    raise ValueError(voice)


# ------------------------------------------------------------------ 2. fault script

def draws(voice: str, take: int) -> np.ndarray:
    """N_SLOTS rows of (exist, pos, len, shrink, rev, dive), fixed per voice. A take nudges
    them a bounded amount: the same hit stumbling slightly differently, not a new pattern."""
    base = np.abs(sk.Noise(sk.seed_for("HICCUP", voice, "script")).array(N_SLOTS * 6)).reshape(N_SLOTS, 6)
    if take:
        nudge = sk.Noise(sk.seed_for("HICCUP", voice, "script", take)).array(N_SLOTS * 6).reshape(N_SLOTS, 6)
        base = np.clip(base + 0.12 * nudge, 0.0, 0.9999)
        base[:, 0] = np.abs(sk.Noise(sk.seed_for("HICCUP", voice, "script")).array(N_SLOTS * 6)).reshape(N_SLOTS, 6)[:, 0]
    return base


def snap_to_pitch(length_s: float, root_hz: float) -> float:
    """Below about 12 ms a repeat is heard as a pitch, so make it one: the repeat period snaps
    to a minor-pentatonic note over the voice's root."""
    if length_s >= 0.012:
        return length_s
    semis = 12.0 * math.log2((1.0 / length_s) / root_hz)
    octave = math.floor(semis / 12.0)
    within = semis - 12 * octave
    nearest = min(np.append(PENTA, 12), key=lambda p: abs(p - within))
    return 1.0 / (root_hz * 2.0 ** ((12 * octave + nearest) / 12.0))


def script(voice: str, m: dict, vel: float, take: int, dry_seconds: float) -> list[dict]:
    d = draws(voice, take)
    fe = m["FAULT"] * sk.lin(vel, 0.55, 1.0)             # soft hits stumble less
    order = np.argsort(d[:, 0])
    events = []
    for rank, i in enumerate(order):
        threshold = (rank + d[i, 0]) / N_SLOTS           # spread over 0..1, one slot per fifth
        if fe <= threshold:
            continue
        u_pos, u_len, u_shrink, u_rev, u_dive = d[i, 1:]
        pos = 0.0 if u_pos < 0.34 else (u_pos - 0.34) / 0.66 * 0.55 * dry_seconds
        base = sk.exp_map(m["CHOP"], 0.110, 0.0016)
        tune_hz = ROOT_HZ[voice] * 2.0 ** ((m["TUNE"] - 0.5) * 2.0)
        while tune_hz > 220.0:
            tune_hz /= 2.0
        length = snap_to_pitch(base * 2.0 ** ((u_len - 0.5) * 1.4), tune_hz)
        budget = sk.lin(fe, 0.05, 0.26) * (0.6 + 0.8 * u_len)
        events.append({
            "slot": int(i), "pos": pos, "length": length,
            "repeats": int(min(48, max(2, round(budget / length)))),
            "shrink": 1.0 - (0.22 * (u_shrink - 0.5) * 2 * fe if u_shrink > 0.5 else 0.0),
            "reverse": bool(u_rev < 0.35 * fe), "dive": bool(u_dive < 0.4 * fe),
            "budget": budget,
        })
    events.sort(key=lambda e: e["pos"])
    kept, cursor = [], -1.0
    for e in events:                                     # an event cannot start inside the previous one's memory
        if e["pos"] >= cursor and e["pos"] + e["length"] < dry_seconds:
            kept.append(e)
            cursor = e["pos"] + e["length"]
    return kept


# ------------------------------------------------------------------ 3. re-read

def loss(crush: float, generation: int) -> float:
    """CRUSH is loss per re-read. Generation 0 (the hit itself) keeps a quarter of it."""
    depth = 1.0 - math.exp(-generation / 2.5)
    return crush * (0.25 + 0.75 * depth)


def segment(dry, src_start_s, length_s, gen, m, reverse=False, speed0=1.0, speed1=1.0, gain=1.0, edges=(True, True)):
    n = max(8, int(length_s * RATE))
    c = loss(m["CRUSH"], gen)
    k = np.arange(n, dtype=np.float64)
    if c > 1e-6:                                         # sample-and-hold in output time
        hold = RATE / sk.exp_map(c, 44100.0, 1100.0)
        k = np.floor(k / hold) * hold
    speed = speed0 + (speed1 - speed0) * (k / n)
    travel = np.cumsum(np.full(n, 1.0)) - 1.0 if speed0 == speed1 == 1.0 else None
    posn = k if travel is not None else (speed0 * k + (speed1 - speed0) * k * k / (2 * n))
    del speed
    idx = src_start_s * RATE + (n - 1 - posn if reverse else posn)
    y = np.interp(idx, np.arange(len(dry)), dry, left=0.0, right=0.0) * gain
    if c > 1e-6:                                         # fewer levels, mid-tread (no DC, tails gate shut)
        levels = sk.exp_map(c, 4096.0, 5.0)
        y = np.round(y * levels) / levels
    e = min(n // 2, int(EDGE_MS * 1e-3 * RATE))
    ramp = 0.5 - 0.5 * np.cos(np.pi * np.arange(e) / e)
    if edges[0]:
        y[:e] *= ramp
    if edges[1]:
        y[-e:] *= ramp[::-1]
    return y


def reread(dry: np.ndarray, events: list[dict], m: dict) -> np.ndarray:
    peak = max(1e-9, float(np.max(np.abs(dry))))
    dry = dry / peak                                     # the quantizer needs a known full scale
    total = len(dry) / RATE
    out, cursor = [], 0.0
    for e in events:
        if e["pos"] > cursor:                            # the hit plays through to the snag
            out.append(segment(dry, cursor, e["pos"] - cursor, 0, m, edges=(cursor > 0, True)))
        length = e["length"]
        out.append(segment(dry, e["pos"], length, 0, m, edges=(e["pos"] > 0, True)))   # first pass, as played
        span = e["budget"] * 1.5
        elapsed = 0.0
        for k in range(1, e["repeats"] + 1):
            ell = max(0.0006, length * e["shrink"] ** k)
            dive0 = 1.0 - 0.55 * (elapsed / e["budget"]) if e["dive"] else 1.0
            dive1 = 1.0 - 0.55 * ((elapsed + ell) / e["budget"]) if e["dive"] else 1.0
            out.append(segment(dry, e["pos"], ell, k, m, reverse=e["reverse"] and k % 2 == 1,
                               speed0=max(0.3, dive0), speed1=max(0.3, dive1),
                               gain=math.exp(-elapsed / span)))
            elapsed += ell
        cursor = e["pos"] + length
    if cursor < total:
        out.append(segment(dry, cursor, total - cursor, 0, m, edges=(cursor > 0, False)))
    y = np.concatenate(out)
    return y[: int(MAX_SECONDS * RATE)]


def render(voice: str, macros: dict | None = None, vel: float = 1.0, take: int = 0, info: dict | None = None):
    m = {**MACROS, **(macros or {})}
    dry = source(voice, m, vel, take)
    ev = script(voice, m, vel, take, len(dry) / RATE)
    if info is not None:
        info["events"] = ev
    return reread(dry, ev, m)


def finish(raw):
    return sk.finish(np.concatenate([raw, np.zeros(int(0.02 * RATE))]))


# ------------------------------------------------------------------ probes and audition

def beat(bpm: float, bars: int, pattern: dict, kit: dict) -> np.ndarray:
    """Lay rendered pads on a 16th grid so the kit can be judged the way it lands."""
    step = 60.0 / bpm / 4.0
    n = int((bars * 16 * step + 1.5) * sk.RATE)
    mix = np.zeros(n)
    hits = {}
    for name, steps in pattern.items():
        voice, macros, gain = kit[name]
        choke_until = None
        for bar in range(bars):
            for s_i, (s, vel) in enumerate(steps):
                take = (bar * len(steps) + s_i) % 4          # round robin across four takes
                key = (name, round(vel, 2), take)
                if key not in hits:
                    hits[key] = finish(render(voice, macros, vel, take))
                h = hits[key] * gain * (0.45 + 0.55 * vel)
                at = int((bar * 16 + s) * step * sk.RATE)
                mix[at: at + len(h)] += h[: n - at]
    return mix / max(1e-9, np.max(np.abs(mix))) * 0.9


def main(out_dir: str) -> int:
    os.makedirs(out_dir, exist_ok=True)
    R = {"renders": [], "macro_activity_dB": {}, "checks": {}}
    gap = np.zeros(int(0.35 * sk.RATE))
    w = lambda name, x: sk.write_wav(os.path.join(out_dir, name + ".wav"), x)

    # every voice at defaults
    t0 = time.time()
    for v in VOICES:
        info = {}
        raw = render(v, info=info)
        out = finish(raw)
        rep = pr.report(out, sk.RATE, v)
        rep["events"] = len(info["events"])
        rep["alias_proxy_raw"] = round(pr.alias_proxy(raw, RATE), 5)
        R["renders"].append(rep)
    R["checks"]["python_ms_per_voice"] = round((time.time() - t0) / len(VOICES) * 1000, 1)

    # FAULT 0 is the dry hit, bit for bit (with CRUSH 0 the fault stage is the identity)
    ident = {}
    for v in VOICES:
        m = {**MACROS, "FAULT": 0.0, "CRUSH": 0.0}
        dry = source(v, m, 1.0, 0)
        y = render(v, {"FAULT": 0.0, "CRUSH": 0.0})
        nn = min(len(dry), len(y))
        ident[v] = float(np.max(np.abs(y[:nn] * np.max(np.abs(dry)) - dry[:nn])))
    R["checks"]["fault0_crush0_max_abs_error_vs_dry"] = ident

    # FAULT only ever adds events: the slot set at a is a subset of the set at b > a
    subset_ok = True
    counts = {}
    for v in VOICES:
        prev = set()
        counts[v] = []
        for f in np.linspace(0, 1, 21):
            m = {**MACROS, "FAULT": float(f)}
            ev = script(v, m, 1.0, 0, 10.0)               # long dry so the overlap rule cannot hide a slot
            slots = {e["slot"] for e in ev}
            subset_ok &= prev.issubset(slots)
            prev = slots
            counts[v].append(len(slots))
    R["checks"]["fault_monotone_subset"] = bool(subset_ok)
    R["checks"]["events_at_fault_0_.25_.5_.75_1"] = {v: [c[0], c[5], c[10], c[15], c[20]] for v, c in counts.items()}

    # determinism, length, every macro changes the sound, pairs interact
    a, b = render("SNARE"), render("SNARE")
    R["checks"]["deterministic"] = bool(np.array_equal(a, b))
    R["checks"]["max_seconds"] = max(len(finish(render(v, {"FAULT": 1.0, "DECAY": 1.0, "CHOP": 0.0}))) / sk.RATE for v in VOICES)
    for v in ("KICK", "SNARE", "HAT"):
        R["macro_activity_dB"][v] = {}
        for macro in MACROS:
            lo, hi = finish(render(v, {macro: 0.0})), finish(render(v, {macro: 1.0}))
            R["macro_activity_dB"][v][macro] = round(pr.macro_activity(lo, hi), 2)
    # CRUSH x FAULT: CRUSH should matter more when there are re-reads to lose
    def crush_effect(fault):
        return pr.macro_activity(finish(render("SNARE", {"FAULT": fault, "CRUSH": 0.0})),
                                 finish(render("SNARE", {"FAULT": fault, "CRUSH": 0.8})))
    R["checks"]["crush_effect_dB_at_fault_0_and_1"] = [round(crush_effect(0.0), 2), round(crush_effect(1.0), 2)]

    # round robin: four takes differ from each other but stay the same instrument
    takes = [finish(render("SNARE", take=t)) for t in range(4)]
    def diff(x, y):
        n = min(len(x), len(y))
        return float(np.sqrt(np.mean((x[:n] - y[:n]) ** 2)) / max(1e-9, np.sqrt(np.mean(x[:n] ** 2))))
    R["checks"]["rr_take_pair_rms_difference"] = [round(diff(takes[0], t), 3) for t in takes[1:]]
    R["checks"]["rr_take_centroid_hz"] = [round(pr.spectral_centroid(t), 0) for t in takes]
    R["checks"]["rr_take_seconds"] = [round(len(t) / sk.RATE, 3) for t in takes]

    # velocity changes the strike, not only the level (all renders are leveled the same)
    vels = [finish(render("KICK", vel=v)) for v in (0.2, 0.5, 0.8, 1.0)]
    R["checks"]["velocity_centroid_hz_.2_.5_.8_1"] = [round(pr.spectral_centroid(v), 0) for v in vels]
    R["checks"]["velocity_events_.2_.5_.8_1"] = []
    for v in (0.2, 0.5, 0.8, 1.0):
        info = {}
        render("KICK", vel=v, info=info)
        R["checks"]["velocity_events_.2_.5_.8_1"].append(len(info["events"]))

    # extremes stay finite and bounded
    worst = 0.0
    finite = True
    rng = sk.Noise(sk.seed_for("corners"))
    for v in VOICES:
        for _ in range(12):
            m = {k: clamp01(abs(rng.next())) if abs(rng.next()) > 0.4 else float(rng.next() > 0) for k in MACROS}
            raw = render(v, m, vel=abs(rng.next()), take=int(abs(rng.next()) * 4))
            finite &= bool(np.all(np.isfinite(raw)))
            worst = max(worst, float(np.max(np.abs(raw))))
            out = finish(raw)
            finite &= bool(np.all(np.isfinite(out))) and abs(float(np.mean(out))) < 0.01
    R["checks"]["corners_finite_and_dc_under_0.01"] = finite
    R["checks"]["corners_raw_peak"] = round(worst, 3)

    # ---------------- audition clips (few, on purpose)
    seq = lambda xs: np.concatenate([np.concatenate([x, gap]) for x in xs])
    w("01_voices_default", seq([finish(render(v)) for v in VOICES]))
    w("02_kick_FAULT_0_33_66_100", seq([finish(render("KICK", {"FAULT": f})) for f in (0, .33, .66, 1)]))
    w("03_snare_CHOP_0_33_66_100", seq([finish(render("SNARE", {"FAULT": .7, "CHOP": c})) for c in (0, .33, .66, 1)]))
    w("04_hat_CRUSH_0_33_66_100", seq([finish(render("HAT", {"DECAY": .7, "FAULT": .6, "CRUSH": c})) for c in (0, .33, .66, 1)]))
    w("05_TOY_0_50_100_kick_snare_blip", seq([finish(render(v, {"TOY": t})) for v in ("KICK", "SNARE", "BLIP") for t in (0, .5, 1)]))
    w("06_snare_round_robin_4_takes_twice", seq(takes + takes))
    w("07_kick_velocity_20_50_80_100", seq([v * g for v, g in zip(vels, (.4, .6, .8, 1))]))

    hop_kit = {
        "kick": ("KICK", {"TUNE": .42, "DECAY": .55, "FAULT": .35, "CHOP": .35, "CRUSH": .25, "TOY": .2}, 1.0),
        "snare": ("SNARE", {"TUNE": .5, "DECAY": .5, "FAULT": .6, "CHOP": .55, "CRUSH": .4, "TOY": .3}, .8),
        "hat": ("HAT", {"DECAY": .15, "FAULT": .3, "CHOP": .8, "CRUSH": .3, "TOY": .4}, .35),
        "ohat": ("HAT", {"DECAY": .7, "FAULT": .7, "CHOP": .7, "CRUSH": .5, "TOY": .4}, .3),
        "blip": ("BLIP", {"TUNE": .6, "DECAY": .4, "FAULT": .8, "CHOP": .75, "CRUSH": .5, "TOY": .8}, .4),
        "squeak": ("SQUEAK", {"DECAY": .4, "FAULT": .5, "CHOP": .6, "CRUSH": .3, "TOY": .5}, .35),
    }
    hop = {"kick": [(0, 1), (7, .7), (10, 1)], "snare": [(4, 1), (12, 1), (15, .5)],
           "hat": [(s, .9 if s % 4 == 2 else .5) for s in range(0, 16, 2)], "ohat": [(14, .8)],
           "blip": [(6, .8)], "squeak": [(11, .7)]}
    w("08_beat_glitch_hop_98bpm", beat(98, 4, hop, hop_kit))
    pop_kit = {
        "kick": ("KICK", {"TUNE": .6, "DECAY": .7, "FAULT": .7, "CHOP": .85, "CRUSH": .7, "TOY": .9}, 1.0),
        "snare": ("SNARE", {"TUNE": .7, "DECAY": .35, "FAULT": .85, "CHOP": .8, "CRUSH": .6, "TOY": .8}, .8),
        "clap": ("CLAP", {"TUNE": .6, "DECAY": .4, "FAULT": .5, "CHOP": .9, "CRUSH": .5, "TOY": .7}, .7),
        "hat": ("HAT", {"TUNE": .7, "DECAY": .1, "FAULT": .5, "CHOP": .95, "CRUSH": .6, "TOY": .9}, .3),
        "blip": ("BLIP", {"TUNE": .75, "DECAY": .3, "FAULT": .9, "CHOP": .9, "CRUSH": .4, "TOY": 1.0}, .4),
    }
    pop = {"kick": [(0, 1), (3, .8), (6, 1), (10, 1), (13, .8)], "snare": [(4, 1), (12, 1)], "clap": [(4, .8), (12, .9), (14, .6)],
           "hat": [(s, .9 if s % 2 == 0 else .5) for s in range(16)], "blip": [(7, .8), (15, .9)]}
    w("09_beat_hyperpop_160bpm", beat(160, 4, pop, pop_kit))

    pr.spectrogram_png(finish(render("SNARE", {"FAULT": .8, "CHOP": .6})), sk.RATE,
                       os.path.join(out_dir, "snare_fault.png"), "SNARE FAULT .8 CHOP .6")
    with open(os.path.join(out_dir, "report.json"), "w") as f:
        json.dump(R, f, indent=2)
    print(json.dumps(R, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "out"))
