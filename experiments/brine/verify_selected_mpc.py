"""Compare R13 MPC PCM to prior non-cymbals and selected crashes; audit every trimmed end."""
import pathlib,json,sys
import numpy as np
from scipy.io import wavfile
root=pathlib.Path(sys.argv[1]);old=pathlib.Path('testkit/brine-r8-audition/mpc-test-kit');rows=[];unchanged=0
for program in sorted((root/'mpc-test-kit').iterdir()):
 if not program.is_dir():continue
 name=program.name;data=program/(name+'_[TrackData]');oldname=name.replace(' R13','');olddata=old/oldname/(oldname+'_[TrackData]')
 for p in sorted(data.glob('*.wav')):
  sr,x=wavfile.read(p);sr0,y=wavfile.read(olddata/p.name);assert sr==sr0==44100
  xf=x.astype(float)/2**31;yf=y.astype(float)/2**31;pad=int(p.name[1:3]);assert xf.ndim==1
  assert len(x)<len(y) and np.max(np.abs(xf))<1 and abs(xf.mean())<.005 and abs(xf[-1])<1e-7
  active=np.flatnonzero(np.abs(xf)>np.max(np.abs(xf))*10**(-90/20))
  assert len(active)>0 and (len(x)-active[-1]-1)/sr<.14,(p,(len(x)-active[-1]-1)/sr)
  if pad<=10:
   assert np.array_equal(x[:-441],y[:len(x)-441]),p
   assert np.sum(yf[len(x):]**2)/np.sum(yf**2)<1e-7,p
   unchanged+=1
  if pad in (12,13) and 'Full Chain' in name:
   ref='r9-thin-crash-focused' if pad==12 else 'r9-choked-crash-focused'
   _,chosen=wavfile.read(root/'audio'/f'{ref}-raw.wav');assert np.array_equal(x,chosen),p
  rows.append({'program':name,'file':p.name,'oldSeconds':len(y)/sr,'seconds':len(x)/sr})
assert len(rows)==68 and unchanged==48,(len(rows),unchanged)
# Entire audition stays byte-identical: rebuilding the kit must not retune the listening choices.
previous=json.loads(pathlib.Path('testkit/brine-r12-audition/manifest.json').read_text())
now={c['id']:c for c in json.loads((root/'manifest.json').read_text())['clips']}
for c in previous['clips']:
 for k in ['sha256','rawSha256']:assert c[k]==now[c['id']][k]
report={'samples':68,'unchangedNonCymbalPrefixes':48,'auditionPairsUnchanged':20,'maximumSeconds':max(r['seconds'] for r in rows),'files':rows}
(root/'mpc-length-verification.json').write_text(json.dumps(report,indent=2)+'\n')
print('PASS: all 68 shortened WAVs have clean endings; 48 non-cymbal sample prefixes unchanged; wet crashes exactly match selected audition PCM; all 20 audition pairs unchanged')
print('Maximum sample duration:',round(report['maximumSeconds'],3),'seconds')
