"""Reference-fitted synthesis, not playback. See NOTICE-measured.txt for provenance.
The snare/cross-stick fits carry measured soft/hard spectra; the single-level
stick capture uses a disclosed contact-width velocity law.
"""
import functools,json,sys
from pathlib import Path
import numpy as np
from scipy import signal
import snipkit as sk
from contacts import loss
from fit_contacts import module
ROOT=Path(__file__).resolve().parents[2]
F=module('parade_measured_striker',ROOT/'prototype/calabash/fitted.py')
F.data=lambda: json.loads((Path(__file__).parent/'measured-contacts.json').read_text())
# Keep one immutable coefficient table; no source PCM is read by the renderer.
_TABLE=F.data();F.data=lambda:_TABLE
sys.path.insert(0,str(ROOT/'prototype/calabash'))
import calabash_proto as C

@functools.lru_cache(maxsize=12)
def cymbal(v,take,seconds):
 C._breaker_cymbals()
 rng=np.random.default_rng(sk.seed_for('PARADE_MEASURED_CRASH',take))
 y=C.e.fitted_modes('breaker_crash',.5,.3,v,rng,seconds)
 y+=.8*C.e.stick_burst('breaker_crash',v,rng,len(y))
 y*=np.minimum(np.arange(len(y))/sk.RATE/.0004,1)
 return signal.resample_poly(y,4,1)

def render(p,v,take,legacy):
 name,fam,root,life,count,width=p
 if fam=='metal':
  import crash_wash
  y=crash_wash.render(v*.5 if name=='cymbal_touch' else v,take)
  if name=='cymbal_crash':return y
  seconds=.8 if name=='cymbal_touch' else 1.4
  y=y[:round(seconds*sk.INTERNAL)]
  t=np.arange(len(y))/sk.INTERNAL
  lo=signal.sosfilt(signal.butter(2,1800,fs=sk.INTERNAL,output='sos'),y)
  start=.18 if name=='cymbal_choke' else .045
  envelope=loss(t,start,.14 if name=='cymbal_choke' else .06)
  active=t>=start
  # Preserve the source strike exactly until contact; high bands settle first.
  y[active]=lo[active]*np.exp(-20*envelope[active])+(y[active]-lo[active])*np.exp(-55*envelope[active])
  return y
 key=name if fam=='wood' else 'rimshot'
 out=np.zeros(round(1.2*sk.INTERNAL))
 for player in range(count):
  onset=np.random.default_rng(sk.seed_for('PARADE_HIT',name,player,take))
  offset=0 if player==0 else round(onset.uniform(.0004,.006)*sk.INTERNAL)
  rng=np.random.default_rng(sk.seed_for('PARADE_MEASURED_CONTACT',name,player,take))
  ratio=1. if fam=='wood' else root/255
  body,residual=F.strike(key,v,rng,1.2,tune_ratio=ratio,damp=.3,contact_ms=.12 if fam=='rim_dry' else .25)
  if fam=='rim_dry':residual*=np.exp(-np.arange(len(residual))/sk.RATE/.007)
  y=signal.resample_poly(body+residual,4,1)
  n=min(len(y),len(out)-offset);out[offset:offset+n]+=y[:n]*v/np.sqrt(count)
 return out
