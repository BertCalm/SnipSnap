"""Write OUT/page/index.html and MP3 copies of the demos for the BOROUGH listening page.

    python3 build_page.py OUT_DIR [BEFORE_OUT_DIR [BEFORE_LABEL]]
BEFORE_OUT_DIR (optional) is an earlier round's build; its grooves become a "before" player on
each kit sheet so the two rounds can be compared. The round is read from the kick's parameters
(round 0 drum-machine voices carry `f0`, round 1 acoustic voices carry `f1`).
The page plays synthesized audio only. Reference loops are never copied to it.
"""
from __future__ import annotations
import html, json, subprocess, sys
from pathlib import Path

PAGE = r"""<title>Borough Kits</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Archivo:wght@500;700;800&family=IBM+Plex+Mono:wght@400;500&family=Inter:wght@400;500&display=swap">
<style>
/* Layout: one column; a kit-by-stack mixer up top, then each kit's sheet. Pads drawn as an MPC-style 4x4 grid. */
:root{
  --bg:#eef0f2; --panel:#ffffff; --ink:#15191e; --muted:#5b6672; --line:#d3d8de;
  --lamp:#c77700; --lamp-ink:#ffffff; --pad:#e2e6ea; --pad-perc:#f4e3c6;
  --display:"Archivo",system-ui,sans-serif; --body:"Inter",system-ui,sans-serif; --mono:"IBM Plex Mono",ui-monospace,monospace;
}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){
  --bg:#12161b; --panel:#1b2128; --ink:#e7ebef; --muted:#93a0ad; --line:#2c353f;
  --lamp:#f0a43a; --lamp-ink:#1a1206; --pad:#252d36; --pad-perc:#3a2e1c; color-scheme:dark}}
:root[data-theme="dark"]{
  --bg:#12161b; --panel:#1b2128; --ink:#e7ebef; --muted:#93a0ad; --line:#2c353f;
  --lamp:#f0a43a; --lamp-ink:#1a1206; --pad:#252d36; --pad-perc:#3a2e1c; color-scheme:dark}
body{background:var(--bg);color:var(--ink);font:15px/1.55 var(--body)}
.wrap{max-width:960px;margin:0 auto;padding-block:28px 64px;padding-left:max(16px,env(safe-area-inset-left,0px));padding-right:max(16px,env(safe-area-inset-right,0px));display:grid;gap:28px}
h1,h2,h3{font-family:var(--display);text-wrap:balance;margin:0;letter-spacing:-.01em}
h1{font-size:2.1rem;font-weight:800}
h2{font-size:1.35rem;font-weight:700}
h3{font-size:.8rem;text-transform:uppercase;letter-spacing:.08em;color:var(--muted);font-weight:700}
p{margin:0;max-width:68ch}
.lede{display:grid;gap:10px}
.note{color:var(--muted);font-size:.9rem}
.panel{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:18px;display:grid;gap:14px;min-width:0}
.mixer .row{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
.chip{font:500 .85rem var(--mono);border:1px solid var(--line);background:var(--bg);color:var(--ink);border-radius:999px;padding:6px 12px;cursor:pointer}
.chip[aria-pressed="true"]{background:var(--lamp);color:var(--lamp-ink);border-color:var(--lamp)}
.chip:focus-visible,button:focus-visible{outline:2px solid var(--lamp);outline-offset:2px}
audio{width:100%}
.now{font:500 .85rem var(--mono);color:var(--muted)}
.grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:6px}
.pad{background:var(--pad);border-radius:6px;padding:8px;min-height:52px;font:500 .72rem var(--mono);display:flex;flex-direction:column;justify-content:space-between;gap:4px;min-width:0}
.pad.perc{background:var(--pad-perc)}
.pad b{font-weight:500;color:var(--muted)}
.kit{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.2fr);gap:18px}
@media (max-width:700px){.kit{grid-template-columns:1fr}}
.kit .media{display:grid;gap:10px;min-width:0}
.facts{font:400 .8rem var(--mono);color:var(--muted);display:grid;gap:2px}
table{border-collapse:collapse;font:400 .8rem var(--mono);width:100%}
td,th{border-bottom:1px solid var(--line);padding:5px 8px;text-align:left;font-variant-numeric:tabular-nums}
.scroll{overflow-x:auto}
ul{margin:0;padding-left:20px;display:grid;gap:4px}
</style>
<div class="wrap">
<header class="lede">
  <h1>Borough Kits</h1>
  <p>__LEDE__</p>
  <p class="note">Every kit fills pads A01–A12 and every stack fills A13–A16, so any kit plus any stack is a complete sixteen-pad bank. The grooves below are original patterns at each kit's measured tempo and swing, not transcriptions.</p>
</header>
<section class="panel mixer" aria-labelledby="mixh">
  <h2 id="mixh">Mix a bank</h2>
  <h3>Drum kit</h3><div class="row" id="kits"></div>
  <h3>Perc stack</h3><div class="row" id="stacks"></div>
  <div class="now" id="now"></div>
  <audio id="player" controls preload="none"></audio>
  <div class="grid" id="bank"></div>
</section>
<section class="panel" aria-labelledby="stackh">
  <h2 id="stackh">Perc stacks on their own</h2>
  <p class="note">Each stack's four pads at full strength, one after another.</p>
  <div id="stacklist" style="display:grid;gap:10px"></div>
</section>
<div id="kitlist" style="display:grid;gap:28px"></div>
<section class="panel" aria-labelledby="lh">
  <h2 id="lh">What to listen for</h2>
  <ul id="listen"></ul>
</section>
</div>
<script>
const M = __MANIFEST__;
let kit = M.kits[0].id, stack = null;
const $ = id => document.getElementById(id);
function chip(label, pressed, on){const b=document.createElement('button');b.className='chip';b.textContent=label;b.setAttribute('aria-pressed',pressed);b.onclick=on;return b}
function draw(){
  $('kits').replaceChildren(...M.kits.map(k=>chip(k.name,k.id===kit,()=>{kit=k.id;draw();play()})));
  $('stacks').replaceChildren(chip('none',stack===null,()=>{stack=null;draw();play()}),...M.stacks.map(s=>chip(s.name,s.id===stack,()=>{stack=s.id;draw();play()})));
  const k=M.kits.find(x=>x.id===kit), s=M.stacks.find(x=>x.id===stack);
  $('now').textContent=`${k.name}${s?' + '+s.name:''} · ${k.bpm} BPM · 16th swing ${k.swing_ms} ms`;
  const cells=[...k.pads.map(p=>[p.pad,p.label,false]),...(s?s.pads.map(p=>[p.pad,p.label,true]):[13,14,15,16].map(n=>[n,'empty',true]))];
  const order=[13,14,15,16,9,10,11,12,5,6,7,8,1,2,3,4];
  $('bank').replaceChildren(...order.map(n=>{const c=cells.find(x=>x[0]===n);const d=document.createElement('div');d.className='pad'+(c[2]?' perc':'');d.innerHTML=`<b>A${String(n).padStart(2,'0')}</b><span></span>`;d.lastChild.textContent=c[1];return d}));
}
function play(){const src=`audio/${kit}${stack?'-with-'+stack:'-groove'}.mp3`;const a=$('player');a.src=src}
draw();play();
for(const s of M.stacks){const d=document.createElement('div');d.innerHTML=`<h3></h3><audio controls preload="none" src="audio/stack-${s.id}-pads.mp3"></audio>`;d.querySelector('h3').textContent=`${s.name} · ${s.pads.map(p=>p.label.toLowerCase()).join(', ')}`;$('stacklist').append(d)}
for(const k of M.kits){
  const sec=document.createElement('section');sec.className='panel';
  const res=Object.entries(k.fitted_residual_db).map(([r,v])=>`${r} ${v.toFixed(1)} dB`).join(' · ');
  sec.innerHTML=`<div class="kit"><div style="display:grid;gap:8px;min-width:0"><h2></h2><p class="role"></p><div class="facts"></div></div><div class="media">
  <h3>Groove</h3><audio controls preload="none" src="audio/${k.id}-groove.mp3"></audio>
  ${k.bus_preview?`<h3>Preview: the same groove through a whole-kit compressor and limiter (not in the app yet)</h3><audio controls preload="none" src="audio/${k.id}-groove-glued.mp3"></audio>`:''}
  <h3>All twelve pads, hardest zone</h3><audio controls preload="none" src="audio/${k.id}-pads.mp3"></audio>
  <h3>Touch: kick, snare, hat · soft ×3, medium ×3, hard ×3</h3><audio controls preload="none" src="audio/${k.id}-touch.mp3"></audio>
  ${M.before?`<h3>Before: the same groove in ${M.before}</h3><audio controls preload="none" src="audio/before-${k.id}-groove.mp3"></audio>`:''}</div></div>`;
  sec.querySelector('h2').textContent=k.name;sec.querySelector('.role').textContent=k.role;
  const kp=k.pads[0].params, sp=k.pads[1].params;
  const head=kp.f0!==undefined
    ?[`kick ${kp.f0.toFixed(0)} Hz, sweep ×${kp.sweep.toFixed(2)} over ${kp.sweep_ms.toFixed(0)} ms, body ${kp.decay_ms.toFixed(0)} ms`,`snare head ${sp.f1.toFixed(0)} Hz, wires ${sp.wire_lo.toFixed(0)}–${sp.wire_hi.toFixed(0)} Hz for ${sp.wire_ms.toFixed(0)} ms`,`low-pass ${(kp.lp/1000).toFixed(1)} kHz kick · ${(sp.lp/1000).toFixed(1)} kHz snare · drive ${kp.drive.toFixed(2)}`]
    :[`kick head ${kp.f1.toFixed(0)} Hz, rings ${(kp.t1*1000).toFixed(0)} ms, beater contact ${kp.beater_ms.toFixed(1)} ms`,`snare head ${sp.f1.toFixed(0)} Hz, rings ${(sp.t1*1000).toFixed(0)} ms, wires ×${sp.wire.toFixed(2)}, stick contact ${sp.stick_ms.toFixed(2)} ms`,`record: room ${kp.distance.toFixed(2)} · squash ${kp.comp.toFixed(2)} · drive ${kp.drive.toFixed(2)} · low-pass ${(kp.lp/1000).toFixed(1)} kHz (kick), ${(sp.lp/1000).toFixed(1)} kHz (snare)`];
  const f=[`${k.bpm} BPM · swing ${k.swing_ms} ms on the 16th offbeat`,...head,`fit residual ${res}`,`from ${k.primary.replace(/_/g,' ')} + ${k.secondary.replace(/_/g,' ')}`];
  sec.querySelector('.facts').replaceChildren(...f.map(t=>{const d=document.createElement('div');d.textContent=t;return d}));
  $('kitlist').append(sec);
}
$('listen').replaceChildren(...M.listen.map(t=>{const li=document.createElement('li');li.textContent=t;return li}));
</script>
"""

LEDE = {
    0: 'Round 0 listening page. Six drum kits and four perc stacks, all synthesized from oscillators and fresh noise, with their parameters fitted to measurements of your twenty reference loops. No audio from the references is in these sounds or on this page.',
    1: 'Round 1 listening page. Round 0 sounded like a drum machine, so every voice is now an acoustic model: drum heads that ring with many overtones, a felt beater, snare wires that only rattle when the head moves hard enough, cymbals fitted to real cymbal recordings, and a room. Each hit then goes through the sound of a sampled record: compression, saturation and a darker top end. The models were refitted to measurements of your twenty reference loops. No audio from the references is in these sounds or on this page.',
}

LISTEN_R1 = [
    'New in this version, a preview: each kit sheet has a second groove, the same performance through one compressor and a limiter across the whole kit, set so it is exactly as dense as its loop. Your loops get their squash from that, and compressing each drum on its own barely gets there. It is only on this page: the app cannot do it yet. If you like it, the next step is building it into the app.',
    'New in this version: STOOP\'s kick lost its fast flutter. Its two drum heads were tuned 12% apart, so they beat about 12 times a second, and that ran through a squashed, saturated boom over a second long, a lot like a woofer flapping. The heads are now almost in unison and the boom stops by about 0.8 s. Six other kicks got the same correction, mostly the KICK 2 pads. STOOP needs less level trim now (-2.2 dB, was -5.5).',
    'New in this version: the ride lost its dead spot. Its ring used to fade out and come back (7.5 dB on a typical hit): the record chain darkened it so much that only a few cymbal partials were left, and they beat together. It is brighter now and dips under 2.2 dB, smoother than the EMBER ride you approved. The before player is the version you heard.',
    'New in this version: every kit\'s kicks are set against its loop\'s low-to-mid balance. STOOP DUST came down 5.5 dB and CELLAR BOOM 6.5 dB; DRY ROOM 1.8 dB; the others barely moved.',
    'Also fixed: open hats that swelled after the stick (the chain cut their click). Every cymbal now peaks at the strike.',
    'New since your last listen: RIDE & RIMS now has a real ride. A03 is a ride cymbal fitted to the ride loop, A04 its bell, and the groove rides eighths with the bell closing every other bar. Before, a closed hat stood in for it.',
    'Before and after: each kit sheet has the round 0 groove last. Does round 1 sound like drums in a room rather than a machine?',
    'Kick: a felt beater on a head, with the air and boom of the shell, rather than a falling sine. Snare: a stick on a head with the wires reacting, rather than tone plus noise.',
    'Feel: the grooves now drift by a few milliseconds and vary in strength, the snare sits slightly late, and quarter-note hats are accented.',
    'Touch clips: soft strikes should be darker and rounder, with fewer wires, not just quieter.',
    'Toms, rim, pedal hat, roll and the perc stacks are EMBER acoustic voices tuned to your loops, but not yet fitted to them.',
    'Suspects from the spectrograms: the open hat may be brighter and longer than anything in the loops, and DRY ROOM carries more room between hits than keap does.',
    'The record is in each hit (room, squash, drive, low-pass). There is no vinyl crackle bed yet; say if you want one.',
]

LISTEN = [
    'Does each kit sound like the family of loops it was fitted to? The kit sheet names its two source loops.',
    'Touch clips: the soft strikes should change colour (darker, less wire, less click), not only get quieter.',
    'Takes: the three strikes in each zone should be audibly the same drum hit three slightly different ways.',
    'Kick and snare first. Pads marked derived (open and pedal hat, rim, ghost, toms, roll) were not fitted.',
    'Stacks: do the shaker, tambourine and bells sit inside every kit without fighting the hats?',
    'The dust and room of the records is in the recording chain of each hit; there is no vinyl noise bed yet.',
]


def main(out, before=None, before_label=None):
    out = Path(out); page = out / 'page'; (page / 'audio').mkdir(parents=True, exist_ok=True)
    man = json.loads((out / 'manifest.json').read_text())
    for w in sorted((out / 'demo').glob('*.wav')):
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(w), '-codec:a', 'libmp3lame', '-b:a', '160k',
                        str(page / 'audio' / (w.stem.replace('+', '-with-') + '.mp3'))], check=True)
    slim = dict(kits=[{k: v for k, v in kit.items() if k != 'pads'} | {'pads': [dict(pad=p['pad'], label=p['label'], params=p['params']) for p in kit['pads']]} for kit in man['kits']],
                stacks=[dict(id=s['id'], name=s['name'], pads=[dict(pad=p['pad'], label=p['label']) for p in s['pads']]) for s in man['stacks']],
                listen=LISTEN)
    rnd = 0 if 'f0' in man['kits'][0]['pads'][0]['params'] else 1
    if rnd == 1:
        slim['listen'] = LISTEN_R1
    if before:
        for kit in man['kits']:
            w = Path(before) / 'demo' / f"{kit['id']}-groove.wav"
            subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(w), '-codec:a', 'libmp3lame', '-b:a', '160k',
                            str(page / 'audio' / f"before-{kit['id']}-groove.mp3")], check=True)
        slim['before'] = before_label or f'round {rnd - 1}'
    (page / 'index.html').write_text(PAGE.replace('__LEDE__', html.escape(LEDE[rnd]))
                                     .replace('__MANIFEST__', json.dumps(slim).replace('</', '<\\/')))
    print('page', page)


if __name__ == '__main__':
    main(*sys.argv[1:4])
