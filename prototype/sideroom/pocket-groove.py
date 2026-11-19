"""Author an original syncopated phrase across every kit for like-for-like comparison."""
from pathlib import Path
import json,sys
import numpy as np
from scipy.io import wavfile
import resynth
p=Path(sys.argv[1]);m=json.loads((p/'manifest.json').read_text());mixes={};beat=.6
for kit in m['modes']:
 bank={(v,l,r):resynth.read(p/kit/f'{v}-{l}-{r}.wav') for v in m['voices'] for l in range(3) for r in range(3)}
 events=[];counts={v:0 for v in m['voices']}
 for bar in range(4):
  base=4*bar
  for b in (0,.75,1.5,2.75,3.5):events.append(((base+b)*beat,'kick',2 if b in (0,2.75) else 1,.9))
  for b in (1,3):events.append(((base+b)*beat+.008,'snare',2,1.0))
  for b in (.5,1.75,2.5,3.75):events.append(((base+b)*beat+.012,'snare',0,.85))
  for i in range(16):
   v='openhat' if i==14 and bar%2 else 'hat';l=2 if i%4==0 or v=='openhat' else 1 if i%2==0 else 0
   events.append(((base+i*.25)*beat,v,l,.85))
 ordered=sorted(events);length=max(t+len(bank[v,l,0])/44100 for t,v,l,g in events);out=np.zeros(round((length+.02)*44100))
 for t,v,l,g in ordered:
  rr=counts[v]%3;counts[v]+=1;x=bank[v,l,rr].copy()*g
  if v=='openhat':
   stop=next((tm for tm,w,lv,gg in ordered if w=='hat' and tm>t),16*beat)
   x=x[:max(220,round((stop-t)*44100))];x[-220:]*=np.linspace(1,0,220)
  at=round(t*44100);out[at:at+len(x)]+=x
 out[-882:]*=np.linspace(1,0,882);mixes[kit]=out
scale=.85/max(np.max(abs(x)) for x in mixes.values());length=max(map(len,mixes.values()))
for kit,x in mixes.items():wavfile.write(p/kit/'pocket.wav',44100,np.rint(np.pad(x,(0,length-len(x)))*scale*32767).astype(np.int16))
print('Five-kit pocket phrase:',len(mixes),'kits; identical authored events and shared gain')
