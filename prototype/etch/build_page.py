"""Build the ETCH round-1 audition page (index.html) from manifest.json.

Every number on the page is read from the manifest the generator wrote; nothing is typed
in by hand. Run after gen_audition.py:

    python3 build_page.py OUT_DIR
"""
from __future__ import annotations

import html
import json
import os
import subprocess
import sys

OUT = sys.argv[1] if len(sys.argv) > 1 else "etch-audition"
M = json.load(open(os.path.join(OUT, "manifest.json")))
try:
    COMMIT = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], text=True).strip()
except Exception:  # noqa: BLE001
    COMMIT = "unknown"

ESC = html.escape

LABELS = {
    "band_centroid_hz": ("CENTROID", "{:.0f} Hz"),
    "upper_db": ("ABOVE 2 kHz", "{:.1f} dB"),
    "crest_db": ("CREST", "{:.1f} dB"),
    "note_peak_spread_db": ("STEP SPREAD", "{:.2f} dB"),
    "accent_lift_db": ("ACCENT LIFT", "{:.1f} dB"),
    "seam_error": ("SEAM", "{:.0e}"),
    "attack_band_centroid_hz": ("ATTACK", "{:.0f} Hz"),
    "late_band_centroid_hz": ("AT 0.2 s", "{:.0f} Hz"),
    "raw_peak_db": ("UNMATCHED PEAK", "{:.1f} dB"),
    "second_harmonic_db": ("2ND HARMONIC", "{:.1f} dB"),
}


def chips(metrics: dict) -> str:
    out = []
    for k, v in metrics.items():
        if k in ("peak", "dc"):
            continue
        if isinstance(v, list):
            label = {"band_centroid_by_bar_hz": "CENTROID BY BAR",
                     "upper_db_by_bar": "ABOVE 2 kHz BY BAR",
                     "accent_step_centroid_hz": "EACH ACCENT"}.get(k, k.upper())
            unit = " Hz" if k.endswith("hz") else " dB"
            val = " → ".join(f"{x:.0f}" if unit == " Hz" else f"{x:.1f}" for x in v) + unit
        elif k in LABELS:
            label, fmt = LABELS[k]
            val = "0" if (k == "seam_error" and v == 0) else fmt.format(v)
        else:
            continue
        out.append(f'<span class="chip"><b>{ESC(label)}</b> {ESC(val)}</span>')
    return "".join(out)


def clip_html(c: dict) -> str:
    return f"""
      <li class="clip">
        <div class="clip-head">
          <span class="clip-id">{ESC(c['name'])}</span>
          <span class="clip-len">{c['seconds']:.1f} s</span>
        </div>
        <p class="clip-note">{ESC(c['note'])}</p>
        <audio controls preload="none" src="{ESC(c['file'])}"></audio>
        <div class="chips">{chips(c['metrics'])}</div>
      </li>"""


LISTEN = {
    "chain-a": "Does the bare voice already sound like the darker, sparse groove of reference A? "
               "Does each stage add something you would keep?",
    "chain-b": "Is BITE at this level the gentle glue you asked for, or too much grit? "
               "Do the dotted-eighth repeats sit above the bass or muddy it?",
    "ride": "Does the climb feel like the hand on the cutoff in reference B? "
            "Should a ride like this be baked into the loop pads?",
    "bite": "Where does glue turn into grit for you: 0.25, 0.5 or 0.8?",
    "space": "Which SPACE amount and which delay division suit an MPC loop pad?",
    "filter": "Is every knob useful across its whole travel?",
    "accent": "Can you hear the run of accents opening further each time?",
    "slide": "Is the glide speed right?",
    "waves": "Do the two waves now sound clearly different? The square should be hollower.",
    "notes": "Are these usable as keygroup notes as they are?",
    "velocity": "Does the hard hit sound like an accent, not just louder?",
    "round-robin": "Are the takes different enough for a repeated note? "
                   "The accented takes vary less than the plain ones.",
}

sections = []
for s in M["sections"]:
    clips = "".join(clip_html(c) for c in s["clips"])
    ask = LISTEN.get(s["id"], "")
    extra = ""
    if s["id"] == "round-robin":
        rr = M["checks"]["round_robin"]
        rows = "".join(
            f"<tr><td>{v}</td><td>{' · '.join(str(x) for x in d['attack_band_centroid_hz'])}</td>"
            f"<td>{d['attack_spread_pct']}%</td><td>{d['late_spread_pct']}%</td></tr>"
            for v, d in rr.items())
        extra = f"""
        <div class="table-wrap"><table>
          <thead><tr><th>Velocity</th><th>Attack centroid per take (Hz)</th><th>Attack spread</th><th>Spread at 0.2 s</th></tr></thead>
          <tbody>{rows}</tbody></table></div>"""
    sections.append(f"""
    <section class="card" id="{ESC(s['id'])}">
      <h2>{ESC(s['title'])}</h2>
      <p class="intro">{ESC(s['intro'])}</p>
      {f'<p class="ask"><span class="tag">LISTEN FOR</span> {ESC(ask)}</p>' if ask else ''}
      {extra}
      <ol class="clips">{clips}</ol>
    </section>""")

nav = "".join(f'<a href="#{ESC(s["id"])}">{ESC(s["title"].split(":")[0].split(" (")[0])}</a>' for s in M["sections"])
checks = M["checks"]

GATES = [
    ("Melodic: six distinct voices with presets", "open",
     "Two waves built (SAW, SQUARE) on one voice model. Six voices are proposed below for you to choose; none has presets yet."),
    ("Melodic: playable keygroups", "not yet verified",
     "Single notes render at C1, C2 and C3. No keygroup has been built or played."),
    ("Melodic: premade kit", "open", "No kit yet."),
    ("Melodic: chord-tool compatibility", "your call",
     "The line voice is monophonic on purpose: slide and accent carry from one note to the next. "
     "Single notes could be rendered as chords for keygroups. Decide whether the gate applies."),
    ("Articulation: appropriate HOLD", "open",
     "Proposed: HOLD lengthens the held note up to a seamless sustain loop, and its top step plays the line as a LOOP. Not built."),
    ("Articulation: STRUM", "not applicable", "Not a picked or thumbed string."),
    ("Percussion: roll or riser", "not applicable", "A bass engine, not percussion."),
    ("Drums: genre kits", "not applicable", "Not a drum engine."),
    ("Velocity: rendering", "pass (Python only)",
     "Velocity sets the accent, which changes the filter, not only the level: level-matched attack centroid "
     + " → ".join(str(c["metrics"]["attack_band_centroid_hz"]) for c in
                  next(s for s in M["sections"] if s["id"] == "velocity")["clips"][:4])
     + " Hz from velocity 0.35 to 1.0. Kotlin not built."),
    ("Round robin: rendering", "partial (Python only)",
     f"Takes differ (attack spread {checks['round_robin']['0.6']['attack_spread_pct']}% at velocity 0.6), "
     f"but accented takes differ little ({checks['round_robin']['1.0']['attack_spread_pct']}% at attack, "
     f"{checks['round_robin']['1.0']['late_spread_pct']}% at 0.2 s). Your ears decide whether that is enough."),
    ("Velocity and round robin: saving", "not yet verified", "No Kotlin patch exists to save."),
    ("Velocity and round robin: playback", "not yet verified", "No phone playback."),
    ("Velocity and round robin: export", "not yet verified", "No MPC export."),
    ("Sound character", "not heard", "Waiting for your listen."),
]


def gate_class(st: str) -> str:
    if st.startswith("pass"):
        return "ok"
    if st.startswith("not applicable"):
        return "na"
    if st.startswith("partial") or st.startswith("your call"):
        return "mid"
    return "open"


gate_rows = "".join(
    f'<tr><td>{ESC(g)}</td><td><span class="pill {gate_class(st)}">{ESC(st.upper())}</span></td><td>{ESC(why)}</td></tr>'
    for g, st, why in GATES)

page = f"""<title>ETCH Audition</title>
<meta name="claude:viewport-fit-sides" content="cover">
<style>
/* Layout: one column of TapeOS cards on the gray ground; sound lives in dark LCD panels. */
:root {{
  --gray: #f8f3e9; --raised: #fffaf1; --win: #f8f3e9; --edge: #dbd5c7; --frame: #b4ad9e;
  --ink: #292820; --ink2: #69675a; --ink3: #817a6b; --accent: #a74c00; --bin: #813600;
  --lcd: #22211a; --lcd-ink: #f8f3e9; --lcd-ink2: rgba(248, 243, 233, 0.75); --amber: #ff8c00;
  --ok: #2f6b2a; --mid: #8a5a00; --na: #69675a;
  --sans: Roboto, system-ui, -apple-system, "Segoe UI", sans-serif;
  --mono: "Droid Sans Mono", "Roboto Mono", ui-monospace, Menlo, Consolas, monospace;
  --serif: "Noto Serif", Georgia, "Times New Roman", serif;
}}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{
  --gray: #1d1e1b; --raised: #232420; --win: #1d1e1b; --edge: #2d2f28; --frame: #55564b;
  --ink: #eeeadd; --ink2: #b3b4a6; --ink3: #929587; --accent: #e08a68; --bin: #d6877b;
  --lcd: #0f100e; --lcd-ink: #eeeadd; --lcd-ink2: rgba(238, 234, 221, 0.75); --amber: #e08a68;
  --ok: #8fc98a; --mid: #e0b45c; --na: #b3b4a6; color-scheme: dark; }} }}
:root[data-theme="dark"] {{
  --gray: #1d1e1b; --raised: #232420; --win: #1d1e1b; --edge: #2d2f28; --frame: #55564b;
  --ink: #eeeadd; --ink2: #b3b4a6; --ink3: #929587; --accent: #e08a68; --bin: #d6877b;
  --lcd: #0f100e; --lcd-ink: #eeeadd; --lcd-ink2: rgba(238, 234, 221, 0.75); --amber: #e08a68;
  --ok: #8fc98a; --mid: #e0b45c; --na: #b3b4a6; color-scheme: dark; }}
body {{ background: var(--gray); color: var(--ink); font: 15px/1.55 var(--sans); }}
.wrap {{ max-width: 860px; margin: 0 auto; padding-block: 20px 48px;
  padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px));
  display: grid; gap: 12px; }}
.eyebrow, .tag, .clip-id, .chip b, nav a, th, .pill, .lcd {{ font-family: var(--mono); }}
.eyebrow {{ font-size: 11px; letter-spacing: 1px; color: var(--ink2); text-transform: uppercase; }}
header {{ display: grid; gap: 8px; padding-block: 8px; }}
.mark {{ display: flex; align-items: center; gap: 8px; }}
.mark i {{ width: 9px; height: 9px; background: var(--accent); transform: rotate(45deg); display: inline-block; }}
h1 {{ font: 800 30px/1.15 var(--sans); letter-spacing: 1px; margin: 0; text-wrap: balance; }}
h2 {{ font: 600 18px/1.3 var(--sans); letter-spacing: .5px; margin: 0; text-wrap: balance; }}
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
.table-wrap {{ overflow-x: auto; }}
table {{ border-collapse: collapse; width: 100%; font-size: 13px; }}
th {{ font-size: 11px; letter-spacing: 1px; text-transform: uppercase; color: var(--ink2); font-weight: 400; text-align: left; }}
th, td {{ border-bottom: 1px solid var(--edge); padding: 8px 6px; vertical-align: top; font-variant-numeric: tabular-nums; }}
.pill {{ font-size: 11px; letter-spacing: .5px; white-space: nowrap; padding: 2px 6px; border: 1px solid currentColor; border-radius: 2px; }}
.pill.ok {{ color: var(--ok); border-width: 2px; }} .pill.mid {{ color: var(--mid); }} .pill.na {{ color: var(--na); }} .pill.open {{ color: var(--bin); }}
ul.plain {{ margin: 0; padding-left: 18px; display: grid; gap: 4px; max-width: 70ch; }}
a {{ color: var(--accent); }}
.foot {{ font-size: 13px; color: var(--ink2); }}
@media (prefers-reduced-motion: reduce) {{ * {{ scroll-behavior: auto; }} }}
</style>

<div class="wrap">
  <header>
    <div class="mark"><i></i><span class="eyebrow">SNIPSNAP · ETCH · ROUND 1 · PYTHON HARNESS · NOT HEARD</span></div>
    <h1>ETCH Audition</h1>
    <p class="lede">A squelching step-line bass, then a drive that glues the steps, then a tempo delay into a reverb.
    Built from your two reference clips. These are prototype renders from a Python model, not the app.
    Every clip is level-matched, so a later stage never wins by being louder.</p>
    <p class="gesture">The gesture: a sixteen-step line, accents bite and build, slides carry the pitch and keep the filter open, the drive evens out the peaks, and dotted-eighth repeats fall into a room behind it.</p>
  </header>

  <section class="card">
    <h2>The chain</h2>
    <div class="lcd">16-step line ─► <em>VOICE</em> ─────────► <em>BITE</em> ─────────► <em>SPACE</em> ──────────────► pad
               saw or square     clean / drive     delay ─► reverb
               4-pole filter     blend, 4x         dotted 1/8, then
               accent + slide    oversampled       the room on all of it

MACROS  TUNE · CUTOFF · PEAK · SWEEP · BITE · SPACE      VELOCITY = ACCENT</div>
    <ul class="plain">
      <li><b>CUTOFF</b> where the filter sits. <b>PEAK</b> resonance. <b>SWEEP</b> how deep and how long the filter envelope opens (one knob, because the hand rides both).</li>
      <li><b>BITE</b> blends a pre-emphasised soft clip with the clean signal, like a bass overdrive's dry/drive balance. Accents and resonant peaks hit it hardest, so it compresses them.</li>
      <li><b>SPACE</b> raises delay level and repeats together and grows the room. Repeats lose lows and highs each pass, so they sit above the bass. Reverb follows the delay, the order the reference pedal's manual gives.</li>
      <li><b>Velocity</b> is the accent: a harder hit opens the filter further, decays faster and charges the accent capacitor, so a run of accents climbs.</li>
    </ul>
  </section>

  <nav aria-label="Sections">{nav}<a href="#gates">Gates</a><a href="#next">Your answers</a></nav>

  {''.join(sections)}

  <section class="card" id="gates">
    <h2>Acceptance gates</h2>
    <p class="intro">From docs/ENGINE_ACCEPTANCE_GATES.md and docs/INSTRUMENT_ACCEPTANCE_GATES.md. Round 1 is a prototype; the engine stays in progress until every applicable row has evidence.</p>
    <div class="table-wrap"><table>
      <thead><tr><th>Gate</th><th>Status</th><th>Evidence</th></tr></thead>
      <tbody>{gate_rows}</tbody>
    </table></div>
  </section>

  <section class="card" id="next">
    <h2>What I need from you</h2>
    <ul class="plain">
      <li><b>Chain:</b> keep, change or drop each stage (the four clips in each chain section).</li>
      <li><b>BITE and SPACE defaults:</b> which clip number in each sweep is the default.</li>
      <li><b>Ride:</b> should loop pads carry a baked cutoff ride, or stay static?</li>
      <li><b>Round robin:</b> is the analog wander enough, or should takes differ more?</li>
      <li><b>Six voices</b> (proposed, pick or replace): SAW LINE and SQUARE LINE (built), RUBBER (square plus sub, low peak), WHISTLE (peak near self-oscillation), GRIT (BITE first, hard), DUB (dark, long SPACE, repeats re-filtered).</li>
      <li><b>Chord tools:</b> does the chord gate apply to a mono line voice?</li>
    </ul>
  </section>

  <section class="card foot">
    <h2>Measured, not heard</h2>
    <p>{checks['clips']} clips, all finite: {'yes' if checks['all_finite'] else 'NO'}. Loop renders byte-identical across runs: {'yes' if checks['determinism_line_a'] else 'NO'}.
    Seam error is measured from a continuous render (two warm passes, then the loop and its continuation) against the house limit of 0.001.
    Centroid is magnitude-weighted over 150 Hz to 5 kHz, the band the references were measured in. "Above 2 kHz" is energy above 2 kHz as dB of 150 Hz to 10 kHz, which rises with CUTOFF where the centroid does not.
    Reference measurements: clip A about 112 BPM, low notes F1 and C2; clip B about 130 BPM, C2 with C3 and C#3, centroid rising from about 1.0 to 2.6 kHz during the ride. The reference clips themselves are not published here.</p>
    <p>Sources: <a href="https://manuals.plus/earthquake/avalanche-run-r2-manual.pdf" target="_blank" rel="noopener">delay and reverb pedal manual</a> (reverb follows delay; tone in the delay line only; tap divisions);
    <a href="https://www.adorama.com/col/productManuals/BSODB3.pdf" target="_blank" rel="noopener">bass overdrive manual</a> (BALANCE sets dry against overdrive);
    <a href="https://synth-diy.org/pipermail/synth-diy/2012-May/140040.html" target="_blank" rel="noopener">slide circuit thread</a> (100 k and 220 n RC portamento);
    <a href="https://ladyada.net/wiki/x0x/envelopemods" target="_blank" rel="noopener">accent sweep notes</a> (the capacitor charges across accents and grows with resonance).
    Source: SnipSnap prototype/etch at {ESC(COMMIT)}.</p>
  </section>
</div>
"""
with open(os.path.join(OUT, "index.html"), "w") as f:
    f.write(page)
print("wrote", os.path.join(OUT, "index.html"), len(page), "bytes")
