#!/usr/bin/env python3
"""Freeze the approved Sunroom R10 WAVs without rerendering or changing PCM."""
from pathlib import Path
import sys,json,hashlib,gzip,re,subprocess,wave,shutil
site=Path(sys.argv[1]);root=Path(__file__).resolve().parents[1];out=root/'synth/src/main/resources/sunroom/v1';out.mkdir(parents=True,exist_ok=True)
commit='290b9a2f6a2057f33e1664e07af93269836ae5f9'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=site).decode().strip()==commit,'Expected approved R10 site commit'
m=json.loads((site/'dist/ensembles/manifest.json').read_text());labels=json.loads(re.search(r'const labels=(.*);', (site/'dist/ensemble-player.js').read_text()).group(1));entries=[]
for e in m['samples']:
 data=(site/'dist/ensembles'/e['file']).read_bytes();assert hashlib.sha256(data).hexdigest()==e['sha256'];name=f"{e['kind']}/{e['slot']}-{e['zone']}-{e['take']}.wav.gz";p=out/name;p.parent.mkdir(exist_ok=True);p.write_bytes(gzip.compress(data,compresslevel=9,mtime=0))
 with wave.open(str(site/'dist/ensembles'/e['file'])) as w:frames=w.getnframes();assert w.getnchannels()==1 and w.getframerate()==44100 and w.getsampwidth()==2
 entries.append(dict(kind=e['kind'],slot=e['slot'],voice=e['voice'],zone=e['zone'],take=e['take'],file=name,sha256=e['sha256'],frames=frames))
manifest={'version':1,'siteCommit':commit,'audition':'https://sunroom-drums.bertcalm.chatgpt.site/ensembles.html','approval':'Full kit approved 2026-11-18: Sounds much better, continue','roles':m['roles'],'labels':labels,'samples':entries}
(out/'manifest.json').write_text(json.dumps(manifest,separators=(',',':')))
credits=['NOTICE.txt','DRUM-REFERENCE-LICENSE.txt','TAMBOURINE-LICENSE.txt','percussion/CREDITS.txt','percussion/FREEPATS-LICENSE.txt','percussion/VSCO-LICENSE.txt','acoustic/CREDITS.txt','acoustic/swirly-LICENSE.txt','acoustic/vcsl-LICENSE.txt']
(out/'NOTICE.txt').write_text('Sunroom factory bank v1: printed synthesis and CC0 reference-derived resynthesis. Frozen R10 WAVs preserve the listening-approved PCM. Historical credits below describe earlier stages; the current bank is now integrated. No commercial song audio is included.\n\n'+'\n\n'.join('SOURCE: '+f+'\n'+(site/'dist'/f).read_text() for f in credits))
for name,path in [('percussion-provenance.json','percussion/provenance.json'),('acoustic-provenance.json','acoustic/provenance.json'),('brush-provenance.json','models/brush-r9-provenance.json')]:shutil.copyfile(site/'dist'/path,out/name)
print('Imported',len(entries),'unchanged WAVs')
