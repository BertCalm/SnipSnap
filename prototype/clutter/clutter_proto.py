"""CLUTTER round 0: a found-sound drum kit built from modelled household objects.

Python harness, not Kotlin. Nobody has heard these renders yet. Every constant marked "shape" is
a starting value for a listening pass, not a measurement.

The characteristic gesture is: a hand or a utensil hits an everyday object -> the object's
unruly body rings -> whatever is loose in it or on it rattles a moment later -> the kitchen
answers, then it is still.

Eight objects, one per kit role:

    BUCKET  kick        upturned plastic bucket, palm on the base: clamped plate + trapped air
    BOX     snare       cardboard box with dried rice inside, slapped: panel + flying grains
    BOOK    clap        hardback book slammed shut: pages meeting in quick taps + an air pop
    JAR     closed hat  glass jar of rice, one sharp shake: a grain cloud on a glass wall
    KEYS    open hat    a ring of keys shaken: small free bars colliding
    TABLE   rim/knock   knuckle on a wooden table: wood plate + cutlery that jumps
    POT     perc        saucepan struck on its side: steel ring modes + a chattering lid
    BOTTLE  perc        glass bottle tapped with a key: glass ring + air + rocking on its base

What makes them "found" rather than "drums" is one mechanism they share: the LOOSE PARTS.
Rice in a box, a lid on a pot, a handle on a bucket, cutlery on a table only move once the
struck surface accelerates harder than gravity holds them down. Below that threshold nothing
rattles; above it they fly, land a few tens of milliseconds later and bounce. So a soft hit
is a clean knock and a hard hit is a knock and then a "shhk" or a chatter: velocity changes
what you hear, not only how loud it is.

Signal flow per pad:

    note, velocity, take, macros
        |
        v
    [contact pulse: area = impulse J (velocity), width = TOOL (palm .. metal), Hertz-style
     shortening with velocity] --(force @176.4 kHz)--> [object body: modal bank, strike
     position weights per take] --+
        |                                                                       |
        +--> drive = J against a fixed lift threshold (RATTLE loosens it)       |
                 |                                                              |
                 v                                                              |
          [loose parts: grains (flight 2 v0 / g) or a bouncing part              |
           (v_k+1 = e v_k)] --(impacts)--> their own modes + a small kick back --+--> sum
                                                                                |
                                     band-limit -> decimate -> MIC (phone mic in a kitchen,
                                     a cached impulse response keyed by MIC only) -> DC -> finish

One-way readout: the loose parts are scheduled from the strike, then they re-excite the body.
The body never throws the parts a second time. Disclosed, and it keeps the render a fixed cost.

Run:  python3 clutter_proto.py OUT_DIR   (writes one WAV per voice at the defaults)
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

RATE = sk.RATE          # 44 100, the MPC rate
FS = sk.INTERNAL        # 176 400, the rate the objects ring at
G = 9.81                # gravity, m/s^2: the lift threshold and the flight times use it

VOICES = ("BUCKET", "BOX", "BOOK", "JAR", "KEYS", "TABLE", "POT", "BOTTLE",
          "SUITCASE", "MATCHBOX", "DRAWER", "TUB", "PAN", "MUG", "GLASS", "ZIP")
DRUM_CLASS = {"BUCKET": "KICK", "BOX": "SNARE", "BOOK": "CLAP", "JAR": "HAT_CLOSED",
              "KEYS": "HAT_OPEN", "TABLE": "PERC", "POT": "PERC", "BOTTLE": "PERC",
              "SUITCASE": "KICK", "MATCHBOX": "HAT_CLOSED", "DRAWER": "HAT_OPEN", "TUB": "TOM",
              "PAN": "TOM", "MUG": "PERC", "GLASS": "PERC", "ZIP": "PERC"}
MACROS = ("TUNE", "TOOL", "RATTLE", "DAMP", "MIC")

# What each macro means on each object. TUNE is the object's size (all modes scale together,
# +-7 semitones). DAMP is a hand resting on it. MIC walks a phone from the object to across the
# kitchen. TOOL and RATTLE are read per object, because "what you hit it with" and "what is loose"
# are different things on a jar and on a pot.
MEANING = {
    "BUCKET": {"TOOL": "palm -> drumstick -> metal spoon", "RATTLE": "the wire handle bouncing on the floor"},
    "BOX":    {"TOOL": "palm -> fingertips -> wooden spoon", "RATTLE": "how much rice is inside"},
    "BOOK":   {"TOOL": "paperback -> hardback -> board cover", "RATTLE": "how many pages slap separately"},
    "JAR":    {"TOOL": "what is inside: sand -> rice -> coins", "RATTLE": "how full the jar is"},
    "KEYS":   {"TOOL": "soft grip -> hard shake", "RATTLE": "how many keys are on the ring"},
    "TABLE":  {"TOOL": "knuckle -> spoon handle -> fork", "RATTLE": "cutlery on the table jumping"},
    "POT":    {"TOOL": "palm -> wooden spoon -> metal ladle", "RATTLE": "the lid chattering"},
    "BOTTLE": {"TOOL": "fingertip -> wooden spoon -> a key", "RATTLE": "the bottle rocking on its base"},
    "SUITCASE": {"TOOL": "fist -> heel -> drumstick", "RATTLE": "the two latches hopping"},
    "MATCHBOX": {"TOOL": "a lazy shake -> a sharp flick", "RATTLE": "how full the box is"},
    "DRAWER":   {"TOOL": "pushed shut -> slammed", "RATTLE": "how much cutlery is inside"},
    "TUB":      {"TOOL": "palm -> drumstick -> metal spoon", "RATTLE": "an unclipped corner of the lid chattering"},
    "PAN":      {"TOOL": "palm -> wooden spoon -> metal ladle", "RATTLE": "the loose rivet in the handle buzzing"},
    "MUG":      {"TOOL": "fingertip -> pencil -> metal spoon", "RATTLE": "the spoon left in the mug"},
    "GLASS":    {"TOOL": "finger pad -> fingernail -> metal spoon", "RATTLE": "ice cubes inside"},
    "ZIP":      {"TOOL": "how fast you pull: slow rasp -> quick zip", "RATTLE": "the pull tab clinking at the end"},
}

# Per-object defaults. "shape"
DEFAULTS = {
    "BUCKET": {"TUNE": .5, "TOOL": .10, "RATTLE": .40, "DAMP": .30, "MIC": .30},
    "BOX":    {"TUNE": .5, "TOOL": .15, "RATTLE": .55, "DAMP": .25, "MIC": .30},
    "BOOK":   {"TUNE": .5, "TOOL": .45, "RATTLE": .50, "DAMP": .30, "MIC": .40},
    "JAR":    {"TUNE": .5, "TOOL": .35, "RATTLE": .50, "DAMP": .30, "MIC": .25},
    "KEYS":   {"TUNE": .5, "TOOL": .50, "RATTLE": .50, "DAMP": .20, "MIC": .30},
    "TABLE":  {"TUNE": .5, "TOOL": .20, "RATTLE": .40, "DAMP": .30, "MIC": .30},
    "POT":    {"TUNE": .5, "TOOL": .45, "RATTLE": .45, "DAMP": .30, "MIC": .35},
    "BOTTLE": {"TUNE": .5, "TOOL": .75, "RATTLE": .35, "DAMP": .20, "MIC": .30},
    "SUITCASE": {"TUNE": .5, "TOOL": .15, "RATTLE": .40, "DAMP": .30, "MIC": .30},
    "MATCHBOX": {"TUNE": .5, "TOOL": .55, "RATTLE": .55, "DAMP": .30, "MIC": .25},
    "DRAWER":   {"TUNE": .5, "TOOL": .60, "RATTLE": .55, "DAMP": .25, "MIC": .40},
    "TUB":      {"TUNE": .5, "TOOL": .35, "RATTLE": .35, "DAMP": .35, "MIC": .30},
    "PAN":      {"TUNE": .5, "TOOL": .45, "RATTLE": .50, "DAMP": .45, "MIC": .30},
    "MUG":      {"TUNE": .5, "TOOL": .85, "RATTLE": .40, "DAMP": .30, "MIC": .30},
    "GLASS":    {"TUNE": .5, "TOOL": .50, "RATTLE": .40, "DAMP": .15, "MIC": .30},
    "ZIP":      {"TUNE": .5, "TOOL": .55, "RATTLE": .40, "DAMP": .30, "MIC": .25},
}

# Render length per object, seconds, before the MIC tail. "shape"
LENGTH = {"BUCKET": .70, "BOX": .45, "BOOK": .40, "JAR": .32, "KEYS": 1.0, "TABLE": .50,
          "POT": 1.40, "BOTTLE": 1.20, "SUITCASE": .60, "MATCHBOX": .35, "DRAWER": 1.20, "TUB": .60,
          "PAN": 1.40, "MUG": .70, "GLASS": 2.0, "ZIP": .60}

PEAK = 0.89  # every one-shot is peak-matched, as in SCUFF, so no pad wins by being louder

# ---------------------------------------------------------------------------------- helpers


def settled(voice: str, macros: dict | None) -> dict:
    """Clamp to 0..1, drop unknown keys, fall back to the default for missing or non-finite."""
    d = dict(DEFAULTS[voice])
    for k, v in (macros or {}).items():
        if k in d and v is not None and math.isfinite(v):
            d[k] = min(1.0, max(0.0, float(v)))
    return d


def tune_ratio(tune: float) -> float:
    return 2.0 ** ((tune - 0.5) * 14.0 / 12.0)  # +-7 semitones


def damp_factor(damp: float) -> float:
    """How much a hand shortens the ring: 1 at DAMP 0, 0.15 at DAMP 1."""
    return sk.exp_map(damp, 1.0, 0.15)


def smooth(x: float, lo: float, hi: float) -> float:
    t = min(1.0, max(0.0, (x - lo) / (hi - lo)))
    return t * t * (3 - 2 * t)


def rng_for(*parts) -> np.random.Generator:
    """Seeded generator. Python harness only: the Kotlin port uses Dsp.Noise(Dsp.seedFor(...))."""
    return np.random.default_rng(sk.seed_for("CLUTTER", *parts) & 0xFFFFFFFF)


def contact(n: int, at: float, width_s: float, area: float) -> np.ndarray:
    """A raised-cosine force pulse whose AREA is the impulse delivered. A wide palm and a narrow
    spoon delivering the same impulse ring the low modes the same; only the narrow one reaches
    the high modes. That is what makes TOOL a brightness control with a physical reason."""
    buf = np.zeros(n)
    w = max(2, int(round(width_s * FS)))
    s = int(at * FS)
    p = 0.5 * (1 - np.cos(2 * np.pi * np.arange(w) / w))
    p *= area / p.sum()
    e = min(n, s + w)
    if s < n:
        buf[s:e] += p[: e - s]
    return buf


def impulses(n: int, times, amps, width_s: float = 0.0) -> np.ndarray:
    """A train of contacts. width 0 = single-sample impulses (filtered later by a kernel)."""
    buf = np.zeros(n)
    for t, a in zip(times, amps):
        i = int(t * FS)
        if 0 <= i < n:
            buf[i] += a
    if width_s <= 0:
        return buf
    # Every contact in the train has the same width: one convolution with a unit-area pulse.
    w = max(2, int(round(width_s * FS)))
    p = 0.5 * (1 - np.cos(2 * np.pi * np.arange(w) / w))
    return np.convolve(buf, p / p.sum())[:n]


def modes(exc: np.ndarray, f0: float, table) -> np.ndarray:
    """Modal bank: (ratio, gain, t60). Gains are compensated so a mode's peak does not depend on
    its frequency (the Kotlin `Modes.ring` is not compensated; the port must peak-normalise each
    body before mixing, as `snipsnap-conventions.md` says)."""
    return sk.modal_ring(exc, f0, table, FS, compensate=True)


def doublets(table, split: float):
    """Real round objects are never perfectly round: each mode is two modes a hair apart, and
    they beat. split 0.0025 = 0.25 percent."""
    out = []
    for r, g, t in table:
        out.append((r * (1 - split), g * 0.5, t))
        out.append((r * (1 + split), g * 0.5, t))
    return out


def bandpass(x: np.ndarray, lo: float, hi: float, order: int = 2) -> np.ndarray:
    sos = signal.butter(order, [lo / (FS / 2), min(hi, FS * 0.45) / (FS / 2)], btype="band", output="sos")
    return signal.sosfilt(sos, x)


def lift(velocity: float, rattle: float, lo_thr: float = 0.15, hi_thr: float = 0.65) -> float:
    """How far the strike's surface acceleration exceeds what holds the loose parts down, 0..1.

    The drive is the impulse (velocity^1.2), compared with a FIXED threshold: never normalised per
    hit, so a soft strike really does leave the rice lying still. RATTLE loosens the parts (lowers
    the threshold) as well as adding more of them. "shape" thresholds."""
    drive = velocity ** 1.2
    thr = sk.lin(rattle, hi_thr, lo_thr)
    return max(0.0, (drive - thr) / (1.0 - thr))


def bounce_train(t0: float, v0: float, e: float, vmin: float = 0.004, limit: int = 24):
    """A part thrown up at v0 m/s lands after 2 v0 / g, comes off at e times the speed, and so on:
    the accelerating chatter of a lid or a dropped fork. Returns (times, speeds)."""
    ts, vs = [], []
    t, v = t0, v0
    for _ in range(limit):
        t += 2 * v / G
        ts.append(t)
        vs.append(v)
        v *= e
        if v < vmin:
            break
    return ts, vs


def utensil_ring(n: int, pulse: np.ndarray, tool: float) -> np.ndarray:
    """A metal spoon or key rings a little on its own when it hits something. Only the top third of
    TOOL reaches it. Free-free bar ratios (1, 2.756, 5.404). "shape" frequency."""
    g = smooth(tool, 0.6, 1.0)
    if g <= 0:
        return np.zeros(n)
    table = [(1.0, 1.0, .07), (2.756, .6, .045), (5.404, .35, .03)]
    return 0.22 * g * modes(pulse, 2900.0, table)


def take_weights(rng: np.random.Generator, count: int) -> np.ndarray:
    """Where on the object the hit lands changes which modes it reaches: weight k by how far that
    spot is from mode k's node lines. A take is a hand landing somewhere else."""
    pos = rng.uniform(0.18, 0.42)
    k = np.arange(1, count + 1)
    phase = rng.uniform(0, 0.25, count)
    return 0.3 + 0.7 * np.abs(np.sin(np.pi * k * pos + phase))


# Thin-ring flexural modes (Rayleigh): f_n proportional to n (n^2 - 1) / sqrt(n^2 + 1), n = 2..6.
# A pot wall and a bottle wall are rings to first order. Normalised to n = 2.
RING = [n * (n * n - 1) / math.sqrt(n * n + 1) for n in range(2, 7)]
RING = [r / RING[0] for r in RING]                                     # 1, 2.83, 5.42, 8.77, 12.9
CLAMPED_PLATE = [1.0, 2.08, 3.41, 3.89, 5.00, 5.95, 7.43]              # clamped circular plate
FREE_PLATE = [1.0, 1.73, 2.33, 3.91, 4.11]                             # free circular plate (a lid)
FREE_BAR = [1.0, 2.756, 5.404]                                         # free-free bar (a key, a fork)

# ---------------------------------------------------------------------------------- objects


def strike_width(tool: float, velocity: float, soft_ms: float, hard_ms: float, jitter: float) -> float:
    """Contact time: TOOL picks soft..hard, and a harder hit shortens it (Hertz contact: t ~ v^-1/5)."""
    return sk.exp_map(tool, soft_ms, hard_ms) * 1e-3 * velocity ** -0.2 * jitter


def bucket(m, vel, take, n):
    rng = rng_for("BUCKET", take)
    tr, df = tune_ratio(m["TUNE"]), damp_factor(m["DAMP"])
    J = vel ** 1.2 * rng.uniform(0.95, 1.05)
    width = strike_width(m["TOOL"], vel, 7.0, 0.35, rng.uniform(0.94, 1.06))
    pulse = contact(n, 0.0, width, J)
    t = np.arange(n) / FS
    # Trapped air under the base: the boom. Plastic stiffens at large displacement, so a hard hit
    # starts the boom higher and it falls back: a kick's pitch drop with a physical reason.
    f_air = 64.0 * tr
    drop = 0.32 * vel ** 1.2
    f = f_air * (1 + drop * np.exp(-t / 0.028))
    phase = 2 * np.pi * np.cumsum(f) / FS
    rise = 1 - np.exp(-t / max(width * 0.6, 2e-4))
    air = J * np.sin(phase) * rise * np.exp(-6.9078 * t / (0.42 * df ** 0.7))
    # The base itself: a clamped plastic plate, lossy.
    w = take_weights(rng, len(CLAMPED_PLATE))
    plate_t = [.13, .085, .06, .05, .04, .035, .03]
    table = [(r, (1.0 / (i + 1) ** 0.6) * w[i], plate_t[i] * df) for i, r in enumerate(CLAMPED_PLATE)]
    plate = modes(pulse, 150.0 * tr, table)
    # The rim slaps the floor on a hard hit: a short wooden-ish knock.
    floor = 0.25 * vel ** 2 * modes(contact(n, 0.0015, 0.0008, J), 340.0 * tr, [(1, 1, .025), (1.9, .6, .018)])
    out = 1.0 * air + 0.55 * plate + floor + utensil_ring(n, pulse, m["TOOL"])
    # RATTLE: the wire handle lying on the floor bounces.
    L = lift(vel, m["RATTLE"])
    if L > 0:
        ts, vs = bounce_train(0.002, 0.11 * L * rng.uniform(0.85, 1.15), 0.45)
        hit = impulses(n, ts, [v / 0.11 for v in vs], 0.00012)
        handle = modes(hit, 2300.0 * rng.uniform(0.95, 1.05), [(r, g, .05) for r, g in zip(FREE_BAR, (1, .5, .25))])
        out += 0.05 * smooth(m["RATTLE"], 0, .6) * handle * J + 0.05 * modes(hit, 150.0 * tr, table[:3]) * J
    return out


def box(m, vel, take, n):
    rng = rng_for("BOX", take)
    tr, df = tune_ratio(m["TUNE"]), damp_factor(m["DAMP"])
    J = vel ** 1.2 * rng.uniform(0.95, 1.05)
    width = strike_width(m["TOOL"], vel, 5.0, 0.4, rng.uniform(0.94, 1.06))
    pulse = contact(n, 0.0, width, J)
    # Top panel, simply supported, 40 x 30 cm: f_mn ~ (m/a)^2 + (n/b)^2. The hand lands at (x, y).
    a, b = 0.40, 0.30
    x, y = rng.uniform(0.25, 0.5), rng.uniform(0.25, 0.5)
    raw = sorted(((mm / a) ** 2 + (nn / b) ** 2, mm, nn) for mm in range(1, 5) for nn in range(1, 5))[:14]
    base = raw[0][0]
    table = []
    for i, (fq, mm, nn) in enumerate(raw):
        r = fq / base
        shape = abs(math.sin(math.pi * mm * x) * math.sin(math.pi * nn * y))
        table.append((r, (0.25 + shape) / (i + 1) ** 0.45, 0.075 * df * r ** -0.5))
    panel = modes(pulse, 185.0 * tr, table)
    cavity = 0.35 * modes(pulse, 430.0 * tr, [(1, 1, .05 * df), (1.6, .4, .03 * df)])
    # Cardboard fibres crush under the hand: a short noisy scuff that follows the contact.
    crush_noise = rng.standard_normal(n) * np.exp(-np.arange(n) / (FS * max(width, 3e-4) * 1.4))
    crush = 0.12 * (0.6 + m["TOOL"]) * J * bandpass(crush_noise, 1200, 9000) / max(1e-9, width * 1e3) ** 0.3
    out = panel + cavity + crush + utensil_ring(n, pulse, m["TOOL"])
    # RATTLE: rice thrown up by the panel lands 5 to 50 ms later and bounces once.
    L = lift(vel, m["RATTLE"])
    if L > 0:
        pool = 900
        launch = rng.uniform(0, 0.004, pool)
        speed = 0.30 * rng.uniform(0.15, 1.0, pool) ** 0.7
        mass = rng.uniform(0.5, 1.0, pool)
        count = int(pool * L * smooth(m["RATTLE"], 0, 0.9))
        ts, amps = [], []
        for i in range(count):
            v = speed[i] * math.sqrt(L)
            t1 = launch[i] + 2 * v / G
            ts += [t1, t1 + 2 * 0.25 * v / G]
            amps += [mass[i] * v, mass[i] * v * 0.25]
        hit = impulses(n, ts, amps)
        kernel = bandpass(rng.standard_normal(int(0.0004 * FS)) * np.hanning(int(0.0004 * FS)), 1800, 7000)
        grains = signal.fftconvolve(hit, kernel)[:n]
        out += 0.6 * grains * J + 0.04 * modes(hit, 185.0 * tr, table[:6]) * J
    return out


def book(m, vel, take, n):
    rng = rng_for("BOOK", take)
    tr, df = tune_ratio(m["TUNE"]), damp_factor(m["DAMP"])
    J = vel ** 1.2 * rng.uniform(0.95, 1.05)
    # The pages meet in a few quick taps; a harder slam closes them faster.
    taps = 2 + int(round(m["RATTLE"] * 4))
    gaps = rng.uniform(2.5, 7.0, 8) * 1e-3 / vel ** 0.5
    times = np.concatenate([[0.0], np.cumsum(gaps[: taps - 1])])
    amps = rng.uniform(0.55, 1.0, 8)[:taps]
    amps[-1] = 1.0  # the cover lands last and hardest
    width = strike_width(m["TOOL"], vel, 1.6, 0.25, 1.0)
    out = np.zeros(n)
    w = take_weights(rng, 4)
    board = [(1.0, w[0], .045 * df), (1.9, .7 * w[1], .035 * df), (3.0, .5 * w[2], .028 * df), (4.4, .35 * w[3], .022 * df)]
    k = int(0.0012 * FS)
    burst = np.zeros(n)
    for t, a in zip(times, amps):
        s = int(t * FS)
        burst[s:s + k] += a * rng.standard_normal(k) * np.hanning(k)
    out += 0.9 * J * bandpass(burst, 700, 4500) / (width * 1e3) ** 0.25
    out += 0.6 * modes(impulses(n, times, amps * J, width), 420.0 * tr, board)
    # The air squeezed out between the covers: a low pop at the last tap.
    pop = np.zeros(n)
    s = int(times[-1] * FS)
    k = int(0.006 * FS)
    pop[s:s + k] = rng.standard_normal(k) * np.exp(-np.arange(k) / (0.0015 * FS))
    out += 0.7 * J * vel * bandpass(pop, 180, 1100)
    return out


def jar(m, vel, take, n):
    rng = rng_for("JAR", take)
    tr, df = tune_ratio(m["TUNE"]), damp_factor(m["DAMP"])
    J = vel ** 1.2 * rng.uniform(0.95, 1.05)
    tool = m["TOOL"]
    # One flick: the contents leave the bottom together and hit the wall spread over a few ms. A
    # harder flick throws them tighter. Finer contents (low TOOL) are more grains, smaller, higher.
    pool = 1600
    per = sk.exp_map(tool, 3.0, 0.12)
    count = int(min(pool, (80 + 520 * m["RATTLE"]) * per * vel ** 0.8))
    spread = 0.016 / vel ** 0.6 * sk.lin(m["DAMP"], 1.0, 0.6)
    ts = 0.0015 + rng.gamma(2.2, spread / 2.2, pool)[:count]
    amps = (rng.uniform(0.3, 1.0, pool) ** 2)[:count] / per ** 0.5
    # Stragglers arrive late because they were thrown slowly: they land softer, never as a lone click.
    amps = amps * np.exp(-np.maximum(0.0, ts - 1.5 * spread) / spread)
    hit = impulses(n, ts, amps)
    kl = max(8, int(sk.exp_map(tool, 0.00012, 0.0006) * FS))
    fc = sk.exp_map(tool, 9500.0, 3400.0)
    kernel = bandpass(rng.standard_normal(kl) * np.hanning(kl), fc / 1.8, fc * 1.6)
    grains = signal.fftconvolve(hit, kernel)[:n]
    w = take_weights(rng, 5)
    wall = modes(hit, 1250.0 * tr, [(r, w[i] / (i + 1) ** 0.5, .12 * df) for i, r in enumerate([1, 2.3, 3.9, 5.6, 7.4])])
    coin = smooth(tool, 0.6, 1.0)
    coins = coin * modes(hit, 4100.0, [(1, 1, .14), (1.78, .6, .09), (2.9, .3, .06)]) if coin > 0 else 0.0
    return (grains * 2.2 + 0.08 * wall + 0.12 * coins) * J


def keys(m, vel, take, n):
    rng = rng_for("KEYS", take)
    tr, df = tune_ratio(m["TUNE"]), damp_factor(m["DAMP"])
    J = vel ** 1.2 * rng.uniform(0.95, 1.05)
    count = 3 + int(round(m["RATTLE"] * 6))
    f_keys = rng.uniform(2400.0, 4800.0, 9)
    t60s = rng.uniform(0.25, 0.6, 9)
    # Collisions: a burst then a thinning rate, by thinning a Poisson process. Fixed pools so a
    # velocity change does not reshuffle which key is which.
    tau = 0.10 * (1 + m["RATTLE"]) * sk.lin(m["DAMP"], 1.0, 0.5)
    rate0 = 380.0 * vel
    cand = np.sort(rng.uniform(0, 0.7, 1400))
    keep = rng.uniform(0, 1, 1400) < (rate0 * np.exp(-cand / tau)) / 380.0
    times = cand[keep]
    pa = rng.integers(0, count, len(cand))[keep]
    pb = rng.integers(0, count, len(cand))[keep]
    av = rng.uniform(0.3, 1.0, len(cand))[keep]
    width = strike_width(m["TOOL"], vel, 0.6, 0.07, 1.0)
    out = np.zeros(n)
    for k in range(count):
        sel = (pa == k) | (pb == k)
        if not sel.any():
            continue
        exc = impulses(n, times[sel], av[sel] * np.exp(-times[sel] / (tau * 1.5)), width)
        table = [(r, g, t60s[k] * df * r ** -0.6) for r, g in zip(FREE_BAR, (1.0, .55, .3))]
        out += modes(exc, f_keys[k] * tr, table)
    ring_exc = impulses(n, times, av * 0.4, width)
    out += 0.4 * modes(ring_exc, 1750.0 * tr, [(1, 1, .3 * df), (2.7, .5, .18 * df)])
    return out * J * 3.0


def table(m, vel, take, n):
    rng = rng_for("TABLE", take)
    tr, df = tune_ratio(m["TUNE"]), damp_factor(m["DAMP"])
    J = vel ** 1.2 * rng.uniform(0.95, 1.05)
    width = strike_width(m["TOOL"], vel, 1.8, 0.15, rng.uniform(0.94, 1.06))
    pulse = contact(n, 0.0, width, J)
    ratios = [1, 1.47, 2.09, 2.56, 3.18, 3.9, 4.6, 5.7, 6.9, 8.3]  # "shape": a dense wooden plate
    w = take_weights(rng, len(ratios))
    wood = [(r, w[i] / (i + 1) ** 0.4, 0.10 * df * r ** -0.8) for i, r in enumerate(ratios)]
    out = modes(pulse, 215.0 * tr, wood) + utensil_ring(n, pulse, m["TOOL"])
    # A knuckle is bone under skin: a dull low thump under the knock.
    out += 0.35 * modes(pulse, 120.0, [(1, 1, .03)])
    # RATTLE: a fork and a spoon on the table jump and land.
    L = lift(vel, m["RATTLE"], 0.2, 0.75)
    if L > 0:
        items = 1 + int(round(m["RATTLE"] * 2))
        for i in range(items):
            ts, vs = bounce_train(rng.uniform(0.001, 0.004), 0.09 * L * rng.uniform(0.85, 1.1), 0.35)
            hit = impulses(n, ts, [v / 0.09 for v in vs], 0.0001)
            f = (1150.0, 1420.0, 1290.0)[i] * rng.uniform(0.97, 1.03)  # the same fork and spoon every take
            out += 0.12 * J * modes(hit, f, [(r, g, .22 * r ** -0.5) for r, g in zip(FREE_BAR, (1, .7, .4))])
            out += 0.06 * J * modes(hit, 215.0 * tr, wood[:4])
    return out


def pot(m, vel, take, n):
    rng = rng_for("POT", take)
    tr, df = tune_ratio(m["TUNE"]), damp_factor(m["DAMP"])
    J = vel ** 1.2 * rng.uniform(0.95, 1.05)
    width = strike_width(m["TOOL"], vel, 4.0, 0.2, rng.uniform(0.94, 1.06))
    pulse = contact(n, 0.0, width, J)
    w = take_weights(rng, len(RING) + 3)
    wall = [(r, w[i] / (i + 1) ** 0.5, 1.5 * df * r ** -0.6) for i, r in enumerate(RING)]
    bottom = [(0.72 * r, 0.5 * w[len(RING) + i] / (i + 1), 0.6 * df) for i, r in enumerate(CLAMPED_PLATE[:3])]
    body_table = doublets(wall + bottom, 0.0025)
    f1 = 410.0 * tr
    out = modes(pulse, f1, body_table) + utensil_ring(n, pulse, m["TOOL"])
    # RATTLE: the lid hops and rocks: each landing is two contacts (one edge, then the other).
    L = lift(vel, m["RATTLE"])
    if L > 0:
        ts, vs = bounce_train(0.0015, 0.13 * L * rng.uniform(0.85, 1.15), 0.55)
        times, amps = [], []
        for t, v in zip(ts, vs):
            gap = rng.uniform(0.0015, 0.004)
            times += [t, t + gap]
            amps += [0.6 * v / 0.13, 0.4 * v / 0.13]
        hit = impulses(n, times, amps, 0.00015)
        lid_w = take_weights(rng, len(FREE_PLATE))
        lid = modes(hit, 640.0 * tr, doublets([(r, lid_w[i] / (i + 1) ** 0.5, .45 * df) for i, r in enumerate(FREE_PLATE)], 0.004))
        out += 0.45 * smooth(m["RATTLE"], 0, .6) * J * lid + 0.10 * J * modes(hit, f1, body_table[:6])
    return out


def bottle(m, vel, take, n):
    rng = rng_for("BOTTLE", take)
    tr, df = tune_ratio(m["TUNE"]), damp_factor(m["DAMP"])
    J = vel ** 1.2 * rng.uniform(0.95, 1.05)
    width = strike_width(m["TOOL"], vel, 2.5, 0.12, rng.uniform(0.94, 1.06))
    pulse = contact(n, 0.0, width, J)
    w = take_weights(rng, len(RING))
    glass = doublets([(r, w[i] / (i + 1) ** 0.4, 0.9 * df * r ** -0.5) for i, r in enumerate(RING)], 0.0015)
    f1 = 1450.0 * tr
    out = modes(pulse, f1, glass) + utensil_ring(n, pulse, m["TOOL"])
    # The air inside (a Helmholtz resonator) is barely moved by a tap on the side: a soft body.
    out += 0.25 * modes(pulse, 118.0 * tr, [(1, 1, .22 * df)])
    # RATTLE: a hard tap tips the bottle; its base lands back on the table, twice or three times.
    L = lift(vel, m["RATTLE"], 0.25, 0.8)
    if L > 0:
        ts, vs = bounce_train(0.003, 0.06 * L * rng.uniform(0.8, 1.2), 0.5, vmin=0.006)
        hit = impulses(n, ts, [v / 0.06 for v in vs], 0.0004)
        rock = smooth(m["RATTLE"], 0, .6)
        out += 0.5 * rock * J * modes(hit, 380.0, [(1, 1, .03), (1.8, .6, .02), (2.9, .3, .015)])
        out += 0.35 * rock * J * modes(hit, f1, glass[:4])
    return out


def suitcase(m, vel, take, n):
    rng = rng_for("SUITCASE", take)
    tr, df = tune_ratio(m["TUNE"]), damp_factor(m["DAMP"])
    J = vel ** 1.2 * rng.uniform(0.95, 1.05)
    width = strike_width(m["TOOL"], vel, 9.0, 0.6, rng.uniform(0.94, 1.06))
    pulse = contact(n, 0.0, width, J)
    t = np.arange(n) / FS
    # A 70 x 45 cm fibreboard lid under fabric: very lossy, so it thuds rather than booms.
    a, b = 0.70, 0.45
    x, y = rng.uniform(0.3, 0.5), rng.uniform(0.3, 0.5)
    raw = sorted(((mm / a) ** 2 + (nn / b) ** 2, mm, nn) for mm in range(1, 4) for nn in range(1, 4))[:8]
    table = [((fq / raw[0][0]), (0.25 + abs(math.sin(math.pi * mm * x) * math.sin(math.pi * nn * y))) / (i + 1) ** 0.5,
              0.11 * df * (fq / raw[0][0]) ** -0.7) for i, (fq, mm, nn) in enumerate(raw)]
    panel = modes(pulse, 78.0 * tr, table)
    # The air inside: a short low thud. Fabric does not stiffen, so no pitch drop (the bucket has one).
    air = J * np.sin(2 * np.pi * 52.0 * tr * t) * (1 - np.exp(-t / max(width * 0.6, 2e-4))) * np.exp(-6.9078 * t / (0.2 * df ** 0.7))
    slap = 0.08 * J * bandpass(rng.standard_normal(n) * np.exp(-t / max(width * 1.2, 3e-4)), 60, 1500)
    out = 0.9 * air + 0.8 * panel + slap
    # RATTLE: the two latches hop.
    L = lift(vel, m["RATTLE"])
    if L > 0:
        for i in range(2):
            ts, vs = bounce_train(rng.uniform(0.0005, 0.002), 0.08 * L * rng.uniform(0.8, 1.2), 0.4)
            hit = impulses(n, ts, [v / 0.08 for v in vs], 0.0001)
            f = (1900.0, 2350.0)[i] * rng.uniform(0.97, 1.03)
            out += 0.03 * smooth(m["RATTLE"], 0, .6) * J * modes(hit, f, [(1, 1, .04), (2.4, .5, .025), (4.1, .3, .015)])
    return out


def matchbox(m, vel, take, n):
    rng = rng_for("MATCHBOX", take)
    tr, df = tune_ratio(m["TUNE"]), damp_factor(m["DAMP"])
    J = vel ** 1.2 * rng.uniform(0.95, 1.05)
    # The matches slide and hit the end of the tray together, then a few slide back.
    pool = 500
    count = int(min(pool, (45 + 180 * m["RATTLE"]) * vel ** 0.7))
    spread = sk.exp_map(m["TOOL"], 0.03, 0.008) / vel ** 0.5 * sk.lin(m["DAMP"], 1.0, 0.6)
    ts = 0.001 + rng.gamma(2.5, spread / 2.5, pool)[:count]
    amps = rng.uniform(0.3, 1.0, pool)[:count] * np.exp(-np.maximum(0.0, ts - 1.5 * spread) / spread)
    back = rng.uniform(0.07, 0.11)
    nb = count // 3
    ts2 = back + rng.gamma(2.5, spread / 2.5, pool)[:nb]
    amps2 = 0.35 * rng.uniform(0.3, 1.0, pool)[:nb]
    hit = impulses(n, np.concatenate([ts, ts2]), np.concatenate([amps, amps2]))
    kl = int(0.0003 * FS)
    kernel = bandpass(rng.standard_normal(kl) * np.hanning(kl), 1500.0 * tr, 6000.0 * tr)
    clicks = signal.fftconvolve(hit, kernel)[:n]
    tray = modes(hit, 880.0 * tr, [(1, 1, .03 * df), (1.7, .6, .02 * df), (2.6, .4, .015 * df)])
    return (2.0 * clicks + 0.10 * tray) * J


def drawer(m, vel, take, n):
    rng = rng_for("DRAWER", take)
    tr, df = tune_ratio(m["TUNE"]), damp_factor(m["DAMP"])
    J = vel ** 1.2 * rng.uniform(0.95, 1.05)
    width = strike_width(m["TOOL"], vel, 6.0, 1.0, rng.uniform(0.94, 1.06))
    pulse = contact(n, 0.0, width, J)
    wood = modes(pulse, 140.0 * tr, [(1, 1, .06 * df), (1.6, .7, .045 * df), (2.5, .5, .03 * df), (3.7, .3, .02 * df)])
    # The cutlery keeps going after the drawer stops: it slides into the front and piles up.
    items = 6 + int(round(m["RATTLE"] * 14))
    f = rng.uniform(650.0, 3600.0, 20)
    t60 = rng.uniform(0.3, 0.8, 20)
    tau = 0.09 * (1 + m["RATTLE"]) * sk.lin(m["DAMP"], 1.0, 0.5)
    start = rng.uniform(0.004, 0.012)
    cand = np.sort(rng.uniform(0, 0.9, 3000))
    # A harder slam (TOOL) throws the cutlery harder: more collisions at first, shorter, brighter contacts.
    slam = sk.lin(m["TOOL"], 0.45, 1.3)
    keep = rng.uniform(0, 1, 3000) < np.minimum(1.0, slam * vel * np.exp(-cand / tau))
    who = rng.integers(0, 20, 3000)[keep]
    av = rng.uniform(0.3, 1.0, 3000)[keep]
    times = cand[keep] + start
    cutlery = np.zeros(n)
    for k in range(items):
        sel = who == k
        if not sel.any():
            continue
        exc = impulses(n, times[sel], av[sel] * np.exp(-times[sel] / (tau * 1.5)), sk.exp_map(m["TOOL"], 0.0005, 0.00008))
        cutlery += modes(exc, f[k] * tr, [(1.0, 1.0, t60[k] * df), (2.756, .6, t60[k] * df * 0.55)])
    return wood + 1.8 * J * cutlery


def tub(m, vel, take, n):
    rng = rng_for("TUB", take)
    tr, df = tune_ratio(m["TUNE"]), damp_factor(m["DAMP"])
    J = vel ** 1.2 * rng.uniform(0.95, 1.05)
    width = strike_width(m["TOOL"], vel, 5.0, 0.3, rng.uniform(0.94, 1.06))
    pulse = contact(n, 0.0, width, J)
    t = np.arange(n) / FS
    # The lid is the drumhead: a 45 x 35 cm plastic rectangle over a box of air.
    a, b = 0.45, 0.35
    x, y = rng.uniform(0.25, 0.5), rng.uniform(0.25, 0.5)
    raw = sorted(((mm / a) ** 2 + (nn / b) ** 2, mm, nn) for mm in range(1, 4) for nn in range(1, 4))[:8]
    table = [((fq / raw[0][0]), (0.25 + abs(math.sin(math.pi * mm * x) * math.sin(math.pi * nn * y))) / (i + 1) ** 0.5,
              0.10 * df * (fq / raw[0][0]) ** -0.6) for i, (fq, mm, nn) in enumerate(raw)]
    lid = modes(pulse, 120.0 * tr, table)
    f = 96.0 * tr * (1 + 0.15 * vel ** 1.2 * np.exp(-t / 0.02))
    air = J * np.sin(2 * np.pi * np.cumsum(f) / FS) * (1 - np.exp(-t / max(width * 0.6, 2e-4))) * np.exp(-6.9078 * t / (0.22 * df ** 0.7))
    out = 0.8 * air + 0.8 * lid + utensil_ring(n, pulse, m["TOOL"])
    # RATTLE: one corner of the lid has come unclipped and chatters.
    L = lift(vel, m["RATTLE"])
    if L > 0:
        ts, vs = bounce_train(0.001, 0.05 * L * rng.uniform(0.85, 1.15), 0.65)
        hit = impulses(n, ts, [v / 0.05 for v in vs], 0.0002)
        out += 0.25 * smooth(m["RATTLE"], 0, .6) * J * modes(hit, 520.0 * tr, [(1, 1, .04), (1.7, .6, .03), (2.6, .4, .02)])
    return out


PAN_GAP = 0.6  # the rivet's play in the body's own units at a full-strength hit: "shape", fixed, never per hit


def pan(m, vel, take, n):
    rng = rng_for("PAN", take)
    tr, df = tune_ratio(m["TUNE"]), damp_factor(m["DAMP"])
    J = vel ** 1.2 * rng.uniform(0.95, 1.05)
    width = strike_width(m["TOOL"], vel, 4.0, 0.25, rng.uniform(0.94, 1.06))
    pulse = contact(n, 0.0, width, J)
    w = take_weights(rng, len(FREE_PLATE))
    plate = doublets([(r, w[i] / (i + 1) ** 0.5, 0.9 * df * r ** -0.5) for i, r in enumerate(FREE_PLATE)], 0.003)
    body = modes(pulse, 230.0 * tr, plate)
    out = body + 0.25 * modes(pulse, 165.0 * tr, [(1, 1, .12 * df)]) + utensil_ring(n, pulse, m["TOOL"])
    # RATTLE: the handle's loose rivet. Each time the pan swings past the rivet's play, the rivet
    # knocks, so it buzzes while the pan rings hard and stops on its own as the ring dies away.
    # A soft hit never reaches the play. This one reads the body (one way); the rest are thrown.
    rattle = m["RATTLE"]
    if rattle > 0:
        gap = PAN_GAP * sk.lin(rattle, 1.6, 0.25)
        up = np.where((body[:-1] < gap) & (body[1:] >= gap))[0]
        if len(up):
            from scipy.ndimage import maximum_filter1d
            env = maximum_filter1d(np.abs(body), size=int(FS / (230.0 * tr)) + 1)
            hit = impulses(n, up / FS, np.maximum(0.0, env[up] - gap), 0.0001)
            out += 0.3 * smooth(rattle, 0, .6) * modes(hit, 3400.0 * rng.uniform(0.97, 1.03), [(1, 1, .02), (1.8, .5, .012)])
    return out


def mug(m, vel, take, n):
    rng = rng_for("MUG", take)
    tr, df = tune_ratio(m["TUNE"]), damp_factor(m["DAMP"])
    J = vel ** 1.2 * rng.uniform(0.95, 1.05)
    width = strike_width(m["TOOL"], vel, 2.0, 0.12, rng.uniform(0.94, 1.06))
    pulse = contact(n, 0.0, width, J)
    w = take_weights(rng, 4)
    # Thick ceramic with a handle: the handle breaks the symmetry, so the pairs split wider than glass.
    glaze = doublets([(r, w[i] / (i + 1) ** 0.4, 0.28 * df * r ** -0.6) for i, r in enumerate(RING[:4])], 0.015)
    f1 = 1100.0 * tr
    out = modes(pulse, f1, glaze) + utensil_ring(n, pulse, m["TOOL"]) + 0.2 * modes(pulse, 300.0 * tr, [(1, 1, .02)])
    # RATTLE: the spoon left in the mug jumps and knocks the rim.
    L = lift(vel, m["RATTLE"])
    if L > 0:
        ts, vs = bounce_train(0.001, 0.05 * L * rng.uniform(0.85, 1.15), 0.45)
        hit = impulses(n, ts, [v / 0.05 for v in vs], 0.0001)
        k = smooth(m["RATTLE"], 0, .6)
        out += 0.15 * k * J * modes(hit, 1800.0, [(r, g, .12) for r, g in zip(FREE_BAR, (1, .5, .3))])
        out += 0.30 * k * J * modes(hit, f1, glaze[:4])
    return out


def glass(m, vel, take, n):
    rng = rng_for("GLASS", take)
    tr, df = tune_ratio(m["TUNE"]), damp_factor(m["DAMP"])
    J = vel ** 1.2 * rng.uniform(0.95, 1.05)
    width = strike_width(m["TOOL"], vel, 3.0, 0.15, rng.uniform(0.94, 1.06))
    pulse = contact(n, 0.0, width, J)
    w = take_weights(rng, 4)
    # Thin, nearly perfect glass: few partials, long, beating slowly.
    ring = doublets([(r, w[i] / (i + 1) ** 0.9, 2.2 * df * r ** -1.0) for i, r in enumerate(RING[:4])], 0.0012)
    f1 = 880.0 * tr
    out = modes(pulse, f1, ring) + utensil_ring(n, pulse, m["TOOL"])
    # RATTLE: ice cubes, nudged by the flick, settle against the wall a few times.
    L = lift(vel, m["RATTLE"], 0.1, 0.6)
    if L > 0:
        cubes = 1 + int(round(m["RATTLE"] * 4))
        times, amps = [], []
        for c in range(cubes):
            t0 = rng.uniform(0.02, 0.25)
            for j in range(rng.integers(1, 4)):
                times.append(t0 + j * rng.uniform(0.012, 0.05))
                amps.append(L * rng.uniform(0.3, 1.0) * 0.6 ** j)
        hit = impulses(n, times, amps, 0.0002)
        kl = int(0.0004 * FS)
        click = signal.fftconvolve(hit, bandpass(rng.standard_normal(kl) * np.hanning(kl), 2000, 8000))[:n]
        k = smooth(m["RATTLE"], 0, .6)
        out += k * J * (0.5 * click + 0.25 * modes(hit, f1, ring[:4]))
    return out


def zipper(m, vel, take, n):
    rng = rng_for("ZIP", take)
    tr, df = tune_ratio(m["TUNE"]), damp_factor(m["DAMP"])
    # A 12 cm pull whose speed rises and falls (a raised sine); a tooth every 2 mm clicks as it passes.
    # TOOL is how fast you pull; TUNE the tooth size (bigger teeth, fewer and lower clicks).
    dur = sk.exp_map(m["TOOL"], 0.32, 0.09) / vel ** 0.3
    pitch = 0.002 / tr
    teeth = int(0.12 / pitch)
    x = (np.arange(1, teeth + 1) * pitch) + rng.normal(0, pitch * 0.06, teeth)
    x = np.clip(x, 0, 0.12 - 1e-6)
    ts = dur / np.pi * np.arccos(1 - 2 * x / 0.12)
    speed = np.sin(np.pi * ts / dur)
    amps = (0.25 + speed) ** 0.7 * vel * rng.uniform(0.7, 1.0, teeth)
    hit = impulses(n, ts, amps)
    kl = int(0.00025 * FS)
    clicks = signal.fftconvolve(hit, bandpass(rng.standard_normal(kl) * np.hanning(kl), 2500.0 * tr, 9000.0 * tr))[:n]
    # The cloth rustles while it moves; a hand holding it taut (DAMP) quietens it.
    t = np.arange(n) / FS
    env = np.where(t < dur, np.sin(np.pi * np.clip(t / dur, 0, 1)), 0.0)
    cloth = bandpass(rng.standard_normal(n), 700, 4000) * env * 0.05 * vel * (1 - 0.7 * m["DAMP"])
    body = modes(hit, 600.0, [(1, 1, .02 * df), (1.9, .5, .015 * df)])
    out = 2.0 * clicks + cloth + 0.15 * body
    # RATTLE: the pull tab clinks against the teeth when the pull stops.
    k = smooth(m["RATTLE"], 0, .6)
    if k > 0:
        tts, vs = bounce_train(dur, 0.04 * vel, 0.4)
        tab = impulses(n, tts, [v / 0.04 for v in vs], 0.0001)
        out += 0.3 * k * modes(tab, 2600.0, [(r, g, .06) for r, g in zip(FREE_BAR, (1, .5, .3))])
    return out


OBJECTS = {"BUCKET": bucket, "BOX": box, "BOOK": book, "JAR": jar, "KEYS": keys,
           "TABLE": table, "POT": pot, "BOTTLE": bottle, "SUITCASE": suitcase, "MATCHBOX": matchbox,
           "DRAWER": drawer, "TUB": tub, "PAN": pan, "MUG": mug, "GLASS": glass, "ZIP": zipper}

# ---------------------------------------------------------------------------------- MIC


@functools.lru_cache(maxsize=64)
def mic_ir(mic_key: int) -> np.ndarray:
    """The phone in the kitchen, as an impulse response at 44.1 kHz. Cached by MIC alone (to 1/1000):
    a knob turn on any other macro never rebuilds it.

    Distance d runs 12 cm .. 2.8 m on a log scale. The direct sound is 1; the walls of a 3.6 x 3.0
    x 2.5 m kitchen (hard: tile, cupboards; 0.85 reflection) answer from image sources, first and
    second order, at d / path and darkened once per bounce; a short diffuse tail (RT60 0.32 s) grows
    as d passes the critical distance (~0.6 m). This is a sketch of the shared `Room` section's
    method, not a copy of it; the Kotlin port should call the real `Room` with a kitchen preset."""
    mic = mic_key / 1000.0
    d = sk.exp_map(mic, 0.12, 2.8)
    room = np.array([3.6, 3.0, 2.5])
    src = np.array([1.3, 1.4, 0.95])
    direction = np.array([0.82, 0.48, 0.31])
    direction /= np.linalg.norm(direction)
    rec = np.clip(src + d * direction, 0.1, room - 0.1)
    d = float(np.linalg.norm(rec - src))
    c = 343.0
    n = int(0.42 * RATE)
    ir1, ir2 = np.zeros(n), np.zeros(n)
    ir0 = np.zeros(n)
    ir0[0] = 1.0
    for nx in range(-2, 3):
        for ny in range(-2, 3):
            for nz in range(-2, 3):
                order = abs(nx) + abs(ny) + abs(nz)
                if order == 0 or order > 2:
                    continue
                # Image of the source after |k| bounces along one axis: k L + x for even k, (k + 1) L - x
                # for odd k (k = 1 is the mirror in the far wall, k = -1 in the near one).
                img = np.empty(3)
                for ax, k in enumerate((nx, ny, nz)):
                    img[ax] = k * room[ax] + src[ax] if k % 2 == 0 else (k + 1) * room[ax] - src[ax]
                path = float(np.linalg.norm(img - rec))
                delay = (path - d) / c
                i = int(round(delay * RATE))
                if 0 < i < n:
                    (ir1 if order == 1 else ir2)[i] += (d / path) * 0.85 ** order
    lp1 = signal.butter(1, 6500 / (RATE / 2), output="sos")
    ir = ir0 + signal.sosfilt(lp1, ir1) + signal.sosfilt(lp1, signal.sosfilt(lp1, ir2))
    rng = np.random.default_rng(sk.seed_for("CLUTTER", "MIC") & 0xFFFFFFFF)
    t = np.arange(n) / RATE
    start = 2 * min(room) / c
    tail = rng.standard_normal(n) * np.exp(-6.9078 * t / 0.32) * (t > start)
    tail = signal.sosfilt(signal.butter(2, 4200 / (RATE / 2), output="sos"), tail)
    tail *= 0.012 * (d / 0.6) ** 1.5
    ir += tail
    return ir


def mic_tone(x: np.ndarray, mic: float) -> np.ndarray:
    """A phone's own voice: close up the proximity effect lifts the lows; across the room the small
    capsule's high-pass, its presence bump near 2.6 kHz and its top roll-off take over."""
    d = sk.exp_map(mic, 0.12, 2.8)
    y = x
    prox = max(0.0, 6.0 * (1 - d / 0.6))  # dB, gone by 60 cm
    if prox > 0:
        y = y + (10 ** (prox / 20) - 1) * signal.sosfilt(signal.butter(2, 180 / (RATE / 2), output="sos"), y)
    hp = sk.lin(mic, 25.0, 170.0)
    lp = sk.exp_map(mic, 19000.0, 8500.0)
    y = signal.sosfilt(signal.butter(2, hp / (RATE / 2), "high", output="sos"), y)
    y = signal.sosfilt(signal.butter(2, lp / (RATE / 2), output="sos"), y)
    pres = sk.lin(mic, 0.0, 3.5)
    if pres > 0:
        b, a = peaking(2600.0, pres, 1.0)
        y = signal.lfilter(b, a, y)
    return y


def peaking(f0: float, gain_db: float, q: float):
    A = 10 ** (gain_db / 40)
    w = 2 * math.pi * f0 / RATE
    al = math.sin(w) / (2 * q)
    b = [1 + al * A, -2 * math.cos(w), 1 - al * A]
    a = [1 + al / A, -2 * math.cos(w), 1 - al / A]
    return np.array(b) / a[0], np.array(a) / a[0]


def dc(x: np.ndarray) -> np.ndarray:
    """Mean out, then the house 20 Hz one-pole high-pass (vectorised form of snipkit.dc_block)."""
    y = x - np.mean(x)
    rc = 1.0 / (2 * math.pi * sk.OUTPUT_DC_HZ)
    alpha = rc / (rc + 1.0 / RATE)
    return signal.lfilter([alpha, -alpha], [1.0, -alpha], y)


# ---------------------------------------------------------------------------------- render


def render(voice: str, macros: dict | None = None, velocity: float = 1.0, take: int = 0,
           length: float | None = None, peak: float | None = PEAK) -> np.ndarray:
    """One hit at 44.1 kHz. velocity 0..1 is the strike (it changes contact time, the boom's pitch
    drop and whether anything rattles); take 0..3 is where the hand lands and how the loose parts
    fall. peak=None returns the unmatched render so a test can see absolute level."""
    m = settled(voice, macros)
    vel = min(1.0, max(0.05, float(velocity) if math.isfinite(velocity) else 1.0))
    secs = length if length is not None else LENGTH[voice]
    n = int(secs * FS)
    raw = OBJECTS[voice](m, vel, int(take), n)
    y = sk.decimate4(sk.band_limit(raw, FS))
    y = mic_tone(y, m["MIC"])
    ir = mic_ir(int(round(m["MIC"] * 1000)))
    y = signal.fftconvolve(y, ir)[: len(y) + int(0.12 * RATE * m["MIC"])]
    y = dc(y)
    fade = int(0.025 * RATE)
    y[-fade:] *= np.linspace(1, 0, fade) ** 2
    if peak is not None:
        p = float(np.max(np.abs(y)))
        if p > 0:
            y = y * (peak / p)
    return y


def main(argv):
    out = argv[1] if len(argv) > 1 else "clutter-out"
    os.makedirs(out, exist_ok=True)
    for v in VOICES:
        sk.write_wav(os.path.join(out, f"{v.lower()}.wav"), render(v))
        print("wrote", v)


if __name__ == "__main__":
    main(sys.argv)
