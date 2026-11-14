"""Feature a labeled, matched percussion comparison on the existing audition site."""
from pathlib import Path
import csv, html, re, shutil, sys, wave
from html.parser import HTMLParser
from urllib.parse import urlsplit, unquote
src, dist = map(Path, sys.argv[1:3])
assets = dist/'percussion-comparison-1'
shutil.copytree(src, assets, dirs_exist_ok=True)
rows = list(csv.DictReader((src/'matching.csv').open()))
assert len(rows) == 12
for row in rows:
    assert abs(float(row['target'])-float(row['measured'])) < 1e-5
    assert float(row['peak']) <= .85001
    with wave.open(str(src/f"{row['voice']}-{row['stage']}.wav")) as w:
        assert (w.getnchannels(), w.getframerate(), w.getsampwidth()) == (1,44100,3)
section = '''<section id="new-percussion"><h2>Dry source or processed?</h2><p>Compare the same hard strike three ways. Dry bypasses the chorus, compressor and amp. Lower drive keeps the same chorus and compression, with less amp drive. Previous is the processed sound from the last audition.</p><p class="hint">Playback levels are matched within each instrument using the same loudness measure, with peak headroom and no extra limiting. Matching is approximate to hearing; the natural attack and tail remain different.</p><div class="grid">'''
for voice, label in [('cross_stick','Cross-stick'),('tambourine','Tambourine'),('cowbell','Cowbell'),('ride','Ride cymbal')]:
    section += f'<article><h3>{label}</h3>'
    for stage, title in [('dry','1 · Dry source — effects bypassed'),('lower-drive','2 · Processed — lower amp drive'),('previous','3 · Processed — previous drive')]:
        section += f'<p>{title}</p><audio controls preload="none" aria-label="{label}: {html.escape(title)}" src="percussion-comparison-1/{voice}-{stage}.wav"></audio>'
    section += f'<p class="hint"><a download href="percussion-comparison-1/{voice}-lower-drive.json">Lower-drive recipe</a></p></article>'
section += '''</div><p>The lower-drive versions are audition candidates. The existing <a href="pushed-kit.html">MPC kit</a> and <a href="kit-and-phone.html#phone-preview">Android preview</a> still contain the previous processing.</p><details><summary>Comparison settings</summary><p>Only DRIVE changes: 0.65 → 0.15. SWAY 0.55, TRAIL 0.55 and PRESS 0.75 stay fixed. Dry uses the same source tuning, damping, deterministic strike and standard source output conditioning. Lower drive still includes amp coloration.</p><a href="percussion-comparison-1/matching.csv">Level-matching measurements</a></details></section>'''
p = dist/'index.html'
s = p.read_text()
s, count = re.subn(r'<section id="new-percussion">.*?</section>', lambda _:section, s, count=1, flags=re.S)
assert count == 1
p.write_text(s)
class Check(HTMLParser):
    def handle_starttag(self, tag, attrs):
        for key, value in attrs:
            if key in ('src','href') or tag == 'option' and key == 'value':
                u=urlsplit(value)
                if not u.scheme and u.path: assert (dist/unquote(u.path)).is_file(), value
Check().feed(s)
print('Verified 12 WAVs, level matching, and page links.')
