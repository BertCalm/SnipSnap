#!/usr/bin/env python3
"""Self-contained listening page for actual exported-PCM round-robin playback."""
import base64
from pathlib import Path
import sys

root = Path(sys.argv[1])
cards = []
for route in ['A', 'B']:
    for mode in ['finite', 'held']:
        clips = []
        for touch, label in enumerate(['Soft', 'Medium', 'Hard'], 1):
            wav = root / 'previews' / f'{route}_{mode}_T{touch}.wav'
            data = base64.b64encode(wav.read_bytes()).decode()
            clips.append(f'<h3>{label}</h3><audio controls preload="metadata" src="data:audio/wav;base64,{data}"></audio>')
        cards.append(f'<section><h2>Route {route} · {mode}</h2>' + ''.join(clips) + '</section>')
page = '''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Bristle · alternating bass takes</title><style>
*{box-sizing:border-box}body{margin:0;background:#17191d;color:#f3efe6;font:16px/1.5 system-ui,sans-serif}
main{max-width:1000px;margin:auto;padding:32px 20px}h1{font-size:clamp(30px,6vw,52px);line-height:1.1}h2{font-size:22px}h3{font-size:16px;margin-bottom:8px}
p{max-width:760px;color:#c8c9ce}.grid{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin:30px 0}
section{background:#23262d;border:1px solid #42454e;border-radius:14px;padding:22px}audio{width:100%;height:42px}
@media(max-width:650px){.grid{grid-template-columns:1fr}main{padding:20px 14px}}
</style><main><p>BRISTLE · ROUND-ROBIN PROTOTYPE</p><h1>One bass, two attacks.</h1>
<p>Each clip plays C2 four times: original, variation, original, variation.
Compare the start of each note. The variation changes oscillator phase, attack and the opening filter motion; it adds no pitch jitter.</p>
<div class="grid">''' + ''.join(cards) + '''</div>
<p>These clips use the actual exported samples through SnipSnap’s JVM reference sampler.
Each note is held for half a second, followed by a 300 ms release window. This is not a phone or MPC recording.</p>
<p>Each instrument covers C1–C3 with three touch levels and two takes. The phone program cycles within each touch level.
The main MPC program uses the original take at three touch levels. For MPC cycling, open one of the separate Soft, Medium or Hard programs under its TrackData folder.
Those programs cycle both takes at a fixed tone; velocity still changes volume.</p>
<p>The new take needs your listening judgment. Physical MPC playback and combined velocity switching with round robin remain unverified.</p>
</main></html>'''
(root / 'listen.html').write_text(page)
print('Wrote', root / 'listen.html')
