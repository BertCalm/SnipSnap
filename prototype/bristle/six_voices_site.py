#!/usr/bin/env python3
"""Copy verified auditions and portable starter into the existing private listening Site."""
import html
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

root, dist = map(Path, sys.argv[1:3])
voices = [('ROUND','Round Floor','Rounded sub with touch-dependent second harmonic.'),
          ('PULSE','Pulse Bite','Narrow pulse, driven after filtering.'),
          ('HOLLOW','Hollow Box','Square-wave body with gentler saturation.'),
          ('REED','Reed Throat','Asymmetric edge with a nasal filter emphasis.'),
          ('WIRE','Wire Knock','FM bite that settles into a steady low note.'),
          ('FOLD','Fold Rasp','Smooth wavefolding over a protected fundamental.')]
assert (dist/'index.html').is_file()
if not (dist/'approved.html').exists():
    shutil.copy2(dist/'index.html',dist/'approved.html')
for mode in ['finite','held','chord']:
    with zipfile.ZipFile(dist/f'bristle-six-{mode}-wav.zip','w',zipfile.ZIP_DEFLATED) as z:
        for voice,_,_ in voices:
            wav=root/'previews'/f'{voice}_{mode}.wav'
            assert wav.is_file(),wav
            z.write(wav,wav.name)
            subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-i',str(wav),'-ac','1','-b:a','96k',str(dist/f'{voice}_{mode}.mp3')],check=True)
with zipfile.ZipFile(dist/'bristle-starter-mpc.zip','w',zipfile.ZIP_DEFLATED) as z:
    for p in (root/'Starter MPC').rglob('*'):
        if p.is_file(): z.write(p,p.relative_to(root/'Starter MPC'))
cards=[]
for voice,title,description in voices:
    clips=''.join(f'<div class="clip"><h3>{label}</h3><audio controls preload="metadata" src="{voice}_{mode}.mp3"></audio><a href="{voice}_{mode}.mp3" download>Download MP3</a></div>'
                  for mode,label in [('finite','Finite notes'),('held','Sustain and release'),('chord','Chord test')])
    cards.append(f'<section><span class="tag">{voice}</span><h2>{title}</h2><p>{description}</p>{clips}</section>')
page='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Bristle · six new bass voices</title><style>
*{box-sizing:border-box}body{margin:0;background:#17191d;color:#f3efe6;font:16px/1.5 system-ui,sans-serif}
main{max-width:1050px;margin:auto;padding:36px 20px}h1{font-size:clamp(30px,6vw,52px);line-height:1.1}h2{font-size:24px;margin:8px 0}h3{font-size:16px;margin-bottom:8px}
p{max-width:780px;color:#c8c9ce}.grid{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin:30px 0}
section{background:#23262d;border:1px solid #42454e;border-radius:14px;padding:22px}audio{width:100%;height:42px}.tag{color:#ffb661;font-size:13px;letter-spacing:.1em}.clip{margin:24px 0}a{color:#ffbf78}.clip a{font-size:14px}nav{display:flex;gap:14px;flex-wrap:wrap}.downloads{padding:20px;border:1px solid #42454e;border-radius:14px}
@media(max-width:650px){.grid{grid-template-columns:1fr}main{padding:20px 14px}}
</style><main><span class="tag">SNIPSNAP · BRISTLE</span><h1>Six new bass voices.</h1>
<p>These are new candidates for your listening judgment. Your approved A/B basses are unchanged.</p>
<nav><a href="approved.html">Approved A/B comparisons</a><a href="bristle-starter-mpc.zip" download>Download the 16-pad MPC starter</a></nav>
<p>Finite and sustain clips play soft, medium and hard touch, two alternating takes each.
Sustain notes hold for two seconds before release. Chord clips play a C-minor add-nine voicing for three seconds.
All clips use the actual exported samples through SnipSnap’s reference sampler.</p>
<div class="grid">'''+''.join(cards)+'''</div>
<div class="downloads"><h2>Lossless listening downloads</h2><nav><a href="bristle-six-finite-wav.zip" download>Finite WAVs</a><a href="bristle-six-held-wav.zip" download>Sustain WAVs</a><a href="bristle-six-chord-wav.zip" download>Chord WAVs</a></nav></div>
<p>The starter has an eight-note bass walk, the six new voices and both approved routes.
The phone starter carries three touch levels and two takes. In the download, the main .xtd and its .xpm twin use three touch levels with first takes. The separate Soft, Medium and Hard programs under Cycling Programs alternate two WAVs at one fixed touch.</p>
<p>Keygroup instruments still use separate soft, medium and hard MPC cycling programs.
Combined timbral velocity switching and round robin in one MPC keygroup remains an open limitation.
The new voices are not covered by the earlier device approval.</p>
</main><script>document.addEventListener('play',e=>{if(e.target.tagName==='AUDIO')document.querySelectorAll('audio').forEach(a=>{if(a!==e.target)a.pause()})},true)</script></html>'''
if (dist/'bristle-source-and-checks.zip').is_file():
    page=page.replace('</nav>', '<a href="bristle-source-and-checks.zip" download>Source patch and verification</a></nav>', 1)
(dist/'index.html').write_text(page)
print('Prepared six-voice listening page with 18 clips, three WAV packs and the MPC starter.')
