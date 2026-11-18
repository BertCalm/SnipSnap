"""Package verified native/MPC study packs and compare the corrected repeats."""
import argparse,hashlib,json,zipfile
from pathlib import Path
import numpy as np
from scipy.io import wavfile
import study
import snipkit as sk

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--previous',type=Path,required=True);ap.add_argument('--study',type=Path,required=True);ap.add_argument('--transport',type=Path,required=True);a=ap.parse_args()
 # Only a successful transport process may create this marker.
 assert (a.transport/'PASS.txt').is_file(),'Native transport has not passed'
 changed={'bass_open','bass_muted','bell'};identical=0;modified=0
 for kit,(_,pads) in study.KITS.items():
  for i,p in enumerate(pads):
   for v in [.3,.6,1.]:
    for take in range(3):
     path=f'{kit}/{i+1:02}_{p[0]}_v{v}_t{take}.wav';old=(a.previous/path).read_bytes();new=(a.study/path).read_bytes()
     if kit=='second-line' and p[0] in changed and take>0:
      assert old!=new,path;modified+=1
     else:assert old==new,path;identical+=1
 out=a.study/'comparison';out.mkdir(exist_ok=True);cards=[]
 for i,p in enumerate(study.NOLA):
  if p[0] not in changed:continue
  strips=[]
  for folder in [a.previous,a.study]:
   clips=[]
   for v in [.3,1.]:
    for take in range(3):
     rate,x=wavfile.read(folder/'second-line'/f'{i+1:02}_{p[0]}_v{v}_t{take}.wav');assert rate==44100
     clips.extend([x.astype(float)/32768,np.zeros(4410)])
   strips.append(np.concatenate(clips))
  gain=.9/max(np.max(np.abs(x)) for x in strips)
  for label,x in zip(['before','after'],strips):sk.write_wav(str(out/f'{p[0]}-{label}.wav'),gain*x)
  cards.append(f'<article><h3>{p[0].replace("_"," ").title()}</h3><p>Previous repeats</p><audio controls preload="none" src="comparison/{p[0]}-before.wav?revision=8"></audio><p>Revised repeats</p><audio controls preload="none" src="comparison/{p[0]}-after.wav?revision=8"></audio></article>')
 page=a.study/'index.html';s=page.read_text();i=s.index('<section>')
 s=s[:i]+'<section><h2>Verified kit packs and repeat comparison</h2><p>Each comparison plays three soft then three hard takes, at a shared pair gain. Take 0 is unchanged. All Samba audio is unchanged.</p><p>Downloads include SnipSnap native kits and MPC3 tracks with three velocity zones and three round-robin takes. MPC2 twins preserve velocity only. These are rendered study packs; editable Kotlin synthesis and hardware verification remain open.</p><div class="grid">'+''.join(cards)+'</div></section>'+s[i:]
 for kit in study.KITS:
  pack=a.study/f'{kit}-verified-pack.zip'
  with zipfile.ZipFile(pack,'w',zipfile.ZIP_DEFLATED) as z:
   for p in sorted((a.transport/kit).rglob('*')):
    if p.is_file():z.write(p,str(Path(kit)/p.relative_to(a.transport/kit)))
  assert pack.stat().st_size<25*1024**2
  with zipfile.ZipFile(pack) as z:assert z.testzip() is None
  s=s.replace(f'{kit}-study-wavs.zip',pack.name)
 s=s.replace('Download study WAVs and recipes','Download verified native / MPC study pack').replace('Downloaded WAVs are not MPC program files.','The verified packs below include MPC3 tracks and MPC2 program twins.')
 page.write_text(s.replace('.wav"', '.wav?revision=8"').replace('.zip"', '.zip?revision=8"'))
 report={'unchanged_grid_wavs':identical,'changed_repeat_wavs':modified,'reference_hits_unchanged':True}
 (a.study/'transport-comparison.json').write_text(json.dumps(report,indent=2)+'\n');print(report)
if __name__=='__main__':main()
