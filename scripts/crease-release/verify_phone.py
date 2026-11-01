#!/usr/bin/env python3
"""Bind the actual native executable's parity result to cache, fixtures and sources."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''): h.update(chunk)
    return h.hexdigest()
def native_sources():
    paths=sorted((ROOT/'app/src/main/cpp/crease').glob('*'))+[ROOT/'app/src/main/cpp/OboeOutput.h']
    return {str(p.relative_to(ROOT)):sha(p) for p in paths if p.is_file()}

def main():
    out=Path(sys.argv[1]).resolve();raw=out/'raw'
    m=json.loads((out/'manifest.json').read_text());fixture=json.loads((raw/'reader-fixtures.json').read_text())
    assert sha(raw/'native-pcm.i16')==m['container']['pcmSha256'],'Native PCM fixture differs from delivered cache'
    assert sha(raw/'reader-fixtures.f32')==fixture['sha256'],'Python fixture changed'
    layout=[m[k] for k in ['heldFrames','loopStart','loopFrames','rootCycles','leadFrames','tailFrames','crossfadeFrames','releaseFrames']]+[len(m['tailCuts'])]+m['tailCuts']
    assert list(map(int,(raw/'native-layout.txt').read_text().split()))==layout
    assert list(map(int,(raw/'native-cases.txt').read_text().split()))==[len(fixture['cuts']),fixture['frames']]+fixture['cuts']
    before=native_sources()
    subprocess.run([str(ROOT/'build/native-tests/crease_study_tests'),str(out)],check=True)
    assert before==native_sources(),'Native sources changed during validation'
    r=json.loads((out/'native-verification.json').read_text())
    assert r['cases']==m['accuracy']['total'] and r['output48kCases']==24
    r.update(pcmSha256=m['container']['pcmSha256'],nativeSources=before,
        executableSha256=sha(ROOT/'build/native-tests/crease_study_tests'),
        reference48kSha256=sha(raw/'native-48k-fixtures.bin'),pythonFixtureSha256=fixture['sha256'])
    (out/'native-verification.json').write_text(json.dumps(r,indent=2)+'\n')
if __name__=='__main__': main()
