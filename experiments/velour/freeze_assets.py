"""Freeze owner-selected synthetic sources and finite DSP tables; no captured audio.
Run from repository root: python3 experiments/velour/freeze_assets.py
Fixtures are the independent Python reference for the native processing port.
"""
from pathlib import Path
import json, hashlib
import numpy as np
from scipy import signal
from scipy.io import wavfile
import dry_round2 as drums
import selected_c_round3 as selected
import velour as v
import snipkit as sk

ROOT=Path(__file__).resolve().parents[2]
ASSETS=ROOT/'synth/src/main/resources/velour/v1'
TEST=ROOT/'synth/src/test/resources/velour'
ASSETS.mkdir(parents=True,exist_ok=True); TEST.mkdir(parents=True,exist_ok=True)

for voice in ['kick','snare']:
    for zone,velocity in [('soft',.42),('mid',.7),('hard',1.)]:
        wavfile.write(ASSETS/f'{voice}-{zone}.wav',sk.RATE,drums.render(voice,'C',velocity).astype(np.float32))

for tag,space in [('velour',.3),('bloom',.6)]:
    tr=np.arange(4*sk.RATE)/sk.RATE
    ir=np.random.default_rng(sk.seed_for('velour','room-v1')).normal(size=len(tr))
    ir=v.lp(ir,3900)*np.exp(-6.9078*tr/(.45+2.4*space))
    ir[:int(.022*sk.RATE)]=0; ir/=max(1,np.abs(ir).sum())
    ir.astype('<f4').tofile(ASSETS/f'{tag}-room.f32')

# Freeze the existing event RNG draws, not a second competing runtime noise generator.
np.random.default_rng(sk.seed_for('velour','grain-v1')).random((256,8,3)).astype('<f8').tofile(ASSETS/'grain-draws.f64')
filters={}
for name,hz,rate,kind,order in [('lp3900',3900,44100,'low',2),('lp4800',4800,44100,'low',2),
    ('hp20',20,44100,'high',2),('hp48',48,176400,'high',2),('hp55',55,176400,'high',2),('cab',5300,176400,'low',4)]:
    filters[name]=signal.butter(order,hz,fs=rate,btype=kind,output='sos').tolist()
fir=signal.firwin(81,1/4,window=('kaiser',5.0)).tolist()
code='package com.snipsnap.synth\n\n/** Frozen SciPy filter coefficients used by the owner-approved Velour model 1. */\ninternal object VelourCoefficients {\n'
for name,rows in filters.items():
    code+=f'    val {name} = arrayOf(\n'+''.join('        doubleArrayOf('+', '.join(repr(x) for x in row)+'),\n' for row in rows)+'    )\n'
code+='    val fir = doubleArrayOf('+', '.join(repr(x) for x in fir)+')\n}\n'
(ROOT/'synth/src/main/kotlin/com/snipsnap/synth/VelourCoefficients.kt').write_text(code)

dry,send,onsets=selected.groove()
for name,x in [('dry',dry),('send',send)]:wavfile.write(TEST/f'{name}.wav',44100,x.astype(np.float32))
for tag,m in [('velour',v.DEFAULTS),('bloom',v.DEFAULTS|dict(SCATTER=.65,SPACE=.6,DRIVE=.5))]:
    a,b,_=v.treatment(dry,send,onsets,m)
    wavfile.write(TEST/f'{tag}.wav',44100,b.astype(np.float32))
(TEST/'onsets.json').write_text(json.dumps(onsets))
manifest=dict(model=1,source='Synthetic Python C source selected by owner; no video samples.',
    approval='Both round-3 treatments approved; new kit extensions remain listening candidates.',
    source_settings=drums.SETTINGS['C'],
    files={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(ASSETS.iterdir()) if p.name != 'manifest.json'})
(ASSETS/'manifest.json').write_text(json.dumps(manifest,indent=2))
print('Frozen source bank, DSP tables and independent parity fixtures.')
