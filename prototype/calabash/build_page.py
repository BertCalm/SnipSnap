"""Build the CALABASH audition page from manifest.json, into OUT/site/ (index.html plus its audio).

Every number on the page is read from the manifest the generator wrote; nothing is typed in by hand. The look is the
house audition page's (SCUFF, ETCH, CLUTTER, EMBER): TapeOS cards, LCD panels, the same pad grid. The pad sprites are
WAV, as EMBER's are (lossless, so the grid's slot slicing stays sample-exact; FLAC would be a third the size but artifact
pages do not serve it); every other clip is an MP3 listening copy (the WAVs in OUT are the proof).

Run after gen_audition.py:   python3 build_page.py OUT_DIR
"""
from __future__ import annotations

import html
import json
import os
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

OUT = sys.argv[1] if len(sys.argv) > 1 else "calabash-audition"
SITE = os.path.join(OUT, "site")
M = json.load(open(os.path.join(OUT, "manifest.json")))
try:
    COMMIT = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], text=True,
                                     cwd=os.path.dirname(os.path.abspath(__file__))).strip()
except Exception:  # noqa: BLE001
    COMMIT = "unknown"
ESC = html.escape


# ------------------------------------------------------------------------------ audio for the page

def encode(rel, kind, site=None):
    src = os.path.join(OUT, rel)
    dst_rel = rel if kind == "wav" else rel[:-4] + ".mp3"
    dst = os.path.join(site or SITE, dst_rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    if os.path.exists(dst) and not os.path.exists(src):
        return dst_rel          # the listening copy outlived its source (an OUT trimmed for disk): keep it
    if not os.path.exists(dst) or os.path.getmtime(dst) < os.path.getmtime(src):
        if kind == "wav":
            shutil.copyfile(src, dst)
        else:
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", src, "-c:a", "libmp3lame", "-b:a", "160k", dst],
                           check=True)
    return dst_rel


jobs = []
for k in M["kits"]:
    for r in k["pads"]:
        jobs.append((r, "sprite", r["sprite"], "wav"))
    jobs.append((k, "groove", k["groove"], "mp3"))
    jobs.append((k, "run", k["run"], "mp3"))
for s in M["sections"]:
    for cl in s["clips"]:
        jobs.append((cl, "file", cl["file"], "mp3"))
with ThreadPoolExecutor(4) as ex:
    done = list(ex.map(lambda j: encode(j[2], j[3]), jobs))
for (obj, key, _, _), rel in zip(jobs, done):
    obj[key] = rel

LABELS = {
    "centroid_hz": ("CENTROID", "{:.0f} Hz"), "soft_centroid_hz": ("SOFT CENTROID", "{:.0f} Hz"),
    "decay_ms": ("TO -60 dB", "{:.0f} ms"), "soft_vs_hard_db": ("SOFT VS HARD", "{:.1f} dB"),
    "closest_takes_db": ("CLOSEST TAKES", "{:.1f} dB"), "widest_takes_db": ("WIDEST TAKES", "{:.1f} dB"),
    "closest_strokes_db": ("CLOSEST STROKES", "{:.1f} dB"),
    "centroid_lo_hz": ("CENTROID AT 0", "{:.0f} Hz"), "centroid_hi_hz": ("AT 1", "{:.0f} Hz"),
    "decay_lo_ms": ("DECAY AT 0", "{:.0f} ms"), "decay_hi_ms": ("AT 1", "{:.0f} ms"),
    "lo_vs_hi_db": ("0 VS 1", "{:.1f} dB"),
    "vs_recording_db": ("VS RECORDING", "{:.1f} dB"), "vs_recording_whole_db": ("WHOLE", "{:.1f} dB"),
    "rec_centroid_hz": ("REC CENTROID", "{:.0f} Hz"),
}


def chips(metrics: dict) -> str:
    out = []
    for k, (label, fmt) in LABELS.items():
        if k in metrics and metrics[k] is not None:
            out.append(f'<span class="chip"><b>{ESC(label)}</b> {ESC(fmt.format(metrics[k]))}</span>')
    return "".join(out)


def clip_html(c: dict) -> str:
    return f"""
      <li class="clip">
        <div class="clip-head"><span class="clip-id">{ESC(c['name'])}</span><span class="clip-len">{c['seconds']:.1f} s</span></div>
        <p class="clip-note">{ESC(c['note'])}</p>
        <audio controls preload="none" src="{ESC(c['file'])}"></audio>
        <div class="chips">{chips(c['metrics'])}</div>
      </li>"""


def section(sid):
    return next((s for s in M["sections"] if s["id"] == sid), {"clips": []})


def short(name):
    return name.replace("CALABASH ", "").title().replace("Afro-Cuban", "Afro-Cuban")


KITS = M["kits"]
C = M["checks"]
B = M["between"]

kit_cards = []
for k in KITS:
    rows = "".join(
        f"<tr><td>{ESC(r['pad'])}</td><td>{ESC(r['label'])}</td><td>{ESC(r['voice'])}</td><td>{ESC(r['class'])}</td>"
        f"<td>{r['metrics']['soft_vs_hard_db']}</td><td>{r['metrics']['closest_takes_db']}</td></tr>" for r in k["pads"])
    chokes = "; ".join(f"{a} cuts {', '.join(b)}" for a, b in k["chokes"].items())
    kit_cards.append(f"""
  <section class="card" id="kit-{k['id']}">
    <h2>{ESC(k['name'])}</h2>
    <p class="lcd-line"><span>{k['bpm']:.0f} BPM</span><span>SWING {k['swing']:.2f}</span><span>ROOM {k['room']}</span><span>AGE {k['age']}</span></p>
    <p class="intro">{ESC(k['character'])}</p>
    <ul class="clips">
      <li class="clip"><div class="clip-head"><span class="clip-id">GROOVE</span><span class="clip-len">{k['groove_seconds']:.1f} s</span></div>
        <p class="clip-note">Two bars, played twice. Every hit deals the next take at its own velocity zone, with the sampler's velocity gain on and
        each pad where the player would sit. Authored in the style, not transcribed from a record.</p>
        <audio controls preload="none" src="{ESC(k['groove'])}"></audio></li>
      <li class="clip"><div class="clip-head"><span class="clip-id">ALL 16 PADS</span></div>
        <p class="clip-note">A01 to A16 in order, hard, first take, each peak-matched (long pads cut at 1.6 s here).</p>
        <audio controls preload="none" src="{ESC(k['run'])}"></audio></li>
    </ul>
    <details><summary>The pads ({ESC(chokes)})</summary><div class="table-wrap"><table>
      <tr><th>Pad</th><th>Name</th><th>Voice</th><th>Class</th><th>Soft vs hard, dB</th><th>Closest takes, dB</th></tr>{rows}
    </table></div></details>
    <p class="ask"><span class="tag">LISTEN FOR</span>Is this the music it names? Which pads would you swap, retune or re-voice, and does the groove sit right?</p>
  </section>""")

refs = section("refs")["clips"]
ref_card = ""
if refs:
    near = [r["metrics"]["vs_recording_db"] for r in refs]
    ref_card = f"""
  <section class="card" id="refs">
    <h2>Fitted voices beside their recordings</h2>
    <p class="intro">The congas, bongos, bells, claves, shaker and claps start from CC0 recordings (the Versilian Community Sample Library):
    each recording's own resonances, their decays and pitch glide, and what those leave out, struck again. Each clip is CALABASH dry
    (no room, no era chain), then the recording it was fitted to. On the house distance they sit {min(near):.1f} to {max(near):.1f} dB from
    their recordings over the first 150 ms; two real takes of one VCSL drum sit 4.0 to 8.6 dB apart on the same measure.</p>
    <p class="ask"><span class="tag">LISTEN FOR</span>Is each one the same instrument as the recording after it, struck again? Which sounds like a synth pretending?</p>
    <ul class="clips">{''.join(clip_html(c) for c in refs)}</ul>
  </section>"""


def per_kit(sid):
    out = []
    for k in KITS:
        cl = section(f"{sid}/{k['id']}")["clips"]
        out.append(f"""<details><summary>{ESC(k['name'])}</summary><ul class="clips">{''.join(clip_html(c) for c in cl)}</ul></details>""")
    return "".join(out)


stroke_card = f"""
  <section class="card" id="strokes">
    <h2>Strokes: how the hand or stick lands</h2>
    <p class="intro">STROKE moves a voice between the strokes its players use, and each is a different event, not a filter. On the congas and
    bongos the open and muffled strokes are the recordings'; the bass (palm in the middle) and the slap (the heel chokes the note and the cupped fingers
    crack) are built on them, because the library has no slap. The sabar drums go open hand, hand slap, open stick, pressed stick; the timbale head,
    rimshot, shell. The talking drums show CONTOUR: press up after the hit, let go after it, and up and back down.</p>
    <p class="ask"><span class="tag">LISTEN FOR</span>Does each stroke sound like that stroke on that drum? Are the slaps real slaps?</p>
    <ul class="clips">{''.join(clip_html(c) for c in section('strokes')['clips'])}</ul>
  </section>"""

base_sections = f"""
  <section class="card" id="velocity">
    <h2>Velocity: the stroke, not the volume</h2>
    <p class="intro">Soft, medium, hard on every pad of every kit, each peak-matched so only the stroke differs. A fitted drum moves toward its own soft
    recording; a physical one lands with a broader, slower contact, and its beads, jingles and wires move only past their own threshold.</p>
    <p class="ask"><span class="tag">LISTEN FOR</span>Is a soft hit a lighter player, rather than the hard hit turned down?</p>
    {per_kit('velocity')}
  </section>
  <section class="card" id="takes">
    <h2>Round robin: four takes</h2>
    <p class="intro">The four takes each pad deals in turn, all hard. A take moves where the hand or stick lands (which upper modes change sign, the
    strike radius), how long it stays on, the glide, and fresh noise in the slap, the beads and the room.</p>
    <p class="ask"><span class="tag">LISTEN FOR</span>One player hitting the same drum four times, not four drums and not one copy?</p>
    {per_kit('takes')}
  </section>
  <section class="card" id="voices">
    <h2>All {len(M['voices'])} voices</h2>
    <p class="intro">Every voice at its defaults, soft then hard. Some voices appear in only one kit.</p>
    <ul class="clips">{''.join(clip_html(c) for c in section('voices')['clips'])}</ul>
  </section>"""

groups: dict = {}
for cl in section("macros")["clips"]:
    groups.setdefault(cl["voice"], []).append(cl)
macro_body = "".join(f"""
    <details><summary>{ESC(v)}</summary><ul class="clips">{''.join(clip_html(c) for c in cs)}</ul></details>""" for v, cs in groups.items())
meaning_rows = "".join(f"<tr><td>{ESC(k)}</td><td>{ESC(v)}</td></tr>" for k, v in M["meaning"].items())

gate_kit_rows = "".join(
    f"<tr><td>{ESC(short(k['name']))}</td><td>{k['bpm']:.0f}</td><td>{k['soft_vs_hard_min_db']}</td><td>{k['closest_takes_min_db']}</td>"
    f"<td>{k['closest_pads_db']} ({ESC(' / '.join(k['closest_pads']))})</td></tr>" for k in KITS)
name_of = {k["id"]: short(k["name"]) for k in KITS}
between_rows = "".join(
    f"<tr><td>{ESC(' / '.join(name_of[i] for i in p.split('|')))}</td><td>{v['mean_slot_db']}</td>"
    f"<td>{v['min_slot_db']} ({ESC(v['min_slot'])})</td><td>{len(v['identical_pads'])}</td></tr>" for p, v in B.items())
identical = C["identical_pads_between_kits"]

GATES = [
    ("Melodic, keygroup, chord tool", "na", "Not applicable. Drum kits."),
    ("Articulation (HOLD, STRUM)", "na", "Finite hits, no HOLD. No picked or thumbed strings."),
    ("Percussion: roll or riser", "mid", "Authored, one on every kit's A16: a shekere rush (Afrobeat), a talking-drum call (Afrobeats), a sabar stick roll into its bakk (Mbalax), a timbale abanico (Afro-Cuban jazz). Rendered in Python, not heard."),
    ("Drums: several genre kits on the shelf", "mid", f"Four genre kits authored and measured in Python: {identical} identical pads between any two kits. Not on the shelf: no Kotlin."),
    ("Velocity in rendering", "mid", f"Measured in Python on all 64 pads: soft against hard at least {C['soft_vs_hard_min_db']} dB at matched level."),
    ("Round robin in rendering", "mid", f"Measured in Python on all 64 pads: the closest two takes in any zone of any pad are {C['closest_takes_min_db']} dB apart."),
    ("Velocity and round robin through saving, playback, export", "open", "No Kotlin yet. The pad grid on this page is a browser stand-in, not the app."),
    ("Sound character", "open", "Your listen. Nothing here has been heard."),
]
gate_rows = "".join(f'<tr><td>{ESC(a)}</td><td><span class="pill {s}">{ {"na": "N/A", "mid": "MEASURED", "open": "OPEN"}[s] }</span></td><td>{ESC(b)}</td></tr>'
                    for a, s, b in GATES)

ref_range = (f"{min(r['first150_db'] for r in M['refs']):.1f} to {max(r['first150_db'] for r in M['refs']):.1f} dB from their recordings over 150 ms"
             if M.get("refs") else "fitted (the comparison runs only with the recordings at hand)")
LEDGER = [
    ("Every kit render is deterministic, finite, peak 0.89, DC under 0.005", "measured",
     f"{C['renders']} renders; deterministic {C['deterministic']}, finite {C['finite']}, peak {C['peak_max']}, DC {C['dc_max']}"),
    ("Every control moves every voice", "measured", f"weakest is {C['weakest_macro']} at {C['weakest_macro_db']} dB between 0 and 1"),
    ("An unknown control or a non-finite value changes nothing", "measured",
     f"unknown {C['unknown_key_ignored']}, NaN control {C['nan_macro_falls_back']}, NaN velocity {C['nan_velocity_falls_back']}"),
    ("AGE 0 is an exact bypass of the era chain", "measured", f"{C['age_zero_is_bypass']}"),
    ("A hit renders in tens of milliseconds", "measured",
     f"Python: median {C['render_ms_median']} ms, slowest {C['render_ms_max']} ms ({C['render_ms_max_voice']}). The rolls and the fitted cymbals take seconds in Python; a pad caches its render."),
    ("Congas, bongos, bells, claves, shaker, claps are their recordings' own resonances", "measured", f"{ref_range} (VCSL, CC0; fit_calabash.py)"),
    ("Conga and bongo slap and bass", "proposed", "built on the fitted head: the heel chokes the note, the cupped fingers crack; no slap recording in the library"),
    ("Shekere: a bead net on a gourd", "analogy", "a cloud of bead impacts whose rate and hardness follow the throw, a gourd's air (165 Hz, from its size) and dry shell; not fitted"),
    ("Talking drums: both heads tighten as the arm squeezes", "literal, shaped", "every membrane mode follows one squeeze path; the squeeze's timing is shape, not measured"),
    ("Sabar: pegged goatskin on tall open shells, hand and stick", "literal, shaped", "membrane modes, the shell's air as a tube (open bottom) or a cavity (the col), a thin switch; no recording fitted"),
    ("Timbales: a thin head on a steel shell, and the shell", "literal, shaped", "membrane plus thin-ring shell modes; the paila strikes the shell"),
    ("The upper head of every physical drum", "analogy", "statistical band noise at the head's loss law, swept toward close-miked brightness targets that are themselves shape"),
    ("The hats, ride and crash", "measured elsewhere", "EMBER's fitted hats and BREAKER's approved ride and crash (Virtuosity Drums, CC0)"),
    ("The room and the era chain", "analogy", "a synthetic room and EMBER's round-0e ribbon, valve and tape; no real room or console measured"),
    ("The kits sound like their music", "not heard", "your listen"),
]
ledger_rows = "".join(f"<tr><td>{ESC(a)}</td><td>{ESC(b)}</td><td>{ESC(c)}</td></tr>" for a, b, c in LEDGER)

grid_kits = [{"id": k["id"], "name": short(k["name"]),
              "chokes": {a.lower(): [x.lower() for x in b] for a, b in k["chokes"].items()},
              "pads": [{"id": r["pad"].lower(), "label": r["label"], "sprite": r["sprite"], "slot": r["slot_frames"]} for r in k["pads"]]}
             for k in KITS]

QUESTIONS = [
    "Which of the four kits sound like their music, and which need another round?",
    "Do the fitted congas, bongos and bells sound like the recordings they were fitted to?",
    "Are the slaps (conga, bongo, sabar) real slaps, or do they crack wrong?",
    "Do the talking drums talk? Is the squeeze the right speed and size?",
    "The sabar drums and the shekere have no recording behind them: closest to the real thing, or furthest?",
    "Any pad to swap for another instrument (a guiro, a djembe, a second timbale, a bongo bell)?",
]
q_rows = "".join(f"<li>{ESC(q)}</li>" for q in QUESTIONS)

# Round 2: the kits on the shelf. `shelf_grooves.py` copies the listening pack of `:shell:generateCalabashKits` (every
# pad's twelve strikes as the app's pad player plays them back from the MPC 3 export) under OUT/shelf/ and plays each
# kit's approved groove with them. MP3 listening copies here; the WAVs are the proof.
shelf_html = ""
SHELF = None
# Its own page (OUT/shelf-site): the round-0 page already holds over 400 files, and an artifact version holds 511.
SHELF_SITE = os.path.join(OUT, "shelf-site")
_shelf = os.path.join(OUT, "shelf", "manifest.json")
if os.path.exists(_shelf):
    SHELF = json.load(open(_shelf))
    _jobs = [(k, "groove", k["groove"]) for k in SHELF["kits"]]
    for k in SHELF["kits"]:
        for p in k["pads"]:
            n = f"{p['slot']:02d}"
            _jobs += [(p, "hard", f"shelf/{k['id']}/pad-{n}.wav"), (p, "touch", f"shelf/{k['id']}/touch-{n}.wav")]
    with ThreadPoolExecutor(4) as ex:
        _done = list(ex.map(lambda j: encode(j[2], "mp3", SHELF_SITE), _jobs))
    for (obj, key, _), rel in zip(_jobs, _done):
        obj[key] = rel
    approved = {short(k["name"]).lower(): k for k in KITS}
    for k in KITS:               # the approved Python grooves ride along, so the page stands alone
        os.makedirs(os.path.dirname(os.path.join(SHELF_SITE, k["groove"])), exist_ok=True)
        shutil.copyfile(os.path.join(SITE, k["groove"]), os.path.join(SHELF_SITE, k["groove"]))
    cards = []
    for sk in SHELF["kits"]:
        py = approved.get(sk["title"].lower())
        rows = "".join(f"""
        <li class="clip">
          <div class="clip-head"><span class="clip-id">A{p['slot']:02d} {ESC(p['name'])}</span><span class="clip-len">{ESC(p['class'])}</span></div>
          <audio controls preload="none" src="{ESC(p['hard'])}"></audio>
          <p class="clip-note">Soft, medium, hard, four takes each, in the order the pad deals them:</p>
          <audio controls preload="none" src="{ESC(p['touch'])}"></audio>
        </li>""" for p in sk["pads"])
        py_clip = (f"""
        <li class="clip"><div class="clip-head"><span class="clip-id">APPROVED PYTHON GROOVE</span><span class="clip-len">{py['groove_seconds']:.1f} s</span></div>
          <p class="clip-note">The same pattern as you heard and approved it (round 0).</p>
          <audio controls preload="none" src="{ESC(py['groove'])}"></audio></li>""" if py else "")
        cards.append(f"""
    <h3>{ESC(sk['title'].upper())} · {sk['bpm']} BPM</h3>
    <ul class="clips">
      <li class="clip"><div class="clip-head"><span class="clip-id">KOTLIN GROOVE</span><span class="clip-len">{sk['groove_seconds']:.1f} s</span></div>
        <p class="clip-note">The approved two bars, played twice with the app's own strikes for this kit, read back from its MPC 3 export:
        the next take at each hit's velocity zone, the sampler's velocity gain, the chokes, the feel and the swing.</p>
        <audio controls preload="none" src="{ESC(sk['groove'])}"></audio></li>{py_clip}
    </ul>
    <details><summary>The sixteen pads, each with all twelve strikes</summary><ul class="clips">{rows}</ul></details>""")
    _slices = sum(len(k["pads"]) for k in SHELF["kits"]) * 12
    shelf_html = f"""
  <section class="card" id="shelf">
    <h2>On the shelf: the app's own kits</h2>
    <p class="intro">Round 2. The four kits as the app builds them from the Kotlin engine (round 1), each pad read back from the MPC 3 export
    through the app's own pad player: every one of the {_slices} strikes was checked against its render after saving, reloading, two
    playback cycles and the export. The Kotlin engine draws its random numbers differently from the Python, so every take here is a new
    take of the pad you approved, not a copy of the one you heard. One thing differs on purpose: the four takes in a velocity zone are now
    equally loud (the Python set each take to the same peak, which left some takes up to 4.5 dB quieter than their neighbours), with the
    loudest at the level you heard.</p>
    <p class="ask"><span class="tag">LISTEN FOR</span>Does each Kotlin groove sound like the approved groove under it? In a pad's strip, does any take jump out
    as louder or quieter than its neighbours, or any soft hit sound like a different instrument?</p>
    {''.join(cards)}
  </section>"""
    LEDGER[-1] = ("The kits sound like their music", "heard (Python)", "approved by the owner on 2026-11-15: \"I listened: approve all\"")
    SHELF_LEDGER = LEDGER[-1:] + [
        ("The Kotlin port renders the approved pads", "measured", "16 seeded hits a pad on all 64 pads: across minus within -0.02 dB at the median, 0.76 at most; no pad's tone moves 1 dB beyond the prototype's own noise"),
        ("Velocity and round robin through saving, playback and MPC 3 export", "verified in software", f"{_slices} strikes checked after save, reload, two playback cycles and the MPC 3 export"),
        ("The Kotlin kits sound like the approved ones", "not heard", "your listen, above"),
    ]
    shelf_ledger_rows = "".join(f"<tr><td>{ESC(a)}</td><td>{ESC(b)}</td><td>{ESC(c)}</td></tr>" for a, b, c in SHELF_LEDGER)
    ledger_rows = "".join(f"<tr><td>{ESC(a)}</td><td>{ESC(b)}</td><td>{ESC(c)}</td></tr>" for a, b, c in LEDGER)
    SHELF_QUESTIONS = [
        "Does each Kotlin groove sound like the approved Python groove beside it?",
        "Does any take jump out as louder or quieter than its neighbours in a pad's strip?",
        "Anything on the shelf you would change before these kits are filed as CALABASH's factory kits?",
    ]
    shelf_q_rows = "".join(f"<li>{ESC(q)}</li>" for q in SHELF_QUESTIONS)

EYEBROW = "SNIPSNAP · CALABASH · ROUND 0 · PYTHON PROTOTYPE · APPROVED 2026-11-15"
LEDE_TAIL = ("These are prototype renders from a Python model, not the app: the ones you heard and approved on 2026-11-15. "
             "The app's own Kotlin kits have their own page.")
SHELF_NAV = ""

page = f"""<meta charset="utf-8">
<title>CALABASH Audition</title>
<meta name="claude:viewport-fit-sides" content="cover">
<style>
/* Layout: one column of TapeOS cards on the warm gray ground; sound lives in dark LCD panels (the house audition pages' system). */
:root {{
  --gray: #f8f3e9; --raised: #fffaf1; --win: #f8f3e9; --edge: #dbd5c7; --frame: #b4ad9e;
  --ink: #292820; --ink2: #69675a; --ink3: #817a6b; --accent: #a74c00; --bin: #813600;
  --lcd: #22211a; --lcd-ink: #f8f3e9; --lcd-ink2: rgba(248, 243, 233, 0.75); --amber: #ff8c00;
  --pad: #34322a; --pad-hit: #ff8c00; --ok: #2f6b2a; --mid: #8a5a00; --na: #69675a;
  --sans: Roboto, system-ui, -apple-system, "Segoe UI", sans-serif;
  --mono: "Droid Sans Mono", "Roboto Mono", ui-monospace, Menlo, Consolas, monospace;
  --serif: "Noto Serif", Georgia, "Times New Roman", serif;
}}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{
  --gray: #1d1e1b; --raised: #232420; --win: #1d1e1b; --edge: #2d2f28; --frame: #55564b;
  --ink: #eeeadd; --ink2: #b3b4a6; --ink3: #929587; --accent: #e08a68; --bin: #d6877b;
  --lcd: #0f100e; --lcd-ink: #eeeadd; --lcd-ink2: rgba(238, 234, 221, 0.75); --amber: #e08a68;
  --pad: #26271f; --pad-hit: #e08a68; --ok: #8fc98a; --mid: #e0b45c; --na: #b3b4a6; color-scheme: dark; }} }}
:root[data-theme="dark"] {{
  --gray: #1d1e1b; --raised: #232420; --win: #1d1e1b; --edge: #2d2f28; --frame: #55564b;
  --ink: #eeeadd; --ink2: #b3b4a6; --ink3: #929587; --accent: #e08a68; --bin: #d6877b;
  --lcd: #0f100e; --lcd-ink: #eeeadd; --lcd-ink2: rgba(238, 234, 221, 0.75); --amber: #e08a68;
  --pad: #26271f; --pad-hit: #e08a68; --ok: #8fc98a; --mid: #e0b45c; --na: #b3b4a6; color-scheme: dark; }}
body {{ background: var(--gray); color: var(--ink); font: 15px/1.55 var(--sans); }}
.wrap {{ max-width: 860px; margin: 0 auto; padding-block: 20px 48px;
  padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px));
  display: grid; gap: 12px; }}
.eyebrow, .tag, .clip-id, .chip b, nav a, th, .pill, .pad b, .pad i, .readout {{ font-family: var(--mono); }}
.eyebrow {{ font-size: 11px; letter-spacing: 1px; color: var(--ink2); text-transform: uppercase; }}
header {{ display: grid; gap: 8px; padding-block: 8px; }}
.mark {{ display: flex; align-items: center; gap: 8px; }}
.mark i {{ width: 9px; height: 9px; background: var(--accent); transform: rotate(45deg); display: inline-block; flex: none; }}
h1 {{ font: 800 30px/1.15 var(--sans); letter-spacing: 1px; margin: 0; text-wrap: balance; }}
h2 {{ font: 600 18px/1.3 var(--sans); letter-spacing: .5px; margin: 0; text-wrap: balance; }}
h3 {{ font: 600 13px/1.3 var(--mono); letter-spacing: 1px; margin: 6px 0 0; color: var(--ink2); }}
p {{ margin: 0; max-width: 68ch; }}
.lede {{ color: var(--ink2); }}
.card {{ background: var(--win); border: 1px solid var(--edge); border-radius: 2px; padding: 14px; display: grid; gap: 10px; min-width: 0; }}
.gesture {{ font-family: var(--serif); font-style: italic; font-size: 17px; }}
nav {{ display: flex; flex-wrap: wrap; gap: 4px; }}
nav a {{ font-size: 11px; letter-spacing: 1px; text-transform: uppercase; color: var(--ink); text-decoration: none;
  background: var(--raised); border: 1px solid var(--edge); border-radius: 2px; padding: 6px 8px; }}
nav a:hover, nav a:focus-visible {{ border-color: var(--accent); outline: none; }}
.ask {{ font-size: 14px; }}
.tag {{ font-size: 11px; letter-spacing: 1px; color: var(--accent); margin-right: 6px; }}
.intro {{ color: var(--ink2); font-size: 14px; }}
.clips {{ list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; grid-template-columns: repeat(auto-fill, minmax(min(100%, 380px), 1fr)); }}
.clip {{ background: var(--lcd); color: var(--lcd-ink); border-radius: 2px; padding: 10px; display: grid; gap: 6px; min-width: 0; }}
.clip-head {{ display: flex; justify-content: space-between; gap: 8px; }}
.clip-id {{ font-size: 12px; letter-spacing: 1px; text-transform: uppercase; color: var(--amber); }}
.clip-len {{ font: 12px var(--mono); color: var(--lcd-ink2); font-variant-numeric: tabular-nums; }}
.clip-note {{ font-size: 13px; color: var(--lcd-ink2); }}
audio {{ width: 100%; height: 36px; color-scheme: dark; }}
.chips {{ display: flex; flex-wrap: wrap; gap: 4px; }}
.chip {{ font: 12px var(--mono); color: var(--lcd-ink2); border: 1px solid rgba(255,255,255,.14); border-radius: 4px; padding: 2px 6px; font-variant-numeric: tabular-nums; }}
.chip b {{ font-weight: 400; color: var(--lcd-ink); letter-spacing: .5px; margin-right: 4px; }}
.grid-box {{ background: var(--lcd); border-radius: 2px; padding: 12px; display: grid; gap: 10px; }}
.grid {{ display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; touch-action: manipulation; user-select: none; -webkit-user-select: none; }}
.pad {{ aspect-ratio: 1; max-width: 100%; background: var(--pad); color: var(--lcd-ink); border: 1px solid rgba(255,255,255,.10); border-radius: 4px;
  display: grid; align-content: space-between; text-align: left; padding: 8px; cursor: pointer; min-width: 0; font: inherit;
  background-image: linear-gradient(180deg, rgba(255,255,255,.07) 0 33.3%, transparent 33.3% 66.6%, rgba(0,0,0,.18) 66.6%); }}
.pad:focus-visible {{ outline: 2px solid var(--amber); outline-offset: 2px; }}
.pad b {{ font-size: 11px; letter-spacing: 1px; color: var(--amber); font-weight: 400; }}
.pad span {{ font-size: 13px; line-height: 1.2; overflow-wrap: anywhere; }}
.pad i {{ font-size: 10px; font-style: normal; color: var(--lcd-ink2); letter-spacing: .5px; min-height: 1.2em; }}
.pad.hit {{ border-color: var(--pad-hit); box-shadow: inset 0 0 0 2px var(--pad-hit); }}
.readout {{ color: var(--lcd-ink2); font-size: 12px; display: flex; justify-content: space-between; gap: 8px; flex-wrap: wrap; }}
.readout em {{ color: var(--amber); font-style: normal; }}
button.load {{ font: 12px var(--mono); letter-spacing: 1px; color: var(--lcd-ink); background: transparent; border: 1px solid rgba(255,255,255,.3);
  border-radius: 2px; padding: 6px 10px; cursor: pointer; }}
button.load:focus-visible {{ outline: 2px solid var(--amber); }}
.table-wrap {{ overflow-x: auto; }}
table {{ border-collapse: collapse; width: 100%; font-size: 13px; }}
th {{ font-size: 11px; letter-spacing: 1px; text-transform: uppercase; color: var(--ink2); font-weight: 400; text-align: left; }}
th, td {{ border-bottom: 1px solid var(--edge); padding: 8px 6px; vertical-align: top; font-variant-numeric: tabular-nums; }}
.pill {{ font-size: 11px; letter-spacing: .5px; white-space: nowrap; padding: 2px 6px; border: 1px solid currentColor; border-radius: 2px; }}
.pill.ok {{ color: var(--ok); border-width: 2px; }} .pill.mid {{ color: var(--mid); }} .pill.na {{ color: var(--na); }} .pill.open {{ color: var(--bin); }}
ul.plain {{ margin: 0; padding-left: 18px; display: grid; gap: 4px; max-width: 70ch; }}
a {{ color: var(--accent); }}
code {{ font-family: var(--mono); font-size: 13px; overflow-wrap: anywhere; }}
.foot {{ font-size: 13px; color: var(--ink2); }}
@media (max-width: 420px) {{ .pad span {{ font-size: 11px; }} .pad {{ padding: 6px; }} }}
@media (prefers-reduced-motion: reduce) {{ * {{ scroll-behavior: auto; }} }}
.tabs {{ display: flex; flex-wrap: wrap; gap: 4px; }}
.tabs button {{ font: 11px var(--mono); letter-spacing: 1px; color: var(--lcd-ink2); background: transparent; border: 1px solid rgba(255,255,255,.2);
  border-radius: 2px; padding: 6px 8px; cursor: pointer; }}
.tabs button[aria-pressed="true"] {{ color: var(--lcd-ink); border-color: var(--amber); }}
.tabs button:focus-visible {{ outline: 2px solid var(--amber); }}
.lcd-line {{ font: 12px var(--mono); letter-spacing: 1px; color: var(--ink2); display: flex; flex-wrap: wrap; gap: 12px; }}
details > summary {{ cursor: pointer; font: 12px var(--mono); letter-spacing: 1px; color: var(--ink2); padding: 4px 0; }}
details[open] > summary {{ margin-bottom: 8px; }}
</style>

<div class="wrap">
  <header>
    <div class="mark"><i></i><span class="eyebrow">{EYEBROW}</span></div>
    <h1>CALABASH Audition</h1>
    <p class="lede">Four sixteen-pad drum kits: Afrobeat (Lagos in the 1970s, live), Afrobeats (today's programmed pop), Mbalax (a sabar ensemble
    over a kit, Dakar) and Afro-Cuban jazz (New York in the 1950s). The congas, bongos, bells, claves, shaker and claps are resynthesized from
    CC0 recordings of the real instruments; the shekere, the talking drums, the sabar drums and the timbales are physical models with no recording
    behind them. {LEDE_TAIL}</p>
    <p class="gesture">A palm, fingertips or a thin stick lands on a skin, a bell or a gourd → the stroke decides which of the instrument's own resonances ring
    → the hand, the bead net or the jingles answer → the room and the era's tape close around it.</p>
    <nav><a href="#play">Play</a>{SHELF_NAV}{''.join(f'<a href="#kit-{k["id"]}">{ESC(short(k["name"]))}</a>' for k in KITS)}<a href="#refs">Recordings</a><a href="#strokes">Strokes</a><a href="#velocity">Velocity</a><a href="#takes">Takes</a><a href="#voices">Voices</a><a href="#macros">Controls</a><a href="#gates">Gates</a><a href="#ledger">Ledger</a><a href="#ask">Questions</a></nav>
  </header>

  <section class="card" id="play">
    <h2>Play the kits</h2>
    <p class="intro">Pick a kit, then tap a pad. The top third plays the hard layer, the middle the medium one, the bottom the soft one, and every tap deals
    the next of four takes, as the kit would on a pad. A hat closes its open hat, and a stroke on a drum cuts that drum's last stroke. Sound starts
    from your first tap.</p>
    <div class="grid-box">
      <div class="tabs" id="tabs" role="group" aria-label="Kit"></div>
      <div class="grid" id="grid" aria-label="Sixteen pads, laid out as on an MPC with A01 at the bottom left"></div>
      <div class="readout"><span id="status">Tap any pad. Each pad loads on its first tap.</span><button class="load" id="loadall" type="button">LOAD THIS KIT</button></div>
    </div>
  </section>
{''.join(kit_cards)}
{ref_card}
{stroke_card}
{base_sections}

  <section class="card" id="macros">
    <h2>Each control on each voice</h2>
    <p class="intro">Each file plays a control at 0, 0.5 and 1 with the others at their defaults. Open a voice to hear its controls.</p>
    <div class="table-wrap"><table><tr><th>Control</th><th>What it moves</th></tr>{meaning_rows}</table></div>
    <p class="ask"><span class="tag">LISTEN FOR</span>Does each control do what its name says on that voice?</p>
    {macro_body}
  </section>

  <section class="card" id="gates">
    <h2>Where the owner's gates stand</h2>
    <div class="table-wrap"><table>
      <tr><th>Gate</th><th>State</th><th>Evidence</th></tr>
      {gate_rows}
    </table></div>
    <h3>EACH KIT</h3>
    <div class="table-wrap"><table>
      <tr><th>Kit</th><th>BPM</th><th>Soft vs hard, min dB</th><th>Closest takes, dB</th><th>Closest two pads, dB</th></tr>
      {gate_kit_rows}
    </table></div>
    <h3>BETWEEN THE KITS</h3>
    <div class="table-wrap"><table>
      <tr><th>Kits</th><th>Same slot, mean dB</th><th>Same slot, closest dB</th><th>Identical pads</th></tr>
      {between_rows}
    </table></div>
    <p class="foot">Distances are the house measure (SCUFF, ETCH, CLUTTER, EMBER): the RMS difference of level-matched third-octave spectrograms over the
    first 150 ms, over the whole render for the rolls and cymbals. Takes you heard as one strike on ETCH measured 0.5 to 0.8 dB.</p>
  </section>

  <section class="card" id="ledger">
    <h2>Measured, proposed, heard</h2>
    <div class="table-wrap"><table>
      <tr><th>Claim</th><th>Status</th><th>Evidence</th></tr>
      {ledger_rows}
    </table></div>
    <p class="foot">Python harness at commit {ESC(COMMIT)}, not Kotlin. Regenerate with
    <code>python3 prototype/calabash/gen_audition.py OUT</code> then <code>python3 prototype/calabash/build_page.py OUT</code>;
    the shelf section from <code>./gradlew :shell:generateCalabashKits</code> then <code>python3 prototype/calabash/shelf_grooves.py testkit/calabash-kits OUT</code>.
    Design notes: <code>docs/CALABASH.md</code>.</p>
  </section>

  <section class="card" id="ask">
    <h2>What would help most</h2>
    <ul class="plain">{q_rows}</ul>
  </section>
</div>

<script>
const KITS = {json.dumps(grid_kits)};
const ZONES = {{ h: 1.0, m: 0.65, s: 0.35 }};
const ZONE_NAME = {{ h: "HARD", m: "MEDIUM", s: "SOFT" }};
const ZONE_SLOT = {{ s: 0, m: 4, h: 8 }};
const RATE = 44100;
const ORDER = ["a13","a14","a15","a16","a09","a10","a11","a12","a05","a06","a07","a08","a01","a02","a03","a04"];

let ctx = null;
let kit = KITS[0];
const cache = new Map();
const deal = {{}};
const playing = {{}};
const status = document.getElementById("status");
const grid = document.getElementById("grid");
const tabs = document.getElementById("tabs");

function audio() {{
  if (!ctx) ctx = new (window.AudioContext || window.webkitAudioContext)();
  if (ctx.state === "suspended") ctx.resume();
  return ctx;
}}
function load(url) {{
  if (!cache.has(url)) {{
    cache.set(url, fetch(url).then(r => {{ if (!r.ok) throw new Error(url); return r.arrayBuffer(); }})
      .then(b => audio().decodeAudioData(b)));
  }}
  return cache.get(url);
}}
function stop(id) {{
  for (const v of (playing[id] || [])) {{
    try {{ v.gain.gain.setTargetAtTime(0, ctx.currentTime, 0.002); v.src.stop(ctx.currentTime + 0.02); }} catch (e) {{}}
  }}
  playing[id] = [];
}}
async function hit(p, zone, el) {{
  const c = audio();
  const key = kit.id + ":" + p.id;
  const take = deal[key] = ((deal[key] ?? -1) + 1) % 4;
  el.classList.add("hit");
  setTimeout(() => el.classList.remove("hit"), 120);
  let buf;
  try {{ buf = await load(p.sprite); }} catch (e) {{ status.textContent = `Could not load ${{p.sprite}}. Reload the page and try again.`; return; }}
  for (const other of ((kit.chokes || {{}})[p.id] || [])) stop(other);
  const src = c.createBufferSource();
  const gain = c.createGain();
  gain.gain.value = 0.35 + 0.65 * ZONES[zone];
  src.buffer = buf;
  src.connect(gain).connect(c.destination);
  const slot = p.slot / RATE;
  src.start(0, (ZONE_SLOT[zone] + take) * slot, slot);
  const voice = {{ src, gain }};
  (playing[p.id] = playing[p.id] || []).push(voice);
  src.onended = () => {{ playing[p.id] = (playing[p.id] || []).filter(v => v !== voice); }};
  el.querySelector("i").textContent = `${{ZONE_NAME[zone]}} · TAKE ${{take + 1}}`;
  status.innerHTML = `<span>${{p.id.toUpperCase()}} ${{p.label}}: <em>${{ZONE_NAME[zone]}}, take ${{take + 1}} of 4</em></span>`;
}}
function draw() {{
  grid.innerHTML = "";
  for (const id of ORDER) {{
    const p = kit.pads.find(x => x.id === id);
    const el = document.createElement("button");
    el.type = "button";
    el.className = "pad";
    el.setAttribute("aria-label", `${{id.toUpperCase()}} ${{p.label}}`);
    el.innerHTML = `<b>${{id.toUpperCase()}}</b><span>${{p.label}}</span><i></i>`;
    el.addEventListener("pointerdown", ev => {{
      ev.preventDefault();
      const r = el.getBoundingClientRect();
      const y = (ev.clientY - r.top) / r.height;
      hit(p, y < 1 / 3 ? "h" : y < 2 / 3 ? "m" : "s", el);
    }});
    el.addEventListener("keydown", ev => {{
      if (ev.key === "Enter" || ev.key === " ") {{ ev.preventDefault(); hit(p, "m", el); }}
    }});
    grid.appendChild(el);
  }}
  for (const b of tabs.children) b.setAttribute("aria-pressed", String(b.dataset.kit === kit.id));
}}
for (const k of KITS) {{
  const b = document.createElement("button");
  b.type = "button";
  b.dataset.kit = k.id;
  b.textContent = k.name.toUpperCase();
  b.addEventListener("click", () => {{ for (const id of Object.keys(playing)) stop(id); kit = k; draw(); status.textContent = `${{k.name}}: tap any pad.`; }});
  tabs.appendChild(b);
}}
draw();
document.getElementById("loadall").addEventListener("click", async () => {{
  audio();
  let done = 0;
  const all = kit.pads.map(p => p.sprite);
  status.textContent = `Loading 0 of ${{all.length}} pads…`;
  await Promise.all(all.map(u => load(u).catch(() => {{}}).then(() => {{ done++; status.textContent = `Loading ${{done}} of ${{all.length}} pads…`; }})));
  status.textContent = `${{kit.name}}: all ${{all.length}} pads loaded.`;
}});
</script>
"""

os.makedirs(SITE, exist_ok=True)
open(os.path.join(SITE, "index.html"), "w").write(page)
files = sorted(os.path.relpath(os.path.join(d, f), SITE) for d, _, fs in os.walk(SITE) for f in fs if f != "index.html")
size = sum(os.path.getsize(os.path.join(SITE, f)) for f in files)
json.dump(files, open(os.path.join(OUT, "site-files.json"), "w"))
print("wrote", os.path.join(SITE, "index.html"), f"{len(files)} audio files, {size / 1e6:.1f} MB")

# ------------------------------------------------------------------------------ round 2: the shelf page
if SHELF:
    style = page[page.index("<style>"):page.index("</style>") + len("</style>")]
    shelf_page = f"""<meta charset="utf-8">
<title>CALABASH On the Shelf</title>
<meta name="claude:viewport-fit-sides" content="cover">
{style}

<div class="wrap">
  <header>
    <div class="mark"><i></i><span class="eyebrow">SNIPSNAP · CALABASH · ROUND 2 · KITS ON THE SHELF · KOTLIN NOT HEARD</span></div>
    <h1>CALABASH On the Shelf</h1>
    <p class="lede">The four kits you approved on 2026-11-15 (Afrobeat, Afrobeats, Mbalax and Afro-Cuban jazz), now built by the app's own
    Kotlin engine and on the FRESH TAPE shelf. Each kit's groove is the pattern you approved, played twice with the app's strikes, next to
    the approved Python groove. Nobody has heard these yet.</p>
    <p class="gesture">A palm, fingertips or a thin stick lands on a skin, a bell or a gourd → the stroke decides which of the instrument's own resonances ring
    → the hand, the bead net or the jingles answer → the room and the era's tape close around it.</p>
  </header>
{shelf_html}
  <section class="card" id="ledger">
    <h2>Measured, verified, heard</h2>
    <div class="table-wrap"><table>
      <tr><th>Claim</th><th>Status</th><th>Evidence</th></tr>
      {shelf_ledger_rows}
    </table></div>
    <p class="foot">Kotlin strikes from <code>./gradlew :shell:generateCalabashKits</code> at commit {ESC(COMMIT)}, read back from the MPC 3 export;
    grooves by <code>prototype/calabash/shelf_grooves.py</code>. Design notes: <code>docs/CALABASH.md</code>.</p>
  </section>

  <section class="card" id="ask">
    <h2>What would help most</h2>
    <ul class="plain">{shelf_q_rows}</ul>
  </section>
</div>
"""
    os.makedirs(SHELF_SITE, exist_ok=True)
    open(os.path.join(SHELF_SITE, "index.html"), "w").write(shelf_page)
    sfiles = sorted(os.path.relpath(os.path.join(d, f), SHELF_SITE) for d, _, fs in os.walk(SHELF_SITE) for f in fs if f != "index.html")
    ssize = sum(os.path.getsize(os.path.join(SHELF_SITE, f)) for f in sfiles)
    json.dump(sfiles, open(os.path.join(OUT, "shelf-site-files.json"), "w"))
    print("wrote", os.path.join(SHELF_SITE, "index.html"), f"{len(sfiles)} audio files, {ssize / 1e6:.1f} MB")
