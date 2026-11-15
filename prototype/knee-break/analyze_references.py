"""Whole-mix reference analysis; not instrument isolation. Pass four MP4 paths."""
import sys,subprocess,hashlib,json
from pathlib import Path
import numpy as np
from scipy.signal import stft,find_peaks
rows=[]
for i,file in enumerate(sorted(map(Path,sys.argv[1:])),1):
 raw=subprocess.check_output(['ffmpeg','-v','error','-i',str(file),'-vn','-ac','1','-ar','44100','-f','s16le','-'])
 x=np.frombuffer(raw,dtype='<i2').astype(float)/32768;sr=44100
 f,t,z=stft(x,sr,nperseg=1024,noverlap=768);mag=abs(z);flux=np.maximum(np.diff(mag,axis=1),0).sum(axis=0)
 peaks,_=find_peaks(flux,distance=round(.12*sr/256),prominence=np.percentile(flux,75)*.5);chosen=sorted(peaks,key=lambda p:flux[p],reverse=True)[:8];energy=(mag**2).sum(axis=1)
 rows.append({'id':i,'file':file.name,'sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'seconds':len(x)/sr,'peak':float(abs(x).max()),'rms':float(np.sqrt(np.mean(x*x))),'band_energy_fractions':{label:float(energy[(f>=lo)&(f<hi)].sum()/energy.sum()) for label,lo,hi in [('below150',0,150),('150to1500',150,1500),('1500to6000',1500,6000),('above6000',6000,22050)]},'strong_attack_times_seconds':sorted([round(float(t[p+1]),3) for p in chosen])})
print(json.dumps(rows,indent=2))
