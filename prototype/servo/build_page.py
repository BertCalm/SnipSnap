"""Build the SERVO audition page (index.html) from manifest.json.

Every number on the page is read from the manifest the generator wrote; nothing is typed in by
hand. The look is the house audition page's (SCUFF, ETCH, CLUTTER): page.css is their stylesheet.
Run after gen_audition.py:   python3 build_page.py OUT_DIR
"""
from __future__ import annotations

import html
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1] if len(sys.argv) > 1 else "servo-audition"
M = json.load(open(os.path.join(OUT, "manifest.json")))
CSS = open(os.path.join(HERE, "page.css")).read()
try:
    COMMIT = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], text=True, cwd=HERE).strip()
except Exception:  # noqa: BLE001
    COMMIT = "unknown"
ESC = html.escape

LABELS = {
    "centroid_hz": ("CENTROID", "{:.0f} Hz"), "soft_centroid_hz": ("SOFT CENTROID", "{:.0f} Hz"),
    "decay_ms": ("TO -60 dB", "{:.0f} ms"), "rail_max": ("DEEPEST SAG", "{:.2f}"),
    "soft_vs_hard_db": ("SOFT VS HARD", "{:.1f} dB"),
    "closest_takes_db": ("CLOSEST TAKES", "{:.1f} dB"), "widest_takes_db": ("WIDEST TAKES", "{:.1f} dB"),
    "off_vs_sag_db": ("OFF VS SAG", "{:.1f} dB"), "sag_vs_ring_db": ("SAG VS RINGING", "{:.1f} dB"),
    "ripple_damped_db": ("RIPPLE, DAMPED", "{:.1f} dB"), "ripple_ringing_db": ("RINGING", "{:.1f} dB"),
    "lo_vs_hi_db": ("0 VS 1", "{:.1f} dB"), "centroid_lo_hz": ("CENTROID AT 0", "{:.0f} Hz"), "centroid_hi_hz": ("AT 1", "{:.0f} Hz"),
    "decay_lo_ms": ("DECAY AT 0", "{:.0f} ms"), "decay_hi_ms": ("AT 1", "{:.0f} ms"),
}


def web(rel: str) -> str:
    """What the page plays: the WAV itself for pad sprites (the grid slices them by frame, and the
    page host serves WAV but not FLAC), the MP3 copy for clips."""
    return rel if rel.startswith("pads/") else rel[:-4] + ".mp3"


def chips(metrics: dict) -> str:
    return "".join(f'<span class="chip"><b>{ESC(label)}</b> {ESC(fmt.format(metrics[k]))}</span>'
                   for k, (label, fmt) in LABELS.items() if metrics.get(k) is not None)


def clip(title, file, note, metrics=None, length=None):
    tail = f'<span class="clip-len">{length:.1f} s</span>' if length else ""
    return f"""
      <li class="clip">
        <div class="clip-head"><span class="clip-id">{ESC(title)}</span>{tail}</div>
        <p class="clip-note">{ESC(note)}</p>
        <audio controls preload="none" src="{ESC(web(file))}"></audio>
        <div class="chips">{chips(metrics or {})}</div>
      </li>"""


def section(sid):
    return next(s for s in M["sections"] if s["id"] == sid)


KITS = M["kits"]
C = M["checks"]
B = M["between"]

kit_cards = []
for k in KITS:
    rows = "".join(
        f"<tr><td>{ESC(r['pad'])}</td><td>{ESC(r['label'])}</td><td>{ESC(r['voice'])}</td>"
        f"<td>{' '.join(f'{int(round(v * 100)):d}' for v in r['macros'].values())}</td>"
        f"<td>{r['metrics']['decay_ms']:.0f}</td><td>{r['metrics']['soft_vs_hard_db']}</td><td>{r['metrics']['closest_takes_db']}</td></tr>"
        for r in k["pads"])
    p = k["phrases"]
    kit_cards.append(f"""
  <section class="card" id="kit-{k['id']}">
    <h2>{ESC(k['name'])}</h2>
    <p class="lcd-line"><span>{k['bpm']:.0f} BPM</span><span>RAIL GROWL {k['rail_growl']}</span><span>MEAN DECAY {k['mean_decay_ms']} ms</span></p>
    <p class="intro">{ESC(k['character'])}</p>
    <ul class="clips">
      {clip("GROOVE · ONE RAIL", p['groove']['shared'], "Two bars, played twice, baked on one shared rail: every hit pulls the same supply, so the kick moves the hats.", None, p['groove']['seconds'])}
      {clip("GROOVE · PADS AS EXPORTED", p['groove']['own'], f"The same notes with every hit on its own rail: what the sixteen one-shot pads sound like from a sequencer. The two differ by {p['groove']['shared_vs_own_db']} dB on the house measure.", None, p['groove']['seconds'])}
      {clip("B01 ROLL · ONE RAIL", p['roll']['shared'], "Two beats. The hits pile up on the rail, so the roll sinks as it tightens.", None, p['roll']['seconds'])}
      {clip("B02 RISER · ONE RAIL", p['riser']['shared'], "Four beats of gathering hits, then the landing.", None, p['riser']['seconds'])}
      {clip("ALL 16 PADS", k['run'], "A01 to A16 in order, hard, first take, each peak-matched.")}
    </ul>
    <details><summary>The pads and their settings</summary><div class="table-wrap"><table>
      <tr><th>Pad</th><th>Name</th><th>Voice</th><th>TUNE DECAY ZAP SAG GROWL HEAT, %</th><th>To -60 dB, ms</th><th>Soft vs hard, dB</th><th>Closest takes, dB</th></tr>{rows}
    </table></div></details>
    <p class="ask"><span class="tag">LISTEN FOR</span>Is this the genre? Does the one-rail groove add movement you want, or does it smear the hats? Which pads would you swap or retune?</p>
  </section>""")

base_sections = []
for sid, title, intro, ask in (
    ("rail", "The rail on its own", "The one new mechanism. Each file is the same hit three times: the rail switched off (a plain machine drum), "
     "the rail sagging and recovering smoothly, and the rail ringing as it recovers.",
     "Does the sag add punch and a dive without thinning the drum? Is the ringing rail a growl, or only a wobble? This is the first thing to judge: if it does not work here, the kits will not fix it."),
    ("voices", "The ten voices", "Each voice at its default settings, a hard hit on its first take.",
     "Which voices already sound like a drum machine you would use, and which sound thin or toy-like?"),
    ("velocity", "Velocity: the hit, not the volume", "Soft, medium, hard on each voice, peak-matched so only the character differs. A harder hit pulls more current, so it dives deeper and clips harder.",
     "Is soft a cleaner, rounder drum and hard a meaner one?"),
    ("takes", "Round robin: four takes", "Take 1 fires on a rested rail. Takes 2 to 4 fire while the rail is still recovering from an earlier hit, with the trigger a little stronger or weaker each time.",
     "Do the four sound like one drum caught at four moments, rather than four drums or one copy? The kick and the rim vary least."),
):
    sec = section(sid)
    base_sections.append(f"""
  <section class="card" id="{sid}">
    <h2>{ESC(title)}</h2>
    <p class="intro">{ESC(intro)}</p>
    <p class="ask"><span class="tag">LISTEN FOR</span>{ESC(ask)}</p>
    <ul class="clips">{''.join(clip(c['name'].upper(), c['file'], c['note'], c['metrics'], c['seconds']) for c in sec['clips'])}</ul>
  </section>""")

groups: dict = {}
for c in section("macros")["clips"]:
    groups.setdefault(c["voice"], []).append(c)
macro_body = "".join(f"""
    <details><summary>{ESC(v)}</summary><ul class="clips">{''.join(clip(c['macro'], c['file'], c['note'], c['metrics'], c['seconds']) for c in cs)}</ul></details>""" for v, cs in groups.items())

gate_kit_rows = "".join(
    f"<tr><td>{ESC(k['name'])}</td><td>{k['bpm']:.0f}</td><td>{k['soft_vs_hard_min_db']}</td><td>{k['closest_takes_min_db']}</td>"
    f"<td>{k['closest_pads_db']} ({ESC(' / '.join(k['closest_pads']))})</td></tr>" for k in KITS)
between_rows = "".join(
    f"<tr><td>{ESC(p.replace('-', ' / ').upper())}</td><td>{v['mean_slot_db']}</td><td>{v['min_slot_db']} ({ESC(v['min_slot'])})</td>"
    f"<td>{len(v['identical_pads'])}</td></tr>" for p, v in B.items())

GATES = [
    ("Melodic, keygroup, chord tool", "na", "Not applicable. A drum engine; the voices are not tuned to notes."),
    ("Articulation (HOLD, STRUM)", "na", "Finite hits, no HOLD. Not a plucked string."),
    ("Percussion: roll or riser", "mid", "A roll (B01) and a riser (B02) are authored for each kit in Python, each hit a fresh strike. Not built in Kotlin, not heard."),
    ("Drums: several genre kits on the shelf", "mid", f"Three genre kits authored and measured in Python: no pad shared between any two, the same slot {min(v['min_slot_db'] for v in B.values())} dB apart at the closest. Not on the shelf: no Kotlin."),
    ("Velocity in rendering", "mid", f"Measured in Python on all 48 pads: soft against hard at least {C['soft_vs_hard_min_db']} dB at matched level."),
    ("Round robin in rendering", "mid", f"Measured in Python on all 48 pads: the closest two takes of any pad are {C['closest_takes_min_db']} dB apart."),
    ("Velocity and round robin through saving, playback, export", "open", "No Kotlin yet. The page's pad grid is a browser stand-in, not the app."),
    ("Phone and MPC", "open", "Nothing has been loaded on a device."),
]
gate_rows = "".join(f'<tr><td>{ESC(a)}</td><td><span class="pill {s}">{ {"na": "N/A", "mid": "MEASURED", "open": "OPEN"}[s] }</span></td><td>{ESC(b)}</td></tr>'
                    for a, s, b in GATES)

rip = C["ripple_db_by_growl"]
LEDGER = [
    ("Every render is deterministic and finite", "measured", f"{C['renders']} renders; deterministic {C['deterministic']}, finite {C['finite']}, DC under {C['dc_max']}"),
    ("The drum's note survives the rail", "measured", f"kick and tom tails within {C['root_cents_max']} cents of the TUNE root at every SAG and GROWL corner (FFT resolution is about that size)"),
    ("The rail cannot run away", "measured", f"largest |r| at the worst corner {C['rail_abs_max']} against a bound of {C['rail_bound']}; the rail filter is a stable low-pass and nothing feeds back into it"),
    ("SAG at 0 switches the rail off", "measured", f"GROWL then changes nothing, bit for bit: {C['sag_zero_ignores_growl']}"),
    ("GROWL puts the rail's ring into the sound", "measured", "ripple at GROWL 0, .35, .7, 1: " + "; ".join(f"{v.lower()} {' '.join(str(x) for x in xs)} dB" for v, xs in rip.items())),
    ("Every control moves every voice", "measured", f"weakest is {C['weakest_macro']} at {C['weakest_macro_db']} dB between 0 and 1"),
    ("Controls interact", "measured", "smallest change in one control's effect across the other, dB: " + ", ".join(f"{k.replace('x', ' x ')} {v}" for k, v in C["pair_interaction_min_db"].items()) + ". SAG x HEAT and ZAP x SAG are weak on at least one voice"),
    ("An unknown control or a non-finite value changes nothing", "measured", f"unknown {C['unknown_key_ignored']}, NaN control {C['nan_macro_falls_back']}, NaN velocity {C['nan_velocity_falls_back']}, velocity 0 silent {C['zero_velocity_silent']}"),
    ("A hit renders in tens of milliseconds", "measured", f"Python: mean {C['render_ms_mean']} ms, slowest {C['render_ms_max']} ms (the crash)"),
    ("Aliasing", "not measured", "voices use band-limited squares and saws at 4x and the clipper is band-limited before the rate drops, but no probe against a higher-rate reference has run"),
    ("A weak supply does this to a real drum machine", "analogy", "the rail is a parameter law, not a circuit model; no schematic was simulated"),
    ("The kits sound like their genres; the rail sounds good", "not heard", "your listen"),
]
ledger_rows = "".join(f"<tr><td>{ESC(a)}</td><td>{ESC(b)}</td><td>{ESC(c)}</td></tr>" for a, b, c in LEDGER)

grid_kits = [{"id": k["id"], "name": k["name"], "pads": [{"id": r["pad"].lower(), "label": r["label"], "sprite": web(r["sprite"]),
                                                          "slot": r["slot_frames"]} for r in k["pads"]]} for k in KITS]

page = f"""<meta charset="utf-8">
<title>SERVO Audition</title>
<meta name="claude:viewport-fit-sides" content="cover">
<style>{CSS}</style>

<div class="wrap">
  <header>
    <div class="mark"><i></i><span class="eyebrow">SNIPSNAP · SERVO · ROUND 0 · PYTHON PROTOTYPE · NOT HEARD</span></div>
    <h1>SERVO Audition</h1>
    <p class="lede">Machine drums for electro, ghetto tech and neurofunk, all running off one deliberately weak power supply.
    Every hit pulls the supply down a few milliseconds after it lands, and three things follow it: the pitch dives, the drum
    squares off against a lower ceiling, and its tone sweeps. Let the supply ring as it recovers and the tail growls.
    Ten voices, three sixteen-pad kits. Nothing is sampled, nothing here is Kotlin yet, and nobody has heard it.</p>
    <p class="gesture">The trigger lands clean → the rail sags: the drum dives and flat-tops → the rail rings back: the tail growls → recovered and silent before the next sixteenth.</p>
    <nav><a href="#play">Play</a><a href="#rail">The rail</a>{''.join(f'<a href="#kit-{k["id"]}">{ESC(k["name"].replace("SERVO ", "").title())}</a>' for k in KITS)}<a href="#voices">Voices</a><a href="#velocity">Velocity</a><a href="#takes">Takes</a><a href="#macros">Controls</a><a href="#gates">Gates</a><a href="#ledger">Ledger</a></nav>
  </header>

  <section class="card" id="play">
    <h2>Play the kits</h2>
    <p class="intro">Pick a kit, then tap a pad. The top third plays the hard layer, the middle the medium one, the bottom the soft one, and every tap deals
    the next of four takes, as the kit would on a pad. A03 chokes A04 and A07 chokes A08. Sound starts from your first tap.
    Pads played here each have their own rail; only the baked grooves below share one.</p>
    <div class="grid-box">
      <div class="tabs" id="tabs" role="group" aria-label="Kit"></div>
      <div class="grid" id="grid" aria-label="Sixteen pads, laid out as on an MPC with A01 at the bottom left"></div>
      <div class="readout"><span id="status">Tap any pad. Each pad loads on its first tap.</span><button class="load" id="loadall" type="button">LOAD THIS KIT</button></div>
    </div>
  </section>
{base_sections[0]}
{''.join(kit_cards)}
{''.join(base_sections[1:])}

  <section class="card" id="macros">
    <h2>Each control on each voice</h2>
    <p class="intro">Each file plays a control at 0, 0.5 and 1 with the others at their defaults. TUNE and DECAY are the drum. ZAP is how far and how long the pitch
    is thrown at the start. SAG is how weak the supply is. GROWL is how much it rings as it recovers. HEAT is how hard the drum is driven into the ceiling.</p>
    <p class="ask"><span class="tag">LISTEN FOR</span>Does each control do what its name says? Is anything useless below the halfway mark?</p>
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
    <p class="foot">Distances are the house measure (SCUFF, ETCH, CLUTTER): the RMS difference of level-matched third-octave spectrograms over the first 150 ms.
    Takes you heard as one strike on ETCH measured 0.5 to 0.8 dB.</p>
  </section>

  <section class="card" id="ledger">
    <h2>Measured, proposed, heard</h2>
    <div class="table-wrap"><table>
      <tr><th>Claim</th><th>Status</th><th>Evidence</th></tr>
      {ledger_rows}
    </table></div>
    <p class="foot">Python harness at commit {ESC(COMMIT)}, not Kotlin. Regenerate with
    <code>python3 prototype/servo/gen_audition.py OUT</code> then <code>python3 prototype/servo/build_page.py OUT</code>.
    Design notes: <code>docs/SERVO.md</code>.</p>
  </section>
</div>

<script>
const KITS = {json.dumps(grid_kits)};
const ZONES = {{ h: 1.0, m: 0.65, s: 0.35 }};
const ZONE_NAME = {{ h: "HARD", m: "MEDIUM", s: "SOFT" }};
const ZONE_SLOT = {{ s: 0, m: 4, h: 8 }};
const RATE = 44100;
const ORDER = ["a13","a14","a15","a16","a09","a10","a11","a12","a05","a06","a07","a08","a01","a02","a03","a04"];
const CHOKES = {{ a03: ["a04"], a07: ["a08"] }};
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
  try {{ buf = await load(p.sprite); }} catch (e) {{ cache.delete(p.sprite); status.textContent = `Could not load ${{p.id.toUpperCase()}}. Tap it again, or reload the page.`; return; }}
  for (const other of (CHOKES[p.id] || [])) stop(other);
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
  b.textContent = k.name.replace("SERVO ", "");
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

open(os.path.join(OUT, "index.html"), "w").write(page)
files = {}
for root, _, names in os.walk(OUT):
    for name in names:
        rel = os.path.relpath(os.path.join(root, name), OUT)
        if name.endswith(".mp3") or (name.endswith(".wav") and rel.startswith("pads")):
            files[rel] = rel
json.dump(files, open(os.path.join(OUT, "files.json"), "w"), indent=0)
print("wrote", os.path.join(OUT, "index.html"), "and", len(files), "listening files")
