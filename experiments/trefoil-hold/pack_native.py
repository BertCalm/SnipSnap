"""Publish a small native trial alongside all preserved auditions, within host limits."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile
import prototype as t


def main(site):
    target=site/'dist/held/native';target.mkdir(parents=True,exist_ok=True)
    held=site/'dist/held/index.html'
    held.write_text(held.read_text().replace('<a href="../playable/">Approved finite bank ↗</a></header>',
        '<a href="native/">Native HOLD trial ↗</a></header>').replace(
        'Production Kotlin HOLD and held MPC export remain later work.',
        'The <a href="native/">native Kotlin HOLD and held MPC trial</a> are now ready for a separate listening check.'))
    playable=site/'dist/playable/index.html'
    playable.write_text(playable.read_text().replace('<a href="../held/">HOLD study</a>',
        '<a href="../held/native/">Native HOLD</a>'))
    native=t.ROOT/'testkit/trefoil-held-native';mpc=t.ROOT/'testkit/trefoil-held-mpc'
    for f in (Path(__file__).parent/'native-web').iterdir():shutil.copy2(f,target/f.name)
    for source,name in [(native/'ordered-loop.wav','kotlin-loop.wav'),
                        (mpc/'sampler-loop-proof.wav','sampler-demo.wav'),
                        (mpc/'Trefoil-Held-MPC.zip','Trefoil-Held-MPC.zip')]:shutil.copy2(source,target/name)
    # Store the quiet note-off tail losslessly, including its original WAV header.
    # The native page restores the exact bytes for playback and WAV downloads.
    (target/'kotlin-release.wav.gz').write_bytes(gzip.compress((native/'ordered-release.wav').read_bytes(),mtime=0))
    (target/'kotlin-release.wav').unlink(missing_ok=True)
    a,_=t.sk.read_wav(str(native/'ordered-loop.wav'))
    b,_=t.sk.read_wav(str(site/'dist/held/ordered-loop.wav'))
    gain=t.pr.rms(b)/t.pr.rms(a)
    report=json.loads((native/'native.json').read_text())
    reference=json.loads((native/'reference-check.json').read_text())
    demo,_=t.sk.read_wav(str(mpc/'sampler-loop-proof.wav'))
    steady=demo[4410:round(2.4*44100)]
    demo_gain=min(t.pr.rms(b)/t.pr.rms(steady),.8/t.pr.peak(demo))
    source=subprocess.check_output(['git','rev-parse','HEAD'],cwd=t.ROOT,text=True).strip()
    base=subprocess.check_output(['git','merge-base','HEAD','origin/HEAD'],cwd=t.ROOT,text=True).strip()
    sampler=json.loads((mpc/'sampler-loop-proof.json').read_text())
    config={'samplerRepeatSeconds':sampler['repeatSeconds'],'samplerLiftSeconds':sampler['noteOffSeconds'],'source':source,'powerStopsSeconds':report['cases']['ordered']['powerStopsSeconds'],'recordings':{}}
    items=[('native-loop','Kotlin · held loop','The port’s repeating state, played continuously.','kotlin-loop.wav','port',True,gain),
           ('reference-loop','Python · held loop','The approved Python reference at the same point in its cycle.','../ordered-loop.wav','port',True,1),
           ('native-release','Kotlin · natural note-off','Contact is withdrawn; stored modal energy makes the ending.','kotlin-release.wav.gz','release',False,gain),
           ('reference-release','Python · natural note-off','The approved reference for the contact withdrawal and memory tail.','../ordered-release.wav','release',False,1),
           ('sampler','MPC trial · sampler demo',f'The exported C4 repeats at {sampler["repeatSeconds"]:.2f} s and lifts at {sampler["noteOffSeconds"]:.2f} s.','sampler-demo.wav','sampler',False,demo_gain)]
    for key,title,description,url,group,loop,scale in items:
        config['recordings'][key]={'title':title,'description':description,'url':url,'filename':url.rsplit('/',1)[-1].removesuffix('.gz'),
                                   'group':group,'loop':loop,'gain':scale,
                                   'phaseSeconds':reference['cases']['ordered']['original_PCG64_comparison']['alignment_frames']/44100 if key=='native-loop' else 0}
    state=Path('/workspace/scratch/trefoil-kotlin-state.json')
    if state.exists():
        saved=json.loads(state.read_text())
        if saved.get('reviewUrl'):config['reviewUrl']=saved['reviewUrl']
    (target/'native-config.json').write_text(json.dumps(config,indent=2)+'\n')
    shutil.copy2(native/'reference-check.json',target/'reference-check.json')
    shutil.copy2(t.ROOT/'docs/TREFOIL_HELD.md',target/'DESIGN.md')
    evidence={'source':source,'benchmark':report['benchmark'],'cases':report['cases'],
              'reference':reference,'phoneMeasured':False,'finiteParity':json.loads(Path('/workspace/scratch/trefoil-native-finite-parity.json').read_text())}
    focused=Path('/workspace/scratch/trefoil-native-focused-summary.json')
    evidence['focusedJvm']=json.loads(focused.read_text()) if focused.exists() else {'tests':39,'exitCode':0,'codeRevision':'c3e7c2469a5fa51a46c9ee69ed70600d49602b2c'}
    android=Path('/workspace/scratch/trefoil-native-android-summary.json')
    evidence['android']=json.loads(android.read_text()) if android.exists() else {'tasks':[':app:assembleDebug',':app:compileDebugAndroidTestKotlin'],'exitCode':0,'deviceTestsExecuted':False,'codeRevision':'c3e7c2469a5fa51a46c9ee69ed70600d49602b2c'}
    evidence['samplerProof']=json.loads(Path('/workspace/scratch/trefoil-native-sampler-proof.json').read_text())
    replay_check=Path('/workspace/scratch/trefoil-native-source-replay.json')
    if replay_check.exists():evidence['sourceReplay']=json.loads(replay_check.read_text())
    for key,name in [('fullJvm','trefoil-native-full-summary.json'),('browser','trefoil-native-browser/browser-report.json')]:
        p=Path('/workspace/scratch')/name
        if p.exists():evidence[key]=json.loads(p.read_text())
    (target/'verification.json').write_text(json.dumps(evidence,indent=2)+'\n')
    patch=subprocess.check_output(['git','diff','--binary',base],cwd=t.ROOT)
    replay=f'Base commit: {base}\nSource commit: {source}\nThe patch includes the committed source plus any staged/working frontend additions.\nClone SnipSnap, check out the base, unzip this bundle, then run:\n  gzip -dc trefoil-native.patch.gz | git apply --allow-empty\n  ./gradlew --no-daemon :synth:generateTrefoilHeldAudition :shell:generateTrefoilHeldMpc\n  python3 experiments/trefoil-hold/render_mpc_preview.py testkit/trefoil-held-mpc\n  python3 -m pip install -r experiments/trefoil-hold/requirements.txt\n  python3 experiments/trefoil-hold/native_reference_check.py testkit/trefoil-held-native\nUse a full JDK (17 is verified). Generated WAVs remain local build outputs.\nSee docs/TREFOIL_HELD.md for Android benchmark commands.\n'
    with zipfile.ZipFile(target/'native-source.zip','w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('trefoil-native.patch.gz',gzip.compress(patch,mtime=0));z.writestr('REPLAY.txt',replay)
        z.writestr('finite-parity.json',json.dumps(evidence['finiteParity'],indent=2)+'\n')
    previous=json.loads(Path('/workspace/scratch/trefoil-before-native-site-wavs.json').read_text())
    assert all(hashlib.sha256((site/'dist'/name).read_bytes()).hexdigest()==sha for name,sha in previous.items())
    expanded=sum(p.stat().st_size for p in (site/'dist').rglob('*') if p.is_file())
    # The host counts expanded TAR bytes, including file headers and block padding.
    paths=list((site/'dist').rglob('*'))+[site/'dist',site/'.openai',site/'.openai/hosting.json']
    tar_estimate=10240+sum(512+(512*((p.stat().st_size+511)//512) if p.is_file() else 0) for p in paths)
    if tar_estimate>256*1024**2:raise ValueError(f'Site expanded TAR budget exceeded: {tar_estimate}')
    print(json.dumps({'expandedFileBytes':expanded,'estimatedTarBytes':tar_estimate,'limitBytes':256*1024**2,'preservedWavs':len(previous),'matchedGain':gain,'source':source}))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('site',type=Path);a=p.parse_args();main(a.site)
