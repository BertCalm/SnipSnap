import urllib.request,re,json,hashlib,subprocess,concurrent.futures
from pathlib import Path
root=Path('testkit/fired-bronze-r2/references')
root.mkdir(parents=True, exist_ok=True)
def fetch(url): return urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'}),timeout=40).read()
pack=fetch('https://freesound.org/people/ajaysm/packs/10737/').decode()
ids=sorted(set(re.findall(r'/people/ajaysm/sounds/(\d+)/',pack)))
def get(i):
 try:
  url=f'https://freesound.org/people/ajaysm/sounds/{i}/';s=fetch(url).decode()
  title=re.search(r'<title>(.*?)</title>',s,re.S).group(1).strip()
  if not any((' - '+x+' stroke') in title or ('> '+x+' stroke') in title or title.startswith(x+' stroke') for x in ['tin','tun','na','ga','te']):return {'id':i,'title':title,'skip':True}
  assert 'https://creativecommons.org/licenses/by/4.0/' in s
  audio=re.search(r'https://cdn.freesound.org/previews/[^\s"\u0027<>]+-hq.mp3',s).group(0)
  data=fetch(audio);mp=root/(i+'.mp3');mp.write_bytes(data)
  subprocess.run(['ffmpeg','-y','-v','error','-i',str(mp),'-ac','1','-ar','44100',str(root/(i+'.wav'))],check=True)
  return {'id':i,'title':title,'page':url,'preview':audio,'sha256':hashlib.sha256(data).hexdigest(),'license':'CC BY 4.0','author':'ajaysm','changes':'HQ MP3 preview decoded to mono 44.1 kHz; reference only, not original WAV'}
 except Exception as e:return {'id':i,'error':str(e)}
rows=list(concurrent.futures.ThreadPoolExecutor(max_workers=4).map(get,ids));print(json.dumps(rows,indent=2));(root/'provenance.json').write_text(json.dumps(rows,indent=2))
