"""Freeze approved procedural renders for the native cached-bank integration."""
from pathlib import Path
import gzip,hashlib,json,sys,shutil
src,dest=map(Path,sys.argv[1:3]);m=json.loads((src/'manifest.json').read_text());dest.mkdir(parents=True,exist_ok=True)
rows=['file,sha256'];models={}
for kit in m['modes']:
 for voice in m['voices']:
  for level in range(3):
   for take in range(3):
    name=f'{kit}/{voice}-{level}-{take}.wav';data=(src/name).read_bytes();p=dest/(name+'.gz');p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(gzip.compress(data,mtime=0));rows.append(name+','+hashlib.sha256(data).hexdigest())
 name=m['models'][kit];data=(src/name).read_bytes();p=dest/'models'/(kit+'.json.gz');p.parent.mkdir(exist_ok=True);p.write_bytes(gzip.compress(data,mtime=0));models[kit]=hashlib.sha256(data).hexdigest()
(dest/'index.csv').write_text('\n'.join(rows)+'\n');(dest/'models.json').write_text(json.dumps(models,indent=2))
for name in ('CC0.txt','NOTICE.txt','provenance.json'):shutil.copy(src/name,dest/name)
(dest/'NOTICE.txt').write_text((dest/'NOTICE.txt').read_text()+'Native SIDEROOM v1 contains approved synthesized PCM, not the acoustic source recordings. Models and their original provenance are retained. Kotlin replays the fixed bank and authors finite phrases; the Python spectral renderer remains the regeneration tool.\n')
print('Frozen',len(rows)-1,'approved synthesized performances')
