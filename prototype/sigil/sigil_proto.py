"""SIGIL round 0: the voices and the three kits' wear chains. Python harness, not Kotlin; nothing heard.

SIGIL is a working name for a family of three drum kits written after the owner's 23 reference loops
(measured in docs/SIGIL.md). The references are never committed, sliced or shipped; only numbers taken from
them appear here.

The brief in one line: impactful, dark, centred kick and snare under percussion that is part organic (wood,
glass, beads, skin, breath) and part synthetic (pitch-swept sines, FM, sample-crushed grains).

Every voice renders at 4x (176.4 kHz), as the house does, so the drive stages fold their harmonics above the
band the decimator removes. `finish` is the house output stage in Python (snipkit): band limit, decimate,
DC block, a 4 ms tail fade.

    render(kit, pad, velocity, take) -> float32 mono at 44.1 kHz
"""
from __future__ import annotations

import functools
import math
import os
import sys

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", ".claude", "skills", "luthier", "scripts"))
import snipkit as sk  # noqa: E402

RATE = sk.RATE
R = sk.INTERNAL  # internal rate, 4x

# A minor: every pitched pad sits in this key. The owner's tonal loops are labelled Am, F, C and E,
# all inside A natural minor, so one key keeps every tuned pad agreeing with every reference.
A2, C3, D3, E3, G3, A3 = 110.0, 130.81, 146.83, 164.81, 196.0, 220.0
A4, C5, E5 = 440.0, 523.25, 659.26


# ------------------------------------------------------------------ small tools

def n_of(seconds):
    return int(round(seconds * R))


def t_axis(n):
    return np.arange(n) / R


def rng_for(*parts):
    return np.random.default_rng(sk.seed_for("SIGIL", *parts))


def sos(kind, hz, order=2, rate=R):
    if kind == "band":
        return signal.butter(order, [hz[0], hz[1]], btype="bandpass", fs=rate, output="sos")
    return signal.butter(order, hz, btype=kind, fs=rate, output="sos")


def filt(x, kind, hz, order=2):
    return signal.sosfilt(sos(kind, hz, order), x)


def peaking(x, hz, gain_db, q=0.9, rate=R):
    """RBJ peaking EQ (one biquad)."""
    a = 10 ** (gain_db / 40)
    w = 2 * math.pi * hz / rate
    al = math.sin(w) / (2 * q)
    b = [1 + al * a, -2 * math.cos(w), 1 - al * a]
    d = [1 + al / a, -2 * math.cos(w), 1 - al / a]
    return signal.lfilter(np.array(b) / d[0], np.array(d) / d[0], x)


def sweep_phase(f):
    """Phase of an oscillator whose frequency follows the array f (Hz per sample)."""
    return 2 * math.pi * np.cumsum(f) / R


def env_exp(n, tau, hold=0.0):
    t = t_axis(n)
    return np.where(t < hold, 1.0, np.exp(-(t - hold) / max(tau, 1e-4)))


def attack(n, ms):
    """A short raised-cosine onset so no voice starts on a step (a step is a click nobody chose)."""
    k = max(1, int(ms * 1e-3 * R))
    e = np.ones(n)
    e[:k] = 0.5 - 0.5 * np.cos(np.linspace(0, math.pi, k))
    return e


def modal(n, freqs, amps, t60s, bright=1.0, rng=None, phase_jitter=0.0):
    """Struck modes as decaying sines. `bright` weights the upper modes (a harder strike)."""
    t = t_axis(n)
    y = np.zeros(n)
    for f, a, t60 in zip(freqs, amps, t60s):
        if f >= RATE / 2 * 0.95:
            continue
        w = a * (bright ** math.log2(max(f / freqs[0], 1.0)))
        ph = 0.0 if rng is None else rng.uniform(-phase_jitter, phase_jitter)
        y += w * np.exp(-t * 6.91 / t60) * np.sin(2 * math.pi * f * t + ph)
    return y


def drive(x, amount, asym=0.0):
    """tanh saturation; `asym` biases it so even harmonics appear (a cone or a tape pushed off-centre)."""
    if amount <= 0:
        return x
    g = 1 + 9 * amount
    y = np.tanh(g * (x + asym)) - math.tanh(g * asym)
    return y / math.tanh(g)


def crush(x, keep_hz, bits=None):
    """Sample-and-hold to a lower rate (no anti-alias filter on purpose: the inorganic grain), optional bits."""
    step = max(1, int(R / keep_hz))
    idx = (np.arange(len(x)) // step) * step
    y = x[idx]
    if bits:
        q = 2 ** (bits - 1)
        y = np.round(y * q) / q
    return y


def norm(x, peak=1.0):
    m = np.max(np.abs(x))
    return x * (peak / m) if m > 0 else x


def finish(raw, seconds=None):
    """House output stage (snipkit): band limit at 4x, decimate to 44.1 kHz, DC block, 4 ms tail fade."""
    y = sk.decimate4(sk.band_limit(raw, R))
    y = sk.dc_block(y)
    if seconds is not None:
        y = y[: int(seconds * RATE)]
    return sk.fade_tail(y).astype(np.float32)


# ------------------------------------------------------------------ drums

def kick(p, v, rng):
    """A sub drop: a sine whose pitch falls from f_start to f_end, a beater click, a short box knock.
    Measured on the references: 105 to 135 Hz at the attack falling to 36 to 60 Hz, gone 20 dB in 70 to 150 ms
    (the blown one 350), harmonics 8 to 13 dB under the fundamental when clean."""
    tune = 1 + rng.normal(0, 0.006)
    f1 = p["f_end"] * tune
    f0 = f1 + (p["f_start"] * tune - f1) * (0.55 + 0.45 * v)  # a soft hit sweeps less: less snap
    n = n_of(p["len"])
    t = t_axis(n)
    tau = p["sweep"] * (1 + rng.normal(0, 0.05))
    f = f1 + (f0 - f1) * np.exp(-t / tau)
    body = np.sin(sweep_phase(f)) * env_exp(n, p["decay"] * (1 + rng.normal(0, 0.04)), p.get("hold", 0.0))
    click = filt(rng.normal(0, 1, n), "band", (1500, 6000)) * env_exp(n, 0.0025) * 0.35
    click += np.sin(2 * math.pi * 1800 * t) * env_exp(n, 0.0015) * 0.4
    knock = np.sin(2 * math.pi * p.get("knock_hz", 210) * tune * t) * env_exp(n, 0.022) * p.get("knock", 0.15)
    y = body + click * p["click"] * v ** 1.5 + knock * (0.6 + 0.4 * v)
    y *= attack(n, 0.3)
    y = drive(y, p["drive"] * (0.55 + 0.45 * v), p.get("asym", 0.0))
    return y


def snare(p, v, rng):
    """Two head modes, wires (filtered noise), a crack, and a short tail. `wire_lp` is the darkness:
    the references' snares sit at a 1.1 to 1.8 kHz centroid (dark) and 2.4 kHz (the crack)."""
    n = n_of(p["len"])
    t = t_axis(n)
    tune = p["tune"] * (1 + rng.normal(0, 0.008))
    drop = 1 + 0.08 * v * np.exp(-t / 0.012)  # the head bends down after a hard hit
    head = (np.sin(sweep_phase(185 * tune * drop)) * env_exp(n, p["head_decay"])
            + 0.55 * np.sin(sweep_phase(328 * tune * drop) + 0.7) * env_exp(n, p["head_decay"] * 0.6))
    nz = rng.normal(0, 1, n)
    lp = p["wire_lp"] * (0.6 + 0.4 * v)  # soft hit: darker wires
    wires = filt(filt(nz, "high", 450), "low", lp, 2) * env_exp(n, p["wire_decay"] * (1 + rng.normal(0, 0.05)))
    crack = filt(nz, "high", 1800) * env_exp(n, 0.004) * p["crack"] * v ** 1.3
    # the tail: the same wires through a short dark room, so the decay is long without being a reverb
    tail = filt(filt(rng.normal(0, 1, n), "band", (300, p["wire_lp"] * 0.7)), "low", 2500)
    tail *= env_exp(n, p["tail"]) * (1 - env_exp(n, 0.006)) * p.get("tail_gain", 0.35)
    y = p["head"] * head + p["wires"] * wires + crack + tail
    y *= attack(n, 0.2)
    return drive(y, p["drive"] * (0.5 + 0.5 * v))


def clap(p, v, rng):
    """Four noise bursts 7 to 11 ms apart and a tail; the last burst sample-crushed (the synthetic grain)."""
    n = n_of(p["len"])
    y = np.zeros(n)
    at = 0
    for k in range(4):
        b = filt(rng.normal(0, 1, n), "band", (900, 2600)) * env_exp(n, 0.005 if k < 3 else p["decay"])
        b = np.roll(b, at)
        b[:at] = 0
        if k == 3:
            b = 0.6 * b + 0.4 * crush(b, 9000, 8)
        y += b * (0.7 + 0.3 * rng.random())
        at += int(R * (0.007 + 0.004 * rng.random()))
    return drive(y * (0.7 + 0.3 * v), p.get("drive", 0.3))


HAT_HZ = [205.3, 304.4, 369.6, 522.7, 540.0, 800.0]  # six detuned squares: the drum-machine metal


def hat(p, v, rng):
    """Inorganic metal (six band-limited squares) mixed with organic noise, through a band and a decay.
    Additive squares (odd harmonics to 20 kHz) so nothing aliases at the oversampled rate."""
    n = n_of(p["len"])
    t = t_axis(n)
    ratio = p["ratio"] * (1 + rng.normal(0, 0.004))
    metal = np.zeros(n)
    for f in HAT_HZ:
        f *= ratio
        k = 1
        while f * k < 20000:
            metal += np.sin(2 * math.pi * f * k * t + rng.uniform(0, 6.28)) / k
            k += 2
    nz = rng.normal(0, 1, n)
    y = p["metal"] * metal / 6 + (1 - p["metal"]) * nz
    y = filt(y, "high", p["hp"] * (0.8 + 0.2 * v), 4)
    y = filt(y, "low", p["lp"] * (0.45 + 0.55 * v), 2)  # a soft stick closes the top: the hat's own touch
    y *= env_exp(n, p["decay"] * (0.8 + 0.2 * v) * (1 + rng.normal(0, 0.05))) * attack(n, 0.15)
    return y


# ------------------------------------------------------------------ hybrid percussion

def log_drum(p, v, rng):
    """Half synth, half wood. The synthetic half is a sine that bends down an interval into the note (the
    glide a programmed log drum has); the organic half is a free bar's modes (1, 2.76, 5.40) struck by a mallet.
    A little drive knits them. Tuned in A minor."""
    n = n_of(p["len"])
    t = t_axis(n)
    f = p["note"] * (1 + rng.normal(0, 0.002))
    # each take lands the mallet a little differently: the bend, its glide and the ring move a few percent
    bend = 2 ** (p["bend"] * (0.6 + 0.4 * v) * (1 + rng.normal(0, 0.08)) / 12)
    fs = f * (1 + (bend - 1) * np.exp(-t / (p.get("glide", 0.018) * (1 + rng.normal(0, 0.1)))))
    synth = np.sin(sweep_phase(fs)) * env_exp(n, p["decay"] * (1 + rng.normal(0, 0.05)))
    wood = modal(n, [f, f * 2.756, f * 5.404], [1, 0.45, 0.2], [p["decay"] * 3, p["decay"] * 1.2, 0.05],
                 bright=0.5 + 0.5 * v, rng=rng, phase_jitter=0.5)
    mallet = filt(rng.normal(0, 1, n), "band", (f * 4, min(f * 30, 12000))) * env_exp(n, 0.0025) * 0.4 * v * v
    y = (1 - p["wood"]) * synth + p["wood"] * norm(wood) + mallet
    return drive(y * attack(n, 0.25) * (0.4 + 0.6 * v), p.get("drive", 0.35))  # a soft strike saturates less


def glass_bell(p, v, rng):
    """FM (carrier at the note, modulator at 3.51x, index falling) beside glass modes (1, 2.32, 4.25, 6.63)."""
    n = n_of(p["len"])
    t = t_axis(n)
    f = p["note"] * (1 + rng.normal(0, 0.002))
    index = (0.3 + 3.5 * v) * np.exp(-t / 0.05) + 0.4
    fm = np.sin(2 * math.pi * f * t + index * np.sin(2 * math.pi * f * 3.51 * t)) * env_exp(n, p["decay"])
    glass = modal(n, [f, f * 2.32, f * 4.25, f * 6.63], [1, 0.6, 0.35, 0.2],
                  [p["decay"] * 4, p["decay"] * 2, p["decay"], p["decay"] * 0.5], bright=0.4 + 0.6 * v, rng=rng,
                  phase_jitter=1.0)
    tick = filt(rng.normal(0, 1, n), "high", 5000) * env_exp(n, 0.001) * 0.3 * v
    return ((1 - p["glass"]) * fm + p["glass"] * norm(glass) + tick) * attack(n, 0.1)


def tine(p, v, rng):
    """A thumb tine (clamped-bar modes 1, 6.27, 17.55) with a buzz; its shadow is the same note crushed to 8 bits."""
    n = n_of(p["len"])
    t = t_axis(n)
    f = p["note"] * (1 + rng.normal(0, 0.002))
    y = modal(n, [f, f * 6.27, f * 17.55], [1, 0.25 * v, 0.08 * v], [p["decay"], p["decay"] * 0.2, 0.03],
              rng=rng, phase_jitter=0.3)
    buzz = filt(rng.normal(0, 1, n), "band", (2000, 6000)) * np.abs(y) * 0.6 * p.get("buzz", 0.5) * v
    shadow = crush(np.sin(2 * math.pi * f * 2 * t) * env_exp(n, p["decay"] * 0.4), 11025, 6) * p["shadow"] * v
    return (y + buzz + shadow) * attack(n, 0.2)


def clock_tick(p, v, rng):
    """Tick-tock: two tiny wooden clicks a few ms apart (the escapement), one of them sample-crushed."""
    n = n_of(p["len"])
    f = p["tune"] * (1 + rng.normal(0, 0.01))
    one = modal(n, [f, f * 1.62, f * 2.51], [1, 0.6, 0.35], [0.025, 0.018, 0.01], bright=0.5 + 0.5 * v)
    gap = int(R * (p.get("gap", 0.006) + rng.uniform(-0.0008, 0.0008)))
    two = np.roll(crush(one, 8000, 7), gap) * 0.55
    two[:gap] = 0
    return (one + two) * attack(n, 0.05)


def shaker(p, v, rng):
    """Bead grains under a push or pull envelope; a share of the grains are single sample-held pulses at 11 kHz
    (digital beads). `shape` 0 is a push (fast in), 1 a pull (swells then cuts)."""
    n = n_of(p["len"])
    t = t_axis(n)
    peak_t = 0.012 + p["shape"] * 0.05
    e = np.where(t < peak_t, (t / peak_t) ** (1 + 2 * p["shape"]), np.exp(-(t - peak_t) / p["decay"]))
    rate = p.get("density", 2500) * (0.5 + 0.5 * v)
    grains = (rng.random(n) < rate / R).astype(float) * rng.choice([-1, 1], n) * rng.uniform(0.3, 1, n)
    lo, hi = p["band"][0] * (0.7 + 0.3 * v), p["band"][1] * (0.55 + 0.45 * v)  # a soft stroke: duller beads
    beads = filt(filt(grains, "band", (lo, hi), 2), "high", 2500)
    digital = crush(grains, 6000 + 8000 * v) * 0.8
    digital = filt(digital, "high", 3000)
    y = (1 - p["digital"]) * norm(beads) + p["digital"] * norm(digital)
    return y * e


def shaker_lead(p):
    """Seconds from the pad's start to its loudest point: a pull swells into the beat, so a player starts it early."""
    return 0.012 + p["shape"] * 0.05 - 0.012


def touch_tone(x, v):
    """A soft strike is darker, on every pad: a low-pass that opens with velocity (1.8 kHz at .40, 5.5 kHz at .70,
    fully open at 1.0), with v squared of the dry hit left on top so the body stays. Each voice also darkens itself; this is the
    floor under all of them, so no soft zone comes out brighter than the hard one."""
    if v >= 0.99:
        return x
    dark = filt(x, "low", 600 * 40 ** v, 2)
    return v * v * x + (1 - v * v) * dark


def wood_chirp(p, v, rng):
    """A wooden knock whose pitch sweeps upward (a synthetic chirp riding an organic knock)."""
    n = n_of(p["len"])
    t = t_axis(n)
    f0 = p["tune"] * (1 + rng.normal(0, 0.01))
    f = f0 * (1 + p["chirp"] * (1 - np.exp(-t / 0.02)))
    chirp = np.sin(sweep_phase(f)) * env_exp(n, p["decay"])
    knock = modal(n, [f0 * 0.5, f0 * 1.31, f0 * 2.2], [1, 0.5, 0.3], [0.06, 0.03, 0.02], bright=0.4 + 0.6 * v)
    return (0.55 * chirp + 0.45 * norm(knock)) * attack(n, 0.1)


FORMANTS = {"o": (450, 800, 2830), "a": (730, 1090, 2440), "u": (325, 700, 2530), "e": (530, 1840, 2480)}


def breath(p, v, rng):
    """A breath or half-voiced syllable: noise and a glottal buzz through three formants, ring-modulated a
    little (the synthetic edge). No words, no real voice."""
    n = n_of(p["len"])
    t = t_axis(n)
    f0 = p["pitch"] * (1 + rng.normal(0, 0.02)) * (1 - 0.08 * t / max(t[-1], 1e-3))
    buzz = signal.sawtooth(sweep_phase(f0)) * p["voiced"]
    src = buzz + (1 - p["voiced"]) * rng.normal(0, 1, n)
    y = np.zeros(n)
    for k, fc in enumerate(FORMANTS[p["vowel"]]):
        y += signal.sosfilt(signal.butter(2, [fc * 0.88, fc * 1.12], "bandpass", fs=R, output="sos"), src) / (k + 1)
    y *= 1 - p["ring"] + p["ring"] * np.sin(2 * math.pi * p.get("ring_hz", 290) * t)
    e = np.minimum(t / 0.008, 1) * env_exp(n, p["decay"] * (0.8 + 0.2 * v))
    return y * e


def water_drop(p, v, rng):
    """A droplet: a sine whose pitch rises fast (the synthetic part), over a tiny splash of noise."""
    n = n_of(p["len"])
    t = t_axis(n)
    f0 = p["tune"] * (1 + rng.normal(0, 0.02))
    f = f0 * np.exp(np.minimum(t, 0.05) * p["rise"])
    y = np.sin(sweep_phase(f)) * env_exp(n, p["decay"])
    splash = filt(rng.normal(0, 1, n), "band", (2500, 8000)) * env_exp(n, 0.004) * 0.2 * v
    return (y + splash) * attack(n, 0.3)


def rod(p, v, rng):
    """A metal rod rubbed, or the wind through a pipe: noise into a tuned comb that rings, slowly swelling,
    with an FM shimmer above it. The texture pad."""
    n = n_of(p["len"])
    t = t_axis(n)
    f = p["note"]
    d = int(R / f)
    x = filt(rng.normal(0, 1, n), "band", (f, 9000)) * (np.minimum(t / p["swell"], 1) * env_exp(n, p["decay"]))
    y = signal.lfilter([1], np.concatenate([[1], np.zeros(d - 1), [-0.985]]), x)
    shimmer = np.sin(2 * math.pi * f * 4 * t + 2 * np.sin(2 * math.pi * f * 4 * 1.41 * t)) * 0.15 * v
    shimmer *= np.minimum(t / p["swell"], 1) * env_exp(n, p["decay"] * 0.7)
    return norm(y) + shimmer


def hand_drum(p, v, rng):
    """A hand drum (membrane modes) with a synthetic sub-drop under it so it sits with the kick."""
    n = n_of(p["len"])
    t = t_axis(n)
    f = p["tune"] * (1 + rng.normal(0, 0.006))
    skin = modal(n, [f * r for r in (1, 1.594, 2.136, 2.296, 2.653, 2.918)], [1, 0.7, 0.5, 0.4, 0.3, 0.25],
                 [p["decay"], p["decay"] * 0.7, 0.12, 0.1, 0.07, 0.05], bright=0.15 + 0.85 * v, rng=rng,
                 phase_jitter=0.8)
    slap = filt(rng.normal(0, 1, n), "band", (1500, 5000)) * env_exp(n, 0.006) * p["slap"] * v * v
    sub = np.sin(sweep_phase(f * 0.5 * (1 + 0.5 * np.exp(-t / 0.02)))) * env_exp(n, p["decay"] * 0.8) * p["sub"]
    # a soft palm is more thump and less skin: the skin and slap grow with the strike, the sub does not
    return (norm(skin) * (0.55 + 0.45 * v) + slap + sub * (1.3 - 0.3 * v)) * attack(n, 0.2)


def tambourine(p, v, rng):
    """Ten jingle pairs (high inharmonic modes) each landing a few ms apart, over a frame knock."""
    n = n_of(p["len"])
    y = np.zeros(n)
    for k in range(10):
        f = rng.uniform(6000, 7000 + 4000 * v)  # a soft shake rings fewer of the highest jingles
        j = modal(n, [f, f * 1.37, f * 1.82], [1, 0.6, 0.4], [p["decay"], p["decay"] * 0.7, p["decay"] * 0.5])
        at = int(R * rng.uniform(0, 0.012 + 0.01 * (1 - v)))
        y += np.roll(j, at) * rng.uniform(0.4, 1.0)
    frame = modal(n, [330, 520, 910], [1, 0.5, 0.3], [0.04, 0.03, 0.02]) * 0.3
    return (norm(y) * (0.6 + 0.4 * v) + frame) * attack(n, 0.1)


def rim(p, v, rng):
    """A rim knock: short stick-on-wood modes plus a click."""
    n = n_of(p["len"])
    f = p["tune"] * (1 + rng.normal(0, 0.008))
    y = modal(n, [f, f * 3.3, f * 6.4], [1, 0.6, 0.3], [0.05, 0.025, 0.012], bright=0.25 + 0.75 * v, rng=rng,
              phase_jitter=0.6)
    click = filt(rng.normal(0, 1, n), "high", 3000) * env_exp(n, 0.0012) * 0.5 * v * v
    return (norm(y) + click) * attack(n, 0.05)


def metal_tick(p, v, rng):
    """A short FM ping, the most synthetic hit in the set."""
    n = n_of(p["len"])
    t = t_axis(n)
    f = p["tune"] * (1 + rng.normal(0, 0.004))
    # each take its own strike: the FM depth and the ring vary a little, and a tiny noise edge differs every time
    i = (1 + 4 * v) * (1 + rng.normal(0, 0.08)) * np.exp(-t / 0.01)
    decay = p["decay"] * (1 + rng.normal(0, 0.06))
    edge = filt(rng.normal(0, 1, n), "high", 4000) * env_exp(n, 0.001) * 0.05 * v
    tone = np.sin(2 * math.pi * f * t + i * np.sin(2 * math.pi * f * 1.4 * t)) * env_exp(n, decay)
    return (tone + edge) * attack(n, 0.05)


def zap(p, v, rng):
    """A falling sine (the synthetic drop), driven so it reads on a phone."""
    n = n_of(p["len"])
    t = t_axis(n)
    f_start = p["f_start"] * (0.7 + 0.3 * v) * (1 + rng.normal(0, 0.03))
    f = p["f_end"] + (f_start - p["f_end"]) * np.exp(-t / (p["sweep"] * (1 + rng.normal(0, 0.08))))
    grit = filt(rng.normal(0, 1, n), "band", (1000, 4000)) * env_exp(n, 0.01) * 0.08 * v  # each take its own edge
    return drive((np.sin(sweep_phase(f)) + grit) * env_exp(n, p["decay"]) * attack(n, 0.1), 0.4)


def snap(p, v, rng):
    """A finger snap: a short bright burst with the fingers' own little resonance (organic); a share of it
    sample-crushed (the synthetic grain)."""
    n = n_of(p["len"])
    burst = filt(rng.normal(0, 1, n), "band", (1800 * (0.8 + 0.2 * v), 4500 * (0.6 + 0.4 * v))) * env_exp(n, 0.004)
    f = 1150 * (1 + rng.normal(0, 0.02))
    flesh = modal(n, [f, f * 2.2], [1, 0.4], [0.03, 0.015], bright=0.4 + 0.6 * v, rng=rng, phase_jitter=1.0) * 0.5
    y = burst + flesh
    return ((1 - p["digital"]) * y + p["digital"] * crush(y, 9000, 8)) * attack(n, 0.05)


def scrape(p, v, rng):
    """A guiro-style scrape: a train of tiny wooden clicks, fast then slowing as the stick runs out of ridges.
    Every third click is sample-crushed, so the scrape has a synthetic grain running through it."""
    n = n_of(p["len"])
    y = np.zeros(n)
    t, k = 0.0, 0
    rate = p["rate"] * (0.8 + 0.2 * v) * (1 + rng.normal(0, 0.05))
    one_n = n_of(0.012)
    while t < p["len"] - 0.015:
        f = p["tune"] * (1 + rng.normal(0, 0.04))
        c = modal(one_n, [f, f * 1.7], [1, 0.5], [0.008, 0.005], bright=0.4 + 0.6 * v)
        if k % 3 == 2:
            c = crush(c, 7000, 7)
        at = n_of(t)
        y[at: at + one_n] += c[: n - at] * (0.6 + 0.4 * rng.random()) * (1 - 0.5 * t / p["len"])
        t += 1.0 / (rate * (1 - 0.45 * t / p["len"]))
        k += 1
    return y


VOICES = {f.__name__: f for f in (kick, snare, clap, hat, log_drum, glass_bell, tine, clock_tick, shaker, wood_chirp,
                                   breath, water_drop, rod, hand_drum, tambourine, rim, metal_tick, zap, snap, scrape)}


# ------------------------------------------------------------------ wear: each kit's print

def blown_speaker(x, amount):
    """A small speaker pushed past its excursion: asymmetric clipping, a cone that rolls off above 4.5 kHz and
    rattles (envelope-driven buzz) when it is pushed hard. The IRON kit's print."""
    y = drive(x, amount, asym=0.12 * amount)
    env = np.abs(signal.sosfilt(sos("low", 40), np.abs(x)))
    rattle = filt(np.sign(y) * np.maximum(env - 0.35, 0), "band", (900, 3200)) * 0.5 * amount
    y = y + rattle
    y = filt(y, "low", 4500, 2)
    y = filt(y, "high", 32, 2)
    return y


def slam(x, amount):
    """Clean and loud: a soft knee into a hard ceiling. The NEON kit's print."""
    return drive(x * (1 + 1.5 * amount), 0.25 * amount)


def tape(x, amount):
    """Tape pushed: a head bump at 90 Hz, asymmetric saturation (even harmonics), the top rolled off at 10 kHz.
    The ASH kit's print. Wow, flutter, hiss and crackle live on the groove only (`tape_motion`)."""
    y = peaking(x, 90, 2.5 * amount)
    y = drive(y, 0.45 * amount, asym=0.05)
    return filt(y, "low", 10000, 2)


def tape_motion(x44, seed, depth=1.0, crackle_per_s=6):
    """At 44.1 kHz: wow (0.6 Hz) and flutter (7 Hz) as a moving delay, hiss at about -62 dB, sparse crackle.
    A record has the same slow wow (a warp at 0.55 Hz is one turn at 33 rpm) and more crackle."""
    rng = np.random.default_rng(seed)
    n = len(x44)
    t = np.arange(n) / RATE
    delay = (0.0009 * np.sin(2 * math.pi * 0.6 * t) + 0.00012 * np.sin(2 * math.pi * 7 * t)) * depth * RATE
    idx = np.clip(np.arange(n) - 60 - delay, 0, n - 1)
    y = np.interp(idx, np.arange(n), x44)
    hiss = filt_44(rng.normal(0, 1, n), "band", (1500, 9000)) * 10 ** (-62 / 20) * depth
    crackle = (rng.random(n) < crackle_per_s / RATE) * rng.uniform(-1, 1, n) * 0.05 * depth
    crackle = filt_44(crackle, "high", 2000)
    return y + hiss + crackle


def filt_44(x, kind, hz, order=2):
    return signal.sosfilt(sos(kind, hz, order, RATE), x)


def sampler(x, amount):
    """An old sampler: 12 bits, held at about 26 kHz with no filter before the hold, so the images fold back down
    as grit (the KILN kit's print), a little drive, and the top eased off at 13 kHz. The hold runs at the 4x rate,
    so its step is a whole number of internal samples (29.4 kHz here): close to the classic rate, not exact."""
    held = crush(x, 26040, 12)
    y = (1 - amount) * x + amount * held
    y = drive(y, 0.25 * amount)
    return filt(y, "low", 13000, 2)


PRINTS = {"blown": blown_speaker, "slam": slam, "tape": tape, "sampler": sampler}
