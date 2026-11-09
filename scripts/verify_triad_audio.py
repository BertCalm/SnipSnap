"""Independent inspection of delivered PCM, not a listening verdict or hardware match."""
from pathlib import Path
import json
import numpy as np
from scipy.io import wavfile
from scipy.signal import welch
root=Path(__file__).resolve().parents[1]/'testkit/triad-audition'
rows=[]
for p in sorted(root.glob('*.wav')):
    sr,pcm=wavfile.read(p)
    assert sr==44100 and pcm.ndim==1 and pcm.dtype==np.int32
    x=pcm.astype(np.float64)/2**31
    f,power=welch(x,sr,nperseg=8192)
    peak=float(np.max(np.abs(x)));dc=float(x.mean())
    assert np.isfinite(x).all() and .001<peak<1 and abs(dc)<.01,p.name
    rows.append(dict(file=p.name,peak=peak,dc=dc,rms=float(np.sqrt(np.mean(x*x))),above18kFraction=float(power[f>18000].sum()/power.sum()),tailRms=float(np.sqrt(np.mean(x[-4410:]**2)))))
assert len(rows)==44
(root/'pcm-verification.json').write_text(json.dumps({'status':'Independent Python measurements of native Kotlin PCM24 files','limitations':'No listening verdict. High-band power is not an alias-error measurement. These are finite phrases, not seamless loops.','files':rows},indent=2))
print(f'Validated {len(rows)} mono 44.1 kHz PCM files; max peak {max(r["peak"] for r in rows):.6f}; max abs DC {max(abs(r["dc"]) for r in rows):.6f}')
