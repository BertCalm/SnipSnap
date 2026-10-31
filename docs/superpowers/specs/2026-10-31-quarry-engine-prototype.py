"""QUARRY prototype: one captured sound, quarried three ways into a pitched instrument.

Python harness, not Kotlin. Numbers it prints are harness numbers (snipkit's RMS-proxy
levelling, probes' NSDF pitch) and say nothing about what anyone has heard.

The engine idea (see 2026-10-31-quarry-engine-design-brief.md):

  source ─┬─ CYCLE : one period of the source (or, unpitched, one 46 ms excerpt forced to
          │          loop) becomes a mip-mapped wavetable sung at the note
          ├─ FIELD : pitched source: a seeded grain cloud of the source, one grain per period of the
          │          note, transposed (pitch-synchronous); unpitched source: the forced cycle sliding
          │          through the source (FIELD_FREE in the report is the free-running cloud both replaced)
          └─ STAIN : the source's long-term spectral envelope pressed onto the mix

  BLEND crossfades CYCLE and FIELD; STAIN presses the colour on; ATTACK splices the
  source's own first milliseconds in front; HOLD freezes the scrub and loops whole periods.

What it settles with a number:
  1. does an UNPITCHED source, forced into one cycle, still read as the note at C2, C3, C4
     (root within 20 cents, confidence >= 0.3, the Kotlin tests' floor)?
  2. does the FIELD keep the root when the grains are re-pitched?
  3. does every macro change the sound (log-spectral distance between extremes)?
  4. does the mip-mapped table stay clean of aliasing at the top of TUNE?
  5. does HOLD close (seam against a real preceding cycle, under 1e-3)?
  6. drum guard: with a kick source and ATTACK at 1, how much of the render is the head?

Run from the repository root:
  python3 docs/superpowers/specs/2026-10-31-quarry-engine-prototype.py <out_dir>
Sources are synthesized here so the run is deterministic; swap `SOURCES` for real captures
(44.1 kHz mono WAVs via snipkit.read_wav) to hear it on your own material.
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "..", ".claude", "skills", "luthier", "scripts"))
import snipkit as sk  # noqa: E402
import probes as pr   # noqa: E402

RATE = sk.RATE
INTERNAL = sk.INTERNAL
TABLE = 2048              # cycle points; Snap uses 256 for a photo, audio wants more harmonics
HEAD_MAX_MS = 60.0        # ATTACK 1 keeps this much of the real source in front ("shape")
T60 = 1.5                 # one-shot decay, seconds ("shape")
SECONDS = 2.0
ROOT_MIDI = 48
TUNE_SEMITONES = 24
MACROS = {"TUNE": 0.5, "BLEND": 0.5, "GRAIN": 0.5, "STAIN": 0.5, "ATTACK": 0.3}
PITCHED_CONFIDENCE = 0.5  # above: the source has a note to quarry ("shape"; Kotlin tests use 0.3)
UNPITCHED_REF_HZ = 130.81 # C3: an unpitched source is "at" C3, like Grains' PITCH 0.5 and PadFromAnything's drone


def midi_for(tune: float) -> int:
    return ROOT_MIDI + int(round((tune - 0.5) * TUNE_SEMITONES))


def hz_for(midi: int) -> float:
    return 440.0 * 2 ** ((midi - 69) / 12)


# ---------------------------------------------------------------- stand-in sources (44.1 kHz mono)

def src_pluck() -> np.ndarray:
    """A pitched source: a Karplus-Strong pluck at A3, the clearest case."""
    exc = sk.noise_burst(3.0, INTERNAL, sk.seed_for("pluck"), 1.0, 1.0, lowpass_hz=5000)
    raw = sk.KSLoop(220.0, INTERNAL, loop_gain=0.997, lowpass=0.4).render(exc)
    return sk.decimate4(sk.band_limit(raw, INTERNAL))


def src_kick() -> np.ndarray:
    """An unpitched-by-the-classifier source with a swept low fundamental: the drum guard case."""
    n = int(0.4 * RATE)
    t = np.arange(n) / RATE
    hz = 45 + 105 * np.exp(-t / 0.03)
    phase = 2 * np.pi * np.cumsum(hz) / RATE
    return np.sin(phase) * np.exp(-t / 0.12) * np.minimum(1.0, t / 0.001)


def src_bark() -> np.ndarray:
    """An unpitched formant burst (two resonances on noise, 120 ms): the 'dog bark' case."""
    n = int(0.3 * RATE)
    noise = sk.Noise(sk.seed_for("bark")).array(n)
    out = np.zeros(n)
    for hz, q, g in ((900.0, 6.0, 1.0), (2200.0, 8.0, 0.5)):
        b, a = signal.iirpeak(hz, q, RATE)
        out += g * signal.lfilter(b, a, noise)
    env = np.zeros(n)
    w = int(0.12 * RATE)
    env[:w] = np.hanning(w)
    return out * env / np.max(np.abs(out * env))


def src_snare() -> np.ndarray:
    """Noise plus a short 180 Hz body: a source whose 'cycle' is mostly noise."""
    n = int(0.25 * RATE)
    t = np.arange(n) / RATE
    noise = sk.Noise(sk.seed_for("snare")).array(n) * np.exp(-t / 0.08)
    body = np.sin(2 * np.pi * 180 * t) * np.exp(-t / 0.05)
    out = 0.7 * noise + 0.6 * body
    return out / np.max(np.abs(out))


SOURCES = {"PLUCK": src_pluck, "KICK": src_kick, "BARK": src_bark, "SNARE": src_snare}
# In Kotlin the classifier's DrumClass decides whether a source has a note to quarry (TONAL) or is
# forced (every drum class). Here that verdict is declared, because a raw pitch confidence misreads
# a formant ring as a note (the first run: BARK 1075 Hz at 0.94, SNARE 191 Hz at 0.88).
SOURCE_PITCHED = {"PLUCK": True, "KICK": False, "BARK": False, "SNARE": False}


# ---------------------------------------------------------------- analysis (cached per source in Kotlin, keyed by hash)

def forced_cycle(src: np.ndarray, start: int) -> np.ndarray:
    """TABLE samples (46 ms) of the source from `start`, forced to wrap with a short raised-cosine at
    each end, mean removed, peak 1. Played as one period it is periodic by construction."""
    start = int(max(0, min(start, len(src) - TABLE)))
    cycle = np.array(src[start:start + TABLE], dtype=float)
    if len(cycle) < TABLE:
        cycle = np.pad(cycle, (0, TABLE - len(cycle)))
    edge = 128
    ramp = 0.5 - 0.5 * np.cos(np.pi * np.arange(edge) / edge)
    cycle[:edge] *= ramp
    cycle[-edge:] *= ramp[::-1]
    cycle = cycle - np.mean(cycle)
    return cycle / max(1e-9, np.max(np.abs(cycle)))


def analyse(src: np.ndarray, pitched_by_class: bool = True) -> dict:
    env = pr.envelope(src, RATE, block_ms=5.0)
    loud = int(np.argmax(env) * 0.005 * RATE)
    win = min(0.1, len(src) / RATE - loud / RATE - 0.01)
    hz, conf = pr.pitch(src, RATE, start_seconds=loud / RATE, window_seconds=max(0.03, win))
    pitched = bool(pitched_by_class and conf >= PITCHED_CONFIDENCE and 40 <= hz <= 2000)
    if pitched:
        # average eight consecutive periods from the loudest point, each resampled to TABLE points:
        # consecutive periods share a phase, so the mean is one clean cycle that wraps by construction
        period = RATE / hz
        cycles = []
        for m in range(8):
            pos = loud + m * period + np.arange(TABLE) * period / TABLE
            if pos[-1] >= len(src) - 1:
                break
            cycles.append(np.interp(pos, np.arange(len(src)), src))
        cycle = np.mean(cycles, axis=0)
    else:
        # no period to find: TABLE samples (46 ms) around the loudest point, forced to wrap with a short
        # raised-cosine at each end. Played as a cycle it is periodic by construction: the note is
        # guaranteed, the question (probe 1) is whether the fundamental is strong enough to read
        cycle = forced_cycle(src, loud - TABLE // 4)
    cycle = cycle - np.mean(cycle)
    cycle /= max(1e-9, np.max(np.abs(cycle)))
    # colour: the long-term magnitude envelope, Spectral's own geometry (1024/256), smoothed, max 1, floor -40 dB
    f, _, z = signal.stft(src, RATE, nperseg=1024, noverlap=768)
    mag = np.mean(np.abs(z), axis=1)
    mag = np.convolve(mag, np.ones(5) / 5, mode="same")
    mag /= max(1e-9, mag.max())
    colour = np.maximum(mag, 10 ** (-40 / 20))
    return {"hz": hz, "conf": conf, "pitched": pitched, "cycle": cycle, "colour_f": f, "colour": colour,
            "ref_hz": hz if pitched else UNPITCHED_REF_HZ, "loud": loud}


# ---------------------------------------------------------------- the three readers

def table_for(cycle: np.ndarray, f0: float) -> np.ndarray:
    """Mip-map: drop every harmonic that would land above 20 kHz at the OUTPUT rate, so the
    4x internal render has nothing to fold back (Snap and Contour do this per note)."""
    spec = np.fft.rfft(cycle)
    kmax = int(20_000 / f0)
    spec[0] = 0
    spec[kmax + 1:] = 0
    t = np.fft.irfft(spec, n=len(cycle))
    return t / max(1e-9, np.max(np.abs(t)))


def cycle_voice(a: dict, f0: float, n: int) -> np.ndarray:
    table = table_for(a["cycle"], f0)
    N = len(table)
    phase = (np.arange(n) * f0 / INTERNAL) % 1.0 * N
    i = phase.astype(int)
    frac = phase - i
    return table[i] * (1 - frac) + table[(i + 1) % N] * frac   # linear; the Kotlin wants cubic above 2 kHz


def field_voice(a: dict, src: np.ndarray, f0: float, n: int, grain: float, seed: int,
                freeze: bool = False, period_frames: int | None = None, sync: bool = True) -> np.ndarray:
    """A seeded Hann grain cloud. The scrub point scans the source start to end across the
    render (Grains' arc rule) or freezes at the loudest point for HOLD. Each grain is read at
    the ratio f0 / ref_hz, so a pitched source lands on the note and an unpitched one is
    treated as 'at C3'. With period_frames the schedule repeats exactly every period, so a
    HOLD loop is periodic by construction (the seam is then a real measurement, not a paste)."""
    if sync and not a["pitched"]:
        # unpitched: strobing the source at the note rate kept the source's own formant as the
        # detected pitch (the measured runs), so the field is instead the forced cycle SLIDING
        # through the source: K excerpt tables from the arrival toward the end, crossfaded as the
        # render proceeds. Each instant is a mip-mapped table at f0, so the note is guaranteed by
        # construction and the motion is the source's own material passing by. GRAIN is how far the
        # slide travels (0: it stays at the arrival, the CYCLE itself; 1: to the end of the source).
        K = 12
        reach = sk.lin(grain, 0.0, 1.0) * max(0, len(src) - a["loud"] - TABLE)
        positions = a["loud"] - TABLE // 4 + np.linspace(0.0, reach, K)
        tables = np.stack([table_for(forced_cycle(src, int(p)), f0) for p in positions])
        N = tables.shape[1]
        phase = (np.arange(n) * f0 / INTERNAL) % 1.0 * N
        i = phase.astype(int)
        frac = phase - i
        oscs = tables[:, i] * (1 - frac) + tables[:, (i + 1) % N] * frac
        scan = np.zeros(n) if freeze else (np.arange(n) / max(1, n - 1)) * (K - 1)
        k0 = np.minimum(scan.astype(int), K - 2)
        w = scan - k0
        out = oscs[k0, np.arange(n)] * (1 - w) + oscs[k0 + 1, np.arange(n)] * w
        return out / max(1e-9, np.max(np.abs(out)))
    if sync:
        # pitch-synchronous: one grain per period of the NOTE, each GRAIN periods long (2 = PSOLA's
        # Hann pair, up to 16 = a dense pad), read at the source's own rate so its formants stay put.
        # The cloud is then periodic at f0 whatever the source was: the note is carried by the
        # grain rate, not by what is inside the grains.
        hop = INTERNAL / f0
        size = int(hop * sk.exp_map(grain, 2.0, 8.0))
        # a pitched source is transposed inside the grain too, so content and grain rate agree (a
        # sampler's transpose); an unpitched one is strobed at its own rate, so a bark stays a bark
        ratio = f0 / a["ref_hz"] if a["pitched"] else 1.0
    else:
        size = int(sk.exp_map(grain, 8.0, 120.0) * 1e-3 * INTERNAL)
        overlap = 6
        hop = max(1, size // overlap)
        ratio = f0 / a["ref_hz"]
    if period_frames:
        count = max(1, int(round(period_frames / hop)))
        hop = period_frames / count
    src_len = len(src)
    rng = np.random.default_rng(seed)
    starts = np.arange(0, n + size, hop)
    jitter_table = rng.uniform(-1, 1, size=(count if period_frames else len(starts)))
    out = np.zeros(n + 2 * size)
    window = np.hanning(size)
    read_len = int(size * ratio / 4)                       # grains read the 44.1k source, written at INTERNAL
    for k, s0 in enumerate(starts):
        j = jitter_table[k % len(jitter_table)]
        # the scrub runs from the arrival (the loudest point) to the end, so the cloud follows the
        # source's own arc and never swells after the attack; the wander is a few ms in sync mode
        # (successive grains must resemble each other to carry a period) and wide when free
        centre = a["loud"] if freeze else a["loud"] + (s0 / max(1, n)) * (src_len - a["loud"])
        centre += j * (0.002 * RATE if sync else 0.25 * src_len) * (0.3 if freeze else 1.0)
        start = int(np.clip(centre - read_len / 2, 0, max(0, src_len - read_len - 1)))
        pos = start + np.arange(size) * ratio / 4
        g = np.interp(pos, np.arange(src_len), src) * window
        s = int(round(s0))
        out[s:s + size] += g
    out = out[:n]
    return out / max(1e-9, np.max(np.abs(out)))


def stain(mix: np.ndarray, a: dict, amount: float) -> np.ndarray:
    """Press the source's own spectral envelope onto the mix (a fixed per-bin gain, an LTI
    filter in STFT clothing). Amount 0 is a bypass; 1 is the envelope to the power 1.5."""
    power = sk.lin(amount, 0.0, 1.5)
    if power <= 0:
        return mix
    f, _, z = signal.stft(mix, INTERNAL, nperseg=4096, noverlap=3072)
    gain = np.interp(f, a["colour_f"], a["colour"], right=a["colour"][-1]) ** power
    gain[f > RATE / 2] = (10 ** (-40 / 20)) ** power
    _, y = signal.istft(z * gain[:, None], INTERNAL, nperseg=4096, noverlap=3072)
    y = y[:len(mix)]
    return y / max(1e-9, np.max(np.abs(y)))


def render(name: str, macros: dict, midi: int, hold: bool = False, seconds: float = SECONDS,
           period_frames: int | None = None, hz_override: float | None = None, sync: bool = True) -> np.ndarray:
    """The engine at the internal rate. Pure: same inputs, same samples."""
    src = SOURCES[name]()
    a = analyse(src, SOURCE_PITCHED[name])
    m = {**MACROS, **macros}
    f0 = hz_override or hz_for(midi)
    n = int(seconds * INTERNAL)
    t = np.arange(n) / INTERNAL
    seed = sk.seed_for("quarry", name, midi, round(m["GRAIN"], 3))
    cyc = cycle_voice(a, f0, n)
    fld = field_voice(a, src, f0, n, m["GRAIN"], seed, freeze=hold, period_frames=period_frames, sync=sync)
    b = m["BLEND"]
    mix = np.cos(b * np.pi / 2) * cyc + np.sin(b * np.pi / 2) * fld   # equal power
    mix = stain(mix, a, m["STAIN"])
    if hold:
        env = np.minimum(1.0, t / 0.3)
    else:
        env = np.minimum(1.0, t / 0.005) * 10 ** (-3 * t / T60)
    out = mix * env
    head_ms = sk.lin(m["ATTACK"], 0.0, HEAD_MAX_MS)
    if head_ms > 0:
        nh = int(head_ms * 1e-3 * INTERNAL)
        head = signal.resample_poly(src[: int(head_ms * 1e-3 * RATE)], 4, 1)[:nh]
        nh = len(head)                                      # resample_poly rounds; the fades follow the real head
        head = head / max(1e-9, np.max(np.abs(head)))
        xf = min(nh, int(0.01 * INTERNAL))
        fade_out = np.ones(nh)
        fade_out[nh - xf:] = 0.5 + 0.5 * np.cos(np.pi * np.arange(xf) / xf)
        fade_in = np.ones(n)
        fade_in[:nh - xf] = 0.0
        fade_in[nh - xf:nh] = 0.5 - 0.5 * np.cos(np.pi * np.arange(xf) / xf)
        out = out * fade_in
        out[:nh] += head * fade_out
    return out


# ---------------------------------------------------------------- the probes

def main(out_dir: str) -> int:
    os.makedirs(out_dir, exist_ok=True)
    results = {"harness": "Python (snipkit RMS-proxy level, probes NSDF pitch), not Kotlin",
               "sources": {}, "root": [], "macro_activity_dB": {}, "alias": {}, "hold": {}, "drum_guard": {}}

    # 0. the sources on record: which were read as pitched, and at what
    for name, fn in SOURCES.items():
        src = fn()
        a = analyse(src, SOURCE_PITCHED[name])
        sk.write_wav(os.path.join(out_dir, f"source_{name}.wav"), src / max(1e-9, np.max(np.abs(src))) * 0.9)
        results["sources"][name] = {"seconds": round(len(src) / RATE, 3), "pitch_hz": round(a["hz"], 2),
                                    "confidence": round(a["conf"], 3), "read_as": "pitched" if a["pitched"] else "unpitched"}

    # 1 + 2. root at C2, C3, C4 for the CYCLE alone, the FIELD alone and the default blend, every source
    for name in SOURCES:
        for voice, blend, sync in (("CYCLE", 0.0, True), ("FIELD", 1.0, True), ("FIELD_FREE", 1.0, False), ("BLEND", 0.5, True)):
            for midi in (36, 48, 60):
                out = sk.finish(render(name, {"BLEND": blend, "ATTACK": 0.0}, midi, sync=sync))
                tag = f"{name}_{voice}_midi{midi}"
                sk.write_wav(os.path.join(out_dir, tag + ".wav"), out)
                hz, conf = pr.pitch(out, RATE, lo_hz=40.0, window_seconds=0.3)
                ok, grow = pr.never_grows(out, RATE)
                results["root"].append({"source": name, "voice": voice, "midi": midi, "target_hz": round(hz_for(midi), 2),
                                        "pitch_hz": round(hz, 2), "cents": round(pr.cents(hz, hz_for(midi)), 1),
                                        "confidence": round(conf, 3), "peak": round(pr.peak(out), 3),
                                        "dc": round(pr.dc(out), 5), "never_grows": ok, "finite": pr.finite(out)})

    # 3. every macro changes the sound: extremes at C3 on the two hardest sources
    for name in ("BARK", "PLUCK"):
        results["macro_activity_dB"][name] = {}
        for macro in MACROS:
            if macro == "TUNE":
                continue
            lo = sk.finish(render(name, {macro: 0.0}, 48))
            hi = sk.finish(render(name, {macro: 1.0}, 48))
            results["macro_activity_dB"][name][macro] = round(pr.macro_activity(lo, hi), 2)
            sk.write_wav(os.path.join(out_dir, f"{name}_{macro}_0.wav"), lo)
            sk.write_wav(os.path.join(out_dir, f"{name}_{macro}_1.wav"), hi)

    # 4. aliasing at the top of TUNE (C5), CYCLE alone, brightest source, raw before bandLimit
    for name in ("BARK", "SNARE"):
        raw = render(name, {"BLEND": 0.0, "STAIN": 0.0, "ATTACK": 0.0}, midi_for(1.0))
        out = sk.finish(raw)
        results["alias"][name] = {"raw_share_above_output_nyquist": round(pr.alias_proxy(raw, INTERNAL), 6),
                                  "partials_above_nyquist_after_finish": pr.partials_above_nyquist(out, RATE)}

    # 5. HOLD: whole periods at C3, the scrub frozen, the grain schedule periodic in the loop,
    #    warm-up of 1 s, seam measured against that real preceding cycle (Keys.seamError's shape)
    for name in ("PLUCK", "BARK"):
        hz = hz_for(48)
        loop_frames = sk.whole_period_loop_frames(hz, RATE, 1.0)
        periods = round(loop_frames / RATE * hz)
        hz_fit = periods * RATE / loop_frames
        warm = 1.0
        total = warm + loop_frames / RATE + 0.25
        raw = render(name, {"ATTACK": 0.0}, 48, hold=True, seconds=total,
                     period_frames=loop_frames * sk.OVERSAMPLE, hz_override=hz_fit)
        held = sk.dc_block(sk.decimate4(sk.band_limit(raw, INTERNAL)))
        loop_start = int(warm * RATE)
        seam = sk.seam_error(held[: loop_start + loop_frames], loop_start)
        loop = sk.level_to(held[loop_start: loop_start + loop_frames])
        sk.write_wav(os.path.join(out_dir, f"{name}_HOLD_x4.wav"), np.tile(loop, 4))
        results["hold"][name] = {"loop_frames": loop_frames, "hz_fit": round(hz_fit, 3),
                                 "cents_off": round(pr.cents(hz_fit, hz), 2), "seam_error": float(seam),
                                 "passes_MAX_SEAM_ERROR": bool(seam < sk.MAX_SEAM_ERROR)}

    # 6. drum guard: the KICK source with ATTACK at 1 at C2. The classifier cannot be reproduced here;
    #    what is reported is how much of the render's energy sits in the spliced head, the decay, and
    #    whether the note still reads, so the Kotlin probe knows what to assert.
    out = sk.finish(render("KICK", {"ATTACK": 1.0, "BLEND": 0.0}, 36))
    sk.write_wav(os.path.join(out_dir, "KICK_ATTACK1_midi36.wav"), out)
    nh = int(HEAD_MAX_MS * 1e-3 * RATE)
    hz, conf = pr.pitch(out, RATE, lo_hz=40.0, window_seconds=0.3)
    results["drum_guard"] = {"head_energy_share": round(float(np.sum(out[:nh] ** 2) / np.sum(out ** 2)), 4),
                             "decay_seconds_to_-60dB": round(pr.decay_seconds(out, RATE), 3),
                             "pitch_hz": round(hz, 2), "cents": round(pr.cents(hz, hz_for(36)), 1), "confidence": round(conf, 3)}

    with open(os.path.join(out_dir, "report.json"), "w") as f:
        json.dump(results, f, indent=2)
    print(json.dumps(results, indent=2))
    print(f"\nwrote {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "quarry_proto_out"))
