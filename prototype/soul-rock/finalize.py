from pathlib import Path
import json,zipfile,re
import numpy as np
from scipy import signal
from scipy.io import wavfile
r=Path(__file__).parent;m=json.loads((r/'manifest.json').read_text());audio=[]
for p in r.glob('*.wav'):
 fs,x=wavfile.read(p);y=x.astype(float)/32768;pk=float(max(abs(signal.resample_poly(y,4,1))));g=min(1,.92/pk)
 if g<1:wavfile.write(p,fs,np.round(y*g*32767).astype(np.int16))
 audio.append({'file':p.name,'estimated_true_peak_before':pk,'gain':g})
m['audition_output']=audio;m['verification']['scope']='Python and PCM16 readback. Estimated 4x true peak verified separately in verification.json; full alias-reference, native integration and hardware checks remain open.'
(r/'manifest.json').write_text(json.dumps(m,indent=2))
p=r/'index.html';s=p.read_text();s=s.replace('<section>', '<section>',1).replace('This is a recipe direction, not a completed fit or approved factory collection.', 'Kick tuning uses provisional low-frequency targets measured from your clips; other tone settings remain authored starting points. This is a recipe direction, not a completed fit or approved factory collection.')
s=s.replace('</h1>','</h1><p>Reference families: Signed, Sealed, Delivered; Cowboy Song; Hold Your Head Up. Source titles are shown in the supplied videos. These audition grooves are original arrangements.</p>',1)
p.write_text(s)
for src in re.findall(r'src="([^"]+)"',s):assert (r/src).is_file(),src
with zipfile.ZipFile(r/'audition.zip','w',zipfile.ZIP_DEFLATED) as z:
 for p in r.glob('*.wav'):z.write(p,p.name)
 for name in ['index.html','manifest.json','verification.json','EMBER-NOTICE.txt']:z.write(r/name,name)
 z.write(r/'DESIGN.txt','DESIGN.txt')
for kit in m['kits']:
 name=kit['name']
 with zipfile.ZipFile(r/f'{name}-wav-kit.zip','w',zipfile.ZIP_DEFLATED) as z:
  for p in (r/name).glob('*.wav'):z.write(p,str(p.relative_to(r)))
  for file in ['manifest.json','verification.json','EMBER-NOTICE.txt']:z.write(r/file,file)
for p in r.glob('*.zip'):
 with zipfile.ZipFile(p) as z:assert z.testzip() is None
 print(p.name,round(p.stat().st_size/1024**2,2),'MiB')
print('Audition references and archive integrity verified.')
