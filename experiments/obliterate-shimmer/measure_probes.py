#!/usr/bin/env python3
"""Reproduce the one-tone/release probes separately from WAV production."""
import json,math,hashlib
from pathlib import Path
import numpy as np
import prototype as p
OUT=Path(__file__).resolve().parents[2]/'testkit/obliterate-shimmer-audition'
n=np.arange(44100);x=.2*np.sin(2*np.pi*16000*n/p.RATE)
a=p.octave_up(x,False)[10000:];b=p.octave_up(x,True)[10000:]
reduction=20*np.log10(np.sqrt(np.mean(a*a))/np.sqrt(np.mean(b*b)))
imp=np.zeros((44100,2));imp[0]=.5;r=p.render(imp,.3)
early=np.sqrt(np.mean(r.wet[2000:12000]**2));late=np.sqrt(np.mean(r.wet[40000:44000]**2))
diagnostics=json.loads((OUT/'render-diagnostics.json').read_text())
frequency_probes=[]
for hz in [220.,440.,233.]:
    y=p.octave_up(.2*np.sin(2*np.pi*hz*n/p.RATE))[10000:,0]
    peak=np.fft.rfftfreq(len(y),1/p.RATE)[np.argmax(np.abs(np.fft.rfft(y*np.hanning(len(y)))))]
    frequency_probes.append({'inputHz':hz,'dominantOutputHz':float(peak),'centsFromOctave':1200*math.log2(peak/(2*hz))})
report={'sourceCommit':diagnostics['provenance']['sourceCommit'],'workingTreeDirty':diagnostics['provenance']['workingTreeDirty'],
    'manifestSha256':hashlib.sha256((OUT/'manifest.json').read_bytes()).hexdigest(),'highToneProbeHz':16000,'octaveReaderFoldedHighReductionDb':float(reduction),
    'scope':'single input tone, shift reader only; not complete-chain alias rejection',
    'impulseWetLateToEarlyDb':float(20*np.log10(late/early)), 'tankInfinityContractionBound':.76,
    'frequencyProbes':frequency_probes,'renderHostMs':[{'source':d['sourceId'],'variant':d['variant'],'ms':d['shimmer']['hostRenderMs']} for d in diagnostics['cases']],
    'wetTailMaximumBeforeFade':max(d['shimmer'].get('wetLast100msPeakBeforeFade',0) for d in diagnostics['cases'])}
(OUT/'probes.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='renderHostMs'}))
