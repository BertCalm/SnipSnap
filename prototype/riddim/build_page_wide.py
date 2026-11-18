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
TEMPLATE = (B.TEMPLATE.replace(INTRO_OLD, INTRO_NEW).replace(QUESTIONS_OLD, QUESTIONS_NEW)
            .replace("<title>Riddim Kits</title>", "<title>Riddim Wider Kits</title>")
            .replace('<p class="meta">Python renders, mono. Not yet heard: the kit layouts, the rolls and the grooves.</p>', ""))
assert "round 3" in TEMPLATE and "Ting" in TEMPLATE

if __name__ == "__main__":
    B.main(sys.argv[1], W, TEMPLATE)
