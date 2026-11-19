"""Stage the native audition and cycling downloads in a NEW directory.
Usage: stage-native.py NATIVE_DELIVERY R9_GROOVES NEW_SITE_DIST CYCLE_DELIVERY
"""
from pathlib import Path
import shutil,sys
source,grooves,out,cycles=map(Path,sys.argv[1:5])
if out.exists():raise SystemExit('Output must be a new directory; existing files are not replaced.')
kits=['deep-pocket','high-voltage','angular','tight-pocket','open-road']
files=[(source/n,Path(n)) for n in ['delivery-checks.json','verification.csv']]
files.append((cycles/'cycle-delivery-checks.json',Path('cycle-delivery-checks.json')))
for kit in kits:
 files.extend([(source/(kit+'-native.zip'),Path(kit+'-native.zip')),(cycles/(kit+'-cycles.zip'),Path(kit+'-cycles.zip'))])
 for role in ['roll','drumfill']:
  for z in range(3):
   for t in range(3):
    rel=Path(kit)/f'{role}-{z}-{t}.wav';files.append((source/rel,rel))
 for name in ['groove','pocket']:
  rel=Path(kit)/(name+'.wav');files.append((grooves/rel,rel))
assert all(p.is_file() for p,_ in files),'Missing native/cycling delivery or comparison groove'
assert all(p.stat().st_size<=25*1024**2 for p,_ in files),'File exceeds host limit'
assert sum(p.stat().st_size for p,_ in files)<250*1024**2,'Deployment exceeds budget'
out.mkdir(parents=True)
for p,rel in files:
 dest=out/rel;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy(p,dest)
for name,target in [('native.html','index.html'),('native.js','native.js')]:shutil.copy(Path(__file__).with_name(name),out/target)
print(out)
