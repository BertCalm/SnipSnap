"""Freeze the approved synthesized audition bank; gzip preserves exact WAV bytes."""
import json,gzip,hashlib,sys,shutil
from pathlib import Path
site=Path(sys.argv[1]);root=Path(__file__).resolve().parents[1];out=root/'synth/src/main/resources/knee-break/v1';out.mkdir(parents=True,exist_ok=True)
r4=json.loads((site/'round4/manifest.json').read_text());r5=json.loads((site/'round5/manifest.json').read_text());rows=[e for e in r4['samples'] if e['fx']!='knee']+r5;assert len(rows)==864
assets=[]
for e in rows:
 source=site/('round5' if e['fx']=='knee' else 'round4')/e['file'];data=source.read_bytes();assert hashlib.sha256(data).hexdigest()==e['sha256'];name=f"{e['kit']}/{e['fx']}/{e['slot']}-{e['zone']}-{e['take']}.wav.gz";p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(gzip.compress(data,mtime=0));assets.append({**e,'file':name})
manifest={'version':1,'audition':'https://knee-break-audition.bertcalm.chatgpt.site','siteCommit':'cba79a1894784fae4c487b172827f872955a5ce2','roles':r4['roles'],'samples':assets,'origin':'Frozen Python oscillator/noise renders, not acoustic source PCM; revised knee v2'}
(out/'manifest.json').write_text(json.dumps(manifest,separators=(',',':')))
for old,new in [('round4/LICENSE.txt','REFERENCE-LICENSE.txt'),('round4/NOTICE.txt','NOTICE.txt'),('round4/model.json','model.json')]:shutil.copyfile(site/old,out/new)
print('864 frozen synthesized WAVs:',sum(p.stat().st_size for p in out.rglob('*.gz')),'compressed bytes')
