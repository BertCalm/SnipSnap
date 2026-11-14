"""SCUFF round 0b: a dry, mid-forward Michigan drum kit and the chain that makes it.

Python harness, not Kotlin. Nothing here has been heard by its author; every constant marked
"shape" is a starting value for a listening pass, not a measurement. The brief is the owner's
Detroit/Flint drum description (docs/SCUFF.md); the numbers in it are the design targets.

Round 0b (2026-11-12) answers the owner's note on round 0, "more character in general".
Measured, round 0's ten pads were far apart from each other (the closest pair 18 dB), so the
blandness was inside each hit and in the chain: BARK moved every voice by under 1 dB (a
unity-gain band-pass blended at 17 percent is inaudible). Round 0b gives every voice one more
mechanism (a knock, a crack, a harmonic path, a woody body, a tip, a second modulator, a
mechanical click, a slap) and makes BARK a resonant boost that also narrows the dry path,
LOCKER a hotter send with a soft clip in its loop, SLAM more asymmetric, and every pad
bone-dry by a hard tail gate. Round 0 renders stay reachable from git for the A/B.

Ten voices and one chain. Every pad goes:

    voice (4x) ─► band-limit, decimate ─► GRIT ─► LOCKER ─► BARK ─► up 4x ─► SLAM ─► tail gate, finish
                                          crush   comb     cocked   asymmetric clip
                                          (some   slap     band-    + hard ceiling
                                          voices) (some)   pass

Why each stage sits where it does:
  * Voices run at 4x (176.4 kHz) because the kick's and the bass hit's saturation, the rim's
    overdrive and the hat's square cluster all make harmonics that would alias at 44.1 kHz.
  * GRIT (sample-and-hold plus quantisation) runs at 1x on purpose: the aliasing is the sound,
    exactly as CRUNCH does it. It is skipped on the kick, bass, bell, zap and pluck, as the
    brief asks, so the sub stays clean.
  * LOCKER and BARK run at 1x. Both are parallel sends, so at 0 the dry path is untouched.
  * SLAM is the one nonlinear stage after the voice, so it goes back up to 4x before clipping
    and is band-limited before the rate drops (the VALVE and FORGE rule).

Two more effects live outside the chain, as the rack would carry them: DROP (a tape-stop read)
and CHOKE (a dense burst into a hard gate). They are rendered on single pads in the audition.

Run:  python3 scuff_proto.py OUT_DIR   (writes one WAV per voice at the defaults)
"""
from __future__ import annotations

import math
import os
import sys

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", ".claude", "skills", "luthier", "scripts"))
import snipkit as sk  # noqa: E402

RATE = sk.RATE          # 44 100, the MPC rate
FS = sk.INTERNAL        # 176 400, the rate voices synthesise at

VOICES = ("KICK", "CLAP", "BOOM", "RIM", "HAT", "BELL", "ZAP", "TICK", "PLUCK", "INHALE")
CORE = VOICES[:5]
RARE = VOICES[5:]

# The engine macros: TUNE and DECAY per voice as THUMP has them, and four chain macros that
# every pad shares. All 0..1. Chain macros at 0 are exact bypasses.
# Round 0b defaults sit higher than round 0's (SLAM .55, GRIT .45, BARK .35, LOCKER .3): the
# chain was measured as barely there. "shape"
DEFAULTS = {"TUNE": 0.5, "DECAY": 0.5, "SLAM": 0.65, "GRIT": 0.55, "BARK": 0.5, "LOCKER": 0.45}

# Which voices take which chain stage, from the brief: crush the claps, rims and hats, not the
# sub; the comb goes on the rim, clap and pluck (the "small metal locker"); BARK on the mid
# percussion; SLAM on everything (it is the bus).
CHAIN = {
    "KICK":   dict(grit=False, locker=False, bark=False),
    "CLAP":   dict(grit=True,  locker=True,  bark=True),
    "BOOM":   dict(grit=False, locker=False, bark=False),
    "RIM":    dict(grit=True,  locker=True,  bark=True),
    "HAT":    dict(grit=True,  locker=False, bark=False),
    "BELL":   dict(grit=False, locker=False, bark=True),
    "ZAP":    dict(grit=False, locker=False, bark=False),
    "TICK":   dict(grit=False, locker=False, bark=False),
    "PLUCK":  dict(grit=False, locker=True,  bark=True),
    "INHALE": dict(grit=False, locker=False, bark=False),
}
# Comb delay per voice, ms: the locker's size. Shorter is a smaller box. "shape"
LOCKER_MS = {"CLAP": 19.0, "RIM": 11.0, "PLUCK": 24.0}
# The tail gate: a pad ends when its envelope is this far down, over 2 ms. Bone dry. "shape"
TAIL_GATE_DB = -40.0
# The take dimensions use a 4-position Latin square so no two takes share a position on any
# dimension (the ETCH round-robin lesson: small independent wobbles read as one strike).
_STRATA = (-0.75, -0.25, 0.25, 0.75)
_PERMS = {"a": (0, 1, 2, 3), "b": (2, 0, 3, 1), "c": (1, 3, 0, 2), "d": (3, 2, 1, 0)}


def lin(x, lo, hi):
    return lo + (hi - lo) * min(1.0, max(0.0, x))


def expmap(x, lo, hi):
    return lo * (hi / lo) ** min(1.0, max(0.0, x))


def take_params(seed):
    """Four audible dimensions per take, dealt out so no two of four takes share a position.
    Seed 0 is the plain render. `rng` is the take's own noise source."""
    rng = np.random.default_rng(sk.seed_for("SCUFF", seed) & 0xFFFFFFFF)
    if not seed:
        return dict(a=0.0, b=0.0, c=0.0, d=0.0, rng=rng, phase=0.0)
    k = (seed - 1) % 4
    jit = lambda: (float(rng.random()) - 0.5) * 0.3  # noqa: E731  stays inside the stratum
    return dict(a=_STRATA[_PERMS["a"][k]] + jit(), b=_STRATA[_PERMS["b"][k]] + jit(),
                c=_STRATA[_PERMS["c"][k]] + jit(), d=_STRATA[_PERMS["d"][k]] + jit(),
                rng=rng, phase=float(rng.random()))


# ----------------------------------------------------------------------------- small tools

def t_axis(seconds):
    return np.arange(int(seconds * FS)) / FS


def exp_env(t, t60, hold=0.0):
    """A -60 dB decay in t60 seconds, after an optional hold."""
    return np.exp(-6.9078 * np.maximum(t - hold, 0.0) / t60)


def squared_tail(t, t60, plateau=1.6):
    """The genre's squared-off decay: the exponential is lifted and clipped, so the body sits
    at full level and then falls, instead of starting to fall at once. "shape" """
    return np.minimum(1.0, plateau * exp_env(t, t60))


def butter(x, hz, order, kind, fs):
    sos = signal.butter(order, hz / (fs / 2), btype=kind, output="sos")
    return signal.sosfilt(sos, x)


def peak_filter(x, hz, q, fs, gain_db=None):
    """A unity-peak band-pass (scipy's iirpeak). With gain_db it is a parallel boost."""
    b, a = signal.iirpeak(hz / (fs / 2), q)
    y = signal.lfilter(b, a, x)
    if gain_db is None:
        return y
    return x + (10 ** (gain_db / 20) - 1.0) * y


def resonant_lp(x, hz, q, fs):
    """A two-pole resonant low-pass (RBJ), the rubbery bounce of a resonant filter near the
    body of a kick."""
    w0 = 2 * math.pi * hz / fs
    alpha = math.sin(w0) / (2 * q)
    cw = math.cos(w0)
    b = [(1 - cw) / 2, 1 - cw, (1 - cw) / 2]
    a = [1 + alpha, -2 * cw, 1 - alpha]
    return signal.lfilter(np.array(b) / a[0], np.array(a) / a[0], x)


def asym_clip(x, drive, bias):
    """Asymmetric soft clip: a DC offset before tanh, the offset's own output subtracted
    after, so even harmonics appear and no DC leaves. The brief's 'trunk rattle' shaper."""
    return np.tanh(drive * (x + bias)) - math.tanh(drive * bias)


def cubic_clip(x):
    """A cubic polynomial clipper: soft up to +-1.5 (where it reaches 1), hard beyond."""
    s = np.clip(x, -1.5, 1.5)
    return s - s ** 3 / 6.75


def phase_of(hz_curve):
    return np.cumsum(2.0 * math.pi * hz_curve / FS)


def noise(rng, n):
    return rng.uniform(-1.0, 1.0, n)


def to_output_rate(raw):
    return sk.decimate4(sk.band_limit(raw, FS))


def up4(y):
    return signal.resample_poly(y, 4, 1, window=("kaiser", 8.0))


def tail_gate(y, floor_db=TAIL_GATE_DB, fade_ms=2.0):
    """End the pad where its 5 ms envelope last falls under `floor_db` of its peak, with a
    short fade: the genre's abrupt, dry ending. A pad that never falls that far is left whole."""
    n = int(0.005 * RATE)
    env = np.sqrt(np.convolve(y * y, np.ones(n) / n, mode="same"))
    thr = env.max() * 10 ** (floor_db / 20)
    above = np.where(env > thr)[0]
    if len(above) == 0:
        return y
    end = min(len(y), int(above[-1]) + int(fade_ms * 1e-3 * RATE))
    out = y[:end].copy()
    f = int(fade_ms * 1e-3 * RATE)
    if f < end:
        out[-f:] *= np.linspace(1.0, 0.0, f)
    return out


# ----------------------------------------------------------------------------- the voices
# Every voice takes (m, vel, take) and returns audio at FS, peak about 1.

def kick(m, vel, tk):
    # Punch kick: 80 to 160 Hz body, a 250 -> 95 Hz drop in about 20 ms, a 3 kHz click,
    # 120 to 180 ms squared-off decay, no sub below 45 Hz.
    # Round 0b: a deeper, slower drop; a 400 Hz knock under the click; a resonant low-pass
    # near the body (the rubbery bounce); a harder, more asymmetric shaper.
    f_end = expmap(m["TUNE"], 70.0, 140.0)                       # 99 Hz at the default
    f_start = f_end * 3.0 * (1.0 + 0.15 * tk["a"]) * (0.45 + 0.55 * vel)  # harder = deeper drop
    tau = 0.009 * (1.0 + 0.35 * tk["b"])                          # 95% of the drop in ~27 ms
    t60 = expmap(m["DECAY"], 0.09, 0.22) * (1.0 + 0.12 * tk["c"]) * (0.8 + 0.2 * vel)  # 141 ms
    t = t_axis(t60 * 1.5 + 0.05)
    n = len(t)
    hz = f_end + (f_start - f_end) * np.exp(-t / tau)
    ph = phase_of(hz)
    body = np.sin(ph)
    tri = (2.0 / math.pi) * np.arcsin(np.sin(ph))
    body = (1.0 - m["SQUARE"]) * body + m["SQUARE"] * tri
    body = body * squared_tail(t, t60, 1.8)
    # The click: 3 kHz band-passed noise, 3 ms, level and brightness with velocity.
    click_delay = int(0.0005 * (0.5 + 0.5 * tk["d"] + 0.5) * FS)
    nz = noise(tk["rng"], n)
    c = peak_filter(nz * exp_env(t, 0.004), 3000.0 * (0.8 + 0.2 * vel), 2.0, FS)
    c = np.roll(c, click_delay) * m["CLICK"] * vel ** 3 * 2.2
    # The knock: a 400 Hz burst, 6 ms, the beater on the head. "shape"
    k = peak_filter(nz * exp_env(t, 0.006), 420.0, 3.0, FS) * (0.4 + 0.6 * vel) * 1.2
    drive = (2.5 + 2.0 * m["SQUARE"]) * (0.5 + 0.5 * vel)          # a soft hit stays rounder
    y = asym_clip(body * (0.6 + 0.4 * vel) + k, drive, 0.25)
    y = resonant_lp(y, f_end * 2.2, 2.2, FS) + c                  # the bounce; the click stays on top
    return butter(y, 45.0, 2, "high", FS)


def clap(m, vel, tk):
    # Flint clap: 3 to 4 micro impulses 8 to 12 ms apart, then a 60 to 90 ms noise burst.
    # High-passed below 250 Hz, a peak at 1.2 to 2 kHz, air from 5 to 10 kHz, a 220 Hz body.
    # Round 0b: sharper taps (3 ms), a narrower louder peak, and a whip crack (a 12 ms
    # downward sine chirp) on the last tap.
    spacing = lin(m["SNAP"], 0.012, 0.008) * (1.0 + 0.12 * tk["a"])
    t60 = expmap(m["DECAY"], 0.05, 0.12) * (1.0 + 0.08 * tk["b"])  # 77 ms at the default
    peak_hz = 1600.0 * 2 ** (0.5 * (m["TUNE"] - 0.5)) * (1.0 + 0.05 * tk["c"]) * (0.85 + 0.25 * vel)
    taps = 4 if vel >= 0.55 else 3
    t60 *= (0.8 + 0.2 * vel)
    t = t_axis(spacing * 4 + t60 * 1.4)
    n = len(t)
    nz = noise(tk["rng"], n)
    env = np.zeros(n)
    for k in range(taps):
        t0 = k * spacing * (1.0 + 0.04 * tk["d"] * (k % 2 * 2 - 1))
        env += np.where(t >= t0, exp_env(t, 0.003, t0), 0.0) * (0.7 + 0.1 * k)
    tail_t0 = (taps - 1) * spacing
    env += np.where(t >= tail_t0, exp_env(t, t60, tail_t0), 0.0) * 0.8
    y = nz * env
    y = butter(y, 250.0, 4, "high", FS)
    y = peak_filter(y, peak_hz, 2.5, FS, gain_db=10.0)
    y = peak_filter(y, 7000.0, 1.0, FS, gain_db=3.0)
    body_hz = 220.0 * 2 ** (m["TUNE"] - 0.5)
    body = (2.0 / math.pi) * np.arcsin(np.sin(phase_of(np.full(n, body_hz)))) * exp_env(t, 0.035)
    # The crack: a sine falling from 2.5 kHz to 900 Hz over 12 ms, on the last tap. "shape"
    tc = np.maximum(t - tail_t0, 0.0)
    crack_hz = 900.0 + 1600.0 * np.exp(-tc / 0.004)
    crack = np.sin(phase_of(crack_hz)) * np.where(t >= tail_t0, exp_env(t, 0.012, tail_t0), 0.0)
    y = y + 0.25 * body * vel + 0.6 * crack * vel
    return np.clip(y * (0.6 + 0.4 * vel) * 1.5, -0.8, 0.8)  # brittle: a hard ceiling in the voice


def boom(m, vel, tk):
    # Staccato bass hit: 50 to 75 Hz, a fast pitch punch, saturated so 100 to 300 Hz carry the
    # note, 250 to 450 ms decay with an abrupt gate, low-passed around 500 Hz.
    # Round 0b: a separate harmonic path (the body high-passed at 90 Hz, clipped hard,
    # band-passed 100 to 400 Hz) mixed under the sub, so the harmonics outlive the drive;
    # the note-off lands while the note is still loud, so the mute is heard.
    f0 = expmap(m["TUNE"], 45.0, 80.0)                            # 60 Hz at the default
    punch = (1.5 + 1.5 * vel) * (1.0 + 0.3 * tk["a"])             # starts punch+1 times f0
    tau = 0.004 * (1.0 + 0.35 * tk["b"])
    t60 = expmap(m["DECAY"], 0.20, 0.50) * (1.0 + 0.10 * tk["c"]) * (0.8 + 0.2 * vel)  # 316 ms
    gate_at = t60 * 0.7
    t = t_axis(gate_at + 0.01)
    hz = f0 * (1.0 + punch * np.exp(-t / tau))
    ph = phase_of(hz) + 0.3 * tk["phase"]
    body = np.sin(ph) * np.minimum(1.0, t / 0.001) * squared_tail(t, t60 * 1.3, 2.5)
    drive = m["DRIVE"] * (0.4 + 0.6 * vel)
    sub = asym_clip(body, 1.0 + 4.0 * drive, 0.15)
    sub = butter(sub, 500.0, 4, "low", FS)
    # The harmonic path: everything above the fundamental, clipped hard, kept to 100 to 400 Hz.
    harm = butter(body, 90.0, 2, "high", FS)
    harm = asym_clip(harm * 4.0, 2.0 + 8.0 * drive, 0.3)
    harm = butter(butter(harm, 100.0, 2, "high", FS), 420.0, 4, "low", FS)
    y = sub + (0.35 + 0.45 * drive) * (0.4 + 0.6 * vel) * harm   # a soft hit saturates less (round 1)
    # The hard note-off: the next note mutes this one. 3 ms, as a sampler's choke would.
    return y * np.clip((gate_at - t) / 0.003, 0.0, 1.0)


def rim(m, vel, tk):
    # Stank rim: a 2 ms bipolar impulse into two high-Q resonators at 850 and 1350 Hz,
    # a 40 to 70 ms ring, overdriven a little.
    # Round 0b: a louder woody click, a third low body resonance near 480 Hz, higher Q so the
    # ring is a pitch, and more overdrive by default.
    f1 = 850.0 * 2 ** (m["TUNE"] - 0.5) * (1.0 + 0.025 * tk["a"])
    f2 = f1 * (1350.0 / 850.0) * (1.0 + 0.04 * tk["b"])
    t60 = expmap(m["DECAY"], 0.035, 0.09) * (1.0 + 0.15 * tk["c"]) * (0.85 + 0.15 * vel)  # 56 ms
    width = 0.002 * (1.0 + 0.4 * tk["d"])
    t = t_axis(t60 * 1.5 + 0.02)
    n = len(t)
    imp = np.where(t < width, np.sin(2 * math.pi * t / width), 0.0)  # one bipolar cycle
    click = np.where(t < 0.0015, noise(tk["rng"], n), 0.0) * exp_env(t, 0.002) * (0.6 + 0.6 * vel)
    y = peak_filter(imp, f1, 26.0, FS) + (0.4 + 0.5 * vel) * peak_filter(imp, f2, 26.0, FS)
    y = y + 0.35 * peak_filter(imp, f1 * 0.56, 12.0, FS)          # the woody body under it
    y = y / (np.max(np.abs(y)) + 1e-9)
    y = y * exp_env(t, t60) + 0.9 * click + 0.5 * imp * exp_env(t, 0.004)
    bite = 1.0 + 8.0 * m["BITE"] * vel * vel
    return np.tanh(bite * y) / math.tanh(bite)


HAT_HZ = (205.3, 304.4, 369.6, 522.7, 540.0, 800.0)  # the classic six-oscillator cluster


def hat(m, vel, tk):
    # Chopper hat: six squares, high-passed at 5 kHz, a ring near 9 kHz, 25 to 45 ms decay.
    # Round robin is real here: the oscillators run free, so every hit catches new phases.
    # Round 0b: a tip (1 ms of 10 kHz noise on the stick contact) and a two-stage decay, a
    # fast first 8 ms then the ring, so the hat says "tst" instead of "sss".
    scale = 2 ** (0.5 * (m["TUNE"] - 0.5))
    t60 = expmap(m["DECAY"], 0.020, 0.060) * (1.0 + 0.08 * tk["c"])  # 35 ms at the default
    t = t_axis(t60 * 1.6 + 0.01)
    n = len(t)
    y = np.zeros(n)
    for i, hz in enumerate(HAT_HZ):
        ph0 = (0.35 * tk["rng"].random() if tk["a"] != 0.0 else 0.0)
        y += np.sign(np.sin(2 * math.pi * (hz * scale * t + ph0)))
    y = butter(y, 5000.0 * (0.8 + 0.2 * vel), 6, "high", FS)
    y = peak_filter(y, 9500.0 * 2 ** (0.3 * (m["TUNE"] - 0.5)), 3.0, FS, gain_db=12.0)
    env = 0.55 * exp_env(t, 0.008) + 0.45 * exp_env(t, t60)       # the two-stage decay
    y = y * env * (0.5 + 0.5 * vel)
    tip = butter(noise(tk["rng"], n), 9000.0, 4, "high", FS) * exp_env(t, 0.0012) * 1.5 * vel
    y = y / (np.max(np.abs(y)) + 1e-9) + tip
    return np.clip(y * 1.3, -0.9, 0.9)  # clean digital clipping


def bell(m, vel, tk):
    # Bando bell: two-operator FM at a 1 : 1.414 ratio, 600 to 900 ms, drifting 12 cents flat.
    # Round 0b: a second modulator at 1 : 2.756 that dies in the first 80 ms (the clang on the
    # strike, then the cold ring), and a brighter index.
    fc = expmap(m["TUNE"], 300.0, 1200.0)
    ratio = 1.414 * (1.0 + 0.01 * tk["b"])
    t60 = expmap(m["DECAY"], 0.5, 1.1) * (0.75 + 0.25 * vel)
    index = lin(m["TONE"], 1.5, 7.0) * (1.0 + 0.25 * tk["a"]) * (0.4 + 0.6 * vel)
    t = t_axis(t60 * 1.2)
    drift = -(12.0 + 3.0 * tk["c"]) / 1200.0 * np.minimum(1.0, t / t60)   # cents, linear over the tail
    fc_t = fc * 2 ** drift
    mod = np.sin(phase_of(fc_t * ratio) + 2 * math.pi * tk["phase"]) * index * exp_env(t, t60 * 0.5)
    clang = np.sin(phase_of(fc_t * 2.756)) * index * 0.8 * exp_env(t, 0.08) * vel
    y = np.sin(phase_of(fc_t) + mod + clang) * np.minimum(1.0, t / 0.001) * exp_env(t, t60)
    return np.tanh(1.6 * y) / math.tanh(1.6)


def zap(m, vel, tk):
    # Laser tom: an exponential sweep from 1.2 kHz to 120 Hz over 80 to 130 ms, lightly clipped.
    # Round 0b: the clip bites hardest at the top of the sweep (the buzz of the start), and a
    # square share by default.
    f_hi = expmap(m["TUNE"], 800.0, 1800.0) * (1.0 + 0.06 * tk["a"]) * (0.6 + 0.4 * vel)
    sweep = expmap(m["DECAY"], 0.08, 0.13) * (1.0 + 0.1 * tk["b"])
    t = t_axis(sweep * 1.4)
    hz = f_hi * (120.0 / f_hi) ** np.minimum(1.0, t / sweep)
    ph = phase_of(hz) + 2 * math.pi * tk["phase"]
    y = (1.0 - m["SHAPE"]) * np.sin(ph) + m["SHAPE"] * (2.0 / math.pi) * np.arcsin(np.sin(ph))
    gain = 1.2 + 2.0 * np.exp(-t / (sweep * 0.3)) * vel            # hardest at the start
    y = np.tanh(gain * y) * exp_env(t, sweep * 1.2) * (0.6 + 0.4 * vel)
    return y / (np.max(np.abs(y)) + 1e-9)


def tick(m, vel, tk):
    # Money counter: 7 kHz+ noise, 10 to 15 ms, cutoff and level following velocity.
    # Round 0b: a 0.8 ms pitched click at 3.6 kHz under the hiss, the mechanism's own tick.
    t60 = expmap(m["DECAY"], 0.008, 0.020) * (0.7 + 0.3 * vel)
    cut = 7000.0 * 2 ** (m["TUNE"] - 0.5) * (1.0 + 0.1 * tk["a"]) * (1.4 - 0.4 * vel)  # soft hits sit higher and thinner; TUNE half an octave (round 1)
    t = t_axis(t60 * 2.0)
    y = noise(tk["rng"], len(t)) * np.minimum(1.0, t / 0.0003) * exp_env(t, t60)
    y = butter(y, cut, 4, "high", FS) * (0.4 + 0.6 * vel)
    click = np.sin(2 * math.pi * 3600.0 * (1.0 + 0.05 * tk["b"]) * t) * exp_env(t, 0.0008) * 0.7
    y = y / (np.max(np.abs(y)) + 1e-9) + click
    return y / (np.max(np.abs(y)) + 1e-9)


def pluck(m, vel, tk):
    # Hollow conga: one sine at 220 to 320 Hz that rises ~30 Hz in 8 ms, then falls ~80 Hz.
    # Round 0b: a slap (the octave, 15 ms) on the contact and more rubber in the shaper.
    f0 = expmap(m["TUNE"], 200.0, 340.0)
    up = 30.0 * (1.0 + 0.2 * tk["a"]) * (0.7 + 0.3 * vel)
    down = 80.0 * (1.0 + 0.2 * tk["b"])
    t60 = expmap(m["DECAY"], 0.08, 0.20) * (1.0 + 0.12 * tk["c"])
    t = t_axis(t60 * 1.3)
    rise = 1.0 - np.exp(-t / 0.0027)
    fall = np.where(t > 0.008, 1.0 - np.exp(-(t - 0.008) / 0.02), 0.0)
    hz = f0 + up * rise - down * fall
    ph = phase_of(hz) + 2 * math.pi * 0.1 * tk["phase"]
    y = np.sin(ph) * np.minimum(1.0, t / 0.001) * exp_env(t, t60)
    slap = np.sin(2 * ph) * exp_env(t, 0.015) * 0.5 * vel
    return np.tanh(2.2 * (y + slap) * (0.7 + 0.3 * vel)) / math.tanh(2.2)


def inhale(m, vel, tk):
    # Suck-back crash: metallic noise rising over 250 to 400 ms, cut at a zero crossing.
    # Round 0b: two ring-modulator carriers (1.37 and 2.21 kHz) so the metal is denser.
    rise = expmap(m["DECAY"], 0.25, 0.40) * (1.0 + 0.1 * tk["b"]) * (1.3 - 0.3 * vel)  # soft = slower
    t = t_axis(rise)
    n = len(t)
    scale = 1.7 * 2 ** (0.5 * (m["TUNE"] - 0.5))
    y = np.zeros(n)
    for hz in HAT_HZ:
        y += np.sign(np.sin(2 * math.pi * (hz * scale * t + tk["rng"].random())))
    y = y * (0.6 * np.sin(2 * math.pi * 1370.0 * t) + 0.4 * np.sin(2 * math.pi * 2210.0 * t))
    y = 0.6 * y + 0.4 * noise(tk["rng"], n)
    y = butter(butter(y, 3000.0 * (1.5 - 0.5 * vel), 2, "high", FS), 12000.0, 2, "low", FS)
    y *= np.exp(6.9078 * (t - rise) / rise) * (0.5 + 0.5 * vel)     # -60 dB up to 0 dB at `rise`
    # The gate: find the last zero crossing and stop there, sample-accurate.
    zc = np.where(np.diff(np.sign(y[-int(0.005 * FS):])) != 0)[0]
    cut = n - int(0.005 * FS) + (int(zc[-1]) + 1 if len(zc) else 0)
    y = y[:cut]
    return y / (np.max(np.abs(y)) + 1e-9)


RENDER = {"KICK": kick, "CLAP": clap, "BOOM": boom, "RIM": rim, "HAT": hat,
          "BELL": bell, "ZAP": zap, "TICK": tick, "PLUCK": pluck, "INHALE": inhale}

# Per-voice extra macros and their defaults ("shape"). TUNE and DECAY come from DEFAULTS.
VOICE_MACROS = {
    "KICK": {"CLICK": 0.6, "SQUARE": 0.5}, "CLAP": {"SNAP": 0.5}, "BOOM": {"DRIVE": 0.65},
    "RIM": {"BITE": 0.65}, "HAT": {}, "BELL": {"TONE": 0.55}, "ZAP": {"SHAPE": 0.5},
    "TICK": {}, "PLUCK": {}, "INHALE": {},
}


def macros_for(voice, macros=None):
    m = {**DEFAULTS, **VOICE_MACROS[voice]}
    m.update(macros or {})
    return m


# ----------------------------------------------------------------------------- the chain (1x)

def grit(y, amount, vel):
    """CRUNCH's move, velocity-aware: a zero-order hold down to 11 kHz and 16 to 9 bit
    quantisation, both deeper on a harder hit. 0 is an exact bypass."""
    if amount <= 0.0:
        return y
    depth = amount * (0.55 + 0.45 * vel)                         # harder hits crunch harder
    bits = lin(depth, 16.0, 9.0)
    hold = expmap(depth, RATE, 11000.0)
    k = np.floor(np.arange(len(y)) * hold / RATE)
    idx = np.searchsorted(k, k)                                   # first sample of each hold
    z = y[idx]
    q = 2.0 ** (bits - 1)
    return np.round(z * q) / q


def _biquad(b, a, x_in, z):
    """One direct-form-II step; z is the two-element state (mutated)."""
    w = x_in - a[1] * z[0] - a[2] * z[1]
    out = b[0] * w + b[1] * z[0] + b[2] * z[1]
    z[1] = z[0]
    z[0] = w
    return out


def locker(y, amount, delay_ms):
    """A feedback comb 8 to 28 ms long with a band-pass (800 Hz to 2.5 kHz) and a soft clip
    inside the loop, as a parallel send. The 'room' is a metal box, not a tail; the clip is
    the box's walls giving. 0 is an exact bypass."""
    if amount <= 0.0:
        return y
    fb = lin(amount, 0.5, 0.74)
    send = lin(amount, 0.0, 0.9)
    d = int(delay_ms * 1e-3 * RATE)
    b, a = signal.iirpeak(1400.0 / (RATE / 2), 1.0)             # ~800 Hz to 2.5 kHz at -3 dB
    n_tail = int(0.20 * RATE)                                     # the comb rings past the hit
    x = np.concatenate([y, np.zeros(n_tail)])
    out = np.zeros(len(x))
    buf = np.zeros(d)
    z = [0.0, 0.0]
    for i in range(len(x)):
        r = buf[i % d]
        v = x[i] + fb * math.tanh(1.5 * _biquad(b, a, r, z)) / 1.5  # the soft clip keeps the loop bounded
        buf[i % d] = v
        out[i] = v
    wet = out - x
    return x + send * wet


def bark(y, amount):
    """The cocked wah: a band-pass of Q 3.5 to 6 parked near 1.1 kHz, boosted up to 18 dB and
    blended in; past 0.3 the dry path also loses its lows and highs, as a parked wah
    does. Round 0 blended a unity-gain band-pass at 17 percent and measured under 1 dB of
    change on every voice. 0 is an exact bypass."""
    if amount <= 0.0:
        return y
    q = lin(amount, 3.5, 6.0)
    boost = 10 ** (lin(amount, 0.0, 18.0) / 20)
    bp = peak_filter(y, 1100.0, q, RATE)
    dry = y
    if amount > 0.3:
        focus = (amount - 0.3) / 0.7
        dry = butter(butter(y, 150.0 + 250.0 * focus, 1, "high", RATE), 9000.0 - 5000.0 * focus, 1, "low", RATE)
    out = dry + boost * bp
    return out * (np.max(np.abs(y)) / (np.max(np.abs(out)) + 1e-9))


def slam(y, amount):
    """The bus clipper. Up to 4x, an asymmetric cubic clip at up to 22 dB of drive, the clipped
    path low-passed at 600 Hz (so the growl is low-mid, not fizz) with a little of its top kept,
    blended with the dry path, then a hard ceiling. 0 is an exact bypass."""
    if amount <= 0.0:
        return y
    x = up4(y)
    drive = expmap(amount, 1.0, 12.0)
    bias = 0.2 * amount                                            # round 0: 0.12
    wet = cubic_clip(drive * (x + bias)) - cubic_clip(np.full(1, drive * bias))[0]
    wet = wet / drive ** 0.5                                      # keep its level in the room
    wet_lp = butter(wet, 600.0, 2, "low", FS)
    wet_hp = wet - wet_lp
    mix = lin(amount, 0.0, 0.85)
    out = (1.0 - mix) * x + mix * (wet_lp + 0.35 * wet_hp)
    out = np.clip(out * (1.0 + 2.0 * amount), -0.98, 0.98)       # the digital ceiling
    return to_output_rate(out)


def finish(y, peak=0.89):
    """Peak-matched, not loudness-matched: a kit's hits are compared at one peak."""
    y = sk.dc_block(y, RATE)
    y = y * (peak / (np.max(np.abs(y)) + 1e-9))
    return sk.fade_tail(y, RATE)


def render_pad(voice, macros=None, vel=1.0, take=0, stages=("grit", "locker", "bark", "slam"),
               level=True, gate=True):
    """One pad through the chain, at the output rate. `stages` chooses which chain stages run;
    the voice itself always does. `gate` applies the tail gate."""
    m = macros_for(voice, macros)
    tk = take_params(take * 101 + VOICES.index(voice) * 7 if take else 0)
    raw = RENDER[voice](m, vel, tk)
    y = to_output_rate(raw)
    c = CHAIN[voice]
    if "grit" in stages and c["grit"]:
        y = grit(y, m["GRIT"], vel)
    if "locker" in stages and c["locker"]:
        y = locker(y, m["LOCKER"], LOCKER_MS[voice])
    if "bark" in stages and c["bark"]:
        y = bark(y, m["BARK"])
    if "slam" in stages:
        y = slam(y, m["SLAM"])
    if gate and voice != "INHALE":
        y = tail_gate(y)
    return finish(y) if level else y


# ----------------------------------------------------------------------------- rack-side effects

def drop(y, over_s=0.08, bottom=0.5):
    """DROP: the tape stop. The read head slows from 1x to `bottom` over `over_s`, then mutes.
    The pad is read at a variable rate, so its pitch falls with its speed."""
    n_out = int(over_s * RATE)
    speed = 1.0 - (1.0 - bottom) * (np.arange(n_out) / n_out)
    pos = np.cumsum(speed)
    pos = pos[pos < len(y) - 1]
    out = np.interp(pos, np.arange(len(y)), y)
    out *= np.clip((len(out) - np.arange(len(out))) / (0.003 * RATE), 0.0, 1.0)  # the hard mute
    return out


def choke(y, hold_s=0.08, amount=0.8):
    """CHOKE: a dense, short burst (eight combs, two allpasses, slammed) into a downward
    expander keyed by the dry hit: 0 ms attack, `hold_s` hold, 5 ms release."""
    n = len(y) + int(0.25 * RATE)
    x = np.concatenate([y, np.zeros(n - len(y))])
    wet = np.zeros(n)
    for ms, g in ((5.3, .78), (7.9, .77), (11.3, .76), (13.7, .75), (17.3, .74), (19.9, .73), (23.3, .72), (29.1, .71)):
        d = int(ms * 1e-3 * RATE)
        a = np.zeros(d + 1); a[0] = 1.0; a[d] = -g
        wet += signal.lfilter([1.0], a, x)
    for ms in (1.7, 5.3):
        d = int(ms * 1e-3 * RATE)
        b = np.zeros(d + 1); b[0] = -0.6; b[d] = 1.0
        a = np.zeros(d + 1); a[0] = 1.0; a[d] = -0.6
        wet = signal.lfilter(b, a, wet)
    wet = np.tanh(2.5 * wet / (np.max(np.abs(wet)) + 1e-9))      # slammed with input gain
    gate = np.zeros(n)
    onset = int(np.argmax(np.abs(y) > 0.1 * np.max(np.abs(y))))
    close = onset + int(hold_s * RATE)
    gate[onset:close] = 1.0
    rel = int(0.005 * RATE)
    gate[close:close + rel] = np.linspace(1.0, 0.0, rel)
    out = x + amount * wet * gate * 0.6
    return out[:close + rel + int(0.01 * RATE)]


# ----------------------------------------------------------------------------- the pattern

BPM = 118.0  # Detroit sits at 110 to 130; 118 is the middle of the brief's range. "shape"


def pattern_events():
    """Two bars on a straight sixteenth grid, 32 steps. (step, voice, velocity, extra macros).
    Written for this audition in the habits the brief describes: the clap on 2 and 4 with a
    ghost, the bass as short notes with octave jumps, the rim on the e's and a's, straight-eighth
    hats with one 32nd burst, a tick under it all, the bell on bar 1, the zap before bar 2's
    clap, and the suck-back into bar 2."""
    ev = []
    kick_steps = (0, 7, 10, 16, 22, 25)
    clap_steps = {4: 1.0, 12: 1.0, 15: 0.45, 20: 1.0, 28: 1.0, 30: 0.4}
    boom_steps = {0: 0.0, 3: 0.0, 6: 12.0, 10: 0.0, 16: 0.0, 19: 12.0, 22: 0.0, 26: -2.0, 29: 0.0}
    rim_steps = (1, 3, 9, 11, 14, 17, 19, 23, 27, 31)
    for s in kick_steps:
        ev.append((s, "KICK", 1.0, {}))
    for s, v in clap_steps.items():
        ev.append((s, "CLAP", v, {}))
    for s, semis in boom_steps.items():
        ev.append((s, "BOOM", 1.0 if semis <= 0 else 0.85, {"TUNE": 0.5 + semis / 24.0 * 0.5}))
    for s in rim_steps:
        ev.append((s, "RIM", 0.9 if s % 4 == 3 else 0.7, {}))
    for s in range(0, 32, 2):
        if s != 30:
            ev.append((s, "HAT", 0.9 if s % 4 == 0 else 0.7, {}))
    for s in range(32):
        ev.append((s, "TICK", 0.5 if s % 2 else 0.75, {}))
    ev.append((0, "BELL", 1.0, {}))
    ev.append((19, "ZAP", 1.0, {}))
    ev.append((8, "PLUCK", 0.9, {})); ev.append((13, "PLUCK", 0.7, {"TUNE": 0.35}))
    ev.append((24, "PLUCK", 0.9, {})); ev.append((29, "PLUCK", 0.7, {"TUNE": 0.35}))
    return ev


def render_pattern(macros=None, bus_slam=None, bars=2, stages=("grit", "locker", "bark"), drop_hat=True,
                   inhale_in=True, burst=True, take_cycle=4):
    """The two bars: every pad through its own GRIT/LOCKER/BARK, summed, then SLAM on the bus,
    which is where the kick and the bass fuse. Takes cycle per voice (round robin as a kit
    player would deal them). Returns the loop at the output rate, peak-matched."""
    m = {**DEFAULTS, **(macros or {})}
    step = 60.0 / BPM / 4.0
    n = int(step * 16 * bars * RATE) + int(0.6 * RATE)
    mix = np.zeros(n)
    counts = {}
    for s, voice, vel, extra in sorted(pattern_events(), key=lambda e: e[0]):
        if s >= 16 * bars:
            continue
        counts[voice] = counts.get(voice, 0) + 1
        take = counts[voice] % take_cycle + 1
        y = render_pad(voice, {**m, **extra}, vel, take, stages=stages, level=False)
        y = y * (0.89 / (np.max(np.abs(y)) + 1e-9)) * PAD_GAIN[voice] * (0.55 + 0.45 * vel)
        i = int(s * step * RATE)
        mix[i:i + len(y)] += y[:n - i]
    if burst:  # a 32nd burst on the last eighth of bar 1
        for j in range(4):
            y = render_pad("HAT", m, 0.6 + 0.1 * j, j + 1, stages=stages, level=False)
            y = y * (0.89 / (np.max(np.abs(y)) + 1e-9)) * PAD_GAIN["HAT"] * 0.7
            i = int((14 + j * 0.5) * step * RATE)
            mix[i:i + len(y)] += y[:n - i]
    if drop_hat:  # the last hat of bar 2 is a tape-stopped stutter
        y = render_pad("HAT", {**m, "DECAY": 0.9}, 0.9, 1, stages=stages, level=False, gate=False)
        y = drop(y * (0.89 / (np.max(np.abs(y)) + 1e-9)) * PAD_GAIN["HAT"], over_s=0.09)
        i = int(30 * step * RATE)
        mix[i:i + len(y)] += y[:n - i]
    if inhale_in:  # the crash sucked back into bar 2's downbeat
        y = render_pad("INHALE", m, 1.0, 1, stages=stages, level=False)
        y = y * (0.89 / (np.max(np.abs(y)) + 1e-9)) * PAD_GAIN["INHALE"]
        i = int(16 * step * RATE) - len(y)
        mix[i:i + len(y)] += y
    loop_n = int(step * 16 * bars * RATE)
    mix = mix[:loop_n]
    amount = m["SLAM"] if bus_slam is None else bus_slam
    out = slam(mix, amount) if amount > 0 else mix
    out = out[:loop_n]
    return out * (0.95 / (np.max(np.abs(out)) + 1e-9))


# Kit balance, as a producer would set the faders. "shape"
PAD_GAIN = {"KICK": 1.0, "CLAP": 0.8, "BOOM": 0.9, "RIM": 0.55, "HAT": 0.4, "BELL": 0.5,
            "ZAP": 0.6, "TICK": 0.16, "PLUCK": 0.5, "INHALE": 0.5}


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "scuff-out"
    os.makedirs(out, exist_ok=True)
    for v in VOICES:
        y = render_pad(v)
        sk.write_wav(os.path.join(out, f"{v.lower()}.wav"), y)
        print(v, len(y) / RATE, "s", "peak", round(float(np.max(np.abs(y))), 3))
    sk.write_wav(os.path.join(out, "pattern.wav"), render_pattern())
    print("wrote", out)
