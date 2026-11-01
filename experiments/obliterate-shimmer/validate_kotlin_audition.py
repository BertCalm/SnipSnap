#!/usr/bin/env python3
"""Independent read-only PCM, same-output, provenance and retention checks."""
from pathlib import Path
import hashlib,json,wave,zipfile
import numpy as np
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'testkit/obliterate-shimmer-kotlin'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def pcm(p):
    with wave.open(str(p),'rb') as w:
        assert w.getframerate()==44100 and w.getsampwidth()==3
        channels,frames=w.getnchannels(),w.getnframes();a=np.frombuffer(w.readframes(frames),dtype=np.uint8).reshape(-1,3)
    q=a[:,0].astype(np.int32)+(a[:,1].astype(np.int32)<<8)+(a[:,2].astype(np.int32)<<16)
    return ((q^0x800000)-0x800000).reshape(frames,channels)
def main():
    m=json.loads((OUT/'manifest.json').read_text());report=json.loads((OUT/'parity-report.json').read_text());assert report['allPass'] and len(report['cases'])==16
    for artifact in [m,report]:
        for p,h in artifact['sourceHashes'].items():assert sha(ROOT/p)==h,p
    reference=json.loads((OUT/'reference.json').read_text());actual=json.loads((OUT/'kotlin-results.json').read_text())
    assert sha(OUT/'reference.json')==report['referenceSha256']==actual['referenceSha256']
    assert sha(OUT/'kotlin-results.json')==report['kotlinResultsSha256']
    for artifact in [reference,actual]:
        for p,h in artifact['sourceHashes'].items():assert sha(ROOT/p)==h,p
    for expected,rendered in zip(reference['cases'],actual['cases'],strict=True):
        assert expected['id']==rendered['id']
        assert expected['preFrames']==rendered['preFrames'] and expected['preChannels']==rendered['preChannels']
        assert expected['fullFrames']==rendered['fullFrames'] and rendered['fullChannels']==2
        assert rendered['determinismVerified'] and rendered['inputUnchanged']
        if expected['mix']>0:
            assert abs(expected['shimmerDiagnostics']['guardGain']-rendered['guardGain'])<1e-12
            assert rendered['delayHistorySamples']*8<310000
    assert sha(OUT/m['inputArchive'])==m['inputArchiveSha256']
    with zipfile.ZipFile(OUT/m['inputArchive']) as z:
        assert z.testzip() is None
        sources=json.loads(z.read('sources.json'))['sources']
        assert set(z.namelist())=={'sources.json',*(s['inputFile'] for s in sources)}
        for source in sources:assert hashlib.sha256(z.read(source['inputFile'])).hexdigest()==source['inputSha256']
    files=set();fold_error=0.
    for source in m['sources']:
        for c in source['clips']:
            arrays={}
            for mode in ['stereo','mono']:
                p=OUT/c[mode];assert p.resolve().is_relative_to(OUT);assert sha(p)==c[mode+'Sha256']
                a=pcm(p);arrays[mode]=a;assert a.shape==(c['recipe']['phraseFrames'],2 if mode=='stereo' else 1)
                assert not np.any(a[c['recipe']['hitFrames']:]);assert not np.any(a[c['recipe']['hitFrames']-1]);files.add(c[mode])
            raw=OUT/c['recipe']['outputFloat32File'];assert sha(raw)==c['recipe']['outputFloat32Sha256']
            x=np.fromfile(raw,dtype='<f4').astype(np.float64).reshape(-1,2)
            expected=np.clip(np.rint(x*8388608),-8388608,8388607).astype(np.int32)
            assert np.array_equal(expected,arrays['stereo'][:len(x)])
            err=np.max(np.abs(arrays['stereo'].astype(np.float64).mean(axis=1)-arrays['mono'][:,0]));assert err<=1.;fold_error=max(fold_error,float(err))
    assert len(files)==32
    # The heard 005 artifacts remain unchanged; no source-code equality to current main is assumed.
    old=ROOT/'testkit/obliterate-shimmer-audition';h=json.loads((old/'manifest.json').read_text())
    oldfiles={c[k]:c[k+'Sha256'] for s in h['sources'] for c in s['clips'] for k in ['stereo','mono','rawStereo','rawMono']}
    oldfiles.update({c[k]:c[k+'Sha256'] for c in h['isolation']['clips'] for k in ['stereo','mono','rawStereo','rawMono']})
    for p,v in oldfiles.items():assert sha(old/p)==v
    result={'newWavsVerified':len(files),'rawFloat32OutputsRetained':16,'oldHeardWavsUnchanged':len(oldfiles),
        'maximumFoldErrorPcm24Lsb':fold_error,'parityCases':16,'pcmComparisons':48,'sourceCommit':m['sourceCommit']}
    print(json.dumps(result,indent=2))
if __name__=='__main__':main()
