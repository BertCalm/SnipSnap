"""Numerical, file and SFZ mapping checks; not sampler/device playback evidence."""
import hashlib,json,re,sys,wave,zipfile
from pathlib import Path
import numpy as np
from scipy.signal import welch
root=Path(sys.argv[1]);m=json.loads((root/'manifest.json').read_text());features={}
def read(p):
 with wave.open(str(p),'rb') as w:
  assert w.getnchannels()==1 and w.getframerate()==44100 and w.getsampwidth()==2
  b=w.readframes(w.getnframes())
 return np.frombuffer(b,'<i2').astype(float)/32767,b
for k in m['kits']:
 p=root/k['id'];regions=[dict(re.findall(r'(\w+)=([^\s]+)',s)) for s in (p/'sideroom.sfz').read_text().splitlines() if s.startswith('<region>')]
 assert len(regions)==120
 with zipfile.ZipFile(root/(k['id']+'.zip')) as z:
  assert z.testzip() is None
  for voice in m['voices']:
   levels=[]
   for d in range(3):
    waves=[]
    for t in range(4):
     e=next(e for e in k['entries'] if (e['voice'],e['dynamic'],e['take'])==(voice,d,t));x,b=read(root/e['file'])
     assert len(x)==e['frames'] and hashlib.sha256(b).hexdigest()==e['sha256pcm']
     assert z.read('audio/'+Path(e['file']).name)==(root/e['file']).read_bytes()
     assert np.isfinite(x).all() and max(abs(x))<.95 and abs(np.mean(x))<.003 and x[0]==x[-1]==0
     waves.append(x)
    levels.append(np.mean([np.sqrt(np.mean(x*x)) for x in waves]))
    for t in range(1,4):
     a=waves[0]/np.linalg.norm(waves[0]);b=waves[t]/np.linalg.norm(waves[t]);assert np.linalg.norm(a-b)>.01,(k['id'],voice,d,t)
   assert levels[0]<levels[1]<levels[2],(voice,levels)
   note=36+list(m['voices']).index(voice)
   for velocity in range(1,128):
    selected=[r for r in regions if int(r['key'])==note and int(r['lovel'])<=velocity<=int(r['hivel'])]
    assert len(selected)==4
    for hit in range(9):
     r=next(r for r in selected if int(r['seq_position'])==hit%4+1)
     d=0 if velocity<=41 else 1 if velocity<=84 else 2
     assert r['sample']==f'{voice}-v{d}-r{hit%4}.wav'
  features[k['id']]={}
  for v in ('kick','snare','hat'):
   x,_=read(p/'audio'/f'{v}-v2-r0.wav');f,power=welch(x,44100,nperseg=2048)
   bands=np.array([power[(f>=lo)&(f<hi)].sum() for lo,hi in zip([20,80,180,400,1000,2500,6000],[80,180,400,1000,2500,6000,18000])]);bands/=bands.sum()
   features[k['id']][v]=bands.tolist()
print('PASS: 600 decoded performances, timbral/take variation, dynamic ordering, endpoints, ZIP PCM equality and all velocity/take SFZ mappings.')
(root/'signatures.json').write_text(json.dumps(features,indent=2))
# Pairwise signatures document differentiation, without pretending this proves listening approval.
for voice in ('kick','snare','hat'):
 distances=[float(np.linalg.norm(np.array(features[a][voice])-features[b][voice])) for i,a in enumerate(features) for b in list(features)[i+1:]]
 assert min(distances)>.015,(voice,distances)
 print(voice,'minimum pairwise normalized spectral distance',round(min(distances),4))
