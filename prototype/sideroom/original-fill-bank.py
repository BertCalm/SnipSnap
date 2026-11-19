"""Promote the approved original audition fill; preserve medium/take 1 exactly."""
from pathlib import Path
import sys,gzip,hashlib,json
import numpy as np
from scipy.io import wavfile
src,out=map(Path,sys.argv[1:3]);out.mkdir(parents=True,exist_ok=True)
kits=['open-road','tight-pocket','angular','high-voltage','deep-pocket'];rows=['file,sha256'];report={}
for kit in kits:
 sr,original=wavfile.read(src/kit/'fill.wav');assert sr==44100 and original.dtype==np.int16
 bank={}
 for v in ['snare','tom','floor','kick','crash']:
  for z in range(3):
   for t in range(3):bank[v,z,t]=wavfile.read(src/kit/f'{v}-{z}-{t}.wav')[1].astype(float)/32768
 def mix(z,t):
  events=[(i*.15,v,(1 if i%2 else 2) if z==1 else (0 if z==0 else 2),.8) for i,v in enumerate(['snare','snare','tom','tom','floor','floor','snare','floor'])]
  events += [(1.2,'kick',0 if z==0 else 2,.9),(1.2,'crash',0 if z==0 else 2,.7)]
  x=np.zeros(len(original));counts={v:t for v in ['snare','tom','floor','kick','crash']}
  for tm,v,l,g in sorted(events):
   a=bank[v,l,counts[v]%3]*g;counts[v]+=1;at=round(tm*sr);n=min(len(a),len(x)-at);x[at:at+n]+=a[:n]
  x[-2205:]*=np.linspace(1,0,2205);return x
 base=mix(1,0);ref=original.astype(float)/32768;gain=float(np.dot(base,ref)/np.dot(base,base))
 hard=[mix(2,t) for t in range(3)];hg=min(gain*1.1,.96/max(np.max(abs(x)) for x in hard))
 energies=[]
 for z in range(3):
  ee=[]
  for t in range(3):
   name=f'{kit}/fill-{z}-{t}.wav';p=out/name;p.parent.mkdir(exist_ok=True)
   if z==1 and t==0:raw=(src/kit/'fill.wav').read_bytes();a=ref
   else:
    a=mix(z,t)*([gain*.8,gain,hg][z]);assert np.max(abs(a))<1
    wavfile.write(p,sr,np.rint(a*32767).astype(np.int16));raw=p.read_bytes();p.unlink()
   (out/(name+'.gz')).write_bytes(gzip.compress(raw,mtime=0));rows.append(name+','+hashlib.sha256(raw).hexdigest());ee.append(float(np.dot(a,a)))
  energies.append(float(np.mean(ee)))
 assert energies[0]<energies[1]<energies[2],(kit,energies)
 report[kit]={'energy_by_layer':energies,'medium_take_1_sha256':hashlib.sha256((src/kit/'fill.wav').read_bytes()).hexdigest(),'tempo':100}
(out/'index.csv').write_text('\n'.join(rows)+'\n');(out/'verification.json').write_text(json.dumps(report,indent=2))
print('45 performances; five original medium/take-1 files preserved byte-for-byte; dynamics ordered')
