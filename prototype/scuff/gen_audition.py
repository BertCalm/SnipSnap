"""Render the SCUFF round-0 audition: WAVs per section plus manifest.json with measurements.

Python harness, not Kotlin. Every one-shot is peak-matched (0.89) and every loop too (0.95),
so a later stage never wins by being louder. Run:  python3 gen_audition.py OUT_DIR
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import sys

import numpy as np

import scuff_proto as e
import snipkit as sk
import probes as pr

OUT = sys.argv[1] if len(sys.argv) > 1 else "scuff-audition"
MANIFEST: dict = {"sections": [], "checks": {}}
RATE = e.RATE


# ------------------------------------------------------------------ measures

def spectrum(x):
    X = np.abs(np.fft.rfft(x * np.hanning(len(x)))) ** 2
    f = np.fft.rfftfreq(len(x), 1 / RATE)
    return f, X


def centroid(x, lo=150.0, hi=12000.0):
    f, X = spectrum(x)
    X = np.sqrt(X)
    b = (f > lo) & (f < hi)
    return float((X[b] * f[b]).sum() / (X[b].sum() + 1e-12))


def band_db(x, lo, hi, ref_lo=30.0, ref_hi=16000.0):
    """Energy in lo..hi as dB of ref_lo..ref_hi."""
    f, X = spectrum(x)
    num = X[(f >= lo) & (f < hi)].sum()
    den = X[(f >= ref_lo) & (f < ref_hi)].sum()
    return float(10 * math.log10((num + 1e-18) / (den + 1e-18)))


def dominant_hz(x, lo=40.0, hi=16000.0):
    f, X = spectrum(x)
    b = (f >= lo) & (f < hi)
    return float(f[b][np.argmax(X[b])])


def two_peaks_hz(x, lo=500.0, hi=2500.0, apart=200.0):
    f, X = spectrum(x)
    b = (f >= lo) & (f < hi)
    f, X = f[b], X[b]
    i = int(np.argmax(X))
    mask = np.abs(f - f[i]) > apart
    j = int(np.argmax(np.where(mask, X, 0)))
    return sorted([round(float(f[i])), round(float(f[j]))])


def window_hz(x, t0, t1, lo=40.0, hi=4000.0):
    """Dominant frequency inside a time window: the pitch of a sweep at that moment."""
    a, b = int(t0 * RATE), int(t1 * RATE)
    seg = x[a:b]
    if len(seg) < 64:
        return float("nan")
    seg = np.concatenate([seg * np.hanning(len(seg)), np.zeros(1 << 15)])
    f = np.fft.rfftfreq(len(seg), 1 / RATE)
    X = np.abs(np.fft.rfft(seg))
    m = (f >= lo) & (f < hi)
    return round(float(f[m][np.argmax(X[m])]), 1)


def decay60(x):
    return round(pr.decay_seconds(x, RATE) * 1000.0, 1)  # ms


def crest_db(x):
    return round(float(20 * np.log10(np.max(np.abs(x)) / (np.sqrt(np.mean(x ** 2)) + 1e-12))), 1)


def plateau_pct(x, ms=120.0, frac=0.7):
    """How much of the first `ms` sits within 3 dB of the peak: the square-topped signature."""
    seg = np.abs(x[:int(ms * 1e-3 * RATE)])
    return round(float(100.0 * np.mean(seg > frac * seg.max())), 1)


def onsets_ms(x, min_gap_ms=6.0, first_ms=45.0):
    """Peaks of a 1 ms RMS envelope in the first `first_ms`, at least min_gap apart, above
    -12 dB of the loudest: the clap's taps."""
    x = x[:int(first_ms * 1e-3 * RATE)]
    n = int(0.001 * RATE)
    env = np.sqrt(np.convolve(x * x, np.ones(n) / n, mode="same"))
    thr = env.max() * 0.25
    out, last = [], -1e9
    for i in range(1, len(env) - 1):
        if env[i] > thr and env[i] >= env[i - 1] and env[i] > env[i + 1] and (i - last) > min_gap_ms * 1e-3 * RATE:
            out.append(round(1000.0 * i / RATE, 1))
            last = i
    return out


THIRD_OCT = 80.0 * 2 ** (np.arange(0, 22) / 3)   # 80 Hz to ~10 kHz


def spectro_db(x, ms=5, total=0.15):
    x = x[:int(total * RATE)]
    x = x / (np.sqrt(np.mean(x ** 2)) + 1e-12)
    n = int(ms * 1e-3 * RATE)
    w = 4 * n
    f = np.fft.rfftfreq(w, 1 / RATE)
    win = np.hanning(w)
    rows = []
    for i in range(0, max(1, len(x) - w), n):
        X = np.abs(np.fft.rfft(x[i:i + w] * win)) ** 2
        rows.append([10 * math.log10(X[(f >= c / 2 ** (1 / 6)) & (f < c * 2 ** (1 / 6))].sum() + 1e-9) for c in THIRD_OCT])
    return np.array(rows)


def take_distance_db(a, b):
    """RMS difference of two strikes' level-matched third-octave spectrograms over their
    first 150 ms, dB. ETCH's measure, with 5 ms frames because drums are short. Takes the
    owner heard as one strike (ETCH round 1) measured 0.5 to 0.8 dB on the 10 ms version."""
    A, B = spectro_db(a), spectro_db(b)
    m = min(len(A), len(B))
    return round(float(np.sqrt(np.mean((A[:m] - B[:m]) ** 2))), 2)


def attack_centroid(x, ms=30.0):
    return round(centroid(x[:int(ms * 1e-3 * RATE)]))


def basic(x):
    return {"centroid_hz": round(centroid(x)), "decay_ms": decay60(x), "crest_db": crest_db(x),
            "peak": round(float(np.max(np.abs(x))), 3), "dc": round(float(np.mean(x)), 5)}


# ------------------------------------------------------------------ plumbing

def save(section, name, y, note="", metrics=None):
    os.makedirs(os.path.join(OUT, section["id"]), exist_ok=True)
    path = f"{section['id']}/{name}.wav"
    sk.write_wav(os.path.join(OUT, path), y)
    section["clips"].append({"file": path, "name": name, "note": note,
                             "seconds": round(len(y) / RATE, 2), "metrics": metrics or {}})
    return y


def section(sid, title, intro):
    s = {"id": sid, "title": title, "intro": intro, "clips": []}
    MANIFEST["sections"].append(s)
    return s


def twice(y, gap_s=0.35):
    """A one-shot played twice with a gap, so a short hit can be heard."""
    g = np.zeros(int(gap_s * RATE))
    return np.concatenate([y, g, y, g])


VOICE_NOTE = {
    "KICK": "Punch kick: 80 to 160 Hz body, a 250 to 95 Hz drop in about 20 ms, a 3 kHz click, squared-off tail.",
    "CLAP": "Flint clap: four micro-impulses 10 ms apart into a 77 ms noise burst, high-passed at 250 Hz, peak near 1.5 kHz.",
    "BOOM": "Bass hit: 60 Hz with a fast pitch punch, saturated so 100 to 300 Hz carry the note, muted hard at 285 ms.",
    "RIM": "Stank rim: a 2 ms bipolar impulse into resonators at 850 and 1350 Hz, 56 ms ring, overdriven.",
    "HAT": "Chopper hat: six squares high-passed at 5 kHz with a 9 kHz ring, 35 ms, clipped clean.",
    "BELL": "Bando bell: two-operator FM at 1 : 1.414, 740 ms, drifting 12 cents flat over its tail.",
    "ZAP": "Laser tom: 1.2 kHz to 120 Hz over 100 ms, lightly clipped.",
    "TICK": "Money counter: 7 kHz+ noise, 13 ms, no crush (the brief keeps GRIT for claps, rims and hats).",
    "PLUCK": "Hollow conga: one sine at 260 Hz, up 30 Hz in 8 ms then down 80 Hz, no noise.",
    "INHALE": "Suck-back crash: ring-modulated metal noise rising over 316 ms, cut at a zero crossing.",
}


def voice_metrics(v, y):
    m = basic(y)
    if v == "KICK":
        m.update({"hz_at_5ms": window_hz(y, 0.0, 0.010), "hz_at_50ms": window_hz(y, 0.040, 0.090),
                  "body_80_160_db": round(band_db(y, 80, 160), 1), "sub_below_45_db": round(band_db(y, 20, 45), 1),
                  "plateau_pct": plateau_pct(y)})
    elif v == "CLAP":
        m.update({"onsets_ms": onsets_ms(y), "below_250_db": round(band_db(y, 20, 250), 1),
                  "peak_band_hz": round(dominant_hz(y, 300, 6000))})
    elif v == "BOOM":
        m.update({"hz_at_5ms": window_hz(y, 0.0, 0.012, 40, 600), "hz_at_100ms": window_hz(y, 0.08, 0.16, 40, 600),
                  "harmonics_100_300_db": round(band_db(y, 100, 300), 1), "sub_45_80_db": round(band_db(y, 45, 80), 1)})
    elif v == "RIM":
        m.update({"peaks_hz": two_peaks_hz(y), "band_700_1800_db": round(band_db(y, 700, 1800), 1)})
    elif v == "HAT":
        m.update({"below_4k_db": round(band_db(y, 20, 4000), 1), "ring_hz": round(dominant_hz(y, 5000, 14000))})
    elif v == "BELL":
        fc = e.expmap(e.macros_for("BELL")["TUNE"], 300.0, 1200.0)
        hz_end = window_hz(y, 0.55, 0.72, fc * 0.9, fc * 1.1)
        m.update({"hz_at_end": hz_end, "drift_cents": round(1200 * math.log2(hz_end / fc), 1)})
    elif v == "ZAP":
        m.update({"hz_at_5ms": window_hz(y, 0.0, 0.012, 60, 3000), "hz_at_90ms": window_hz(y, 0.085, 0.105, 60, 3000)})
    elif v == "TICK":
        m.update({"below_7k_db": round(band_db(y, 20, 7000), 1)})
    elif v == "PLUCK":
        m.update({"hz_at_2ms": window_hz(y, 0.0, 0.006, 100, 600), "hz_at_8ms": window_hz(y, 0.006, 0.012, 100, 600),
                  "hz_at_80ms": window_hz(y, 0.06, 0.11, 100, 600)})
    elif v == "INHALE":
        env = np.abs(y)
        n20 = int(np.argmax(env > 0.1 * env.max()))
        m.update({"rise_ms": round(1000.0 * (len(y) - n20) / RATE, 1), "last_sample": round(float(y[-1]), 4)})
    return m


# ------------------------------------------------------------------ 1. the kit
s = section("kit", "The kit: ten pads at the defaults",
            "Every pad through its whole chain (GRIT where the brief wants it, LOCKER and BARK as "
            "parallel sends, SLAM on everything), peak-matched. Each short hit plays twice.")
KIT = {}
for v in e.VOICES:
    y = e.render_pad(v)
    KIT[v] = y
    save(s, v.lower(), twice(y), VOICE_NOTE[v], voice_metrics(v, y))

# ------------------------------------------------------------------ 2. the chain, one stage at a time
for v in ("RIM", "CLAP"):
    s = section(f"chain-{v.lower()}", f"{v}: the chain, one stage at a time",
                "The same hit with each stage switched in: dry voice, then GRIT (sample-and-hold and 10 to "
                "12 bits), then LOCKER (the comb slapback send), then BARK (the cocked band-pass), then "
                "SLAM (the bus clipper). Peak-matched.")
    chain = [("1-dry", (), "The voice alone."), ("2-grit", ("grit",), "Plus GRIT."),
             ("3-locker", ("grit", "locker"), "Plus LOCKER."), ("4-bark", ("grit", "locker", "bark"), "Plus BARK."),
             ("5-slam", ("grit", "locker", "bark", "slam"), "Plus SLAM: the whole chain.")]
    for name, st, note in chain:
        y = e.render_pad(v, stages=st)
        m = basic(y)
        m["band_700_1800_db"] = round(band_db(y, 700, 1800), 1)
        m["plateau_pct"] = plateau_pct(y, ms=60)
        save(s, name, twice(y), note, m)

# ------------------------------------------------------------------ 3. the kick and the bass fuse
s = section("fuse", "SLAM: the kick and the bass fuse",
            "The brief's defining move: the kick and the bass hit land together and go into the bus "
            "clipper, so their low end squares off into one growl. One hit of both, then two bars, at "
            "three SLAM settings. The clipped path is low-passed at 600 Hz, so the growl is low-mid, "
            "not fizz. Peak-matched, so listen for density, not loudness.")
pair_dry = np.zeros(int(0.5 * RATE))
for v in ("KICK", "BOOM"):
    y = e.render_pad(v, stages=("grit", "locker", "bark"), level=False)
    y = y * (0.89 / np.max(np.abs(y))) * e.PAD_GAIN[v]
    pair_dry[:len(y)] += y[:len(pair_dry)]
FUSE = {}
for amt in (0.0, 0.55, 0.9):
    y = e.slam(pair_dry, amt) if amt > 0 else pair_dry
    y = y[:len(pair_dry)]
    y = y * (0.89 / np.max(np.abs(y)))
    m = {"crest_db": crest_db(y), "harmonics_100_300_db": round(band_db(y, 100, 300), 1),
         "plateau_pct": plateau_pct(y, ms=150), "centroid_hz": round(centroid(y))}
    FUSE[amt] = m
    save(s, f"pair-slam-{amt:.2f}", twice(y, 0.25), f"Kick and bass together, SLAM {amt:.2f}.", m)
for amt in (0.0, 0.55, 0.9):
    y = e.render_pattern(bus_slam=amt)
    m = {"crest_db": crest_db(y), "harmonics_100_300_db": round(band_db(y, 100, 300), 1),
         "plateau_pct": plateau_pct(y, ms=4000, frac=0.85)}
    save(s, f"bars-slam-{amt:.2f}", np.concatenate([y, y]), f"Two bars at {e.BPM:.0f} BPM, SLAM {amt:.2f} on the bus, played twice.", m)
MANIFEST["checks"]["fuse"] = {str(k): v for k, v in FUSE.items()}

# ------------------------------------------------------------------ 4. the chain macros
s = section("grit", "GRIT on the clap, rim and hat",
            "Sample-and-hold without an anti-aliasing filter plus bit reduction, deeper on a harder hit. "
            "0 is a bypass. At 0.9 the hold runs near 14 kHz and the word is 9 bits.")
for v in ("CLAP", "RIM", "HAT"):
    for amt in (0.0, 0.45, 0.9):
        y = e.render_pad(v, {"GRIT": amt})
        save(s, f"{v.lower()}-{amt:.2f}", twice(y), f"{v}, GRIT {amt:.2f}.", {"centroid_hz": round(centroid(y)), "above_8k_db": round(band_db(y, 8000, 16000), 1)})
s = section("locker", "LOCKER on the rim and the clap",
            "A feedback comb (11 ms on the rim, 19 ms on the clap) with a band-pass inside the loop, as a "
            "parallel send. The box, not a tail. 0 is a bypass; at 1 the feedback is 0.70, just under ringing.")
for v in ("RIM", "CLAP"):
    for amt in (0.0, 0.3, 0.7, 1.0):
        y = e.render_pad(v, {"LOCKER": amt})
        save(s, f"{v.lower()}-{amt:.2f}", twice(y), f"{v}, LOCKER {amt:.2f}.", {"decay_ms": decay60(y), "band_700_1800_db": round(band_db(y, 700, 1800), 1)})
s = section("bark", "BARK on the rim, clap and pluck",
            "A band-pass parked at 1.1 kHz, Q 3.5 to 6, blended in parallel up to half. "
            "The nasal mid that sits beside a voice. 0 is a bypass.")
for v in ("RIM", "CLAP", "PLUCK"):
    for amt in (0.0, 0.35, 0.8):
        y = e.render_pad(v, {"BARK": amt})
        save(s, f"{v.lower()}-{amt:.2f}", twice(y), f"{v}, BARK {amt:.2f}.", {"centroid_hz": round(centroid(y)), "band_900_1400_db": round(band_db(y, 900, 1400), 1)})
s = section("slam", "SLAM on single pads",
            "The bus clipper on one pad at a time: kick, bass hit, hat. Peak-matched, so the change "
            "is in density and low-mid harmonics.")
for v in ("KICK", "BOOM", "HAT"):
    for amt in (0.0, 0.55, 0.9):
        y = e.render_pad(v, {"SLAM": amt})
        save(s, f"{v.lower()}-{amt:.2f}", twice(y), f"{v}, SLAM {amt:.2f}.", {"crest_db": crest_db(y), "plateau_pct": plateau_pct(y), "harmonics_100_300_db": round(band_db(y, 100, 300), 1)})

# ------------------------------------------------------------------ 5. TUNE and DECAY
s = section("shape", "TUNE and DECAY on the kick, bass hit and rim",
            "The per-voice knobs THUMP also has. TUNE keeps each voice in its register (the kick 70 to "
            "140 Hz, the bass 45 to 80 Hz, the rim 600 to 1200 Hz); DECAY stays inside the brief's windows.")
for v in ("KICK", "BOOM", "RIM"):
    for knob in ("TUNE", "DECAY"):
        for amt in (0.1, 0.5, 0.9):
            y = e.render_pad(v, {knob: amt})
            mm = {"decay_ms": decay60(y)}
            if v == "RIM":
                mm["peaks_hz"] = two_peaks_hz(y)
            else:
                mm["hz_at_50ms"] = window_hz(y, 0.04, 0.09, 40, 600)
            save(s, f"{v.lower()}-{knob.lower()}-{amt:.1f}", twice(y), f"{v}, {knob} {amt:.1f}.", mm)

# ------------------------------------------------------------------ 6. the pattern
s = section("pattern", "Two bars, the parts in and out",
            f"{e.BPM:.0f} BPM on a straight sixteenth grid, no swing. Kick, clap with ghosts, bass hits with "
            "octave jumps, rims on the e's and a's, straight-eighth hats with one 32nd burst and a "
            "tape-stopped hat at the end, a tick under it all, the bell on the one, the zap before bar 2's "
            "clap, the crash sucked back into bar 2. Each loop plays twice.")
PAT = e.render_pattern()
save(s, "full", np.concatenate([PAT, PAT]), "Everything, SLAM on the bus at its default.", {"crest_db": crest_db(PAT), "harmonics_100_300_db": round(band_db(PAT, 100, 300), 1)})
y = e.render_pattern(stages=("grit", "locker", "bark"), bus_slam=0.0)
save(s, "no-slam", np.concatenate([y, y]), "The same bars with the bus clipper off.", {"crest_db": crest_db(y)})
y = e.render_pattern(stages=(), bus_slam=0.0)
save(s, "voices-only", np.concatenate([y, y]), "Voices only: no GRIT, LOCKER, BARK or SLAM anywhere.", {"crest_db": crest_db(y)})
y = e.render_pattern(drop_hat=False, inhale_in=False, burst=False)
save(s, "no-ear-candy", np.concatenate([y, y]), "Without the 32nd burst, the tape-stopped hat and the suck-back.", {"crest_db": crest_db(y)})

# ------------------------------------------------------------------ 7. rare voices and rack effects
s = section("rare", "The rare voices",
            "The five ear-candy voices on their own, each twice: bell, zap, tick, pluck, suck-back.")
for v in e.RARE:
    y = KIT[v]
    save(s, v.lower(), twice(y, 0.5), VOICE_NOTE[v], voice_metrics(v, y))
s = section("rack", "DROP and CHOKE, from the rack",
            "Two effects the brief names that live outside the engine: DROP, a tape stop (the read head slows "
            "to half speed over 90 ms, then mutes), on a long hat; CHOKE, a dense slammed burst into a gate with "
            "an 80 ms hold and 5 ms release, on the clap and the bell. DIVE already does DROP's move in the rack; "
            "GATECRASH's space already keys a room from the dry hit.")
y = e.render_pad("HAT", {"DECAY": 0.9}, level=False)
y = e.finish(e.drop(y, over_s=0.09))
save(s, "drop-hat", twice(y), "A long hat through DROP.", {"hz_at_start": window_hz(y, 0, 0.02, 4000, 16000), "hz_at_end": window_hz(y, 0.06, 0.085, 2000, 16000), "decay_ms": decay60(y)})
for v in ("CLAP", "BELL"):
    y = e.finish(e.choke(KIT[v]))
    save(s, f"choke-{v.lower()}", twice(y, 0.5), f"{v} through CHOKE.", {"decay_ms": decay60(y), "crest_db": crest_db(y)})

# ------------------------------------------------------------------ 8. velocity
s = section("velocity", "Velocity on every voice",
            "Each voice at velocity 0.4, 0.7 and 1.0, peak-matched: what should change is the tone. The kick "
            "drops further and clicks harder; the bass drives harder; the clap and hat brighten; GRIT bites "
            "deeper on the crushed voices; the rim's overdrive grows.")
VEL = {}
for v in e.VOICES:
    row = {}
    for vel in (0.4, 0.7, 1.0):
        y = e.render_pad(v, vel=vel)
        row[str(vel)] = {"attack_hz": attack_centroid(y), "centroid_hz": round(centroid(y)), "crest_db": crest_db(y)}
        save(s, f"{v.lower()}-{vel:.1f}", twice(y), f"{v} at velocity {vel:.1f}.", row[str(vel)])
    row["soft_vs_hard_db"] = take_distance_db(e.render_pad(v, vel=0.4), e.render_pad(v, vel=1.0))
    VEL[v] = row
MANIFEST["checks"]["velocity"] = VEL

# ------------------------------------------------------------------ 9. round robin
s = section("round-robin", "Round robin: four takes of every voice",
            "Four takes at velocity 0.8, dealt on four dimensions so no two share a setting (the kick's "
            "drop depth and speed, click timing and decay; the hat's free-running oscillator phases; the "
            "clap's tap spacing and noise; the rim's two resonator pitches). Each clip plays the four takes "
            "in a row. The last two clips are the A/B: the rim as one take repeated four times, then four takes.")
RR = {}
for v in e.VOICES:
    takes = [e.render_pad(v, vel=0.8, take=k) for k in (1, 2, 3, 4)]
    d = [take_distance_db(takes[i], takes[j]) for i in range(4) for j in range(i + 1, 4)]
    RR[v] = {"closest_db": min(d), "widest_db": max(d), "velocity_step_db": VEL[v]["soft_vs_hard_db"]}
    g = np.zeros(int(0.25 * RATE))
    save(s, v.lower(), np.concatenate(sum(([t, g] for t in takes), [])), f"{v}: takes 1 to 4.", {"closest_takes_db": min(d), "widest_takes_db": max(d)})
MANIFEST["checks"]["round_robin"] = RR
g = np.zeros(int(0.25 * RATE))
one = e.render_pad("RIM", vel=0.8, take=1)
save(s, "ab-a-rim-one-take-x4", np.concatenate([one, g] * 4), "A: one rim take, four times.", {})
save(s, "ab-b-rim-four-takes", np.concatenate(sum(([e.render_pad("RIM", vel=0.8, take=k), g] for k in (1, 2, 3, 4)), [])), "B: four rim takes.", {})

# ------------------------------------------------------------------ checks
clips = [c for sct in MANIFEST["sections"] for c in sct["clips"]]
allfin = True
for c in clips:
    x, _ = sk.read_wav(os.path.join(OUT, c["file"]))
    allfin &= bool(np.all(np.isfinite(x)))


def digest(y):
    return hashlib.sha256(y.tobytes()).hexdigest()


det = all(digest(e.render_pad(v)) == digest(KIT[v]) for v in e.VOICES)
det_loop = digest(e.render_pattern()) == digest(PAT)
MANIFEST["checks"].update({"clips": len(clips), "all_finite": allfin, "determinism_pads": det,
                           "determinism_pattern": det_loop, "bpm": e.BPM,
                           "kit": {v: voice_metrics(v, KIT[v]) for v in e.VOICES}})
json.dump(MANIFEST, open(os.path.join(OUT, "manifest.json"), "w"), indent=1)
print("clips", len(clips), "finite", allfin, "deterministic", det, det_loop)
for v in e.VOICES:
    print(v, voice_metrics(v, KIT[v]))
print("fuse", FUSE)
print("velocity", {v: VEL[v]["soft_vs_hard_db"] for v in e.VOICES})
print("rr", RR)
