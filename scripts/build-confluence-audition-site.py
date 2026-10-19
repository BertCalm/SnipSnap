#!/usr/bin/env python3
"""Stage the real renderer's listening evidence with the buildless audition UI."""
from pathlib import Path
import argparse
import hashlib
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
# Playback kits are downloadable ZIP/XPN assets. Their loose working folders
# duplicate the same WAVs and exceed the host's total expanded archive budget.
export_directories = {'rendered-kit', 'wordless-rendered-kit'}
for name in export_directories:
    target = output / name
    if target.is_dir():
        shutil.rmtree(target)
# Copy generated listening evidence; preserve unrelated output files.
for item in source.iterdir():
    if item.name.startswith('.') or item.name in export_directories:
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
role_family = [clip for clip in clips if clip.get('matchingPolicy') == 'shared-gathering-gain']
if role_family:
    for reference_id in {clip['matchedReferenceId'] for clip in role_family}:
        family = [clip for clip in role_family if clip['matchedReferenceId'] == reference_id]
        assert reference_id in {clip['id'] for clip in family}, 'Missing complete gathering reference'
        assert {clip['roleIds'][0] for clip in family if len(clip['roleIds']) == 1} == {'FOUNDATION', 'AIRY', 'ANSWER'}, 'The three-role listening family is incomplete'
        assert len({clip['matchedGain'] for clip in family}) == 1, 'Do not individually level the role stems'
        assert len({clip['rawExportGain'] for clip in family}) == 1, 'Role stems must share the full raw export gain'
        assert len({clip['frames'] for clip in family}) == 1, 'Role stems must retain the complete arrangement timeline'
        for clip in family:
            assert clip['roleIds'] and set(clip['roleIds']) <= {'FOUNDATION', 'AIRY', 'ANSWER'}
            assert clip['evidence']['sharedSchedule'] and clip['evidence']['sharedMasterGain']
        airy_clip = next(clip for clip in family if clip['roleIds'] == ['AIRY'])
        assert len(airy_clip['sourceIds']) == 1, 'The upper role needs one explicit recorded source'
        airy = next(item for item in manifest['sources'] if item['id'] == airy_clip['sourceIds'][0])
        assert airy['license'] and airy['licenseUrl'] and airy['attribution'] and airy['modifications']
        assert hashlib.sha256((output / airy['path']).read_bytes()).hexdigest() == airy['sha256'], 'Airy source provenance does not match its WAV'
if 'starterVoiceBank' in manifest:
    bank_record = manifest['starterVoiceBank']
    bank_file = output / bank_record['manifestPath']
    bank = json.loads(bank_file.read_text())
    assert bank['id'] == bank_record['id'] and bank['version'] == bank_record['version']
    assert bank['mainPresetId'] == bank_record['mainPresetId']
    assert all(voice['wordless'] and voice['provenance']['wordless'] for voice in bank['voices'])
    assert all(not voice['provenance'].get('demonstrationOnly', False) for voice in bank['voices'])
    wordless = [clip for clip in clips if clip['group'] in ('wordless', 'bank_presets')]
    source_ids = {voice['fixtureId'] for voice in bank['voices']}
    assert wordless and all(set(clip['sourceIds']) <= source_ids for clip in wordless), 'Factory auditions may use only the wordless bank'
    assert all(clip.get('contentClass') == 'historical-demo' for clip in clips if clip['group'] == 'demo_roles')
# Preserve the renderer manifest byte-for-byte: independent reviews and the
# replay bundle identify it by SHA-256. Site staging metadata belongs separately.
publication = {
    'sourceRepository': 'https://github.com/BertCalm/SnipSnap/tree/codex/confluence-first-audition',
    'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(),
    'sourceTreeCleanAtPackaging': not bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo, text=True).strip()),
    'title': 'Confluence — SnipSnap audition', 'finitePrototype': True, 'maxPerformers': 8,
    'manifestSha256': hashlib.sha256((source / 'manifest.json').read_bytes()).hexdigest(),
    'threeRoleArrangement': bool(role_family),
    'exportWorkingDirectoriesOmitted': sorted(export_directories),
    'exportDownloadsRetained': True,
}
(output / 'site-build.json').write_text(json.dumps(publication, indent=2) + '\n')
for item in output.rglob('*'):
    if item.is_file():
        assert item.stat().st_size <= 25 * 1024 * 1024, f'Hosting asset exceeds 25 MiB: {item}'
expanded_bytes = sum(item.stat().st_size for item in output.rglob('*') if item.is_file())
assert expanded_bytes < 256 * 1024 * 1024, 'Static output exceeds the hosting expanded archive limit (256 MiB)'
print(json.dumps({'output': str(output), 'clips': len(clips), 'files': sum(p.is_file() for p in output.rglob('*'))}))
