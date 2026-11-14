"""Fit a compact additive/stochastic study from credited HQ previews, not original WAVs.
Run from repository root after fired_bronze_fetch_references.py. No PCM is embedded.
The complex envelopes retain onset/partial evolution; residual bands retain unpitched contact.
"""
import json, hashlib
from pathlib import Path
import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, sosfiltfilt, hilbert, find_peaks
from scipy.ndimage import gaussian_filter1d
ROOT=Path('testkit/fired-bronze-r2/references')
OUT=Path('synth/src/main/resources/fired-bronze-r2')
IDS={'TIN':'171911','TUN':'171909','NA':'171905','TE':'171913','GA':'171902'}
HOP=.0025
provenance={r['id']:r for r in json.loads((ROOT/'provenance.json').read_text()) if 'preview' in r}
report={}
for name,id in IDS.items():
 sr,pcm=wavfile.read(ROOT/(id+'.wav')); x=pcm.astype(float)/32768
 x=sosfiltfilt(butter(2,45,fs=sr,btype='highpass',output='sos'),x)
 nfft=131072; spectrum=np.abs(np.fft.rfft(x*np.hanning(len(x)),nfft)); freq=np.fft.rfftfreq(nfft,1/sr)
 peaks,_=find_peaks(spectrum,distance=70*nfft/sr,prominence=max(spectrum)*.001)
 peaks=[i for i in peaks if 65<freq[i]<10000]
 peaks=sorted(sorted(peaks,key=lambda i:spectrum[i],reverse=True)[:20])
 centers=freq[peaks]; t=np.arange(len(x))/sr; knots=np.arange(0,t[-1]+HOP,HOP)
 summed=np.zeros_like(x); modes=[]
 for j,f in enumerate(centers):
  width=min(65, (f-centers[j-1])*.45 if j else 65, (centers[j+1]-f)*.45 if j+1<len(centers) else 65)
  y=sosfiltfilt(butter(2,[max(30,f-width),f+width],fs=sr,btype='bandpass',output='sos'),x)
  z=hilbert(y)*np.exp(-2j*np.pi*f*t)
  # 400 Hz control rate, with anti-alias smoothing before envelope sampling.
  z=gaussian_filter1d(z.real,sr*.0015)+1j*gaussian_filter1d(z.imag,sr*.0015)
  re=np.interp(knots,t,z.real);im=np.interp(knots,t,z.imag)
  reconstructed=np.interp(t,knots,re)*np.cos(2*np.pi*f*t)-np.interp(t,knots,im)*np.sin(2*np.pi*f*t)
  summed+=reconstructed
  modes.append({'hz':round(float(f),6),'real':np.round(re,7).tolist(),'imag':np.round(im,7).tolist()})
 residual=x-summed; bands=[]
 for lo,hi in [(80,300),(300,800),(800,1800),(1800,3500),(3500,6500),(6500,14000)]:
  y=sosfiltfilt(butter(2,[lo,hi],fs=sr,btype='bandpass',output='sos'),residual)
  env=np.sqrt(gaussian_filter1d(y*y,sr*.0025))
  bands.append({'lo':lo,'hi':hi,'rms':np.round(np.interp(knots,t,env),7).tolist()})
 profile={'version':2,'name':name,'seconds':len(x)/sr,'hopSeconds':HOP,'modes':modes,'noise':bands,'reference':provenance[id], 'limitation':'Single short lossy preview; endpoint is file truncation, not measured natural T60. Noise and off-reference controls are synthesis approximations.'}
 (OUT/(name.lower()+'.json')).write_text(json.dumps(profile,separators=(',',':'))+'\n')
 order=sorted(peaks,key=lambda i:spectrum[i],reverse=True)[:5]
 report[name]={'duration':len(x)/sr,'dominant_hz':[round(float(freq[i]),2) for i in order],'modes':len(modes),'residual_energy_fraction':float(np.sum(residual**2)/np.sum(x*x)),'source_sha256':provenance[id]['sha256']}
(OUT/'analysis.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
