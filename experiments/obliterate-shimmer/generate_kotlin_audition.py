#!/usr/bin/env python3
"""Single-hit raw Python/Kotlin full-chain comparison; original audio stays intact."""
from pathlib import Path
import json,hashlib,zipfile
import numpy as np
import kotlin_reference as ref
helpers=ref.core  # DSP is never re-run here; only the actual stored renderer outputs are read.
spec=ref.importlib.util.spec_from_file_location('obliterate_audio_export',ref.ROOT/'experiments/obliterate-core/generate_audition.py');audio=ref.importlib.util.module_from_spec(spec);spec.loader.exec_module(audio)
OUT=ref.OUT

def generate():
    report=json.loads((OUT/'parity-report.json').read_text());assert report['allPass']
    reference=json.loads((OUT/'reference.json').read_text());kotlin=json.loads((OUT/'kotlin-results.json').read_text())
    kr={c['id']:c for c in kotlin['cases']};sources=[];unique={}
    for s in reference['sources']:
        clips=[]
        for variant in ['shimmer15','shimmer30']:
            c=next(c for c in reference['cases'] if c['id']==s['id']+'-'+variant)
            for implementation in ['python','kotlin']:
                record=c if implementation=='python' else kr[c['id']]
                data=np.fromfile(OUT/record['files']['full'],dtype='<f4').astype(np.float64).reshape(-1,2)
                phrase=np.pad(data,((0,8820),(0,0)));suffix='15' if variant=='shimmer15' else '30';vid=implementation+suffix
                paths={'stereo':f'audio/{s["id"]}-{vid}-stereo.wav','mono':f'audio/{s["id"]}-{vid}-mono.wav'}
                for key,array in [('stereo',phrase),('mono',audio.mono_fold(phrase))]:
                    audio.write_pcm24(OUT/paths[key],array);unique[paths[key]]=ref.sha(OUT/paths[key])
                metrics=audio.numeric_metrics(phrase)
                clips.append({'id':s['id']+'-'+vid,'variant':vid,'implementation':implementation,'label':f'{implementation.capitalize()} · shimmer {suffix}%',
                    'mix':c['mix'],'coreAmount':c['amount'],**paths,'rawStereo':paths['stereo'],'rawMono':paths['mono'],
                    'stereoSha256':unique[paths['stereo']],'rawStereoSha256':unique[paths['stereo']],
                    'monoSha256':unique[paths['mono']],'rawMonoSha256':unique[paths['mono']],
                    'metrics':metrics,'rawMetrics':metrics,'durationSeconds':len(phrase)/44100,
                    'recipe':{'inputSha256':s['inputSha256'],'sourceCase':c['id'],'outputFloat32File':record['files']['full'],
                        'outputFloat32Sha256':ref.sha(OUT/record['files']['full']),
                        'hitFrames':len(data),'hitStartsFrames':[0],'phraseFrames':len(phrase),'coreAmount':c['amount'],
                        'shimmerMix':c['mix'],'bpm':120.,'division':16,'seed':20261023,'playbackGain':1.,
                        'policy':'Raw processor output, one complete hit and finite tails, then 200 ms silence. Mono is an unchanged average.'}})
        sources.append({'id':s['id'],'name':s['name'],'coreAmount':c['amount'],'sourceEndSeconds':s['inputFrames']/44100,
            'description':f'Core stays at {round(c["amount"]*100)}%. Raw complete-chain output, one full hit.', 'clips':clips})
    ref.dump(OUT/'sources.json',{'schema':1,'sampleRate':44100,'policy':'Retained normalized Float32 little-endian inputs; hashes are the identity, not a new source-engine render.','sources':reference['sources']})
    archive=OUT/'snipsnap-obliterate-inputs.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
        for name in ['sources.json',*(source['inputFile'] for source in reference['sources'])]:
            info=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o644<<16
            z.writestr(info,(OUT/name).read_bytes())
    m={'schema':1,'edition':'006','title':'OBLITERATE complete Kotlin chain',**ref.provenance(),'sources':sources,
       'inputArchive':archive.name,'inputArchiveSha256':ref.sha(archive),'parityReport':'parity-report.json','boundaryPolicy':reference['policy'],'wavFiles':len(unique),
       'wavBytes':sum((OUT/p).stat().st_size for p in unique),'audio':{'sampleRate':44100,'bitDepth':24,'hitsPerClip':1},
       'listeningStatus':'General positive feedback on Python shimmer recorded; no preferred amount or Kotlin listening verdict supplied.'}
    ref.dump(OUT/'manifest.json',m);print(json.dumps({'wavs':m['wavFiles'],'bytes':m['wavBytes']}))
if __name__=='__main__':generate()
