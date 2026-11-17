"""Check common strike identity, progressive loss and isolation of cached renders."""
import json
from pathlib import Path
import numpy as np
from scipy import signal
import study,crash_wash
R=study.R
pads={p[0]:p for p in study.NOLA}
def energy(x,a,b,band=None):
 if band:x=signal.sosfilt(signal.butter(3,band,btype='bandpass',fs=R,output='sos'),x)
 return float(np.mean(x[round(a*R):round(b*R)]**2))
def check_copy(v,take):
 expected=crash_wash.render(v,take).copy()
 returned=crash_wash.render(v,take);returned[100]=12345
 assert np.array_equal(expected,crash_wash.render(v,take)), 'Cached crash changed after caller mutation'

def main():
 rows=[]
 for v,take in [(.3,0),(1.,2)]:
  source=study.raw(pads['cymbal_crash'],v,take)
  choke=study.raw(pads['cymbal_choke'],v,take)
  assert np.array_equal(source[:round(.18*R)],choke[:round(.18*R)])
  ratio=energy(choke,.4,.7)/energy(source,.4,.7);assert ratio<.05
  low=energy(choke,.4,.7,(300,1400))/energy(source,.4,.7,(300,1400))
  high=energy(choke,.4,.7,(5000,12000))/energy(source,.4,.7,(5000,12000))
  assert high<low/2,(high,low)
  soft=study.raw(pads['cymbal_crash'],v*.5,take)
  touch=study.raw(pads['cymbal_touch'],v,take)
  assert np.array_equal(soft[:round(.045*R)],touch[:round(.045*R)])
  touch_ratio=energy(touch,.2,.5)/energy(soft,.2,.5);assert touch_ratio<.05
  assert np.array_equal(source,study.raw(pads['cymbal_crash'],v,take))
  check_copy(v,take)
  rows.append({'velocity':v,'take':take,'choke_late_energy_ratio':ratio,'choke_high_vs_low_loss_db':float(10*np.log10(high/low)),'touch_late_energy_ratio':touch_ratio,'strike_identity':True,'cache_isolation':True})
 # Remove only the defensive copy: the same check must now reject the cache leak.
 original=crash_wash.render;rejected=False
 try:
  crash_wash.render=crash_wash._render
  try:check_copy(1.,2)
  except AssertionError as e:rejected=str(e)=='Cached crash changed after caller mutation'
 finally:
  crash_wash.render=original;crash_wash._render.cache_clear()
 assert rejected,'Mutation negative control did not fail'
 print(json.dumps({'checks':rows,'cache_max_entries':crash_wash._render.cache_info().maxsize,'mutation_negative_control_rejected':rejected},indent=2))
if __name__=='__main__':main()
