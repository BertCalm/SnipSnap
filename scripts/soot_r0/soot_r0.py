#!/usr/bin/env python3
"""SOOT round 0: a grimy boom bap kit from physical models (Python harness, not Kotlin).

The characteristic gesture is: dry skin knock that sags -> papery crack -> tight coil clatter ->
the whole kit breathing through a dictaphone.

Everything here is a listening study for the owner's round-0 gate. No constant is measured
against a real instrument; every one is a "shape" until the owner has heard it. The house
contract (4x internal synthesis, the Kotlin output stage) is approximated: linear modal and
additive voices are synthesized at 44.1 kHz with every partial under 16 kHz, so nothing can
alias; the two nonlinear stages (the cassette edge and the sub's drive) run at 4x and are
band-limited back down, as Valve does.

Run:  python3 scripts/soot_r0/soot_r0.py [out_dir]   (default testkit/soot-r0)
Needs numpy and scipy.
"""
from __future__ import annotations

import csv
import hashlib
import json
import math
import os
import sys
import time
import wave

import numpy as np
from scipy import signal

RATE = 44_100
PAD_CAP_SECONDS = 1.45  # under the classifier's 1.5 s LOOP threshold, as BREAKER caps its hits


# ---------------------------------------------------------------- small helpers

def seed_for(*parts) -> int:
    """A stable seed from any parts (the role of Dsp.seedFor): same parts, same seed."""
    h = hashlib.blake2b(digest_size=8)
    for p in parts:
        h.update(repr(p).encode())
        h.update(b"\x1f")
    return int.from_bytes(h.digest(), "big") & 0x7FFF_FFFF


def rng(*parts) -> np.random.Generator:
    return np.random.default_rng(seed_for(*parts))


def t_axis(seconds: float) -> np.ndarray:
    return np.arange(int(seconds * RATE)) / RATE


def peak(x: np.ndarray) -> float:
    return float(np.max(np.abs(x))) if x.size else 0.0


def norm(x: np.ndarray, to: float = 1.0) -> np.ndarray:
    p = peak(x)
    return x * (to / p) if p > 0 else x


def butter(kind: str, hz, order: int = 2):
    return signal.butter(order, hz, btype=kind, fs=RATE, output="sos")


def filt(sos, x: np.ndarray) -> np.ndarray:
    return signal.sosfilt(sos, x)


def peaking(x: np.ndarray, hz: float, db: float, q: float) -> np.ndarray:
    """RBJ peaking biquad."""
    a = 10 ** (db / 40)
    w = 2 * math.pi * hz / RATE
    al = math.sin(w) / (2 * q)
    b = [1 + al * a, -2 * math.cos(w), 1 - al * a]
    aa = [1 + al / a, -2 * math.cos(w), 1 - al / a]
    return signal.lfilter(b, aa, x)


def finish(x: np.ndarray, cap: float = PAD_CAP_SECONDS) -> np.ndarray:
    """The output stage's shape: subtract the mean, 20 Hz high-pass, cap, 4 ms fade, peak 0.89."""
    x = x - np.mean(x)
    x = filt(butter("highpass", 20.0, 1), x)
    n = min(len(x), int(cap * RATE))
    x = x[:n].copy()
    f = int(0.004 * RATE)
    x[-f:] *= np.linspace(1, 0, f)
    return norm(x, 0.89)


def oversampled(x: np.ndarray, fn) -> np.ndarray:
    """Run a memoryless nonlinearity at 4x, band-limited back down (Valve.upsample -> stage -> decimate)."""
    up = signal.resample_poly(x, 4, 1)
    return signal.resample_poly(fn(up), 1, 4)[: len(x)]


def damped_partials(freqs, gains, t60s, seconds: float, phases=None, freq_curve=None) -> np.ndarray:
    """Sum of exponentially decaying sines. freq_curve(t) multiplies every frequency (tension sag)."""
    t = t_axis(seconds)
    out = np.zeros_like(t)
    mult = np.ones_like(t) if freq_curve is None else freq_curve(t)
    for i, (f, g, t60) in enumerate(zip(freqs, gains, t60s)):
        if f >= 16_000 or g == 0:
            continue
        ph0 = 0.0 if phases is None else phases[i]
        phase = ph0 + 2 * math.pi * np.cumsum(f * mult) / RATE
        out += g * np.exp(-6.9078 * t / t60) * np.sin(phase)
    return out


def contact(freqs, v: float, lo: float, hi: float, power: float = 1.5) -> np.ndarray:
    """A strike's contact law (shape): a softer strike is a longer contact, a lower corner."""
    fc = lo + (hi - lo) * v ** power
    f = np.asarray(freqs, dtype=float)
    return 1 / (1 + (f / fc) ** 2)


def attack(n: int, ms: float) -> np.ndarray:
    a = np.ones(n)
    k = max(1, int(ms / 1000 * RATE))
    a[:k] = 0.5 - 0.5 * np.cos(np.linspace(0, math.pi, k))
    return a


# ---------------------------------------------------------------- the kick

MEMBRANE = [1.0, 1.594, 2.136, 2.296, 2.653, 2.918, 3.156, 3.501, 3.600, 3.652]  # ideal circular membrane
MEMBRANE_GAIN = [1.0, .55, .42, .30, .25, .20, .16, .12, .10, .08]


def kick(v: float = 1.0, take: int = 0, sag: float = .35, sub_t60: float = .32, knock_hz: float = 87.31,
         sub_on: bool = True) -> np.ndarray:
    """Membranophone knock with a tension sag, plus a tuned sub one octave under it.

    sag: semitones. On impact the head is stretched sharp by `sag x v^1.5` (tension grows with
    amplitude squared, so a soft hit sags less), then relaxes through its rest pitch and settles
    half that amount flat (the damp head buckling) over about 90 ms.
    """
    r = rng("kick", take)
    seconds = 1.0
    s = sag * v ** 1.5 * (1 + .1 * r.uniform(-1, 1))

    def curve(t):
        semis = s * np.exp(-t / .015) - .5 * s * (1 - np.exp(-t / .09))
        return 2 ** (semis / 12)

    freqs = [knock_hz * m for m in MEMBRANE]
    jitter = 10 ** (r.uniform(-1.5, 1.5, len(freqs)) / 20)  # strike position moves a little per take
    gains = np.array(MEMBRANE_GAIN) * jitter * contact(freqs, v, 120, 1400)
    t60s = [.30 / m ** 1.4 for m in MEMBRANE]                 # dry: no box, the overtones die fast
    body = damped_partials(freqs, gains, t60s, seconds, freq_curve=curve)
    body *= attack(len(body), .6 + 1.2 * (1 - v))
    # a fixed scale, not a per-hit peak normalisation: a hard hit's partials line up into a taller peak,
    # so normalising each hit would quietly take energy away from exactly the hits that should be brightest
    body /= .5 * sum(MEMBRANE_GAIN)

    # wood frame of the table: short, quiet, above the boxy region
    wood = damped_partials([640, 1180, 1930], [.5, .35, .2], [.035, .025, .018], seconds)
    body += .18 * v * norm(wood)

    # the beater's knock: a 2 ms seeded click, 1 to 4 kHz
    n = len(body)
    click = np.zeros(n)
    k = int(.002 * RATE)
    click[:k] = r.standard_normal(k) * np.exp(-np.arange(k) / (.0005 * RATE))
    click = filt(butter("bandpass", [1000, 4000]), click)
    body += .3 * v ** 1.3 * norm(click)

    out = body
    if sub_on:
        t = t_axis(seconds)
        f = (knock_hz / 2) * 2 ** ((7 * np.exp(-t / .018)) / 12)   # +7 st falling in a tight exponential
        sub = np.sin(2 * math.pi * np.cumsum(f) / RATE)
        sub *= np.exp(-6.9078 * t / (sub_t60 * (1 + .06 * r.uniform(-1, 1)))) * attack(n, 2.0)
        # a little drive so the sub has a third harmonic a phone can play (the OCTAVER lesson)
        sub = oversampled(sub, lambda u: np.tanh(1.8 * u) / np.tanh(1.8))
        # constant sub level: a hard hit adds knock and overtones on top of the same weight, so it is brighter
        out = out + .8 * 10 ** (r.uniform(-.4, .4) / 20) * sub
    return finish(out)


# ---------------------------------------------------------------- the coil (a tight helical spring)

class Tank:
    def __init__(self, fc: float, ap: float, stages: int, springs, t60_base: float, t60_span: float,
                 send_hp: float = 250.0, tone: float = 3500.0):
        self.fc, self.ap, self.stages, self.springs = fc, ap, stages, springs
        self.k = round(RATE / (2 * fc))
        self.t60_base, self.t60_span, self.send_hp, self.tone = t60_base, t60_span, send_hp, tone


# BREAKER's tank B (the owner's surf spring, BreakerChain.TANK_B): kept as the reference.
TANK_SURF = Tank(3000, .70, 100, [(.037, 0, 1.0), (.043, .004, .8)], 1.0, 1.6)
# The tight coil: short transits (dense clatter), a lower bend (rubbery), short decay (claustrophobic).
TANK_COIL = Tank(2200, .62, 70, [(.019, 0, 1.0), (.024, .003, .8)], .35, .55, tone=3000.0)


def spring(x: np.ndarray, drip: float, frames: int, tank: Tank, send_hp: float | None = None) -> np.ndarray:
    """BreakerChain.spring, vectorised block by block (a block is one transit, so the loop closes exactly).

    Each echo is the last one through a chain of stretched allpasses H(z) = (a + z^-k)/(1 + a z^-k)
    and a 4th-order low-pass at fc, a transit later and g quieter: dispersion grows every bounce.
    The chain is allpass and g < 1, so the loop gain is under 1 at every frequency by construction.
    """
    hp = butter("highpass", send_hp or tank.send_hp, 2)
    xs = np.zeros(frames)
    xs[: min(frames, len(x))] = x[:frames]
    send = filt(hp, xs)
    p = peak(send)
    out = np.zeros(frames)
    if p <= 0:
        return out
    dwell = 1 + 7 * drip
    send = np.tanh(dwell * send / p) / math.tanh(dwell)
    t60 = tank.t60_base + tank.t60_span * drip
    k, a = tank.k, tank.ap
    b_ap = np.zeros(k + 1); b_ap[0] = a; b_ap[k] = 1
    a_ap = np.zeros(k + 1); a_ap[0] = 1; a_ap[k] = a
    lp = signal.butter(4, tank.fc, fs=RATE, output="sos")
    for transit, pre, weight in tank.springs:
        L = round(transit * RATE)
        g = 10 ** (-3 * transit / t60)
        zi = [np.zeros(k) for _ in range(tank.stages)]
        zlp = np.zeros((lp.shape[0], 2))
        stored = np.zeros(L)
        echo = np.zeros(frames)
        for start in range(0, frames, L):
            n = min(L, frames - start)
            e = stored[:n]
            s = send[start:start + n] + g * e
            for st in range(tank.stages):
                s, zi[st] = signal.lfilter(b_ap, a_ap, s, zi=zi[st])
            y, zlp = signal.sosfilt(lp, s, zi=zlp)
            echo[start:start + n] = e
            stored = np.zeros(L)
            stored[:n] = y
        pre_n = round(pre * RATE)
        out[pre_n:] += weight * echo[: frames - pre_n]
    return out


def tank_mix(x: np.ndarray, drip: float, tank: Tank, seconds: float = PAD_CAP_SECONDS) -> np.ndarray:
    """BreakerChain.tankMix: the dry hit plus the return, toned, at 0.9 x drip of the dry peak."""
    frames = int(seconds * RATE)
    xs = np.zeros(frames)
    xs[: min(frames, len(x))] = x[:frames]
    if drip <= 0:
        return xs
    ret = filt(butter("lowpass", tank.tone), spring(xs, drip, frames, tank))
    pr = peak(ret)
    return xs + (.9 * drip * peak(xs) / pr) * ret if pr > 0 else xs


def group_delay_ms(tank: Tank, hz: float) -> float:
    """Group delay of one pass through the allpass chain at hz (analytic)."""
    b = np.zeros(tank.k + 1); b[0] = tank.ap; b[tank.k] = 1
    a = np.zeros(tank.k + 1); a[0] = 1; a[tank.k] = tank.ap
    _, gd = signal.group_delay((b, a), w=[hz], fs=RATE)
    return float(gd[0]) * tank.stages / RATE * 1000


# ---------------------------------------------------------------- cassette edge

def cassette(x: np.ndarray, edge: float = .5) -> np.ndarray:
    """Tape-style asymmetric saturation at 4x, a high-frequency loss, and a 12-bit grain past half."""
    if edge <= 0:
        return x
    p = peak(x)
    drive = 1 + 3 * edge

    def sat(u):
        u = u / p * drive
        return np.where(u >= 0, np.tanh(u), u / np.sqrt(1 + u * u))

    y = oversampled(x, sat)
    y = filt(butter("lowpass", 12_000 - 4_000 * edge, 1), y)
    if edge > .5:
        bits = 12
        q = 2.0 ** (bits - 1)
        y = np.round(norm(y) * q) / q
    return norm(y, p)


# ---------------------------------------------------------------- the snare

PLATE = sorted({round(math.sqrt(m * m + (n * 1.37) ** 2), 4) for m in range(1, 7) for n in range(1, 6)})


def snare(v: float = 1.0, take: int = 0, drip: float = .55, tank: Tank = TANK_COIL, edge: float = .5,
          bench: float = .35) -> np.ndarray:
    """Papery stick crack, a taut head, wires, a metal bench slap, then the coil, then the cassette."""
    r = rng("snare", take)
    seconds = .6
    n = int(seconds * RATE)
    t = t_axis(seconds)

    head_f = [196.0 * m for m in MEMBRANE[:7]]
    head_g = np.array(MEMBRANE_GAIN[:7]) * 10 ** (r.uniform(-1.5, 1.5, 7) / 20) * contact(head_f, v, 500, 4500)
    head = norm(damped_partials(head_f, head_g, [.17 / m ** 1.2 for m in MEMBRANE[:7]], seconds))

    # the crack: a sub-millisecond broadband stick snap plus two papery bands
    burst = r.standard_normal(n)
    snap = burst * np.exp(-t / .0007)
    snap = filt(butter("highpass", 1800 - 900 * (1 - v)), snap)
    paper = burst * np.exp(-t / .004)
    paper = peaking(peaking(filt(butter("bandpass", [1500, 9000]), paper), 2600, 9, 2.5), 5200, 7, 2.5)
    crack = norm(norm(snap) + .7 * norm(paper))

    # wires: the one place noise is allowed (they are wires, not the hats); BOOM_BAP's soft-wire law
    wires = r.standard_normal(n) * np.exp(-t / (.075 * (.55 + .45 * math.sqrt(v))))
    wires = filt(butter("bandpass", [1800, 3000 + 6000 * v ** .8]), wires)

    # the metal workbench: an unpitched plate, short
    bf = [410 * m for m in PLATE[:24]]
    bg = r.uniform(.4, 1, len(bf)) * contact(bf, v, 1500, 8000)
    bench_s = norm(damped_partials(bf, bg, [.12 / (m ** .5) for m in PLATE[:24]], seconds, phases=r.uniform(0, 6.28, len(bf))))

    dry = (.9 * head + 1.0 * v ** 1.2 * crack + .55 * math.sqrt(v) * norm(wires) + bench * v * bench_s)
    dry *= attack(n, .15)
    x = tank_mix(dry, drip, tank) if drip > 0 else dry
    return finish(cassette(x, edge))


# ---------------------------------------------------------------- microtonal wire: hats and metal percussion

BAR = [1.0, 2.756, 5.404, 8.933]  # free-free bar (a struck rod)
HAT_RODS = [3150, 3720, 4410, 5230, 6180, 7340]


def hat_partials(detune_cents: float):
    """The hat's fixed identity: six struck rods (bar overtones), each with a detuned twin, plus a dense
    seeded cluster from 2.5 to 15 kHz. Fixed per voice, so every take is the same metal."""
    r = rng("hat-identity")
    f, g = [], []
    for base in HAT_RODS:
        for j, m in enumerate(BAR[:2]):
            for twin in (0, 1):
                cents = (r.uniform(.5, 1) * detune_cents) * (1 if twin else 0)
                f.append(base * m * 2 ** (cents / 1200))
                g.append((1.0 if j == 0 else .5) * (1 if twin == 0 else .8))
    cluster = np.exp(r.uniform(math.log(2500), math.log(15000), 44))
    for c in cluster:
        f.append(c)
        g.append(r.uniform(.15, .45))
        f.append(c * 2 ** (r.uniform(-1, 1) * detune_cents / 1200))
        g.append(r.uniform(.1, .3))
    return np.array(f), np.array(g)


def hat(v: float = 1.0, take: int = 0, open_: bool = False, detune: float = 9.0) -> np.ndarray:
    r = rng("hat", open_, take)
    f, g = hat_partials(detune)
    g = g * contact(f, v, 2500, 11000, 1.2) * 10 ** (r.uniform(-1, 1, len(f)) / 20)
    base = .55 if open_ else .075
    t60 = base * (4000 / f) ** .45
    seconds = 1.2 if open_ else .35
    x = damped_partials(f, g, t60, seconds, phases=r.uniform(0, 2 * math.pi, len(f)))
    x *= attack(len(x), .25)
    tick = np.zeros(len(x)); tick[:8] = r.standard_normal(8)
    x = norm(x) + .25 * v * norm(filt(butter("highpass", 6000), tick))
    return finish(x)


def rod(v: float = 1.0, take: int = 0) -> np.ndarray:
    """A threaded tension rod: bar overtones, the fundamental doubled 11 cents sharp, so it beats."""
    r = rng("rod", take)
    f0 = 1180.0
    f = [f0 * m for m in BAR] + [f0 * 2 ** (11 / 1200)]
    g = np.array([1, .6, .35, .2, .7]) * contact(f, v, 2500, 12000)
    x = damped_partials(f, g, [.9, .45, .25, .14, .8], 1.2, phases=r.uniform(0, 6.28, 5))
    x *= attack(len(x), .2)
    return finish(x)


def plate(v: float = 1.0, take: int = 0) -> np.ndarray:
    """An unpitched steel plate: 30 rectangular-plate modes, dense and clangy."""
    r = rng("plate-identity")
    f = [215 * m for m in PLATE[:30]]
    g = r.uniform(.3, 1, len(f)) * contact(f, v, 1200, 7000)
    rt = rng("plate", take)
    g = g * 10 ** (rt.uniform(-1.5, 1.5, len(f)) / 20)
    x = damped_partials(f, g, [.8 / (m ** .6) for m in PLATE[:30]], 1.2, phases=rt.uniform(0, 6.28, len(f)))
    x *= attack(len(x), .3)
    return finish(x)


def cup(v: float = 1.0, take: int = 0) -> np.ndarray:
    """A small struck metal cup (a nut, a bell cap): five inharmonic modes, short."""
    r = rng("cup", take)
    f = [1650 * m for m in (1, 1.47, 2.09, 2.56, 3.39)]
    g = np.array([1, .7, .5, .35, .25]) * contact(f, v, 2500, 10000)
    x = damped_partials(f, g, [.28, .2, .14, .1, .07], .6, phases=r.uniform(0, 6.28, 5))
    x *= attack(len(x), .15)
    return finish(x)


def coil_hit(v: float = 1.0, take: int = 0) -> np.ndarray:
    """The coil kicked on its own: a soft thump into a tank with the send opened low."""
    r = rng("coil", take)
    t = t_axis(.05)
    thump = np.sin(2 * math.pi * 140 * t) * np.exp(-t / .008) + .2 * r.standard_normal(len(t)) * np.exp(-t / .002)
    ret = spring(thump * v, .85, int(1.4 * RATE), TANK_COIL, send_hp=120.0)
    ret = filt(butter("lowpass", 3200), ret)
    x = np.zeros(int(1.4 * RATE)); x[: len(thump)] += .15 * norm(thump)
    return finish(x + norm(ret))


# ---------------------------------------------------------------- the glue: pump and dictaphone

def compressor(x: np.ndarray, key: np.ndarray, thr_db: float, ratio: float, att: float, rel: float) -> np.ndarray:
    """A feed-forward gain computer on a peak-relative detector, smoothed in the log domain."""
    p = peak(key) or 1.0
    lvl = 20 * np.log10(np.abs(key) / p + 1e-9)
    over = np.maximum(lvl - thr_db, 0)
    gc = -over * (1 - 1 / ratio)
    aa, ar = math.exp(-1 / (att * RATE)), math.exp(-1 / (rel * RATE))
    g = np.empty_like(gc)
    s = 0.0
    for i, c in enumerate(gc):  # one pass, scalar; fine at this length
        s = aa * s + (1 - aa) * c if c < s else ar * s + (1 - ar) * c
        g[i] = s
    return x * 10 ** (g / 20)


def dictaphone(x: np.ndarray, amount: float = .45, sub_clear: bool = True, key: np.ndarray | None = None) -> np.ndarray:
    """The retro dictaphone (shape): an AGC that pumps, a small resonant speaker band, and a ROM reader's
    zero-order hold and bit loss. With sub_clear the lows under 100 Hz bypass the grit (not the pump)."""
    key = x if key is None else key
    if sub_clear:
        low = filt(butter("lowpass", 100, 4), x)
        high = filt(butter("highpass", 100, 4), x)
    else:
        low, high = np.zeros_like(x), x
    pumped = compressor(high, key, -16 - 8 * amount, 6 + 6 * amount, .003, .18)
    low = compressor(low, key, -10, 2.0, .003, .18)
    y = peaking(pumped, 2700, 3 + 3 * amount, 1.2)
    y = filt(butter("lowpass", 7200 - 3200 * amount), y)
    # ROM reader: hold every Nth sample (aliasing gives the metallic footprint), drop bits
    hold = int(round(2 + 2 * amount))  # 22 kHz -> 11 kHz effective rate
    idx = (np.arange(len(y)) // hold) * hold
    rom = y[idx]
    bits = 12 - 4 * amount
    q = 2.0 ** (bits - 1)
    pr = peak(rom) or 1.0
    rom = np.round(rom / pr * q) / q * pr
    rom = filt(butter("lowpass", 9000, 1), rom)
    y = (1 - .6 * amount) * y + .6 * amount * rom
    return y + low


# ---------------------------------------------------------------- pads and grooves

def pad_bus(x: np.ndarray, send: float, dict_amt: float, send_hp: float = 250.0) -> np.ndarray:
    """What a pad carries when its own glue is printed on it: its own coil send, then the dictaphone."""
    frames = len(x)
    if send > 0:
        ret = filt(butter("lowpass", TANK_COIL.tone), spring(x, .5, frames, TANK_COIL, send_hp=send_hp))
        pr = peak(ret)
        if pr > 0:
            x = x + send * peak(x) / pr * ret
    return finish(dictaphone(x, dict_amt))


SENDS = {"kick": .45, "snare": .25, "hat": .05, "ohat": .08, "rod": .3, "plate": .25, "cup": .2}

BPM = 84


def groove_events(swing: bool):
    """Four bars. Hats on eighths, dragged behind the beat; the snare a hair late; ghosts and metal."""
    step = 60 / BPM / 4
    r = rng("groove-human")
    ev = []
    kicks = [[0, 6, 10], [0, 3, 10, 11], [0, 6, 10], [0, 7, 9, 14]]
    for bar in range(4):
        b0 = bar * 16
        for s in kicks[bar]:
            ev.append((b0 + s, "kick", 1.0 if s == 0 else .85))
        for s in (4, 12):
            ev.append((b0 + s, "snare", 1.0))
        for s in range(0, 16, 2):
            if bar in (1, 3) and s == 14:
                ev.append((b0 + s, "ohat", .8))
            else:
                ev.append((b0 + s, "hat", .85 if s % 4 == 0 else .55))
        if bar in (1, 2):
            ev.append((b0 + 15 if bar == 1 else b0 + 7, "snare", .3))
    ev += [(11, "rod", .7), (16 + 13, "cup", .6), (32 + 13, "cup", .5), (48 + 3, "plate", .65), (48 + 11, "rod", .55)]
    out = []
    counts: dict[str, int] = {}
    for st, name, vel in sorted(ev):
        t = st * step
        if swing:
            if st % 2 == 1:
                t += .16 * step * 2  # 58% sixteenth swing
            if name in ("hat", "ohat"):
                t += .014 + r.uniform(-.003, .003)  # hats dragged ~14 ms behind
            elif name == "snare":
                t += .006 + r.uniform(-.002, .002)
            else:
                t += r.uniform(-.002, .002)
        take = counts.get(name, 0) % 3
        counts[name] = counts.get(name, 0) + 1
        out.append((t, name, vel, take))
    return out


def render_hit(name: str, vel: float, take: int, cfg) -> np.ndarray:
    if name == "kick":
        return kick(vel, take, sag=cfg["sag"], sub_t60=cfg["sub_t60"])
    if name == "snare":
        return snare(vel, take)
    if name == "hat":
        return hat(vel, take)
    if name == "ohat":
        return hat(vel, take, open_=True)
    return {"rod": rod, "plate": plate, "cup": cup}[name](vel, take)


def groove(mode: str, cfg) -> np.ndarray:
    """mode: dry | coupled | uncoupled. Coupled: one shared coil and one dictaphone the kick pumps.
    Uncoupled: every hit printed through its own glue (what a sampler playing pads gives)."""
    bars = 4
    total = int((bars * 4 * 60 / BPM + 1.5) * RATE)
    evs = groove_events(cfg["swing"])
    mix = np.zeros(total)
    send = np.zeros(total)
    kick_bus = np.zeros(total)
    gains = {"kick": 1.0, "snare": .8, "hat": .28, "ohat": .3, "rod": .3, "plate": .3, "cup": .3}
    cache: dict = {}
    last_open_end = None
    openbuf = None
    for t, name, vel, take in evs:
        key = (name, vel, take)
        if key not in cache:
            x = render_hit(name, vel, take, cfg)
            if mode == "uncoupled":
                x = pad_bus(x, SENDS[name], cfg["dict"], 90.0 if name == "kick" else 250.0)
            cache[key] = x * vel
        x = cache[key].copy()
        i = int((t + .05) * RATE)  # 50 ms lead-in, so the humanised first hit never starts before 0
        if name == "hat" and last_open_end is not None and i < last_open_end[1]:
            j0 = last_open_end[0]
            f = int(.012 * RATE)
            cut = i - j0
            seg = mix[i:i + f]
            seg -= openbuf[cut:cut + f][: len(seg)] * gains["ohat"] * np.linspace(0, 1, len(seg))
            mix[i + f:last_open_end[1]] -= openbuf[cut + f: last_open_end[1] - j0] * gains["ohat"]
            last_open_end = None
        n = min(len(x), total - i)
        mix[i:i + n] += gains[name] * x[:n]
        if name == "kick":
            kick_bus[i:i + n] += gains[name] * x[:n]
        else:
            send[i:i + n] += gains[name] * SENDS[name] * x[:n]
        if name == "ohat":
            openbuf = x
            last_open_end = (i, i + n)
    if mode == "coupled":
        # the kick excites the coil with its send opened to 90 Hz: the kit's tail breathes with it
        kick_send = filt(butter("highpass", 90.0), kick_bus) * SENDS["kick"]
        ret = spring(send + kick_send, .5, total, TANK_COIL, send_hp=60.0)
        ret = filt(butter("lowpass", TANK_COIL.tone), ret)
        mix = mix + .35 * peak(mix) / (peak(ret) or 1) * ret
        mix = dictaphone(mix, cfg["dict"], cfg["sub_clear"], key=kick_bus + .3 * mix)
    mix = mix - np.mean(mix)
    return norm(mix, .89)


# ---------------------------------------------------------------- measurement

def centroid(x: np.ndarray) -> float:
    s = np.abs(np.fft.rfft(x * np.hanning(len(x))))
    f = np.fft.rfftfreq(len(x), 1 / RATE)
    return float(np.sum(f * s) / (np.sum(s) + 1e-12))


def onset_centroid(x: np.ndarray, ms: float = 60) -> float:
    return centroid(x[: int(ms / 1000 * RATE)])


def pitch_track(x: np.ndarray, lo: float, hi: float, centre_s: float, win_s: float = .04) -> float:
    """The strongest frequency in [lo, hi] in a window, refined by a long zero-padded FFT."""
    c = int(centre_s * RATE)
    w = int(win_s * RATE)
    seg = x[max(0, c - w // 2): c + w // 2]
    seg = seg * np.hanning(len(seg))
    nfft = 1 << 20
    s = np.abs(np.fft.rfft(seg, nfft))
    f = np.fft.rfftfreq(nfft, 1 / RATE)
    m = (f >= lo) & (f <= hi)
    return float(f[m][np.argmax(s[m])])


def band_db(x: np.ndarray, lo: float, hi: float) -> float:
    s = np.abs(np.fft.rfft(x)) ** 2
    f = np.fft.rfftfreq(len(x), 1 / RATE)
    m = (f >= lo) & (f < hi)
    return 10 * math.log10(np.sum(s[m]) / (np.sum(s) + 1e-20) + 1e-20)


def diff_db(a: np.ndarray, b: np.ndarray) -> float:
    n = min(len(a), len(b))
    a, b = norm(a[:n]), norm(b[:n])
    return 10 * math.log10(np.sum((a - b) ** 2) / (np.sum(a ** 2) + 1e-20) + 1e-20)


def env_db(x: np.ndarray, block_ms: float = 10) -> np.ndarray:
    b = int(block_ms / 1000 * RATE)
    n = len(x) // b
    r = np.sqrt(np.mean(x[: n * b].reshape(n, b) ** 2, axis=1))
    return 20 * np.log10(r / (np.max(r) + 1e-20) + 1e-9)


# ---------------------------------------------------------------- output

def write_wav(path: str, x: np.ndarray) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    pcm = np.clip(np.round(x * 32767), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())


def main(out: str) -> int:
    os.makedirs(out, exist_ok=True)
    clips = []
    metrics = []
    t_render = {}

    def emit(section: str, cid: str, label: str, x: np.ndarray, note: str = ""):
        path = os.path.join(out, section, cid + ".wav")
        write_wav(path, x)
        clips.append({"section": section, "id": cid, "label": label, "file": os.path.relpath(path, out), "note": note,
                      "seconds": round(len(x) / RATE, 3)})
        assert np.all(np.isfinite(x))
        metrics.append({"clip": f"{section}/{cid}", "seconds": round(len(x) / RATE, 3), "peak": round(peak(x), 4),
                        "dc": round(float(np.mean(x)), 6), "onset_centroid_hz": round(onset_centroid(x)),
                        "below_100hz_db": round(band_db(x, 20, 100), 1), "above_6khz_db": round(band_db(x, 6000, 20000), 1)})

    def timed(name, fn):
        t0 = time.perf_counter()
        x = fn()
        t_render[name] = round((time.perf_counter() - t0) * 1000)
        return x

    # Q1 the kick
    k_sags = {"k0": 0.0, "k1": .35, "k2": 1.0}
    kicks = {}
    for cid, s in k_sags.items():
        kicks[cid] = timed(f"kick sag {s}", lambda s=s: kick(1.0, 0, sag=s))
        emit("kick", cid, f"Sag {s} semitone", kicks[cid])
    emit("kick", "k1_long", "Sag 0.35, longer sub (t60 0.6 s)", kick(1.0, 0, sag=.35, sub_t60=.6))
    emit("kick", "k1_nosub", "Sag 0.35, knock only (no sub)", kick(1.0, 0, sag=.35, sub_on=False))

    # Q2 the snare
    snares = {
        "s_coil": ("Coil (tight spring), cassette 0.5", dict()),
        "s_dry": ("Crack only, no spring", dict(drip=0)),
        "s_surf": ("BREAKER's surf tank B instead of the coil", dict(tank=TANK_SURF)),
        "s_bench": ("More metal bench in the crack", dict(bench=.9)),
        "s_clean": ("Coil, no cassette edge", dict(edge=0)),
        "s_hot": ("Coil, cassette 1.0 (12-bit)", dict(edge=1.0)),
    }
    for cid, (label, kw) in snares.items():
        x = timed(f"snare {cid}", lambda kw=kw: snare(1.0, 0, **kw))
        emit("snare", cid, label, x)

    # Q3 the hats
    emit("hats", "h_closed", "Closed, detune 9 cents", timed("hat closed", lambda: hat(1.0, 0)))
    emit("hats", "h_open", "Open, detune 9 cents", timed("hat open", lambda: hat(1.0, 0, open_=True)))
    emit("hats", "h_closed_wide", "Closed, detune 25 cents (more eerie)", hat(1.0, 0, detune=25))
    emit("hats", "h_open_wide", "Open, detune 25 cents", hat(1.0, 0, open_=True, detune=25))
    emit("hats", "h_closed_pure", "Closed, no detune (control)", hat(1.0, 0, detune=0))

    # Q4 metal percussion
    emit("perc", "rod", "Tension rod", timed("rod", rod))
    emit("perc", "plate", "Steel plate", timed("plate", plate))
    emit("perc", "cup", "Small metal cup", timed("cup", cup))
    emit("perc", "coil", "The coil kicked alone", timed("coil", coil_hit))

    # Q5 touch: velocity x take strips (each strike peak-levelled, so you hear character, not volume)
    def strip(fn, gap=.12):
        parts = []
        for v in (.3, .6, 1.0):
            for tk in range(3):
                x = norm(fn(v, tk), .89)
                parts += [x, np.zeros(int(gap * RATE))]
            parts.append(np.zeros(int(.35 * RATE)))
        return np.concatenate(parts)

    touch_src = {"kick": lambda v, tk: kick(v, tk)[: int(.55 * RATE)],
                 "snare": lambda v, tk: snare(v, tk)[: int(.7 * RATE)],
                 "hat": lambda v, tk: hat(v, tk)}
    for name, fn in touch_src.items():
        emit("touch", name, f"{name}: soft, mid, hard, three takes each", strip(fn))

    # Q6 the glue
    base = {"sag": .35, "sub_t60": .32, "dict": .45, "sub_clear": True, "swing": True}
    grooves = {
        "g_full": ("Coupled bus, dictaphone 0.45, sub kept clear, dragged", {}, "coupled"),
        "g_uncoupled": ("Each pad printed with its own glue (what pads give)", {}, "uncoupled"),
        "g_dry": ("Dry pads, no glue, dragged", {}, "dry"),
        "g_straight": ("Coupled, not dragged (control)", {"swing": False}, "coupled"),
        "g_heavy": ("Coupled, dictaphone 0.9", {"dict": .9}, "coupled"),
        "g_subgrit": ("Coupled, sub through the grit too", {"sub_clear": False}, "coupled"),
    }
    glued = {}
    for cid, (label, over, mode) in grooves.items():
        cfg = dict(base, **over)
        glued[cid] = timed(f"groove {cid}", lambda cfg=cfg, mode=mode: groove(mode, cfg))
        emit("glue", cid, label, glued[cid])

    # pads as they land (each through its own glue)
    landing = {"kick": kicks["k1"], "snare": snare(), "hat": hat(), "ohat": hat(open_=True), "rod": rod(), "cup": cup()}
    for name, x in landing.items():
        emit("landed", name, name, pad_bus(x, SENDS[name], .45, 90.0 if name == "kick" else 250.0))

    # ---- measurements
    m = {}
    def sag_cents(x):
        """Instantaneous frequency of the knock's fundamental (band-passed, Hilbert phase), 4-12 ms vs 150-250 ms."""
        f = signal.sosfiltfilt(butter("bandpass", [70, 106], 2), x)
        ph = np.unwrap(np.angle(signal.hilbert(f)))
        inst = np.diff(ph) * RATE / (2 * math.pi)
        a = np.median(inst[int(.004 * RATE):int(.012 * RATE)])
        b = np.median(inst[int(.15 * RATE):int(.25 * RATE)])
        mid = np.median(inst[int(.03 * RATE):int(.05 * RATE)])
        return round(1200 * math.log2(a / 87.31), 1), round(1200 * math.log2(mid / 87.31), 1), round(1200 * math.log2(b / 87.31), 1)

    ref = sag_cents(kick(1.0, 0, sag=0.0, sub_on=False))
    for cid in ("k1", "k2"):
        got = sag_cents(kick(1.0, 0, sag=k_sags[cid], sub_on=False))
        m[f"kick {cid} knock fundamental vs the no-sag kick at 4-12 / 30-50 / 150-250 ms (cents)"] = [round(a - b, 1) for a, b in zip(got, ref)]
    got = sag_cents(kick(.3, 0, sag=.35, sub_on=False))
    ref3 = sag_cents(kick(.3, 0, sag=0.0, sub_on=False))
    m["kick k1 at 30% velocity vs no sag, same windows (cents)"] = [round(a - b, 1) for a, b in zip(got, ref3)]
    m["kick, first 80 ms at 0.3 / 0.6 / 1.0: 120-400 Hz share (dB, upper membrane modes)"] = [
        round(band_db(kick(v, 0)[: int(.08 * RATE)], 120, 400), 1) for v in (.3, .6, 1.0)]
    m["kick, first 80 ms at 0.3 / 0.6 / 1.0: 1.5-6 kHz share (dB, the beater's knock)"] = [
        round(band_db(kick(v, 0)[: int(.08 * RATE)], 1500, 6000), 1) for v in (.3, .6, 1.0)]
    for name, tank in (("coil", TANK_COIL), ("surf", TANK_SURF)):
        m[f"{name} tank one-pass group delay at 200 Hz / 1 kHz / 2 kHz (ms)"] = [round(group_delay_ms(tank, f), 2) for f in (200, 1000, 2000)]
    imp = np.zeros(int(1.4 * RATE)); imp[0] = 1
    e = env_db(spring(imp, .55, len(imp), TANK_COIL))
    e100 = e[::10]
    m["coil energy per 100 ms after its peak, dB (DRIP .55)"] = [round(float(v), 1) for v in e100[np.argmax(e100):][:8]]
    m["coil energy monotonically falling past 100 ms (100 ms blocks)"] = bool(np.all(np.diff(e100[np.argmax(e100) + 1:][:10]) < 0))
    for name, fn in touch_src.items():
        cs = [round(onset_centroid(fn(v, 0))) for v in (.3, .6, 1.0)]
        m[f"{name} onset centroid at 0.3 / 0.6 / 1.0 (Hz, 60 ms)"] = cs
        m[f"{name} take 0 vs 1 vs 2 at full: difference energy (dB re signal)"] = [
            round(diff_db(fn(1.0, 0), fn(1.0, 1)), 1), round(diff_db(fn(1.0, 1), fn(1.0, 2)), 1)]
    for cid in ("g_full", "g_uncoupled", "g_dry"):
        x = glued[cid]
        m[f"{cid} energy under 100 Hz (dB re total)"] = round(band_db(x, 20, 100), 1)
        m[f"{cid} energy over 6 kHz (dB re total)"] = round(band_db(x, 6000, 20000), 1)
    m["g_subgrit energy under 100 Hz (dB re total)"] = round(band_db(glued["g_subgrit"], 20, 100), 1)
    # pump: hat-band level in the 60 ms after each downbeat kick vs before it, coupled vs uncoupled
    def pump(x):
        hb = filt(butter("bandpass", [5000, 12000]), x)
        step = 60 / BPM / 4
        d = []
        for bar in range(4):
            i = int((bar * 16 * step + .05) * RATE)
            pre = hb[max(0, i - int(.06 * RATE)): i]
            post = hb[i + int(.02 * RATE): i + int(.08 * RATE)]
            if len(pre) and len(post):
                d.append(20 * math.log10((np.sqrt(np.mean(post ** 2)) + 1e-12) / (np.sqrt(np.mean(pre ** 2)) + 1e-12)))
        return round(float(np.mean(d)), 1)
    m["hat-band level just after a downbeat kick vs just before, coupled (dB)"] = pump(glued["g_full"])
    m["hat-band level just after a downbeat kick vs just before, uncoupled (dB)"] = pump(glued["g_uncoupled"])
    m["render time per clip, Python (ms)"] = t_render

    manifest = {"engine": "SOOT (working name)", "round": 0, "harness": "Python, not Kotlin", "bpm": BPM,
                "clips": clips, "measurements": m}
    with open(os.path.join(out, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=1)
    with open(os.path.join(out, "metrics.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(metrics[0]))
        w.writeheader()
        w.writerows(metrics)
    print(json.dumps(m, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "testkit/soot-r0"))
