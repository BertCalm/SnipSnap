#!/usr/bin/env python3
"""Freeze the selected B preview's PCM16 into JVM resources (requires ffmpeg)."""
import hashlib,io,json,subprocess,sys,wave
from pathlib import Path
site=Path(sys.argv[1]);source=site/'dist/round7';manifest=json.loads((source/'manifest.json').read_text())
out=Path(__file__).resolve().parents[1]/'synth/src/main/resources/velour/ride-b-v1'
entries=[e for e in manifest['samples'] if e['voice']=='tight' and e['treatment']!='dry']
assert len(entries)==36
HARD_PREVIEWS={'close': 'e351662a1aea92518338479f446cfd164f0c62f4bb6d4b4e7367b57710d12b46', 'velour': '3655162442d89a2dfaf5f9983f78d38bf7dd1c1f351e9b5f8a01fd994cbd7b42', 'bloom': 'a2718478cf2cf703d7542a02c60a2b8deb80f27ed485852ff0e0d0714a2deb41'}
assets=[]
for e in entries:
 if e['zone']==2 and e['take']==0:assert e['sha256']==HARD_PREVIEWS[e['treatment']]
 f=source/e['file'];assert hashlib.sha256(f.read_bytes()).hexdigest()==e['sha256']
 pcm=subprocess.check_output(['ffmpeg','-v','error','-i',str(f),'-f','s16le','-'])
 assert len(pcm)//2==round(e['seconds']*44100)
 b=io.BytesIO()
 with wave.open(b,'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(44100);w.writeframes(pcm)
 name=f"{e['treatment']}/{e['zone']}-{e['take']}.wav";data=b.getvalue();assets.append((name,data,e))
for name,data,e in assets:
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
(out/'REFERENCE-LICENSE.txt').write_bytes((source/'REFERENCE-LICENSE.txt').read_bytes())
(out/'manifest.json').write_text(json.dumps({'version':1,'selection':'Owner selected B (Tighter body) after Round 7; soft/medium and alternate takes extend that selection.', 'audition':'https://velour-drum-audition.bertcalm.chatgpt.site/round7/','reference':manifest['reference'],'license':'CC0-1.0','sourceCommit':manifest['sourceCommit'],'assets':[{'file':name,'sha256':hashlib.sha256(data).hexdigest(),'previewSha256':e['sha256'],'frames':len(data[44:])//2} for name,data,e in assets]},indent=2))
print(f'Imported {len(assets)} exact PCM16 preview files ({sum(len(d) for _,d,_ in assets)} bytes).')
