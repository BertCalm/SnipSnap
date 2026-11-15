"""FOOTWORK R0. Python sound proof, not a delivered Kotlin instrument.
Adapted from the repository Luthier proto_template workflow. Shape constants are
proposals. No recordings or full biomechanics simulation. Seeded contact forces
feed damped shoe/floor modes; composite gestures use fresh contact events.
"""
from pathlib import Path
import hashlib, json, sys, time, zipfile
import numpy as np
from scipy import signal
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'.claude/skills/luthier/scripts'))
import snipkit as sk
RATE=44100; FS=RATE*4
VOICES=('toe','heel','stamp','brush','clap','thigh','shuffle','flam')
VELOCITIES=(.30,.65,1.0)

def rng_for(voice,take):
    return np.random.default_rng(int.from_bytes(hashlib.sha256(f'footwork-r0:{voice}:{take}'.encode()).digest()[:8],'little'))

def contact(voice,velocity,take,seconds=.55):
    n=round(seconds*FS);t=np.arange(n)/FS;rng=rng_for(voice,take)
    if velocity==0:return np.zeros(n)
    # Shape hypotheses: metal toe gives a short bright contact; loaded heel
    # excites the floor more strongly. The floor is not a reverb preset.
    settings={'toe':(.00030,2300,.09,.15),'heel':(.0011,1100,.065,.42),
              'stamp':(.006,160,.11,1.0),'brush':(.0005,2400,.045,.08),
              'clap':(.001,1350,.035,0),'thigh':(.003,320,.07,.05)}
    width,hz,decay,floor=settings[voice]
    width*= (1.3-.5*velocity)*rng.uniform(.94,1.06)
    pulse=np.zeros(n);k=max(4,round(width*FS));pulse[:k]=np.sin(np.linspace(0,np.pi,k))**2
    pulse*=velocity**1.15/max(pulse.sum(),1e-12)
    if voice=='brush':
        # R0 limitation: envelope-shaped noise, without a spatial roughness field
        # or signed travel. The owner rejected this sound; retained for comparison.
        duration=.065+.04*(1-velocity);mask=t<duration
        rough=rng.normal(size=n);env=np.zeros(n);env[mask]=np.sin(np.pi*t[mask]/duration)**1.5
        pulse=rough*env*velocity**1.2*.005
    elif voice=='clap':
        # Palm seal and fingers meet at several sub-millisecond offsets.
        pulse=np.zeros(n)
        for at,force in [(0,1),(.003,.6),(.007,.28)]:
            local=np.maximum(t-at,0);env=np.exp(-local/.003)*(t>=at)
            pulse+=rng.normal(size=n)*env*force*.006*velocity**1.25
    modes=[(1,1,decay),(1.43,.62,decay*.65),(2.29,.34,decay*.45),(3.11,.22,decay*.3)]
    modes=[(ratio,gain*rng.uniform(.85,1.15)*(ratio**((velocity-1)*.7)),d) for ratio,gain,d in modes]
    body=sk.modal_ring(pulse,hz*rng.uniform(.985,1.015),modes,FS,compensate=True)
    floor_modes=[(1,1,.24),(1.62,.50,.16),(2.91,.25,.095),(4.15,.12,.055)]
    body+=floor*sk.modal_ring(pulse,78*rng.uniform(.98,1.02),floor_modes,FS,compensate=True)
    # Short friction/skin transient, coupled to force and contact duration.
    noise=rng.normal(size=n);noise=signal.sosfilt(signal.butter(2,[350,11000],fs=FS,btype='bandpass',output='sos'),noise)
    body+=noise*np.exp(-t/max(.0015,width*1.6))*.002*velocity**1.4
    return body

def raw(voice,velocity,take):
    if voice in ('shuffle','flam'):
        plan=[('brush',0,.8),('brush',.095,1)] if voice=='shuffle' else [('toe',0,.52),('heel',.035,1)]
        out=np.zeros(round(.8*FS))
        for i,(v,at,force) in enumerate(plan):
            hit=contact(v,velocity*force,take*7+i);start=round(at*FS)
            out[start:start+len(hit)]+=hit
        return out
    return contact(voice,velocity,take)

def render(voice,velocity=1.,take=0):
    assert voice in VOICES and np.isfinite(velocity) and 0<=velocity<=1 and 0<=take<4
    x=raw(voice,velocity,take)
    # Fixed gain shared by all velocities/takes; never peak-match each hit.
    gain={'toe':.482985,'heel':.719795,'stamp':.443728,'brush':2.320739,'clap':4.259127,'thigh':.738615,'shuffle':1.993449,'flam':.714402}[voice]
    return sk.fade_tail(sk.dc_block(sk.decimate4(sk.band_limit(x,FS)),RATE)*gain,RATE)

def main(out):
    out=Path(out);out.mkdir(parents=True,exist_ok=True);audio=out/'audio';audio.mkdir(exist_ok=True)
    manifest={'status':'Python R0 sound proof; delivery gates open','voices':[],'checks':{},'files':{}}
    grids={};start=time.perf_counter();max_dc=0;max_peak=0
    for v in VOICES:
        grid=[[render(v,z,t) for t in range(4)] for z in VELOCITIES];grids[v]=grid
        peaks=[max(abs(x)) for row in grid for x in row]
        assert max(peaks)<.95,(v,max(peaks))
        for row in grid:
            assert all(np.isfinite(x).all() and x[-1]==0 for x in row)
            assert all(np.linalg.norm(row[0]/np.linalg.norm(row[0])-x/np.linalg.norm(x))>.005 for x in row[1:])
        for take in range(4):
            p=[np.max(abs(row[take])) for row in grid];assert p[0]<p[1]<p[2],(v,p)
            a,b=grid[0][take],grid[2][take]
            assert np.linalg.norm(a/np.linalg.norm(a)-b/np.linalg.norm(b))>.005,(v,'gain-only velocity')
        for z,row in zip(VELOCITIES,grid):
            for take,x in enumerate(row):assert np.array_equal(x,render(v,z,take))
        assert not np.any(render(v,0,0))
        max_dc=max(max_dc,max(abs(x.mean()) for row in grid for x in row));max_peak=max(max_peak,max(peaks))
        for z,row in enumerate(grid):
            for take,x in enumerate(row):sk.write_wav(str(audio/f'{v}-z{z}-t{take}.wav'),x)
        touch=np.concatenate([np.concatenate([x,np.zeros(round(.16*RATE))]) for row in grid for x in row])
        sk.write_wav(str(audio/f'{v}-touch.wav'),touch)
        manifest['voices'].append({'id':v,'solo':f'{v}-z2-t0.wav','touch':f'{v}-touch.wav'})
    assert max_dc<.005,max_dc
    # Core articulations must differ before their mix gains are heard.
    for a,b in [('toe','heel'),('heel','stamp'),('brush','clap')]:
        x,y=grids[a][2][0],grids[b][2][0]
        assert abs(np.dot(x,y)/(np.linalg.norm(x)*np.linalg.norm(y)))<.90,(a,b,'duplicate shape')
    # Original dance phrase: alternating feet, double brushes and body answers.
    events=[]
    for bar in range(4):
        base=bar*4
        events += [('stamp',base,1),('toe',base+.5,.65),('heel',base+.75,.5),('clap',base+1,.8),('shuffle',base+1.5,.65),('stamp',base+2,.65),('toe',base+2.5,.65),('thigh',base+3,.8),('flam',base+3.5,.65)]
    bpm=112;phrase=np.zeros(round((16*60/bpm+1)*RATE));counters={}
    for voice,beat,vel in events:
        take=counters.get(voice,0)%4;counters[voice]=take+1
        x=render(voice,vel,take);at=round(beat*60/bpm*RATE);phrase[at:at+len(x)]+=x*.7
    assert max(abs(phrase))<.98,max(abs(phrase))
    sk.write_wav(str(audio/'phrase.wav'),phrase)
    manifest['checks']={'gridCells':96,'finite':True,'zeroVelocity':True,'distinctTakes':True,'timbralVelocity':True,'deterministicReplay':True,'maxPeak':float(max_peak),'maxAbsDc':float(max_dc),'seconds':time.perf_counter()-start}
    for p in audio.glob('*.wav'):manifest['files'][p.name]=hashlib.sha256(p.read_bytes()).hexdigest()
    (out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    buttons=''.join(f'<section><h2>{v.title()}</h2><button data-file="{v}-z2-t0.wav">Single hit</button> <button data-file="{v}-touch.wav">3 velocities × 4 takes</button></section>' for v in VOICES)
    (out/'index.html').write_text('''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Footwork · First sound proof</title><style>body{font:17px system-ui;background:#171610;color:#f5eddb;max-width:900px;margin:auto;padding:30px}h1{font-size:3rem}button{font:inherit;padding:14px;background:#e8b25b;color:#18150d;border:0;border-radius:7px;cursor:pointer}section{display:inline-block;min-width:220px;padding:12px;border-bottom:1px solid #695c43}audio{width:100%;position:sticky;top:0}p{line-height:1.6}</style><h1>FOOTWORK</h1><p>Toe → heel → brush → stamp → body answer. Sample-free Python sound proof. No source recordings. These eight gesture candidates are not the completed three-kit instrument.</p><button data-file="phrase.wav">Play original dance phrase · 112 BPM</button><p id="now" aria-live="polite">Choose a voice.</p><audio id="audio" controls preload="none"></audio>'''+buttons+'''<p>All 96 prototype cells have deterministic, distinct repeats and timbral velocity changes. Shelf integration, authored roll/riser, native saving/playback, SFZ/MPC export and device acceptance remain open. Fixed voice gains preserve dynamics; quiet voices are not individually normalized.</p><script>const a=document.getElementById('audio'),n=document.getElementById('now');let id=0;for(const b of document.querySelectorAll('button'))b.onclick=()=>{const request=++id;a.pause();a.src='audio/'+b.dataset.file;n.textContent=b.dataset.file;a.play().catch(()=>{if(request===id)n.textContent='Press play in the audio controls.'})};a.onerror=()=>n.textContent='Audio could not load.';</script></html>''')
    with zipfile.ZipFile(out/'footwork-first-audition.zip','w',zipfile.ZIP_DEFLATED) as z:
        for p in [out/'index.html',out/'manifest.json',*audio.glob('*.wav')]:z.write(p,p.relative_to(out))
    print(json.dumps(manifest['checks'],indent=2))
if __name__=='__main__':main(sys.argv[1])
