"""Freeze Python Float32 test oracles; these are test resources, never runtime sources."""
import gzip
import hashlib
import json
from pathlib import Path
import numpy as np
from model import ROOT, render
m=json.loads(Path(__file__).with_name('model.json').read_text())
root=ROOT/'synth/src/test/resources/spore-kick-approved';root.mkdir(exist_ok=True)
cases=[dict(id=f'z{z}-t{t}',velocity=v,take=t) for z,v in enumerate([.3,.65,1.]) for t in range(3)]
cases += [dict(id=f'{key}-{v}',**{key:v}) for key in ['tune','strike','body','click'] for v in [0.,1.]]
for c in cases:
    x=render(m,**{k:v for k,v in c.items() if k!='id'}).astype('<f4').tobytes()
    (root/(c['id']+'.f32.gz')).write_bytes(gzip.compress(x,mtime=0))
    c['sha256']=hashlib.sha256(x).hexdigest()
(root/'manifest.json').write_text(json.dumps(dict(ownerApproval='2026-11-14: Sounds good, proceed',
    modelSha256=hashlib.sha256(Path(__file__).with_name('model.json').read_bytes()).hexdigest(),
    frames=19845,cases=cases),indent=2)+'\n')
print('Frozen',len(cases),'Python waveform oracles')
