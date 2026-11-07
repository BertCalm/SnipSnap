#!/usr/bin/env python3
"""Export only a locked, passing shared-cache experiment and its oracle fixtures."""
from pathlib import Path
import json
import shutil
import numpy as np
import shared_cache as s
p=s.p
source=p.ROOT/'testkit/crease-phone-release-r2'
out=p.ROOT/'testkit/crease-shared-cache-r1'
m=json.loads((source/'manifest.json').read_text())
d=json.loads((out/'development.json').read_text()); v=json.loads((out/'fresh-validation.json').read_text())
s.require(v['failed']==0 and d['selectedComponents'] is not None,'shared cache has not passed fresh validation')
locked=np.load(out/'locked-basis.npz');basis=locked['basis']; coefficients=locked['coefficients']; offsets=locked['offsets']
for key,name in [('basis','basisSha256'),('coefficients','coefficientsSha256'),('offsets','offsetsSha256')]:
 s.require(p.sha(locked[key].tobytes())==d[name],f'locked {key} differs')
pcm=np.fromfile(source/'raw/native-pcm.i16',dtype='<i2')[:m['heldFrames']]
held=pcm.astype(float)/32767
banks=s.reconstruct(m,basis,coefficients,offsets)
# Frame-major storage keeps each basis dot product contiguous in the callback.
flat=np.concatenate([pcm,basis.T.ravel()])
container=p.encode_cache(out,flat)
rank=len(basis); cuts=m['tailCuts']
# Include immutable coordinate/offset tables and the whole fixed-size native
# voice array conservatively. Actual sizeof(Reader) is checked by the host.
resident=flat.nbytes+coefficients.nbytes+offsets.nbytes+4*len(cuts)+8192
s.require(resident<=s.LIMITS['residentBytes'],'shared residency exceeds 8 MiB')
old=json.loads((source/'raw/capture.json').read_text())['testCuts']
fresh=json.loads((out/'fresh-positions.json').read_text())['cuts']
all_cuts=sorted(old+fresh)
s.require(len(set(all_cuts))==len(all_cuts),'fresh validation overlaps development')
selected=json.loads((out/f'development-{rank}.json').read_text())
accuracy=dict(total=len(all_cuts),passed=len(all_cuts),failed=0,development=len(old),holdouts=len(fresh),
 maxPeakError=max(selected['maxPeakError'],v['maxPeakError']),peakErrorLimit=.025)
m.update(format='crease-shared-pcm-v1',container=container,accuracy=accuracy,
 shared=dict(components=rank,frames=basis.shape[1],pad=s.PAD,offsets=offsets.tolist(),
             coefficients=coefficients.ravel().tolist(),coefficientsSha256=d['coefficientsSha256'],
             storage='frame-major',residentBytes=resident),
 cacheIdentity={**m['cacheIdentity'],'algorithm':'integer-phase-pcm-basis-v1',
                'baselinePcmSha256':d['baselinePcmSha256'],'basisSha256':d['basisSha256'],
                'coefficientsSha256':d['coefficientsSha256'],'offsetsSha256':d['offsetsSha256']})
report=dict(scope='Locked shared PCM16 basis versus retained and fresh actual Kotlin continuations',
 approximationPassed=True,thresholds=s.LIMITS,development=d,selected=selected,fresh=v,
 residentBytes=resident,compressedBytes=container['compressedBytes'])
raw=out/'raw';raw.mkdir(exist_ok=True)
flat.tofile(raw/'native-pcm.i16')
coefficients.tofile(raw/'shared-coefficients.f32')
p.write(raw/'capture.json',{'testCuts':all_cuts,'originalTestCuts':old,'freshHoldoutTestCuts':fresh})
# Oracle reconstructs offline, then uses the unchanged transport. Neither the
# native nor JS shipping reader expands the tails this way.
fade=p.fade_curve()
with (raw/'reader-fixtures.f32').open('wb') as f:
 for cut in all_cuts:
  f.write((p.approximation(cut,cuts,banks,held,m)*fade*.12).astype('<f4').tobytes())
p.write(raw/'reader-fixtures.json',dict(cuts=all_cuts,frames=len(fade),sha256=p.sha((raw/'reader-fixtures.f32').read_bytes()),scope='Offline reconstructed quantized-basis oracle'))
clips=[]
for clip in m['clips']:
 if clip['mode']!='cached':
  for name in ['wav','audio']:
   target=out/clip[name];target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source/clip[name],target)
  clips.append(clip);continue
 cut=round(clip['holdSeconds']*p.RATE)
 prefix=np.fromfile(source/'raw/held.f32',dtype='<f4')[:min(cut,p.RATE)].astype(float)*m['commonGain']
 if cut>p.RATE: prefix=p.cubic(held,np.arange(cut-p.RATE,cut),m['loopStart'])
 audio=np.concatenate([prefix,p.approximation(cut,cuts,banks,held,m)*fade,np.zeros(5292)])
 clips.append({**{k:clip[k] for k in ['id','holdSeconds','keyUpSeconds','mode']},**p.p.export_audio(out,clip['id'],audio)})
m['clips']=clips
p.write(out/'manifest.json',m);p.write(out/'report.json',report)
(out/'README.txt').write_text('Crease Reed Mark C4 shared-cache study. Accepted patch and held PCM unchanged.\n32 phase-aligned PCM16 basis waveforms, Float32 coefficients, no live circuit.\nSee report.json for old/fresh cases and unchanged accuracy limits.\nHost parity and emulator checks do not establish physical-device timing or listening preference.\n')
print(json.dumps(dict(cases=len(all_cuts),residentBytes=resident,pcmBytes=flat.nbytes,compressedBytes=container['compressedBytes'])),flush=True)
