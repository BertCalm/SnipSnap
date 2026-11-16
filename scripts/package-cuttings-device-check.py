"""Package generated kit files and take-order references for physical device checks."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import csv,hashlib,json,shutil,sys,wave
src=Path(sys.argv[1]);dst=Path(sys.argv[2]);dst.mkdir(parents=True,exist_ok=True)
for kit in ('cuttings-pocket','cuttings-night','cuttings-circuit'):
 p=src/kit;ref=dst/kit;ref.mkdir(exist_ok=True);m=json.loads((p/'manifest.json').read_text())
 chunks=[];events=[];frame=0
 for d in range(3):
  for n in range(8):
   entry=next(e for e in m['entries'] if e['voice']=='p02' and e['dynamic']==d and e['take']==n%4)
   with wave.open(str(p/entry['file']),'rb') as w:
    assert w.getsampwidth()==3 and w.getnchannels()==1 and w.getframerate()==44100
    data=w.readframes(w.getnframes());count=len(data)//3
   events.append([round(frame/44100,6),[25,70,110][d],d+1,n%4+1,entry['file']])
   chunks.extend([data,b'\0'*(max(4410,26460-count)*3)]);frame+=count+max(4410,26460-count)
 with wave.open(str(ref/'A02-take-order.wav'),'wb') as w:
  w.setnchannels(1);w.setsampwidth(3);w.setframerate(44100);w.writeframes(b''.join(chunks))
 with (ref/'A02-take-order.csv').open('w') as f:
  writer=csv.writer(f);writer.writerow(['seconds','suggested_midi_velocity','layer','take','source']);writer.writerows(events)
 shutil.copyfile(p/'mixes'/f'{kit}-glue.wav',ref/f'{kit}-glue.wav')
 for platform in ('mpc3','native'):
  archive=dst/f'{kit}-{platform}.zip'
  with ZipFile(archive,'w',ZIP_DEFLATED) as z:
   for f in sorted((p/platform).rglob('*')):
    if f.is_file():z.write(f,Path(kit)/f.relative_to(p/platform))
  with ZipFile(archive) as z:assert z.testzip() is None
shutil.copyfile(Path(__file__).resolve().parents[1]/'docs/CUTTINGS_DEVICE_CHECK.md',dst/'START-HERE.md')
records=[]
for f in sorted(dst.rglob('*')):
 if f.is_file() and f.name!='SHA256SUMS':records.append(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+str(f.relative_to(dst)))
(dst/'SHA256SUMS').write_text('\n'.join(records)+'\n')
print('Built six verified archives, three take-order references and Glue mixes.')
