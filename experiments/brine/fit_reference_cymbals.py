from pathlib import Path
import numpy as np
from scipy.io import wavfile
from scipy.signal import stft,welch
from scipy.ndimage import gaussian_filter
from scipy.interpolate import interp1d
import json,sys
root=Path(sys.argv[1]);output=Path(sys.argv[2]);output.mkdir(parents=True,exist_ok=True);n=2048;hop=256;sr=44100
knots=np.array([0,.006,.012,.024,.04,.064,.10,.15,.22,.32,.46,.65,.9,1.25,1.7,2.3,3.1,4.2,5.5,7.0,8.5])
def metrics(x):
 x=np.pad(x,(0,max(0,sr-len(x))));f,p=welch(x[:sr],sr,nperseg=4096)
 en=np.cumsum(x*x);en/=en[-1]
 F,t,z=stft(x,sr,nperseg=2048,noverlap=1536);spec=abs(z[:,(t>.1)&(t<.5)])**2;mask=(F>1000)&(F<10000)
 flat=np.mean(np.exp(np.mean(np.log(spec[mask]+1e-18),axis=0))/np.mean(spec[mask]+1e-18,axis=0))
 return {'centroid':float(sum(f*p)/sum(p)),'t50':float(np.searchsorted(en,.5)/sr),'t90':float(np.searchsorted(en,.9)/sr),'flatness':float(flat)}
for ident in ['ride-main','choke-main','crash-main']:
 rate,x=wavfile.read(root/(ident+'.wav'));x=x.astype(float);on=np.flatnonzero(abs(x)>max(abs(x))*.015)[0];x=x[max(0,on-44):];x=x[:int(8.5*sr)]
 f,t,z=stft(x,sr,nperseg=n,noverlap=n-hop,boundary='zeros');m=abs(z)
 # Keep a measured dense frequency profile, but just 21 time snapshots and no original phase.
 smooth=np.sqrt(gaussian_filter(m*m,sigma=(.45,1.0)));p=interp1d(t,smooth,axis=1,bounds_error=False,fill_value=0)(knots)
 p[f<100,:]=0;p[f>18000,:]=0
 times=np.arange(0,len(x)+n//2,hop)/sr
 mags=interp1d(knots,p,axis=1,bounds_error=False,fill_value=0)(times)
 rng=np.random.default_rng(734);win=np.hanning(n+1)[:-1];y=np.zeros(len(times)*hop+n)
 for j in range(len(times)):
  phases=rng.uniform(-np.pi,np.pi,len(f)); c=mags[:,j]*np.exp(1j*phases);c[0]=0;c[-1]=0
  frame=np.fft.irfft(c,n);y[j*hop:j*hop+n]+=frame*win
 y=y[n//2:n//2+len(x)];y*=np.sqrt(np.sum(x*x)/np.sum(y*y))
 # Scale and save prototype plus compact measured profiles.
 y[:88]*=np.linspace(0,1,88);y[-2205:]*=np.linspace(1,0,2205)
 wavfile.write(root/(ident+'-proto.wav'),sr,(y*.85/max(abs(y))).astype('float32'))
 np.savez(root/(ident+'-profile.npz'),times=knots,magnitudes=p)
 profile={'version':7,'fftSize':n,'hop':hop,'sampleRate':sr,'frames':len(x),'times':knots.tolist(),
          'magnitude':np.round((p/p.max()).T,7).tolist(),'referenceId':ident,
          'sourceLicense':'CC0-1.0','method':'RMS-smoothed STFT magnitudes, 21 time knots; original phases discarded'}
 (output/(ident+'.json')).write_text(json.dumps(profile,separators=(',',':'))+'\n')
 wavfile.write(root/(ident+'-aligned.wav'),sr,x.astype('float32'))
 print(ident,'ref',metrics(x),'prototype',metrics(y))
