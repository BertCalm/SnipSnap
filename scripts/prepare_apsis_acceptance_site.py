"""Add the native acceptance bank while preserving every earlier PCM take.

Playback WAVs use lossless gzip storage. Original score-source WAVs stay at their
original paths for command-line replay; browser downloads unpack real WAV bytes.
This stages files only. Use the Sites source/publishing workflow to publish.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import shutil


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--site', type=Path, required=True)
    parser.add_argument('--renders', type=Path, default=Path('testkit/apsis-acceptance'))
    args = parser.parse_args()
    dist = args.site / 'dist'
    packed = dist / 'data/manifest.json.gz'
    manifest = json.loads(gzip.decompress(packed.read_bytes()))
    old = [c for c in manifest['clips'] if c.get('group') not in ('acceptance', 'acceptance-pads')]
    kit = json.loads((args.renders / 'kit-manifest.json').read_text())
    performances = json.loads((args.renders / 'performances.json').read_text())['clips']
    archive = args.renders / kit['archive']
    shutil.copyfile(Path(__file__).resolve().parents[1] / 'docs/APSIS-ACCEPTANCE.md', dist / 'downloads/APSIS-ACCEPTANCE.md')
    shutil.copyfile(archive, dist / 'downloads' / archive.name)
    shutil.copyfile(archive.with_suffix('.zip.sha256'), dist / 'downloads' / (archive.name + '.sha256'))
    out = dist / 'acceptance'
    out.mkdir(exist_ok=True)
    (out / 'kit-manifest.json').write_text(json.dumps(kit, indent=2) + '\n')
    shutil.copytree(args.renders / kit['name'] / 'recipes', out / 'recipes', dirs_exist_ok=True)
    shutil.copytree(args.renders / 'scores', out / 'scores', dirs_exist_ok=True)
    shutil.copyfile(args.renders / kit['name'] / 'README.txt', out / 'MPC-CHECKLIST.txt')
    clips, pads = [], []
    for pad in kit['pads']:
        layer = pad['layers'][-1]
        target = out / 'pads' / (pad['id'] + '.wav')
        target.parent.mkdir(exist_ok=True)
        shutil.copyfile(args.renders / layer['sample'], target)
        assert sha(target.read_bytes()) == layer['sha256']
        path = target.relative_to(dist).as_posix()
        clips.append(dict(id=pad['id'], title=pad['label'] + ' / ' + pad['name'], voice=pad['voice'],
                          group='acceptance-pads', kind='pad', padSlot=pad['slot'], modelVersion=3,
                          midi=pad['midi'], velocity=1., macros=pad['patch']['macros'],
                          description=pad['role'] + '. Actual exported hard layer; both listening modes use the same native PCM.',
                          loop=pad['loop'], durationSeconds=layer['frames']/44100,
                          rawFile=path, matchedFile=path, rawSha256=layer['sha256'], matchedSha256=layer['sha256'],
                          recipeFile='acceptance/recipes/' + pad['label'] + '.json',
                          gainPolicy='Native exported PCM at pad level 1. No audition matching; master volume still applies.'))
        pads.append(dict(slot=pad['slot'], label=pad['label'], name=pad['name'], role=pad['role'], midi=pad['midi'],
                         held=pad['loop'], durationSeconds=layer['frames']/44100, previewClipId=pad['id'],
                         velocityZones=[dict(low=l['velStart'], high=l['velEnd'], renderVelocity=l['renderVelocity']) for l in pad['layers']]))
    bank = dict(title=kit['name'], modelVersion=3, pads=pads,
                download=dict(href='downloads/'+archive.name, bytes=archive.stat().st_size),
                verificationText='16 pads, 48 native mono 44.1 kHz PCM24 samples. Both MPC program readbacks, SMPL metadata, exact PCM and preflight checks passed. Physical MPC acceptance is pending.')
    (out / 'bank.json').write_text(json.dumps(bank, indent=2) + '\n')
    for clip in performances:
        paths = {}
        hashes = {}
        for key in ('rawFile', 'matchedFile'):
            target = out / clip[key]
            target.parent.mkdir(exist_ok=True)
            shutil.copyfile(args.renders / clip[key], target)
            paths[key] = target.relative_to(dist).as_posix()
            hashes['rawSha256' if key == 'rawFile' else 'matchedSha256'] = sha(target.read_bytes())
        clips.append(dict(id=clip['id'], title=clip['title'], group='acceptance', kind='score', modelVersion=3,
                          tempoBpm=80,
                          description=clip['description'], loop=False,
                          durationSeconds=clip['frames']/44100, recipeFile='acceptance/'+clip['score'],
                          gainPolicy='Raw keeps the score’s fixed .35 monitor gain. Matched applies one gain to the entire score; relative attacks remain intact.',
                          metrics=dict(rawPeak=clip['rawPeak'], listeningGain=clip['matchedGain']), **paths, **hashes))
    manifest['clips'] = clips[16:] + clips[:16] + old
    manifest['clipCount'] = len(manifest['clips'])
    manifest['acceptanceKit'] = dict(name=kit['name'], archive='downloads/'+archive.name,
                                   sha256=sha(archive.read_bytes()), sourceRevision=kit['sourceRevision'])
    protected = set()
    for p in (dist / 'music/scores').glob('*.json'):
        score = json.loads(p.read_text())
        protected.update(l['sample'] for pad in score.get('pads', []) for l in pad.get('layers', []))
    storage = {}
    for clip in manifest['clips']:
        for key, hashkey in [('rawFile', 'rawSha256'), ('matchedFile', 'matchedSha256')]:
            path = clip.get(key)
            if not path:
                continue
            file = dist / path
            if file.is_file() and file.suffix == '.wav' and path not in protected:
                raw = file.read_bytes()
                if clip.get(hashkey):
                    assert sha(raw) == clip[hashkey], path
                packed_file = file.with_suffix('.wav.gz')
                data = gzip.compress(raw, mtime=0)
                assert gzip.decompress(data) == raw
                packed_file.write_bytes(data)
                storage[path] = dict(sha256=sha(raw), wavBytes=len(raw), storedBytes=len(data))
                file.unlink()
                # Shared raw/matched paths and repeated references resolve below.
            if not file.exists() and file.with_suffix(file.suffix + '.gz').exists():
                clip[key] = path + '.gz'
            actual = dist / clip[key]
            raw = gzip.decompress(actual.read_bytes()) if actual.suffix == '.gz' else actual.read_bytes()
            if actual.suffix == '.gz':
                storage[clip[key][:-3]] = dict(sha256=sha(raw), wavBytes=len(raw), storedBytes=actual.stat().st_size)
            if clip.get(hashkey):
                assert sha(raw) == clip[hashkey], clip['id']
    packed.write_bytes(gzip.compress(json.dumps(manifest, separators=(',', ':')).encode(), mtime=0))
    (out / 'lossless-storage.json').write_text(json.dumps(dict(policy='Byte-identical WAV restoration; original source-score paths retained.', files=storage), indent=2)+'\n')

    js = dist / 'app.js'
    text = js.read_text()
    if 'async function fetchWavBytes(' not in text:
        text = text.replace('// One decoded source at a time.', '''// Lossless storage keeps the original WAV bytes available to playback and download.
async function fetchWavBytes(path) {
  const response = await fetch(path);
  if (!response.ok) throw new Error('Audio returned ' + response.status);
  return path.endsWith('.gz')
    ? new Response(response.body.pipeThrough(new DecompressionStream('gzip'))).arrayBuffer()
    : response.arrayBuffer();
}
async function downloadWav(path) {
  const bytes = await fetchWavBytes(path);
  const url = URL.createObjectURL(new Blob([bytes], {type: 'audio/wav'}));
  const link = document.createElement('a');
  link.href = url; link.download = path.split('/').pop().replace(/\\.gz$/, '');
  document.body.append(link); link.click(); link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
}

// One decoded source at a time.''')
        text = text.replace("fetch(source).then(response => {\n        if (!response.ok) throw new Error('Audio returned ' + response.status);\n        return response.arrayBuffer();\n      })", 'fetchWavBytes(source)')
        text = text.replace("wav.href = fileFor(clip) || '#'; wav.download = '';", """wav.href = fileFor(clip) || '#'; wav.download = '';
  wav.addEventListener('click', async event => {
    event.preventDefault(); const path = fileFor(clip); if (!path) return;
    wav.setAttribute('aria-busy', 'true');
    try { await downloadWav(path); }
    catch (_) { message.textContent = 'The WAV download failed. Please try again.'; }
    finally { wav.removeAttribute('aria-busy'); }
  });""")
    if '"acceptance":' not in text:
        text = text.replace('const GROUPS = {', '''const GROUPS = {
  "acceptance": {number: "01", name: "MPC ACCEPTANCE", title: "Grain and Anchor, on pads.", description: "Actual exported samples: a short performance, native touch layers, same-pad restart versus two-pad carry, and held wrap/release checks. Download the bank for the physical MPC pass."},
  "acceptance-pads": {number: "02", name: "GRAIN + ANCHOR PADS", title: "Sixteen pads. Six gestures.", description: "The hard layers from the new MPC bank at their native level. A13/A14 duplicate A01/A05 for carry comparisons; A15/A16 repeat while held. Soft and normal layers are in the download."},''')
        text = text.replace("renderGroup(clips.some(clip => clip.normalizedGroup === 'kotlin-port')", "renderGroup(clips.some(clip => clip.normalizedGroup === 'acceptance') ? 'acceptance' : clips.some(clip => clip.normalizedGroup === 'kotlin-port')")
    text = text.replace("typeof clip.prototype === 'string' ? 'Study settings ↓' : 'Recipe ↓'", "typeof clip.prototype === 'string' ? 'Study settings ↓' : clip.kind === 'score' ? 'Score ↓' : 'Recipe ↓'")
    text = text.replace("renderGroup('pads');\n      document.getElementById('listening-room')", "renderGroup(preview?.normalizedGroup || 'pads');\n      document.getElementById('listening-room')")
    if 'let bankRequest = 0;' not in text:
        text = text.replace('async function loadBank() {', "let bankRequest = 0;\nasync function loadBank(path = 'acceptance/bank.json') {\n  const request = ++bankRequest;")
        text = text.replace("fetch('data/bank.json', { cache: 'no-cache' })", "fetch(path, { cache: 'no-cache' })")
        text = text.replace('renderBank(await response.json());', "const bank = await response.json();\n    if (request !== bankRequest) return;\n    renderBank(bank);")
        text = text.replace('loadBank();', '''document.querySelectorAll('[data-bank-source]').forEach(button => {
  button.addEventListener('click', () => {
    audio.pause();
    document.querySelectorAll('[data-bank-source]').forEach(b => b.setAttribute('aria-pressed', String(b === button)));
    loadBank(button.dataset.bankSource);
  });
});
loadBank();''')
    js.write_text(text)
    html = dist / 'index.html'
    text = html.read_text()
    text = text.replace('One orbit, six responses. Compare plucked tethers, ringing vessels, airy coronas, granular contacts and pressure-driven bass with a PASSAGE study reference.', 'GRAIN and ANCHOR, ready for pads. Compare touch, overlapping tails and held notes in a native MPC kit. Earlier voice studies remain below.')
    text = text.replace('Compare GRAIN and ANCHOR in SnipSnap’s Kotlin engine against the preferred Python studies. Earlier voices and attack revisions remain available.', 'Play the GRAIN and ANCHOR MPC acceptance kit: six presets, three native touch layers, held pads and exported-sample performances.')
    text = text.replace('href="downloads/SnipSnap-Apsis-Kit.zip"', 'href="downloads/SnipSnap-Apsis-Grain-Anchor-Kit.zip"')
    text = text.replace('The existing musical examples and native bank still use Kotlin PASSAGE.', 'Earlier musical examples and the original bank use Kotlin PASSAGE; the new acceptance bank includes all six GRAIN and ANCHOR presets.')
    text = text.replace('Compare the Kotlin voices', 'Hear the MPC acceptance kit')
    text = text.replace('Existing PASSAGE MPC bank', 'Download the selected MPC bank')
    text = text.replace('GRAIN &amp; ANCHOR · KOTLIN PORT', 'GRAIN &amp; ANCHOR · MPC ACCEPTANCE')
    text = text.replace('GRAIN + ANCHOR in Kotlin <span>·</span> Python comparisons <span>·</span> Dry audio', 'Six presets <span>·</span> Three native touch layers <span>·</span> MPC 3 + MPC 2')
    text = re.sub(r'<p>GRAIN and ANCHOR now render.*?</p>', '<p>Start with MPC acceptance. Every note in these performances comes from the downloadable kit’s exported WAVs. Compare soft, normal and hard touch; same-pad restart and two-pad carry; then held repetition and release. The earlier Kotlin/Python comparisons and all previous studies remain available.</p>', text)
    if 'data-filter="acceptance"' not in text:
        text = text.replace('<button type="button" data-filter="kotlin-port" aria-pressed="true">', '<button type="button" data-filter="acceptance" aria-pressed="true"><span>01</span> MPC acceptance</button><button type="button" data-filter="acceptance-pads" aria-pressed="false"><span>02</span> Grain + Anchor pads</button><button type="button" data-filter="kotlin-port" aria-pressed="false">')
    if 'data-bank-source=' not in text:
        text = text.replace('<div class="bank-panel"><div class="bank-panel-heading">', '<div class="bank-panel"><nav class="group-filters" aria-label="Choose native bank"><button type="button" data-bank-source="acceptance/bank.json" aria-pressed="true">GRAIN + ANCHOR</button><button type="button" data-bank-source="data/bank.json" aria-pressed="false">Original PASSAGE</button></nav><div class="bank-panel-heading">')
    if 'acceptance/MPC-CHECKLIST.txt' not in text:
        text = text.replace('<nav class="footer-evidence" aria-label="Source and audition evidence">', '<nav class="footer-evidence" aria-label="Source and audition evidence"><a href="acceptance/MPC-CHECKLIST.txt" download>MPC acceptance checklist ↓</a><a href="acceptance/kit-manifest.json" download>Acceptance bank data ↓</a>')
    if 'downloads/APSIS-ACCEPTANCE.md' not in text:
        text = text.replace('<nav class="footer-evidence" aria-label="Source and audition evidence">', '<nav class="footer-evidence" aria-label="Source and audition evidence"><a href="downloads/APSIS-ACCEPTANCE.md" download>Acceptance kit notes ↓</a>')
    version = sha(js.read_bytes())[:12]
    text = re.sub(r'<script src="app.js[^\"]*"', f'<script src="app.js?build={version}"', text)
    html.write_text(text)
    total = sum(p.stat().st_size for p in dist.rglob('*') if p.is_file())
    assert total < 254*1024*1024, total
    print(json.dumps(dict(clips=len(manifest['clips']), pads=len(pads), archiveBytes=archive.stat().st_size,
                          expandedBytes=total, losslesslyPacked=len(storage))))


if __name__ == '__main__':
    main()
