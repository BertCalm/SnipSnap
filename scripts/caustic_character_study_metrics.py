#!/usr/bin/env python3
"""Diagnostics for a CAUSTIC character study, using supplied mono float audio.

Requires NumPy and SciPy; it does not render, level, or modify the input audio.
These are shape screens and pitch warnings, never listening-acceptance claims.

Public API::

    profiles = [
        {"id": "fuller-body", "midi": 60, "samples": baseline_matched,
         "description": "Unchanged native anchor"},
        {"id": "travelling-catches", "midi": 60, "samples": candidate_matched,
         "eventTimesSeconds": [0.0, .12, .25, .41]},
    ]
    report = evaluate_study(profiles, baseline_id="fuller-body")

An id can repeat at different MIDI notes. An optional ``family`` matches the
baseline family when ids instead identify individual recordings. Metadata is
preserved under ``metadata``; samples are excluded from the JSON-ready result.
``measure_profile`` and ``compare_profiles`` also work independently. Main
comparisons use .05..1.5 s; a shorter recording is explicitly zero-padded.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
import math
from typing import Any

import numpy as np
from scipy.signal import correlate, find_peaks, stft


DEFAULT_THRESHOLDS = {
    # Descriptive shape thresholds chosen for this study, not perceptual limits.
    "spectralL1": .35,
    "envelopeResidual": .25,
    "attack50ShiftSeconds": .040,
    "energyMedianShiftSeconds": .120,
    "extraFluxEvents": 2,
    "fluxMinimumSpacingSeconds": .080,
    "fluxRelativeProminence": .12,
    "fluxRelativeHeight": .18,
    "fluxEnergyGateBelowPeakDb": -30.0,
    "pitchConfidence": .30,
    "pitchCents": 20.0,
    "quietMainDbFS": -60.0,
}

MAIN_WINDOW = (.05, 1.5)
SPECTRAL_EDGES_HZ = 40.0 * 2.0 ** (np.arange(55) / 6.0)
_EPS = 1e-100


def _thresholds(overrides: Mapping[str, float] | None) -> dict[str, float]:
    result = dict(DEFAULT_THRESHOLDS)
    if overrides:
        unknown = set(overrides) - set(result)
        if unknown:
            raise ValueError(f"Unknown metric thresholds: {sorted(unknown)}")
        for key, value in overrides.items():
            if not math.isfinite(float(value)):
                raise ValueError(f"Nonfinite threshold: {key}")
            result[key] = float(value)
    for key, value in result.items():
        if key not in ("fluxEnergyGateBelowPeakDb", "quietMainDbFS") and value < 0:
            raise ValueError(f"Negative metric threshold: {key}")
    return result


def _json_value(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {str(k): _json_value(v) for k, v in value.items()}
    if isinstance(value, (list, tuple, np.ndarray)):
        return [_json_value(v) for v in value]
    if isinstance(value, np.generic):
        value = value.item()
    if isinstance(value, float) and not math.isfinite(value):
        return None
    if value is None or isinstance(value, (str, bool, int, float)):
        return value
    raise TypeError(f"Metadata is not JSON-compatible: {type(value).__name__}")


def _audio(profile: Mapping[str, Any], sample_rate: int) -> np.ndarray:
    if not isinstance(profile.get("id"), str) or not profile["id"]:
        raise ValueError("Every profile needs a nonempty string id")
    midi = profile.get("midi")
    if isinstance(midi, bool) or not isinstance(midi, (int, np.integer)) or not 0 <= midi <= 127:
        raise ValueError(f"{profile['id']}: midi must be an integer in 0..127")
    if sample_rate < 8000:
        raise ValueError("sample_rate must be at least 8000 Hz")
    if "sampleRate" in profile and profile["sampleRate"] != sample_rate:
        raise ValueError(f"{profile['id']}: inconsistent sampleRate")
    samples = np.asarray(profile["samples"], dtype=np.float64)
    if samples.ndim != 1 or len(samples) < 4:
        raise ValueError(f"{profile['id']}: samples must be a nonempty mono float array")
    if not np.isfinite(samples).all():
        raise ValueError(f"{profile['id']}: samples contain NaN or infinity")
    return samples


def _db(amplitude: float) -> float | None:
    return float(20 * np.log10(amplitude)) if amplitude > 0 else None


def _rms(samples: np.ndarray) -> float:
    return float(np.sqrt(np.mean(samples * samples))) if len(samples) else 0.0


def _window(samples: np.ndarray, rate: int, start: float, end: float) -> np.ndarray:
    """Fixed-length windows make missing tails explicit rather than dropping them."""
    first, last = round(start * rate), round(end * rate)
    result = np.zeros(last - first, dtype=np.float64)
    available = samples[first:min(last, len(samples))]
    result[:len(available)] = available
    return result


def _normalize(values: np.ndarray) -> np.ndarray:
    return values / max(float(values.sum()), _EPS)


def _fit_residual(a: np.ndarray, b: np.ndarray) -> dict[str, float | None]:
    ea, eb = float(np.dot(a, a)), float(np.dot(b, b))
    gain = float(np.dot(a, b) / ea) if ea > _EPS else 0.0
    residual = b - gain * a
    return {
        "gainBOverA": gain,
        "residual": float(np.sqrt(np.dot(residual, residual) / eb)) if eb > _EPS else None,
        "differenceRmsDbFS": _db(_rms(residual)),
        "unfittedDifferenceRmsDbFS": _db(_rms(b - a)),
    }


def _rms_envelope(samples: np.ndarray, rate: int, window_seconds: float = .010,
                  hop_seconds: float = .002) -> tuple[np.ndarray, np.ndarray]:
    size, hop = max(1, round(window_seconds * rate)), max(1, round(hop_seconds * rate))
    starts = np.arange(0, max(1, len(samples) - size + 1), hop)
    ends = np.minimum(starts + size, len(samples))
    sums = np.concatenate(([0.0], np.cumsum(samples * samples)))
    envelope = np.sqrt(np.maximum(0, sums[ends] - sums[starts]) / np.maximum(1, ends - starts))
    return (starts + (ends - starts) / 2) / rate, envelope


def _spectrum(samples: np.ndarray, rate: int) -> dict[str, Any]:
    size = min(4096, len(samples))
    frequency, _, transformed = stft(samples, rate, window="hann", nperseg=size,
        noverlap=3 * size // 4, boundary=None, padded=False)
    power = np.mean(np.abs(transformed) ** 2, axis=1)
    total = float(power.sum())
    edges = SPECTRAL_EDGES_HZ[SPECTRAL_EDGES_HZ <= rate / 2]
    if edges[-1] < rate / 2:
        edges = np.append(edges, rate / 2)
    bands = np.histogram(frequency, bins=edges, weights=power)[0]
    return {
        "bands": _normalize(bands),
        "centroidHz": float(np.dot(frequency, power) / total) if total > _EPS else None,
        "frequency": frequency, "power": power,
    }


def _crossing(times: np.ndarray, values: np.ndarray, fraction: float) -> float | None:
    if not len(values) or values.max() <= _EPS:
        return None
    found = np.flatnonzero(values >= fraction * values.max())
    return float(times[found[0]]) if len(found) else None


def _pitch(samples: np.ndarray, rate: int, midi: int, start: float,
           seconds: float = .25) -> dict[str, Any]:
    """Float64 port of Pitch.kt's candidate/refinement rules, pending JVM check."""
    # Kotlin's public window arguments are Float, including their frame product.
    first = max(0, int(np.float32(start) * np.float32(rate)))
    frames = int(np.float32(seconds) * np.float32(rate))
    x = samples[first:min(len(samples), first + frames)]
    result: dict[str, Any] = {"windowStartSeconds": start, "windowSeconds": len(x) / rate,
        "hz": None, "centsFromRequested": None, "confidence": None,
        "periodHz": None, "spectralRefinementUsed": False, "status": "unavailable"}
    if len(x) < 1200 or float(np.dot(x, x)) <= 1e-9:
        return result
    low, high = max(2, int(rate / 1200)), min(int(rate / 40), len(x) - 2)
    if high <= low + 2:
        return result
    correlation = correlate(x, x, mode="full", method="fft")[len(x) - 1:]
    correlation = correlation[:high + 1] / max(float(np.dot(x, x)), _EPS)
    maximum = float(correlation[low:high + 1].max())
    result["maximumCorrelation"] = maximum
    if maximum < .3:
        return result
    lag = next((i for i in range(low + 1, high)
        if correlation[i] > .85 * maximum and correlation[i] >= correlation[i - 1]
        and correlation[i] >= correlation[i + 1]), None)
    if lag is None:
        return result
    curve = correlation[lag - 1] - 2 * correlation[lag] + correlation[lag + 1]
    shift = float(np.clip(.5 * (correlation[lag - 1] - correlation[lag + 1]) / curve, -.5, .5)) if abs(curve) > 1e-12 else 0.0
    candidate = rate / (lag + shift)
    found = candidate
    size = 1 << (len(x) - 1).bit_length()
    power = np.abs(np.fft.rfft(x * np.hanning(len(x)), n=size)) ** 2
    bin_hz = rate / size
    semitone = 2 ** (1 / 12)
    lower, upper = candidate / semitone, candidate * semitone
    lo, hi = max(1, math.ceil(lower / bin_hz)), min(len(power) - 2, math.floor(upper / bin_hz))
    peaks = [i for i in range(lo, hi + 1) if power[i] >= power[i - 1] and power[i] >= power[i + 1]]
    if peaks:
        peak = max(peaks, key=lambda i: power[i])
        candidate_bin = min(len(power) - 1, int(math.floor(candidate / bin_hz + .5)))
        if power[peak] >= max(power[1:-1]) * .005 and abs(peak - candidate_bin) >= 2 and power[peak] >= power[candidate_bin] * 10:
            left, middle, right = np.log(np.maximum(power[peak - 1:peak + 2], 1e-30))
            denominator = left - 2 * middle + right
            offset = float(np.clip(.5 * (left - right) / denominator, -.5, .5)) if abs(denominator) > 1e-12 else 0.0
            refined = (peak + offset) * bin_hz
            if lower <= refined <= upper:
                found = refined
                result["spectralRefinementUsed"] = True
    target = 440 * 2 ** ((midi - 69) / 12)
    result.update(hz=float(found), centsFromRequested=float(1200 * np.log2(found / target)),
        confidence=float(np.clip(correlation[lag], 0, 1)), periodHz=float(candidate), status="measured")
    return result


def _local_root(samples: np.ndarray, rate: int, midi: int) -> dict[str, Any]:
    """A target-constrained spectral check, never a substitute for broad pitch."""
    available_end = min(1.5, len(samples) / rate)
    segment = samples[round(.05 * rate):round(available_end * rate)]
    result: dict[str, Any] = {"hz": None, "centsFromRequested": None,
        "relativeToStrongestPeakPower": None, "substantial": False}
    if len(segment) < 1200 or _rms(segment) <= 1e-10:
        return result
    size = 4 * (1 << (len(segment) - 1).bit_length())
    power = np.abs(np.fft.rfft(segment * np.hanning(len(segment)), n=size)) ** 2
    frequency = np.fft.rfftfreq(size, 1 / rate)
    target = 440 * 2 ** ((midi - 69) / 12)
    indices = np.flatnonzero((frequency >= target / 2 ** (1 / 12)) & (frequency <= target * 2 ** (1 / 12)))
    peaks = [i for i in indices if 0 < i < len(power) - 1 and power[i] >= power[i - 1] and power[i] >= power[i + 1]]
    if not peaks:
        return result
    peak = max(peaks, key=lambda i: power[i])
    left, middle, right = np.log(np.maximum(power[peak - 1:peak + 2], 1e-30))
    denominator = left - 2 * middle + right
    offset = float(np.clip(.5 * (left - right) / denominator, -.5, .5)) if abs(denominator) > 1e-12 else 0.0
    found = (peak + offset) * rate / size
    strength = float(power[peak] / max(float(power.max()), _EPS))
    result.update(hz=float(found), centsFromRequested=float(1200 * np.log2(found / target)),
        relativeToStrongestPeakPower=strength, substantial=strength >= .005)
    return result


def _flux(samples: np.ndarray, rate: int, midi: int, thresholds: Mapping[str, float],
          event_times: Iterable[float] | None = None) -> dict[str, Any]:
    """Detected novelty peaks; modal beating can produce false event candidates."""
    root_hz = 440 * 2 ** ((midi - 69) / 12)
    # A 32ms window is only two C2 cycles and creates strong periodic leakage.
    size = max(32, round(max(.032, 6 / root_hz) * rate))
    # Preserve actual right-hand context: cutting a live note at1.5s introduces
    # a fictitious spectral event at the analysis boundary.
    x = _window(samples, rate, 0, 1.5 + size / rate)
    hop = max(1, round(.005 * rate))
    _, times, transformed = stft(x, rate, window="hann", nperseg=size,
        noverlap=size - hop, boundary="zeros", padded=True)
    magnitudes = np.abs(transformed)
    # Amplitude-weighted flux retains the energy distinction between a main event
    # and a quiet-tail wiggle; no per-frame normalization that amplifies silence.
    flux = np.sqrt(np.sum(np.maximum(0, np.diff(magnitudes, axis=1)) ** 2, axis=0))
    flux = np.concatenate(([0.0], flux))
    energy = np.sqrt(np.sum(magnitudes ** 2, axis=0))
    flux[energy < energy.max() * 10 ** (thresholds["fluxEnergyGateBelowPeakDb"] / 20)] = 0
    peak_flux = float(flux.max())
    relative = flux / max(peak_flux, _EPS)
    found, properties = find_peaks(relative,
        distance=max(1, round(thresholds["fluxMinimumSpacingSeconds"] * rate / hop)),
        height=thresholds["fluxRelativeHeight"], prominence=thresholds["fluxRelativeProminence"])
    keep = [(int(index), i) for i, index in enumerate(found) if 0 <= times[index] <= 1.5]
    events = [{"seconds": float(times[index]), "relativeFlux": float(relative[index]),
        "relativeProminence": float(properties["prominences"][i]),
        "frameRmsRelativeToStrongest": float(energy[index] / max(float(energy.max()), _EPS))}
        for index, i in keep]
    planned = []
    if event_times is not None:
        for value in event_times:
            when = float(value)
            if not math.isfinite(when):
                raise ValueError("eventTimesSeconds must be finite")
            nearby = np.flatnonzero(np.abs(times - when) <= .040)
            best = int(nearby[np.argmax(relative[nearby])]) if len(nearby) else None
            planned.append({"plannedSeconds": when,
                "nearbyMaximumRelativeFlux": float(relative[best]) if best is not None else None,
                "nearbyMaximumSeconds": float(times[best]) if best is not None else None,
                "nearestDetectedPeakDistanceSeconds": min((abs(e["seconds"] - when) for e in events), default=None)})
    return {"detectedPeakCount": len(events), "peaks": events, "windowSeconds": size / rate,
        "plannedEventEvidence": planned, "status": "novelty candidates, not inferred physical contacts"}


def _prepare(profile: Mapping[str, Any], sample_rate: int,
             thresholds: Mapping[str, float]) -> dict[str, Any]:
    samples = _audio(profile, sample_rate)
    main = _window(samples, sample_rate, *MAIN_WINDOW)
    spectrum = _spectrum(main, sample_rate)
    _, envelope = _rms_envelope(main, sample_rate, .025, .025)
    times, onset_envelope = _rms_envelope(_window(samples, sample_rate, 0, 1.5), sample_rate)
    onset10, onset50 = _crossing(times, onset_envelope, .1), _crossing(times, onset_envelope, .5)
    peak_time = float(times[int(onset_envelope.argmax())]) if onset_envelope.max() > _EPS else None
    early = _window(samples, sample_rate, 0, 1.5)
    cumulative = np.cumsum(early * early)
    def energy_time(fraction: float) -> float | None:
        return float(np.searchsorted(cumulative, fraction * cumulative[-1]) / sample_rate) if cumulative[-1] > _EPS else None
    windows = {"attack": (0, .15), "earlyBody": (.15, .45), "lateMainBody": (.6, 1.5),
        "main": MAIN_WINDOW, "laterBody": (1.5, 3.0), "tail": (3.0, 6.0)}
    levels = {key: _db(_rms(_window(samples, sample_rate, *bounds))) for key, bounds in windows.items()}
    pitch = [_pitch(samples, sample_rate, int(profile["midi"]), start) for start in (.05, .30, .65)]
    duration = len(samples) / sample_rate
    audition_pitch = _pitch(samples, sample_rate, int(profile["midi"]),
        min(.5, duration * .15), min(.35, duration * .6))
    warnings = []
    if abs(samples).max() >= 1:
        warnings.append("Provided samples reach or exceed full scale; check the actual export level.")
    if len(samples) / sample_rate < MAIN_WINDOW[1]:
        warnings.append("Recording ends inside the main comparison window; absent samples are zero-padded.")
    if _rms(main) <= 1e-10:
        warnings.append("Main window has negligible energy; contrast numbers are not meaningful.")
    initial_pitch = pitch[0]
    if initial_pitch["hz"] is None:
        warnings.append("Initial broad pitch unavailable; a local target peak cannot establish pitch correctness.")
    elif abs(initial_pitch["centsFromRequested"]) > thresholds["pitchCents"]:
        warnings.append(f"Initial broad pitch exceeds the {thresholds['pitchCents']:g}-cent diagnostic limit.")
    if initial_pitch["confidence"] is not None and initial_pitch["confidence"] < thresholds["pitchConfidence"]:
        warnings.append("Initial broad pitch confidence is below the diagnostic limit.")
    local = _local_root(samples, sample_rate, int(profile["midi"]))
    if not local["substantial"]:
        warnings.append("The target-constrained local root peak is weak or missing.")
    measured_cents = [p["centsFromRequested"] for p in pitch if p["hz"] is not None]
    if measured_cents and max(measured_cents) - min(measured_cents) > thresholds["pitchCents"]:
        warnings.append("Broad pitch varies across the measured body windows; inspect the gesture and JVM detector.")
    root_hz = 440 * 2 ** ((int(profile["midi"]) - 69) / 12)
    upper_fraction = float(spectrum["power"][spectrum["frequency"] >= 1.5 * root_hz].sum() / max(float(spectrum["power"].sum()), _EPS))
    metadata = _json_value({k: v for k, v in profile.items() if k != "samples"})
    summary = {"id": profile["id"], "family": profile.get("family", profile["id"]), "midi": int(profile["midi"]),
        "metadata": metadata, "sampleRate": sample_rate, "seconds": len(samples) / sample_rate,
        "finite": True, "samplePeak": float(abs(samples).max()), "rmsDbFS": levels,
        "attackMinusLateMainBodyDb": levels["attack"] - levels["lateMainBody"] if levels["attack"] is not None and levels["lateMainBody"] is not None else None,
        "onset10Seconds": onset10, "attack50Seconds": onset50, "envelopePeakSeconds": peak_time,
        "energy10Seconds": energy_time(.1), "energyMedianSeconds": energy_time(.5),
        "mainSpectralCentroidHz": spectrum["centroidHz"], "mainUpperPowerFraction": upper_fraction,
        "mainSpectralPowerBands": spectrum["bands"].tolist(), "mainEnvelopeRms": envelope.tolist(),
        "broadPitch": initial_pitch, "auditionWindowPitch": audition_pitch,
        "bodyPitchWindows": pitch, "localRoot": local,
        "flux": _flux(samples, sample_rate, int(profile["midi"]), thresholds, profile.get("eventTimesSeconds")), "warnings": warnings}
    return {"profile": profile, "samples": samples, "main": main, "spectrum": spectrum,
        "envelope": envelope, "summary": summary}


def measure_profile(profile: Mapping[str, Any], sample_rate: int = 44100, *,
                    thresholds: Mapping[str, float] | None = None) -> dict[str, Any]:
    """Measure one profile; the returned dictionary contains no audio samples."""
    return _prepare(profile, sample_rate, _thresholds(thresholds))["summary"]


def _compare(a: Mapping[str, Any], b: Mapping[str, Any],
             thresholds: Mapping[str, float]) -> dict[str, Any]:
    sa, sb = a["summary"], b["summary"]
    if sa["midi"] != sb["midi"]:
        raise ValueError("Character comparisons require the same MIDI note")
    signal_available = _rms(a["main"]) > 1e-10 and _rms(b["main"]) > 1e-10
    spectral = float(np.abs(a["spectrum"]["bands"] - b["spectrum"]["bands"]).sum()) if signal_available else None
    envelope = _fit_residual(a["envelope"], b["envelope"])
    waveform = _fit_residual(a["main"], b["main"])
    def delta(key: str) -> float | None:
        return sb[key] - sa[key] if sa[key] is not None and sb[key] is not None else None
    onset_delta, median_delta = delta("attack50Seconds"), delta("energyMedianSeconds")
    flux_delta = sb["flux"]["detectedPeakCount"] - sa["flux"]["detectedPeakCount"]
    flags = []
    if signal_available and spectral >= thresholds["spectralL1"]:
        flags.append("spectral-power-envelope")
    if signal_available and envelope["residual"] is not None and envelope["residual"] >= thresholds["envelopeResidual"]:
        flags.append("main-rms-envelope")
    if signal_available and onset_delta is not None and abs(onset_delta) >= thresholds["attack50ShiftSeconds"]:
        flags.append("attack-time")
    if signal_available and median_delta is not None and abs(median_delta) >= thresholds["energyMedianShiftSeconds"]:
        flags.append("main-energy-timing")
    if signal_available and flux_delta >= thresholds["extraFluxEvents"]:
        flags.append("additional-flux-events-candidate")
    warnings = []
    if not signal_available:
        warnings.append("One or both main windows have negligible energy; shape evidence is unavailable.")
    elif not flags:
        warnings.append("No declared main-window shape threshold was reached; review for insufficient contrast.")
    if "additional-flux-events-candidate" in flags:
        warnings.append("Flux can respond to modal beating; inspect peak times and planned-event evidence before calling this a rake.")
    if sa["warnings"] or sb["warnings"]:
        warnings.append("One or both input profiles have measurement warnings; inspect their profile rows.")
    for label, summary in (("baseline", sa), ("candidate", sb)):
        if summary["rmsDbFS"]["main"] is None or summary["rmsDbFS"]["main"] < thresholds["quietMainDbFS"]:
            warnings.append(f"{label} main-window RMS is below {thresholds['quietMainDbFS']:g} dBFS; differences may be confined to a quiet signal.")
    late_a, late_b = sa["rmsDbFS"]["lateMainBody"], sb["rmsDbFS"]["lateMainBody"]
    return {"baselineId": sa["id"], "candidateId": sb["id"], "midi": sa["midi"],
        "spectralL1": spectral, "envelopeResidual": envelope["residual"] if signal_available else None,
        "envelopeGainCandidateOverBaseline": envelope["gainBOverA"],
        "waveformResidualDiagnosticOnly": waveform["residual"],
        "gainFittedDifferenceRmsDbFS": waveform["differenceRmsDbFS"],
        "unfittedDifferenceRmsDbFS": waveform["unfittedDifferenceRmsDbFS"],
        "mainRmsBaselineDbFS": sa["rmsDbFS"]["main"], "mainRmsCandidateDbFS": sb["rmsDbFS"]["main"],
        "attack50ShiftSeconds": onset_delta, "onset10ShiftSeconds": delta("onset10Seconds"),
        "energyMedianShiftSeconds": median_delta, "lateMainBodyGainDb": late_b - late_a if late_a is not None and late_b is not None else None,
        "baselineFluxCount": sa["flux"]["detectedPeakCount"], "candidateFluxCount": sb["flux"]["detectedPeakCount"],
        "extraFluxEvents": flux_delta, "shapeFlags": flags,
        "shapeScreen": "review-unavailable-signal" if not signal_available else ("threshold-evidence" if flags else "review-insufficient-contrast"),
        "listeningAcceptance": "not established by metrics", "warnings": warnings}


def compare_profiles(baseline: Mapping[str, Any], candidate: Mapping[str, Any],
                     sample_rate: int = 44100, *,
                     thresholds: Mapping[str, float] | None = None) -> dict[str, Any]:
    """Compare supplied same-note audio; waveform residual never qualifies shape."""
    limits = _thresholds(thresholds)
    return _compare(_prepare(baseline, sample_rate, limits),
        _prepare(candidate, sample_rate, limits), limits)


def evaluate_study(profiles: Iterable[Mapping[str, Any]], sample_rate: int = 44100, *,
                   baseline_id: str = "fuller-body",
                   thresholds: Mapping[str, float] | None = None) -> dict[str, Any]:
    """Measure profiles and compare every candidate with its same-MIDI baseline.

    ``baseline_id`` matches ``family`` when present, otherwise ``id``. Missing or
    duplicate baselines are explicit errors/warnings rather than cross-register
    comparisons. All output is JSON-compatible, including unavailable values.
    """
    limits = _thresholds(thresholds)
    prepared = [_prepare(p, sample_rate, limits) for p in profiles]
    if not prepared:
        raise ValueError("The study contains no profiles")
    keys = [(p["summary"]["id"], p["summary"]["midi"]) for p in prepared]
    if len(set(keys)) != len(keys):
        raise ValueError("Profile (id, midi) keys must be unique")
    baselines = {}
    for p in prepared:
        summary = p["summary"]
        if summary["family"] == baseline_id:
            if summary["midi"] in baselines:
                raise ValueError(f"Duplicate {baseline_id} baseline at MIDI {summary['midi']}")
            baselines[summary["midi"]] = p
    if not baselines:
        raise ValueError(f"No profiles match baseline_id={baseline_id!r}")
    warnings = []
    comparisons = []
    for p in prepared:
        s = p["summary"]
        if s["family"] == baseline_id:
            continue
        baseline = baselines.get(s["midi"])
        if baseline is None:
            warnings.append(f"{s['id']} MIDI {s['midi']}: no same-note baseline; comparison omitted.")
            continue
        comparisons.append(_compare(baseline, p, limits))
    for row in prepared:
        if row["summary"]["warnings"]:
            warnings.append(f"{row['summary']['id']} MIDI {row['summary']['midi']}: inspect profile warnings.")
    for row in comparisons:
        if row["warnings"]:
            warnings.append(f"{row['candidateId']} MIDI {row['midi']}: inspect comparison warnings.")
    return {"schemaVersion": 1, "baselineId": baseline_id, "sampleRate": sample_rate,
        "method": {"status": "measured, not heard", "inputLevel": "provided audio; no hidden leveling",
            "mainWindowSeconds": list(MAIN_WINDOW), "shortFiles": "zero-padded, with warning",
            "spectralEnvelope": "mean 4096-sample Hann STFT power; hop1024; normalized 1/6-octave bands40Hz..Nyquist; L1 range0..2",
            "rmsEnvelope": "25ms nonoverlapping RMS; scalar gain fitted before residual",
            "attack": "10ms RMS / 2ms hop; window-center timestamps; first10% and50% crossings of the strongest envelope value in0..1.5s",
            "lateMainBodySeconds": [.6, 1.5],
            "flux": "Hann STFT window max(32ms,6root periods) / 5ms hop; real context beyond1.5s; positive amplitude-weighted flux with declared height, prominence, energy gate, spacing",
            "pitch": "float64 port of Pitch.kt autocorrelation and restricted spectral refinement; initial .05..0.30s plus later body windows; separate existing-audition window estimate; pending JVM cross-check",
            "localRoot": "target-constrained spectral presence only; never overrides broad pitch",
            "waveformResidual": "diagnostic only; phase or submillisecond delays can inflate it",
            "shapeScreen": "threshold evidence is a listening candidate, never a perceptual or acceptance pass",
            "fluxLimit": "modal beating can create novelty peaks; compare supplied planned event times",
            "pitchLimit": "normalized correlation confidence is not probability; transient or missing-root gestures may produce octave errors"},
        "thresholds": limits, "profiles": [p["summary"] for p in prepared],
        "comparisons": comparisons, "warnings": warnings,
        "listeningAcceptance": "not established by metrics"}
