"""CYPHER round 0: the sliding sub drum and the hat ratchet, measured before any Kotlin.

Python harness, not Kotlin. Nothing here has been heard by its author; every constant marked
"shape" is a starting value for a listening pass, not a measurement.

Two questions a number can settle before the six hip-hop kits are authored:

1. SLIDE, a sub drum whose pitch holds its root and then glides to a target. THUMP's kick
   cannot do it (its root is 34 to 62 Hz, its tail is capped near 0.84 s so it keeps reading
   as a kick, and its pitch only falls onto the root in the first few tens of ms). FATHOM's
   GLIDE slides *into* the note from below over the first third of the decay, the reverse
   gesture. So SLIDE is new, and the risks are: does the slide read as a pitch move (cents
   moved, pitch confidence at both ends), does the drum keep harmonics where a phone speaker
   plays (the SUB pedal lesson: 150 to 400 Hz share), does a softer hit change character and
   not only level, and are four takes four different strikes without detuning the sub.

2. The hat ratchet. Trap and drill hats roll at 1/32, 1/32 triplets and 1/64 at 140 BPM, which
   is 54, 36 and 27 ms between hits. A hat whose decay is longer than that smears into a buzz.
   The probe asks, for three hat decays, how many onsets a lead detector hears against how many
   were played, and how deep the dip between hits is. That number is what the TRAP and DRILL
   kits' hat DECAY settings must respect.

The chain for SLIDE, in order:

    pitch law (root, settle, slide) ─► sine @4x ─► × amp env ─► + click ─► DRIVE tanh @4x
        ─► band-limit + decimate ─► DC block ─► fade tail

Why each stage sits where it does:
  * The sine and the clipper run at 4x like every SnipSnap engine, because a tanh on a sub
    makes harmonics well up the band and they have to be filtered before the rate drops (the
    VALVE and FORGE rule).
  * DRIVE comes after the click is added, so the click is also pushed: a sub drum's click is
    part of the same converter path on the machines this gesture comes from.
  * The pitch law is one function of time integrated into phase, never a retune of a ringing
    resonator, so a slide cannot click.

Run:  python3 cypher_proto.py OUT_DIR
"""
from __future__ import annotations

import json
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", ".claude", "skills", "luthier", "scripts"))
import snipkit as sk  # noqa: E402
import probes as pr   # noqa: E402

RATE = sk.RATE
FS = sk.INTERNAL  # 176_400

# ----------------------------------------------------------------------------- SLIDE

# Six controls, the house ceiling, TUNE included. A kit author sets them; a later THUMP voice
# could expose them. Every default is shape.
DEFAULTS = {
    "TUNE": 0.5,    # root: E1 (41.2 Hz) at 0 to E2 (82.4 Hz) at 1, in semitones
    "DECAY": 0.6,   # t60 of the body: 0.25 s to 1.3 s. Over 0.5 s the classifier reads TONAL; disclosed.
    "SLIDE": 0.5,   # where the pitch goes: -12 semitones at 0, 0 at .5, +12 at 1 (centred, neutral .5)
    "WHEN": 0.5,    # when the slide starts, as a share of the decay: 0.15 .. 0.75 of t60
    "DRIVE": 0.4,   # tanh gain 1 .. 6: the harmonics a phone plays
    "CLICK": 0.5,   # the onset click's level
}


# Four takes, five audible dimensions dealt out so no two takes share a setting and take 0 is
# the authored drum. The root is never one of them: a detuned sub is a different note. Shape.
# Measured (Python harness): on a sine into a tanh the log-spectral distance between takes is
# set almost entirely by the drive ratio, about 10 dB per unit of ln(ratio); the drives below
# sit 0.16 to 0.22 apart in ln so every pair is at least 2 dB apart (report.json, slide_takes).
TAKES = (
    {"settle": 1.00, "click": 1.00, "drive": 1.00, "when_ms": 0.0, "glide": 1.00},
    {"settle": 0.70, "click": 1.45, "drive": 0.80, "when_ms": 10.0, "glide": 1.18},
    {"settle": 1.30, "click": 0.65, "drive": 1.25, "when_ms": -8.0, "glide": 0.88},
    {"settle": 0.88, "click": 1.60, "drive": 0.64, "when_ms": 5.0, "glide": 1.06},
)


def root_hz(tune: float) -> float:
    semis = sk.lin(tune, 0.0, 12.0)
    return 41.2034 * 2 ** (semis / 12)


def t60_for(decay: float) -> float:
    return sk.exp_map(decay, 0.25, 1.3)


def slide_semis(slide: float) -> float:
    # Centred: 0.5 is no slide. Symmetric an octave each way.
    return sk.lin(slide, -12.0, 12.0)


def render_slide(macros: dict, velocity: float = 1.0, take: int = 0, rate: int = FS) -> np.ndarray:
    """The drum. Pure in (macros, velocity, take). Returns raw audio at the internal rate,
    peak near 1, not yet through the output stage."""
    m = {**DEFAULTS, **macros}
    v = float(np.clip(velocity, 0.05, 1.0))
    tk = TAKES[take % len(TAKES)]

    root = root_hz(m["TUNE"])
    t60 = t60_for(m["DECAY"]) * (0.85 + 0.15 * v)          # a softer hit rings a little less (shape)
    hold = 0.012                                             # the sine sits at full level this long (shape)
    total = t60 * 1.3 + hold + 0.05
    n = int(total * rate)
    t = np.arange(n) / rate

    # 1. Pitch law. A short settle like the machines this comes from: the sine starts a little
    #    high and falls onto the root in ~25 ms (settle_oct, settle_tau). Then the slide: from
    #    when_s, an exponential approach to root + slide_semis with time constant glide_tau, so
    #    the note arrives and sits. A take moves when_s by up to 4 ms so no two takes slide at
    #    the same instant; the root never moves (a detuned sub is a different note).
    settle_oct = 0.35 * (0.6 + 0.4 * v) * tk["settle"]       # shape; softer hits sweep less, as THUMP's kick does
    settle_tau = 0.012
    target = slide_semis(m["SLIDE"])
    when_s = sk.lin(m["WHEN"], 0.15, 0.75) * t60 + tk["when_ms"] * 1e-3
    glide_tau = (0.08 + 0.10 * abs(target) / 12) * tk["glide"]   # a bigger interval takes longer (shape)
    semis = target * (1 - np.exp(-np.clip(t - when_s, 0, None) / glide_tau)) * (t >= when_s)
    f = root * 2 ** (settle_oct * np.exp(-t / settle_tau)) * 2 ** (semis / 12)
    phase = 2 * np.pi * np.cumsum(f) / rate
    body = np.sin(phase)

    # 2. Amplitude: 1 ms attack, hold, then an exponential decay to -60 dB at t60.
    env = np.ones(n)
    a = int(0.001 * rate)
    env[:a] = np.linspace(0, 1, a, endpoint=False)
    after = np.clip(t - hold, 0, None)
    env *= np.exp(-after * (math.log(1000) / t60))
    body *= env

    # 3. The click: a 1.5 ms raised-cosine pulse plus 4 ms of low-passed noise, both scaled by
    #    velocity to a power above one so a soft hit loses its click faster than its body.
    click_level = sk.lin(m["CLICK"], 0.0, 0.5) * v ** 1.6 * tk["click"]
    pulse = sk.hann_pulse(1.5, rate, 1.0, total)[:n]
    noise = sk.noise_burst(4.0, rate, sk.seed_for("CYPHER", "SLIDE", "click", take), 1.0, total, lowpass_hz=4000)[:n]
    click = click_level * (pulse + 0.6 * noise)

    # 4. DRIVE: a tanh at 4x. Gain follows velocity, so a soft hit is cleaner as well as quieter;
    #    a take moves the gain by up to 3 percent, a fresh strike into the same stage.
    g = sk.lin(m["DRIVE"], 1.0, 6.0) * (0.55 + 0.45 * v) * tk["drive"]
    x = body + click
    y = np.tanh(g * x) / math.tanh(g)
    return y / max(1e-9, float(np.max(np.abs(y))))


def finish_peak(raw: np.ndarray, peak: float = 0.92) -> np.ndarray:
    """The house output stage without the loudness match: kits level per zone, not per hit."""
    y = sk.band_limit(raw, FS)
    y = sk.decimate4(y)
    y = sk.dc_block(y, RATE)
    y *= peak / max(1e-9, float(np.max(np.abs(y))))
    return sk.fade_tail(y, RATE)


def band_share(x: np.ndarray, lo: float, hi: float, rate: int = RATE) -> float:
    spec = np.abs(np.fft.rfft(x)) ** 2
    freqs = np.fft.rfftfreq(len(x), 1 / rate)
    tot = float(spec.sum()) or 1e-18
    return float(spec[(freqs >= lo) & (freqs < hi)].sum() / tot)


def pitch_window(x: np.ndarray, start_s: float, window_s: float = 0.12) -> tuple[float, float]:
    return pr.pitch(x, RATE, lo_hz=30.0, hi_hz=400.0, window_seconds=window_s, start_seconds=start_s)


def slide_report(name: str, macros: dict, velocity: float = 1.0, take: int = 0, out_dir: str | None = None) -> dict:
    raw = render_slide(macros, velocity, take)
    out = finish_peak(raw)
    if out_dir:
        sk.write_wav(os.path.join(out_dir, name + ".wav"), out)
    m = {**DEFAULTS, **macros}
    t60 = t60_for(m["DECAY"])
    when_s = sk.lin(m["WHEN"], 0.15, 0.75) * t60
    root = root_hz(m["TUNE"])
    # The slide read as pitch: a window before the slide starts (after the settle) and one well
    # after the glide has arrived, each measured by the same autocorrelation the house uses.
    before_hz, before_conf = pitch_window(out, 0.04, min(0.12, max(0.06, when_s - 0.05)))
    arrive = when_s + 3 * (0.08 + 0.10 * abs(slide_semis(m["SLIDE"])) / 12)
    after_hz, after_conf = pitch_window(out, min(arrive, len(out) / RATE - 0.15), 0.12)
    rep = pr.report(out, RATE, name, ref_hz=root)
    rep.update({
        "root_hz": round(root, 2),
        "intended_slide_cents": round(slide_semis(m["SLIDE"]) * 100, 1),
        "before_slide_hz": round(before_hz, 2), "before_conf": round(before_conf, 3),
        "after_slide_hz": round(after_hz, 2), "after_conf": round(after_conf, 3),
        "measured_slide_cents": round(pr.cents(after_hz, before_hz), 1) if before_hz > 0 and after_hz > 0 else None,
        "share_150_400Hz": round(band_share(out, 150, 400), 4),
        "share_under_100Hz": round(band_share(out, 0, 100), 4),
        "alias_proxy_raw": round(pr.alias_proxy(raw, FS), 6),
    })
    return rep


# ----------------------------------------------------------------------------- hat ratchet

def hat(t60: float, rate: int = RATE, seed: int = 3) -> np.ndarray:
    """A plain closed hat for the timing probe: band-passed noise with an exponential decay.
    Not THUMP's or SKIN's hat; the question is timing, not timbre."""
    n = int((t60 * 1.5 + 0.01) * rate)
    rng = np.random.default_rng(seed)
    x = rng.standard_normal(n)
    # a 6 to 12 kHz band, two one-pole stages each way, the cheapest shape that reads as a hat
    from scipy import signal  # the harness already needs scipy
    b, a = signal.butter(2, [6000 / (rate / 2), 12000 / (rate / 2)], btype="band")
    x = signal.lfilter(b, a, x)
    t = np.arange(n) / rate
    x *= np.exp(-t * (math.log(1000) / t60))
    att = int(0.0005 * rate)
    x[:att] *= np.linspace(0, 1, att, endpoint=False)
    return x / max(1e-9, float(np.max(np.abs(x))))


def ratchet(period_s: float, count: int, hat_t60: float, rate: int = RATE) -> np.ndarray:
    h = hat(hat_t60, rate)
    n = int(period_s * count * rate) + len(h)
    out = np.zeros(n)
    for i in range(count):
        s = int(round(i * period_s * rate))
        out[s: s + len(h)] += h
    return out / max(1e-9, float(np.max(np.abs(out))))


def onsets_heard(x: np.ndarray, rate: int = RATE, lead_db: float = 4.6, rearm_s: float = 0.012,
                 lookback_s: float = 0.003, release_s: float = 0.030) -> int:
    """GATECRASH's key, in miniature: an attack is where the level leads the held peak of a
    moment ago (lookback_s) by lead_db. A smeared ratchet never leads its held peak, so it
    counts as one attack."""
    env = np.abs(x)
    release = math.exp(-1 / (release_s * rate))
    held = np.empty(len(env))
    h = 0.0
    for i, v in enumerate(env):
        h = max(v, h * release)
        held[i] = h
    look = int(lookback_s * rate)
    lead = 10 ** (lead_db / 20)
    count = 0
    armed = 0
    for i in range(look, len(env)):
        if armed <= 0 and env[i] > 0.05 and env[i] > held[i - look] * lead:
            count += 1
            armed = int(rearm_s * rate)
        armed -= 1
    return count


def dip_db(x: np.ndarray, period_s: float, count: int, rate: int = RATE) -> float:
    """The deepest trough between consecutive hits against the hit peak, in dB (more negative
    is cleaner). Measured on a 2 ms RMS envelope."""
    env = pr.envelope(x, rate, block_ms=2.0)
    per_block = period_s * rate / (0.002 * rate)
    worst = 0.0
    for i in range(count - 1):
        a = int(i * per_block)
        b = int((i + 1) * per_block)
        seg = env[a:b]
        if len(seg) < 3:
            continue
        pk = float(seg.max()) or 1e-9
        trough = float(seg[len(seg) // 2:].min())
        worst = min(worst, 20 * math.log10(max(trough, 1e-9) / pk))
    return round(worst, 1)


# ----------------------------------------------------------------------------- main

def main(out_dir: str) -> int:
    os.makedirs(out_dir, exist_ok=True)
    results: dict = {"slide": {}, "slide_velocity": {}, "slide_takes": {}, "slide_drive": {}, "ratchet": {}}

    # 1. The three slide shapes the kits use: straight (no slide), down a fourth, up an octave,
    #    and the drill two-way (down a fifth, late).
    shapes = {
        "straight": {"SLIDE": 0.5},
        "down_fourth": {"SLIDE": 0.5 - 5 / 24},
        "up_octave": {"SLIDE": 1.0, "WHEN": 0.35},
        "drill_down_fifth_late": {"SLIDE": 0.5 - 7 / 24, "WHEN": 0.7, "DECAY": 0.75},
        "trap_short_punch": {"SLIDE": 0.5, "DECAY": 0.3, "DRIVE": 0.6, "CLICK": 0.7},
    }
    for name, macros in shapes.items():
        results["slide"][name] = slide_report("SLIDE_" + name, macros, out_dir=out_dir)

    # 2. Velocity: a soft, a medium and a hard hit of the down-fourth slide, each levelled to the
    #    same peak so what differs is character. Distances are log-spectral, the house probe.
    base = shapes["down_fourth"]
    hits = {v: finish_peak(render_slide(base, v, 0)) for v in (0.3, 0.65, 1.0)}
    for v, x in hits.items():
        results["slide_velocity"][f"v{v}"] = {
            "share_150_400Hz": round(band_share(x, 150, 400), 4),
            "centroid_hz": round(pr.spectral_centroid(x, RATE), 1),
            "click_share_2k_8k": round(band_share(x[: int(0.03 * RATE)], 2000, 8000), 4),
            "vs_hard_dB": round(pr.macro_activity(x, hits[1.0]), 2),
        }
        sk.write_wav(os.path.join(out_dir, f"SLIDE_down_fourth_v{v}.wav"), x)

    # 3. Takes: four strikes of the same drum at one velocity. Each pair's distance, and the
    #    root of each (a take must not detune the sub).
    takes = [finish_peak(render_slide(base, 1.0, t)) for t in range(4)]
    pairs = {}
    for i in range(4):
        for j in range(i + 1, 4):
            pairs[f"{i}-{j}"] = round(pr.macro_activity(takes[i], takes[j]), 2)
        sk.write_wav(os.path.join(out_dir, f"SLIDE_down_fourth_take{i}.wav"), takes[i])
    roots = [round(pitch_window(x, 0.04, 0.10)[0], 2) for x in takes]
    results["slide_takes"] = {"pair_distance_dB": pairs, "root_hz_per_take": roots,
                              "closest_pair_dB": min(pairs.values()), "root_spread_cents":
                              round(1200 * math.log2(max(roots) / min(roots)), 2) if min(roots) > 0 else None}

    # 3b. DRIVE: the share of the drum between 150 and 400 Hz, where a phone plays (the SUB
    #     pedal lesson: a clean sub under 60 Hz is weight a phone does not reproduce).
    results["slide_drive"] = {}
    for d in (0.0, 0.25, 0.5, 0.75, 1.0):
        x = finish_peak(render_slide({**base, "DRIVE": d}, 1.0, 0))
        results["slide_drive"][f"drive{d}"] = {
            "share_150_400Hz": round(band_share(x, 150, 400), 4),
            "share_under_100Hz": round(band_share(x, 0, 100), 4),
            "centroid_hz": round(pr.spectral_centroid(x, RATE), 1),
            "vs_drive0_dB": round(pr.macro_activity(x, finish_peak(render_slide({**base, "DRIVE": 0.0}, 1.0, 0))), 2),
        }
        sk.write_wav(os.path.join(out_dir, f"SLIDE_down_fourth_drive{d}.wav"), x)

    # 4. The hat ratchet at 140 BPM: 1/32, 1/32 triplet and 1/64, for three hat decays.
    bpm = 140.0
    beat = 60 / bpm
    grids = {"1/32": beat / 8, "1/32T": beat / 12, "1/64": beat / 16}
    for t60 in (0.04, 0.08, 0.15):
        for gname, period in grids.items():
            count = 8
            x = ratchet(period, count, t60)
            key = f"t60_{int(t60 * 1000)}ms_{gname}"
            results["ratchet"][key] = {
                "period_ms": round(period * 1000, 1), "played": count,
                "heard": onsets_heard(x), "worst_dip_dB": dip_db(x, period, count),
                "t60_over_period": round(t60 / period, 2),
            }
            sk.write_wav(os.path.join(out_dir, f"RATCHET_{key.replace('/', '')}.wav"), x)

    with open(os.path.join(out_dir, "report.json"), "w") as f:
        json.dump(results, f, indent=2)
    print(json.dumps(results, indent=2))
    print(f"\nwrote {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "cypher_out"))
