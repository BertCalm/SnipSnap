"""Stage Kotlin/Python comparisons in an already opened audition Site checkout.

This prepares files only. Publication uses the Sites source/publishing workflow.
Existing audio remains byte-identical. Large JSON evidence is losslessly gzipped
to fit hosting's expanded archive limit; browsers decompress the main manifest.
"""
import argparse
import copy
import gzip
import hashlib
import json
from pathlib import Path
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--site', type=Path, required=True)
    parser.add_argument('--renders', type=Path, default=Path('testkit/apsis-populations'))
    args = parser.parse_args()
    dist = args.site / 'dist'
    plain = dist / 'data/manifest.json'
    packed = plain.with_suffix('.json.gz')
    manifest = json.loads(plain.read_text() if plain.exists() else gzip.decompress(packed.read_bytes()))
    rendered = json.loads((args.renders / 'manifest.json').read_text())
    report = json.loads((args.renders / 'prototype-comparison.json').read_text())
    assert len(report['comparisons']) == 12
    original = [c for c in manifest['clips'] if c.get('group') != 'kotlin-port']
    by_id = {c['id']: c for c in original}
    new = []
    for voice in ('GRAIN', 'ANCHOR'):
        for kind, suffix in [('c4', 'identity_C4'), ('phrase', 'phrase'), ('held', 'held_cycle')]:
            source = by_id[voice.lower() + '_' + suffix]
            comparison = voice.lower() + '_' + kind + '_port'
            before = copy.deepcopy(source)
            before.update(id='port_reference_' + source['id'], group='kotlin-port',
                          title=voice.title() + ' / Python / ' + kind,
                          comparisonGroup=comparison)
            new.append(before)
            clip = next(c for c in rendered['clips'] if c['voice'] == voice and c['kind'] == kind)
            after = dict(id=clip['id'], voice=voice, group='kotlin-port', comparisonGroup=comparison,
                         title=voice.title() + ' / Kotlin / ' + kind, modelVersion=3,
                         midi=clip.get('midi'), velocity=clip.get('velocity'),
                         description='The port in SnipSnap’s Kotlin engine, with the house output filters. '
                         + ('Two genuine settled cycles; Repeat continues the return.' if kind == 'held' else
                            'The same three-note score and complete tails as the study.' if kind == 'phrase' else
                            'The same C4 encounter at native velocity .65.'),
                         loop=kind == 'held', kind='score' if kind == 'phrase' else 'note',
                         scoreSummary=source.get('scoreSummary'), durationSeconds=clip['frames'] / 44100,
                         gainPolicy='Whole-take loudest-200ms RMS match at .12, peak cap .85; '
                         'raw uses the study’s original shared monitor gain. No individual attack matching.',
                         metrics={k:clip[k] for k in ('rawPeak','rawRms','rawDc','stateError','seamError') if k in clip})
            for key in ('rawFile', 'matchedFile'):
                target = Path('populations') / clip[key]
                (dist / target).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(args.renders / clip[key], dist / target)
                after[key] = target.as_posix()
                after['rawSha256' if key == 'rawFile' else 'matchedSha256'] = hashlib.sha256((dist / target).read_bytes()).hexdigest()
            if 'recipe' in clip:
                target = Path('populations') / clip['recipe']
                (dist / target).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(args.renders / clip['recipe'], dist / target)
                after['recipeFile'] = target.as_posix()
                after['macros'] = json.loads((args.renders / clip['recipe']).read_text())['macros']
            new.append(after)
    manifest['clips'] = new + original
    manifest['clipCount'] = len(manifest['clips'])
    manifest['populationPort'] = report
    packed.write_bytes(gzip.compress(json.dumps(manifest,separators=(',',':')).encode(), mtime=0))
    if plain.exists(): plain.unlink()
    for name in ('history-model2.json', 'performance-manifest.json'):
        path = dist / 'data' / name
        if path.exists():
            data = path.read_bytes()
            path.with_suffix('.json.gz').write_bytes(gzip.compress(data, mtime=0))
            assert gzip.decompress(path.with_suffix('.json.gz').read_bytes()) == data
            path.unlink()
    shutil.copyfile(args.renders / 'prototype-comparison.json', dist / 'populations/prototype-comparison.json')
    shutil.copyfile(args.renders / 'manifest.json', dist / 'populations/render-manifest.json')
    js = dist / 'app.js'
    text = js.read_text()
    if '"kotlin-port":' not in text:
        text = text.replace('const GROUPS = {', 'const GROUPS = {\n  "kotlin-port": {number: "↳", name: "KOTLIN PORT", title: "From study to SnipSnap.", description: "GRAIN and ANCHOR: Python and Kotlin at the same note, in the same phrase, and held. Compare the character across each pair. Earlier candidates and attack revisions remain below."},')
        text = text.replace("renderGroup(clips.some(clip => clip.normalizedGroup === 'attack-revision')", "renderGroup(clips.some(clip => clip.normalizedGroup === 'kotlin-port') ? 'kotlin-port' : clips.some(clip => clip.normalizedGroup === 'attack-revision')")
    text = text.replace("fetch('data/manifest.json',", "fetch('data/manifest.json.gz',")
    text = text.replace('const data = await response.json();\n    if (!Array.isArray(data.clips)', "const data = await new Response(response.body.pipeThrough(new DecompressionStream('gzip'))).json();\n    if (!Array.isArray(data.clips)")
    js.write_text(text)
    html = dist / 'index.html'
    text = html.read_text()
    text = text.replace('ATTACKS &amp; OVERLAP · STUDY 05', 'GRAIN &amp; ANCHOR · KOTLIN PORT').replace('ATTACKS & OVERLAP · STUDY 05', 'GRAIN &amp; ANCHOR · KOTLIN PORT')
    text = text.replace('Compare the revised attacks', 'Compare the Kotlin voices')
    text = text.replace('Python prototypes <span>·</span> Five new candidates <span>·</span> Android voices pending', 'GRAIN + ANCHOR in Kotlin <span>·</span> Python comparisons <span>·</span> Dry audio')
    start = text.index('<p>Start with Attacks &amp; overlap:') if '<p>Start with Attacks &amp; overlap:' in text else text.find('<p>Start with Attacks & overlap:')
    if start >= 0:
        end = text.index('</p>', start) + 4
        text = text[:start] + '<p>GRAIN and ANCHOR now render in SnipSnap’s Kotlin engine. Start with Kotlin port: compare each against the preferred Python study at C4, in the same phrase, and held. The original six candidates and the VESSEL/CORONA attack revisions remain available. Phone and MPC listening acceptance is still pending.</p>' + text[end:]
    if 'data-filter="kotlin-port"' not in text:
        text = text.replace('<button type="button" data-filter="attack-revision" aria-pressed="true">', '<button type="button" data-filter="kotlin-port" aria-pressed="true"><span>↳</span> Kotlin port</button><button type="button" data-filter="attack-revision" aria-pressed="false">')
    text = text.replace('Candidate names, musical usefulness and the production port await listening.', 'GRAIN and ANCHOR now have Kotlin renderers and three starter presets each. Candidate names and musical usefulness still await listening.')
    text = text.replace('href="data/manifest.json"', 'href="data/manifest.json.gz"')
    if 'populations/prototype-comparison.json' not in text:
        text = text.replace('<nav class="footer-evidence" aria-label="Source and audition evidence">', '<nav class="footer-evidence" aria-label="Source and audition evidence"><a href="populations/prototype-comparison.json" download>Kotlin fidelity measurements ↓</a><a href="downloads/APSIS-POPULATIONS.md" download>Kotlin port notes ↓</a>')
    text = text.replace('Compare original and revised Vessel and Corona: varied contacts and resonance that carries into the next attack. Grain and Anchor remain preferred references.', 'Compare GRAIN and ANCHOR in SnipSnap’s Kotlin engine against the preferred Python studies. Earlier voices and attack revisions remain available.')
    html.write_text(text)
    expanded = sum(p.stat().st_size for p in dist.rglob('*') if p.is_file())
    assert expanded < 256 * 1024 * 1024, expanded
    print(json.dumps(dict(clips=len(manifest['clips']), newCards=len(new), expandedBytes=expanded)))


if __name__ == '__main__':
    main()
