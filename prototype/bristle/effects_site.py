#!/usr/bin/env python3
"""Build optional bass-effects comparisons inside the existing private Site."""
from pathlib import Path
import subprocess,sys,zipfile,re
root,dist=map(Path,sys.argv[1:3])
voices=[('A','Filtered Edge'),('B','Driven Edge'),('ROUND','Round Floor'),('PULSE','Pulse Bite'),('HOLLOW','Hollow Box'),('REED','Reed Throat'),('WIRE','Wire Knock'),('FOLD','Fold Rasp')]
modes=[('dry','Dry'),('comp','COMP'),('fuzz','FUZZ'),('both','Both'),('held_both','Both · held')]
with zipfile.ZipFile(dist/'bristle-effects-wav.zip','w',zipfile.ZIP_DEFLATED) as z:
 for voice,_ in voices:
  for mode,_ in modes:
   wav=root/'previews'/f'{voice}_{mode}.wav'
   assert wav.is_file(),wav
   z.write(wav,wav.name)
   subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-i',str(wav),'-ac','1','-b:a','96k',str(dist/f'fx_{voice}_{mode}.mp3')],check=True)
with zipfile.ZipFile(dist/'bristle-effects-recipes.zip','w',zipfile.ZIP_DEFLATED) as z:
 for f in (root/'recipes').glob('*.json'):z.write(f,f.name)
 z.write(root/'phrase.txt','phrase.txt')
cards=[]
for voice,title in voices:
 buttons=''.join(f'<button type="button" data-mode="{m}" aria-pressed="{str(m=="dry").lower()}">{label}</button>' for m,label in modes)
 cards.append(f'<section data-voice="{voice}"><span class="tag">{voice}</span><h2>{title}</h2><div class="modes" role="group" aria-label="{title} comparison mode">{buttons}</div><p class="setting">Dry · both controls off</p><audio controls preload="metadata" aria-label="{title} comparison" src="fx_{voice}_dry.mp3"></audio><a class="download" href="fx_{voice}_dry.mp3" download>Download selected MP3</a></section>')
page='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Bristle · compression and fuzz</title><style>
*{box-sizing:border-box}body{margin:0;background:#17191d;color:#f3efe6;font:16px/1.5 system-ui,sans-serif}main{max-width:1050px;margin:auto;padding:36px 20px}h1{font-size:clamp(30px,6vw,52px);line-height:1.1}h2{font-size:24px;margin:8px 0}p{max-width:780px;color:#c8c9ce}.grid{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin:30px 0}section{background:#23262d;border:1px solid #42454e;border-radius:14px;padding:22px}audio{width:100%;height:42px}.tag{color:#ffb661;font-size:13px;letter-spacing:.1em}a{color:#ffbf78}.download{display:inline-block;font-size:14px;margin-top:12px}nav,.modes{display:flex;gap:10px;flex-wrap:wrap}.modes{margin:20px 0}button{font:inherit;border:1px solid #696c74;border-radius:7px;background:#17191d;color:#f3efe6;padding:8px 12px;cursor:pointer}button[aria-pressed=true]{background:#ffb661;color:#17191d;border-color:#ffb661}button:focus-visible,a:focus-visible{outline:3px solid #fff;outline-offset:3px}.setting{min-height:48px;font-size:14px}
@media(max-width:650px){.grid{grid-template-columns:1fr}main{padding:20px 14px}}
</style><main><span class="tag">SNIPSNAP · BRISTLE</span><h1>Compression and fuzz.</h1><p>All eight dry sounds are retained. These two optional controls start at zero, so the approved sounds stay intact.</p>
<p>COMP lifts the body of a note. FUZZ adds a harder edge while the clean sub stays outside its distortion path. The enabled controls are set to 70% here.</p>
<p>Each finite comparison plays the same four notes and touch levels. Choose “Both · held” to hear the lowest note sustain for three seconds, then release.</p>
<nav><a href="index.html">Retained six-voice bank</a><a href="approved.html">Original A/B comparisons</a><a href="bristle-effects-wav.zip" download>All effects clips · WAV</a><a href="bristle-effects-recipes.zip" download>Effect recipes</a></nav>
<div class="grid">'''+''.join(cards)+'''</div><p>The new treatments are listening candidates. These clips have no additional audition gain. The held comparisons play the actual exported samples through SnipSnap’s reference sampler.</p></main>
<script>
const descriptions={dry:'Dry · both controls off',comp:'COMP 70% · FUZZ off',fuzz:'COMP off · FUZZ 70%',both:'COMP 70% · FUZZ 70%',held_both:'COMP 70% · FUZZ 70% · three-second held note and release'};
document.querySelectorAll('section').forEach(card=>{const player=card.querySelector('audio');card.querySelectorAll('button').forEach(button=>button.addEventListener('click',()=>{const mode=button.dataset.mode;const src='fx_'+card.dataset.voice+'_'+mode+'.mp3';player.pause();player.src=src;card.querySelector('.download').href=src;card.querySelector('.setting').textContent=descriptions[mode];card.querySelectorAll('button').forEach(b=>b.setAttribute('aria-pressed',String(b===button)));player.load();player.play().catch(()=>{});}));});
document.addEventListener('play',event=>{if(event.target.tagName==='AUDIO')document.querySelectorAll('audio').forEach(a=>{if(a!==event.target)a.pause();});},true);
</script></html>'''
(dist/'effects.html').write_text(page)
index=(dist/'index.html').read_text()
index=index.replace('These are new candidates for your listening judgment. Your approved A/B basses are unchanged.','You chose to keep all six voices. Your approved A/B basses are unchanged.')
index=index.replace('<h1>Six new bass voices.</h1>','<h1>Six retained bass voices.</h1>')
index=index.replace('<title>Bristle · six new bass voices</title>','<title>Bristle · retained bass voices</title>')
if 'href="effects.html"' not in index:index=index.replace('<nav>','<nav><a href="effects.html">Hear compression and fuzz</a>',1)
(dist/'index.html').write_text(index)
for name in ['index.html','effects.html','approved.html']:
 for asset in re.findall(r'(?:src|href)="([^"]+)"',(dist/name).read_text()):assert(dist/asset).is_file(),asset
print('Built eight effects comparison cards with forty MP3s and lossless downloads.')
