#!/usr/bin/env python3
"""Package actual CAIRN JVM renders beside four byte-preserved historical recordings.

Requires Python, numpy, scipy, ffmpeg. No synthesis, resampling or sample editing
happens here. FLAC round trips are checked against the native 24-bit WAV data.
Run after :synth:generateCairnAudition, with --site pointing at the existing Site.
"""
import argparse, base64, hashlib, json, re, shutil, subprocess, wave, zipfile
from pathlib import Path
import numpy as np
from scipy.signal import lfilter

REPO=Path(__file__).resolve().parents[1]
RATE=44100
BASE_REVISION='7659ed77d5448f140b1a01c2d35cc358600b24b6'

def sha(data): return hashlib.sha256(data).hexdigest()
def pcm(path):
    return subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-f','s24le','-c:a','pcm_s24le','pipe:1'])
def samples(path):
    return np.frombuffer(subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-f','f32le','pipe:1']),dtype='<f4')
def loudness(x):
    # Double-precision port of Loudness.kt's low cut and loudest 200 ms window.
    # Used only for preview gain; original PCM and native renderer levels stay intact.
    if not len(x): return 0.
    a=1-np.exp(-2*np.pi*120/RATE)
    lp=lfilter([a],[1,-(1-a)],x)
    y=x-.7*lp
    return max(float(np.sqrt(np.mean(y[start:min(start+8820,len(y))]**2)))
               for start in range(0,max(1,len(y)-8820)+1,2205))
def gain(x):
    peak=float(np.max(np.abs(x))) if len(x) else 0
    level=loudness(x)
    return min(.15/level,.9/peak) if level>1e-8 and peak>1e-8 else 1.
def write_zip(path, files):
    path.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for name,content in sorted(files.items()):
            item=zipfile.ZipInfo(name,(2026,10,24,0,0,0));item.compress_type=zipfile.ZIP_DEFLATED
            item.external_attr=0o644<<16;z.writestr(item,content)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audition',type=Path,default=REPO/'testkit/cairn-reconstruction')
    parser.add_argument('--site',type=Path,required=True)
    parser.add_argument('--base-revision',default=BASE_REVISION,
                        help='Revision against which the downloadable source patch applies')
    args=parser.parse_args()
    source=args.audition.resolve();site=args.site.resolve()
    m=json.loads((source/'manifest.json').read_text())
    for name,digest in m['sourceSha256'].items():
        assert sha((REPO/'synth/src/main/kotlin/com/snipsnap/synth'/name).read_bytes())==digest,'Render source changed: regenerate audio.'
    original=(site/'src/comparison.html').read_bytes()
    assert sha(original)=='c01b763c99c9be3c0f5393de50036bd163bd21f140131cf284cff72e1d1aba2d'
    historic=json.loads(re.search(rb'<script type="application/json" id="audition-data">(.*?)</script>',original,re.S).group(1))
    if isinstance(historic,dict):historic=historic.get('clips',historic.get('rows',historic))
    dest=site/'dist/reconstruction';dest.mkdir(parents=True,exist_ok=True)
    for name in ['index.html','styles.css','app.js']:
        shutil.copyfile(REPO/'synth/src/test/resources/cairn-audition'/name,dest/name)
    for folder in ['recipes','events','traces']:
        shutil.copytree(source/folder,dest/folder,dirs_exist_ok=True)
    (dest/'audio').mkdir(exist_ok=True)
    for clip in m['clips']:
        wav=source/clip['full'];output=dest/'audio'/f"{clip['id']}.flac"
        subprocess.run(['ffmpeg','-v','error','-y','-i',str(wav),'-c:a','flac','-compression_level','12',str(output)],check=True)
        with wave.open(str(wav)) as w:
            assert w.getparams()[:3]==(1,3,RATE)
            raw=w.readframes(w.getnframes())
        assert pcm(output)==raw, 'FLAC changed PCM'
        with wave.open(str(source/clip['audio'])) as w:
            cued=w.readframes(w.getnframes())
        cue=round(clip['outputStartMs']*RATE/1000)
        assert cued==raw[cue*3:], 'Contact cue changed the source samples'
        clip['renderWavSha256']=clip.pop('sha256')
        clip['audio']=clip['full']='audio/'+output.name
        clip['previewFlacSha256']=sha(output.read_bytes())
        clip['previewGain']=gain(samples(output)[cue:])
    refs=[]
    (dest/'references').mkdir(exist_ok=True)
    for voice in ['KNOCK','ANSWER']:
        for section,key in [('Voices','default'),('Equal controls','middle')]:
            row=next(r for r in historic if r['voice']==voice and r['section']==section)
            data=base64.b64decode(row['after'].split(',',1)[1])
            name=f"{voice.lower()}-{key}.flac";output=dest/'references'/name;output.write_bytes(data)
            first=float(row['afterMetrics']['firstImpact'])*1000
            cue=max(0,round((first-2)*RATE/1000))
            refs.append({'id':f'reference-{voice.lower()}-{key}','voice':voice,'reference':True,
                'audio':'references/'+name,'sha256':sha(data),'firstContactMs':first,
                'previewGain':gain(samples(output)[cue:]),'macros':row['macros'],
                'historicalMetrics':row['afterMetrics']})
    m['references']=refs
    m['previewPolicy']='Original PCM preserved in lossless FLAC. Preview gain uses a double-precision port of the house loudness proxy, target .15, peak ceiling .9. The first-contact cue skips samples only during playback.'
    (dest/'manifest.json').write_text(json.dumps(m,indent=2)+'\n')
    # The backup is a reviewable source delta against a named repository revision.
    # Include new files in the diff without committing or staging their contents.
    subprocess.run(['git','add','-N','.'],cwd=REPO,check=True)
    patch=subprocess.check_output(['git','diff','--binary',args.base_revision],cwd=REPO)
    assert patch.strip(),'No source delta to back up'
    files={'cairn.patch':patch,
           'README.txt':('CAIRN model 1 reconstruction\n\n'
             'New source, not a recovery of the six-voice renderer.\n'
             f"Base repository: https://github.com/BertCalm/SnipSnap\nBase revision: {args.base_revision}\n"
             f"Render revision: {m['sourceRevision']} (exact DSP file hashes are in the manifest)\n"
             'Checkout that revision, then run: git apply cairn.patch\n'
             'JDK 21: ./gradlew --no-daemon :synth:generateCairnAudition\n'
             'Verification: ./gradlew --no-daemon test\n'
             'Read docs/CAIRN.md for scope, measurements and deferred work.\n').encode()}
    for path in ['synth/src/main/kotlin/com/snipsnap/synth/Cairn.kt',
                 'synth/src/main/kotlin/com/snipsnap/synth/CairnPatch.kt',
                 'synth/src/main/kotlin/com/snipsnap/synth/CairnPresets.kt',
                 'synth/src/test/kotlin/com/snipsnap/synth/CairnTest.kt',
                 'synth/src/test/kotlin/com/snipsnap/synth/CairnAuditionGenerator.kt','docs/CAIRN.md']:
        files[path]=(REPO/path).read_bytes()
    write_zip(dest/'source/cairn-reconstruction-source.zip',files)
    # Existing sample bank and original comparison retain their audio and downloads.
    index=site/'src/index.html';text=index.read_text()
    if 'href="reconstruction/"' not in text:
        text=text.replace('<a href="#presets">Sounds</a>', '<a href="reconstruction/">New engine ↗</a><a href="#presets">Sounds</a>')
        text=text.replace('<div class="hero-actions">', '<div class="hero-actions"><a class="button" href="reconstruction/">Hear the new KNOCK / ANSWER engine ↗</a>')
        index.write_text(text)
    shutil.copyfile(index,site/'dist/index.html')
    print(json.dumps({'clips':len(m['clips']),'references':len(refs),'changedReferencePCM':0,
                      'sourceZip':str(dest/'source/cairn-reconstruction-source.zip'),
                      'previewDirectory':str(dest)}))
if __name__=='__main__':main()
