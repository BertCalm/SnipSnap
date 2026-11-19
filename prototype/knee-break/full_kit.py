"""R4 full synth-kit audition. Approved R3 core PCM must remain byte-identical.
Python prototype; SFZ delivery, not native SnipSnap registration or MPC proof.
"""
from pathlib import Path
import sys,json,hashlib,copy,zipfile,shutil,time
from functools import lru_cache
import numpy as np
from scipy import signal
from scipy.io import wavfile
from fitted_synth import render,MODEL,SR
from render import treatment
ROOT=Path(__file__).resolve().parents[2];SITE=Path(sys.argv[1]);OUT=SITE/'round4';(OUT/'audio').mkdir(parents=True,exist_ok=True)
model=json.loads(MODEL.read_text());extra=json.loads(Path(__file__).with_name('extra-model.json').read_text());model['voices'].update(extra['voices'])
# Lower tom preserves the fitted head proportions; pitches are scaled, not replayed.
model['voices']['floor-tom']=copy.deepcopy(model['voices']['rack-tom'])
for layer in model['voices']['floor-tom']:
 for b in layer['bands']:
  b['frequencies']=[f*.72 for f in b['frequencies']]
roles=['kick','snare','closed-hat','open-hat','short-kick','ghost-snare','rimshot','cross-stick','floor-tom','rack-tom','pedal-hat','ride','shaker','woodblock','snare-roll','snare-riser']
names=['Felt Kick','Damped Snare','Closed Hat','Open Hat','Short Kick','Ghost Snare','Rimshot','Cross-stick','Low Tom','Rack Tom','Pedal Hat','Dark Ride','Shaker','Woodblock','Snare Drag / Roll','Snare Riser']
levels=[.8,.8,.34,.32,.65,.6,.65,.6,.62,.6,.3,.28,.25,.35,.65,.6]
starts=[.065,.055,.025,.09,.045,.025,.055,.025,.06,.05,.015,.08,.02,.015,0,0]
taus=[.17,.15,.09,.28,.1,.09,.17,.11,.2,.16,.07,.32,.1,.08,0,0]
velocity=[.38,.68,1.];cache={};entries=[];begin=time.perf_counter()
def read(p):
 rate,x=wavfile.read(p);assert rate==SR;return x.astype(float)/32767

def finish(x):
 x=signal.sosfilt(signal.butter(2,22,fs=SR,btype='highpass',output='sos'),x)
 active=np.flatnonzero(abs(x)>10**(-65/20));end=min(len(x),active[-1]+1103) if len(active) else 1;x=x[:end].copy();f=min(len(x),353);x[-f:]*=np.linspace(1,0,f)
 return np.r_[x,np.zeros(882)]
@lru_cache(maxsize=512)
def raw(v,z,take):
 if v in model['voices']:return render(model,v,z,take)
 rng=np.random.default_rng(int.from_bytes(hashlib.sha256(f'r4:{v}:{z}:{take}'.encode()).digest()[:8],'little'));rate=SR*4;t=np.arange(round(.55*rate))/rate;strength=velocity[z]
 if v=='shaker':
  out=np.zeros(len(t))
  for at,g in [(0,1),(.025,.7),(.055,.4)]:
   at+=rng.uniform(0,.004);dt=np.maximum(0,t-at);noise=signal.sosfilt(signal.butter(2,[1800+500*strength,12000],fs=rate,btype='bandpass',output='sos'),rng.normal(size=len(t)));out+=noise*(t>=at)*(1-np.exp(-dt/.001))*np.exp(-dt/(.018+.01*strength))*g
  out*=strength**1.3*.6
 else:
  out=np.zeros(len(t));hzs=[690,1630,2860,4310]
  for i,hz in enumerate(hzs):out+=np.sin(2*np.pi*hz*(1+rng.uniform(-.004,.004))*t)*np.exp(-t/(.055/(1+i*.5)))*(1/(i+1))
  out*=np.minimum(1,t/(.0014-.0006*strength))*strength**1.4*.65
 return signal.resample_poly(out,1,4)

def dry(kit,slot,z,take):
 key=(kit,slot,z,take)
 if key in cache:return cache[key]
 if slot<=4:
  x=read(SITE/f'round3/audio/{kit}-{roles[slot-1]}-{z}-{take}.wav')
 elif slot<=14:
  v=roles[slot-1];x=raw(v,z,take).copy();t=np.arange(len(x))/SR
  x*=np.exp(-np.maximum(0,t-starts[slot-1])/(taus[slot-1]*(1 if kit=='body' else .55)))*levels[slot-1];x=finish(x)
 else:
  # Six independent synthesized contacts per take rotate through the authored score.
  count=8 if slot==15 else 20;times=np.cumsum(np.r_[0,np.full(count-1,.055) if slot==15 else np.linspace(.13,.035,count-1)])
  x=np.zeros(round((times[-1]+1)*SR))
  for i,at in enumerate(times):
   contact=raw('snare',z,3+take*6+i%6).copy();t=np.arange(len(contact))/SR;contact*=np.exp(-np.maximum(0,t-.055)/(.15*(1 if kit=='body' else .55)))*.8
   # Alternating hands; the riser adds finite increasing event energy.
   gain=(.65 if i%2==0 else .43)*(1 if slot==15 else .25+.75*i/(count-1));start=round(at*SR);contact=contact[:len(x)-start];x[start:start+len(contact)]+=contact*gain
  x=finish(x)*levels[slot-1]
 cache[key]=x;return x

for kit in ['body','tight']:
 for slot in range(1,17):
  for z in range(3):
   for take in range(3):
    x=dry(kit,slot,z,take)
    for fx in ['close','knee','cassette']:
     y=x if fx=='close' else finish(treatment(np.pad(x,(0,SR)),fx))
     assert np.isfinite(y).all() and abs(y).max()<.98,(kit,slot,z,take,fx,float(abs(y).max()))
     name=f'{kit}-{fx}-{slot}-{z}-{take}.wav';path=OUT/'audio'/name
     if slot<=4 and fx=='close':shutil.copyfile(SITE/f'round3/audio/{kit}-{roles[slot-1]}-{z}-{take}.wav',path)
     else:wavfile.write(path,SR,np.round(y*32767).astype('<i2'))
     entries.append({'kit':kit,'fx':fx,'slot':slot,'name':names[slot-1],'zone':z,'take':take,'file':'audio/'+name,'frames':len(y),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
  print(kit,slot,names[slot-1],flush=True)
# An original four-bar groove exercises the full pad palette; R3 main voices are unchanged.
step=60/94/4;n=round((64*step+4)*SR);events=[]
for s in range(0,64,2):events.append((s,3,1 if s%4==0 else 0,.75))
for s in [0,7,10,16,22,26,32,39,42,48,54]:events.append((s,1,2 if s%8==0 else 1,1))
for s in [4,12,20,28,36,44,52]:events.append((s,2,2,.95))
for s in [3,11,19,27,35,43,51]:events.append((s,6,1,.85))
for s in [14,30,46]:events.append((s,4,1,.75))
for s in [6,38]:events.append((s,5,1,.9))
for s in [24,40]:events.append((s,8,2,.75))
for s in [15,31,47]:events.append((s,11,1,.7))
for s in range(32,48,4):events.append((s,12,1,.7))
for s in range(1,64,4):events.append((s,13,1,.65))
for s in [9,25,41]:events.append((s,14,1,.55))
events += [(55,15,1,.8),(56,10,2,.8),(57,9,2,.8),(58,16,1,.48),(63,7,2,.9)]
for kit in ['body','tight']:
 mix=np.zeros(n);hats=sorted(e[0] for e in events if e[1] in [3,4,11])
 for i,(s,slot,z,g) in enumerate(sorted(events)):
  x=dry(kit,slot,z,i%3).copy()*g
  if slot==4:
   later=[h for h in hats if h>s]
   if later:x=x[:round((later[0]-s)*step*SR)];f=min(220,len(x));x[-f:]*=np.linspace(1,0,f)
  at=round(s*step*SR);x=x[:n-at];mix[at:at+len(x)]+=x
 versions={fx:treatment(mix,fx) for fx in ['close','knee','cassette']};gain=min(1,.89/max(max(abs(y)) for y in versions.values()))
 for fx,y in versions.items():wavfile.write(OUT/f'audio/groove-{kit}-{fx}.wav',SR,np.round(y*gain*32767).astype('<i2'))
# Native WAV bank and SFZ grids: all takes, explicit velocity ranges and hat choke group.
packages={}
for kit in ['body','tight']:
 for fx in ['close','knee','cassette']:
  regions=[]
  for e in entries:
   if e['kit']!=kit or e['fx']!=fx:continue
   lo,hi=[(1,42),(43,84),(85,127)][e['zone']];group=' group=1 off_by=1 off_mode=fast' if e['slot'] in [3,4,11] else ''
   regions.append(f"<region> sample={e['file']} key={35+e['slot']} lovel={lo} hivel={hi} seq_length=3 seq_position={e['take']+1}{group}")
  sfz=f'{kit}-{fx}.sfz';(OUT/sfz).write_text('<group> loop_mode=one_shot\n'+'\n'.join(regions)+'\n')
  path=OUT/f'{kit}-{fx}.zip'
  with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as z:
   z.write(OUT/sfz,sfz)
   for e in entries:
    if e['kit']==kit and e['fx']==fx:z.write(OUT/e['file'],e['file'])
   z.writestr('README.txt','Knee Break R4 Python sound prototype. 16 pads, 3 velocity layers x 3 takes. MIDI keys 36-51. SFZ sequence/choke metadata verified; player-specific behaviour and MPC hardware unverified. Effects in these pads are printed per hit; groove comparisons use a shared bus. Sources are synthesized from fitted numeric parameters, plus procedural shaker/woodblock.\n')
   z.write(ROOT/'synth/src/main/resources/spore-station/v7/LICENSE.txt','REFERENCE-LICENSE.txt')
  packages[kit+'-'+fx]={'file':path.name,'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
# Independent decoded file and packaged-byte checks.
maxpeak=0.;maxdc=0.;minres=1.;preserved=0
for e in entries:
 y=read(OUT/e['file']);assert len(y)==e['frames'] and y[-1]==0;maxpeak=max(maxpeak,float(max(abs(y))));maxdc=max(maxdc,float(abs(y.mean())))
 if e['slot']<=4 and e['fx']=='close':
  assert (OUT/e['file']).read_bytes()==(SITE/f"round3/audio/{e['kit']}-{roles[e['slot']-1]}-{e['zone']}-{e['take']}.wav").read_bytes();preserved+=1
for kit in ['body','tight']:
 for fx in ['close','knee','cassette']:
  with zipfile.ZipFile(OUT/f'{kit}-{fx}.zip') as z:
   for e in entries:
    if e['kit']==kit and e['fx']==fx:assert z.read(e['file'])==(OUT/e['file']).read_bytes()
  for slot in range(1,17):
   energy=[]
   for zone in range(3):
    row=[read(OUT/f'audio/{kit}-{fx}-{slot}-{zone}-{t}.wav') for t in range(3)];energy.append(np.mean([a@a for a in row]))
    for i,a in enumerate(row):
     for b in row[i+1:]:
      n=max(len(a),len(b));aa=np.pad(a,(0,n-len(a)));bb=np.pad(b,(0,n-len(b)));r=float(np.sum((bb-aa*(aa@bb)/(aa@aa))**2)/(bb@bb));minres=min(minres,r);assert r>1e-6,(kit,fx,slot,zone)
   assert np.all(np.diff(energy)>0),(kit,fx,slot,energy)
assert maxdc<.005
report={'samples':len(entries),'approved_core_files_byte_identical':preserved,'maximum_peak':maxpeak,'maximum_dc':maxdc,'minimum_gain_removed_take_residual':minres,'velocity_order':'all 96 kit/treatment/pad combinations increase mean energy across three layers','packages':'all 864 WAVs byte-exact in six SFZ ZIPs','scope':'Python audition and SFZ grids; no native integration or hardware certification','render_and_check_seconds':time.perf_counter()-begin}
(OUT/'manifest.json').write_text(json.dumps({'samples':entries,'roles':names},indent=2));(OUT/'verification.json').write_text(json.dumps(report,indent=2));(OUT/'downloads.json').write_text(json.dumps(packages,indent=2));(OUT/'model.json').write_text(json.dumps(model,separators=(',',':')))
shutil.copyfile(ROOT/'synth/src/main/resources/spore-station/v7/LICENSE.txt',OUT/'LICENSE.txt');print(json.dumps(report),flush=True)
