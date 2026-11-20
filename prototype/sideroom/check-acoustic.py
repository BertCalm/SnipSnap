import hashlib,json,sys,zipfile
from pathlib import Path
import numpy as np
from scipy.io import wavfile
p=Path(sys.argv[1]);m=json.loads((p/'manifest.json').read_text());report={}
assert len(m['files'])==len(m['modes'])*len(m['voices'])*len(m['levels'])*m['takes']
with zipfile.ZipFile(p/'open-road-acoustic.zip') as z:
 for mode in m['modes']:
  for voice in m['voices']:
   energies=[];hashes=set();onsets=[]
   for level in range(3):
    rms=[]
    for take in range(3):
     rel=m['files'][f'{mode}/{voice}/{level}/{take}'];raw=(p/rel).read_bytes();assert raw==z.read(rel)
     sr,x=wavfile.read(p/rel);assert sr==44100 and x.dtype==np.int16 and x.ndim==1
     y=x.astype(float)/32768;assert np.isfinite(y).all() and np.max(np.abs(y))<.99
     assert abs(y[0])<.001 and abs(y[-1])<.001
     hashes.add(hashlib.sha256(raw).hexdigest());rms.append(np.sqrt(np.mean(y[:22050]**2)))
     onsets.append(float(np.flatnonzero(np.abs(y)>.1*np.max(np.abs(y)))[0]/sr))
    energies.append(float(np.mean(rms)))
   assert len(hashes)==9
   assert energies[0]<energies[1]<energies[2],(mode,voice,energies)
   report[mode+'/'+voice]={'level_rms_db':[round(20*np.log10(e),2) for e in energies],'latest_onset_ms':round(max(onsets)*1000,2)}
  regions=[line for line in (p/mode/'kit.sfz').read_text().splitlines() if line.startswith('<region>')];assert len(regions)==len(m['voices'])*9
  for velocity in range(1,128):
   for key in m.get('keys',dict(zip(m['voices'],[36,38,42,46]))).values():
    matches=[]
    for line in regions:
     fields=dict(token.split('=',1) for token in line.split() if '=' in token)
     if int(fields['key'])==key and int(fields['lovel'])<=velocity<=int(fields['hivel']):matches.append(fields)
    assert sorted(int(f['seq_position']) for f in matches)==[1,2,3]
    assert all((p/mode/f['sample']).exists() for f in matches)
(p/'checks.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2));print('PASS: WAVs, real-file uniqueness, dynamics, endpoints, ZIP equality, complete SFZ velocity/take mapping')
