"""CONSORT prototype: a lead woodwind and up to three harmony players, each a blown pipe.

Python harness, not Kotlin. Nothing here has been heard by anyone; the numbers it prints are
measurements of its own renders. Every constant is a "shape" value until a listen backs it.

How it is put together (read this before the code):

  1. A PLAYER is a plan, not a sound: from the note, the velocity and the macros, `plan`
     writes four curves over the length of the note: mouth pressure, pitch offset in cents
     (vibrato, drift, scoop, trill, fall), breath noise, and for the flute where the jet sits.
     Trill, flutter, scoop, fall and overblow are all just shapes in those curves.
  2. A PIPE turns the plan into sound (`blow`). It is the loop BORE already ships
     (synth/.../Bore.kt, the `tick` at line 1012): a delay line one period long, a valve at
     the mouth end that decides how much of the returning wave bounces back. Three pipes:
       CONE  (sax, oboe, bassoon): reed valve, full-period loop, every harmonic.
       CYL   (clarinet, duduk): reed valve, half-period loop that flips sign, odd harmonics.
       JET   (flute): an air jet across an edge; shortening the jet's travel time makes the
             note crack up an octave, which is what overblowing is.
  3. A BODY (`body`) is a fixed tone shape per instrument: the bell's tilt plus two or three
     formant bumps that stay at the same frequencies whatever note is played. This is what
     separates oboe from sax from bassoon here; they share the CONE pipe.
  4. The ENSEMBLE (`render`) runs the lead and each harmony player through 1 to 3 with their
     own seed, their own small lateness, tuning drift and level, at lead + interval semitones.
  5. The ROOM seats each player on their own chair: every chair gets its own first wall
     bounces and its own distance from the microphone, then all share one wash. LOOSE is how
     human the players are, and a `take` number reseeds it all, which is what round robin cycles.

Run:  python3 -I docs/prototypes/consort/consort_proto.py <out_dir>
"""
from __future__ import annotations

import json
import math
import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "..", ".claude", "skills", "luthier", "scripts"))
import snipkit as sk  # noqa: E402
import probes as pr   # noqa: E402

RATE = sk.INTERNAL            # 176.4 kHz, the house 4x internal rate
TUNE_SEMITONES = 24

# name: pipe kind, root midi at TUNE 0, reed offset (how open the reed rests: lower is looser
# and buzzier), bell low-pass in Hz (or a multiple of the note for the flute), breath noise
# low..high, rasp (how hard the reed beats: see `rasp`), vibrato Hz and cents (taken from the owner's references: 3.0 to 6.1 Hz, 6 to 22
# cents), attack seconds, and the body's formants as (Hz, dB, Q).
INSTRUMENTS = {
    "FLUTE":    dict(pipe="JET",  root=60, rasp=0.8, offset=0.0,  bell=3.5,  noise=(0.08, 0.55), vib=(4.8, 9),  attack=0.060,
                     tilt_hz=2400, formants=[(800, 3, 1.2), (2600, 4, 1.5)]),
    "SAX":      dict(pipe="CONE", root=48, rasp=3.2, offset=0.60, bell=2600, noise=(0.04, 0.30), vib=(4.6, 14), attack=0.035,
                     tilt_hz=4200, formants=[(650, 5, 1.6), (1450, 6, 2.0), (2900, 7, 2.0)]),
    "CLARINET": dict(pipe="CYL",  root=50, rasp=1.6, offset=0.68, bell=2400, noise=(0.02, 0.16), vib=(3.2, 6),  attack=0.045,
                     tilt_hz=3000, formants=[(1550, 4, 2.0), (3600, 3, 2.0)]),
    "OBOE":     dict(pipe="CONE", root=58, rasp=3.6, offset=0.74, bell=3400, noise=(0.015, 0.12), vib=(4.4, 10), attack=0.030,
                     tilt_hz=3600, formants=[(1100, 9, 3.0), (2850, 8, 3.0)]),
    "BASSOON":  dict(pipe="CONE", root=34, rasp=2.6, offset=0.70, bell=1900, noise=(0.02, 0.15), vib=(5.3, 13), attack=0.050,
                     tilt_hz=2400, formants=[(470, 8, 2.5), (1150, 6, 2.5), (2000, 2, 2.0)]),
    "DUDUK":    dict(pipe="CYL",  root=57, rasp=1.1, offset=0.56, bell=1500, noise=(0.05, 0.28), vib=(5.1, 20), attack=0.110,
                     tilt_hz=1500,  formants=[(600, 5, 1.5), (1250, 4, 2.0)]),
}
VOICES = list(INSTRUMENTS)

# The harmony player's distance from the lead, in semitones. Fixed intervals (owner's choice).
INTERVALS = {"OCT_DOWN": -12, "UNISON": 0, "FIFTH": 7, "MIN7": 10, "MAJ7": 11, "OCT_UP": 12}

GESTURES = ["PLAIN", "SCOOP", "TRILL", "FLUTTER", "FALL"]   # GESTURE is a stepped knob over these
MACROS = {"TUNE": 0.5, "BREATH": 0.6, "GESTURE": 0.0, "MOTION": 0.5, "BLEND": 0.6, "LOOSE": 0.5, "ROOM": 0.4, "HOLD": 0.45}

REED_SLOPE = -0.3             # Bore.REED_SLOPE
LOOP_GAIN = 0.95              # Bore.LOOP_GAIN
OVERBLOW_FROM = 0.85          # effort above this overblows: the flute cracks, the reeds growl
GROWL_HZ = 31.0               # Bore.OVERBLOW_GROWL_HZ, the rate the owner chose by ear for BORE
FLUTTER_HZ = 24.0             # a flutter-tongue is 20 to 30 rolls a second


def hz_of(midi: float) -> float:
    return 440.0 * 2 ** ((midi - 69) / 12)


def midi_for(inst: str, tune: float) -> int:
    return INSTRUMENTS[inst]["root"] + int(round(tune * TUNE_SEMITONES))


def gesture_of(value: float) -> str:
    return GESTURES[min(len(GESTURES) - 1, int(value * len(GESTURES)))]


def gesture_value(name: str) -> float:
    return (GESTURES.index(name) + 0.5) / len(GESTURES)


def effort_of(breath: float, velocity: float) -> float:
    """Velocity is breath effort riding on the BREATH knob: BREATH sets where the player sits,
    how hard the pad is hit pushes from there. At BREATH 0.6 only a hard hit (1.0) overblows."""
    return float(np.clip(0.5 * breath + 0.6 * velocity, 0.0, 1.0))


def hold_seconds(hold: float) -> float:
    return sk.exp_map(hold, 0.3, 4.0)       # Bore.HOLD_MIN_SECONDS .. HOLD_MAX_SECONDS


def smooth_noise(n: int, seed: int, corner_hz: float) -> np.ndarray:
    """Slow seeded wander in -1..1: white noise through two one-poles, made at 1 kHz and
    stretched, so it is cheap. Used for tuning drift and for the unevenness of a trill."""
    step = RATE // 1000
    m = n // step + 3
    w = sk.Noise(seed).array(m)
    a = math.exp(-2 * math.pi * corner_hz / 1000.0)
    y = np.empty(m)
    s1 = s2 = 0.0
    for i in range(m):
        s1 = a * s1 + (1 - a) * w[i]
        s2 = a * s2 + (1 - a) * s1
        y[i] = s2
    y /= max(1e-9, np.max(np.abs(y)))
    return np.interp(np.arange(n) / step, np.arange(m), y)


def plan(inst: str, midi: float, velocity: float, macros: dict, role: int, seconds: float, take: int = 0) -> dict:
    """The player. Returns the curves the pipe is blown with. `role` 0 is the lead; harmony
    players (1..3) breathe and bend with the lead but only the lead trills and flutters, the
    way a section holds its notes under a soloist's ornament."""
    spec = INSTRUMENTS[inst]
    m = {**MACROS, **macros}
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    # `take` is the round-robin number: a new take reseeds everything a player does differently
    # each time (drift, lateness, breath, vibrato), and nothing about the instrument itself
    seed = sk.seed_for("CONSORT", inst, int(round(midi)), role, take)
    effort = effort_of(m["BREATH"], velocity)
    gesture = gesture_of(m["GESTURE"])
    motion = m["MOTION"]
    # LOOSE is how human the players are. The lead gets 60 % of it: a soloist is steadier than
    # the section behind them, but not a machine
    loose = m["LOOSE"] if role > 0 else 0.6 * m["LOOSE"]
    blown = hold_seconds(m["HOLD"])
    release = 0.09
    human = {}
    over = max(0.0, (effort - OVERBLOW_FROM) / (1.0 - OVERBLOW_FROM))

    # each harmony player comes in a little late (up to 35 ms at LOOSE 1), by their own amount
    rnd = sk.Noise(seed + 7)
    late = (0.004 + 0.031 * abs(rnd.next())) * loose if role > 0 else 0.0
    tt = np.maximum(t - late, 0.0)
    # six ways no two players are alike, each drawn once per player per take and scaled by LOOSE:
    flat = (12.0 if role > 0 else 4.0) * loose * rnd.next()   # 1. where they sit against the pitch (the lead stays close: it is the reference)
    attack_scale = 1.0 + 0.45 * loose * rnd.next()   # 2. how quickly their note speaks
    stop = 0.07 * loose * rnd.next()                 # 3. who lets go first: up to 70 ms early or late
    release = release * (1.0 + 0.6 * loose * abs(rnd.next()))
    under = 1.0 + 0.8 * loose * rnd.next()           # 4. how far under the note they start
    vib_late = 0.12 * loose * rnd.next()             # 5. when their vibrato comes in
    air = 1.0 + 0.5 * loose * rnd.next()             # 6. how much air leaks round the reed or the lip
    human = dict(flat_cents=round(flat, 1), attack_scale=round(attack_scale, 2), stop_ms=round(1000 * stop, 1))
    ends = late + blown + stop

    attack = spec["attack"] * sk.lin(effort, 1.5, 0.6) * attack_scale   # a hard blow speaks faster
    env = np.clip(tt / attack, 0, 1) ** 0.7
    env *= np.clip((ends - t) / release, 0, 1)                    # the breath stops, the pipe rings out
    env[t < late] = 0.0
    # a breath is never flat: it swells a little toward the middle of a long note
    frac = np.clip(tt / max(blown, 1e-3), 0, 1)
    env *= 1.0 + 0.06 * np.sin(np.pi * frac) * (0.5 + effort)
    # and it runs out: over the last third the pressure tapers and the pitch sags a few cents with it
    tired = np.clip((frac - 0.67) / 0.33, 0, 1) ** 2
    env *= 1.0 - 0.14 * loose * tired
    # the diaphragm is not a pump: the pressure wanders a few percent, slowly, the whole way through
    wander = smooth_noise(n, seed + 17, 2.5)
    env *= 1.0 + sk.lin(loose, 0.01, 0.09) * wander

    cents = np.zeros(n)
    # vibrato enters late and grows, as a held wind note's does; MOTION scales its depth on a plain note
    vib_hz, vib_cents = spec["vib"]
    vib_hz *= 1.0 + 0.06 * rnd.next()
    depth = vib_cents * (sk.lin(motion, 0.2, 1.6) if gesture == "PLAIN" else 0.6)
    rise = np.clip((tt - 0.18 - vib_late) / 0.35, 0, 1)
    # a played vibrato is not a sine from a box: its speed wanders (up to 9 %), its depth breathes
    # (up to 35 %), and it shakes the breath as well as the pitch, so the tone pulses with it
    vib_rate = vib_hz * (1.0 + 0.09 * loose * smooth_noise(n, seed + 19, 1.2))
    vib_phase = 2 * np.pi * np.cumsum(vib_rate) / RATE + 2 * np.pi * abs(rnd.next())
    vib_depth = depth * rise * (1.0 + 0.35 * loose * smooth_noise(n, seed + 23, 0.9))
    cents += vib_depth * np.sin(vib_phase)
    env *= 1.0 + 0.0025 * vib_depth * np.sin(vib_phase + 0.6)
    # tuning drift: nobody holds a pitch still. A few cents for the lead, more for a loose section
    cents += sk.lin(loose, 2.0, 14.0) * smooth_noise(n, seed + 11, 0.8) + flat - 9.0 * loose * tired
    # every wind attack starts a few cents under and settles; a harder blow starts further under
    cents -= sk.lin(effort, 6.0, 22.0) * under * np.exp(-tt / 0.04)

    pressure_scale = np.ones(n)
    if gesture == "SCOOP":
        # the note is approached from below: up to three semitones, over up to a third of a second
        cents -= sk.lin(motion, 80.0, 300.0) * np.exp(-tt / sk.lin(motion, 0.05, 0.14))
    if gesture == "FALL":
        # the end of the note drops away: pitch falls and the breath lets go with it
        length = sk.lin(motion, 0.15, 0.6)
        x = np.clip((t - (ends - length)) / length, 0, 1)
        cents -= sk.lin(motion, 400.0, 1200.0) * x ** 1.6
        pressure_scale *= 1.0 - 0.45 * x
    if role == 0 and gesture == "TRILL":
        # the lead alternates with the note a whole tone above. The trill starts slow and speeds
        # up (5 Hz to as much as 11), and each finger move takes about 12 ms, not zero
        start = 0.22
        rate_hz = sk.lin(motion, 5.0, 8.0) + sk.lin(motion, 0.5, 3.0) * np.clip((tt - start) / 1.2, 0, 1)
        rate_hz *= 1.0 + 0.05 * smooth_noise(n, seed + 13, 2.0)
        phase = np.cumsum(rate_hz) / RATE
        # fingers are not a square wave: the upper note is held a little shorter than the lower,
        # by an amount that wanders
        duty = 0.46 + 0.07 * loose * smooth_noise(n, seed + 29, 3.0)
        upper = ((phase % 1.0) < duty) & (tt > start) & (t < ends - 0.05)
        k = int(0.012 * RATE)
        moved = np.convolve(upper.astype(float), np.hanning(k) / np.hanning(k).sum(), mode="same")
        cents += 200.0 * moved
        # each finger move leaks: the tone dips for the 12 ms the hole is half covered
        pressure_scale *= 1.0 - 0.5 * loose * 4.0 * moved * (1.0 - moved)
    if role == 0 and gesture == "FLUTTER":
        # flutter-tongue: the tongue rolls and chops the breath 24 times a second
        d = sk.lin(motion, 0.25, 0.75)
        pressure_scale *= 1.0 - d * (0.5 + 0.5 * np.sin(2 * np.pi * FLUTTER_HZ * tt)) * np.clip((tt - 0.05) / 0.08, 0, 1)
    if over > 0 and spec["pipe"] != "JET":
        # an overblown reed growls: the throat shakes the mouth pressure (BORE's owner-chosen flavour)
        pressure_scale *= 1.0 + 0.4 * over ** 1.2 * np.sin(2 * np.pi * GROWL_HZ * tt) * np.clip((tt - 0.06) / 0.05, 0, 1)

    lo, hi = spec["noise"]
    noise = sk.lin(effort, lo, hi) * air * (1.0 + 2.5 * np.exp(-tt / 0.05))     # extra air at the tongue: the chiff
    noise = noise * (1.0 + 0.4 * loose * smooth_noise(n, seed + 31, 4.0))       # and the leak is never steady
    # the flute's overblow: after the low note has spoken (0.14 s) the jet's travel time halves
    # over 5 ms and the note cracks to the octave. `jet` is that travel time in periods.
    jet = np.full(n, 0.5)
    if over > 0 and spec["pipe"] == "JET":
        jet -= 0.25 * np.clip((tt - 0.14) / 0.005, 0, 1)
        pressure_scale *= 1.0 + 0.25 * over * np.sin(2 * np.pi * GROWL_HZ * tt) * np.clip((tt - 0.2) / 0.05, 0, 1)
    return dict(env=env * pressure_scale, cents=cents, noise=noise, jet=jet, effort=effort, over=over,
                seed=seed, late=late, human=human)


def blow(inst: str, hz: float, p: dict, pin_cents: float = 0.0) -> np.ndarray:
    """The pipe. One sample at a time, because each new sample depends on the wave that comes
    back from the far end. Returns the wave inside the bore (what BORE listens to, not the
    valve's own output, which carries the mouth's steady pressure)."""
    spec = INSTRUMENTS[inst]
    pipe = spec["pipe"]
    effort = p["effort"]
    n = len(p["env"])
    half = pipe == "CYL"                       # a closed cylinder's round trip is half a period, sign flipped
    sign = -1.0 if half else 1.0

    bell_hz = spec["bell"] * hz * sk.lin(effort, 0.8, 1.25) if pipe == "JET" else spec["bell"] * sk.lin(effort, 0.75, 1.35)
    bell_hz = min(bell_hz, 0.4 * RATE)
    g = 1.0 - math.exp(-2 * math.pi * bell_hz / RATE)          # the bell: a one-pole low-pass in the loop
    dc_hz = 20.0 if pipe == "JET" else hz / 25.0               # Bore.SAX_BLOCKER_DIVISOR
    r_dc = math.exp(-2 * math.pi * dc_hz / RATE)

    # the loop's filters delay the wave too, so the delay line is shortened by what they add
    f = hz * 2 ** ((p["cents"] + pin_cents) / 1200.0)
    w = 2 * math.pi * f / RATE
    a = 1.0 - g
    lp_delay = np.arctan2(a * np.sin(w), 1 - a * np.cos(w)) / w
    hp_lead = np.arctan(dc_hz / f) / w
    length = (RATE / f) * (0.5 if half else 1.0) - lp_delay + hp_lead
    length = np.maximum(length, 3.0)
    jet_len = np.maximum(p["jet"] * RATE / f, 2.0)

    size = int(np.max(length)) + 8
    buf = [0.0] * size
    jbuf = [0.0] * (int(np.max(jet_len)) + 8)
    jsize = len(jbuf)
    out = [0.0] * n
    rng = sk.Noise(p["seed"] + 1).array(n)

    if pipe == "JET":
        pm_curve = sk.lin(effort, 0.80, 1.02) * p["env"] * (1.0 + p["noise"] * rng)
        pm_curve = np.minimum(pm_curve, 1.3)
        bias = sk.lin(effort, 0.42, 0.22)       # where the jet rests against the edge (BORE's LIP, driven by effort)
    else:
        offset = spec["offset"]
        closing = (1.0 - offset) / -REED_SLOPE                 # pressure that shuts the reed
        share = sk.lin(effort, 0.60 + 0.63 * (offset - 0.5) + 0.08, 0.93)
        pm_curve = np.minimum(closing * share * p["env"] * (1.0 + p["noise"] * rng), closing * 0.99)
    # the tongue's seed: eight periods of the note put straight into the bore so a reed speaks at once
    seed = np.zeros(n)
    start = int((p["late"] + 0.6 * spec["attack"]) * RATE)
    k = int(8 * RATE / hz)
    if pipe != "JET" and start + k < n:
        i = np.arange(k)
        seed[start:start + k] = 0.3 * sk.lin(effort, 0.3, 1.0) * (0.5 - 0.5 * np.cos(2 * np.pi * i / k)) * np.sin(2 * np.pi * hz * i / RATE)
    if pipe == "JET" and p["over"] > 0:
        at = int((p["late"] + 0.145) * RATE)                   # an octave seed where the jet arrives, as BORE's crack has
        k2 = int(8 * RATE / (2 * hz))
        if at + k2 < n:
            i = np.arange(k2)
            seed[at:at + k2] = 0.3 * (0.5 - 0.5 * np.cos(2 * np.pi * i / k2)) * np.sin(2 * np.pi * 2 * hz * i / RATE)

    L = length.tolist(); J = jet_len.tolist(); P = pm_curve.tolist(); S = seed.tolist()
    lp = 0.0; dx = 0.0; dy = 0.0
    tanh = math.tanh; floor = math.floor
    reed = pipe != "JET"
    offset = spec["offset"]
    for i in range(n):
        pos = i - L[i]
        i0 = floor(pos)
        fr = pos - i0
        x0 = buf[i0 % size]
        back = x0 + (buf[(i0 + 1) % size] - x0) * fr
        lp += g * (back - lp)
        dy = lp - dx + r_dc * dy
        dx = lp
        ret = sign * LOOP_GAIN * dy
        pm = P[i]
        if reed:
            delta = ret - pm                                   # pressure across the reed
            r = offset + REED_SLOPE * delta                    # Smith's reflection table, clamped: it can never add energy
            if r > 1.0: r = 1.0
            elif r < -1.0: r = -1.0
            y = pm + delta * r
        else:
            jp = i - J[i]
            j0 = floor(jp)
            jf = jp - j0
            a0 = jbuf[j0 % jsize]
            jet_out = a0 + (jbuf[(j0 + 1) % jsize] - a0) * jf
            jbuf[i % jsize] = -0.5 * ret
            y = tanh(3.0 * pm * jet_out + bias) * (pm if pm < 1.0 else 1.0) + 0.5 * ret
        buf[i % size] = y + S[i]
        out[i] = ret
    return np.asarray(out)


def rasp(x: np.ndarray, inst: str, effort: float) -> np.ndarray:
    """The reed beating against the mouthpiece. The loop on its own gives a soft, nearly pure
    wave; a real reed slams shut once a cycle and that is where a sax's edge comes from. BORE
    solved this with a memoryless bend, `tanh(drive * (x + bias))` (its RASP), and so does this:
    it has no memory, so it cannot change the pitch or the timing, only add harmonics, and it
    adds more the harder the player blows, which is what makes velocity a change of tone.
    The cylinders (clarinet, duduk) get a symmetric bend so their hollow odd-harmonic sound
    survives; an asymmetric one would fill in the even harmonics."""
    spec = INSTRUMENTS[inst]
    drive = spec["rasp"] * sk.exp_map(effort, 0.2, 2.4)
    bias = 0.0 if spec["pipe"] == "CYL" else 0.4          # Bore.RASP_BIAS
    peak = max(1e-9, float(np.max(np.abs(x))))
    return (np.tanh(drive * (x / peak + bias)) - math.tanh(drive * bias)) * peak / max(drive, 1.0)


def body(x: np.ndarray, inst: str, effort: float) -> np.ndarray:
    """The instrument's fixed tone shape, applied to the whole note at once in the frequency
    domain (zero phase; the Kotlin would use `Dsp.Biquad` shelves and peaks, as BORE's `voice`
    does). A bell radiates highs better than lows, so the bore wave is tilted up to `tilt_hz`,
    then each formant is a bump that does not move with the note."""
    spec = INSTRUMENTS[inst]
    n = len(x)
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(n, 1.0 / RATE)
    tilt = spec["tilt_hz"] * sk.exp_map(effort, 0.45, 1.6)
    gain = np.sqrt((f / 150.0) ** 2 / (1 + (f / 150.0) ** 2)) * np.sqrt(1 + (f / 300.0) ** 2) / np.sqrt(1 + (f / tilt) ** 2)
    for hz, db, q in spec["formants"]:
        gain *= 1.0 + (10 ** (db / 20) - 1.0) / (1.0 + (q * (f / hz - hz / np.maximum(f, 1.0))) ** 2)
    gain /= np.sqrt(1 + (f / 9000.0) ** 4)
    return np.fft.irfft(X * gain, n)


_PIN: dict = {}


def pin_for(inst: str, midi: int, effort: float) -> float:
    """Cents to add so the note lands on pitch. The valve pulls a blown pipe a little off the
    delay line's own pitch (BORE carries SAX_PIN_CENTS and FLUTE_PIN_CENTS for the same reason),
    so a short plain note is rendered once, measured, and the correction remembered. This is a
    precompute: its key is (instrument, note, effort bucket), and the Kotlin would hold a table."""
    key = (inst, midi, round(effort * 4))
    if key not in _PIN:
        p = plan(inst, midi, 0.7, {"BREATH": (key[2] / 4 - 0.42) / 0.5, "LOOSE": 0.0, "MOTION": 0.0, "HOLD": 0.25}, 0, 0.8)
        p["cents"][:] = 0.0
        p["jet"][:] = 0.5
        y = sk.decimate4(sk.band_limit(blow(inst, hz_of(midi), p), RATE))
        hz, conf = pr.pitch(y, sk.RATE, lo_hz=30, hi_hz=3000, start_seconds=0.35)
        err = pr.cents(hz, hz_of(midi)) if conf > 0.5 else 0.0
        _PIN[key] = -err if abs(err) < 150 else 0.0
    return _PIN[key]


def player(inst: str, midi: int, velocity: float, macros: dict, role: int, seconds: float, take: int = 0) -> np.ndarray:
    p = plan(inst, midi, velocity, macros, role, seconds, take)
    raw = blow(inst, hz_of(midi), p, pin_for(inst, midi, p["effort"]))
    y = body(rasp(raw, inst, p["effort"]), inst, p["effort"])
    return y / max(1e-9, sk.rms(y[int(0.1 * RATE):]))            # each player at unit level before the mix


# Where each chair sits, in metres, in a room 9 x 7 x 3.6 m with the microphone at MIC. The lead
# is closest; the others sit behind and to the sides, so each one's sound reaches the microphone
# by its own set of wall bounces. That difference is most of what says "several people in a room".
ROOM_DIMS = (9.0, 7.0, 3.6)
MIC = (4.5, 2.0, 1.5)
CHAIRS = [(4.4, 3.2, 1.2), (3.1, 4.3, 1.2), (5.8, 4.5, 1.2), (4.6, 5.4, 1.3)]
SPEED_OF_SOUND = 343.0          # Room.SPEED_OF_SOUND


def early(role: int, amount: float) -> np.ndarray:
    """The first wall bounces for one chair: the direct sound, the six walls, and the eighteen
    two-wall paths, each a spike delayed by its extra distance and quieter by distance and by
    0.75 a bounce. This is geometry (a shoebox room's mirror images), not a recording."""
    src = CHAIRS[role]
    direct = math.dist(src, MIC)
    ir = np.zeros(int(0.09 * RATE))
    ir[0] = 1.0
    for ix in (-1, 0, 1):
        for iy in (-1, 0, 1):
            for iz in (-1, 0, 1):
                bounces = abs(ix) + abs(iy) + abs(iz)
                if bounces == 0 or bounces > 2:
                    continue
                image = [(-s if i == -1 else (2 * d - s if i == 1 else s)) for s, d, i in zip(src, ROOM_DIMS, (ix, iy, iz))]
                dist = math.dist(image, MIC)
                at = int((dist - direct) / SPEED_OF_SOUND * RATE)
                if at < len(ir):
                    # a bounce off a wall is duller than the direct sound: spread it over 0.25 ms
                    k = int(0.00025 * RATE * bounces) + 1
                    ir[at:at + k] += amount * (0.75 ** bounces) * (direct / dist) / k * (1 if (ix + iy + iz) % 2 == 0 else -1)
    return ir


def late_tail(amount: float, seconds: float) -> np.ndarray:
    """The room's wash after the first bounces: seeded noise that dies away, its highs twice as
    fast as its lows, starting 25 ms in. A Python stand-in for the rack's ROOM (`Room.kt`, a
    16-line feedback network); the Kotlin would use that, not a convolution."""
    n = int(seconds * 2.2 * RATE)
    t = np.arange(n) / RATE
    w = sk.Noise(sk.seed_for("CONSORT", "room")).array(n)
    a = math.exp(-2 * math.pi * 3500.0 / RATE)
    low = np.empty(n)
    s = 0.0
    for i, x in enumerate(w.tolist()):
        s = a * s + (1 - a) * x
        low[i] = s
    high = w - low
    tail = low * np.exp(-6.9 * t / seconds) + 0.7 * high * np.exp(-6.9 * t / (0.5 * seconds))
    tail *= np.clip((t - 0.025) / 0.03, 0, 1)
    return amount * tail / math.sqrt(np.sum(tail ** 2))


def render(lead: str, macros: dict, velocity: float = 0.7, parts: list | None = None, take: int = 0) -> np.ndarray:
    """The engine. `parts` is the section: up to three (instrument, interval name) pairs.
    Pure: the same inputs give the same samples."""
    from scipy.signal import fftconvolve
    m = {**MACROS, **macros}
    midi = midi_for(lead, m["TUNE"])
    seconds = hold_seconds(m["HOLD"]) + 0.35
    room = m["ROOM"]
    bounce = sk.lin(room, 0.0, 0.9)
    chairs = [(lead, midi, velocity, 1.0)]
    for inst, interval in parts or []:
        # BLEND is how far forward the section sits: tucked 14 dB under the lead, up to level with it
        chairs.append((inst, midi + INTERVALS[interval], velocity * 0.92, 10 ** (sk.lin(m["BLEND"], -14.0, 0.0) / 20)))
    mix = None
    for role, (inst, note, vel, level) in enumerate(chairs):
        y = level * player(inst, note, vel, m, role, seconds, take)
        if room > 0:
            # further chairs are quieter and arrive later, as well as bouncing differently
            lead_dist = math.dist(CHAIRS[0], MIC)
            dist = math.dist(CHAIRS[role], MIC)
            y = fftconvolve(y, early(role, bounce))
            y = np.concatenate([np.zeros(int(room * (dist - lead_dist) / SPEED_OF_SOUND * RATE)), y]) * (lead_dist / dist) ** (0.6 * room)
        if mix is None:
            mix = y
        else:
            size = max(len(mix), len(y))
            mix = np.pad(mix, (0, size - len(mix))) + np.pad(y, (0, size - len(y)))
    if room > 0:
        # ROOM is one knob for size and amount: the wash rings 0.35 to 0.9 s (the rack ROOM's
        # MAX_RT60) and sits 16 dB under the players up to 5 dB under. The tail has unit energy,
        # so a held note comes back from it at about its own level times `wet`
        rt60 = sk.lin(room, 0.35, 0.9)
        wet = 10 ** (sk.lin(room, -16.0, -5.0) / 20)
        mix = np.pad(mix, (0, int(rt60 * 1.3 * RATE)))
        mix = mix + wet * fftconvolve(mix, late_tail(1.0, rt60))[:len(mix)]
    return mix


# ------------------------------------------------------------------------------------------

SECTIONS = {
    "sax_fifths":      ("SAX",      [("SAX", "FIFTH"), ("SAX", "OCT_DOWN")]),
    "reed_trio_min7":  ("OBOE",     [("CLARINET", "FIFTH"), ("BASSOON", "MIN7")]),
    "flute_octaves":   ("FLUTE",    [("FLUTE", "OCT_UP"), ("CLARINET", "OCT_DOWN")]),
    "duduk_drone":     ("DUDUK",    [("DUDUK", "OCT_DOWN"), ("DUDUK", "FIFTH")]),
    "full_quartet":    ("CLARINET", [("FLUTE", "OCT_UP"), ("OBOE", "FIFTH"), ("BASSOON", "OCT_DOWN")]),
    "sax_maj7_stack":  ("SAX",      [("CLARINET", "MAJ7"), ("BASSOON", "OCT_DOWN"), ("FLUTE", "FIFTH")]),
}


def save(out_dir: str, sub: str, name: str, raw: np.ndarray) -> np.ndarray:
    os.makedirs(os.path.join(out_dir, sub), exist_ok=True)
    y = sk.finish(raw)
    sk.write_wav(os.path.join(out_dir, sub, name + ".wav"), y)
    return y


def main(out_dir: str) -> int:
    t0 = time.time()
    res = {"voices": [], "velocity": [], "gestures": {}, "sections": [], "macro_activity_dB": {}, "seconds_per_player_second": None}

    # 1. each voice alone at three notes: does every pipe speak, on pitch, across two octaves?
    blown = 0.0
    t1 = time.time()
    for inst in VOICES:
        for tune in (0.0, 0.5, 1.0):
            midi = midi_for(inst, tune)
            y = save(out_dir, "1_voices", f"{inst}_midi{midi}", render(inst, {"TUNE": tune}))
            blown += len(y) / sk.RATE
            hz, conf = pr.pitch(y, sk.RATE, lo_hz=30, hi_hz=3000, start_seconds=0.45)
            ok, grow = pr.never_grows(y, sk.RATE, attack_seconds=0.3)
            res["voices"].append(dict(voice=inst, midi=midi, cents=round(pr.cents(hz, hz_of(midi)), 1), confidence=round(conf, 2),
                                      centroid_hz=round(pr.spectral_centroid(y)), peak=round(pr.peak(y), 3),
                                      dc=round(pr.dc(y), 5), finite=pr.finite(y), later_over_attack=round(grow, 2)))
    res["seconds_per_player_second"] = round((time.time() - t1) / blown, 2)

    # 2. velocity is breath: soft, medium, hard on each voice. The centroid must rise, not just the level
    for inst in VOICES:
        row = dict(voice=inst)
        for label, vel in (("soft", 0.3), ("medium", 0.7), ("hard", 1.0)):
            y = save(out_dir, "2_velocity", f"{inst}_{label}", render(inst, {}, velocity=vel))
            hz, conf = pr.pitch(y, sk.RATE, lo_hz=30, hi_hz=3000, start_seconds=0.6)
            row[label] = dict(centroid_hz=round(pr.spectral_centroid(y)), cents=round(pr.cents(hz, hz_of(midi_for(inst, 0.5))), 1),
                              confidence=round(conf, 2))
        res["velocity"].append(row)

    # 3. the gestures, on a long note so they have room
    for inst in ("FLUTE", "SAX", "DUDUK"):
        plain = None
        for g in GESTURES:
            y = save(out_dir, "3_gestures", f"{inst}_{g}", render(inst, {"GESTURE": gesture_value(g), "MOTION": 0.7, "HOLD": 0.75}))
            if g == "PLAIN":
                plain = y
            else:
                res["gestures"][f"{inst}_{g}_vs_PLAIN_dB"] = round(pr.macro_activity(plain, y), 2)

    # 4. sections: the lead alone, then with its harmony players, plain and with the lead trilling
    for name, (lead, parts) in SECTIONS.items():
        y = save(out_dir, "4_sections", f"{name}", render(lead, {"HOLD": 0.75}, parts=parts))
        save(out_dir, "4_sections", f"{name}_lead_trill", render(lead, {"HOLD": 0.75, "GESTURE": gesture_value("TRILL")}, parts=parts))
        save(out_dir, "4_sections", f"{name}_hard_fall", render(lead, {"HOLD": 0.6, "GESTURE": gesture_value("FALL")}, velocity=1.0, parts=parts))
        res["sections"].append(dict(section=name, lead=lead, parts=parts, peak=round(pr.peak(y), 3), finite=pr.finite(y),
                                    centroid_hz=round(pr.spectral_centroid(y))))

    # 5. the room: the same notes dry, at the default, and with the room wide open
    res["room"] = []
    for name in ("sax_fifths", "full_quartet"):
        lead, parts = SECTIONS[name]
        for label, room in (("dry", 0.0), ("default", MACROS["ROOM"]), ("big", 1.0)):
            y = save(out_dir, "5_room", f"{name}_{label}", render(lead, {"HOLD": 0.6, "ROOM": room}, parts=parts))
            res["room"].append(dict(section=name, room=room, seconds=round(len(y) / sk.RATE, 2), peak=round(pr.peak(y), 3), finite=pr.finite(y)))
    for label, room in (("dry", 0.0), ("default", MACROS["ROOM"]), ("big", 1.0)):
        save(out_dir, "5_room", f"OBOE_solo_{label}", render("OBOE", {"HOLD": 0.6, "ROOM": room}))

    # 6. humanization: the same section stiff, at the default and loose; then three takes of one
    #    pad, which is what round robin would cycle. Takes must differ but stay the same note.
    res["takes"] = []
    lead, parts = SECTIONS["reed_trio_min7"]
    for label, loose in (("stiff", 0.0), ("default", MACROS["LOOSE"]), ("loose", 1.0)):
        save(out_dir, "6_human", f"reed_trio_{label}", render(lead, {"HOLD": 0.75, "LOOSE": loose}, parts=parts))
        save(out_dir, "6_human", f"DUDUK_solo_{label}", render("DUDUK", {"HOLD": 0.75, "LOOSE": loose}))
    for name, (ld, pt) in (("SAX_solo", ("SAX", [])), ("flute_octaves", SECTIONS["flute_octaves"])):
        ys = [save(out_dir, "7_takes", f"{name}_take{k + 1}", render(ld, {"HOLD": 0.6}, parts=pt, take=k)) for k in range(3)]
        n = min(len(y) for y in ys)
        hz = [pr.pitch(y, sk.RATE, lo_hz=30, hi_hz=3000, start_seconds=0.6)[0] for y in ys]
        res["takes"].append(dict(pad=name,
                                 correlation_1v2=round(float(np.corrcoef(ys[0][:n], ys[1][:n])[0, 1]), 3),
                                 correlation_1v3=round(float(np.corrcoef(ys[0][:n], ys[2][:n])[0, 1]), 3),
                                 detected_hz=[round(h, 2) for h in hz]))

    # 8. every macro changes the sound (one section, extremes, levelled)
    lead, parts = SECTIONS["reed_trio_min7"]
    for macro in ("BREATH", "MOTION", "BLEND", "LOOSE", "ROOM"):
        lo = sk.finish(render(lead, {macro: 0.0}, parts=parts))
        hi = sk.finish(render(lead, {macro: 1.0}, parts=parts))
        res["macro_activity_dB"][macro] = round(pr.macro_activity(lo, hi), 2)

    # 9. aliasing proxy at the brightest corner: energy above the output Nyquist before band limiting
    raw = render("OBOE", {"TUNE": 1.0, "BREATH": 1.0, "ROOM": 0.0}, velocity=1.0)
    res["alias_proxy_oboe_top_hard"] = round(pr.alias_proxy(raw, RATE), 6)
    res["wall_seconds"] = round(time.time() - t0, 1)
    res["pin_cents"] = {f"{k[0]}_{k[1]}_{k[2]}": round(v, 1) for k, v in _PIN.items()}

    with open(os.path.join(out_dir, "report.json"), "w") as fh:
        json.dump(res, fh, indent=1)
    print(json.dumps(res, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "consort_out"))
