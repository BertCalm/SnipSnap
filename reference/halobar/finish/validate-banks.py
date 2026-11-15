from pathlib import Path
import subprocess,zipfile,hashlib,json,gzip,xml.etree.ElementTree as ET
p=Path('/workspace/halobar-finish/release');r=Path('/workspace/SnipSnap');j=Path('/workspace/halobar-jdk-location.txt').read_text().strip();cp=Path('/workspace/halobar-next/study-classpath.txt').read_text().strip()
# Class directories stay at the same paths, rebuilt from the frozen source.
original=Path('/workspace/halobar-six/HALOBAR-Six-Keygroups.zip');target=p/'keygroups';assert not target.exists();target.mkdir()
with zipfile.ZipFile(original) as z:
 assert z.testzip() is None
 for name in z.namelist():assert (target/name).resolve().is_relative_to(target.resolve())
 z.extractall(target)
folder=target/'HALOBAR Keys';before={str(f.relative_to(folder)):hashlib.sha256(f.read_bytes()).hexdigest() for f in folder.rglob('*') if f.is_file()}
subprocess.run([j+'/bin/javac','-cp',cp,'-d',str(p),str(p/'UpgradeHalobarBanks.java')],check=True)
subprocess.run([j+'/bin/java','-Xmx1g','-XX:ActiveProcessorCount=4','-cp',cp+':'+str(p),'UpgradeHalobarBanks',str(folder)],check=True)
roots=0;rows=[]
for sidecar in sorted(folder.glob('*.instrument.json')):
 data=json.loads(sidecar.read_text());name=data['name'];assert data['version']==5
 native=json.loads(gzip.decompress((folder/(name+'.xty')).read_bytes()).split(b'\n',5)[5])['data']['program']['drum']['instruments']
 xml=ET.parse(folder/(name+'_[TrackData]')/(name+'.xpm')).findall('.//Instrument')
 assert len(xml)==len(data['zones'])
 for i,zone in enumerate(data['zones']):
  assert zone['cycleLayers'] and zone['mpcVelocityCycle'];assert len(zone['layers'])==6
  assert [(x['velStart'],x['velEnd']) for x in zone['layers']]==[(0,79)]*3+[(80,127)]*3
  active=[x for x in native[i]['layersv'] if x['sampleName']];assert native[i]['zonePlayTime']==0
  expected=zone['layers'][3:]+zone['layers'][:3]
  assert [x['sampleName'] for x in active]==[Path(x['sample']).stem for x in expected]
  assert [(x['velocityStart'],x['velocityEnd']) for x in active]==[(80,127)]*3+[(0,79)]*3
  assert all(x['rootNote']==zone['root'] for x in active)
  xactive=[x for x in xml[i].findall('./Layers/Layer') if x.findtext('Active')=='True']
  assert xml[i].findtext('ZonePlay')=='0';assert len(xactive)==6
  assert [x.findtext('SampleName') for x in xactive]==[Path(x['sample']).stem for x in zone['layers']]
  assert [(int(x.findtext('VelStart')),int(x.findtext('VelEnd'))) for x in xactive]==[(0,79)]*3+[(80,127)]*3
  assert all(int(x.findtext('RootNote'))==zone['root'] for x in xactive)
  # Read the exported selection vocabulary for independent per-window cycles.
  cursors={40:0,110:0}
  for velocity in [40,110]*6:
   eligible=[x for x in active if x['velocityStart']<=velocity<=x['velocityEnd']]
   selected=eligible[cursors[velocity]%len(eligible)]['sampleName'];cursors[velocity]+=1
   expected_layer=zone['layers'][(0 if velocity==40 else 3)+(cursors[velocity]-1)%3]
   assert selected==Path(expected_layer['sample']).stem
  roots+=1
 rows.append(dict(bank=name,roots=len(data['zones']),captures=sum(len(z['layers']) for z in data['zones']),nativeCycle=True,xpmCycle=True))
after={str(f.relative_to(folder)):hashlib.sha256(f.read_bytes()).hexdigest() for f in folder.rglob('*') if f.is_file()}
assert set(before)==set(after);changed=[name for name in before if before[name]!=after[name]]
assert len(changed)==18 and all(name.endswith(('.instrument.json','.xty','.xpm')) for name in changed)
wavs=[name for name in before if name.endswith('.wav')];assert len(wavs)==426 and all(before[name]==after[name] for name in wavs);assert roots==71
archive=p/'HALOBAR-Six-Keygroups-Final.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for f in sorted(folder.rglob('*')):
  if f.is_file():z.write(f,'HALOBAR Keys/'+str(f.relative_to(folder)))
with zipfile.ZipFile(archive) as z:assert z.testzip() is None
report=dict(state='passed',sourceCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=r,text=True).strip(),originalArchiveSha256=hashlib.sha256(original.read_bytes()).hexdigest(),outputArchiveSha256=hashlib.sha256(archive.read_bytes()).hexdigest(),outputBytes=archive.stat().st_size,banks=rows,roots=roots,captures=len(wavs),everyWavByteIdentical=True,recipesUnchanged=True,changedFiles=changed,ownerHardwarePassReported=True,newHardwareExecution=False)
(p/'bank-validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
