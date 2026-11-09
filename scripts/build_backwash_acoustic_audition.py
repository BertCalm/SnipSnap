"""Fetch pinned CC0 recordings and render an effects-free source audition.
Requires Python/numpy and ffmpeg. Usage: script CACHE_DIR OUTPUT_DIR
No sample assets are installed into SnipSnap by this script.
"""
from pathlib import Path
import concurrent.futures, hashlib, json, re, subprocess, sys, urllib.request
import numpy as np
repo=Path(__file__).resolve().parents[1]
cache,out=map(Path,sys.argv[1:3]);cache.mkdir(parents=True,exist_ok=True);out.mkdir(parents=True,exist_ok=True)
rows=json.loads((repo/'reference/backwash-acoustic/sources.json').read_text())
rate=44100

def get(r):
 p=cache/Path(r['path']).name
 if not p.exists():p.write_bytes(urllib.request.urlopen(r['downloadUrl'],timeout=90).read())
 b=p.read_bytes();assert hashlib.sha256(b).hexdigest()==r['sha256'],p
 x=np.frombuffer(subprocess.check_output(['ffmpeg','-v','error','-i',str(p),'-f','f32le','-ac','1','-ar',str(rate),'-']),dtype='<f4').copy()
 assert np.isfinite(x).all() and np.max(abs(x))>0
 if r['voice']=='cross-stick':
  n=int(re.search(r'_vl(\d+)',p.name)[1]);v=(n-1)//4+1;rr=(n-1)%4+1
 else:
  v=int(re.search(r'_v(?:l)?(\d+)',p.name)[1]);rr=int(re.search(r'_rr(\d+)',p.name)[1])
 return dict(r,velocityGroup=v,roundRobin=rr,audio=x)
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:records=list(pool.map(get,rows))
manifest={'rate':rate,'channels':1,'effects':'none','processing':'ffmpeg mono downmix/resampling; one constant gain per instrument across every dynamic and take; no EQ, compression, distortion or added reverb; full recorded tails retained','crossStickMapping':'16 upstream velocity recordings grouped into four adjacent dynamic groups, four distinct takes per group. This is an audition mapping, not the upstream RR map.','files':[],'sources':rows}

def write(name,x,events,gain):
 assert np.isfinite(x).all() and max(abs(x))<=.85001
 p=out/(name+'.flac')
 subprocess.run(['ffmpeg','-v','error','-y','-f','f32le','-ar',str(rate),'-ac','1','-i','-','-c:a','flac','-sample_fmt','s32','-bits_per_raw_sample','24',str(p)],input=x.astype('<f4').tobytes(),check=True)
 y=np.frombuffer(subprocess.check_output(['ffmpeg','-v','error','-i',str(p),'-f','f32le','-']),dtype='<f4')
 assert len(x)==len(y) and np.max(abs(x-y))<2e-7
 manifest['files'].append(dict(file=p.name,seconds=len(x)/rate,peak=float(max(abs(x))),gain=gain,sha256=hashlib.sha256(p.read_bytes()).hexdigest(),events=events))

def render(events,gain):
 length=max(int(t*rate)+len(r['audio']) for t,r in events)
 x=np.zeros(length,dtype=np.float32)
 for t,r in events:
  i=int(t*rate);x[i:i+len(r['audio'])]+=r['audio']*gain
 return x

for voice in ['cross-stick','cowbell','tambourine','ride-bell']:
 group=sorted([r for r in records if r['voice']==voice],key=lambda r:(r['velocityGroup'],r['roundRobin']))
 layers=sorted(set(r['velocityGroup'] for r in group))
 # Every variant is a distinct recorded PCM buffer, not copies or pitch randomization.
 assert len({hashlib.sha256(r['audio'].tobytes()).hexdigest() for r in group})==len(group)
 dynamic=[(.8*i,next(r for r in group if r['velocityGroup']==v and r['roundRobin']==2)) for i,v in enumerate(layers)]
 mid=layers[len(layers)//2];alternates=[r for r in group if r['velocityGroup']==mid]
 repeats=[(i*.4,alternates[i%len(alternates)]) for i in range(8)]
 hard=[(0,next(r for r in group if r['velocityGroup']==layers[-1] and r['roundRobin']==1))]
 playlists={'single':hard,'dynamics':dynamic,'alternates':repeats}
 # One shared gain also accommodates overlapping tails in the audition phrases.
 gain=.85/max(max(abs(render(events,1))) for events in playlists.values())
 for kind,events in playlists.items():
  labels=[dict(time=t,source=r['path'],velocityGroup=r['velocityGroup'],roundRobin=r['roundRobin']) for t,r in events]
  write(voice+'-'+kind,render(events,gain),labels,float(gain))
roll=next(r for r in records if r['voice']=='tambourine-roll' and r['velocityGroup']==2)
gain=.65/max(abs(roll['audio']));write('tambourine-recorded-roll',roll['audio']*gain,[dict(time=0,source=roll['path'],velocityGroup=2,roundRobin=1)],float(gain))
(out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
for p in (repo/'reference/backwash-acoustic').glob('*LICENSE*'):(out/p.name).write_bytes(p.read_bytes())
(out/'CREDITS.txt').write_text('Acoustic source audition, not a finished SnipSnap engine.\n\nVirtuosity Drums / Versilian Studios; drum performances by Austin McMahon, recorded at Virtuosity Musical Instruments, Boston.\nhttps://github.com/sfzinstruments/virtuosity_drums\n\nVSCO 2 Community Edition / Versilian Studios. Recordings: Sam Gossner and Simon Dalzell. Sample cutting: Elan Hickler / Soundemote.\nhttps://github.com/sgossner/VSCO-2-CE\n\nBoth sources: CC0 1.0 Universal. See accompanying license texts. Pinned source commits, original hashes, audition gains and event-to-take mappings are in manifest.json.\nChanges: mono 44.1 kHz conversion, fixed gain per instrument and assembled audition phrases. No added effects. Recorded room sound remains.\n')
print(f'PASS: {len(records)} pinned downloads; 13 finite, peak-safe FLAC renders checked against decoded PCM; distinct recorded takes; manifest saved. Assets: {sum(p.stat().st_size for p in out.iterdir())} bytes.')
