"""OBLITERATE destruction-core audition, not a shipping SnipSnap processor.

The gesture is octave grit -> backward captured fragments -> stereo chop.
This is an input effect: unlike the pitched-engine prototype template it never
folds the input to mono or levels it to a target. There is no shimmer, feedback,
whole-file reverse, held loop, or claim of a seamless loop.

Workflow provenance: SnipSnap fd76ffa382c1bf94d6a82e5a9484e6cd4140e91c,
.claude/skills/luthier/scripts/proto_template.py and scripts/probes.py informed
the pure-render / diagnostics / signal-probe separation. No engine algorithm or
snipkit.finish implementation was copied: its mono/target-level output contract
does not apply to this stereo input effect. DSP here uses NumPy/SciPy primitives.
Captured grain spans precede their event onsets, but the complete processor is
offline: compensated resampling FIRs and the final whole-file peak guard do not
constitute a streaming-causal implementation.

Why it is built this way:
* Full-wave rectification really doubles a sine's period rate; 8x processing
  reduces the aliases of its sharp zero crossings before captured grains repeat it.
* A causal DC filter runs before capture so rectification's positive offset is
  not mistaken for useful grain energy.
* Each grain freezes a recent, already available source span. Reverse and pitch
  are actual read directions/rates, rather than names for a whole-file texture.
* One event schedule and one overlap divisor serve both channels. A stereo image
  and a quiet source survive the linear memory stage without per-grain levelling.
* An explicit musical clock opens the two channels at different phases. Its
  3 ms cosine edges trade an ideal hard chop for fewer artificial clicks.

All constants below are audition shape choices, not measured pedal modelling.
Python/SciPy host timings are not Android or Kotlin performance measurements.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import hashlib
import json
import math
import time
from typing import Any

import numpy as np
from scipy import signal


SAMPLE_RATE = 44_100
OVERSAMPLE = 8
MAX_TAIL_SECONDS = 0.75
TAIL_FRAMES = round(MAX_TAIL_SECONDS * SAMPLE_RATE)
MAX_INPUT_SECONDS = 12.0
MAX_GRAINS = 1024
PEAK_CEILING = 0.98

SHAPE_CONSTANTS: dict[str, Any] = {
    "model": "obliterate-core-python-r1",
    "sample_rate": SAMPLE_RATE,
    "oversample": OVERSAMPLE,
    "resampling": "offline scipy.signal.resample_poly, Kaiser beta 10, compensated FIR lookahead/delay",
    "streaming_causal": False,
    "fuzz_drive": "1 + 11 * amount",
    "fuzz_curve": "0.5 * tanh(drive * abs(x)); channel-wise full-wave rectification",
    "fuzz_mix": "0.9 * amount",
    "fuzz_dc_highpass_hz": 30.0,
    "fuzz_dc_order": 2,
    "fuzz_tone_lowpass_hz": 9_000.0,
    "fuzz_tone_order": 4,
    "grain_history_seconds": 0.320,
    "grain_duration_seconds": "0.075 + 0.060 * amount, shortened to valid captured span",
    "grain_capture_lag_seconds": "0.012 + uniform(0, 0.055 * amount)",
    "grain_hop": "half the explicit clock division",
    "grain_onset_jitter": "uniform(-1,1) * 0.10 * amount * clock step",
    "grain_pitch_pattern_semitones": [0, 7, -12, 12],
    "grain_pitch_depth": "pattern semitones * amount",
    "grain_reverse": "three of every four events; fourth is a forward scatter fragment",
    "grain_stereo_balance": "one seeded +/-0.35*amount balance; never crossfeed or mono-fold",
    "grain_window": "Hann with both endpoints zero",
    "grain_interpolation": "linear; no ratio-specific antialias resampler, so pitched residual highs can alias",
    "grain_overlap_divisor": "max(1, sum of windows), linked across channels",
    "grain_mix": "0.82 * amount, faded in from 18 to 48 ms",
    "post_fragment_dc_highpass_hz": 20.0,
    "chop_depth": "amount, faded in from 18 to 48 ms",
    "chop_duty": "0.58 - 0.13 * amount",
    "chop_right_phase_cycles": "0.5 * amount",
    "chop_edge_seconds": 0.003,
    "clock_step_seconds": "60 / bpm * 4 / division; sample-index phase, no rounded-step accumulation",
    "tail_seconds": MAX_TAIL_SECONDS,
    "tail_end_fade_seconds": 0.004,
    "max_input_seconds": MAX_INPUT_SECONDS,
    "max_grains": MAX_GRAINS,
    "peak_guard": "one linked gain min(1, 0.98 / peak), attenuation only; not loudness matching",
    "seed_generator": "NumPy PCG64; explicitly seeded, fresh per render",
}


@dataclass(frozen=True)
class Settings:
    amount: float = 0.7
    bpm: float = 120.0
    division: int = 16
    seed: int = 20261023

    def __post_init__(self) -> None:
        if isinstance(self.amount, bool) or not math.isfinite(self.amount) or not 0 <= self.amount <= 1:
            raise ValueError("amount must be finite and in [0, 1]")
        if isinstance(self.bpm, bool) or not math.isfinite(self.bpm) or not 40 <= self.bpm <= 240:
            raise ValueError("bpm must be finite and in [40, 240]")
        if isinstance(self.division, bool) or self.division not in (4, 8, 16, 32):
            raise ValueError("division must be one of 4, 8, 16, 32")
        if isinstance(self.seed, bool) or not isinstance(self.seed, int) or not 0 <= self.seed < 2**32:
            raise ValueError("seed must be an integer in [0, 2**32)")


@dataclass(frozen=True)
class RenderResult:
    audio: np.ndarray
    diagnostics: dict[str, Any]


@dataclass(frozen=True)
class GrainEvent:
    onset_frame: int
    length_frames: int
    source_first_frame: float
    source_last_frame: float
    pitch_ratio: float
    semitones: float
    reverse: bool
    balance: float


def _peak(audio: np.ndarray) -> float:
    return float(np.max(np.abs(audio), initial=0.0))


def _metrics(audio: np.ndarray) -> dict[str, Any]:
    return {"peak": _peak(audio), "rms": float(np.sqrt(np.mean(audio * audio))),
            "channel_mean": np.mean(audio, axis=0).tolist()}


def _engagement(frames: int) -> np.ndarray:
    # Keep the first 18 ms available to identify a drum/pluck, then break its sustain.
    ramp = np.clip((np.arange(frames) / SAMPLE_RATE - 0.018) / 0.030, 0.0, 1.0)
    return 0.5 - 0.5 * np.cos(np.pi * ramp)


def _dc_filter(audio: np.ndarray, hz: float, rate: float) -> np.ndarray:
    return signal.sosfilt(signal.butter(2, hz, btype="highpass", fs=rate, output="sos"), audio, axis=0)


def _fuzz(audio: np.ndarray, amount: float, oversample: int = OVERSAMPLE) -> np.ndarray:
    """Raw stage for alias probes too; no pad, guard, normalization or state reuse."""
    rate = SAMPLE_RATE * oversample
    up = signal.resample_poly(audio, oversample, 1, axis=0, window=("kaiser", 10.0)) if oversample > 1 else audio
    wet = 0.5 * np.tanh((1.0 + 11.0 * amount) * np.abs(up))
    shaped = (1.0 - 0.9 * amount) * up + (0.9 * amount) * wet
    shaped = _dc_filter(shaped, 30.0, rate)
    shaped = signal.sosfilt(signal.butter(4, 9_000.0, fs=rate, output="sos"), shaped, axis=0)
    if oversample > 1:
        shaped = signal.resample_poly(shaped, 1, oversample, axis=0, window=("kaiser", 10.0))
    return np.asarray(shaped[:len(audio)], dtype=np.float64)


def _schedule(input_frames: int, settings: Settings) -> tuple[GrainEvent, ...]:
    """Precompute a bounded, causal event inventory before the audio grain loop."""
    rng = np.random.Generator(np.random.PCG64(settings.seed))
    amount = settings.amount
    step = 60.0 / settings.bpm * 4.0 / settings.division * SAMPLE_RATE
    hop = step * 0.5
    count = max(0, math.ceil(input_frames / hop) - 1)
    if count > MAX_GRAINS:
        raise ValueError("grain schedule exceeds fixed event budget")
    history = round(0.320 * SAMPLE_RATE)
    preferred_length = round((0.075 + 0.060 * amount) * SAMPLE_RATE)
    events: list[GrainEvent] = []
    for index in range(count):
        onset = round((index + 1) * hop + rng.uniform(-1.0, 1.0) * 0.10 * amount * step)
        onset = min(input_frames - 1, max(1, onset))
        lag = (0.012 + rng.uniform(0.0, 0.055 * amount)) * SAMPLE_RATE
        latest = onset - max(1.0, lag)
        earliest = max(0.0, onset - history)
        semitones = (0, 7, -12, 12)[index % 4] * amount
        ratio = 2.0 ** (semitones / 12.0)
        length = min(preferred_length, math.floor((latest - earliest) / ratio) + 1)
        balance = float(rng.uniform(-0.35, 0.35) * amount)
        if length < 32:
            continue
        reverse = index % 4 != 3
        low = latest - (length - 1) * ratio
        first, last = (latest, low) if reverse else (low, latest)
        events.append(GrainEvent(onset, length, first, last, ratio, semitones, reverse, balance))
    return tuple(events)


def _fragments(audio: np.ndarray, settings: Settings,
               events: tuple[GrainEvent, ...]) -> np.ndarray:
    wet = np.zeros_like(audio)
    overlap = np.zeros(len(audio), dtype=np.float64)
    for event in events:
        # The entire span was available at onset. Linear reads never run through
        # a moving write head, so there is no read-wrap click or future access.
        positions = np.linspace(event.source_first_frame, event.source_last_frame, event.length_frames)
        low = np.floor(positions).astype(np.int64)
        fraction = (positions - low)[:, None]
        grain = audio[low] * (1.0 - fraction) + audio[low + 1] * fraction
        window = np.hanning(event.length_frames)
        gains = np.array([1.0 - max(event.balance, 0.0), 1.0 + min(event.balance, 0.0)])
        start, end = event.onset_frame, event.onset_frame + event.length_frames
        wet[start:end] += grain * window[:, None] * gains
        overlap[start:end] += window
    wet /= np.maximum(1.0, overlap)[:, None]
    mix = (0.82 * settings.amount) * _engagement(len(audio))
    output = audio * (1.0 - mix[:, None]) + wet * mix[:, None]
    # Windowing a captured asymmetric/DC transient can recreate subsonic energy.
    return _dc_filter(output, 20.0, SAMPLE_RATE)


def _chop_gains(frames: int, settings: Settings) -> np.ndarray:
    step_seconds = 60.0 / settings.bpm * 4.0 / settings.division
    duty = 0.58 - 0.13 * settings.amount
    edge = min(0.003, step_seconds * duty * 0.25)
    time_seconds = np.arange(frames, dtype=np.float64) / SAMPLE_RATE
    envelope = np.empty((frames, 2), dtype=np.float64)
    for channel, offset in enumerate((0.0, 0.5 * settings.amount)):
        phase = np.remainder(time_seconds / step_seconds + offset, 1.0) * step_seconds
        attack = np.clip(phase / edge, 0.0, 1.0)
        release = np.clip((duty * step_seconds - phase) / edge, 0.0, 1.0)
        gate = np.sin(np.pi * 0.5 * attack)**2 * np.sin(np.pi * 0.5 * release)**2
        envelope[:, channel] = 1.0 - settings.amount * _engagement(frames) * (1.0 - gate)
    return envelope


def render_core(samples: np.ndarray, settings: Settings = Settings(), stages: int = 3) -> RenderResult:
    """Render mono/stereo 44.1 kHz PCM; AMT0 is an exact, same-shape hard bypass.

    Nonzero renders have two channels and exactly .75 s of appended tail budget.
    Maximum input length is 12 s; BPM40..240 and divisions4/8/16/32 keep the
    precomputed event inventory below1024. No event can read future input.
    """
    started = time.perf_counter()
    if not isinstance(settings, Settings):
        raise TypeError("settings must be Settings")
    if isinstance(stages, bool) or not isinstance(stages, int) or stages not in (1, 2, 3):
        raise ValueError("stages must be 1, 2, or 3")
    if not isinstance(samples, np.ndarray) or samples.ndim != 2 or samples.shape[1] not in (1, 2):
        raise ValueError("samples must be a frames-by-1-or-2 ndarray")
    if samples.dtype.kind != "f" or not len(samples) or not np.isfinite(samples).all():
        raise ValueError("samples must be nonempty finite floating-point PCM")
    if len(samples) > round(MAX_INPUT_SECONDS * SAMPLE_RATE):
        raise ValueError("input exceeds 12 second audition budget")
    if _peak(samples) > 1.0:
        raise ValueError("input PCM must be in [-1, 1]")
    diagnostics: dict[str, Any] = {
        "model": SHAPE_CONSTANTS["model"], "sample_rate": SAMPLE_RATE,
        "settings": asdict(settings), "stages": stages,
        "input_frames": len(samples), "input_channels": samples.shape[1],
        "input_metrics": _metrics(samples), "bypass": settings.amount == 0.0,
        "shimmer": False, "feedback": False, "seamless_loop_claim": False,
        "streaming_causal": False,
        "grain_reads_before_event_onset": True,
        "resampling_fir_policy": "offline compensated FIR; lookahead retained",
    }
    if settings.amount == 0.0:
        diagnostics.update(output_frames=len(samples), output_channels=samples.shape[1],
                           tail_frames=0, guard_gain=1.0, pre_guard_peak=_peak(samples),
                           grain_events=[], event_schedule_sha256=hashlib.sha256(b"[]").hexdigest(),
                           output_metrics=_metrics(samples), runtime_seconds=time.perf_counter() - started)
        return RenderResult(samples, diagnostics)
    stereo = np.repeat(samples, 2, axis=1) if samples.shape[1] == 1 else samples
    padded = np.pad(stereo.astype(np.float64), ((0, TAIL_FRAMES), (0, 0)))
    audio = _fuzz(padded, settings.amount)
    stage_metrics = [{"stage": "octave_fuzz", **_metrics(audio)}]
    events = _schedule(len(samples), settings) if stages >= 2 else ()
    if stages >= 2:
        audio = _fragments(audio, settings, events)
        stage_metrics.append({"stage": "captured_fragments", **_metrics(audio)})
    if stages >= 3:
        audio *= _chop_gains(len(audio), settings)
        stage_metrics.append({"stage": "stereo_chop", **_metrics(audio)})
    # A finite filter release and Hann grains already decay. The final 4ms fade
    # is a file boundary guard, not a loop crossfade or an energy stabilizer.
    fade_frames = round(0.004 * SAMPLE_RATE)
    audio[-fade_frames:] *= np.linspace(1.0, 0.0, fade_frames)[:, None]
    pre_guard_peak = _peak(audio)
    guard_gain = min(1.0, PEAK_CEILING / pre_guard_peak) if pre_guard_peak > 0.0 else 1.0
    audio *= guard_gain
    if not np.isfinite(audio).all():
        raise FloatingPointError("non-finite core output")
    event_dicts = [asdict(event) for event in events]
    schedule_json = json.dumps(event_dicts, sort_keys=True, separators=(",", ":"))
    diagnostics.update(
        output_frames=len(audio), output_channels=2, tail_frames=TAIL_FRAMES,
        guard_gain=guard_gain, pre_guard_peak=pre_guard_peak,
        grain_events=event_dicts,
        event_schedule_sha256=hashlib.sha256(schedule_json.encode()).hexdigest(),
        clock_step_seconds=60.0 / settings.bpm * 4.0 / settings.division,
        clock_origin_frame=0, compensated_fir_latency_frames=0,
        stage_metrics=stage_metrics, output_metrics=_metrics(audio),
        runtime_seconds=time.perf_counter() - started,
    )
    return RenderResult(audio, diagnostics)
