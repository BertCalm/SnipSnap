"""Fit VCSL CC0 triangle references; retain coefficients and source hashes, not PCM."""
import argparse,hashlib,json
from pathlib import Path
from urllib.request import urlopen
from urllib.parse import quote
import numpy as np
from scipy.io import wavfile
from scipy import signal
from fit_contacts import module,ROOT
PIN='c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e'
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--references',type=Path,required=True);a=ap.parse_args();a.references.mkdir(exist_ok=True,parents=True)
 f=module('triangle_fitter',ROOT/'prototype/calabash/fit_calabash.py')
 out={'bands':f.BANDS,'times':f.TIMES,'early':f.EARLY,'voices':{},'sources':[]}
 for name,hi in [('triangle',2)]:
  paths=[]
  for v in [hi,1]:
   path=f'Idiophones/Struck Idiophones/Triangles/Triangle1_Hit_v{v}_rr1_Mid.wav'
   url=f'https://raw.githubusercontent.com/sgossner/VCSL/{PIN}/'+quote(path)
   data=urlopen(url,timeout=60).read();dest=a.references/f'{name}-v{v}.wav';dest.write_bytes(data)
   out['sources'].append({'url':url,'sha256':hashlib.sha256(data).hexdigest(),'license':'CC0 1.0'})
   rate,x=wavfile.read(dest);x=x.astype(float)
   if x.ndim>1:x=x.mean(axis=1)
   if rate!=44100:x=signal.resample_poly(x,44100,rate)
   fit=a.references/f'{name}-v{v}-fit.wav';wavfile.write(fit,44100,x.astype(np.float32));paths.append(fit)
  d=f.fit_voice(*paths,128,300);d['glide']=[0.,.02];out['voices'][name]=d;print(name,len(d['modes']),flush=True)
 (Path(__file__).parent/'triangle-fit.json').write_text(json.dumps(out,separators=(',',':'))+'\n')
if __name__=='__main__':main()
