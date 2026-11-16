from pathlib import Path
import json,hashlib,zipfile
import numpy as np
from scipy import signal
from scipy.io import wavfile
root=Path(__file__).parent;m=json.loads((root/'manifest.json').read_text());rows=[];spread=[]
for kit in m['kits']:
 for p in kit['pads']:
  db=[]
  for rel in p['variants']:
   path=root/rel;fs,y=wavfile.read(path);assert fs==44100 and y.ndim==1;y=y.astype(float)/32768
   assert np.isfinite(y).all();tp=float(max(abs(signal.resample_poly(y,4,1))))
   assert tp<.98 and abs(y.mean())<.001 and np.sqrt(np.mean(y[-441:]**2))<.002
   rows.append({'file':rel,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'true_peak_estimate_4x':tp})
   db.append(20*np.log10(np.sqrt(np.mean(y*y))))
  spread.extend(float(np.ptp(db[z*3:z*3+3])) for z in range(3))
assert len(rows)==432 and len({r['sha256'] for r in rows})==432
for z in root.glob('*.zip'):
 with zipfile.ZipFile(z) as f:assert f.testzip() is None
report={'variants':len(rows),'distinct_pcm_files':len(set(r['sha256'] for r in rows)),'max_estimated_true_peak_4x':max(r['true_peak_estimate_4x'] for r in rows),'max_take_rms_spread_db':max(spread),'archive_crc':'pass','limitations':'Python prototype only. No full alias-reference, SnipSnap save/load, MPC import or hardware proof.'}
(root/'verification.json').write_text(json.dumps({'summary':report,'files':rows},indent=2));print(json.dumps(report))
