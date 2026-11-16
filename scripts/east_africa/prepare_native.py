"""Export scalar coefficients and take offsets, never waveform data (NumPy/SciPy needed offline)."""
from pathlib import Path
import json
import numpy as np
from scipy import signal
import model
root=Path(__file__).resolve().parents[2]/'synth/src/main/resources/east-africa'
rate=176400
out={'lowpass':signal.butter(8,18000,fs=rate,output='sos').tolist(),
     'highpass':signal.butter(2,22,btype='highpass',fs=44100,output='sos').tolist(),
     'fir':signal.firwin(81,1/4,window=('kaiser',5.0)).tolist(), 'filters':{},'offsets':{}}
for tune in [1.,1.08,.97,1.03]:
    out['filters'][str(tune)]=[{'sos':model.noise_filter(min(17000,max(20,lo*tune)),min(20000,hi*tune),rate)[0].tolist(),
       'norm':float(model.noise_filter(min(17000,max(20,lo*tune)),min(20000,hi*tune),rate)[1])}
       for lo,hi in zip([35,90,180,350,700,1400,2800,5600,10000,16000],[90,180,350,700,1400,2800,5600,10000,16000,20000])]
for voice,layers in model.models().items():
    out['offsets'][voice]=[]
    for layer in layers:
        takes=[]
        for take in range(3):
            rng=np.random.default_rng(model.seed(voice,take))
            modes=[[float(rng.uniform(-.0008,.0008)),float(rng.uniform(-.07,.07)),float(rng.uniform(-.035,.035))] for _ in layer['modes']]
            noise=[float(rng.uniform(-.025,.025)) for _ in layer['noise']]
            takes.append({'modes':modes,'noise':noise})
        out['offsets'][voice].append(takes)
(root/'native.json').write_text(json.dumps(out,separators=(',',':'))+'\n')
# Single authoritative model data in the runtime resource directory.
for name in ['models.json','calibration.json']:
    (root/name).write_bytes((Path(__file__).parent/name).read_bytes())
