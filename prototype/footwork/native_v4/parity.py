"""Describe native/Python differences across 72 cells; not a listening acceptance test.
Usage: parity.py native_root contact_study connected_study report_path
"""
from pathlib import Path
import sys,json,numpy as np
from scipy.io import wavfile
ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'prototype/footwork/tap_contact_fit'))
from analyze import metrics
native,contacts,connected,output=map(Path,sys.argv[1:])
def read(p):
 r,x=wavfile.read(p);assert r==44100
 return x.astype(float)/(2**31 if x.dtype==np.int32 else 32768)
pairs={2:'heel',3:'toe',7:'shuffle',8:'flap',17:'roll',18:'build'}
rows=[]
for pad,name in pairs.items():
 n=len(read(native/'footwork-tap-jazz'/f'pad-{pad:02d}.wav'))
 strip=read(native/'footwork-tap-jazz'/f'touch-{pad:02d}.wav')
 reference=read(contacts/f'{name}-dynamics.wav') if pad in [2,3] else None
 for z,v in enumerate([.3,.65,1.]):
  for take in range(4):
   i=z*4+take;a=strip[i*(n+5512):i*(n+5512)+n]
   b=reference[i*17640:i*17640+10584] if reference is not None else read(connected/f'{name}-v{z}-rr{take}.wav')
   assert len(a)==len(b),(name,len(a),len(b))
   ma=metrics(a);mb=metrics(b)
   rows.append({'role':name,'velocity':v,'take':take,'native':ma,'python':mb,
     'rms_ratio':ma['rms']/mb['rms'],'band_fraction_l1':float(sum(abs(np.array(ma['band_fraction'])-mb['band_fraction'])))})
summary={name:{'rms_ratio_range':[min(r['rms_ratio'] for r in rows if r['role']==name),max(r['rms_ratio'] for r in rows if r['role']==name)],'mean_band_fraction_l1':float(np.mean([r['band_fraction_l1'] for r in rows if r['role']==name]))} for name in pairs.values()}
output.write_text(json.dumps({'scope':'72-cell descriptive comparison; RNG/house filters and soft-brush pressure differ','summary':summary,'cells':rows},indent=2)+'\n')
print(json.dumps(summary,indent=2))
