"""Runtime isolation, deterministic variation and serialized-parameter checks."""
import json,sys
from pathlib import Path
import numpy as np
import resynth
p=Path(sys.argv[1]);model=json.loads((p/'synthesis-model.json').read_text())
def forbidden(*args,**kwargs):raise AssertionError('Runtime attempted to read an audio file')
resynth.wavfile.read=forbidden
report={}
for voice in resynth.VOICES:
 levels=[]
 for level in range(3):
  hits=[resynth.render(model,voice,level,rr)[0] for rr in range(3)]
  assert np.array_equal(hits[0],resynth.render(model,voice,level,0)[0])
  assert all(np.isfinite(x).all() and np.max(abs(x))<.99 for x in hits)
  errors=[]
  for a,b in ((0,1),(0,2),(1,2)):
   x,y=hits[a],hits[b];errors.append(float(np.linalg.norm(x/np.linalg.norm(x)-y/np.linalg.norm(y))))
  assert min(errors)>.05,(voice,level,errors)
  levels.append(float(np.mean([np.sum(x*x) for x in hits])))
  report[f'{voice}/{level}']={'minimum_normalized_take_distance':min(errors)}
 assert levels[0]<levels[1]<levels[2]
(p/'runtime-checks.json').write_text(json.dumps(report,indent=2));print('PASS: render reads no audio, deterministic output, ordered energy, alternate-strike waveform differences')
