"""Describe owner reference recordings without treating recording gain as force.

Usage: analyze.py decoded_reference_directory output_directory
Inputs are private, mono float WAVs named ref-1.wav through ref-5.wav.
Detected events are candidate transients, not verified toe/heel labels.
"""
import json, sys
from pathlib import Path
import numpy as np
from scipy import signal
from scipy.io import wavfile
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

RATE = 44100
BANDS = [80, 250, 800, 2000, 5000, 12000]

def read(path):
    rate, x = wavfile.read(path)
    assert rate == RATE and x.ndim == 1
    return x.astype(float) / (2**(x.dtype.itemsize*8-1) if x.dtype.kind == 'i' else 1)

def metrics(x):
    energy = np.dot(x, x)
    cum = np.cumsum(x*x) / max(energy, 1e-20)
    f, p = signal.periodogram(x, RATE)
    bands = [float(p[(f>=a)&(f<b)].sum()) for a,b in zip(BANDS[:-1],BANDS[1:])]
    total = max(sum(bands),1e-20)
    return dict(rms=float(np.sqrt(energy/len(x))), peak=float(max(abs(x))),
                t50_ms=float(np.searchsorted(cum,.5)/RATE*1000),
                t90_ms=float(np.searchsorted(cum,.9)/RATE*1000),
                crest_db=float(20*np.log10(max(abs(x))/max(np.sqrt(energy/len(x)),1e-20))),
                band_fraction=[b/total for b in bands])

def main(src, out):
    out.mkdir(parents=True,exist_ok=True)
    report = {'band_edges_hz': BANDS, 'event_window_ms': 120, 'references': []}
    fig, axes = plt.subplots(5,2,figsize=(12,12))
    for index in range(1,6):
        x = read(src/f'ref-{index}.wav')
        hp = signal.sosfilt(signal.butter(2,300,fs=RATE,btype='highpass',output='sos'),x)
        hop=220
        env=np.sqrt(np.array([np.mean(hp[i:i+hop]**2) for i in range(0,len(x)-hop,hop)]))
        delta=np.maximum(0,env-np.r_[0,env[:-1]])
        peaks,_=signal.find_peaks(delta,distance=16,prominence=max(delta)*.1)
        events=[]
        for pk in peaks:
            # Move to a low-energy point before the measured jump, bounded to 10ms.
            start=max(0,(pk-1)*hop)
            stop=min(len(x),start+round(.12*RATE))
            m=metrics(x[start:stop]);m['start_seconds']=start/RATE
            m['onset_strength']=float(delta[pk]);events.append(m)
        report['references'].append(dict(id=index,duration=len(x)/RATE,events=events))
        t=np.arange(len(x))/RATE
        axes[index-1,0].plot(t[::44],x[::44],lw=.4)
        for e in events:axes[index-1,0].axvline(e['start_seconds'],alpha=.25,color='r')
        axes[index-1,0].set(title=f'Reference {index}: candidate onsets',xlabel='seconds')
        axes[index-1,1].specgram(x,NFFT=512,Fs=RATE,noverlap=384,vmin=-100,vmax=-35)
        axes[index-1,1].set(ylim=(0,10000),xlabel='seconds',ylabel='Hz')
    fig.tight_layout();fig.savefig(out/'overview.png',dpi=100);plt.close(fig)
    (out/'analysis.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({r['id']:{'events':len(r['events']), 'strongest': sorted(r['events'],key=lambda e:e['onset_strength'],reverse=True)[:5]} for r in report['references']},indent=2))

if __name__=='__main__':main(Path(sys.argv[1]),Path(sys.argv[2]))
