#!/usr/bin/env python3
"""Independently check cache integers, then compare the JS reader to all Python cases."""
import gzip
import json
from pathlib import Path
import subprocess
import sys
import numpy as np
import playable as b

root=b.ROOT;out=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else root/'testkit/crease-playable-release';raw=out/'raw'
m=json.loads((out/'manifest.json').read_text());meta=json.loads((raw/'capture.json').read_text())
payload=(out/m['container']['file']).read_bytes()
decoded=np.frombuffer(gzip.decompress(payload),dtype=np.uint8).reshape(2,-1).T.copy()
pcm=np.frombuffer(decoded.tobytes(),dtype='<i2').astype(np.int64)
for _ in range(m['container']['deltaOrder']): pcm=np.cumsum(pcm,dtype=np.int64)
pcm=pcm.astype('<i2')
b.p.require(b.sha(pcm.tobytes())==m['container']['pcmSha256'],'container PCM hash')
gain=m['commonGain'];all_ids={c:i for i,c in enumerate(meta['bankCuts'])}
original=[np.fromfile(raw/'held.f32',dtype='<f4')]
original.extend(np.fromfile(raw/f'bank-{all_ids[c]:04d}.f32',dtype='<f4') for c in m['tailCuts'])
expected=np.concatenate([np.rint(x.astype(np.float64)*gain*32767).astype('<i2') for x in original])
b.p.require(np.array_equal(expected,pcm),'container versus separately read circuit samples')
held=pcm[:m['heldFrames']].astype(np.float64)/32767
banks={cut:pcm[m['heldFrames']+i*m['tailFrames']:m['heldFrames']+(i+1)*m['tailFrames']].astype(np.float64)/32767
       for i,cut in enumerate(m['tailCuts'])}
for t in [.1,.25,.5,.9]:
    x=np.full(20,.25)
    b.p.require(abs(b.cubic(x,np.array([8+t]))[0]-.25)<1e-12,'constant interpolation gain')
    x=np.arange(20,dtype=np.float64)
    b.p.require(abs(b.cubic(x,np.array([8+t]))[0]-(8+t))<1e-12,'linear interpolation')
fade=b.fade_curve();fixture=raw/'reader-fixtures.f32'
with fixture.open('wb') as f:
    for cut in meta['testCuts']:
        samples=b.approximation(cut,m['tailCuts'],banks,held,meta)*fade*.12
        f.write(samples.astype('<f4').tobytes())
b.write(raw/'reader-fixtures.json',{'cuts':meta['testCuts'],'frames':len(fade),
    'sha256':b.sha(fixture.read_bytes()),'scope':'Python cached-transport reference, not a new circuit render'})
cmd=['node',str(Path(__file__).with_name('verify-reader.mjs')),str(out)]
result=subprocess.run(cmd,cwd=root)
raise SystemExit(result.returncode)
