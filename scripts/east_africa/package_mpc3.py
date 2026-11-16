"""Package unchanged MPC3 files in independently extractable ZIPs below Page's 10 MiB cap.
Extract every part for a kit into the same destination to reconstruct its complete folder.
"""
from pathlib import Path
import io,json,sys,zipfile,hashlib
OUT=Path(sys.argv[1] if len(sys.argv)>1 else 'testkit/east-africa-kits')
D=OUT/'downloads';D.mkdir(exist_ok=True)
report=[]
for k in ['bongo-flava','singeli','bongopiano','genge','kapuka']:
 root=OUT/k/'mpc3';groups=[[]];size=0
 for p in sorted(root.rglob('*')):
  if not p.is_file():continue
  b=io.BytesIO()
  with zipfile.ZipFile(b,'w',zipfile.ZIP_DEFLATED) as z:z.write(p,p.relative_to(root))
  cost=len(b.getvalue())
  assert cost<9*1024**2,p
  if size+cost>9*1024**2:groups.append([]);size=0
  groups[-1].append(p);size+=cost
 verified=set()
 for part,files in enumerate(groups,1):
  name=f'{k}-mpc3-part-{part}-of-{len(groups)}.zip';path=D/name
  with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as z:
   z.writestr(f'{k}/READ ME.txt',f'Extract ALL {len(groups)} ZIP parts for {k} into the SAME folder.\nLoad the .xtd beside its TrackData directory. Do not move the .xtd alone.\n22 pads, 3 velocity zones, 3 takes. Software PCM/metadata checked; MPC hardware acceptance remains open.\nFitted synthesis from scalar CC0 calibration; no recording playback.\n')
   for p in files:z.write(p,Path(k)/p.relative_to(root))
  with zipfile.ZipFile(path) as z:
   for p in files:
    assert z.read(str(Path(k)/p.relative_to(root)))==p.read_bytes();verified.add(p)
  assert path.stat().st_size<10*1024**2
  report.append(dict(kit=k,file=name,bytes=path.stat().st_size,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),files=len(files),roundtrip='byte-identical'))
 assert verified=={p for p in root.rglob('*') if p.is_file()}
(D/'manifest.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
