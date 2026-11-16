"""Second listening round: plate wash, rim contact and wooden impacts.
All constants are listening shapes; no measured acoustic fit is claimed.
"""
import numpy as np
from scipy import signal
import snipkit as sk
R=sk.INTERNAL

def band(x,lo,hi):
 return signal.sosfilt(signal.butter(2,[lo,hi],btype='bandpass',fs=R,output='sos'),x)

def loss(t,start,width=.14):
 u=np.maximum(t-start,0);z=np.minimum(u,width)
 return z**3/width**2-.5*z**4/width**3+np.maximum(u-width,0)

def render(p,v,take,legacy):
 name,fam,root,life,count,width=p
 if fam=='metal':
  duration=4.5 if name=='cymbal_crash' else 1.4 if name=='cymbal_choke' else .8
  t=np.arange(round(duration*R))/R
  identity=np.random.default_rng(sk.seed_for('PARADE_PLATE_BODY',1))
  rng=np.random.default_rng(sk.seed_for('PARADE_PLATE_HIT',name,take))
  # Non-harmonic overlapping modes and independent phases prevent a pitched comb.
  frequencies=np.geomspace(310,16600,144)*identity.uniform(.955,1.045,144)
  y=np.zeros(len(t));contact=loss(t,.18) if name=='cymbal_choke' else np.zeros(len(t))
  for f,phase,gain in zip(frequencies,identity.uniform(-np.pi,np.pi,144),rng.uniform(.75,1.25,144)):
   if f>=18000:continue
   ring=np.exp(-.5*(np.log(f/1250)/.7)**2)
   sustain=(.65+2.8*np.exp(-f/2600))*(1+.5*ring)
   if name=='cymbal_touch':sustain*=.17
   weight=(f/1000)**(-.32)*np.exp(-f/(4000+10000*v))*gain/12
   damping=(14+31*(f/16000)**.7)*contact
   y+=weight*np.sin(2*np.pi*f*t+phase)*np.exp(-6.9078*t/sustain-damping)
  noise=rng.uniform(-1,1,len(t))
  wash=band(noise,1400,14500)
  # Broad strike wash falls away while lower plate modes continue ringing.
  y+=1.25*v**.6*wash*np.exp(-t/(.024 if name=='cymbal_touch' else .095)-38*contact)
  y*=np.minimum(t/.0007,1)*v
  return y
 if fam=='wood':
  t=np.arange(round(1.2*R))/R
  rng=np.random.default_rng(sk.seed_for('PARADE_WOOD',name,take))
  cross=name=='cross_stick'
  # Cross-stick reads rim/shell and a resting stick; the click is two free sticks.
  frequencies=[730,1230,1920,2780,4200] if cross else [1520,2730,4350,6700,9100]
  lifetimes=[.052,.038,.027,.018,.012] if cross else [.022,.018,.013,.009,.006]
  gains=[.48,1,.58,.3,.12] if cross else [.4,1,.65,.25,.1]
  y=np.zeros(len(t));force=np.hanning(max(4,round((.00022 if cross else .00009)*(1.5-.65*v)*R)));force/=force.sum()
  for f,l,g in zip(frequencies,lifetimes,gains):
   modal=np.sin(2*np.pi*f*t)*np.exp(-6.9078*t/l)
   y+=g*rng.uniform(.88,1.12)*signal.lfilter(force,[1],modal)
  impact=band(rng.uniform(-1,1,len(t)),650 if cross else 1700,12500)
  y+=1.5*v**.5*impact*np.minimum(t/.00005,1)*np.exp(-t/(.0011 if cross else .00055))
  return y*v
 # A rimshot includes both rim and head. Dry repinique has no snare-wire bed.
 y=.75*legacy((name,'skin',root,life,count,width),v,take)
 for player in range(count):
  identity=np.random.default_rng(sk.seed_for('PARADE_RIM_BODY',name,player))
  rng=np.random.default_rng(sk.seed_for('PARADE_RIM_CONTACT',name,player,take))
  offset=0 if player==0 else round(rng.uniform(.0004,.006)*R)
  t=np.arange(len(y)-offset)/R;clack=np.zeros(len(t))
  force=np.hanning(max(4,round(.00012*(1.5-.7*v)*R)));force/=force.sum()
  for f in np.array([920,1370,1890,2480,3150,4020,5170,6430,8100])*identity.uniform(.98,1.02,9):
   mode=np.sin(2*np.pi*f*t)*np.exp(-6.9078*t/(.055*(1300/f)**.5))
   clack+=rng.uniform(.65,1.1)*(f/1800)**(-.3)*signal.lfilter(force,[1],mode)
  noise=rng.uniform(-1,1,len(t))
  clack+=2.4*band(noise,1400,14500)*np.minimum(t/.00005,1)*np.exp(-t/.0013)
  if fam=='rim':clack+=.28*band(noise,1900,9500)*np.minimum(t/.00015,1)*np.exp(-6.9078*t/(life*.6))
  y[offset:]+=clack*v**.8/np.sqrt(count)
 return y
