#!/usr/bin/env python3
"""Validate the generated prototype and measure spectra; numpy is used only for FFT analysis."""
import hashlib,io,json,re,sys,wave,zipfile
from pathlib import Path
import numpy as np
root=Path(sys.argv[1]);manifest=json.loads((root/'manifest.json').read_text())
assert len(manifest['clips'])==21
samples={}
for clip in manifest['clips']:
    for mode in ('raw','matched'):
        path=root/clip[mode]
        with wave.open(str(path)) as w:
            assert w.getnchannels()==1 and w.getsampwidth()==3 and w.getframerate()==44100
            assert w.getnframes()==clip['frames']
            data=w.readframes(w.getnframes())
        x=np.array([int.from_bytes(data[i:i+3],'little',signed=True)/8388608 for i in range(0,len(data),3)])
        assert np.isfinite(x).all() and max(abs(x))<=1
        if mode=='raw':
            assert hashlib.sha256(path.read_bytes()).hexdigest()==clip['sha256']
            samples[clip['id']]=x
for source,digest in manifest['sourceSha256'].items():
    assert hashlib.sha256(Path(source).read_bytes()).hexdigest()==digest
for t in manifest['traces']:
    assert t['contactEnergyAdded']<1e-10
    assert t['maximumFreeEnergy']<=t['attackEnergy']*1.03
    assert t['finalEnergy']<t['attackEnergy']*1e-5
archive=root/manifest['download']
assert hashlib.sha256(archive.read_bytes()).hexdigest()==manifest['downloadSha256']
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    kit=json.loads(z.read('Native/kit.json'));assert len(kit['pads'])==2
    sfz_name=next(n for n in z.namelist() if n.endswith('.sfz'))
    sfz=z.read(sfz_name).decode();regions=[s for s in sfz.splitlines() if s.startswith('<region>')]
    assert len(regions)==18
    for i,pad in enumerate(kit['pads']):
        with wave.open(io.BytesIO(z.read('Native/'+pad['sample']))) as w:frames=w.getnframes()
        boundaries=pad['chain']['boundaries']+[frames]
        for n,line in enumerate(regions[i*9:(i+1)*9]):
            fields=dict(re.findall(r'(\w+)=([^ ]+)',line));zone=pad['chain']['zones'][n//3]
            assert int(fields['offset'])==boundaries[n] and int(fields['end'])==boundaries[n+1]-1
            assert int(fields['seq_length'])==3 and int(fields['seq_position'])==n%3+1
            assert int(fields['lovel'])==zone['velStart'] and int(fields['hivel'])==zone['velEnd']
    assert sum(n.endswith('.wav') for n in z.namelist())==4 # Two native WAVs and their SFZ copies.
bands={}
for id in ('reference-0','new-0','reference-1','new-1','heads-only'):
    x=samples[id];e=abs(np.fft.rfft(x))**2;f=np.fft.rfftfreq(len(x),1/44100)
    bands[id]={f'{a}-{b} Hz':float(e[(f>=a)&(f<b)].sum()/e.sum()) for a,b in [(20,200),(200,1000),(1000,4000),(4000,15000)]}
report={'wavFiles':42,'nativePads':2,'sfzRegions':18,'hashesAndWindows':'passed','energyDiagnostics':'passed',
        'renderMsMedian':float(np.median(manifest['renderMs'])),'renderMsMax':max(manifest['renderMs']),
        'spectralEnergyFractions':bands,'listeningAcceptance':'pending','browserAudio':'unverified','mpcHardware':'unverified'}
(root/'validation.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
