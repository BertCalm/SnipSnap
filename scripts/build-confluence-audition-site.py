#!/usr/bin/env python3
"""Stage the real renderer's listening evidence with the buildless audition UI."""
from pathlib import Path
import argparse
import json
import shutil
import subprocess

repo = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--audition', type=Path, default=repo / 'testkit/confluence-audition')
parser.add_argument('--output', type=Path, default=repo / 'testkit/confluence-audition-site')
args = parser.parse_args()
source, output = args.audition.resolve(), args.output.resolve()
manifest = json.loads((source / 'manifest.json').read_text())
clips = manifest['clips']
assert clips, 'Run :synth:generateConfluenceAudition first'
assert output != source and source not in output.parents, 'Keep site output separate from renderer output'
output.mkdir(parents=True, exist_ok=True)
# Copy only generated evidence. Never remove unrelated files in a supplied output directory.
for item in source.iterdir():
    if item.name.startswith('.'):
        continue
    target = output / item.name
    if item.is_dir():
        shutil.copytree(item, target, dirs_exist_ok=True)
    else:
        shutil.copy2(item, target)
for filename in ('index.html', 'styles.css', 'app.js'):
    shutil.copy2(repo / 'design/confluence-audition' / filename, output / filename)
for clip in clips:
    for field in ('rawPath', 'matchedPath', 'recipePath', 'diagnosticsPath'):
        relative = Path(clip[field])
        assert not relative.is_absolute() and '..' not in relative.parts, f'Unsafe {field}'
        assert (output / relative).is_file(), f'Missing {relative}'
    assert clip['finite'] and clip['channels'] == 1 and clip['sampleRate'] == 44100
manifest['sourceRepository'] = 'https://github.com/BertCalm/SnipSnap/tree/codex/confluence-first-audition'
manifest['sourceCommit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
manifest['sourceTreeCleanAtPackaging'] = not bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo, text=True).strip())
manifest['publication'] = {'title': 'Confluence — SnipSnap audition', 'finitePrototype': True, 'maxPerformers': 8}
(output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
for item in output.rglob('*'):
    if item.is_file():
        assert item.stat().st_size <= 25 * 1024 * 1024, f'Hosting asset exceeds 25 MiB: {item}'
print(json.dumps({'output': str(output), 'clips': len(clips), 'files': sum(p.is_file() for p in output.rglob('*'))}))
