"""Freeze Study 04's finished 3x4 grids for the CUTTINGS native sample bank.
Run after rendering prototype/cuttings/cuttings_proto.py into an output directory.
The version check prevents silently replacing accepted audio with a future study.
"""
import hashlib,json,shutil,sys
from pathlib import Path
src=Path(sys.argv[1]);root=Path(__file__).resolve().parents[1]
m=json.loads((src/'manifest.json').read_text())
assert m['version']=='cuttings-r4-sampler-color-1'
selected=[e for e in m['entries'] if e['stage']=='finished']
assert len(selected)==192
assert len({(e['voice'],e['dynamic'],e['take']) for e in selected})==192
dst=root/'synth/src/main/resources/cuttings/v1';dst.mkdir(parents=True,exist_ok=True)
rows=[]
for e in selected:
 f=src/e['file'];name=f.name
 shutil.copyfile(f,dst/name)
 rows.append({**{k:e[k] for k in ('voice','dynamic','take','frames','pcm_sha256')},'file':name,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()})
(dst/'manifest.json').write_text(json.dumps({'version':1,'sourceVersion':m['version'],'voices':m['voices'],'names':m['names'],'entries':rows},indent=2)+'\n')
print('Frozen 192 approved Study 04 performances.')
