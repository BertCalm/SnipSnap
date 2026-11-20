"""Build the DRAWL round-0 audition page from gen_audition.py's output.

    python3 prototype/drawl/build_page.py OUT [COMPARE_JSON] [AB_DIR]

AB_DIR (round 0b) holds before/ and after/ clips named <voice>-<default|hard|slide|strum>.wav and
before.json / after.json from character_probe.py; the page then opens with an A/B of the owner's notes.

COMPARE_JSON is compare_refs.py's output (each voice beside its reference loops' numbers).

Writes OUT/site/index.html and OUT/site/audio/*.mp3 (needs ffmpeg). Only DRAWL renders go on
the page; the owner's reference loops are commercial audio and stay out of it.
"""
from __future__ import annotations

import html
import json
import os
import subprocess
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import drawl_proto as dp  # noqa: E402
import measure  # noqa: E402

ABOUT = {
    "CONE": ("Resonator guitar", "Steel bar, metal fingerpicks, a spun aluminium cone. Nasal mids at 580, 820 and 1180 Hz, measured in all your resonator loops.", "dobro"),
    "BOTTLE": ("Electric slide", "Bottleneck on a fretted electric, bridge pickup, a small amp that grits up with BODY and with a hard pick.", "electric slide"),
    "LAP": ("Lap steel", "Bar across the strings, so a chord slides as one. Dark pickup, slow bar.", "lap steel"),
    "STEEL": ("Pedal steel", "Volume-pedal swell, then pedals raise the inner tones into the chord and stop dead. Gentle bar vibrato.", "pedal steel"),
    "FLATTOP": ("Country flat-top", "Steel-string acoustic, fretted. A slide steps over the frets a semitone at a time. Boomy body.", "acoustic"),
    "GUT": ("Western nylon", "Nylon strings, flesh fingers, finger vibrato. Dark and short, for the spaghetti-western end.", "nylon"),
}
QUESTIONS = {
    "CONE": "Does the cone honk read as a resonator, or as a guitar through an EQ? Is the bar landing too fast or too polite?",
    "BOTTLE": "Is the amp grit the right amount at the default? Does the vibrato sit like a slide player's?",
    "LAP": "Does the chord slide (STRUM) feel like one bar moving four strings?",
    "STEEL": "Is the pedal rise the cry you want, and is the swell long enough?",
    "FLATTOP": "Does the fret-stepped slide read as a country slide-in? Is the body boom too much?",
    "GUT": "Is it dark enough for the western end, or too dull to cut?",
}
CLIPS = [  # (label, report file key template)
    ("Default", "voices/{v}/mid"), ("Low", "voices/{v}/low"), ("High", "voices/{v}/high"),
    ("Soft", "velocity/{v}/soft"), ("Hard", "velocity/{v}/hard"),
    ("Take 2", "takes/{v}/1"), ("Take 3", "takes/{v}/2"), ("Take 4", "takes/{v}/3"),
    ("Still bar", "pairs/{v}/slide0-waver0"), ("Long slide + wide vibrato", "pairs/{v}/slide1-waver1"),
    ("Strum down", "strum/{v}/DOWN"), ("Strum up", "strum/{v}/UP"),
]


def mp3(src, dst):
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", src, "-ac", "1", "-b:a", "96k", dst], check=True)


def pitch_trace(path, target_hz, seconds=1.6, points=160):
    x, sr = measure.load(path)
    f0, dt = measure.pitch_track(x[: int(seconds * sr)], sr)
    c = 1200 * np.log2(f0 / target_hz)
    t = np.arange(len(c)) * dt
    grid = np.linspace(0, min(seconds, t[-1] if len(t) else seconds), points)
    out = []
    for g in grid:
        i = int(round(g / dt))
        v = c[i] if i < len(c) else np.nan
        out.append(None if not np.isfinite(v) or abs(v) > 1400 else round(float(v), 1))
    return out


def svg_trace(traces, lo=-1300, hi=300, w=480, h=96):
    """Cents against the note over the first 1.6 s, one polyline group per (class, trace);
    dashed line at the note, dotted an octave below."""
    def y(c):
        return round(h - 6 - (c - lo) / (hi - lo) * (h - 12), 1)
    lines = ""
    for cls, trace in traces:
        segs, cur = [], []
        for i, c in enumerate(trace):
            if c is None:
                if len(cur) > 1:
                    segs.append(cur)
                cur = []
            else:
                cur.append(f"{round(i / (len(trace) - 1) * w, 1)},{y(c)}")
        if len(cur) > 1:
            segs.append(cur)
        lines += f'<g class="{cls}">' + "".join(f'<polyline points="{" ".join(s)}"/>' for s in segs) + "</g>"
    return (f'<svg viewBox="0 0 {w} {h}" preserveAspectRatio="none" role="img" '
            f'aria-label="Pitch against the note over the first 1.6 seconds">'
            f'<line class="zero" x1="0" x2="{w}" y1="{y(0)}" y2="{y(0)}"/>'
            f'<line class="oct" x1="0" x2="{w}" y1="{y(-1200)}" y2="{y(-1200)}"/>'
            f'{lines}</svg>')


def main():
    out = sys.argv[1]
    rep = json.load(open(os.path.join(out, "report.json")))
    if len(sys.argv) > 2:
        rep["compare"] = json.load(open(sys.argv[2]))
    site = os.path.join(out, "site")
    os.makedirs(os.path.join(site, "audio"), exist_ok=True)
    cards = []
    for v in dp.VOICES:
        d = rep["voices"][v]
        name, blurb, ref_word = ABOUT[v]
        buttons = []
        for label, key in CLIPS:
            rel = rep["files"].get(key.format(v=v))
            if not rel:
                continue
            fn = os.path.basename(rel).replace(".wav", ".mp3")
            mp3(os.path.join(out, rel), os.path.join(site, "audio", fn))
            buttons.append(f'<button class="clip" type="button" data-src="audio/{fn}">'
                           f'<span class="tri" aria-hidden="true">▶</span>{html.escape(label)}</button>')
        midi = dp.midi_for(v, .5)
        trace = pitch_trace(os.path.join(out, rep["files"][f"voices/{v}/mid"]), dp.hz(midi))
        long_trace = pitch_trace(os.path.join(out, rep["files"][f"pairs/{v}/slide1-waver1"]), dp.hz(midi))
        comp = rep.get("compare", {}).get(v, {})
        ours, refs = comp.get("drawl", {}), comp.get("reference_range", {})
        def rr(k):
            r = refs.get(k)
            return "n/a" if not r else (f"{r[0]:g}" if r[0] == r[1] else f"{r[0]:g} to {r[1]:g}")
        vel = d["velocity"]
        m = d["macro_activity"]
        rows = [
            ("Root, 3 notes", f'{max(abs(x) for x in d["root_cents_house"].values()):.1f} cents off at worst', "house tolerance 5 to 50"),
            ("Brightness (centroid)", f'{ours.get("centroid_hz", d["centroid_hz"])} Hz', f'your {ref_word} loops: {rr("centroid_hz")} Hz'),
            ("Decay", f'{ours.get("decay_db_per_s_loudest_note")} dB/s', f'your loops: {rr("decay_db_per_s_loudest_note")} dB/s'),
            ("Touch: soft · med · hard onset", " · ".join(str(vel[k]["onset_centroid_hz_level_matched"]) for k in ("soft", "medium", "hard")) + " Hz", "brighter as harder: timbre, not only level"),
            ("WAVER 0 · .5 · 1", " · ".join(str(x) for x in m["WAVER_pitch_std_cents_at_0_.5_1"].values()) + " cents spread", "pitch wobble, measured on the pitch track"),
            ("Takes", f'4 distinct, pitch held to {max(abs(x) for x in d["takes"]["steady_pitch_cents"] if x is not None):.1f} c', "round robin never detunes"),
        ]
        table = "".join(f"<tr><th scope=\"row\">{html.escape(a)}</th><td>{html.escape(b)}</td><td>{html.escape(c)}</td></tr>" for a, b, c in rows)
        note = dp.hz(midi)
        cards.append(f"""
<section class="voice" id="{v.lower()}" aria-labelledby="h-{v.lower()}">
  <header class="vhead">
    <h2 id="h-{v.lower()}"><span class="id">{v}</span> <span class="vname">{html.escape(name)}</span></h2>
    <p class="blurb">{html.escape(blurb)}</p>
  </header>
  <div class="lcd">
    <div class="lcdlabel"><span>PITCH · {note:.0f} HZ NOTE</span><span>0 → 1.6 S</span></div>
    {svg_trace([("long", long_trace), ("trace", trace)])}
    <div class="lcdlegend"><span><span class="key amber">━</span> default <span class="key pale">━</span> long slide, wide vibrato</span><span class="dim">- - the note · ··· an octave below</span></div>
  </div>
  <div class="clips">{"".join(buttons)}</div>
  <p class="ask"><span class="eyebrow">LISTEN FOR</span> {html.escape(QUESTIONS[v])}</p>
  <div class="tablewrap"><table><thead><tr><th scope="col">Probe</th><th scope="col">DRAWL</th><th scope="col">Against</th></tr></thead><tbody>{table}</tbody></table></div>
</section>""")
    ab = ab_section(sys.argv[3], site) if len(sys.argv) > 3 else ""
    z = rep["zipper"]
    page = TEMPLATE.replace("{{AB}}", ab).replace("{{CARDS}}", "".join(cards)).replace(
        "{{ZIP}}", f'{z["house_allpass_retune"]["hf_share_glide_db"]} dB during a 12-semitone slide, '
                   f'{z["house_allpass_retune"]["hf_share_vibrato_db"]} dB under full vibrato')
    open(os.path.join(site, "index.html"), "w").write(page)
    print(os.path.join(site, "index.html"))


AB_NOTES = {
    "CONE": ("CONE · more twang", "Pick nearer the bridge and harder; a bigger pitch drop after the attack; the cone's 2.3 kHz nasal band raised; the string keeps its top instead of losing it first."),
    "BOTTLE": ("BOTTLE · more whine", "The string keeps its top; a sharp pickup peak at 2.6 kHz (the knobs full up); more amp sustain; the bar moves a little slower so the glide is heard."),
}
AB_ROWS = [("Default", "default"), ("Hard pick", "hard"), ("Long slide, wide vibrato", "slide"), ("Strum", "strum")]
AB_PROBES = [("Boing, hard pick", "hard", "boing_cents", "cents"),
             ("Sustain 1 to 4 kHz", "medium", "sustain_band_db", "dB of total"),
             ("Highs vs root after 1 s", "medium", "highs_hold_db", "dB")]


def ab_section(ab_dir, site):
    before = json.load(open(os.path.join(ab_dir, "before.json")))
    after = json.load(open(os.path.join(ab_dir, "after.json")))
    blocks = []
    for v, (title, what) in AB_NOTES.items():
        rows = []
        for label, tag in AB_ROWS:
            btn = []
            for side in ("before", "after"):
                fn = f"ab-{side}-{v.lower()}-{tag}.mp3"
                mp3(os.path.join(ab_dir, side, f"{v.lower()}-{tag}.wav"), os.path.join(site, "audio", fn))
                btn.append(f'<button class="clip" type="button" data-src="audio/{fn}"><span class="tri" aria-hidden="true">▶</span>{"Round 0" if side == "before" else "Now"}</button>')
            rows.append(f'<div class="abrow"><span class="ablabel">{html.escape(label)}</span><div class="clips">{"".join(btn)}</div></div>')
        nums = "".join(
            f"<tr><th scope=\"row\">{html.escape(n)}</th><td>{before[v][t][k]}</td><td>{after[v][t][k]}</td><td>{u}</td></tr>"
            for n, t, k, u in AB_PROBES)
        blocks.append(f"""<div class="abvoice"><h3>{html.escape(title)}</h3><p class="blurb">{html.escape(what)}</p>{"".join(rows)}
<div class="tablewrap"><table><thead><tr><th scope="col">Probe, still bar</th><th scope="col">Round 0</th><th scope="col">Now</th><th scope="col">Unit</th></tr></thead><tbody>{nums}</tbody></table></div></div>""")
    return f"""<section class="voice ab" aria-labelledby="h-ab">
  <header class="vhead"><h2 id="h-ab"><span class="id">0B</span> <span class="vname">Your notes, before and after</span></h2>
  <p class="blurb">"Resonator could use more twang, slide a bit more whine." Each pair plays round 0 and the change. LAP, the other slide, is unchanged so far. Watch for harshness in the whine: about half of BOTTLE's attack now sits between 1.5 and 4 kHz.</p></header>
  {"".join(blocks)}
</section>"""


TEMPLATE = """<title>DRAWL Round 0</title>
<meta name="claude:viewport-fit-sides" content="cover">
<style>
/* Layout: one column of voice sheets on the window chrome; sound lives on the dark LCD.
   Tokens: SnipSnap design system, VENT (dark) and OPENSKY (paper). */
:root {
  --gray: #f8f3e9; --raised: #fffaf1; --field: #fffaf1; --edge: #dbd5c7; --frame: #b4ad9e;
  --desk: #efe8da; --ink: #292820; --ink2: #69675a; --ink3: #817a6b; --accent: #a74c00;
  --lcd: #22211a; --lcdInk: #f8f3e9; --lcdInk2: rgba(248, 243, 233, .75); --amber: #ff8c00;
  --mono: "Droid Sans Mono", "Roboto Mono", ui-monospace, Menlo, Consolas, monospace;
  --sans: Roboto, system-ui, -apple-system, "Segoe UI", sans-serif;
  --serif: "Noto Serif", Georgia, "Times New Roman", serif;
}
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {
  --gray: #1d1e1b; --raised: #232420; --field: #232420; --edge: #2d2f28; --frame: #55564b;
  --desk: #151614; --ink: #eeeadd; --ink2: #b3b4a6; --ink3: #929587; --accent: #e08a68;
  --lcd: #0f100e; --lcdInk: #eeeadd; --lcdInk2: rgba(238, 234, 221, .75); --amber: #e08a68; color-scheme: dark } }
:root[data-theme="dark"] {
  --gray: #1d1e1b; --raised: #232420; --field: #232420; --edge: #2d2f28; --frame: #55564b;
  --desk: #151614; --ink: #eeeadd; --ink2: #b3b4a6; --ink3: #929587; --accent: #e08a68;
  --lcd: #0f100e; --lcdInk: #eeeadd; --lcdInk2: rgba(238, 234, 221, .75); --amber: #e08a68; color-scheme: dark }
body { background: var(--desk); color: var(--ink); font: 15px/1.5 var(--sans); }
.window { max-width: 860px; margin: 12px auto; background: var(--gray); border: 1px solid var(--frame);
  border-radius: 2px; padding-block: 12px 24px;
  padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); }
.titlebar { display: flex; align-items: center; gap: 8px; border-bottom: 1px solid var(--edge); padding-bottom: 10px; }
.mark { width: 9px; height: 9px; background: var(--accent); transform: rotate(45deg); flex: none; }
.titlebar h1 { margin: 0; font: 800 18px/1.2 var(--sans); letter-spacing: 1px; }
.status { margin-left: auto; font: 11px var(--mono); letter-spacing: 1px; color: var(--ink2); text-align: right; }
.eyebrow { font: 11px var(--mono); letter-spacing: 1px; color: var(--ink2); text-transform: uppercase; }
.intro { display: grid; gap: 8px; padding: 14px 0; max-width: 66ch; }
.intro p { margin: 0; }
.gesture { font: 13px/1.5 var(--mono); letter-spacing: .5px; color: var(--ink); background: var(--field);
  border: 1px solid var(--edge); border-radius: 2px; padding: 10px; }
.voice { border: 1px solid var(--edge); border-radius: 2px; background: var(--gray); padding: 10px; display: grid; gap: 10px; margin-top: 12px; }
.vhead h2 { margin: 0; font: 600 17px/1.3 var(--sans); letter-spacing: .5px; text-wrap: balance; }
.vhead .id { font: 13px var(--mono); letter-spacing: 1px; color: var(--accent); margin-right: 4px; }
.blurb { margin: 2px 0 0; color: var(--ink2); max-width: 66ch; font-size: 14px; }
.lcd { background: var(--lcd); border-radius: 2px; padding: 8px 10px; color: var(--lcdInk);
  background-image: repeating-linear-gradient(0deg, rgba(0,0,0,.18) 0 1px, transparent 1px 3px); }
.lcdlabel, .lcdlegend { display: flex; justify-content: space-between; gap: 8px; flex-wrap: wrap;
  font: 11px var(--mono); letter-spacing: 1px; color: var(--lcdInk2); }
.lcd svg { display: block; width: 100%; height: 96px; margin: 4px 0; }
.lcd .zero { stroke: var(--lcdInk2); stroke-width: 1; stroke-dasharray: 4 4; vector-effect: non-scaling-stroke; }
.lcd .oct { stroke: var(--lcdInk2); stroke-opacity: .35; stroke-width: 1; stroke-dasharray: 2 6; vector-effect: non-scaling-stroke; }
.lcd .trace polyline { fill: none; stroke: var(--amber); stroke-width: 2; vector-effect: non-scaling-stroke; stroke-linejoin: round; }
.lcd .long polyline { fill: none; stroke: var(--lcdInk2); stroke-width: 1.5; vector-effect: non-scaling-stroke; stroke-linejoin: round; }
.lcdlegend .dim { opacity: .75; }
.lcdlegend .key { font-weight: 700; }
.lcdlegend .amber { color: var(--amber); }
.lcdlegend .pale { color: var(--lcdInk2); margin-left: 6px; }
.clips { display: flex; flex-wrap: wrap; gap: 4px; }
.clip { min-height: 48px; padding: 0 12px; display: inline-flex; align-items: center; gap: 8px;
  background: var(--raised); color: var(--ink); border: 1px solid var(--edge); border-radius: 2px;
  font: 600 13px var(--sans); letter-spacing: .3px; cursor: pointer; }
.clip .tri { font-size: 10px; color: var(--ink2); }
.clip:hover { border-color: var(--frame); }
.clip:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.clip[aria-pressed="true"] { background: var(--field); border: 2px solid var(--accent); padding: 0 11px; }
.clip[aria-pressed="true"] .tri { color: var(--accent); }
.ask { margin: 0; font-size: 14px; max-width: 66ch; }
.ask .eyebrow { margin-right: 6px; }
.tablewrap { overflow-x: auto; }
table { border-collapse: collapse; width: 100%; font-size: 13px; font-variant-numeric: tabular-nums; }
th, td { text-align: left; padding: 6px 8px 6px 0; border-top: 1px solid var(--edge); vertical-align: top; }
thead th { font: 11px var(--mono); letter-spacing: 1px; color: var(--ink2); text-transform: uppercase; border-top: 0; }
tbody th { font-weight: 500; color: var(--ink2); }
.abvoice { display: grid; gap: 8px; border-top: 1px solid var(--edge); padding-top: 10px; }
.abvoice h3 { margin: 0; font: 600 15px/1.3 var(--sans); }
.abrow { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.ablabel { font: 11px var(--mono); letter-spacing: 1px; color: var(--ink2); text-transform: uppercase; min-width: 13ch; }
.foot { margin-top: 16px; display: grid; gap: 8px; max-width: 66ch; color: var(--ink2); font-size: 14px; }
.foot p { margin: 0; }
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
<div class="window">
  <div class="titlebar"><span class="mark" aria-hidden="true"></span><h1>DRAWL</h1>
    <span class="status">ROUND 0B · PYTHON HARNESS · TWO NOTES HEARD</span></div>
  <div class="intro">
    <p class="eyebrow">Proposed engine · western and cosmic country strings</p>
    <p class="gesture">PICK → THE BAR SLIDES THE NOTE IN → THE BAR SHIMMERS → THE STRING RINGS OUT THROUGH ITS BODY</p>
    <p>Six voices on one mechanism: a string whose pitch path is the performance. These are Python prototype renders, measured against your fifteen reference loops. You have heard round 0 and asked for two changes, which open the page. Your loops are not on this page; only their numbers are used.</p>
    <p>Each sheet plays the default note, its low and high notes, soft and hard touch, three more round-robin takes, the bar held still, a long slide with wide vibrato, and a strum each way. On each dark panel the amber line is the default note's measured pitch and the pale line the long slide's: watch the bar slide up to the dashed note, land, and start to shimmer. A line stops where the note has rung too quiet to track.</p>
  </div>
  {{AB}}
  {{CARDS}}
  <div class="foot">
    <p><span class="eyebrow">Under the hood</span> The slide reuses SnipSnap's own string loop. Its retune artifacts measured {{ZIP}}, under the note: not audible, so no new string code is needed.</p>
    <p>Every render was byte-identical on a second run, finite, DC-free, and ignored unknown or broken knob values. Everything over 1.5 s files as LOOP, as the classifier does today.</p>
  </div>
</div>
<script>
(() => {
  const audio = new Audio();
  let current = null;
  const reset = () => { if (current) current.setAttribute("aria-pressed", "false"); current = null; };
  audio.addEventListener("ended", reset);
  document.querySelectorAll(".clip").forEach((b) => {
    b.setAttribute("aria-pressed", "false");
    b.addEventListener("click", () => {
      if (current === b && !audio.paused) { audio.pause(); reset(); return; }
      reset();
      audio.src = b.dataset.src;
      audio.currentTime = 0;
      audio.play().then(() => { current = b; b.setAttribute("aria-pressed", "true"); }).catch(reset);
    });
  });
})();
</script>
"""

if __name__ == "__main__":
    main()
