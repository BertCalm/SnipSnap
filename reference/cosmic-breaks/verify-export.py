"""Inspect serialized grid fields independently of ExportReadBack (which skips chains)."""
import gzip, json, sys, xml.etree.ElementTree as ET
from pathlib import Path
root=Path(sys.argv[1] if len(sys.argv)>1 else 'testkit/cosmic-breaks-kit/Cosmic Breaks Kit')
payload=json.loads(gzip.decompress((root/'Cosmic Breaks.xtd').read_bytes()).decode().split('\n',5)[5])
def objects(value):
    if isinstance(value,dict):
        yield value
        for child in value.values(): yield from objects(child)
    elif isinstance(value,list):
        for child in value: yield from objects(child)
layers=[v for v in objects(payload) if 'sliceCycleLength' in v and v.get('sampleName')]
assert len(layers)==48,len(layers)
for i,v in enumerate(layers):
    z=2-i%3
    assert (v['velocityStart'],v['velocityEnd'])==[(0,41),(42,84),(85,127)][z]
    assert v['sliceIndex']==z*4 and v['sliceIncrement']==1 and v['sliceCycleLength']==4
    assert v['sliceInfo']['Start']==z*4*176400 and v['sliceInfo']['End']==(z*4+1)*176400
xml=ET.parse(root/'Cosmic Breaks_[TrackData]'/'Cosmic Breaks.xpm')
layers=[e for e in xml.findall('.//Layer') if e.findtext('SampleName')]
assert len(layers)==48,len(layers)
for i,e in enumerate(layers):
    z=i%3
    assert (int(e.findtext('VelStart')),int(e.findtext('VelEnd')))==[(0,41),(42,84),(85,127)][z]
    assert int(e.findtext('SliceStart'))==z*4*176400 and int(e.findtext('SliceEnd'))==(z*4+1)*176400
print('48 MPC 3 layer anchors/cycle fields and 48 MPC 2 first-take windows verified. MPC 3 hardware slice-map behavior remains unverified.')
