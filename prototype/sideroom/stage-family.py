"""Stage a bounded website; preserve exact model bytes using gzip for prior kits."""
from pathlib import Path
import gzip,json,shutil,sys
src,dest=map(Path,sys.argv[1:3]);assert src.resolve()!=dest.resolve()
if dest.exists():shutil.rmtree(dest)
shutil.copytree(src,dest,ignore=shutil.ignore_patterns('open-road-acoustic.zip'))
m=json.loads((dest/'manifest.json').read_text());m['model_encoding']={}
for kit,name in list(m['models'].items()):
 if name=='synthesis-model.json':m['model_encoding'][kit]='json';continue
 raw=(dest/name).read_bytes();packed=gzip.compress(raw,mtime=0);assert gzip.decompress(packed)==raw
 (dest/(name+'.gz')).write_bytes(packed);(dest/name).unlink();m['models'][kit]=name+'.gz';m['model_encoding'][kit]='gzip'
(dest/'manifest.json').write_text(json.dumps(m,indent=2))
files=[f for f in dest.rglob('*') if f.is_file()];total=sum(f.stat().st_size for f in files)
assert total+(len(files)+32)*2048<256*1024*1024,total  # Conservative tar headers/padding allowance.
assert max(f.stat().st_size for f in files)<32*1024*1024
print('Staged',round(total/1024**2,1),'MiB; all prior model bytes preserved losslessly')
