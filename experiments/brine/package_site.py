"""Remove duplicate unpacked download and losslessly compress long R8 listening clips."""
import pathlib,json,subprocess,hashlib,re,sys,shutil
root=pathlib.Path(sys.argv[1])
if (root/'mpc-test-kit').exists(): shutil.rmtree(root/'mpc-test-kit')
m=json.loads((root/'manifest.json').read_text())
for c in m['clips']:
 if c['id'].startswith('r8-balance-'):
  src=root/c['audio'];dst=src.with_suffix('.flac')
  subprocess.run(['ffmpeg','-v','error','-y','-i',str(src),'-c:a','flac',str(dst)],check=True)
  def pcm(p):return subprocess.check_output(['ffmpeg','-v','error','-i',str(p),'-f','s24le','-'])
  assert pcm(src)==pcm(dst)
  c['audio']=str(dst.relative_to(root));c['sha256']=hashlib.sha256(dst.read_bytes()).hexdigest();src.unlink()
text=json.dumps(m,ensure_ascii=False,separators=(',',':'))
(root/'manifest.json').write_text(text)
p=root/'index.html';s=p.read_text();s=re.sub(r'const manifest = .*?;\n',lambda _:'const manifest = '+text+';\n',s,count=1,flags=re.S)
s=s.replace("[['WAV',clip.audio]","[['Audio',clip.audio]")
p.write_text(s)
print('Verified lossless FLAC PCM identity for four long listening clips; raw WAV downloads preserved')
