"""Measure reference-fit agreement on Kotlin WAVs; no perceptual acceptance claim."""
import json
from pathlib import Path
import numpy as np
from scipy.io import wavfile
from scipy.signal import stft, find_peaks
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
ROOT=Path('testkit/fired-bronze-r2')
IDS={'tin':'171911','tun':'171909','na':'171905','te':'171913','ga':'171902'}
def read(p):
 sr,x=wavfile.read(p);return sr,x.astype(float)/(32768 if x.dtype==np.int16 else 2147483648)
def spectrum(x,sr):
 n=131072;f=np.fft.rfftfreq(n,1/sr);a=np.abs(np.fft.rfft(x*np.hanning(len(x)),n));return f,a/max(a)
report={};fig,axes=plt.subplots(3,1,figsize=(11,9),layout='constrained')
for name,id in IDS.items():
 sr,a=read(ROOT/'references'/(id+'.wav'));_,b=read(ROOT/'native'/(name+'_default.wav'))
 f,sa=spectrum(a,sr);_,sb=spectrum(b,sr)
 useful=(f>=65)&(f<8000)
 similarity=float(np.dot(sa[useful],sb[useful])/(np.linalg.norm(sa[useful])*np.linalg.norm(sb[useful])))
 def peak(s):return float(f[np.where(useful)[0][np.argmax(s[useful])]])
 # Envelope shape comparison at 5 ms resolution after energy normalization.
 def rms(x):
  hop=220;return np.array([np.sqrt(np.mean(x[i:i+hop]**2)) for i in range(0,len(x),hop)])
 ea,eb=rms(a),rms(b);n=min(len(ea),len(eb));ea=ea[:n]/max(ea);eb=eb[:n]/max(eb)
 report[name]={'reference_peak_hz':peak(sa),'kotlin_peak_hz':peak(sb),'spectrum_cosine_65_8000':similarity,'normalized_envelope_mae':float(np.mean(abs(ea-eb))),'reference_seconds':len(a)/sr,'synth_seconds':len(b)/sr}
 if name in ['tin','tun','na']:
  ax=axes[['tin','tun','na'].index(name)];ax.plot(f,20*np.log10(sa+1e-6),label='Reference preview',color='#555555',lw=1.4);ax.plot(f,20*np.log10(sb+1e-6),label='Kotlin R2',color='#bd681d',alpha=.8,lw=1)
  ax.set(xlim=(100,3000),ylim=(-65,2),title=f'{name.upper()} · spectral cosine {similarity:.3f}',ylabel='dB relative to peak');ax.grid(alpha=.15);ax.legend(loc='upper right')
axes[-1].set_xlabel('Frequency (Hz)');fig.suptitle('Fired Bronze R2: measured reference versus dry synthesis\nSingle short MP3 previews; these plots do not prove instrument fidelity',fontsize=13)
fig.savefig(ROOT/'reference-comparison.png',dpi=140)
(ROOT/'reference-comparison.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
