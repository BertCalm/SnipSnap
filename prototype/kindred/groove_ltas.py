"""Band balance of the four kit grooves against the median of the owner's eleven DFAM reference clips
(REF, measured 2026-11-15 from phone captures of YouTube demos; the clips are not in the repo).
Run: KINDRED_MODEL=2 python3 groove_ltas.py out_dir"""
import os, sys, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kindred_kits as kk
from scipy import signal
BANDS=[(25,60),(60,150),(150,400),(400,1000),(1000,2500),(2500,6000),(6000,16000)]
REF=np.array([-9.0,-7.9,-7.2,-8.0,-9.6,-16.3,-25.5])
def ltas(x,r=44100):
    f,p=signal.welch(x,r,nperseg=8192); tot=p[(f>25)&(f<16000)].sum()
    return np.array([10*np.log10(p[(f>=a)&(f<b)].sum()/tot) for a,b in BANDS])
out=sys.argv[1]; os.makedirs(out,exist_ok=True)
L=[]
for k in kk.KITS:
    g=kk.groove(k); L.append(ltas(g)); kk.sk.write_wav(f"{out}/{k.lower()}_groove.wav", kk.kp.level(g,.13))
    print("%-11s"%k+"".join("%7.1f"%v for v in L[-1]))
m=np.median(L,0); print("%-11s"%"median"+"".join("%7.1f"%v for v in m)); print("%-11s"%"minus ref"+"".join("%7.1f"%v for v in m-REF))
