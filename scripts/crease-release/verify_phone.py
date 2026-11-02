#!/usr/bin/env python3
"""Bind the actual native executable's parity result to cache, fixtures and sources."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
def require(condition,message):
    if not condition: raise ValueError(message)
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
    require(sha(raw/'native-pcm.i16')==m['container']['pcmSha256'],'Native PCM fixture differs from delivered cache')
    require(sha(raw/'reader-fixtures.f32')==fixture['sha256'],'Python fixture changed')
    layout=[m[k] for k in ['heldFrames','loopStart','loopFrames','rootCycles','leadFrames','tailFrames','crossfadeFrames','releaseFrames']]+[len(m['tailCuts'])]+m['tailCuts']
    require(list(map(int,(raw/'native-layout.txt').read_text().split()))==layout,'Native layout fixture differs')
    require(list(map(int,(raw/'native-cases.txt').read_text().split()))==[len(fixture['cuts']),fixture['frames']]+fixture['cuts'],'Native case fixture differs')
    # Build from this checkout before binding source hashes. An old executable
    # must not certify new reader code merely because it still passes fixtures.
    cache=ROOT/'build/native-tests/CMakeCache.txt'
    entries={line.split('=',1)[0]:line.split('=',1)[1] for line in cache.read_text().splitlines() if '=' in line and not line.startswith(('#','//'))}
    require(Path(entries['CMAKE_HOME_DIRECTORY:INTERNAL']).resolve()==ROOT/'app/src/main/cpp/test','Configure the native harness for this checkout')
    before=native_sources()
    subprocess.run([entries['CMAKE_COMMAND:INTERNAL'],'--build',str(ROOT/'build/native-tests'),'--target','crease_study_tests'],check=True)
    require(before==native_sources(),'Native sources changed during build')
    subprocess.run([str(ROOT/'build/native-tests/crease_study_tests'),str(out)],check=True)
    require(before==native_sources(),'Native sources changed during validation')
    r=json.loads((out/'native-verification.json').read_text())
    require(r['cases']==m['accuracy']['total'] and r['output48kCases']==24,'Native coverage differs from the study')
    r.update(receiptVersion=2,builtFromCurrentCheckout=True,pcmSha256=m['container']['pcmSha256'],nativeSources=before,
        executableSha256=sha(ROOT/'build/native-tests/crease_study_tests'),
        reference48kSha256=sha(raw/'native-48k-fixtures.bin'),pythonFixtureSha256=fixture['sha256'])
    (out/'native-verification.json').write_text(json.dumps(r,indent=2)+'\n')
if __name__=='__main__': main()
