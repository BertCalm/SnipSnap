"""EMBER round 0: a live jazz and soul drum kit in a small club in 1962, from physical models.

Python harness, not Kotlin. Nobody has heard these renders yet. Every constant marked "shape" is a
starting value for a listening pass, not a measurement.

The characteristic gesture is: a stick, a brush or a hand meets calfskin or thin bronze -> the head
sags back to pitch and the snare wires and rivets answer on their own threshold -> the rest of the
kit hums along -> a low-ceilinged room and a warm 1962 chain close around it.

What makes it alive rather than sampled:

  * CALF HEADS. Every drum is a circular membrane (Bessel-zero mode ratios) whose high modes die
    much faster than its low ones (calfskin's loss law), whose pitch sags back after a hard hit
    (tension modulation), and whose second head beats against the first.
  * THRESHOLD PARTS. Snare wires, ride rivets, hi-hat plates and tambourine jingles move only when
    the surface under them moves hard enough. So a soft stroke is clean and a hard stroke sizzles:
    velocity changes what you hear, not only how loud it is. (CLUTTER's loose-parts idea, made
    continuous: the wires and rivets follow the ringing, not only the strike.)
  * THE KIT AS ONE INSTRUMENT. A kick or a tom sets off the snare wires across the kit (BUZZ).
  * IMPLEMENTS. Sticks (short Hertz contact), brushes (forty-odd wires landing over milliseconds, or
    dragged in circles), felt mallets, hands, a bow, an elbow on the head.
  * THE CLUB. A low ceiling, a hard bar and a soft crowd: a short, warm room (DISTANCE), then a
    ribbon mic, a valve preamp and tape (WARMTH; 0 is an exact bypass).

Signal flow per pad:

    voice, macros, velocity, take
        |
        v
    [implement: contact force @176.4 kHz; width and amount follow velocity; take moves the strike]
        |
        v
    [body: membrane modes (calf loss, sag, two heads) | cymbal modes in three bands, the top band
     growing in after the strike (wash) | wood and steel modes for sticks and hoops]
        |                         \
        |                          +--> [threshold parts: wires / rivets / plates / jingles, driven
        |                                by the body's own motion, one-way readout]
        v                                           |
        +--------------------- sum -----------------+
        |
        v
    [DISTANCE: direct + the club's response, cached by DISTANCE] -> [WARMTH: ribbon, valve, tape]
        -> band-limit, decimate to 44.1 kHz, DC, fade

One-way readouts: the wires and rivets never push back on the head or the cymbal. Disclosed; it
keeps every render a fixed cost.

The prototype draws noise from numpy's PCG64 seeded by `snipkit.seed_for`; the Kotlin port uses
`Dsp.Noise`. Same seeds, different streams: the Kotlin takes will be new takes, not the same ones.

Run:  python3 ember_proto.py OUT_DIR   (writes one WAV per voice at the defaults)
"""
from __future__ import annotations

import functools
import json
import math
import os
import sys

import numpy as np
from scipy import signal, special

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", ".claude", "skills", "luthier", "scripts"))
import snipkit as sk  # noqa: E402

RATE = sk.RATE      # 44 100
FS = sk.INTERNAL    # 176 400
TAU = 2 * math.pi

# ------------------------------------------------------------------------------ voices and controls

# voice -> (drum class, seconds, macros). Longer than 1.5 s only for the roll, the riser and the bow,
# which are declared PERC as BREAKER declares its riser.
VOICES = {
    "KICK":         ("KICK",       1.20, ("TUNE", "DAMP", "BUZZ", "DISTANCE", "WARMTH")),
    "SNARE":        ("SNARE",      0.90, ("TUNE", "DAMP", "BUZZ", "DISTANCE", "WARMTH")),
    "RIMSHOT":      ("SNARE",      0.90, ("TUNE", "DAMP", "BUZZ", "DISTANCE", "WARMTH")),
    "CROSS_STICK":  ("PERC",       0.55, ("TUNE", "DAMP", "BUZZ", "DISTANCE", "WARMTH")),
    "BRUSH_TAP":    ("SNARE",      0.80, ("TUNE", "DAMP", "BUZZ", "DISTANCE", "WARMTH")),
    "BRUSH_SWEEP":  ("PERC",       2.25, ("TUNE", "SPEED", "BUZZ", "DISTANCE", "WARMTH")),
    "TOM_RACK":     ("TOM",        1.30, ("TUNE", "DAMP", "BUZZ", "DISTANCE", "WARMTH")),
    "TOM_FLOOR":    ("TOM",        1.40, ("TUNE", "DAMP", "BUZZ", "DISTANCE", "WARMTH")),
    "ELBOW_TOM":    ("TOM",        1.40, ("TUNE", "BEND", "BUZZ", "DISTANCE", "WARMTH")),
    "MALLET_TOM":   ("TOM",        1.40, ("TUNE", "DAMP", "BUZZ", "DISTANCE", "WARMTH")),
    "BRUSH_TOM":    ("TOM",        1.20, ("TUNE", "DAMP", "BUZZ", "DISTANCE", "WARMTH")),
    "RIDE":         ("PERC",       1.45, ("TUNE", "DAMP", "BUZZ", "DISTANCE", "WARMTH")),
    "RIDE_BELL":    ("PERC",       1.45, ("TUNE", "DAMP", "BUZZ", "DISTANCE", "WARMTH")),
    "BRUSH_RIDE":   ("PERC",       1.45, ("TUNE", "DAMP", "BUZZ", "DISTANCE", "WARMTH")),
    "SIZZLE":       ("PERC",       2.20, ("TUNE", "DAMP", "DISTANCE", "WARMTH")),
    "HAT_FOOT":     ("HAT_CLOSED", 0.45, ("TUNE", "DAMP", "BUZZ", "DISTANCE", "WARMTH")),
    "HAT_CLOSED":   ("HAT_CLOSED", 0.45, ("TUNE", "DAMP", "BUZZ", "DISTANCE", "WARMTH")),
    "HAT_OPEN":     ("HAT_OPEN",   1.20, ("TUNE", "DAMP", "BUZZ", "DISTANCE", "WARMTH")),
    "BOWED_CYMBAL": ("PERC",       2.40, ("TUNE", "PRESSURE", "BUZZ", "DISTANCE", "WARMTH")),
    "CYMBAL_SWELL": ("PERC",       3.00, ("TUNE", "SPEED", "BUZZ", "DISTANCE", "WARMTH")),
    "PRESS_ROLL":   ("PERC",       2.60, ("TUNE", "SPEED", "BUZZ", "DISTANCE", "WARMTH")),
    "BRUSH_ROLL":   ("PERC",       2.60, ("TUNE", "SPEED", "BUZZ", "DISTANCE", "WARMTH")),
    "SNAPS":        ("CLAP",       0.60, ("TUNE", "CROWD", "DISTANCE", "WARMTH")),
    "CLAPS":        ("CLAP",       0.70, ("TUNE", "CROWD", "DISTANCE", "WARMTH")),
    "TAMBOURINE":   ("PERC",       0.80, ("TUNE", "DAMP", "BUZZ", "DISTANCE", "WARMTH")),
    "CONGA":        ("PERC",       0.90, ("TUNE", "DAMP", "SLAP", "DISTANCE", "WARMTH")),
    "STICKS":       ("PERC",       0.40, ("TUNE", "DAMP", "DISTANCE", "WARMTH")),
}

DEFAULTS = {"TUNE": .5, "DAMP": .3, "BUZZ": .5, "DISTANCE": .3, "WARMTH": .5, "SPEED": .5,
            "BEND": .5, "PRESSURE": .5, "CROWD": .4, "SLAP": .3}

# Per-voice defaults (round 0b): on the fitted cymbals and hats BUZZ adds looseness beyond the recorded
# cymbal, so it starts at 0, the cymbal as recorded.
VOICE_DEFAULTS = {v: {"BUZZ": 0.0} for v in ("RIDE", "RIDE_BELL", "BRUSH_RIDE", "BOWED_CYMBAL", "CYMBAL_SWELL",
                                               "HAT_FOOT", "HAT_CLOSED", "HAT_OPEN")}

# What each control means, per voice where it differs (the audition page reads this).
MEANING = {
    "TUNE": "head tension or cymbal size, about +-5 semitones",
    "DAMP": "a felt strip, a wallet on the snare, a finger on a cymbal, the hi-hat pedal pressure",
    "BUZZ": "how loose the snare wires, rivets, plates and jingles are, and how much the kit hums along",
    "DISTANCE": "the microphone, from over the drum to the back of the club",
    "WARMTH": "the 1962 chain: ribbon mic, valve preamp, tape (0 is a clean modern mic)",
    "SPEED": "how fast the brush circles, the roll strokes or the mallets come",
    "BEND": "how far the elbow presses the head after the hit",
    "PRESSURE": "how hard the bow bites the cymbal edge",
    "CROWD": "one person to a table of six, each a little early or late",
    "SLAP": "open palm tone to a cupped slap",
}


def macros_for(voice):
    return VOICES[voice][2]


def settled(voice, macros=None):
    """Clamp to 0..1, drop unknown keys, a non-finite value falls back to its default (the house rule)."""
    out = {}
    for k in macros_for(voice):
        v = (macros or {}).get(k)
        d = VOICE_DEFAULTS.get(voice, {}).get(k, DEFAULTS[k])
        out[k] = d if v is None or not math.isfinite(v) else min(1.0, max(0.0, float(v)))
    return out


def rng_for(*parts):
    return np.random.default_rng(sk.seed_for(*parts))


def semis(x, span):
    """TUNE 0..1 -> a frequency ratio, `span` semitones either side of 0.5."""
    return 2.0 ** ((x - .5) * 2 * span / 12)


# ------------------------------------------------------------------------------ small DSP

def n_of(seconds):
    return int(round(seconds * FS))


def hann_force(width_s, amount, n, at_s=0.0):
    """A raised-cosine contact force with unit area times `amount` (an impulse J), at `at_s`."""
    w = max(3, int(width_s * FS))
    f = np.zeros(n)
    i = int(at_s * FS)
    if i >= n:
        return f
    p = 0.5 * (1 - np.cos(TAU * np.arange(w) / w))
    p *= amount / p.sum() * 40.0     # 40: keeps a 1 ms unit pulse near unit peak at 176.4 kHz (shape)
    e = min(n, i + w)
    f[i:e] += p[:e - i]
    return f


def modal_ir(freqs, amps, t60s, n, phases=None, warp=None):
    """Sum of decaying sines at FS: sum a_k exp(-6.9 t / t60_k) sin(2 pi f_k warp(t) + ph_k).
    `warp` is the time the phase runs on (t for a fixed pitch; its integral for a gliding one)."""
    t = np.arange(n) / FS
    w = t if warp is None else warp
    freqs, amps, t60s = (np.asarray(x, float) for x in (freqs, amps, t60s))
    ph = np.zeros(len(freqs)) if phases is None else np.asarray(phases, float)
    keep = (freqs < 19_500) & (freqs > 15) & (t60s > 0) & (amps != 0)
    freqs, amps, t60s, ph = freqs[keep], amps[keep], t60s[keep], ph[keep]
    out = np.zeros(n)
    for s in range(0, len(freqs), 12):
        sl = slice(s, s + 12)
        # Only as long as the slowest mode in the block needs (to -80 dB).
        m = min(n, int(np.max(t60s[sl]) * 80 / 60 * FS) + 1)
        env = np.exp(-6.9078 * t[None, :m] / t60s[sl, None])
        out[:m] += (amps[sl, None] * env * np.sin(TAU * freqs[sl, None] * w[None, :m] + ph[sl, None])).sum(0)
    return out


def sag_warp(n, amount, tau):
    """Tension modulation: the pitch starts `amount` sharp and settles with time constant `tau`.
    Phase time = integral of (1 + amount e^(-t/tau)) dt, in closed form."""
    t = np.arange(n) / FS
    return t + amount * tau * (1 - np.exp(-t / tau))


def conv(x, h, n):
    return signal.oaconvolve(x, h)[:n]


def env_follow(x, ms):
    a = math.exp(-1.0 / (ms * 1e-3 * FS))
    return signal.lfilter([1 - a], [1, -a], np.abs(x))


def band(x, lo, hi, order=2):
    sos = signal.butter(order, [lo / (FS / 2), min(hi, 0.45 * FS) / (FS / 2)], "band", output="sos")
    return signal.sosfilt(sos, x)


def hp(x, hz, order=2):
    return signal.sosfilt(signal.butter(order, hz / (FS / 2), "high", output="sos"), x)


def lp(x, hz, order=2):
    return signal.sosfilt(signal.butter(order, min(hz, 0.45 * FS) / (FS / 2), output="sos"), x)


def peak_norm(x):
    p = np.max(np.abs(x))
    return x / p if p > 0 else x


# ------------------------------------------------------------------------------ the membrane

@functools.cache
def bessel_table(count=20):
    """(ratio to the (0,1) mode, m, n, zero) for the lowest `count` modes of an ideal circular membrane."""
    rows = []
    for m in range(0, 10):
        for k, z in enumerate(special.jn_zeros(m, 5)):
            rows.append((float(z), m, k + 1))
    rows.sort()
    rows = rows[:count]
    j01 = rows[0][0]
    return tuple((z / j01, m, nn, z) for z, m, nn in rows)


def membrane_ir(f1, n, t1, alpha, r, angle, sag=0.0, sag_tau=0.05, reso=(1.025, 0.35), count=18,
                jitter_seed=None, bend=None, scatter=None):
    """The impulse response of a calf-headed drum struck at radius r (0 centre .. 1 edge).

    t60 of mode k = t1 * (f_k / f1)^-alpha: calf's high modes die far faster than its low ones
    (alpha ~ 1.1 for calf against ~ 0.6 for a plastic head; shape). Strike weight is |J_m(z r)|
    times cos(m angle); radiation favours the m = 0 modes (shape). The second head (`reso`: ratio,
    gain) rings the same modes a little higher, so the drum beats as it sustains."""
    tab = bessel_table(count)
    js = np.random.default_rng(jitter_seed).normal(0, 0.006, len(tab)) if jitter_seed is not None else np.zeros(len(tab))
    fr, am, tt, ph = [], [], [], []
    for (ratio, m, nn, z), j in zip(tab, js):
        f = f1 * ratio * (1 + j)
        wgt = abs(special.jv(m, z * r)) * (1.0 if m == 0 else math.cos(m * angle) ** 2 * 0.8 + 0.2)
        rad = 1.0 if m == 0 else (0.55 if m == 1 else 0.35)
        a = wgt * rad / (1 + 0.15 * nn)
        if scatter is not None:            # a take: the stick's angle and bounce excite each mode a little differently
            a *= 10 ** (scatter.normal(0, 1.5) / 20)
        t60 = t1 * (ratio ** -alpha)
        fr.append(f); am.append(a); tt.append(t60); ph.append(0.0)
        if reso is not None and ratio < 3.2:
            fr.append(f * reso[0]); am.append(a * reso[1]); tt.append(t60 * 1.15); ph.append(0.4)
    if bend is not None:
        warp = np.cumsum(bend) / FS
    elif sag > 0:
        warp = sag_warp(n, sag, sag_tau)
    else:
        warp = None
    return modal_ir(fr, am, tt, n, ph, warp)


# ------------------------------------------------------------------------------ implements

def stick(v, rng, n, width_hard=0.28e-3, at=0.0):
    """A hickory tip: a short Hertz-like contact. Softer is longer (darker) and much weaker.
    Contact time ~ v^-0.3 (Hertz gives v^-0.2; widened for audibility, shape)."""
    w = width_hard * v ** -0.3 * (1 + 0.07 * rng.standard_normal())
    return hann_force(w, v ** 1.25, n, at)


def mallet(v, rng, n, at=0.0, width_hard=1.6e-3):
    w = width_hard * v ** -0.35 * (1 + 0.06 * rng.standard_normal())
    return hann_force(w, v ** 1.3, n, at)


def brush_tap(v, rng, n, at=0.0):
    """A wire brush: 25 to 65 wires land over 2.5 to 7 ms (soft spreads them and lands fewer)."""
    k = int(25 + 40 * v)
    spread = (7.0 - 4.5 * v) * 1e-3
    times = at + np.sort(rng.gamma(2.0, spread / 4, k))
    f = np.zeros(n)
    for t, a in zip(times, rng.uniform(0.3, 1.0, k)):
        f += hann_force(0.07e-3, a * v ** 1.4 / k * 6, n, t)
    return f


def hand(v, rng, n, at=0.0, cupped=0.0):
    """A palm: a broad push plus finger slaps; cupping makes the slap sharper."""
    f = hann_force((2.4 - 1.2 * cupped) * 1e-3 * v ** -0.2, v ** 1.2, n, at)
    for i in range(4):
        f += hann_force(0.35e-3, (0.15 + 0.5 * cupped) * v ** 1.6 * rng.uniform(.6, 1), n, at + 0.0006 * i + rng.uniform(0, 4e-4))
    return f


# ------------------------------------------------------------------------------ threshold parts

def wires(head, v, buzz, rng, n, sym=1.0):
    """Snare wires. They sit on the bottom head and slap it whenever the head swings away faster
    than their tension holds them: a noise burst on each half cycle the head's motion exceeds the
    threshold. Loose wires (BUZZ high) lift sooner, ring longer and darker. `head` is the body's
    low-mode motion, peak-normalised to the hardest hit by the caller. One-way: they never damp it."""
    if buzz <= 0:
        return np.zeros(n)                       # BUZZ 0: snares off, an exact bypass
    theta = sk.lin(buzz, 0.42, 0.05)
    lowpart = lp(head, 700)
    drive = np.maximum(0.0, np.abs(lowpart) - theta) * sym
    drive = env_follow(drive, sk.lin(buzz, 1.5, 6.0))
    grit = rng.standard_normal(n) * (rng.random(n) < 0.35)
    tone_hi = sk.lin(buzz, 9500, 5200)
    x = band(grit * drive, 2200 - 900 * buzz, tone_hi) + 0.35 * band(grit * drive, 5500, 12000)
    return x * min(1.0, buzz / 0.1)              # the strainer lets the wires down gently near 0


def rattlers(drive_env, count, theta_lo, theta_hi, rate_hz, ring, rng, n, kick_noise=0.4):
    """Rivets, jingles, a loose top hat plate: `count` parts each lifting when the drive envelope
    passes its own threshold, then landing at a rate that grows with the excess. Each landing is
    a tiny ring (`ring`: list of (hz, t60)) plus a grain of broadband noise. Vectorised impact
    scheduling: integrate the rate, impacts at every whole count, jittered."""
    out = np.zeros(n)
    if count <= 0:
        return out
    ring_ir = modal_ir([h for h, _ in ring], [1.0] * len(ring), [t for _, t in ring], n_of(0.03))
    grain_n = n_of(0.0015)
    for i in range(count):
        th = rng.uniform(theta_lo, theta_hi)
        ex = np.maximum(0.0, drive_env - th)
        rate = rate_hz * np.sqrt(ex / max(1e-9, 1 - th)) * rng.uniform(0.8, 1.25)
        phase = np.cumsum(rate) / FS + rng.random()
        idx = np.nonzero(np.diff(np.floor(phase)) > 0)[0]
        if not len(idx):
            continue
        idx = np.clip(idx + rng.integers(-40, 40, len(idx)), 0, n - 1)
        imp = np.zeros(n)
        imp[idx] = ex[idx] * rng.uniform(0.5, 1.0, len(idx))
        part = conv(imp, ring_ir, n)
        grains = conv(imp, rng.standard_normal(grain_n) * np.hanning(grain_n), n)
        out += part + kick_noise * hp(grains, 3000)
    return out


# ------------------------------------------------------------------------------ cymbals (round 0b: fitted)
#
# Round 0's cymbals were guessed (modes placed by a formula) and the owner heard them as "hitting sheet
# metal". Measured, they sat 11 to 34 dB from real cymbals with half the brightness. From round 0b every
# cymbal and hat is resynthesized from a CC0 recording of a jazz club kit, the way BREAKER's approved
# cymbals are (docs/BREAKER.md, "The cymbals"): `fit_ember_cymbals.py` runs BREAKER's fitter and writes
# `cymbals.json` (measured numbers only; NOTICE.txt lists the recordings). The modes are rendered at
# 44.1 kHz (a sum of sines is band-limited by construction) and upsampled into the 176.4 kHz chain.

FIT_PATH = os.path.join(HERE, "cymbals.json")


@functools.cache
def fit():
    with open(FIT_PATH) as fh:
        return json.load(fh)


@functools.cache
def fit_band_sos(i):
    fc = fit()["bands"][i]
    lo, hi = fc / 2 ** (1 / 6), min(fc * 2 ** (1 / 6), RATE * 0.49)
    return signal.butter(4, [lo / (RATE / 2), hi / (RATE / 2)], "band", output="sos")


def soft_weight(v):
    """0 at a full hit, 1 at the recordings' soft layer (velocity .35), a little past it below that."""
    return float(np.clip((1.0 - v) / 0.65, 0.0, 1.3))


def damp_factor(damp):
    """How much faster every mode decays: DAMP .3 (the default) is the cymbal as recorded, 1 is three
    times faster (a finger on the edge), 0 is a little longer (0.64x the decay rate). Shape."""
    return 2.0 ** ((damp - 0.3) / 0.7 * 1.6)


def fitted_modes(kind, tune, damp, v, tr, seconds, split_hz=None):
    """The fitted cymbal struck once: every mode at its measured frequency, amplitude and decay, moved
    by TUNE (3 semitones either way), DAMP and the measured soft-minus-hard tilt of its band. A take is a
    fresh strike (BREAKER's law): up to 0.3 Hz of detune, a fresh phase, 1.6 dB rms level scatter.
    Normalized to unit rms over its first 300 ms. With `split_hz`, returns (below, above) that frequency."""
    m = np.array(fit()["voices"][kind]["modes"])
    k = len(m)
    hz = m[:, 0] * semis(tune, 3) + tr.uniform(-0.3, 0.3, k)
    amp = m[:, 1] * 10 ** ((m[:, 3] * soft_weight(v) + tr.normal(0, 1.6, k)) / 20)
    slope = np.minimum(m[:, 2] * damp_factor(damp), -1.0)             # dB per second
    # Doublets: a circular plate's modes come in degenerate pairs, and hammering splits each pair by a few
    # hertz, so a real cymbal's partials beat and shimmer instead of standing as steady lines. Each fitted
    # mode becomes a pair 0.4 to 3 Hz apart sharing its energy. The split belongs to the cymbal (its own
    # seed), not to the take, so every take is the same cymbal. Shape: the fit measured single peaks at
    # 5.4 Hz resolution, which cannot see the split.
    cr = np.random.default_rng(sk.seed_for("ember-doublets", kind))
    split = cr.uniform(0.4, 3.0, k) * semis(tune, 3)
    share = cr.uniform(0.2 * math.pi, 0.3 * math.pi, k)
    hz = np.concatenate([hz, hz + split])
    amp = np.concatenate([amp * np.cos(share), amp * np.sin(share)])
    slope = np.concatenate([slope, slope * cr.uniform(0.9, 1.1, k)])
    k = 2 * k
    ph = tr.uniform(0, TAU, k)
    n = int(round(seconds * RATE))
    t = np.arange(n) / RATE
    ok = hz < 0.47 * RATE

    def synth(sel):
        out = np.zeros(n)
        idx = np.nonzero(sel & ok)[0]
        for s0 in range(0, len(idx), 24):
            j = idx[s0:s0 + 24]
            lim = min(n, int(90.0 / -slope[j].max() * RATE) + 1)        # each block to -90 dB
            out[:lim] += (amp[j, None] * 10 ** (slope[j, None] * t[None, :lim] / 20)
                          * np.sin(TAU * hz[j, None] * t[None, :lim] + ph[j, None])).sum(0)
        return out

    if split_hz is None:
        y = synth(np.ones(k, bool))
        return y / (sk.rms(y[:int(0.3 * RATE)]) + 1e-20)
    lo, hi = synth(hz < split_hz), synth(hz >= split_hz)
    r = sk.rms((lo + hi)[:int(0.3 * RATE)]) + 1e-20
    return lo / r, hi / r


def stick_burst(kind, v, tr, n):
    """The stick's own 60 ms of noise at the recording's band levels over its first 40 ms, moving toward
    the soft recording's spectrum as velocity falls (BREAKER's law). Unit rms over 20 ms."""
    d = fit()["voices"][kind]
    w = min(1.0, soft_weight(v))
    lv = (1 - w) * np.array(d["stick"]) + w * np.array(d["stickSoft"])
    m = int(0.06 * RATE)
    tt = np.arange(m) / RATE
    b = np.zeros(m)
    for i, level in enumerate(lv):
        b += level * signal.sosfilt(fit_band_sos(i), tr.standard_normal(m))
    b *= np.exp(-tt / 0.008) * np.minimum(1, tt / 0.0004)
    out = np.zeros(n)
    out[:m] = b[:n]
    return out / (sk.rms(b[:int(0.02 * RATE)]) + 1e-20)


def envelope_hat(kind, tune, damp, v, tr, n):
    """A closed or pedalled hat from its fitted band envelopes: unit band noise in each third octave,
    shaped by the recording's level in that band over time, between the hard and soft recordings.
    TUNE slides the spectrum by up to 3 semitones; DAMP shortens it. A take is fresh noise and 1 dB of
    band scatter."""
    d = fit()["voices"][kind]
    bands = fit()["bands"]
    w = soft_weight(v)
    lev = (1 - w) * np.array(d["hard"]) + w * np.array(d["soft"])
    shift = (tune - 0.5) * 2 * 3 / 4                                 # third-octave bands to slide by
    idx = np.arange(len(bands))
    lev = np.array([np.interp(idx - shift, idx, lev[:, j]) for j in range(lev.shape[1])]).T
    T = np.array(d["times"])
    t = np.arange(n) / RATE * damp_factor(damp)
    out = np.zeros(n)
    for i, fc in enumerate(bands):
        if fc * 2 ** (1 / 6) >= 0.49 * RATE:
            continue
        x = signal.sosfilt(fit_band_sos(i), tr.standard_normal(n + 2000))[2000:]
        x /= sk.rms(x) + 1e-20
        out += x * 10 ** ((np.interp(t, T, lev[i]) + tr.normal(0, 1.0)) / 20)
    return out


def residual_noise(kind, tune, damp, v, tr, n, ref_rms):
    """The fitted residual: band noise along what the modes and stick leave out of the recording, per third
    octave over time, between hard and soft. Bands under 200 Hz are left out: there the overhead heard the
    kick and the room, not the cymbal. Levels are dB re the strike's rms over its first 0.5 s (`ref_rms`)."""
    d = fit()["voices"][kind]
    bands = fit()["bands"]
    w = min(1.0, soft_weight(v))
    lev = (1 - w) * np.array(d["residual"]) + w * np.array(d["residualSoft"])
    shift = (tune - 0.5) * 2 * 3 / 4
    idx = np.arange(len(bands))
    lev = np.array([np.interp(idx - shift, idx, lev[:, j]) for j in range(lev.shape[1])]).T
    T = np.array(d["times"])
    t = np.arange(n) / RATE * damp_factor(damp)
    out = np.zeros(n)
    for i, fc in enumerate(bands):
        if fc < 200 or fc * 2 ** (1 / 6) >= 0.49 * RATE:
            continue
        x = signal.sosfilt(fit_band_sos(i), tr.standard_normal(n + 2000))[2000:]
        x /= sk.rms(x) + 1e-20
        out += x * 10 ** (np.interp(t, T, lev[i]) / 20)
    return out * ref_rms


def to_internal(y44, n):
    """44.1 kHz into the 176.4 kHz chain (polyphase, band-limited)."""
    y = signal.resample_poly(y44, 4, 1)
    return y[:n] if len(y) >= n else np.pad(y, (0, n - len(y)))


def to_rate(x):
    """A 176.4 kHz contact force down to 44.1 kHz, keeping each impulse's area (sum of four)."""
    k = len(x) // 4 * 4
    return x[:k].reshape(-1, 4).sum(1)


def struck(kind, m, v, tr, n, burst=0.6, seconds=None):
    """A stick strike on a fitted cymbal: the modes plus the stick, at FS. Returns (sound, drive env)."""
    sec = seconds or n / FS
    y = fitted_modes(kind, m.get("TUNE", .5), m.get("DAMP", .3), v, tr, sec)
    y = y + burst * stick_burst(kind, v, tr, len(y))
    y = y + residual_noise(kind, m.get("TUNE", .5), m.get("DAMP", .3), v, tr, len(y), sk.rms(y[:int(0.5 * RATE)]))
    out = to_internal(y, n)
    return out, env_follow(lp(out, 2500), 6.0)


def driven(kind, m, v, tr, n, force, seconds=None, split_hz=None):
    """A fitted cymbal driven by any implement's force (brush, mallet, bow): the force at 44.1 kHz
    convolved with the struck modes. With `split_hz`, returns (below, above) at FS."""
    sec = seconds or n / FS
    f44 = to_rate(force)
    k = int(round(sec * RATE))
    ir = fitted_modes(kind, m.get("TUNE", .5), m.get("DAMP", .3), v, tr, sec, split_hz)
    if split_hz is None:
        return to_internal(signal.oaconvolve(f44, ir)[:k], n)
    return tuple(to_internal(signal.oaconvolve(f44, x)[:k], n) for x in ir)


# Threshold parts are scaled to the body they sit on, so a soft hit's wires are quieter than a hard
# hit's in proportion as well as in level. Calibrated once at the defaults (Python, round 0):
# WIRE_GAIN puts a hard centre snare hit's wires at about 0.4 of the head's RMS over 200 ms;
# RIVET_GAIN puts the ride's sizzle at about 0.35 of the ride's RMS. Shape.
WIRE_GAIN = 9.0
RIVET_GAIN = 0.17


def rivets(drive, buzz, v, rng, n, scale=1.0):
    """Six to ten loose rivets in the ride's edge: the classic sizzle that follows the ride and
    stops before the ride does. BUZZ 0 takes them out (an exact bypass)."""
    count = int(round(sk.lin(buzz, 0, 10)))
    if count == 0:
        return np.zeros(n)
    d = drive / (np.max(drive) + 1e-12) * min(1.0, 0.35 + 0.65 * v)
    return rattlers(d, count, 0.10, 0.45, 520 * scale, [(8200, .006), (11300, .004), (6400, .008)], rng, n, 0.6)


# ------------------------------------------------------------------------------ the club and the chain

@functools.cache
def room_ir(distance):
    """The club, cached by DISTANCE: a low ceiling (~2.6 m), a hard bar along one wall, a brick
    back wall, a soft crowd. Early reflections at fixed delays (the room's geometry, shape), then a
    two-band tail: lows ring ~0.62 s, highs ~0.32 s (people and drapes eat the top; shape)."""
    r = np.random.default_rng(sk.seed_for("ember-club"))
    n = n_of(0.9)
    h = np.zeros(n)
    for ms, g, cut in ((7.6, .55, 5000), (11.2, .42, 7000), (16.8, .38, 4000), (23.5, .30, 6000),
                       (29.0, .22, 3500), (37.0, .20, 4500), (44.0, .15, 3000), (53.0, .12, 3500)):
        i = int(ms * 1e-3 * FS)
        tap = np.zeros(n_of(0.004)); tap[0] = g
        tap = lp(tap, cut, 1)
        h[i:i + len(tap)] += tap
    t = np.arange(n) / FS
    lo = lp(r.standard_normal(n), 900) * np.exp(-6.9078 * t / 0.62)
    hi = hp(r.standard_normal(n), 900) * np.exp(-6.9078 * t / 0.32)
    onset = np.clip((t - 0.012) / 0.03, 0, 1)
    h += (lo * 0.09 + hi * 0.05) * onset * 1.6
    h = lp(h, 9000, 1)
    # The direct path: louder and brighter close up; across the room it is quieter, later and darker.
    direct = np.zeros(n_of(0.01)); direct[int(distance * 0.006 * FS)] = 1.0
    direct = lp(direct, sk.exp_map(distance, 19000, 5200), 1)
    # Balance by energy, not by peak: the direct-to-room ratio runs from +16 dB over the drum to
    # -4 dB at the back of the club (shape; a small, dead-ish room with the mic close is +5 to +10).
    drr_db = sk.lin(distance, 16.0, -4.0)
    out = np.zeros(n)
    out[:len(direct)] += direct / math.sqrt(np.sum(direct ** 2))
    out += h / math.sqrt(np.sum(h ** 2)) * 10 ** (-drr_db / 20)
    return out


def warmth_chain(x, warmth, v):
    """WARMTH: a ribbon mic (a gentle top roll-off and a low lift), a valve preamp (asymmetric soft
    clip, mostly second harmonic) driven harder by harder hits, and tape (a head bump near 70 Hz
    and a soft top). WARMTH 0 is an exact bypass: the function returns its input."""
    if warmth <= 0:
        return x
    y = lp(x, sk.exp_map(warmth, 16000, 6800), 2)
    y = y + warmth * 0.25 * lp(y, 160, 1)
    pk = np.max(np.abs(y)) + 1e-12
    drive = 0.6 + 3.0 * warmth * (0.4 + 0.6 * v)
    bias = 0.18 * warmth
    z = y / pk * drive
    z = (np.tanh(z + bias) - math.tanh(bias)) / drive * pk
    y = (1 - warmth) * y + warmth * z
    b, a = peaking(70.0, 2.8 * warmth, 0.9)
    y = signal.lfilter(b, a, y)
    return lp(y, sk.exp_map(warmth, 18000, 9000), 1)


def peaking(f0, gain_db, q):
    A = 10 ** (gain_db / 40)
    w = TAU * f0 / FS
    al = math.sin(w) / (2 * q)
    b = np.array([1 + al * A, -2 * math.cos(w), 1 - al * A])
    a = np.array([1 + al / A, -2 * math.cos(w), 1 - al / A])
    return b / a[0], a / a[0]


def finish(raw, m, v, seconds):
    """DISTANCE -> WARMTH -> the house output stage, at 44.1 kHz, peak 0.89 (the audition match)."""
    n = len(raw)
    y = conv(raw, room_ir(round(m["DISTANCE"], 3)), n)
    y = warmth_chain(y, m["WARMTH"], v)
    y = signal.resample_poly(y, 1, 4)
    y = y - np.mean(y)
    rc = 1 / (TAU * sk.OUTPUT_DC_HZ)
    al = rc / (rc + 1 / RATE)
    y = signal.lfilter([al, -al], [1, -al], y)
    out = int(seconds * RATE)
    y = y[:out]
    fade = min(len(y), int(0.06 * RATE))
    y[-fade:] *= 0.5 * (1 + np.cos(np.linspace(0, math.pi, fade)))
    return peak_norm(y) * 0.89


# ------------------------------------------------------------------------------ the voices

def _drum(f1, t1, alpha, v, take_rng, n, r_base, implement, m, snare=False, sym_wires=0.0,
          sag_max=0.04, extra=None):
    """Shared drum path: implement -> calf membrane (sag grows with v^2) -> optional wires."""
    r = float(np.clip(r_base + take_rng.normal(0, 0.05), 0.0, 0.95))
    ang = take_rng.uniform(0, math.pi)
    ir = membrane_ir(f1, n, t1, alpha, r, ang, sag=sag_max * v * v, sag_tau=0.04 + 0.02 * take_rng.random(),
                     jitter_seed=None)
    ir = peak_norm(ir)
    force = implement
    head = conv(force, ir, n)
    out = head.copy()
    if snare or sym_wires > 0:
        ref = 1.0 if snare else sym_wires
        out += (0.55 if snare else 0.25) * ref * wires(head / (np.max(np.abs(head)) + 1e-12) * (v ** 0.7), v,
                                                      m.get("BUZZ", .5), take_rng, n)
    if extra is not None:
        out += extra
    return out


def render(voice, macros=None, velocity=1.0, take=0):
    """One hit of `voice` at `velocity` (0..1), round-robin take `take`, at 44.1 kHz, peak 0.89."""
    if voice not in VOICES:
        raise ValueError(voice)
    v = 1.0 if velocity is None or not math.isfinite(velocity) else float(np.clip(velocity, 0.05, 1.0))
    m = settled(voice, macros)
    seconds = seconds_for(voice, m)
    n = n_of(seconds + 0.05)
    tr = rng_for("ember", voice, take)       # what changes between takes
    raw = RENDER[voice](m, v, tr, n)
    return finish(raw, m, v, seconds)


def _kick(m, v, tr, n):
    """An 18-inch bass drum, calf both sides, no port, a felt beater: tuned up and left open, so
    it booms with a pitch, the way it was played under the band ('feathered' at low velocity)."""
    f1 = 66.0 * semis(m["TUNE"], 6)
    t1 = sk.lin(m["DAMP"], 0.95, 0.16)
    beater = mallet(v, tr, n, width_hard=1.3e-3)
    air = hann_force(9e-3, 0.5 * v ** 1.6, n)            # the air the beater pushes through the shell
    out = _drum(f1, t1, 1.25, v, tr, n, 0.12, beater + air, m, sym_wires=0.0, sag_max=0.07)
    # The kit hums along: the snare wires across the kit and the floor tom answer the kick.
    if m["BUZZ"] > 0:
        head = lp(out, 300)
        out += 0.03 * m["BUZZ"] * WIRE_GAIN * np.max(np.abs(out)) * wires(head / (np.max(np.abs(head)) + 1e-12) * v, v, 0.8, tr, n)
        tom = peak_norm(membrane_ir(104.0, n, 0.9, 1.1, 0.3, 0.7))
        out += 0.10 * m["BUZZ"] * peak_norm(conv(lp(out, 200), tom, n)) * v
    click = hp(hann_force(0.5e-3, 0.06 * v ** 2, n), 1500)
    return out + click


def _snare_core(m, v, tr, n, force, r_base, wire_gain=1.0):
    """The snare: calf head plus wires. The wires' drive is the head's low motion measured against
    one hard stick stroke on this same head, so a roll's early strokes engage the wires the way a
    single stroke of that strength would (not against the roll's own loudest moment). The drive is
    compressed (power 0.6): wires answer ghost notes too, more gently."""
    f1 = 285.0 * semis(m["TUNE"], 5)
    t1 = sk.lin(m.get("DAMP", .3), 0.42, 0.07)
    r = float(np.clip(r_base + tr.normal(0, 0.07), 0.0, 0.95))
    ir = peak_norm(membrane_ir(f1, n, t1, 1.15, r, tr.uniform(0, math.pi), sag=0.03 * v * v,
                               sag_tau=0.04 + 0.02 * tr.random(), scatter=tr))
    head = conv(force, ir, n)
    k = n_of(0.08)
    ref_full = conv(hann_force(0.28e-3, 1.0, k), ir[:k], k)
    ref_peak = np.max(np.abs(ref_full))
    x = lp(head, 900) / (np.max(np.abs(lp(ref_full, 900))) + 1e-12)
    hd = np.sign(x) * np.abs(x) ** 0.6
    return head + wire_gain * WIRE_GAIN * ref_peak * wires(hd, v, m["BUZZ"], tr, n)


def _snare(m, v, tr, n):
    force = stick(v, tr, n)
    crack = hp(force, 2500) * 0.18           # the stick on the coated head, heard directly
    return _snare_core(m, v, tr, n, force, 0.28) + crack


def _rimshot(m, v, tr, n):
    force = stick(v, tr, n, width_hard=0.2e-3)
    hoop = modal_ir([960, 2210, 3720, 5300], [1, .7, .5, .3], [.09, .06, .045, .03], n)
    rim = conv(force, peak_norm(hoop), n)
    return _snare_core(m, v, tr, n, force * 1.2, 0.85) + 0.9 * rim + hp(force, 2000) * 0.35


def _cross_stick(m, v, tr, n):
    """The stick laid across the head, the butt clicking the hoop: wood and steel, with the head
    and a whisper of wires under it."""
    force = stick(v, tr, n, width_hard=0.22e-3)
    s = semis(m["TUNE"], 5)
    # Where the butt lands on the hoop and how the stick lies across the head: that is the take.
    wood = modal_ir(np.array([505, 1390, 2730, 4500]) * s * tr.uniform(.97, 1.03),
                    np.array([1, .8, .45, .2]) * tr.uniform(0.5, 1.5, 4),
                    np.array([.05, .035, .02, .012]) * sk.lin(m["DAMP"], 1.4, 0.55), n)
    hoop = modal_ir(np.array([1180, 1960, 2840, 3950]) * s, np.array([.6, 1, .5, .3]) * tr.uniform(0.4, 1.6, 4),
                    np.array([.07, .06, .04, .03]) * sk.lin(m["DAMP"], 2.0, 0.3), n)
    knock = conv(force, peak_norm(wood) + 0.7 * peak_norm(hoop), n)
    head = _snare_core(m, v, tr, n, force * 0.25, 0.6, wire_gain=0.4)
    return knock + 0.35 * head


def _brush_tap(m, v, tr, n):
    force = brush_tap(v, tr, n)
    scrape_len = n_of(0.025 + 0.02 * (1 - v))
    sc = tr.standard_normal(scrape_len) * np.linspace(1, 0, scrape_len) ** 2
    scrape = np.zeros(n); scrape[:scrape_len] = sc
    scrape = band(scrape, 2500, 11000) * 0.05 * v
    return _snare_core(m, v, tr, n, force, 0.3, wire_gain=0.8) + scrape


def sweep_seconds(speed):
    """One full circle: 1.9 s at SPEED 0 (a ballad's slow stir, long enough to join the next) to 0.5 s."""
    return sk.lin(speed, 1.9, 0.5)


def seconds_for(voice, m):
    """Render length. Fixed per voice, except the sweep, whose length follows its circle."""
    if voice == "BRUSH_SWEEP":
        return round(sweep_seconds(m["SPEED"]) + 0.35, 3)
    return VOICES[voice][1]


def _sweep_force(speed_curve, pressure, tr, n, grain=6000):
    """Friction of brush wires on a coated head: a cloud of micro-slips whose rate follows the
    sweep speed, pushed by the pressure."""
    rate = grain * speed_curve
    events = tr.random(n) < rate / FS
    amp = tr.uniform(0.2, 1.0, n) * events * pressure
    return amp


def _brush_sweep(m, v, tr, n):
    """A brush circling on the snare head: one full circle, faster in the middle, the wires
    hissing on the coating and the head and wires answering underneath. SPEED is the circle's
    speed (and so how long it takes)."""
    t = np.arange(n) / FS
    dur = sweep_seconds(m["SPEED"]) * tr.uniform(0.97, 1.0)
    x = np.clip(t / dur, 0, 1)
    speed = np.sin(math.pi * x) ** 1.3 * (0.75 + 0.25 * np.cos(TAU * 2 * x + tr.uniform(0, 1)))
    speed[t > dur] = 0
    press = 0.4 + 0.6 * v
    slips = _sweep_force(speed, press, tr, n, grain=5000 + 6000 * m["SPEED"])
    # The hiss: the coating, brighter as the brush speeds up.
    hiss = band(slips, 1200 + 1400 * (1 - v), 9000) + (0.3 + 0.5 * v) * band(slips, 4000, 12000) * speed
    # The head underneath, struck from a position that circles (centre <-> edge crossfade).
    f1 = 285.0 * semis(m["TUNE"], 5)
    irc = peak_norm(membrane_ir(f1, n, 0.25, 1.15, 0.2, 0.3))
    ire = peak_norm(membrane_ir(f1, n, 0.25, 1.15, 0.75, 1.1))
    lowf = lp(slips, 600) * 30
    circle = 0.5 + 0.5 * np.sin(TAU * x + tr.uniform(0, TAU))
    head = conv(lowf * circle, irc, n) + conv(lowf * (1 - circle), ire, n)
    hd = peak_norm(lp(head, 900)) * 0.6 * v
    # Pressure (velocity) pushes the head and the wires up under the hiss: a light sweep is air,
    # a heavy one has a body.
    return (0.55 * peak_norm(hiss) + 0.45 * v ** 1.2 * peak_norm(head)
            + 0.6 * v * WIRE_GAIN * wires(hd, v, m["BUZZ"], tr, n))


def _tom(m, v, tr, n, f_base, implement, r_base, t1_max, bend=None):
    f1 = f_base * semis(m["TUNE"], 6)
    t1 = sk.lin(m.get("DAMP", .3), t1_max, 0.15)
    r = float(np.clip(r_base + tr.normal(0, 0.05), 0, .95))
    if bend is not None:
        ir = membrane_ir(f1, n, t1, 1.05, r, tr.uniform(0, math.pi), bend=bend)
    else:
        ir = membrane_ir(f1, n, t1, 1.05, r, tr.uniform(0, math.pi), sag=0.045 * v * v, sag_tau=0.05, scatter=tr)
    head = conv(implement, peak_norm(ir), n)
    sym = m.get("BUZZ", .5)
    if sym > 0:   # the snare wires across the kit answer the tom
        hd = peak_norm(lp(head, 700)) * v * 0.7
        head = head + 0.06 * sym * WIRE_GAIN * np.max(np.abs(head)) * wires(hd, v, 0.75, tr, n)
    return head


def _tom_rack(m, v, tr, n):
    f = stick(v, tr, n)
    return _tom(m, v, tr, n, 172.0, f, 0.3, 0.95) + hp(f, 2500) * 0.12


def _tom_floor(m, v, tr, n):
    f = stick(v, tr, n)
    return _tom(m, v, tr, n, 104.0, f, 0.3, 1.25) + hp(f, 2500) * 0.1


def _mallet_tom(m, v, tr, n):
    return _tom(m, v, tr, n, 138.0, mallet(v, tr, n, width_hard=2.4e-3), 0.4, 1.3)


def _brush_tom(m, v, tr, n):
    return _tom(m, v, tr, n, 118.0, brush_tap(v, tr, n), 0.35, 1.0)


def _elbow_tom(m, v, tr, n):
    """Hit the floor tom, then lean an elbow into the head: the pitch climbs and the ring
    shortens. BEND is how far (0 to 5 semitones; shape)."""
    t = np.arange(n) / FS
    start = 0.06 + tr.uniform(0, 0.02)
    sm = np.clip((t - start) / 0.16, 0, 1)
    sm = sm * sm * (3 - 2 * sm)
    semi = sk.lin(m["BEND"], 0.4, 5.0)
    curve = 2 ** (semi * sm / 12)
    f = stick(v, tr, n)
    body = _tom(dict(m, DAMP=.25), v, tr, n, 104.0, f, 0.3, 1.25, bend=curve)
    return body * (1 - 0.35 * sm) + hp(f, 2500) * 0.1


def _ride(m, v, tr, n):
    """The flat ride, as fitted: a dry, spread jazz ride with no bell. BUZZ adds loose rivets on top of
    the recorded cymbal (0, the default, is the cymbal as recorded)."""
    sound, drive = struck("flatride", m, v, tr, n)
    return sound + RIVET_GAIN * np.max(np.abs(sound)) * rivets(drive, m["BUZZ"], v, tr, n)


def _ride_bell(m, v, tr, n):
    sound, drive = struck("bell", m, v, tr, n)
    return sound + 0.6 * RIVET_GAIN * np.max(np.abs(sound)) * rivets(drive, m["BUZZ"], v, tr, n)


def _sizzle(m, v, tr, n):
    """A thin crash with its rivets in, as fitted from the recording: the riveted sizzle cymbal of a 1960s
    jazz kit, with the rivets in the recording rather than modelled."""
    sound, _ = struck("sizzle", m, v, tr, n)
    return sound


def _brush_ride(m, v, tr, n):
    """Wire brush on the flat ride: the brush's spray of wires through the fitted modes, no stick, and the
    wires' own scrape."""
    force = brush_tap(v, tr, n)
    sound = driven("flatride", m, v, tr, n, force)
    k = n_of(0.03)
    scrape = np.zeros(n)
    scrape[:k] = tr.standard_normal(k) * np.linspace(1, 0, k) ** 2
    sound = peak_norm(sound) + 0.08 * v * peak_norm(band(scrape, 3000, 12000))
    drive = env_follow(lp(sound, 2500), 6.0)
    return sound + 1.3 * RIVET_GAIN * rivets(drive, m["BUZZ"], v, tr, n)


def _hat_chatter(sound, m, v, tr, n, rate):
    """BUZZ on the hats: the plates looser than recorded, chattering where they touch (0 is as recorded)."""
    count = int(round(sk.lin(m["BUZZ"], 0, 4)))
    if count == 0:
        return sound
    d = env_follow(lp(sound, 4000), 3.0)
    d = d / (np.max(d) + 1e-12) * (0.5 + 0.5 * v)
    chatter = rattlers(d, count, 0.05, 0.3, rate, [(7400, .004), (10200, .003)], tr, n, 0.8)
    return sound + 1.2 * RIVET_GAIN * np.max(np.abs(sound)) * chatter


def _hat_closed(m, v, tr, n):
    sound = to_internal(envelope_hat("hat_closed", m["TUNE"], m["DAMP"], v, tr, int(n / 4) + 1), n)
    return _hat_chatter(sound, m, v, tr, n, 1600)


def _hat_foot(m, v, tr, n):
    """The pedal brings the plates together, the 'chick' on 2 and 4, as fitted from the recording."""
    sound = to_internal(envelope_hat("hat_pedal", m["TUNE"], m["DAMP"], v, tr, int(n / 4) + 1), n)
    return _hat_chatter(sound, m, v, tr, n, 1200)


def _hat_open(m, v, tr, n):
    sound, _ = struck("hat_open", m, v, tr, n)
    return _hat_chatter(sound, m, v, tr, n, 900)


def _bowed_cymbal(m, v, tr, n):
    """A bass bow drawn across the flat ride's edge: stick-slip locks to the cymbal's strongest fitted mode
    between 500 Hz and 1.6 kHz and the rest bloom around it. The bow is a sawtooth-like slip train at that
    mode with jitter (an analogy of friction; the Kotlin can use Modes.Friction). PRESSURE: more bite,
    more harmonics, more scratch."""
    t = np.arange(n) / FS
    md = np.array(fit()["voices"]["flatride"]["modes"])
    sel = (md[:, 0] > 500) & (md[:, 0] < 1600)
    f_lock = md[sel][np.argmax(md[sel, 1]), 0] * semis(m["TUNE"], 3) * tr.uniform(0.998, 1.002)
    jitter = np.cumsum(tr.normal(0, 1, n)) / FS * 0.8
    ph = TAU * f_lock * (t + jitter * 0.001)
    bite = sk.lin(m["PRESSURE"], 0.3, 1.0)
    saw = np.sin(ph) + bite * 0.5 * np.sin(2 * ph) + bite * 0.33 * np.sin(3 * ph) + bite * 0.25 * np.sin(4 * ph + 0.3)
    swell = np.clip(t / (0.5 - 0.25 * v), 0, 1) ** 1.5 * np.clip((2.3 - t) / 0.35, 0, 1)
    wob = 1 + 0.15 * np.sin(TAU * 4.3 * t + tr.uniform(0, TAU))
    scratch = band(tr.standard_normal(n), 2000, 7000) * bite * 0.03
    drive_sig = (saw * 0.004 + scratch * 0.01) * swell * wob * v
    sound = driven("flatride", dict(m, DAMP=.3), v, tr, n, drive_sig)
    direct = lp(saw, 6000) * swell * wob * 0.25 * np.max(np.abs(sound))
    out = sound + direct
    drive = env_follow(lp(out, 2500), 6.0)
    return out + 0.7 * RIVET_GAIN * np.max(np.abs(out)) * rivets(drive, m["BUZZ"], v * 0.6, tr, n)


def _cymbal_swell(m, v, tr, n):
    """The riser: soft felt mallets rolling on the flat ride, from nothing to a full wash over 2.6 s. The
    modes above 6 kHz follow the running intensity squared (a cymbal's top grows with how hard it is
    driven; an analogy, not the nonlinear cascade itself)."""
    t_end = 2.6
    rate0, rate1 = sk.lin(m["SPEED"], 9, 16), sk.lin(m["SPEED"], 15, 26)
    force = np.zeros(n)
    tt = 0.0
    k = 0
    while tt < t_end:
        x = tt / t_end
        a = (0.12 + 0.88 * x ** 1.6) * v
        force += mallet(max(0.08, a), tr, n, at=tt, width_hard=2.8e-3) * (1.0 if k % 2 else 0.85)
        tt += 1.0 / sk.lin(x, rate0, rate1) * tr.uniform(0.9, 1.1)
        k += 1
    intensity = env_follow(force, 120.0)
    intensity = intensity / (np.max(intensity) + 1e-12) * v      # a soft swell never reaches the full wash
    low, top = driven("flatride", dict(m, DAMP=.3), v, tr, n, force, split_hz=6000.0)
    top2 = driven("flatride", dict(m, DAMP=.3), v, tr, n, force * intensity ** 2, split_hz=6000.0)[1]
    body = low + 0.4 * top + 1.6 * top2
    drive = env_follow(lp(body, 2500), 6.0)
    return body + 0.9 * RIVET_GAIN * np.max(np.abs(body)) * rivets(drive, m["BUZZ"], v, tr, n)


def _roll(m, v, tr, n, brush=False):
    """Percussion gate: an authored roll. Sticks: buzz strokes, each hand pressing three to six
    bounces into the head, the hands alternating faster as the roll crescendos into an accent.
    Brushes: the brush version, wires on wires. 2.6 s with the accent at 2.3 s."""
    t_end = 2.3
    rate0, rate1 = sk.lin(m["SPEED"], 9, 14), sk.lin(m["SPEED"], 14, 22)
    force = np.zeros(n)
    tt, k = 0.02, 0
    while tt < t_end - 0.04:
        x = tt / t_end
        a = (0.15 + 0.85 * x ** 1.8) * v * (1.0 if k % 2 == 0 else 0.88)
        if brush:
            force += brush_tap(max(0.08, a), tr, n, at=tt) * 1.2
        else:
            bounces = tr.integers(3, 7)
            for b in range(bounces):
                force += stick(max(0.08, a * 0.62 ** b), tr, n, at=tt + b * tr.uniform(0.006, 0.009))
        tt += 1.0 / sk.lin(x, rate0, rate1) * tr.uniform(0.92, 1.08)
        k += 1
    accent = brush_tap(v, tr, n, at=t_end) * 2 if brush else stick(v, tr, n, at=t_end) * 1.6
    force += accent
    return _snare_core(m, v, tr, n, force, 0.35, wire_gain=1.0) + hp(force, 2500) * (0.05 if brush else 0.12)


def _press_roll(m, v, tr, n):
    return _roll(m, v, tr, n, brush=False)


def _brush_roll(m, v, tr, n):
    return _roll(m, v, tr, n, brush=True)


def _crowd(m, v, tr, n, clap):
    """A table of listeners. Each has their own hands (cavity pitch), timing and strength.
    Snaps: a finger cracks onto the palm, a small bright pop. Claps: two palms, a lower, longer
    air pocket, sometimes a double strike."""
    people = 1 + int(round(m["CROWD"] * 5))
    out = np.zeros(n)
    s = semis(m["TUNE"], 5)
    # A soft cue: only the keenest join, and lightly. A hard one: the whole table.
    joining = 1 + int(round((people - 1) * (0.25 + 0.75 * v)))
    for p in range(joining):
        pr = np.random.default_rng(sk.seed_for("ember-crowd", clap, p))   # the same people every take
        at = 0.004 + (0 if p == 0 else abs(tr.normal(0, 0.012)) + pr.uniform(0, 0.01))
        a = (1.0 if p == 0 else pr.uniform(0.35, 0.8)) * v ** (1.2 if p else 1.0)
        if clap:
            cav = pr.uniform(800, 1500) * s
            width = n_of(pr.uniform(0.012, 0.022))
            q = 3.0
        else:
            cav = pr.uniform(1700, 2700) * s
            width = n_of(pr.uniform(0.006, 0.011))
            q = 5.0
        burst = tr.standard_normal(width) * np.exp(-np.arange(width) / (width / 3))
        x = np.zeros(n)
        i = int(at * FS)
        x[i:i + width] = burst[:max(0, min(width, n - i))]
        b, aa = signal.iirpeak(cav / (FS / 2), q)
        body = signal.lfilter(b, aa, x)
        crack = hp(hann_force((0.12 if not clap else 0.25) * 1e-3, 1.0, n, at), 1800) * (0.5 + 0.5 * v)
        if clap and pr.random() < 0.4:   # the flat double clap
            crack += 0.5 * np.roll(crack, n_of(pr.uniform(0.004, 0.009)))
        out += a * (peak_norm(body) + 0.8 * peak_norm(crack) * v ** 1.5 + 0.15 * peak_norm(hp(x, 3000)))
    return out


def _snaps(m, v, tr, n):
    return _crowd(m, v, tr, n, False)


def _claps(m, v, tr, n):
    return _crowd(m, v, tr, n, True)


def _tambourine(m, v, tr, n):
    """A calf-headed tambourine struck on the palm: the head thumps and eight jingle pairs fly,
    landing and bouncing (each bounce lower by the restitution) for a couple of hundred ms."""
    s = semis(m["TUNE"], 4)
    force = hand(v, tr, n, cupped=0.4)
    head = conv(force, peak_norm(membrane_ir(310 * s, n, sk.lin(m["DAMP"], .12, .04), 1.2, 0.5, 0.3)), n)
    t = np.arange(n) / FS
    drive = np.exp(-t / sk.lin(m["DAMP"], 0.11, 0.05)) * v
    pairs = 4 + int(round(m["BUZZ"] * 6))
    jingles = rattlers(drive, pairs, 0.02, 0.35, 260 + 300 * v,
                       [(5600 * s, .035), (8100 * s, .025), (11800 * s, .018), (4300 * s, .05)], tr, n, 0.3)
    return 0.4 * peak_norm(head) + peak_norm(jingles)


def _conga(m, v, tr, n):
    """A rawhide conga, open tone off the edge with the hand; SLAP cups the hand for a cracking
    slap (more finger, less palm, the edge less damped)."""
    f1 = 196.0 * semis(m["TUNE"], 6)
    slap = m["SLAP"]
    force = hand(v, tr, n, cupped=slap)
    t1 = sk.lin(m["DAMP"], 0.55, 0.12) * (1 - 0.5 * slap)
    ir = membrane_ir(f1, n, t1, 1.35, 0.78 + tr.normal(0, .03), tr.uniform(0, math.pi), sag=0.02 * v * v, reso=None)
    head = conv(force, peak_norm(ir), n)
    crack = hp(force, 2200) * 0.5 * slap
    shell = conv(force, peak_norm(modal_ir([410 * semis(m["TUNE"], 6)], [1], [0.06], n)), n) * 0.2
    return head + crack + shell


def _sticks(m, v, tr, n):
    """Two hickory sticks clicked together (the count-in): free-free beams, ~330 and 345 Hz with
    their overtones at 2.76x, 5.40x, 8.93x (beam theory for a 40 cm, 14 mm dowel; shape)."""
    s = semis(m["TUNE"], 5)
    force = stick(v, tr, n, width_hard=0.12e-3)
    damp = sk.lin(m["DAMP"], 1.4, 0.4)
    out = np.zeros(n)
    # Where the sticks meet along their length changes which overtones ring: that is the take.
    for f0, g in ((330 * s * tr.uniform(.985, 1.015), 1.0), (345 * s * tr.uniform(.985, 1.015), 0.8)):
        r = np.array([1, 2.756, 5.404, 8.933]) * f0
        # A harder click is a stiffer, shorter contact: the upper overtones come up with it.
        w = np.array([.5, 1, .7 * v ** 0.8, .4 * v ** 1.5]) * tr.uniform(0.35, 1.6, 4)
        out += g * conv(force, peak_norm(modal_ir(r, w, np.array([.09, .05, .03, .02]) * damp, n)), n)
    return out + hp(force, 3000) * 0.4


RENDER = {
    "KICK": _kick, "SNARE": _snare, "RIMSHOT": _rimshot, "CROSS_STICK": _cross_stick, "BRUSH_TAP": _brush_tap,
    "BRUSH_SWEEP": _brush_sweep, "TOM_RACK": _tom_rack, "TOM_FLOOR": _tom_floor, "ELBOW_TOM": _elbow_tom,
    "MALLET_TOM": _mallet_tom, "BRUSH_TOM": _brush_tom, "RIDE": _ride, "RIDE_BELL": _ride_bell,
    "BRUSH_RIDE": _brush_ride, "SIZZLE": _sizzle, "HAT_FOOT": _hat_foot, "HAT_CLOSED": _hat_closed, "HAT_OPEN": _hat_open,
    "BOWED_CYMBAL": _bowed_cymbal, "CYMBAL_SWELL": _cymbal_swell, "PRESS_ROLL": _press_roll,
    "BRUSH_ROLL": _brush_roll, "SNAPS": _snaps, "CLAPS": _claps, "TAMBOURINE": _tambourine,
    "CONGA": _conga, "STICKS": _sticks,
}
assert set(RENDER) == set(VOICES)


if __name__ == "__main__":
    import time
    out = sys.argv[1] if len(sys.argv) > 1 else "ember-voices"
    os.makedirs(out, exist_ok=True)
    for name in VOICES:
        t0 = time.perf_counter()
        y = render(name)
        dt = (time.perf_counter() - t0) * 1000
        sk.write_wav(os.path.join(out, name.lower() + ".wav"), y)
        print(f"{name:13s} {len(y) / RATE:5.2f} s  {dt:7.0f} ms  peak {np.max(np.abs(y)):.3f}")
