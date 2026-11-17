"""R5 source descriptors and synthesized eight-piece Open Road study."""
from pathlib import Path
import concurrent.futures,json,sys,shutil,zipfile,time
import numpy as np
from scipy import signal
from scipy.io import wavfile
import acoustic,resynth
ROOT=Path(__file__).resolve().parent
NEW=['tom','floor','ride','crash'];CORE=['kick','snare','hat','openhat']
KEYS=dict(zip(CORE+NEW,[36,38,42,46,48,41,51,49]))
def sources(out):
 chosen=json.loads((ROOT/'expansion-sources.json').read_text())
 data=list(concurrent.futures.ThreadPoolExecutor(6).map(acoustic.fetch,chosen))
 lookup={Path(s['path']).stem:(s,x,h) for s,x,h in data};provenance=[]
 for voice,prefix in [('tom','mid_htom_center'),('floor','mid_ltom_center'),('ride','oh_ride_ride'),('crash','oh_crash_crash')]:
  hits=[]
  for level in range(3):
   for rr in range(3):
    name=f'{prefix}_vl{[4,9,14][level]+rr}' if voice in ('tom','floor') else f'{prefix}_vl{level+1}_rr{rr+1}'
    s,x,h=lookup[name];x=signal.sosfilt(signal.butter(2,22,fs=44100,btype='highpass',output='sos'),x)
    x=signal.sosfilt(signal.butter(2,9000 if voice in ('tom','floor') else 12000,fs=44100,output='sos'),x)
    # Retain recorded decay up to a finite 10-second cymbal / 4-second tom study tail.
    x=x[:int((4 if voice in ('tom','floor') else 10)*44100)].copy();x[:44]*=np.linspace(0,1,44);x[-2205:]*=np.linspace(1,0,2205)
    hits.append((level,rr,x));provenance.append({'voice':voice,'level':level,'take':rr,'url':acoustic.URL+s['path'],'git_blob':s['sha'],'source_sha256':h})
  gain={'tom':.63,'floor':.65,'ride':.28,'crash':.38}[voice]/max(np.max(abs(x)) for l,r,x in hits)
  for l,r,x in hits:
   p=out/'open-road'/f'{voice}-{l}-{r}.wav';p.parent.mkdir(parents=True,exist_ok=True);wavfile.write(p,44100,np.rint(x*gain*32767).astype(np.int16))
 return provenance

def mix(bank,fill=False):
 beat=.6;events=[];counts={v:0 for v in CORE+NEW}
 if fill:
  for i,v in enumerate(['snare','snare','tom','tom','floor','floor','snare','floor']):events.append((i*.25*beat,v,1 if i%2 else 2,.8))
  events.extend([(2*beat,'kick',2,.9),(2*beat,'crash',2,.7)])
  length=2*beat+10
 else:
  for step in range(32):
   b=step/2;v=('ride' if step>=16 else 'hat')
   if step==15:v='openhat'
   events.append((b*beat,v,2 if step%2==0 else 1,1.0))
   if step%8 in (0,4,7):events.append((b*beat,'kick',2 if step%8==0 else 1,1))
   if step%8 in (2,6) and step<28:events.append((b*beat+.008,'snare',2,1))
  for i,v in enumerate(['snare','tom','tom','floor','floor','floor','snare','floor']):events.append(((14+i*.25)*beat,v,2 if i%2==0 else 1,.8))
  events.extend([(0,'crash',1,.65),(16*beat,'crash',2,.7),(16*beat,'kick',2,.9)])
  length=16*beat+10
 length=max(length,max(tm+max(len(bank[v,l,r]) for r in range(3))/44100 for tm,v,l,g in events))
 out=np.zeros(round(length*44100));ordered=sorted(events)
 for tm,v,l,gain in ordered:
  rr=counts[v]%3;counts[v]+=1;x=bank[v,l,rr].copy()*gain
  if v=='openhat':
   stop=next((t for t,w,l,g in ordered if w=='hat' and t>tm),8*beat)
   x=x[:max(220,round((stop-tm)*44100))];x[-220:]*=np.linspace(1,0,220)
  at=round(tm*44100);n=min(len(x),len(out)-at);out[at:at+n]+=x[:n]
 out[-2205:]*=np.linspace(1,0,2205);return out

def main(approved,out):
 out.mkdir(parents=True,exist_ok=True);source=out/'analysis-source';provenance=sources(source)
 # Analyze only new voices, then retain the exact approved core descriptor grids.
 resynth.VOICES=NEW;model=resynth.analyze(source);core=json.loads((approved/'synthesis-model.json').read_text())
 model['voices']={**core['voices'],**model['voices']};model['version']=3
 (out/'synthesis-model.json').write_text(json.dumps(model,separators=(',',':')))
 model=json.loads((out/'synthesis-model.json').read_text());banks={m:{} for m in ('synth','acoustic')};times=[]
 for v in CORE+NEW:
  for l in range(3):
   for rr in range(3):
    if v in CORE:
     banks['synth'][v,l,rr]=resynth.read(approved/'synth'/f'{v}-{l}-{rr}.wav')
     banks['acoustic'][v,l,rr]=resynth.read(approved/'acoustic'/f'{v}-{l}-{rr}.wav')
    else:
     x,ms=resynth.render(model,v,l,rr);times.append(ms);assert np.array_equal(x,resynth.render(model,v,l,rr)[0])
     banks['synth'][v,l,rr]=x;banks['acoustic'][v,l,rr]=resynth.read(source/'open-road'/f'{v}-{l}-{rr}.wav')
 # Only calibrate added instruments if needed; approved core PCM remains byte-identical.
 added_gain=min(1,.90/max(np.max(abs(x)) for bank in banks.values() for (v,l,r),x in bank.items() if v in NEW))
 manifest={'revision':'R5 expanded synthesized kit','levels':['Soft','Medium','Hard'],'takes':3,'modes':['synth','acoustic'],'voices':CORE+NEW,'keys':KEYS,'files':{}}
 def write(p,x):
  assert np.isfinite(x).all() and np.max(abs(x))<.99;p.parent.mkdir(parents=True,exist_ok=True);wavfile.write(p,44100,np.rint(x*32767).astype(np.int16))
 for mode,bank in banks.items():
  regions=[]
  for (v,l,r),x in bank.items():
   rel=f'{mode}/{v}-{l}-{r}.wav'
   if v in CORE:
    (out/mode).mkdir(exist_ok=True);shutil.copyfile(approved/mode/f'{v}-{l}-{r}.wav',out/rel)
   else:bank[v,l,r]=x*added_gain;write(out/rel,bank[v,l,r])
   manifest['files'][f'{mode}/{v}/{l}/{r}']=rel
   choke=' group=1 off_by=1 off_mode=fast' if v in ('hat','openhat') else ''
   regions.append(f'<region> sample={v}-{l}-{r}.wav key={KEYS[v]} lovel={[1,51,96][l]} hivel={[50,95,127][l]} seq_length=3 seq_position={r+1} amp_veltrack=0 loop_mode=one_shot{choke}')
  (out/mode/'kit.sfz').write_text('\n'.join(regions)+'\n')
 for phrase in ('groove','fill'):
  mixes={mode:mix(bank,phrase=='fill') for mode,bank in banks.items()};gain=.85/max(np.max(abs(x)) for x in mixes.values())
  for mode,x in mixes.items():write(out/mode/f'{phrase}.wav',x*gain)
 (out/'manifest.json').write_text(json.dumps(manifest,indent=2))
 for name in ('CC0.txt','NOTICE.txt'):shutil.copy(approved/name,out/name)
 (out/'NOTICE.txt').write_text((out/'NOTICE.txt').read_text()+'R5 adds synthesized rack/floor toms, ride and crash from the same pinned CC0 library. All new source selections and conversion details are in provenance.json.\n')
 old=json.loads((approved/'provenance.json').read_text());old['expansion_files']=provenance;old['expansion_note']='Toms: adjacent source dynamics 4–6, 9–11, 14–16. Ride/crash: source dynamics 1–3 and RR 1–3. Tails bounded to 4/10 seconds, high-pass 22 Hz and low-pass 9/12 kHz. Fixed per-instrument calibration.'
 (out/'provenance.json').write_text(json.dumps(old,indent=2));shutil.rmtree(source)
 report={'new_performances':36,'total_performances':144,'approved_core':'PCM byte-identical to R4','median_new_render_ms':float(np.median(times)),'max_new_render_ms':max(times),'new_instrument_gain':added_gain}
 (out/'synthesis-report.json').write_text(json.dumps(report,indent=2))
 with zipfile.ZipFile(out/'open-road-acoustic.zip','w',zipfile.ZIP_DEFLATED) as z:
  for p in sorted(out.rglob('*')):
   if p.is_file() and p.suffix in ('.wav','.sfz','.json','.txt'):z.write(p,p.relative_to(out))
 print(json.dumps(report,indent=2))
if __name__=='__main__':main(Path(sys.argv[1]),Path(sys.argv[2]))
