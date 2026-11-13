#!/usr/bin/env python3
"""MIRRORBALL round 0: a 1970s disco kit through an FET compressor, a multi-head tape echo, then an amp.

Python harness, not Kotlin. Nothing here has been heard by its author; every constant is a shape value
for the owner's first listen. See docs/superpowers/specs/2026-11-12-mirrorball-disco-kit-design.md.

Usage:
    python3 scripts/mirrorball_r0.py <dir of dry SKIN/THUMP wavs> <out dir>

The dry sources are the app's own SKIN and THUMP renders (the spec lists the controls). The chain is
the owner's order, which the FX rack cannot make (VALVE runs before TAPE and ECHO in FxChain.SECTIONS):

    hit -> PRESS (FET compressor) -> REEL (multi-head tape echo) -> COMBO (tube amp + open speaker) -> out

Every stage is written as plain numpy/scipy with comments on *why*, because the reader is learning DSP.
Requires numpy and scipy.
"""
from __future__ import annotations

import json
import math
import os
import sys
import time
import wave

import numpy as np
from scipy import signal

RATE = 44_100
OS = 4                      # oversampling factor for the nonlinear stages, as the house does (Dsp.OVERSAMPLE)
BPM = 120.0                 # render() has no tempo in SnipSnap; a tempo-locked echo assumes one and says so
SIXTEENTH = 60.0 / BPM / 4  # 125 ms at 120 BPM
MAX_SECONDS = 1.49          # the classifier files anything over 1.5 s as LOOP; a hit stays a hit
SEED = 0x1976               # tape drift is seeded, so every run is byte-identical


# --------------------------------------------------------------------------------------------- wav i/o

def read_wav(path: str) -> np.ndarray:
    with wave.open(path, "rb") as w:
        sw, ch, n = w.getsampwidth(), w.getnchannels(), w.getnframes()
        raw = w.readframes(n)
    if sw == 2:
        x = np.frombuffer(raw, "<i2").astype(np.float64) / 32768.0
    elif sw == 3:
        b = np.frombuffer(raw, np.uint8).reshape(-1, 3).astype(np.int32)
        v = b[:, 0] | (b[:, 1] << 8) | (b[:, 2] << 16)
        x = np.where(v & 0x800000, v - (1 << 24), v).astype(np.float64) / (1 << 23)
    else:
        raise ValueError(sw)
    return x.reshape(-1, ch).mean(axis=1) if ch > 1 else x


def write_wav(path: str, x: np.ndarray) -> None:
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(pcm.tobytes())


# --------------------------------------------------------------------------------------------- helpers

def up(x):   return signal.resample_poly(x, OS, 1)       # to 176.4 kHz: room for the harmonics a clip makes
def down(x): return signal.resample_poly(x, 1, OS)       # band-limit and back: what would fold is filtered first

def peak(x):  return float(np.max(np.abs(x))) if len(x) else 0.0
def db(v):    return 20 * math.log10(max(v, 1e-12))

def biquad(kind: str, f0: float, q: float, rate: int, gain_db: float = 0.0):
    """RBJ cookbook coefficients (the same family as Dsp.Biquad)."""
    w = 2 * math.pi * f0 / rate; c = math.cos(w); s = math.sin(w); al = s / (2 * q); A = 10 ** (gain_db / 40)
    if kind == "lp":   b = [(1 - c) / 2, 1 - c, (1 - c) / 2]; a = [1 + al, -2 * c, 1 - al]
    elif kind == "hp": b = [(1 + c) / 2, -(1 + c), (1 + c) / 2]; a = [1 + al, -2 * c, 1 - al]
    elif kind == "peak":
        b = [1 + al * A, -2 * c, 1 - al * A]; a = [1 + al / A, -2 * c, 1 - al / A]
    elif kind == "hshelf":
        sq = 2 * math.sqrt(A) * al
        b = [A * ((A + 1) + (A - 1) * c + sq), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sq)]
        a = [(A + 1) - (A - 1) * c + sq, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sq]
    else:
        raise ValueError(kind)
    return np.array(b) / a[0], np.array(a) / a[0]

def filt(x, kind, f0, q=0.7071, rate=RATE, gain_db=0.0):
    b, a = biquad(kind, f0, q, rate, gain_db)
    return signal.lfilter(b, a, x)

def loudness(x: np.ndarray) -> float:
    """Loudness.of's shape: a gentle low cut, then the loudest 200 ms RMS. Used to match levels, so a
    comparison is never 'the louder one sounds better'."""
    y = signal.lfilter([1, -1], [1, -math.exp(-2 * math.pi * 120 / RATE)], x)
    n = int(0.2 * RATE)
    if len(y) <= n:
        return float(np.sqrt(np.mean(y * y)))
    c = np.cumsum(np.concatenate([[0.0], y * y]))
    return float(np.sqrt(np.max(c[n:] - c[:-n]) / n))

def finish(x: np.ndarray, target: float = 0.12, cap: bool = True) -> np.ndarray:
    """House output stage, condensed: DC out, cap at 1.49 s with a fade (hits only), level, 4 ms tail fade."""
    x = x - np.mean(x)
    x = signal.lfilter([1, -1], [1, -math.exp(-2 * math.pi * 20 / RATE)], x)
    n = min(len(x), int(MAX_SECONDS * RATE)) if cap else len(x)
    x = x[:n].copy()
    # trim trailing silence (under -70 dB of peak) so a dry hit is not padded to 1.49 s
    thr = peak(x) * 10 ** (-70 / 20)
    nz = np.nonzero(np.abs(x) > thr)[0]
    if len(nz):
        x = x[: min(len(x), nz[-1] + int(0.02 * RATE))]
    if cap and len(x) >= int((MAX_SECONDS - 0.01) * RATE):        # it hit the cap: fade the last 150 ms, raised cosine
        f = int(0.15 * RATE)
        x[-f:] *= 0.5 * (1 + np.cos(np.linspace(0, math.pi, f)))
    L = loudness(x)
    if L > 0:
        x *= target / L
    if peak(x) > 0.98:
        x *= 0.98 / peak(x)
    f = int(0.004 * RATE)
    x[-f:] *= np.linspace(1, 0, f)
    return x


# --------------------------------------------------------------------------------------------- PRESS

RATIOS = {"4": 4.0, "8": 8.0, "12": 12.0, "20": 20.0, "ALL": 20.0}

def press(x: np.ndarray, input_db: float, attack: float, release: float, ratio: str = "4",
          stats: dict | None = None) -> np.ndarray:
    """An FET limiting amplifier in the 1176's manner.

    What makes that box itself, as mechanisms:
      * INPUT, not threshold: the threshold is fixed and you drive into it. More input = more squeeze.
      * Very fast attack: 20 us to 800 us (the original's published range). Fast enough to shave a
        beater click, which is why drummers set it slower on a kick.
      * Release 50 ms to 1.1 s, and program dependent: after a long squeeze it lets go more slowly.
      * At fast attack and release the gain follows the waveform itself on low notes, and that is
        distortion (the pedal's manual: both knobs fully clockwise "adds extra harmonics and
        distortion"). Here it is not added: it falls out of the fast detector.
      * The FET gain cell adds its own low-order harmonics, growing with how hard it is working.
      * ALL ("all buttons in"): the ratio pins, the knee goes strange, the attack overshoots and the
        distortion rises. Modeled as ratio 20, a wide knee, a 3 dB overshoot after each attack and
        double gain-cell distortion: a law, not a circuit.

    attack and release are 0..1 knobs: 0 slowest, 1 fastest (on the hardware fully clockwise is fastest).
    Runs at 4x, because a gain that moves in 20 us makes sidebands far above the audio band.
    """
    p = peak(x)
    if p <= 0:
        return x
    r = RATE * OS
    u = up(x / p) * 10 ** (input_db / 20)
    thr_db = -6.0                                           # fixed, like the hardware; INPUT does the work
    R = RATIOS[ratio]
    knee = 12.0 if ratio == "ALL" else 4.0
    t_att = 800e-6 * (20e-6 / 800e-6) ** attack             # 800 us -> 20 us
    t_rel = 1.1 * (0.05 / 1.1) ** release                   # 1.1 s -> 50 ms
    a_att = math.exp(-1 / (t_att * r))
    a_rel_fast = math.exp(-1 / (t_rel * r))
    a_rel_slow = math.exp(-1 / (t_rel * 5 * r))              # program dependence: a slow second stage
    a_hold = math.exp(-1 / (0.15 * r))                       # how quickly "it has been squeezing" builds
    dist = 0.10 if ratio == "ALL" else 0.05
    over = 10 ** (3 / 20) if ratio == "ALL" else 1.0

    level = np.abs(u)
    out = np.empty_like(u)
    gr_db = 0.0; busy = 0.0; max_gr = 0.0; prev_target = 0.0
    for i in range(len(u)):
        l_db = 20 * math.log10(level[i] + 1e-12)
        over_db = l_db - thr_db
        # soft knee gain computer (Giannoulis, Massberg and Reiss 2012)
        if 2 * over_db < -knee:
            target = 0.0
        elif 2 * abs(over_db) <= knee:
            target = (1 - 1 / R) * (over_db + knee / 2) ** 2 / (2 * knee)
        else:
            target = (1 - 1 / R) * over_db
        if target > gr_db:                                   # attack: the squeeze lands
            gr_db = a_att * gr_db + (1 - a_att) * target
            if over > 1 and target > prev_target + 0.5:      # ALL: overshoot on a fresh attack
                gr_db = min(gr_db * over, target + 3)
        else:                                                # release: fast stage, then the slow one
            a = a_rel_fast * (1 - busy) + a_rel_slow * busy
            gr_db = a * gr_db + (1 - a) * target
        prev_target = target
        busy = a_hold * busy + (1 - a_hold) * (1.0 if gr_db > 3 else 0.0)
        g = 10 ** (-gr_db / 20)
        y = u[i] * g
        # the FET cell: an even-plus-odd bend that grows with gain reduction
        work = min(gr_db / 20, 1.0)
        out[i] = y + dist * work * (0.5 * y * y - 0.3 * y * y * y)
        max_gr = max(max_gr, gr_db)
    # the output transformer and class-A line amp: barely there, but it rounds the very top
    out = np.tanh(out * 1.2) / 1.2
    y = down(out)[: len(x)]
    if stats is not None:
        stats["press_max_gr_db"] = round(max_gr, 1)
    return y * (p / max(peak(y), 1e-12))


# --------------------------------------------------------------------------------------------- REEL

HEADS = {1: 1.0, 2: 3.0, 3: 4.0}    # in sixteenths: the manual's tap subdivisions 1/16, dotted 1/8, 1/4

def reel(x: np.ndarray, heads: tuple[int, ...], mix: float, repeats: float, age: float, wow: float,
         contour_hz: float = 180.0, drive: float = 0.4, seed: int = SEED, length: int | None = None) -> np.ndarray:
    """A tape echo with three playback heads, two of which can play at once (the multi-head machine).

    Mechanisms kept:
      * Heads at fixed distances, so the repeats are a rhythm, not a number: heads 1 and 2 at 120 BPM
        are a sixteenth and a dotted eighth, which on a hi-hat is the disco gallop.
      * Every pass goes through the tape again: it saturates (record bias), loses top (TAPE AGE),
        loses bottom (the low-end contour), so the repeats darken and thin as they fade.
      * Wow (slow) and flutter (fast) move the whole tape, so every head wobbles together. Seeded.
      * REPEATS is loop gain, bounded below 1 by construction and with a tanh in the loop, so it
        cannot run away. ("Saturated oscillation" on the pedal needs feedback above 1, which the
        house does not allow; this stops short of it and says so.)
    The output is dry + mix * wet, and capped at 1.49 s later, so a hit stays a hit.
    """
    rng = np.random.default_rng(seed)
    n_tail = length or int(MAX_SECONDS * RATE)
    n = max(len(x), n_tail)
    xin = np.zeros(n); xin[: len(x)] = x
    delays = [HEADS[h] * SIXTEENTH * RATE for h in heads]
    max_d = int(max(delays) + 0.01 * RATE + 8)
    buf = np.zeros(n + max_d + 4)
    wet = np.zeros(n)
    # tape transport: wow ~0.7 Hz, flutter ~6.5 Hz with a seeded wander, in samples of delay modulation
    t = np.arange(n) / RATE
    ph1, ph2 = rng.uniform(0, 2 * math.pi, 2)
    drift = np.cumsum(rng.normal(0, 1, n)); drift = signal.lfilter([0.002], [1, -0.998], drift - drift.mean())
    drift /= max(peak(drift), 1e-9)
    mod = wow * (0.0018 * np.sin(2 * math.pi * 0.7 * t + ph1)
                 + 0.00025 * np.sin(2 * math.pi * 6.5 * t + ph2)
                 + 0.0006 * drift) * RATE
    # tape age: the loop's top end, 7 kHz on new tape down to 1.8 kHz on worn tape
    lp_hz = 7000 * (1800 / 7000) ** age
    a_lp = math.exp(-2 * math.pi * lp_hz / RATE)
    a_hp = math.exp(-2 * math.pi * contour_hz / RATE)
    g = repeats * 0.92 / len(heads)                      # the loop gain stays under 0.92 whatever is set
    lp = hp_x = hp_y = 0.0
    k = 1 + 4 * drive
    for i in range(n):
        # read every head off the same moving tape
        s = 0.0
        for d in delays:
            pos = i - d - mod[i]
            j = int(math.floor(pos)); f = pos - j
            if j >= 0:
                s += buf[j] * (1 - f) + buf[j + 1] * f if j + 1 < len(buf) else buf[j]
        wet[i] = s
        # the loop: worn top, contoured bottom, back to the record head
        lp = (1 - a_lp) * s + a_lp * lp
        hp_y = a_hp * (hp_y + lp - hp_x); hp_x = lp
        rec = xin[i] + g * hp_y
        buf[i] = math.tanh(k * rec) / k                  # the record head saturates (bias), every pass
    # what comes off the playback heads has been through the record head once too: darken the first pass
    wet = signal.lfilter([1 - a_lp], [1, -a_lp], wet)
    return xin + mix * wet


# --------------------------------------------------------------------------------------------- COMBO

def combo(x: np.ndarray, drive: float, sag: float = 0.4, cab_hz: float = 5200.0,
          stats: dict | None = None) -> np.ndarray:
    """A small clean tube combo with an open-backed speaker, after the echo.

    Order is the point: because the amp is last, the echo's repeats go through the tubes as well as the
    hit. The supply sags after the loud hit, so the first repeats get through at slightly lower gain, and
    then the amp is evened out like a second, softer compressor across hit and repeats.

      * Preamp: the asymmetric tube curve (tanh above zero, softer below: even harmonics), VALVE's curve.
      * Tone stack: a broad mid scoop and a little top, the clean-combo voicing.
      * Sag: the supply drops with the power drawn and recovers over ~120 ms.
      * Power stage: a symmetric soft clip.
      * Speaker: open back, so less low thump; a bump near 110 Hz and a roll-off above ~5 kHz.
    drive is the preamp gain, linear (1.5 is clean, 6 is pushed); cab_hz is where the speaker
    rolls off (5.2 kHz a 12-inch cone, 8.5 kHz a brighter one). Runs at 4x.
    """
    p = peak(x)
    if p <= 0:
        return x
    r = RATE * OS
    u = up(x / p) * drive
    pre = np.where(u >= 0, np.tanh(u), u / np.sqrt(1 + u * u))
    pre = filt(pre, "peak", 550, 0.6, r, -5.0)          # mid scoop
    pre = filt(pre, "hshelf", 2800, 0.7, r, +2.5)       # bright cap's share
    # sag: an envelope of power, which takes gain away
    env = np.empty_like(pre); e = 0.0
    a_up = math.exp(-1 / (0.004 * r)); a_dn = math.exp(-1 / (0.12 * r))
    sq = pre * pre
    for i in range(len(pre)):
        a = a_up if sq[i] > e else a_dn
        e = a * e + (1 - a) * sq[i]
        env[i] = e
    gain = 1 / (1 + sag * 3 * np.sqrt(env))
    pw = np.tanh(1.4 * pre * gain) / 1.4
    y = down(pw)[: len(x)]
    if stats is not None:
        stats["combo_min_gain_db"] = round(db(float(np.min(gain[: len(gain)]))), 1)
    # the speaker, at the output rate (linear, nothing to fold)
    y = filt(y, "hp", 75, 0.8)
    y = filt(y, "peak", 110, 1.4, RATE, +3.0)
    y = filt(y, "lp", cab_hz, 0.8)
    y = filt(y, "lp", max(9000, cab_hz * 1.6), 0.6)
    return y * (p / max(peak(y), 1e-12))


# --------------------------------------------------------------------------------------------- the kit

# Each pad: its PRESS settings, its REEL send, and whether it is a drum the era kept dry.
# Disco drums were dry: the echo is a send on the hats and percussion, a whisper on the snare, and
# nothing on the kick, so four-on-the-floor stays four, not sixteen.
PADS = {
    "kick":   dict(press=dict(input_db=14, attack=0.45, release=0.80, ratio="8"),
                   reel=dict(heads=(1,), mix=0.00, repeats=0.0)),
    "snare":  dict(press=dict(input_db=16, attack=0.70, release=0.75, ratio="4"),
                   reel=dict(heads=(1,), mix=0.16, repeats=0.15)),
    "hatc":   dict(press=dict(input_db=10, attack=0.60, release=0.60, ratio="4"),
                   reel=dict(heads=(1, 2), mix=0.30, repeats=0.35)),
    "hato":   dict(press=dict(input_db=10, attack=0.60, release=0.55, ratio="4"),
                   reel=dict(heads=(1, 2), mix=0.24, repeats=0.30)),
    "shaker": dict(press=dict(input_db=12, attack=0.70, release=0.60, ratio="4"),
                   reel=dict(heads=(1, 2), mix=0.28, repeats=0.35)),
    "tom":    dict(press=dict(input_db=12, attack=0.55, release=0.60, ratio="8"),
                   reel=dict(heads=(2,), mix=0.22, repeats=0.25)),
    "pew":    dict(press=dict(input_db=12, attack=0.55, release=0.55, ratio="4"),
                   reel=dict(heads=(2, 3), mix=0.30, repeats=0.45)),
}
REEL_COMMON = dict(age=0.45, wow=0.5)
AMPS = {"clean": dict(drive=1.6, sag=0.35), "bright": dict(drive=1.6, sag=0.35, cab_hz=8500.0),
        "pushed": dict(drive=5.0, sag=0.6)}


def chain(x, pad, amp="clean", order="owner", ratio=None, stats=None):
    cfg = PADS[pad]
    pr = dict(cfg["press"]);  pr["ratio"] = ratio or pr["ratio"]
    y = press(x, **pr, stats=stats)
    if order == "owner":                                      # PRESS -> REEL -> COMBO
        y = reel(y, **cfg["reel"], **REEL_COMMON)
        y = combo(y, **AMPS[amp], stats=stats)
    else:                                                     # the rack's order: PRESS -> COMBO -> REEL
        y = combo(y, **AMPS[amp], stats=stats)
        y = reel(y, **cfg["reel"], **REEL_COMMON)
    return y


# --------------------------------------------------------------------------------------------- measures

def centroid(x):
    if len(x) < 64 or peak(x) == 0: return 0.0
    X = np.abs(np.fft.rfft(x * np.hanning(len(x)))); f = np.fft.rfftfreq(len(x), 1 / RATE)
    return float(np.sum(f * X) / max(np.sum(X), 1e-12))

def crest_db(x, ms=100):
    seg = x[: int(ms * 1e-3 * RATE)]
    return db(peak(seg) / max(float(np.sqrt(np.mean(seg * seg))), 1e-12))

def band_share(x, lo, hi, t0, t1):
    seg = x[int(t0 * RATE): int(t1 * RATE)]
    if len(seg) < 64: return float("nan")
    X = np.abs(np.fft.rfft(seg * np.hanning(len(seg)))) ** 2; f = np.fft.rfftfreq(len(seg), 1 / RATE)
    return db(math.sqrt(np.sum(X[(f >= lo) & (f < hi)]) / max(np.sum(X), 1e-30)))

def repeat_stats(y, dry_len, heads):
    """Level and brightness of each repeat window against the hit (the first 60 ms)."""
    hit = y[: int(0.06 * RATE)]
    out = []
    times = sorted({HEADS[h] * k for h in heads for k in (1, 2, 3)})[:4]
    for s in times:
        t0 = s * SIXTEENTH; w = y[int(t0 * RATE): int((t0 + 0.06) * RATE)]
        if len(w) < 64: break
        out.append(dict(at_ms=round(t0 * 1000), level_db=round(db(peak(w) / peak(hit)), 1),
                        centroid_hz=round(centroid(w))))
    return out


# --------------------------------------------------------------------------------------------- main

def main(src: str, dst: str) -> int:
    os.makedirs(dst, exist_ok=True)
    report: dict = {"harness": "Python, not Kotlin", "bpm": BPM, "pads": {}, "order": {}, "groove": {}}
    timing = []
    for pad in PADS:
        dry = read_wav(os.path.join(src, f"{pad}_v100_t0.wav"))
        st: dict = {}
        t0 = time.perf_counter()
        pressed = press(dry, **PADS[pad]["press"], stats=st)
        reeled = reel(pressed, **PADS[pad]["reel"], **REEL_COMMON)
        full = combo(reeled, **AMPS["clean"], stats=st)
        timing.append(time.perf_counter() - t0)
        pushed = chain(dry, pad, amp="pushed")
        bright = chain(dry, pad, amp="bright")
        rack = chain(dry, pad, order="rack")
        nuke = chain(dry, pad, ratio="ALL")
        soft = chain(read_wav(os.path.join(src, f"{pad}_v45_t0.wav")), pad)
        take2 = chain(read_wav(os.path.join(src, f"{pad}_v100_t1.wav")), pad)
        files = {"dry": dry, "press": pressed, "press_reel": reeled, "full": full, "bright": bright, "pushed": pushed,
                 "rack_order": rack, "all_buttons": nuke, "soft": soft, "take2": take2}
        for k, v in files.items():
            write_wav(os.path.join(dst, f"{pad}-{k}.wav"), finish(v))
        f_dry, f_press, f_full = finish(dry), finish(pressed), finish(full)
        report["pads"][pad] = dict(
            press_max_gr_db=st.get("press_max_gr_db"),
            crest_dry_db=round(crest_db(f_dry), 1), crest_press_db=round(crest_db(f_press), 1),
            crest_full_db=round(crest_db(f_full), 1),
            centroid_dry_hz=round(centroid(f_dry[: int(.15 * RATE)])),
            centroid_full_hz=round(centroid(f_full[: int(.15 * RATE)])),
            length_s=round(len(f_full) / RATE, 3),
            combo_min_gain_db=st.get("combo_min_gain_db"),
        )
        if PADS[pad]["reel"]["mix"] > 0:
            hs = PADS[pad]["reel"]["heads"]
            report["order"][pad] = dict(owner=repeat_stats(full, len(dry), hs),
                                        rack=repeat_stats(rack, len(dry), hs))
    # FET distortion on the kick, from the fast detector alone: share of energy above 150 Hz, 60-250 ms
    kd = read_wav(os.path.join(src, "kick_v100_t0.wav"))
    slow = press(kd, 14, 0.0, 0.0, "8"); fast = press(kd, 14, 1.0, 1.0, "8")
    report["kick_fast_release_harmonics"] = dict(
        dry_db=round(band_share(kd, 150, 5000, .06, .25), 1),
        slow_knobs_db=round(band_share(slow, 150, 5000, .06, .25), 1),
        fast_knobs_db=round(band_share(fast, 150, 5000, .06, .25), 1))
    # loop stability at the top of REPEATS on an impulse: the 100 ms windows must keep falling
    # (windows are 375 ms, three sixteenths, so each holds the same share of the two heads' rhythm)
    imp = np.zeros(int(0.01 * RATE)); imp[10] = 1
    e = reel(imp, (1, 2), 1.0, 1.0, 0.45, 0.5, length=int(4.0 * RATE))
    win = 3 * SIXTEENTH
    w = [db(math.sqrt(np.mean(e[int(a * RATE): int((a + win) * RATE)] ** 2)))
         for a in np.arange(win, 4.0 - win, win)]
    report["reel_stability_repeats1"] = dict(windows_db=[round(v, 1) for v in w],
                                             falls=bool(all(b < a for a, b in zip(w, w[1:]))))
    report["render_seconds_per_pad_python"] = [round(t, 2) for t in timing]
    groove(src, dst, report)
    with open(os.path.join(dst, "report.json"), "w") as fh:
        json.dump(report, fh, indent=1)
    print(json.dumps(report, indent=1))
    return 0


def groove(src: str, dst: str, report: dict) -> None:
    """Two bars at 120 BPM, played the way a sampler plays a kit: each pad's own printed hit, with
    takes alternating (round robin) and the soft zone on ghost notes. Then the same two bars dry, and
    the same two bars with the chain across the whole kit (a drum bus), which a pad cannot do."""
    def hit(pad, v, t, chain_on, amp="clean"):
        x = read_wav(os.path.join(src, f"{pad}_v{v}_t{t}.wav"))
        return chain(x, pad, amp=amp) if chain_on else x
    bar = 4 * 4 * SIXTEENTH
    n = int((2 * bar + 1.2) * RATE)
    # (pad, sixteenth index, velocity, gain)
    pattern = []
    for b in range(2):
        o = b * 16
        for q in range(4):  pattern.append(("kick", o + 4 * q, 100, 1.0))
        for q in (1, 3):    pattern.append(("snare", o + 4 * q, 100, 0.8))
        for e8 in range(8):
            s = o + 2 * e8
            pattern.append(("hato" if e8 % 2 == 1 else "hatc", s, 100, 0.45 if e8 % 2 else 0.35))
        for s16 in range(16):
            if s16 % 2: pattern.append(("shaker", o + s16, 45, 0.18))
    pattern.append(("pew", 28, 100, 0.5)); pattern.append(("pew", 30, 100, 0.45))
    cache: dict = {}
    for name, mode in (("groove-dry", "dry"), ("groove-pads", "pads")):
        mix = np.zeros(n); count: dict = {}
        for pad, s, v, gain in pattern:
            t = count.get((pad, v), 0) % 2; count[(pad, v)] = count.get((pad, v), 0) + 1
            key = (pad, v, t, mode)
            if key not in cache:
                cache[key] = finish(hit(pad, v, t, mode == "pads"), 0.12)
            x = cache[key] * gain
            i = int(s * SIXTEENTH * RATE); m = min(len(x), n - i); mix[i: i + m] += x[:m]
        write_wav(os.path.join(dst, f"{name}.wav"), finish(mix, 0.16, cap=False))
    # the bus version: the dry groove through PRESS -> REEL -> COMBO as one signal
    dry = read_wav(os.path.join(dst, "groove-dry.wav"))
    bus = press(dry, 10, 0.5, 0.7, "4")
    bus = reel(bus, (1, 2), 0.12, 0.25, **REEL_COMMON, length=len(bus))
    bus = combo(bus, **AMPS["clean"])
    out = finish(bus, 0.16, cap=False)
    write_wav(os.path.join(dst, "groove-bus.wav"), out)
    report["groove"] = dict(bars=2, bpm=BPM, note="pads = per-pad prints as a sampler plays them; bus = one chain across the kit")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__); sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
