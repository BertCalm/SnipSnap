import gzip,json,pathlib,xml.etree.ElementTree as ET,zipfile,sys,wave
root=pathlib.Path(sys.argv[1]) if len(sys.argv)>1 else pathlib.Path('testkit/brine-r5-audition')
programs=list((root/'mpc-test-kit').rglob('*.xtd'))
assert len(programs)==4
expected=[0,0,1,1,1,0,0,0,0,0,0,2,2]
for xtd in programs:
 lines=gzip.decompress(xtd.read_bytes()).decode().split('\n',5)
 assert lines[0]=='ACVS' and lines[2]=='SerialisableTrackData'
 track=json.loads(lines[5])['data']
 data=xtd.parent/(xtd.stem+'_[TrackData]')
 native=track['program']['drum']['instruments']
 xml=ET.parse(data/(xtd.stem+'.xpm')).getroot().findall('.//Instrument')
 assert len(native)>=13 and len(xml)>=13
 for i,group in enumerate(expected):
  a=native[i]; b=xml[i]
  assert a['whichMuteGroup']==group and int(b.findtext('MuteGroup'))==group
  assert a['triggerMode']==0 and b.findtext('OneShot')=='True'
  native_layers=sorted([l for l in a['layersv'] if l.get('sampleFile')],key=lambda l:l['velocityStart'])
  names=[l['sampleFile'] for l in native_layers]
  stems=[l.findtext('SampleName') for l in b.findall('.//Layer') if l.findtext('SampleName')]
  assert len(names)==len(stems)==(3 if i in (1,10) else 1)
  if i in (1,10):
   assert [(l['velocityStart'],l['velocityEnd']) for l in native_layers]==[(1,41),(42,84),(85,127)]
   assert [(int(l.findtext('VelStart')),int(l.findtext('VelEnd'))) for l in b.findall('.//Layer') if l.findtext('SampleName')]==[(1,41),(42,84),(85,127)]
  xml_layers=[l for l in b.findall(".//Layer") if l.findtext("SampleName")]
  for name,stem,nlayer,xlayer in zip(names,stems,native_layers,xml_layers):
   assert pathlib.Path(name).stem==stem
   assert (data/name).is_file()
   with wave.open(str(data/name),'rb') as wav:
    assert wav.getnchannels()==1 and wav.getframerate()==44100 and wav.getsampwidth()==3
    assert nlayer['sliceInfo']['Start']==0 and nlayer['sliceInfo']['End']==wav.getnframes()
    assert int(xlayer.findtext('SliceStart'))==0 and int(xlayer.findtext('SliceEnd'))==wav.getnframes()
 assert len(list(data.glob('*.wav')))==17
with zipfile.ZipFile(root/'BRINE-MPC-test-kit.zip') as z:
 assert z.testzip() is None
 assert len([n for n in z.namelist() if n.endswith('.wav')])==68
 assert len([n for n in z.namelist() if n.endswith('.xtd')])==4
 assert len([n for n in z.namelist() if n.endswith('.xpm')])==4
print('PASS: MPC 2/3 program references, 68 samples across four velocity-layered programs, one-shot flags, hat/crash choke groups, ZIP integrity (hardware unverified)')
