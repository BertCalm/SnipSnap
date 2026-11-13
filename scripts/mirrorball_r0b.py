#!/usr/bin/env python3
"""MIRRORBALL round 0b: punch and character, after the owner heard round 0 as "kind of basic and not very punchy or
character rich" (2026-11-12).

Python harness, not Kotlin. Usage:
    python3 scripts/mirrorball_r0b.py <round-0 sources> <retuned sources> <out dir>

What round 0 measured as basic (scripts/mirrorball_r0.py output, 0 to 300 ms):
  * the kick was nearly a pure sub sine: fundamental under 60 Hz, everything above 250 Hz 56 to 80 dB down,
    and no beater click at all (1.5 to 4 kHz about 80 dB down);
  * the tom was a pure sine at 100 to 250 Hz;
  * the amp's speaker took 10 to 12 dB off the snare's and hats' top (8 to 16 kHz);
  * the compressor and the clean amp added almost no harmonics.

What round 0b adds, in the order a 1970s record was made:
  1. The drums themselves are retuned: the kick up to about 60 Hz with a hard beater, the snare snappier, the
     tom with a stick attack (all SKIN settings; see the spec).
  2. PRINT, the drum as it was recorded before any pedal: console EQ (the research note's thump at 60 to 80 Hz
     and beater at 2 to 3 kHz on the kick, body and a high shelf on the snare, stick attack on the tom, the
     hats' harsh top rolled off), then hot tape: asymmetric saturation at 4x, which turns a sine kick into a
     kick with harmonics a small speaker can play.
  3. PRESS in the pedal's parallel mode: the dry hit plus a crushed copy (all buttons in, fastest attack and
     release) under it. The dry copy keeps the transient; the crushed copy fills the body in behind it.
  4. COMBO pushed harder, with the bright speaker on everything but the kick and tom.
"""
from __future__ import annotations

import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mirrorball_r0 as r0  # noqa: E402
from mirrorball_r0 import RATE, OS, filt, up, down, peak, finish, read_wav, write_wav, press, reel, combo, SIXTEENTH, REEL_COMMON, loudness  # noqa: E402

# Console EQ per drum: (kind, Hz, Q, dB). Shapes from the owner's research note, not measured on a record.
CONSOLE = {
    "kick":  [("peak", 68, 1.1, +6.0), ("peak", 380, 1.2, -6.0), ("peak", 3000, 0.9, +11.0)],
    "snare": [("peak", 210, 1.0, +4.0), ("peak", 700, 1.2, -3.0), ("hshelf", 5500, .7, +6.0)],
    "tom":   [("peak", 110, 1.0, +3.0), ("peak", 420, 1.2, -4.0), ("peak", 3500, 1.2, +7.0)],
    "hatc":  [("hp", 350, .7, 0.0), ("peak", 11000, .9, -2.5)],
    "hato":  [("hp", 350, .7, 0.0), ("peak", 11000, .9, -2.5)],
    "clap":  [("peak", 1200, .9, +3.0), ("hshelf", 6000, .7, +3.0)],
    "tamb":  [("hp", 400, .7, 0.0)],
    "conga": [("peak", 3200, 1.2, +5.0)],
}


def beater(x: np.ndarray, level: float, seed: int, velocity: float = 1.0) -> np.ndarray:
    """A felt beater on the batter head: a 2 to 3 ms click of band-limited noise at the very front of the kick.

    SKIN's kick has almost none above 1.5 kHz (measured), and a disco kick's identity is that click. The noise is
    seeded per take, so takes differ at the attack; a softer hit gets a quieter and duller click."""
    rng = np.random.default_rng(seed)
    n = int(.004 * RATE)
    t = np.arange(n) / RATE
    burst = rng.standard_normal(n) * np.exp(-t / (.0012 + .0008 * (1 - velocity)))
    burst = filt(filt(burst, "hp", 1800, .7), "lp", 3000 + 2500 * velocity, .7)
    y = x / max(peak(x), 1e-12)
    y = y.copy()
    y[:n] += level * velocity * burst / max(peak(burst), 1e-12)
    return y


def print_(x: np.ndarray, pad: str, hot: float) -> np.ndarray:
    """The drum as recorded: console EQ, then tape driven by `hot` (0 is clean, 1 is slammed). Runs at 4x."""
    y = x / max(peak(x), 1e-12)
    for kind, hz, q, g in CONSOLE[pad]:
        y = filt(y, kind, hz, q, RATE, g)
    if hot <= 0:
        return y
    u = up(y / max(peak(y), 1e-12)) * (1 + 5 * hot)
    # Tape: asymmetric (a little even harmonic), soft, and it rounds off what goes over.
    t = np.where(u >= 0, np.tanh(u), np.tanh(0.8 * u) / 0.8)
    t = t - np.mean(t)
    return down(t)[: len(x)]


def parallel_press(x: np.ndarray, base: dict, blend: float) -> np.ndarray:
    """The pedal's parallel mode: the dry hit plus a crushed copy (all buttons, fastest knobs) under it."""
    if blend <= 0:
        return press(x, **base)
    p = peak(x)
    clean = press(x, **base)
    smash = press(x, input_db=24, attack=1.0, release=1.0, ratio="ALL")
    smash *= p / max(peak(smash), 1e-12)
    y = clean + blend * smash
    return y * (p / max(peak(y), 1e-12))


# Per drum: compressor, echo send. The kick and tom keep the darker speaker; everything else gets the bright one.
PADS = {
    "kick":  dict(press=dict(input_db=12, attack=0.0, release=0.85, ratio="8"), reel=None, cab=6500),
    "snare": dict(press=dict(input_db=14, attack=0.55, release=0.80, ratio="4"), reel=dict(heads=(1,), mix=0.16, repeats=0.15), cab=8500),
    "tom":   dict(press=dict(input_db=12, attack=0.10, release=0.70, ratio="8"), reel=dict(heads=(2,), mix=0.20, repeats=0.25), cab=6500),
    "hatc":  dict(press=dict(input_db=10, attack=0.60, release=0.60, ratio="4"), reel=dict(heads=(1, 2), mix=0.28, repeats=0.32), cab=8500),
    "hato":  dict(press=dict(input_db=10, attack=0.60, release=0.55, ratio="4"), reel=dict(heads=(1, 2), mix=0.22, repeats=0.28), cab=8500),
    "clap":  dict(press=dict(input_db=14, attack=0.70, release=0.70, ratio="4"), reel=dict(heads=(1, 2), mix=0.20, repeats=0.22), cab=8500),
    "tamb":  dict(press=dict(input_db=12, attack=0.65, release=0.60, ratio="4"), reel=dict(heads=(1, 2), mix=0.22, repeats=0.28), cab=8500),
    "conga": dict(press=dict(input_db=10, attack=0.50, release=0.60, ratio="4"), reel=dict(heads=(2,), mix=0.16, repeats=0.18), cab=6500),
}

# The candidates: how hot the print, how much smash under the hit, how hard the amp.
CANDIDATES = {
    "punch": dict(hot=0.45, blend=0.45, drive=2.6, sag=0.45),
    "hot":   dict(hot=0.85, blend=0.75, drive=5.0, sag=0.60),
}


def chain(x: np.ndarray, pad: str, cand: dict, seed: int = 1, velocity: float = 1.0) -> np.ndarray:
    cfg = PADS[pad]
    if pad == "kick":
        x = beater(x, .55, seed, velocity)
    y = print_(x, pad, cand["hot"])
    y = parallel_press(y, cfg["press"], cand["blend"])
    if cfg["reel"]:
        y = reel(y, **cfg["reel"], **REEL_COMMON)
    return combo(y, drive=cand["drive"], sag=cand["sag"], cab_hz=cfg["cab"])


BANDS = [(30, 60), (60, 100), (100, 250), (250, 600), (600, 1500), (1500, 4000), (4000, 8000), (8000, 16000)]


def bands(x: np.ndarray) -> list[float]:
    seg = x[: int(.3 * RATE)]
    X = np.abs(np.fft.rfft(seg * np.hanning(len(seg)))) ** 2
    f = np.fft.rfftfreq(len(seg), 1 / RATE)
    tot = X.sum()
    return [round(10 * math.log10(X[(f >= a) & (f < b)].sum() / tot + 1e-12), 1) for a, b in BANDS]


def body_db(x: np.ndarray) -> float:
    """How much of the hit is still there 20 to 80 ms in, against its peak: higher is a fatter, punchier body."""
    return round(20 * math.log10(np.sqrt(np.mean(x[int(.02 * RATE): int(.08 * RATE)] ** 2)) / max(peak(x), 1e-12) + 1e-12), 1)


def main(src0: str, src2: str, dst: str) -> int:
    os.makedirs(dst, exist_ok=True)
    rep: dict = {"harness": "Python, not Kotlin", "bands_hz": BANDS, "pads": {}}
    r0_pads = {"kick": "kick", "snare": "snare", "tom": "tom", "hatc": "hatc", "hato": "hato"}
    for pad in PADS:
        dry = read_wav(os.path.join(src2, f"{pad}_v100_t0.wav"))
        out = {"dry": dry}
        if pad in r0_pads:
            out["round0"] = r0.chain(read_wav(os.path.join(src0, f"{pad}_v100_t0.wav")), pad)
        for name, cand in CANDIDATES.items():
            out[name] = chain(dry, pad, cand)
        out["punch_soft"] = chain(read_wav(os.path.join(src2, f"{pad}_v45_t0.wav")), pad, CANDIDATES["punch"], velocity=.45)
        out["punch_take2"] = chain(read_wav(os.path.join(src2, f"{pad}_v100_t1.wav")), pad, CANDIDATES["punch"], seed=2)
        rep["pads"][pad] = {}
        for k, v in out.items():
            f = finish(v)
            write_wav(os.path.join(dst, f"{pad}-{k}.wav"), f)
            rep["pads"][pad][k] = dict(bands=bands(f), body_db=body_db(f))
    grooves(src0, src2, dst)
    with open(os.path.join(dst, "report.json"), "w") as fh:
        json.dump(rep, fh, indent=1)
    for pad, d in rep["pads"].items():
        for k, v in d.items():
            print(f"{pad:6s}{k:12s} body {v['body_db']:6.1f}  bands " + " ".join(f"{b:6.1f}" for b in v["bands"]))
    return 0


def grooves(src0: str, src2: str, dst: str) -> None:
    """Two bars at 120 BPM, each pad printed on its own as a sampler plays it, takes alternating."""
    bar = 16 * SIXTEENTH
    n = int((2 * bar + 1.2) * RATE)
    pattern = []
    for b in range(2):
        o = b * 16
        for q in range(4): pattern.append(("kick", o + 4 * q, 100, 1.0))
        for q in (1, 3): pattern.append(("snare", o + 4 * q, 100, .8)); pattern.append(("clap", o + 4 * q, 100, .45))
        for e in range(8): pattern.append(("hato" if e % 2 else "hatc", o + 2 * e, 100, .42 if e % 2 else .32))
        for s in range(16):
            if s % 2 == 1: pattern.append(("tamb", o + s, 45, .22))
        pattern += [("conga", o + 3, 100, .35), ("conga", o + 6, 45, .3), ("conga", o + 11, 100, .35)]
    pattern += [("tom", 28, 100, .6), ("tom", 29, 100, .55), ("tom", 30, 100, .6), ("tom", 31, 100, .65)]

    def render(mode: str) -> np.ndarray:
        cache: dict = {}
        mix = np.zeros(n)
        count: dict = {}
        for pad, s, v, gain in pattern:
            if mode == "round0" and pad not in ("kick", "snare", "tom", "hatc", "hato"):
                continue
            t = count.get((pad, v), 0) % 2
            count[(pad, v)] = count.get((pad, v), 0) + 1
            key = (pad, v, t)
            if key not in cache:
                if mode == "round0":
                    x = r0.chain(read_wav(os.path.join(src0, f"{pad}_v{v}_t{t}.wav")), pad)
                elif mode == "dry":
                    x = read_wav(os.path.join(src2, f"{pad}_v{v}_t{t}.wav"))
                else:
                    x = chain(read_wav(os.path.join(src2, f"{pad}_v{v}_t{t}.wav")), pad, CANDIDATES[mode], seed=1 + t, velocity=v / 100)
                cache[key] = finish(x, .12)
            x = cache[key] * gain
            i = int(s * SIXTEENTH * RATE)
            m = min(len(x), n - i)
            mix[i: i + m] += x[:m]
        return finish(mix, .16, cap=False)

    for mode in ("round0", "dry", "punch", "hot"):
        write_wav(os.path.join(dst, f"groove-{mode}.wav"), render(mode))


if __name__ == "__main__":
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(*sys.argv[1:]))
