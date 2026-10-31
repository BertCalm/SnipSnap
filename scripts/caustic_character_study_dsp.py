"""Audition-only Python references for two CAUSTIC character studies.

These are new proposed shapes, not native engine models and not measured wood/glass.
The source is routed through the exported CausticGeometry paths before passive modes.
No sample, normalization, limiter, DC conditioning, decimation, fade or output FX runs here.
The caller applies the shared output stage once. Rubbing is a driven pressure/noise
approximation, not a feedback friction or bow simulation.

A path has delayR * .65 / requestedHz seconds. Four-tap causal Lagrange interpolation
represents its fractional sample delay. All path weights retain their exported allocation;
there is no per-position or per-event renormalization. The modal transfer function is the
velocity readout of Kotlin Modes.Bank's rotate-then-kick recurrence, evaluated with SciPy.
"""
from __future__ import annotations

import hashlib
import math
from typing import Any, Callable

import numpy as np
from scipy import signal

RATE = 44_100
OVERSAMPLE = 4
INTERNAL_RATE = RATE * OVERSAMPLE
SECONDS = 8.0
FRAMES = int(SECONDS * INTERNAL_RATE)
DELAY_CYCLES = .65
MODEL_VERSION = 1

HOLLOW_PARAMETERS: dict[str, Any] = {
    "name": "Hollow Mallet", "reference": "Python Modes.Bank velocity recurrence",
    "gesture": "soft contact -> hollow low/odd shell -> short pitched decay",
    "contact_seconds_soft": .025, "contact_seconds_hard": .015,
    "force": 1_000.0, "fixed_output_gain": .45, "contact_noise": .055,
    "source_harmonics": [[1, .90], [2, .08], [3, .55], [4, .04], [5, .26], [7, .10]],
    "modes": [[1.0, 1.0, .46], [2.0, .16, .24], [3.005, 3.2, .38],
              [4.02, .08, .12], [5.01, 1.2, .24], [7.03, .40, .16]],
    "noise": "Dsp.Noise 31-bit LCG and Float conversion; exact Kotlin seedFor string hash",
    "status": "proposed audition shape; no physical material or hearing claim",
}
RUBBED_PARAMETERS: dict[str, Any] = {
    "name": "Rubbed Ring", "reference": "Python Modes.Bank velocity recurrence",
    "gesture": "rough contact -> pitched pressure swell -> driven body -> release",
    "rise_seconds": .34, "sustain_until_seconds": 1.18, "release_seconds": .28,
    "force": 120.0, "fixed_output_gain": .16, "rough_modulation": .65,
    "turbulence": .26, "contact_radiation": .25,
    "source_harmonics": [[1, .90], [2, .05], [3, .66], [4, .025], [5, .36], [7, .17], [9, .08]],
    "modes": [[1.0, .95, 1.05], [2.0, .08, .30], [3.0, 3.6, .85],
              [4.0, .04, .18], [5.0, 2.6, .55], [7.0, 1.4, .30], [9.0, .60, .18]],
    "noise": "Dsp.Noise 31-bit LCG and Float conversion; exact Kotlin seedFor string hash",
    "status": "proposed one-way pressure/noise rubbing approximation, not feedback friction",
}


def parameters() -> dict[str, Any]:
    """Declarative, JSON-serializable provenance for the caller's manifest."""
    return {"model": MODEL_VERSION, "sample_rate": RATE, "oversample": OVERSAMPLE,
            "seconds": SECONDS, "delay_cycles": DELAY_CYCLES,
            "fractional_delay": "causal four-tap Lagrange; compare against continuous source reference",
            "hollow": HOLLOW_PARAMETERS, "rubbed": RUBBED_PARAMETERS}


def _int32(x: int) -> int:
    x &= 0xffffffff
    return x if x < 0x80000000 else x - 0x100000000


def _string_hash(value: str) -> int:
    h = 0
    for char in value:
        h = _int32(h * 31 + ord(char))
    return h


def _seed_for(*parts: Any) -> int:
    h = 17
    for part in parts:
        h = _int32(h * 31 + _string_hash(str(part)))
    return h


def _noise(seed: int, n: int) -> np.ndarray:
    """Faithful Dsp.Noise state and Float arithmetic, returned as float64 samples."""
    state = seed if seed != 0 else 1
    values = np.empty(n, dtype=np.float64)
    for i in range(n):
        state = (state * 1103515245 + 12345) & 0x7fffffff
        values[i] = state
    return ((values.astype(np.float32) / np.float32(0x3fffffff)) - np.float32(1.0)).astype(np.float64)


def _validate(midi: int, velocity: float, geometry: dict[str, Any]) -> tuple[float, float, list[dict[str, Any]]]:
    if not isinstance(midi, (int, np.integer)) or not 36 <= midi <= 84:
        raise ValueError("character study MIDI must be an integer in 36..84")
    if not math.isfinite(velocity) or not 0 <= velocity <= 1:
        raise ValueError("velocity must be finite in 0..1")
    paths = geometry["paths"]
    for path in paths:
        if not 0 <= int(path["pickup"]) < 9:
            raise ValueError("pickup must be in 0..8")
        if not math.isfinite(path["delayR"]) or not 0 <= path["delayR"] <= 4:
            raise ValueError("delayR must be finite and causal")
        if not math.isfinite(path["weight"]) or path["weight"] < 0:
            raise ValueError("exported path weight must be finite and nonnegative")
    return 440.0 * 2.0 ** ((int(midi) - 69) / 12.0), float(velocity), paths


def _lowpass(x: np.ndarray, hz: float, order: int = 2) -> np.ndarray:
    return signal.sosfilt(signal.butter(order, hz, fs=INTERNAL_RATE, output="sos"), x)


def _source_harmonics(time: np.ndarray, hz: float, harmonics: list[list[float]]) -> np.ndarray:
    out = np.zeros_like(time)
    for harmonic, amplitude in harmonics:
        frequency = harmonic * hz
        if frequency >= 20_000:
            continue
        rolloff = 1.0 if frequency < 16_000 else .5 + .5 * np.cos(np.pi * (frequency - 16_000) / 4_000)
        out += amplitude * rolloff * np.sin(2 * np.pi * frequency * time)
    return out


def _fractional_weights(fraction: float) -> np.ndarray:
    coefficients = np.ones(4)
    for i in range(4):
        for j in range(4):
            if i != j:
                coefficients[i] *= (fraction - j) / (i - j)
    return coefficients


def _mode_ir(paths: list[dict[str, Any]], hz: float, mode_index: int,
             ratio: float, material: str) -> np.ndarray:
    """Signed, coherent modal-port reception of the one shared source waveform."""
    maximum = max((p["delayR"] * DELAY_CYCLES / hz * INTERNAL_RATE for p in paths), default=0)
    impulse = np.zeros(int(math.floor(maximum)) + 4)
    for path in paths:
        y = (int(path["pickup"]) - 4) / 4.0
        if mode_index == 0:
            participation = .92 + .08 * math.cos(math.pi * y)
        elif material == "hollow":
            participation = .28 + .72 * math.cos(math.pi * (.30 + .16 * (mode_index % 4)) *
                                                (y + .12 * math.sin(mode_index)))
        else:
            participation = .22 + .78 * math.cos(math.pi * (.35 + .19 * (mode_index % 4)) *
                                                (y + .20 * math.cos(mode_index)))
        # Fixed port/material participation. This is a modal band projection, not output EQ.
        loss = 1.0 / (1.0 + (ratio * (.035 + .085 * int(path["pickup"]) / 8.0)) ** 1.6)
        delay = path["delayR"] * DELAY_CYCLES / hz * INTERNAL_RATE
        integer = int(math.floor(delay))
        impulse[integer:integer + 4] += path["weight"] * participation * loss * _fractional_weights(delay - integer)
    return impulse


def _bank_velocity(force: np.ndarray, frequency: float, t60: float) -> np.ndarray:
    """Exactly the uncoupled rotate-then-kick Bank velocity transfer, to roundoff.

    z[n]=r*exp(i*w*T)*z[n-1]-i*T*force[n], y[n]=-Im(z[n]).
    Therefore H(z)=T*(1-r*cos(w*T)*z^-1)/(1-2*r*cos(w*T)*z^-1+r^2*z^-2).
    """
    radius = math.exp(-math.log(1000) / (t60 * INTERNAL_RATE))
    cr = radius * math.cos(2 * math.pi * frequency / INTERNAL_RATE)
    assert 0 < radius < 1
    return signal.lfilter([1.0 / INTERNAL_RATE, -cr / INTERNAL_RATE],
                          [1.0, -2.0 * cr, radius * radius], force)


def _ring(source: np.ndarray, hz: float, paths: list[dict[str, Any]],
          params: dict[str, Any], material: str) -> np.ndarray:
    out = np.zeros(FRAMES)
    for mode_index, (ratio, gain, t60) in enumerate(params["modes"]):
        frequency = ratio * hz
        if frequency >= 20_000:
            continue
        impulse = _mode_ir(paths, hz, mode_index, ratio, material)
        drive = signal.fftconvolve(source, impulse, mode="full")
        force = np.zeros(FRAMES)
        force[:min(FRAMES, len(drive))] = drive[:FRAMES] * params["force"]
        out += gain * _bank_velocity(force, frequency, t60)
    out *= params["fixed_output_gain"]
    if not np.isfinite(out).all() or np.max(np.abs(out)) >= 8:
        raise FloatingPointError("character study exceeded its unconditioned state/headroom bound")
    return out


def render_hollow(midi: int, velocity: float, geometry: dict[str, Any]) -> np.ndarray:
    """Eight-second 4x raw Hollow Mallet. No native processing or output normalization."""
    hz, vel, paths = _validate(midi, velocity, geometry)
    if vel == 0:
        return np.zeros(FRAMES)
    p = HOLLOW_PARAMETERS
    width = p["contact_seconds_soft"] + (p["contact_seconds_hard"] - p["contact_seconds_soft"]) * vel
    count = int(math.ceil(width * INTERNAL_RATE)) + 1
    time = np.arange(count) / INTERNAL_RATE
    contact = np.where(time < width, .5 - .5 * np.cos(2 * np.pi * time / width), 0.0)
    noise = _lowpass(_noise(_seed_for("CAUSTIC_CHARACTER", "HOLLOW", midi, MODEL_VERSION), count), 2_400)
    source = contact * (_source_harmonics(time, hz, p["source_harmonics"]) + p["contact_noise"] * noise)
    source *= vel ** .9
    return _ring(source, hz, paths, p, "hollow")


def render_rubbed(midi: int, velocity: float, geometry: dict[str, Any]) -> np.ndarray:
    """Eight-second 4x raw driven rubbing approximation; no feedback friction claim."""
    hz, vel, paths = _validate(midi, velocity, geometry)
    if vel == 0:
        return np.zeros(FRAMES)
    p = RUBBED_PARAMETERS
    end = p["sustain_until_seconds"] + p["release_seconds"]
    count = int(math.ceil((end + .05) * INTERNAL_RATE))
    time = np.arange(count) / INTERNAL_RATE
    pressure = np.where(time < p["rise_seconds"], .5 - .5 * np.cos(np.pi * time / p["rise_seconds"]), 1.0)
    release_phase = np.clip((time - p["sustain_until_seconds"]) / p["release_seconds"], 0, 1)
    pressure *= .5 + .5 * np.cos(np.pi * release_phase)
    noise = _noise(_seed_for("CAUSTIC_CHARACTER", "RUBBED", midi, MODEL_VERSION), count)
    slow_roughness = _lowpass(noise, 45.0) * 6.0
    roughness = _lowpass(noise, 5_000.0)
    harmonic = _source_harmonics(time, hz, p["source_harmonics"])
    source = pressure * (harmonic * (1.0 + p["rough_modulation"] * slow_roughness) + p["turbulence"] * roughness)
    source *= vel ** .85
    out = _ring(source, hz, paths, p, "rubbed")
    # Quiet contact radiation follows the same pressure, noise and geometric path budget.
    # It is a source readout, not a room/reverb layer or an independently normalized hiss.
    impulse = _mode_ir(paths, hz, 0, 1.0, "rubbed")
    radiation = signal.fftconvolve(pressure * roughness * vel, impulse)
    out[:len(radiation)] += radiation * p["contact_radiation"]
    if not np.isfinite(out).all() or np.max(np.abs(out)) >= 8:
        raise FloatingPointError("rubbed pressure exceeded raw headroom")
    return out


def health(raw: np.ndarray, midi: int, character: str) -> dict[str, Any]:
    """Read-only raw checks. Constrained-root/periodicity probes are not broad Pitch.detect."""
    expected = 440.0 * 2.0 ** ((midi - 69) / 12.0)
    if not np.any(raw):
        return {"finite": bool(np.isfinite(raw).all()), "samples": len(raw), "sample_rate": INTERNAL_RATE,
                "raw_peak": 0.0, "raw_rms": 0.0, "raw_mean": 0.0,
                "constrained_fft_root_hz": None, "constrained_fft_root_cents": None,
                "periodicity_at_requested_root": 0.0, "pitch_note": "silence has no estimated root"}
    start, end = ((.04, .24) if character.lower().startswith("hollow") else (.55, 1.05))
    segment = raw[int(start * INTERNAL_RATE):int(end * INTERNAL_RATE):4]
    rate = RATE
    nfft = 1 << max(18, (len(segment) - 1).bit_length())
    spectrum = np.abs(np.fft.rfft(segment * np.hanning(len(segment)), n=nfft)) ** 2
    frequencies = np.fft.rfftfreq(nfft, 1.0 / rate)
    candidates = np.flatnonzero((frequencies > expected * .96) & (frequencies < expected * 1.04))
    center = int(candidates[np.argmax(spectrum[candidates])])
    y = np.log(np.maximum(spectrum[center - 1:center + 2], 1e-100))
    denom = y[0] - 2 * y[1] + y[2]
    fraction = .5 * (y[0] - y[2]) / denom if denom else 0.0
    root = (center + float(np.clip(fraction, -.5, .5))) * rate / nfft
    lag = round(rate / expected)
    periodicity = float(np.dot(segment[:-lag], segment[lag:]) /
                        np.sqrt(np.dot(segment[:-lag], segment[:-lag]) * np.dot(segment[lag:], segment[lag:])))
    return {"finite": bool(np.isfinite(raw).all()), "samples": len(raw), "sample_rate": INTERNAL_RATE,
            "raw_peak": float(np.max(np.abs(raw))), "raw_rms": float(np.sqrt(np.mean(raw * raw))),
            "raw_mean": float(np.mean(raw)), "constrained_fft_root_hz": root,
            "constrained_fft_root_cents": 1200 * math.log2(root / expected),
            "periodicity_at_requested_root": periodicity,
            "pitch_note": "constrained FFT/periodicity only; shared finished-audio broad Pitch.detect still required"}


def determinism_probe(renderer: Callable[..., np.ndarray], midi: int, velocity: float,
                      geometry: dict[str, Any]) -> dict[str, Any]:
    a = renderer(midi, velocity, geometry)
    b = renderer(midi, velocity, geometry)
    return {"identical": bool(np.array_equal(a, b)),
            "sha256_f64": hashlib.sha256(a.astype("<f8", copy=False).tobytes()).hexdigest()}
