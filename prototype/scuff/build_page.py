"""Build the SCUFF round-0b audition page (index.html) from manifest.json.

Every number on the page is read from the manifest the generator wrote; nothing is typed in
by hand. Run after gen_audition.py:   python3 build_page.py OUT_DIR
"""
from __future__ import annotations

import html
import json
import os
import subprocess
import sys

OUT = sys.argv[1] if len(sys.argv) > 1 else "scuff-audition"
M = json.load(open(os.path.join(OUT, "manifest.json")))
try:
    COMMIT = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], text=True).strip()
except Exception:  # noqa: BLE001
    COMMIT = "unknown"
ESC = html.escape

LABELS = {
    "centroid_hz": ("CENTROID", "{:.0f} Hz"), "attack_hz": ("ATTACK", "{:.0f} Hz"),
    "decay_ms": ("TO -60 dB", "{:.0f} ms"), "crest_db": ("CREST", "{:.1f} dB"),
    "plateau_pct": ("AT THE CEILING", "{:.0f}%"), "hz_at_5ms": ("PITCH AT 5 ms", "{:.0f} Hz"),
    "hz_at_50ms": ("AT 50 ms", "{:.0f} Hz"), "hz_at_100ms": ("AT 100 ms", "{:.0f} Hz"),
    "hz_at_90ms": ("AT 90 ms", "{:.0f} Hz"), "hz_at_2ms": ("AT 2 ms", "{:.0f} Hz"),
    "hz_at_8ms": ("AT 8 ms", "{:.0f} Hz"), "hz_at_80ms": ("AT 80 ms", "{:.0f} Hz"),
    "hz_at_end": ("AT THE END", "{:.0f} Hz"), "hz_at_start": ("START", "{:.0f} Hz"),
    "drift_cents": ("DRIFT", "{:.0f} cents"), "body_80_160_db": ("80 TO 160 Hz", "{:.1f} dB"),
    "sub_below_45_db": ("BELOW 45 Hz", "{:.1f} dB"), "below_250_db": ("BELOW 250 Hz", "{:.1f} dB"),
    "below_4k_db": ("BELOW 4 kHz", "{:.1f} dB"), "below_7k_db": ("BELOW 7 kHz", "{:.1f} dB"),
    "peak_band_hz": ("PEAK BAND", "{:.0f} Hz"), "harmonics_100_300_db": ("100 TO 300 Hz", "{:.1f} dB"),
    "sub_45_80_db": ("45 TO 80 Hz", "{:.1f} dB"), "band_700_1800_db": ("700 Hz TO 1.8 kHz", "{:.1f} dB"),
    "band_900_1400_db": ("900 Hz TO 1.4 kHz", "{:.1f} dB"), "above_8k_db": ("ABOVE 8 kHz", "{:.1f} dB"),
    "ring_hz": ("RING", "{:.0f} Hz"), "rise_ms": ("RISE FROM -20 dB", "{:.0f} ms"),
    "last_sample": ("LAST SAMPLE", "{:.4f}"), "closest_takes_db": ("CLOSEST TAKES", "{:.1f} dB"),
    "widest_takes_db": ("WIDEST TAKES", "{:.1f} dB"),
    "round0_to_0b_db": ("FROM ROUND 0", "{:.1f} dB"),
    "python_to_kotlin_db": ("FROM PYTHON", "{:.1f} dB"),
}


def chips(metrics: dict) -> str:
    out = []
    for k, v in metrics.items():
        if k in ("peak", "dc"):
            continue
        if isinstance(v, list):
            label = {"onsets_ms": "TAPS AT", "peaks_hz": "PEAKS"}.get(k, k.upper())
            unit = " ms" if k.endswith("ms") else " Hz"
            val = " · ".join(f"{x:.0f}" if unit == " Hz" else f"{x:.1f}" for x in v) + unit
        elif k in LABELS:
            label, fmt = LABELS[k]
            val = fmt.format(v)
        else:
            continue
        out.append(f'<span class="chip"><b>{ESC(label)}</b> {ESC(val)}</span>')
    return "".join(out)


def clip_html(c: dict) -> str:
    return f"""
      <li class="clip">
        <div class="clip-head"><span class="clip-id">{ESC(c['name'])}</span><span class="clip-len">{(f"{c['seconds']:.1f} s" if c['seconds'] else "")}</span></div>
        <p class="clip-note">{ESC(c['note'])}</p>
        <audio controls preload="none" src="{ESC(c['file'])}"></audio>
        <div class="chips">{chips(c['metrics'])}</div>
      </li>"""


LISTEN = {
    "before-after": "Play each pair. Is round 0b more the sound, or just more? Name any pad where round 0 was closer.",
    "kit": "Is this the kit? Dry, mid-forward, in your face. Which pads are already right, and which are not the sound?",
    "chain-rim": "Does each stage add something you would keep? Where does the rim become the stank rim: GRIT, LOCKER or BARK?",
    "chain-clap": "Is the clap thin and papery enough, or too harsh once GRIT is in?",
    "fuse": "Does the kick and bass pair fuse into one growl at 0.55? Is 0.9 the trunk, or too much?",
    "grit": "Is the crush metallic in the right way, or does it just sound like a bad file?",
    "locker": "Does the comb put the rim in a metal box without adding a tail you would call reverb?",
    "bark": "Does BARK give the nasal mid that sits beside a voice, and is 0.8 ever useful?",
    "slam": "On a single pad, is SLAM glue or mush? Compare the hat: should the hats go through the bus at all?",
    "shape": "Is every knob useful across its whole travel, and does each voice stay itself at the ends?",
    "pattern": "Does it sit in the pocket at this tempo with zero swing? Compare full against no-slam: is the bus clipper the sound?",
    "rare": "Which of the five would you reach for? Which would you drop?",
    "rack": "Is DROP the tape stop you meant? Is CHOKE the rare bar-end accent, cut off hard enough?",
    "velocity": "Does the soft hit sound like a softer hit, not a quieter one? On the kick, the bass and the rim especially.",
    "round-robin": "Start with the A/B pair at the end. Does B sound like four strikes and A like one strike repeated? Is any take so different it sounds like another pad?",
}

# The Kotlin port against the Python prototype, when compare_kotlin.py has run.
KOTLIN = None
try:
    KOTLIN = json.load(open(os.path.join(OUT, "kotlin.json")))
except FileNotFoundError:
    pass


def kotlin_section():
    if KOTLIN is None:
        return ""
    pairs = KOTLIN["pairs"]
    scale = KOTLIN["scale"]
    by = lambda sec: [p for p in pairs if p["section"] == sec]  # noqa: E731
    rows = "".join(
        f"<tr><th>{p['name'].upper()}</th><td>{p['distance_db']} dB</td><td>{p['correlation']:.3f} · {p['residual_db']} dB</td><td>{scale[p['name'].upper()]['takes_closest_db']} to {scale[p['name'].upper()]['takes_widest_db']} dB</td>"
        f"<td>{scale[p['name'].upper()]['velocity_step_db']} dB</td><td>{p['python_measures']['centroid_hz']} → {p['kotlin_measures']['centroid_hz']} Hz</td>"
        f"<td>{p['python_measures']['decay_ms']:.0f} → {p['kotlin_measures']['decay_ms']:.0f} ms</td></tr>" for p in by("kit"))
    def pair_clips(ps):
        out = []
        for p in ps:
            out.append(clip_html({"name": p["name"] + " · python", "seconds": p["python_measures"]["seconds"], "note": "Round 0b, the accepted Python render.", "file": p["python"],
                                  "metrics": {"centroid_hz": p["python_measures"]["centroid_hz"], "decay_ms": p["python_measures"]["decay_ms"], "crest_db": p["python_measures"]["crest_db"]}}))
            out.append(clip_html({"name": p["name"] + " · kotlin", "seconds": p["kotlin_measures"]["seconds"], "note": "Round 1, the Kotlin engine.", "file": p["kotlin"],
                                  "metrics": {"centroid_hz": p["kotlin_measures"]["centroid_hz"], "decay_ms": p["kotlin_measures"]["decay_ms"], "crest_db": p["kotlin_measures"]["crest_db"], "python_to_kotlin_db": p["distance_db"]}}))
        return "".join(out)
    def summary(sec, label):
        ps = by(sec)
        if not ps:
            return ""
        return f"<tr><th>{label}</th><td>{len(ps)}</td><td>{min(p['distance_db'] for p in ps)} to {max(p['distance_db'] for p in ps)} dB</td></tr>"
    other = "".join(summary(s, l) for s, l in (("velocity", "velocity, 3 per voice"), ("round-robin", "takes, 4 per voice"), ("chain-rim", "the chain on the rim"), ("chain-clap", "the chain on the clap"), ("fuse", "the kick and bass pair")))
    return f"""
    <section class="card" id="kotlin">
      <h2>Round 1: the Kotlin engine against the Python prototype</h2>
      <p class="intro">The app's own engine (`Scuff.kt`, model {KOTLIN.get('model')}) rendering the same pads as the accepted round-0b prototype. The port keeps every constant;
      what cannot be the same arithmetic is the resampler, the noise generator and how takes are dealt. The distance is the take-distance measure used everywhere on this page.
      For scale, the next two columns are how far that voice's own four takes sit apart and how far its soft hit sits from its hard hit: a port closer than a take is the same drum.</p>
      <p class="ask"><span class="tag">LISTEN FOR</span> Each pair: is the Kotlin pad the same drum as the Python one? Name any pair where the port lost something.</p>
      <p class="intro">The clips are aligned by cross-correlation first (the two resamplers differ by about 1 ms of latency). "Waveform" is the correlation of the aligned clips and the residual between them in dB:
      1.000 and -30 dB or lower means the same waveform; a lower correlation means a different noise realisation (the tick's hiss and the hat's tip come from a different noise generator in Kotlin, as another take would).</p>
      <div class="table-wrap"><table><thead><tr><th>Voice</th><th>Python to Kotlin</th><th>Waveform</th><th>Its takes</th><th>Soft to hard</th><th>Centroid</th><th>To -60 dB</th></tr></thead><tbody>{rows}</tbody></table></div>
      <div class="table-wrap"><table><thead><tr><th>Also compared</th><th>Pairs</th><th>Python to Kotlin</th></tr></thead><tbody>{other}</tbody></table></div>
      <ol class="clips">{pair_clips(by("kit"))}{pair_clips(by("fuse"))}{pair_clips(by("chain-rim"))}</ol>
    </section>"""


# The kit on the shelf (round 2), when the shell generator's pack has been copied in under shelf/.
SHELF = None
try:
    SHELF = json.load(open(os.path.join(OUT, "shelf", "manifest.json")))
except FileNotFoundError:
    pass


def shelf_section():
    if SHELF is None:
        return ""
    out = []
    for kit in SHELF["kits"]:
        clips = []
        for p in kit["pads"]:
            n = f"{p['slot']:02d}"
            clips.append(clip_html({"name": f"A{n} {p['name']}", "seconds": p["seconds"], "file": f"shelf/{kit['id']}/pad-{n}.wav",
                                    "note": f"{p['class']}: the hard first take, as the saved kit plays it.", "metrics": {}}))
            if p["touch"]:
                clips.append(clip_html({"name": f"A{n} {p['name']} · touch", "seconds": 0.0, "file": f"shelf/{kit['id']}/touch-{n}.wav",
                                        "note": "Twelve strikes in the order the pad deals them: four soft takes, four medium, four hard.", "metrics": {}}))
        out.append(f"""
    <section class="card" id="shelf-{kit['id']}">
      <h2>Round 2: SCUFF {kit['title'].upper()} on the shelf</h2>
      <p class="intro">The sixteen-pad kit the app lists under SKINS, at {kit['bpm']} BPM: fourteen hits with three velocity zones of four takes each, rendered by the engine
      itself, and two phrases through one bus clipper, "Two Bars" (the round-0b pattern) and "Clap Riser" (eighths tightening to thirty-seconds).
      Every clip here is cut from the exported MPC 3 track's own audio through the app's pad resolver, so what you hear is what the kit plays.
      The grid is proved in software: the saved kit reloads equal, every slice matches its render, playback deals the takes in order, and the MPC 3 track reads back.</p>
      <p class="ask"><span class="tag">LISTEN FOR</span> Is this a kit you would load? Do the hats choke right, do the three bass hits sit as one instrument, and does "Two Bars" fuse the kick and the bass the way the one-shots cannot?</p>
      <ol class="clips">{''.join(clips)}</ol>
    </section>""")
    return "".join(out)


sections = []
for s in M["sections"]:
    clips = "".join(clip_html(c) for c in s["clips"])
    ask = LISTEN.get(s["id"], "")
    extra = ""
    if s["id"] == "velocity":
        vel = M["checks"]["velocity"]
        rows = "".join(
            f"<tr><th>{v}</th><td>{d['0.4']['attack_hz']} → {d['0.7']['attack_hz']} → {d['1.0']['attack_hz']} Hz</td>"
            f"<td>{d['0.4']['crest_db']} → {d['1.0']['crest_db']} dB</td><td>{d['soft_vs_hard_db']} dB</td></tr>" for v, d in vel.items())
        extra = f"""
        <p class="intro">Attack centroid (first 30 ms) at velocity 0.4, 0.7 and 1.0, all peak-matched; crest factor soft against hard;
        and the distance between the soft and the hard hit (RMS difference of level-matched third-octave spectrograms over the first 150 ms, 5 ms frames).</p>
        <div class="table-wrap"><table><thead><tr><th>Voice</th><th>Attack centroid</th><th>Crest, soft → hard</th><th>Soft vs hard</th></tr></thead><tbody>{rows}</tbody></table></div>"""
    if s["id"] == "before-after":
        ab = M["checks"]["before_after"]
        rows = "".join(f"<tr><th>{v}</th><td>{d['distance_db']} dB</td><td>{d['centroid_before']} → {d['centroid_after']} Hz</td><td>{d['crest_before']} → {d['crest_after']} dB</td></tr>" for v, d in ab.items())
        extra = f"""
        <div class="table-wrap"><table><thead><tr><th>Voice</th><th>Round 0 to 0b</th><th>Centroid</th><th>Crest</th></tr></thead><tbody>{rows}</tbody></table></div>"""
    if s["id"] == "chain-rim":
        st = M["checks"]["stage_moves_db"]
        rows = "".join(f"<tr><th>{v}</th>" + "".join(f"<td>{d[k]:.1f}</td>" for k in ("grit", "locker", "bark", "slam")) + "</tr>" for v, d in st.items())
        extra = f"""
        <p class="intro">How far each stage alone moves each voice from its dry render, in dB by the take-distance measure. A 0 is a stage the brief keeps off that voice. Round 0's BARK measured under 1 dB everywhere; it now boosts up to 18 dB and narrows the dry path.</p>
        <div class="table-wrap"><table><thead><tr><th>Voice</th><th>GRIT</th><th>LOCKER</th><th>BARK</th><th>SLAM</th></tr></thead><tbody>{rows}</tbody></table></div>"""
    if s["id"] == "round-robin":
        rr = M["checks"]["round_robin"]
        rows = "".join(f"<tr><th>{v}</th><td>{d['closest_db']} dB</td><td>{d['widest_db']} dB</td><td>{d['velocity_step_db']} dB</td></tr>" for v, d in rr.items())
        extra = f"""
        <p class="intro">Take distance is the same measure as the velocity table. The last column is the gap between velocity 0.4 and 1.0 on that voice,
        for scale: a widest pair above it means two takes can differ more than a soft and a hard hit do.</p>
        <div class="table-wrap"><table><thead><tr><th>Voice</th><th>Closest two takes</th><th>Widest two takes</th><th>Velocity 0.4 → 1.0</th></tr></thead><tbody>{rows}</tbody></table></div>"""
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
kit = checks["kit"]
fuse = checks["fuse"]
rr = checks["round_robin"]
vel = checks["velocity"]

GATES = [
    ("Melodic: six voices, keygroups, chord tools", "not applicable", "A drum engine. BOOM and PLUCK are pitched and could be keygrouped later; not a gate here."),
    ("Articulation: HOLD and STRUM", "not applicable", "Finite hits, no HOLD. Not a picked or thumbed string."),
    ("Percussion: authored roll or riser", "open",
     "The 32nd hat burst and the suck-back crash are in the two-bar pattern. No roll or riser pad is authored yet; a snare-roll riser in the GATECRASH and BREAKER mould is proposed for round 1."),
    ("Drums: multiple distinct genre kits on the shelf", "open", "One kit's worth of voices, in Python. The brief covers one genre; a second kit (Flint faster and brighter, Detroit heavier) is proposed, not built."),
    ("Velocity: rendering", "pass in Kotlin, by test",
     "ScuffTest holds that a soft and a hard hit differ in spectral shape at matched level on every core voice, the kick clicks and drops harder, the clap loses a tap. Python, soft against hard, in dB: " + " · ".join(f"{v} {d['soft_vs_hard_db']}" for v, d in vel.items()) + "."),
    ("Round robin: rendering", "pass in Kotlin, by test; not heard",
     f"Four takes per voice, dealt on four dimensions; ScuffTest holds that no two are copies and each replays byte for byte. Python: closest two takes {min(d['closest_db'] for d in rr.values())} to {max(d['closest_db'] for d in rr.values())} dB apart; "
     f"widest {min(d['widest_db'] for d in rr.values())} to {max(d['widest_db'] for d in rr.values())} dB."),
    ("Velocity and round robin: saving", "pass, by test", "A SCUFF patch saves its velocity, take and model version and replays byte for byte through JSON (DeterminismTest). No kit is saved yet."),
    ("Velocity and round robin: playback, export", "not yet verified", "No kit, no phone playback, no MPC export."),
    ("Sound character", "partial", "Round 0b accepted 2026-11-12 (\"Sounds good\"). The Kotlin port measures as the same waveform on the five deterministic voices and as another take on the four noise voices; it has not been heard."),
]


def gate_class(st: str) -> str:
    if st.startswith("pass"):
        return "ok"
    if st.startswith("not applicable"):
        return "na"
    if st.startswith("partial"):
        return "mid"
    return "open"


gate_rows = "".join(f'<tr><td>{ESC(g)}</td><td><span class="pill {gate_class(st)}">{ESC(st.upper())}</span></td><td>{ESC(why)}</td></tr>' for g, st, why in GATES)

TARGETS = [
    ("Kick body 80 to 160 Hz; drop 250 → 95 Hz in ~20 ms; 120 to 180 ms; nothing under 45 Hz",
     f"body share {kit['KICK']['body_80_160_db']} dB · {kit['KICK']['hz_at_5ms']:.0f} Hz at 5 ms → {kit['KICK']['hz_at_50ms']:.0f} Hz at 50 ms · {kit['KICK']['decay_ms']:.0f} ms to -60 dB · below 45 Hz {kit['KICK']['sub_below_45_db']} dB"),
    ("Clap: 3 to 4 taps 8 to 12 ms apart; 60 to 90 ms; nothing under 250 Hz; peak 1.2 to 2 kHz",
     f"taps at {' · '.join(f'{x:.0f}' for x in kit['CLAP']['onsets_ms'])} ms · {kit['CLAP']['decay_ms']:.0f} ms to -60 dB (with the comb send) · below 250 Hz {kit['CLAP']['below_250_db']} dB · peak band {kit['CLAP']['peak_band_hz']} Hz"),
    ("Bass hit 50 to 75 Hz; fast punch; strong 100 to 300 Hz; 250 to 450 ms; hard mute",
     f"{kit['BOOM']['hz_at_5ms']:.0f} Hz at 5 ms → {kit['BOOM']['hz_at_100ms']:.0f} Hz at 100 ms · 100 to 300 Hz {kit['BOOM']['harmonics_100_300_db']} dB against 45 to 80 Hz {kit['BOOM']['sub_45_80_db']} dB · {kit['BOOM']['decay_ms']:.0f} ms"),
    ("Rim: peaks at 850 and 1350 Hz; 40 to 70 ms; 700 Hz to 1.8 kHz",
     f"peaks {kit['RIM']['peaks_hz'][0]} and {kit['RIM']['peaks_hz'][1]} Hz · {kit['RIM']['decay_ms']:.0f} ms · band share {kit['RIM']['band_700_1800_db']} dB"),
    ("Hat: high-passed at 5 kHz; ring 8 to 11 kHz; 25 to 45 ms",
     f"ring at {kit['HAT']['ring_hz']} Hz · below 4 kHz {kit['HAT']['below_4k_db']} dB · {kit['HAT']['decay_ms']:.0f} ms"),
    ("Bell: 600 to 900 ms, 10 to 15 cents flat over the tail", f"{kit['BELL']['decay_ms']:.0f} ms · drift {kit['BELL']['drift_cents']} cents"),
    ("Zap: 1.2 kHz → 120 Hz over 80 to 130 ms", f"{kit['ZAP']['hz_at_5ms']:.0f} Hz at 5 ms → {kit['ZAP']['hz_at_90ms']:.0f} Hz at 90 ms · {kit['ZAP']['decay_ms']:.0f} ms"),
    ("Pluck: up ~30 Hz in 8 ms, then down ~80 Hz over 60 ms", f"{kit['PLUCK']['hz_at_2ms']:.0f} → {kit['PLUCK']['hz_at_8ms']:.0f} → {kit['PLUCK']['hz_at_80ms']:.0f} Hz"),
    ("Suck-back: rises over 250 to 400 ms, ends at zero", f"{kit['INHALE']['decay_ms']:.0f} ms long · last sample {kit['INHALE']['last_sample']}"),
    ("The bus: kick and bass fuse into a flat-topped growl",
     f"kick + bass, SLAM 0 → 0.55 → 0.9: crest {fuse['0.0']['crest_db']} → {fuse['0.55']['crest_db']} → {fuse['0.9']['crest_db']} dB · "
     f"100 to 300 Hz {fuse['0.0']['harmonics_100_300_db']} → {fuse['0.55']['harmonics_100_300_db']} → {fuse['0.9']['harmonics_100_300_db']} dB · "
     f"time at the ceiling {fuse['0.0']['plateau_pct']} → {fuse['0.55']['plateau_pct']} → {fuse['0.9']['plateau_pct']}%"),
]
target_rows = "".join(f"<tr><td>{ESC(a)}</td><td>{ESC(b)}</td></tr>" for a, b in TARGETS)

page = f"""<title>SCUFF Audition</title>
<meta name="claude:viewport-fit-sides" content="cover">
<style>
/* Layout: one column of TapeOS cards on the gray ground; sound lives in dark LCD panels (the ETCH page's system). */
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
    <div class="mark"><i></i><span class="eyebrow">SNIPSNAP · SCUFF · ROUND 1 · KOTLIN AGAINST THE ACCEPTED PYTHON · KOTLIN NOT HEARD</span></div>
    <h1>SCUFF Audition</h1>
    <p class="lede">A dry, mid-forward Michigan drum kit and the chain that makes it: ten voices built to your brief's numbers,
    crushed, boxed, barked and slammed into the ceiling. These are prototype renders from a Python model, not the app.
    Every one-shot is peak-matched, so a later stage never wins by being louder.</p>
    <p class="lede"><b>Round 1</b> is the Kotlin engine. Start with the first section: every pad from the app's own code beside the round-0b render you accepted.
    The rest of the page is round 0b as you approved it, re-measured with a corrected distance measure (see the foot).</p>
    <p class="lede"><b>Round 0b</b> answered "more character in general": every voice gained one more mechanism (a knock, a crack, a harmonic path, a woody body, a tip, a clang, a mechanical click, a slap),
    BARK went from inaudible to a real boost, LOCKER's loop got hotter with a soft clip in it, SLAM got more asymmetric, and every pad ends bone-dry at a hard tail gate. Start with the before/after section.</p>
    <p class="gesture">The gesture: a dry hit lands at the front of the speaker, the mids bark, the bus squares it off against the ceiling, and it is gone before the next sixteenth.</p>
  </header>

  <section class="card">
    <h2>The chain</h2>
    <div class="lcd">voice (4x) ─► <em>GRIT</em> ────► <em>LOCKER</em> ───► <em>BARK</em> ────► <em>SLAM</em> (4x) ──► pad
            crush      comb box      cocked        asymmetric clip,
            claps rims  rims claps    band-pass     600 Hz on the wet path,
            hats only   pluck         1.1 kHz       then a hard ceiling

MACROS  TUNE · DECAY (per voice)   SLAM · GRIT · BARK · LOCKER (the chain)      VELOCITY = how hard
RACK    DROP (tape stop, DIVE's move) and CHOKE (gated burst, GATECRASH's room) stay outside</div>
    <ul class="plain">
      <li><b>GRIT</b> is a sample-and-hold at 16 to 22 kHz with no anti-aliasing filter, plus 10 to 12 bits, deeper on a harder hit. It goes on the claps, rims and hats only, so the kick and bass stay clean.</li>
      <li><b>LOCKER</b> is a feedback comb 11 to 24 ms long with a band-pass inside the loop, as a parallel send: the metal locker, not a reverb.</li>
      <li><b>BARK</b> parks a resonant band-pass at 1.1 kHz and blends it in beside the dry hit: the nasal mid that sits next to a voice.</li>
      <li><b>SLAM</b> is the bus. An asymmetric clipper (a DC offset before the clip, so even harmonics appear) with its clipped path low-passed at 600 Hz, blended with the dry path, then a hard ceiling. On the two-bar loops it runs on the whole mix, which is where the kick and the bass fuse.</li>
    </ul>
  </section>

  <section class="card">
    <h2>The brief's numbers, measured</h2>
    <p class="intro">Your targets on the left, the Python render at the defaults on the right. Band shares are dB of the energy from 30 Hz to 16 kHz, so 0 dB means all of it.</p>
    <div class="table-wrap"><table><thead><tr><th>Target</th><th>Measured</th></tr></thead><tbody>{target_rows}</tbody></table></div>
  </section>

  <nav aria-label="Sections">{''.join(f'<a href="#shelf-{k["id"]}">Shelf</a>' for k in (SHELF or {}).get("kits", []))}{'<a href="#kotlin">Kotlin</a>' if KOTLIN else ''}{nav}<a href="#gates">Gates</a><a href="#next">Your answers</a></nav>

  {shelf_section()}
  {kotlin_section()}
  {''.join(sections)}

  <section class="card" id="gates">
    <h2>Acceptance gates</h2>
    <p class="intro">From docs/ENGINE_ACCEPTANCE_GATES.md. Round 0b is a prototype; the engine stays in progress until every applicable row has evidence.</p>
    <div class="table-wrap"><table><thead><tr><th>Gate</th><th>Status</th><th>Evidence</th></tr></thead><tbody>{gate_rows}</tbody></table></div>
  </section>

  <section class="card" id="next">
    <h2>What I need from you</h2>
    <ul class="plain">
      <li><b>Kotlin:</b> in the first section, is every Kotlin pad the same drum as the Python one you accepted? The clap, hat, tick and crash are different noise takes by construction; do they still read as the same pad?</li>
      <li><b>Round 0 or 0b:</b> pad by pad, which has the character you want? Any pad that got worse?</li>
      <li><b>The kit:</b> which of the ten pads are the sound, which are close, and which are wrong? Name the wrong ones and what they should do instead.</li>
      <li><b>The bus:</b> is SLAM 0.55 the glue and 0.9 the trunk, or is the whole thing too flat? Should hats go through the bus?</li>
      <li><b>The stages:</b> on the rim and the clap, which stage makes it stank: GRIT, LOCKER or BARK? Any you would drop?</li>
      <li><b>Tempo:</b> the loops are at {checks['bpm']:.0f} BPM. Faster (Flint) or heavier (Detroit) for the second kit?</li>
      <li><b>Velocity and takes:</b> does a soft hit sound softer, and do the four takes sound like four strikes (the A/B at the end)?</li>
      <li><b>The landing:</b> an engine with its own ten voices, or a kit family on THUMP and TINES plus a new chain? The brief doc has the three directions.</li>
    </ul>
  </section>

  <section class="card foot">
    <h2>Measured, not heard</h2>
    <p>{checks['clips']} clips, all finite: {'yes' if checks['all_finite'] else 'NO'}. Pads and the two-bar loop render byte-identical across runs: {'yes' if checks['determinism_pads'] and checks['determinism_pattern'] else 'NO'}.
    Centroid is magnitude-weighted over 150 Hz to 12 kHz. "To -60 dB" is the time from the peak to 60 dB below it. "At the ceiling" is the share of samples within 3 dB of the peak in the hit's first 120 ms (4 s and 1.5 dB for the loops): the square-topped signature.
    Take and velocity distances are the RMS difference of two hits' level-matched third-octave spectrograms over their first 150 ms in 5 ms frames, every band floored 80 dB under the loudest.
    Round 1 added that floor after the measure read a 16-bit copy of a clip as 12 dB from its float original; every distance on this page is re-measured with it, so the round-0b numbers are lower than the ones first published (the kick's round 0 to 0b, for instance, 8.5 dB where 20.0 was quoted).</p>
    <p>Research (names allowed here, never on a pad): the distortion pedal's filter is a passive low-pass after the clip (<a href="https://thejhsshow.com/articles/history-and-timeline-of-the-proco-rat-pedal" target="_blank" rel="noopener">history</a>);
    the bass overdrive's BALANCE blends dry against drive (<a href="https://www.adorama.com/col/productManuals/BSODB3.pdf" target="_blank" rel="noopener">manual</a>);
    the 12-bit sampler ran 26.04 kHz (<a href="https://www.rossum-electro.com/products/sp-1200/" target="_blank" rel="noopener">reissue page</a>);
    the analog delay's standard range is 20 to 300 ms (<a href="https://www.boss.info/products/dm-2w/" target="_blank" rel="noopener">product page</a>);
    the drum machine's hat is six square oscillators from a hex Schmitt inverter near 205, 304, 370, 523, 540 and 800 Hz (<a href="https://speech.di.uoa.gr/ICMC-SMC-2014/images/VOL_2/1453.pdf" target="_blank" rel="noopener">ICMC 2014 paper</a>, <a href="https://www.kvraudio.com/forum/viewtopic.php?p=7494787" target="_blank" rel="noopener">forum analysis</a>).
    Source: SnipSnap prototype/scuff at {ESC(COMMIT)}.</p>
  </section>
</div>
"""
with open(os.path.join(OUT, "index.html"), "w") as f:
    f.write(page)
print("wrote", os.path.join(OUT, "index.html"), len(page), "bytes")
