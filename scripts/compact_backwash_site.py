"""Keep unlinked historical sweep WAVs in their ZIP to fit the static hosting budget."""
from pathlib import Path
from html.parser import HTMLParser
from urllib.parse import unquote
import zipfile
import sys
root=Path(sys.argv[1]);refs=set()
class Links(HTMLParser):
 def handle_starttag(self,tag,attrs):
  for key,value in attrs:
   if key in ('src','href') or tag=='option' and key=='value':refs.add(unquote(value).split('#')[0])
for page in root.glob('*.html'):Links().feed(page.read_text())
removed=0
for directory, name in [(root/'revision-3', 'backwash-revision-3.zip'), (root, 'backwash-revision-2.zip')]:
 with zipfile.ZipFile(directory/name) as archive:
  for wav in directory.glob('*.wav'):
   if str(wav.relative_to(root)) not in refs and wav.name in archive.namelist():
    assert wav.read_bytes()==archive.read(wav.name)
    removed+=wav.stat().st_size;wav.unlink()
files=[p for p in root.rglob('*') if p.is_file()]
assert max(p.stat().st_size for p in files)<=25*1024*1024
assert sum(p.stat().st_size for p in files)<256*1024*1024
print(f'Removed {removed} bytes of duplicate, unlinked sweeps; every removed WAV remains in its download ZIP.')
