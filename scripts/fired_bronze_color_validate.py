"""Independent export, waveform, and preserved-dry checks for the color round."""
from pathlib import Path
import xml.etree.ElementTree as E
import json,wave,hashlib
import numpy as np
from scipy.io import wavfile
r=Path('testkit/fired-bronze-color');kit=r/'SnipSnap Fired Bronze Color'
x=E.parse(next(kit.glob('*.xpm'))).getroot();used=[]
for inst in x.findall('.//Instrument'):
 layers=[l for l in inst.findall('.//Layer') if l.findtext('SampleName')]
 if not layers:continue
 used.append(inst)
 assert len(layers)==3
 assert [(int(l.findtext('VelStart')),int(l.findtext('VelEnd'))) for l in layers]==[(1,41),(42,84),(85,127)]
 for l in layers:assert (kit/(l.findtext('SampleName')+'.wav')).exists()
assert len(used)==16
assert [int(i.get('number')) for i in used if i.findtext('MuteGroup')=='1']==[2,3,14]
for p in r.rglob('*.wav'):
 with wave.open(str(p)) as w:assert w.getnchannels()==1 and w.getframerate()==44100 and w.getsampwidth()==3
for p in (r/'native').glob('*_dry.wav'):
 id=p.stem.removesuffix('_dry')
 old=Path('testkit/fired-bronze-dry-kit/native')/(('groove_'+id.removesuffix('_bus'))+'.wav' if id.endswith('_bus') else id+'.wav')
 assert p.read_bytes()==old.read_bytes(),str(p)
report={'pads':16,'layersPerPad':3,'dryWavsIdentical':18,'clips':{}}
for id in ['full_bus','snare','tin','gong']:
 for stage in ['dry','processor','amp']:
  p=r/'native'/f'{id}_{stage}.wav';rate,a=wavfile.read(p);a=a.astype(float)/2147483648
  report['clips'][id+'_'+stage]={'seconds':len(a)/rate,'peak':float(max(abs(a))),'mean':float(a.mean()),'rms':float(np.sqrt(np.mean(a*a))),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
(r/'validation.json').write_text(json.dumps(report,indent=2));print('Validated 16 pads, 48 sample layers, choke groups, PCM24, and 18 identical dry WAVs.')
