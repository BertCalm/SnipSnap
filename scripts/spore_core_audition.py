"""Package the accepted Kotlin snare and the new Python kick, keeping their status explicit."""
import argparse
import gzip
import hashlib
import json
import sys
import wave
import zipfile
from pathlib import Path
import numpy as np
from scipy.io import wavfile
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts/spore_kick_study'))
import model as kick
RATE=44100

def read(path):
    rate,x=wavfile.read(path);assert rate==RATE
    x=x.astype(float)/(np.iinfo(x.dtype).max+1.)
    return x.mean(axis=1) if x.ndim>1 else x

def write(path,x):
    assert np.isfinite(x).all() and max(abs(x),default=0)<1
    pcm=np.round(x*8388607).astype(np.int32)
    b=np.column_stack([pcm&255,(pcm>>8)&255,(pcm>>16)&255]).astype('uint8').tobytes()
    with wave.open(str(path),'wb') as w:
        w.setnchannels(1);w.setsampwidth(3);w.setframerate(RATE);w.writeframes(b)

def repeated(x):
    y=np.zeros(round(1.15*RATE));y[:len(x)]+=x;y[round(.65*RATE):round(.65*RATE)+len(x)]+=x
    return y

def main():
    p=argparse.ArgumentParser();p.add_argument('--snare',type=Path,required=True);p.add_argument('--kick',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    a.output.mkdir(parents=True,exist_ok=True);audio=a.output/'audio';audio.mkdir(exist_ok=True)
    km=json.loads((a.kick/'model.json').read_text())
    snare=[[read(a.snare/f'audio/snare-z{z}-t{t}.wav') for t in range(3)] for z in range(3)]
    kicks=[[kick.render(km,velocity=v,take=t) for t in range(3)] for v in [.3,.65,1.]]
    python=np.frombuffer(gzip.decompress((ROOT/'synth/src/test/resources/spore-snare-approved/z2-t0.f32.gz').read_bytes()),dtype='<f4').astype(float)
    reference=read(a.kick/'reference-crop.wav');reference=np.pad(reference,(0,len(python)-len(reference)))
    old=read(a.kick/'previous.wav')
    sources={'python-snare':python,'kotlin-snare':snare[2][0],'kick-reference':reference,'kick-study':kicks[2][0],'kick-previous':old}
    gains={k:.12/np.sqrt(np.mean(x[:round(.12*RATE)]**2)) for k,x in sources.items()}
    safe=min(1.,.90/max(max(abs(x))*gains[k] for k,x in sources.items()));gains={k:v*safe for k,v in gains.items()}
    clips=[]
    def add(id,title,group,x,g):
        write(audio/(id+'.wav'),x);write(audio/(id+'-matched.wav'),x*g)
        clips.append(dict(id=id,title=title,group=group,raw=f'audio/{id}.wav',matched=f'audio/{id}-matched.wav'))
    for id,title,group in [('python-snare','Approved Python snare','snare'),('kotlin-snare','Kotlin snare port','snare'),
        ('kick-reference','Your live kick reference','kick'),('kick-study','New synthesized kick','kick'),('kick-previous','Rejected kick prototype','kick')]:
        add(id,title,group,repeated(sources[id]),gains[id])
    for z,name in enumerate(['soft','medium','hard']):
        for voice,grid in [('snare',snare),('kick',kicks)]:
            x=np.zeros(round(1.8*RATE))
            for t in range(3):
                at=round(t*.6*RATE);x[at:at+len(grid[z][t])]+=grid[z][t]
            add(f'{voice}-{name}',f'{voice.capitalize()} · {name} · three takes','touch',x,gains['kotlin-snare' if voice=='snare' else 'kick-study'])
    notes=[('kick',b,z) for b,z in [(0,2),(.75,1),(2,2),(2.5,1),(4,2),(4.5,1),(6,2),(6.75,1)]]
    notes += [('snare',b,z) for b,z in [(1,2),(1.75,0),(2.75,0),(3,2),(3.5,0),(5,2),(5.75,0),(6.5,1),(7,2),(7.5,0)]]
    phrase=np.zeros(round((8*60/170+.5)*RATE));count={(v,z):0 for v in ['kick','snare'] for z in range(3)}
    for voice,beat,z in sorted(notes,key=lambda e:e[1]):
        t=count[voice,z]%3;count[voice,z]+=1
        hit=(kicks if voice=='kick' else snare)[z][t]*gains['kick-study' if voice=='kick' else 'kotlin-snare']
        at=round(beat*60/170*RATE);phrase[at:at+len(hit)]+=hit
    phrase*=min(1.,.9/max(abs(phrase)))
    add('both','Kick + approved snare · 170 BPM','phrase',phrase,1.)
    add('native','SnipSnap saved-kit playback · all nine snare takes','native',read(a.snare/'audio/native-playback.wav'),gains['kotlin-snare'])
    for filename,source in [('spore-approved-snare.zip',a.snare/'spore-approved-snare.zip'),('snare-delivery.json',a.snare/'delivery.json'),('snare-parity.json',a.snare/'parity.json'),('kick-report.json',a.kick/'report.json'),('kick-model.json',a.kick/'model.json')]:
        (a.output/filename).write_bytes(source.read_bytes())
    with zipfile.ZipFile(a.output/'kick-study-wavs.zip','w',zipfile.ZIP_DEFLATED) as z:
        for zone in range(3):
            for take in range(3):
                f=a.kick/f'kick-z{zone}-t{take}.wav';write(f,kicks[zone][take]);z.write(f,f.name)
        z.write(a.kick/'model.json','model.json')
        z.writestr('README.txt','Unapproved Python synthesized kick study. Three velocities x three takes. WAVs only, no sampler mapping or reference audio.\n')
    manifest=dict(status='snare-approved-kotlin-parity-kick-awaiting-listen',clips=clips,
        modelSha256=json.loads((a.snare/'delivery.json').read_text())['modelSha256'],
        levelMatch='First 120 ms RMS per source; fixed candidate gains across velocities and takes',
        audioHashes={str(f.relative_to(a.output)):hashlib.sha256(f.read_bytes()).hexdigest() for f in audio.glob('*.wav')})
    (a.output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    html=(ROOT/'scripts/spore_core_audition.html').read_text();(a.output/'index.html').write_text(html)
    (a.output/'design.md').write_bytes((ROOT/'docs/superpowers/specs/2026-11-14-spore-approved-port-and-kick.md').read_bytes())
    local=html.replace("fetch('manifest.json').then(r=>{if(!r.ok)throw Error('manifest');return r.json()})",'Promise.resolve('+json.dumps(manifest)+')')
    local=local.replace('href="../"','href="https://spore-station-jungle-audition.bertcalm.chatgpt.site/"')
    with zipfile.ZipFile(a.output/'spore-core-offline.zip','w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('index.html',local)
        for f in sorted(a.output.rglob('*')):
            if f.is_file() and f.name not in ['index.html','spore-core-offline.zip']:z.write(f,str(f.relative_to(a.output)))
    print('Packaged',len(clips),'clips, native/SFZ/MPC3 snare and separate kick WAV study.')
if __name__=='__main__':main()
