"""snipkit: a small Python stand-in for SnipSnap's synth toolkit, for prototyping.

Why this exists: SnipSnap renders in Kotlin, offline, mono, 44.1 kHz, at 4x internally, then
band-limits, decimates, removes DC, levels to a loudness target and fades the tail. When you
want to hear or measure a design before writing Kotlin, you need the same shape of pipeline so
the numbers mean something. This module gives you that shape with NumPy and SciPy only.

It is a stand-in, not the house code:
  * `Modes.ring` is reproduced faithfully (2-pole resonator per mode, gain without the (1-r)
    factor, modes at or above Nyquist skipped).
  * `Dsp.decimate` is approximated by two cascaded 2:1 windowed-sinc steps (same idea, not the
    same taps).
  * `Loudness.of` is NOT reproduced; `level_to` uses an RMS proxy. Compare designs against
    each other with it, never against the Kotlin target value.
  * `Dsp.Noise` is an LCG; ours is a different LCG, so sequences differ.
Say which of these you relied on when you report a number.

Everything here is deterministic: seeds come from `seed_for(*parts)`.

Usage sketch:
    import snipkit as sk
    rate = sk.RATE * sk.OVERSAMPLE
    exc = sk.hann_pulse(1.2, rate, amplitude=1.0, total_seconds=2.0)
    modes = [(1.0, 1.0, 1.6), (2.76, .5, .9), (5.4, .25, .5)]   # (ratio, gain, t60 seconds)
    raw = sk.modal_ring(exc, hz=220.0, modes=modes, rate=rate)
    out = sk.finish(raw)            # the house output stage at 44.1 kHz
    sk.write_wav("bell.wav", out)
"""
from __future__ import annotations

import hashlib
import math
import struct
import wave
from dataclasses import dataclass
from typing import Iterable, Sequence

import numpy as np
from scipy import signal

RATE = 44_100                 # Dsp.RATE (the MPC sample rate)
OVERSAMPLE = 4                # Dsp.OVERSAMPLE
INTERNAL = RATE * OVERSAMPLE  # the rate engines synthesize at
MELODIC_LOUDNESS_TARGET = 0.1834  # Dsp.MELODIC_LOUDNESS_TARGET (house units; see level_to)
MAX_SEAM_ERROR = 1e-3         # Keys.MAX_SEAM_ERROR
OUTPUT_DC_HZ = 20.0           # the house one-pole high-pass after the mean subtraction


# ---------------------------------------------------------------- seeds and noise

def seed_for(*parts) -> int:
    """A stable 63-bit seed from any parts, like Dsp.seedFor: same parts, same seed, forever."""
    h = hashlib.blake2b(digest_size=8)
    for p in parts:
        h.update(repr(p).encode("utf-8"))
        h.update(b"\x1f")
    return int.from_bytes(h.digest(), "big") & 0x7FFF_FFFF_FFFF_FFFF


class Noise:
    """Deterministic uniform noise in [-1, 1). A 64-bit LCG, so a seed reproduces bit for bit."""

    def __init__(self, seed: int):
        self.state = (seed * 6364136223846793005 + 1442695040888963407) & 0xFFFF_FFFF_FFFF_FFFF

    def next(self) -> float:
        self.state = (self.state * 6364136223846793005 + 1442695040888963407) & 0xFFFF_FFFF_FFFF_FFFF
        return ((self.state >> 11) / float(1 << 53)) * 2.0 - 1.0

    def array(self, n: int) -> np.ndarray:
        # Vectorized form of the same recurrence, so array(n) == [next() for _ in range(n)].
        out = np.empty(n)
        s = self.state
        for i in range(n):
            s = (s * 6364136223846793005 + 1442695040888963407) & 0xFFFF_FFFF_FFFF_FFFF
            out[i] = ((s >> 11) / float(1 << 53)) * 2.0 - 1.0
        self.state = s
        return out


# ---------------------------------------------------------------- exciters

def hann_pulse(width_ms: float, rate: int, amplitude: float = 1.0, total_seconds: float = 1.0,
               at_seconds: float = 0.0) -> np.ndarray:
    """A raised-cosine force pulse (a mallet, a knock). Width sets brightness: a 0.3 ms pulse
    is a hard stick, 3 ms is a soft mallet. Returns a buffer `total_seconds` long with the
    pulse placed at `at_seconds`, so you can schedule several and sum them."""
    n = int(total_seconds * rate)
    w = max(2, int(width_ms * 1e-3 * rate))
    buf = np.zeros(n)
    start = int(at_seconds * rate)
    pulse = amplitude * 0.5 * (1 - np.cos(2 * np.pi * np.arange(w) / w))
    end = min(n, start + w)
    buf[start:end] += pulse[: end - start]
    return buf


def noise_burst(width_ms: float, rate: int, seed: int, amplitude: float = 1.0,
                total_seconds: float = 1.0, lowpass_hz: float | None = None) -> np.ndarray:
    """A windowed noise burst (a pluck, a chiff). Lowpass it to soften the pick."""
    n = int(total_seconds * rate)
    w = max(4, int(width_ms * 1e-3 * rate))
    burst = Noise(seed).array(w) * np.hanning(w) * amplitude
    if lowpass_hz:
        b, a = signal.butter(2, lowpass_hz / (rate / 2))
        burst = signal.lfilter(b, a, burst)
    buf = np.zeros(n)
    buf[:w] = burst
    return buf


# ---------------------------------------------------------------- resonators

def modal_ring(excitation: np.ndarray, hz: float, modes: Sequence[tuple[float, float, float]],
               rate: int, compensate: bool = False) -> np.ndarray:
    """Modes.ring: a two-pole resonator per mode.

    modes: (ratio, gain, t60_seconds). Frequency = hz * ratio. Pole radius r = exp(-6.9078 /
    (t60 * rate)) so the mode decays 60 dB in t60. The gain is the plain gain (no (1-r)
    factor), which keeps onset level flat across t60, as the Kotlin KDoc explains; the price
    is that peak amplitude scales with 1/sin(theta) and grows at low pitch and at oversampled
    rates (at 176.4 kHz a 65 Hz mode rings about 10 dB louder than a 220 Hz one for the same
    gain). Peak-normalize before mixing with anything else. Modes at or above Nyquist are
    skipped, as in the Kotlin.

    compensate=True multiplies each gain by sin(theta) so a mode's peak response no longer
    depends on its frequency. That is NOT what the Kotlin does; use it to hear a mode table
    as its ratios and gains intend, and leave it False when you are checking how a table
    will actually behave once ported."""
    out = np.zeros_like(excitation)
    nyq = rate / 2
    for ratio, gain, t60 in modes:
        f = hz * ratio
        if f >= nyq or t60 <= 0:
            continue
        r = math.exp(-6.9078 / (t60 * rate))
        theta = 2 * math.pi * f / rate
        g = gain * (math.sin(theta) if compensate else 1.0)
        # y[n] = g x[n] + 2 r cos(theta) y[n-1] - r^2 y[n-2]
        b = [g]
        a = [1.0, -2 * r * math.cos(theta), r * r]
        out += signal.lfilter(b, a, excitation)
    return out


@dataclass
class KSLoop:
    """A Karplus-Strong / waveguide loop with a fractional delay (linear interpolation), a
    one-pole loop lowpass and a loop gain. The simplest `Strings.Loop`. The tuning is not
    budgeted for the filter's phase delay (the Kotlin does budget it); expect a few cents
    flat at high notes, and say so if you report pitch from it."""
    hz: float
    rate: int
    loop_gain: float = 0.996
    lowpass: float = 0.5      # 0..1, the one-pole coefficient in the loop (0 = no filtering)

    def render(self, excitation: np.ndarray) -> np.ndarray:
        period = self.rate / self.hz
        n = len(excitation)
        size = int(period) + 2
        buf = np.zeros(size)
        out = np.zeros(n)
        w = 0
        lp = 0.0
        frac = period - int(period)
        for i in range(n):
            # read one period back, fractionally
            r = (w - period) % size
            i0 = int(r)
            i1 = (i0 + 1) % size
            delayed = buf[i0] * (1 - frac) + buf[i1] * frac
            lp = lp + self.lowpass * (delayed - lp)
            y = excitation[i] + self.loop_gain * lp
            buf[w] = y
            out[i] = y
            w = (w + 1) % size
        return out


# ---------------------------------------------------------------- the house output stage

def band_limit(x: np.ndarray, rate: int, cutoff_hz: float = 20_000.0) -> np.ndarray:
    """Tide.bandLimit: an 8th-order Butterworth lowpass before decimation."""
    sos = signal.butter(8, cutoff_hz / (rate / 2), output="sos")
    return signal.sosfilt(sos, x)


def decimate4(x: np.ndarray) -> np.ndarray:
    """Dsp.decimate: two cascaded 2:1 windowed-sinc steps (approximate taps)."""
    y = x
    for _ in range(2):
        taps = signal.firwin(63, 0.45)  # half-band-ish windowed sinc
        y = signal.lfilter(taps, [1.0], y)[::2]
    return y


def dc_block(x: np.ndarray, rate: int = RATE, hz: float = OUTPUT_DC_HZ) -> np.ndarray:
    """Subtract the mean, then a one-pole high-pass at `hz` (the house order)."""
    y = x - np.mean(x)
    rc = 1.0 / (2 * math.pi * hz)
    alpha = rc / (rc + 1.0 / rate)
    out = np.empty_like(y)
    prev_x = 0.0
    prev_y = 0.0
    for i in range(len(y)):
        prev_y = alpha * (prev_y + y[i] - prev_x)
        prev_x = y[i]
        out[i] = prev_y
    return out


def rms(x: np.ndarray) -> float:
    return float(np.sqrt(np.mean(x * x))) if len(x) else 0.0


def level_to(x: np.ndarray, target_rms: float = 0.1, ceiling: float = 0.99) -> np.ndarray:
    """A stand-in for Dsp.levelTo: scale to an RMS target, then limit the peak to `ceiling`.
    The Kotlin levels to Loudness.of (perceived level), not RMS; use this to compare designs
    with each other, not to hit the house number."""
    r = rms(x)
    if r <= 0:
        return x
    y = x * (target_rms / r)
    p = np.max(np.abs(y))
    if p > ceiling:
        y = y * (ceiling / p)
    return y


def fade_tail(x: np.ndarray, rate: int = RATE, ms: float = 4.0) -> np.ndarray:
    n = min(len(x), int(ms * 1e-3 * rate))
    if n <= 0:
        return x
    y = x.copy()
    y[-n:] *= np.linspace(1.0, 0.0, n)
    return y


def finish(raw_internal: np.ndarray, target_rms: float = 0.1) -> np.ndarray:
    """The house output stage: bandLimit → decimate → DC → level → fadeTail.
    Input is at INTERNAL rate; output is at RATE."""
    y = band_limit(raw_internal, INTERNAL)
    y = decimate4(y)
    y = dc_block(y, RATE)
    y = level_to(y, target_rms)
    return fade_tail(y, RATE)


# ---------------------------------------------------------------- loops

def seam_error(s: np.ndarray, loop_start: int) -> float:
    """Keys.seamError: over the 256 frames before loop_start, sum((s[i+len]-s[i])^2) /
    sum(s[i]^2), where len = total - loop_start. The loop is everything from loop_start to
    the END of the buffer, so pass exactly warm-up + loop: the frames just before the loop
    are compared with the last frames of the loop. The warm-up must be a real preceding
    cycle (preroll longer than the longest decay); a loop pasted twice is periodic by
    construction and reads 0, which proves nothing."""
    n = len(s)
    loop_len = n - loop_start
    if loop_len <= 0 or loop_start < 256:
        return float("inf")
    i = np.arange(loop_start - 256, loop_start)
    j = i + loop_len
    j = j[j < n]
    i = i[: len(j)]
    num = float(np.sum((s[j] - s[i]) ** 2))
    den = float(np.sum(s[i] ** 2))
    return num / den if den > 0 else float("inf")


def whole_period_loop_frames(hz: float, rate: int, target_seconds: float) -> int:
    """Mercury-style planning: the number of whole periods closest to target_seconds, in whole
    frames, rounded to an even count. The pitch is then adjusted so those periods fit exactly:
    hz_adjusted = periods * rate / frames."""
    period = rate / hz                      # frames per period
    periods = max(1, round(target_seconds * hz))
    frames = int(round(periods * period))
    if frames % 2:
        frames += 1
    return frames


# ---------------------------------------------------------------- files

def write_wav(path: str, x: np.ndarray, rate: int = RATE) -> None:
    """16-bit PCM mono, the MPC-compatible shape. Clips at ±1 rather than wrapping."""
    y = np.clip(x, -1.0, 1.0)
    pcm = (y * 32767.0).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())


def read_wav(path: str) -> tuple[np.ndarray, int]:
    with wave.open(path, "rb") as w:
        rate = w.getframerate()
        n = w.getnframes()
        ch = w.getnchannels()
        sw = w.getsampwidth()
        raw = w.readframes(n)
    if sw == 2:
        x = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0
    elif sw == 3:
        b = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3)
        x = (b[:, 0].astype(np.int32) | (b[:, 1].astype(np.int32) << 8) | (b[:, 2].astype(np.int32) << 16))
        x = np.where(x & 0x800000, x - (1 << 24), x).astype(np.float64) / (1 << 23)
    else:
        raise ValueError(f"unsupported sample width {sw}")
    if ch > 1:
        x = x.reshape(-1, ch).mean(axis=1)
    return x, rate


# ---------------------------------------------------------------- macro maps (Dsp.lin / expMap / around)

def lin(x: float, lo: float, hi: float) -> float:
    return lo + (hi - lo) * min(1.0, max(0.0, x))


def exp_map(x: float, lo: float, hi: float) -> float:
    """Exponential map, for frequencies and times (0.5 lands at the geometric mean)."""
    x = min(1.0, max(0.0, x))
    return lo * (hi / lo) ** x


def around(x: float, centre: float, lo: float, hi: float) -> float:
    """A pinned-centre map: 0.5 returns exactly `centre`, 0 returns lo, 1 returns hi."""
    x = min(1.0, max(0.0, x))
    return centre + (x - 0.5) * 2 * ((centre - lo) if x < 0.5 else (hi - centre))
