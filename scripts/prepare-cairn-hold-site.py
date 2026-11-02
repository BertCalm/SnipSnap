#!/usr/bin/env python3
"""Package Cairn's actual JVM HOLD renders without changing previous audition audio."""
import argparse, hashlib, json, os, shutil, subprocess, tempfile, wave, zipfile
from pathlib import Path
import numpy as np
from scipy.signal import lfilter

REPO = Path(__file__).resolve().parents[1]
RATE = 44100

def sha(data): return hashlib.sha256(data).hexdigest()
def decode(path, fmt='s24le'):
    return subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-f',fmt,'pipe:1'])
def preview_gain(path):
    x = np.frombuffer(decode(path,'f32le'),dtype='<f4').astype(float)
    a = 1-np.exp(-2*np.pi*120/RATE)
    y = x-.7*lfilter([a],[1,-(1-a)],x)
    level = max(float(np.sqrt(np.mean(y[i:min(i+8820,len(y))]**2)))
                for i in range(0,max(1,len(y)-8820)+1,2205))
    peak = float(np.max(np.abs(x)))
    return min(.15/level,.9/peak) if level>1e-8 and peak>1e-8 else 1.
def encode(source,dest):
    dest.parent.mkdir(parents=True,exist_ok=True)
    subprocess.run(['ffmpeg','-v','error','-y','-i',str(source),'-c:a','flac','-compression_level','12',str(dest)],check=True)
    with wave.open(str(source)) as w:
        assert w.getparams()[:3]==(1,3,RATE)
        assert decode(dest)==w.readframes(w.getnframes()),'FLAC changed source PCM'
def git(*args): return subprocess.check_output(['git',*args],cwd=REPO)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--site',type=Path,required=True)
    p.add_argument('--audition',type=Path,default=REPO/'testkit/cairn-hold')
    p.add_argument('--source-only',action='store_true',help='Refresh source backup and provenance without re-encoding unchanged audio')
    p.add_argument('--base-revision',default='bf17f4561bda7418cc4ebf7bae8e78d7c8c45329')
    args=p.parse_args();site=args.site.resolve();source=args.audition.resolve()
    # Pin every previously published reconstruction file, including its source
    # archive. The old sample bank's audio and downloads are also immutable.
    retained=[site/'dist/reconstruction',site/'dist/audio',site/'dist/downloads']
    before={str(f.relative_to(site)):sha(f.read_bytes()) for directory in retained if directory.exists()
            for f in directory.rglob('*') if f.is_file()}
    manifest_path=site/'dist/hold/manifest.json' if args.source_only else source/'manifest.json'
    m=json.loads(manifest_path.read_text())
    for name,digest in m['sourceSha256'].items():
        assert sha((REPO/'synth/src/main/kotlin/com/snipsnap/synth'/name).read_bytes())==digest,'Render source changed; regenerate audio'
    dest=site/'dist/hold';dest.mkdir(parents=True,exist_ok=True)
    for name in ['index.html','styles.css','app.js']:
        shutil.copyfile(REPO/'synth/src/test/resources/cairn-hold-audition'/name,dest/name)
    for directory in ['recipes','events','preceding-events','traces']:
        shutil.copytree(source/directory,dest/directory,dirs_exist_ok=True)
    if not args.source_only:
        for clip in m['clips']+[m['example']]:
            wav=source/clip['audio'];output=dest/'audio'/f"{clip['id']}.flac"
            assert sha(wav.read_bytes())==clip['sha256']
            encode(wav,output)
            clip['nativeWavSha256']=clip.pop('sha256');clip['audio']='audio/'+output.name
            clip['previewGain']=preview_gain(output);clip['flacSha256']=sha(output.read_bytes())
            if clip.get('held'):
                continuation=source/f"continuation/{clip['id']}.wav"
                target=dest/f"continuation/{clip['id']}.flac"
                encode(continuation,target)
                clip['continuation']=str(target.relative_to(dest))
                clip['continuationFlacSha256']=sha(target.read_bytes())
    m['previewPolicy']='Native mono 44.1 kHz, 24-bit PCM preserved losslessly. Preview gain only: house low-cut loudness proxy target .15, peak ceiling .9. HOLD plays the exported cycle eight times; seam evidence comes from the independently advanced preceding segment and kept cycle.'
    (dest/'manifest.json').write_text(json.dumps(m,indent=2)+'\n')
    # Include new task files in the reviewable patch without staging contents.
    subprocess.run(['git','add','-N','.'],cwd=REPO,check=True)
    patch=git('diff','--binary',args.base_revision)
    assert patch.strip()
    # Verify the complete source backup against a fresh index at the named base.
    # The renderer ran from a modified worktree; do not label the base commit
    # itself as containing the new DSP. File hashes pin that actual renderer.
    with tempfile.TemporaryDirectory(prefix='cairn-restore-') as temporary:
        environment=os.environ.copy()
        environment['GIT_INDEX_FILE']=str(Path(temporary)/'index')
        subprocess.run(['git','read-tree',args.base_revision],cwd=REPO,env=environment,check=True)
        subprocess.run(['git','apply','--cached','--binary','-'],input=patch,cwd=REPO,env=environment,check=True)
        restored=subprocess.check_output(['git','write-tree'],cwd=REPO,env=environment).decode().strip()
    m['sourceArchiveBase']=args.base_revision
    m['sourceArchiveTree']=restored
    m['renderSourceNote']='Rendered from a modified worktree based on sourceRevision. sourceSha256 pins the actual DSP; sourceArchiveTree pins the complete restored backup.'
    (dest/'manifest.json').write_text(json.dumps(m,indent=2)+'\n')
    source_dir=dest/'source';source_dir.mkdir(exist_ok=True)
    archive=source_dir/'cairn-hold-source.zip'
    files={'cairn-hold.patch':patch,'README.txt':(
        'CAIRN model 2: KNOCK and ANSWER HOLD\n\n'
        'Base repository: https://github.com/BertCalm/SnipSnap\n'
        f'Base revision: {args.base_revision}\n'
        f'Render worktree base: {m["sourceRevision"]}; exact DSP hashes are in manifest.json.\n'
        f'Restored source tree: {restored}\n'
        'Check out the base revision, then run: git apply cairn-hold.patch\n'
        'JDK 21: ./gradlew --no-daemon :synth:generateCairnHoldAudition\n'
        'Verification: ./gradlew --no-daemon test\n'
        'Read docs/CAIRN.md for model compatibility, measured seams and limits.\n').encode()}
    paths=['synth/src/main/kotlin/com/snipsnap/synth/Cairn.kt',
           'synth/src/main/kotlin/com/snipsnap/synth/CairnPatch.kt',
           'synth/src/main/kotlin/com/snipsnap/synth/Modes.kt',
           'synth/src/test/kotlin/com/snipsnap/synth/CairnHoldTest.kt',
           'synth/src/test/kotlin/com/snipsnap/synth/ModesPeriodicTest.kt',
           'synth/src/test/kotlin/com/snipsnap/synth/CairnHoldAuditionGenerator.kt','docs/CAIRN.md']
    files.update({path:(REPO/path).read_bytes() for path in paths})
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for name,content in sorted(files.items()):
            item=zipfile.ZipInfo(name,(2026,10,27,0,0,0));item.compress_type=zipfile.ZIP_DEFLATED
            item.external_attr=0o644<<16;z.writestr(item,content)
    # Only add navigation on the bank homepage. The previous audition remains
    # exactly as published, including its original audio and source download.
    index=site/'src/index.html';text=index.read_text()
    if 'href="hold/"' not in text:
        text=text.replace('<a href="reconstruction/">New engine ↗</a>',
                          '<a href="hold/">New HOLD audition ↗</a><a href="reconstruction/">Previous audition</a>')
        text=text.replace('<div class="hero-actions">','<div class="hero-actions"><a class="button" href="hold/">Hear the continuing KNOCK / ANSWER gestures ↗</a>')
        index.write_text(text)
    shutil.copyfile(index,site/'dist/index.html')
    assert all(sha((site/name).read_bytes())==digest for name,digest in before.items()),'A preserved file changed'
    proof={'preservedFiles':len(before),'changedPreservedFiles':0,'nativePcmChanged':0,
           'sourceBase':args.base_revision,'restoredSourceTree':restored,
           'sourcePatchSha256':sha(patch),'sourceZipSha256':sha(archive.read_bytes())}
    (dest/'packaging-verification.json').write_text(json.dumps(proof,indent=2)+'\n')
    print(json.dumps({'clips':len(m['clips'])+1,'path':str(dest),**proof}))
if __name__=='__main__':main()
