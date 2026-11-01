"""KIT FROM ANYTHING prototype: one captured sound becomes sixteen slots, in a key, in a genre.

Python harness, not Kotlin. The one Kotlin thing in the loop is the real classifier: every drum
slot this writes is judged by `snipsnap classify` (the CLI jar), because the repo's own rules
decide what a kick, a snare, a clap and a hat are. Nothing here has been heard.

The slot plan (see 2026-11-01-kit-from-anything-design-brief.md). Purist: every pad is the
source and only the source, transformed.

  A01 KICK     the source's forced cycle sung at the genre's kick pitch with a pitch drop,
               low-passed, the source's own first 8 ms as the click
  A02 SNARE    the source whitened (its own colour divided out) as the noise, decaying; the
               forced cycle at the genre's snare tune as the body
  A03 HAT CL   the source sped up (the sampler trick: faster is higher), high-passed hard, short
  A04 HAT OP   the same, long
  A05 CLAP     four bursts of the whitened source 12 ms apart, then its tail
  A06 RIDE     the sped-up source band-passed, long, with a short cycle ping as the bell
  A07 CRASH    the sped-up source paulstretched into a wash, high-passed, under 1.5 s
  A08 SIREN    the source's cycle with a pitch trajectory (a drop, a rise-and-fall, a climb)
  A09..A12 BASS  the QUARRY engine (2026-10-31 brief) at the key's root, third, fifth, octave, low
  A13..A16 TONE  the same an octave and a half up, with the grain field open

The genre table (GENRES) is "shape": every number is derived from the house's own THUMP preset
rows for BOOM BAP and HOUSE (ThumpPresets.kt) and from HOUSE's WAREHOUSE / PEAK TIME corner for
TECHNO, translated into milliseconds and hertz. None has been heard.

Run from the repository root (needs cli/build/libs/snipsnap.jar, from ./gradlew :cli:snipsnapJar):
  python3 docs/superpowers/specs/2026-11-01-kit-from-anything-prototype.py <out_dir>
"""
from __future__ import annotations

import importlib.util
import json
import os
import re
import subprocess
import sys

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, ".claude", "skills", "luthier", "scripts"))
import snipkit as sk  # noqa: E402
import probes as pr   # noqa: E402

_spec = importlib.util.spec_from_file_location("quarry", os.path.join(HERE, "2026-10-31-quarry-engine-prototype.py"))
quarry = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(quarry)

RATE = sk.RATE
JAR = os.path.join(ROOT, "cli", "build", "libs", "snipsnap.jar")
LOOP_MIN_SECONDS = 1.5          # Classifier.LOOP_MIN_SECONDS: anything longer is a LOOP, so every pad stays under

SLOTS = ["KICK", "SNARE", "HAT_CLOSED", "HAT_OPEN", "CLAP", "RIDE", "CRASH", "SIREN",
         "BASS1", "BASS3", "BASS5", "BASS8", "TONE1", "TONE3", "TONE5", "TONE8"]
# what the classifier is asked to agree with; the rest are tagged by the assembler, not found
JUDGED = {"KICK": "KICK", "SNARE": "SNARE", "HAT_CLOSED": "HAT_CLOSED", "HAT_OPEN": "HAT_OPEN", "CLAP": "CLAP"}

SCALES = {"MINOR": [0, 3, 7, 12], "MAJOR": [0, 4, 7, 12]}   # root, third, fifth, octave (Scale.MINOR / MAJOR)

GENRES = {
    # BOOM BAP: THUMP rows DUSTY BOOM..TAPE THUMP (kick TUNE .16-.34, DECAY .37-.53, CLICK low, DRIVE .35-.55),
    # DEEP ROOM..BACKBEAT (snare TUNE .12-.38, DECAY .30-.75), RAGGA TICK..FAST TAP (hat DECAY .05-.30), *_WASH .42
    "BOOM_BAP": dict(bpm=92, kick_hz=52.0, kick_drop=2.2, kick_drop_ms=40.0, kick_decay_ms=280.0, kick_click=0.25,
                     kick_lp_hz=110.0, drive=0.35, snare_hz=190.0, snare_decay_ms=230.0, snare_bright=0.45,
                     clap_decay_ms=180.0, hat_closed_ms=70.0, hat_open_ms=380.0, hat_speed=5.0, ride_decay_ms=700.0,
                     crash_sec=1.3, siren=("drop", 0.45, 7.0), bass_decay_ms=450.0, bass_lp_hz=900.0, tone_decay_ms=800.0),
    # HOUSE: FOUR FLOOR..PEAK TIME (kick TUNE .33-.51, SWEEP .48-.68, DECAY .14-.30, CLICK .38-.62, DRIVE .48-.68),
    # CRACKED CLAY..PICCOLO (snare TUNE .35-.80, DECAY .18-.55, TONE .4-.72), CLUB TICK..PEAK TAP (hat .35-.60), .47-.67
    "HOUSE": dict(bpm=124, kick_hz=48.0, kick_drop=3.0, kick_drop_ms=25.0, kick_decay_ms=240.0, kick_click=0.45,
                  kick_lp_hz=120.0, drive=0.45, snare_hz=220.0, snare_decay_ms=170.0, snare_bright=0.6,
                  clap_decay_ms=220.0, hat_closed_ms=90.0, hat_open_ms=480.0, hat_speed=6.0, ride_decay_ms=800.0,
                  crash_sec=1.4, siren=("risefall", 0.8, 12.0), bass_decay_ms=280.0, bass_lp_hz=1200.0, tone_decay_ms=900.0),
    # TECHNO: HOUSE's WAREHOUSE / PEAK TIME corner pushed (shortest DECAY .14, most CLICK .62, most DRIVE .68)
    "TECHNO": dict(bpm=132, kick_hz=50.0, kick_drop=4.0, kick_drop_ms=18.0, kick_decay_ms=170.0, kick_click=0.6,
                   kick_lp_hz=115.0, drive=0.6, snare_hz=240.0, snare_decay_ms=120.0, snare_bright=0.7,
                   clap_decay_ms=150.0, hat_closed_ms=50.0, hat_open_ms=300.0, hat_speed=7.0, ride_decay_ms=600.0,
                   crash_sec=1.2, siren=("climb", 1.0, 12.0), bass_decay_ms=160.0, bass_lp_hz=700.0, tone_decay_ms=500.0),
}


# ---------------------------------------------------------------- primitives (44.1 kHz unless said)

def decay_env(n: int, decay_ms: float, attack_ms: float = 1.0) -> np.ndarray:
    """-20 dB at decay_ms, the classifier's own decay definition (Features.decayMs)."""
    t = np.arange(n) / RATE
    return np.minimum(1.0, t / (attack_ms * 1e-3)) * 10 ** (-t / (decay_ms * 1e-3))


def hp(x: np.ndarray, hz: float, order: int = 2) -> np.ndarray:
    return signal.sosfilt(signal.butter(order, hz / (RATE / 2), "high", output="sos"), x)


def lp(x: np.ndarray, hz: float, order: int = 2) -> np.ndarray:
    return signal.sosfilt(signal.butter(order, hz / (RATE / 2), "low", output="sos"), x)


def bp(x: np.ndarray, lo: float, hi: float, order: int = 2) -> np.ndarray:
    return signal.sosfilt(signal.butter(order, [lo / (RATE / 2), hi / (RATE / 2)], "band", output="sos"), x)


def norm(x: np.ndarray) -> np.ndarray:
    return x / max(1e-9, np.max(np.abs(x)))


def speed(src: np.ndarray, ratio: float) -> np.ndarray:
    """The sampler trick: read faster, it gets higher and shorter. Linear read (the Kotlin wants cubic)."""
    n = int(len(src) / ratio)
    return np.interp(np.arange(n) * ratio, np.arange(len(src)), src)


def whiten(src: np.ndarray, a: dict) -> np.ndarray:
    """The source divided by its own long-term colour: its noise and time structure kept, its
    resonances flattened. The inverse of QUARRY's STAIN, same STFT door (1024/256)."""
    # two passes, each dividing by the exact long-term magnitude (lines included), floored at -50 dB:
    # the floor is the purist limit. What a source cannot be flattened into within it is not forced;
    # the slot falls back to the blend instead (the brief's rule), and the report says which
    y = np.array(src, dtype=float)
    for _ in range(2):
        f, _, z = signal.stft(y, RATE, nperseg=1024, noverlap=768)
        mag = np.mean(np.abs(z), axis=1)
        mag /= max(1e-9, mag.max())
        gain = 1.0 / np.maximum(mag, 10 ** (-50 / 20))
        _, y = signal.istft(z * gain[:, None], RATE, nperseg=1024, noverlap=768)
        y = norm(y[:len(src)])
    return y


def paulstretch(src: np.ndarray, factor: float, seed: int, window: int = 4096) -> np.ndarray:
    """Stretch.kt's idea in ten lines: big Hann frames read at 1/factor pace, phases randomized."""
    hop = window // 4
    out_n = int(len(src) * factor)
    out = np.zeros(out_n + window)
    rng = np.random.default_rng(seed)
    win = np.hanning(window)
    for o in range(0, out_n, hop):
        p = int(o / factor)
        frame = src[p:p + window]
        if len(frame) < window:
            frame = np.pad(frame, (0, window - len(frame)))
        spec = np.abs(np.fft.rfft(frame * win)) * np.exp(1j * rng.uniform(0, 2 * np.pi, window // 2 + 1))
        out[o:o + window] += np.fft.irfft(spec, n=window) * win
    return norm(out[:out_n])


def cycle_at(a: dict, hz_track: np.ndarray) -> np.ndarray:
    """The source's cycle (QUARRY's CYCLE) read along a pitch trajectory at 44.1 kHz, mip-mapped
    for the trajectory's highest note."""
    table = quarry.table_for(a["cycle"], float(hz_track.max()))
    N = len(table)
    phase = (np.cumsum(hz_track) / RATE) % 1.0 * N
    i = phase.astype(int)
    frac = phase - i
    return table[i] * (1 - frac) + table[(i + 1) % N] * frac


def drive(x: np.ndarray, amount: float) -> np.ndarray:
    return norm(np.tanh(x * (1.0 + 3.0 * amount)))


def head(src: np.ndarray, a: dict, ms: float) -> np.ndarray:
    s = a["loud"]
    h = np.array(src[s:s + int(ms * 1e-3 * RATE)], dtype=float)
    return h * np.hanning(2 * len(h))[len(h):] if len(h) else h


def noise_of(src: np.ndarray, a: dict, n: int, seed: int) -> np.ndarray:
    """The source's own noise: its whitened arrival (60 ms) paulstretched to n samples with a short
    window. Broadband by construction, so a harmonic source (a pluck) still yields noise. The first
    run showed why: whitening a line spectrum leaves lines, and the classifier's flatness gates
    (0.25 for a snare, 0.35 for a clap) are what a snare and a clap ARE to this repo."""
    white = whiten(src, a)
    seg = white[a["loud"]: a["loud"] + int(0.06 * RATE)]
    if len(seg) < 512:
        seg = np.pad(seg, (0, 512 - len(seg)))
    noise = paulstretch(seg, max(2.0, 2 * n / len(seg)), seed, window=512)
    # a source with no top end (a kick) whitens to nothing up there, so it is read faster until the
    # power above 2 kHz is at least half (the classifier's high band): the sampler's own trick, still the source
    for _ in range(4):
        f, pxx = signal.welch(noise, RATE, nperseg=2048)
        if pxx[f >= 2000].sum() / max(1e-12, pxx.sum()) >= 0.5:
            break
        noise = speed(noise, 2.0)
    return norm(np.resize(noise, n))


# ---------------------------------------------------------------- the slots

def kick(src, a, g, name):
    n = int(min(0.9, g["kick_decay_ms"] * 3e-3) * RATE)
    t = np.arange(n) / RATE
    hz = g["kick_hz"] * (1 + (g["kick_drop"] - 1) * np.exp(-t / (g["kick_drop_ms"] * 1e-3)))
    # drive first, then a steep low-pass: the drive's harmonics are what the filter takes away, and
    # the classifier wants centroid under 100 Hz with the low band over 0.55 (Classifier.classifyBass)
    body = lp(drive(cycle_at(a, hz), g["drive"]), g["kick_lp_hz"], order=4) * decay_env(n, g["kick_decay_ms"], 0.5)
    click = hp(head(src, a, 8.0), 1000.0)
    out = norm(body)
    if len(click):
        out[:len(click)] += 0.35 * g["kick_click"] * norm(click)
    return norm(out)


def snare(src, a, g, name):
    n = int(min(0.8, g["snare_decay_ms"] * 3e-3) * RATE)
    noise = noise_of(src, a, n, sk.seed_for("snare", name)) * decay_env(n, g["snare_decay_ms"], 0.5)
    noise = hp(noise, 250.0, order=1)                      # gentle: steep filters empty bins and sink flatness
    bright = hp(noise, 2500.0, order=1)
    noise = (1 - g["snare_bright"]) * noise + g["snare_bright"] * bright
    body = cycle_at(a, np.full(n, g["snare_hz"])) * decay_env(n, g["snare_decay_ms"] * 0.5, 0.5)
    return norm(0.85 * norm(noise) + 0.35 * norm(body))


def hat(src, a, g, name, decay_ms):
    # the real transient sped up (identity, the first 30 ms) over the source's own noise (length):
    # tiling the sped-up source ran out of material in the first run and read as a 12 ms closed hat
    n = int(min(0.9, decay_ms * 3e-3) * RATE)
    fast = speed(src, g["hat_speed"])
    s = int(a["loud"] / g["hat_speed"])
    tick = np.zeros(n)
    seg = fast[s:s + int(0.03 * RATE)]
    tick[:len(seg)] = seg * np.hanning(2 * len(seg))[len(seg):]
    # the noise read at the hat's speed too, so its band reaches the top of the spectrum (centroid over 12 kHz)
    body = np.resize(speed(noise_of(src, a, n, sk.seed_for("hat", name, decay_ms)), g["hat_speed"]), n)
    x = 0.25 * norm(hp(tick, 9000.0, order=4)) + norm(hp(body, 9000.0, order=4))
    return norm(x * decay_env(n, decay_ms, 0.3))


def clap(src, a, g, name):
    n = int(min(0.8, g["clap_decay_ms"] * 3e-3) * RATE)
    noise = noise_of(src, a, n, sk.seed_for("clap", name))
    out = np.zeros(n)
    burst = int(0.009 * RATE)
    for k, level in enumerate((0.55, 0.7, 0.85, 1.0)):            # four flams 12 ms apart, rising
        o = int(k * 0.012 * RATE)                                  # 3 ms of near-silence between: the counter re-arms at 0.24 x peak
        out[o:o + burst] += level * noise[o:o + burst] * np.hanning(burst)
    tail_start = int(4 * 0.012 * RATE)
    out[tail_start:] += 0.45 * noise[tail_start:] * decay_env(n - tail_start, g["clap_decay_ms"], 0.5)
    return norm(lp(hp(out, 400.0, order=1), 9000.0, order=1))      # gentle on purpose: flatness over 0.35 is the gate


def ride(src, a, g, name):
    fast = speed(src, 4.0)
    n = int(min(1.4, g["ride_decay_ms"] * 2.5e-3) * RATE)
    s = int(a["loud"] / 4.0)
    x = np.resize(fast[s:], n) if len(fast) - s < n else fast[s:s + n]
    wash = bp(x, 4000.0, 11000.0) * decay_env(n, g["ride_decay_ms"], 0.3)
    ping = cycle_at(a, np.full(n, 1800.0)) * decay_env(n, 60.0, 0.2)
    return norm(0.9 * norm(wash) + 0.35 * ping)


def crash(src, a, g, name):
    fast = speed(src[a["loud"]:], 3.0)
    wash = paulstretch(fast, max(2.0, (g["crash_sec"] * RATE) / max(1, len(fast))), sk.seed_for("crash", name))
    n = int(min(LOOP_MIN_SECONDS - 0.05, g["crash_sec"]) * RATE)
    wash = np.resize(wash, n)
    return norm(hp(wash, 3000.0, order=2) * decay_env(n, g["crash_sec"] * 650.0, 8.0))


def siren(src, a, g, name):
    kind, seconds, semis = g["siren"]
    n = int(seconds * RATE)
    t = np.linspace(0, 1, n)
    base = a["ref_hz"] if a["pitched"] else 440.0
    if kind == "drop":
        semi = semis * (1 - t)                      # from +semis down to the note
    elif kind == "risefall":
        semi = semis * (1 - np.abs(2 * t - 1))      # up and back, a triangle in semitones (Siren.kt's log-domain rule)
    else:
        semi = semis * np.minimum(1.0, t / 0.8)     # climb and hold
    hz = base * 2 ** (semi / 12)
    env = np.minimum(1.0, np.arange(n) / (0.01 * RATE)) * np.minimum(1.0, (n - np.arange(n)) / (0.05 * RATE))
    return norm(lp(cycle_at(a, hz), 6000.0) * env)


def note(name, a, g, midi, blend, grain, decay_ms, lp_hz):
    """A QUARRY note (the 2026-10-31 prototype's render), trimmed under the LOOP line and shaped."""
    seconds = min(LOOP_MIN_SECONDS - 0.1, max(0.5, decay_ms * 3e-3))
    raw = quarry.render(name, {"BLEND": blend, "GRAIN": grain, "STAIN": 0.5, "ATTACK": 0.25}, midi, seconds=seconds)
    out = sk.finish(raw)
    out = out * decay_env(len(out), decay_ms, 2.0)
    return norm(lp(out, lp_hz) if lp_hz else out)


def render_kit(name: str, genre: str, root: int, scale: str) -> dict[str, np.ndarray]:
    src = quarry.SOURCES[name]()
    a = quarry.analyse(src, quarry.SOURCE_PITCHED[name])
    g = GENRES[genre]
    degrees = SCALES[scale]
    pads = {
        "KICK": kick(src, a, g, name), "SNARE": snare(src, a, g, name),
        "HAT_CLOSED": hat(src, a, g, name, g["hat_closed_ms"]), "HAT_OPEN": hat(src, a, g, name, g["hat_open_ms"]),
        "CLAP": clap(src, a, g, name), "RIDE": ride(src, a, g, name), "CRASH": crash(src, a, g, name),
        "SIREN": siren(src, a, g, name),
    }
    for tag, d in zip(("BASS1", "BASS3", "BASS5", "BASS8"), degrees):
        pads[tag] = note(name, a, g, 36 + root + d, 0.3, 0.3, g["bass_decay_ms"], g["bass_lp_hz"])
    for tag, d in zip(("TONE1", "TONE3", "TONE5", "TONE8"), degrees):
        pads[tag] = note(name, a, g, 60 + root + d, 0.6, 0.5, g["tone_decay_ms"], None)
    return pads


# ---------------------------------------------------------------- the real classifier

LINE = re.compile(r"^(?P<file>\S+)\s+\??(?P<cls>[A-Z_]+)\s+(?P<conf>[\d.]+)\s+(?P<dur>[\d.]+)s\s+(?P<cen>\d+)Hz\s+(?P<dec>\d+)ms\s+(?P<low>[\d.]+)\s+(?P<mid>[\d.]+)\s+(?P<high>[\d.]+)")


def classify(paths: list[str]) -> dict[str, dict]:
    out = subprocess.run(["java", "-jar", JAR, "classify", *paths], capture_output=True, text=True, check=True).stdout
    verdicts = {}
    for line in out.splitlines():
        m = LINE.match(line.strip())
        if m:
            d = m.groupdict()
            verdicts[d["file"]] = {"class": d["cls"], "confidence": float(d["conf"]), "seconds": float(d["dur"]),
                                   "centroid_hz": int(d["cen"]), "decay_ms": int(d["dec"]),
                                   "low": float(d["low"]), "mid": float(d["mid"]), "high": float(d["high"])}
    return verdicts


def main(out_dir: str) -> int:
    os.makedirs(out_dir, exist_ok=True)
    if not os.path.isfile(JAR):
        print(f"no jar at {JAR}: run ./gradlew :cli:snipsnapJar first", file=sys.stderr)
        return 2
    root, scale = 9, "MINOR"                      # A minor: the key a novice picks first
    results = {"harness": "Python renders, Kotlin classifier (cli/build/libs/snipsnap.jar classify)",
               "key": {"root": root, "scale": scale}, "kits": [], "score": {}}
    paths = []
    index = {}
    for name in quarry.SOURCES:
        for genre in GENRES:
            pads = render_kit(name, genre, root, scale)
            for i, slot in enumerate(SLOTS):
                fn = f"{name}_{genre}_{i + 1:02d}_{slot}.wav"
                p = os.path.join(out_dir, fn)
                sk.write_wav(p, pads[slot] * 0.9)
                paths.append(p)
                index[fn] = (name, genre, slot, pads[slot])
    verdicts = classify(paths)
    hits = {s: 0 for s in JUDGED}
    total = 0
    for fn, (name, genre, slot, x) in index.items():
        v = verdicts.get(fn[:28], verdicts.get(fn, {}))
        row = {"source": name, "genre": genre, "slot": slot, "file": fn, **v}
        if slot in JUDGED:
            total += 1
            row["wanted"] = JUDGED[slot]
            row["agrees"] = v.get("class") == JUDGED[slot]
            hits[slot] += int(row["agrees"])
        if slot.startswith(("BASS", "TONE", "SIREN")):
            # the same call the QUARRY probe used (40 Hz floor, 0.3 s window): with a 30 Hz floor and a
            # short window the NSDF picked exact sub-harmonics (1/2, 1/3, 1/4 of the note) at confidence 1.0
            # on half the rows, a detector ambiguity every periodic signal has, not a synthesis error
            hz, conf = pr.pitch(x, RATE, lo_hz=40.0, window_seconds=0.3)
            row["pitch_hz"] = round(hz, 2)
            row["pitch_confidence"] = round(conf, 3)
        row["under_loop_line"] = len(x) / RATE < LOOP_MIN_SECONDS
        results["kits"].append(row)
    results["score"] = {"judged_slots": total, "agreed": sum(hits.values()), "by_slot": hits,
                        "kits": len(quarry.SOURCES) * len(GENRES)}
    with open(os.path.join(out_dir, "report.json"), "w") as f:
        json.dump(results, f, indent=2)
    print(json.dumps(results["score"], indent=2))
    print(f"\nwrote {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "kit_proto_out"))
