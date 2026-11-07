"""Generate R3 audio and assertions under testkit/afterimage-audition.

Run from any directory: python experiments/afterimage/audition.py [output_directory]
"""
from pathlib import Path
import base64,json,time,statistics,sys
import numpy as np
import engine as e
import baseline as b
ROOT=(Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[2]/'testkit'/'afterimage-audition')
(ROOT/'audio').mkdir(parents=True,exist_ok=True)
report={'status':'R2 behavior accepted by owner; R3 motion proposed, not yet heard','renders':[],'checks':{},'timing':{}}
tracks=[]; outputs={}

def save(name,label,raw,description='',gain=None):
    conditioned=e.condition(raw)
    if gain is None: y,gain=e.level(conditioned)
    else: y=conditioned*gain
    assert np.isfinite(raw).all() and np.max(np.abs(y))<=.951 and abs(np.mean(y))<.005,name
    e.sk.write_wav(str(ROOT/'audio'/f'{name}.wav'),y)
    tracks.append((name,label,description)); outputs[name]=y
    report['renders'].append({'name':name,'stats':e.stats(y),'gain_db':float(20*np.log10(gain))})
    return gain

# Normalized defaults preserve accepted half-speed bass/pluck and original stretch pad.
for voice in ('bass','pluck','pad'):
 for midi in (36,48,60):
    tune=(midi-36)/24
    save(f'{voice}_{midi}',f'{voice.title()} C{midi//12-1}',e.render_patch(voice,{'TUNE':tune}), 'Accepted static memory' if voice!='pad' else 'Original history-based stretch memory')
for voice,midi in [('bass',36),('pluck',60)]:
    tune=(midi-36)/24
    shared_gain=next(r for r in report['renders'] if r['name']==f'{voice}_{midi}')['gain_db']
    shared_gain=10**(shared_gain/20)
    for motion in (.35,.75):
        save(f'{voice}_motion_{motion}',f'{voice.title()} · motion {motion:g}',e.render_patch(voice,{'TUNE':tune,'MOTION':motion}), 'Shared gain with static version; continuous spectral shading',shared_gain)
    for motion in (0.,.35,.75):
        save(f'{voice}_isolated_{motion}',f'{voice.title()} memory only · motion {motion:g}',e.render(voice=voice,midi=midi,mode='tape',clock=.5,component='memory',space=0,motion=motion),'Memory only; independently leveled for inspection')
for mode,clock in [('tape',0.),('tape',1.),('reverse',.5)]:
    save(f'pad_{mode}_{clock}',f'Pad · {mode} · '+{0.:'half speed',.5:'original pitch',1.:'double speed'}[clock],e.render_patch('pad',{'CLOCK':clock},pad_mode=mode),'Original pad history behavior')
# A chord composed of three independent source/capture paths, all accepted motion=0.
for voice in ('pluck','pad'):
    chord=sum(e.render_patch(voice,{'TUNE':(m-36)/24}) for m in (48,51,55))/3
    save(f'{voice}_chord',f'{voice.title()} · C minor chord',chord,'Each note has its own memory; not a shared chord looper')

checks=report['checks']; checks['baseline_equal']={}
for voice in ('bass','pluck','pad'):
 for midi in (36,48,60):
    for clock in (.5,1.,2.):
        kwargs=dict(voice=voice,midi=midi,mode='stretch' if voice=='pad' else 'tape',clock=clock)
        same=bool(np.array_equal(e.render(**kwargs),b.render(**kwargs)))
        checks['baseline_equal'][f'{voice}_{midi}_{clock}']=same;assert same
checks['motion_bounds']={}
for midi in (36,48,60):
    _,src=e.source('pluck',midi)
    buf,_=e.frozen_capture(src,'pluck',midi)
    x=e.frozen_read(buf,e.INTERNAL*3,.5,e.hz_for(midi))
    for motion in (.2,.6,1.):
        y=e.animate_tail(x,motion,e.hz_for(midi)*.5)
        # Convex mix with a one-pole lowpass cannot exceed the input's absolute peak.
        assert np.max(np.abs(y))<=np.max(np.abs(x))+1e-10
        assert np.isfinite(y).all()
        gain_slew_bound=.65*motion*np.pi*.21
        checks['motion_bounds'][f'{midi}_{motion}']={'peak':float(np.max(np.abs(y))),'maximum_blend_slew_per_second':gain_slew_bound}
checks['macro_activity_db']={}
for voice in ('bass','pluck','pad'):
    activity={}
    for key in e.controls_for(voice):
        a,_=e.level(e.condition(e.render_patch(voice,{key:.2})))
        z,_=e.level(e.condition(e.render_patch(voice,{key:.6})))
        activity[key]={'full':float(e.pr.macro_activity(a,z)),'tail_after_2s':float(e.pr.macro_activity(a[2*e.RATE:],z[2*e.RATE:]))}
    checks['macro_activity_db'][voice]=activity
checks['unknown_key_ignored']=bool(np.array_equal(e.render_patch('bass'),e.render_patch('bass',{'UNUSED':1})))
checks['nonfinite_uses_default']=bool(np.array_equal(e.render_patch('pluck'),e.render_patch('pluck',{'COLOR':float('nan')})))
checks['deterministic']=bool(np.array_equal(e.render_patch('bass',{'MOTION':.35}),e.render_patch('bass',{'MOTION':.35})))
assert checks['unknown_key_ignored'] and checks['nonfinite_uses_default'] and checks['deterministic']
# Complete renders at bright/high-memory/high-motion corners.
for voice in ('bass','pluck','pad'):
 for tune in (0.,1.):
    macros={k:1. for k in e.controls_for(voice)};macros['TUNE']=tune
    raw=e.render_patch(voice,macros);assert np.isfinite(raw).all() and np.max(np.abs(raw))<4
checks['finite_bounded_corners']=6
full=[]; cached=[]
for i in range(3):
    e.source.cache_clear();start=time.perf_counter();e.condition(e.render_patch('pluck',{'MOTION':.35}));full.append((time.perf_counter()-start)*1000)
    start=time.perf_counter();e.condition(e.render_patch('pluck',{'MOTION':.6}));cached.append((time.perf_counter()-start)*1000)
report['timing']={'full_ms':full,'cached_source_ms':cached,'full_median_ms':statistics.median(full),'cached_median_ms':statistics.median(cached),'scope':'Python synthesis + output conditioning, four-second sample; no phone/Kotlin claim'}
order=['bass_36','bass_motion_0.35','pluck_60','pluck_motion_0.35','pad_48','pluck_chord']
e.sk.write_wav(str(ROOT/'quick-tour.wav'),np.concatenate([np.concatenate([outputs[n],np.zeros(round(.35*e.RATE))]) for n in order]))
report['quick_tour_order']=order
(ROOT/'report.json').write_text(json.dumps(report,indent=2))
(ROOT/'controls.json').write_text(json.dumps({v:e.controls_for(v) for v in ('bass','pluck','pad')},indent=2))

def card(name):
    _,label,description=next(t for t in tracks if t[0]==name)
    encoded=base64.b64encode((ROOT/'audio'/f'{name}.wav').read_bytes()).decode()
    return f'<article><h3>{label}</h3><p>{description}</p><audio controls preload="none" src="data:audio/wav;base64,{encoded}"></audio></article>'
body=''
for title,names in [('Compare gentle motion',['bass_36','bass_motion_0.35','bass_motion_0.75','pluck_60','pluck_motion_0.35','pluck_motion_0.75']),('Inspect the tail',[n for n,_,_ in tracks if 'isolated' in n]),('Accepted sounds across registers',[f'{v}_{m}' for v in ('bass','pluck','pad') for m in (36,48,60)]),('Pad memory and chords',[n for n,_,_ in tracks if '_tape_' in n or '_reverse_' in n or '_chord' in n])]:
    body+=f'<h2>{title}</h2><div class="grid">'+''.join(card(n) for n in names)+'</div>'
page='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Afterimage R3 · motion study</title><style>body{background:#12171d;color:#e7ecef;font:16px/1.55 system-ui;margin:0}main{max-width:1040px;margin:auto;padding:36px 24px}p{color:#b8c7d0}h1{font-size:42px}h2{margin-top:36px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:14px}article{background:#1c252e;border:1px solid #34414d;border-radius:12px;padding:16px}audio{width:100%}h3{font-size:17px}article p{font-size:14px;min-height:42px}.note{border-left:3px solid #8ce0c8;padding-left:18px}</style><main><p>AFTERIMAGE · R3</p><h1>Keep the sustain. Try gentle motion.</h1><p class="note">The accepted static bass/pluck and original pad remain the defaults. New motion slowly shades the held waveform toward a darker tone, without recapturing the note or restarting its envelope.</p><p>Compare static, .35 and .75 motion. Does the movement add something useful, and does the tail still avoid the repeated attack? Mixed motion comparisons share gain. Isolated memories are independently leveled. These settings are research candidates, not finalized presets.</p>'''+body+'''<h2>Controls</h2><p>Bass/pluck: TUNE, COLOR, CHORUS, MEMORY, CLOCK, MOTION.<br>Pad: TUNE, COLOR, CHORUS, MEMORY, CLOCK, SPACE.</p><p>CLOCK selects half, original, or double speed. Pad also offers tape, stretch, and reverse. Frozen bass/pluck have one sustain behavior. MOTION defaults to zero. Duration remains explicit; seamless HOLD is not implemented.</p><p>Why it is built this way: the two memory paths follow the listening result; slow filtering adds movement independently of the note envelope; retaining the static default keeps the accepted sound available. The handoff brief and measured report are included in the full download.</p></main><script>document.addEventListener('play',e=>{if(e.target.tagName==='AUDIO')document.querySelectorAll('audio').forEach(a=>{if(a!==e.target)a.pause()})},true)</script></html>'''
(ROOT/'listen.html').write_text(page)
print(json.dumps({'tracks':len(tracks),'timing':report['timing'],'activity':checks['macro_activity_db']},indent=2))
