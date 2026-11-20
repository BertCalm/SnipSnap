"""Build bounded per-kit packages for Sites' 32 MiB individual-file limit."""
import json,sys,zipfile
from pathlib import Path
p=Path(sys.argv[1]);m=json.loads((p/'manifest.json').read_text())
models=m.get('models',{'angular':'synthesis-model.json','tight-pocket':'tight-pocket-model.json','open-road':'open-road-model.json'})
for kit in m['modes']:
 selected={**m,'modes':[kit],'models':{kit:models[kit]},'files':{k:v for k,v in m['files'].items() if k.startswith(kit+'/')}}
 archive=p/(kit+'.zip')
 with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
  for f in sorted((p/kit).rglob('*')):
   if f.is_file():z.write(f,f.relative_to(p))
  for name in ['CC0.txt','NOTICE.txt','provenance.json',models[kit]]:z.write(p/name,name)
  z.writestr('manifest.json',json.dumps(selected,indent=2))
 assert archive.stat().st_size<32*1024*1024
 with zipfile.ZipFile(archive) as z:
  assert json.loads(z.read('manifest.json'))==selected
  for rel in selected['files'].values():assert z.read(rel)==(p/rel).read_bytes()
 print(kit,archive.stat().st_size,'bytes; full sample byte equality verified')
