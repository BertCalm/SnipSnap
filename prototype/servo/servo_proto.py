"""SERVO round 0: machine drums on a weak power rail, for electro, ghetto tech and neurofunk.

Python harness, not Kotlin. Nothing here has been heard by its author; every constant marked
"shape" is a starting value for a listening pass, not a measurement. Design notes: docs/SERVO.md.

The idea in one paragraph. An analog drum machine's voices are oscillators, pinged resonators
and filtered noise, all fed from one power supply. SERVO imagines that supply is too weak for
the job. Every hit draws current, the supply voltage (the "rail") sags a few milliseconds
later, and then it recovers, ringing if it is underdamped. Three things in every voice read
the rail at once:

    pitch     falls while the rail is down      (the electro dive, exaggerated)
    swing     the clip ceiling and the output    (the ghetto tech flat-top, hot against the rail,
              level fall with it                  and the body ducking under its own transient)
    tone      a resonant peak moves with it      (the neurofunk snarl when the rail rings)

One state, three readouts. This is an ANALOGY with a stated law, not a circuit simulation: no
schematic is modelled, and no real machine behaves exactly this way.

The chain, in order (voices synthesise at 4x, 176.4 kHz):

    macros, velocity ─► amplitude envelope ─► LOAD (what the hit draws)
                                                │
                                                ▼
                           RAIL: one resonant 2-pole low-pass of the load   (SAG depth, GROWL damping)
                                                │ r(t), bounded by a tanh
              ┌─────────────────────────────────┼──────────────────────────┐
              ▼ pitch × 2^(-0.5 r)              ▼ tone peak × 2^(-1.6 r)   ▼ swing × (1 - 0.7 r)
    voice oscillators/noise @4x ─► TONE (moving peak or low-pass) ─► HEAT (drive into the ceiling)
              ─► band-limit ─► decimate 4:1 ─► DC block ─► tail fade ─► 44.1 kHz mono

Why each stage sits where it does:
  * The rail is computed FIRST, from the envelope alone, and the audio only reads it (a one-way
    readout). So it is cheap (one biquad over a control signal), deterministic, and it cannot
    feed back: the rail filter is a stable linear low-pass, and its output is bounded by a tanh
    before anything reads it. No limiter is hiding growth because nothing can grow.
  * The rail lags the hit by a few milliseconds, so the first transient always passes on a
    full rail. That is what keeps the punch: the attack is clean, then the body dives and
    squares off. (A compressor with a slow attack does the same job for level; this does it for
    pitch, tone and headroom together.)
  * TONE comes before HEAT so the moving peak is driven into the clipper: the peak decides which
    harmonics the clip makes, which is where a "snarl" comes from. HEAT runs at 4x and is
    band-limited before the rate drops, because a clipper makes harmonics (the VALVE/FORGE rule).
  * In a baked phrase (a roll, a groove bar) every hit loads ONE rail, so a kick makes the hats
    dive and flatten for a moment: a pump that detunes instead of only ducking. One-shot pads
    cannot do that to each other on an MPC, so the shared rail only exists in phrase pads.

Run:  python3 servo_proto.py OUT_DIR   (writes one WAV per voice at the defaults)
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
MODEL_VERSION = 0

VOICES = ("KICK", "SNARE", "CLAP", "HAT", "RIM", "BELL", "TOM", "LASER", "STAB", "CRASH")

# Six macros, all 0..1. SAG at 0 switches the rail off exactly (r = 0 everywhere), which leaves
# a plain machine drum. HEAT at 0 leaves the clipper in its linear region unless the rail sags.
DEFAULTS = {"TUNE": 0.5, "DECAY": 0.5, "ZAP": 0.4, "SAG": 0.45, "GROWL": 0.35, "HEAT": 0.4}

# The rail's three readouts, as depths at r = 1. "shape"
PITCH_OCT = 0.5     # octaves the pitch falls
TONE_OCT = 1.6      # octaves the tone peak falls
CEIL_DROP = 0.7     # share of the headroom lost
R_MAX = 0.85        # the tanh bound on r, so pitch and ceiling can never reach zero
PRE_LEVEL = 0.5     # a full-velocity voice peaks here before HEAT; the clip knee is at 0.5

# DRAW: how much current a voice pulls (its weight on a shared rail). SENS: how deeply a voice
# reads the sag its OWN hit causes. CROSS: how much of the sag caused by OTHER hits reaches it
# (only inside a baked phrase, where every hit loads one rail): the kick is stiffly fed and
# barely notices the hats; the hats' square oscillators follow whatever the kick does. "shape"
DRAW = {"KICK": 1.0, "SNARE": 0.6, "CLAP": 0.45, "HAT": 0.2, "RIM": 0.3, "BELL": 0.35,
        "TOM": 0.7, "LASER": 0.35, "STAB": 0.9, "CRASH": 0.3}
SENS = {"KICK": 1.0, "SNARE": 0.95, "CLAP": 0.9, "HAT": 0.75, "RIM": 0.85, "BELL": 0.85,
        "TOM": 1.0, "LASER": 0.85, "STAB": 0.95, "CRASH": 0.75}
CROSS = {"KICK": 0.3, "SNARE": 0.5, "CLAP": 0.55, "HAT": 0.6, "RIM": 0.6, "BELL": 0.6,
         "TOM": 0.45, "LASER": 0.6, "STAB": 0.4, "CRASH": 0.6}

# The six-square metal cluster's ratios, as THUMP's hat has them (Thump.kt, grepped).
CLUSTER = (1.0, 1.342, 1.681, 1.940, 2.318, 2.703)


# ---------------------------------------------------------------- small tools

def settled(macros: dict | None) -> dict:
    """Clamp to 0..1, drop unknown keys, fall back to the default on a non-finite value."""
    out = dict(DEFAULTS)
    for k, v in (macros or {}).items():
        if k in DEFAULTS and isinstance(v, (int, float)) and math.isfinite(v):
            out[k] = min(1.0, max(0.0, float(v)))
    return out


def _rng(*parts) -> np.random.Generator:
    """Deterministic noise. NumPy's PCG64, not the house LCG (Dsp.Noise): the Kotlin port will
    draw different numbers, so a Kotlin take is a new take of the same drum, not a copy."""
    return np.random.default_rng(sk.seed_for("SERVO", MODEL_VERSION, *parts))


def _env(n: int, t60: float, attack: float = 0.0008) -> np.ndarray:
    t = np.arange(n) / FS
    return (1.0 - np.exp(-t / attack)) * np.exp(-6.908 * t / t60)


def _frames(t60: float, cap: float = 1.45) -> int:
    """Length of a hit: its decay plus a margin, capped under the classifier's 1.5 s LOOP line."""
    return int(min(cap, t60 * 1.15 + 0.03) * FS)


def _blep(t: np.ndarray, dt: np.ndarray) -> np.ndarray:
    """PolyBLEP residual: rounds off a waveform's step so it does not alias (a raw square does)."""
    out = np.zeros_like(t)
    m = t < dt
    x = t[m] / dt[m]
    out[m] = x + x - x * x - 1.0
    m = t > 1.0 - dt
    x = (t[m] - 1.0) / dt[m]
    out[m] = x * x + x + x + 1.0
    return out


def _square(freq: np.ndarray, phase0: float = 0.0) -> np.ndarray:
    dt = freq / FS
    t = (np.cumsum(dt) + phase0) % 1.0
    return np.where(t < 0.5, 1.0, -1.0) + _blep(t, dt) - _blep((t + 0.5) % 1.0, dt)


def _saw(freq: np.ndarray, phase0: float = 0.0) -> np.ndarray:
    dt = freq / FS
    t = (np.cumsum(dt) + phase0) % 1.0
    return 2.0 * t - 1.0 - _blep(t, dt)


def _sine(freq: np.ndarray, phase0: float = 0.0) -> np.ndarray:
    return np.sin(2 * np.pi * (np.cumsum(freq) / FS + phase0))


def _bp(x, hz, q):
    b, a = signal.iirpeak(min(hz, FS * 0.45) / (FS / 2), q)
    return signal.lfilter(b, a, x)


def _hp(x, hz, order=2):
    sos = signal.butter(order, hz / (FS / 2), btype="high", output="sos")
    return signal.sosfilt(sos, x)


def _lp(x, hz, order=2):
    sos = signal.butter(order, min(hz, FS * 0.45) / (FS / 2), output="sos")
    return signal.sosfilt(sos, x)


def _click(n: int, width_ms: float, amp: float) -> np.ndarray:
    """A trigger pulse: one raised-cosine bump, already band-limited by its own width."""
    w = max(8, int(width_ms * 1e-3 * FS))
    buf = np.zeros(n)
    buf[:w] = amp * 0.5 * (1 - np.cos(2 * np.pi * np.arange(w) / w))
    return buf


def _sweep(n: int, amount: float, seconds: float) -> np.ndarray:
    """A pitch envelope: starts `amount` times above the root and lands on it exponentially."""
    t = np.arange(n) / FS
    return 1.0 + amount * np.exp(-t / seconds)


# ---------------------------------------------------------------- the rail

def rail_params(growl: float) -> tuple[float, float]:
    """GROWL sets how the supply recovers. At 0 it is critically damped (zeta 1): a smooth sag
    and return, no ripple. At 1 it rings near 70 Hz for about a quarter of a second. Both the
    ring rate and the damping move, so the knob goes from 'dip' to 'growl'. "shape" """
    hz = sk.exp_map(growl, 26.0, 72.0)
    zeta = sk.exp_map(growl, 1.0, 0.055)
    return hz, zeta


def rail_filter(load: np.ndarray, growl: float) -> np.ndarray:
    """The supply's response to the load: a resonant two-pole low-pass with unity gain at DC.
    Its poles are inside the unit circle for every zeta > 0, so it is stable by construction."""
    hz, zeta = rail_params(growl)
    w0 = 2 * math.pi * hz / FS
    q = 1.0 / (2.0 * zeta)
    alpha = math.sin(w0) / (2 * q)
    c = math.cos(w0)
    b = np.array([(1 - c) / 2, 1 - c, (1 - c) / 2]) / (1 + alpha)
    a = np.array([1.0, -2 * c / (1 + alpha), (1 - alpha) / (1 + alpha)])
    return signal.lfilter(b, a, load)


def rail_read(voice: str, sag: float, own: np.ndarray, others: np.ndarray | None = None) -> np.ndarray:
    """What one voice sees of the rail: the sag its own hit causes plus, in a phrase, a share of
    everyone else's, scaled by SAG and bounded by a tanh so it can never reach the whole rail."""
    seen = SENS[voice] * own
    if others is not None:
        seen = seen + CROSS[voice] * others
    return R_MAX * np.tanh(sk.lin(sag, 0.0, 1.2) * seen / R_MAX)


def _residual(n_pre: int, voice: str, take: int) -> np.ndarray:
    """Round robin, the machine's way. Take 1 starts on a rested rail. Takes 2 to 4 start as if
    another hit landed 30 to 90 ms earlier (a sixteenth or a thirty-second at these tempos), so
    the rail is already down and still moving when this one fires: the same drum caught at a
    different moment of the supply's recovery. Its attack no longer gets a full rail."""
    pre = np.zeros(n_pre)
    if take % 4 == 0:
        return pre
    g = _rng(voice, "residual", take % 4)
    amp = (0.0, 0.55, 0.95, 0.75)[take % 4] * (0.9 + 0.2 * g.random())
    at = n_pre - int((0.03 + 0.06 * g.random()) * FS)
    t = np.arange(n_pre - at) / FS
    pre[at:] = amp * np.exp(-t / 0.07)
    return pre


def drift(voice: str, take: int) -> tuple[float, float]:
    """Analog parts are never quite where they were: each take's tuning sits within about 7 cents
    and its decay within about 5 percent of take 1's. Returned as (pitch ratio, decay ratio)."""
    if take % 4 == 0:
        return 1.0, 1.0
    g = _rng(voice, "drift", take % 4)
    return 2.0 ** ((g.random() - 0.5) * 14.0 / 1200.0), 1.0 + (g.random() - 0.5) * 0.10


TRIGGER = (1.0, 0.76, 1.24, 0.9)   # each take's trigger strength: the four are kept apart on purpose. "shape"


def trigger(voice: str, take: int) -> float:
    """The trigger pulse that fires a voice is not the same strength twice. It sets how hard the
    click is and how far the pitch is thrown at the start. Each take has its own strength (weaker,
    stronger, a little weaker) with a small seeded wobble, so no two takes land on the same one:
    drawn freely, two of a kick's takes came out 0.5 dB apart, which is one strike heard twice."""
    k = take % 4
    return 1.0 if k == 0 else TRIGGER[k] + 0.08 * (_rng(voice, "trigger", k).random() - 0.5)


PRE_ROLL = int(0.13 * FS)
SUPPLY_FLOOR = 1.2   # the most sag piled-up hits can cause, in units of one full kick. "shape"


# ---------------------------------------------------------------- the voices
# Each voice is two functions. plan_X(m, v) returns the amplitude envelope: the rail needs it
# before any audio exists. body_X(m, v, take, env, r) makes the sound, reading the rail r, and
# returns (signal, tone) where tone = (kind, hz, q, gain_db) is the voice's moving TONE stage.

def _decay(m, lo, hi):
    return sk.exp_map(m["DECAY"], lo, hi)


def plan_kick(m, v):
    t60 = _decay(m, 0.12, 1.1)
    return _env(_frames(t60), t60)


def body_kick(m, v, take, env, r):
    n = len(env)
    g = _rng("KICK", take)
    root = sk.exp_map(m["TUNE"], 38.0, 78.0)
    # ZAP: from a 0.6x overshoot in 9 ms (a click of pitch) to 11x in 50 ms (a laser into a boom).
    trig = trigger("KICK", take)
    sweep = _sweep(n, sk.exp_map(m["ZAP"], 0.6, 11.0) * trig, sk.exp_map(m["ZAP"], 0.009, 0.05))
    f = root * sweep * 2.0 ** (-PITCH_OCT * r)
    x = _sine(f, 0.02 * g.random()) * env
    x += _click(n, 0.35 * (0.9 + 0.2 * g.random()), 0.5 * v * v * trig * trig)
    return x, ("peak", root * 2.6, 1.4, 7.0)


def plan_snare(m, v):
    t60 = _decay(m, 0.09, 0.42)
    return _env(_frames(t60), t60)


def body_snare(m, v, take, env, r):
    n = len(env)
    g = _rng("SNARE", take)
    f1 = sk.exp_map(m["TUNE"], 150.0, 265.0)
    sweep = _sweep(n, sk.exp_map(m["ZAP"], 0.25, 5.0), sk.exp_map(m["ZAP"], 0.006, 0.03))
    dive = 2.0 ** (-PITCH_OCT * r)
    body_env = _env(n, _decay(m, 0.07, 0.26))
    body = (_sine(f1 * sweep * dive, 0.05 * g.random()) + 0.55 * _sine(f1 * 1.83 * sweep * dive)) * body_env
    # The wires: noise, brighter and louder the harder the hit (velocity is not level alone).
    noise = _hp(_lp(g.uniform(-1, 1, n), 4500.0 + 11500.0 * v ** 0.6), 1300.0) * env
    x = 0.7 * body + (0.55 + 0.45 * v) * 0.9 * noise + _click(n, 0.25, 0.4 * v)
    return x, ("peak", 1900.0, 3.0, 12.0)


def plan_clap(m, v):
    t60 = _decay(m, 0.08, 0.36)
    return _env(_frames(t60 + 0.03), t60)


def body_clap(m, v, take, env, r):
    n = len(env)
    g = _rng("CLAP", take)
    t = np.arange(n) / FS
    # Three early taps and the tail, 8 to 11 ms apart, jittered per take; a soft clap loses one.
    gaps = 0.0085 + 0.0025 * g.random(3)
    starts = np.concatenate([[0.0], np.cumsum(gaps)])
    taps = np.zeros(n)
    for i, s in enumerate(starts[:3 if v > 0.5 else 2]):
        taps += (t >= s) * np.exp(-np.maximum(t - s, 0) / 0.0035) * (1.0 - 0.12 * i)
    last = starts[3 if v > 0.5 else 2]
    gate = taps + (t >= last) * np.exp(-6.908 * np.maximum(t - last, 0) / _decay(m, 0.08, 0.36))
    centre = sk.exp_map(m["TUNE"], 900.0, 1900.0)
    noise = g.uniform(-1, 1, n)
    # ZAP on a noise voice sweeps the band the noise is heard through, from above.
    amt = sk.exp_map(m["ZAP"], 0.05, 3.0)
    hi = _bp(noise, centre * (1 + amt), 1.6)
    lo = _bp(noise, centre, 1.6)
    blend = np.exp(-t / sk.exp_map(m["ZAP"], 0.004, 0.03))
    x = (hi * blend + lo * (1 - blend)) * gate
    return _hp(x, 500.0), ("peak", centre * 1.5, 3.0, 12.0)


def plan_hat(m, v):
    t60 = _decay(m, 0.03, 0.62)
    return _env(_frames(t60), t60, attack=0.0003)


def _cluster(n, base, r, g, ratios=CLUSTER):
    out = np.zeros(n)
    dive = 2.0 ** (-PITCH_OCT * r)
    for ratio in ratios:
        out += _square(base * ratio * dive, g.random())   # free-running: a new phase every take
    return out / len(ratios)


def body_hat(m, v, take, env, r):
    n = len(env)
    g = _rng("HAT", take)
    base = sk.exp_map(m["TUNE"], 320.0, 620.0)
    sweep = _sweep(n, sk.exp_map(m["ZAP"], 0.02, 1.4), sk.exp_map(m["ZAP"], 0.003, 0.02))
    x = _cluster(n, base * sweep, r, g)
    x = _hp(_bp(x, 8200.0, 0.9) + 0.6 * _bp(x, 11500.0 * (0.8 + 0.2 * v), 1.4), 6200.0, 4)
    return x * env, ("peak", 9000.0, 3.0, 11.0)


def plan_rim(m, v):
    t60 = _decay(m, 0.025, 0.13)
    return _env(_frames(t60), t60, attack=0.0002)


def body_rim(m, v, take, env, r):
    n = len(env)
    g = _rng("RIM", take)
    f1 = sk.exp_map(m["TUNE"], 420.0, 920.0)
    trig = trigger("RIM", take)
    sweep = _sweep(n, sk.exp_map(m["ZAP"], 0.1, 2.5) * trig, sk.exp_map(m["ZAP"], 0.002, 0.012))
    dive = 2.0 ** (-PITCH_OCT * r)
    x = (_sine(f1 * sweep * dive, 0.25) + 0.7 * _sine(f1 * 1.62 * sweep * dive, 0.1 * g.random())) * env
    x += _click(n, 0.12 * (0.9 + 0.2 * g.random()), 0.9 * v * trig * trig)
    return x, ("peak", f1 * 2.6, 3.0, 9.0)


def plan_bell(m, v):
    t60 = _decay(m, 0.08, 0.62)
    return _env(_frames(t60), t60, attack=0.0003)


def body_bell(m, v, take, env, r):
    n = len(env)
    g = _rng("BELL", take)
    base = sk.exp_map(m["TUNE"], 430.0, 760.0)
    sweep = _sweep(n, sk.exp_map(m["ZAP"], 0.01, 0.9), sk.exp_map(m["ZAP"], 0.003, 0.02))
    dive = 2.0 ** (-PITCH_OCT * r)
    x = _square(base * sweep * dive, g.random()) + _square(base * 1.48 * sweep * dive, g.random())
    x = _bp(x, base * 1.55, 2.2)
    t = np.arange(n) / FS
    strike = 0.6 * np.exp(-t / 0.012) + 0.4      # a fast drop onto a slower ring
    return x * env * strike, ("peak", base * 3.1, 2.5, 7.0)


def plan_tom(m, v):
    t60 = _decay(m, 0.12, 0.72)
    return _env(_frames(t60), t60)


def body_tom(m, v, take, env, r):
    n = len(env)
    g = _rng("TOM", take)
    root = sk.exp_map(m["TUNE"], 70.0, 260.0)
    trig = trigger("TOM", take)
    sweep = _sweep(n, sk.exp_map(m["ZAP"], 0.3, 7.0) * trig, sk.exp_map(m["ZAP"], 0.012, 0.07))
    f = root * sweep * 2.0 ** (-PITCH_OCT * r)
    x = (_sine(f, 0.03 * g.random()) + 0.18 * _sine(f * 2.0)) * env
    x += 0.12 * v * trig * _lp(g.uniform(-1, 1, n), 2500.0) * _env(n, 0.05)
    return x, ("peak", root * 3.0, 1.6, 6.0)


def plan_laser(m, v):
    t60 = _decay(m, 0.06, 0.55)
    return _env(_frames(t60), t60, attack=0.0003)


def body_laser(m, v, take, env, r):
    n = len(env)
    g = _rng("LASER", take)
    top = sk.exp_map(m["TUNE"], 1100.0, 5200.0)
    span = sk.exp_map(m["ZAP"], 3.0, 48.0)                 # how far it falls
    t = np.arange(n) / FS
    fall = _decay(m, 0.06, 0.55) * 0.3
    f = top * span ** (-(1 - np.exp(-t / fall))) * 2.0 ** (-PITCH_OCT * r)
    x = (0.6 * _sine(f, g.random()) + 0.4 * _square(f, g.random())) * env
    return x, ("peak", top * 0.5, 2.5, 8.0)


def plan_stab(m, v):
    t60 = _decay(m, 0.12, 0.95)
    n = _frames(t60)
    env = _env(n, t60 * 1.6, attack=0.002)
    hold = int(n * 0.86)                                   # a bass hit ends on a hard mute
    env[hold:] *= np.linspace(1.0, 0.0, n - hold) ** 2
    return env


def body_stab(m, v, take, env, r):
    n = len(env)
    g = _rng("STAB", take)
    root = sk.exp_map(m["TUNE"], 36.7, 73.4)               # D1 to D2
    sweep = _sweep(n, sk.exp_map(m["ZAP"], 0.02, 1.0), sk.exp_map(m["ZAP"], 0.006, 0.04))
    f = root * sweep * 2.0 ** (-PITCH_OCT * r)
    cents = 2.0 ** (17.0 / 1200.0)                         # two saws 34 cents apart: the beating pair. "shape"
    x = 0.5 * (_saw(f * cents, g.random()) + _saw(f / cents, g.random())) + 0.8 * _sine(f * 0.5 if root > 55 else f)
    return x * env, ("lowpass", sk.exp_map(m["TUNE"], 700.0, 1300.0) * (0.7 + 0.5 * v), 4.5, 0.0)


def plan_crash(m, v):
    t60 = _decay(m, 0.4, 1.4)
    return _env(_frames(t60), t60, attack=0.0004)


def body_crash(m, v, take, env, r):
    n = len(env)
    g = _rng("CRASH", take)
    base = sk.exp_map(m["TUNE"], 380.0, 700.0)
    sweep = _sweep(n, sk.exp_map(m["ZAP"], 0.02, 1.0), sk.exp_map(m["ZAP"], 0.004, 0.03))
    x = _cluster(n, base * sweep, r, g, CLUSTER + (3.41, 4.07, 5.33))
    x = _hp(x + 0.5 * g.uniform(-1, 1, n), 4200.0, 4)
    t = np.arange(n) / FS
    return x * env * (0.5 + 0.5 * np.exp(-t / 0.06)), ("peak", 7000.0, 1.8, 7.0)


PLAN = {"KICK": plan_kick, "SNARE": plan_snare, "CLAP": plan_clap, "HAT": plan_hat, "RIM": plan_rim,
        "BELL": plan_bell, "TOM": plan_tom, "LASER": plan_laser, "STAB": plan_stab, "CRASH": plan_crash}
BODY = {"KICK": body_kick, "SNARE": body_snare, "CLAP": body_clap, "HAT": body_hat, "RIM": body_rim,
        "BELL": body_bell, "TOM": body_tom, "LASER": body_laser, "STAB": body_stab, "CRASH": body_crash}
# The class each voice files under by default; a kit may override it per pad (an open hat).
DRUM_CLASS = {"KICK": "KICK", "SNARE": "SNARE", "CLAP": "CLAP", "HAT": "HAT_CLOSED", "RIM": "PERC",
              "BELL": "PERC", "TOM": "TOM", "LASER": "PERC", "STAB": "PERC", "CRASH": "PERC"}


# ---------------------------------------------------------------- TONE and HEAT

TONE_BLOCK = 64   # frames at 4x between coefficient updates: 2.8 kHz, far above the rail's 72 Hz


def _tone_coeffs(kind, hz, q, gain_db):
    w0 = 2 * math.pi * min(hz, FS * 0.42) / FS
    alpha = math.sin(w0) / (2 * q)
    c = math.cos(w0)
    if kind == "lowpass":
        b = np.array([(1 - c) / 2, 1 - c, (1 - c) / 2])
        a = np.array([1 + alpha, -2 * c, 1 - alpha])
    else:                                                  # a peaking boost
        amp = 10 ** (gain_db / 40)
        b = np.array([1 + alpha * amp, -2 * c, 1 - alpha * amp])
        a = np.array([1 + alpha / amp, -2 * c, 1 - alpha / amp])
    return b / a[0], a / a[0]


def tone(x, spec, r):
    """The voice's resonant stage, its frequency riding the rail. Block-wise coefficients with
    the filter state carried across blocks; the Kotlin port would use Dsp.TptSvf per sample,
    which is better behaved under modulation, so expect small differences there."""
    kind, hz, q, gain_db = spec
    if not np.any(r):
        b, a = _tone_coeffs(kind, hz, q, gain_db)
        return signal.lfilter(b, a, x)
    out = np.empty_like(x)
    zi = np.zeros(2)
    for i in range(0, len(x), TONE_BLOCK):
        b, a = _tone_coeffs(kind, hz * 2.0 ** (-TONE_OCT * r[i]), q, gain_db)
        out[i:i + TONE_BLOCK], zi = signal.lfilter(b, a, x[i:i + TONE_BLOCK], zi=zi)
    return out


def heat(x, amount, r):
    """Drive into a ceiling the rail sets, through an output stage whose swing the rail also sets.
    Linear below half the ceiling, then a tanh knee, so HEAT 0 on a rested rail changes nothing.
    Two things happen as the rail falls: the same signal sits closer to the ceiling (more clip),
    and the whole stage swings less (the body ducks under the transient, and a ringing rail
    becomes an audio-rate tremolo: the growl). A small bias makes the clip lopsided (even
    harmonics, the 'bark'); the DC it leaves is removed by the output stage."""
    ceiling = 1.0 - CEIL_DROP * r
    gain = 10 ** (sk.lin(amount, 0.0, 24.0) / 20)
    u = (x * gain + 0.12 * amount * ceiling) / ceiling
    mag = np.abs(u)
    shaped = np.where(mag <= 0.5, u, np.sign(u) * (0.5 + 0.5 * np.tanh((mag - 0.5) / 0.5)))
    return shaped * ceiling * ceiling


# ---------------------------------------------------------------- render

def _finish(x):
    y = sk.decimate4(sk.band_limit(x, FS))
    y = y - np.mean(y)
    alpha = 1.0 / (1.0 + 2 * math.pi * sk.OUTPUT_DC_HZ / RATE)
    y = signal.lfilter([alpha, -alpha], [1.0, -alpha], y)
    return sk.fade_tail(y, RATE)


def _velocity(v) -> float:
    return min(1.0, max(0.0, float(v))) if isinstance(v, (int, float)) and math.isfinite(v) else 1.0


def load_of(voice: str, macros: dict | None, velocity: float = 1.0) -> np.ndarray:
    """What a hit draws from the supply: its envelope, weighted by the voice and by how hard it
    was hit (v^1.5: a hard hit pulls disproportionately more)."""
    m = settled(macros)
    v = _velocity(velocity)
    return DRAW[voice] * v ** 1.5 * PLAN[voice](m, v)


def render(voice: str, macros: dict | None = None, velocity: float = 1.0, take: int = 0,
           shared_rail: np.ndarray | None = None, diagnostics: dict | None = None) -> np.ndarray:
    """One hit at 44.1 kHz mono. Its level is NOT normalised here: a full-velocity voice enters
    HEAT at PRE_LEVEL and softer hits enter lower, so velocity reaches the rail and the clipper.
    `shared_rail` (at 4x, already filtered) is the sag OTHER hits cause, inside a phrase."""
    m = settled(macros)
    v = _velocity(velocity)
    if v <= 0.0:
        return np.zeros(int(0.05 * RATE))
    pitch, stretch = drift(voice, take)
    env = PLAN[voice](m, v)
    if stretch != 1.0:                                     # this take rings a little longer or shorter
        env = env * np.exp(-6.908 * (1.0 / stretch - 1.0) * np.arange(len(env)) / FS / max(0.03, len(env) / FS))
    n = len(env)
    if m["SAG"] <= 0.0:
        r = np.zeros(n)
    else:
        # The hit's own sag, from its own envelope (not weighted by DRAW: a lone voice's depth is
        # SENS). Inside a phrase the earlier hits are real, so the stand-in residual is left out.
        pre = np.zeros(PRE_ROLL) if shared_rail is not None else _residual(PRE_ROLL, voice, take)
        own = rail_filter(np.concatenate([pre, v ** 1.5 * env]), m["GROWL"])[PRE_ROLL:]
        others = None
        if shared_rail is not None:
            others = np.pad(shared_rail[:n], (0, max(0, n - len(shared_rail[:n]))))
        r = rail_read(voice, m["SAG"], own, others)
    r = r - PITCH_DRIFT * math.log2(pitch)                 # fold the take's tuning into the pitch readout
    x, spec = BODY[voice](m, v, take, env, r)
    r = r + PITCH_DRIFT * math.log2(pitch)
    peak = float(np.max(np.abs(x))) or 1.0
    x = x / peak * PRE_LEVEL * v                           # an explicit level law, then the physics
    x = heat(tone(x, spec, r), m["HEAT"], r)
    if diagnostics is not None:
        diagnostics.update(rail_min=float(r.min()), rail_max=float(r.max()),
                           rail_hz=rail_params(m["GROWL"])[0], rail_zeta=rail_params(m["GROWL"])[1])
    return _finish(x)


PITCH_DRIFT = 1.0 / PITCH_OCT   # r units per octave: lets a take's tuning ride the pitch readout


def level(x: np.ndarray, peak: float = 0.89) -> np.ndarray:
    p = float(np.max(np.abs(x)))
    return x * (peak / p) if p > 0 else x


def phrase_rails(events, seconds: float, growl: float) -> list[np.ndarray]:
    """For a baked phrase: events are (time_s, voice, macros, velocity). Returns, per event, the
    sag the OTHER hits cause from that event's start on (the whole rail minus its own share; the
    rail filter is linear, so the subtraction is exact). Pass each to render(shared_rail=...)."""
    n = int(seconds * FS) + FS
    total = np.zeros(n)
    loads = []
    for (at, voice, macros, vel) in events:
        ld = load_of(voice, macros, vel)
        i = int(at * FS)
        ld = ld[:n - i]
        total[i:i + len(ld)] += ld
        loads.append((i, ld))
    rail = rail_filter(total, growl)
    out = []
    for i, ld in loads:
        own = rail_filter(np.concatenate([ld, np.zeros(max(0, min(n - i, int(1.5 * FS)) - len(ld)))]), growl)
        seg = rail[i:i + len(own)].copy()
        seg[:len(own)] -= own[:len(seg)]
        out.append(SUPPLY_FLOOR * np.tanh(seg / SUPPLY_FLOOR))   # a supply can only be pulled so far
    return out


if __name__ == "__main__":
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "servo-out"
    os.makedirs(out_dir, exist_ok=True)
    for name in VOICES:
        y = level(render(name))
        sk.write_wav(os.path.join(out_dir, name.lower() + ".wav"), y)
        print(f"{name:6s} {len(y) / RATE * 1000:6.0f} ms  peak {np.max(np.abs(y)):.2f}")
