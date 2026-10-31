"""OBLITERATE shimmer pre-stage, Python listening prototype, not an Android effect.

Gesture: the dry strike stays present, an octave room blooms, then the approved
fuzz/fragments/chop core cuts that halo. The four-line Hadamard tank and two
complementary Hann read heads use standard equations; OpenSky.kt at d00051f9
was inspected for the same mechanism and its room/shimmer audition lessons.
This is not pitch-synchronous PSOLA or a model of a named pedal. The Luthier
proto_template.py workflow supplies the render/diagnostic/probe separation;
its mono pitched-engine autolevel and HOLD contract do not apply to this effect.

Why it is built this way:
- The additive wet branch leaves the source attack and bass on the dry path.
- Octave-up input makes the first room return distinct; half of each later
  return traverses the octave reader again, creating a rising feedback halo.
- Four convex lowpass poles precede every octave read to reduce folded highs.
- Hadamard row sums, the convex readers and a conservative feedback coefficient
  prove bounded tank state without a nonlinear limiter in the feedback loop.
- A fixed short tail keeps the experiment useful for finite MPC samples.

All shape constants are proposed, not owner-approved or pedal measurements.
"""
from __future__ import annotations
from dataclasses import dataclass
import math
import time
import numpy as np
from scipy import signal

RATE = 44_100
TAIL_FRAMES = 66_150
MAX_INPUT_SECONDS = 9.0
DELAYS = np.array([1493, 1733, 1999, 2381], dtype=np.int64)
HADAMARD = np.array([[1,1,1,1],[1,-1,1,-1],[1,1,-1,-1],[1,-1,-1,1]], dtype=np.float64) / 2
INJECTION = HADAMARD[:, :2]
FEEDBACK = .38
SHIFT_RETURN = .5
WINDOW = 4410
BASE_DELAY = 512
LOWPASS_HZ = 4200.0
MODEL = 'obliterate-shimmer-python-r1'
CORE_COMMIT = 'd00051f9bad01ef55faf0ed5b7f4aeabc877fde7'

@dataclass(frozen=True)
class Result:
    audio: np.ndarray
    wet: np.ndarray
    diagnostics: dict


def _validate(samples: np.ndarray, mix: float):
    if isinstance(mix, (bool, np.bool_)) or not math.isfinite(mix) or not 0 <= mix <= .5:
        raise ValueError('wet mix must be finite and in 0..0.5')
    x = np.asarray(samples)
    if x.dtype.kind != 'f':
        raise ValueError('floating-point PCM required')
    if x.ndim not in (1,2) or (x.ndim == 2 and x.shape[1] not in (1,2)):
        raise ValueError('one or two channels required')
    if not 0 < len(x) <= round(MAX_INPUT_SECONDS * RATE):
        raise ValueError('input must contain 1 frame to 9 seconds')
    if not np.all(np.isfinite(x)) or np.max(np.abs(x)) > 1:
        raise ValueError('finite PCM in -1..1 required')
    return x


def _lp4(x: np.ndarray, state: np.ndarray | None = None):
    # Each pole is a convex weighted history; its infinity-norm gain is <= 1.
    a = math.exp(-2 * math.pi * LOWPASS_HZ / RATE)
    if state is None:
        state = np.zeros((4, 1, x.shape[1]))
    out = x
    for pole in range(4):
        out, state[pole] = signal.lfilter([1-a], [1,-a], out, axis=0, zi=state[pole])
    return out, state


def _read_octave(history: np.ndarray, n: np.ndarray) -> np.ndarray:
    phase_frames = n % WINDOW
    phase = phase_frames / WINDOW
    other_frames = (phase_frames + WINDOW // 2) % WINDOW
    weight = .5 - .5 * np.cos(2 * math.pi * phase)
    out = np.zeros((len(n), history.shape[1]))
    for p, w in [(phase_frames,weight),(other_frames,1-weight)]:
        # At exactly +12 semitones the read heads advance two integer frames.
        # Integer geometry avoids a nearly-zero index flooring to -1 at startup.
        position = n - BASE_DELAY - WINDOW + p
        valid = position >= 0
        index = np.clip(position, 0, len(history)-1)
        out += history[index] * (w * valid)[:,None]
    return out


def octave_up(x: np.ndarray, antialias: bool = True) -> np.ndarray:
    x = np.asarray(x, dtype=np.float64)
    if x.ndim == 1: x = x[:,None]
    filtered = _lp4(x)[0] if antialias else x
    return _read_octave(filtered, np.arange(len(x)))


def _room(x: np.ndarray, pitched: bool, block_size: int = 256):
    if not 1 <= block_size <= BASE_DELAY or block_size > min(DELAYS):
        raise ValueError('block must precede all feedback reads')
    frames = len(x)
    # Highpass is outside the feedback loop; it only removes low-end wet excitation.
    excite = signal.sosfilt(signal.butter(2, 150, btype='highpass', fs=RATE, output='sos'), x, axis=0)
    excite = octave_up(excite) if pitched else _lp4(excite)[0]
    history = np.zeros((frames,4))
    shifted_history = np.zeros_like(history)
    returned = np.zeros_like(history)
    state = np.zeros((4,1,4))
    damping_state = np.zeros((1,4))
    damping = math.exp(-2 * math.pi * 6500 / RATE)
    loop_fraction = SHIFT_RETURN if pitched else 0.0
    state_peak = shifted_peak = 0.0
    for start in range(0, frames, block_size):
        end = min(frames,start+block_size)
        n = np.arange(start,end)
        d = np.zeros((len(n),4))
        for line, delay in enumerate(DELAYS):
            read = n-delay
            valid = read>=0
            d[valid,line] = history[read[valid],line]
        returned[start:end] = d
        damped,damping_state = signal.lfilter([1-damping],[1,-damping],d,axis=0,zi=damping_state)
        if pitched:
            shifted_history[start:end],state = _lp4(damped,state)
            shifted = _read_octave(shifted_history,n)
            shifted_peak = max(shifted_peak,float(np.max(np.abs(shifted))))
        else:
            shifted = np.zeros_like(d)
        feedback = ((1-loop_fraction)*damped + loop_fraction*shifted) @ HADAMARD.T
        written = excite[start:end] @ INJECTION.T + FEEDBACK * feedback
        history[start:end] = written
        state_peak = max(state_peak,float(np.max(np.abs(written))))
    # Stereo projection has row L1 norm 2. No output normalization or state clamp.
    wet = returned @ INJECTION
    return wet, {'statePeak':state_peak,'shiftedFeedbackPeak':shifted_peak,
                 'last100msStatePeak':float(np.max(np.abs(history[-4410:]))),
                 'tankInfinityContractionBound':float(np.max(np.sum(np.abs(HADAMARD),axis=1))*FEEDBACK),
                 'limiterInsideTank':False, 'blockFrames':block_size}


def render(samples: np.ndarray, mix: float = .15, pitched: bool = True) -> Result:
    before = time.perf_counter()
    original = _validate(samples,mix)
    if not isinstance(pitched,(bool,np.bool_)): raise ValueError('pitched must be boolean')
    common={'model':MODEL,'sampleRate':RATE,'inputFrames':len(original),'mix':mix,'pitched':bool(pitched),
            'tailFrames':0 if mix==0 else TAIL_FRAMES,'inputPeak':float(np.max(np.abs(original))),
            'intervalSemitones':12 if pitched else 0,'feedback':FEEDBACK,'shiftReturnFraction':SHIFT_RETURN if pitched else 0.,
            'delayFrames':DELAYS.tolist(),'grainWindowFrames':WINDOW,'minimumReadLagFrames':BASE_DELAY,
            'feedbackAntialias':'four convex one-pole lowpasses at 4200 Hz before each octave read; not alias-free',
            'tailPolicy':'1.5 seconds, final 10 ms fade on wet branch only',
            'dryPolicy':'unity dry plus mix times wet, then linked attenuation-only 0.98 peak guard'}
    if mix==0:
        return Result(original,np.zeros_like(original),{**common,'bypass':True,'guardGain':1.,'hostRenderMs':(time.perf_counter()-before)*1000})
    source = original[:,None] if original.ndim==1 else original
    source = np.repeat(source,2,axis=1) if source.shape[1]==1 else source
    padded = np.pad(source.astype(np.float64),((0,TAIL_FRAMES),(0,0)))
    wet,stats = _room(padded,pitched)
    pre_fade_tail_peak = float(np.max(np.abs(wet[-4410:])))
    wet[-441:] *= np.linspace(1.,0.,441)[:,None]
    contribution = mix * wet
    out = padded + contribution
    peak = float(np.max(np.abs(out)))
    gain = min(1.,.98/max(peak,1e-30))
    out *= gain; contribution *= gain
    return Result(out,contribution,{**common,**stats,'bypass':False,'guardGain':gain,
        'preGuardPeak':peak,'outputPeak':float(np.max(np.abs(out))),
        'wetLast100msPeakBeforeFade':pre_fade_tail_peak,
        'wetPeak':float(np.max(np.abs(contribution))), 'hostRenderMs':(time.perf_counter()-before)*1000})
