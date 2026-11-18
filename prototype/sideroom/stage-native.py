"""Stage the native phrase audition in a NEW output directory.
Usage: stage-native.py NATIVE_DELIVERY R9_GROOVES NEW_SITE_DIST
The existing site can then replace dist with this validated output.
"""
from pathlib import Path
import shutil,sys
source,grooves,out=map(Path,sys.argv[1:4])
if out.exists():raise SystemExit('Output must be a new directory; existing files are not replaced.')
kits=['deep-pocket','high-voltage','angular','tight-pocket','open-road']
required=[source/n for n in ['delivery-checks.json','verification.csv']]
for kit in kits:
 required.append(source/(kit+'-native.zip'))
 required.extend(source/kit/f'{role}-{z}-{t}.wav' for role in ['roll','drumfill'] for z in range(3) for t in range(3))
 required.extend(grooves/kit/(name+'.wav') for name in ['groove','pocket','fill'])
assert all(p.is_file() for p in required),'Missing native delivery or comparison groove'
assert all(p.stat().st_size<=25*1024**2 for p in required),'File exceeds host limit'
assert sum(p.stat().st_size for p in required)<250*1024**2,'Deployment exceeds budget'
out.mkdir(parents=True)
for p in required:
 root=source if p.is_relative_to(source) else grooves
 dest=out/p.relative_to(root);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy(p,dest)
for name,target in [('native.html','index.html'),('native.js','native.js')]:shutil.copy(Path(__file__).with_name(name),out/target)
print(out)
