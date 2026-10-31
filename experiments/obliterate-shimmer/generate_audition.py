#!/usr/bin/env python3
"""Build the separate Python shimmer listening proof on immutable retained inputs."""
from __future__ import annotations
import hashlib,importlib.util,json,math,subprocess,sys
from pathlib import Path
import numpy as np
import scipy
import prototype as shimmer
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
OUT=ROOT/'testkit/obliterate-shimmer-audition'
PORT=ROOT/'testkit/obliterate-kotlin-audition'
CORE=ROOT/'experiments/obliterate-core'

def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);sys.modules[name]=m;spec.loader.exec_module(m);return m
helpers=load('obliterate_core_audition_helpers',CORE/'generate_audition.py')
core=helpers.load_core(CORE/'prototype.py')
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT,text=True).strip()
def dump(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2,allow_nan=False)+'\n')
def provenance():
    paths=list(HERE.glob('*.py'))+[HERE/'README.md',CORE/'prototype.py',CORE/'generate_audition.py']
    return {'sourceCommit':git('rev-parse','HEAD'),'sourceTree':git('rev-parse','HEAD^{tree}'),
            'workingTreeDirty':bool(git('status','--porcelain','--untracked-files=normal')),
            'sourceHashes':{str(p.relative_to(ROOT)):sha(p) for p in sorted(paths) if p.is_file()},
            'coreReferenceCommit':shimmer.CORE_COMMIT,'scope':'Python shimmer before unchanged approved Python core; Kotlin shimmer and Android integration absent'}

def generate():
    ref=json.loads((PORT/'reference-inputs.json').read_text())
    if sha(CORE/'prototype.py')!=ref['pythonProcessorSha256']:raise ValueError('approved Python core changed')
    records=[];diagnostics=[];isolation=None;total=0;byte_count=0
    for source in ref['sources']:
        path=PORT/source['inputFile'];assert sha(path)==source['inputSha256']
        x=np.fromfile(path,dtype='<f4').reshape(source['inputFrames'],source['inputChannels'])
        amount=.7 if source['id']=='snare' else .35
        variants=[('off','Core only',0.,False),('room','Room 30%',.30,False),('shimmer15','Shimmer 15%',.15,True),('shimmer30','Shimmer 30%',.30,True)]
        rendered=[];r30=None
        for vid,label,mix,pitched in variants:
            pre=shimmer.render(x,mix,pitched)
            result=core.render_core(pre.audio,core.Settings(amount=amount))
            hit=result.audio.astype(np.float32).astype(np.float64)
            if vid=='off':
                # The comparator must be the exact old same-input Python Float32 payload.
                old=PORT/f'python-f32/{source["id"]}-a{round(amount*100):03d}-s3.f32'
                assert old.is_file(),old
                assert np.array_equal(hit.astype('<f4').view(np.uint32),np.fromfile(old,dtype='<f4').reshape(hit.shape).view(np.uint32))
            if vid=='shimmer30':r30=pre
            diag={'sourceId':source['id'],'variant':vid,'shimmer':pre.diagnostics,'core':result.diagnostics,
                  'rawHitFrames':len(hit),'hitFloat32Sha256':hashlib.sha256(hit.astype('<f4').tobytes()).hexdigest()}
            diagnostics.append(diag)
            rendered.append({'variant':vid,'label':label,'mix':mix,'pitched':pitched,'hit':hit,'diagnostics':diag})
        maximum=max(len(r['hit']) for r in rendered)
        spacing=math.ceil((maximum/shimmer.RATE+.25)*4)/4
        starts=[0,round(spacing*shimmer.RATE)]
        frames=starts[-1]+maximum+round(.2*shimmer.RATE)
        if source['id']=='pluck':
            rendered+= [
                {'variant':'source','label':'Source alone','mix':0.,'pitched':False,'hit':helpers.stereo(x.astype(np.float64)),'diagnostics':None},
                {'variant':'pre','label':'Shimmer 30% before core','mix':.3,'pitched':True,'hit':r30.audio,'diagnostics':None},
                {'variant':'wet','label':'Octave room return alone','mix':.3,'pitched':True,'hit':r30.wet,'diagnostics':None}]
        phrases={r['variant']:helpers.phrase_audio(r['hit'],frames,starts) for r in rendered}
        gain,_=helpers.match_gain(phrases['off'])
        peak=max(float(np.max(np.abs(a))) for a in phrases.values())
        gain=min(gain,.95/max(peak,1e-30))
        clips=[]
        for row in rendered:
            phrase=phrases[row['variant']];matched=phrase*gain
            name=f'{source["id"]}-{row["variant"]}'
            arrays={'stereo':matched,'mono':helpers.mono_fold(matched),'rawStereo':phrase,'rawMono':helpers.mono_fold(phrase)}
            paths={k:f'audio/{name}-{k}.wav' for k in arrays}
            for k,a in arrays.items():
                helpers.write_pcm24(OUT/paths[k],a);total+=1;byte_count+=(OUT/paths[k]).stat().st_size
            metrics=helpers.numeric_metrics(matched);raw=helpers.numeric_metrics(phrase)
            clips.append({'id':name,'variant':row['variant'],'label':row['label'],'mix':row['mix'],'pitched':row['pitched'],
                'amount':row['mix'],'coreAmount':amount,'implementation':'Python shimmer + approved Python core',**paths,
                **{k+'Sha256':sha(OUT/p) for k,p in paths.items()},'metrics':metrics,'rawMetrics':raw,
                'peakDbfs':metrics['peakDbfs'],'foldLossDb':metrics['foldLossDb'],'correlation':metrics['correlation'],
                'durationSeconds':frames/shimmer.RATE,'sourceEndSeconds':len(x)/shimmer.RATE,
                'recipe':{'inputSha256':source['inputSha256'],'coreAmount':amount,'bpm':120.,'division':16,'seed':20261023,
                    'hitFrames':len(row['hit']),'phraseFrames':frames,'hitStartsFrames':starts,'sharedGain':gain,
                    'gainPolicy':'one source-wide gain shared by all variants, isolated returns and unchanged mono folds; not per-variant loudness matching',
                    'shimmerMix':row['mix'],'pitched':row['pitched'],'diagnostics':row['diagnostics']}})
        records.append({'id':source['id'],'name':source['name'],'description':f'Core held at {round(amount*100)}%. Change only the room / shimmer pre-stage.',
            'coreAmount':amount,'sourceEndSeconds':len(x)/shimmer.RATE,'inputFrames':len(x),'inputChannels':source['inputChannels'],
            'inputFile':source['inputFile'],'inputSha256':source['inputSha256'],'clips':clips[:4]})
        if source['id']=='pluck':isolation={'sourceId':'pluck','clips':clips[4:],'includesFull':True}
    manifest={'schema':1,'edition':'005','title':'OBLITERATE shimmer pre-stage',**provenance(),
        'description':'Separate Python shimmer audition. No new owner verdict is inferred from earlier keep reports.',
        'variants':[{'id':a,'label':b,'mix':c,'pitched':d} for a,b,c,d in variants],
        'settings':{'bpm':120.,'division':16,'seed':20261023},'sources':records,'isolation':isolation,
        'audio':{'encoding':'PCM24','sampleRate':shimmer.RATE,'twoCompleteHits':True,'minimumGapSeconds':.25},
        'levelPolicy':'Matched mode uses one common gain derived from the core-only reference per source, guarded for the whole source set. Raw mode preserves processor gain.',
        'runtime':{'python':sys.version.split()[0],'numpy':np.__version__,'scipy':scipy.__version__,'meaning':'Python host prototype, not Kotlin or phone timing'},
        'wavFiles':total,'wavBytes':byte_count,'inputReferenceManifestSha256':sha(PORT/'reference-inputs.json'),
        'listeningStatus':'Shimmer listening pending; earlier approvals apply only to the destruction core.'}
    dump(OUT/'manifest.json',manifest);dump(OUT/'render-diagnostics.json',{'provenance':provenance(),'cases':diagnostics})
    print(json.dumps({'clips':total//4,'wavs':total,'bytes':byte_count,'sourceCommit':manifest['sourceCommit'],'workingTreeDirty':manifest['workingTreeDirty']}))
if __name__=='__main__':generate()
