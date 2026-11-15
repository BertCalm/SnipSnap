"""TIMBER R3 audition prototype, Python/scipy, not the Kotlin engine.
All modal constants are authored shapes, not measurements of real instruments.
"""
import json, hashlib
from pathlib import Path
import numpy as np
from scipy.signal import lfilter, butter, sosfilt, resample_poly
from scipy.io.wavfile import write
RATE=176400; OUT=44100; N=int(.95*RATE)
# Independent absolute body modes (Hz, relative radiation, T60), string sets,
# dispersion and fingerboard response. No scaled common body template.
PROFILES={
 'guitar':([(98,1,.25),(188,.68,.19),(285,.42,.13),(420,.28,.09),(690,.2,.06)], [82.41,110,146.83,196,246.94,329.63],.00012,1750,.38),
 'acoustic-bass':([(68,1,.34),(134,.62,.26),(231,.39,.18),(365,.3,.11),(560,.17,.08)], [41.2,55,73.42,98],.00042,1050,.52),
 'cello':([(106,.6,.22),(173,1,.31),(287,.72,.2),(463,.36,.15),(730,.23,.08),(1120,.12,.05)], [65.41,98,146.83,220],.00075,2300,.45),
 'upright':([(57,.85,.42),(94,1,.32),(158,.58,.23),(247,.34,.16),(410,.21,.12),(640,.12,.07)], [41.2,55,73.42,98],.0014,740,.68)}
TAKE_POS=[-.009,.004,.011,-.004]; TIMES=[-.0003,.00015,.0004,-.0001]
def pulse(buf,at,width,energy):
 start=max(0,round(at*RATE)); n=max(8,round(width*RATE)); p=np.sin(np.linspace(0,np.pi,n))**2
 end=min(len(buf),start+n)
 if end>start:buf[start:end]+=p[:end-start]*energy/p.sum()
def ring(x,modes):
 out=np.zeros_like(x)
 for hz,g,t60 in modes:
  r=np.exp(-np.log(1000)/(t60*RATE)); theta=2*np.pi*hz/RATE
  out+=lfilter([g*np.sin(theta)],[1,-2*r*np.cos(theta),r*r],x)
 return out
def render(instrument,gesture,v,take,body_off=False):
 modes,strings,stiff,board,collision_gain=PROFILES[instrument]
 texture_take=0 if gesture=='scrape' else take
 rng=np.random.default_rng(int.from_bytes(hashlib.sha256(f'{instrument}/{gesture}/{texture_take}/r3'.encode()).digest()[:8],'little'))
 body=np.zeros(N); ex=np.zeros(N); direct=np.zeros(N)
 delta=TAKE_POS[take]; at=.004+TIMES[take]*.35
 if gesture=='palm':
  # Broad compliant contact and a hand left on the top suppress upper modes.
  pulse(body,at,.005*(1.3-.55*v)*(1+delta*5),1.8)
  modes=[(f,g/(1+(f/210)**2),t*.50) for f,g,t in modes]
  pulse(ex,at,.004,.008)
 elif gesture=='muted':
  # Damped strings receive a short strike and a delayed board collision.
  pulse(body,at,.001*(1.2-.6*v),.32)
  pulse(ex,at,.0005*(1.2-.6*v),.5)
  for j in range(len(strings)):
   hit=at+.0015+j*.0003+delta*.001
   pulse(body,hit,.0002,collision_gain*v*.18)
   pulse(ex,hit,.00015,collision_gain*v*.12)
  noise=rng.normal(size=int(.012*RATE))*np.exp(-np.arange(int(.012*RATE))/(RATE*.0025))
  direct[round(at*RATE):round(at*RATE)+len(noise)]=noise*.004*v
  modes=[(f,g,t*.65) for f,g,t in modes]
 elif gesture=='bridge':
  # Hard nail contact near the bridge radiates upper wood modes strongly.
  pulse(body,at,.00045*(1.4-.8*v)*(1+delta*6),.7)
  pulse(ex,at,.00025,.08)
  modes=[(f,g*(.35+f/300),t*.6) for f,g,t in modes]
  modes += [(board,.14,.025),(board*1.63,.08,.016)]
 elif gesture=='scrape':
  # Nail travelling along winding ridges: accelerating, irregular contacts.
  # Excitation is a finite train, not a stationary noise overlay.
  duration=.19-.045*v
  t=at; j=0
  while t<at+duration:
   progress=(t-at)/duration
   energy=.045*np.sin(np.pi*progress)**.6*(1+rng.uniform(-.12,.12))
   pulse(ex,t,.00012,energy)
   pulse(body,t,.00022,energy*.75)
   pulse(direct,t,.00009,energy*.15)
   t+=(.0028-.0012*progress)*(1+rng.uniform(-.12,.12))*(1+delta*.3)
   j+=1
  modes=[(f,g,t*.7) for f,g,t in modes]
 else:raise ValueError(gesture)
 wood=ring(body,[(f,g*(1+delta*(i-2)),t) for i,(f,g,t) in enumerate(modes)]) if not body_off else np.zeros(N)
 string=np.zeros(N)
 for j,hz in enumerate(strings):
  decay={'palm':.035,'muted':.025,'bridge':.075,'scrape':.042}[gesture]
  partials=[(hz*k*np.sqrt(1+stiff*k*k),np.sin(np.pi*(.19+delta)*k)/(k**.9*len(strings)),decay/(1+.12*k)) for k in range(1,13)]
  string+=ring(ex,partials)
 click=sosfilt(butter(2,[900,min(11000,board*4)],fs=RATE,btype='bandpass',output='sos'),direct)
 x=(wood+string+click)*v*.72
 x=sosfilt(butter(6,18000,fs=RATE,output='sos'),x)
 x=resample_poly(x,1,4)
 x=sosfilt(butter(2,22,fs=OUT,btype='highpass',output='sos'),x-x.mean())
 x[-220:]*=np.linspace(1,0,220)
 assert np.isfinite(x).all()
 return x

def rms(x):return np.sqrt(np.mean(x*x))
def residual(a,b):
 return 10*np.log10(max(1e-30,np.sum((b-a*np.dot(a,b)/np.dot(a,a))**2))/np.dot(b,b))
def main(out):
 out.mkdir(parents=True,exist_ok=True); records=[]; grids={}
 for ins in PROFILES:
  for gesture in ['palm','muted','bridge','scrape']:
   grid=[[render(ins,gesture,v,t) for t in range(4)] for v in [.3,.65,1.]]
   grids[ins,gesture]=grid
 # One global audition gain, preserving relative instruments, touches and takes.
 peak=max(np.max(np.abs(x)) for grid in grids.values() for row in grid for x in row)
 gain=min(1.,.85/peak)
 for (ins,gesture),grid in grids.items():
  spreads=[20*np.log10(max(map(rms,row))/min(map(rms,row))) for row in grid]
  assert max(spreads)<1.5,(ins,gesture,spreads)
  for t in range(4):
   assert rms(grid[0][t])<rms(grid[1][t])<rms(grid[2][t])
   assert residual(grid[0][t],grid[2][t])>-35
  for row in grid:
   for a in range(4):
    for b in range(a+1,4):assert residual(row[a],row[b])>-45,(ins,gesture,a,b,residual(row[a],row[b]))
  assert all(abs(x.mean())<.005 for row in grid for x in row)
  assert np.array_equal(grid[1][2],render(ins,gesture,.65,2))
  # Native touch: four takes per zone. A gap preserves the complete decay.
  gap=np.zeros(int(.16*OUT))
  native=np.concatenate([np.concatenate([x*gain,gap]) for row in grid for x in row])
  # Matched touch compares one take at equal RMS, with one common ceiling.
  matched=[grid[z][0]*rms(grid[2][0])/rms(grid[z][0]) for z in range(3)]
  mg=min(gain,.85/max(np.max(np.abs(x)) for x in matched))
  match=np.concatenate([np.concatenate([x*mg,gap]) for x in matched])
  for suffix,x in [('touch',native),('matched',match),('hard',grid[2][0]*gain)]:
   write(out/f'{ins}-{gesture}-{suffix}.wav',OUT,np.round(x*32767).astype(np.int16))
  if gesture=='scrape':assert residual(render(ins,gesture,1,0,True),grid[2][0])>-20
  records.append(dict(instrument=ins,gesture=gesture,take_spread_db=spreads,
   soft_hard_residual_db=[residual(grid[0][t],grid[2][t]) for t in range(4)],
   peak=max(np.max(np.abs(x))*gain for row in grid for x in row)))
 (out/'report.json').write_text(json.dumps(dict(runtime='Python scipy prototype; not Kotlin',global_gain=gain,profiles=PROFILES,measurements=records),indent=2))
 print(json.dumps(dict(cells=len(records),strikes=192,peak=max(r['peak'] for r in records),max_take_spread_db=max(max(r['take_spread_db']) for r in records))))
if __name__=='__main__':main(Path('/workspace/timber-r3'))
