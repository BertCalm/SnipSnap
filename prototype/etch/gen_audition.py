"""Render the ETCH round-1 audition: WAVs per section plus manifest.json with measurements.

Python harness, not Kotlin. Every clip is level-matched (same RMS) unless its note says
otherwise, so a later stage never wins by being louder.

Run:  python3 gen_audition.py OUT_DIR
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import sys

import numpy as np

import etch_proto as e
import snipkit as sk

OUT = sys.argv[1] if len(sys.argv) > 1 else "etch-audition"
MANIFEST: dict = {"sections": [], "checks": {}}


def band_centroid(x, lo=150.0, hi=5000.0):
    """Magnitude-weighted centroid in 150 Hz..5 kHz: the same band the references were
    measured in, so the numbers compare."""
    X = np.abs(np.fft.rfft(x * np.hanning(len(x))))
    f = np.fft.rfftfreq(len(x), 1 / e.RATE)
    b = (f > lo) & (f < hi)
    return float((X[b] * f[b]).sum() / (X[b].sum() + 1e-12))


def upper_db(x, edge=2000.0):
    """Energy above 2 kHz as dB of 150 Hz..10 kHz: how open the filter is. Unlike the
    centroid, this rises with CUTOFF on this voice (checked across the knob)."""
    X = np.abs(np.fft.rfft(x * np.hanning(len(x)))) ** 2
    f = np.fft.rfftfreq(len(x), 1 / e.RATE)
    return float(10 * math.log10(X[(f > edge) & (f < 10000)].sum() / X[(f > 150) & (f < 10000)].sum()))


def second_harmonic_db(x, f0):
    """Level of the 2nd harmonic relative to the fundamental, dB. A saw has it at about
    -6 dB; a 50% square has almost none. The clearest single number for saw vs square."""
    X = np.abs(np.fft.rfft(x * np.hanning(len(x))))
    f = np.fft.rfftfreq(len(x), 1 / e.RATE)

    def at(hz):
        i = int(np.argmin(np.abs(f - hz)))
        return float(X[i - 3:i + 4].max())
    return round(20 * math.log10(at(2 * f0) / at(f0)), 1)


def save(section, name, y, note="", metrics=None, level=True):
    os.makedirs(os.path.join(OUT, section["id"]), exist_ok=True)
    yy = e.finish(y) if level else sk.fade_tail(sk.dc_block(y, e.RATE), e.RATE)
    if not level:
        yy = yy * (0.95 / max(1e-9, float(np.max(np.abs(yy)))))
    path = f"{section['id']}/{name}.wav"
    sk.write_wav(os.path.join(OUT, path), yy)
    section["clips"].append({"file": path, "name": name, "note": note,
                             "seconds": round(len(yy) / e.RATE, 2), "metrics": metrics or {}})
    return yy


def section(sid, title, intro):
    s = {"id": sid, "title": title, "intro": intro, "clips": []}
    MANIFEST["sections"].append(s)
    return s


def note_peaks(out, line, bars):
    """Peak (dB) at the start of every step that begins a note (not rests, not slid-into
    steps, not delay tails): how even the played steps are, and how far accents stand out."""
    st = line["steps"] * bars
    step = len(out) // len(st)
    pk, acc, plain = [], [], []
    for j, s_ in enumerate(st):
        prev = st[j - 1]
        if s_.note is None or (prev.note is not None and prev.slide):
            continue
        v = 20 * math.log10(float(np.max(np.abs(out[j * step:j * step + int(step * 0.6)]))) + 1e-9)
        pk.append(v)
        (acc if s_.vel >= 1.0 else plain).append(v)
    lift = float(np.mean(acc) - np.mean(plain)) if acc and plain else float("nan")
    return float(np.std(pk)), lift


def loop_metrics(out, line, bars):
    spread, lift = note_peaks(out, line, bars)
    return {"band_centroid_hz": round(band_centroid(out)), "upper_db": round(upper_db(out), 1), "crest_db": round(e.crest_db(out), 2),
            "note_peak_spread_db": round(spread, 2), "accent_lift_db": round(lift, 2)}


def line_with(base, **kw):
    d = dict(base)
    d.update(kw)
    return d


# ------------------------------------------------------------------ 1. the chain, stage by stage
for L in (e.LINE_A, e.LINE_B):
    tag = L["name"][-1]
    s = section(f"chain-{tag.lower()}", f"{L['name']}: the chain, one stage at a time",
                f"{L['bpm']:.0f} BPM, {L['voice'].lower()} wave, root "
                f"{'F1' if L['root'] == 29 else 'C2'}. The same two-bar loop four ways, each "
                "played twice end to end so you also hear the loop point.")
    stages = [("1-voice", (), True, True, "The voice alone: oscillator, filter, accents, slides."),
              ("2-bite", ("bite",), True, True, "Plus BITE: the clean/drive blend."),
              ("3-bite-delay", ("bite", "space"), True, False, "Plus the dotted-eighth delay."),
              ("4-full", ("bite", "space"), True, True, "Plus the reverb after the delay: the whole chain.")]
    for name, st, dl, rv, note in stages:
        out, full, n = e.render_loop(L, stages=st, delay=dl, reverb=rv)
        m = loop_metrics(out, L, 2)
        m["seam_error"] = float(f"{sk.seam_error(full, len(full) - n):.2e}")
        save(s, f"{tag}{name}", np.concatenate([out, out]), note, m)

# ------------------------------------------------------------------ 2. the cutoff ride
s = section("ride", "The cutoff ride (the hand on the knob in reference B)",
            "LINE B over four bars, starting dark (CUTOFF 0.05, SWEEP 0.35): CUTOFF climbs to 0.8 over three bars and comes back in the fourth, "
            "so the loop still closes. Reference B measured 1.0 to 2.6 kHz over its ride.")


def ride(t):
    up = 0.75 * (0.5 - 0.5 * math.cos(math.pi * min(1.0, t / 0.75)))
    return up if t < 0.75 else 0.75 * (0.5 + 0.5 * math.cos(math.pi * (t - 0.75) / 0.25))


RIDE_START = {"CUTOFF": 0.05, "SWEEP": 0.35}
for name, st in (("ride-voice-bite", ("bite",)), ("ride-full", ("bite", "space"))):
    out, full, n = e.render_loop(e.LINE_B, RIDE_START, stages=st, bars=4, ride=ride)
    bar = len(out) // 4
    m = {"band_centroid_by_bar_hz": [round(band_centroid(out[i * bar:(i + 1) * bar])) for i in range(4)],
         "upper_db_by_bar": [round(upper_db(out[i * bar:(i + 1) * bar]), 1) for i in range(4)],
         "seam_error": float(f"{sk.seam_error(full, len(full) - n):.2e}")}
    save(s, name, out, "Four bars, once." if "voice" in name else "Four bars, once, whole chain.", m)

# ------------------------------------------------------------------ 3. BITE alone
s = section("bite", "BITE: how hard the drive glues the steps",
            "LINE B, no SPACE, two bars. Level-matched, so listen for evenness and grit, not volume. "
            "Accent lift is how far accented steps stand above plain ones (dB); lower means the drive "
            "is compressing them, which is the glue.")
for v in (0.0, 0.25, 0.5, 0.8):
    out, full, n = e.render_loop(e.LINE_B, {"BITE": v}, stages=("bite",))
    save(s, f"bite-{int(v * 100):02d}", out, f"BITE {v}", loop_metrics(out, e.LINE_B, 2))

# ------------------------------------------------------------------ 4. SPACE
s = section("space", "SPACE: delay into reverb",
            "LINE A with BITE at its default. Two bars, played twice. SPACE raises delay level and "
            "repeats together and grows the room. The last three use SPACE 0.55 at other delay divisions.")
for v in (0.0, 0.3, 0.6, 0.9):
    out, full, n = e.render_loop(e.LINE_A, {"SPACE": v})
    save(s, f"space-{int(v * 100):02d}", np.concatenate([out, out]), f"SPACE {v}, dotted eighth",
         {"seam_error": float(f"{sk.seam_error(full, len(full) - n):.2e}")})
for div in ("1/8", "1/4", "1/16"):
    out, full, n = e.render_loop(e.LINE_A, {"SPACE": 0.55}, division=div)
    save(s, f"division-{div.replace('/', '-')}", np.concatenate([out, out]), f"SPACE 0.55, {div} delay")

# ------------------------------------------------------------------ 5. filter macros
s = section("filter", "CUTOFF, PEAK and SWEEP",
            "LINE B, BITE default, SPACE 0.15 so the filter stays in front. Two bars each.")
for mac, vals in (("CUTOFF", (0.1, 0.4, 0.75)), ("PEAK", (0.3, 0.65, 0.95)), ("SWEEP", (0.15, 0.5, 0.9))):
    for v in vals:
        out, full, n = e.render_loop(e.LINE_B, {mac: v, "SPACE": 0.15})
        save(s, f"{mac.lower()}-{int(v * 100):02d}", out, f"{mac} {v}",
             {"band_centroid_hz": round(band_centroid(out)), "upper_db": round(upper_db(out), 1)})

# ------------------------------------------------------------------ 6. accents that climb
s = section("accent", "Accents that build",
            "One bar of C2 at PEAK 0.85, BITE 0, SPACE 0. First: four accents in a row, then four "
            "plain steps. Second: the same four accents spread out. In a row, the accent capacitor "
            "has not discharged when the next accent lands, so each one opens further.")
acc_run = line_with(e.LINE_B, steps=e.parse("0a 0a 0a 0a 0 0 0 0 - - - - - - - -"),
                    macros={"CUTOFF": 0.2, "PEAK": 0.85, "SWEEP": 0.5, "BITE": 0.0, "SPACE": 0.0})
acc_spread = line_with(acc_run, steps=e.parse("0a 0 0 0 0a 0 0 0 0a 0 0 0 0a 0 0 0"))
for name, L in (("run-of-four", acc_run), ("spread-out", acc_spread)):
    out, full, n = e.render_loop(L, bars=1)
    step = len(out) // 16
    acc_steps = [0, 1, 2, 3] if name == "run-of-four" else [0, 4, 8, 12]
    m = {"accent_step_centroid_hz": [round(band_centroid(out[j * step:j * step + step // 2])) for j in acc_steps]}
    save(s, name, np.concatenate([out, out]), "Played twice.", m)

# ------------------------------------------------------------------ 7. slide
s = section("slide", "Slide",
            "The same eight notes without and with slides, at PEAK 0.7. A slide glides the pitch "
            "(a 22 ms RC, so it settles in about 66 ms), keeps the gate open, and does not restart "
            "the filter envelope.")
sl_off = line_with(e.LINE_B, steps=e.parse("0 12 0 15 7 12 0 13 0 12 0 15 7 12 0 13"),
                   macros={"CUTOFF": 0.3, "PEAK": 0.7, "SWEEP": 0.55, "BITE": 0.4, "SPACE": 0.0})
sl_on = line_with(sl_off, steps=e.parse("0s 12 0s 15 7s 12 0s 13 0s 12 0s 15 7s 12 0s 13"))
for name, L in (("no-slide", sl_off), ("with-slide", sl_on)):
    out, full, n = e.render_loop(L, bars=1)
    save(s, name, np.concatenate([out, out]), "Played twice.")

# ------------------------------------------------------------------ 7b. saw against square
s = section("waves", "Saw against square, same line",
            "LINE B's notes at CUTOFF 0.45, PEAK 0.6, BITE 0.3, no SPACE, so the filter lets "
            "enough harmonics through to hear the wave. Two bars each. The square has almost no "
            "even harmonics, so it should sound hollower and woodier than the saw.")
for voice in e.VOICES:
    L = line_with(e.LINE_B, voice=voice, macros={"CUTOFF": 0.45, "PEAK": 0.6, "SWEEP": 0.5, "BITE": 0.3, "SPACE": 0.0})
    out, full, n = e.render_loop(L)
    save(s, f"line-{voice.lower()}", out, f"{voice} wave", {"band_centroid_hz": round(band_centroid(out)),
                                                            "upper_db": round(upper_db(out), 1)})
for voice in e.VOICES:
    y = e.render_note(36, voice, {"CUTOFF": 0.5, "PEAK": 0.5, "SWEEP": 0.3, "BITE": 0.0}, gate_s=0.8, seconds=1.0)
    save(s, f"held-{voice.lower()}-C2", y, f"{voice} C2 held, filter open",
         {"second_harmonic_db": second_harmonic_db(y[int(0.3 * e.RATE):int(0.75 * e.RATE)], 65.41)})


# ------------------------------------------------------------------ 8. keygroup notes
s = section("notes", "Single notes (keygroup material)",
            "Each wave at C1, C2 and C3, velocity 0.6, gate 0.45 s, BITE default, no SPACE.")
for voice in e.VOICES:
    for midi, nm in ((24, "C1"), (36, "C2"), (48, "C3")):
        y = e.render_note(midi, voice, {})
        save(s, f"{voice.lower()}-{nm}", y, f"{voice} {nm}",
             {"attack_band_centroid_hz": round(band_centroid(y[:int(0.08 * e.RATE)]))})

# ------------------------------------------------------------------ 9. velocity
s = section("velocity", "Velocity is the accent",
            "SAW C2 at four velocities. The first four clips are level-matched, so only character "
            "differs. The last clip plays all four in order at their real relative levels.")
vels = (0.35, 0.6, 0.8, 1.0)
vel_raw = {}
for v in vels:
    y = e.render_note(36, "SAW", {"PEAK": 0.75}, vel=v)
    vel_raw[v] = y
    a = y[:int(0.06 * e.RATE)]
    late = y[int(0.2 * e.RATE):int(0.3 * e.RATE)]
    save(s, f"vel-{int(v * 100):03d}", y, f"velocity {v}",
         {"attack_band_centroid_hz": round(band_centroid(a)),
          "late_band_centroid_hz": round(band_centroid(late)),
          "raw_peak_db": round(20 * math.log10(float(np.max(np.abs(y)))), 2)})
seq = np.concatenate([np.concatenate([vel_raw[v], np.zeros(int(0.1 * e.RATE))]) for v in vels])
save(s, "vel-sequence-as-played", seq, "0.35, 0.6, 0.8, 1.0 at real relative levels", level=False)

# ------------------------------------------------------------------ 10. round robin
s = section("round-robin", "Round robin",
            "Four takes of SAW C2 at velocity 0.6, then four at 1.0. A take is the box's own wander: "
            "where the oscillator cycle starts, about 1.5% on how far the filter opens, about 6% on the decay. "
            "No detune. The last clip plays the eight takes back to back.")
takes = {}
for v in (0.6, 1.0):
    for k in range(1, 5):
        takes[(v, k)] = e.render_note(36, "SAW", {"PEAK": 0.75}, vel=v, seed=k)
rr_metrics = {}
for v in (0.6, 1.0):
    att = [band_centroid(takes[(v, k)][:int(0.06 * e.RATE)]) for k in range(1, 5)]
    late = [band_centroid(takes[(v, k)][int(0.2 * e.RATE):int(0.3 * e.RATE)]) for k in range(1, 5)]
    rr_metrics[str(v)] = {"attack_band_centroid_hz": [round(a) for a in att],
                          "late_band_centroid_hz": [round(a) for a in late],
                          "attack_spread_pct": round(100 * (max(att) - min(att)) / np.mean(att), 1),
                          "late_spread_pct": round(100 * (max(late) - min(late)) / np.mean(late), 1)}
for (v, k), y in takes.items():
    save(s, f"take-v{int(v * 100):03d}-{k}", y, f"velocity {v}, take {k}")
seq = np.concatenate([np.concatenate([takes[(v, k)][:int(0.32 * e.RATE)], np.zeros(int(0.05 * e.RATE))])
                      for v in (0.6, 1.0) for k in range(1, 5)])
save(s, "takes-back-to-back", seq, "eight takes at real relative levels", level=False)
MANIFEST["checks"]["round_robin"] = rr_metrics

# ------------------------------------------------------------------ checks
a1, _, _ = e.render_loop(e.LINE_A)
a2, _, _ = e.render_loop(e.LINE_A)
MANIFEST["checks"]["determinism_line_a"] = hashlib.sha256(a1.tobytes()).hexdigest() == \
    hashlib.sha256(a2.tobytes()).hexdigest()
allc = [c for s_ in MANIFEST["sections"] for c in s_["clips"]]
MANIFEST["checks"]["clips"] = len(allc)
MANIFEST["checks"]["all_finite"] = True
for c in allc:
    y, _ = sk.read_wav(os.path.join(OUT, c["file"]))
    if not np.all(np.isfinite(y)):
        MANIFEST["checks"]["all_finite"] = False
    c["metrics"]["peak"] = round(float(np.max(np.abs(y))), 3)
    c["metrics"]["dc"] = float(f"{float(np.mean(y)):.1e}")

with open(os.path.join(OUT, "manifest.json"), "w") as f:
    json.dump(MANIFEST, f, indent=1)
print(json.dumps(MANIFEST["checks"], indent=1))
print("clips:", len(allc))
