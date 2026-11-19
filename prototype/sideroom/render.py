"""SIDEROOM R1: original procedural drum studies, Python harness, not Kotlin.
Probe/output workflow follows Luthier proto_template.py; decaying membrane and
metal models adapt the approach in SnipSnap scripts/soot_r0/soot_r0.py.
No reference recording is sampled. All acoustic constants are design values.
"""
from pathlib import Path
import hashlib,json,sys,time,wave,zipfile
import numpy as np
from scipy import signal
RATE=44100;OS=4;SR=RATE*OS
# Fundamental pitches, T60, contact and wire balance define different instruments.
KITS=[
 dict(id='open-road',name='Open Road',reference='Casey Jones',bpm=100,kick=62,kdec=.55,snare=175,sdec=.48,wire=.70,metal=.83,hat=.17,tom=128,tdec=1.00,contact=.65,description='Round heads, woody backbeat, loose metal and open toms.'),
 dict(id='tight-pocket',name='Tight Pocket',reference='Train in Vain',bpm=122,kick=72,kdec=.31,snare=225,sdec=.23,wire=.90,metal=1.06,hat=.085,tom=158,tdec=.49,contact=1.15,description='Short kick, pointed backbeat and clipped hat phrasing.'),
 dict(id='angular',name='Angular',reference='Fashion',bpm=116,kick=56,kdec=.24,snare=245,sdec=.18,wire=.48,metal=1.18,hat=.062,tom=180,tdec=.34,contact=1.35,description='Low dry kick, taut shell crack and precise metallic accents.'),
 dict(id='high-voltage',name='High Voltage',reference='I Believe in a Thing Called Love',bpm=128,kick=54,kdec=.70,snare=157,sdec=.62,wire=1.15,metal=.91,hat=.20,tom=108,tdec=1.35,contact=1.05,description='Large low shells, broad snare wires and long cymbal accents.'),
 dict(id='deep-pocket',name='Deep Pocket',reference='Get on Top',bpm=110,kick=66,kdec=.36,snare=285,sdec=.31,wire=.62,metal=1.0,hat=.105,tom=145,tdec=.72,contact=1.55,description='Firm kick, high shell bite, responsive ghost notes and shifting hats.')]
VOICES=['kick','snare','hat','openhat','tom','floor','ride','crash','roll','riser']
NAMES=['Kick','Snare','Closed hat','Open hat','Rack tom','Floor tom','Ride','Crash','One-bar roll','Two-bar lift']
MEM=np.array([1,1.593,2.136,2.296,2.653,2.918,3.156,3.50,3.60,4.06,4.23,4.61])
def rng(*parts):return np.random.default_rng(int.from_bytes(hashlib.sha256('|'.join(map(str,parts)).encode()).digest()[:8],'little'))
def filt(x,kind,hz):return signal.sosfilt(signal.butter(2,hz,btype=kind,fs=SR,output='sos'),x)
def peak(x):return float(np.max(abs(x))) if len(x) else 0.
def rms(x):return float(np.sqrt(np.mean(x*x))) if len(x) else 0.
def render(k,voice,d,take):
 v=[.34,.64,1.][d];r=rng('sideroom1',k['id'],voice,take);c=k['contact']
 seconds={'kick':1.05,'snare':1.15,'hat':.38,'openhat':1.35,'tom':1.7,'floor':2.,'ride':2.7,'crash':3.2}[voice]
 t=np.arange(round(seconds*SR))/SR;x=np.zeros(len(t));noise=r.standard_normal(len(t))
 jitter=r.uniform(.988,1.012);position=r.uniform(.26,.40)
 if voice in ('kick','snare','tom','floor'):
  f=k['kick'] if voice=='kick' else k['snare'] if voice=='snare' else k['tom']*(.64 if voice=='floor' else 1)
  decay=k['kdec'] if voice=='kick' else k['sdec'] if voice=='snare' else k['tdec']*(1.3 if voice=='floor' else 1)
  # Tension relaxes after impact, rather than a large electronic kick sweep.
  bend=1+(.025+.035*v)*np.exp(-t/.018)
  for j,ratio in enumerate(MEM):
   hz=f*ratio*jitter;level=(1 if j==0 else .45/(1+j*.28))*(.65+.35*np.cos(position*(j+1))**2)
   level*=1/(1+(hz/(f*(2.0+3.5*v*c)))**2)
   phase=2*np.pi*hz*np.cumsum(bend)/SR
   x+=level*np.sin(phase+r.uniform(-.08,.08))*np.exp(-6.9078*t/(decay/ratio**.65))
  contact=filt(noise,'bandpass',[700 if voice=='kick' else 1100,5500+1800*v])
  contact*=np.exp(-t/(.0018 if voice=='kick' else .001))
  x+=contact*(.12 if voice=='kick' else .28)*c*v
  # Unequal shell modes leave a wooden after-knock instead of a lone sine body.
  for hz,gain in ((f*4.81,.055),(f*7.13,.035)):
   x+=gain*np.sin(2*np.pi*hz*t)*np.exp(-6.9078*t/.075)
  if voice=='snare':
   wire=filt(noise,'bandpass',[1400-450*v,10800])
   flutter=1+.22*np.sin(2*np.pi*(115+15*position)*t)+.15*np.sin(2*np.pi*371*t)
   env=(1-np.exp(-t/.0007))*np.exp(-6.9078*t/(decay*(.8+.22*v)))
   # Wires follow head-energy and strike force; soft strikes aren't quiet hard hits.
   x=x*.85+wire*env*flutter*k['wire']*(.55+.7*v)
 else:
  identity=rng('metal-identity',k['id'],voice in ('ride','crash'))
  base=np.geomspace(2100,15200,48)*identity.uniform(.94,1.06,48)*k['metal']
  decay=k['hat'] if voice=='hat' else k['hat']*5.2 if voice=='openhat' else 2.9 if voice=='ride' else 4.0
  if voice=='ride':base=np.r_[base,[620,1170,2430]*np.array(k['metal'])]
  for j,hz in enumerate(base):
   if hz>18000:continue
   weight=(1/(1+(hz/(4000+8000*v))**2))*r.uniform(.7,1.3)/np.sqrt(len(base))
   tau=decay*(4500/hz)**.34
   x+=weight*np.sin(2*np.pi*hz*jitter*t+r.uniform(0,6.28))*np.exp(-6.9078*t/tau)
  wash=filt(noise,'bandpass',[2800,15000])
  x+=wash*np.exp(-6.9078*t/(decay*.7))*(.16 if voice in ('hat','openhat') else .33)
  if voice=='crash':x*=1-np.exp(-t/.006)
  if voice=='ride':x+=.22*np.sin(2*np.pi*1900*k['metal']*t)*np.exp(-6.9078*t/.24)
 # No saturation, compression or room: establish the drum identity first.
 x*=1-np.exp(-t/(.00015+.0003*(1-v)))
 x=signal.resample_poly(x,1,OS,window=('kaiser',9.0))
 x=signal.sosfilt(signal.butter(2,22,btype='highpass',fs=RATE,output='sos'),x)
 x*=v**1.18
 fade=min(441,len(x));x[-fade:]*=np.linspace(1,0,fade);x[:32]*=np.linspace(0,1,32)
 return x

def write(p,x):
 p.parent.mkdir(parents=True,exist_ok=True)
 assert np.isfinite(x).all() and peak(x)<.95
 pcm=np.rint(x*32767).astype('<i2')
 with wave.open(str(p),'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(RATE);w.writeframes(pcm.tobytes())
 with wave.open(str(p),'rb') as w:y=np.frombuffer(w.readframes(w.getnframes()),'<i2').astype(float)/32767
 assert np.max(abs(x-y))<=.5001/32767
 return pcm.tobytes()

def compose(bank,k,events,seconds,take_offset=0):
 out=np.zeros(round(seconds*RATE));counts={};last_open=None
 for beat,voice,d,gain in sorted(events,key=lambda e:e[0]):
  at=round(beat*60/k['bpm']*RATE);n=counts.get((voice,d),0);take=(n+take_offset)%4;counts[(voice,d)]=n+1
  hit=bank[(voice,d,take)].copy()
  if voice=='hat' and last_open is not None:
   start,old=last_open;cut=max(0,at-start);n=min(len(old)-cut,round(.008*RATE))
   if n>0:
    old[cut:cut+n]*=1-np.linspace(1,0,n);out[start+cut:start+len(old)]-=old[cut:];last_open=None
  n=min(len(hit),len(out)-at)
  if n<=0:continue
  out[at:at+n]+=hit[:n]*gain
  if voice=='openhat':last_open=(at,hit[:n]*gain)
 out[-441:]*=np.linspace(1,0,441);return out

def main(dst):
 start=time.perf_counter();dst=Path(dst);dst.mkdir(parents=True,exist_ok=True);manifest={'version':'sideroom-r1','rate':RATE,'voices':dict(zip(VOICES,NAMES)),'kits':[]};report={'kits':{},'claim':'Python prototype only; no native or hardware completion claim'}
 for k in KITS:
  bank={};entries=[];metrics={};folder=dst/k['id'];folder.mkdir(exist_ok=True)
  for voice in VOICES[:8]:
   raw={(d,t):render(k,voice,d,t) for d in range(3) for t in range(4)}
   # One calibration per instrument preserves dynamics and performance variation.
   gain={'kick':.78,'snare':.70,'hat':.30,'openhat':.34,'tom':.62,'floor':.68,'ride':.39,'crash':.46}[voice]/max(peak(x) for x in raw.values())
   for (d,t),x in raw.items():bank[(voice,d,t)]=x*gain
   energies=[np.mean([rms(bank[(voice,d,t)]) for t in range(4)]) for d in range(3)]
   assert energies[0]<energies[1]<energies[2],(k['id'],voice,energies)
   assert np.array_equal(raw[(2,0)],render(k,voice,2,0)), 'determinism'
  for voice in ('roll','riser'):
   bars=1 if voice=='roll' else 2
   for d in range(3):
    for take in range(4):
     events=[]
     for i in range(12 if bars==1 else 20):
      beat=i*.25 if bars==1 else 8*(1-(1-i/20)**1.6)
      role=('snare','tom','floor')[(i//4)%3]
      events.append((beat,role,d,.42+.28*i/(12 if bars==1 else 20)))
     events.append((bars*4-.25,'snare',d,.8))
     bank[(voice,d,take)]=compose(bank,k,events,bars*4*60/k['bpm']+1.4,take)*.7
  sfz=['// SIDEROOM R1, original procedural recordings. Three velocity levels, four takes.','<control> default_path=audio/']
  for vi,voice in enumerate(VOICES):
   for d,(lo,hi) in enumerate(((1,41),(42,84),(85,127))):
    hashes=[]
    for take in range(4):
     x=bank[(voice,d,take)];name=f'{voice}-v{d}-r{take}.wav';pcm=write(folder/'audio'/name,x)
     digest=hashlib.sha256(pcm).hexdigest();hashes.append(digest)
     assert abs(float(np.mean(x)))<.003 and x[0]==0 and x[-1]==0
     entries.append({'voice':voice,'dynamic':d,'take':take,'file':k['id']+'/audio/'+name,'frames':len(x),'sha256pcm':digest})
     choke=' group=1 off_by=1 off_mode=fast' if voice in ('hat','openhat') else ''
     sfz.append(f'<region> sample={name} key={36+vi} lovel={lo} hivel={hi} seq_length=4 seq_position={take+1} loop_mode=one_shot amp_veltrack=0{choke}')
    assert len(set(hashes))==4,(k['id'],voice,d)
   metrics[voice]={'rmsLayers':[round(float(np.mean([rms(bank[(voice,d,t)]) for t in range(4)])),6) for d in range(3)],'peak':round(max(peak(bank[(voice,d,t)]) for d in range(3) for t in range(4)),5)}
  events=[]
  kicks={'open-road':[0,1.5,2.75],'tight-pocket':[0,1.75,2.5,3.5],'angular':[0,.75,2,3.25],'high-voltage':[0,.5,2,2.75],'deep-pocket':[0,.75,1.5,2.75,3.5]}[k['id']]
  for bar in range(4):
   for b in kicks:events.append((bar*4+b,'kick',2,.85))
   for b in (1,3):events.append((bar*4+b,'snare',2,.82))
   if k['id'] in ('open-road','deep-pocket'):events.extend([(bar*4+2.5,'snare',0,.60),(bar*4+3.75,'snare',0,.5)])
   step=.25 if k['id'] in ('deep-pocket','angular') else .5
   for i,b in enumerate(np.arange(0,4,step)):
    role='openhat' if i==int(4/step)-1 and bar%2 else 'hat'
    events.append((bar*4+float(b),role,1 if i%2==0 else 0,.8))
   if bar==3:
    for i in range(4):events.append((15+i*.25,'tom' if i<2 else 'floor',1,.5))
  groove=compose(bank,k,events,16*60/k['bpm']+2.5);groove*=min(1,.88/max(peak(groove),1e-8));write(folder/'groove.wav',groove)
  (folder/'sideroom.sfz').write_text('\n'.join(sfz)+'\n')
  km={**k,'entries':entries,'groove':k['id']+'/groove.wav'};manifest['kits'].append(km)
  (folder/'manifest.json').write_text(json.dumps(km,indent=2));report['kits'][k['id']]=metrics
  with zipfile.ZipFile(dst/(k['id']+'.zip'),'w',zipfile.ZIP_DEFLATED) as z:
   for p in sorted(folder.rglob('*')):
    if p.is_file():z.write(p,p.relative_to(folder))
  print(k['name'],'120 checked renders and groove',flush=True)
 report['renderSeconds']=round(time.perf_counter()-start,2)
 (dst/'manifest.json').write_text(json.dumps(manifest));(dst/'report.json').write_text(json.dumps(report,indent=2))
 print('Complete',report['renderSeconds'],'seconds',flush=True)
if __name__=='__main__':main(sys.argv[1])
