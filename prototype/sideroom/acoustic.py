"""Reproducible CC0 recorded-core study. No commercial reference audio used."""
import concurrent.futures, hashlib, json, subprocess, sys, time, urllib.request, urllib.error
from pathlib import Path
import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, sosfilt
ROOT=Path(__file__).resolve().parent
PIN='9f04cf9a734527edfbb0a4eee1f674e45bbf71bc'
URL=f'https://raw.githubusercontent.com/sfzinstruments/virtuosity_drums/{PIN}/'
CACHE=Path('/workspace/shared/sideroom-acoustic-cache'); CACHE.mkdir(parents=True,exist_ok=True)
SOURCES=json.loads((ROOT/'acoustic-sources.json').read_text())
def fetch(s):
 p=CACHE/Path(s['path']).name
 if not p.exists():
  for attempt in range(4):
   try:
    data=urllib.request.urlopen(URL+s['path'],timeout=60).read();p.write_bytes(data);break
   except (urllib.error.URLError,TimeoutError):
    if attempt==3:raise
    time.sleep(attempt+1)
 b=p.read_bytes(); assert hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()==s['sha'],p
 raw=subprocess.check_output(['ffmpeg','-v','error','-i',str(p),'-f','f32le','-ac','1','-ar','44100','-'])
 x=np.frombuffer(raw,dtype='<f4').astype(float)
 return s,x,hashlib.sha256(b).hexdigest()
if __name__=='__main__':
 data=list(concurrent.futures.ThreadPoolExecutor(6).map(fetch,SOURCES))
 for s,x,h in data:
  print(Path(s['path']).stem, len(x)/44100, round(20*np.log10(np.sqrt(np.mean(x[:22050]**2))+1e-12),1),flush=True)
 out=Path(sys.argv[1]) if len(sys.argv)>1 else Path('/workspace/shared/sideroom-r2')
 out.mkdir(parents=True,exist_ok=True)
 lookup={Path(s['path']).stem:(s,x,h) for s,x,h in data}
 banks={}; provenance=[]; manifest={'revision':'R2 acoustic core','levels':['Soft','Medium','Hard'],'takes':3,'modes':['source','open-road'],'voices':['kick','snare','hat','openhat'],'files':{}}
 for voice,prefix in [('kick','kickmic_kick_snon'),('snare','snaremic_snare_center'),('hat','oh_hh_closed'),('openhat','oh_hh_open')]:
  hits=[]
  for level in range(3):
   for take in range(3):
    name=f'{prefix}_vl{[13,23,32][level]+take}' if voice=='snare' else f'{prefix}_vl{[2,3,4][level]}_rr{take+1}'
    s,x,h=lookup[name]
    # Remove sub-audible offset, preserve onset and tail; no per-hit normalization.
    x=sosfilt(butter(2,22,fs=44100,btype='highpass',output='sos'),x)
    x[:44]*=np.linspace(0,1,44); x[-882:]*=np.linspace(1,0,882)
    hits.append((level,take,x))
    provenance.append({'voice':voice,'level':level,'take':take,'url':URL+s['path'],'git_blob':s['sha'],'source_sha256':h,'source_articulation':name})
  gain={'kick':.60,'snare':.75,'hat':.42,'openhat':.38}[voice]/max(np.max(np.abs(x)) for _,_,x in hits)
  for mode in manifest['modes']:
   banks[mode,voice]=[]
   for level,take,x in hits:
    y=x*gain
    if mode=='open-road':
     # Broad warmth and restrained tail damping; no artificial attack/noise layer.
     cutoff={'kick':3500,'snare':7800,'hat':10500,'openhat':10500}[voice]
     y=sosfilt(butter(2,cutoff,fs=44100,output='sos'),y)
     t=np.arange(len(y))/44100
     if voice in ('kick','snare'): y*=.65+.35*np.exp(-t/.22)
    banks[mode,voice].append(y)
 # Match the two treatments per instrument across all nine hits, retaining dynamic differences.
 for voice in manifest['voices']:
  a=banks['source',voice]; b=banks['open-road',voice]
  ratio=np.sqrt(sum(np.sum(x*x) for x in a)/sum(np.sum(x*x) for x in b))
  banks['open-road',voice]=[x*ratio for x in b]
 def write(path,x):
  assert np.isfinite(x).all() and np.max(np.abs(x))<.99,(path,np.max(np.abs(x)))
  path.parent.mkdir(parents=True,exist_ok=True)
  wavfile.write(path,44100,np.rint(x*32767).astype(np.int16))
 for mode in manifest['modes']:
  sfz=['// CC0 recordings; source attribution and exact selections in provenance.json']
  for voice,key in zip(manifest['voices'],[36,38,42,46]):
   for i,x in enumerate(banks[mode,voice]):
    level,take=divmod(i,3); rel=f'{mode}/{voice}-{level}-{take}.wav'; write(out/rel,x)
    manifest['files'][f'{mode}/{voice}/{level}/{take}']=rel
    choke=' group=1 off_by=1 off_mode=fast' if voice in ('hat','openhat') else ''
    sfz.append(f'<region> sample={voice}-{level}-{take}.wav key={key} lovel={[1,51,96][level]} hivel={[50,95,127][level]} seq_length=3 seq_position={take+1} amp_veltrack=0 loop_mode=one_shot{choke}')
  (out/mode/'kit.sfz').write_text('\n'.join(sfz)+'\n')
  # Authored 4-bar straight rock study, not a transcription of the commercial reference.
  events=[]; beat=60/100; counts={v:0 for v in manifest['voices']}
  for step in range(32):
   b=step/2; v='openhat' if step in (15,31) else 'hat'
   events.append((b*beat,v,2 if step%2==0 or v=='openhat' else 1))
   if step%8 in (0,4,7): events.append((b*beat,'kick',2 if step%8==0 else 1))
   if step%8 in (2,6): events.append((b*beat+.008,'snare',2))
   if step in (13,29): events.append((b*beat+.014,'snare',0))
  length=int((16*beat+4)*44100); mix=np.zeros(length)
  ordered=sorted(events)
  for time,voice,lev in ordered:
   rr=counts[voice]%3; counts[voice]+=1; x=banks[mode,voice][lev*3+rr].copy()
   if voice=='openhat':
    next_close=next((tm for tm,v,l in ordered if v=='hat' and tm>time),16*beat)
    if next_close is not None:
     n=min(len(x),int((next_close-time)*44100)); x=x[:n]; x[-min(220,n):]*=np.linspace(1,0,min(220,n))
   pos=round(time*44100); n=min(len(x),length-pos); mix[pos:pos+n]+=x[:n]
  banks[mode,'groove']=mix
 peak=max(np.max(np.abs(banks[m,'groove'])) for m in manifest['modes'])
 for m in manifest['modes']: write(out/f'{m}/groove.wav',banks[m,'groove']*.85/peak)
 (out/'manifest.json').write_text(json.dumps(manifest,indent=2))
 (out/'provenance.json').write_text(json.dumps({'license':'CC0-1.0','repository_commit':PIN,'credit':'Virtuosity Drums / Versilian Studios; performances Austin McMahon','snare_selection':'Three adjacent recorded dynamic hits per authored level, not same-dynamic source RR. Original levels 13–15, 23–25, 32–34.','files':provenance},indent=2))
 import shutil,zipfile
 shutil.copy(ROOT/'CC0.txt',out/'CC0.txt')
 (out/'NOTICE.txt').write_text('Virtuosity Drums / Versilian Studios; drummer Austin McMahon. CC0 1.0. Source: https://github.com/sfzinstruments/virtuosity_drums\nConversion: mono 44.1 kHz PCM16; fixed instrument calibration, 22 Hz high-pass and endpoint fades. Open Road adds low-pass and gentle kick/snare tail damping, with energy matching across each full instrument grid. No song audio sampled. See provenance.json.\n')
 with zipfile.ZipFile(out/'open-road-acoustic.zip','w',zipfile.ZIP_DEFLATED) as z:
  for p in sorted(out.rglob('*')):
   if p.is_file() and p.name!='checks.json' and p.suffix in ('.wav','.sfz','.json','.txt'): z.write(p,p.relative_to(out))
 print('Rendered',len(manifest['files']),'performances to',out)
