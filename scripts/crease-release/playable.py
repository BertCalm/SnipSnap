#!/usr/bin/env python3
"""Measure a PCM release cache against actual compiled Kotlin continuations.

The source circuit is not ported to Python. This is research tooling, not a
shipping player. Thresholds and cases are fixed in playable-protocol.md.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

os.environ['OPENBLAS_NUM_THREADS'] = '1'
os.environ['OMP_NUM_THREADS'] = '1'
import numpy as np
import prototype as p

ROOT = Path(__file__).resolve().parents[2]
RATE = 44100
SECONDS = 1.2
FADE_FRAMES = round(SECONDS * RATE)
CROSSFADE = round(.005 * RATE)


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + '\n')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def source_hashes():
    paths = [Path(__file__), Path(__file__).with_name('CreaseReleaseBank.java'),
             Path(__file__).with_name('CreaseReleaseExperiment.java'),
             Path(__file__).with_name('playable-protocol.md')]
    for module in ['synth', 'audio', 'json', 'kit', 'xpm']:
        paths.extend((ROOT/module/'src/main').rglob('*.kt'))
    return {str(x.relative_to(ROOT)): sha(x.read_bytes()) for x in sorted(paths)}


def execute(raw, skip_build):
    env = os.environ.copy()
    env.pop('ANDROID_HOME', None); env.pop('ANDROID_SDK_ROOT', None)
    if not skip_build:
        cmd = ['./gradlew', '--no-daemon', '--max-workers=2', ':synth:jar']
        with (raw/'synth-jar.log').open('w') as f:
            result = subprocess.run(cmd, cwd=ROOT, env=env, stdout=f, stderr=subprocess.STDOUT)
        (raw/'synth-jar.exit').write_text(str(result.returncode)+'\n')
        p.require(result.returncode == 0, 'fresh synth JAR failed')
    jars = p.classpath()
    bins = p.jdk()
    classes = raw/'classes'; classes.mkdir(exist_ok=True)
    cp = os.pathsep.join(map(str,jars))
    subprocess.run([str(bins/'javac'), '--release', '17', '-cp', cp, '-d', str(classes),
                    str(Path(__file__).with_name('CreaseReleaseExperiment.java')),
                    str(Path(__file__).with_name('CreaseReleaseBank.java'))], check=True, cwd=ROOT)
    cmd = [str(bins/'java'), '-Xmx2g', '-cp', cp+os.pathsep+str(classes),
           'CreaseReleaseBank', str(raw)]
    before = source_hashes()
    with (raw/'capture.log').open('w') as f:
        result = subprocess.run(cmd, cwd=ROOT, stdout=f, stderr=subprocess.STDOUT)
    (raw/'capture.exit').write_text(str(result.returncode)+'\n')
    p.require(result.returncode == 0, 'actual Kotlin capture failed: raw/capture.log')
    p.require(before == source_hashes(), 'sources changed during capture')
    receipt = {'sourceCommit': p.git('rev-parse','HEAD'), 'dirty': bool(p.git('status','--porcelain')),
               'sources': before, 'jars': {str(j): sha(j.read_bytes()) for j in jars},
               'javaCommand': cmd, 'javaExit': result.returncode,
               'build': 'explicit prebuilt JAR reuse' if skip_build else 'fresh :synth:jar',
               'capturedAt': datetime.now(timezone.utc).isoformat()}
    write(raw/'provenance.json',receipt)
    return receipt


def fade_curve():
    gain = np.float32(1); decrement = np.float32(1)/np.float32(np.float32(SECONDS)*RATE)
    out = np.empty(FADE_FRAMES,dtype=np.float64)
    for i in range(FADE_FRAMES):
        gain = np.maximum(np.float32(0),np.float32(gain-decrement))
        out[i] = gain
    return out


def cubic(x, position, loop_start=None):
    a = np.asarray(position, dtype=np.float64)
    if loop_start is not None:
        a = np.where(a >= len(x), loop_start+(a-loop_start)%(len(x)-loop_start), a)
    i = np.floor(a).astype(np.int64); t = a-i
    def tap(offset):
        at = i+offset
        if loop_start is not None:
            at = np.where(at>=len(x),loop_start+(at-loop_start)%(len(x)-loop_start),at)
        return x[np.clip(at,0,len(x)-1)]
    t2=t*t; t3=t2*t
    return (.5*(2*t2-t3-t)*tap(-1)+(1-2.5*t2+1.5*t3)*tap(0)
            +(.5*t+t2-1.5*t3)*tap(1)+.5*(t3-t2)*tap(2))


def neighbours(cut, grid, meta):
    start=meta['loopStart']; period=meta['loopFrames']
    position = cut if cut<start else start+(cut-start)%period
    i=max(0,int(np.searchsorted(grid,position,side='right'))-1)
    left=grid[i]
    if i+1<len(grid):
        right=grid[i+1]; coordinate=right
    else:
        right=start; coordinate=start+period
    weight=(position-left)/max(1,coordinate-left)
    return left,right,float(weight)


def approximation(cut, grid, banks, held, meta, canary=False):
    left,right,weight=neighbours(cut,grid,meta)
    clock=np.arange(FADE_FRAMES,dtype=np.float64)
    root_period=meta['loopFrames']/meta['rootCycles']
    def read(key):
        shift = 0 if canary else ((cut-key+root_period/2)%root_period-root_period/2)
        return cubic(banks[key],meta['leadFrames']+shift+clock)
    if canary:
        tail=read(left if weight<.5 else right)
    else:
        tail=(1-weight)*read(left)+weight*read(right)
    held_continuation=cubic(held,cut+clock,meta['loopStart'])
    blend=np.minimum(1,clock/CROSSFADE)
    blend=blend*blend*(3-2*blend)
    return held_continuation+(tail-held_continuation)*blend


def metrics(actual, reference, meta):
    e=actual-reference
    ref_rms=p.rms(reference); er=p.rms(e)
    tv=[]
    for start,end in [(0.04,.25),(.25,.5)]:
        sl=slice(round(start*RATE),round(end*RATE))
        tv.append(p.variation(actual[sl],reference[sl],meta['hz']))
    return {'referenceRms':ref_rms,'errorRms':er,'normalizedErrorRms':er/max(ref_rms,.005),
            'snrDb':float(20*np.log10(max(ref_rms,1e-30)/max(er,1e-30))),
            'peakError':float(np.max(np.abs(e))), 'harmonicTv':tv,
            'keyUpStepError':float(abs(e[0])), 'first10msPeakError':float(np.max(np.abs(e[:441])))}


def encode_cache(out, pcm):
    raw=pcm.astype('<i2').tobytes()
    options=[]
    for order in [0,1,2]:
        values=pcm.astype(np.int32)
        for _ in range(order): values=np.diff(values,prepend=0)
        predicted=values.astype('<i2').tobytes()
        bytes_=np.frombuffer(predicted,dtype=np.uint8).reshape(-1,2).T.copy().tobytes()
        packed=gzip.compress(bytes_,compresslevel=6,mtime=0)
        options.append((len(packed),order,packed))
    _,order,packed=min(options,key=lambda row:row[0])
    flat=np.frombuffer(gzip.decompress(packed),dtype=np.uint8).reshape(2,-1).T.copy()
    decoded=np.frombuffer(flat.tobytes(),dtype='<i2').astype(np.int64)
    for _ in range(order): decoded=np.cumsum(decoded,dtype=np.int64)
    p.require(decoded.astype('<i2').tobytes()==raw,'lossless PCM container changed samples')
    filename='reed-mark-c4.cpcb'
    (out/filename).write_bytes(packed)
    return {'file':filename,'compression':'gzip','bytePlanes':2,'deltaOrder':order,
            'compressedBytes':len(packed),'pcmBytes':len(raw),'pcmSha256':sha(raw),
            'compressedSha256':sha(packed),'containerRoundTripExact':True,
            'codecCandidates':[{'order':o,'bytes':n} for n,o,_ in options]}


def analyse(out,receipt):
    raw=out/'raw'; meta=json.loads((raw/'capture.json').read_text())
    p.require(meta['publicPrefixError']==meta['forkStateError']==0,'exact parity failed')
    held_raw=np.fromfile(raw/'held.f32',dtype='<f4')
    all_cuts=meta['bankCuts']
    raw_banks={cut:np.fromfile(raw/f'bank-{i:04d}.f32',dtype='<f4') for i,cut in enumerate(all_cuts)}
    max_peak=max(float(np.max(np.abs(held_raw))),*(float(np.max(np.abs(x))) for x in raw_banks.values()))
    gain=min(.1834*meta['velocity']**.4/meta['sustainLoudness'], .85/max_peak)
    def pcm(x): return np.rint(x.astype(np.float64)*gain*32767).astype('<i2')
    held_pcm=pcm(held_raw); banks_pcm={k:pcm(v) for k,v in raw_banks.items()}
    held=held_pcm.astype(np.float64)/32767
    banks={k:v.astype(np.float64)/32767 for k,v in banks_pcm.items()}
    fade=fade_curve()
    references={cut:np.fromfile(raw/f'exact-{cut:07d}.f32',dtype='<f4')[:FADE_FRAMES].astype(np.float64)*gain*fade
                for cut in meta['testCuts']}
    report={'scope':'Python/SciPy measurements of actual Kotlin circuit captures and actual PCM16 cache',
            'renderSource':receipt['sourceCommit'],'capture':meta,'commonGain':gain,
            'thresholds':{'normalizedErrorRms':.031623,'peakError':.025,'harmonicTv':.02,
                          'keyUpStepError':.001,'pcmBytes':32*1024**2,'compressedBytes':12*1024**2},
            'candidates':[]}
    eligible=[]
    for n in [16,32,64]:
        grid=meta['grids'][str(n)]; rows=[]
        for cut,reference in references.items():
            actual=approximation(cut,grid,banks,held,meta)*fade
            row={'cutFrame':cut,'holdSeconds':cut/RATE,**metrics(actual,reference,meta)}
            row['passed']=row['normalizedErrorRms']<=.031623 and row['peakError']<=.025 and max(row['harmonicTv'])<=.02 and row['keyUpStepError']<=.001
            rows.append(row)
        size=held_pcm.nbytes+sum(banks_pcm[c].nbytes for c in grid)
        entry={'positionsPerPeriod':n,'tailCount':len(grid),'pcmBytes':size,'cases':rows,
               'maxNormalizedErrorRms':max(r['normalizedErrorRms'] for r in rows),
               'maxPeakError':max(r['peakError'] for r in rows),
               'maxHarmonicTv':max(max(r['harmonicTv']) for r in rows),
               'failedCases':sum(not r['passed'] for r in rows),
               'passed':all(r['passed'] for r in rows) and size<=32*1024**2}
        report['candidates'].append(entry)
        if size<=32*1024**2: eligible.append(entry)
        print(f"N={n}: {entry['failedCases']}/{len(rows)} approximation failures, {size} PCM bytes, worst NRMS {entry['maxNormalizedErrorRms']:.6f}",flush=True)
    passing=[r for r in eligible if r['passed']]
    selected=min(passing,key=lambda r:r['pcmBytes']) if passing else min(eligible,key=lambda r:r['maxNormalizedErrorRms'])
    n=selected['positionsPerPeriod'];grid=meta['grids'][str(n)]
    pcm_all=np.concatenate([held_pcm]+[banks_pcm[c] for c in grid])
    container=encode_cache(out,pcm_all)
    selected['compressedBytes']=container['compressedBytes']
    selected['passed'] &= container['compressedBytes']<=12*1024**2
    canary=[]
    for cut in meta['testCuts']:
        if cut>=meta['loopStart']:
            x=approximation(cut,grid,banks,held,meta,canary=True)*fade
            canary.append(p.rms(x-references[cut])/max(p.rms(references[cut]),.005))
    p.require(max(canary)>.031623,'unaligned-tail canary did not expose an alignment error')
    report['unalignedCanaryMaxNormalizedErrorRms']=max(canary)
    report['selectedN']=n;report['approximationPassed']=bool(selected['passed'])
    report['phoneIntegrationGate']='pending browser checks' if selected['passed'] else 'blocked by approximation gates'
    # Small fixed A/B/C examples keep exact/fade references reviewable alongside live playing.
    clips=[]
    examples=[.013,.137,.777,2.345,9.123]
    for seconds in examples:
        cut=round(seconds*RATE)
        prefix=held_raw[:min(cut,RATE)].astype(np.float64)*gain
        if cut>RATE:
            prefix=cubic(held,np.arange(cut-RATE,cut),meta['loopStart'])
        choices={'exact':references[cut], 'cached':approximation(cut,grid,banks,held,meta)*fade,
                 'fade':np.fromfile(raw/f'fade-{cut:07d}.f32',dtype='<f4')[:FADE_FRAMES].astype(np.float64)*gain*fade}
        for mode,tail in choices.items():
            x=np.concatenate([prefix,tail,np.zeros(5292)])
            stem=f'hold-{cut}-{mode}'
            audio=p.export_audio(out,stem,x)
            clips.append({'id':stem,'holdSeconds':seconds,'keyUpSeconds':len(prefix)/RATE,'mode':mode,**audio})
    manifest={'format':'crease-playable-pcm-v1','name':'Reed Mark','midi':60,'model':3,
              'rate':RATE,'velocity':meta['velocity'],'renderSource':receipt['sourceCommit'],
              'publicPrefixError':0,'stateForkError':0,'commonGain':gain,
              'loopStart':meta['loopStart'],'loopFrames':meta['loopFrames'],'heldFrames':len(held_pcm),
              'rootCycles':meta['rootCycles'],'hz':meta['hz'],'leadFrames':meta['leadFrames'],
              'tailFrames':meta['tailFrames'],'tailCuts':grid,'positionsPerPeriod':n,
              'releaseSeconds':SECONDS,'crossfadeFrames':CROSSFADE,'maxVoices':8,
              'approximationPassed':bool(selected['passed']),'container':container,'clips':clips,
              'cacheIdentity':{'patch':{'preset':'reed-mark','midi':60,'velocity':meta['velocity'],'model':3,'macros':meta['macros']},
                               'sourceHashes':receipt['sources'],'jarHashes':receipt['jars'],
                               'algorithm':'carrier-aligned-linear-v1','rate':RATE,'grid':grid}}
    write(out/'manifest.json',manifest);write(out/'report.json',report)
    (out/'README.txt').write_text('Reed Mark C4 playable-release research. See manifest.json and report.json.\n'
        'The actual Kotlin circuit supplies every reference and cached tail. Python measures a PCM16 approximation.\n'
        'Phone/MPC playback and musical preference are not established by these files.\n'
        f'Approximation gates passed: {selected["passed"]}. Render source: {receipt["sourceCommit"]}.\n')
    return report


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--analyse-only',action='store_true')
    parser.add_argument('--skip-build',action='store_true',help='explicitly reuse the already freshly built unchanged synth JAR')
    args=parser.parse_args()
    out=ROOT/'testkit/crease-playable-release';raw=out/'raw';raw.mkdir(parents=True,exist_ok=True)
    started=time.monotonic()
    if args.analyse_only:
        receipt=json.loads((raw/'provenance.json').read_text())
        p.require(receipt['sources']==source_hashes(),'source hashes differ from saved execution')
        p.require(receipt['jars']=={str(j):sha(j.read_bytes()) for j in p.classpath()},'runtime JARs changed')
    else:
        p.require(not p.git('status','--porcelain'),'commit the experiment before rendering')
        receipt=execute(raw,args.skip_build)
    report=analyse(out,receipt)
    print(json.dumps({'approximationPassed':report['approximationPassed'],'selectedN':report['selectedN'],
                      'analysisSeconds':time.monotonic()-started,'output':str(out)}),flush=True)


if __name__=='__main__': main()
