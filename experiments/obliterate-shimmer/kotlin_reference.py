#!/usr/bin/env python3
"""Same-boundary reference export and independent Kotlin-result comparison."""
import argparse,hashlib,importlib.util,json,subprocess,sys
from pathlib import Path
import numpy as np
import prototype as shim
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[1];PORT=ROOT/'testkit/obliterate-kotlin-audition';OUT=ROOT/'testkit/obliterate-shimmer-kotlin';INPUTS=PORT/'reference-inputs.json'
spec=importlib.util.spec_from_file_location('obliterate_core_reference',ROOT/'experiments/obliterate-core/prototype.py');core=importlib.util.module_from_spec(spec);sys.modules[spec.name]=core;spec.loader.exec_module(core)

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def dump(p,d):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(d,indent=2,allow_nan=False)+'\n')
def provenance():
    names=list(HERE.glob('*.py'))+[HERE/'ShimmerPortRenderer.kt',HERE/'shimmer-port.init.gradle']+list((ROOT/'synth/src/main/kotlin/com/snipsnap/synth').glob('Obliterate*.kt'))
    return {'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
      'workingTreeDirty':bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip()),
      'sourceHashes':{str(p.relative_to(ROOT)):sha(p) for p in sorted(names) if p.is_file()}}
def payload(p,a):
    p.parent.mkdir(parents=True,exist_ok=True);a.astype('<f4').tofile(p);return sha(p)

def export():
    ref=json.loads(INPUTS.read_text());input_root=INPUTS.parent;sources=[];cases=[]
    for src in ref['sources']:
        x=np.fromfile(input_root/src['inputFile'],dtype='<f4').reshape(src['inputFrames'],src['inputChannels'])
        assert sha(input_root/src['inputFile'])==src['inputSha256']
        inpath=f'inputs/{src["id"]}.f32';payload(OUT/inpath,x)
        sources.append({**src,'inputFile':inpath})
        for variant,mix,pitched in [('off',0.,True),('room',.3,False),('shimmer15',.15,True),('shimmer30',.3,True)]:
            ident=src['id']+'-'+variant;amount=.7 if src['id']=='snare' else .35
            pre=shim.render(x,mix,pitched)
            boundary=pre.audio.astype(np.float32)
            new=core.render_core(boundary,core.Settings(amount=amount)).audio.astype(np.float32)
            historical=core.render_core(pre.audio,core.Settings(amount=amount)).audio.astype(np.float32)
            delta=new.astype(np.float64)-historical.astype(np.float64)
            outputs={'pre':boundary,'wet':pre.wet.astype(np.float32),'full':new}
            paths={kind:f'python/{ident}-{kind}.f32' for kind in outputs}
            hashes={kind:payload(OUT/p,outputs[kind]) for kind,p in paths.items()}
            cases.append({'id':ident,'sourceId':src['id'],'variant':variant,'mix':mix,'pitched':pitched,'amount':amount,
              'bpm':120.,'division':16,'seed':20261023,'files':paths,'hashes':hashes,'preFrames':len(boundary),
              'preChannels':boundary.shape[1],'fullFrames':len(new),'historicalBoundaryMax':float(np.max(np.abs(delta))),
              'historicalBoundaryRms':float(np.sqrt(np.mean(delta*delta))),'shimmerDiagnostics':pre.diagnostics})
    dump(OUT/'reference.json',{'schema':1,**provenance(),'sources':sources,'cases':cases,
      'policy':'Actual Float32 Snip input; Float32 public Snip between pre-stage and core. Historical edition 005 had Float64 at that intermediate boundary.',
      'thresholds':{'maximumAbsolute':2e-6,'rmsAbsolute':2e-7}})
    print('Exported',len(cases),'three-output reference cases')

def fixtures():
    path=ROOT/'synth/src/test/resources/obliterate-shimmer';n=np.arange(6000)
    x=np.column_stack([.18*np.sin(2*np.pi*220*n/shim.RATE)*np.exp(-n/1800),.13*np.sin(2*np.pi*331*n/shim.RATE)*np.exp(-n/1200)]).astype('<f4');x[0]=[.3,-.2]
    input_hash=payload(path/'input.f32',x);rows=[]
    for name,mix,pitched in [('room',.3,False),('shimmer15',.15,True),('shimmer30',.3,True)]:
        r=shim.render(x,mix,pitched);f=name+'.f32';h=payload(path/f,r.audio)
        rows.append({'file':f,'sha256':h,'mix':mix,'pitched':pitched,'frames':len(r.audio)})
    dump(path/'manifest.json',{'referenceCommit':'0c00ad0c743e89d99c820d4ab5da587c9410bddc','referenceProcessorSha256':sha(HERE/'prototype.py'),'inputSha256':input_hash,'inputFrames':len(x),'rows':rows})
    print('Wrote independent Python fixtures')

def compare():
    ref=json.loads((OUT/'reference.json').read_text());got=json.loads((OUT/'kotlin-results.json').read_text());rows=[]
    for name,h in ref['sourceHashes'].items():assert sha(ROOT/name)==h,name
    for c,k in zip(ref['cases'],got['cases'],strict=True):
        assert c['id']==k['id'];metrics={}
        for kind in ['pre','wet','full']:
            a=np.fromfile(OUT/c['files'][kind],dtype='<f4').astype(np.float64);b=np.fromfile(OUT/k['files'][kind],dtype='<f4').astype(np.float64)
            assert a.shape==b.shape
            d=a-b;mx=float(np.max(np.abs(d)));rms=float(np.sqrt(np.mean(d*d)))
            metrics[kind]={'maximumAbsolute':mx,'rmsAbsolute':rms,'pass':mx<=2e-6 and rms<=2e-7}
        rows.append({'id':c['id'],'metrics':metrics,'pass':all(x['pass'] for x in metrics.values()),'historicalBoundaryMax':c['historicalBoundaryMax'],'historicalBoundaryRms':c['historicalBoundaryRms']})
    report={'schema':1,**provenance(),'allPass':all(x['pass'] for x in rows),'cases':rows,'thresholds':ref['thresholds'],
      'referenceSha256':sha(OUT/'reference.json'),'kotlinResultsSha256':sha(OUT/'kotlin-results.json'),
      'maximumAbsolute':max(v['maximumAbsolute'] for r in rows for v in r['metrics'].values()),
      'maximumRms':max(v['rmsAbsolute'] for r in rows for v in r['metrics'].values()),
      'historicalBoundaryMaximum':max(c['historicalBoundaryMax'] for c in ref['cases'])}
    dump(OUT/'parity-report.json',report);print(json.dumps({k:v for k,v in report.items() if k not in ['sourceHashes','cases']}));return report['allPass']
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('command',choices=['export','fixtures','compare']);p.add_argument('--inputs',type=Path,default=INPUTS);p.add_argument('--output',type=Path,default=OUT);a=p.parse_args();INPUTS=a.inputs.resolve();OUT=a.output.resolve()
    if a.command=='export':export()
    elif a.command=='fixtures':fixtures()
    else:sys.exit(0 if compare() else 1)
