#!/usr/bin/env python3
"""Stage only a measured, independently checked cache into the study APK."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
from verify_phone import native_sources

source=Path(sys.argv[1]).resolve(); target=Path(sys.argv[2]).resolve()
m=json.loads((source/'manifest.json').read_text()); r=json.loads((source/'report.json').read_text())
v=json.loads((source/'reader-verification.json').read_text())
n=json.loads((source/'native-verification.json').read_text())
assert m['phoneStudy']==1 and m['approximationPassed'] and r['approximationPassed'], 'Failed cache cannot enter the phone APK'
assert m['accuracy']['failed']==0 and m['accuracy']['passed']==m['accuracy']['total'], 'Incomplete accuracy gate'
assert v['result']=='passed', 'Independent reader verification has not passed'
c=m['container']; assert c['pcmBytes']<=32*1024**2 and c['compressedBytes']<=12*1024**2
assert Path(c['file']).name==c['file'] and c['file'].endswith('.cpcb')
payload=(source/c['file']).read_bytes()
assert len(payload)==c['compressedBytes'] and hashlib.sha256(payload).hexdigest()==c['compressedSha256']
assert n['result']=='passed' and n['cases']==m['accuracy']['total'] and n['output48kCases']==24, 'Native reader verification has not passed'
assert n['pcmSha256']==m['container']['pcmSha256'], 'Native reader receipt belongs to another cache'
assert n['nativeSources']==native_sources(), 'Native sources changed after verification'
# Bind receipt to this cache, rather than accepting a green result from another run.
assert v['cacheIdentity']==m['cacheIdentity'], 'Reader receipt belongs to a different cache'
target.mkdir(parents=True,exist_ok=True)
for old in target.iterdir():
    if old.is_file(): old.unlink()
shutil.copyfile(source/'manifest.json',target/'manifest.json')
shutil.copyfile(source/c['file'],target/c['file'])
print(f"Staged {m['accuracy']['total']} passing cases, {c['pcmBytes']} PCM bytes")
