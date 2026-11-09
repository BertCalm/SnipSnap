"""Feature a labeled, matched percussion comparison on the existing audition site."""
from pathlib import Path
import csv, html, re, shutil, sys, wave
from html.parser import HTMLParser
from urllib.parse import urlsplit, unquote
src, dist = map(Path, sys.argv[1:3])
assets = dist/'percussion-revision-3'
shutil.copytree(src, assets, dirs_exist_ok=True)
rows = list(csv.DictReader((src/'matching.csv').open()))
assert len(rows) == 12
for row in rows:
    assert abs(float(row['target'])-float(row['measured'])) < 1e-5
    assert float(row['peak']) <= .85001
    with wave.open(str(src/f"{row['voice']}-{row['stage']}.wav")) as w:
        assert (w.getnchannels(), w.getframerate(), w.getsampwidth()) == (1,44100,3)
section = '''<section id="new-percussion"><h2>Percussion rebuilt around your references</h2><p>The new dry sources target a wooden cross-stick, the cowbell character in your groove, bright mounted-tambourine jingles, and a sustained ride-bell tone. Compare the last candidate with this revision before adding the effects.</p><p class="hint">Playback levels are matched within each instrument using the same loudness measure, with peak headroom and no extra limiting. Matching is approximate to hearing; the natural attack and tail remain different.</p><div class="grid">'''
for voice, label in [('cross_stick','Cross-stick'),('tambourine','Tambourine'),('cowbell','Cowbell'),('ride','Ride cymbal')]:
    section += f'<article><h3>{label}</h3>'
    for stage, title in [('previous-dry','1 · Last candidate — dry'),('dry','2 · Reference-guided revision — dry'),('lower-drive','3 · Revised source — lower-drive processing')]:
        section += f'<p>{title}</p><audio controls preload="none" aria-label="{label}: {html.escape(title)}" src="percussion-revision-3/{voice}-{stage}.wav"></audio>'
    section += '</article>'
section += '''</div><p>These source revisions are audition candidates. The existing <a href="pushed-kit.html">MPC kit</a> and <a href="kit-and-phone.html#phone-preview">Android preview</a> still contain the previous sources and processing.</p><details><summary>Comparison settings</summary><p>Cross-stick uses a short, filtered contact burst with rapidly damped wood modes. Cowbell shifts its main resonances toward the persistent peaks in the reference groove. Tambourine moves its jingle energy upward and concentrates it into a struck rattle. Ride emphasizes a sustained bell near 2.85 kHz with quieter plate modes. Reference recordings contain full grooves, so these are synthesis targets, not isolated measurements or sampled replicas. Processing retains SWAY 0.55, TRAIL 0.55, PRESS 0.75 and DRIVE 0.15. This audition preserves the full ride tail beyond the current engine’s 1.49-second limit.</p><a href="percussion-revision-3/matching.csv">Level-matching measurements</a> · <a href="percussion-revision-3/metrics.csv">Tail measurements</a></details></section>'''
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
