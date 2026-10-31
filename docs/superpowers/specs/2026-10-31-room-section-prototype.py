"""ROOM, the drum-room rack section: the Python prototype behind the design brief
(docs/superpowers/specs/2026-10-31-room-section-design-brief.md).

Python harness, not Kotlin. Its numbers are measurements of this file; nothing here has been heard.

What it holds:
  spring()      Spring.kt ported line for line (max error 1.3e-7 against the Kotlin render of a THUMP snare)
  room()        the candidate ROOM: direct sound, image-source reflections to two bounces (jittered: walls are
                not mirrors), each bounce darkened by the wall, then a 16-line Hadamard delay network with
                Jot's per-line damping, fed at the second-bounce arrival; peak-matched like the rack
  ir_metrics()  the meters: when the tail turns dense (echo density), flutter (a regular repeat in the tail),
                late tonal peaks (a mode outliving its band), and RT60

Run:  python3 2026-10-31-room-section-prototype.py SOURCE_DIR [OUT_DIR]
SOURCE_DIR holds `<name>_dry.wav` files (44.1 kHz mono). The brief's numbers used THUMP's KICK, SNARE,
HAT_CLOSED and CLAP, SKIN's SNARE, and ForgeAuditionGenerator.chop of `reference/live3 room take.wav`,
each written with WavWriter from a scratch test.
"""
from __future__ import annotations

import json
import math
import os
import sys
import wave

import numpy as np
from scipy.signal import butter, lfilter, sosfilt

RATE = 44_100
MAX_TAIL_SECONDS = 1.0     # FxChain.MAX_CHAIN_TAIL_SECONDS
SPEED = 343.0              # ChamberRoom.SPEED_OF_SOUND
ORDER = 2                  # reflections up to two bounces (24 paths); the network takes over after
SPREAD = (0.015, 0.08)     # delay lines 15 to 80 ms at SIZE 0.5: shorter sang (tonal peaks), longer filled in late
WET_TRIM = 0.35            # the room against the hit: at the default a snare plays 0.5 dB louder at the same peak


# ------------------------------------------------------------------ small tools

def lin(x, lo, hi):
    return lo + (hi - lo) * x


def exp_map(x, lo, hi):
    return lo * (hi / lo) ** x


def one_pole(hz):
    return 1.0 - math.exp(-2.0 * math.pi * hz / RATE)


def highpass(x, hz):
    return sosfilt(butter(2, hz, "hp", fs=RATE, output="sos"), x)


def allpass(x, d, g):
    b = np.zeros(d + 1); b[0] = -g; b[d] = 1
    a = np.zeros(d + 1); a[0] = 1; a[d] = -g
    return lfilter(b, a, x)


def primes_near(targets):
    out = []
    for t in targets:
        p = int(t)
        while not (p > 2 and all(p % q for q in range(2, int(p ** .5) + 1)) and p not in out):
            p += 1
        out.append(p)
    return out


def hadamard16(v):
    v = v.copy()
    span = 1
    while span < 16:
        for g in range(0, 16, span * 2):
            a = v[g:g + span].copy(); b = v[g + span:g + 2 * span].copy()
            v[g:g + span] = a + b; v[g + span:g + 2 * span] = a - b
        span *= 2
    return v * 0.25   # orthonormal: H16 / 4


def rms(x):
    return float(np.sqrt(np.mean(np.asarray(x, float) ** 2)))


def crest(x):
    return float(np.max(np.abs(x)) / (rms(x) + 1e-12))


def read_wav(path):
    with wave.open(path) as w:
        n, ch, sw = w.getnframes(), w.getnchannels(), w.getsampwidth()
        raw = w.readframes(n)
    if sw == 2:
        x = np.frombuffer(raw, "<i2").astype(np.float32) / 32768
    elif sw == 3:
        b = np.frombuffer(raw, np.uint8).reshape(-1, 3)
        i = (b[:, 0].astype(np.int32) | (b[:, 1].astype(np.int32) << 8) | (b[:, 2].astype(np.int32) << 16))
        x = (np.where(i >= 1 << 23, i - (1 << 24), i) / float(1 << 23)).astype(np.float32)
    else:
        raise ValueError(f"{path}: {8 * sw}-bit WAV")
    return x.reshape(-1, ch).mean(axis=1)


def write_wav(path, x):
    y = np.clip(np.asarray(x, float), -1, 1)
    with wave.open(path, "w") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes((y * 32767).astype("<i2").tobytes())


# ------------------------------------------------------------------ SPRING, ported from Spring.kt

def spring(x, size=0.35, tone=0.55, mix=0.3, peak_match=True):
    x = np.asarray(x, np.float32)
    if mix <= 0.001:
        return x.copy()
    scale = lin(size, 0.5, 1.5)
    rt60 = exp_map(size, 0.25, 1.4)
    a = np.float32(one_pole(exp_map(tone, 1800.0, 9500.0)))
    n = len(x) + min(int(rt60 * RATE), int(1.2 * RATE))
    combs = [max(32, int(d * scale)) for d in (1687, 1601, 2053, 2251)]
    aps = [max(16, int(d * scale)) for d in (347, 113)]
    fb = [np.float32(10.0 ** (-3.0 * d / (rt60 * RATE))) for d in combs]
    cb = [np.zeros(d, np.float32) for d in combs]
    lp = [np.float32(0)] * 4
    ab = [np.zeros(d, np.float32) for d in aps]
    out = np.zeros(n, np.float32)
    g = np.float32(0.7)
    for f in range(n):
        dry = x[f] if f < len(x) else np.float32(0)
        wet = np.float32(0)
        for c in range(4):
            i = f % combs[c]
            fed = cb[c][i]
            wet += fed
            lp[c] = lp[c] + a * (fed - lp[c])
            cb[c][i] = dry + fb[c] * lp[c]
        wet *= np.float32(0.25)
        for k in range(2):
            i = f % aps[k]
            d = ab[k][i]
            ab[k][i] = wet + g * d
            wet = d - g * ab[k][i]
        out[f] = dry + np.float32(mix) * wet
    if peak_match:
        ip, op = np.max(np.abs(x)), np.max(np.abs(out))
        if op > 1e-9 and ip > 1e-9:
            out = np.clip(out * (ip / op), -1, 1)
    return out


def spring_ir(size, seconds=1.4):
    imp = np.zeros(int(0.01 * RATE), np.float32); imp[0] = 1
    y = spring(imp, size, mix=1.0, peak_match=False).astype(float)
    y[0] -= 1
    return highpass(y[: int(seconds * RATE)], 40.0)


# ------------------------------------------------------------------ ROOM

def geometry(size):
    lo, hi = np.array([2.4, 2.0, 2.2]), np.array([14.0, 10.0, 6.0])
    return lo * (hi / lo) ** size                     # a booth to a hall-sized live room, each side on a log scale


def absorption(walls):
    return exp_map(walls, 0.55, 0.06)                 # soft (curtains) to hard (tile)


def placement(dims, distance):
    src = np.array([dims[0] * 0.33, dims[1] * 0.45, 0.9])
    far = np.array([dims[0] * 0.85, dims[1] * 0.8, 1.6])
    span = np.linalg.norm(far - src)
    return src, src + (far - src) * ((0.4 + (span - 0.4) * distance) / span)   # the mic walks from 0.4 m out


def early(dims, src, mic, alpha, n, seed=7):
    r = math.sqrt(1 - alpha)
    rng = np.random.default_rng(seed)
    d0 = float(np.linalg.norm(src - mic))
    er = np.zeros((ORDER + 1, n))
    for nx in range(-ORDER, ORDER + 1):
        for ny in range(-ORDER, ORDER + 1):
            for nz in range(-ORDER, ORDER + 1):
                for px in (0, 1):
                    for py in (0, 1):
                        for pz in (0, 1):
                            b = abs(2 * nx - px) + abs(2 * ny - py) + abs(2 * nz - pz)
                            if b == 0 or b > ORDER:
                                continue
                            img = np.array([(1 - 2 * px) * src[0] + 2 * nx * dims[0],
                                            (1 - 2 * py) * src[1] + 2 * ny * dims[1],
                                            (1 - 2 * pz) * src[2] + 2 * nz * dims[2]])
                            d = float(np.linalg.norm(img - mic))
                            t = ((d - d0) / SPEED + rng.uniform(-1, 1) * 0.0004 * b) * RATE
                            i = int(t)
                            if 0 <= i < n - 1:
                                gain = r ** b / max(d, 0.25)
                                er[b, i] += gain * (1 - (t - i)); er[b, i + 1] += gain * (t - i)
    return er


def network(excite, size, rt_dc, hf_ratio):
    """16 lines, orthonormal Hadamard feedback, Jot's one-pole absorbent filter per line (RT at Nyquist =
    hf_ratio x RT at DC), so every mode in a band decays at the same rate."""
    L = primes_near(np.geomspace(*SPREAD, 16) * RATE * exp_map(size, 0.6, 1.6))
    g = np.array([10 ** (-3 * l / (rt_dc * RATE)) for l in L])
    b = np.clip([math.log(10) / 4 * math.log10(gi) * (1 - 1 / hf_ratio ** 2) for gi in g], 0.0, 0.9)
    bufs = [np.zeros(l) for l in L]; heads = [0] * 16
    state = np.zeros(16)
    sign = np.where(np.arange(16) % 3 == 0, -1.0, 1.0)
    out = np.zeros(len(excite))
    for f in range(len(excite)):
        old = np.array([bufs[i][heads[i]] for i in range(16)])
        state = g * (1 - b) * old + b * state
        out[f] = float(np.dot(sign, old)) * 0.25
        mixed = hadamard16(state) + excite[f] * sign * 0.25
        for i in range(16):
            bufs[i][heads[i]] = mixed[i]; heads[i] = (heads[i] + 1) % L[i]
    return out


_IRS = {}


def room_ir(size=0.4, walls=0.5, distance=0.5, seconds=1.2):
    """The wet response alone, relative to the direct arrival. ROOM is linear, so the Kotlin section can run
    the same taps and network on the signal directly; the prototype convolves for speed of writing."""
    key = (size, walls, distance, seconds)
    if key in _IRS:
        return _IRS[key]
    dims, alpha = geometry(size), absorption(walls)
    src, mic = placement(dims, distance)
    n = int(seconds * RATE)
    er = early(dims, src, mic, alpha, n)
    wall_hz = exp_map(walls, 3500.0, 14000.0)
    a = one_pole(wall_hz)
    out = np.zeros(n)
    for b in range(1, ORDER + 1):
        y = er[b]
        for _ in range(b):                            # each bounce darkens
            y = lfilter([a], [1, -(1 - a)], y)
        out += y
    V = dims.prod()
    S = 2 * (dims[0] * dims[1] + dims[0] * dims[2] + dims[1] * dims[2])
    rt60 = min(0.161 * V / (S * alpha), 0.9)          # Sabine, held under the rack's cap
    start = int(np.argmax(er[2] != 0)) if np.any(er[2]) else int(0.01 * RATE)
    feed = np.zeros(n); feed[start] = 1.0
    for d, gg in ((142, .7), (107, .7)):              # short diffusers only: a 379-sample one fluttered at 8.6 ms
        feed = allpass(feed, d, gg)
    late = highpass(network(feed, size, rt60, lin(walls, 0.25, 0.75)), 40.0)
    diffuse = math.sqrt(16 * math.pi / (S * alpha / (1 - alpha)))   # Sabine's diffuse level (shape, not measured)
    late *= diffuse / (np.sqrt(np.sum(late ** 2)) + 1e-12) * 0.5
    ir = out + late
    cap, fade = int(MAX_TAIL_SECONDS * RATE), int(0.05 * RATE)
    ir[cap - fade:cap] *= np.linspace(1, 0, fade); ir[cap:] = 0
    _IRS[key] = (ir, {"dims_m": [round(float(v), 2) for v in dims], "alpha": round(alpha, 3),
                      "sabine_rt60_s": round(rt60, 3), "mic_m": round(float(np.linalg.norm(src - mic)), 2)})
    return _IRS[key]


def room(x, size=0.4, walls=0.5, distance=0.5):
    x = np.asarray(x, np.float32)
    if distance <= 0.001:                             # DISTANCE 0 is the neutral: no room at all
        return x.copy()
    ir, _ = room_ir(size, walls, distance)
    src, mic = placement(geometry(size), distance)
    direct = 0.4 / max(float(np.linalg.norm(src - mic)), 0.4)
    n = len(x) + int(MAX_TAIL_SECONDS * RATE)
    out = np.zeros(n)
    out[: len(x)] += direct * x
    out += np.convolve(x, ir)[:n] * 0.4 * WET_TRIM
    out = highpass(out, 20.0)
    ip = np.max(np.abs(x))
    out = np.clip(out * (ip / np.max(np.abs(out))), -1, 1)
    above = np.nonzero(np.abs(out) > ip * 1e-3)[0]    # the tail ends 60 dB under the peak, as SPRING sizes its own
    end = min(len(out), max(len(x), int(above[-1]) + int(0.02 * RATE)))
    out = out[:end]
    f = min(int(0.01 * RATE), end)
    out[end - f:] *= np.linspace(1, 0, f)
    return out.astype(np.float32)


ROOMS = {
    "booth": dict(size=0.1, walls=0.3, distance=0.4), "default": dict(size=0.4, walls=0.5, distance=0.5),
    "live": dict(size=0.7, walls=0.7, distance=0.6), "soft": dict(size=0.4, walls=0.0, distance=0.5),
    "hard": dict(size=0.4, walls=1.0, distance=0.5), "close": dict(size=0.4, walls=0.5, distance=0.15),
    "far": dict(size=0.4, walls=0.5, distance=1.0), "big": dict(size=1.0, walls=0.6, distance=0.6),
}


# ------------------------------------------------------------------ meters

def echo_density_ms(ir, win_ms=20.0):
    """When the tail turns dense: Abel and Huang's normalised echo density first holds at 0.9 (ms after onset)."""
    w = int(win_ms * 1e-3 * RATE); hop = w // 4
    hann = np.hanning(w); hann /= hann.sum()
    ned = []
    for c in range(0, len(ir) - w, hop):
        seg = ir[c:c + w]
        sd = math.sqrt(np.sum(hann * seg ** 2))
        ned.append(0.0 if sd < 1e-12 else float(np.sum(hann * (np.abs(seg) > sd)) / 0.3173))
    first = int(np.argmax(np.abs(ir) > 1e-6))
    for i, v in enumerate(ned):
        if i * hop > first and v >= 0.9 and all(u >= 0.8 for u in ned[i:i + 8]):
            return (i * hop - first) / RATE * 1000
    return float("nan")


def flutter(ir, start_ms=60, end_ms=500):
    """Peak normalised autocorrelation of the tail at 8 to 120 ms lags. Diffuse noise reads about 0.04."""
    seg = ir[int(start_ms * 1e-3 * RATE): int(end_ms * 1e-3 * RATE)]
    seg = seg - np.mean(seg)
    ac = np.correlate(seg, seg, "full")[len(seg) - 1:]
    ac /= ac[0] + 1e-12
    lo, hi = int(0.008 * RATE), int(0.12 * RATE)
    return float(np.max(ac[lo:hi]))


def late_peak_db(ir, start_ms=250, end_ms=700):
    """The strongest late-tail peak over its 1/3-octave band's median, audible bands only (median within 30 dB of
    the loudest band's). Diffuse noise reads about 10 to 11 dB."""
    seg = ir[int(start_ms * 1e-3 * RATE): int(end_ms * 1e-3 * RATE)]
    db = 20 * np.log10(np.abs(np.fft.rfft(seg * np.hanning(len(seg)), 1 << 15)) + 1e-30)
    hz = np.fft.rfftfreq(1 << 15, 1 / RATE)
    rows = []
    for c in np.geomspace(200, 8000, 40):
        sel = (hz > c / 2 ** (1 / 6)) & (hz < c * 2 ** (1 / 6))
        if sel.sum() > 8:
            rows.append((float(np.median(db[sel])), float(np.max(db[sel]) - np.median(db[sel]))))
    top = max(r[0] for r in rows)
    return max(r[1] for r in rows if r[0] > top - 30)


def rt60(ir):
    e = np.cumsum((ir ** 2)[::-1])[::-1]
    e_db = 10 * np.log10(e / (e[0] + 1e-30) + 1e-30)
    i5, i25 = int(np.argmax(e_db < -5)), int(np.argmax(e_db < -25))
    return float(-60 / ((e_db[i25] - e_db[i5]) / ((i25 - i5) / RATE))) if i25 > i5 else float("nan")


def ir_metrics(ir):
    return {"dense_after_ms": round(echo_density_ms(ir), 1), "flutter": round(flutter(ir), 3),
            "late_peak_db": round(late_peak_db(ir), 1), "rt60_s": round(rt60(ir), 3)}


def main(src_dir, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    report = {"note": "Python harness, not Kotlin. Nothing heard.", "ir": {}, "rooms": {}, "renders": {}}
    rng = np.random.default_rng(0)
    t = np.arange(int(1.2 * RATE)) / RATE
    report["ir"]["diffuse_noise_0.55s"] = ir_metrics(rng.standard_normal(len(t)) * 10 ** (-3 * t / 0.55))
    for s, name in ((.1, "spring_small"), (.35, "spring_default"), (.4, "spring_mid"), (.8, "spring_big")):
        report["ir"][name] = ir_metrics(spring_ir(s))
    for name, m in ROOMS.items():
        ir, info = room_ir(**m)
        report["ir"]["room_" + name] = ir_metrics(ir)
        report["rooms"][name] = info
    for f in sorted(os.listdir(src_dir)):
        if not f.endswith("_dry.wav"):
            continue
        name = f[: -len("_dry.wav")]
        x = read_wav(os.path.join(src_dir, f))
        clips = {"dry": x, "spring_default": spring(x), "spring_mid": spring(x, 0.4, mix=0.4)}
        for r, m in ROOMS.items():
            clips["room_" + r] = room(x, **m)
        report["renders"][name] = {k: {"head_db": round(20 * math.log10(rms(v[:len(x)]) / rms(x)), 2),
                                       "tail_s": round((len(v) - len(x)) / RATE, 3), "crest": round(crest(v), 2)}
                                   for k, v in clips.items()}
        for k, v in clips.items():
            write_wav(os.path.join(out_dir, f"{name}_{k}.wav"), v)
    with open(os.path.join(out_dir, "report.json"), "w") as fh:
        json.dump(report, fh, indent=1)
    for k, v in report["ir"].items():
        print(f"{k:22s} {v}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "room-prototype-out")
