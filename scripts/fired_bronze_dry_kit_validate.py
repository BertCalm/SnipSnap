"""Validate rendered export structure and measure native WAVs, independently of Kotlin."""
from pathlib import Path
import xml.etree.ElementTree as E
import json,wave,numpy as np
from scipy.io import wavfile
r=Path('testkit/fired-bronze-dry-kit');kit=r/'SnipSnap Fired Bronze Dry'
x=E.parse(next(kit.glob('*.xpm'))).getroot()
used=[]
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
result={'populatedPads':16,'velocityLayersPerPad':3,'hatChokeSlots':['A03','A04','A15']}
for id in ['kick','snare','closed_hat','open_hat']:
 rate,a=wavfile.read(r/'native'/f'{id}.wav');a=a.astype(float)/2147483648
 freq=np.fft.rfftfreq(len(a),1/rate);power=abs(np.fft.rfft(a*np.hanning(len(a))))**2
 result[id]={'peak':float(max(abs(a))),'centroidHz':float((freq*power).sum()/power.sum()),'lateToEarlyEnergy':float((a[int(.1*rate):int(.2*rate)]**2).sum()/(a[:int(.04*rate)]**2).sum())}
(r/'validation.json').write_text(json.dumps(result,indent=2))
print('Export checks passed: 16 pads, 48 layers, hat choke group, PCM24 references.')
