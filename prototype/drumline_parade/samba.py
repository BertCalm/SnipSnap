"""Instrument-specific Samba study. Shape constants except the fitted agogo.
Stable player bodies, fresh contacts; no sample playback. 4x internal mono.
"""
import functools,json
from pathlib import Path
import numpy as np
from scipy import signal
import snipkit as sk
from contacts import loss
from fit_contacts import module,ROOT
R=sk.INTERNAL

@functools.lru_cache(maxsize=1)
def bells():
 f=module('samba_agogo',ROOT/'prototype/calabash/fitted.py')
 table=json.loads((Path(__file__).parent/'agogo-fit.json').read_text());f.data=lambda:table
 return f

def render(p,v,take):
 name,fam,root,life,count,width=p
 if fam=='call':
  times=[0,.107,.214,.321,.535,.643,.75];out=np.zeros(round(1.95*R))
  for j,at in enumerate(times):
   x=render(('repinique_open','skin',root,.29,count,width),v*(.95 if j%4==0 else .55),take*32+j)
   i=round(at*R);out[i:i+len(x)]+=x
  return out
 if name.startswith('agogo'):
  rng=np.random.default_rng(sk.seed_for('SAMBA_BELL',name,take))
  body,contact=bells().strike(name,v,rng,1.2,contact_ms=.18)
  return signal.resample_poly((body+contact)*v,4,1)
 # Open/muted/rim strokes share a fixed body for each player.
 family=('surdo_low' if name.startswith('surdo_low') else 'surdo_mid' if name.startswith('surdo_mid')
         else 'surdo_cutter' if name.startswith('surdo') else name.split('_')[0])
 out=np.zeros(round(1.2*R))
 for player in range(count):
  identity=np.random.default_rng(sk.seed_for('SAMBA_BODY',family,player))
  rng=np.random.default_rng(sk.seed_for('SAMBA_CONTACT',name,player,take))
  start=0 if player==0 else round(rng.uniform(.0005,.008)*R)
  t=np.arange(len(out)-start)/R;touch=v*rng.uniform(.95,1.04)
  f=root*2**(identity.uniform(-12,12)/1200);noise=rng.standard_normal(len(t))
  if family=='chocalho':
   # Discrete jingle collisions excite fixed metal resonances. Direction changes
   # the arrival pattern, not the instrument's tuning.
   strike=np.zeros(len(t));back=name.endswith('back')
   for at in np.sort(rng.beta(2,4 if back else 2.4,32)*(.075 if back else .11)*(1.2-.3*touch)):
    i=round(at*R);strike[i]+=rng.uniform(.3,1)*touch
   y=np.zeros(len(t))
   for hz in np.geomspace(1800,14500,22)*identity.uniform(.94,1.06,22):
    radius=np.exp(-1/(R*identity.uniform(.002,.012)))
    y+=signal.lfilter([.12*np.exp(-hz/9000*(1-touch))], [1,-2*radius*np.cos(2*np.pi*hz/R),radius*radius],strike)
   rub=signal.sosfilt(signal.butter(2,[3000,14000],btype='bandpass',fs=R,output='sos'),noise)
   env=signal.lfilter([1],[1,-np.exp(-1/(.0015*R))],strike)
   y+=.18*rub*env
  else:
   surdo=family.startswith('surdo');tamb=family=='tamborim';caixa=family=='caixa'
   # Coupled head modes for surdo, short bright high-tension heads elsewhere.
   ratios=np.array([1,1.48,1.59,2.14,2.30,2.65,2.92,3.16,3.5,4.06,4.61,5.14])
   base_life=(1.25 if family=='surdo_low' else .95 if family=='surdo_mid' else .58) if surdo else (.16 if tamb else .24 if caixa else .38)
   y=np.zeros(len(t));position=rng.uniform(.18,.32) if surdo else rng.uniform(.28,.48)
   glide=(.008 if surdo else .025)*touch**2
   warp=t+glide*.012*(1-np.exp(-t/.012))
   for k,a in enumerate(ratios):
    hz=f*a*identity.uniform(.994,1.006)
    gain=np.cos(position*k*1.4)*np.exp(-a*(.08+.45*(1-touch)))/a**(.8 if surdo else .45)
    if surdo and k<3:gain*=1.5
    decay=base_life/a**(.6 if surdo else .45)
    y+=gain*np.sin(2*np.pi*hz*warp)*np.exp(-6.9078*t/decay)
   force=np.hanning(max(4,round(width*.001*(1.4-.5*touch)*R)));force/=force.sum()
   y=signal.lfilter(force,[1],y)
   lo,hi=(180,2200) if surdo else (1200,11000) if tamb else (700,9500)
   impact=signal.sosfilt(signal.butter(2,[lo,hi],btype='bandpass',fs=R,output='sos'),noise)
   y+=(.32 if surdo else 1.1)*touch**.8*impact*(1-np.exp(-t/.0003))*np.exp(-t/(.005 if surdo else .0025))
   if caixa:
    wire=signal.sosfilt(signal.butter(2,[1900,11500],btype='bandpass',fs=R,output='sos'),noise)
    # The vibrating head modulates wire contact instead of a flat noise tail.
    coupling=.3+.7*np.abs(np.sin(2*np.pi*f*t)*np.exp(-t/.055))
    y+=1.5*wire*coupling*(1-np.exp(-t/.0005))*np.exp(-t/.025)
   if name.endswith('rim'):
    # Dry shell/rim contact on the same head. Repinique has no snare wires.
    y*=.6
    for hz,g in [(1650,1.2),(2770,.8),(4230,.55),(6380,.3)]:
     hz*=identity.uniform(.96,1.04)
     y+=g*np.sin(2*np.pi*hz*t)*np.exp(-t/(.009*(1650/hz)**.35))
    y+=1.8*impact*np.exp(-t/.0015)
   if name.endswith('muted'):
    # Hand contact dissipates the shared head continuously; no hard gate.
    damp=loss(t,.004 if tamb else .012,.015 if tamb else .04)
    y*=np.exp(-(145 if tamb else 60)*damp)
   if tamb:
    # Flexible multi-rod contact: a short secondary excitation, not an echo.
    delay=round(rng.uniform(.0006,.0014)*R)
    y[delay:]+=.22*touch*y[:-delay].copy()
  y*=touch*np.minimum(t/.00015,1)
  out[start:]+=y/np.sqrt(count)
 return out
