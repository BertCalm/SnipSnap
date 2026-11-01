#!/usr/bin/env python3
"""Stage only a measured, independently checked cache into the study APK."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
from verify_phone import native_sources, require



def stage(source, target):
    source=Path(source).resolve(); target=Path(target).resolve()
    require(source!=target and source not in target.parents and target not in source.parents,
            'Study input and output must not overlap')
    m=json.loads((source/'manifest.json').read_text()); r=json.loads((source/'report.json').read_text())
    v=json.loads((source/'reader-verification.json').read_text())
    n=json.loads((source/'native-verification.json').read_text())
    require(m['phoneStudy']==1 and m['approximationPassed'] and r['approximationPassed'], 'Failed cache cannot enter the phone APK')
    require(m['accuracy']['failed']==0 and m['accuracy']['passed']==m['accuracy']['total'], 'Incomplete accuracy gate')
    require(v['result']=='passed', 'Independent reader verification has not passed')
    c=m['container']
    require(0<c['pcmBytes']<=32*1024**2 and 0<c['compressedBytes']<=12*1024**2, 'Study exceeds its byte limits')
    require(Path(c['file']).name==c['file'] and c['file'].endswith('.cpcb'), 'Invalid study payload name')
    payload=(source/c['file']).read_bytes()
    require(len(payload)==c['compressedBytes'] and hashlib.sha256(payload).hexdigest()==c['compressedSha256'], 'Study payload hash differs')
    require(n.get('receiptVersion')==2 and n.get('builtFromCurrentCheckout') is True, 'Native verification must build the current checkout')
    require(n['result']=='passed' and n['cases']==m['accuracy']['total'] and n['output48kCases']==24, 'Native reader verification has not passed')
    require(n['pcmSha256']==c['pcmSha256'], 'Native reader receipt belongs to another cache')
    require(n['nativeSources']==native_sources(), 'Native sources changed after verification')
    require(v['cacheIdentity']==m['cacheIdentity'], 'Reader receipt belongs to a different cache')
    # Only replace an owned study output. A mistaken destination must not erase
    # the expensive input captures or unrelated files.
    old_files=[]
    if target.exists() and any(target.iterdir()):
        previous=target/'manifest.json'
        require(previous.is_file(), 'Destination is not a study output')
        prior=json.loads(previous.read_text())
        require(prior.get('format')=='crease-playable-pcm-v1' and prior.get('phoneStudy')==1, 'Destination is not a study output')
        allowed={'manifest.json',prior['container']['file']}
        old_files=list(target.iterdir())
        require(all(p.is_file() and p.name in allowed for p in old_files), 'Destination contains unrelated files')
    target.mkdir(parents=True,exist_ok=True)
    for old in old_files:
        old.unlink()
    shutil.copyfile(source/'manifest.json',target/'manifest.json')
    shutil.copyfile(source/c['file'],target/c['file'])
    print(f"Staged {m['accuracy']['total']} passing cases, {c['pcmBytes']} PCM bytes")


if __name__=='__main__':
    stage(sys.argv[1],sys.argv[2])
