"""Round-3 listening page: python3 build_page_wide.py OUT (after kits_wide.py OUT). Writes OUT/site/index.html.

The round-1 page builder, given round 3's kits and its own words. Needs ffmpeg."""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_page as B  # noqa: E402  (imports kits.py first, before CALABASH's folder joins the path)
import kits_wide as W  # noqa: E402

INTRO_OLD = B.TEMPLATE[B.TEMPLATE.index('<span class="eyebrow">'):B.TEMPLATE.index('<div class="legend">')]
INTRO_NEW = '''<span class="eyebrow">SnipSnap · dancehall kits · round 3 · Python prototype</span>
    <h1>Riddim, wider</h1>
    <p>Three more 16-pad kits, from the references you sent for Brazilian funk, West African swing and wood and metal. Pads sit the way an MPC shows them: A01 bottom-left, A16 top-right. Each A16 is the kit's roll or riser. Tap a pad name to hear it soft, mid and hard; tap "4 takes" to hear four hard hits in a row, which should sound like one drum hit four times.</p>
    <p>The grooves are swung the way the references measured (58%, 61% and 55%). Swing belongs to the groove, not the kit: on the MPC or in the app you set it yourself.</p>
    '''
QUESTIONS_OLD = B.TEMPLATE[B.TEMPLATE.index('<ol>'):B.TEMPLATE.index('</ol>')]
QUESTIONS_NEW = '''<ol>
    <li>Two new sounds: the <b>Ting</b> bell and the <b>Ratchet</b> scrape (Baile Funk A09 to A12). Keep, change or drop?</li>
    <li>Keep or swap: any pad you would move, replace or drop.</li>
    <li>The three A16s (ratchet riser, sabar roll, wood tremolo): right speed and length?</li>
    <li>The swing and balance in each groove: too much, too little, anything buried?</li>
    <li>Any pad whose soft hit does not sound like the same drum played softer.</li>
  '''
QUESTIONS_NEW = QUESTIONS_NEW.replace("<ol>\n", "<ol>\n    <li><b>Clave Dancehall woods</b> (top of the page): keep A, the approved mallet, or switch to B, the hardest?</li>\n", 1)

# The owner heard round 3 and said "wood might need more of an attack" (2026-11-18), compared mallets 0.5, 0.85 and
# 1.0, and chose the hardest for Wood and Bronze (2026-11-19). For Clave Dancehall, approved and on the shelf with the
# 0.5 mallet, they asked for an A/B. BEATER is the mallet: 0.5 is the wood of rounds 1 and 2, 1.0 the hardest.
# (kit module, kit id, label, wood pad tunings in semitones, [(BEATER, name, what)])
import kits as K  # noqa: E402  (already imported by build_page, first on the path)

ROWS = (
    (W, "wood_bronze", "Wood and Bronze", (-4, 3, 6),
     ((0.5, "Before", "the wood you first heard"), (W.WOOD_BEATER, "Your choice", "the hardest mallet, as the kit now plays"))),
    (K, "clave_dancehall", "Clave Dancehall", (0, 3, 7, 12),
     ((0.5, "A: approved", "as on the shelf now"), (1.0, "B: hardest", "the same kit, every wood at the hardest mallet"))),
)
AB = '''<section class="kit" aria-labelledby="wood-attack">
  <div class="kit-head"><h2 id="wood-attack">Wood attack</h2><span class="meta">BEATER: the mallet, yarn (0) to hard (1)</span></div>
  <p>You asked for more attack on the wood and chose the hardest mallet for Wood and Bronze. Measured against your wood loops, the first 8 ms of a hard hit now sits near your woody loop's brightness, and soft hits stay dark. Below is that change, then the A/B you asked for on Clave Dancehall's four woods, which are on the shelf with the approved mallet.</p>
  {ROWS}
  <p class="foot">Each "hits" clip plays the kit's woods hard, low to high, then one wood soft, mid and hard. Each "groove" clip is the kit's own groove with every wood at that mallet.</p>
</section>
'''
ROW = '''<h3 class="ab-title">{title}</h3>
  <div class="grid ab">{cells}</div>'''
CELL = '''<div class="pad perc">
  <button type="button" class="hit play" data-src="audio/wood_ab_{key}_groove.mp3" aria-label="{title}, {name}: groove at BEATER {b}">
    <span class="pid">BEATER {b}</span><span class="pl">{name}</span></button>
  <div class="sub"><button type="button" class="mini play" data-src="audio/wood_ab_{key}_groove.mp3">groove</button>
  <button type="button" class="mini play" data-src="audio/wood_ab_{key}_hits.mp3">hits</button></div>
  <span class="meas">{what}</span>
</div>'''


def _key(kit, b):
    return f"{kit}_{str(b).replace('.', '')}"


ROWS_HTML = "\n  ".join(ROW.format(title=t, cells="".join(CELL.format(key=_key(kid, b), b=b, name=n, what=w, title=t) for b, n, w in sets))
                         for _, kid, t, _, sets in ROWS)
TEMPLATE = (B.TEMPLATE.replace(INTRO_OLD, INTRO_NEW).replace(QUESTIONS_OLD, QUESTIONS_NEW)
            .replace("<title>Riddim Kits</title>", "<title>Riddim Wider Kits</title>")
            .replace('<p class="meta">Python renders, mono. Not yet heard: the kit layouts, the rolls and the grooves.</p>', "")
            .replace("  {SECTIONS}", AB.replace("{ROWS}", ROWS_HTML) + "  {SECTIONS}")
            .replace(".pad.perc { background: var(--perc); }", ".pad.perc { background: var(--perc); }\n"
                     ".grid.ab { grid-template-columns: repeat(2, minmax(0, 1fr)); }\n"
                     ".ab-title { font: 600 1rem var(--display); margin: 6px 0 0; }"))
assert "round 3" in TEMPLATE and "Ting" in TEMPLATE and "wood-attack" in TEMPLATE and "{SECTIONS}" in TEMPLATE and "B: hardest" in TEMPLATE


def wood_ab(site):
    """Renders each row's clips into site/audio: the kit's groove and a set of hits at each BEATER setting."""
    import numpy as np
    rp = W.rp
    for mod, kid, _, tunings, sets in ROWS:
        kit = mod.KITS[kid]
        real = kit["pads"]
        for b, _, _ in sets:
            kit["pads"] = [dict(p, macros=dict(p["macros"], BEATER=b)) if p["voice"] == "WOOD" else p for p in real]
            try:
                B.mp3(mod.play_groove(kid), os.path.join(site, "audio", f"wood_ab_{_key(kid, b)}_groove.mp3"))
            finally:
                kit["pads"] = real
            gap = np.zeros(int(.22 * W.RATE))
            hits = [rp.render("WOOD", W.st("WOOD", s, BEATER=b), 1.0, 0)[: int(.6 * W.RATE)] for s in tunings]
            hits += [rp.render("WOOD", W.st("WOOD", tunings[1], BEATER=b), v, 1)[: int(.6 * W.RATE)] for v in W.ZONES]
            B.mp3(np.concatenate([x for h in hits for x in (h, gap)]), os.path.join(site, "audio", f"wood_ab_{_key(kid, b)}_hits.mp3"))


if __name__ == "__main__":
    os.makedirs(os.path.join(sys.argv[1], "site", "audio"), exist_ok=True)
    wood_ab(os.path.join(sys.argv[1], "site"))
    B.main(sys.argv[1], W, TEMPLATE)
