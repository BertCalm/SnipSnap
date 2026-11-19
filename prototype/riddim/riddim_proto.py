"""RIDDIM round 0: a dancehall voice palette in Python (working name; not an engine yet).

Python harness, not Kotlin. Nothing here has been heard. The owner's reference loops are used as
*targets* (measured pitch sweeps, decay times, brightness, the pitches of the wood and metal parts;
see ../../docs/RIDDIM.md). No audio from those loops is copied, fitted or shipped: they are commercial
sample-pack loops, so their own resonances stay out of the app. The percussion that needs a recording's
realism reuses CALABASH's voices, which were fitted to CC0 recordings (prototype/calabash/NOTICE.txt).

Two families, so drums and percussion can be mixed on one kit:
  * Electronic drums, synthesized from scratch: KICK, DEMBOW_KICK, BOOM (a long tuned 808-style boom),
    SLIDE (the same boom with a slow pitch glide), RIMSHOT, CLAP, SNARE, HAT_CLOSED, HAT_OPEN.
    Dancehall since the mid-1980s is drum-machine music, so a synthesized kick is the real instrument
    here, not an imitation of an acoustic one.
  * Percussion: WOOD (tuned wooden bar, struck with a mallet) and METAL (a keyed bronze bar played in
    a detuned pair, as gamelan players tune pairs to beat), plus CALABASH's SHAKER, CLAVE, STICKS,
    COWBELL, CONGA, BONGO_HIGH and TAMA (talking drum).

    python3 prototype/riddim/riddim_proto.py OUT     # every voice soft/hard, two takes, three grooves
"""
from __future__ import annotations

import functools
import math
import os
import sys

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "calabash"))
import calabash_proto as cb  # noqa: E402  (CALABASH's fitted percussion, and its house finish)

RATE = 44_100
OVER = 4
FS = RATE * OVER          # every electronic voice is drawn at 176.4 kHz, then decimated (house rule)
TAU = 2 * math.pi


def hz(midi: float) -> float:
    return 440.0 * 2 ** ((midi - 69) / 12)


def rng_for(*parts) -> np.random.Generator:
    """Deterministic randomness: the same voice, velocity and take always give the same bytes."""
    # Python's own hash() is salted per process, so derive the seed from a stable digest instead.
    import hashlib
    seed = int.from_bytes(hashlib.sha256(repr(("riddim",) + parts).encode()).digest()[:4], "little")
    return np.random.default_rng(seed)


def t_axis(seconds: float) -> np.ndarray:
    return np.arange(int(seconds * FS)) / FS


def finish(raw: np.ndarray, seconds: float) -> np.ndarray:
    """House output stage, the same shape CALABASH uses so both families sit at one level:
    decimate 4:1 with an anti-alias filter, remove DC (20 Hz one-pole), 50 ms fade, peak 0.89."""
    y = signal.resample_poly(raw, 1, OVER)
    y = y - np.mean(y)
    rc = 1 / (TAU * 20.0)
    al = rc / (rc + 1 / RATE)
    y = signal.lfilter([al, -al], [1, -al], y)[: int(seconds * RATE)]
    fade = min(len(y), int(0.05 * RATE))
    y[-fade:] *= 0.5 * (1 + np.cos(np.linspace(0, math.pi, fade)))
    p = np.max(np.abs(y))
    return y / p * 0.89 if p > 0 else y


# ------------------------------------------------------------------ electronic building blocks

def swept_sine(t, f_end, f_start, tau):
    """A sine whose pitch falls from f_start to f_end with time constant tau.
    This falling pitch is what makes an electronic kick 'thump': the first few milliseconds are
    a fast, bright swoop, then it settles on the note. Phase is the running integral of frequency,
    so the sweep is smooth (no clicks from jumping frequency)."""
    f = f_end + (f_start - f_end) * np.exp(-t / tau)
    return np.sin(TAU * np.cumsum(f) / FS), f


def drive(x, amount):
    """Soft clipping (tanh). It adds harmonics at 2x/3x the note, which is how a 40 Hz boom stays
    audible on a phone speaker that cannot play 40 Hz. amount 0 = clean."""
    if amount <= 0:
        return x
    g = 1 + 6 * amount
    return np.tanh(g * x) / np.tanh(g)


def bandpass(x, lo, hi, order=2):
    sos = signal.butter(order, [lo, hi], "bandpass", fs=FS, output="sos")
    return signal.sosfilt(sos, x)


def highpass(x, f, order=2):
    return signal.sosfilt(signal.butter(order, f, "highpass", fs=FS, output="sos"), x)


def click(n, v, rng, width_ms=0.25):
    """The beater: a short noise tick, high-passed. Velocity makes it louder and brighter."""
    w = int(width_ms * 1e-3 * FS)
    c = np.zeros(n)
    c[:w] = rng.uniform(-1, 1, w) * np.hanning(w)
    return highpass(c, 1500 + 3000 * v)


def env_exp(t, t60):
    """Exponential decay that falls 60 dB in t60 seconds."""
    return np.exp(-6.9078 * t / t60)


def resonator(x, f, t60):
    """One ringing mode (a two-pole filter): what a struck bar or a bridged-T drum circuit does."""
    r = math.exp(-6.9078 / (t60 * FS))
    w = TAU * f / FS
    return signal.lfilter([1 - r], [1, -2 * r * math.cos(w), r * r], x)


# ------------------------------------------------------------------ the electronic voices
# Every voice takes (macros m, velocity v 0..1, take k) and returns raw audio at FS.
# Velocity changes character, not only level (owner's gate): softer = less click, lower sweep start,
# darker noise. Each take re-strikes with small, seeded differences (round robin).

def _kick(m, v, k, seconds, f_end, f_start, tau, t60, drv, click_amt):
    rng = rng_for("kick", f_end, round(v, 3), k)
    t = t_axis(seconds)
    jit = 1 + rng.normal(0, 0.02)                         # take-to-take: a slightly different hit
    tune = 2 ** ((m.get("TUNE", .5) - .5) * 12 / 12)      # TUNE: +-6 semitones
    # A soft hit starts its sweep lower and drives the circuit less, so it is rounder, not only quieter.
    body, _ = swept_sine(t, f_end * tune, f_start * tune * (0.6 + 0.4 * v) * jit, tau * jit)
    amp = env_exp(t, t60 * (0.85 + 0.15 * m.get("DECAY", .5) * 2))
    y = drive(body * amp * (0.6 + 0.4 * v), drv * m.get("DRIVE", .5) * 2 * (0.3 + 0.7 * v))
    return y + click_amt * v * v * click(len(t), v, rng)      # v squared: a soft hit loses its beater fastest


def kick(m, v, k):
    """Dancehall punch kick. Target (killawani/favelastomp, measured): pitch about 73 Hz averaged over the
    first 93 ms, falling to about 40 Hz by 80 ms; band decay to -20 dB in about 55 ms."""
    return _kick(m, v, k, 0.6, f_end=40.0, f_start=190.0, tau=0.028, t60=0.30, drv=0.35, click_amt=0.35)


def dembow_kick(m, v, k):
    """Deep dembow kick. Target (the lofi dembow loop): 58 Hz falling past 28 Hz to about 23 Hz.
    Below 30 Hz a phone plays nothing, so DRIVE puts harmonics where speakers can play (THUMP SUB's lesson)."""
    return _kick(m, v, k, 0.7, f_end=27.0, f_start=140.0, tau=0.030, t60=0.55, drv=0.6, click_amt=0.25)


def boom(m, v, k, glide=0.025, f_start=150.0):
    """Long tuned 808-style boom: the kick *is* the bassline. Target (nasty 808 loop): settles on
    43.3 Hz (F1, -13 cents) within 80 ms and sustains. Default note F1; TUNE moves it +-12 semitones, so
    one voice spreads a bass line across pads."""
    rng = rng_for("boom", glide, round(v, 3), k)
    seconds = 1.6
    t = t_axis(seconds)
    note = 29 + round((m.get("TUNE", .5) - .5) * 24)      # MIDI 29 = F1 (43.65 Hz)
    # Each take varies the glide and the ring a little, so repeated notes are new strikes, not copies.
    glide = glide * (1 + rng.normal(0, .12))
    body, _ = swept_sine(t, hz(note), f_start * (0.8 + 0.2 * v) * (1 + rng.normal(0, .03)), glide)
    t60 = (0.6 + 1.6 * m.get("DECAY", .5)) * (1 + rng.normal(0, .04))
    amp = env_exp(t, t60) * np.minimum(1, t / 0.002)       # 2 ms attack: no click unless asked for
    y = drive(body * amp * (0.55 + 0.45 * v), m.get("DRIVE", .5) * 0.9 * (0.5 + 0.5 * v))   # harder = more growl
    return y + 0.12 * v * v * click(len(t), v, rng, 0.15)


def slide(m, v, k):
    """The same boom with a slow glide. Target (fandango loop): 76 -> 67 -> 55 -> 43 Hz over 200 ms."""
    return boom(m, v, k, glide=0.09, f_start=95.0)


def rimshot(m, v, k):
    """Digital dancehall rim: two bright resonators (a bridged-T drum circuit's ring) struck by a pulse.
    Target: snare/rim energy peaking near 1.0 to 1.1 kHz (killawani), very short (-20 dB in about 32 ms)."""
    rng = rng_for("rim", round(v, 3), k)
    seconds = 0.25
    n = int(seconds * FS)
    tune = 2 ** ((m.get("TUNE", .5) - .5))
    x = np.zeros(n)
    x[: int(0.0004 * FS)] = 1
    x += 0.15 * rng.uniform(-1, 1, n) * env_exp(np.arange(n) / FS, 0.01)
    lo = resonator(x, 480 * tune, 0.05)
    hi = resonator(x, 1080 * tune * (1 + rng.normal(0, .006)), 0.06 + 0.04 * m.get("DECAY", .5))
    y = 0.6 * lo + hi * (0.6 + 0.6 * v)
    return drive(y / np.max(np.abs(y)), 0.4 + 0.4 * v)


def clap(m, v, k):
    """Electronic clap: three or four quick noise bursts (several hands, not quite together), then a
    tail, all through a band centred where the references put their clap (1.05 to 1.35 kHz)."""
    rng = rng_for("clap", round(v, 3), k)
    seconds = 0.45
    t = t_axis(seconds)
    n = len(t)
    noise = rng.uniform(-1, 1, n)
    e = np.zeros(n)
    gaps = np.cumsum([0, 0.009, 0.011, 0.008]) * (1 + rng.normal(0, .08))
    for i, g in enumerate(gaps[: 3 + (v > .5)]):
        tt = t - g
        e += (tt >= 0) * np.exp(-np.maximum(tt, 0) / 0.0025) * (0.8 + 0.2 * i / 3)
    tail_at = gaps[-1]
    e += (t >= tail_at) * np.exp(-np.maximum(t - tail_at, 0) / (0.02 + 0.04 * m.get("DECAY", .5)))
    centre = 1200 * 2 ** ((m.get("TUNE", .5) - .5)) * (0.85 + 0.15 * v)
    y = bandpass(noise * e, centre / 1.9, centre * 2.6) + 0.15 * v * highpass(noise * e, 4000)
    return y


def snare(m, v, k):
    """Dancehall snare: a short tuned body (about 207 and 260 Hz, the dembow snare's partials) plus
    bright noise for the wires. The body is the 'tock', the noise the 'tss'."""
    rng = rng_for("snare", round(v, 3), k)
    seconds = 0.45
    t = t_axis(seconds)
    tune = 2 ** ((m.get("TUNE", .5) - .5))
    b1, _ = swept_sine(t, 207 * tune, 290 * tune, 0.01)
    b2, _ = swept_sine(t, 262 * tune, 340 * tune, 0.008)
    body = (b1 + 0.6 * b2) * env_exp(t, 0.16)
    wires = bandpass(rng.uniform(-1, 1, len(t)), 1500 + 1500 * v, 9000) * env_exp(t, 0.12 + 0.2 * m.get("DECAY", .5))
    return drive(0.7 * body + (0.5 + 0.5 * v) * wires * 1.4, 0.3)


# Six square-wave oscillators at unrelated frequencies make the metallic cluster drum machines use for hats.
HAT_FREQS = (205.3, 304.4, 369.6, 522.7, 540.0, 800.0)


def _squares(t, rng):
    y = np.zeros(len(t))
    for f in HAT_FREQS:
        f = f * (1 + rng.normal(0, .002))
        ph = rng.uniform(0, 1)
        # band-limited square: odd harmonics only up to 40 kHz, so nothing folds back (no aliasing)
        for h in range(1, int(40000 / f) + 1, 2):
            y += np.sin(TAU * (f * h * t + ph * h)) / h
    return y


def hat(m, v, k, t60):
    rng = rng_for("hat", t60, round(v, 3), k)
    seconds = max(0.25, t60 * 1.2)
    t = t_axis(seconds)
    metal = _squares(t, rng)
    lo = 6500 * 2 ** ((m.get("TUNE", .5) - .5)) * (0.85 + 0.15 * v)   # softer = darker
    y = bandpass(0.6 * metal + 0.4 * rng.uniform(-1, 1, len(t)) * 3, lo, 15000, order=3)
    y = signal.sosfilt(signal.butter(2, 8000 + 9000 * v, fs=FS, output="sos"), y)   # a soft hit loses its sizzle
    return y * env_exp(t, t60 * (0.6 + 0.8 * m.get("DECAY", .5))) * np.minimum(1, t / 0.0004)


def hat_closed(m, v, k):
    """Target: tops band -20 dB in 24 to 33 ms (killawani, lofi dembow)."""
    return hat(m, v, k, 0.09)


def hat_open(m, v, k):
    return hat(m, v, k, 0.55)


# ------------------------------------------------------------------ the new percussion voices

# Bar mode ratios. A free wooden bar's overtones are inharmonic (2.76, 5.40 for a uniform bar);
# a marimba maker carves the underside so the second mode lands on 4x (two octaves) and the third on
# about 10x, which is why tuned wood sounds sweet rather than clanky. MATERIAL in a kit could choose.
TUNED_BAR = ((1.0, 1.0, 1.0), (3.98, 0.35, 0.35), (9.9, 0.12, 0.15))
BRONZE_BAR = ((1.0, 1.0, 1.0), (2.76, 0.5, 0.7), (5.40, 0.28, 0.5), (8.93, 0.12, 0.35))


def _struck(f0, modes, t60_root, v, k, seconds, mallet_soft_ms, mallet_hard_ms, name):
    rng = rng_for(name, round(f0, 2), round(v, 3), k)
    n = int(seconds * FS)
    # Mallet: a raised-cosine force pulse. Harder hit = shorter pulse = brighter (more high modes).
    # The pulse width is the mallet: a hard stroke presses for a fraction of a millisecond, a soft one for
    # several, and a pulse W seconds long cannot excite much above 2/W Hz, so softer is darker.
    mallet_ms = mallet_soft_ms + (mallet_hard_ms - mallet_soft_ms) * v
    w = max(2, int(mallet_ms * 1e-3 * FS * (1 + rng.normal(0, .05))))
    x = np.zeros(n)
    x[:w] = 0.5 * (1 - np.cos(TAU * np.arange(w) / w))
    # Round robin: a player never hits the same spot twice. Moving the strike point along the bar changes
    # how much each overtone is excited (a mode is quiet where it has a node), so each take re-weights the
    # upper modes by up to about +-2.5 dB and nudges the pitch by a cent or two. Before this, only the pulse
    # width varied, and two takes that rounded to the same width came out identical (round 1, 2026-11-18).
    y = np.zeros(n)
    for i, (ratio, gain, t60_scale) in enumerate(modes):
        f = f0 * ratio * 2 ** (rng.normal(0, 1.5) / 1200)
        if f > 18000:
            continue
        g = gain if i == 0 else gain * 10 ** (rng.uniform(-2.5, 2.5) / 20)
        y += g * resonator(x, f, t60_root * t60_scale * (1 + rng.normal(0, .04)))
    return y


def wood(m, v, k):
    """Tuned wood. Target: the wood-mallet loop's pitches D4, F#4, A4 and the woody loop's F#3 to D4;
    each hit falls 20 dB in 43 to 60 ms, so T60 is about 0.2 s. Default note F#3 (the loops sit in F# minor);
    TUNE spans an octave either way so one voice plays a whole phrase across pads."""
    note = 54 + round((m.get("TUNE", .5) - .5) * 24)
    # BEATER (added 2026-11-18, after the owner heard round 3: "wood might need more of an attack"). The mallet,
    # from yarn (0) to hard (1), named as CALABASH names its kick beater. At 0.5 the wood is exactly the one
    # approved in rounds 1 and 2: the mallet width is unchanged and no knock is added.
    beater = m.get("BEATER", .5)
    width = 2 ** ((0.5 - beater) * 2)          # 1 at 0.5; a hard mallet presses half as long, a yarn one twice
    knock = max(0.0, (beater - 0.5) * 2)       # 0 up to the middle, 1 at a hard mallet
    # A harder mallet also lifts the bar's own upper modes (they still die faster than the fundamental), so the
    # first tens of milliseconds are brighter, as the references are, not only the first click.
    modes = tuple((r, g * (1 + UPPER_BOOST * knock) ** i, t) for i, (r, g, t) in enumerate(TUNED_BAR))
    f0 = hz(note)
    y = _struck(f0, modes, 0.06 + 0.2 * m.get("DECAY", .5), v, k, 0.6, 3.0 * width, 0.5 * width, "wood")
    if knock > 0:
        y = y + knock * v ** 1.2 * _knock(f0, v, k, 3.0 * width, 0.5 * width)
    return y


# The tock's own ringing, fixed in frequency: the mallet head and the block's small, stiff modes do not move with
# the bar's note. (Hz, gain, T60 s.)
WOOD_KNOCK = ((1900, 1.0, .012), (2750, .8, .009), (4100, .5, .006))
UPPER_BOOST = 3.0     # with KNOCK_GAIN: a hard BEATER puts the first 8 ms near 1.4 to 1.6 kHz (woody loop 1.5 kHz)
KNOCK_GAIN = 6.0


def _knock(f0, v, k, soft_ms, hard_ms):
    """The tock of a mallet landing on wood. Measured on the owner's wood loops (round 3 follow-up): the first
    8 ms of a hit centre near 1.5 to 2.4 kHz, while the tuned wood alone centred near 0.3 to 0.5 kHz, almost all
    fundamental. A real bar also has untuned upper modes that die within a few tens of milliseconds, and the
    mallet's contact is a short burst of noise. Both are drawn here, struck by the same mallet pulse, so a
    softer stroke (a wider pulse) still reaches less of them and stays darker."""
    rng = rng_for("wood-knock", round(f0, 2), round(v, 3), k)
    n = int(0.6 * FS)
    w = max(2, int((soft_ms + (hard_ms - soft_ms) * v) * 1e-3 * FS * (1 + rng.normal(0, .05))))
    pulse = np.zeros(n)
    pulse[:w] = 0.5 * (1 - np.cos(TAU * np.arange(w) / w))
    modes = np.zeros(n)
    for hz_, gain, t60 in WOOD_KNOCK:
        f = hz_ * 2 ** (rng.normal(0, 30) / 1200)               # each take lands a little apart
        modes += gain * 10 ** (rng.uniform(-2, 2) / 20) * resonator(pulse, f, t60 * (1 + rng.normal(0, .08)))
    contact = bandpass(rng.normal(0, 1, n) * pulse, 1500, 7000)
    return KNOCK_GAIN * (modes + 0.25 * contact)


def metal(m, v, k):
    """Keyed bronze, played as a detuned pair. Gamelan players tune each instrument of a pair a few Hz
    apart so the two beat together (ombak, 'wave'). The beating gives a mono render movement that the
    stereo reference loop gets from width. Target: the gamelan loop rings 20 dB down in about 280 ms;
    default note D5 (the loop is in D)."""
    note = 74 + round((m.get("TUNE", .5) - .5) * 24)
    f = hz(note)
    beat = 2 + 6 * m.get("WAVE", .5)                       # Hz between the pair
    t60 = 0.8 + 1.6 * m.get("DECAY", .5)
    a = _struck(f, BRONZE_BAR, t60, v, k, 2.0, 1.5, 0.25, "metal")
    b = _struck(f + beat, BRONZE_BAR, t60, v, k + 101, 2.0, 1.5, 0.25, "metal")
    return a + 0.85 * b



# ------------------------------------------------------------------ round 3: the Brazilian funk tops

# The "ting" loop's bell measured four close partials, 3234, 3309, 3715 and 3875 Hz, falling 20 dB in 48 ms
# (round 3, reference_targets.json "ting:ting"). Written as ratios to the lowest: two pairs a few percent
# apart, so the pairs beat against each other the way a small hand bell or a struck pan lid shimmers.
TING_BAR = ((1.0, 1.0, 1.0), (1.023, 0.8, 0.9), (1.149, 0.6, 0.7), (1.198, 0.45, 0.6), (2.31, 0.15, 0.3))


def ting(m, v, k):
    """A small, high, metal bell: the bright "ting" that rides a Brazilian funk groove. Default 3234 Hz (the
    reference's lowest partial); TUNE spans an octave either way. A metal beater is a short pulse, so the
    mallet runs 0.4 ms soft to 0.06 ms hard: a soft stroke cannot reach the upper pair, so it is darker."""
    f0 = 3234.0 * 2 ** ((m.get("TUNE", .5) - .5) * 24 / 12)
    t60 = 0.08 + 0.38 * m.get("DECAY", .5)        # default 0.27 s: the pairs beat, so -20 dB lands near 50 ms
    return _struck(f0, TING_BAR, t60, v, k, 0.5, 0.4, 0.06, "ting")


def ratchet(m, v, k):
    """A scraper (a ratchet or a ridged gourd): a stick dragged over teeth. Each tooth is a click that rings a
    small plate; the plate measured a cluster at 2.9 to 3.06 kHz and the whole scrape fell 20 dB in 77 ms
    (round 3, "ratchet:ratchet"). Teeth come faster through the middle of the drag and slow at its end.
    TUNE moves the plate an octave either way, DECAY the drag's length (40 to 200 ms), WAVE how fast the teeth
    come (120 to 420 a second). A harder drag is faster, louder per tooth and brighter; a softer one leaves
    more of the low body (near 500 Hz, the "ratchet:body" partials) and less of the plate, so it is darker."""
    rng = rng_for("ratchet", k, round(v, 3))
    length = 0.04 + 0.16 * m.get("DECAY", .5)
    rate = (120 + 300 * m.get("WAVE", .5)) * (0.75 + 0.35 * v)
    n = int((length + 0.12) * FS)
    x = np.zeros(n)
    t = 0.0
    while t < length:
        phase = t / length                                   # 0..1 through the drag
        speed = 0.6 + 0.8 * math.sin(math.pi * min(1.0, phase * 1.1))    # slow, fast, slow
        level = (0.35 + 0.65 * math.sin(math.pi * min(1.0, phase * 1.05 + 0.05))) * rng.uniform(0.55, 1.0)
        i = int(t * FS)
        x[i] += level
        if i + 1 < n:
            x[i + 1] -= 0.6 * level                          # a tooth is a sharp up-down, not a step
        t += (1 / (rate * speed)) * (1 + rng.normal(0, 0.12))   # each take drags its own way
    shift = 2 ** ((m.get("TUNE", .5) - .5) * 2)
    plate = sum(g * resonator(x, f * shift * 2 ** (rng.normal(0, 8) / 1200), t60)
                for f, g, t60 in ((2913, 1.0, .022), (2985, .8, .018), (3060, .7, .02), (5480, .25, .01)))
    body = sum(g * resonator(x, f * shift, .03) for f, g in ((488, .6), (526, .45)))
    grit = bandpass(x * rng.normal(0, 1, n), 2000, 7000) * 0.3
    bright = 0.35 + 0.65 * v                                 # softer: less plate and grit, more body
    return bright * (plate + grit) + (1.15 - 0.6 * v) * body


# ------------------------------------------------------------------ the ratchet A/B (2026-11-19)
# The owner heard RATCHET and was "not sure it sounds as it should". Measured again (Python harness), the sound
# in the "ratchet" top loop is not a scrape: each hit is a smooth, pitched tone (spectral flatness 0.006 to 0.07,
# where noise is 1), its note changes from hit to hit (about 2.2, 2.5, 3.0 and 3.8 kHz), and only 27% of its level
# movement is fast flutter (11 to 41%), where RATCHET's tooth train is 48 to 60%. The owner asked for both
# readings side by side: BLIP is the loop's sound, RATCHET_WOOD a real ratchet noisemaker done properly.

def blip(m, v, k):
    """A: the loop's top sound. A short, smooth, high tone that rises a little as it sounds (one reference hit went
    2455 to 2498 Hz, +1.7%) with two faint upper tones at the ratios seen in the loop (1.24 and 1.83 of the main
    one). Default 2500 Hz, TUNE an octave either way, so pads can play the loop's changing notes. DECAY default
    puts -20 dB near the measured 77 ms. A softer hit starts a little slower, rises less and keeps less of the
    upper tones, so it is darker."""
    rng = rng_for("blip", k, round(v, 3))
    f0 = 2500.0 * 2 ** ((m.get("TUNE", .5) - .5) * 2) * 2 ** (rng.normal(0, 8) / 1200)
    t60 = (0.08 + 0.3 * m.get("DECAY", .5)) * (1 + rng.normal(0, .05))
    t = t_axis(0.5)
    glide = 0.017 * (0.5 + 0.5 * v) * (1 + rng.normal(0, .2))
    f = f0 * (1 + glide * (1 - np.exp(-t / 0.02)))
    phase = TAU * np.cumsum(f) / FS
    attack = 0.005 - 0.003 * v                          # 5 ms soft, 2 ms hard: smooth, never a click
    env = np.minimum(1.0, t / attack) * env_exp(t, t60)
    upper = (0.25 * 10 ** (rng.uniform(-2, 2) / 20) * np.sin(1.24 * phase + rng.uniform(0, TAU))
             + 0.15 * 10 ** (rng.uniform(-2, 2) / 20) * np.sin(1.83 * phase + rng.uniform(0, TAU)))
    breath = bandpass(rng.normal(0, 1, len(t)), f0 * 0.85, min(f0 * 1.15, 20000)) * 0.08
    return env * (np.sin(phase) + v ** 1.5 * upper) + env * breath


def ratchet_wood(m, v, k):
    """B: a real ratchet. A wooden gear, turned, flicks a wooden tongue: separate clicks you can count, 20 to 40 a
    second, not the 300-a-second tooth train of RATCHET, which reads as a buzz. Each click rings the tongue (about
    1.6, 2.4 and 3.3 kHz, dying in 10 to 25 ms) and the hollow box it sits on (near 420 Hz). DECAY is how long
    the turn lasts (0.12 to 0.6 s), WAVE how fast the gear turns. A harder turn is faster and brighter; a softer
    one is slower, with more box and less tongue, so it is darker. Each take turns a little differently."""
    rng = rng_for("ratchet-wood", k, round(v, 3))
    length = 0.12 + 0.48 * m.get("DECAY", .5)
    rate = (20 + 20 * m.get("WAVE", .5)) * (0.7 + 0.45 * v)
    n = int((length + 0.15) * FS)
    x = np.zeros(n)
    t = 0.0
    i = 0
    while t < length:
        level = (1.0 - 0.35 * t / length) * rng.uniform(0.7, 1.0)    # the turn loses a little force
        a = int(t * FS)
        w = max(2, int(0.0004 * FS))
        x[a:a + w] += level * 0.5 * (1 - np.cos(TAU * np.arange(w) / w))[: n - a]
        slow = 1 + 0.4 * (t / length) ** 2                            # the gear slows at the end of the turn
        t += (1 / rate) * slow * (1 + rng.normal(0, .08))
        i += 1
    shift = 2 ** ((m.get("TUNE", .5) - .5) * 2)
    tongue = sum(g * resonator(x, f * shift * 2 ** (rng.normal(0, 15) / 1200), t60)
                 for f, g, t60 in ((1600, 1.0, .025), (2400, .7, .018), (3300, .45, .012)))
    box = resonator(x, 420 * shift, .04)
    snap = bandpass(x * rng.normal(0, 1, n), 2000, 8000) * 0.3
    return (0.3 + 0.7 * v) * (tongue + snap) + (1.2 - 0.6 * v) * 0.6 * box

# ------------------------------------------------------------------ the palette

ELECTRONIC = {
    # name: (renderer, seconds, kit role)
    "KICK": (kick, 0.6, "kick"),
    "DEMBOW_KICK": (dembow_kick, 0.7, "kick"),
    "BOOM": (boom, 1.6, "808"),
    "SLIDE": (slide, 1.6, "808"),
    "RIMSHOT": (rimshot, 0.25, "snare"),
    "CLAP": (clap, 0.45, "snare"),
    "SNARE": (snare, 0.45, "snare"),
    "HAT_CLOSED": (hat_closed, 0.25, "hat"),
    "HAT_OPEN": (hat_open, 0.7, "hat"),
    "WOOD": (wood, 0.6, "perc"),
    "METAL": (metal, 2.0, "perc"),
    "TING": (ting, 0.5, "perc"),          # round 3
    "RATCHET": (ratchet, 0.35, "perc"),   # round 3
    "BLIP": (blip, 0.5, "perc"),          # ratchet A/B: the loop's sound
    "RATCHET_WOOD": (ratchet_wood, 0.8, "perc"),   # ratchet A/B: a real ratchet
}
# Mixed in from CALABASH (fitted to CC0 recordings, approved by the owner 2026-11-15).
BORROWED = ["SHAKER", "CLAVE", "STICKS", "COWBELL", "CONGA", "BONGO_HIGH", "TAMA",
            # round 3: the funk tambor (TUMBA, QUINTO), the sabar family and its friends, the agogo
            "TUMBA", "QUINTO", "NDER", "MBENG", "COL", "DUNDUN", "SHEKERE", "AGOGO_HIGH", "TALKING_CALL"]


def render(voice: str, macros: dict | None = None, velocity: float = 1.0, take: int = 0) -> np.ndarray:
    """One hit at 44.1 kHz, peak 0.89, from either family. Cached: the same strike is never drawn twice."""
    return _render_cached(voice, tuple(sorted((macros or {}).items())), round(float(velocity), 4), int(take)).copy()


@functools.lru_cache(maxsize=4096)
def _render_cached(voice, macro_items, velocity, take):
    macros = dict(macro_items)
    m = {"TUNE": .5, "DECAY": .5, "DRIVE": .5, "WAVE": .5} | (macros or {})
    v = float(np.clip(velocity, 0.05, 1.0))
    if voice in ELECTRONIC:
        fn, seconds, _ = ELECTRONIC[voice]
        raw = fn(m, v, take)
        if not np.all(np.isfinite(raw)):
            raise FloatingPointError(voice)
        return finish(raw, seconds)
    if voice in BORROWED:
        cm = {key: val for key, val in m.items() if key in cb.DEFAULTS}
        return cb.render(voice, cm, v, take)
    raise ValueError(voice)


# ------------------------------------------------------------------ grooves (authored sketches, not transcriptions)

def place(bars, bpm, swing, hits):
    """hits: list of (voice, macros, step_in_bar, gain, velocity). 16 steps per bar.
    swing: fraction of a sixteenth that off-beat sixteenths are pushed late (0 = straight)."""
    step = 60 / bpm / 4
    n = int((bars * 16 * step + 2.0) * RATE)
    out = np.zeros(n)
    count = {}
    for b in range(bars):
        for voice, macros, s, gain, vel in hits:
            if isinstance(s, tuple):           # (step, only_on_bar)
                s, only = s
                if b % 4 != only:
                    continue
            key = (voice, tuple(sorted((macros or {}).items())))
            take = count.get(key, 0) % 4       # round robin: four takes dealt in turn
            count[key] = take + 1
            y = render(voice, macros, vel, take) * gain
            at = int(((b * 16 + s) * step + (swing * step if s % 2 else 0)) * RATE)
            out[at: at + len(y)] += y[: max(0, n - at)]
    out = out[: int(bars * 16 * step * RATE) + int(0.3 * RATE)]
    return out / np.max(np.abs(out)) * 0.89


def grooves():
    F = {"TUNE": .5}
    return {
        # Clave dancehall: the kick and rim lock to the 3-2 son clave (steps 1, 4, 7, 11, 13), as in the
        # killawani/favelastomp/reasons references; clap on 2 and 4; wood answers in F# minor.
        "clave_dancehall_102": (102, 0.0, [
            ("KICK", F, s, 1.0, 1.0) for s in (0, 3, 6, 10, 12)] + [
            ("RIMSHOT", F, s, .45, .8) for s in (3, 6, 10)] + [
            ("CLAP", F, s, .7, 1.0) for s in (4, 12)] + [
            ("HAT_CLOSED", F, s, .32 if s % 4 == 2 else .18, .9 if s % 4 == 2 else .45) for s in range(0, 16, 2)] + [
            ("SHAKER", None, s, .25, .7) for s in (1, 5, 9, 13)] + [
            ("WOOD", {"TUNE": .5}, (7, 1), .4, .9), ("WOOD", {"TUNE": .5 + 3 / 24}, (9, 1), .35, .7),
            ("WOOD", {"TUNE": .5 + 7 / 24}, (13, 3), .4, .9), ("WOOD", {"TUNE": .5 + 5 / 24}, (15, 3), .3, .6)]),
        # Dembow: kick on every beat, snare on steps 4, 7, 12, 15 (the lofi dembow reference's pattern).
        "dembow_95": (95, 0.0, [
            ("DEMBOW_KICK", F, s, 1.0, 1.0) for s in (0, 4, 8, 12)] + [
            ("SNARE", F, s, .75, 1.0 if s in (3, 11) else .8) for s in (3, 6, 11, 14)] + [
            ("HAT_CLOSED", F, s, .25, .7) for s in (2, 6, 10, 14)] + [
            ("HAT_OPEN", F, (14, 3), .25, .8)] + [
            ("CONGA", None, s, .3, .7) for s in (7, 15)] + [
            ("COWBELL", None, (0, 0), .25, .8), ("CLAVE", None, (10, 2), .3, .8)]),
        # 808 riddim: the boom plays a bass line (F1, Ab1, F1, Bb1); rim on the backbeat; the metal pair answers.
        "boom_riddim_100": (100, 0.0, [
            ("BOOM", {"TUNE": .5}, (0, 0), 1.0, 1.0), ("BOOM", {"TUNE": .5}, (10, 0), .9, .9),
            ("BOOM", {"TUNE": .5 + 3 / 24}, (0, 1), 1.0, 1.0), ("SLIDE", {"TUNE": .5}, (10, 1), .9, .9),
            ("BOOM", {"TUNE": .5}, (0, 2), 1.0, 1.0), ("BOOM", {"TUNE": .5}, (10, 2), .9, .9),
            ("BOOM", {"TUNE": .5 + 5 / 24}, (0, 3), 1.0, 1.0),
            ("SLIDE", {"TUNE": .5 + 3 / 24}, (10, 3), .9, .9)] + [
            ("RIMSHOT", F, s, .6, 1.0) for s in (4, 12)] + [
            ("CLAP", F, (12, 3), .5, .9)] + [
            ("HAT_CLOSED", F, s, .22, .5 + .4 * (s % 4 == 2)) for s in range(16)] + [
            ("METAL", {"TUNE": .5}, (14, 1), .3, .8), ("METAL", {"TUNE": .5 - 2 / 24}, (14, 3), .3, .8),
            ("STICKS", None, (7, 2), .3, .7)]),
    }


def main(out):
    import wave
    os.makedirs(os.path.join(out, "voices"), exist_ok=True)
    os.makedirs(os.path.join(out, "grooves"), exist_ok=True)

    def write(path, y):
        pcm = (np.clip(y, -1, 1) * 32767).astype("<i2")
        with wave.open(path, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(pcm.tobytes())

    for voice in list(ELECTRONIC) + BORROWED:
        for vel in (0.4, 1.0):
            for take in (0, 1):
                y = render(voice, None, vel, take)
                assert np.all(np.isfinite(y))
                write(os.path.join(out, "voices", f"{voice}_v{int(vel * 100):03d}_t{take}.wav"), y)
    for name, (bpm, swing, hits) in grooves().items():
        write(os.path.join(out, "grooves", f"{name}.wav"), place(4, bpm, swing, hits))
    print("wrote", out)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "riddim-out")
