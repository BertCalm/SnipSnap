"""Round-1 listening page: python3 build_page.py OUT (after kits.py OUT). Writes OUT/site/index.html and MP3s.

Each pad gets two clips: its velocity ladder (soft, mid, hard, take 1 each) and its four hard takes in a row,
so velocity and round robin can be judged by ear. Needs ffmpeg."""
from __future__ import annotations

import html
import json
import os
import subprocess
import sys
import wave

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kits as K  # noqa: E402

RATE = K.RATE
E = html.escape


def rd(path):
    with wave.open(path) as w:
        return np.frombuffer(w.readframes(w.getnframes()), "<i2") / 32768


def chain(paths, gap=0.25, cap=1.2):
    parts = []
    for p in paths:
        y = rd(p)[: int(cap * RATE)]
        parts += [y, np.zeros(int(gap * RATE))]
    return np.concatenate(parts)


def mp3(y, path):
    tmp = path + ".wav"
    K.write_wav(tmp, y)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", tmp, "-ac", "1", "-b:a", "112k", path], check=True)
    os.remove(tmp)


def main(out):
    site = os.path.join(out, "site")
    os.makedirs(os.path.join(site, "audio"), exist_ok=True)
    checks = json.load(open(os.path.join(out, "checks.json")))
    sections = []
    for kit, k in K.KITS.items():
        g = os.path.join(out, kit)
        mp3(rd(os.path.join(g, "groove.wav")), os.path.join(site, "audio", f"{kit}_groove.mp3"))
        cells = {}
        for pid, p in zip(K.PADS, k["pads"]):
            is_roll = "roll" in p
            cap = 3.0 if is_roll else 1.2
            ladder = chain([os.path.join(g, "pads", f"{pid}_z{z}_t0.wav") for z in range(3)], cap=cap)
            takes = chain([os.path.join(g, "pads", f"{pid}_z2_t{t}.wav") for t in range(4)], gap=0.18, cap=cap if is_roll else 0.7)
            mp3(ladder, os.path.join(site, "audio", f"{kit}_{pid}_ladder.mp3"))
            mp3(takes, os.path.join(site, "audio", f"{kit}_{pid}_takes.mp3"))
            c = checks[kit][pid]
            fam = "roll" if is_roll else ("perc" if p["voice"] in ("SHAKER", "CLAVE", "STICKS", "COWBELL", "CONGA", "BONGO_HIGH",
                                                                      "TAMA", "WOOD", "METAL") else "drum")
            cells[pid] = f'''<div class="pad {fam}">
  <button type="button" class="hit play" data-src="audio/{kit}_{pid}_ladder.mp3" aria-label="{E(p['label'])}: soft, mid, hard">
    <span class="pid">{pid}</span><span class="pl">{E(p['label'])}</span></button>
  <div class="sub"><button type="button" class="mini play" data-src="audio/{kit}_{pid}_ladder.mp3">soft→hard</button>
  <button type="button" class="mini play" data-src="audio/{kit}_{pid}_takes.mp3">4 takes</button></div>
  <span class="meas" title="closest two takes; a copy is above 40 dB">{c['closest_takes_db']:.0f} dB{'' if c['darker_when_softer'] else ' · soft not darker'}</span>
</div>'''
        # MPC layout: A13-A16 on top, A01-A04 at the bottom
        rows = [K.PADS[12:16], K.PADS[8:12], K.PADS[4:8], K.PADS[0:4]]
        grid = "".join(cells[pid] for row in rows for pid in row)
        mono = sum(1 for c in checks[kit].values() if c["darker_when_softer"])
        worst = max(c["closest_takes_db"] for c in checks[kit].values())
        key = f" · {E(k['key'])}" if k["key"] else ""
        sections.append(f'''<section class="kit" aria-labelledby="{kit}">
  <div class="kit-head"><h2 id="{kit}">{E(k['title'])}</h2><span class="meta">{k['bpm']} BPM{key}</span></div>
  <p>{E(k['about'])}</p>
  <div class="groove"><button type="button" class="play big" data-src="audio/{kit}_groove.mp3" aria-label="Play the {E(k['title'])} groove"><span class="ico" aria-hidden="true"></span></button>
  <span>Four-bar groove, played the way the app deals the kit: velocity picks the zone, takes cycle, hats choke{', booms cut each other' if kit == 'boom_riddim' else ''}.</span></div>
  <div class="grid">{grid}</div>
  <p class="foot">Measured: {mono} of 16 pads get darker at each softer zone; the closest two takes on any pad are {worst:.1f} dB apart (a copy is above 40).</p>
</section>''')
    page = TEMPLATE.replace("{SECTIONS}", "".join(sections))
    open(os.path.join(site, "index.html"), "w").write(page)
    print("wrote", site)


TEMPLATE = '''<title>Riddim Kits</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Archivo:wght@400;600;800&family=IBM+Plex+Mono:wght@400;500&family=Source+Sans+3:wght@400;600&display=swap">
<style>
/* Layout: one column; each kit is an MPC-style 4x4 pad grid (A13 top-left, A01 bottom-left) under its groove. */
:root {
  --bg: #f3f5f1; --surface: #ffffff; --fg: #18211b; --muted: #5a6a5e; --line: #d5ddd4;
  --accent: #0f7a45; --accent-ink: #ffffff; --gold: #b07d06; --perc: #e9efe2; --drum: #ffffff; --roll: #fbf3dc;
  --display: "Archivo", "Helvetica Neue", Arial, sans-serif; --body: "Source Sans 3", "Segoe UI", system-ui, sans-serif;
  --mono: "IBM Plex Mono", ui-monospace, Menlo, monospace;
}
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {
  --bg: #111613; --surface: #19201b; --fg: #e6ece6; --muted: #9aaa9d; --line: #2c372f;
  --accent: #3fbf7f; --accent-ink: #0b130e; --gold: #e0b23c; --perc: #1d2a21; --drum: #19201b; --roll: #2a2614; color-scheme: dark; } }
:root[data-theme="dark"] {
  --bg: #111613; --surface: #19201b; --fg: #e6ece6; --muted: #9aaa9d; --line: #2c372f;
  --accent: #3fbf7f; --accent-ink: #0b130e; --gold: #e0b23c; --perc: #1d2a21; --drum: #19201b; --roll: #2a2614; color-scheme: dark; }
body { background: var(--bg); color: var(--fg); font: 16px/1.55 var(--body); }
.wrap { max-width: 880px; margin: 0 auto; padding-block: 28px 64px; padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); display: grid; gap: 44px; }
h1, h2 { font-family: var(--display); text-wrap: balance; margin: 0; }
h1 { font-size: clamp(2rem, 6vw, 3rem); font-weight: 800; letter-spacing: -0.02em; line-height: 1.05; }
h2 { font-size: 1.5rem; font-weight: 800; }
p { margin: 0; max-width: 65ch; }
.eyebrow { font: 500 0.75rem var(--mono); letter-spacing: 0.08em; text-transform: uppercase; color: var(--muted); }
.intro, .kit { display: grid; gap: 14px; }
.kit-head { display: flex; flex-wrap: wrap; gap: 6px 14px; align-items: baseline; }
.meta { font: 0.85rem var(--mono); color: var(--muted); }
.legend { display: flex; flex-wrap: wrap; gap: 8px 16px; font: 0.8rem var(--mono); color: var(--muted); }
.legend span::before { content: ""; display: inline-block; width: 12px; height: 12px; border-radius: 3px; margin-right: 6px; vertical-align: -2px; border: 1px solid var(--line); }
.legend .d::before { background: var(--drum); } .legend .p::before { background: var(--perc); } .legend .r::before { background: var(--roll); }
.groove { display: flex; gap: 14px; align-items: center; font-size: 0.95rem; }
.grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
.pad { border: 1px solid var(--line); border-radius: 6px; padding: 8px; display: grid; gap: 6px; min-width: 0; background: var(--drum); }
.pad.perc { background: var(--perc); } .pad.roll { background: var(--roll); }
.hit { all: unset; cursor: pointer; display: grid; gap: 2px; min-height: 52px; border-radius: 4px; padding: 4px; }
.hit:hover { background: color-mix(in srgb, var(--accent) 12%, transparent); }
.hit:focus-visible, .mini:focus-visible, .big:focus-visible { outline: 2px solid var(--gold); outline-offset: 2px; }
.hit.on { background: var(--accent); color: var(--accent-ink); }
.pid { font: 500 0.7rem var(--mono); color: var(--muted); }
.hit.on .pid { color: var(--accent-ink); }
.pl { font: 600 0.95rem var(--display); line-height: 1.2; overflow-wrap: anywhere; }
.sub { display: flex; flex-wrap: wrap; gap: 4px; }
.mini { font: 0.7rem var(--mono); padding: 3px 6px; border-radius: 3px; border: 1px solid var(--line); background: transparent; color: var(--fg); cursor: pointer; }
.mini.on { background: var(--accent); color: var(--accent-ink); border-color: var(--accent); }
.meas { font: 0.65rem var(--mono); color: var(--muted); }
.big { flex: none; width: 48px; height: 48px; border-radius: 50%; border: 1.5px solid var(--accent); background: transparent; color: var(--accent); cursor: pointer; display: grid; place-items: center; padding: 0; }
.big:hover, .big.on { background: var(--accent); color: var(--accent-ink); }
.big .ico { width: 0; height: 0; border-left: 12px solid currentColor; border-top: 7px solid transparent; border-bottom: 7px solid transparent; margin-left: 3px; }
.big.on .ico { width: 12px; height: 12px; border: 0; margin: 0; background: currentColor; }
.foot { font-size: 0.85rem; color: var(--muted); }
@media (max-width: 520px) { .grid { gap: 5px; } .pad { padding: 5px; } .pl { font-size: 0.8rem; } .mini { font-size: 0.62rem; padding: 2px 4px; } }
ol { margin: 0; padding-left: 1.3rem; display: grid; gap: 8px; max-width: 65ch; }
</style>
<div class="wrap">
  <div class="intro">
    <span class="eyebrow">SnipSnap · dancehall kits · round 1 · Python prototype</span>
    <h1>Riddim kits</h1>
    <p>Three 16-pad kits built from the round-0 sounds you heard as pretty good. Pads sit the way an MPC shows them: A01 bottom-left, A16 top-right. Each A16 is the kit's roll or riser. Tap a pad name to hear it soft, mid and hard; tap "4 takes" to hear four hard hits in a row, which should sound like one drum hit four times.</p>
    <div class="legend"><span class="d">drum</span><span class="p">percussion</span><span class="r">roll or riser</span></div>
    <p class="meta">Python renders, mono. Not yet heard: the kit layouts, the rolls and the grooves.</p>
  </div>
  {SECTIONS}
  <section class="intro" aria-labelledby="q"><h2 id="q">What to tell me</h2>
  <ol>
    <li>Keep or swap: any pad you would move, replace or drop.</li>
    <li>The three rolls on A16: right speed and length?</li>
    <li>The balance in each groove: anything too loud or buried?</li>
    <li>Any pad whose soft hit does not sound like the same drum played softer.</li>
  </ol></section>
</div>
<script>
let cur = null, curBtn = null;
document.querySelectorAll('.play').forEach(b => b.addEventListener('click', () => {
  if (curBtn === b && cur && !cur.paused) { cur.pause(); b.classList.remove('on'); return; }
  if (cur) { cur.pause(); curBtn && curBtn.classList.remove('on'); }
  cur = new Audio(b.dataset.src); curBtn = b; b.classList.add('on');
  cur.addEventListener('ended', () => b.classList.remove('on'));
  cur.play().catch(() => b.classList.remove('on'));
}));
</script>
'''

if __name__ == "__main__":
    main(sys.argv[1])
