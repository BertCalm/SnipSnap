#!/usr/bin/env python3
"""Build SOOT's listening pages (round 0, and with --round 1 the Kotlin confirming listen): loudness-matched MP3s embedded in one HTML file.

Run:  python3 scripts/soot_r0/build_page.py [r0_dir] [boom_bap_dir] [out.html]
      python3 scripts/soot_r0/build_page.py --round 1|1b [r1_dir] [r0_dir] [out.html]
      python3 scripts/soot_r0/build_page.py --round 2 testkit/soot-kit testkit/soot-r0 [out.html]
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

# Round 1b: the hats re-heard after model 2, the alternates the owner left blank, the new GRIME default.
QUESTIONS_R1B = [
    ("same", "1 · The hats again", "Model 2 takes the hats' own send into the coil away. Each Kotlin hat sits beside the Python one you picked; model 1's closed hat, the one you heard as different, is last.",
     "Is each one now the hat you picked?", ["hat", "hat_open", "hat_m1"], "yesno", ["Same drum", "Different"],
     {"hat": ("hats/h_closed_wide.wav", "Your round-0 pick (Python)"), "hat_open": ("hats/h_open_wide.wav", "Your round-0 pick (Python)")}),
    ("alternates", "2 · The alternates", "These were left unanswered last round. The approved layout's other pads, now at GRIME .45.",
     "Keep, change or drop each.", ["kick_heavy", "kick_tight", "kick_dry", "snare_coil", "snare_dry_crack", "snare_bench",
                                     "hat_closed_narrow", "hat_open_narrow", "hat_closed_pure", "rod_dry", "plate_dry", "cup_dry", "coil_dry"],
     "each", ["Keep", "Change", "Drop"], {}),
    ("pads", "3 · The pads at your pick", "Every voice as its pad now prints it: GRIME .45, the hats dry of the coil.",
     "Anything off?", ["kick", "snare", "hat_closed", "hat_open", "rod", "plate", "cup", "coil"], "note", [], {}),
    ("kit", "4 · Four bars from the pads", "The groove you picked, GRIME .45, with the new hats.",
     "Is this the kit?", ["grime45"], "yesno", ["Yes", "Not yet"], {}),
]

MEASURED_R1B = {
    "Closed hat, dry, level at 100 ms re its loudest 20 ms": "model 1 -29.2 dB · model 2 -76.2 dB · your Python pick -76.2 dB",
    "Open hat, dry, level at 700 ms": "model 1 -62.5 dB · model 2 -71.7 dB (its own ring covers most of the coil)",
    "Closed hat energy below 2.4 kHz": "Kotlin about 10 dB over your pick; your pick's own eight takes spread 15 dB there on their random phases, so this is take-to-take variation",
    "GRIME default": "model 2: .45 on every voice (your four-bar pick); model-1 saves keep .9",
}

# Round 2: the kit on the shelf (`:shell:generateSootKit`), every pad read back from the MPC 3 export.
QUESTIONS_R2 = [
    ("pads", "0 · The three snares, re-voiced", "You heard A02, A11 and A12 as the same drum. They measured 0.4 and 2.0 dB apart (A02's dry crack, A06, sits 3.8 dB away). A11 is now a clean, high metal slap (no coil, no cassette, no dictaphone); A12 a low snare drowned in the coil. Each is now 8.7 dB or more from the others. Two other candidates are underneath, in case you prefer one.",
     "Are they three different drums now?", ["a02", "a11", "a12"], "yesno", ["Different", "Still too close"],
     {"a11": ("../soot-snares/a11_d.wav", "Another bench option: a little coil and grit, less high (5.3 dB from A02)"),
      "a12": ("../soot-snares/a12_a.wav", "Another coil option: less low, the default dictaphone (6.6 dB from A02)")}),
    ("roll", "1 · The coil roll (A15)", "Sixteen fresh snare strikes speeding up into one coil, so the spring fills as the roll builds, then rings out. A fader push over the build, as BREAKER's riser. Each zone as the pad plays it.",
     "Is this the roll?", ["z2", "z1", "z0"], "yesno", ["Yes", "Not yet"], {}),
    ("phrase", "2 · Four bars (A16)", "Your round-0 groove through one shared coil the kick drives, then the dictaphone the kick pumps: the only pad where the whole kit breathes through one spring. The kit prints it at GRIME .45; the .9 version is here to steer by.",
     "Which dictaphone for the four bars?", ["z2", "grime45", "z1", "z0"], "pick", [], {}),
    ("phrase", "3 · The loop point", "Four bars, played around twice. The file is exactly four bars at 84 BPM, its tail wrapped into its start.",
     "Does it loop cleanly?", ["looped"], "yesno", ["Clean", "I hear a seam"], {}),
    ("pads", "4 · The sixteen pads", "Each pad's hard first take, read back from the exported MPC 3 files through the app's own pad resolver.",
     "Anything off?", [f"a{n:02d}" for n in range(1, 17)], "note", [], {}),
    ("touch", "5 · Touch and takes, as the pad deals them", "Soft, medium, hard, four takes each, read back from the export. A selection of the fourteen hits.",
     "Does each zone sound like a softer hand, and each take like another strike?", ["a01", "a02", "a03", "a09"], "yesno", ["Yes", "Not yet"], {}),
    ("groove", "6 · Two bars from the single pads", "For contrast: the same groove dealt from the single-hit pads, each in its own print, with no shared coil.",
     "Notes?", ["pads"], "note", [], {}),
]

MEASURED_R2 = {
    "Save, reload, playback, MPC 3 export (all 16 pads, 192 slices)": "verified: the reloaded kit equals the saved one; saved and exported PCM match the renders through PadHit.resolve over two cycles of every zone; the exported track's velocity ranges, base slices, cycle lengths and frame windows match",
    "Coil roll: last 400 ms of the build against the first 400 ms": "+11.9 dB (the pump flattened it to +1.9 dB before the fader push)",
    "Four bars: the loop point's step against the music's own 99.9th-percentile step": "take 0: 0.0074 vs 0.1168 · take 3: 0.0012 vs 0.1233",
    "Soft against hard zone, share above 3 kHz": "roll -21.9 / -18.6 dB · four bars -19.9 / -18.8 dB",
    "The three snares, log-spectral distance (24 bands, 60 Hz to 16 kHz)": "before: A02/A12 0.4 dB, A02/A11 2.0 dB · now: A02/A11 8.7, A02/A12 8.8, A11/A12 11.7 dB (SootKitsTest holds them at 6 dB or more)",
    "Kit build time, this host (4 cores, in parallel)": "27.6 s: the four bars are 2.0 s a render, 12 renders; a hit 10 to 225 ms; the roll 0.3 s",
}

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


GATES_R2 = """      <tbody>
        <tr><td>Sound character</td><td><span class="state s-part">Hits approved</span></td><td>You approved the fourteen hits and the kit (round 1b). A11 and A12 are re-voiced; the roll and the four bars are new on this page.</td></tr>
        <tr><td>Melodic, keygroups, chord tools</td><td><span class="state s-na">N/A</span></td><td>A drum kit.</td></tr>
        <tr><td>Articulation</td><td><span class="state s-na">N/A</span></td><td>Finite hits, no HOLD.</td></tr>
        <tr><td>Percussion roll or riser</td><td><span class="state s-part">Built, not heard</span></td><td>A15, the coil roll, with its three zones and four takes (section 1).</td></tr>
        <tr><td>Multiple genre kits</td><td><span class="state s-open">One of three</span></td><td>SOOT is on the shelf. Two sibling kits, your choice, are still to come.</td></tr>
        <tr><td>Velocity and round robin: rendering</td><td><span class="state s-part">Verified</span></td><td>Each zone a softer strike, each take a fresh one.</td></tr>
        <tr><td>Velocity and round robin: saving and playback</td><td><span class="state s-part">Verified in software</span></td><td>The saved kit reloads equal; all 192 slices play back through the app's resolver, zone by velocity, takes in order then repeating. No phone has played it.</td></tr>
        <tr><td>Velocity and round robin: export</td><td><span class="state s-part">MPC 3 verified by read-back</span></td><td>The exported track's zones, cycles and windows match. MPC 2 keeps one take per zone and does not pass. No MPC has loaded it.</td></tr>
      </tbody>"""


def main_r1(r1: str, r0: str, out: str, sub: str = "1") -> int:
    global TARGET_DB
    TARGET_DB = -26.0  # the printed metal's peaks are high for its loudness; match lower so few clips hit the limit
    questions, measured = {"1b": (QUESTIONS_R1B, MEASURED_R1B), "2": (QUESTIONS_R2, MEASURED_R2)}.get(sub, (QUESTIONS_R1, MEASURED_R1))
    man = json.load(open(os.path.join(r1, "manifest.json")))
    by = {(c["section"], c["id"]): c for c in man["clips"]}
    sections = []
    for qi, (key, title, what, ask, ids, mode, options, refs) in enumerate(questions):
        rows = []
        for cid in ids:
            c = by[(key, cid)]
            src, trim = matched_mp3(read(os.path.join(r1, c["file"])))
            rows.append({"id": cid, "label": c["label"], "src": src, "secs": c["seconds"], "note": "", "trim": round(trim, 1)})
            if cid in refs and os.path.exists(os.path.join(r0, refs[cid][0])):
                src, trim = matched_mp3(read(os.path.join(r0, refs[cid][0])))
                rows.append({"id": "ref", "label": refs[cid][1], "src": src, "secs": None, "note": "", "trim": round(trim, 1), "ref": True})
        # Two questions may share a clip folder; the page keys each question on its own.
        sections.append({"key": key if sub != "2" else f"{key}{qi}", "tag": key, "title": title, "what": what, "ask": ask, "rows": rows, "mode": mode, "options": options})
    meas = dict(measured)
    if "render_ms_warm_median5" in man:
        meas["Warm render time per hit, this host, ms"] = man["render_ms_warm_median5"]
    tpl = open(os.path.join(os.path.dirname(__file__), "template_r1.html")).read()
    if sub == "2":
        a = tpl.index("      <tbody>"); b = tpl.index("      </tbody>") + len("      </tbody>")
        tpl = (tpl[:a] + GATES_R2 + tpl[b:])
        tpl = (tpl.replace("Round 1 · Kotlin, the app's own engine", "Round 2 · the kit on the shelf")
               .replace("<title>SOOT Round 1</title>", "<title>SOOT Round 2</title>")
               .replace('const KEY = "soot-r1-picks";', 'const KEY = "soot-r2-picks";')
               .replace('const lines = ["SOOT round 1 answers"];', 'const lines = ["SOOT round 2 answers"];')
               .replace("Your round-0 picks, now built in the app's engine: the kick with the longer sub, the hot snare, the wide hats, the heavy dictaphone, and the layout you approved. This page is the confirming listen. Nothing here has been heard by anyone yet.",
                        "SOOT is on the FRESH TAPE shelf: the sixteen pads you approved, each with three touch zones and four takes, plus the two new pads: the coil roll on A15 and the four bars on A16. Every pad on this page was saved, reloaded, exported to MPC 3 and read back through the app's own pad resolver.")
               .replace('Every clip plays at the same loudness. Kotlin and Python render noise and a few filters differently, so "the same drum" means the same character, not the same file. Pick on each question, then copy the answers at the bottom and paste them back to me.',
                        "Every clip plays at the same loudness. Pick on each question, then copy the answers at the bottom and paste them back to me.")
               .replace('<section aria-labelledby="kit-h">', '<section aria-labelledby="kit-h" hidden>')
               .replace('lines.push("6 · Layout swaps: " + (picks.layout || "no answer"));', "")
               .replace('<div class="eyebrow">7 · Where the gates stand</div>', '<div class="eyebrow">7 · Where the gates stand</div>')
               .replace('<p>From the engine acceptance gates. This round builds the drums; the kit, saving, playback and export are the next round.</p>',
                        '<p>From the engine acceptance gates. This round puts the kit on the shelf and proves its grids in software. Hardware and the sibling kits remain.</p>')
               .replace('<div class="eyebrow">8 · What I measured</div>', '<div class="eyebrow">8 · What I measured</div>'))
    if sub == "1b":
        tpl = (tpl.replace("Round 1 · Kotlin, the app's own engine", "Round 1b · Kotlin, renderer model 2")
               .replace("<title>SOOT Round 1</title>", "<title>SOOT Round 1b</title>")
               .replace('const KEY = "soot-r1-picks";', 'const KEY = "soot-r1b-picks";')
               .replace('const lines = ["SOOT round 1 answers"];', 'const lines = ["SOOT round 1b answers"];')
               .replace("Your round-0 picks, now built in the app's engine: the kick with the longer sub, the hot snare, the wide hats, the heavy dictaphone, and the layout you approved. This page is the confirming listen. Nothing here has been heard by anyone yet.",
                        "Your round-1 answers, built: the hats no longer send into the coil (the effect you heard was its tail), and every pad prints at GRIME .45, your four-bar pick. The kick and snare are unchanged; you heard them as the same drums.")
               .replace('<section aria-labelledby="kit-h">', '<section aria-labelledby="kit-h" hidden>')
               .replace('lines.push("6 · Layout swaps: " + (picks.layout || "no answer"));', "")
               .replace('<div class="eyebrow">7 · Where the gates stand</div>', '<div class="eyebrow">5 · Where the gates stand</div>')
               .replace('<div class="eyebrow">8 · What I measured</div>', '<div class="eyebrow">6 · What I measured</div>')
               .replace("<td>Sound character</td><td><span class=\"state s-part\">Round 0 picked</span></td><td>Your round-0 picks; this page confirms the Kotlin renders.</td>",
                        "<td>Sound character</td><td><span class=\"state s-part\">Kick and snare confirmed</span></td><td>You heard the Kotlin kick and snare as your picks. The hats are re-heard here.</td>")
               .replace("Through GRIME .9 the hats keep almost none of that brightness (section 4 asks).",
                        "You heard every touch strip as a softer hand through the print."))
    with open(out, "w") as f:
        f.write(tpl.replace("/*DATA*/null", json.dumps({"sections": sections, "measurements": meas})))
    print(out, round(os.path.getsize(out) / 1e6, 2), "MB")
    return 0


def main() -> int:
    if len(sys.argv) > 2 and sys.argv[1] == "--round" and sys.argv[2] in ("1", "1b", "2"):
        a = sys.argv[3:]
        return main_r1(a[0] if a else "testkit/soot-audition", a[1] if len(a) > 1 else "testkit/soot-r0",
                       a[2] if len(a) > 2 else "testkit/soot-audition/index.html", sys.argv[2])
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
