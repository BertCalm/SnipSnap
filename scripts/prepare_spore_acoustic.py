#!/usr/bin/env python3
"""Reproducible CC0 sample selection and mono conversion; records every source and output hash."""
import json,urllib.request,hashlib,subprocess,concurrent.futures
from pathlib import Path
import numpy as np
from scipy.io import wavfile
root=Path(__file__).resolve().parents[1]/'synth/src/main/resources/spore-station/v7';root.mkdir(parents=True,exist_ok=True)
import argparse
parser=argparse.ArgumentParser(description='Prepare the pinned CC0 Spore acoustic bank. Requires ffmpeg, numpy and scipy.')
parser.add_argument('--cache-dir',type=Path,required=True)
cache=parser.parse_args().cache_dir;cache.mkdir(parents=True,exist_ok=True)
commit='9f04cf9a734527edfbb0a4eee1f674e45bbf71bc'
base=f'https://raw.githubusercontent.com/sfzinstruments/virtuosity_drums/{commit}/'
tree={r['path']:r for r in json.load(urllib.request.urlopen(f'https://api.github.com/repos/sfzinstruments/virtuosity_drums/git/trees/{commit}?recursive=1'))['tree']}
banks={
'kick':('kickmic/kick/kickmic_kick_snon',[[1,1,1],[3,3,3],[4,4,4]],True),
'heel':('kickmic/kick/kickmic_kick_snon',[[1,1,1],[2,2,2],[3,3,3]],True),
'snare':('snaremic/snare/snaremic_snare_center',[[10,11,12],[22,23,24],[34,35,36]],False),
'ghost':('snaremic/snare/snaremic_snare_center',[[7,8,9],[13,14,15],[19,20,21]],False),
'rim':('snaremic/snare/snaremic_snare_rimshot',[[1,2,3],[6,7,8],[10,11,12]],False),
'stick':('snaremic/snare/snaremic_snare_crossstick',[[1,2,3],[7,8,9],[14,15,16]],False),
'closed':('oh/hh/oh_hh_closed',[[1,1,1],[2,2,2],[4,4,4]],True),
'open':('oh/hh/oh_hh_open',[[1,1,1],[2,2,2],[4,4,4]],True),
'pedal':('oh/hh/oh_hh_pedal',[[1,1,1],[2,2,2],[3,3,3]],True),
'ride':('oh/ride/oh_ride_ride',[[1,1,1],[2,2,2],[3,3,3]],True),
'tom':('oh/htom/oh_htom_center',[[1,2,3],[7,8,9],[14,15,16]],False)}
rows=[]
for name,(prefix,layers,rr) in banks.items():
 for z,vals in enumerate(layers):
  for t,v in enumerate(vals):
   path=f'Samples/{prefix}_vl{v}'+(f'_rr{t+1}' if rr else '')+'.flac'
   assert path in tree,path
   rows.append(dict(voice=name.strip(),zone=z,take=t,path=path,kind='recorded-round-robin' if rr else 'adjacent-recorded-dynamics'))
paths=sorted(set(r['path'] for r in rows))
def fetch(path):
 f=cache/Path(path).name
 if not f.exists():f.write_bytes(urllib.request.urlopen(base+path,timeout=60).read())
 data=f.read_bytes();assert hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()==tree[path]['sha'],path
 return path,f
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool: sources=dict(pool.map(fetch,paths))
converted={};peaks={}
for path,src in sources.items():
 dest=root/(src.stem+'.wav');tmp=cache/(src.stem+'.wav')
 subprocess.run(['ffmpeg','-v','error','-y','-i',str(src),'-ac','1','-ar','44100','-acodec','pcm_f32le',str(tmp)],check=True)
 sr,x=wavfile.read(tmp);x=x.astype(np.float64)
 # Remove pre-hit silence at -60 dB relative peak, retaining 2 ms before the first crossing.
 threshold=max(abs(x))*.001;active=np.flatnonzero(abs(x)>threshold);start=max(0,int(active[0])-88)
 # Trim only near-silence: retain 80 ms beyond the last -60 dB crossing, with a 4 ms end fade.
 end=min(len(x),int(active[-1])+3528);x=x[start:end];n=min(176,len(x));x[-n:]*=np.linspace(1,0,n)
 raw=cache/(src.stem+'-trim.wav');wavfile.write(raw,sr,x.astype(np.float32))
 subprocess.run(['ffmpeg','-v','error','-y','-i',str(raw),'-acodec','pcm_s24le',str(dest)],check=True)
 converted[path]=(dest,start,end,hashlib.sha256(src.read_bytes()).hexdigest());peaks[path]=float(max(abs(x)))
# Remove stale generated samples after a deliberate selection change.
keep={entry[0].name for entry in converted.values()}
for old in root.glob('*.wav'):
 if old.name not in keep:old.unlink()
# Calibration is fixed per instrument family, including shared center/ghost snare calibration.
family={'heel':'kick','ghost':'snare'}
maxima={}
for r in rows:
 f=family.get(r['voice'],r['voice']);maxima[f]=max(maxima.get(f,0),peaks[r['path']])
index=['voice,zone,take,file,gain']
for r in rows:
 dest,start,end,sha=converted[r['path']];f=family.get(r['voice'],r['voice']);gain=.85/maxima[f]
 index.append(f"{r['voice']},{r['zone']},{r['take']},{dest.name},{gain:.9f}")
 r.update(commit=commit,url=base+r['path'],gitBlob=tree[r['path']]['sha'],sourceSha256=sha,resource=dest.name,resourceSha256=hashlib.sha256(dest.read_bytes()).hexdigest(),startFrame=start,endFrame=end,gain=gain)
(root/'index.csv').write_text('\n'.join(index)+'\n');(root/'provenance.json').write_text(json.dumps(rows,indent=2)+'\n')
(root/'LICENSE.txt').write_bytes(urllib.request.urlopen(base+'LICENSE').read())
(root/'NOTICE.txt').write_text('Spore Station acoustic core v7. Virtuosity Drums, Versilian Studios; drummer Austin McMahon. CC0 1.0. Source: https://github.com/sfzinstruments/virtuosity_drums at '+commit+'. Close kick/snare microphones; overhead cymbals/tom. Mono 44.1 kHz PCM24 conversion; leading/trailing near-silence trimmed at -60 dB relative peak with 2 ms pre-hit and 80 ms tail margins, 4 ms final fade. No synthesis, EQ, compression, pitch shifting or added reverb. Fixed instrument-family calibration preserves recorded strike levels. Snare, ghost, rimshot, cross-stick and tom alternate adjacent recorded dynamic hits; kick and cymbals use dedicated recorded round robins. Exact files and conversion hashes: provenance.json.\n')
print(json.dumps({'files':len(paths),'gridEntries':len(rows),'bytes':sum(p.stat().st_size for p in root.glob('*.wav')),'maxFrames':max((end-start) for _,start,end,_ in converted.values())}))
