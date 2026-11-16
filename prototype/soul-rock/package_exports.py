"""Package verified MPC exports into independently extractable upload-sized ZIP parts.
Extract every part for one kit into the same destination folder.
"""
from pathlib import Path
import argparse,hashlib,json,zipfile,zlib
p=argparse.ArgumentParser();p.add_argument('root',type=Path);a=p.parse_args();out=a.root/'downloads';out.mkdir(exist_ok=True)
report=[]
for folder in sorted(a.root.iterdir()):
 source=folder/'mpc'
 if not source.is_dir():continue
 files=sorted(f for f in source.rglob('*') if f.is_file());groups=[];group=[];size=0
 for f in files:
  estimate=len(zlib.compress(f.read_bytes()))+len(str(f.relative_to(source)))*2+200
  if group and size+estimate>9*1024**2:groups.append(group);group=[];size=0
  assert estimate<10*1024**2
  group.append(f);size+=estimate
 if group:groups.append(group)
 for i,group in enumerate(groups,1):
  path=out/f'{folder.name}-part-{i:02d}.zip'
  with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as z:
   for f in group:z.write(f,str(Path(folder.name)/f.relative_to(source)))
  assert path.stat().st_size<=10*1024**2
  with zipfile.ZipFile(path) as z:
   assert z.testzip() is None
   for f in group:assert z.read(str(Path(folder.name)/f.relative_to(source)))==f.read_bytes()
  report.append({'kit':folder.name,'part':i,'parts':len(groups),'file':path.name,'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
(out/'manifest.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
