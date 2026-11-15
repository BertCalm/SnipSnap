"""CALABASH round 0: drum kits for Afrobeat, Afrobeats, Mbalax and Afro-Cuban jazz.

Python harness, not Kotlin. Nobody has heard these renders yet. Every constant marked "shape" is a starting value for
a listening pass, not a measurement. CALABASH is a working name (the gourd under the shekere, and under many West
African resonators); it is not an engine name anywhere in the repository.

The characteristic gesture is: a palm, fingertips or a thin stick lands on a skin, a bell or a gourd -> the stroke
decides which of the instrument's own resonances ring (an open tone, a choked slap, a pressed stick, a squeezed
talking drum bending as it rings) -> the hand, the bead net or the jingles answer -> the room and the era's tape
close around it.

Where each voice comes from (the honesty line the owner's gates ask for):

  * FITTED. Quinto, conga, tumba, both bongos, two cowbells, both agogo bells, claves, the shaker and the claps are
    resynthesized from CC0 recordings (VCSL; fit_calabash.py, hands.json, NOTICE.txt): each recording's own
    resonances with their decays, its pitch glide, and what those leave out (the hand's slap, the room) as band
    noise. Their open and muffled strokes are the recorded strokes. Measured against the recordings (check_fit.py):
    2.1 to 6.8 dB over the first 150 ms, where two real takes of one drum measure 4.0 to 8.6 dB apart.
  * BUILT ON A FIT. The conga and bongo slap and bass tones are the fitted head reshaped by the stroke (the heel
    chokes the fundamental, the cupped fingers crack the upper modes; the palm in the centre keeps the low modes).
    VCSL has no slap recording, so these are shape, not measured.
  * PHYSICAL, NOT FITTED (no CC0 recording found): the shekere (a bead net on a gourd), the talking drums (an
    hourglass drum squeezed while it rings), the sabar family (pegged goatskin on tall open shells, hand and stick),
    the timbales (a thin head on a steel shell, and the shell itself), the kit drums (EMBER's membrane and
    implements), the programmed kick.
  * REUSED. Hats from EMBER's fit of a jazz club kit (Virtuosity Drums, CC0); ride and crash from BREAKER's
    approved fit; snaps from EMBER's crowd. Imported, not copied: `ember_proto` is the toolkit.

Every voice has ROOM and AGE:

  * ROOM: from a dead booth over the drum (0) to the back of a big live studio (1): a sized early-reflection pattern
    and a two-band tail, cached by ROOM.
  * AGE: the era's chain, EMBER's ribbon, valve and tape (round 0e, which the owner heard as "Much better"). 0 is an
    exact bypass, a clean modern mic; the Afrobeats kit sits near 0, the 1950s New York kit highest.

Signal flow per pad:

    voice, macros, velocity, take
        |
        v
    [stroke: hand (palm, fingertips, cupped), stick (thin galan, curved talking-drum stick, timbale stick), bead
     net, or the recording's own strike; width and force follow velocity; the take moves where it lands]
        |
        v
    [body: fitted modes + residual @44.1 kHz, upsampled | membrane modes with a pitch path (squeeze, sag) | pipe
     or cavity under the head | steel shell rings | gourd and its air]
        |
        v
    [ROOM (cached)] -> [AGE: ribbon, valve, tape] -> band-limit, decimate to 44.1 kHz, DC, fade, peak 0.89

The prototype draws noise from numpy's PCG64 seeded by `snipkit.seed_for`; a Kotlin port uses `Dsp.Noise`. Same
seeds, different streams: Kotlin takes will be new takes, not these.

Run:  python3 calabash_proto.py OUT_DIR   (writes one WAV per voice at its defaults)
"""
from __future__ import annotations

import functools
import json
import math
import os
import sys

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.append(os.path.join(HERE, "..", "ember"))   # after this folder, so kits.py is CALABASH's, not EMBER's
import ember_proto as e  # noqa: E402  (the shared toolkit: membranes, implements, fitted cymbals, the 1962 chain)
import fitted as F  # noqa: E402

sk = e.sk
RATE = e.RATE      # 44 100
FS = e.FS          # 176 400
TAU = 2 * math.pi
semis = e.semis
n_of = e.n_of

# ------------------------------------------------------------------------------ voices and controls

STD = ("ROOM", "AGE")

# voice -> (drum class, seconds, macros). Over 1.5 s only for the cymbals and the four rolls, declared PERC as BREAKER
# declares its crash and riser.
VOICES = {
    # the kit
    "KICK":        ("KICK",       0.90, ("TUNE", "DAMP", "BEATER") + STD),
    "DEEP_KICK":   ("KICK",       1.00, ("TUNE", "DAMP", "CLICK") + STD),
    "SNARE":       ("SNARE",      0.80, ("TUNE", "DAMP", "WIRES") + STD),
    "RIMSHOT":     ("SNARE",      0.80, ("TUNE", "DAMP", "WIRES") + STD),
    "RIM":         ("PERC",       0.45, ("TUNE", "DAMP") + STD),
    "TOM":         ("TOM",        1.20, ("TUNE", "DAMP") + STD),
    "HAT_CLOSED":  ("HAT_CLOSED", 0.45, ("TUNE", "DAMP") + STD),
    "HAT_OPEN":    ("HAT_OPEN",   1.20, ("TUNE", "DAMP") + STD),
    "HAT_FOOT":    ("HAT_CLOSED", 0.45, ("TUNE", "DAMP") + STD),
    "RIDE":        ("PERC",       2.20, ("TUNE", "DAMP") + STD),
    "CRASH":       ("PERC",       2.60, ("TUNE", "DAMP") + STD),
    "CLAP":        ("CLAP",       0.60, ("TUNE", "CROWD") + STD),
    "SNAP":        ("CLAP",       0.55, ("TUNE", "CROWD") + STD),
    "SHAKER":      ("PERC",       0.50, ("TUNE", "STROKE") + STD),
    # hand drums (fitted)
    "QUINTO":      ("PERC",       0.90, ("TUNE", "DAMP", "STROKE") + STD),
    "CONGA":       ("PERC",       0.95, ("TUNE", "DAMP", "STROKE") + STD),
    "TUMBA":       ("PERC",       1.05, ("TUNE", "DAMP", "STROKE") + STD),
    "BONGO_HIGH":  ("PERC",       0.70, ("TUNE", "DAMP", "STROKE") + STD),
    "BONGO_LOW":   ("PERC",       0.75, ("TUNE", "DAMP", "STROKE") + STD),
    # bells and wood (fitted)
    "COWBELL":     ("PERC",       0.90, ("TUNE", "DAMP", "STROKE") + STD),
    "BIG_BELL":    ("PERC",       1.10, ("TUNE", "DAMP", "STROKE") + STD),
    "AGOGO_HIGH":  ("PERC",       1.10, ("TUNE", "DAMP") + STD),
    "AGOGO_LOW":   ("PERC",       1.30, ("TUNE", "DAMP") + STD),
    "CLAVE":       ("PERC",       0.60, ("TUNE", "DAMP") + STD),
    "STICKS":      ("PERC",       0.60, ("TUNE", "DAMP") + STD),
    # physical
    "SHEKERE":     ("PERC",       0.70, ("TUNE", "DAMP", "STROKE") + STD),
    "DUNDUN":      ("PERC",       1.00, ("TUNE", "SQUEEZE", "CONTOUR") + STD),
    "TAMA":        ("PERC",       0.80, ("TUNE", "SQUEEZE", "CONTOUR") + STD),
    "NDER":        ("PERC",       0.80, ("TUNE", "DAMP", "STROKE") + STD),
    "MBENG":       ("PERC",       0.85, ("TUNE", "DAMP", "STROKE") + STD),
    "COL":         ("TOM",        1.10, ("TUNE", "DAMP", "STROKE") + STD),
    "TIMBALE":     ("PERC",       1.10, ("TUNE", "DAMP", "STROKE") + STD),
    # the four authored rolls and risers (the percussion gate)
    "SHEKERE_RUSH": ("PERC",      2.60, ("TUNE", "SPEED") + STD),
    "TALKING_CALL": ("PERC",      2.60, ("TUNE", "SPEED", "SQUEEZE") + STD),
    "SABAR_ROLL":  ("PERC",       2.40, ("TUNE", "SPEED") + STD),
    "ABANICO":     ("PERC",       2.20, ("TUNE", "SPEED") + STD),
}

DEFAULTS = {"TUNE": .5, "DAMP": .3, "BEATER": .5, "CLICK": .5, "WIRES": .5, "CROWD": .3, "STROKE": 1 / 3,
            "SQUEEZE": .45, "CONTOUR": 0.0, "SPEED": .5, "ROOM": .25, "AGE": .3}

# Per-voice defaults: each voice opens on its signature stroke.
VOICE_DEFAULTS = {
    "SHAKER": {"STROKE": 0.0}, "COWBELL": {"STROKE": 0.0}, "BIG_BELL": {"STROKE": 0.0},
    "SHEKERE": {"STROKE": 0.0}, "NDER": {"STROKE": 2 / 3}, "MBENG": {"STROKE": 0.0}, "COL": {"STROKE": 0.0},
    "TIMBALE": {"STROKE": 0.0}, "TAMA": {"SQUEEZE": .35},
}

# What each control means, per voice where it differs (the audition page reads this).
MEANING = {
    "TUNE": "head tension, bell or gourd size, about +-4 semitones (the kit's toms and talking drums wider)",
    "DAMP": "a hand resting on the shell, a felt on the kick, fingers on the bell, how tight the bead net is held",
    "BEATER": "the kick's beater, from a felt feathering the head (0) to a hard beater with its click (1)",
    "CLICK": "the programmed kick's attack: from a rounded thump (0) to a sharp tick on top (1)",
    "WIRES": "how loose the snare wires are, from off (0) to loose and buzzing (1)",
    "CROWD": "one pair of hands to a group, each a little early or late",
    "STROKE": "how the hand or stick lands: hand drums go bass (palm in the middle), open, muffled, slap; the sabar "
              "open hand, hand slap, open stick, pressed stick; timbales head, rimshot, shell (paila); bells mouth to "
              "muted; shaker and shekere a push, a pull back, a slap",
    "SQUEEZE": "how far the arm squeezes the talking drum's cords: up to about ten semitones",
    "CONTOUR": "the squeeze's shape: press up after the hit (0), let go after the hit (0.5), up and back down (1)",
    "SPEED": "how fast the roll's strokes come",
    "ROOM": "the microphone, from a dead booth over the drum (0) to the back of a big live studio (1)",
    "AGE": "the era's chain: ribbon mic, valve preamp, tape (0 is a clean modern mic)",
}

# Pad labels for the anchor values of STROKE, per voice family (the page prints them; kits use these values).
STROKES = {
    "HAND": ((0.0, "bass"), (1 / 3, "open"), (2 / 3, "muffled"), (1.0, "slap")),
    "BONGO": ((0.0, "thumb"), (1 / 3, "open"), (2 / 3, "muffled"), (1.0, "slap")),
    "SABAR": ((0.0, "open hand"), (1 / 3, "hand slap"), (2 / 3, "open stick"), (1.0, "pressed stick")),
    "TIMBALE": ((0.0, "head"), (0.5, "rimshot"), (1.0, "shell")),
    "COWBELL": ((0.0, "mouth"), (0.5, "body"), (1.0, "muted")),
    "BIG_BELL": ((0.0, "open"), (1.0, "muted")),
    "SHAKE": ((0.0, "push"), (0.5, "pull back"), (1.0, "slap")),
}
STROKE_FAMILY = {"QUINTO": "HAND", "CONGA": "HAND", "TUMBA": "HAND", "BONGO_HIGH": "BONGO", "BONGO_LOW": "BONGO",
                 "NDER": "SABAR", "MBENG": "SABAR", "COL": "SABAR", "TIMBALE": "TIMBALE", "COWBELL": "COWBELL",
                 "BIG_BELL": "BIG_BELL", "SHAKER": "SHAKE", "SHEKERE": "SHAKE"}


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


def anchors_for(x, anchors):
    """The two stroke anchors either side of x and the weight of the upper one (equal-power blend between them).
    Between two anchors CALABASH plays both strokes blended: an analogy (the hand partly one way), not a solve."""
    vals = [a for a, _ in anchors]
    if x <= vals[0]:
        return 0, 0, 0.0
    for i in range(len(vals) - 1):
        if x <= vals[i + 1]:
            w = (x - vals[i]) / (vals[i + 1] - vals[i])
            return i, i + 1, w
    return len(vals) - 1, len(vals) - 1, 0.0


def blended(x, anchors, make):
    """Render the anchor strokes either side of x (make(i) returns a render) and blend at equal power. At an anchor
    only that stroke is rendered."""
    i, j, w = anchors_for(x, anchors)
    if i == j or w <= 1e-6:
        return make(i)
    if w >= 1 - 1e-6:
        return make(j)
    a, b = make(i), make(j)
    n = max(len(a), len(b))
    a, b = np.pad(a, (0, n - len(a))), np.pad(b, (0, n - len(b)))
    return math.cos(w * math.pi / 2) * a + math.sin(w * math.pi / 2) * b


# ------------------------------------------------------------------------------ the room and the era

@functools.cache
def room_ir(room):
    """ROOM, cached: a dead booth (0) to the back of a big live studio (1). Early reflections at delays that grow
    with the room, then a two-band tail (lows 0.2 to 1.1 s, highs 0.12 to 0.6 s), balanced by energy from +18 dB
    direct-to-room over the drum to -2 dB at the back. Shape: no real room was measured."""
    r = np.random.default_rng(sk.seed_for("calabash-room"))
    size = 0.35 + 1.65 * room
    rt_lo, rt_hi = sk.lin(room, 0.2, 1.1), sk.lin(room, 0.12, 0.6)
    n = n_of(min(1.6, 0.05 + rt_lo * 1.25))
    h = np.zeros(n)
    for ms, g, cut in ((3.1, .55, 7000), (4.9, .45, 8000), (7.3, .38, 5500), (10.2, .30, 6500), (13.6, .24, 4500),
                       (17.9, .2, 5000), (23.0, .15, 4000), (29.5, .12, 3500)):
        i = int(ms * size * 1e-3 * FS)
        if i >= n:
            continue
        tap = np.zeros(n_of(0.004)); tap[0] = g
        tap = e.lp(tap, cut, 1)
        h[i:i + len(tap)] += tap[:n - i]
    t = np.arange(n) / FS
    lo = e.lp(r.standard_normal(n), 1000) * np.exp(-6.9078 * t / rt_lo)
    hi = e.hp(r.standard_normal(n), 1000) * np.exp(-6.9078 * t / rt_hi)
    onset = np.clip((t - 0.004 * size) / (0.02 * size), 0, 1)
    h += (lo * 0.09 + hi * 0.06) * onset * 1.6
    h = e.lp(h, 11000, 1)
    direct = np.zeros(n_of(0.01)); direct[int(room * 0.004 * FS)] = 1.0
    direct = e.lp(direct, sk.exp_map(room, 20000, 7000), 1)
    drr_db = sk.lin(room, 18.0, -2.0)
    out = np.zeros(n)
    out[:len(direct)] += direct / math.sqrt(np.sum(direct ** 2))
    out += h / math.sqrt(np.sum(h ** 2)) * 10 ** (-drr_db / 20)
    return out


def finish(raw, m, v, seconds):
    """ROOM -> AGE -> the house output stage at 44.1 kHz, peak 0.89 (the audition match)."""
    n = len(raw)
    y = e.conv(raw, room_ir(round(m["ROOM"], 3)), n)
    y = e.warmth_chain(y, m["AGE"], v)
    y = signal.resample_poly(y, 1, 4)
    y = y - np.mean(y)
    rc = 1 / (TAU * sk.OUTPUT_DC_HZ)
    al = rc / (rc + 1 / RATE)
    y = signal.lfilter([al, -al], [1, -al], y)
    out = int(seconds * RATE)
    y = y[:out]
    fade = min(len(y), int(0.05 * RATE))
    y[-fade:] *= 0.5 * (1 + np.cos(np.linspace(0, math.pi, fade)))
    return e.peak_norm(y) * 0.89


def render(voice, macros=None, velocity=1.0, take=0):
    """One hit of `voice` at `velocity` (0..1), round-robin take `take`, at 44.1 kHz, peak 0.89."""
    if voice not in VOICES:
        raise ValueError(voice)
    v = 1.0 if velocity is None or not math.isfinite(velocity) else float(np.clip(velocity, 0.05, 1.0))
    m = settled(voice, macros)
    seconds = VOICES[voice][1]
    n = n_of(seconds + 0.05)
    tr = rng_for("calabash", voice, take)       # what changes between takes
    raw = RENDER[voice](m, v, tr, n)
    if not np.all(np.isfinite(raw)):
        raise FloatingPointError(voice)
    return finish(raw, m, v, seconds)


# ------------------------------------------------------------------------------ fitted hand drums

def up(y44, n):
    """A 44.1 kHz fitted render into the 176.4 kHz chain."""
    return e.to_internal(y44, n)


@functools.cache
def _third_octaves(lo):
    return tuple(fc for fc in (25.0 * 2 ** (k / 3) for k in range(40)) if lo <= fc <= 16000.0)


def dense_head(f1, t1, alpha, n, tr, f_from, tilt_db=-3.0):
    """The upper head, statistically. EMBER's membrane stops at 50 Bessel modes, which for a 340 Hz sabar head ends
    near 2.5 kHz; a real head has hundreds above that (TERRA's hand drums use about 900). Here they are band noise
    per third octave from `f_from` up, each band dying at the head's own loss law, T60 = t1 (f / f1)^-alpha (floored
    at 3 ms), falling `tilt_db` per octave. Convolved with the stroke's force like the discrete modes, so a thin
    stick (a short contact, a flat spectrum) brings it out and a broad palm hides it. Peak-normalized. A fresh noise
    every take: the hand or the stick never lands on exactly the same upper modes. Shape: not fitted."""
    t = np.arange(n) / FS
    out = np.zeros(n)
    for fc in _third_octaves(f_from):
        t60 = max(SKIN_T60_FLOOR, t1 * (fc / f1) ** -alpha)
        m = min(n, int(t60 * 1.6 * FS) + 1)
        x = e.band(tr.standard_normal(m + 600), fc / 2 ** (1 / 6), fc * 2 ** (1 / 6))[600:]
        x /= np.sqrt(np.mean(x ** 2)) + 1e-20
        g = 10 ** (tilt_db * math.log2(fc / f_from) / 20)
        out[:m] += g * x * np.exp(-6.9078 * t[:m] / t60)
    return e.peak_norm(out)


def tip(v, tr, n, width_hard=0.28e-3, at=0.0, scale=1.0, click=0.06):
    """A stick tip landing, CALABASH's contact. EMBER's `stick` is a raised cosine, whose spectrum is gone above 2/w
    (7 kHz for a 0.28 ms hit): it leaves a stick stroke with no top. A half-sine (closer to a Hertzian contact) falls
    off at 12 dB an octave instead, and the tip's own click (wood or metal meeting skin, 0.04 ms) adds the last
    octave. Width grows as velocity falls (v^-0.3, EMBER's law), so a soft stroke is darker. Unit-area force times
    v^1.25, as EMBER's."""
    w = max(4, int(width_hard * v ** -0.3 * (1 + 0.07 * tr.standard_normal()) * FS))
    f = np.zeros(n)
    i = int(at * FS)
    if i >= n:
        return f
    p = np.sin(np.pi * (np.arange(w) + 0.5) / w)
    p *= v ** 1.25 * scale / p.sum() * 40.0
    k = max(3, int(0.04e-3 * FS))
    c = np.hanning(k + 2)[1:-1]
    c *= click * v ** 1.6 * scale / c.sum() * 40.0 * tr.uniform(.7, 1.3)
    f[i:min(n, i + w)] += p[:min(w, n - i)]
    f[i:min(n, i + k)] += c[:min(k, n - i)]
    return f


SKIN_T60_FLOOR = 0.006
SKIN_TILT = {"TOM": 3.0, "DUNDUN": 0.0, "TAMA": 3.0, "SABAR_STICK": 3.0, "SABAR_SLAP": 3.0, "SABAR_PRESSED": 3.0,
             "TIMBALE_HEAD": 0.0, "TIMBALE_RIM": 0.0}


def skin(force, f1, t1, alpha, n, tr, amount, f_from=None, tilt_db=None, key=None):
    """The stroke's force through the dense upper head (`dense_head`), scaled by `amount` (shape, per voice)."""
    if amount <= 0:
        return np.zeros(n)
    tilt = SKIN_TILT.get(key, -3.0) if tilt_db is None else tilt_db
    ir = dense_head(f1, t1, alpha, n, tr, f_from or 6.5 * f1, tilt)
    return amount * e.conv(force, ir, n)


@functools.cache
def fit_hz(name, rank=0):
    """The fitted voice's strongest mode frequency (rank 0), from hands.json."""
    md = sorted(F.data()["voices"][name]["modes"], key=lambda q: -q[1])
    return md[rank][0]


def _hand_drum(prefix, tune_span, m, v, tr, n):
    """A conga or bongo. STROKE runs bass (palm in the middle), open, muffled, slap. Open and muffled are the
    recordings' strokes. Bass and slap are built on them (shape: VCSL has neither)."""
    secs = n / FS
    ratio = semis(m["TUNE"], tune_span)
    damp = m["DAMP"]
    f0 = fit_hz(prefix + "_open") * ratio                         # the head's own note
    f_air = min(fit_hz(prefix + "_muff"), fit_hz(prefix + "_open")) * ratio

    def bass():
        # The palm lands flat in the middle and bounces: the head's lowest mode and the shell's air sing, the
        # upper modes (which a centre strike barely excites, and the palm's breadth smooths out) fall away.
        keep = lambda f: 10 ** ((0.0 if f < 1.35 * f0 else -10.0 - 9.0 * math.log2(f / (1.35 * f0))) / 20)
        a = F.fitted(prefix + "_open", v, tr, secs, tune_ratio=ratio, damp=damp, mode_gain=keep,
                     attack_db=-9.0, residual_db=-4.0, contact_ms=2.2)
        airy = lambda f: 10 ** ((3.0 if f < 1.2 * f_air else -18.0) / 20)
        b = F.fitted(prefix + "_muff", v, tr, secs, tune_ratio=ratio, damp=damp, mode_gain=airy,
                     attack_db=-12.0, residual_db=-8.0, contact_ms=2.2)
        return a + 0.8 * b

    def open_():
        return F.fitted(prefix + "_open", v, tr, secs, tune_ratio=ratio, damp=damp, contact_ms=0.7)

    def muff():
        return F.fitted(prefix + "_muff", v, tr, secs, tune_ratio=ratio, damp=damp, contact_ms=0.7)

    def slap():
        # The heel presses the head near the rim while the cupped fingers crack onto it and stay a moment: the
        # fundamental is choked (-14 dB, gone four times faster), the upper modes ring briefly and brighter, and the
        # skin-on-skin smack is the loudest thing in the first few milliseconds.
        shape = lambda f: 10 ** ((-14.0 if f < 1.3 * f0 else min(9.0, 3.0 + 4.0 * math.log2(f / (1.3 * f0)))) / 20)
        slope = lambda f: 4.0 if f < 1.3 * f0 else 2.4
        modes, res = F.strike(prefix + "_open", v, tr, secs, tune_ratio=ratio, damp=damp, mode_gain=shape,
                              slope_mul=slope, attack_db=6.0, residual_db=-3.0, contact_ms=0.22, glide_mul=0.5)
        body = modes + res
        k = len(body)
        smack = np.zeros(k)
        w = max(3, int(0.00022 * v ** -0.3 * RATE * tr.uniform(.85, 1.15)))
        smack[:w] = np.hanning(w + 2)[1:-1]
        smack = signal.sosfilt(signal.butter(2, [1200 / (RATE / 2), 9000 / (RATE / 2)], "band", output="sos"), smack)
        tail = np.exp(-np.arange(k) / (0.003 * RATE))
        crack = signal.sosfilt(signal.butter(2, [1500 / (RATE / 2), 7000 / (RATE / 2)], "band", output="sos"),
                               tr.standard_normal(k)) * tail
        ref = np.sqrt(np.mean(body[:int(0.05 * RATE)] ** 2)) + 1e-12
        hit = e.peak_norm(smack) * 2.2 * ref * v ** 0.5 + 0.7 * ref * v ** 0.7 * crack / (np.std(crack[:200]) + 1e-12)
        return body + hit

    makers = (bass, open_, muff, slap)
    y = blended(m["STROKE"], STROKES["HAND"], lambda i: makers[i]())
    return up(y, n)


def _quinto(m, v, tr, n):
    return _hand_drum("quinto", 4, m, v, tr, n)


def _conga(m, v, tr, n):
    return _hand_drum("conga", 4, m, v, tr, n)


def _tumba(m, v, tr, n):
    return _hand_drum("tumba", 4, m, v, tr, n)


def _bongo_high(m, v, tr, n):
    return _hand_drum("bongo_hi", 4, m, v, tr, n)


def _bongo_low(m, v, tr, n):
    return _hand_drum("bongo_lo", 4, m, v, tr, n)


# ------------------------------------------------------------------------------ fitted bells, wood, shaker, claps

def _fitted_simple(name, m, v, tr, n, contact=0.2, tune_span=3):
    return up(F.fitted(name, v, tr, n / FS, tune_ratio=semis(m["TUNE"], tune_span), damp=m.get("DAMP", .3),
                       contact_ms=contact), n)


def _cowbell(m, v, tr, n):
    """A cowbell struck with a stick: on the mouth's edge (open, the low ring), on the body (higher, drier), or with
    the hand closed round it (muted). All three are recorded strokes."""
    names = ("bell1_mouth", "bell1_body", "bell1_muted")
    return blended(m["STROKE"], STROKES["COWBELL"], lambda i: _fitted_simple(names[i], m, v, tr, n))


def _big_bell(m, v, tr, n):
    """The larger, lower cowbell, open or muted (both recorded)."""
    names = ("bell2_open", "bell2_muted")
    return blended(m["STROKE"], STROKES["BIG_BELL"], lambda i: _fitted_simple(names[i], m, v, tr, n))


def _agogo_high(m, v, tr, n):
    return _fitted_simple("agogo_hi", m, v, tr, n)


def _agogo_low(m, v, tr, n):
    return _fitted_simple("agogo_lo", m, v, tr, n)


def _clave(m, v, tr, n):
    return _fitted_simple("clave2", m, v, tr, n, contact=0.15)


def _sticks(m, v, tr, n):
    """Afrobeat's sticks (the 'clips' line): the lower of the two recorded pairs of claves, played a little flatter."""
    return _fitted_simple("clave1", m, v, tr, n, contact=0.15)


def _shaker(m, v, tr, n):
    """The recorded large shaker: a push, a pull back, or a slap of the palm (all recorded)."""
    names = ("shaker_down", "shaker_up", "shaker_hit")
    return blended(m["STROKE"], STROKES["SHAKE"],
                   lambda i: up(F.fitted(names[i], v, tr, n / FS, tune_ratio=semis(m["TUNE"], 3)), n))


def _clap(m, v, tr, n):
    """CROWD 0 is the recorded solo clap, 1 the recorded group; between, the two blended."""
    a = lambda: up(F.fitted("clap_solo", v, tr, n / FS, tune_ratio=semis(m["TUNE"], 3)), n)
    b = lambda: up(F.fitted("clap_group", v, tr, n / FS, tune_ratio=semis(m["TUNE"], 3)), n)
    return blended(m["CROWD"], ((0.0, "solo"), (1.0, "group")), lambda i: (a, b)[i]())


def _snap(m, v, tr, n):
    """EMBER's snaps (a finger cracking onto the palm, a small bright pop), CROWD people at once."""
    return e._snaps({"TUNE": m["TUNE"], "CROWD": m["CROWD"]}, v, tr, n)


# ------------------------------------------------------------------------------ the kit

HEAD_ALPHA = 0.85   # a coated plastic head: its high modes outlive calf's (EMBER's calf is 1.15). Shape.

# How much of the dense upper head (`skin`) and the tip's own click each physical stroke carries. Tuned once in the
# Python harness toward close-miked brightness targets (`BRIGHT_TARGETS`, octave bands re the whole hit's power),
# which are themselves shape: typical of close-miked stick drums, not measured from a recording of these drums.
# Swept in round 0 (amount 2 to 64, click 0.1 to 2.4, tilt -3 to +3 dB an octave, the upper head's decay floored at
# 6 ms) to the nearest of these targets, 1, 2, 4, 8 kHz octaves: tom and dundun -22 -24 -30 -40; tama -8 -16 -22
# -30; nder open stick -9 -14 -16 -24, pressed stick -7 -12 -15 -22; timbale head -10 -14 -20 -28. Reached within
# 3 dB a band except the tom's 2 kHz (-31) and the tama's 1 kHz (-19). The hand strokes were not swept.
SKIN = {"TOM": 8, "DUNDUN": 4, "TAMA": 16, "SABAR_HAND": 3, "SABAR_SLAP": 4, "SABAR_STICK": 8,
        "SABAR_PRESSED": 4, "TIMBALE_HEAD": 4, "TIMBALE_RIM": 4}
CLICK = {"TOM": 2.4, "DUNDUN": 2.4, "TAMA": 0.1, "SABAR": 1.2, "TIMBALE": 2.4, "TIMBALE_SHELL": 0.6}


def _kick(m, v, tr, n):
    """A 20 to 22-inch kick with its front head on and a blanket inside: a round, punchy note. BEATER runs from a
    felt that feathers the head to a hard beater whose click cuts through."""
    f1 = 56.0 * semis(m["TUNE"], 6)
    t1 = sk.lin(m["DAMP"], 0.9, 0.12)
    hard = m["BEATER"]
    width = sk.lin(hard, 1.8e-3, 0.45e-3)
    beater = e.mallet(v, tr, n, width_hard=width)
    air = e.hann_force(8e-3, 0.45 * v ** 1.6, n)
    out = e._drum(f1, t1, HEAD_ALPHA + 0.3, v, tr, n, 0.1, beater + air, m, sag_max=0.08)
    out = out + np.max(np.abs(out)) * skin(beater, f1, t1, HEAD_ALPHA + 0.3, n, tr, 0.12, f_from=900.0)
    click = e.hp(e.hann_force(0.35e-3, (0.02 + 0.12 * hard) * v ** 1.8, n), 1800)
    return out + click * np.max(np.abs(out)) * 6


def _deep_kick(m, v, tr, n):
    """The programmed kick: a swept sine (the drum machine's bridged-T idea) with a short tick. A harder pad starts
    higher, sweeps faster, ticks louder and drives the soft clipper harder, so it is a punchier kick, not a louder
    one. A take is a small move of the sweep and the tick (weaker than a re-strike: disclosed, as the house does for
    THUMP's kick)."""
    t = np.arange(n) / FS
    f_end = 48.0 * semis(m["TUNE"], 7)
    f_start = f_end * (2.4 + 1.6 * v) * tr.uniform(.95, 1.05)
    tau_p = (0.022 + 0.01 * (1 - v)) * tr.uniform(.9, 1.1)
    f = f_end + (f_start - f_end) * np.exp(-t / tau_p)
    ph = TAU * np.cumsum(f) / FS
    decay = sk.lin(m["DAMP"], 0.75, 0.16) * tr.uniform(.94, 1.06)
    body = np.sin(ph) * np.exp(-6.9078 * t / decay) * np.minimum(1, t / 0.0006)
    drive = 1.2 + 2.2 * v
    body = np.tanh(body * drive) / math.tanh(drive)
    tick = (e.hp(tr.standard_normal(n) * np.exp(-t / 0.0015), 2500) * (0.05 + 0.4 * m["CLICK"]) * v ** 1.5
            * tr.uniform(.75, 1.25))
    knock = np.sin(TAU * 1500 * t) * np.exp(-t / 0.0025) * 0.25 * m["CLICK"] * v
    return body + tick + knock


def snare_core(m, v, tr, n, force, r_base, wire_gain=1.0, f_base=300.0, alpha=HEAD_ALPHA):
    """EMBER's snare (head plus wires driven by the head's motion), with a plastic head and WIRES for BUZZ."""
    f1 = f_base * semis(m["TUNE"], 5)
    t1 = sk.lin(m.get("DAMP", .3), 0.42, 0.07)
    r = float(np.clip(r_base + tr.normal(0, 0.07), 0.0, 0.95))
    ir = e.peak_norm(e.membrane_ir(f1, n, t1, alpha, r, tr.uniform(0, math.pi), sag=0.03 * v * v,
                                   sag_tau=0.04 + 0.02 * tr.random(), scatter=tr))
    head = e.conv(force, ir, n)
    k = n_of(0.08)
    ref_full = e.conv(e.hann_force(0.28e-3, 1.0, k), ir[:k], k)
    ref_peak = np.max(np.abs(ref_full))
    x = e.lp(head, 900) / (np.max(np.abs(e.lp(ref_full, 900))) + 1e-12)
    hd = np.sign(x) * np.abs(x) ** 0.6
    return head + wire_gain * e.WIRE_GAIN * ref_peak * e.wires(hd, v, m["WIRES"], tr, n)


def _snare(m, v, tr, n):
    force = e.stick(v, tr, n)
    return snare_core(m, v, tr, n, force, 0.3) + e.hp(force, 2500) * 0.2


def _rimshot(m, v, tr, n):
    force = e.stick(v, tr, n, width_hard=0.2e-3)
    s = semis(m["TUNE"], 5)
    hoop = e.modal_ir(np.array([960, 2210, 3720, 5300]) * s, [1, .7, .5, .3], [.09, .06, .045, .03], n)
    rim = e.conv(force, e.peak_norm(hoop), n)
    return snare_core(m, v, tr, n, force * 1.2, 0.85) + 0.9 * rim + e.hp(force, 2000) * 0.35


def _rim(m, v, tr, n):
    """The programmed rim: a side stick close up and dry, the stick's butt on the steel hoop and its shaft on the head,
    no wires. EMBER's cross stick's wood and hoop modes (with two more of each up top), struck by CALABASH's tip so it
    keeps its top: Afrobeats' rim is a crisp click."""
    s = semis(m["TUNE"], 5)
    force = tip(v, tr, n, width_hard=0.15e-3, click=0.8)
    damp = sk.lin(m["DAMP"], 1.4, 0.5)
    wood = e.modal_ir(np.array([505, 1390, 2730, 4500, 6700]) * s * tr.uniform(.97, 1.03),
                      np.array([1, .8, .5, .3, .2]) * tr.uniform(0.5, 1.5, 5),
                      np.array([.05, .035, .02, .012, .008]) * damp, n)
    hoop = e.modal_ir(np.array([1180, 1960, 2840, 3950, 5600, 7900]) * s,
                      np.array([.6, 1, .5, .35, .25, .15]) * tr.uniform(0.4, 1.6, 6),
                      np.array([.07, .06, .04, .03, .02, .012]) * damp, n)
    head = e.membrane_ir(300 * s, n, 0.12 * damp, HEAD_ALPHA, 0.8, tr.uniform(0, math.pi), reso=None, scatter=tr)
    # A soft rim is mostly the shaft's wood on the head; a hard one rings the steel hoop and cracks.
    return (e.conv(force, e.peak_norm(wood) + (0.25 + 0.75 * v ** 1.5) * e.peak_norm(hoop), n)
            + (0.35 - 0.15 * v) * e.conv(force, e.peak_norm(head), n) + e.hp(force, 3000) * 1.2 * v ** 0.8)


def _tom(m, v, tr, n):
    """A rack or floor tom, plastic heads. TUNE spans 74 to 230 Hz (the floor to a high, tight Mbalax rack tom)."""
    f1 = 130.0 * semis(m["TUNE"], 10)
    t1 = sk.lin(m["DAMP"], 1.1, 0.14) * (130.0 / f1) ** 0.3
    force = tip(v, tr, n, click=CLICK["TOM"])
    r = float(np.clip(0.3 + tr.normal(0, 0.05), 0, .95))
    ir = e.membrane_ir(f1, n, t1, HEAD_ALPHA + .15, r, tr.uniform(0, math.pi), sag=0.05 * v * v, sag_tau=0.05,
                       scatter=tr)
    return (e.conv(force, e.peak_norm(ir), n) + skin(force, f1, t1, HEAD_ALPHA + .15, n, tr, SKIN["TOM"], key="TOM")
            + e.hp(force, 2500) * 0.12)


def _hat_closed(m, v, tr, n):
    return e._hat_closed({"TUNE": m["TUNE"], "DAMP": m["DAMP"], "BUZZ": 0.0}, v, tr, n)


def _hat_open(m, v, tr, n):
    """EMBER's fitted open hat; DAMP closes the pedal toward a half-open slosh."""
    return e._hat_open({"TUNE": m["TUNE"], "DAMP": m["DAMP"], "BUZZ": 0.0}, v, tr, n)


def _hat_foot(m, v, tr, n):
    return e._hat_foot({"TUNE": m["TUNE"], "DAMP": m["DAMP"], "BUZZ": 0.0}, v, tr, n)


@functools.cache
def _breaker_cymbals():
    """BREAKER's approved ride and crash (synth/src/main/resources/breaker/cymbals.json, BREAKER's fitter on
    Virtuosity Drums, CC0) put into EMBER's fitted-cymbal table, with no residual (BREAKER renders modes and stick
    only, as approved)."""
    path = os.path.join(HERE, "..", "..", "synth", "src", "main", "resources", "breaker", "cymbals.json")
    with open(path) as fh:
        b = json.load(fh)
    fit = e.fit()
    assert [round(x, 3) for x in b["bands"]] == [round(x, 3) for x in fit["bands"]]
    silent = [[-200.0] * len(fit["voices"]["flatride"]["times"]) for _ in fit["bands"]]
    for k in ("ride", "crash"):
        d = dict(b["voices"][k])
        d.update(residual=silent, residualSoft=silent, times=fit["voices"]["flatride"]["times"])
        fit["voices"]["breaker_" + k] = d
    return True


def _ride(m, v, tr, n):
    _breaker_cymbals()
    sound, _ = e.struck("breaker_ride", m, v, tr, n)
    return sound


def _crash(m, v, tr, n):
    _breaker_cymbals()
    sound, _ = e.struck("breaker_crash", m, v, tr, n, burst=0.8)
    return sound


# ------------------------------------------------------------------------------ the shekere (physical)

def bead_cloud(rate, n, tr, bright, gain):
    """A bead net hitting a gourd: impacts at `rate` per second (an array at FS), each a short click whose width
    follows how hard the beads land (`bright` 0..1), plus the net's own slide. A dense cloud sums toward a smooth
    noise; a sparse one ticks. Poisson by thinning, vectorised."""
    p = np.clip(rate / FS, 0, 0.5)
    hits = tr.random(n) < p
    amp = np.zeros(n)
    amp[hits] = tr.uniform(0.3, 1.0, hits.sum()) * gain[hits]
    out = np.zeros(n)
    for lo, hi, g in ((1800, 5200, 1.0), (4200, 11000, 0.55 + 0.6 * bright)):
        click = e.band(amp * tr.choice((-1.0, 1.0), n), lo, hi)
        out += g * click
    slide = e.band(tr.standard_normal(n), 2500, 9000) * np.sqrt(np.clip(rate, 0, None) / 4000.0) * gain * 0.08
    return out + slide


@functools.cache
def gourd_ir(tune):
    """The calabash: its air (a Helmholtz mode near 165 Hz for an 8-litre gourd with a 4 cm neck, c/2pi sqrt(A/VL);
    shape) and a dry, well-damped shell (380 to 1900 Hz). Cached by TUNE."""
    s = semis(tune, 4)
    k = n_of(0.5)
    air = e.modal_ir([165 * s], [1.0], [0.18], k)
    shell = e.modal_ir(np.array([380, 610, 870, 1240, 1900]) * s, [.8, .9, .7, .5, .3], [.06, .05, .04, .03, .02], k)
    return e.peak_norm(air), e.peak_norm(shell)


def _shekere_stroke(m, v, tr, n, kind, at=0.0, scale=1.0):
    """One shekere stroke: 'push' (the gourd thrown forward, the net lagging and landing), 'pull' (back the other
    way, a longer, smoother swirl), 'slap' (the palm on the gourd's bottom: its air booms and the whole net jumps)."""
    t = np.arange(n) / FS - at
    s = semis(m["TUNE"], 4)
    tight = m["DAMP"]
    air, shell = gourd_ir(round(m["TUNE"], 3))
    if kind == "slap":
        dur = 0.05
    else:
        dur = (0.085 if kind == "push" else 0.16) * (1 - 0.35 * tight) * tr.uniform(.92, 1.08)
    # How hard the gourd moves: a half-sine of acceleration; the net lands at its end (the accent).
    acc = np.where((t >= 0) & (t < dur), np.sin(np.pi * np.clip(t / dur, 0, 1)), 0.0)
    land_at = dur * (0.75 if kind == "push" else 0.55)
    land = np.exp(-np.clip(t - land_at, 0, None) / (0.018 * (1 - 0.5 * tight))) * (t >= land_at)
    drive = (0.55 * acc + land * (1.0 if kind == "push" else 0.6)) * scale
    if kind == "slap":
        drive = land * 1.2 * scale
    # Beads lift only above a threshold that a soft shake barely passes, and land harder the harder it moves.
    beads = 260 * (1 - 0.4 * tight)
    rate = beads * 40 * np.clip(drive * v - 0.08, 0, None)
    gain = np.clip(drive, 0, None) * v ** 0.8
    cloud = bead_cloud(rate, n, tr, v, gain)
    # The beads also knock the gourd: its shell and air answer the cloud's low part.
    knock = e.lp(np.abs(cloud), 400)
    body = 0.5 * e.conv(knock, shell, n) + 0.06 * e.conv(knock, air, n)
    if kind == "slap":
        palm = e.hand(v, tr, n, at=max(0.0, at), cupped=0.1) * scale
        body = body + 1.6 * e.conv(palm, air, n) + 0.7 * e.conv(palm, shell, n)
    return cloud + body * 0.15


def _shekere(m, v, tr, n):
    kinds = ("push", "pull", "slap")
    return blended(m["STROKE"], STROKES["SHAKE"], lambda i: _shekere_stroke(m, v, tr, n, kinds[i]))


# ------------------------------------------------------------------------------ talking drums (physical)

def squeeze_path(n, squeeze_semis, contour, tr, hit_at=0.0, rise_s=0.12, start_s=0.02):
    """The talking drum's pitch over time, as a ratio. CONTOUR 0: press up after the hit; 0.5: hit already squeezed
    and let go; 1: up and back down (the speech glide). Blended between. The squeeze takes 90 to 160 ms (shape:
    the Yoruba speech-surrogacy literature describes glides within one beat; no rate was measured here)."""
    t = np.arange(n) / FS - hit_at
    start = start_s + tr.uniform(0, 0.015)
    rise = rise_s * tr.uniform(.85, 1.15)
    x = np.clip((t - start) / rise, 0, 1)
    smooth = x * x * (3 - 2 * x)
    up_ = smooth
    down = 1 - smooth
    bump = np.sin(np.pi * np.clip((t - start) / (2.2 * rise), 0, 1)) ** 1.5
    c = contour
    if c <= .5:
        w = c / .5
        shape = (1 - w) * up_ + w * down
    else:
        w = (c - .5) / .5
        shape = (1 - w) * down + w * bump
    return 2 ** (squeeze_semis * shape / 12)


def _talking(m, v, tr, n, f_base, t1, alpha, stick_w, cavity, hit_at=0.0, scale=1.0, squeeze=None, kind="DUNDUN",
             rise_s=0.12):
    """An hourglass drum, two goatskin heads laced together. The arm squeezes the lacing and both heads tighten at
    once, so every mode rises together while it rings. The stick is curved; the far head and the waist's air add a
    little body under the struck head."""
    sq = (m["SQUEEZE"] if squeeze is None else squeeze) * 10.0
    path = squeeze_path(n, sq, m["CONTOUR"], tr, hit_at, rise_s, 0.02 if rise_s > .1 else 0.008)
    f1 = f_base * semis(m["TUNE"], 6)
    r = float(np.clip(0.35 + tr.normal(0, .06), 0, .9))
    sag = 1 + 0.06 * v * v * np.exp(-np.arange(n) / FS / 0.04)       # a hard stroke starts sharp and settles
    ir = e.membrane_ir(f1, n, t1, alpha, r, tr.uniform(0, math.pi), bend=path * sag, reso=(1.012, 0.45),
                       scatter=tr)
    force = tip(v, tr, n, width_hard=stick_w, at=max(0.0, hit_at), scale=scale, click=CLICK[kind])
    head = e.conv(force, e.peak_norm(ir), n)
    waist = e.conv(force, e.peak_norm(e.membrane_ir(f1 * cavity, n, t1 * 0.7, 1.4, 0.0, 0.0, bend=path,
                                                    reso=None, count=3)), n)
    return head + 0.25 * waist + skin(force, f1, t1, alpha, n, tr, SKIN[kind], key=kind) + e.hp(force, 3000) * 0.08


def _dundun(m, v, tr, n):
    """The Yoruba dundun (the large iya ilu): a relaxed note near 150 Hz, a padded curved stick (shape)."""
    return _talking(m, v, tr, n, 150.0, 0.55, 1.0, 0.38e-3, 0.62)


def _tama(m, v, tr, n):
    """The Wolof tama: a small, very tight armpit drum with a thin hooked stick, near 420 Hz, cracking and short."""
    return _talking(m, v, tr, n, 420.0, 0.45, 1.05, 0.25e-3, 0.7, kind="TAMA", rise_s=0.065)


# ------------------------------------------------------------------------------ the sabar family (physical)

def pipe_ir(length_m, n, closed_bottom, damping):
    """The air in the shell under the head. Open bottom (nder, mbeng-mbeng): a tube closed by the head at the top,
    its resonances at odd multiples of c/4L. Closed bottom (col): an egg-shaped vessel, one cavity mode. Shape."""
    if closed_bottom:
        return e.modal_ir([95.0], [1.0], [0.25 * damping], n)
    f = 343.0 / (4 * length_m)
    return e.modal_ir([f, 3 * f, 5 * f], [1.0, .45, .2], [.22 * damping, .12 * damping, .07 * damping], n)


def galan(v, tr, n, at=0.0, scale=1.0):
    """The sabar stick: a thin, springy switch (about 5 mm). A very short contact plus the stick's own whip."""
    f = tip(v, tr, n, width_hard=0.14e-3, at=at, scale=scale, click=CLICK["SABAR"])
    whip = e.modal_ir([1450 * tr.uniform(.97, 1.03), 3900 * tr.uniform(.97, 1.03)], [1, .4], [.012, .006], n)
    return f, e.conv(f, whip, n)


def _sabar(m, v, tr, n, f_base, length_m, closed, at=0.0, scale=1.0, stroke=None):
    """One sabar stroke. The head is goatskin pegged and roped very tight, so it rings high and bright. STROKE: open
    hand (the low, hollow tone: fingers near the middle, bouncing off), hand slap (cupped at the edge, choked),
    open stick (the switch cracks and bounces off), pressed stick (the hand presses the head as the stick lands: it
    sounds higher, because pressing tightens it, and short). Winter lists the hand, stick and hand-plus-stick
    strokes by their Wolof names; this model does not claim which name each setting is."""
    s = semis(m["TUNE"], 5)
    f1 = f_base * s
    damp = m["DAMP"]
    pipe = e.peak_norm(pipe_ir(length_m / s ** 0.25, n, closed, sk.lin(damp, 1.2, 0.4)))
    x = m["STROKE"] if stroke is None else stroke

    def head(t1, r, pitch=1.0, alpha=1.0, force=None, bright=1.0, key=None):
        t1d = t1 * sk.lin(damp, 1.25, 0.45)
        ir = e.membrane_ir(f1 * pitch, n, t1d, alpha, r, tr.uniform(0, math.pi),
                           sag=0.035 * v * v, sag_tau=0.03, reso=None, scatter=tr)
        return e.conv(force, e.peak_norm(ir), n) + skin(force, f1 * pitch, t1d, alpha, n, tr, bright, key=key)

    def open_hand():
        f = e.hand(v, tr, n, at=at, cupped=0.0) * scale
        h = head(0.45, 0.25 + tr.normal(0, .04), force=f, bright=SKIN["SABAR_HAND"], key="SABAR_HAND")
        return (h + 0.55 * e.conv(e.lp(f, 600), pipe, n) * (1.0 if not closed else 1.6)
                + hand_crack(h, v, tr, n, at, level=0.12 if closed else 0.22))

    def slap():
        f = e.hand(v, tr, n, at=at, cupped=1.0) * scale
        h = head(0.11, 0.85 + tr.normal(0, .03), force=f, alpha=0.8, bright=SKIN["SABAR_SLAP"], key="SABAR_SLAP")
        return h + e.hp(f, 1800) * 0.9 + 0.05 * e.conv(e.lp(f, 500), pipe, n) + hand_crack(h, v, tr, n, at)

    def stick_open():
        f, whip = galan(v, tr, n, at, scale)
        h = head(0.5, 0.6 + tr.normal(0, .05), force=f, alpha=0.75, bright=SKIN["SABAR_STICK"], key="SABAR_STICK")
        return h + 0.35 * whip + e.hp(f, 2500) * 0.35 + 0.25 * e.conv(e.lp(f, 600), pipe, n)

    def stick_pressed():
        f, whip = galan(v, tr, n, at, scale)
        h = head(0.08, 0.55 + tr.normal(0, .05), pitch=1.12 * tr.uniform(.99, 1.01), force=f, alpha=0.7, bright=SKIN["SABAR_PRESSED"], key="SABAR_PRESSED")
        return h + 0.5 * whip + e.hp(f, 2500) * 0.45

    makers = (open_hand, slap, stick_open, stick_pressed)
    return blended(x, STROKES["SABAR"], lambda i: makers[i]())


def hand_crack(body, v, tr, n, at=0.0, level=0.8):
    """Skin meeting skin: cupped fingers cracking onto the head make a broadband smack over about 3 ms (1.5 to 10
    kHz), louder than the head in its first milliseconds. A hand contact alone cannot reach that top. Shape."""
    t = np.arange(n) / FS - at
    env = np.where(t >= 0, np.exp(-np.clip(t, 0, None) / 0.003), 0.0)
    x = e.band(tr.standard_normal(n), 1500, 10000) * env
    ref = np.max(np.abs(body)) + 1e-12
    return x / (np.max(np.abs(x)) + 1e-12) * ref * level * v ** 0.7


def _nder(m, v, tr, n):
    """The nder, the tallest open-bottom sabar and the lead drum (Tang, MIT 21M.030 sabar terms)."""
    return _sabar(m, v, tr, n, 340.0, 0.78, False)


def _mbeng(m, v, tr, n):
    """The mbeng-mbeng, a medium open-bottom sabar: it carries the mbalax, the accompaniment part (Tang)."""
    return _sabar(m, v, tr, n, 270.0, 0.55, False)


def _col(m, v, tr, n):
    """The col, the closed-bottom, egg-shaped bass drum with the ensemble's lowest sound (Tang)."""
    return _sabar(m, v, tr, n, 125.0, 0.5, True)


# ------------------------------------------------------------------------------ timbales (physical)

def steel_shell(n, s, damp, tr, gain_hi=1.0):
    """A shallow steel shell's ring modes: n(n^2-1)/sqrt(n^2+1) for a thin ring (shape), each split into an axial
    pair, ringing for a few hundred ms. Struck directly (the paila), it is a metallic click and clatter."""
    base = 1050.0 * s
    fr, am, tt = [], [], []
    for k in range(2, 9):
        r = k * (k * k - 1) / math.sqrt(k * k + 1) / (2 * 3 / math.sqrt(5))
        for ax, g in ((1.0, 1.0), (1.18 + 0.03 * k, 0.6)):
            f = base * r * ax * tr.uniform(.995, 1.005)
            fr.append(f)
            am.append(g * (gain_hi if f > 3000 else 1.0) / (1 + 0.25 * k))
            tt.append(sk.lin(damp, 0.55, 0.12) * (1050.0 / max(f, 1050.0)) ** 0.4)
    return e.modal_ir(fr, am, tt, n, phases=tr.uniform(0, TAU, len(fr)))


def _timbale_stroke(m, v, tr, n, x, at=0.0, scale=1.0):
    """A timbale: a thin plastic head, very tight, on a shallow steel shell, played with thin sticks. STROKE: head
    (open, ringing), rimshot (the stick catches head and rim together: the loud crack the timbalero accents with),
    shell (the paila: the stick on the shell's side, the cascara's click)."""
    s = semis(m["TUNE"], 6)
    f1 = 310.0 * s
    damp = m["DAMP"]

    def head_():
        f = tip(v, tr, n, width_hard=0.22e-3, at=at, scale=scale, click=CLICK["TIMBALE"])
        ir = e.membrane_ir(f1, n, sk.lin(damp, 0.75, 0.15), 0.62, 0.45 + tr.normal(0, .05), tr.uniform(0, math.pi),
                           sag=0.025 * v * v, sag_tau=0.04, reso=None, scatter=tr)
        h = e.conv(f, e.peak_norm(ir), n) + skin(f, f1, sk.lin(damp, 0.75, 0.15), 0.62, n, tr, SKIN["TIMBALE_HEAD"], key="TIMBALE_HEAD")
        sh = e.conv(f, e.peak_norm(steel_shell(n, s, damp, tr)), n)
        return h + 0.35 * sh + e.hp(f, 2500) * 0.15

    def rim():
        f = tip(v, tr, n, width_hard=0.16e-3, at=at, scale=scale * 1.3, click=CLICK["TIMBALE"])
        ir = e.membrane_ir(f1, n, sk.lin(damp, 0.6, 0.12), 0.62, 0.92, tr.uniform(0, math.pi), sag=0.03 * v * v,
                           reso=None, scatter=tr)
        h = e.conv(f, e.peak_norm(ir), n) + skin(f, f1, sk.lin(damp, 0.6, 0.12), 0.62, n, tr, SKIN["TIMBALE_RIM"], key="TIMBALE_RIM")
        sh = e.conv(f, e.peak_norm(steel_shell(n, s, damp, tr, 1.6)), n)
        return 0.8 * h + 0.9 * sh + e.hp(f, 1800) * 0.5

    def shell():
        f = tip(v, tr, n, width_hard=0.12e-3, at=at, scale=scale, click=CLICK["TIMBALE_SHELL"])
        sh = e.conv(f, e.peak_norm(steel_shell(n, s, sk.lin(damp, .5, 1.0), tr, 1.8)), n)
        ir = e.membrane_ir(f1, n, 0.3, 0.62, 0.95, 0.0, reso=None, count=6)
        sym = e.conv(e.lp(f, 800), e.peak_norm(ir), n)
        return sh + 0.12 * sym + e.hp(f, 3000) * 0.6

    makers = (head_, rim, shell)
    return blended(x, STROKES["TIMBALE"], lambda i: makers[i]())


def _timbale(m, v, tr, n):
    return _timbale_stroke(m, v, tr, n, m["STROKE"])


# ------------------------------------------------------------------------------ the four rolls and risers

def place(out, y, at):
    """Add a stroke rendered from its own onset into `out` at `at` seconds."""
    i = int(round(at * FS))
    if i < len(out):
        k = min(len(y), len(out) - i)
        out[i:i + k] += y[:k]


STROKE_S = 0.6     # each roll stroke is rendered this long from its own onset, then placed (a roll is fast to build)


def roll_times(m, t_end, rate0, rate1, tr):
    """Stroke onsets accelerating from rate0 to rate1 strokes a second over t_end, with the player's own unevenness."""
    out = []
    tt = 0.02
    while tt < t_end - 0.03:
        x = tt / t_end
        out.append(tt)
        tt += 1.0 / sk.lin(x, rate0, rate1) * tr.uniform(0.93, 1.07)
    return out


def _shekere_rush(m, v, tr, n):
    """Afrobeat's riser: shekere strokes doubling from eighths to thirty-seconds as it swells, pushes and pulls
    alternating, into a bottom slap at 2.3 s."""
    mm = {"TUNE": m["TUNE"], "DAMP": 0.25}
    out = np.zeros(n)
    t_end = 2.3
    for k, at in enumerate(roll_times(m, t_end, sk.lin(m["SPEED"], 4, 6), sk.lin(m["SPEED"], 12, 20), tr)):
        a = 0.2 + 0.8 * (at / t_end) ** 1.6
        place(out, _shekere_stroke(mm, max(0.1, v * a), tr, n_of(STROKE_S), "push" if k % 2 == 0 else "pull"), at)
    place(out, 1.3 * _shekere_stroke(mm, v, tr, n_of(STROKE_S), "slap"), t_end)
    return out


def _talking_call(m, v, tr, n):
    """Afrobeats' riser: the talking drum calling, strokes quickening and each squeezed higher than the last, into a
    long up-and-down glide at 2.25 s."""
    out = np.zeros(n)
    t_end = 2.25
    times = roll_times(m, t_end, sk.lin(m["SPEED"], 3, 5), sk.lin(m["SPEED"], 9, 15), tr)
    for k, at in enumerate(times):
        x = at / t_end
        mm = {"TUNE": m["TUNE"], "SQUEEZE": m["SQUEEZE"] * (0.2 + 0.8 * x), "CONTOUR": 0.0}
        place(out, _talking(mm, max(0.1, v * (0.3 + 0.7 * x ** 1.3)), tr, n_of(STROKE_S), 150.0, 0.4, 1.0, 0.38e-3,
                            0.62), at)
    mm = {"TUNE": m["TUNE"], "SQUEEZE": m["SQUEEZE"], "CONTOUR": 1.0}
    place(out, 1.2 * _talking(mm, v, tr, n - n_of(t_end), 150.0, 0.55, 1.0, 0.38e-3, 0.62), t_end)
    return out


def _sabar_roll(m, v, tr, n):
    """Mbalax's roll: the nder's stick rolling, faster and louder, the hand dropping open tones under it, into a
    pressed-stick accent with a slap at 2.15 s (the bakk's landing)."""
    mm = {"TUNE": m["TUNE"], "DAMP": 0.3, "STROKE": 2 / 3}
    out = np.zeros(n)
    t_end = 2.15
    for k, at in enumerate(roll_times(m, t_end, sk.lin(m["SPEED"], 8, 12), sk.lin(m["SPEED"], 18, 28), tr)):
        a = 0.18 + 0.82 * (at / t_end) ** 1.7
        st = 2 / 3 if k % 3 else 0.0
        place(out, _sabar(mm, max(0.1, v * a), tr, n_of(STROKE_S), 340.0, 0.78, False, stroke=st), at)
    place(out, 1.2 * _sabar(mm, v, tr, n_of(STROKE_S), 340.0, 0.78, False, stroke=1.0), t_end)
    place(out, 0.9 * _sabar(mm, v, tr, n_of(STROKE_S), 340.0, 0.78, False, stroke=1 / 3), t_end + 0.004)
    return out


def _abanico(m, v, tr, n):
    """The timbalero's abanico: a press roll on the macho opening into the rimshot that kicks the band into the
    mambo, at 1.9 s, here preceded by a run of single strokes speeding up (the riser)."""
    mm = {"TUNE": m["TUNE"], "DAMP": 0.3}
    out = np.zeros(n)
    t_end = 1.9
    for k, at in enumerate(roll_times(m, t_end - 0.25, sk.lin(m["SPEED"], 6, 9), sk.lin(m["SPEED"], 14, 20), tr)):
        a = 0.2 + 0.8 * (at / t_end) ** 1.5
        place(out, _timbale_stroke(mm, max(0.1, v * a), tr, n_of(STROKE_S), 0.0), at)
    # the press roll: bounces pressed into the head for 0.25 s, crescendo, then the rimshot
    tt = t_end - 0.25
    while tt < t_end - 0.01:
        a = 0.45 + 0.5 * (tt - (t_end - 0.25)) / 0.25
        for b in range(int(tr.integers(3, 6))):
            place(out, _timbale_stroke(mm, max(0.08, v * a * 0.6 ** b), tr, n_of(STROKE_S), 0.0) * 0.8, tt + b * 0.006)
        tt += 1 / 22 * tr.uniform(.9, 1.1)
    place(out, 1.6 * _timbale_stroke(mm, v, tr, n - n_of(t_end), 0.5), t_end)
    return out


RENDER = {
    "KICK": _kick, "DEEP_KICK": _deep_kick, "SNARE": _snare, "RIMSHOT": _rimshot, "RIM": _rim, "TOM": _tom,
    "HAT_CLOSED": _hat_closed, "HAT_OPEN": _hat_open, "HAT_FOOT": _hat_foot, "RIDE": _ride, "CRASH": _crash,
    "CLAP": _clap, "SNAP": _snap, "SHAKER": _shaker,
    "QUINTO": _quinto, "CONGA": _conga, "TUMBA": _tumba, "BONGO_HIGH": _bongo_high, "BONGO_LOW": _bongo_low,
    "COWBELL": _cowbell, "BIG_BELL": _big_bell, "AGOGO_HIGH": _agogo_high, "AGOGO_LOW": _agogo_low,
    "CLAVE": _clave, "STICKS": _sticks,
    "SHEKERE": _shekere, "DUNDUN": _dundun, "TAMA": _tama, "NDER": _nder, "MBENG": _mbeng, "COL": _col,
    "TIMBALE": _timbale,
    "SHEKERE_RUSH": _shekere_rush, "TALKING_CALL": _talking_call, "SABAR_ROLL": _sabar_roll, "ABANICO": _abanico,
}
assert set(RENDER) == set(VOICES)


if __name__ == "__main__":
    import time
    out = sys.argv[1] if len(sys.argv) > 1 else "calabash-voices"
    os.makedirs(out, exist_ok=True)
    only = sys.argv[2:] or list(VOICES)
    for name in only:
        t0 = time.perf_counter()
        y = render(name)
        dt = (time.perf_counter() - t0) * 1000
        sk.write_wav(os.path.join(out, name.lower() + ".wav"), y)
        print(f"{name:13s} {len(y) / RATE:5.2f} s  {dt:7.0f} ms  peak {np.max(np.abs(y)):.3f}", flush=True)
