"""Publish the owner-selected source through the full rig matrix; preserve earlier auditions."""
from pathlib import Path
import sys,subprocess,json,hashlib,shutil,re,zipfile
import numpy as np
from scipy.io import wavfile
repo=Path(__file__).resolve().parents[1]
src=Path(sys.argv[1])
site=Path(sys.argv[2])/'dist'
out=site/'accepted';out.mkdir(exist_ok=True)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
before={str(p.relative_to(site)):sha(p) for p in site.rglob('*') if p.is_file() and not p.is_relative_to(out) and p!=site/'index.html'}
if not (site/'r2.html').exists(): shutil.copy2(site/'index.html',site/'r2.html')
def samples(p):
    sr,x=wavfile.read(p);assert sr==44100 and x.dtype==np.int32 and x.ndim==1
    return x.astype(float)/2**31
proof={}
for rig in ['dry','warehouse','submerged','fracture']:
    old=samples(site/'hands-r2'/f'{rig}-revised.wav')
    new=samples(src/('dry.wav' if rig=='dry' else f'{rig}-main.wav'))
    assert old.shape==new.shape
    gain=float(np.dot(new,old)/np.dot(old,old))
    error=float(np.sqrt(np.mean((new-gain*old)**2)))
    assert error<2e-7,(rig,error)
    proof[rig]={'gainToAcceptedR2':gain,'pcmRmsResidual':error}
for lane in ['thud','sub','clap','open-hat','closed-hat','shaker','rim']:
    name=f'stem-{lane}-native.wav'
    assert (src/name).read_bytes()==(site/name).read_bytes(),name
(src/'acceptance-verification.json').write_text(json.dumps({'acceptedSource':'handRevision 2, owner verdict Much better, continue','mainComparisons':proof,'sevenOtherNativeStemsByteIdentical':True},indent=2))
for f in ['TRIAD_ACCEPTANCE.md','TRIAD_HANDS_R2.md']:shutil.copy2(repo/'docs'/f,out/f)
subprocess.run([sys.executable,str(repo/'scripts/verify_triad_audio.py'),str(src)],check=True)
subprocess.run([sys.executable,str(repo/'scripts/build_triad_page.py'),str(out),str(src)],check=True)
manifest=json.loads((out/'manifest.json').read_text())
archived={}
for p in sorted(out.glob('*.wav')):
    archived[p.name]={'sha256':sha(p),'archive':'triad-native.zip' if '-native' in p.stem else 'triad-audition.zip'}
    if '-native' not in p.stem:
        flac=p.with_suffix('.flac')
        subprocess.run(['ffmpeg','-v','error','-y','-i',str(p),'-c:a','flac',str(flac)],check=True)
        decoded=subprocess.check_output(['ffmpeg','-v','error','-i',str(flac),'-f','s32le','-acodec','pcm_s32le','-'])
        sr,pcm=wavfile.read(p)
        assert decoded==pcm.astype('<i4').tobytes(),p.name
        manifest['files'][flac.name]=sha(flac)
    manifest['files'].pop(p.name)
    p.unlink()
manifest['archivedWavs']=archived
manifest['transport']='FLAC decoded byte-identically to delivered PCM24 WAV; WAVs in archives'
(out/'manifest.json').write_text(json.dumps(manifest,indent=2))
html=(out/'index.html').read_text().replace('.wav','.flac')
html=html.replace('SnipSnap / listening room / study 01','SnipSnap / accepted hand drums / complete rig pack')
html=html.replace('First native Kotlin audition. These are listening candidates.','The revised conga and bongo you preferred now play through all nine rig variations.')
html=html.replace('TRIAD · SnipSnap · Study 01','TRIAD · SnipSnap · Accepted hand drums')
html=html.replace('Tell me which rig and amount you prefer, whether the dry hand drums have enough character, and whether the kick still has enough punch.','The hand-drum source is the exact revision you preferred. Compare the full Light/Main/Heavy range to choose the intensity for each rig.')
html=html.replace('First-build','Accepted-source')
html=html.replace('No listening verdict is claimed.','The owner preferred hand revision 2; this pack retains that source.')
html=html.replace('<section id="compare">','<p><a href="../r2.html">Hand-drum before/after</a> · <a href="../r1.html">Original audition</a> · <a href="TRIAD_ACCEPTANCE.md">Acceptance and reproduction notes</a></p><section id="compare">')
(out/'index.html').write_text(html)
(site/'index.html').write_text(html.replace('<head>','<head><base href="accepted/">',1))
for link in re.findall(r'(?:src|href)="([^"]+)"',html):
    if not link.startswith(('#','http')):assert (out/link).is_file(),link
for path,digest in before.items():assert sha(site/path)==digest,path
for p in out.glob('*.zip'):
    assert p.stat().st_size<25*1024**2
    with zipfile.ZipFile(p) as z: assert z.testzip() is None
assert all(sha(out/path)==digest for path,digest in manifest['files'].items())
print(json.dumps({'mainComparisons':proof,'preservedFiles':len(before),'losslessPlaybackFiles':len(list(out.glob('*.flac'))),'wavDownloads':len(archived)},indent=2))
