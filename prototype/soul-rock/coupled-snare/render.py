"""One coupled acoustic snare study. Authored reduced model, not measured drum fit."""
import ctypes,json,time,hashlib,sys
from pathlib import Path
import numpy as np
from scipy import signal,special
from scipy.io import wavfile
ROOT=Path(__file__).parent;FS=44100;INTERNAL=4*FS;H=18;W=28
lib=ctypes.CDLL(str(ROOT/'model.so'));ptr=np.ctypeslib.ndpointer(dtype=np.float64,flags='C_CONTIGUOUS')
lib.simulate.argtypes=[ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_double]+[ptr]*7+[ctypes.c_double]*3+[ptr]
lib.simulate.restype=None
rows=sorted([(z,m) for m in range(7) for z in special.jn_zeros(m,5)])[:H]
ratios=np.array([z/rows[0][0] for z,m in rows])
def render(velocity,take,coupling=.7,wires=1.):
 rng=np.random.default_rng(91021+take);r=.30+rng.uniform(-.06,.06);ang=rng.uniform(0,6.28)
 strike=np.array([special.jv(m,z*r)*np.cos(m*ang) for z,m in rows]);strike/=np.linalg.norm(strike)
 air=np.array([1/(1+i)**1.1 if m==0 else .07/(1+i) for i,(z,m) in enumerate(rows)])
 air/=np.linalg.norm(air);air=np.concatenate([air,-air])
 contacts=[]
 for a in np.linspace(-.80,.80,W):
  rr=np.sqrt(a*a+.06**2);angle=np.arctan2(.06,a)
  c=np.array([special.jv(m,z*rr)*np.cos(m*angle) for z,m in rows]);c*=.23/np.linalg.norm(c);contacts.append(c)
 contacts=np.array(contacts).ravel()
 freq=np.concatenate([190*ratios,295*ratios])*(1+rng.normal(0,.001,2*H))
 decay=np.concatenate([.43/ratios**.65,.24/ratios**.65])
 wf=np.geomspace(720,6900,W)*(1+rng.uniform(-.13,.13,W))
 gaps=rng.uniform(.02e-6,.18e-6,W)
 out=np.zeros((round(1.1*INTERNAL),5));start=time.perf_counter()
 lib.simulate(len(out),H,W,INTERNAL,*[np.ascontiguousarray(x) for x in [freq,decay,strike,air,contacts,wf,gaps]],.075*velocity,coupling,wires,out)
 assert np.isfinite(out).all()
 # Velocity radiation from the heads and wires; one common gain, no separate
 # component normalization or synthetic wire envelope.
 # Emphasize the mechanically generated wire component, not an added noise burst.
 wire_detail=signal.sosfilt(signal.butter(2,1600,fs=INTERNAL,btype='highpass',output='sos'),out[:,2])
 raw=out[:,0]-.65*out[:,1]+6.0*out[:,2]+3.0*wire_detail
 y=signal.resample_poly(raw,1,4);y=signal.sosfilt(signal.butter(2,25,fs=FS,btype='highpass',output='sos'),y)
 y[:44]*=np.linspace(0,1,44);y[-1764:]*=.5*(1+np.cos(np.linspace(0,np.pi,1764)))
 diag={'render_seconds':time.perf_counter()-start,'initial_energy':out[0,4],'max_energy':float(max(out[:,4])),'late_energy':float(max(out[round(.3*INTERNAL):,4])),'wire_rms':float(np.sqrt(np.mean(out[:,2]**2))),'wire_attack_rms':float(np.sqrt(np.mean(out[:round(.03*INTERNAL),2]**2))),'wire_tail_rms':float(np.sqrt(np.mean(out[round(.06*INTERNAL):round(.20*INTERNAL),2]**2))),'contacts':int(np.count_nonzero(out[:,3]))}
 assert diag['max_energy']<diag['initial_energy']*1.12,diag
 assert diag['late_energy']<diag['initial_energy']*.1,diag
 return y,diag

def rms(x):return float(np.sqrt(np.mean(x*x)))
def peak(x):return float(max(abs(signal.resample_poly(x,4,1))))
def write(name,x):
 assert np.isfinite(x).all() and peak(x)<.91
 p=ROOT/name;wavfile.write(p,FS,np.round(x*32767).astype(np.int16));y=wavfile.read(p)[1].astype(float)/32768
 assert abs(np.mean(y))<.001 and rms(y[-441:])<.002 and peak(y)<.91
 return y

if __name__ == '__main__':
    output = Path(sys.argv[1]).resolve()
    output.mkdir(parents=True, exist_ok=True)
    bank = {(z,t): render(v,t)[0] for z,v in enumerate([.34,.64,1.]) for t in range(3)}
    gain = .88 / max(peak(y) for y in bank.values())
    ROOT = output
    for (z,t), y in bank.items():
        write(f'snare-v{z+1}-t{t+1}.wav', y*gain)
