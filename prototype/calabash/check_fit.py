"""Each fitted voice against the recording it was fitted to, a real second take, and its own soft layer.

Python harness, not Kotlin. Run after fit_calabash.py, on the same decoded WAVs:
    python3 -I prototype/calabash/check_fit.py <dir of decoded VCSL WAVs>
Every comparison is high-passed at the voice's floor first (fit_calabash.VOICES), so a bell is not marked down for the
room rumble under it that the fit leaves out on purpose. Distances are EMBER's and CLUTTER's house measure."""
import sys, os, json, numpy as np, warnings
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE); sys.path.append(os.path.join(HERE, "..", "ember"))
import fitted as F, measures as ms
from scipy.io import wavfile
warnings.simplefilter("ignore")
D=sys.argv[1]
def rec(name, secs):
    r,x=wavfile.read(os.path.join(D,name+".wav")); x=x.astype(float)
    a=np.abs(x); i=max(0,int(np.argmax(a>.05*a.max()))-22); x=x[i:i+int(secs*44100)]
    return np.pad(x,(0,max(0,int(secs*44100)-len(x))))
def full(a,b):
    n=max(len(a),len(b)); a=np.pad(a,(0,n-len(a))); b=np.pad(b,(0,n-len(b)))
    A,B=ms.spectro_db(a,ms=10,total=n/44100),ms.spectro_db(b,ms=10,total=n/44100); return round(float(np.sqrt(np.mean((A-B)**2))),2)
d=F.data()["voices"]
for name,v in d.items():
    h,s=v["files"]; secs=min(0.8, v["seconds"])
    x=rec(h,secs); xs=rec(s,secs)
    # real take to compare: rr2 of hard file if exists
    alt=h.replace("rr1","rr2") if "rr1" in h else h.replace("rr2","rr1").replace("rr3","rr2").replace("rr4","rr1")
    xa=rec(alt,secs) if os.path.exists(os.path.join(D,alt+".wav")) and alt!=h else None
    from scipy import signal as sg
    fl=v.get("floor",40); hpf=lambda q: sg.sosfiltfilt(sg.butter(4,max(fl,40)/22050,"high",output="sos"),q)
    x,xs=hpf(x),hpf(xs); xa=hpf(xa) if xa is not None else None
    y=F.fitted(name,1.0,np.random.default_rng(1),secs)
    ysoft=F.fitted(name,.3,np.random.default_rng(1),secs)
    y2=F.fitted(name,1.0,np.random.default_rng(2),secs)
    print(f"{name:14s} vs rec150 {ms.take_distance_db(y,x):5.1f} whole {full(y,x):5.1f} | soft vs softrec {ms.take_distance_db(ysoft,xs):5.1f} | real takes {ms.take_distance_db(x,xa) if xa is not None else float('nan'):5.1f} | model takes {ms.take_distance_db(y,y2):5.1f} | cen {ms.centroid(y):5d} rec {ms.centroid(x):5d} | model soft-hard {ms.take_distance_db(y,ysoft):4.1f} real soft-hard {ms.take_distance_db(x,xs):4.1f}")
