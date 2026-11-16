"""Compare exported Kotlin mixes with the accepted Python compressor law."""
import json,math,sys,wave
from pathlib import Path
import numpy as np
from scipy.ndimage import maximum_filter1d
root=Path(sys.argv[1])
def read(p):
 with wave.open(str(p),'rb') as w:
  assert w.getsampwidth()==3 and w.getnchannels()==1 and w.getframerate()==44100
  b=np.frombuffer(w.readframes(w.getnframes()),np.uint8).reshape(-1,3)
 i=b[:,0].astype(np.int32)|(b[:,1].astype(np.int32)<<8)|(b[:,2].astype(np.int32)<<16)
 return ((i+8388608)%16777216-8388608)/8388608
for kit in ('cuttings-pocket','cuttings-night','cuttings-circuit'):
 p=root/kit;x=read(p/'audio/groove.wav');expected={'finished':x}
 for name,threshold,ratio,attack,release in [('glue',-24,4.5,.0004,.09),('squeeze',-34,12,.0001,.055)]:
  env=0.;e=np.empty(len(x));a=math.exp(-1/(44100*attack));r=math.exp(-1/(44100*release))
  for i,v in enumerate(maximum_filter1d(np.abs(x),177,mode='constant')):
   c=a if v>env else r;env=c*env+(1-c)*v;e[i]=env
  over=20*np.log10(np.maximum(e,1e-9))-threshold
  gr=np.where(over < -3,0,np.where(over>3,over*(1-1/ratio),(over+3)**2/12*(1-1/ratio)))
  expected[name]=x*10**(-gr/20)
 rms={k:np.sqrt(np.mean(v*v)) for k,v in expected.items()}
 target=min(rms['finished'],*(.88*rms[k]/np.max(abs(v)) for k,v in expected.items()))
 for name in ('glue','squeeze'):
  actual=read(p/f'audio/groove_{name}_v2.wav');reference=expected[name]*target/rms[name]
  error=np.max(abs(actual-reference));assert error<2e-6,(kit,name,error)
  assert np.isfinite(actual).all() and np.max(abs(actual))<=.880001
  assert abs(20*np.log10(np.sqrt(np.mean(actual*actual))/target))<.001
  if name=='glue':assert np.array_equal(actual,read(p/f'mixes/{kit}-glue.wav'))
  print(kit,name,'max PCM error',float(error))
print('PASS: Kotlin saved 24-bit mixes match Python reference; Glue delivery files identical.')
