"""Fit CC0 contact recordings using SnipSnap's existing CALABASH fitter.
External reference directory must contain stick_hard.wav (see NOTICE-measured.txt).
Only derived synthesis parameters are written to the repository.
"""
import argparse,hashlib,importlib.util,json,math
from pathlib import Path
import numpy as np
from scipy import signal
from scipy.io import wavfile
ROOT=Path(__file__).resolve().parents[2]
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--references',type=Path,required=True);a=ap.parse_args()
 f=module('contact_fitter',ROOT/'prototype/calabash/fit_calabash.py')
 core=ROOT/'synth/src/main/resources/spore-station/v7'
 sources={'rimshot':(core/'snaremic_snare_rimshot_vl10.wav',core/'snaremic_snare_rimshot_vl2.wav',160,40),
 'cross_stick':(core/'snaremic_snare_crossstick_vl16.wav',core/'snaremic_snare_crossstick_vl2.wav',80,150),
 'stick_click':(a.references/'stick_hard.wav',a.references/'stick_hard.wav',80,150)}
 assert hashlib.sha256((a.references/'stick_hard.wav').read_bytes()).hexdigest()=='ce808a9081f309cca28538b172014e44f8eafe2f944403147446741be76df3bf'
 out={'bands':f.BANDS,'times':f.TIMES,'early':f.EARLY,'voices':{},'sources':{}}
 for name,(hard,soft,n,floor) in sources.items():
  paths=[]
  for label,p in [('hard',hard),('soft',soft)]:
   rate,x=wavfile.read(p);x=x.astype(float)
   if x.ndim>1:x=x.mean(axis=1)
   g=math.gcd(rate,44100);x=signal.resample_poly(x,44100//g,rate//g)
   dest=a.references/f'{name}-{label}-fit.wav';wavfile.write(dest,44100,x.astype(np.float32));paths.append(dest)
  d=f.fit_voice(*paths,n,floor);d['glide']=[0.,.02] if name!='rimshot' else d['glide']
  out['voices'][name]=d
  out['sources'][name]={'hard':hard.name,'soft':soft.name,'hard_sha256':hashlib.sha256(hard.read_bytes()).hexdigest(),'soft_sha256':hashlib.sha256(soft.read_bytes()).hexdigest(),'soft_measured':name!='stick_click'}
  print(name,len(d['modes']),flush=True)
 (Path(__file__).parent/'measured-contacts.json').write_text(json.dumps(out,separators=(',',':'))+'\n')
if __name__=='__main__':main()
