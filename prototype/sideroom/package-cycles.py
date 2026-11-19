"""Package the verified discrete-WAV cycle companions; not a combined-grid claim."""
from pathlib import Path
import sys,zipfile,shutil,json
src,out=map(Path,sys.argv[1:3]);out.mkdir(parents=True,exist_ok=True)
license_root=Path(__file__).resolve().parents[2]/'synth/src/main/resources/sideroom/v1'
report={}
for kit in sorted(src.iterdir()):
 if not (kit/'cycles').is_dir():continue
 root=kit/'cycles';files=[p for p in root.rglob('*') if p.is_file()]
 assert len(list(root.rglob('*.xtd')))==3 and len(list(root.rglob('*.xpm')))==3
 assert len(list(root.rglob('*.wav')))==90
 target=out/(kit.name+'-cycles.zip')
 with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
  for p in files:z.write(p,p.relative_to(root))
  for n in ['CC0.txt','NOTICE.txt']:z.write(license_root/n,n)
  z.writestr('README.txt','SIDEROOM cycling companions. Choose soft, medium or hard, then load that folder’s .xtd on MPC 3 or the .xpm inside its _[TrackData] folder on MPC 2. Keep each .xtd beside its matching data folder. Each program has ten pads and three discrete cycling WAV takes per pad. Touch timbre is fixed by the selected program; this does NOT combine velocity switching and round robin on one pad. All 90 performances are present across the three programs. The original fill stays at 100 BPM; the roll uses the kit tempo. Software checks verify both descriptors and all PCM against the native grid; physical MPC playback remains unverified. The original full-grid export’s slice-map issue remains open.\n')
 with zipfile.ZipFile(target) as z:
  for p in files:assert z.read(str(p.relative_to(root)))==p.read_bytes()
 assert target.stat().st_size<25*1024**2,target
 report[kit.name]={'programs':3,'pads_per_program':10,'takes_per_pad':3,'wav_files':90,'archive_bytes':target.stat().st_size,'combined_velocity_cycle':False,'hardware_verified':False}
 print(kit.name,round(target.stat().st_size/1024**2,2),'MiB',flush=True)
(out/'cycle-delivery-checks.json').write_text(json.dumps(report,indent=2))
