#!/usr/bin/env python3
"""Read-only independent PCM validation, without importing the shimmer DSP/exporter."""
from pathlib import Path
import hashlib,json,wave
import numpy as np
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'testkit/obliterate-shimmer-audition';SCALE=8388608

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):
    with wave.open(str(p),'rb') as w:
        assert w.getframerate()==44100 and w.getsampwidth()==3
        n,c=w.getnframes(),w.getnchannels();b=np.frombuffer(w.readframes(n),dtype=np.uint8).reshape(-1,3)
    q=b[:,0].astype(np.int32)+(b[:,1].astype(np.int32)<<8)+(b[:,2].astype(np.int32)<<16)
    return ((q^0x800000)-0x800000).reshape(n,c)

def main():
    m=json.loads((OUT/'manifest.json').read_text())
    for path,h in m['sourceHashes'].items():assert sha(ROOT/path)==h,path
    previous=json.loads((ROOT/'testkit/obliterate-kotlin-audition/manifest.json').read_text())
    old_clips=[c for s in previous['sources'] for k in ['clips','baselineClips'] for c in s[k]]+previous['isolation']['clips']+previous['isolation']['referenceClips']
    old_files={c[k]:c[k+'Sha256'] for c in old_clips for k in ['stereo','mono','rawStereo','rawMono']}
    for path,h in old_files.items():assert sha(ROOT/'testkit/obliterate-kotlin-audition'/path)==h,path
    count=0;byte_count=0;fold_error=0.;gain_error=0.
    for source in m['sources']:
        clips=source['clips']+(m['isolation']['clips'] if source['id']==m['isolation']['sourceId'] else [])
        gains={c['recipe']['sharedGain'] for c in clips};assert len(gains)==1
        for c in clips:
            arrays={}
            r=c['recipe'];first,second=r['hitStartsFrames'];assert first==0
            for k in ['stereo','mono','rawStereo','rawMono']:
                p=OUT/c[k];assert p.resolve().is_relative_to(OUT);assert sha(p)==c[k+'Sha256'],p
                a=read(p);arrays[k]=a;assert a.shape==(r['phraseFrames'],1 if 'mono' in k.lower() else 2)
                assert np.array_equal(a[:r['hitFrames']],a[second:second+r['hitFrames']]),p
                assert second-r['hitFrames']>=11025
                assert not np.any(a[r['hitFrames']:second]);assert not np.any(a[second+r['hitFrames']:])
                assert not np.any(a[r['hitFrames']-1]),p
                count+=1;byte_count+=p.stat().st_size
            for stereo,mono in [('stereo','mono'),('rawStereo','rawMono')]:
                error=np.max(np.abs(arrays[stereo].astype(np.float64).mean(axis=1)-arrays[mono][:,0]));assert error<=1.;fold_error=max(fold_error,float(error))
            for raw,matched in [('rawStereo','stereo'),('rawMono','mono')]:
                error=np.max(np.abs(arrays[raw].astype(np.float64)*r['sharedGain']-arrays[matched]));assert error<=.500001*(1+r['sharedGain']);gain_error=max(gain_error,float(error))
            if c['variant']=='off':
                p=ROOT/'testkit/obliterate-kotlin-audition/python-f32'/f'{source["id"]}-a{round(source["coreAmount"]*100):03d}-s3.f32'
                hit=np.fromfile(p,dtype='<f4').astype(np.float64).reshape(-1,2)
                expected=np.clip(np.rint(hit*SCALE),-SCALE,SCALE-1).astype(np.int32)
                assert np.array_equal(expected,arrays['rawStereo'][:len(hit)])
    assert count==76 and byte_count==m['wavBytes']
    result={'newWavsVerified':count,'newWavBytes':byte_count,'oldKotlinWavsUnchanged':len(old_files),
            'exactCoreOnlyReferences':4,'sourceWideCommonGainVerified':True,'fullRepeatedHitsAndTailsVerified':True,
            'maximumFoldErrorPcm24Lsb':fold_error,'maximumCommonGainErrorPcm24Lsb':gain_error,'manifestSha256':sha(OUT/'manifest.json')}
    print(json.dumps(result,indent=2))
if __name__=='__main__':main()
