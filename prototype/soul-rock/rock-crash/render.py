"""Non-riveted crash audition. Statistical modal plate, not a measured cymbal fit."""
from pathlib import Path
import numpy as np,json,time,gzip,io,hashlib,sys
from scipy import signal
from scipy.io import wavfile
ROOT=Path(__file__).parent;FS=44100

def rms(x):return float(np.sqrt(np.mean(x*x)))
def peak(x):return float(max(abs(signal.resample_poly(x,4,1))))
def write(name,x):
 assert np.isfinite(x).all() and peak(x)<.91
 p=ROOT/name;wavfile.write(p,FS,np.round(x*32767).astype(np.int16));y=wavfile.read(p)[1].astype(float)/32768
 assert peak(y)<.91 and abs(y.mean())<.001 and rms(y[-441:])<.002
 return y

def render(v,take):
 start=time.perf_counter();metal=np.random.default_rng(74126);hit=np.random.default_rng(571+take)
 # Fixed irregular modes and doublets describe one plate across every hit.
 hz=np.sort(np.concatenate([metal.uniform(220,1500,70),metal.uniform(1500,16000,650)]))
 split=metal.uniform(2.,13.,len(hz));hz=np.concatenate([hz,hz+split])
 shape=(1-np.exp(-(hz/1350)**1.8))*np.exp(-hz/19000)*metal.uniform(.8,1.2,len(hz))
 decay=(5.2/(1+(hz/12000)**.7))*np.minimum(1.,.45+hz/2300)*metal.uniform(.8,1.2,len(hz))
 # Strike strength changes spectral weighting and bloom, not just gain.
 amp=shape*(.15+.85*v**(hz/8500))*10**(hit.normal(0,1.3,len(hz))/20)
 phase=hit.uniform(-np.pi,np.pi,len(hz));t=np.arange(round(4.5*FS))/FS;y=np.zeros(len(t))
 for j in range(0,len(hz),16):
  f=hz[j:j+16,None];tt=t[None,:]
  bloom=(1-np.exp(-tt/(.0003+.024*(f/16000)**.7)))*np.exp(-6.907755*tt/decay[j:j+16,None])
  y+=(amp[j:j+16,None]*bloom*np.sin(2*np.pi*f*tt+phase[j:j+16,None])).sum(axis=0)
 # Linear bandlimited modes at output rate; only the nonlinear stage runs at 4x.
 # A common fixed scale retains strike energy and avoids normalizing each component.
 # A short broadband stick/edge contact widens onset; the continuing
 # wash remains the dense modal field, not a steady noise tail.
 strike=signal.sosfilt(signal.butter(2,[1700,14500],fs=FS,btype='bandpass',output='sos'),hit.normal(size=len(t)))
 y*=.018*v**.85
 y+=.22*v**1.5*strike*(1-np.exp(-t/.0003))*np.exp(-t/.014)
 up=signal.resample_poly(y,4,1);up=.85*up+.15*np.tanh(1.7*up)/1.7
 y=signal.resample_poly(up,1,4)
 y=signal.sosfilt(signal.butter(2,35,btype='highpass',fs=FS,output='sos'),y)
 y[-4410:]*=.5*(1+np.cos(np.linspace(0,np.pi,4410)))
 return y,time.perf_counter()-start

if __name__ == '__main__':
    ROOT = Path(sys.argv[1]).resolve()
    ROOT.mkdir(parents=True, exist_ok=True)
    bank = {(z,t): render(v,t)[0] for z,v in enumerate([.34,.64,1.]) for t in range(3)}
    gain = .88 / max(peak(x) for x in bank.values())
    for (z,t), x in bank.items():
        write(f'crash-v{z+1}-t{t+1}.wav', x*gain)
