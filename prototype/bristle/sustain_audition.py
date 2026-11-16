#!/usr/bin/env python3
"""Package actual exported-sample reference playback as a self-contained audition."""
import base64
from pathlib import Path
import sys

root = Path(sys.argv[1])
def audio(path):
    encoded = base64.b64encode(path.read_bytes()).decode()
    return f'<audio controls preload="metadata" src="data:audio/wav;base64,{encoded}"></audio>'

cards = []
for route, description in [('A', 'Drive before filtering'), ('B', 'Drive after filtering')]:
    rows = []
    for touch, label in enumerate(['Soft', 'Medium', 'Hard'], 1):
        rows.append(f'<div class="clip"><h3>{label}</h3>{audio(root / "previews" / f"{route}_held_T{touch}.wav")}</div>')
    rows.append('<div class="clip finite"><h3>Original finite note</h3>' + audio(
        root / 'previews' / f'{route}_finite_T3.wav') + '</div>')
    cards.append(f'<section><p class="eyebrow">ROUTE {route}</p><h2>{description}</h2>' + ''.join(rows) + '</section>')
page = '''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Bristle · held bass audition</title><style>
*{box-sizing:border-box}body{margin:0;background:#17191d;color:#f3efe6;font:16px/1.5 system-ui,sans-serif}
main{max-width:1000px;margin:auto;padding:42px 22px}h1{font-size:clamp(32px,6vw,56px);line-height:1.1;margin:10px 0 20px}
h2{font-size:22px;margin:4px 0 25px}h3{font-size:15px;font-weight:600;margin:0 0 8px}.eyebrow{color:#ffb661;font-size:13px;letter-spacing:.13em;font-weight:700}
.intro{max-width:680px;color:#c8c9ce}.grid{display:grid;grid-template-columns:1fr 1fr;gap:24px;margin:32px 0}
section{background:#23262d;border:1px solid #42454e;border-radius:14px;padding:24px}.clip{margin:20px 0}audio{width:100%;height:42px}
.finite{border-top:1px solid #464952;padding-top:22px;margin-top:26px}footer{color:#b4b7be;font-size:14px;max-width:760px}
@media(max-width:650px){.grid{grid-template-columns:1fr}main{padding:24px 14px}section{padding:20px}}
</style><main><p class="eyebrow">BRISTLE · SUSTAIN PROTOTYPE</p><h1>Let the low note hold.</h1>
<p class="intro">Each new example holds C2 for three seconds, then releases over 200 milliseconds.
Compare soft, medium and hard touch on both routes. The original finite notes are included below for reference.</p>
<div class="grid">''' + ''.join(cards) + '''</div><footer>
<p>All examples play the exported samples through SnipSnap’s JVM reference sampler,
using the same playback gain. The finite examples end naturally within the same three-second listening window.
This is a listening prototype, not a phone or physical MPC recording.</p>
<p>The download includes both finite and held instruments, each covering C1–C3 with three touch layers.
The held option retains the opening slide and filter bite before looping the settled tone. Round robin is not included.</p>
</footer></main></html>'''
(root / 'listen.html').write_text(page)
print('Wrote', root / 'listen.html', len(page), 'characters')
