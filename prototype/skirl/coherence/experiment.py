"""Isolated contact-coherence tests; Python, no native engine changes."""
from pathlib import Path
import sys,json,math,argparse
import numpy as np
from scipy import signal
ROOT=Path(__file__).resolve().parents[1]/'reference'
sys.path.insert(0,str(ROOT))
import mechanics as m
import render_audition as r
import snipkit as sk
from first_proof import pitch_nsdf
HERE=Path(__file__).resolve().parent
RATE=sk.INTERNAL

def java_hash(s):
 h=0
 for c in s:h=(h*31+ord(c))&0xffffffff
 return h-2**32 if h>=2**31 else h

def seed_for(*parts):
 h=17
 for s in parts:h=(h*31+java_hash(str(s)))&0xffffffff
 return h-2**32 if h>=2**31 else h

def noise(seed,n):
 s=seed if seed else 1
 a=np.empty(n)
 for i in range(n):
  s=(s*1103515245+12345)&0x7fffffff
  a[i]=np.float32(np.float32(s)/np.float32(0x3fffffff)-np.float32(1))
 return a

SEED=seed_for('SKIRL',1,'WHIRR')

def signed_add(routes,p,start,budget,direction):
 end=min(routes.shape[1],start+len(p))
 if end<=start:return
 d=np.array(direction);d/=np.linalg.norm(d)
 proposed=d[:,None]*p[None,:end-start]
 old=routes[:,start:end]
 q=np.sum(proposed**2)/RATE;c=np.sum(proposed*old)/RATE
 if q<=0:return
 root=budget/(math.sqrt(c*c+q*budget)+c) if c>=0 else (-c+math.sqrt(c*c+q*budget))/q
 a=min(math.sqrt(budget),root)
 old+=a*proposed
 assert a<=math.sqrt(budget)*(1+1e-14)
 assert q*a*a+2*c*a<=budget+1e-12

def pulse(width,variant,event,index,lut):
 n=max(4,round(width*RATE));u=np.arange(n)/(n-1)
 base=np.sin(np.pi*u)**2
 if variant.startswith('rim'):
  phase=(event['rim_phase_rad']/(2*np.pi))%1*len(lut)
  lo=int(phase);f=phase-lo
  value=lut[lo]*(1-f)+lut[(lo+1)%len(lut)]*f
  p=base*(1 if value>=0 else -1)
 elif variant=='hash_sign':p=base*(1 if noise(seed_for(SEED,'polarity'),index+1)[-1]>=0 else -1)
 elif variant=='bipolar':p=base*np.sin(2*np.pi*u)
 elif variant=='grain':
  rough=noise(seed_for(SEED,index),n)
  # A finite 2.5kHz rough friction kernel follows the existing contact event.
  rough=signal.sosfilt(signal.butter(2,2500,fs=RATE,output='sos'),rough)
  rough/=max(np.std(rough),1e-9)
  p=base*rough
 else:p=base
 return p/np.sqrt(np.sum(p*p)/RATE)

def evaluate(out,midi,onset):
 hz=440*2**((midi-69)/12)
 result={}
 for name,start in [('early',.25),('later',.75),('middle',onset*.5),('tail',onset+.35)]:
  x=out[round(start*sk.RATE):round((start+.3)*sk.RATE)]
  d=pitch_nsdf(x,sk.RATE)
  d['cents']=1200*np.log2(d['hz']/hz) if d['hz'] else 1e9
  nfft=131072; f=np.fft.rfftfreq(nfft,1/sk.RATE);a=abs(np.fft.rfft(x*np.hanning(len(x)),nfft))
  select=(f>40)&(f<350)
  peaks,_=signal.find_peaks(a)
  peaks=[j for j in peaks if select[j]]
  peaks=sorted(peaks,key=lambda j:a[j],reverse=True)[:5]
  d['spectral_peaks']=[(float(f[j]),float(a[j])) for j in peaks]
  rootmask=abs(f-hz)<2
  d['root_peak']=float(max(a[rootmask]))
  result[name]=d
 return result

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--report',type=Path,default=HERE/'report.json')
 parser.add_argument('--audio-dir',type=Path,default=None,
                     help='Optional scratch output folder; no WAVs are written by default.')
 args=parser.parse_args()
 if args.audio_dir is not None:args.audio_dir.mkdir(parents=True,exist_ok=True)
 cfg=m.MotionConfig(lean=0,mu_roll_override=.0044,collapse=(7-1.96)/6)
 motion=m.generate_motion(cfg)
 _,diag=r.mechanical_excitation(motion,.4,True)
 all_events=diag['rolling_events']+diag['terminal_events']
 report={'seed':SEED,'motion':motion['diagnostics'],'variants':[]}
 for velocity in [.25,1.]:
  for variant in ['positive','rim64','rim256','rim1024','hash_sign','bipolar','grain']:
   lut=noise(SEED,int(variant[3:])) if variant.startswith('rim') else None
   routes=np.zeros((2,math.ceil(diag['source_seconds']*RATE)))
   assigned=0
   for index,e in enumerate(all_events):
    e={**e,'rim_phase_rad':float(np.interp(e['time_s'],motion['time_s'],motion['rim_phase_rad']))}
    feature=e.get('feature',0);terminal=index>=len(diag['rolling_events'])
    width=.006*(.0007/.006)**.4*(1.6-.6*velocity)
    width*=.9 if terminal else (.65 if feature else 1)
    kernel=pulse(width,variant,e,index,lut)
    if terminal:direction=(1.,.7)
    else:
     az=np.interp(e['time_s'],motion['time_s'],motion['contact_phase_rad'])
     direction=(.82+.18*np.cos(e['rim_phase_rad']+.2),.85*(.82+.18*np.cos(az+.9)))
    budget=e['assigned_energy']*velocity**2
    assigned+=budget
    signed_add(routes,kernel,round(e['time_s']*RATE),budget,direction)
   energy=np.sum(routes**2)/RATE
   assert energy<=assigned+1e-9
   routes=signal.sosfilt(signal.butter(6,8000,fs=RATE,output='sos'),routes,axis=1)
   rf=round(diag['rest_start_s']*RATE);withdraw=round(.01*RATE)
   routes[:,rf-withdraw:rf]*=np.linspace(1,0,withdraw);routes[:,rf:]=0
   for midi in [36,48]:
    hz=440*2**((midi-69)/12)
    raw=sk.modal_ring(routes[0],hz,r.DISK_MODES,RATE,compensate=True)+.3*sk.modal_ring(routes[1],hz,r.SUPPORT_MODES,RATE,compensate=True)
    out=sk.finish(raw)
    metrics=evaluate(out,midi,diag['rolling_duration_s'])
    item={'variant':variant,'velocity':velocity,'midi':midi,'source_energy':float(energy),'assigned':assigned,'metrics':metrics}
    report['variants'].append(item)
    print(json.dumps({'variant':variant,'velocity':velocity,'midi':midi,'cents':{k:round(v['cents'],1) for k,v in metrics.items()},'confidence':{k:round(v['confidence'],3) for k,v in metrics.items()}}),flush=True)
    if args.audio_dir is not None and variant in ['positive','rim256','rim1024','hash_sign','grain']:
     sk.write_wav(str(args.audio_dir/f'{variant}_M{midi}_v{velocity}.wav'),out)
 args.report.write_text(json.dumps(report,indent=2)+'\n')
if __name__=='__main__':main()
