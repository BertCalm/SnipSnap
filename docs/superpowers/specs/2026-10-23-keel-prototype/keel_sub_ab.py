"""KEEL sub shapes, A/B for the owner's ear (2026-10-26).

The owner heard WEIGHT 0, .7 and 1 as the same sound. Measured: the as-built sine sub sits
2 to 16 dB under the note's fundamental at 23 to 46 Hz, below what ears and headphones
reproduce. This renders, per note, five clips that differ ONLY in the sub:

  none      WEIGHT 0
  sine      the as-built shape, a sine
  soft      a softly clipped sine, tanh(3 x)/tanh(3)
  square    a divided-down square (Ogre's flip-flop), band-limited, two one-pole low-passes at
            8x the sub's pitch

Each sub is calibrated in the output so its fundamental equals the note's fundamental over
0.15 to 0.6 s (the as-built sine at WEIGHT 1 measured 1 to 4 dB under that in these clips,
so a separate "as built" clip would be nearly the same as "sine").

Every clip keeps the design's grow law (35% to 100% as the note settles), the low fade under
20 Hz and the borrowed source envelope. Clips are levelled the Kotlin way, with the house
Loudness measure (loudest 200 ms after a partial 120 Hz low cut), so adding a sub does not
turn the note down the way plain RMS levelling would.
"""
from __future__ import annotations

import json
import math
import os
import sys

import numpy as np
from scipy import signal

import keel_proto as kp
import snipkit as sk
import probes as pr

RATE = sk.RATE
INTERNAL = sk.INTERNAL


def house_loudness(x: np.ndarray, rate: int = RATE) -> float:
    """Loudness.of: a one-pole low-pass at 120 Hz, 70% of it subtracted, then the RMS of the
    loudest 200 ms window at a 50 ms hop."""
    a = 1.0 - math.exp(-2.0 * math.pi * 120.0 / rate)
    lp = signal.lfilter([a], [1.0, -(1.0 - a)], x)
    f = x - 0.7 * lp
    win, hop = int(0.2 * rate), int(0.05 * rate)
    best = 0.0
    for s in range(0, max(1, len(f) - win + 1), hop):
        best = max(best, float(np.mean(f[s:s + win] ** 2)))
    return math.sqrt(best)


def finish_house(raw: np.ndarray) -> np.ndarray:
    y = sk.band_limit(raw, INTERNAL)
    y = sk.decimate4(y)
    y = sk.dc_block(y, RATE)
    loud = house_loudness(y)
    if loud > 1e-9:
        y = y * (sk.MELODIC_LOUDNESS_TARGET / loud)
    p = float(np.max(np.abs(y)))
    if p > 0.99:
        y = y * (0.99 / p)
    return sk.fade_tail(y, RATE)


def fundamental_amplitude(x: np.ndarray, phase_cycles: np.ndarray, frm: int, to: int) -> float:
    """Amplitude of the component locked to `phase_cycles`, by synchronous demodulation."""
    c = np.mean(x[frm:to] * np.exp(-2j * math.pi * phase_cycles[frm:to]))
    return 2.0 * abs(c)


def square_shape(sub_phase: np.ndarray, sub_hz: np.ndarray) -> np.ndarray:
    dt = sub_hz / INTERNAL
    sq = kp.polyblep_saw(sub_phase, dt) - kp.polyblep_saw(sub_phase + 0.5, dt)
    cutoff = 8.0 * sub_hz
    return kp.closing_lowpass(sq, cutoff, INTERNAL, order=2)


def soft_shape(sub_phase: np.ndarray) -> np.ndarray:
    return np.tanh(3.0 * np.sin(2.0 * math.pi * sub_phase)) / math.tanh(3.0)


def render(voice: str, macros: dict, midi: int, variant: str) -> tuple[np.ndarray, dict]:
    """keel_proto.render with the sub swapped; everything before the sub is unchanged."""
    m = {**kp.MACROS, **macros}
    hz = kp.hz_for(midi)
    length = kp.length_seconds(m)
    n = int(1.15 * length * INTERNAL)
    s = kp.settle_curve(n, INTERNAL, kp.half_life_seconds(m))
    f, phase = kp.pitch_track(hz, m["SLIDE"], n, INTERNAL)
    seed = sk.seed_for("KEEL", voice, midi)
    src = kp.VOICE_FN[voice](m, hz, n, INTERNAL, s, f, phase, seed)
    src /= max(1e-9, np.max(np.abs(src))) / 0.8
    src = kp.grit(src, m["GRIT"])
    env = np.minimum(1.0, kp.source_envelope(src, INTERNAL))
    grow = 0.35 + 0.65 * (1.0 - s)
    sub_hz = f / 2.0
    low_fade = np.clip((sub_hz - 20.0) / 10.0, 0.0, 1.0)
    sub_phase = phase / 2.0
    amp = kp.amp_env(n, INTERNAL, length)
    info = {}
    if variant == "none":
        sub = np.zeros(n)
    else:
        shape = {"sine": lambda: np.sin(2.0 * math.pi * sub_phase),
                 "soft": lambda: soft_shape(sub_phase),
                 "square": lambda: square_shape(sub_phase, sub_hz)}[variant]()
        unit = grow * low_fade * shape * env
        # The same measurement the report makes, so the calibration reads 0 dB there.
        gain = 10.0 ** ((band_amp_db(src * amp, hz, 0.15, 0.6, INTERNAL) - band_amp_db(unit * amp, hz / 2, 0.15, 0.6, INTERNAL)) / 20.0)
        info["gain"] = gain
        sub = gain * unit
    mix = (src + sub) * amp
    return mix, info


def band_amp_db(y: np.ndarray, hz: float, frm: float, to: float, rate: int = RATE) -> float:
    a, b = int(frm * rate), int(to * rate)
    seg = y[a:b] * np.hanning(b - a)
    spec = np.abs(np.fft.rfft(seg))
    fr = np.fft.rfftfreq(b - a, 1.0 / rate)
    m = (fr > hz * 0.94) & (fr < hz * 1.06)
    return 20.0 * math.log10(float(spec[m].max()) + 1e-12)


def main(out_dir: str) -> int:
    os.makedirs(out_dir, exist_ok=True)
    notes = [("UPRIGHT", 36, "C2"), ("REDUCE", 42, "Fs2")]
    variants = ["none", "sine", "soft", "square"]
    macros = {"LENGTH": 0.8}
    report = []
    for voice, midi, name in notes:
        hz = kp.hz_for(midi)
        for v in variants:
            raw, info = render(voice, {**macros, "WEIGHT": 0.0 if v == "none" else 1.0}, midi, v)
            one = finish_house(raw)
            gap = np.zeros(int(0.2 * RATE))
            clip = np.concatenate([one, gap, one, gap])
            path = os.path.join(out_dir, f"{voice.lower()}_{name}_{v}.wav")
            sk.write_wav(path, clip)
            fund = band_amp_db(one, hz, 0.15, 0.6)
            row = {"voice": voice, "note": name, "variant": v,
                   "sub_fund_re_note_dB": round(band_amp_db(one, hz / 2, 0.15, 0.6) - fund, 1),
                   "sub_3rd_re_note_dB": round(band_amp_db(one, 1.5 * hz, 0.15, 0.6) - fund, 1),
                   "peak": round(float(np.max(np.abs(one))), 3),
                   "house_loudness": round(house_loudness(one), 4)}
            hz25, c25 = pr.pitch(one, RATE, lo_hz=25.0, hi_hz=1000.0, window_seconds=0.25, start_seconds=0.35)
            row["detector_floor25_hz"] = round(hz25, 1)
            report.append(row)
            print(json.dumps(row))
    with open(os.path.join(out_dir, "report.json"), "w") as fh:
        json.dump(report, fh, indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "out_sub_ab"))
