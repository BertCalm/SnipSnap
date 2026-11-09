"""Read SFZ and DecentSampler take order, zones and PCM windows back against saved kits."""
from pathlib import Path
import json,re,sys,xml.etree.ElementTree as ET,wave
root=Path(sys.argv[1])
for ident in ['backwash-soul','backwash-psych']:
 r=root/ident;kit=json.loads((r/'native/kit.json').read_text());sfz=next(r.rglob('*.sfz')).read_text();ds=ET.parse(next(r.rglob('*.dspreset')))
 regions=[dict(re.findall(r'(\w+)=([^\s]+)',line)) for line in sfz.splitlines() if line.startswith('<region>')]
 for p in kit['pads']:
  chain=p['chain'];name=p['sample']
  with wave.open(str(r/'native'/name)) as w:frames=w.getnframes()
  rr=[x for x in regions if x['sample']==name]
  groups=[g for g in ds.findall('.//group') if any(s.attrib['path']=='Samples/'+name for s in g.findall('sample'))]
  assert len(groups)==len(chain['zones'])
  assert len(rr)==sum(z['cycle'] for z in chain['zones'])
  cursor=0
  for z,g in zip(chain['zones'],groups):
   assert g.attrib['seqMode']=='round_robin' and int(g.attrib['seqLength'])==z['cycle']
   samples=g.findall('sample');assert len(samples)==z['cycle']
   for t,s in enumerate(samples):
    idx=z['baseSlice']+t;start=chain['boundaries'][idx];end=(chain['boundaries'][idx+1] if idx+1<len(chain['boundaries']) else frames)-1
    x=rr[cursor];cursor+=1
    assert [int(x[k]) for k in ['lovel','hivel','seq_length','seq_position','offset','end']]==[z['velStart'],z['velEnd'],z['cycle'],t+1,start,end]
    assert [int(s.attrib[k]) for k in ['loVel','hiVel','seqPosition','start','end']]==[z['velStart'],z['velEnd'],t+1,start,end]
 print(ident+': SFZ and DecentSampler zones, cycle positions and PCM windows pass')
