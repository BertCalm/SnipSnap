"""Serve the verified APK through bounded static assets with client checksum verification."""
from pathlib import Path
import argparse,hashlib,json,re
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('dist', type=Path)
parser.add_argument('apk', type=Path)
parser.add_argument('--page', default='kit-and-phone.html')
args=parser.parse_args()
root=args.dist;assets=root/'android-preview'
apk=args.apk.read_bytes()
report=json.loads((assets/'backwash-preview-verification.json').read_text())
assert hashlib.sha256(apk).hexdigest()==report['apkSha256']
parts=[]
for i,offset in enumerate(range(0,len(apk),8*1024*1024),1):
 name=f'backwash-apk.part{i}';(assets/name).write_bytes(apk[offset:offset+8*1024*1024]);parts.append('android-preview/'+name)
assert hashlib.sha256(b''.join((root/p).read_bytes() for p in parts)).hexdigest()==report['apkSha256']
(assets/'snipsnap-backwash-preview.apk').unlink(missing_ok=True)
p=root/args.page;s=p.read_text().replace('<a class="download" download href="android-preview/snipsnap-backwash-preview.apk">Download Android preview · 63 MB</a>','<button class="download" id="download-apk" style="font:inherit;border:0;cursor:pointer">Download Android preview · 63 MB</button><span id="apk-status" role="status" style="display:block;margin-top:10px"></span>')
script='''const apkButton=document.getElementById('download-apk');
apkButton.addEventListener('click',async()=>{
 const status=document.getElementById('apk-status'); apkButton.disabled=true;
 try {
  const urls=PARTS;const chunks=[];let total=0;
  for(let i=0;i<urls.length;i++){
   status.textContent=`Preparing download: part ${i+1} of ${urls.length}…`;
   const response=await fetch(urls[i],{credentials:'same-origin'});
   if(!response.ok)throw new Error('Download interrupted.');
   const bytes=new Uint8Array(await response.arrayBuffer());chunks.push(bytes);total+=bytes.length;
  }
  if(total!==SIZE)throw new Error('Incomplete download.');
  const apk=new Uint8Array(total);let offset=0;
  for(const chunk of chunks){apk.set(chunk,offset);offset+=chunk.length;}
  status.textContent='Checking the download…';
  const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',apk))).map(b=>b.toString(16).padStart(2,'0')).join('');
  if(hash!==HASH)throw new Error('Download checksum did not match.');
  const url=URL.createObjectURL(new Blob([apk],{type:'application/vnd.android.package-archive'}));
  const link=document.createElement('a');link.href=url;link.download='snipsnap-backwash-preview.apk';document.body.append(link);link.click();link.remove();
  setTimeout(()=>URL.revokeObjectURL(url),60000);
  status.textContent='APK verified. Open snipsnap-backwash-preview.apk from your downloads to install.';
 } catch(error){status.textContent=error.message+' Please try again.';}
 finally{apkButton.disabled=false;}
});'''.replace('PARTS',json.dumps(parts)).replace('SIZE',str(len(apk))).replace('HASH',json.dumps(report['apkSha256']))
s=re.sub(r'<script>const apkButton=.*?</script>', '', s, flags=re.S)
s=s.replace('</body>','<script>'+script+'</script></body>');p.write_text(s)
(assets/'apk-download.js').write_text(script)
print(f'{len(parts)} bounded parts reassemble to the verified APK exactly.')
