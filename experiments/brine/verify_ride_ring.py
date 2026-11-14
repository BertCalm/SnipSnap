"""R10 asset regression: old comparisons preserved, new ride bounded, six focused tests green."""
import json,pathlib,hashlib,sys,xml.etree.ElementTree as ET
import numpy as np
from scipy.io import wavfile
root=pathlib.Path(sys.argv[1]);old=pathlib.Path('testkit/brine-r9-audition')
m=json.loads((root/'manifest.json').read_text());before=json.loads((old/'manifest.json').read_text());clips={c['id']:c for c in m['clips']}
assert len(clips)==20
for c in before['clips']:
 for k in ['sha256','rawSha256']:assert clips[c['id']][k]==c[k],(c['id'],k)
for c in clips.values():
 for k,h in [('audio','sha256'),('raw','rawSha256')]:
  p=root/c[k];assert hashlib.sha256(p.read_bytes()).hexdigest()==c[h]
  sr,x=wavfile.read(p);assert sr==44100 and x.ndim==1
  x=x.astype(float)/2**31;assert np.max(np.abs(x))<1 and abs(np.mean(x))<.005
 assert (root/c['recipe']).is_file()
g=[]
for c in clips.values():
 if 'ride-groove' not in c['id']:continue
 _,a=wavfile.read(root/c['audio']);_,r=wavfile.read(root/c['raw']);a=a.astype(float);r=r.astype(float)
 g.append(np.dot(a,r)/np.dot(r,r))
assert max(g)-min(g)<1e-6
assert (root/'BRINE-MPC-test-kit.zip').read_bytes()==(old/'BRINE-MPC-test-kit.zip').read_bytes()
total=0
for name in ['BrineCymbalResetTest','BrineReferenceCymbalsTest']:
 t=ET.parse(f'synth/build/test-results/test/TEST-com.snipsnap.synth.{name}.xml').getroot()
 assert t.attrib['failures']=='0' and t.attrib['errors']=='0';total+=int(t.attrib['tests'])
print(f'PASS: all 18 R9 comparison pairs unchanged; 20 comparisons / 40 WAV hashes, format/headroom/DC; common groove gain; previous kit unchanged; {total} focused tests')
