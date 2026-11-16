"""Generate coefficient-only Kotlin data from the fitted tap model; no reference PCM."""
import json
from pathlib import Path
import numpy as np
from scipy import signal
root=Path(__file__).resolve().parents[3];m=json.loads(Path(__file__).with_name('model-v1.json').read_text())
def arr(x):return 'doubleArrayOf('+','.join(repr(float(v)) for v in x)+')'
def bank(rows):return 'arrayOf('+',\n'.join(arr(x) for x in rows)+')'
filters={}
for lo,hi in zip(m['edges'][:-1],m['edges'][1:]):filters[f'{int(lo)}_{int(hi)}']=signal.butter(2,[lo,hi],fs=176400,btype='bandpass',output='sos')
for lo,hi in [(100,1700),(120,1800),(650,7500),(1000,10500),(300,9000),(70,900),(600,13000)]:filters[f'{lo}_{hi}']=signal.butter(2,[lo,hi],fs=176400,btype='bandpass',output='sos')
variances=[]
for lo,hi in zip(m['edges'][:-1],m['edges'][1:]):
 _,h=signal.sosfreqz(filters[f'{int(lo)}_{int(hi)}'],worN=8192);variances.append(np.mean(abs(h)**2))
text='''package com.snipsnap.synth

/** Generated broad-band coefficients, not audio. See prototype/footwork/reference_reset/native_coefficients.py.
 * Reference analysis credit: Chinouk211, "tap dancing.", https://freesound.org/people/Chinouk211/sounds/592705/
 * CC BY 4.0 https://creativecommons.org/licenses/by/4.0/ ; no recording is loaded by the renderer.
 */
internal object FootworkData {
'''
text+=f"val edges = {arr(m['edges'])}\nval decays = {arr(m['decays'])}\nval powers = {bank(m['bandPowers'])}\nval variance = {arr(variances)}\nconst val gain = {m['gain']}\nconst val damping = {m['contactDampingSeconds']}\n"
text+='val filters = mapOf(\n'+',\n'.join('"'+k+'" to '+bank(v) for k,v in filters.items())+'\n)\n'
text+='val heelLowpass = '+bank(signal.butter(2,3700,fs=44100,output='sos'))+'\n}\n'
(root/'synth/src/main/kotlin/com/snipsnap/synth/FootworkData.kt').write_text(text)
