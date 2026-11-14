import json,pathlib,hashlib,sys,xml.etree.ElementTree as ET
from scipy.io import wavfile
import numpy as np
root=pathlib.Path(sys.argv[1]);m=json.loads((root/'manifest.json').read_text());old=pathlib.Path('testkit/brine-r8-audition')
assert len(m['clips'])==18
for c in m['clips']:
 for k,h in [('audio','sha256'),('raw','rawSha256')]:
  p=root/c[k];assert hashlib.sha256(p.read_bytes()).hexdigest()==c[h]
  sr,x=wavfile.read(p);assert sr==44100 and x.ndim==1
  x=x.astype(float)/2**31;assert np.max(np.abs(x))<1 and abs(np.mean(x))<.005
 assert (root/c['recipe']).is_file()
 if c['id'].endswith('-reference'):
  for k in ['audio','raw']:assert (root/c[k]).read_bytes()==(old/c[k]).read_bytes()
for style in ['ride','crash']:
 gains=[]
 for v in ['current','lean','focused']:
  c=next(c for c in m['clips'] if c['id']==f'r9-{style}-groove-{v}')
  _,a=wavfile.read(root/c['audio']);_,r=wavfile.read(root/c['raw'])
  a=a.astype(float);r=r.astype(float);gains.append(np.dot(a,r)/np.dot(r,r))
 assert max(gains)-min(gains)<1e-6,gains
assert (root/'BRINE-MPC-test-kit.zip').read_bytes()==(old/'BRINE-MPC-test-kit.zip').read_bytes()
reports=pathlib.Path('synth/build/test-results/test');total=0
for name in ['BrineCymbalResetTest','BrineReferenceCymbalsTest','BrinePerformanceTest','BrineKitTest','BrineKitExpansionTest','BrineTest','DeterminismTest']:
 t=ET.parse(reports/f'TEST-com.snipsnap.synth.{name}.xml').getroot()
 assert t.attrib['failures']=='0' and t.attrib['errors']=='0';total+=int(t.attrib['tests'])
print(f'PASS: 18 comparisons, all 36 WAV hashes/formats/headroom/DC, fixed groove gain, exact real references and previous kit; {total} focused tests')
