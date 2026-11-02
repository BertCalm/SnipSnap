"""Regenerate sparse raw samples from the approved Audition 03 Python source.
Run: python3 scripts/generate_lorentz_reference.py (requires NumPy).
Macro inputs are quantized to Float exactly as saved Kotlin patches are.
"""
from pathlib import Path
import itertools
import numpy as np
from lorentz_reference import render, DEFAULTS

rate = 176_400
destination = Path(__file__).resolve().parents[1]/'synth/src/test/resources/lorentz/audition03.tsv'
with destination.open('w') as f:
    f.write('# Audition 03 raw reference; voice TUNE GAP IMPACT frame amplitude; 176400 Hz\n')
    for voice,tune,gap,impact in itertools.product(('FUSE','LORENTZ'),(0.,.5,1.),(0.,.5,1.),(0.,.5,1.)):
        m={k:float(np.float32(v)) for k,v in {**DEFAULTS,'TUNE':tune,'GAP':gap,'IMPACT':impact}.items()}
        raw,d=render(voice,m,rate)
        tc=int(d['collision_s']*rate);end=int(d['contact_end_s']*rate)
        indices=set(np.linspace(0,len(raw)-1,48,dtype=int))
        indices.update([0,1,52,53,tc-2,tc-1,tc,tc+1,tc+2,tc+128,tc+400,end-1,end,end+1])
        for index in sorted(i for i in indices if 0<=i<len(raw)):
            f.write(f'{voice}\t{tune}\t{gap}\t{impact}\t{index}\t{raw[index]:.17g}\n')
print(destination)
