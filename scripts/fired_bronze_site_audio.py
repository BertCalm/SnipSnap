"""Losslessly compact a static audition Site; downloadable MPC ZIPs remain unchanged.
Run after copying rendered packs into the Site. Every converted file is decoded
and compared with its original PCM bytes before the WAV copy is removed.
"""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import subprocess,sys,wave,hashlib,json
root=Path(sys.argv[1]).resolve()
def convert(p):
 with wave.open(str(p)) as w:
  bits=w.getsampwidth()*8;channels=w.getnchannels();rate=w.getframerate();pcm=w.readframes(w.getnframes())
 assert bits in (16,24)
 target=p.with_suffix('.flac')
 subprocess.run(['ffmpeg','-v','error','-y','-i',str(p),'-c:a','flac','-compression_level','8','-threads','1',str(target)],check=True)
 decoded=subprocess.check_output(['ffmpeg','-v','error','-i',str(target),'-f',f's{bits}le','-acodec',f'pcm_s{bits}le','-ac',str(channels),'-ar',str(rate),'-'])
 assert hashlib.sha256(decoded).digest()==hashlib.sha256(pcm).digest(),str(p)
 before=p.stat().st_size;after=target.stat().st_size;p.unlink()
 return before,after
files=list(root.rglob('*.wav'))
with ThreadPoolExecutor(max_workers=4) as pool: results=list(pool.map(convert,files))
for p in root.rglob('*.html'):
 s=p.read_text().replace('.wav','.flac').replace('>Native WAV<','>Native FLAC<')
 note='<p class="small">Page audio uses lossless FLAC. MPC download ZIPs retain WAV files.</p>'
 if note not in s:s=s.replace('</footer>',note+'</footer>')
 p.write_text(s)
# The old study manifest describes served audition paths, not source provenance.
for p in root.rglob('manifest.json'):
 s=p.read_text()
 import re
 def asset(m):
  value=m.group(1)
  return '"'+value[:-4]+'.flac"' if (p.parent/value).with_suffix('.flac').exists() else m.group(0)
 p.write_text(re.sub(r'"([^"\n]+\.wav)"',asset,s))
report={'verifiedFiles':len(results),'originalBytes':sum(x[0] for x in results),'losslessBytes':sum(x[1] for x in results),'decodedPcm':'SHA-256 identical for every file','zipDownloads':'unchanged'}
(root/'lossless-audio-verification.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report))
print('Expanded site bytes:',sum(p.stat().st_size for p in root.rglob('*') if p.is_file()))
