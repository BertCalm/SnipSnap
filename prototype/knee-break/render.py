"""First sound proof, not an integrated engine. Reuses EMBER's acoustic model.
Run: OPENBLAS_NUM_THREADS=1 python prototype/knee-break/render.py OUT
No audio from the user's reference videos is included in the outputs.
"""
from pathlib import Path
import sys,json,hashlib,time,shutil
import numpy as np
from scipy import signal
from scipy.io import wavfile
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'prototype/ember'))
import ember_proto as model
SR=44100
VOICES={'kick':('KICK',{'TUNE':.24,'DAMP':.86,'BUZZ':.05},.78), 'snare':('SNARE',{'TUNE':.12,'DAMP':.88,'BUZZ':.55},.66), 'closed-hat':('HAT_CLOSED',{'TUNE':.36,'DAMP':.8,'BUZZ':0},.26), 'open-hat':('HAT_OPEN',{'TUNE':.36,'DAMP':.7,'BUZZ':0},.25)}
VELOCITIES=[.38,.68,1.]
def filt(x,hz,kind='lowpass'):
 return signal.sosfilt(signal.butter(2,hz,fs=SR,btype=kind,output='sos'),x)
def trim(x):
 # Fixed absolute floor, with an 8 ms fade and 20 ms of terminal padding.
 x=filt(x,25,'highpass'); nz=np.flatnonzero(abs(x)>10**(-65/20))
 n=min(len(x),int(nz[-1])+int(.028*SR)) if len(nz) else 1
 x=x[:n].copy();fade=min(n,round(.008*SR));x[-fade:]*=np.linspace(1,0,fade)
 return np.r_[x,np.zeros(round(.02*SR))]
def treatment(x,kind):
 if kind=='close':return x.copy()
 # Proposed knee-position colour: reduced top and a short shared reflection.
 knee=filt(filt(x,90,'highpass'),4700);d=round(.011*SR);knee[d:]+=.17*knee[:-d].copy()
 # 5 ms RMS detector, peak hold with 105 ms release; gain reduction is shared
 # by the summed groove. Separately printed pads cannot reproduce its pumping.
 power=signal.lfilter([1/(.005*SR)],[1,-(1-1/(.005*SR))],knee*knee)
 env=np.sqrt(np.maximum(power,0));gain=np.minimum(1,(.075/np.maximum(env,1e-8))**.75)
 release=np.exp(-1/(.105*SR));last=1.
 for i in range(len(gain)):
  last=min(gain[i],release*last+(1-release)*gain[i]);gain[i]=last
 knee*=gain*1.8
 if kind=='knee':return .56*x+.54*knee
 # Saturation at 4x, then anti-aliased downsample. No claim of a particular plugin model.
 up=signal.resample_poly(knee,4,1);wet=signal.resample_poly(np.tanh(up*2.2)/2.2,1,4)[:len(x)]
 wet=filt(wet,3900);idx=np.arange(len(wet));warped=idx+SR*.00045*np.sin(2*np.pi*.7*idx/SR)
 wet=np.interp(warped,idx,wet,left=0,right=0)
 out=.58*x+.48*wet
 # Fixed 160 ms echo, 0.27 gain per pass, four finite repeats.
 for j in range(1,5):
  d=round(.16*j*SR)
  if d<len(out):out[d:]+=.25*(.27**(j-1))*wet[:-d]
 return out

def main(out):
 out.mkdir(parents=True,exist_ok=True);audio=out/'audio';audio.mkdir(exist_ok=True)
 cache={};entries=[];start=time.perf_counter()
 for name,(voice,push,level) in VOICES.items():
  for z,v in enumerate(VELOCITIES):
   for take in range(3):
    m={'DISTANCE':0.,'WARMTH':0.,**push}
    # EMBER's preview normalizes peaks. Restore a declared velocity gain here;
    # the source also changes contact shape and wire/plate response with velocity.
    x=model.render(voice,m,velocity=v,take=take)*level*v**1.25
    x=trim(x);cache[name,z,take]=x
    for kind in ['close','knee','cassette']:
     y=trim(treatment(np.pad(x,(0,SR)),kind))*.7
     file=f'{kind}-{name}-{z}-{take}.wav';pcm=np.round(np.clip(y,-1,1)*32767).astype('<i2');wavfile.write(audio/file,SR,pcm)
     entries.append({'file':'audio/'+file,'treatment':kind,'voice':name,'zone':z,'velocity':v,'take':take,'frames':len(pcm),'peak':float(abs(y).max()),'dc':float(abs(y.mean())),'sha256':hashlib.sha256((audio/file).read_bytes()).hexdigest()})
  print(name,'rendered',flush=True)
 # Original two-bar pattern at 94 BPM; source timings were not transcribed.
 bpm=94;step=60/bpm/4;n=round((32*step+2)*SR);mix=np.zeros(n);events=[]
 for s in range(0,32,2):events.append((s,'closed-hat',1 if s%4==0 else 0))
 for s in [0,7,10,16,22,26]:events.append((s,'kick',2 if s%8==0 else 1))
 for s in [4,12,20,28]:events.append((s,'snare',2))
 for s in [3,11,19,23,27,30]:events.append((s,'snare',0))
 # The open hat replaces the closed hat, then closes at the next hat event.
 events=[e for e in events if not(e[0] in [14,30] and e[1]=='closed-hat')]+[(14,'open-hat',1),(30,'open-hat',1)]
 hats=sorted(e[0] for e in events if 'hat' in e[1])
 for i,(s,voice,z) in enumerate(sorted(events)):
  x=cache[voice,z,i%3].copy();at=round(s*step*SR)
  if voice=='open-hat':
   nexts=[h for h in hats if h>s]
   if nexts:
    length=min(len(x),round((nexts[0]-s)*step*SR));x=x[:length];f=min(220,length);x[-f:]*=np.linspace(1,0,f)
  mix[at:at+len(x)]+=x
 grooves={k:treatment(mix,k) for k in ['close','knee','cassette']}
 common=min(1,.88/max(float(abs(y).max()) for y in grooves.values()))
 for kind,y in grooves.items():wavfile.write(audio/f'groove-{kind}.wav',SR,np.round(y*common*32767).astype('<i2'))
 # Decoded-file audit, not merely an audit of in-memory synthesis.
 minimum=1.
 for kind in ['close','knee','cassette']:
  for voice in VOICES:
   energies=[]
   for z in range(3):
    takes=[]
    for take in range(3):
     _,p=wavfile.read(audio/f'{kind}-{voice}-{z}-{take}.wav');a=p.astype(float)/32768
     assert np.isfinite(a).all() and abs(a).max()<.95 and abs(a.mean())<.005 and a[-1]==0
     takes.append(a)
    energies.append(np.mean([np.sum(a*a) for a in takes]))
    for a,b in [(takes[0],takes[1]),(takes[0],takes[2]),(takes[1],takes[2])]:
     n=max(len(a),len(b));a=np.pad(a,(0,n-len(a)));b=np.pad(b,(0,n-len(b)));g=np.dot(a,b)/np.dot(a,a);res=np.sum((b-g*a)**2)/np.sum(b*b);minimum=min(minimum,res);assert res>1e-6
   assert np.all(np.diff(energies)>0),(kind,voice,energies)
 # Exact deterministic re-render of one source.
 voice,push,level=VOICES['kick'];again=trim(model.render(voice,{'DISTANCE':0.,'WARMTH':0.,**push},velocity=1.,take=0)*level)
 assert np.array_equal(again,cache['kick',2,0])
 report={'status':'Python sound proof; not Kotlin or MPC integration','samples':len(entries),'minimum_take_residual_after_gain_removal':minimum,'velocity_order':'mean layer energy strictly increases in each voice/treatment','determinism':'kick hard take 0 repeated exactly','render_seconds':time.perf_counter()-start,'groove_bpm':bpm,'groove_common_gain':common,'reference_matching':'visual evidence and whole-mix measurements; no isolated-hit fit or listening approval'}
 (out/'manifest.json').write_text(json.dumps({'samples':entries,'report':report},indent=2));(out/'verification.json').write_text(json.dumps(report,indent=2));shutil.copyfile(ROOT/'prototype/ember/NOTICE.txt',out/'NOTICE.txt')
 html=['<!doctype html><meta charset="utf-8"><title>Knee Break · First sound proof</title><style>body{background:#202624;color:#f4e9ce;font:17px system-ui;max-width:900px;margin:40px auto;padding:20px}audio{width:100%}section{background:#303a35;padding:20px;margin:16px 0;border-radius:12px}a{color:#f7c576}</style><h1>Knee Break</h1><p>First sound proof. Four core voices, three velocities, three independent takes. Original synthesized audio; no reference-video samples. These are listening candidates, not a finished MPC kit.</p><h2>Same groove, three treatments</h2><p>The two-bar pattern is original, at 94 BPM. Treatment is applied to the whole groove so the shared compressor can respond to overlapping hits. Fixed common gain, no individual loudness matching.</p>']
 for kind in ['close','knee','cassette']:
  html.append(f'<section><h3>{kind.title()}</h3><audio controls src="audio/groove-{kind}.wav"></audio></section>')
 html.append('<h2>Individual hits</h2><p>Select the treatment, voice, velocity and strike. Individually printed pads do not recreate shared-bus pumping.</p>')
 for key,options in [('treatment',['close','knee','cassette']),('voice',list(VOICES)),('velocity',['soft','medium','hard']),('take',['1','2','3'])]:
  html.append(f'<label>{key} <select id="{key}">'+''.join(f'<option value="{i if key in ["velocity","take"] else v}">{v}</option>' for i,v in enumerate(options))+'</select></label> ')
 html.append('<p><audio id="hit" controls></audio></p><p><a href="verification.json">Measured checks</a> · <a href="NOTICE.txt">Source credits</a></p><script>const ids=["treatment","voice","velocity","take"];function update(){document.getElementById("hit").src="audio/"+ids.map(id=>document.getElementById(id).value).join("-")+".wav";}ids.forEach(id=>document.getElementById(id).onchange=update);update();</script>')
 (out/'index.html').write_text(''.join(html));print(json.dumps(report,indent=2),flush=True)
if __name__=='__main__':main(Path(sys.argv[1]))
