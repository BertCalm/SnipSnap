r"""KINDRED round 0 (working name): a one-voice, eight-step analog-style percussion synth.

Python harness, not Kotlin. Nothing here has been heard by anyone.

The characteristic gesture: a strike -> the pitch dives from a zap into a body -> the ladder
closes over the harmonics -> the next step re-strikes the SAME voice at its own height and force,
cutting the last tail, so eight strikes of one patch make a pattern.

Signal flow (one continuous voice, 4x internal rate):

  triggers (time, pitch offset, velocity)
     |                 pitch EG (decay) --+-- AMT1 -->  VCO1 tri/square ----+---------------+
     |                                     \                                 | FM 1->2, sync |
     |                                      +- AMT2 -->  VCO2 tri/square <---+               |
     |                                                                                       v
     |                                        noise ------------------------------------> MIX
     |                                          \ noise -> cutoff                             |
     +---> filter EG (decay) x velocity -----> 4-pole ladder, LP or HP (tanh stages) <--------+
     +---> VCA EG (fast/slow attack, decay) x velocity ------------------> VCA --> house finish

Velocity scales the VCA and the filter envelope (the reference manual: "loudness and envelope
strength") and, a little, the pitch dive. So a soft step is darker and less zappy, not only
quieter. The oscillators run free between strikes, so every strike starts at a new phase: that
is the instrument's own round robin, and the take seed also moves noise and a few cents of drift.
"""
from __future__ import annotations

import json
import math
import os
import sys
import time

import numpy as np
from numba import njit
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", ".claude", "skills", "luthier", "scripts"))
import snipkit as sk  # noqa: E402

RATE = sk.RATE
FS = sk.INTERNAL  # 176400


# ------------------------------------------------------------------ the voice (compiled loop)

@njit(cache=True)
def _run(n, fs, trig_at, trig_semi, trig_vel, trig_to2_only, trig_reset,
         f1, f2, w1, w2, pamt1, pamt2, ptau, fm, sync, lv1, lv2, lnoise,
         hp, cutoff, res, famt, ftau, nmod, atk, atau, drive,
         ph1, ph2, noise, vel_pitch, veldrive):
    out = np.zeros(n)
    y1 = y2 = y3 = y4 = 0.0
    penv = 0.0
    fenv = 0.0
    aenv = 0.0
    a_target_phase = 0  # 1 while attacking
    a_start = 0.0
    a_t = 0.0
    vel = 0.0
    semi = 0.0
    to2 = 0
    k = 4.0 * res
    pa = math.exp(-1.0 / (ptau * fs))
    fa = math.exp(-1.0 / (ftau * fs))
    aa = math.exp(-1.0 / (atau * fs))
    atk_n = max(1.0, atk * fs)
    ti = 0
    nt = len(trig_at)
    prev1 = 0.0
    for i in range(n):
        while ti < nt and trig_at[ti] <= i:
            # DFAM-style retrigger: decay EGs jump to peak, the VCA re-attacks from where it is
            vel = trig_vel[ti]
            semi = trig_semi[ti]
            to2 = trig_to2_only[ti]
            if trig_reset[ti] == 1:
                ph1 = 0.0
                ph2 = 0.25
            penv = 1.0
            fenv = 1.0
            a_target_phase = 1
            a_start = aenv
            a_t = 0.0
            ti += 1
        # envelopes
        penv *= pa
        fenv *= fa
        if a_target_phase == 1:
            a_t += 1.0
            aenv = a_start + (1.0 - a_start) * min(1.0, a_t / atk_n)
            if a_t >= atk_n:
                a_target_phase = 0
        else:
            aenv *= aa
        pv = (1.0 - vel_pitch) + vel_pitch * vel
        s1 = 0.0 if to2 == 1 else semi
        hz1 = f1 * 2.0 ** (s1 / 12.0 + pamt1 * penv * pv)
        hz2 = f2 * 2.0 ** (semi / 12.0 + pamt2 * penv * pv)
        # VCO1
        dt1 = min(hz1 / fs, 0.45)
        ph1 += dt1
        wrapped = False
        if ph1 >= 1.0:
            ph1 -= 1.0
            wrapped = True
        if w1 == 1:
            o1 = 1.0 if ph1 < 0.5 else -1.0
            # polyBLEP at the two edges
            t = ph1
            if t < dt1:
                x = t / dt1
                o1 += x + x - x * x - 1.0
            elif t > 1.0 - dt1:
                x = (t - 1.0) / dt1
                o1 -= -(x * x + x + x + 1.0)
            t = ph1 - 0.5
            if t < 0:
                t += 1.0
            if t < dt1:
                x = t / dt1
                o1 -= x + x - x * x - 1.0
            elif t > 1.0 - dt1:
                x = (t - 1.0) / dt1
                o1 += -(x * x + x + x + 1.0)
        else:
            o1 = 4.0 * abs(ph1 - 0.5) - 1.0
        # VCO2, FM from VCO1 (linear, through zero allowed), optional hard sync to VCO1
        dt2 = hz2 * (1.0 + fm * o1) / fs
        if dt2 > 0.45:
            dt2 = 0.45
        if dt2 < -0.45:
            dt2 = -0.45
        ph2 += dt2
        if sync == 1 and wrapped:
            ph2 = ph1 * (dt2 / dt1) if dt1 > 0 else 0.0
        ph2 -= math.floor(ph2)
        if w2 == 1:
            o2 = 1.0 if ph2 < 0.5 else -1.0
        else:
            o2 = 4.0 * abs(ph2 - 0.5) - 1.0
        nz = noise[i]
        mix = lv1 * o1 + lv2 * o2 + lnoise * nz
        # ladder: cutoff from knob, filter EG x velocity, noise modulation
        fv = 0.25 + 0.75 * vel
        fc = cutoff * 2.0 ** (famt * fenv * fv + nmod * nz)
        if fc > 0.42 * fs:
            fc = 0.42 * fs
        if fc < 15.0:
            fc = 15.0
        g = 1.0 - math.exp(-2.0 * math.pi * fc / fs)
        dr = drive * (1.0 - veldrive + veldrive * vel)  # a harder strike drives the ladder harder
        u = math.tanh(dr * mix - k * y4)
        y1 += g * (u - math.tanh(y1))
        y2 += g * (math.tanh(y1) - math.tanh(y2))
        y3 += g * (math.tanh(y2) - math.tanh(y3))
        y4 += g * (math.tanh(y3) - math.tanh(y4))
        if hp == 1:
            f = u - 4.0 * y1 + 6.0 * y2 - 4.0 * y3 + y4
        else:
            f = y4 * (1.0 + 0.5 * res)  # make up some of the resonance's passband loss
        out[i] = f * aenv * vel
        prev1 = o1
    return out


# ------------------------------------------------------------------ voices and macros

# Six voices, each read off a reference clip (tag in the comment). Every number is "shape":
# a starting point to be judged by ear, not a measurement of the hardware.
VOICES = {
    # 131242: dive from ~400 Hz to ~70 Hz in ~80 ms, long low tail
    "KICK":  dict(f1=46, f2=92, w1=0, w2=0, pamt1=3.2, pamt2=3.2, ptau=.028, fm=0.0, sync=0,
                  lv1=1.0, lv2=.18, lnoise=.0, hp=0, cutoff=420, res=.15, famt=3.2, ftau=.04,
                  nmod=0.0, atk=.0008, atau=.16, drive=1.6, seconds=.9, velp=.7),
    # 130552: the industrial pew, harmonics falling from >10 kHz to ~1 kHz over ~150 ms
    "ZAP":   dict(f1=70, f2=105, w1=1, w2=1, pamt1=5.5, pamt2=4.5, ptau=.045, fm=.35, sync=0,
                  lv1=.8, lv2=.7, lnoise=.05, hp=0, cutoff=500, res=.45, famt=4.5, ftau=.07,
                  nmod=0.0, atk=.0008, atau=.13, drive=2.2, seconds=.9),
    # 131242 snare half: a short body under filtered noise
    "SNARE": dict(f1=185, f2=330, w1=0, w2=0, pamt1=1.0, pamt2=.8, ptau=.018, fm=0.0, sync=0,
                  lv1=.55, lv2=.3, lnoise=1.0, hp=0, cutoff=1300, res=.2, famt=3.4, ftau=.04,
                  nmod=.6, velp=.6, atk=.0008, atau=.07, drive=1.5, seconds=.6),
    # 'FM TOYS': VCO1 frequency-modulates VCO2, a talking tom
    "FMTOM": dict(f1=95, f2=140, w1=0, w2=0, pamt1=1.4, pamt2=1.8, ptau=.06, fm=1.6, sync=0,
                  lv1=.0, lv2=1.0, lnoise=.0, hp=0, cutoff=700, res=.35, famt=3.0, ftau=.09,
                  nmod=0.0, atk=.0008, atau=.14, drive=1.8, seconds=.8),
    # 130630: bright synced pings, short
    "METAL": dict(f1=310, f2=1330, w1=1, w2=1, pamt1=.6, pamt2=2.6, ptau=.03, fm=.25, sync=1,
                  lv1=.25, lv2=.9, lnoise=.0, hp=1, cutoff=500, res=.55, famt=2.6, ftau=.05, velp=.6,
                  nmod=0.0, atk=.0008, atau=.06, drive=1.6, seconds=.5),
    # 'QUICK HATS': noise through the high-pass ladder, resonant, very short
    "HAT":   dict(f1=540, f2=810, w1=1, w2=1, pamt1=0, pamt2=0, ptau=.02, fm=1.0, sync=0,
                  lv1=.12, lv2=.12, lnoise=1.0, hp=0, cutoff=2600, res=.55, famt=2.4, ftau=.03,
                  nmod=.4, atk=.0008, atau=.03, drive=1.2, seconds=.35),
}
MACROS = ["TUNE", "DIVE", "CLANG", "NOISE", "TONE", "LENGTH"]
DEFAULTS = {m: .5 for m in MACROS}


def around(x, lo, hi):
    """0.5 is the voice as written; 0 and 1 reach lo and hi multipliers (log-symmetric)."""
    return lo ** (1 - 2 * x) if x < .5 else hi ** (2 * x - 1)


def apply_macros(v: dict, m: dict) -> dict:
    p = dict(v)
    tune = (m["TUNE"] - .5) * 24.0                       # +-12 semitones
    p["f1"] *= 2 ** (tune / 12)
    p["f2"] *= 2 ** (tune / 12)
    d = m["DIVE"]                                        # pitch-dive depth and time together
    p["pamt1"] *= around(d, .0, 1.8) if d >= .5 else 2 * d
    p["pamt2"] *= around(d, .0, 1.8) if d >= .5 else 2 * d
    p["ptau"] *= around(d, .6, 2.2)
    c = m["CLANG"]                                       # FM 1->2 depth, sync above .8
    p["fm"] = p["fm"] * 2 * c if c < .5 else p["fm"] + (c - .5) * 4.0
    if c >= .8:
        p["sync"] = 1
    nz = m["NOISE"]                                      # noise level and noise -> cutoff
    p["lnoise"] = p["lnoise"] * 2 * nz if nz < .5 else p["lnoise"] + (nz - .5) * 3.0
    p["nmod"] = p["nmod"] * 2 * nz if nz < .5 else p["nmod"] + (nz - .5) * 2.0
    t = m["TONE"]                                        # cutoff and filter-EG together
    p["cutoff"] *= 2 ** ((t - .5) * 5.0)
    p["famt"] *= around(t, .5, 1.5)
    p["res"] = min(.92, p["res"] + max(0.0, t - .6) * .6)
    ln = m["LENGTH"]                                     # VCA and filter decays together
    p["atau"] *= around(ln, .3, 3.0)
    p["ftau"] *= around(ln, .5, 2.0)
    p["seconds"] = min(1.45, p["seconds"] * around(ln, .7, 1.6))
    return p


def _noise(n, seed):
    return np.random.default_rng(seed).uniform(-1.0, 1.0, n)


def render_raw(p: dict, triggers, seconds: float, take: int, seed_parts=("KINDRED",)) -> np.ndarray:
    """triggers: list of (time_s, semitones, velocity, to_vco2_only). Returns internal-rate audio."""
    n = int(seconds * FS)
    seed = sk.seed_for(*seed_parts, take)
    rng = np.random.default_rng(seed)
    # take 0 is the reference strike; later takes move phase, drift and decay a little
    ph1, ph2 = (0.0, 0.25) if take == 0 else tuple(rng.uniform(0, 1, 2))
    drift = 1.0 if take == 0 else 2 ** (rng.uniform(-4, 4) / 1200)
    dec = 1.0 if take == 0 else 1 + rng.uniform(-.05, .05)
    at = np.array([int(t * FS) for t, *_ in triggers], dtype=np.int64)
    se = np.array([s for _, s, _, _ in triggers], dtype=np.float64)
    ve = np.array([v for *_, v, _ in triggers], dtype=np.float64)
    to2 = np.array([o for *_, o in triggers], dtype=np.int64)
    return _run(n, float(FS), at, se, ve, to2, np.zeros(len(at), dtype=np.int64),
                p["f1"] * drift, p["f2"] * drift, p["w1"], p["w2"], p["pamt1"], p["pamt2"],
                p["ptau"] * dec, p["fm"], p["sync"], p["lv1"], p["lv2"], p["lnoise"],
                p["hp"], p["cutoff"], p["res"], p["famt"], p["ftau"] * dec, p["nmod"],
                p["atk"], p["atau"] * dec, p["drive"], ph1, ph2, _noise(n, seed), p.get("velp", .35), p.get("veldrive", .3))


def to_output(raw: np.ndarray) -> np.ndarray:
    """House order without levelling (a strip is levelled as a whole so velocity stays audible)."""
    y = sk.band_limit(raw, FS)
    y = sk.decimate4(y)
    return sk.dc_block(y, RATE)


def hit(voice, macros=None, vel=1.0, take=0) -> np.ndarray:
    p = apply_macros(VOICES[voice], macros or DEFAULTS)
    return to_output(render_raw(p, [(0.0, 0.0, vel, 0)], p["seconds"], take, (voice,)))


# ------------------------------------------------------------------ patterns (HOLD) and the riser

# Eight steps: (semitones, velocity). Velocity 0 is a silent step, as on the reference.
PATTERNS = {
    "TECHNO":     dict(voice="KICK", bpm=132, div=4,
                       steps=[(0, 1), (12, .35), (0, .6), (7, .45), (0, 1), (12, .3), (3, .7), (19, .4)],
                       macros=dict(DEFAULTS, LENGTH=.42, CLANG=.55)),
    "INDUSTRIAL": dict(voice="ZAP", bpm=96, div=4,
                       steps=[(0, 1), (-5, .55), (7, .8), (0, 0), (-12, 1), (5, .45), (0, .7), (12, .6)],
                       macros=dict(DEFAULTS, DIVE=.62, NOISE=.6)),
    "ELECTRO":    dict(voice="FMTOM", bpm=118, div=4,
                       steps=[(0, 1), (0, .4), (10, .75), (0, 0), (5, 1), (17, .5), (0, .65), (12, .8)],
                       macros=dict(DEFAULTS, CLANG=.6, LENGTH=.45)),
}


def pattern_loop(name: str, take: int = 0):
    """Render three passes continuously (oscillators reset at each pass start, noise repeated per
    pass), keep warm-up + last pass, measure the seam against a real preceding pass."""
    P = PATTERNS[name]
    p = apply_macros(VOICES[P["voice"]], P["macros"])
    step = 60.0 / P["bpm"] / P["div"]
    cycle = step * 8
    # pass length snapped to whole output frames
    cyc_n = int(round(cycle * RATE)) * 4
    trig = []
    for c in range(3):
        for i, (s, v) in enumerate(P["steps"]):
            if v > 0:
                trig.append((c * cyc_n / FS + i * step, float(s), float(v), 1 if P["voice"] == "FMTOM" else 0))
    seed = sk.seed_for(name, take)
    nz1 = _noise(cyc_n, seed)
    raw = np.zeros(3 * cyc_n)
    # run pass by pass so the oscillators restart each pass but envelopes and filter carry over:
    # simplest faithful way here is one run with noise tiled, phases reset handled by tiling too
    at = np.array([int(round(t * FS)) for t, *_ in trig], dtype=np.int64)
    se = np.array([s for _, s, _, _ in trig]); ve = np.array([v for *_, v, _ in trig])
    o2 = np.array([o for *_, o in trig], dtype=np.int64)
    rs = (at % cyc_n == 0).astype(np.int64)  # step 1 of each pass restarts the oscillators
    raw = _run(3 * cyc_n, float(FS), at, se, ve, o2, rs, p["f1"], p["f2"], p["w1"], p["w2"],
               p["pamt1"], p["pamt2"], p["ptau"], p["fm"], p["sync"], p["lv1"], p["lv2"],
               p["lnoise"], p["hp"], p["cutoff"], p["res"], p["famt"], p["ftau"], p["nmod"],
               p["atk"], p["atau"], p["drive"], 0.0, .25, np.tile(nz1, 3), p.get("velp", .35), p.get("veldrive", .3))
    y = to_output(raw)
    L = cyc_n // 4
    warm_and_loop = y[L:3 * L]          # pass 2 is the real preceding cycle, pass 3 the loop
    seam = sk.seam_error(warm_and_loop, L)
    return warm_and_loop[L:], seam, step


def riser(voice="ZAP", seconds=2.4, take=0):
    """The sequencer accelerating from 8 steps/s to audio rate: the pattern turns into a pitch.
    The reference's tempo reaches ~700 Hz; this stops at 320 steps/s (a 40 Hz pattern tone)."""
    P = PATTERNS["INDUSTRIAL"]
    p = apply_macros(VOICES[voice], dict(DEFAULTS, LENGTH=.3, DIVE=.55))
    trig, t, i = [], 0.0, 0
    while t < seconds - .05:
        frac = t / seconds
        rate = 8.0 * (320.0 / 8.0) ** (frac ** 1.6)
        s, v = P["steps"][i % 8]
        trig.append((t, float(s), max(v, .3) * (.55 + .45 * frac), 0))
        t += 1.0 / rate
        i += 1
    raw = render_raw(p, trig, seconds + .25, take, ("RISER", voice))
    y = to_output(raw)
    return y


# ------------------------------------------------------------------ measurements

def third_octave_db(x):
    s = np.abs(np.fft.rfft(x * np.hanning(len(x)), 1 << 16)) ** 2
    f = np.fft.rfftfreq(1 << 16, 1 / RATE)
    out = []
    c = 40.0
    while c < 16000:
        b = (f >= c / 2 ** (1 / 6)) & (f < c * 2 ** (1 / 6))
        out.append(10 * np.log10(s[b].sum() + 1e-20))
        c *= 2 ** (1 / 3)
    return np.array(out)


def shape_change_db(a, b):
    """Mean |dB| difference of third-octave spectra after matching total energy: a change in
    spectral SHAPE, which a volume change cannot produce."""
    da, db = third_octave_db(a), third_octave_db(b)
    ea = 10 * np.log10(np.sum(10 ** (da / 10))); eb = 10 * np.log10(np.sum(10 ** (db / 10)))
    w = da > da.max() - 50
    return float(np.mean(np.abs((da - ea) - (db - eb))[w]))


def separation_db(a, b):
    """House-style take distance: energy of a over energy of (a - b) after RMS match. A copy
    reads above 40 dB; lower is more different."""
    n = min(len(a), len(b)); a, b = a[:n], b[:n]
    b = b * (sk.rms(a) / max(sk.rms(b), 1e-12))
    return float(10 * np.log10(np.sum(a * a) / max(np.sum((a - b) ** 2), 1e-20)))


def centroid(x):
    s = np.abs(np.fft.rfft(x)); f = np.fft.rfftfreq(len(x), 1 / RATE)
    return float((s * f).sum() / s.sum())


# ------------------------------------------------------------------ audition build

def strip(parts, gap=.18):
    g = np.zeros(int(gap * RATE))
    out = []
    for p in parts:
        out += [p, g]
    return np.concatenate(out)


def level(x, target=.12):
    return sk.fade_tail(sk.level_to(x, target, .97), RATE)


def main(out):
    os.makedirs(out, exist_ok=True)
    rep = {"note": "Python harness, not Kotlin. Nothing heard.", "voices": {}, "patterns": {}}
    hit("KICK")  # compile
    clips = []
    for v in VOICES:
        t0 = time.perf_counter(); h = hit(v); ms = (time.perf_counter() - t0) * 1000
        soft, mid = hit(v, vel=.3), hit(v, vel=.65)
        takes = [hit(v, take=k) for k in range(4)]
        seps = [separation_db(takes[i], takes[j]) for i in range(4) for j in range(i + 1, 4)]
        rep["voices"][v] = {
            "render_ms_warm": round(ms, 1),
            "peak_before_level": round(float(np.max(np.abs(h))), 3),
            "finite": bool(np.all(np.isfinite(h))),
            "centroid_hz_soft_mid_hard": [round(centroid(x)) for x in (soft, mid, h)],
            "shape_change_soft_vs_hard_db": round(shape_change_db(soft, h), 2),
            "closest_takes_db": round(max(seps), 1), "furthest_takes_db": round(min(seps), 1),
            "seconds": round(len(h) / RATE, 3),
        }
        # one strip per voice: hard, then soft -> hard, then four takes at full force
        name = f"voice_{v.lower()}"
        sk.write_wav(f"{out}/{name}.wav", level(strip([h, soft, mid, h] + takes)))
        clips.append((name, f"{v}: one hit, then soft, medium, hard, then four takes"))
    # macro sweeps on ZAP and KICK
    for v in ("ZAP", "KICK"):
        for m in MACROS:
            parts = [hit(v, dict(DEFAULTS, **{m: x})) for x in (0, .25, .5, .75, 1)]
            name = f"sweep_{v.lower()}_{m.lower()}"
            sk.write_wav(f"{out}/{name}.wav", level(strip(parts)))
            clips.append((name, f"{v}: {m} at 0, .25, .5, .75, 1"))
            a, b = parts[0], parts[-1]
            rep.setdefault("macro_moves", {})[f"{v}.{m}"] = round(shape_change_db(a, b), 2)
    for name in PATTERNS:
        loop, seam, step = pattern_loop(name)
        rep["patterns"][name] = {"seam_error": float(f"{seam:.2e}"), "max": sk.MAX_SEAM_ERROR,
                                 "seconds": round(len(loop) / RATE, 3), "bpm": PATTERNS[name]["bpm"]}
        sk.write_wav(f"{out}/loop_{name.lower()}.wav", level(np.tile(loop, 4), .14))
        clips.append((f"loop_{name.lower()}", f"{name} pattern ({PATTERNS[name]['bpm']} BPM, 8 steps), the loop played four times end to end"))
    r = riser()
    sk.write_wav(f"{out}/riser.wav", level(r, .12))
    clips.append(("riser", "RISER: the sequencer speeds up from 8 steps a second until the pattern becomes a tone"))
    rep["riser_seconds"] = round(len(r) / RATE, 2)
    rep["clips"] = clips
    with open(f"{out}/report.json", "w") as fh:
        json.dump(rep, fh, indent=1)
    print(json.dumps({k: v for k, v in rep.items() if k != "clips"}, indent=1))


if __name__ == "__main__":
    main(sys.argv[1])
