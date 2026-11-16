"""Preserve the owner-approved genre audition bytes in the native factory bank."""
import json,gzip,hashlib,sys,shutil
from pathlib import Path
site=Path(sys.argv[1]);root=Path(__file__).resolve().parents[1];out=root/'synth/src/main/resources/knee-break-genres/v1';out.mkdir(parents=True,exist_ok=True)
m=json.loads((site/'genres/manifest.json').read_text());rows=m['entries'];assert len(rows)==432
assets=[]
for e in rows:
 data=(site/'genres'/e['file']).read_bytes();assert hashlib.sha256(data).hexdigest()==e['sha256']
 name=f"{e['genre']}/{e['slot']}-{e['zone']}-{e['take']}.wav.gz";p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(gzip.compress(data,mtime=0));assets.append({**e,'file':name})
m={'version':1,'audition':'https://knee-break-audition.bertcalm.chatgpt.site/genres.html','siteCommit':'0f6a0aa941c41655635faa628d3cdadb0d526759','config':m['config'],'roles':[e['name'] for e in rows if e['genre']=='boom-bap' and e['zone']==0 and e['take']==0],'samples':assets}
(out/'manifest.json').write_text(json.dumps(m,separators=(',',':')))
shutil.copyfile(root/'synth/src/main/resources/knee-break/v1/REFERENCE-LICENSE.txt',out/'REFERENCE-LICENSE.txt')
(out/'NOTICE.txt').write_text('Knee Break genre bank v1. Owner-approved synthetic audition renders: boom-bap, trip-hop and funk breaks. Derived from the approved oscillator/noise bank with linear tuning, filtering and decay. Original CC0 reference attribution is in REFERENCE-LICENSE.txt. No user video PCM.\n')
print(len(assets),'assets frozen')
