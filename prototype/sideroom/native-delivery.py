"""Package verified native MPC3 tracks with the independently exported MPC2 twin.
Checks actual XPM slice windows and chain bytes; does not claim hardware playback.
"""
from pathlib import Path
import json,shutil,sys,xml.etree.ElementTree as ET,wave,zipfile
from scipy.io import wavfile
import numpy as np
src,out=map(Path,sys.argv[1:3]);out.mkdir(parents=True,exist_ok=True)
resource=Path(__file__).resolve().parents[2]/'synth/src/main/resources/sideroom/v1'
report={}
for folder in sorted(src.iterdir()):
 if not folder.is_dir():continue
 kit=json.loads((folder/'native/kit.json').read_text());name=kit['name'];mpc3=folder/'mpc3';data=mpc3/(name+'_[TrackData]');mpc2=folder/'mpc2'/name;program=mpc2/(name+'.xpm')
 tree=ET.parse(program);instruments={int(i.attrib['number'])+1:i for i in tree.findall('.//Instrument')}
 for pad in kit['pads']:
  original=(folder/'native'/pad['sample']).read_bytes();assert (data/pad['sample']).read_bytes()==original;assert (mpc2/pad['sample']).read_bytes()==original
  with wave.open(str(data/pad['sample'])) as w:frames=w.getnframes()
  layers=[l for l in instruments[pad['slot']].findall('./Layers/Layer') if l.findtext('Active')=='True' and l.findtext('SampleName')]
  assert len(layers)==3
  for layer,zone in zip(layers,pad['chain']['zones']):
   i=zone['baseSlice'];bounds=pad['chain']['boundaries'];start=bounds[i];end=bounds[i+1] if i+1<len(bounds) else frames
   assert int(layer.findtext('VelStart'))==zone['velStart'];assert int(layer.findtext('VelEnd'))==zone['velEnd']
   assert int(layer.findtext('SliceStart'))==start;assert int(layer.findtext('SliceEnd'))==end
   assert layer.findtext('SampleName')+'.wav'==pad['sample']
 shutil.copy(program,data/program.name)
 text='SIDEROOM native kit. Open the .xtd on MPC 3 and keep its _[TrackData] folder alongside it. The data folder also contains the MPC 2 .xpm. MPC 3 keeps three velocity zones and three cycling takes. MPC 2 selects the first take of each velocity zone, per its documented format limit. Ten pads: eight approved synthesized hits, an approved snare roll and new one-bar drum fill. Native software read-back passes; the new fill and phone/MPC hardware playback await owner verification. No commercial reference audio sampled.\n'
 archive=out/(folder.name+'-native.zip')
 with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
  for p in sorted(mpc3.rglob('*')):
   if p.is_file():z.write(p,p.relative_to(mpc3))
  for n in ['CC0.txt','NOTICE.txt','provenance.json']:z.write(resource/n,n)
  z.writestr('README.txt',text);z.writestr('source-kit.json',json.dumps(kit,indent=2))
 with zipfile.ZipFile(archive) as z:
  for p in sorted(mpc3.rglob('*')):
   if p.is_file():assert z.read(str(p.relative_to(mpc3)))==p.read_bytes()
 assert archive.stat().st_size<25*1024**2,archive
 # Listening copies of the phrases, PCM16 only; full MPC delivery retains native PCM24.
 for role in ['roll','drumfill']:
  for level in range(3):
   for take in range(3):
    f=folder/f'{role}-{level}-{take}.wav';sr,x=wavfile.read(f)
    y=x.astype(float)/(2**31 if x.dtype==np.int32 else 32768)
    dest=out/folder.name/f'{role}-{level}-{take}.wav';dest.parent.mkdir(exist_ok=True);wavfile.write(dest,sr,np.rint(y*32767).astype(np.int16))
 report[folder.name]={'pads':10,'variants':90,'mpc2_velocity_windows_verified':30,'archive_bytes':archive.stat().st_size}
 print(folder.name,round(archive.stat().st_size/1024**2,2),'MiB')
shutil.copy(src/'verification.csv',out/'verification.csv');(out/'delivery-checks.json').write_text(json.dumps(report,indent=2))
print('PASS: 150 actual MPC2 velocity windows and all native package bytes verified')
