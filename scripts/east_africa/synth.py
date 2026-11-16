"""Adapter for the compact fitted model. JSON calibration is scalar data, not audio."""
from pathlib import Path
from functools import lru_cache
import json,numpy as np
import model
SR=44100
VOICES=['deep-kick','tight-kick','snare','rimshot','rim','closed-hat','open-hat','shaker','low-bongo','high-bongo','conga','bell','low-log','high-log','muted-tap','open-conga','clave','wood-knock','metal-low','metal-high']
@lru_cache(None)
def calibration():return json.loads((Path(__file__).parent/'calibration.json').read_text())
def render(voice,velocity=1.,take=0,tune=1.,damp=1.,strike=.5,oversample=4):
 if voice not in VOICES or not np.isfinite([velocity,tune,damp,strike]).all():raise ValueError('Invalid input')
 v=float(np.clip(velocity,0,1));z=int(np.argmin(abs(np.array(model.VEL)-v)))
 x=model.synthesize(voice,z,take,float(np.clip(tune,.75,1.25)),float(np.clip(damp,.5,2.5)),float(np.clip(strike,0,1)),oversample)
 return x*(v/model.VEL[z])*(calibration()[voice][z] if voice in calibration() else 1.)
