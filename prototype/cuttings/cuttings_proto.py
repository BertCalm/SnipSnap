"""CUTTINGS R4. Adapted from Luthier proto_template.py; Python, not Kotlin.
Original imaginary recording scenes. No external audio assets.
Render: python3 prototype/cuttings/cuttings_proto.py /absolute/output
"""
from __future__ import annotations
import hashlib
import itertools
import json
from pathlib import Path
import sys
import time
import wave
import numpy as np
from scipy import signal

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / '.claude/skills/luthier/scripts'))
import snipkit as sk
import probes as pr

RATE = 44100
INTERNAL = RATE * 4
VERSION = 'cuttings-r4-sampler-color-1'
SOURCE_VERSION = 'cuttings-r3-pocket-finds-1'
SEED_VERSION = 'cuttings-r1-original-scenes-1'  # preserve primary performances for A/B
CORE = ('kick', 'snare', 'hat', 'wood')
EXTRA = ('thump','brush','openhat','clap','rim','hand','shaker','metal','impact','cymbal')
PHRASES = ('roll','riser')
VOICES = CORE + EXTRA + PHRASES
STAGES = ('source', 'splice', 'clean', 'finished')
NAMES = {'kick': 'Pocket / bass shadow', 'snare': 'Backbeat / borrowed room',
         'hat': 'Hat / tambourine catch', 'wood': 'Wood / library metal'}
# Bounds and constants are artistic hypotheses, not measured acoustic models.
DURATIONS = {'kick': .57, 'snare': .48, 'hat': .22, 'wood': .42}
GAINS = {'kick': .73, 'snare': .66, 'hat': .59, 'wood': .60}
NAMES.update(dict(zip(EXTRA+PHRASES, ('Trapdoor / felt thump','Paper / brush cut',
    'Loose change / open hat','Double exposure / clap','Knuckle / rim cut',
    'Skin pocket / hand drum','Matchbox / shaker','Bent coin / metal',
    'Floor drop / low impact','Flash / cymbal catch','Splice roll','Closing credits / rising fill'))))
DURATIONS.update(dict(zip(EXTRA,(.48,.42,.55,.47,.30,.48,.28,.62,.68,.60))))
GAINS.update({v:.58 for v in EXTRA})
# Distinct physical exciters and cut gestures, not copies with effect changes.
PROFILES = {
 'thump': (72,[1,1.55,2.6],.075,2100,.12,.034,.23,'bass'),
 'brush': (193,[1,1.52,2.3],.032,5100,.70,.063,.25,'paper'),
 'openhat': (2011,[1,1.37,1.89,2.63],.11,8500,.20,.071,.37,'jingle'),
 'clap': (640,[1,1.31,2.1],.018,4100,.90,.046,.24,'clap'),
 'rim': (823,[1,1.71,2.44],.021,6100,.10,.023,.13,'wood'),
 'hand': (238,[1,1.47,1.98,2.77],.085,3800,.16,.041,.29,'skin'),
 'shaker': (1570,[1,1.67],.014,6500,.85,.022,.17,'shaker'),
 'metal': (683,[1,1.41,2.13,3.05],.13,6200,.05,.048,.39,'coin'),
 'impact': (103,[1,1.59,2.11],.16,2900,.14,.074,.45,'floor'),
 'cymbal': (1373,[1,1.31,1.73,2.11,2.71,3.47],.16,10000,.30,.061,.35,'flash')}



def filt(x, hz, rate, kind='lowpass', order=2):
    return signal.sosfilt(signal.butter(order, hz, btype=kind, fs=rate, output='sos'), x)


def room(x, rate, size, amount, seed):
    """Finite early-reflection field; separate rooms survive source replacement."""
    rng = np.random.default_rng(seed)
    out = x.copy()
    for i, delay in enumerate((.009, .017, .031, .047, .073, .101, .139)):
        k = int(delay * size * rate)
        if k >= len(x):
            continue
        out[k:] += x[:-k] * amount * (.76 ** i) * rng.choice([-1, 1])
    return out


def modes(t, base, ratios, decay, rng, velocity):
    """Different strike positions weight modes differently for every take."""
    position = rng.uniform(.18, .43)
    y = np.zeros_like(t)
    for i, ratio in enumerate(ratios):
        weight = np.sin(np.pi * position * (i + 1))
        weight *= (0.45 + velocity * .6) ** (i * .45)
        y += weight / (1 + i * .7) * np.sin(2*np.pi*base*ratio*t) * np.exp(-t/(decay/(1+i*.3)))
    return y


def found_scene(voice,dynamic,take,secondary,rate):
    rng=np.random.default_rng(sk.seed_for(SOURCE_VERSION,voice,dynamic,take,secondary))
    v=(.40,.69,1.)[dynamic]
    base,ratios,decay,cutoff,noisiness,seam,end,gesture=PROFILES[voice]
    t=np.arange(int(DURATIONS[voice]*rate))/rate
    noise=rng.normal(0,1,len(t))
    attack=1-np.exp(-t/(.0004+.0018*(1-v)))
    body=modes(t,base*rng.uniform(.98,1.02),ratios,decay*rng.uniform(.87,1.13),rng,v)
    contact=filt(noise,cutoff,rate)
    if gesture in ('jingle','shaker','flash'):contact=filt(contact,1800,rate,'highpass')
    if gesture in ('paper','clap'):contact=filt(contact,750,rate,'highpass')
    envelope=np.exp(-t/(decay*(.6+.65*v)))
    if gesture=='paper':envelope*=.5+.5*np.sin(2*np.pi*43*t)**2
    if gesture=='shaker':envelope*=np.exp(-((t-.018)/.035)**2)
    hit=((1-noisiness)*.72*body+noisiness*contact*1.7*envelope)*attack
    if gesture=='clap':
        for at in (.009,.021):
            k=int(at*rate);hit[k:]+=.32*contact[:-k]*np.exp(-t[:-k]/.020)
    context=np.zeros_like(t)
    if secondary:
        u=np.maximum(0,t-seam)
        env=(t>seam)*(1-np.exp(-u/.001))*np.exp(-u/(decay*.7+.025))
        if gesture in ('bass','floor'):
            phase=2*np.pi*((47 if gesture=='floor' else 98)*u+2*(1-np.exp(-u/.03)))
            context=.27*env*(np.sin(phase)+.22*np.sin(phase*2))
            hit=filt(hit,1200,rate)*.6
        elif gesture in ('paper','shaker'):
            # A second little scrape/paper tear is cropped out of its own scene.
            context=.5*env*filt(contact,1900 if gesture=='paper' else 4300,rate)
            context*=.6+.4*np.sin(2*np.pi*(69 if gesture=='paper' else 101)*u)
            hit*=.5
        elif gesture in ('jingle','coin','flash'):
            freq={'jingle':3071,'coin':431,'flash':977}[gesture]
            phase=2*np.pi*(freq*u+freq*.013*(1-np.exp(-u/.027)))
            context=.21*env*(np.sin(phase)+.42*np.sin(phase*1.43))
            if gesture=='flash':context*=np.clip((end-t)/.06,0,1)
            hit*=.52
        elif gesture=='clap':
            context=.55*env*filt(contact,2200,rate)
            hit=room(hit,rate,1.8,.32,sk.seed_for(voice,take,'stairwell'))
        elif gesture=='wood':
            context=.25*env*(np.sin(2*np.pi*391*u)+.35*np.sin(2*np.pi*863*u))
            hit*=.45
        elif gesture=='skin':
            phase=2*np.pi*(179*u+29*.022*(1-np.exp(-u/.022)))
            context=.27*env*(np.sin(phase)+.22*np.sin(phase*1.52))
            hit*=.6
    hit=room(hit,rate,1.2 if secondary else .55,.18 if secondary else .06,sk.seed_for(voice,take,secondary))
    return filt(hit,cutoff if not secondary else cutoff*.66,rate)*(.38+.62*v),context*(.4+.6*v)


def phrase(voice,dynamic,take,rate,context):
    # A finite, one-bar composed articulation at a persisted tempo, not a cloud
    # of randomly stacked hits. Alternate source performances preserve rhythm.
    beat=60/89; duration=4*beat+.32
    stages={s:np.zeros(round(duration*RATE)) for s in STAGES}
    if voice=='roll':
        events=[(i/4,'rim' if i%3 else 'snare',.54+.02*i) for i in range(12)]
        events += [(3.0,'wood',.75),(3.5,'rim',.57)]
    else:
        events=[(0,'hand',.48),(1,'hand',.52),(1.5,'rim',.53),(2,'rim',.56),
                (2.5,'rim',.60),(2.75,'snare',.61),(3,'snare',.65),
                (3.25,'snare',.69),(3.5,'snare',.73),(3.625,'snare',.75),
                (3.75,'wood',.78)]
    for i,(at,source,gain) in enumerate(events):
        data=render(source,dynamic,(take+i)%4,rate,context)
        k=round(at*beat*RATE)
        for stage,y in data.items():
            n=min(len(y),len(stages[stage])-k)
            stages[stage][k:k+n]+=y[:n]*gain
    for y in stages.values():y[-441:]*=np.linspace(1,0,441)
    return stages

def scene(voice, dynamic, take, secondary=False, rate=INTERNAL):
    if voice in EXTRA:return found_scene(voice,dynamic,take,secondary,rate)
    rng = np.random.default_rng(sk.seed_for(SEED_VERSION, voice, dynamic, take, secondary))
    v = (.40, .69, 1.0)[dynamic]
    t = np.arange(int(DURATIONS[voice]*rate)) / rate
    noise = rng.normal(0, 1, len(t))
    attack = 1-np.exp(-t/(.00045+.001*(1-v)))
    stretch = rng.uniform(.91, 1.10)
    context = np.zeros_like(t)
    if voice == 'kick':
        f = (66 if secondary else 53) * rng.uniform(.985, 1.015)
        # Integrated downward membrane bend, not a fixed sine oscillator.
        phase = 2*np.pi*(f*t + (30+22*v)*.019*(1-np.exp(-t/.019)))
        body = np.sin(phase)*np.exp(-t/(.095*stretch))
        skin = modes(t, 147, [1, 1.59, 2.14], .026, rng, v)
        hit = .84*body + (.10+.18*v)*skin
        hit += .65*v*filt(noise, 2300+1800*v, rate)*np.exp(-t/.004)
        # Muted bass pickup belongs to the scene and follows the hit's dynamic.
        u = np.maximum(0, t-.051)
        context = .105*(t>.051)*(np.sin(2*np.pi*82.41*u)+.24*np.sin(2*np.pi*164.82*u))*np.exp(-u/.105)
    elif voice == 'snare':
        hit = modes(t, 176 if secondary else 204, [1,1.48,2.18,3.1,4.4], .042*stretch, rng, v)*.63
        wires = filt(filt(noise, 1000, rate, 'highpass'), 4700+3300*v, rate)
        hit += wires*(.48+.50*v)*np.exp(-t/(.055+.035*v))*attack
        if secondary:
            # Two imperfect handclaps recorded in the second room, not extra hiss.
            for offset in (.013+rng.uniform(-.002,.002), .027+rng.uniform(-.003,.003)):
                u = np.maximum(0,t-offset)
                hit += .56*wires*(t>offset)*(1-np.exp(-u/.0008))*np.exp(-u/.025)
        context = .025*np.sin(2*np.pi*392*t)*np.exp(-t/.12)
    elif voice == 'hat':
        base = 1830 if secondary else 2540
        # Inharmonic partials plus stick contact and separate delayed jingles.
        hit = modes(t, base, [1,1.273,1.853,2.417,3.031], .022*stretch, rng, v)*.36
        hit += filt(noise, 4300-900*v, rate, 'highpass')*.22*np.exp(-t/(.019+.012*v))
        if secondary:
            for delay in (.012,.028,.041):
                u=np.maximum(0,t-delay)
                context += .08*(t>delay)*(np.sin(2*np.pi*3911*u)+np.sin(2*np.pi*6107*u))*np.exp(-u/.018)
    else:
        base = 478 if secondary else 352
        hit = modes(t, base, [1,1.51,2.32,3.76], .058*stretch, rng, v)*.63
        hit += .28*filt(noise, 3600, rate)*np.exp(-t/.003)
        if secondary:
            u=np.maximum(0,t-.033)
            context = .16*(t>.033)*(np.sin(2*np.pi*702*u)+.35*np.sin(2*np.pi*1137*u))*np.exp(-u/.090)
    if secondary:
        # R2: authored, role-specific finds. Distinction comes from source
        # articulation and time edits, before the common finish stage.
        if voice == 'kick':
            u=np.maximum(0,t-.047)
            env=(t>.047)*(1-np.exp(-u/.0015))*np.exp(-u/.065)
            phase=2*np.pi*(82.41*u+105*.012*(1-np.exp(-u/.012)))
            bass=np.sin(phase)+.35*np.sin(2*phase)+.16*np.sin(3*phase)
            context=.33*env*bass
            # Tiny second muted-string catch is an intentional source fragment.
            w=np.maximum(0,t-.132)
            context+=.11*(t>.132)*(1-np.exp(-w/.001))*np.exp(-w/.022)*np.sin(2*np.pi*164.82*w)
        elif voice == 'snare':
            # A late, band-limited handclap blooms in a different room.
            u=np.maximum(0,t-.045)
            cl=filt(filt(noise,700,rate,'highpass'),2900,rate)
            hit=.30*hit+.95*cl*(t>.045)*(1-np.exp(-u/.001))*np.exp(-u/.052)
            context=.16*(t>.047)*(1-np.exp(-u/.002))*np.exp(-u/.065)*(np.sin(2*np.pi*311*u)+.5*np.sin(2*np.pi*466*u))
        elif voice == 'hat':
            # Three descending jingle catches, then a decisive crop.
            context=np.zeros_like(t)
            for delay,freq,gain in ((.014,5100,.24),(.041,3800,.20),(.077,2750,.16)):
                u=np.maximum(0,t-delay)
                env=(t>delay)*(1-np.exp(-u/.0007))*np.exp(-u/.011)
                context+=gain*env*(np.sin(2*np.pi*freq*u)+.6*np.sin(2*np.pi*freq*1.413*u))
            hit*=.55
        else:
            # A reversed metal pickup folds into a lower damped bell cut.
            u=np.maximum(0,t-.083)
            reverse=np.exp(-np.abs(t-.079)/.016)*(t<.083)
            context=.23*reverse*(np.sin(2*np.pi*1223*t)+.4*np.sin(2*np.pi*1793*t))
            context+=.30*(t>.083)*(1-np.exp(-u/.001))*np.exp(-u/.060)*(np.sin(2*np.pi*523*u)+.45*np.sin(2*np.pi*831*u))
            hit*=.45
    hit *= attack * (.42+.58*v)
    context *= .55+.45*v
    hit = room(hit, rate, 1.45 if secondary else .48, .23 if secondary else .08,
               sk.seed_for(voice, take, secondary, 'room'))
    # Source recording color is fixed per scene, not a global vinyl overlay.
    cutoff = (5400 if secondary else 10800) if voice != 'kick' else (2700 if secondary else 6800)
    hit = filt(hit, cutoff, rate)
    context = filt(context, min(cutoff, 4600), rate)
    return hit, context


def sampler_color(x,rate,voice,dynamic,take):
    """Generic early-sampler approximation, not a named hardware model.
    Bandlimit before 22.05 kHz conversion; 12-bit TPDF quantization; mild
    converter compression. Parallel blend retains attack and upper detail.
    """
    rng=np.random.default_rng(sk.seed_for(VERSION,voice,dynamic,take,'converter'))
    band=filt(x,7800,rate)
    low=signal.resample_poly(band,1,rate//22050)
    low=np.tanh(low*1.65)/1.65
    step=1/2048
    dither=(rng.random(len(low))-rng.random(len(low)))*step
    low=np.round((low+dither)/step)*step
    returned=signal.resample_poly(low,rate//22050,1)[:len(x)]
    return .50*x+.50*returned


def render(voice, dynamic, take, rate=INTERNAL, context=True):
    """All comparison stages share performances. Only the cut/finish changes."""
    if voice in PHRASES:return phrase(voice,dynamic,take,rate,context)
    a, ac = scene(voice,dynamic,take,False,rate)
    b, bc = scene(voice,dynamic,take,True,rate)
    if not context:
        ac=np.zeros_like(ac); bc=np.zeros_like(bc)
    t=np.arange(len(a))/rate
    start=PROFILES[voice][5] if voice in EXTRA else {'kick':.052,'snare':.027,'hat':.010,'wood':.022}[voice]
    # Smooth 3 ms insertion: onset stays from A; B owns part of the tail.
    gate=np.clip((t-start)/.003,0,1)
    gate=.5-.5*np.cos(np.pi*gate)
    amount=.85 if voice in EXTRA else {'kick':.60,'snare':.94,'hat':.88,'wood':.91}[voice]
    source=a+ac
    cut=a*(1-amount*gate)+b*amount*gate+ac*(1-gate)*.5+bc*gate
    # Slightly shortened crop makes the borrowed room end decisively.
    end=PROFILES[voice][6] if voice in EXTRA else {'kick':.38,'snare':.285,'hat':.114,'wood':.242}[voice]
    crop=np.clip((end-t)/.006,0,1)
    if voice=='snare':
        # A short smooth room dropout exposes the edit without a digital click.
        dip=np.clip((t-.029)/.002,0,1)*np.clip((.043-t)/.002,0,1)
        cut*=1-.92*dip
    cut*=crop
    clean=filt(np.tanh(cut*1.35)/1.35, 9300,rate)
    finished=sampler_color(clean,rate,voice,dynamic,take)
    stages={}
    for name,x in zip(STAGES,(source,cut,clean,finished)):
        # Use the template's anti-alias/decimate/DC stages, but never normalize
        # individual dynamics or takes: that would erase performance levels.
        y=signal.resample_poly(x,1,rate//RATE)
        y=sk.dc_block(y)
        y*=GAINS[voice]
        y[:44]*=np.linspace(0,1,44)
        y[-441:]*=np.linspace(1,0,441)
        stages[name]=y
    return stages


def pcm(x):
    assert np.isfinite(x).all() and np.max(np.abs(x))<.99
    return np.round(x*32767).astype('<i2').tobytes()


def write(path,x):
    raw=pcm(x)
    with wave.open(str(path),'wb') as f:
        f.setnchannels(1); f.setsampwidth(2); f.setframerate(RATE); f.writeframes(raw)
    return hashlib.sha256(raw).hexdigest()


def groove(bank, stage):
    # Two-bar pocket repeated with new takes; musical timing authored, not jittered.
    bpm=89; step=60/bpm/4
    y=np.zeros(int((64*step+.7)*RATE)); counts={v:0 for v in VOICES}
    events=[]
    for bar in range(4):
        for s,d in [(0,2),(6,1),(10,2),(14,0)]: events.append((bar*16+s,'kick',d,0))
        for s,d in [(4,2),(12,2),(11,0)]: events.append((bar*16+s,'snare',d,.008 if s!=11 else -.012))
        for s in range(0,16,2): events.append((bar*16+s,'hat',1 if s%4==0 else 0,.017 if s%4 else 0))
        for s in (7,15): events.append((bar*16+s,'wood' if bar%2==0 else 'hand',1,.012))
        for s in (3,11): events.append((bar*16+s,'shaker',0,.010))
        if bar%2:events.append((bar*16+14,'openhat',1,.006))
        if bar==2:events.append((bar*16,'impact',1,0))
        if bar==3:events.append((bar*16+15,'metal',1,.004))
    for pos,voice,d,offset in sorted(events):
        take=counts[voice]%4; counts[voice]+=1
        x=bank[(voice,d,take)][stage]
        k=max(0,round((pos*step+offset)*RATE))
        gain={'kick':.88,'snare':.80,'hat':.53,'wood':.48,'hand':.57,'shaker':.35,'openhat':.39,'impact':.40,'metal':.40}[voice]
        y[k:k+len(x)]+=x*gain
    return y*.76


def main(out_dir):
    out=Path(out_dir); audio=out/'audio'; audio.mkdir(parents=True,exist_ok=True)
    bank={}; entries=[]; elapsed=[]
    for voice,d,take in itertools.product(VOICES,range(3),range(4)):
        start=time.perf_counter(); stages=render(voice,d,take); elapsed.append((time.perf_counter()-start)*1000)
        bank[(voice,d,take)]=stages
        for stage,y in stages.items():
            name=f'{voice}_v{d}_r{take}_{stage}.wav'
            digest=write(audio/name,y)
            entries.append({'voice':voice,'dynamic':d,'take':take,'stage':stage,'file':'audio/'+name,
                            'frames':len(y),'pcm_sha256':digest,'peak':pr.peak(y),'dc':pr.dc(y),'rms':pr.rms(y)})
    # A/B gains are per voice and stage, calibrated to the middle dynamic across
    # all takes. Playback retains the same gain at other dynamics, preserving velocity.
    match={}
    for voice in VOICES:
        target=np.mean([pr.rms(bank[(voice,1,r)]['finished']) for r in range(4)])
        match[voice]={s:float(target/np.mean([pr.rms(bank[(voice,1,r)][s]) for r in range(4)])) for s in STAGES}
    grooves={}
    for stage in STAGES:
        y=groove(bank,stage)
        name=f'groove_{stage}.wav'; write(audio/name,y)
        grooves[stage]={'file':'audio/'+name,'rms':pr.rms(y)}
    contextless={key:render(*key,context=False) for key in bank}
    for stage in STAGES:
        y=groove(contextless,stage)
        name=f'groove_{stage}_no_context.wav'; write(audio/name,y)
        grooves[stage+'_no_context']={'file':'audio/'+name,'rms':pr.rms(y)}
    target=grooves['finished']['rms']
    for s in grooves: grooves[s]['gain']=target/grooves[s]['rms']
    manifest={'version':VERSION,'rate':RATE,'bpm':89,'names':NAMES,'voices':list(VOICES),
              'phraseBars':1,'phraseVoices':list(PHRASES),'stages':list(STAGES),'entries':entries,'comparisonGain':match,'grooves':grooves,
              'source':'Original procedural imaginary sessions; no sampled records.',
              'status':'Python R4 audition. App, save/load, MPC export gates remain open.'}
    (out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    # Saved manifest and WAVs must describe precisely the rendered grid.
    restored=json.loads((out/'manifest.json').read_text())
    for e in restored['entries']:
        with wave.open(str(out/e['file']),'rb') as f:
            assert f.getframerate()==RATE and f.getnchannels()==1 and f.getnframes()==e['frames']
            assert hashlib.sha256(f.readframes(f.getnframes())).hexdigest()==e['pcm_sha256']
    metrics={}
    for voice in VOICES:
        layers=[]; rr=[]; timbre=[]
        for d in range(3):
            xs=[bank[(voice,d,r)]['finished'] for r in range(4)]
            assert len({hashlib.sha256(pcm(x)).hexdigest() for x in xs})==4
            corr=[float(np.corrcoef(a,b)[0,1]) for a,b in itertools.combinations(xs,2)]
            assert max(corr)<.999, (voice,d,corr)
            rr.append(max(corr)); layers.append(float(np.mean([pr.rms(x) for x in xs])))
        assert layers[0]<layers[1]<layers[2], (voice,layers)
        for d in (0,1):
            a=bank[(voice,d,0)]['finished']; b=bank[(voice,d+1,0)]['finished']
            timbre.append(float(pr.macro_activity(a/max(pr.rms(a),1e-9)*.1,b/max(pr.rms(b),1e-9)*.1)))
        assert min(timbre)>.5, (voice,timbre)
        # Re-render from deserialized identities: deterministic byte equality.
        check=render(voice,1,2)
        assert all(pcm(check[s])==pcm(bank[(voice,1,2)][s]) for s in STAGES)
        metrics[voice]={'velocity_rms':layers,'max_rr_correlation_by_zone':rr,
                        'adjacent_velocity_spectral_distance_db':timbre}
    report={'harness':'Python, not Kotlin','variant_count':len(VOICES)*12,'stage_wavs':len(entries),
            'finite':True,'pcm_manifest_roundtrip':True,'deterministic_rerender':True,
            'max_peak':max(e['peak'] for e in entries),'max_abs_dc':max(abs(e['dc']) for e in entries),
            'render_ms_four_stages_median':float(np.median(elapsed)),'metrics':metrics,
            'not_verified':['phone render budget','higher-rate aliasing reference','browser audio output',
                            'SnipSnap persistence/playback','MPC export and hardware playback',
                            'genre shelf','phrase listening acceptance','owner sound acceptance']}
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))

if __name__=='__main__': main(sys.argv[1])
