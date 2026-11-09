"""HALOBAR R1: independent Python acoustic sketch, not a SnipSnap engine.

Three bending-mode ratios (1:4:10) follow Carvalho, Debut & Antunes,
https://documentacion.sea-acustica.es/storage/publicaciones/AMS-0_010.pdf.
Xylophone uses the 1:3:6 family described in the publisher-indexed excerpt of
https://www.sciencedirect.com/science/article/abs/pii/S0003682X2400001X.
This is one tuning family, not a claim about every acoustic xylophone.
All gains, contact widths, decays, filtering and macro curves are SHAPE choices.
The source is a reduced modal model; no geometry, air/bar coupling, actual
motor, mallet collision solver, sampled instrument or physical tape is simulated.
"""
from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
import hashlib
import math
import numpy as np
from scipy import signal

RATE = 44100
OVERSAMPLE = 4
MODEL_VERSION = "halobar-r0.2"
PROCESSOR_VERSION = "halobar-r0.1"
SOURCE_VERSIONS = dict(vibraphone="halobar-r0.1", marimba="halobar-r0.1",
                       xylophone="halobar-r0.2")
BPM = 72.0  # Reference clock baked into every render, not host tempo sync.
DEFAULTS = dict(TUNE=.5, TOUCH=.4, RATE=.45, RETURN=.45, SPACE=.35,
                MOTION=.25, HOLD=.3)
DIVISIONS = (1.5, 1., .75, .5, .375, .25)  # In quarter-note beats, slow to fast.
DIVISION_NAMES = ("dotted quarter", "quarter", "dotted eighth", "eighth",
                  "dotted sixteenth", "sixteenth")


def settled(macros=None):
    """Unknown values are dropped and nonfinite controls revert to defaults."""
    result = DEFAULTS.copy()
    for k, v in (macros or {}).items():
        if k in result:
            try:
                v = float(v)
            except (TypeError, ValueError):
                continue
            if math.isfinite(v):
                result[k] = min(1., max(0., v))
    return result


def midi_hz(midi):
    return 440. * 2. ** ((midi - 69.) / 12.)


def note_for(midi, m):
    # Explicit MIDI is the audition's base note; TUNE .5 leaves it untouched.
    return midi + 24. * (m["TUNE"] - .5)


def division(m):
    i = min(len(DIVISIONS)-1, int(m["RATE"] * len(DIVISIONS)))
    return DIVISIONS[i], DIVISION_NAMES[i]


def seed_for(*parts):
    # Stand-in only. This is NOT the house Dsp.seedFor or its pinned LCG.
    digest = hashlib.sha256("|".join(map(str, parts)).encode()).digest()
    return int.from_bytes(digest[:8], "little")


@lru_cache(maxsize=128)
def mode_design(voice, hz, touch, hold, rate):
    """Small cached design only; audio is regenerated, not cached.

    Full key includes voice, fundamental, TOUCH, HOLD and sample rate. Cache is
    process-local to this immutable model version. MOTION is applied downstream.
    """
    if voice == "vibraphone":
        ratios = (1., 4., 10.)
        weights = (1., .47, .20)
        decays = (6.2, 1.8, .65)
        soft, hard = .0014, .00012
    elif voice == "marimba":
        ratios = (1., 4., 10.)
        weights = (1., .66, .34)
        decays = (1.65, .43, .16)
        soft, hard = .0023, .00018
    elif voice == "xylophone":
        ratios = (1., 3., 6.)
        weights = (1., 1.1, .55)
        decays = (.62, .18, .075)
        soft, hard = .00085, .00007
    else:
        raise ValueError(voice)
    # Larger bars keep a longer contact, without solving a stiff collision.
    width = soft * (hard / soft) ** touch * (261.625565 / hz) ** .65
    width = min(.004, max(.000065, width))
    n = max(5, int(round(width * rate)))
    contact = np.hanning(n)
    contact /= contact.sum()  # Unit impulse area, never normalize each strike.
    life = .5 + 1.8 * hold
    register = (261.625565 / hz) ** .18
    modes = []
    for ratio, gain, t60 in zip(ratios, weights, decays):
        f = hz * ratio
        # Leave a wide guard below the OUTPUT Nyquist, not just internal Nyquist.
        if f >= RATE * .44:
            continue
        radius = np.exp(-np.log(1000.) / (t60 * life * register * rate))
        omega = 2. * np.pi * f / rate
        # Unit modal impulse peak avoids low-note / oversampling gain blowup.
        b = np.array([0., radius * np.sin(omega)])
        a = np.array([1., -2. * radius * np.cos(omega), radius * radius])
        modes.append((gain, b, a))
    return contact, tuple(modes), width


def bar(voice, midi=60, macros=None, seconds=7., oversample=OVERSAMPLE,
        velocity=.78):
    m = settled(macros)
    rate = RATE * oversample
    n = int(round(seconds * rate))
    hz = midi_hz(note_for(midi, m))
    # Velocity changes both energy and contact hardness; the audible demo fixes it.
    effective_touch = np.clip(m["TOUCH"] + .35 * (velocity-.78), 0., 1.)
    contact, modes, width = mode_design(voice, hz, float(effective_touch), m["HOLD"], rate)
    force = np.zeros(n)
    force[:min(n, len(contact))] = contact[:min(n, len(contact))]
    body = np.zeros(n)
    send_body = np.zeros(n)
    for index, (gain, b, a) in enumerate(modes):
        component = gain * signal.lfilter(b, a, force)
        body += component
        # Protect the sustained root from coherent delayed cancellation in mono.
        # The source-aware send keeps more of the fast upper-mode articulation.
        send_body += (.35 if index == 0 else 1.) * component
    # A fixed reference gain preserves real changes in excitation and decay.
    body *= .63 * velocity
    send_body *= .63 * velocity
    if voice == "vibraphone":
        t = np.arange(n) / rate
        depth = .65 * m["MOTION"]
        motor = 2. + 5. * m["MOTION"]
        tremolo = 1. - .5 * depth + .5 * depth * np.cos(2.*np.pi*motor*t)
        body *= tremolo
        send_body *= tremolo
    # Quiet mallet/contact noise belongs only to the dry onset, not the echo send.
    # A new voice must not reseed the already auditioned voices or processor.
    rng = np.random.Generator(np.random.PCG64(seed_for(SOURCE_VERSIONS[voice], voice, midi)))
    base_n = int(RATE * .009)
    hit = rng.uniform(-1., 1., base_n)
    hit = signal.sosfilt(signal.butter(2, min(8500., hz*9.), fs=RATE, output="sos"), hit)
    hit *= np.hanning(base_n) * np.exp(-np.arange(base_n) / (RATE*.0018))
    # Generate the same bandlimited contact at every internal sample rate.
    hit = signal.resample_poly(hit, oversample, 1, window=("kaiser",8.6))
    hit_n = min(n,len(hit))
    dry = body.copy()
    noise_gain = (.025 + .08*m["TOUCH"]) if voice == "xylophone" else (.015 + .055*m["TOUCH"])
    dry[:hit_n] += noise_gain * velocity * hit[:hit_n]
    # Allow the modal onset to open the send, excluding repeated contact clicks.
    send = send_body * (-np.expm1(-np.arange(n) / (rate*.010)))
    return dry, send, dict(hz=hz, contact_ms=1000.*width, mode_count=len(modes),
                          source_version=SOURCE_VERSIONS[voice])


def echo_network(send, m, rate, feedback_override=None):
    """Single feedback delay plus read-only heads.

    Fixed integer delay per render has unit norm. Butterworth LP has |H|<=1;
    tanh(drive*x)/drive is 1-Lipschitz. Feedback gain is <= .84, so the recursive
    map contracts in L2. No limiter provides stability. No HP or variable read
    resampling is hidden inside that proof. The send has its own external HP.
    """
    beat = 60. / BPM
    spacing, _ = division(m)
    delay = max(1, int(round(beat * spacing * rate)))
    g = .12 + .72*m["RETURN"] if feedback_override is None else feedback_override
    cutoff = 5700. * (1900./5700.) ** m["RETURN"]
    sos = signal.butter(2, cutoff, fs=rate, output="sos")
    zi = np.zeros((len(sos), 2))
    q = np.zeros_like(send)
    drive = 1. + 2.5*m["RETURN"]
    # Blocks no longer than the delay make the recurrence causal and exact.
    for start in range(0, len(send), delay):
        stop = min(len(send), start+delay)
        if start < delay:
            q[start:stop] = send[start:stop]
        else:
            old = q[start-delay:stop-delay]
            filtered, zi = signal.sosfilt(sos, old, zi=zi)
            q[start:stop] = send[start:stop] + g*np.tanh(drive*filtered)/drive
    heads = (delay, delay + int(round(.5*beat*rate)), 2*delay)
    echo = np.zeros_like(send)
    for d, weight in zip(heads, (.62, .25, .13)):
        if d < len(send):
            echo[d:] += weight*q[:-d]
    return echo, q, dict(delay_frames=delay, delay_seconds=delay/rate,
                        head_frames=list(heads), feedback_gain=g,
                        loop_lowpass_hz=cutoff)


@lru_cache(maxsize=16)
def halo_kernel(rate):
    # A finite, coloured reflection cloud. It never returns to the delay loop.
    size = int(.9*RATE)
    rng = np.random.Generator(np.random.PCG64(seed_for(PROCESSOR_VERSION, "halo")))
    h = rng.uniform(-1., 1., size)
    h = signal.sosfilt(signal.butter(2, 4000., fs=RATE, output="sos"), h)
    t = np.arange(size)/RATE
    h *= np.exp(-t/.15) * np.minimum(t/.018, 1.)
    h /= np.sqrt(np.sum(h*h))
    # Fixed diffusion gain; normalization is design-time and outside feedback.
    factor = rate//RATE
    # A discrete convolution kernel represents h(t)*dt. Interpolate one fixed
    # reference IR and divide by the upsampling factor, preserving its gain.
    return signal.resample_poly(h*.24, factor, 1, window=("kaiser",8.6))/factor


def dub_stage(dry, send, macros=None, rate=RATE*OVERSAMPLE):
    m = settled(macros)
    send = signal.sosfilt(signal.butter(1, 85., "highpass", fs=rate, output="sos"), send)
    send = signal.sosfilt(signal.butter(2, 6800., fs=rate, output="sos"), send)
    echo, state, info = echo_network(send, m, rate)
    # Parallel fixed allpass filters create phase movement without Doppler reads
    # or a time-varying interpolation gain inside the feedback stability argument.
    phased = echo.copy()
    for center in (310., 970., 3100.):
        k = np.tan(np.pi*center/rate)
        a = (k-1.)/(k+1.)
        phased = signal.lfilter([a, 1.], [1., a], phased)
    t = np.arange(len(dry))/rate
    phase_mix = m["MOTION"] * (.32 + .22*np.sin(2.*np.pi*(.08+.18*m["MOTION"])*t))
    moving = echo*(1.-phase_mix) + phased*phase_mix
    halo = signal.fftconvolve(moving, halo_kernel(rate), mode="full")[:len(dry)]
    # This is explicitly a SPACE mix/send control, not a claimed physical parameter.
    wet = (1.55*m["SPACE"]**.65)*moving + (.75*m["SPACE"]**1.2)*halo
    return dry+wet, wet, info


def finish(raw, oversample=OVERSAMPLE, match=False):
    """House-order stand-in, not a bit-compatible house implementation.

    Butterworth band limit -> two 2:1 windowed-sinc steps -> mean removal ->
    20 Hz HP -> optional reference level -> 4ms tail fade. Scipy kernels differ
    from Kotlin and active RMS is not SnipSnap's loudness measurement.
    """
    rate = RATE*oversample
    filtered = signal.sosfilt(signal.butter(8, 19500., fs=rate, output="sos"), raw)
    out = filtered
    factor = oversample
    while factor > 1:
        out = signal.resample_poly(out, 1, 2, window=("kaiser", 8.6))
        factor //= 2
    out = out - out.mean()
    out = signal.sosfilt(signal.butter(1, 20., "highpass", fs=RATE, output="sos"), out)
    gain = 1.
    if match:
        rms = active_rms(out)
        peak = float(np.max(np.abs(out)))
        gain = min(.1834/max(rms, 1e-12), .95/max(peak, 1e-12))
        out = out*gain
    fade = min(len(out), round(.004*RATE))
    out[-fade:] *= np.linspace(1., 0., fade)
    return out, gain


def active_rms(x):
    # Fixed attack/body window, not whole-tail RMS. Explicit audition stand-in.
    x = x[:min(len(x), round(.8*RATE))]
    return float(np.sqrt(np.mean(x*x)))


@dataclass
class Render:
    raw_dry: np.ndarray
    raw_dub: np.ndarray
    matched_dry: np.ndarray
    matched_dub: np.ndarray
    info: dict


def render(voice, midi=60, macros=None, seconds=7., oversample=OVERSAMPLE):
    dry, send, info = bar(voice, midi, macros, seconds, oversample)
    dub, wet, echo_info = dub_stage(dry, send, macros, RATE*oversample)
    a, _ = finish(dry, oversample)
    b, _ = finish(dub, oversample)
    # Do the output filtering once. Matched and raw files then differ by gain only.
    gd = min(.1834/max(active_rms(a), 1e-12), .95/max(float(np.max(np.abs(a))), 1e-12))
    gw = min(.1834/max(active_rms(b), 1e-12), .95/max(float(np.max(np.abs(b))), 1e-12))
    c, d = a*gd, b*gw
    info.update(echo_info, dry_gain=gd, dub_gain=gw,
                raw_internal_peak=float(np.max(np.abs(dub))),
                reference_bpm=BPM, model_version=MODEL_VERSION,
                processor_version=PROCESSOR_VERSION)
    return Render(a, b, c, d, info)
