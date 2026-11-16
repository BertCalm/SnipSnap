"""Procedural parade-kit listening study. Python, not the approved Kotlin renderer.
Run from any directory; numpy/scipy required. Constants are listening shapes.
"""
import argparse, csv, json, sys, zipfile
from pathlib import Path
import numpy as np
from scipy import signal
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'.claude/skills/luthier/scripts'))
import snipkit as sk
R=sk.INTERNAL
# id, family, root Hz, T60 seconds, section players, contact width ms
NOLA=[('bass_open','skin',58,1.35,1,3.2),('bass_muted','skin',58,.32,1,3.2),
 ('snare_center','wire',255,.26,1,.5),('snare_ghost','wire',255,.16,1,.9),
 ('rimshot','rim',255,.19,1,.3),('cross_stick','wood',820,.07,1,.4),
 ('snare_drag','drag',255,.23,1,.5),('snare_flam','flam',255,.25,1,.5),
 ('press_buzz','buzz',255,.24,1,.5),('snare_roll','roll',255,.25,1,.5),
 ('cymbal_crash','metal',500,1.9,1,.4),('cymbal_choke','metal',500,.25,1,.4),
 ('cymbal_touch','metal',500,.48,1,.8),('bell','bell',780,.35,1,.4),
 ('stick_click','wood',1300,.045,1,.3),('turnaround','turn',255,.25,1,.5)]
SAMBA=[('surdo_low_open','skin',58,1.1,2,4),('surdo_low_muted','skin',58,.23,2,4),
 ('surdo_mid_open','skin',77,.8,2,3),('surdo_mid_muted','skin',77,.2,2,3),
 ('surdo_cutter','skin',105,.42,2,2),('caixa','wire',330,.18,4,.35),
 ('caixa_rim','rim',330,.16,4,.25),('repinique_open','skin',285,.29,1,.4),
 ('repinique_rim','rim_dry',285,.18,1,.3),('tamborim_open','skin',640,.13,6,.22),
 ('tamborim_muted','skin',640,.045,6,.22),('chocalho_forward','rattle',2600,.16,3,.3),
 ('chocalho_back','rattle',3100,.11,3,.3),('agogo_low','bell',580,.5,1,.4),
 ('agogo_high','bell',870,.4,1,.4),('repinique_call','call',285,.28,1,.4)]
KITS={'second-line':(104,NOLA),'samba-bateria':(140,SAMBA)}

def raw_legacy(p,v,take):
 name,fam,root,life,count,width=p
 if fam in ('drag','flam','buzz','roll','turn','call'):
  times={'drag':[0,.024,.065], 'flam':[0,.025], 'buzz':list(np.cumsum([0]+[.012+.0015*i for i in range(15)])),
   'roll':[i*.065 for i in range(16)],'turn':[0,.17,.25,.34,.5,.67,.75], 'call':[0,.107,.214,.321,.535,.643,.75]}[fam]
  y=np.zeros(round(max(times)*R)+round(1.2*R))
  for j,at in enumerate(times):
   strength=(.35 if j<len(times)-1 else 1) if fam in ('drag','flam') else (.95 if j%4==0 else .55)
   q=(name,'skin' if fam=='call' else 'wire',root,life,count,width)
   x=raw(q,v*strength,take*32+j);i=round(at*R);y[i:i+len(x)]+=x
  return y
 n=round((2.2 if fam=='metal' else 1.2)*R);out=np.zeros(n)
 for player in range(count):
  identity=np.random.default_rng(sk.seed_for('PARADE_ID',name,player))
  rng=np.random.default_rng(sk.seed_for('PARADE_HIT',name,player,take))
  start=0 if player==0 else round(rng.uniform(.0004,.006)*R)
  t=np.arange(n-start)/R;f=root*2**(identity.uniform(-7,7)/1200)
  touch=v*rng.uniform(.95,1.03)
  if fam in ('metal','bell','rattle','wood'):
   ratios=np.geomspace(1,28,48) if fam=='metal' else np.array([1,1.47,2.03,2.71,3.9,5.2,7.3])
  else:ratios=np.array([1,1.594,2.136,2.296,2.653,2.918,3.156,3.50])
  y=np.zeros(len(t))
  for k,a in enumerate(ratios):
   hz=f*a*identity.uniform(.995,1.005)
   if hz>=18000:continue
   sustain=life/a**.5;g=np.exp(-a*(.12+.8*(1-touch)))/a**.65*rng.uniform(.93,1.07)
   if fam in ('skin','wire') and k==0:g*=1.5
   # Emphasize the large head's low modes without adding an electronic sub.
   if name in ('bass_open','bass_muted'):g*=2.0 if k<2 else .65
   y+=g*np.sin(2*np.pi*hz*t)*np.exp(-6.9078*t/sustain)
  force=np.hanning(max(4,round(width*.001*(1.5-.65*touch)*R)));force/=force.sum()
  y=signal.lfilter(force,[1],y)
  noise=rng.uniform(-1,1,len(t))
  if fam in ('wire','rim','rim_dry'):
   if fam!='rim_dry':
    cord=signal.sosfilt(signal.butter(2,[1700,9500],btype='bandpass',fs=R,output='sos'),noise)
    y+=(1.8 if fam=='wire' else .35)*touch**.7*cord*np.minimum(t/.0002,1)*np.exp(-6.9078*t/life)
    if fam=='wire' and (name.startswith('snare_') or name in ('press_buzz','turnaround')):
     # A brief head/contact pop, distinct from the measured metal rim modes.
     pop=signal.sosfilt(signal.butter(2,[700,4200],btype='bandpass',fs=R,output='sos'),noise)
     y+=5.5*touch**1.2*pop*(1-np.exp(-t/.0003))*np.exp(-t/.006)
     y+=.85*touch*np.sin(2*np.pi*f*2.136*t)*np.exp(-t/.012)
   if fam!='wire':
    for hz,g in [(1550,.8),(2620,.7),(3880,.5)]:
     y+=g*np.sin(2*np.pi*hz*t)*np.exp(-6.9078*t/.035)
  if fam=='metal':
   wash=signal.sosfilt(signal.butter(2,[2000,12000],btype='bandpass',fs=R,output='sos'),noise)
   y+=.4*wash*np.exp(-t/.05)
  if fam=='rattle':
   wash=signal.sosfilt(signal.butter(2,[2500,14000],btype='bandpass',fs=R,output='sos'),noise)
   contacts=np.zeros(len(t))
   for at in rng.uniform(0,.09,18):contacts+=np.exp(-np.maximum(t-at,0)/.005)*(t>=at)
   y=.12*y+.35*wash*contacts
  y*=np.minimum(t/.00015,1)*touch*identity.uniform(.95,1.05)
  out[start:]+=y/np.sqrt(count)
 return out

def raw(p,v,take):
 if p[1] in ('metal','wood','rim','rim_dry'):
  import measured
  return measured.render(p,v,take,raw_legacy)
 return raw_legacy(p,v,take)

def render(p,v,take):return sk.finish(raw(p,v,take))
def phrase(kit,pads,bpm):
 beats=32;out=np.zeros(round((beats*60/bpm+5.0)*sk.RATE));events=[]
 def hit(beat,pad,v=.8):events.append((beat,pad,v))
 for bar in range(8):
  b=bar*4
  if kit=='second-line':
   for q in [0,1.5,2,3.5]:hit(b+q,0,.8 if q in [0,2] else .55)
   for q in [1,3]:hit(b+q,2,1)
   for q in [.75,1.75,2.5,3.75]:hit(b+q,3,.35)
   hit(b+.5,5,.5);hit(b+2.75,4,.75);hit(b,12,.6)
   if bar%4==3:hit(b+3,6,.8)
  else:
   for q in [1,3]:hit(b+q,0,1)
   for q in [0,2]:hit(b+q,2,.8)
   for q in [.75,2.5,3.75]:hit(b+q,4,.7)
   for step in range(16):
    hit(b+step/4,5,.85 if step%4 in [0,3] else .35)
    hit(b+step/4,11 if step%2==0 else 12,.55 if step%2==0 else .35)
   for step in [0,3,6,8,11,14]:hit(b+step/4,9,.7)
   for q,p in [(0,13),(.75,14),(1.5,13),(2.5,14),(3.25,13)]:hit(b+q,p,.6)
   if bar%4==3:hit(b+3,7,.85);hit(b+3.5,8,.7)
 for i,(beat,pad,v) in enumerate(events):
  # New Orleans subdivision lilt is an authored study, not a transcription.
  if kit=='second-line' and beat%1 in [.25,.75]:beat+=.035
  a=pads[pad][(0 if v<.5 else 1 if v<.85 else 2)*3+i%3]
  at=round(beat*60/bpm*sk.RATE);gain=v*(.42 if pad in [11,12] else .6)
  out[at:at+len(a)]+=gain*a
 out*=.9/max(np.max(np.abs(out)),1e-9);return out,events

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--output',type=Path,required=True);dest=ap.parse_args().output;dest.mkdir(parents=True,exist_ok=True)
 rows=[];sections=[]
 for kit,(bpm,recipes) in KITS.items():
  folder=dest/kit;folder.mkdir(exist_ok=True);bank=[];cards=[]
  for index,p in enumerate(recipes):
   grid=[]
   for v in [.3,.6,1.]:
    for take in range(3):
     x=render(p,v,take);assert np.isfinite(x).all() and .01<np.max(np.abs(x))<=1 and abs(np.mean(x))<.005
     assert x[-1]==0
     sk.write_wav(str(folder/f'{index+1:02}_{p[0]}_v{v}_t{take}.wav'),x);grid.append(x)
   assert np.array_equal(grid[6],render(p,1.,0))
   corr=float(np.corrcoef(grid[6],grid[7])[0,1]);assert corr<.99995,(p[0],corr)
   # Normalized waveform difference rejects gain-only velocity variants.
   a=grid[0]/np.linalg.norm(grid[0]);b=grid[6]/np.linalg.norm(grid[6]);movement=float(np.linalg.norm(a-b));assert movement>.01,(p[0],movement)
   rows.append([kit,p[0],p[4],corr,movement,float(np.max(np.abs(grid[6])))])
   sk.write_wav(str(folder/f'{p[0]}.wav'),grid[6]);bank.append(grid)
   cards.append(f'<article><h3>{p[0].replace("_"," ").title()}</h3><p>{p[4]} player'+('s' if p[4]!=1 else '')+f'</p><audio controls preload="none" src="{kit}/{p[0]}.wav"></audio></article>')
  groove,events=phrase(kit,bank,bpm);sk.write_wav(str(folder/'groove.wav'),groove)
  (folder/'recipes.json').write_text(json.dumps({'bpm':bpm,'recipes':recipes,'events':events,'velocities':[.3,.6,1.],'takes':3,'status':'Python listening study; not native kit export'},indent=2))
  with zipfile.ZipFile(dest/f'{kit}-study-wavs.zip','w',zipfile.ZIP_DEFLATED) as z:
   for path in sorted(folder.iterdir()):z.write(path,f'{kit}/{path.name}')
  sections.append(f'<section><h2>{kit.replace("-"," ").title()}</h2><p>{bpm} BPM · 16 pads · 3 velocity levels × 3 takes</p><audio controls preload="none" src="{kit}/groove.wav"></audio><p><a href="{kit}-study-wavs.zip">Download study WAVs and recipes</a></p><div class="grid">'+''.join(cards)+'</div></section>')
 with (dest/'metrics.csv').open('w') as f:
  w=csv.writer(f,lineterminator="\n");w.writerow(['kit','pad','players','hard_take_correlation','normalized_velocity_difference','hard_peak']);w.writerows(rows)
 (dest/'index.html').write_text('''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Two new parade kits · Listening study</title><style>body{background:#10191c;color:#f3eee2;font:17px system-ui;margin:0 auto;padding:32px;max-width:1200px}h1,h2{color:#f0bd65}a{color:#7ce0d2}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:16px}article{padding:16px;background:#1d2b30;border-radius:12px}audio{max-width:100%;width:100%}section{margin:40px 0}p{line-height:1.5}</style><h1>Second Line & Samba Bateria</h1><p>Sixth listening round: more snare pop and a deeper, fuller bass in Second Line. Second Line uses a compact snare/bass/cymbal rhythm section. The Rio-style bateria uses distinct surdo roles, caixa and tamborim sections, repinique calls, chocalho and agogô.</p><p>These are new Python sound studies, not the approved Kotlin Field/Street kits. Grooves are authored sketches, not transcriptions of a particular band or samba school. Downloaded WAVs are not MPC program files.</p>'''+''.join(sections)+'</html>')
 print(json.dumps({'pads':len(rows),'grid_renders':len(rows)*9,'max_take_correlation':max(r[3] for r in rows),'min_velocity_difference':min(r[4] for r in rows)}))
if __name__=='__main__':main()
