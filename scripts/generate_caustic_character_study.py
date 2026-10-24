#!/usr/bin/env python3
"""Audition-first CAUSTIC contrasts. Python prototypes, compiled Kotlin geometry/output.

Run :synth:classes first; this script compiles the small Java API adapter. Native
model 1, factory presets and all earlier audio remain unchanged. Requires NumPy/SciPy.
"""
from __future__ import annotations
import argparse, hashlib, json, os, shutil, subprocess, tempfile, time, wave, zipfile
from pathlib import Path
import numpy as np
from caustic_character_study_dsp import render_hollow, render_rubbed, parameters
from caustic_character_study_metrics import evaluate_study

RATE, INTERNAL = 44100, 176400
MIDI = (36, 60, 84)
FAMILIES = {
    'fuller-body': ('Fuller Body', 'Metallic strike, a fuller tonal body and a long ring.', 'strike → growing body → ringing release'),
    'hollow-mallet': ('Hollow Mallet', 'A softer contact and a short, hollow low/odd-mode body.', 'soft contact → hollow knock → short decay'),
    'rubbed-ring': ('Rubbed Ring', 'A rough driven swell replaces the hard metal strike.', 'rough contact → pressure swell → driven body → release'),
    'travelling-catches': ('Travelling Catches', 'Four energy-budgeted contacts illuminate different paths through one ringing body.', 'first contact → three spatial catches → shared ring'),
}

def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def run(args, **kwargs): return subprocess.run(list(map(str,args)),check=True,text=True,**kwargs)
def wav(path):
    with wave.open(str(path),'rb') as f:
        assert f.getnchannels()==1 and f.getframerate()==RATE and f.getsampwidth()==3
        data=np.frombuffer(f.readframes(f.getnframes()),dtype=np.uint8).reshape(-1,3).astype(np.int32)
    v=data[:,0]|(data[:,1]<<8)|(data[:,2]<<16);v=np.where(v&0x800000,v-0x1000000,v)
    return v.astype(np.float64)/8388608
def revision(repo):
    git=lambda *args:subprocess.check_output(['git','-C',str(repo),*args],text=True).strip()
    roots=['scripts/generate_caustic_character_study.py','scripts/caustic_character_study_dsp.py','scripts/caustic_character_study_metrics.py','scripts/caustic-character','scripts/caustic-character-study-assets']
    files={str(p.relative_to(repo)):sha(p) for p in sorted((repo/'scripts').rglob('*')) if p.is_file() and (p.name.startswith('caustic_character_') or p.name=='generate_caustic_character_study.py' or 'caustic-character' in p.parts or 'caustic-character-study-assets' in p.parts)}
    return {'commit':git('rev-parse','HEAD'),'dirty':bool(git('status','--porcelain','--',*roots)),'files':files,
            'compiledNativeClassSha256':sha(repo/'synth/build/classes/kotlin/main/com/snipsnap/synth/CausticInstrument.class'),
            'nativeModel':1,'python':__import__('sys').version.split()[0],'numpy':np.__version__}

def main():
    p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,default=Path(__file__).resolve().parents[1]);p.add_argument('--output',type=Path);p.add_argument('--reuse-input',type=Path);args=p.parse_args()
    repo=args.repo.resolve();output=(args.output or repo/'testkit/caustic-character-study').resolve()
    assert output!=repo and output not in repo.parents, 'Output cannot contain the source checkout'
    assert not output.is_file(), 'Output must be a generated study directory'
    if output.exists():
        assert (output/'manifest.json').is_file() and json.loads((output/'manifest.json').read_text()).get('title')=='CAUSTIC / Character study', 'Refusing to replace an unrelated directory'
    old=repo/'testkit/caustic-instrument-audition';assert old.is_dir()
    preserved={str(x.relative_to(old)):sha(x) for x in old.rglob('*') if x.is_file()}
    jdk=Path(os.environ.get('JAVA_HOME','/workspace/jdk/current'))
    java=str(jdk/'bin/java') if (jdk/'bin/java').exists() else shutil.which('java')
    javac=str(jdk/'bin/javac') if (jdk/'bin/javac').exists() else shutil.which('javac')
    stdlib=list(Path.home().glob('.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21/*/*.jar'))
    cp=os.pathsep.join(map(str,[*sorted(repo.glob('*/build/classes/kotlin/main')),*stdlib]))
    assert stdlib and (repo/'synth/build/classes/kotlin/main/com/snipsnap/synth/CausticInstrument.class').is_file(), 'Run :synth:classes first'
    output.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.caustic-character-',dir=output.parent) as temp:
        root=Path(temp);classes=root/'classes';classes.mkdir();inputs=args.reuse_input or root/'inputs';inputs.mkdir(exist_ok=True)
        run([javac,'--release','17','-cp',cp,'-d',classes,repo/'scripts/caustic-character/CausticStudyBridge.java'])
        bridge=lambda *a,**kw:run([java,'-Xmx1g','-cp',str(classes)+os.pathsep+cp,'CausticStudyBridge',*a],**kw)
        if not args.reuse_input:bridge('export',inputs)
        raw=root/'raw';raw.mkdir();audio=root/'audio';audio.mkdir();recipes=root/'recipes';recipes.mkdir()
        geometry=json.loads((inputs/'geometry-0.19.json').read_text());profiles=[];records=[];times={}
        sources=[0.,.25,.6,1.];nominal=[0.,.12,.28,.48];gains=np.array([.75,.65,.55,.45]);gains/=np.sum(np.abs(gains))
        study_params=parameters()
        for midi in MIDI:
            octave=midi//12-1;hz=440*2**((midi-69)/12)
            for family,renderer in [('hollow-mallet',render_hollow),('rubbed-ring',render_rubbed)]:
                start=time.perf_counter();data=renderer(midi,.75,geometry);times[(family,midi)]=(time.perf_counter()-start)*1000
                assert data.shape==(8*INTERNAL,) and np.isfinite(data).all() and np.max(np.abs(data))<8
                data.astype('<f4').tofile(raw/f'{family}-c{octave}.internal.f32')
            result=np.zeros(8*INTERNAL);events=[]
            for source,t,gain in zip(sources,nominal,gains):
                # Period-quantized launch times protect the authored root; each
                # spatial response still has its own causal geometry phase.
                at=round(round(t*hz)/hz*INTERNAL);actual=at/INTERNAL
                basis=np.fromfile(inputs/f'native-m{midi}-s{source:.2f}-b0.internal.f32',dtype='<f4')
                result[at:]+=float(gain)*basis[:len(result)-at]
                events.append({'source':source,'nominalTimeSeconds':t,'timeSeconds':actual,'amplitude':float(gain)})
            result.astype('<f4').tofile(raw/f'travelling-catches-c{octave}.internal.f32')
            for family in FAMILIES:
                ident=f'{family}-c{octave}';title,description,gesture=FAMILIES[family]
                recipe={'format':'caustic-character-study','version':1,'family':family,'midi':midi,'velocity':.75,'seconds':8,'sampleRate':RATE,'oversample':4,'status':'native reference' if family=='fuller-body' else 'listening prototype, not a native Patch','geometry':{'source':.5,'focus':.19,'rays':256,'aperture':.05,'pathsSha256':sha(inputs/'geometry-0.19.json')}}
                if family=='fuller-body':
                    src=f'default-c{octave}'
                    for mode in ('native','matched'):shutil.copy2(old/f'audio/{src}-{mode}.wav',audio/f'{ident}-{mode}.wav')
                    recipe['nativePatch']=json.loads((old/f'patches/{src}.json').read_text());recipe['nativeSourceRevision']=json.loads((old/'manifest.json').read_text())['sourceRevision']
                elif family=='travelling-catches':recipe.update({'method':'sum unconditioned responses of one linear native body, then finish once','body':0,'length':1,'contacts':events,'sumAbsoluteContactAmplitudes':float(np.sum(np.abs(gains))),'budget':'L1 contact-force budget. Overlapping contacts get no independent makeup gain; acoustic energy conservation is not claimed.'})
                else:recipe['parameters']=study_params['hollow' if family=='hollow-mallet' else 'rubbed']
                (recipes/f'{ident}.json').write_text(json.dumps(recipe,indent=2)+'\n')
                records.append({'id':ident,'family':family,'title':title,'description':description,'gesture':gesture,'midi':midi,'velocity':.75,'seconds':8,'prototype':family!='fuller-body','raw':f'audio/{ident}-native.wav','matched':f'audio/{ident}-matched.wav','recipe':f'recipes/{ident}.json','eventTimesSeconds':[e['timeSeconds'] for e in events] if family=='travelling-catches' else [0]})
        finished=bridge('finishdir',raw,audio,capture_output=True)
        validations=[json.loads(line) for line in finished.stdout.splitlines() if line.startswith('{')]
        assert len(validations)==9
        for rec in records:
            samples=wav(root/rec['matched']);profiles.append(dict(rec,samples=samples))
            if rec['family']=='fuller-body':
                v=bridge('validate',root/rec['raw'],rec['midi'],capture_output=True);validations.append(json.loads(v.stdout.strip()))
            rec['hashes']={key:sha(root/rec[key]) for key in ('raw','matched','recipe')}
            rec['metrics']={**next(v for v in validations if v['file']==Path(rec['raw']).name),'pythonRawRenderMillis':times.get((rec['family'],rec['midi']))}
        report=evaluate_study(profiles,baseline_id='fuller-body')
        (root/'diagnostics.json').write_text(json.dumps({'kotlinValidation':validations,'contrast':report,'parameters':study_params},indent=2)+'\n')
        (root/'manifest.json').write_text(json.dumps({'title':'CAUSTIC / Character study','description':'Four contrasting gestures at the same pitch.','sourceRevision':revision(repo),'summary':{'clipCount':12,'directions':4,'sampleRate':RATE,'channels':1,'bitDepth':24,'nativeReferenceUnchanged':True,'checks':'All 12 clips pass Kotlin broadband pitch within 20 cents, confidence >=.3 and finite/no clipping checks. Shape diagnostics are not hearing approval.','limits':'Three audition-only prototypes. Rubbing is a driven pressure/noise approximation. No held loop or new factory voice.','downloads':{'comparison':'caustic-character-study.zip'}} ,'clips':records},indent=2)+'\n')
        manifest=json.loads((root/'manifest.json').read_text());(root/'manifest.js').write_text('window.CAUSTIC_CHARACTER_MANIFEST = '+json.dumps(manifest,separators=(',',':'))+';\n')
        shutil.copytree(repo/'scripts/caustic-character-study-assets',root,dirs_exist_ok=True)
        (root/'README.txt').write_text('CAUSTIC character listening study\n\nFuller Body is the unchanged native reference. Other directions are Python listening prototypes, not installed factory voices or native Patch JSON.\n\nEvery primary Play button plays its named sound. Level matched uses the existing loudest-200-ms rule.\n\nReproduce from the source revision with :synth:classes, NumPy/SciPy, then python3 scripts/generate_caustic_character_study.py. The Java bridge uses actual Kotlin geometry, output and pitch APIs.\n\nNo legacy or native audio is overwritten. Native model 1 and its presets remain unchanged pending listening.\n')
        for f in audio.glob('*.f32'):f.unlink()
        for f in audio.glob('*.finish.json'):f.unlink()
        shutil.rmtree(classes);shutil.rmtree(raw)
        if not args.reuse_input:shutil.rmtree(inputs)
        with zipfile.ZipFile(root/'caustic-character-study.zip','w',zipfile.ZIP_DEFLATED,6) as z:
            for f in sorted(root.rglob('*')):
                if f.is_file() and f.suffix!='.zip':z.write(f,f.relative_to(root))
        assert all(sha(old/name)==digest for name,digest in preserved.items())
        if output.exists():shutil.rmtree(output)
        output.mkdir();
        for f in root.iterdir():shutil.move(str(f),output/f.name)
        print(json.dumps({'output':str(output),'clips':12,'validation':validations,'contrastWarnings':report.get('warnings',[])},indent=2))

if __name__=='__main__':main()
