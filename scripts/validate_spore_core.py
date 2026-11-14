"""Check the complete core audition and exported packages, without claiming device playback."""
import argparse
import hashlib
import json
import wave
import zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('root',type=Path);a=p.parse_args();root=a.root
m=json.loads((root/'manifest.json').read_text());assert len(m['clips'])==13
for relative,digest in m['audioHashes'].items():
    f=root/relative;assert hashlib.sha256(f.read_bytes()).hexdigest()==digest
    with wave.open(str(f)) as w:
        assert (w.getframerate(),w.getnchannels(),w.getsampwidth())==(44100,1,3)
        assert w.getnframes()>1000
for c in m['clips']:
    assert (root/c['raw']).is_file() and (root/c['matched']).is_file()
with zipfile.ZipFile(root/'spore-approved-snare.zip') as z:
    assert z.testzip() is None
    native=json.loads(z.read('Native/kit.json'));assert len(native['pads'])==1
    sfz=[n for n in z.namelist() if n.endswith('.sfz')];assert len(sfz)==1
    assert z.read(sfz[0]).decode().count('<region>')==9
    assert any(n.startswith('MPC3/') for n in z.namelist())
    assert not any('reference' in n for n in z.namelist())
with zipfile.ZipFile(root/'kick-study-wavs.zip') as z:
    assert z.testzip() is None
    wavs=[n for n in z.namelist() if n.endswith('.wav')];assert len(wavs)==9
    assert len({hashlib.sha256(z.read(n)).hexdigest() for n in wavs})==9
with zipfile.ZipFile(root/'spore-core-offline.zip') as z:
    assert z.testzip() is None
    html=z.read('index.html').decode();assert "fetch('manifest.json')" not in html
    assert 'Promise.resolve({' in html
parity=json.loads((root/'snare-parity.json').read_text());assert len(parity['cases'])==17
assert parity['maxAbsoluteError']<1e-7
r=json.loads((root/'snare-delivery.json').read_text())
assert all(r[k] for k in ['nativeSaveReplayAndPlayback','sfzGridAndPcm','mpc3GridAndPcm'])
assert r['modelSha256']==m['modelSha256']
for n in ['design.md','snare-delivery.json','snare-parity.json','kick-report.json','kick-model.json']:assert (root/n).is_file()
assert all(f.stat().st_size<25*1024*1024 for f in root.rglob('*') if f.is_file())
print('PASS: 26 audition WAVs, native/SFZ/MPC3 snare archive, nine distinct kick WAVs, 17 parity cases, offline page and hashes.')
