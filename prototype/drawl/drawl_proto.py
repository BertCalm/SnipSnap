"""DRAWL round 0: a bar-stopped string whose pitch path is the performance.

Python harness, not Kotlin. Nothing here has been heard. Every constant is a "shape" value
unless its comment cites a measurement (measure.py on the owner's fifteen reference loops,
docs/superpowers/specs/2026-11-20-drawl-engine-design.md section 2).

The characteristic gesture is: pick -> the bar slides the note into place -> the bar
shimmers -> the string rings out through its body.

Six voices, one mechanism:
  CONE     resonator guitar: steel bar, fingerpicks, a spun metal cone (nasal mid peaks)
  BOTTLE   electric slide: bottleneck on a fretted electric, bridge pickup, small amp
  LAP      lap steel: bar across the strings, chords slide in parallel, dark pickup
  STEEL    pedal steel: volume-pedal swell, pedals raise inner chord tones to a stop
  FLATTOP  country steel-string flat-top: fretted, slides step over frets, boomy body
  GUT      nylon western guitar: fretted, finger vibrato, dark and short

Macros (0..1): TUNE SLIDE WAVER PICK BODY RING, plus HOLD (finite: render length only).
render() takes velocity (0..1] and take (round robin, 0..3) as performance arguments.

Run:  python3 prototype/drawl/drawl_proto.py OUT   (one WAV per voice at its defaults)
"""
from __future__ import annotations

import math
import os
import sys

import numpy as np
from numba import njit
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", ".claude", "skills", "luthier", "scripts"))
import snipkit as sk  # noqa: E402

RATE = sk.RATE
R = sk.INTERNAL  # 176.4 kHz, the house 4x internal rate

VOICES = ["CONE", "BOTTLE", "LAP", "STEEL", "FLATTOP", "GUT"]
BAR = {"CONE", "BOTTLE", "LAP"}          # a steel or glass bar sets the pitch: continuous slides
FRETTED = {"FLATTOP", "GUT"}             # frets set the pitch: slides step a semitone at a time
ELECTRIC = {"BOTTLE", "LAP", "STEEL"}    # read by a magnetic pickup, then a small amp

# Defaults per voice. Hypotheses until the audition: "presets authored blind are disposable".
DEFAULTS = {
    "CONE":    {"TUNE": .5, "SLIDE": .30, "WAVER": .45, "PICK": .75, "BODY": .60, "RING": .55, "HOLD": 0.},
    "BOTTLE":  {"TUNE": .5, "SLIDE": .40, "WAVER": .60, "PICK": .55, "BODY": .60, "RING": .85, "HOLD": 0.},
    "LAP":     {"TUNE": .5, "SLIDE": .55, "WAVER": .40, "PICK": .40, "BODY": .50, "RING": .65, "HOLD": 0.},
    "STEEL":   {"TUNE": .5, "SLIDE": .60, "WAVER": .30, "PICK": .30, "BODY": .50, "RING": .80, "HOLD": 0.},
    "FLATTOP": {"TUNE": .5, "SLIDE": .15, "WAVER": .15, "PICK": .45, "BODY": .70, "RING": .65, "HOLD": 0.},
    "GUT":     {"TUNE": .5, "SLIDE": .10, "WAVER": .45, "PICK": .35, "BODY": .60, "RING": .55, "HOLD": 0.},
}
# Roots where each instrument lives: the steel and slide references sit at a median 250 to
# 373 Hz (measure.py), the resonator references 73 to 368 Hz, the flat-top's boom at D3.
ROOT_MIDI = {"CONE": 57, "BOTTLE": 62, "LAP": 62, "STEEL": 64, "FLATTOP": 50, "GUT": 52}  # A3 D4 D4 E4 D3 E3
TUNE_SEMITONES = 24       # TUNE spans an octave either side of the root (house habit)
BASE_SECONDS = {"CONE": 2.5, "BOTTLE": 3.0, "LAP": 3.5, "STEEL": 4.0, "FLATTOP": 2.5, "GUT": 2.5}
MAX_SECONDS = 8.0         # HOLD's top: a finite long note (HALOBAR/ACCORD/OSTERIA policy)

# RING -> T60 of the fundamental, seconds (lo at RING 0, hi at RING 1, exponential).
# Anchors: the references' loudest isolated notes decay at -17 to -25 dB/s on the resonator
# loops (T60 2.4 to 3.5 s with the player damping) and -4 to -10 dB/s on the steels and the
# electric slide (T60 6 to 15 s). Measured by measure.py, section 2 of the spec.
T60 = {"CONE": (.8, 8.), "BOTTLE": (1.2, 9.), "LAP": (1.5, 10.), "STEEL": (2.5, 14.),
       "FLATTOP": (1.2, 8.), "GUT": (1., 6.)}
# Round 0b (owner, 20 Nov: "resonator could use more twang, slide a bit more whine"): CONE's
# and BOTTLE's loops lose far less top per trip, so the upper harmonics keep ringing.
# Round 0c (owner: "I did mean lap as well"): LAP's loop keeps its top too (4.5 to 8 kHz).
LOOP_HZ = {"CONE": 6500., "BOTTLE": 10000., "LAP": 8000., "STEEL": 8000., "FLATTOP": 3200., "GUT": 2600.}

# Body tables: (Hz, Q, gain). CONE's first four are the peaks measure.py found in all four
# resonator-guitar references (571-603, 764-872, 1050-1190 Hz, and 2347 Hz in one); the two
# high-Q modes are the cone and cover plate ringing (shape). FLATTOP's 431/598/727 Hz are the
# acoustic reference's peaks; the 100/205 Hz air and top modes are textbook dreadnought shape
# values. GUT's 425/501/764 Hz are the nylon references' peaks.
BODY_MODES = {
    "CONE": [(580, 6, 1.0), (820, 5, .8), (1180, 7, .7), (2350, 6, .8)],  # 2350 raised in 0b: the nasal twang band
    "FLATTOP": [(100, 10, .9), (205, 12, .8), (431, 6, .6), (598, 6, .45), (727, 7, .35), (1500, 5, .2)],
    "GUT": [(95, 10, .8), (190, 10, .7), (425, 6, .5), (501, 6, .45), (764, 6, .3)],
}
CONE_RING = [(3300, 25, .12), (4700, 30, .08)]  # the cone and cover plate ringing (shape)
CONE_ROLLOFF_HZ = 5500.   # the cone's mass roll-off; 3 kHz in round 0 took the twang with it
# Tension modulation, cents at full touch: CONE's metal fingerpicks on a high-action steel string
# pull hardest, so its attack starts sharpest and drops (the "boing" in a twang). Shape.
TENSION_CENTS = {"CONE": 28.}
TENSION_SECONDS = {"CONE": .08}
# BOTTLE's bar moves a little slower, so the glide itself is heard (round 0b, "whine").
SLIDE_TIME = {"BOTTLE": 1.35, "LAP": 1.25}
# Electric chain: pickup distance from the bridge as a fraction of the open string, coil
# resonance (Hz, Q), amp drive range over BODY, speaker low-pass. STEEL's 1.5 kHz presence
# peak follows the pedal-steel reference's 1.25-1.85 kHz peaks; LAP's dark coil follows the
# lap-steel reference's 561 Hz centroid.
# BOTTLE (0b): a high-Q coil peak at 2.6 kHz, the volume and tone knobs full up, is the slide's
# vocal whine; more amp drive holds the sustain up so the highs sing.
PICKUP = {"BOTTLE": (.07, 2600., 4.5, (2., 9.), 6000.),
          # LAP (0c): the same whine, kept the darker slide: a lower, softer coil peak than BOTTLE's
          "LAP": (.10, 2200., 3.5, (1.5, 6.), 5000.),
          "STEEL": (.08, 5000., 1.8, (.6, 2.), 7000.)}
CLICK = {"CONE": 1., "BOTTLE": .5, "LAP": .6, "STEEL": .6, "FLATTOP": .8, "GUT": 0.}  # metal/flat picks vs flesh

# PICK's burst low-pass range, Hz: plain steel strings under a hard pick on the electrics.
PICK_HZ = {"CONE": (700., 7000.), "FLATTOP": (700., 7000.), "GUT": (500., 4500.),
           "BOTTLE": (1500., 11000.), "LAP": (1500., 11000.), "STEEL": (6000., 12000.)}
# STEEL's low PICK means "swelled in under the volume pedal" (Performance.swell); a swelled
# note is as bright once it arrives, so its pick range has a high floor.
SCALE_M = .635            # scale length, m (shape: a common long scale)
RIB_M = .00035            # wound-string rib pitch, m (shape: a 0.35 mm winding wire)


def lin(x, lo, hi):
    return lo + (hi - lo) * x


def exp_map(x, lo, hi):
    return lo * (hi / lo) ** x


def hz(midi):
    return 440.0 * 2 ** ((midi - 69) / 12)


def settled(voice, macros):
    m = dict(DEFAULTS[voice])
    for k, v in (macros or {}).items():
        if k in m and v is not None and math.isfinite(v):
            m[k] = min(1.0, max(0.0, float(v)))
    return m


def midi_for(voice, tune):
    return ROOT_MIDI[voice] + int(round((tune - .5) * TUNE_SEMITONES))


def seconds_for(voice, hold):
    return BASE_SECONDS[voice] + (MAX_SECONDS - BASE_SECONDS[voice]) * hold


# ------------------------------------------------------------------ the string (one loop)

@njit(cache=True)
def string_loop(exc, exact, fb, lp_a, mode):
    """One waveguide string with a per-sample loop delay `exact` (samples) and loop gain `fb`.

    mode 0 is the house `Strings.Loop` read, retuned every sample the way SILK's slide does:
    integer delay n, a two-tap average, a first-order allpass for the fraction.
    mode 1 is the proposed glide read: a cubic Lagrange interpolator, no allpass, so the
    fractional delay carries no filter state across integer steps (the zipper risk).
    `exact` is already budgeted for the loop low-pass (and, in mode 0, the average)."""
    N = exc.shape[0]
    maxd = 0.0
    for i in range(N):
        if exact[i] > maxd:
            maxd = exact[i]
    size = int(maxd) + 8
    h = np.zeros(size)
    out = np.zeros(N)
    apx = 0.0
    apy = 0.0
    lp = 0.0
    for i in range(N):
        D = exact[i]
        n = int(math.floor(D))
        frac = D - n
        x = 0.0
        if mode == 0:
            if i > n + 1:
                d = 0.5 * (h[(i - n) % size] + h[(i - n - 1) % size])
                a = (1.0 - frac) / (1.0 + frac)
                tuned = a * (d - apy) + apx
                apx = d
                apy = tuned
                x = tuned
        else:
            if i > n + 3:
                f = frac
                xm1 = h[(i - n + 1) % size]
                x0 = h[(i - n) % size]
                x1 = h[(i - n - 1) % size]
                x2 = h[(i - n - 2) % size]
                x = (-f * (f - 1) * (f - 2) / 6.0) * xm1 + ((f + 1) * (f - 1) * (f - 2) / 2.0) * x0 \
                    + (-(f + 1) * f * (f - 2) / 2.0) * x1 + ((f + 1) * f * (f - 1) / 6.0) * x2
        lp += lp_a * (x - lp)
        y = exc[i] + fb[i] * lp
        h[i % size] = y
        out[i] = y
    return out


@njit(cache=True)
def svf_bandpass(x, fc, q):
    """Cytomic TPT SVF band-pass with a per-sample centre `fc` (Hz at R)."""
    N = x.shape[0]
    out = np.zeros(N)
    ic1 = 0.0
    ic2 = 0.0
    k = 1.0 / q
    for i in range(N):
        g = math.tan(math.pi * fc[i] / 176400.0)
        a1 = 1.0 / (1.0 + g * (g + k))
        a2 = g * a1
        a3 = g * a2
        v3 = x[i] - ic2
        v1 = a1 * ic1 + a2 * v3
        v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2 * v1 - ic1
        ic2 = 2 * v2 - ic2
        out[i] = v1
    return out


def lp_coef(loop_hz):
    return 1.0 - math.exp(-2 * math.pi * min(loop_hz, R * .45) / R)


def loop_budget(f, loop_hz, mode):
    """`Strings.tune`'s budget, vectorized over a pitch path: period minus the low-pass's
    phase delay at the fundamental, minus the average's half sample in the house read."""
    a = lp_coef(loop_hz)
    r = 1 - a
    w = 2 * np.pi * f / R
    filter_delay = np.arctan2(r * np.sin(w), 1 - r * np.cos(w)) / w
    exact = R / f - filter_delay - (0.5 if mode == 0 else 0.0)
    return exact


def loop_gain(f, t60, loop_hz, mode):
    """Loop gain per round trip so the fundamental falls 60 dB in `t60` seconds at every
    point of the path (the hand's loss is per second, not per trip)."""
    a = lp_coef(loop_hz)
    w = 2 * np.pi * f / R
    mag_lp = a / np.abs(1 - (1 - a) * np.exp(-1j * w))
    mag_avg = np.abs(np.cos(w / 2)) if mode == 0 else 1.0
    g = 10 ** (-3.0 / (t60 * f))
    return np.minimum(g / (mag_lp * mag_avg), .99995)


# ------------------------------------------------------------------ the pitch path

def smooth_noise(n, rate_hz, seed, control=1000):
    """A seeded smooth wander in [-1, 1] at about `rate_hz`, sampled at R."""
    rng = np.random.default_rng(seed)
    pts = max(4, int(n / R * rate_hz) + 4)
    knots = rng.uniform(-1, 1, pts)
    t = np.linspace(0, pts - 3, n)
    return np.interp(t, np.arange(pts), knots)


def bar_landing(extent_cents, dur, zeta, n):
    """The arm carrying the bar is a damped mass-spring aimed at the note: a step response
    from -extent to 0 with natural frequency set so it is ~90 % of the way at `dur`. With
    zeta 0.8 it overshoots by about 1.5 % and settles: the bar lands, then corrects."""
    ctrl = 2000.0
    steps = int(n / R * ctrl) + 2
    wn = 3.2 / max(dur, 1e-3)
    x, v = -extent_cents, 0.0
    out = np.empty(steps)
    dt = 1 / ctrl
    for i in range(steps):
        out[i] = x
        acc = wn * wn * (0 - x) - 2 * zeta * wn * v
        v += acc * dt
        x += v * dt
    return np.interp(np.arange(n) / R, np.arange(steps) / ctrl, out)


def pedal_ramp(extent_cents, start, dur, n):
    """A pedal travels at constant speed to a mechanical stop: no overshoot (the opposite of
    the bar). A 5 ms smoothing stands for the rod's give."""
    t = np.arange(n) / R
    p = np.clip((t - start) / max(dur, 1e-3), 0, 1)
    c = -extent_cents * (1 - p)
    k = math.exp(-1 / (.005 * R))
    return signal.lfilter([1 - k], [1, -k], c, zi=[c[0] * k])[0]


def frets(path_cents):
    """Fretted slides step a semitone at each fret; a 2 ms lag rounds each step."""
    q = np.round(path_cents / 100.0) * 100.0
    k = math.exp(-1 / (.002 * R))
    return signal.lfilter([1 - k], [1, -k], q, zi=[q[0] * k])[0]


def vibrato(n, onset, depth, rate, phase, seed, one_sided=False):
    """Hand vibrato after the landing: waits 150 ms, fades in over 350 ms, rate wanders 3 %.
    Bar vibrato is symmetric about the note (measured: the slide references sit centred,
    5.5-7.5 Hz, 140-190 cents peak to peak on single lines). A fretted bend can only go up."""
    t = np.arange(n) / R
    ramp = np.clip((t - onset - .15) / .35, 0, 1)
    ramp = .5 - .5 * np.cos(np.pi * ramp)
    wander = 1 + .03 * smooth_noise(n, 1.5, seed)
    ph = 2 * np.pi * np.cumsum(rate * wander) / R + phase
    s = np.sin(ph)
    if one_sided:
        s = .5 - .5 * np.cos(ph)
    return depth * ramp * s


class Performance:
    """The numbers one render plays: the macros' laws plus velocity and the take's variation.
    Takes never move the target pitch (house precedent: ACCORD's takes vary without detuning)."""

    def __init__(self, voice, m, velocity, take, midi):
        self.voice, self.m, self.velocity, self.take, self.midi = voice, m, velocity, take, midi
        rng = np.random.default_rng(sk.seed_for("DRAWL", voice, midi, take, "TAKE"))
        u = rng.uniform(-1, 1, 8) if take > 0 else np.zeros(8)
        self.seed = sk.seed_for("DRAWL", voice, midi, take)
        v = velocity
        pick = m["PICK"]
        # PICK: pick brightness and where it meets the string; velocity pushes both (timbre,
        # not only level: a harder pick is a shorter contact, so a brighter burst).
        lo_hz, hi_hz = PICK_HZ[voice]
        self.pick_hz = exp_map(pick, lo_hz, hi_hz) * 2 ** (2.5 * (v - .75)) * (1 + .10 * u[0])
        # Tension modulation: a hard pick stretches the string, so the note starts sharp and
        # settles as the swing dies (Pakarinen et al.); shape: 12 cents at full touch, 120 ms.
        self.tension_cents = TENSION_CENTS.get(voice, 12.0) * v * v * (1 + .1 * u[7])
        self.tension_seconds = TENSION_SECONDS.get(voice, .12)
        self.position = lin(pick, .30, .07) * (1 + .06 * u[1])
        self.click = CLICK[voice] * pick * v * v
        self.swell = lin(1 - pick, .02, .7) if voice == "STEEL" else 0.0
        slide, waver = m["SLIDE"], m["WAVER"]
        if voice in BAR:
            self.extent = 1200 * slide ** 1.3
            self.slide_dur = (.07 + .012 * self.extent / 100) * SLIDE_TIME.get(voice, 1.0) * (1 + .10 * u[2])
        elif voice in FRETTED:
            self.extent = 100 * round(3 * slide)
            self.slide_dur = (.04 + .03 * self.extent / 100) * (1 + .10 * u[2])
        else:  # STEEL: SLIDE is pedal travel (a half pedal mid-knob), 2 semitones at the top
            self.extent = 200 * slide
            self.slide_dur = .28 * (1 + .10 * u[2])
        self.zeta = .8 + .05 * u[3]
        depth_scale = {"STEEL": .6, "GUT": .45, "FLATTOP": .35}.get(voice, 1.0)
        # Round 0d (owner: "a tad shallower"): trimmed most where the shake is widest, 20 % at
        # full WAVER and about 10 % at the defaults, which already sat near the references'
        # 140-190 cents peak to peak while full WAVER reached 250-260.
        self.vib_depth = 150 * waver ** 1.3 * (1 - .2 * waver) * depth_scale * (1 + .08 * u[4])
        # A wider shake moves more of the arm, so the rate levels off as the depth grows (round 0c,
        # owner: the long slide's vibrato "a smidge slower"): 5.0 Hz still, 6.05 at .5, 6.8 at 1
        # (round 0: 7.6 at 1, the fast edge of the references' 5.9 to 7.5 Hz).
        self.vib_rate = (5.0 + 2.6 * waver - .8 * waver * waver) * (1 + .04 * u[5])
        self.vib_phase = math.pi * (1 + u[6])
        lo, hi = T60[voice]
        self.t60 = exp_map(m["RING"], lo, hi)
        self.loop_hz = LOOP_HZ[voice] * lin(m["RING"], .75, 1.25)
        self.ghost = .06 * m["RING"] ** 1.5 if voice in BAR else 0.0
        self.body = m["BODY"]
        self.seconds = seconds_for(voice, m["HOLD"])


def path_for(p: Performance, n, pedal_cents=None):  # -> (path, scraping part)
    """Cents against the target over the note. Bar voices: arm landing plus bar vibrato.
    Fretted: the same landing stepped over frets, then a finger vibrato. STEEL: the pedal
    ramp (`pedal_cents` per string in a chord) under a gentle bar vibrato after the swell."""
    v = p.voice
    if v == "STEEL":
        extent = p.extent if pedal_cents is None else pedal_cents
        start = max(.4, p.swell * .8)
        base = pedal_ramp(extent, start, p.slide_dur, n) if extent > 0 else np.zeros(n)
        onset = start + p.slide_dur
    else:
        base = bar_landing(p.extent, p.slide_dur, p.zeta, n) if p.extent > 0 else np.zeros(n)
        if v in FRETTED:
            base = frets(base)
        onset = p.slide_dur if p.extent > 0 else 0.05
    vib = vibrato(n, onset, p.vib_depth, p.vib_rate, p.vib_phase, p.seed + 7, one_sided=(v == "FLATTOP"))
    vib = vib + p.tension_cents * np.exp(-np.arange(n) / (p.tension_seconds * R))
    # What travels along the ribs: the slide itself, and a little of the vibrato (a bar
    # vibrato mostly rocks the contact rather than dragging it; 15 % is a shape value).
    return base + vib, base + .15 * vib


# ------------------------------------------------------------------ excitation

NOISE_SHARE = .25  # shape: how much of the pick is the house noise burst, the rest the pull


def pick_burst(f_start, p: Performance, n_total, seed):
    """A shaped pluck: the string pulled into a triangle that peaks at the pick position
    (harmonic k starts at sin(pi k p) / k^2, so the pick-position notches are built in and
    the fundamental is never left to chance), plus a NOISE_SHARE of `Strings.pluckExciter`'s
    combed noise burst for the pick's roughness, both low-passed by the pick's hardness, plus
    a metal pick's short high-passed click. The house exciter alone is all noise, which deals
    every harmonic a random level per seed: measured here, a LAP take's second harmonic came
    out 1 to 13 dB over its fundamental depending on the take (gen_audition, round 0)."""
    period = int(R / f_start)
    rng = np.random.default_rng(seed)
    k = math.exp(-2 * math.pi * p.pick_hz / R)
    x = np.arange(period) / period
    pos = min(max(p.position, .02), .5)
    # The bridge feels the string's slope, not its displacement: the triangle's slope is a
    # two-level pulse whose harmonic k starts at sin(pi k p) / k (the house differentiates
    # into the body for the same reason, `Strings.bodyRing(differentiate = true)`).
    pull = np.where(x < pos, 1 / pos, -1 / (1 - pos))
    pull = signal.lfilter([1 - k], [1, -k], pull - pull.mean())
    pull -= pull.mean()
    pull /= max(np.max(np.abs(pull)), 1e-9)
    raw = rng.uniform(-1, 1, period)
    raw = signal.lfilter([1 - k], [1, -k], raw)
    raw -= raw.mean()
    raw /= max(np.max(np.abs(raw)), 1e-9)
    d = max(1, int(round(pos * period)))
    combed = raw.copy()
    combed[d:] -= raw[:-d]
    combed /= max(np.max(np.abs(combed)), 1e-9)
    share = NOISE_SHARE * lin(p.velocity, .6, 1.4)  # a harder pick scrapes more as it leaves
    burst = (1 - share) * pull + share * combed
    exc = np.zeros(n_total)
    exc[:period] = burst[:period] * p.velocity
    if p.click > 0:
        cn = int(.0006 * R)
        c = rng.uniform(-1, 1, cn)
        c = signal.lfilter([1, -1], [1, -.6], c) * np.hanning(cn)
        exc[:cn] += .5 * p.click * c
    return exc


def scrape(path_cents, f_target, f_open, p: Performance, seed):
    """The bar riding over a wound string's ribs: noise band-passed at rib-passing frequency
    (bar speed / rib pitch), loud only while the bar moves. Vibrato makes a faint zing too.
    Only wound strings scrape: the lower strings (f under 260 Hz, shape)."""
    n = len(path_cents)
    f = f_target * 2 ** (path_cents / 1200)
    length = SCALE_M * f_open / f
    speed = np.abs(np.gradient(length)) * R  # m/s
    k = math.exp(-1 / (.003 * R))
    speed = signal.lfilter([1 - k], [1, -k], speed)
    fc = np.clip(speed / RIB_M, 150, 9000)
    wound = 1.0 if f_target < 260 else .25
    rng = np.random.default_rng(seed)
    noise = rng.uniform(-1, 1, n)
    band = svf_bandpass(noise, fc, 3.0)
    return band * np.minimum(speed / .5, 2.0) * .05 * wound


# ------------------------------------------------------------------ bodies and pickups

def body(voice, x, p: Performance):
    if voice in ELECTRIC:
        return electric(voice, x, p)
    modes = BODY_MODES[voice]
    mix = lin(p.body, .25, .9)
    y = x
    if voice == "CONE":
        # The biscuit drives the cone: an asymmetric soft curve whose drive rises with BODY,
        # and with velocity because x scales with the pick (shape constants).
        dr = lin(p.body, 1.0, 6.0)
        y = np.tanh(dr * (x + .25 * x * x)) / dr
        hp = signal.butter(2, 120 / (R / 2), "high", output="sos")
        y = signal.sosfilt(hp, y)  # the cone does not boom: the references hold ~0 below 100 Hz
    wet = np.zeros_like(y)
    for f0, q, g in modes:
        b, a = signal.iirpeak(f0, q, R)
        wet += g * signal.lfilter(b, a, y)
    out = (1 - mix) * y + mix * 2.5 * wet
    if voice == "CONE":
        # A spun aluminium cone is a mass: above a few kHz it stops following the bridge
        # (shape: CONE_ROLLOFF_HZ, second order). Its own ring and the cover plate sit on top.
        lp = signal.butter(2, CONE_ROLLOFF_HZ / (R / 2), output="sos")
        out = signal.sosfilt(lp, out)
        for f0, q, g in CONE_RING:
            b, a = signal.iirpeak(f0, q, R)
            out += mix * 2.5 * g * signal.lfilter(b, a, y)
    return out


def electric(voice, x, p: Performance):
    pos, coil_hz, coil_q, drive_range, spk_hz = PICKUP[voice]
    # A pickup at a fixed distance from the bridge combs the string with a fixed delay in
    # samples wherever the bar is (the stopped length times frequency is constant).
    f_open = p.f_open
    d = max(1, int(round(pos * R / f_open)))
    y = x.copy()
    y[d:] -= x[:-d]
    b, a = _rbj_lowpass(coil_hz, coil_q)
    y = signal.lfilter(b, a, y)
    if voice == "STEEL":
        pb, pa = signal.iirpeak(1500, 1.2, R)
        y = y + 1.6 * signal.lfilter(pb, pa, y)
    dr = lin(p.body, *drive_range)
    y = np.tanh(dr * y) / dr
    spk = signal.butter(2, [80 / (R / 2), spk_hz / (R / 2)], "band", output="sos")
    return signal.sosfilt(spk, y)


def _rbj_lowpass(fc, q):
    w = 2 * math.pi * fc / R
    alpha = math.sin(w) / (2 * q)
    b = np.array([(1 - math.cos(w)) / 2, 1 - math.cos(w), (1 - math.cos(w)) / 2])
    a = np.array([1 + alpha, -2 * math.cos(w), 1 - alpha])
    return b / a[0], a / a[0]


# ------------------------------------------------------------------ render

def string_voice(p: Performance, f_target, path, n, seed, delay_frames=0, mode=1, with_ghost=True, scraping=None):
    f = f_target * 2 ** (path / 1200)
    exact = loop_budget(f, p.loop_hz, mode)
    fb = loop_gain(f, p.t60, p.loop_hz, mode)
    exc = pick_burst(float(f[delay_frames]), p, n, seed)
    if delay_frames:
        exc = np.concatenate([np.zeros(delay_frames), exc[:-delay_frames]])
    if p.voice in BAR and scraping is not None:
        # Only a bar scrapes: a fretting finger lifts between frets and a pedal moves no contact.
        exc = exc + scrape(scraping, f_target, p.f_open, p, seed + 11)
    out = string_loop(exc, exact, fb, lp_coef(p.loop_hz), mode)
    if with_ghost and p.ghost > 0:
        # Behind the bar: the stretch between bar and nut rings too, fed weakly through the
        # bar (one-way, so it cannot feed the note back). Its pitch moves against the note's.
        frac = np.clip(p.f_open / f, 0, .97)  # the stopped share of the scale
        fb_hz = np.minimum(p.f_open / (1 - frac), 6000.0)
        g_exact = loop_budget(fb_hz, 5000.0, mode)
        g_fb = loop_gain(fb_hz, .6, 5000.0, mode)
        ghost = string_loop(out * p.ghost * .02, g_exact, g_fb, lp_coef(5000.0), mode)
        out = out + ghost
    return out


def render_raw(voice, macros=None, velocity=.75, take=0, intervals=None, sweep=.06, direction="DOWN",
               midi=None, mode=1):
    """Raw audio at R before the output stage. `intervals` makes a STRUM: one string per
    chord tone, picked `sweep` seconds apart, sharing the bar (BAR), the pedals (STEEL: inner
    tones rise into the chord) or no slide (fretted chords). Bodies are shared."""
    m = settled(voice, macros)
    midi = midi_for(voice, m["TUNE"]) if midi is None else midi
    p = Performance(voice, m, velocity, take, midi)
    n = int(p.seconds * R)
    f_target = hz(midi)
    if intervals is None:
        intervals = [0]
    tones = sorted(intervals) if direction == "DOWN" else sorted(intervals, reverse=True)
    lowest_cents = min(0.0, -p.extent) - 100
    p.f_open = hz(midi + min(intervals)) * 2 ** ((lowest_cents - (500 if p.voice in BAR else 0)) / 1200)
    total = np.zeros(n)
    chord = len(tones) > 1
    ordered = sorted(intervals)
    for k, iv in enumerate(tones):
        delay = int(round(sweep * R * k / max(1, len(tones) - 1))) if chord else 0
        pedal = None
        if voice == "STEEL" and chord:
            rank = ordered.index(iv)
            # Pedals raise the inner and top tones into the chord; the lowest holds.
            pedal = 0.0 if rank == 0 else p.extent * (1.0 if rank == len(ordered) - 1 else .5)
        if chord and voice in FRETTED:
            path = vibrato(n, .05, p.vib_depth, p.vib_rate, p.vib_phase, p.seed + 7, one_sided=voice == "FLATTOP")
            scraping = None
        else:
            path, scraping = path_for(p, n, pedal)
        total += string_voice(p, hz(midi + iv), path, n, p.seed + 101 * k, delay, mode,
                              with_ghost=not chord, scraping=scraping)
    if chord:
        total /= math.sqrt(len(tones))
    y = body(voice, total, p)
    if p.swell > 0:
        t = np.arange(n) / R
        y = y * (.5 - .5 * np.cos(np.pi * np.clip(t / p.swell, 0, 1)))
    return y, p


def condition(raw):
    """The house output stage up to levelling: bandLimit -> decimate -> DC (mean, 20 Hz HP)."""
    y = sk.band_limit(raw, R)
    y = sk.decimate4(y)
    y = y - y.mean()
    rc = 1 / (2 * math.pi * sk.OUTPUT_DC_HZ)
    al = rc / (rc + 1 / RATE)
    return signal.lfilter([al, -al], [1, -al], y)


_GAINS = {}
CEILING = .99


def performance_gain(voice, macros, midi, intervals, mode):
    """One gain per saved performance, shared across velocities and takes (the ACCORD rule),
    so a soft pick is quieter as well as darker. The medium-touch take 0 sets the level;
    the gain is lowered only if the hardest touch would cross the ceiling, so hard is never
    pulled back under medium (round 0 measured FLATTOP's hard 0.9 dB under its medium
    before this)."""
    key = (voice, tuple(sorted(settled(voice, macros).items())), midi, tuple(intervals or [0]), mode)
    if key not in _GAINS:
        raw, _ = render_raw(voice, macros, .75, 0, intervals, midi=midi, mode=mode)
        g = .1 / max(sk.rms(condition(raw)), 1e-12)
        hard, _ = render_raw(voice, macros, 1.0, 0, intervals, midi=midi, mode=mode)
        g = min(g, CEILING / max(float(np.max(np.abs(condition(hard)))), 1e-12))
        _GAINS[key] = g
    return _GAINS[key]


def render(voice, macros=None, velocity=.75, take=0, intervals=None, sweep=.06, direction="DOWN",
           midi=None, mode=1):
    m = settled(voice, macros)
    midi = midi_for(voice, m["TUNE"]) if midi is None else midi
    raw, p = render_raw(voice, m, velocity, take, intervals, sweep, direction, midi, mode)
    y = condition(raw) * performance_gain(voice, m, midi, intervals, mode)
    peak = float(np.max(np.abs(y)))
    if peak > CEILING:  # only a take hotter than take 0 at full touch can reach this
        y *= CEILING / peak
    return sk.fade_tail(y, RATE), p


def drum_class(seconds):
    """Pitched engines file TONAL or LOOP, never a drum: over 1.5 s the classifier says LOOP."""
    return "LOOP" if seconds > 1.5 else "TONAL"


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "drawl-out"
    os.makedirs(out, exist_ok=True)
    for v in VOICES:
        y, _ = render(v)
        sk.write_wav(os.path.join(out, f"{v.lower()}.wav"), y)
        print(v, f"{len(y) / RATE:.2f}s peak {np.max(np.abs(y)):.3f}")
