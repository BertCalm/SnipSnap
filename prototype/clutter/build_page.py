"""Build the CLUTTER round-0 audition page (index.html) from manifest.json.

Every number on the page is read from the manifest the generator wrote; nothing is typed in by
hand. The look is the house audition page's (SCUFF, ETCH): TapeOS cards, LCD panels.
Run after gen_audition.py:   python3 build_page.py OUT_DIR
"""
from __future__ import annotations

import html
import json
import os
import subprocess
import sys

OUT = sys.argv[1] if len(sys.argv) > 1 else "clutter-audition"
M = json.load(open(os.path.join(OUT, "manifest.json")))
try:
    COMMIT = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], text=True,
                                     cwd=os.path.dirname(os.path.abspath(__file__))).strip()
except Exception:  # noqa: BLE001
    COMMIT = "unknown"
ESC = html.escape

LABELS = {
    "centroid_hz": ("CENTROID", "{:.0f} Hz"), "soft_centroid_hz": ("SOFT CENTROID", "{:.0f} Hz"),
    "decay_ms": ("TO -60 dB", "{:.0f} ms"), "crest_db": ("CREST", "{:.1f} dB"),
    "soft_vs_hard_db": ("SOFT VS HARD", "{:.1f} dB"), "after_soft_db": ("RATTLE SOFT", "{:+.1f} dB"),
    "after_hard_db": ("RATTLE HARD", "{:+.1f} dB"), "closest_takes_db": ("CLOSEST TAKES", "{:.1f} dB"),
    "widest_takes_db": ("WIDEST TAKES", "{:.1f} dB"), "render_ms": ("RENDER", "{:.0f} ms"),
    "hz_early_hard": ("BOOM AT 4-30 ms", "{:.0f} Hz"), "hz_late_hard": ("AT 100-300 ms", "{:.0f} Hz"),
    "hz_early_soft": ("SOFT BOOM", "{:.0f} Hz"), "hits": ("HITS", "{:d}"), "bars": ("BARS", "{:d}"),
    "first_gap_ms": ("FIRST GAP", "{:.0f} ms"), "last_gap_ms": ("LAST GAP", "{:.0f} ms"),
    "centroid_lo_hz": ("CENTROID AT 0", "{:.0f} Hz"), "centroid_hi_hz": ("AT 1", "{:.0f} Hz"),
    "decay_lo_ms": ("DECAY AT 0", "{:.0f} ms"), "decay_hi_ms": ("AT 1", "{:.0f} ms"),
    "lo_vs_hi_db": ("0 VS 1", "{:.1f} dB"),
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


SECTIONS = {
    "kit": ("The kit as it lands",
            "Sixteen pads, each a hard hit on its first take. A01 to A04 sit where an MPC player expects the kick, snare and hats; "
            "A15 is the roll and A16 the groove.",
            "Does this read as a kit made of real things? Which objects already convince you, and which sound like a synth pretending?"),
    "velocity": ("Velocity: the strike, not the volume",
                 "Each file is soft, medium, hard on one pad, each peak-matched so only the strike differs. A harder hit is a shorter, brighter "
                 "contact, and past a fixed threshold the loose parts lift off and land a moment later.",
                 "Soft should be a clean knock, hard should make things move. Does the rattle arrive at the right strength, or switch on too suddenly?"),
    "takes": ("Round robin: four takes",
              "The four takes a pad deals in turn, all hard. A take is the hand landing somewhere else on the object, and the loose parts "
              "falling another way.",
              "Do the four sound like one object hit four times, rather than four objects or one copy?"),
    "macros": ("Each control on each object",
               "Each file plays a control at 0, 0.5 and 1 with the others at their defaults.",
               "Does each control do what its name says on that object? Which ones would you never touch?"),
}

kit_clips = next(s for s in M["sections"] if s["id"] == "kit")["clips"]
pads = []
for k in M["kit"]:
    pad = k["pad"].lower()
    one_shot = k["pad"] not in ("A15", "A16")
    pads.append({"id": pad, "label": k["label"], "voice": k["voice"], "cls": k["class"], "oneShot": one_shot})

sections_html = []
for sid, (title, intro, ask) in SECTIONS.items():
    sec = next(s for s in M["sections"] if s["id"] == sid)
    if sid == "macros":
        groups = {}
        for c in sec["clips"]:
            groups.setdefault(c["voice"], []).append(c)
        body = "".join(f"""
      <h3>{ESC(v)}</h3>
      <ul class="clips">{''.join(clip_html(c) for c in cs)}</ul>""" for v, cs in groups.items())
    else:
        body = f'<ul class="clips">{"".join(clip_html(c) for c in sec["clips"])}</ul>'
    sections_html.append(f"""
  <section class="card" id="{sid}">
    <h2>{ESC(title)}</h2>
    <p class="intro">{ESC(intro)}</p>
    <p class="ask"><span class="tag">LISTEN FOR</span>{ESC(ask)}</p>
    {body}
  </section>""")

C = M["checks"]
act = C["macro_activity_db"]
kit_rows = "".join(
    f"<tr><td>{ESC(k['pad'])}</td><td>{ESC(k['label'])}</td><td>{ESC(k['voice'])}</td><td>{ESC(k['class'])}</td>"
    f"<td>{k['metrics'].get('soft_vs_hard_db', '')}</td><td>{k['metrics'].get('closest_takes_db', '')}</td>"
    f"<td>{k['metrics'].get('widest_takes_db', '')}</td></tr>" for k in M["kit"] if "soft_vs_hard_db" in k["metrics"])

GATES = [
    ("Melodic, keygroup, chord tool", "na", "Not applicable. A drum engine; the objects are not tuned to notes."),
    ("Articulation (HOLD, STRUM)", "na", "Finite hits, no HOLD. Not a plucked string."),
    ("Percussion: roll or riser", "mid", "A15 Pot Roll is authored in the prototype. Not heard."),
    ("Drums: several genre kits on the shelf", "open", "One audition kit. Genre kits wait for your verdict on the objects."),
    ("Velocity in rendering", "mid", f"Measured in Python: soft against hard {C['soft_vs_hard_min_db']} dB at the closest pad, at matched level."),
    ("Round robin in rendering", "mid", f"Measured in Python: the closest pair of takes is {C['closest_takes_min_db']} dB apart, the widest {C['widest_takes_max_db']} dB."),
    ("Velocity and round robin through saving, playback, export", "open", "No Kotlin yet, so nothing to save, play or export. The page's pad grid is a browser stand-in, not the app."),
]
gate_rows = "".join(f'<tr><td>{ESC(a)}</td><td><span class="pill {s}">{ {"na": "N/A", "mid": "MEASURED", "open": "OPEN"}[s] }</span></td><td>{ESC(b)}</td></tr>'
                    for a, s, b in GATES)

LEDGER = [
    ("Every render is deterministic, finite, peak 0.89, DC under 0.005", "measured", f"deterministic {C['deterministic']}, finite {C['finite']}, peak {C['peak_max']}, DC {C['dc_max']}"),
    ("Every control moves every object", "measured", f"weakest is {C['weakest_macro']} at {C['weakest_macro_db']} dB between 0 and 1"),
    ("An unknown control or a non-finite value changes nothing", "measured", f"unknown {C['unknown_key_ignored']}, NaN control {C['nan_macro_falls_back']}, NaN velocity {C['nan_velocity_falls_back']}"),
    ("A hit renders in tens of milliseconds", "measured", f"Python: mean {C['render_ms_mean']} ms, slowest {C['render_ms_max']} ms; the MIC response builds in {C['mic_ir_build_ms']} ms and is cached"),
    ("The loose parts make velocity a timbre change", "measured", "soft and hard differ on every pad; the rattle is absent below the threshold by construction"),
    ("The objects sound like the objects", "not heard", "your listen"),
]
ledger_rows = "".join(f"<tr><td>{ESC(a)}</td><td>{ESC(b)}</td><td>{ESC(c)}</td></tr>" for a, b, c in LEDGER)

page = f"""<meta charset="utf-8">
<title>CLUTTER Audition</title>
<meta name="claude:viewport-fit-sides" content="cover">
<style>
/* Layout: one column of TapeOS cards on the warm gray ground; sound lives in dark LCD panels (the SCUFF and ETCH pages' system). */
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
.eyebrow, .tag, .clip-id, .chip b, nav a, th, .pill, .lcd, .pad b, .pad i, .readout {{ font-family: var(--mono); }}
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
.lcd {{ background: var(--lcd); color: var(--lcd-ink); border-radius: 2px; padding: 12px 14px; font-size: 13px; line-height: 1.5;
  overflow-x: auto; white-space: pre; background-image: repeating-linear-gradient(0deg, rgba(0,0,0,.18) 0 1px, transparent 1px 3px); }}
.lcd em {{ color: var(--amber); font-style: normal; }}
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
.pad.on {{ border-color: var(--pad-hit); }}
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
.foot {{ font-size: 13px; color: var(--ink2); }}
@media (max-width: 420px) {{ .pad span {{ font-size: 11px; }} .pad {{ padding: 6px; }} }}
@media (prefers-reduced-motion: reduce) {{ * {{ scroll-behavior: auto; }} }}
</style>

<div class="wrap">
  <header>
    <div class="mark"><i></i><span class="eyebrow">SNIPSNAP · CLUTTER · ROUND 0 · PYTHON PROTOTYPE · NOT HEARD</span></div>
    <h1>CLUTTER Audition</h1>
    <p class="lede">A found-sound drum kit made from modelled household objects: an upturned bucket for the kick, a box of rice for the snare,
    a jar and a key ring for the hats, a book, a table, a saucepan and a bottle. Nothing is sampled. These are prototype renders from a Python model,
    not the app, and nobody has heard them yet.</p>
    <p class="gesture">A hand or a spoon hits an everyday object → its body rings → whatever is loose in it or on it rattles a moment later → the kitchen answers, then it is still.</p>
    <nav><a href="#play">Play</a><a href="#kit">Kit</a><a href="#velocity">Velocity</a><a href="#takes">Takes</a><a href="#macros">Controls</a><a href="#gates">Gates</a><a href="#ledger">Ledger</a></nav>
  </header>

  <section class="card" id="play">
    <h2>Play the kit</h2>
    <p class="intro">Tap a pad. The top third plays the hard layer, the middle the medium one, the bottom the soft one, and every tap deals the next of four takes,
    as the kit would on a pad. The rice jar (A03) chokes the key ring (A04). A15 and A16 play once; tap again to stop. Sound starts from your first tap.</p>
    <div class="grid-box">
      <div class="grid" id="grid" aria-label="Sixteen pads, laid out as on an MPC with A01 at the bottom left"></div>
      <div class="readout"><span id="status">Tap any pad. Each pad loads on its first tap.</span><button class="load" id="loadall" type="button">LOAD ALL PADS</button></div>
    </div>
    <p class="ask"><span class="tag">LISTEN FOR</span>Play A01 and A02 together, soft then hard. Does the snare's rice come in where your hand expects it to?</p>
  </section>
{''.join(sections_html)}

  <section class="card" id="gates">
    <h2>Where the owner's gates stand</h2>
    <div class="table-wrap"><table>
      <tr><th>Gate</th><th>State</th><th>Evidence</th></tr>
      {gate_rows}
    </table></div>
    <div class="table-wrap"><table>
      <tr><th>Pad</th><th>Name</th><th>Object</th><th>Class</th><th>Soft vs hard, dB</th><th>Closest takes, dB</th><th>Widest takes, dB</th></tr>
      {kit_rows}
    </table></div>
    <p class="foot">Distances are the house measure (SCUFF, ETCH): the RMS difference of level-matched third-octave spectrograms over the first 150 ms.
    Takes you heard as one strike on ETCH measured 0.5 to 0.8 dB.</p>
  </section>

  <section class="card" id="ledger">
    <h2>Measured, proposed, heard</h2>
    <div class="table-wrap"><table>
      <tr><th>Claim</th><th>Status</th><th>Evidence</th></tr>
      {ledger_rows}
    </table></div>
    <p class="foot">Python harness at commit {ESC(COMMIT)}, not Kotlin. Regenerate with
    <code>python3 prototype/clutter/gen_audition.py OUT</code> then <code>python3 prototype/clutter/build_page.py OUT</code>.
    Design notes: <code>docs/CLUTTER.md</code>.</p>
  </section>
</div>

<script>
const PADS = {json.dumps(pads)};
const ZONES = {{ h: 1.0, m: 0.65, s: 0.35 }};
const ZONE_NAME = {{ h: "HARD", m: "MEDIUM", s: "SOFT" }};
const ORDER = ["a13","a14","a15","a16","a09","a10","a11","a12","a05","a06","a07","a08","a01","a02","a03","a04"];
const CHOKES = {{ a03: ["a04"] }};
let ctx = null;
const cache = new Map();
const deal = {{}};
const playing = {{}};
const status = document.getElementById("status");

function files(p) {{
  if (!p.oneShot) return [`grid/${{p.id}}.wav`];
  const out = [];
  for (const z of ["s", "m", "h"]) for (let t = 0; t < 4; t++) out.push(`grid/${{p.id}}-${{z}}${{t}}.wav`);
  return out;
}}
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
  const list = playing[id] || [];
  for (const v of list) {{
    try {{ v.gain.gain.setTargetAtTime(0, ctx.currentTime, 0.002); v.src.stop(ctx.currentTime + 0.02); }} catch (e) {{}}
  }}
  playing[id] = [];
  const el = document.querySelector(`[data-pad="${{id}}"]`);
  if (el) el.classList.remove("on");
}}
async function hit(p, zone, el) {{
  const c = audio();
  if (!p.oneShot && (playing[p.id] || []).length) {{ stop(p.id); status.textContent = `${{p.id.toUpperCase()}} stopped.`; return; }}
  const take = p.oneShot ? (deal[p.id] = ((deal[p.id] ?? -1) + 1) % 4) : 0;
  const url = p.oneShot ? `grid/${{p.id}}-${{zone}}${{take}}.wav` : `grid/${{p.id}}.wav`;
  el.classList.add("hit");
  setTimeout(() => el.classList.remove("hit"), 120);
  let buf;
  try {{ buf = await load(url); }} catch (e) {{ status.textContent = `Could not load ${{url}}. Reload the page and try again.`; return; }}
  for (const other of (CHOKES[p.id] || [])) stop(other);
  const src = c.createBufferSource();
  const gain = c.createGain();
  gain.gain.value = p.oneShot ? 0.35 + 0.65 * ZONES[zone] : 1.0;
  src.buffer = buf;
  src.connect(gain).connect(c.destination);
  src.start();
  const voice = {{ src, gain }};
  (playing[p.id] = playing[p.id] || []).push(voice);
  src.onended = () => {{
    playing[p.id] = (playing[p.id] || []).filter(v => v !== voice);
    if (!playing[p.id].length) el.classList.remove("on");
  }};
  if (!p.oneShot) el.classList.add("on");
  const tag = el.querySelector("i");
  tag.textContent = p.oneShot ? `${{ZONE_NAME[zone]}} · TAKE ${{take + 1}}` : "PLAYING";
  status.innerHTML = `<span>${{p.id.toUpperCase()}} ${{p.label}}: <em>${{p.oneShot ? ZONE_NAME[zone] + ", take " + (take + 1) + " of 4" : "playing"}}</em></span>`;
  // Warm the rest of this pad's layers so the next tap is instant.
  files(p).forEach(u => load(u).catch(() => {{}}));
}}
const grid = document.getElementById("grid");
for (const id of ORDER) {{
  const p = PADS.find(x => x.id === id);
  const el = document.createElement("button");
  el.type = "button";
  el.className = "pad";
  el.dataset.pad = id;
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
document.getElementById("loadall").addEventListener("click", async () => {{
  audio();
  const all = PADS.flatMap(files);
  let done = 0;
  status.textContent = `Loading 0 of ${{all.length}} files…`;
  await Promise.all(all.map(u => load(u).catch(() => {{}}).then(() => {{ done++; status.textContent = `Loading ${{done}} of ${{all.length}} files…`; }})));
  status.textContent = `All ${{all.length}} files loaded.`;
}});
</script>
"""

open(os.path.join(OUT, "index.html"), "w").write(page)
print("wrote", os.path.join(OUT, "index.html"))
