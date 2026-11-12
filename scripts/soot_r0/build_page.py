#!/usr/bin/env python3
"""Build SOOT's listening pages (round 0, and with --round 1 the Kotlin confirming listen): loudness-matched MP3s embedded in one HTML file.

Run:  python3 scripts/soot_r0/build_page.py [r0_dir] [boom_bap_dir] [out.html]
      python3 scripts/soot_r0/build_page.py --round 1 [r1_dir] [r0_dir] [out.html]
Needs numpy and ffmpeg. The Backroom references come from `:synth:generateBoomBapAudition`.
"""
from __future__ import annotations

import base64
import html
import json
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

RATE = 44_100
TARGET_DB = -23.0  # gated RMS every single clip is matched to
CEIL = 10 ** (-1 / 20)


def read(path: str) -> np.ndarray:
    with wave.open(path) as w:
        assert w.getnchannels() == 1 and w.getframerate() == RATE, path
        width = w.getsampwidth()
        raw = w.readframes(w.getnframes())
    if width == 2:
        return np.frombuffer(raw, "<i2") / 32768
    if width == 3:
        b = np.frombuffer(raw, np.uint8).reshape(-1, 3)
        v = (b[:, 0].astype(np.int32) | (b[:, 1].astype(np.int32) << 8) | (b[:, 2].astype(np.int32) << 16))
        v = np.where(v >= 1 << 23, v - (1 << 24), v)
        return v / float(1 << 23)
    raise ValueError(path)


def gated_rms_db(x: np.ndarray) -> float:
    b = int(.02 * RATE)
    n = max(1, len(x) // b)
    r = np.sqrt(np.mean(x[: n * b].reshape(n, b) ** 2, axis=1) + 1e-20)
    keep = r > np.max(r) * 10 ** (-30 / 20)
    return 20 * np.log10(np.sqrt(np.mean(r[keep] ** 2)))


def matched_mp3(x: np.ndarray) -> tuple[str, float]:
    g = 10 ** ((TARGET_DB - gated_rms_db(x)) / 20)
    y = x * g
    p = np.max(np.abs(y))
    trimmed = 0.0
    if p > CEIL:  # never clip: a clip that cannot reach the target plays quieter, and the page says so
        trimmed = 20 * np.log10(p / CEIL)
        y *= CEIL / p
    with tempfile.TemporaryDirectory() as d:
        wav, mp3 = os.path.join(d, "a.wav"), os.path.join(d, "a.mp3")
        with wave.open(wav, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
            w.writeframes(np.round(y * 32767).astype("<i2").tobytes())
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav, "-codec:a", "libmp3lame", "-b:a", "192k", mp3], check=True)
        with open(mp3, "rb") as f:
            return "data:audio/mpeg;base64," + base64.b64encode(f.read()).decode(), trimmed


QUESTIONS = [
    ("kick", "1 · The kick", "Membrane knock with a tension sag, a tuned sub an octave under it (F1, 43.7 Hz).",
     "Which kick? Does the sag read as a damp head giving way, or as a detuned drum?",
     ["k0", "k1", "k2", "k1_long", "k1_nosub"], "kick"),
    ("snare", "2 · The snare", "Papery stick crack, taut head, wires, a metal bench slap, the coil, the cassette edge.",
     "Which snare? Does the coil clatter like a spring trap, or just sound like a short reverb?",
     ["s_coil", "s_dry", "s_surf", "s_bench", "s_clean", "s_hot"], "snare"),
    ("hats", "3 · The hats", "No noise at all: six struck rods, each with a detuned twin, plus a dense cluster of metal partials.",
     "Eerie and clinical, or just thin? Which detune?",
     ["h_closed", "h_open", "h_closed_wide", "h_open_wide", "h_closed_pure"], "hat"),
    ("perc", "4 · Metal percussion", "Physical models of struck metal. Keep, change or drop each one.",
     "Which of these belong in the kit?", ["rod", "plate", "cup", "coil"], None),
    ("touch", "5 · Touch", "Soft (0.3), medium (0.6), hard (1.0), three takes each. Every strike is levelled to the same peak, so what changes is the strike, not the volume.",
     "Does a soft hit sound like a softer hand on the same drum? Are the three takes different strikes?",
     ["kick", "snare", "hat"], None),
    ("glue", "6 · The glue", "Four bars at 84 BPM. One shared coil the kick drives, a pump the kick keys, and a dictaphone. Every groove plays at the same loudness.",
     "Does the kit breathe? Which dictaphone? Do the hats drag right?",
     ["g_full", "g_uncoupled", "g_dry", "g_straight", "g_heavy", "g_subgrit"], None),
    ("landed", "7 · Pads as they would land", "Each pad printed through its own glue (its own coil send and the dictaphone at 0.45). This is what a sampler pad would hold.",
     "Any pad that the glue ruins?", ["kick", "snare", "hat", "ohat", "rod", "cup"], None),
]

MODE_R0 = {"kick": "pick", "snare": "pick", "hats": "pick", "glue": "pick", "perc": "each", "touch": "yesno", "landed": "note"}
OPTS_R0 = {"perc": ["Keep", "Change", "Drop"], "touch": ["Softer hand", "Just quieter"]}

REFS = {"kick": ("kick_processed_100.wav", "Backroom kick (accepted)"),
        "snare": ("snare_processed_100.wav", "Backroom snare (accepted)"),
        "hat": ("closed_processed_100.wav", "Backroom closed hat (accepted)")}

NOTES = {
    "glue/g_uncoupled": "Measured bassier: 89% of its energy is under 100 Hz, against 26% for the coupled groove, because each pad's own glue lifts the kick's sub.",
    "glue/g_subgrit": "The dictaphone takes the sub too: 5 dB less energy under 100 Hz than the default.",
    "snare/s_surf": "BREAKER's surf spring, for contrast. At 1.3 s its tail is 38 dB under its peak; the coil's is 74 dB under.",
    "touch/kick": "Measured: from soft to hard, the beater band (1.5 to 6 kHz) rises 11 dB and the sag at 0.35 grows from under 2 cents to about 12 cents.",
}


# Round 1: the confirming listen on the Kotlin renders (`:synth:generateSootAudition`).
QUESTIONS_R1 = [
    ("same", "1 · Same drums?", "Your three main picks rendered by the app's engine, dry, each beside the Python render you picked.",
     "Is each one the drum you picked?", ["kick", "snare", "hat"], "yesno", ["Same drum", "Different"],
     {"kick": ("kick/k1_long.wav", "Your round-0 pick (Python)"), "snare": ("snare/s_hot.wav", "Your round-0 pick (Python)"),
      "hat": ("hats/h_closed_wide.wav", "Your round-0 pick (Python)")}),
    ("pads", "2 · The pads", "Every voice as its pad prints it: its own coil send, then the dictaphone. Each at GRIME .9 (your groove pick) and at .45 (what you heard the pads at in round 0).",
     "Any pad the heavier dictaphone ruins?", ["kick", "kick_45", "snare", "snare_45", "hat_closed", "hat_closed_45", "hat_open", "hat_open_45",
                                                  "rod", "rod_45", "plate", "plate_45", "cup", "cup_45", "coil", "coil_45"], "note", [], {}),
    ("alternates", "3 · The alternates", "The approved layout's other pads, as presets of the same voices.",
     "Keep, change or drop each.", ["kick_heavy", "kick_tight", "kick_dry", "snare_coil", "snare_dry_crack", "snare_bench",
                                     "hat_closed_narrow", "hat_open_narrow", "hat_closed_pure", "rod_dry", "plate_dry", "cup_dry", "coil_dry"],
     "each", ["Keep", "Change", "Drop"], {}),
    ("touch", "4 · Touch, through the print", "Soft, medium, hard, three takes each, at GRIME .9. Each strike is levelled to one peak. Round 0's strips were dry; these go through the dictaphone.",
     "Does a soft hit still sound like a softer hand?", ["kick", "snare", "hat_closed", "rod"], "yesno", ["Softer hand", "Just quieter"], {}),
    ("kit", "5 · Four bars from the pads", "The round-0 groove played the way a sampler plays the pads: each hit its own render, takes dealt in turn, hats dragged. No shared bus yet; that is the phrase pad.",
     "Which dictaphone for the kit?", ["grime90", "grime45"], "pick", [], {}),
]

MEASURED_R1 = {
    "Velocity, dry: share above each drum's split, soft (0.3) to hard (1.0), dB": "kick +10.6 · snare +4.0 · closed hat +5.5 · open hat +5.0 · rod +6.1 · plate +1.5 · cup +3.4 · coil +1.4",
    "Velocity through GRIME .9, same measure, dB": "kick +13.2 · snare +3.2 · closed hat +0.1 · open hat +0.3 · rod +1.8 · plate +0.7 · cup +0.9 · coil +0.8",
    "Takes at full strength, difference energy against the signal, dB": "kick -18.5 to -22.7 (closest) · coil -8.2 to -26.2 · snare, hats and metal -0.3 to +5.2",
    "Coil energy per 100 ms after its peak, dB": "0, -5.4, -13.4, -20.6, -27.5, -33.9, -40.3, -46.8, -52.9, -59.1 (falls every block)",
    "Coil first echo: 300 Hz / 1.8 kHz arrive at, ms": "23.7 / 49.1 (the chirp rises)",
    "GRIME 0 to .9, share above 6 kHz, dB": "snare -17.4 to -22.9 · closed hat -8.6 to -14.3 · open hat -7.8 to -13.7 · plate -51 to -25 (the ROM hold adds aliased grit to a dark plate)",
    "Kick energy under 100 Hz through the print, dB re the whole": "-1.2",
    "BREAKER after the shared spring change": "every voice, full and 60% take 2: byte-identical (SHA-256 before and after)",
}


def main_r1(r1: str, r0: str, out: str) -> int:
    global TARGET_DB
    TARGET_DB = -26.0  # the printed metal's peaks are high for its loudness; match lower so few clips hit the limit
    man = json.load(open(os.path.join(r1, "manifest.json")))
    by = {(c["section"], c["id"]): c for c in man["clips"]}
    sections = []
    for key, title, what, ask, ids, mode, options, refs in QUESTIONS_R1:
        rows = []
        for cid in ids:
            c = by[(key, cid)]
            src, trim = matched_mp3(read(os.path.join(r1, c["file"])))
            rows.append({"id": cid, "label": c["label"], "src": src, "secs": c["seconds"], "note": "", "trim": round(trim, 1)})
            if cid in refs and os.path.exists(os.path.join(r0, refs[cid][0])):
                src, trim = matched_mp3(read(os.path.join(r0, refs[cid][0])))
                rows.append({"id": "ref", "label": refs[cid][1], "src": src, "secs": None, "note": "", "trim": round(trim, 1), "ref": True})
        sections.append({"key": key, "title": title, "what": what, "ask": ask, "rows": rows, "mode": mode, "options": options})
    meas = dict(MEASURED_R1)
    meas["Warm render time per hit, this host, ms"] = man["render_ms_warm_median5"]
    tpl = open(os.path.join(os.path.dirname(__file__), "template_r1.html")).read()
    with open(out, "w") as f:
        f.write(tpl.replace("/*DATA*/null", json.dumps({"sections": sections, "measurements": meas})))
    print(out, round(os.path.getsize(out) / 1e6, 2), "MB")
    return 0


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] == "--round" and sys.argv[2] == "1":
        a = sys.argv[3:]
        return main_r1(a[0] if a else "testkit/soot-audition", a[1] if len(a) > 1 else "testkit/soot-r0",
                       a[2] if len(a) > 2 else "testkit/soot-audition/index.html")
    r0 = sys.argv[1] if len(sys.argv) > 1 else "testkit/soot-r0"
    bb = sys.argv[2] if len(sys.argv) > 2 else "testkit/boom-bap-audition/native"
    out = sys.argv[3] if len(sys.argv) > 3 else os.path.join(r0, "index.html")
    man = json.load(open(os.path.join(r0, "manifest.json")))
    by = {(c["section"], c["id"]): c for c in man["clips"]}
    sections = []
    trims = {}
    for key, title, what, ask, ids, ref in QUESTIONS:
        rows = []
        for cid in ids:
            c = by[(key, cid)]
            src, trim = matched_mp3(read(os.path.join(r0, c["file"])))
            trims[f"{key}/{cid}"] = round(trim, 1)
            rows.append({"id": cid, "label": c["label"], "src": src, "secs": c["seconds"], "note": NOTES.get(f"{key}/{cid}", ""),
                         "trim": round(trim, 1)})
        if ref and os.path.exists(os.path.join(bb, REFS[ref][0])):
            src, trim = matched_mp3(read(os.path.join(bb, REFS[ref][0])))
            rows.append({"id": "ref", "label": REFS[ref][1], "src": src, "secs": None, "note": "Kotlin render, the kit you accepted on 2026-11-07. Reference only.", "trim": round(trim, 1), "ref": True})
        sections.append({"key": key, "title": title, "what": what, "ask": ask, "rows": rows,
                         "mode": MODE_R0[key], "options": OPTS_R0.get(key, [])})
    meas = man["measurements"]
    tpl = open(os.path.join(os.path.dirname(__file__), "template.html")).read()
    page = tpl.replace("/*DATA*/null", json.dumps({"sections": sections, "measurements": meas}))
    with open(out, "w") as f:
        f.write(page)
    print(out, round(os.path.getsize(out) / 1e6, 2), "MB; peak-trimmed clips:", {k: v for k, v in trims.items() if v})
    return 0


if __name__ == "__main__":
    sys.exit(main())
