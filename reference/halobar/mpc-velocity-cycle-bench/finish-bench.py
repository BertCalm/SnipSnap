from pathlib import Path
import sys,json,gzip,struct,zipfile,shutil,hashlib,wave,xml.etree.ElementTree as ET
p=Path(__file__).resolve().parent;root=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else p/'mpc-bench';candidate=root/'03-Velocity-Cycle-Candidate';name='HALO Bench VEL RR'
file=candidate/(name+'.xty');parts=gzip.decompress(file.read_bytes()).split(b'\n',5);assert len(parts)==6 and parts[0]==b'ACVS'
input_payload=candidate/'candidate-native-payload.json'
payload=json.loads(input_payload.read_text()) if input_payload.exists() else json.loads(parts[-1]);inst=payload['data']['program']['drum']['instruments'][0]
active={layer['sampleName']:layer for layer in inst['layersv'] if layer['sampleName']}
assert set(active)=={'Soft1','Soft2','Soft3','Hard1','Hard2','Hard3'}
empty=[layer for layer in inst['layersv'] if not layer['sampleName']]
inst['layersv']=[active[s] for s in ['Hard1','Hard2','Hard3','Soft1','Soft2','Soft3']]+empty
inst['zonePlayTime']=0
body=json.dumps(payload,separators=(',',':'),allow_nan=False).encode()
file.write_bytes(gzip.compress(b'\n'.join(parts[:5])+b'\n'+body,mtime=0))
input_payload.unlink(missing_ok=True)
def variable(n):
 out=[n&127];n>>=7
 while n:out.insert(0,(n&127)|128);n>>=7
 return bytes(out)
scores={}
for pattern in ['soft','hard','alternating']:
 velocities=[40 if pattern=='soft' else 110 if pattern=='hard' else (40 if i%2==0 else 110) for i in range(12)]
 events=[(0,b'\xff\x51\x03'+(833333).to_bytes(3,'big')),(0,b'\xff\x03'+bytes([len(pattern)])+pattern.encode())]
 for index,velocity in enumerate(velocities):
  start=round(index*1.2*1152);end=round((index*1.2+.95)*1152)
  events.extend([(start,bytes([0x90,60,velocity])),(end,bytes([0x80,60,0]))])
 events.sort(key=lambda x:x[0]);track=bytearray();last=0
 for tick,event in events:track+=variable(tick-last)+event;last=tick
 track+=variable(round(14.4*1152)-last)+b'\xff\x2f\x00'
 midi=b'MThd'+struct.pack('>IHHH',6,0,1,960)+b'MTrk'+struct.pack('>I',len(track))+track
 (root/(pattern+'.mid')).write_bytes(midi)
 scores[pattern]={'midiNote':60,'midiVelocities':velocities,'intervalSeconds':1.2,'bpm':72,'hits':12}
rows=[]
for directory in sorted(d for d in root.iterdir() if d.is_dir() and any(d.glob('*.xty'))):
 native=next(directory.glob('*.xty'));pieces=gzip.decompress(native.read_bytes()).split(b'\n',5);j=json.loads(pieces[-1]);z=j['data']['program']['drum']['instruments'][0]
 xml=next(directory.rglob('*.xpm'));tree=ET.parse(xml).getroot();filled=[l for l in tree.findall('.//Instrument/Layers/Layer') if l.findtext('Active')=='True']
 count=6 if 'Candidate' in directory.name else 3 if 'Cycle-Control' in directory.name else 2
 assert len(filled)==count
 assert len([l for l in z['layersv'] if l['sampleName']])==count
 assert all(int(l.findtext('RootNote'))==60 for l in filled)
 for wav in directory.rglob('*.wav'):
  with wave.open(str(wav)) as reader:assert reader.getframerate()==44100 and reader.getnchannels() in [1,2]
 rows.append({'folder':directory.name,'native':str(native.relative_to(root)),'xpm':str(xml.relative_to(root)),
  'activeSamples':count,'zonePlayTime':z['zonePlayTime'],'hardwareVerified':False})
manifest={'purpose':'MPC keygroup velocity/cycle diagnostic','productionEnabled':False,'hardwareVerified':False,
 'controls':['Velocity only: low one-beep samples at 40, high one-beep samples at 110','Cycle only: high one/two/three beep samples at either velocity'],
 'candidate':'Cycle mode with three samples in each velocity window; firmware must select the matching window and advance its takes',
 'referenceAudio':'Saved PCM24 reloaded through InstrumentEngine, common 4x audition gain; these are software expectations',
 'scores':scores,'programs':rows,'hardwareResult':{'firmware':None,'device':None,'nativeControlsPassed':None,'nativeCandidatePassed':None,'xpmControlsPassed':None,'xpmCandidatePassed':None}}
(root/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
(root/'README.txt').write_text('''MPC keygroup velocity and round-robin bench

This diagnostic tests the remaining HALOBAR export gate. It is separate from
the musical study kit. The combined candidate has no hardware verdict and is
not enabled in the production keygroup exporter.

Three programs are included, each with an MPC 3 XTY, XPM, PCM24 WAVs and phone
sidecar. Extract the whole archive. Load a program's XTY beside its TrackData
folder, or its XPM inside TrackData. Keep every supplied WAV with the program.
Play MIDI note 60. The soft window is 0-79; hard is 80-127.

1. 01-Velocity-Control: velocity 40 plays one low beep every time; velocity 110
   plays one high beep every time. This establishes sample loading and gating.
2. 02-Cycle-Control: either velocity plays high one/two/three-beep takes in a
   repeating cycle. This establishes ordinary layer cycling.
3. 03-Velocity-Cycle-Candidate: velocity 40 should cycle low one/two/three-beep
   takes; velocity 110 should cycle high one/two/three-beep takes. Three samples
   share each velocity window in layer Cycle mode. This is the behavior the
   hardware must prove before this mode becomes a production export option.

Import soft.mid, hard.mid and alternating.mid onto each loaded keygroup track.
Each file plays twelve hits at MIDI 60, spaced 1.2 seconds, tempo 72 BPM.
soft uses velocity 40; hard uses 110; alternating switches between the two.
Count beeps in each hit. A cycle may begin at a different phase; it must stay
in the requested velocity group and visit all three takes without silence or
simultaneous takes. Record the order on the alternating sequence as well.

Each folder has Reference-soft.wav, Reference-hard.wav and
Reference-alternating.wav. These reload the saved PCM24 samples in the JVM
reference player, using a common 4x audition gain. They describe expected take
selection; actual phone/device and MPC playback have not been measured.

In manifest.json record the MPC model, firmware, which format was loaded,
control results and actual candidate sequences. Success in one format or
firmware does not establish the other. If the candidate fails, the production
exporter's first-take-per-velocity behavior remains the supported fallback.
The all-capture phone sidecar continues to cycle each velocity window.

The tone generator and packaging script are included under source/. The tones
have bounded peaks (.18 low / .24 high), 5 ms edges, finite .9 second files,
mono 44.1 kHz PCM24 and MIDI root metadata 60.
''')
source=root/'source';source.mkdir(exist_ok=True)
for name in ['MpcVelocityCycleBench.java','finish-bench.py','VerifyMpcBench.java']:
 if (p/name).exists() and (p/name).resolve()!=(source/name).resolve():shutil.copy2(p/name,source/name)
archive=root.parent/'HALOBAR-MPC-Velocity-RR-Bench.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for f in sorted(root.rglob('*')):
  if f.is_file():z.write(f,'HALOBAR MPC Bench/'+str(f.relative_to(root)))
with zipfile.ZipFile(archive) as z:assert z.testzip() is None
result={'programs':3,'midiScores':3,'softwareReferenceClips':9,'hardwareVerified':False,'bytes':archive.stat().st_size,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest()}
(root.parent/'bench-validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
