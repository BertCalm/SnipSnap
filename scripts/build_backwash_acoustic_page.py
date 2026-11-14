"""Publish the acoustic audition within the existing static listening page."""
from pathlib import Path
from html.parser import HTMLParser
from urllib.parse import urlsplit,unquote
import hashlib,json,re,shutil,sys
src,dist=map(Path,sys.argv[1:3]);manifest=json.loads((src/'manifest.json').read_text())
for r in manifest['files']:assert hashlib.sha256((src/r['file']).read_bytes()).hexdigest()==r['sha256']
assert len(manifest['files'])==13
assets=dist/'acoustic-sources-1';shutil.copytree(src,assets,dirs_exist_ok=True)
section='''<section id="new-percussion"><h2>Real acoustic recordings. No added effects.</h2><p>Cross-stick, cowbell, tambourine and ride bell from openly licensed recording libraries. Start with the single strikes, then hear the dynamics and repeated alternate takes. The recordings retain their natural room sound.</p><p class="hint">No chorus, compressor, distortion or added reverb. One fixed gain per instrument preserves differences between its recorded strikes; instrument-to-instrument loudness is not matched. Full recorded tails are retained.</p><div class="grid">'''
items=[('cross-stick','Cross-stick','Virtuosity Drums · 16 recorded dynamics, grouped here into four dynamic bands with four distinct strikes each. Adjacent velocity recordings supply the alternates; this is an audition mapping.'),('cowbell','Cowbell','Virtuosity Drums · three velocity layers × seven recorded alternate takes.'),('tambourine','Tambourine','VSCO 2 CE · two hit velocity layers × two recorded alternate takes, plus a separately recorded roll.'),('ride-bell','Ride bell','Virtuosity Drums · three velocity layers × three recorded alternate takes. Let the full tail play.')]
for voice,title,desc in items:
 section+=f'<article><h3>{title}</h3><p class="hint">{desc}</p>'
 for kind,label in [('single','Single firm strike'),('dynamics','Recorded dynamics · soft to hard'),('alternates','Eight repeated strikes · cycling real takes')]:
  section+=f'<p>{label}</p><audio controls preload="none" aria-label="{title}: {label}" src="acoustic-sources-1/{voice}-{kind}.flac"></audio>'
 section+='</article>'
section+='''</div><h3>Tambourine · recorded roll</h3><audio controls preload="none" aria-label="Tambourine recorded roll" src="acoustic-sources-1/tambourine-recorded-roll.flac"></audio><p>Source-selection audition only. These recordings have not yet replaced the synth sounds in the Android preview or MPC kits.</p><details><summary>Sources, licenses and completion gates</summary><p>Virtuosity Drums / Versilian Studios, drum performances by Austin McMahon. VSCO 2 Community Edition: Sam Gossner, Simon Dalzell and Elan Hickler / Soundemote. Both libraries provide CC0 licenses permitting adaptation and redistribution.</p><p><a href="https://github.com/sfzinstruments/virtuosity_drums">Virtuosity source</a> · <a href="https://github.com/sgossner/VSCO-2-CE">VSCO source</a> · <a href="acoustic-sources-1/CREDITS.txt">Credits and changes</a> · <a href="acoustic-sources-1/VIRTUOSITY-LICENSE.txt">Virtuosity license</a> · <a href="acoustic-sources-1/VSCO-LICENSE.txt">VSCO license</a> · <a href="acoustic-sources-1/manifest.json">Source hashes and audition mapping</a></p><p>Recorded velocities and alternate takes are demonstrated here. Rendering, saving, playback and export in SnipSnap still need end-to-end verification. BACKWASH also needs multiple distinct genre kits and appropriate authored roll/riser coverage before it meets the engine gates.</p></details></section>'''
p=dist/'index.html';s=p.read_text();s,n=re.subn(r'<section id="new-percussion">.*?</section>',lambda _:section,s,count=1,flags=re.S);assert n==1;p.write_text(s)
class Check(HTMLParser):
 def handle_starttag(self,t,a):
  for k,v in a:
   if k in ('src','href') or t=='option' and k=='value':
    u=urlsplit(v)
    if not u.scheme and u.path:assert (dist/unquote(u.path)).is_file(),v
Check().feed(s)
print('PASS: 13 asset hashes and all audition page links.')
