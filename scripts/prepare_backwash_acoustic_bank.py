"""Build immutable model-5 PCM resources from the pinned CC0 source manifest."""
from pathlib import Path
import sys,json,subprocess,hashlib,re,shutil
import numpy as np
repo=Path(__file__).resolve().parents[1];cache=Path(sys.argv[1]);out=repo/'synth/src/main/resources/backwash/v5';out.mkdir(parents=True,exist_ok=True)
rows=json.loads((repo/'reference/backwash-acoustic/sources.json').read_text());lines=[];audit=[];peaks={}
for r in rows:
 p=cache/Path(r['path']).name;assert hashlib.sha256(p.read_bytes()).hexdigest()==r['sha256']
 if r['voice']=='cross-stick':
  n=int(re.search(r'_vl(\d+)',p.name)[1]);v=(n-1)//4+1;rr=(n-1)%4
 else:
  v=int(re.search(r'_v(?:l)?(\d+)',p.name)[1]);rr=int(re.search(r'_rr(\d+)',p.name)[1])-1
 voice={'cross-stick':'CROSS_STICK','ride-bell':'RIDE','cowbell':'COWBELL','tambourine':'TAMBOURINE','tambourine-roll':'TAMBOURINE_ROLL'}[r['voice']]
 name=f'{voice.lower()}-{v}-{rr}.wav';dest=out/name
 subprocess.run(['ffmpeg','-v','error','-y','-i',str(p),'-ac','1','-ar','44100','-c:a','pcm_s24le','-map_metadata','-1',str(dest)],check=True)
 audio=np.frombuffer(subprocess.check_output(['ffmpeg','-v','error','-i',str(dest),'-f','f32le','-']),dtype='<f4');peaks[voice]=max(peaks.get(voice,0),float(max(abs(audio))))
 lines.append(f'{voice},{v},{rr},{name}');audit.append(dict(r,resource=name,resourceSha256=hashlib.sha256(dest.read_bytes()).hexdigest()))
(out/'gain.csv').write_text(''.join(f'{v},{.65/p}\n' for v,p in sorted(peaks.items())))
(out/'index.csv').write_text('voice,layer,take,file\n'+'\n'.join(lines)+'\n')
(out/'provenance.json').write_text(json.dumps(audit,indent=2)+'\n')
for p in (repo/'reference/backwash-acoustic').glob('*LICENSE*'):shutil.copy2(p,out/p.name)
(out/'NOTICE.txt').write_text('BACKWASH acoustic bank v5. Sources: Virtuosity Drums / Versilian Studios, drum performances Austin McMahon; VSCO 2 CE, recordings Sam Gossner and Simon Dalzell, cutting Elan Hickler / Soundemote. Both CC0 1.0. Source links, pinned hashes and conversion hashes: provenance.json. Conversion: mono 44.1 kHz PCM24, no effects, tails retained. Cross-stick: four adjacent recorded dynamics per authored velocity/RR group.\n')
print(len(audit),'assets;',sum(p.stat().st_size for p in out.iterdir()),'bytes')
