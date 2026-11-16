"""Build procedural banks from synth.py plus authored event scores; reads no source WAV."""
from pathlib import Path
import json,time,hashlib,shutil
from functools import lru_cache
import numpy as np
from scipy.io import wavfile
import synth
R=Path(__file__).resolve().parent;A=R/'audio';A.mkdir(exist_ok=True);S=R/'scores';S.mkdir(exist_ok=True)
SR=44100;Z=[.34,.64,1.];TAKES=3
CORE=synth.VOICES[:14]+['hand-roll','shaker-rise'];COLORS=synth.VOICES[14:];VOICES=CORE+COLORS
KITS={'bongo-flava':(100,1.,1.),'singeli':(200,1.08,2.),'bongopiano':(112,1.,.65),'genge':(96,.97,1.4),'kapuka':(108,1.03,1.15)}
TARGET=dict(zip(CORE[:14],[.18,.15,.12,.105,.07,.045,.05,.035,.095,.08,.10,.055,.10,.09]));TARGET.update(dict(zip(COLORS,[.06,.095,.045,.065,.05,.045])))
GAIN={};BANK={}
def write(p,a):
 assert np.isfinite(a).all() and np.max(abs(a))<1,(p,np.max(abs(a)))
 wavfile.write(p,SR,np.round(a*32767).astype(np.int16))
def add(b,x,at,gain=1):
 i=round(at*SR);n=min(len(x),len(b)-i)
 if n>0:b[i:i+n]+=x[:n]*gain

def source_scores():
 for name in [*[f'{k}.json' for k in KITS],'colors.json','singeli-pocket.json']:
  if not (S/name).is_file():raise FileNotFoundError(S/name)

def main():
 start=time.perf_counter();source_scores();times=[]
 # Single fixed calibration for each voice, across all zones, takes and kits.
 for v in synth.VOICES:
  x=synth.render(v);GAIN[v]=1.
 for k,(bpm,tune,damp) in KITS.items():
  for v in synth.VOICES:
   for z,vel in enumerate(Z):
    for t in range(TAKES):
     at=time.perf_counter();BANK[k,v,z,t]=synth.render(v,vel,t,tune,damp)*GAIN[v];times.append((time.perf_counter()-at)*1000)
  for v in CORE[-2:]:
   for z in range(3):
    for t in range(TAKES):
     duration=240/bpm;x=np.zeros(round((duration+1.2)*SR))
     for j in range(16):
      name=['low-bongo','high-bongo','conga'][j%3] if v=='hand-roll' else 'shaker'
      at=duration*(j/16)**(.85 if v=='hand-roll' else 1)
      add(x,BANK[k,name,z,(t+j)%3],at,.38+.48*j/15)
     x[-441:]*=np.linspace(1,0,441);BANK[k,v,z,t]=x
 bankgain=min(1,.82/max(np.max(abs(x)) for x in BANK.values()));BANK.update({k:x*bankgain for k,x in BANK.items()})
 for k in KITS:
  folder=A/k;folder.mkdir(exist_ok=True)
  for v in VOICES:
   for z in range(3):
    for t in range(3):write(folder/f'{VOICES.index(v)+1:02d}-{v}-v{z+1}-r{t+1}.wav',BANK[k,v,z,t])
 grooves={};cs=json.loads((S/'colors.json').read_text())
 for k,(bpm,_,_) in KITS.items():
  if k=='singeli':continue
  events=json.loads((S/f'{k}.json').read_text())['events'];x=np.zeros(round((960/bpm+1.5)*SR))
  for e in events:
   v=CORE[e['pad']];a=BANK[k,v,e['zone'],e['take']]
   if v=='open-hat':
    nxt=[q['beat'] for q in events if q['pad'] in (5,6) and q['beat']>e['beat']]
    if nxt:
     a=a[:round((min(nxt)-e['beat'])*60/bpm*SR)].copy();f=min(176,len(a));a[-f:]*=np.linspace(1,0,f)
   add(x,a,e['beat']*60/bpm,e['gain'])
  for e in cs[k]:add(x,BANK[k,e['voice'],e['zone'],e['take']],e['beat']*60/bpm,e['gain'])
  grooves[k]=x
 # Retain the revised eight-bar Singeli score, including event roles, gates and gains.
 score=json.loads((S/'singeli-pocket.json').read_text());rolemap={'kick':'tight-kick','snare':'snare','rim':'rim','hat':'closed-hat','tap':'muted-tap','wood':'wood-knock','low':'metal-low','high':'metal-high'}
 n=round(32*60/200*SR);loop=np.zeros(n)
 for e in score['events']:
  c=score['roles'][e['role']];a=BANK['singeli',rolemap[e['role']],e['zone'],e['take']][:round(c['duration']*SR)].copy();a[-176:]*=np.linspace(1,0,176)
  idx=(round(e['beat']*SR*3/10)+np.arange(len(a)))%n;np.add.at(loop,idx,a*e['gain']*c['gain'])
 grooves['singeli']=np.tile(loop,2);grooves['singeli'][-441:]*=np.linspace(1,0,441)
 mixgain=min(.8,.86/max(np.max(abs(x)) for x in grooves.values()))
 for k,x in grooves.items():write(A/f'{k}-synthesis.wav',x*mixgain)
 write(A/'singeli-loop.wav',loop*mixgain)
 write(A/'all-pads.wav',np.concatenate([np.r_[BANK['bongo-flava',v,2,0],np.zeros(8820)] for v in VOICES]))
 touch=['tight-kick','snare','low-bongo','closed-hat','shaker','wood-knock']
 write(A/'touch.wav',np.concatenate([np.r_[BANK['bongo-flava',v,z,t],np.zeros(4410)] for v in touch for z in range(3) for t in range(3)]))
 manifest=dict(status='Reference-calibrated synthesis; recordings used offline for scalar fitting only',voices=VOICES,kits=KITS,velocity=Z,takes=3,variants=len(BANK),internal_rate=SR*4,output_rate=SR,fixed_voice_gains=GAIN,fixed_bank_gain=bankgain,fixed_mix_gain=mixgain,render_median_ms=float(np.median(times)),render_p95_ms=float(np.percentile(times,95)),seconds_to_build=time.perf_counter()-start,repo_read='494af88e6545f8ee03ced33cf2dad4598fc67248',module_sha256=hashlib.sha256((R/'synth.py').read_bytes()).hexdigest())
 (R/'manifest.json').write_text(json.dumps(manifest,indent=2));print(json.dumps({k:v for k,v in manifest.items() if k not in ['fixed_voice_gains','voices','kits']},indent=2))
if __name__=='__main__':main()
