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
QUESTIONS_NEW = QUESTIONS_NEW.replace("<ol>\n", "<ol>\n    <li><b>Wood attack</b> (top of the page): is 0.85 right, or do you want the hardest mallet, or something between?</li>\n", 1)

# The owner heard round 3 and said "wood might need more of an attack" (2026-11-18). BEATER is the mallet:
# 0.5 is the wood of rounds 1 and 2, 0.85 is what Wood and Bronze now plays, 1.0 is the hardest.
SETTINGS = ((0.5, "Before", "the wood you heard: rounds 1 to 3"), (W.WOOD_BEATER, "Now", "what Wood and Bronze plays now"),
            (1.0, "Hardest", "the hardest mallet"))
AB = '''<section class="kit" aria-labelledby="wood-attack">
  <div class="kit-head"><h2 id="wood-attack">Wood attack</h2><span class="meta">BEATER: the mallet, yarn (0) to hard (1)</span></div>
  <p>You said the wood might need more of an attack. Measured against your wood loops, it did: the first 8 ms of a hit centred near 0.3 to 0.5 kHz, where your loops sit at 1.5 to 2.4 kHz. A harder mallet now brings out the bar's upper overtones and a short knock, and soft hits stay dark. Clave Dancehall's woods, already on the shelf, keep the 0.5 mallet.</p>
  <div class="grid ab">{CELLS}</div>
  <p class="foot">Each "hits" clip plays Wood D3, A3 and C4 hard, then A3 soft, mid and hard. The groove is Wood and Bronze with every wood at that mallet.</p>
</section>
'''
CELL = '''<div class="pad perc">
  <button type="button" class="hit play" data-src="audio/wood_ab_{key}_groove.mp3" aria-label="{name}: Wood and Bronze groove at BEATER {b}">
    <span class="pid">BEATER {b}</span><span class="pl">{name}</span></button>
  <div class="sub"><button type="button" class="mini play" data-src="audio/wood_ab_{key}_groove.mp3">groove</button>
  <button type="button" class="mini play" data-src="audio/wood_ab_{key}_hits.mp3">hits</button></div>
  <span class="meas">{what}</span>
</div>'''
TEMPLATE = (B.TEMPLATE.replace(INTRO_OLD, INTRO_NEW).replace(QUESTIONS_OLD, QUESTIONS_NEW)
            .replace("<title>Riddim Kits</title>", "<title>Riddim Wider Kits</title>")
            .replace('<p class="meta">Python renders, mono. Not yet heard: the kit layouts, the rolls and the grooves.</p>', "")
            .replace("  {SECTIONS}", AB.replace("{CELLS}", "".join(
                CELL.format(key=str(b).replace(".", ""), b=b, name=n, what=w) for b, n, w in SETTINGS)) + "  {SECTIONS}")
            .replace(".pad.perc { background: var(--perc); }", ".pad.perc { background: var(--perc); }\n.grid.ab { grid-template-columns: repeat(3, minmax(0, 1fr)); }"))
assert "round 3" in TEMPLATE and "Ting" in TEMPLATE and "wood-attack" in TEMPLATE and "{SECTIONS}" in TEMPLATE


def wood_ab(site):
    """Renders the comparison clips into site/audio: the groove and a set of hits at each BEATER setting."""
    import numpy as np
    rp = W.rp
    kit = W.KITS["wood_bronze"]
    real = kit["pads"]
    for b, _, _ in SETTINGS:
        key = str(b).replace(".", "")
        kit["pads"] = [dict(p, macros=dict(p["macros"], BEATER=b)) if p["voice"] == "WOOD" else p for p in real]
        try:
            B.mp3(W.play_groove("wood_bronze"), os.path.join(site, "audio", f"wood_ab_{key}_groove.mp3"))
        finally:
            kit["pads"] = real
        gap = np.zeros(int(.22 * W.RATE))
        hits = [rp.render("WOOD", W.st("WOOD", s, BEATER=b), 1.0, 0)[: int(.6 * W.RATE)] for s in (-4, 3, 6)]
        hits += [rp.render("WOOD", W.st("WOOD", 3, BEATER=b), v, 1)[: int(.6 * W.RATE)] for v in W.ZONES]
        B.mp3(np.concatenate([x for h in hits for x in (h, gap)]), os.path.join(site, "audio", f"wood_ab_{key}_hits.mp3"))


if __name__ == "__main__":
    os.makedirs(os.path.join(sys.argv[1], "site", "audio"), exist_ok=True)
    wood_ab(os.path.join(sys.argv[1], "site"))
    B.main(sys.argv[1], W, TEMPLATE)
