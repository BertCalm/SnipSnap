#!/usr/bin/env python3
"""Publishable three-voice assets, exact PCM checks and a restorable source backup."""
import argparse, hashlib, json, os, shutil, subprocess, tempfile, wave, zipfile
from pathlib import Path
import numpy as np
from scipy.signal import lfilter

REPO=Path(__file__).resolve().parents[1]
RATE=44100
def sha(data): return hashlib.sha256(data).hexdigest()
def git(*args): return subprocess.check_output(['git',*args],cwd=REPO)
def pcm(path): return subprocess.check_output(['ffmpeg','-v','error','-threads','1','-i',str(path),'-f','s24le','pipe:1'])
def samples(raw):
    b=np.frombuffer(raw,dtype=np.uint8).reshape(-1,3)
    v=b[:,0].astype(np.int32)|(b[:,1].astype(np.int32)<<8)|(b[:,2].astype(np.int32)<<16)
    return np.where(v>=8388608,v-16777216,v).astype(float)/8388608
def encode(source,dest):
    dest.parent.mkdir(parents=True,exist_ok=True)
    subprocess.run(['ffmpeg','-v','error','-threads','1','-y','-i',str(source),'-c:a','flac','-compression_level','12',str(dest)],check=True)
    with wave.open(str(source)) as w:
        assert w.getparams()[:3]==(1,3,RATE)
        raw=w.readframes(w.getnframes())
        assert pcm(dest)==raw,'FLAC changed the native PCM'
    return raw
def preview_gain(x):
    a=1-np.exp(-2*np.pi*120/RATE)
    y=x-.7*lfilter([a],[1,-(1-a)],x)
    level=max(float(np.sqrt(np.mean(y[i:min(i+8820,len(y))]**2))) for i in range(0,max(1,len(y)-8820)+1,2205))
    peak=float(np.max(np.abs(x)))
    return min(.15/level,.9/peak) if level>1e-8 and peak>1e-8 else 1.
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--site',type=Path,required=True)
    p.add_argument('--audition',type=Path,default=REPO/'testkit/cairn-tumble')
    p.add_argument('--base-revision',default='2f909395feee9962e15fcf60ea0feee0c16f1615')
    p.add_argument('--source-only',action='store_true')
    args=p.parse_args();site=args.site.resolve();source=args.audition.resolve();dest=site/'dist/tumble'
    retained=[site/'dist'/name for name in ['reconstruction','hold','audio','downloads']]
    before={str(f.relative_to(site)):sha(f.read_bytes()) for directory in retained for f in directory.rglob('*') if f.is_file()}
    dest.mkdir(parents=True,exist_ok=True)
    m=json.loads((dest/'manifest.json' if args.source_only else source/'manifest.json').read_text())
    for name,digest in m['sourceSha256'].items():
        assert sha((REPO/'synth/src/main/kotlin/com/snipsnap/synth'/name).read_bytes())==digest,'DSP changed: regenerate the audio'
    for name in ['index.html','styles.css','app.js']:
        shutil.copyfile(REPO/'synth/src/test/resources/cairn-tumble-audition'/name,dest/name)
    for name in ['recipes','events','preceding-events','traces']:
        shutil.copytree(source/name,dest/name,dirs_exist_ok=True)
    verified=[]
    if not args.source_only:
        for clip in m['clips']+m['examples']:
            wav=source/clip['audio'];assert sha(wav.read_bytes())==clip['sha256']
            target=dest/'audio'/f"{clip['id']}.flac";raw=encode(wav,target);x=samples(raw)
            assert np.isfinite(x).all() and 0<float(max(abs(x)))<.991 and abs(float(x.mean()))<.002
            assert abs(len(x)/RATE-clip['seconds'])<1/RATE
            clip.update(nativeWavSha256=clip.pop('sha256'),audio=str(target.relative_to(dest)),
                        flacSha256=sha(target.read_bytes()),previewGain=preview_gain(x))
            row={'id':clip['id'],'frames':len(x),'peak':float(max(abs(x))),'dc':float(x.mean())}
            if clip.get('held'):
                continuation=dest/f"continuation/{clip['id']}.flac"
                full=encode(source/f"continuation/{clip['id']}.wav",continuation)
                marker=clip['loopStartFrame'];assert raw==full[marker*3:],'Export is not the real continuation suffix'
                y=samples(full);a=y[marker-256:marker];b=y[-256:];difference=float((a-b)@(a-b));level=float(a@a)
                seam=difference/level if level else 0.0 if not difference else float('inf')
                assert seam<1e-3 and clip['fullPrerollReferenceRelativeRms']<1e-5
                assert abs(clip['budgetResidual'])<1e-8 and clip['cycleResidual']<1e-8
                clip.update(continuation=str(continuation.relative_to(dest)),continuationFlacSha256=sha(continuation.read_bytes()))
                row['nativePcmSeamError']=seam
            verified.append(row)
    subprocess.run(['git','add','-N','.'],cwd=REPO,check=True)
    patch=git('diff','--binary',args.base_revision);assert patch.strip()
    with tempfile.TemporaryDirectory(prefix='cairn-tumble-restore-') as temporary:
        env=os.environ.copy();env['GIT_INDEX_FILE']=str(Path(temporary)/'index')
        subprocess.run(['git','read-tree',args.base_revision],cwd=REPO,env=env,check=True)
        subprocess.run(['git','apply','--cached','--binary','-'],input=patch,cwd=REPO,env=env,check=True)
        tree=subprocess.check_output(['git','write-tree'],cwd=REPO,env=env).decode().strip()
    m.update(sourceArchiveBase=args.base_revision,sourceArchiveTree=tree,
             renderSourceNote='sourceRevision names the local render checkout. sourceSha256 pins the actual DSP; the complete patch restores sourceArchiveTree from the named GitHub base.',
             previewPolicy='Native mono 44.1 kHz, 24-bit PCM preserved losslessly. Preview gain only, target .15 with .9 peak ceiling. HOLD plays eight exported cycles; independent preceding audio is the seam evidence.')
    (dest/'manifest.json').write_text(json.dumps(m,indent=2)+'\n')
    archive=dest/'source/cairn-three-voices-source.zip';archive.parent.mkdir(exist_ok=True)
    files={'cairn-three-voices.patch':patch,'README.txt':(
        'CAIRN: KNOCK, ANSWER and TUMBLE\n\n'
        'Repository: https://github.com/BertCalm/SnipSnap\n'
        f'Base revision: {args.base_revision}\nRestored source tree: {tree}\n'
        'Check out the named base, then: git apply cairn-three-voices.patch\n'
        'JDK 21: ./gradlew --no-daemon :synth:generateCairnTumbleAudition\n'
        'Full verification: ./gradlew --no-daemon test\n'
        'Read docs/CAIRN_TUMBLE.md for the mechanism, measured evidence and limits.\n'
        'All new audio comes from Kotlin. Existing recordings and source archives are preserved.\n').encode()}
    for name in ['Cairn.kt','CairnPatch.kt','CairnTumble.kt','Modes.kt']:
        path='synth/src/main/kotlin/com/snipsnap/synth/'+name;files[path]=(REPO/path).read_bytes()
    for path in ['docs/CAIRN.md','docs/CAIRN_TUMBLE.md',
                 'synth/src/test/kotlin/com/snipsnap/synth/CairnTumbleTest.kt',
                 'synth/src/test/kotlin/com/snipsnap/synth/CairnTumbleAuditionGenerator.kt']:
        files[path]=(REPO/path).read_bytes()
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for name,content in sorted(files.items()):
            item=zipfile.ZipInfo(name,(2026,10,31,0,0,0));item.compress_type=zipfile.ZIP_DEFLATED;item.external_attr=0o644<<16
            z.writestr(item,content)
    index=site/'src/index.html';text=index.read_text()
    if 'href="tumble/"' not in text:
        text=text.replace('<a href="hold/">New HOLD audition ↗</a>', '<a href="tumble/">Three-voice audition ↗</a><a href="hold/">Previous HOLD audition</a>')
        text=text.replace('<div class="hero-actions">','<div class="hero-actions"><a class="button" href="tumble/">Hear KNOCK / ANSWER / TUMBLE ↗</a>')
        index.write_text(text)
    shutil.copyfile(index,site/'dist/index.html')
    assert all(sha((site/name).read_bytes())==digest for name,digest in before.items()),'An earlier audition file changed'
    proof={'preservedFiles':len(before),'changedPreservedFiles':0,'nativePcmChanged':0,
           'sourceBase':args.base_revision,'restoredSourceTree':tree,
           'sourcePatchSha256':sha(patch),'sourceZipSha256':sha(archive.read_bytes())}
    if verified:proof.update(clips=len(verified),heldCycles=sum(c['held'] for c in m['clips']),allLoopPcmExactSuffix=True,
                             maxNativePcmSeamError=max(r.get('nativePcmSeamError',0) for r in verified),results=verified)
    elif (dest/'verification.json').exists():
        old=json.loads((dest/'verification.json').read_text());old.update(proof);proof=old
    (dest/'verification.json').write_text(json.dumps(proof,indent=2)+'\n')
    print(json.dumps({k:v for k,v in proof.items() if k!='results'}))
if __name__=='__main__':main()
