#!/usr/bin/env python3
"""Build SOOT's round-0 listening page: loudness-matched MP3s embedded in one HTML file.

Run:  python3 scripts/soot_r0/build_page.py [r0_dir] [boom_bap_dir] [out.html]
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

REFS = {"kick": ("kick_processed_100.wav", "Backroom kick (accepted)"),
        "snare": ("snare_processed_100.wav", "Backroom snare (accepted)"),
        "hat": ("closed_processed_100.wav", "Backroom closed hat (accepted)")}

NOTES = {
    "glue/g_uncoupled": "Measured bassier: 89% of its energy is under 100 Hz, against 26% for the coupled groove, because each pad's own glue lifts the kick's sub.",
    "glue/g_subgrit": "The dictaphone takes the sub too: 5 dB less energy under 100 Hz than the default.",
    "snare/s_surf": "BREAKER's surf spring, for contrast. At 1.3 s its tail is 38 dB under its peak; the coil's is 74 dB under.",
    "touch/kick": "Measured: from soft to hard, the beater band (1.5 to 6 kHz) rises 11 dB and the sag at 0.35 grows from under 2 cents to about 12 cents.",
}


def main() -> int:
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
        sections.append({"key": key, "title": title, "what": what, "ask": ask, "rows": rows})
    meas = man["measurements"]
    tpl = open(os.path.join(os.path.dirname(__file__), "template.html")).read()
    page = tpl.replace("/*DATA*/null", json.dumps({"sections": sections, "measurements": meas}))
    with open(out, "w") as f:
        f.write(page)
    print(out, round(os.path.getsize(out) / 1e6, 2), "MB; peak-trimmed clips:", {k: v for k, v in trims.items() if v})
    return 0


if __name__ == "__main__":
    sys.exit(main())
