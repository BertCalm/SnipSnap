#!/usr/bin/env python3
"""Actual Kotlin circuit experiment, controlled by Python; not a shipping Kotlin engine.

Adapted from Luthier's proto_template workflow, but the core/conditioning here are
the real compiled Crease implementation, not snipkit's approximate output stage.
Java reflection stays in the research harness and is guarded by exact public-prefix
and complete state-fork checks. Measurements below are Python/SciPy measurements.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import wave
import zipfile

os.environ['OPENBLAS_NUM_THREADS'] = '1'
os.environ['OMP_NUM_THREADS'] = '1'
import numpy as np
from scipy import signal
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[2]
RATE = 44100
PRESET_NAMES = {'paper-keys': 'Paper Keys', 'reed-mark': 'Reed Mark', 'ink-ribbon': 'Ink Ribbon'}
PHASES = [0, .25, .5, .75]
EARLY = (.04, .25)
LATE = (.25, .5)
TARGET = float(np.float32(.1834))
SOURCE_PATHS = [
    'synth/src/main/kotlin/com/snipsnap/synth/Crease.kt',
    'synth/src/main/kotlin/com/snipsnap/synth/CreaseKeys.kt',
    'synth/src/main/kotlin/com/snipsnap/synth/CreasePatch.kt',
    'synth/src/main/kotlin/com/snipsnap/synth/CreasePresets.kt',
    'audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt',
    'shell/src/main/kotlin/com/snipsnap/shell/InstrumentEngine.kt',
    'kit/src/main/kotlin/com/snipsnap/kit/InstrumentStore.kt',
    'app/src/main/cpp/PadEngine.cpp',
    'app/src/main/cpp/PadEngine.h',
    'xpm/src/main/kotlin/com/snipsnap/xpm/KeygroupWriter.kt',
    'mpc3/src/main/kotlin/com/snipsnap/mpc3/Mpc3TrackWriter.kt',
]


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, allow_nan=False) + '\n')


def jdk():
    candidates = [shutil.which('javac')]
    if os.environ.get('JAVA_HOME'):
        candidates.append(str(Path(os.environ['JAVA_HOME']) / 'bin/javac'))
    candidates.extend(str(p) for p in Path('/workspace').glob('jdk-*/bin/javac'))
    candidates.extend(str(p) for p in Path('/usr/lib/jvm').glob('*/bin/javac'))
    for item in candidates:
        if item and Path(item).is_file():
            return Path(item).resolve().parent
    raise RuntimeError('A JDK containing javac is required; no automatic installation is attempted')


def classpath():
    jars = [ROOT / f'{name}/build/libs/{name}.jar' for name in ['synth', 'audio', 'json']]
    for jar in jars:
        require(jar.is_file(), f'Build first: env -u ANDROID_HOME -u ANDROID_SDK_ROOT ./gradlew --no-daemon :synth:jar; missing {jar}')
    cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
    stdlibs = list((cache / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21').glob('*/*.jar'))
    require(len(stdlibs) == 1, 'Expected the repository Kotlin 2.0.21 standard library in the Gradle cache')
    return jars + stdlibs


def run_harness(out, build):
    jars = classpath()
    classes = build / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    bins = jdk()
    cp = os.pathsep.join(map(str, jars))
    subprocess.run([str(bins/'javac'), '--release', '17', '-cp', cp, '-d', str(classes),
                    str(Path(__file__).with_name('CreaseReleaseExperiment.java'))], cwd=ROOT, check=True)
    runtime = os.pathsep.join([cp, str(classes)])
    commands = []
    for group in ['main', 'reference', 'silence']:
        folder = build / group
        folder.mkdir(exist_ok=True)
        command = [str(bins/'java'), '-Xmx2g', '-cp', runtime,
                   'CreaseReleaseExperiment', str(folder)] + ([] if group == 'main' else [group])
        begin = time.monotonic()
        with (build/f'{group}.log').open('w') as log:
            result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        (build/f'{group}.exit').write_text(str(result.returncode)+'\n')
        require(result.returncode == 0, f'{group} Java gate failed; see {build / (group+".log")}')
        commands.append({'group': group, 'command': command, 'exitCode': result.returncode,
                         'seconds': time.monotonic()-begin})
        print(f'{group}: actual Kotlin circuit checks passed', flush=True)
    return commands, [{'path': str(p.relative_to(ROOT)) if p.is_relative_to(ROOT) else str(p),
                       'sha256': sha(p.read_bytes())} for p in jars]


def read_cases(folder):
    data = {}
    for p in sorted(folder.glob('*.json')):
        row = json.loads(p.read_text())
        x = np.fromfile(p.with_suffix('.f32'), dtype='<f4')
        require(len(x) > RATE, f'empty release {p.name}')
        require(np.isfinite(x).all(), f'nonfinite {p.name}')
        require(row['publicPrefixMaxError'] == row['forkStateError'] == 0, f'fork/public parity {p.name}')
        require(row['recurrence'] < 1e-7, f'nonrecurring warm-up {p.name}')
        require(row['rawPeak'] <= .91*row['velocity']+1e-9, f'unconditioned bound {p.name}')
        if row['mode'] == 1:
            require(row['deterministicForkVerified'], f'determinism {p.name}')
            require(row['finalExcess'] < 1e-4, f'deformation drain {p.name}')
        elif row['mode'] == 2 and row['velocity'] > 0:
            require(row['postResetStateError'] > 1e-4, f'reset canary did not reset {p.name}')
        data[(row['preset'], row['phase'], row['midi'], row['oversample'], row['mode'])] = (row, x, p)
    return data


def window(x, interval):
    return x[RATE+int(interval[0]*RATE):RATE+int(interval[1]*RATE)].astype(np.float64)


def rms(x):
    return float(np.sqrt(np.mean(np.square(x))))


def profile(x, hz):
    x = np.asarray(x, dtype=np.float64)
    weighted = (x-x.mean()) * signal.windows.hann(len(x), sym=False)
    harmonics = np.arange(1, min(96, int(19500/hz))+1)
    matrix = np.exp((-2j*np.pi*hz/RATE) * harmonics[:, None] * np.arange(len(x))[None, :])
    power = np.abs(matrix @ weighted)**2
    return power / max(float(power.sum()), 1e-30)


def variation(a, b, hz):
    return float(.5 * np.sum(np.abs(profile(a, hz)-profile(b, hz))))


def pitch_near_root(x, hz):
    """Overlap-normalized correlation near the supplied root; no independent octave claim."""
    x = np.asarray(x, dtype=np.float64)
    x = x-x.mean()
    n = len(x)
    lo = max(2, int(RATE/(hz*1.05)))
    hi = min(n//2, int(np.ceil(RATE/(hz*.95))))
    spectrum = np.fft.rfft(x, 2*n)
    ac = np.fft.irfft(spectrum*np.conj(spectrum), 2*n)[:hi+2]
    energy = np.concatenate(([0.0], np.cumsum(x*x)))
    lags = np.arange(1, hi+2)
    denominator = np.sqrt(energy[n-lags] * (energy[n]-energy[lags]))
    corr = np.zeros(hi+2)
    corr[lags] = ac[lags] / np.maximum(denominator, 1e-30)
    lag = int(np.argmax(corr[lo:hi+1]))+lo
    delta = 0.0
    if lo < lag < hi:
        a,b,c = corr[lag-1:lag+2]
        if a-2*b+c != 0:
            delta = .5*(a-c)/(a-2*b+c)
    estimate = RATE/(lag+delta)
    return {'hz': estimate, 'cents': float(1200*np.log2(estimate/hz)),
            'correlation': float(corr[lag]), 'scope': 'root-constrained ±5 percent'}


def pcm16(x):
    require(np.max(np.abs(x)) <= 1, 'PCM encoding would clip')
    return np.rint(x*32767).astype('<i2').tobytes()


def export_audio(out, stem, x):
    audio = out/'audio'
    audio.mkdir(exist_ok=True)
    wav = audio/(stem+'.wav')
    pcm = pcm16(x)
    with wave.open(str(wav), 'wb') as writer:
        writer.setnchannels(1); writer.setsampwidth(2); writer.setframerate(RATE); writer.writeframes(pcm)
    flac = wav.with_suffix('.flac')
    subprocess.run(['ffmpeg','-v','error','-nostdin','-y','-i',str(wav),'-map_metadata','-1',
                    '-c:a','flac','-compression_level','8',str(flac)],check=True)
    decoded = subprocess.check_output(['ffmpeg','-v','error','-nostdin','-i',str(flac),
                                       '-f','s16le','-acodec','pcm_s16le','-'])
    require(decoded == pcm, f'FLAC changed PCM: {stem}')
    return {'audio': 'audio/'+flac.name, 'wav': 'audio/'+wav.name, 'frames': len(x),
            'duration': len(x)/RATE, 'flacBytes': flac.stat().st_size, 'flacSha256': sha(flac.read_bytes()),
            'wavBytes': wav.stat().st_size, 'wavSha256': sha(wav.read_bytes()),
            'pcmSha256': sha(pcm), 'decodedPcmIdentical': True}


def plot_case(out, preset, normal, released, x_a, x_b, hz):
    a = np.genfromtxt(normal, delimiter=',', names=True)
    b = np.genfromtxt(released, delimiter=',', names=True)
    fig, axes = plt.subplots(3, 1, figsize=(9,7), constrained_layout=True)
    for row, title in [(a,'Fade only'),(b,'Unfold')]:
        mask = row['seconds'] <= 1.2
        axes[0].plot(row['seconds'][mask], row['pressure'][mask], label=title)
        axes[1].plot(row['seconds'][mask], row['deformationB'][mask], label=title)
    axes[0].set_ylabel('Pressure state'); axes[1].set_ylabel('Deformation B')
    times = np.arange(.04,.94,.075)
    for x, label in [(x_a,'Fade only'),(x_b,'Unfold')]:
        centroid=[]
        for t in times:
            p=profile(window(x,(t,t+.15)),hz)
            centroid.append(float(np.sum(p*np.arange(1,len(p)+1))))
        axes[2].plot(times,centroid,label=label)
    axes[2].set_ylabel('Harmonic centre'); axes[2].set_xlabel('Seconds after key-up')
    for ax in axes:
        ax.axvline(0,color='#555',ls=':',lw=1); ax.grid(alpha=.2); ax.legend(frameon=False)
    fig.suptitle(PRESET_NAMES[preset]+': same fade, different pressure history')
    path=out/(preset+'-release.png');fig.savefig(path,dpi=150);plt.close(fig)


def analyse(out, build):
    main=read_cases(build/'main');reference=read_cases(build/'reference');silence=read_cases(build/'silence')
    require(len(main)==36 and len(reference)==18 and len(silence)==9,'missing experiment cases')
    require(all(np.count_nonzero(x)==0 for _,x,_ in silence.values()),'zero-velocity self-start')
    pairs=[];clips=[];reference_checks=[]
    for preset in PRESET_NAMES:
        chosen=[v for k,v in main.items() if k[0]==preset and k[4] in (0,1)]
        row=chosen[0][0]
        native_gain=TARGET*row['velocity']**.4/row['sustainLoudness']
        pool_peak=max(float(np.max(np.abs(x)))*native_gain for _,x,_ in chosen)
        shared_headroom=min(1,.99/max(pool_peak,1e-30));gain=native_gain*shared_headroom
        for phase in PHASES:
            ra,xa,pa=main[(preset,phase,60,4,0)]
            rb,xb,pb=main[(preset,phase,60,4,1)]
            _,reset,_=main[(preset,phase,60,4,2)]
            require(np.array_equal(xa[:RATE],xb[:RATE]),'different pre-key-up recordings')
            first_delta=abs(float(xa[RATE])-float(xb[RATE]))
            require(first_delta<1e-4,'key-up boundary impulse')
            early=variation(window(xa,EARLY),window(xb,EARLY),ra['renderedHz'])
            late=variation(window(xa,LATE),window(xb,LATE),ra['renderedHz'])
            pitches=[pitch_near_root(window(x,EARLY),ra['renderedHz']) for x in [xa,xb]]
            require(all(abs(p['cents'])<10 and p['correlation']>=.90 for p in pitches),'release pitch gate')
            pair={'preset':preset,'phase':phase,'earlyHarmonicTv':early,'lateHarmonicTv':late,
                  'resetCanaryTv':variation(window(xb,EARLY),window(reset,EARLY),ra['renderedHz']),
                  'firstPostKeyUpDifference':first_delta,'pitch':pitches,
                  'warmCycles':ra['warmCycles'],'loopFrames':ra['loopFrames'],
                  'releaseSeconds':ra['releaseSeconds'],'commonGain':gain,'sharedHeadroom':shared_headroom,
                  'forkStateError':rb['forkStateError'],'publicPrefixMaxError':rb['publicPrefixMaxError'],
                  'deformationDrainResidual':rb['finalExcess'],'mechanicsDrainSeconds':rb['mechanicsDrainSeconds']}
            pairs.append(pair)
            preview_fade=np.ones(len(xa));preview_fade[:441]=np.linspace(0,1,441)
            for mode,x in [('fade',xa),('unfold',xb)]:
                stem=f'{preset}-p{int(phase*100)}-{mode}'
                entry=export_audio(out,stem,x.astype(np.float64)*gain*preview_fade)
                entry.update(id=stem,preset=preset,phase=phase,mode=mode,level='common',keyUpSeconds=1.0)
                clips.append(entry)
            if phase==.5:
                ar=rms(window(xa,(.04,.5)));br=rms(window(xb,(.04,.5)))
                scalars=[1,ar/br]
                peak=max(float(np.max(np.abs(x)))*gain*s for x,s in zip([xa,xb],scalars))
                headroom=min(1,.99/max(peak,1e-30))
                for mode,x,s in zip(['fade','unfold'],[xa,xb],scalars):
                    stem=f'{preset}-p50-{mode}-matched'
                    entry=export_audio(out,stem,x.astype(np.float64)*gain*s*headroom*preview_fade)
                    entry.update(id=stem,preset=preset,phase=.5,mode=mode,level='tail-matched',
                                 keyUpSeconds=1.0,extraWholeTakeGain=s*headroom,
                                 matchingWindowSecondsAfterKeyUp=[.04,.5])
                    clips.append(entry)
                plot_case(out,preset,pa.with_suffix('.csv'),pb.with_suffix('.csv'),xa,xb,ra['renderedHz'])
        for mode in [0,1]:
            r4,x4,_=reference[(preset,.5,84,4,mode)];_,x16,_=reference[(preset,.5,84,16,mode)]
            tv=variation(window(x4,EARLY),window(x16,EARLY),r4['renderedHz'])
            require(tv<.01,'4x/16x high-register harmonic-shape gate')
            reference_checks.append({'preset':preset,'mode':mode,'midi':84,'harmonicTv':tv,
                                     'scope':'harmonic magnitude shape; not a complete off-harmonic alias-power proof'})
    crest=[p for p in pairs if p['phase']==.5]
    require(sum(max(p['earlyHarmonicTv'],p['lateHarmonicTv'])>=.05 for p in crest)>=2,
            'fewer than two voices reach the preregistered crest contrast floor')
    require(all(max(p['earlyHarmonicTv'],p['lateHarmonicTv'])>=.02 for p in crest),
            'a voice misses the preregistered minimum crest contrast')
    # The measurement must reject the gain-only/null intervention. This is a canary,
    # not a passing assertion that merely restates a successful implementation.
    x=main[('paper-keys',.5,60,4,0)][1];hz=main[('paper-keys',.5,60,4,0)][0]['renderedHz']
    require(variation(window(x,EARLY),window(x,EARLY),hz)==0,'null-intervention canary')
    return {'pairs':pairs,'referenceChecks':reference_checks,'clips':clips,
            'summary':{'pairs':len(pairs),'clips':len(clips),'rawCircuitCases':len(main)+len(reference)+len(silence),
                       'zeroVelocityCases':len(silence),'allPublicPrefixesExact':True,'allStateForksExact':True,
                       'allDeterministicForksPassed':True,'allPcm16FlacDecodesExact':True,
                       'crestMaxTvByPreset':{p['preset']:max(p['earlyHarmonicTv'],p['lateHarmonicTv']) for p in crest},
                       'worstMeasuredPitchCents':max(abs(p['cents']) for row in pairs for p in row['pitch']),
                       'maxReferenceHarmonicTv':max(p['harmonicTv'] for p in reference_checks)}}


def package(out):
    path=out/'crease-release-experiment-wavs.zip'
    with zipfile.ZipFile(path,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for p in sorted((out/'audio').glob('*.wav')):
            z.write(p,'audio/'+p.name)
        for name in ['manifest.json','report.json','README.txt']:
            z.write(out/name,name)
    require(path.stat().st_size<25*1024*1024,'WAV download exceeds hosting file limit')
    with zipfile.ZipFile(path) as z: require(z.testzip() is None,'WAV archive CRC')
    return {'file':path.name,'bytes':path.stat().st_size,'sha256':sha(path.read_bytes()),'zipCrc':'passed'}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,default=ROOT/'testkit/crease-release-experiment')
    parser.add_argument('--analyse-only',action='store_true')
    args=parser.parse_args();out=args.out.resolve();out.mkdir(parents=True,exist_ok=True)
    build=out/'raw';build.mkdir(exist_ok=True)
    started=time.monotonic(); source=git('rev-parse','HEAD')
    source_hashes={p:sha((ROOT/p).read_bytes()) for p in SOURCE_PATHS}
    protocol=Path(__file__).with_name('protocol.md')
    base={'sourceCommit':source,'workingTreeDirty':bool(git('status','--porcelain')),
          'baselineCommit':'fd791f583579dda3dcab3d2ea9e34f9975b07a12',
          'harness':'actual Kotlin Crease circuit via Java reflection; Python/SciPy measurements',
          'productionKotlinChanged':False,'protocolSha256':sha(protocol.read_bytes()),
          'sourceHashes':source_hashes,'generatedAt':datetime.now(timezone.utc).isoformat()}
    try:
        commands,jars=run_harness(out,build) if not args.analyse_only else ([],[])
        measured=analyse(out,build)
        report={**base,'result':'passed','commands':commands,'jarHashes':jars,**measured}
        report['seconds']=time.monotonic()-started
        write_json(out/'report.json',report)
        manifest={**base,'kind':'crease-release-experiment','title':'Key-up: fade or unfold?',
                  'sampleRate':RATE,'keyUpSeconds':1.0,'releaseSeconds':1.2,
                  'presets':[{'id':k,'name':v,'plot':k+'-release.png'} for k,v in PRESET_NAMES.items()],
                  'phases':PHASES,'clips':measured['clips'],'summary':measured['summary'],
                  'limitations':['Offline fixed-key-up experiment; not a playable release-aware instrument.',
                     'Shared linear fade controls amplitude; deformation recovery changes the waveform.',
                     'One second of already-held tone precedes key-up; a common10ms preview fade avoids a cropped-file start click.',
                     'The current phone/export path cannot select a separately evolving release tail at arbitrary key-up.',
                     'No phone/MPC hardware or listening verdict is claimed.']}
        write_json(out/'manifest.json',manifest)
        (out/'README.txt').write_text('CREASE RELEASE EXPERIMENT\n\nActual Kotlin circuit; Java harness and Python measurements. These are fixed-key-up recordings, not release-aware instrument exports.\n\nCompare Fade only and Unfold at the same gesture phase. Both use the same1.2s linear gain fade and a common preset gain. Key-up occurs1.0s into each preview. Tail-matched variants at phase0.5 add one whole-take scalar measured40–500ms after key-up; no dynamic level flattening.\n\nThe WAV archive contains30 mono44.1kHz PCM16 fixed performances. FLAC previews decode to identical PCM.\n')
        archive=package(out);write_json(out/'download.json',archive)
        require(source_hashes=={p:sha((ROOT/p).read_bytes()) for p in SOURCE_PATHS},'source changed during experiment')
        print(json.dumps({'result':'passed','summary':measured['summary'],'seconds':report['seconds'],
                          'wavArchive':archive,'out':str(out)},indent=2),flush=True)
    except Exception as error:
        write_json(out/'failed-report.json',{**base,'result':'failed','error':str(error),
                                           'seconds':time.monotonic()-started})
        raise


if __name__=='__main__':
    main()
