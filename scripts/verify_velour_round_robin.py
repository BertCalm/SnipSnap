#!/usr/bin/env python3
"""Independent audit of Kotlin-emitted PCM and downloadable grids (requires numpy/scipy)."""
import json,sys,zipfile,hashlib,io
from pathlib import Path
import numpy as np
from scipy.io import wavfile
import xml.etree.ElementTree as ET
root=Path(sys.argv[1]);bank=Path(__file__).resolve().parents[1]/'synth/src/main/resources/velour/approved-v1'
m=json.loads((root/'manifest.json').read_text());entries=m['samples'];assert len(entries)==576
records=[];minimum=1.;maxpeak=0.;maxdc=0.;maximum_level_change_db=0.;maximum_train_peak=0.
def read(p):
 rate,x=wavfile.read(p);assert rate==44100 and x.ndim==1
 return x.astype(np.float64)/2**31
for treatment in ['close','velour','bloom']:
 for slot in range(1,17):
  layer_energies=[]
  for zone,label in enumerate(['soft','mid','hard']):
   row=sorted([e for e in entries if e['treatment']==treatment and e['slot']==slot and e['zone']==zone],key=lambda e:e['take'])
   assert [e['take'] for e in row]==[0,1,2,3]
   ref=read(bank/treatment/f'{slot:02d}-{label}.wav');takes=[read(root/e['file']) for e in row]
   assert np.array_equal(ref,takes[0]),(treatment,slot,zone,'reference drift')
   energies=[]
   for e,x in zip(row,takes):
    assert len(x)==len(ref)==e['frames'] and np.isfinite(x).all() and x[-1]==0
    assert np.array_equal(ref[10584:],x[10584:]),'late-tail drift'
    peak=float(abs(x).max());dc=float(abs(x.mean()));energy=float(x@x)
    assert peak<=.950001 and dc<.005
    maxpeak=max(maxpeak,peak);maxdc=max(maxdc,dc);energies.append(energy)
    level=10*np.log10(energy/float(ref@ref));maximum_level_change_db=max(maximum_level_change_db,abs(level))
    assert abs(level)<1.,(treatment,slot,zone,'level drift',level)
    records.append({'file':e['file'],'frames':len(x),'peak':peak,'dc':dc,'energy_change_db':float(level),'sha256':hashlib.sha256((root/e['file']).read_bytes()).hexdigest()})
   for a in range(3):
    for b in range(a+1,4):
     scale=float(takes[a]@takes[b])/energies[a];residual=float(np.sum((takes[b]-scale*takes[a])**2))/energies[a]
     assert residual>1e-6,(treatment,slot,zone,a,b,residual)
     minimum=min(minimum,residual)
   for order in [[0]*8,[0,1,2,3]*2]:
    step=round(30/160*44100);train=np.zeros(step*7+len(ref))
    for hit,take in enumerate(order):train[hit*step:hit*step+len(ref)]+=takes[take]*row[take]['level']*.6
    maximum_train_peak=max(maximum_train_peak,float(abs(train).max()))
    assert abs(train).max()<.98,('audition train peak',treatment,slot,zone,float(abs(train).max()))
   layer_energies.append(energies)
  assert np.all(np.diff(layer_energies,axis=0)>0)
 with zipfile.ZipFile(root/f'{treatment}-rr.zip') as z:
  files=z.namelist();native=json.loads(z.read(next(p for p in files if p.endswith('/kit.json'))))
  assert len(native['pads'])==16
  prefix=next(p for p in files if p.endswith('/kit.json')).rsplit('/',1)[0]
  for slot,pad in enumerate(native['pads'],1):
   chain=read(io.BytesIO(z.read(prefix+'/'+pad['sample'])))
   ends=list(map(int,pad['chain']['boundaries']))+[len(chain)]
   assert len(ends)==13
   for zone in range(3):
    spec=pad['chain']['zones'][zone]
    assert spec['baseSlice']==zone*4 and spec['cycle']==4
    assert spec['velStart']==128*zone//3 and spec['velEnd']==128*(zone+1)//3-1
    for take in range(4):
     e=next(e for e in entries if e['treatment']==treatment and e['slot']==slot and e['zone']==zone and e['take']==take)
     n=zone*4+take
     assert np.array_equal(chain[ends[n]:ends[n+1]],read(root/e['file'])),('ZIP PCM mismatch',treatment,slot,zone,take)
  ds=ET.fromstring(z.read(next(p for p in files if p.endswith('.dspreset'))))
  samples=ds.findall('.//sample');assert len(samples)==192
  groups=ds.findall('.//group');assert len(groups)==48
  for group in groups:
   assert group.attrib['seqMode']=='round_robin' and group.attrib['seqLength']=='4'
   assert [int(s.attrib['seqPosition']) for s in group.findall('sample')]==[1,2,3,4]
  assert any(p.endswith('REFERENCE-LICENSE.txt') for p in files)
report={'status':'Kotlin PCM and package checks passed; listening and hardware pending','samples':576,'approved_references_preserved':144,'minimum_gain_removed_pair_residual':minimum,'maximum_peak':maxpeak,'maximum_absolute_dc':maxdc,'maximum_absolute_energy_change_db':maximum_level_change_db,'maximum_eight_hit_train_peak_at_160_bpm':maximum_train_peak,'late_tails_preserved_after_seconds':.24,'decentsampler_grid_samples':576,'files':records}
(root/'verification.json').write_text(json.dumps(report,indent=2))
print(json.dumps({k:v for k,v in report.items() if k!='files'},indent=2))
