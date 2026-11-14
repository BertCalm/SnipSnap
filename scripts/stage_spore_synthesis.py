#!/usr/bin/env python3
"""Add the separate synthesis prototype to the existing Spore Site; preserve its acoustic assets."""
import argparse,json,shutil
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--prototype-output',type=Path,required=True);p.add_argument('--site-root',type=Path,required=True);a=p.parse_args()
assert (a.site_root/'.openai/hosting.json').is_file()
assert json.loads((a.prototype_output/'manifest.json').read_text())['status']=='two-voice-prototype-awaiting-listen'
dist=a.site_root/'dist';target=dist/'synthesis';staged=dist/'synthesis-next';assert not staged.exists();staged.mkdir()
shutil.copytree(a.prototype_output/'audio',staged/'audio')
for name in ['index.html','manifest.json','validation.json','synthesis-spec.md','spore-synthesis-prototype.zip','REFERENCE-NOTICE.txt','REFERENCE-LICENSE.txt']:
    shutil.copy2(a.prototype_output/name,staged/name)
assert all(f.stat().st_size<25*1024*1024 for f in staged.rglob('*') if f.is_file())
if target.exists():
    assert (target/'manifest.json').is_file()
    shutil.rmtree(target)
staged.rename(target)
index=dist/'index.html';html=index.read_text()
if 'id="synthesis-prototype-link"' not in html:
    html=html.replace('</nav>','</nav><section id="synthesis-prototype-link"><h2>New: sample-free kick and snare</h2><p class="note">Compare the new coupled-head and wire-contact synthesis with the acoustic reference and earlier synthesis.</p><a href="synthesis/">Open the synthesis prototype →</a></section>',1)
index.write_text(html)
print('Staged synthesis/ and added the prototype link; acoustic assets retained.')
