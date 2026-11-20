"""STAGGER round 0: four numbers measured before any kit is authored.

Python harness, not Kotlin. Nothing here has been heard; every constant marked "shape" is a
starting value for a listening pass. numpy only (this sandbox has no scipy), so the filters,
the resampler and the probes are written out below rather than imported from snipkit.

The brief (docs/superpowers/specs/2026-11-18-stagger-drum-kits-brief.md) lands as a kit family
in the CYPHER shape. Four risks a number can settle first:

1. THE MUFFLED KICK. The identity kick is low-passed until it has no click. A phone speaker
   plays almost nothing under 150 Hz (the SUB pedal lesson, docs/CYPHER.md: a sub needs
   harmonics in 150 to 400 Hz or it is inaudible on a phone). So: at which cutoff does a
   two-pole low-pass leave the kick with less than 1 percent of its energy in 150 to 400 Hz,
   and does a tanh *before* the filter put that energy back? The answer sets the MUFFLE law
   (cutoff and drive) on the kick pad's print.

2. THE DRUNK CLOCK. CYPHER has `behind` (one part a fixed number of ms late), `humanised`
   (every note its own seeded offset, independent) and `swung`. The brief asks for a kick
   that drags, a snare that lands a hair early, and a lag that breathes: it grows, then the
   player catches up. That is a shared, mean-reverting drift (an AR(1) walk on the eighth
   grid) plus a per-part bias, clamped to the Note's +-60 ms. Measured here: the mean and
   spread per part, the kick-behind-snare gap, the lag-one autocorrelation (an independent
   scatter measures near 0; a drift measures its coefficient), the worst offset, and that the
   same seed gives the same offsets.

3. THE PITCHED-DOWN FOLEY. The brief's sound effects are "pitched down, grainy and metallic,
   exposing the lower sample rate of older hardware". The rack runs PITCH (Speed) first, then
   CRUNCH, so the converter grain always sits at the converter's rate. A sampler pitches the
   *converted* sample down, and the hold images come down with it into the band a phone
   plays. Measured: the energy share from 6 to 16 kHz for both orders on a timpani and a zap,
   at -7 semitones through a 12-bit, 26.04 kHz hold (Eras.sp1200's numbers).

4. A GLASS SHATTER SOURCE. No engine makes one (grep: "shatter" appears only in two preset
   names). Shape here: 28 tiny modal shards, the first three inside 2 ms, the rest scattered
   over 120 ms with a density that thins, each shard three modes between 2 and 9 kHz with a
   20 to 220 ms t60. Measured: finite, length, centroid, and that two takes are two different
   shatters (peak normalised cross-correlation under 0.5).

Run:  python3 prototype/stagger/stagger_proto.py OUT_DIR
Writes WAVs to OUT_DIR and report.json beside this script.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import struct
import sys
import wave

import numpy as np

RATE = 44_100
OVERSAMPLE = 4
INTERNAL = RATE * OVERSAMPLE


# ---------------------------------------------------------------- determinism (Dsp.seedFor, Dsp.Noise stand-ins)

def seed_for(*parts) -> int:
    h = hashlib.blake2b(digest_size=8)
    for p in parts:
        h.update(repr(p).encode("utf-8"))
        h.update(b"\x1f")
    return int.from_bytes(h.digest(), "big") & 0x7FFF_FFFF_FFFF_FFFF


class Noise:
    """A 64-bit LCG: the same seed gives the same draws, bit for bit."""

    def __init__(self, seed: int):
        self.state = (seed * 6364136223846793005 + 1442695040888963407) & 0xFFFF_FFFF_FFFF_FFFF

    def next(self) -> float:
        self.state = (self.state * 6364136223846793005 + 1442695040888963407) & 0xFFFF_FFFF_FFFF_FFFF
        return ((self.state >> 11) / float(1 << 53)) * 2.0 - 1.0

    def array(self, n: int) -> np.ndarray:
        return np.array([self.next() for _ in range(n)])


# ---------------------------------------------------------------- filters and the output stage (numpy only)

def one_pole_lp(x: np.ndarray, hz: float, rate: int) -> np.ndarray:
    a = math.exp(-2 * math.pi * hz / rate)
    y = np.empty_like(x)
    s = 0.0
    for i in range(len(x)):
        s = (1 - a) * x[i] + a * s
        y[i] = s
    return y


def biquad_lp(x: np.ndarray, hz: float, rate: int, q: float = 0.707) -> np.ndarray:
    """RBJ low-pass, direct form I, like Dsp.Biquad's shape (two poles, 12 dB per octave)."""
    w0 = 2 * math.pi * hz / rate
    alpha = math.sin(w0) / (2 * q)
    b0 = (1 - math.cos(w0)) / 2
    b1 = 1 - math.cos(w0)
    b2 = b0
    a0 = 1 + alpha
    a1 = -2 * math.cos(w0)
    a2 = 1 - alpha
    b0, b1, b2, a1, a2 = b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0
    y = np.empty_like(x)
    x1 = x2 = y1 = y2 = 0.0
    for i in range(len(x)):
        v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, x[i], y1, v
        y[i] = v
    return y


def biquad_bp(x: np.ndarray, hz: float, rate: int, q: float) -> np.ndarray:
    w0 = 2 * math.pi * hz / rate
    alpha = math.sin(w0) / (2 * q)
    b0, b1, b2 = alpha, 0.0, -alpha
    a0, a1, a2 = 1 + alpha, -2 * math.cos(w0), 1 - alpha
    b0, b1, b2, a1, a2 = b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0
    y = np.empty_like(x)
    x1 = x2 = y1 = y2 = 0.0
    for i in range(len(x)):
        v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, x[i], y1, v
        y[i] = v
    return y


def windowed_sinc_lp(x: np.ndarray, cutoff_hz: float, rate: int, taps: int = 255) -> np.ndarray:
    n = np.arange(taps) - (taps - 1) / 2
    fc = cutoff_hz / rate
    h = 2 * fc * np.sinc(2 * fc * n) * np.hanning(taps)
    h /= h.sum()
    return np.convolve(x, h, mode="same")


def finish(raw_internal: np.ndarray) -> np.ndarray:
    """The house output stage's shape: band-limit, decimate 4:1, DC block at 20 Hz, peak to 0.92, 4 ms fade."""
    y = windowed_sinc_lp(raw_internal, 20_000.0, INTERNAL)[::OVERSAMPLE]
    y = y - one_pole_lp(y, 20.0, RATE)
    peak = float(np.max(np.abs(y))) or 1.0
    y = y * (0.92 / peak)
    f = int(0.004 * RATE)
    y[-f:] *= np.linspace(1, 0, f)
    return y


def modal_ring(excitation: np.ndarray, hz: float, modes, rate: int) -> np.ndarray:
    """Modes.ring's shape: one two-pole resonator per (ratio, gain, t60); peak-normalise after."""
    out = np.zeros_like(excitation)
    for ratio, gain, t60 in modes:
        f = hz * ratio
        if f >= rate / 2:
            continue
        r = math.exp(-6.9078 / (t60 * rate))
        theta = 2 * math.pi * f / rate
        a1, a2 = -2 * r * math.cos(theta), r * r
        y1 = y2 = 0.0
        y = np.empty_like(excitation)
        for i in range(len(excitation)):
            v = gain * excitation[i] - a1 * y1 - a2 * y2
            y2, y1 = y1, v
            y[i] = v
        out += y
    peak = float(np.max(np.abs(out))) or 1.0
    return out / peak


def hann_pulse(width_ms: float, rate: int, n: int, at: int = 0, amplitude: float = 1.0) -> np.ndarray:
    w = max(2, int(width_ms * 1e-3 * rate))
    buf = np.zeros(n)
    end = min(n, at + w)
    buf[at:end] += amplitude * 0.5 * (1 - np.cos(2 * np.pi * np.arange(end - at) / w))
    return buf


def write_wav(path: str, x: np.ndarray, rate: int = RATE) -> None:
    pcm = np.clip(x, -1, 1)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(struct.pack("<%dh" % len(pcm), *(np.round(pcm * 32767).astype(np.int16))))


# ---------------------------------------------------------------- probes (numpy only)

def band_share(x: np.ndarray, rate: int, lo: float, hi: float) -> float:
    """The fraction of the whole render's energy between lo and hi Hz."""
    spec = np.abs(np.fft.rfft(x)) ** 2
    f = np.fft.rfftfreq(len(x), 1 / rate)
    total = spec.sum() or 1.0
    return float(spec[(f >= lo) & (f < hi)].sum() / total)


def centroid(x: np.ndarray, rate: int) -> float:
    spec = np.abs(np.fft.rfft(x))
    f = np.fft.rfftfreq(len(x), 1 / rate)
    return float((spec * f).sum() / (spec.sum() or 1.0))


def onset_centroid(x: np.ndarray, rate: int, ms: float = 30.0) -> float:
    """Magnitude-weighted centroid of the first ms (the StrikeVelocityTest lesson: power weighting hides a kick's overtones)."""
    return centroid(x[: int(ms * 1e-3 * rate)], rate)


def envelope(x: np.ndarray, rate: int, block_ms: float = 2.0) -> np.ndarray:
    n = max(1, int(block_ms * 1e-3 * rate))
    m = len(x) // n
    return np.sqrt((x[: m * n].reshape(m, n) ** 2).mean(axis=1))


def attack_ms(x: np.ndarray, rate: int) -> float:
    """Time from the first frame to 90 percent of the peak of a 2 ms RMS envelope."""
    env = envelope(x, rate)
    i = int(np.argmax(env >= 0.9 * env.max()))
    return i * 2.0


def crest_db(x: np.ndarray) -> float:
    rms = math.sqrt(float(np.mean(x ** 2))) or 1e-9
    return 20 * math.log10(float(np.max(np.abs(x))) / rms)


def similarity(a: np.ndarray, b: np.ndarray) -> float:
    """Peak of the normalised cross-correlation within +-5 ms: 1 is a copy, under 0.5 is another hit."""
    n = min(len(a), len(b))
    a, b = a[:n], b[:n]
    a = a / (np.linalg.norm(a) or 1.0)
    b = b / (np.linalg.norm(b) or 1.0)
    best = 0.0
    lag = int(0.005 * RATE)
    for k in range(-lag, lag + 1, 8):
        if k >= 0:
            c = float(np.dot(a[k:], b[: n - k]))
        else:
            c = float(np.dot(a[: n + k], b[-k:]))
        best = max(best, abs(c))
    return best


# ---------------------------------------------------------------- 1. the muffled kick

def kick_raw(seconds: float = 0.9, root_hz: float = 52.0, start_hz: float = 118.0, bend_ms: float = 45.0,
             t60: float = 0.42, click: float = 1.0, seed: int = 1) -> np.ndarray:
    """THUMP KICK's shape: a sine falling onto its root, an exponential decay, a short click. At the internal rate."""
    n = int(seconds * INTERNAL)
    t = np.arange(n) / INTERNAL
    hz = root_hz + (start_hz - root_hz) * np.exp(-t / (bend_ms * 1e-3 / 3))
    phase = 2 * np.pi * np.cumsum(hz) / INTERNAL
    env = np.exp(-6.9078 * t / t60)
    body = np.sin(phase) * env
    if click > 0:
        noise = Noise(seed).array(int(0.004 * INTERNAL)) * np.hanning(int(0.004 * INTERNAL))
        body[: len(noise)] += 0.5 * click * noise
        body += 0.6 * click * hann_pulse(1.5, INTERNAL, n)
    return body


def snare_raw(seconds: float = 0.5, body_hz: float = 196.0, seed: int = 2) -> np.ndarray:
    """A sharp dry snare's shape: a short wire burst band-passed high, over two body modes."""
    n = int(seconds * INTERNAL)
    strike = hann_pulse(0.4, INTERNAL, n)
    body = modal_ring(strike, body_hz, [(1.0, 1.0, 0.16), (1.63, 0.6, 0.11)], INTERNAL)
    wires = Noise(seed).array(int(0.18 * INTERNAL)) * np.exp(-np.arange(int(0.18 * INTERNAL)) / (0.045 * INTERNAL))
    w = np.zeros(n)
    w[: len(wires)] = wires
    w = biquad_bp(w, 3_800.0, INTERNAL, 0.5)
    w /= float(np.max(np.abs(w))) or 1.0
    return 0.55 * body + 0.9 * w


def muffle(x: np.ndarray, cutoff_hz: float | None, drive: float, rate: int) -> np.ndarray:
    """The proposed MUFFLE print: a tanh before a two-pole low-pass. The tanh's harmonics are what the filter keeps."""
    y = x
    if drive > 1.0:
        y = np.tanh(drive * y) / math.tanh(drive)
    if cutoff_hz is not None:
        y = biquad_lp(y, cutoff_hz, rate, q=0.707)
    return y


def probe_kick(out: str) -> dict:
    kick = kick_raw()
    snare = finish(snare_raw())
    write_wav(os.path.join(out, "snare-sharp-dry.wav"), snare)
    rows = []
    for cutoff in [None, 600.0, 350.0, 250.0, 160.0]:
        for drive in [1.0, 3.0]:
            y = finish(muffle(kick, cutoff, drive, INTERNAL))
            name = "kick-%s-drive%.0f" % ("open" if cutoff is None else "lp%d" % int(cutoff), drive)
            write_wav(os.path.join(out, name + ".wav"), y)
            rows.append({
                "cutoff_hz": cutoff, "drive": drive,
                "share_150_400": round(band_share(y, RATE, 150, 400), 4),
                "share_above_1k": round(band_share(y, RATE, 1_000, 22_050), 5),
                "onset_centroid_hz": round(onset_centroid(y, RATE)),
                "centroid_hz": round(centroid(y, RATE)),
                "attack_ms": attack_ms(y, RATE),
                "crest_db": round(crest_db(y), 1),
            })
    return {
        "snare": {"centroid_hz": round(centroid(snare, RATE)), "onset_centroid_hz": round(onset_centroid(snare, RATE)),
                  "share_above_1k": round(band_share(snare, RATE, 1_000, 22_050), 3)},
        "kick": rows,
    }


# ---------------------------------------------------------------- 2. the drunk clock

def staggered(notes, bias_ms: dict, wobble_ms: float, rho: float, seed: str, clamp_ms: float = 60.0):
    """One drummer's clock: a mean-reverting drift shared by every part, sampled on the eighth grid, plus a
    per-part bias. notes: (beat, part, weight). Returns (beat, part, weight, offset_ms). Deterministic in seed."""
    beats = sorted({n[0] for n in notes})
    noise = Noise(seed_for("STAGGER", "clock", seed))
    sigma = wobble_ms * math.sqrt(1 - rho * rho)  # so the walk's stationary spread is wobble_ms
    drift = {}
    d = 0.0
    last = None
    for b in beats:
        steps = 1 if last is None else max(1, int(round((b - last) * 2)))  # the walk ticks per eighth
        for _ in range(steps):
            d = rho * d + sigma * noise.next() * math.sqrt(3)  # uniform noise has variance 1/3
        drift[b] = d
        last = b
    return [(b, p, w, max(-clamp_ms, min(clamp_ms, bias_ms.get(p, 0.0) + drift[b]))) for (b, p, w) in notes]


def humanised(notes, spread_ms: float, seed: str):
    """CYPHER's law: every note its own independent draw."""
    out = []
    for i, (b, p, w) in enumerate(notes):
        draw = Noise(seed_for("CYPHER", "feel", seed, i)).next()
        out.append((b, p, w, max(-60.0, min(60.0, draw * spread_ms))))
    return out


KICK, SNARE, HAT = 0, 1, 2


def pattern(bars: int = 2):
    """A boom-bap bar: kick on 1, the and of 2, and 3's and; snare on 2 and 4; hats on eighths."""
    notes = []
    for bar in range(bars):
        b0 = bar * 4
        notes += [(b0 + 0.0, KICK, 1.0), (b0 + 1.5, KICK, 0.8), (b0 + 2.75, KICK, 0.7)]
        notes += [(b0 + 1.0, SNARE, 1.0), (b0 + 3.0, SNARE, 1.0)]
        notes += [(b0 + k * 0.5, HAT, 0.5 if k % 2 else 0.7) for k in range(8)]
    return sorted(notes)


def lag1_autocorr(xs):
    xs = np.asarray(xs)
    if len(xs) < 3:
        return 0.0
    xs = xs - xs.mean()
    v = float(np.dot(xs, xs)) or 1e-9
    return float(np.dot(xs[:-1], xs[1:]) / v)


def clock_stats(played):
    by_part = {}
    for b, p, w, o in played:
        by_part.setdefault(p, []).append(o)
    hats = sorted((b, o) for b, p, w, o in played if p == HAT)
    kick_at = {b: o for b, p, w, o in played if p == KICK}
    snare_at = {b: o for b, p, w, o in played if p == SNARE}
    # the kick-behind-snare gap: how far the kicks land, on average, after the snares
    gaps = [float(np.mean(list(kick_at.values())) - np.mean(list(snare_at.values())))] if kick_at and snare_at else []
    return {
        "per_part_mean_ms": {k: round(float(np.mean(v)), 1) for k, v in by_part.items()},
        "per_part_std_ms": {k: round(float(np.std(v)), 1) for k, v in by_part.items()},
        "worst_abs_ms": round(max(abs(o) for _, _, _, o in played), 1),
        "hat_lag1_autocorr": round(lag1_autocorr([o for _, o in hats]), 2),
        "kick_minus_snare_mean_ms": round(float(np.mean(gaps)), 1) if gaps else None,
    }


def probe_clock() -> dict:
    notes = pattern(bars=4)
    bias = {KICK: 22.0, SNARE: -6.0, HAT: 8.0}  # shape: the kick drags, the snare snaps early, the hats sit back
    a = staggered(notes, bias, wobble_ms=12.0, rho=0.75, seed="cardboard")
    a2 = staggered(notes, bias, wobble_ms=12.0, rho=0.75, seed="cardboard")
    b = humanised(notes, 22.0, "dust")
    # CYPHER's `behind` for the same biases, no drift
    c = [(bb, p, w, bias[p]) for (bb, p, w) in notes]
    # note: none of this moves a beat, so a bar-exact loop stays bar-exact; the last note's offset is clamped
    return {
        "staggered": clock_stats(a),
        "staggered_same_seed_same_offsets": a == a2,
        "humanised_22ms": clock_stats(b),
        "behind_only": clock_stats(c),
        "law": {"bias_ms": {"kick": 22, "snare": -6, "hat": 8}, "wobble_ms": 12, "rho_per_eighth": 0.75, "clamp_ms": 60},
    }


# ---------------------------------------------------------------- 3. the pitched-down foley: converter order

def timpani_raw(seconds: float = 1.6) -> np.ndarray:
    n = int(seconds * INTERNAL)
    strike = hann_pulse(4.0, INTERNAL, n)
    # a kettle drum's first partials: the 1 : 1.5 : 1.98 : 2.44 : 2.9 family (shape), the root long
    return modal_ring(strike, 88.0, [(1.0, 1.0, 1.3), (1.5, 0.7, 0.9), (1.98, 0.45, 0.6), (2.44, 0.3, 0.45), (2.9, 0.2, 0.3)], INTERNAL)


def zap_raw(seconds: float = 0.35) -> np.ndarray:
    n = int(seconds * INTERNAL)
    t = np.arange(n) / INTERNAL
    hz = 200.0 + (2_600.0 - 200.0) * np.exp(-t / 0.045)
    phase = 2 * np.pi * np.cumsum(hz) / INTERNAL
    env = np.exp(-6.9078 * t / 0.25)
    return (np.sin(phase) + 0.35 * np.sin(2 * phase)) * env


def resample_linear(x: np.ndarray, ratio: float) -> np.ndarray:
    """Play x at `ratio` speed with linear interpolation (ratio < 1 is slower and lower). No anti-alias filter."""
    n = int(len(x) / ratio)
    pos = np.arange(n) * ratio
    return np.interp(pos, np.arange(len(x)), x)


def converter_12bit_26k(x: np.ndarray, rate: int = RATE, hold_hz: float = 26_040.0, bits: int = 12) -> np.ndarray:
    """Eras.sp1200's shape: down to the hold rate without an anti-alias filter, truncate to 12 bits, zero-order hold back up."""
    down = resample_linear(x, rate / hold_hz)
    q = 2 ** (bits - 1)
    down = np.trunc(down * q) / q
    idx = np.minimum((np.arange(int(len(down) * rate / hold_hz)) * hold_hz / rate).astype(int), len(down) - 1)
    return down[idx]


def pitch_down(x: np.ndarray, semitones: float) -> np.ndarray:
    return resample_linear(x, 2 ** (-semitones / 12))


def probe_foley(out: str) -> dict:
    rows = {}
    for name, raw in [("timpani", timpani_raw()), ("zap", zap_raw())]:
        dry = finish(raw)
        rack = converter_12bit_26k(pitch_down(dry, 7))     # the rack's order: PITCH (Speed) first, then CRUNCH
        sampler = pitch_down(converter_12bit_26k(dry), 7)  # a sampler's order: convert, then play the sample slow
        for tag, y in [("dry", dry), ("rack-order", rack), ("sampler-order", sampler)]:
            write_wav(os.path.join(out, "%s-%s.wav" % (name, tag)), y)
        rows[name] = {tag: {
            "share_6k_16k": round(band_share(y, RATE, 6_000, 16_000), 5),
            "share_above_10k": round(band_share(y, RATE, 10_000, 22_050), 5),
            "centroid_hz": round(centroid(y, RATE)),
            "seconds": round(len(y) / RATE, 2),
        } for tag, y in [("dry", dry), ("rack_order", rack), ("sampler_order", sampler)]}
        # where the hold images land: the strongest bin above 10 kHz in each order
        for tag, y in [("rack_order", rack), ("sampler_order", sampler)]:
            spec = np.abs(np.fft.rfft(y))
            f = np.fft.rfftfreq(len(y), 1 / RATE)
            hi = f >= 10_000
            rows[name][tag]["loudest_hz_above_10k"] = round(float(f[hi][np.argmax(spec[hi])]))
    rows["expected_image_hz"] = {
        "hold_image_for_low_content_at_44k1": 44_100 - 26_040,
        "same_image_pitched_down_7_semitones": round((44_100 - 26_040) * 2 ** (-7 / 12)),
    }
    return rows


# ---------------------------------------------------------------- 4. the glass shatter

def shatter_raw(take: int, seconds: float = 0.7, shards: int = 28) -> np.ndarray:
    """Many tiny modal shards. The break is the first three inside 2 ms; the fall is the rest, thinning out over
    120 ms (an exponential density). Every shard's pitch, ring and timing is drawn from the take's seed, so a take
    is another pane breaking, not the same one again."""
    n = int(seconds * INTERNAL)
    noise = Noise(seed_for("STAGGER", "shatter", take))
    out = np.zeros(n)
    for s in range(shards):
        if s < 3:
            at_s = 0.0007 * s
        else:
            u = (noise.next() + 1) / 2
            at_s = 0.002 + 0.12 * (-math.log(max(1e-6, 1 - u)) / 3.0)  # exponential, mostly early
            at_s = min(at_s, 0.3)
        base = 2_200.0 * 2 ** (2.0 * (noise.next() + 1) / 2)       # 2.2 to 8.8 kHz
        t60 = 0.02 + 0.2 * ((noise.next() + 1) / 2) ** 2            # 20 to 220 ms, mostly short
        gain = 0.35 + 0.65 * (noise.next() + 1) / 2
        if s >= 3:
            gain *= math.exp(-at_s / 0.08)                            # later shards are smaller
        r2 = 1.45 + 0.4 * (noise.next() + 1) / 2
        r3 = 2.1 + 0.8 * (noise.next() + 1) / 2
        strike = hann_pulse(0.15, INTERNAL, n, at=int(at_s * INTERNAL))
        out += gain * modal_ring(strike, base, [(1.0, 1.0, t60), (r2, 0.6, t60 * 0.7), (r3, 0.35, t60 * 0.5)], INTERNAL)
    return out


def punch_raw(seconds: float = 0.6) -> np.ndarray:
    """A cartoon punch's shape: a rising noise whoosh whose band falls 3 kHz to 500 Hz over 110 ms, then a short thud."""
    n = int(seconds * INTERNAL)
    noise = Noise(seed_for("STAGGER", "punch")).array(int(0.13 * INTERNAL))
    whoosh = np.zeros(n)
    whoosh[: len(noise)] = noise * np.linspace(0.05, 1.0, len(noise)) ** 2
    # a band that falls: two passes, the first bright and the second dark, crossfaded by time
    bright = biquad_bp(whoosh, 3_000.0, INTERNAL, 1.2)
    dark = biquad_bp(whoosh, 550.0, INTERNAL, 1.2)
    ramp = np.clip(np.arange(n) / (0.11 * INTERNAL), 0, 1)
    swept = bright * (1 - ramp) + dark * ramp
    swept /= float(np.max(np.abs(swept))) or 1.0
    thud = kick_raw(seconds=0.45, root_hz=95.0, start_hz=180.0, bend_ms=25.0, t60=0.22, click=0.3)
    out = 0.5 * swept
    at = int(0.11 * INTERNAL)
    out[at: at + len(thud)] += thud[: n - at]
    return out


def probe_shatter(out: str) -> dict:
    takes = [finish(shatter_raw(t)) for t in range(3)]
    for t, y in enumerate(takes):
        write_wav(os.path.join(out, "shatter-take%d.wav" % t), y)
    pitched = pitch_down(converter_12bit_26k(takes[0]), 5)
    write_wav(os.path.join(out, "shatter-take0-converter-down5.wav"), pitched)
    punch = finish(punch_raw())
    write_wav(os.path.join(out, "punch.wav"), punch)
    write_wav(os.path.join(out, "punch-converter-down5.wav"), pitch_down(converter_12bit_26k(punch), 5))
    return {
        "finite": all(bool(np.all(np.isfinite(y))) for y in takes),
        "seconds": round(len(takes[0]) / RATE, 2),
        "centroid_hz": round(centroid(takes[0], RATE)),
        "centroid_hz_pitched_converted": round(centroid(pitched, RATE)),
        "take_similarity_0_1": round(similarity(takes[0], takes[1]), 3),
        "take_similarity_0_2": round(similarity(takes[0], takes[2]), 3),
        "same_take_twice_identical": bool(np.array_equal(finish(shatter_raw(0)), takes[0])),
        "punch": {"seconds": round(len(punch) / RATE, 2), "centroid_hz": round(centroid(punch, RATE)),
                  "onset_centroid_hz": round(onset_centroid(punch, RATE)), "attack_ms_to_90pct": attack_ms(punch, RATE)},
    }


def main(argv) -> int:
    out = argv[1] if len(argv) > 1 else os.path.join("testkit", "stagger-proto")
    os.makedirs(out, exist_ok=True)
    report = {
        "harness": "Python numpy %s, not Kotlin; nothing heard" % np.__version__,
        "muffled_kick": probe_kick(out),
        "drunk_clock": probe_clock(),
        "foley_converter_order": probe_foley(out),
        "glass_shatter": probe_shatter(out),
    }
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "report.json"), "w") as f:
        json.dump(report, f, indent=2)
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
