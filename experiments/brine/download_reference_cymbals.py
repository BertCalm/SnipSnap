import urllib.request,re,json,hashlib,concurrent.futures,subprocess,sys
from pathlib import Path
root=Path(sys.argv[1] if len(sys.argv)>1 else 'testkit/brine-references');root.mkdir(parents=True,exist_ok=True)
sha='9f04cf9a734527edfbb0a4eee1f674e45bbf71bc'
virt=[('ride-main','ride/mid_ride_ride_vl2_rr1.flac'),('ride-check','ride/mid_ride_ride_vl2_rr2.flac'),('ride-soft','ride/mid_ride_ride_vl1_rr1.flac'),('ride-bell','ride/mid_ride_bell_vl2_rr1.flac'),('flat-ride','flatride/mid_flatride_ride_vl2_rr1.flac'),('crash-main','crash/mid_crash_crash_vl2_rr1.flac')]
def get(item):
 name,url,license,page=item
 data=urllib.request.urlopen(url).read();p=root/(name+('.flac' if url.endswith('.flac') else '.mp3'));p.write_bytes(data)
 subprocess.run(['ffmpeg','-v','error','-y','-i',str(p),'-ac','1','-ar','44100','-c:a','pcm_f32le',str(root/(name+'.wav'))],check=True)
 return dict(id=name,url=url,page=page,license=license,sha256=hashlib.sha256(data).hexdigest(),downloadedFile=p.name)
items=[(name,f'https://raw.githubusercontent.com/sfzinstruments/virtuosity_drums/{sha}/Samples/mid/{path}','CC0-1.0','https://versilian-studios.com/virtuosity-drums/') for name,path in virt]
for name,n in [('choke-main',696202),('choke-check',696204)]:
 page=f'https://freesound.org/people/ankealtd/sounds/{n}/';s=urllib.request.urlopen(page).read().decode();(root/(name+'-page.html')).write_text(s)
 assert 'Creative Commons 0' in s or 'creativecommons.org/publicdomain/zero' in s
 urls=re.findall(r'https://cdn.freesound.org/previews/[^\s"<>]+-hq.mp3',s)
 assert urls
 items.append((name,urls[0],'CC0-1.0',page))
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool: manifest=list(pool.map(get,items))
(root/'sources.json').write_text(json.dumps({'virtuosityCommit':sha,'sources':manifest},indent=2))
license=urllib.request.urlopen(f'https://raw.githubusercontent.com/sfzinstruments/virtuosity_drums/{sha}/LICENSE').read();(root/'VIRTUOSITY-LICENSE.txt').write_bytes(license)
from scipy.io import wavfile
import numpy as np
for item in manifest:
 rate,x=wavfile.read(root/(item['id']+'.wav'));x=x.astype(float)
 on=np.flatnonzero(abs(x)>max(abs(x))*.015)[0];x=x[max(0,on-44):]
 wavfile.write(root/(item['id']+'-aligned.wav'),rate,x.astype('float32'))
for x in manifest:print(x['id'],x['downloadedFile'],x['sha256'][:12])
