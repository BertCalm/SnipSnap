"""Rebuild the compact v1/v2 comparison from saved Kotlin renders (Python 3 and ffmpeg). Historical acceptance fields describe the originating experiment."""
import argparse
from pathlib import Path
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from html import escape
import base64
import hashlib
import json
import math
import subprocess
import wave

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--render', type=Path, required=True, help='Kotlin render directory containing manifest.json and its WAV files')
parser.add_argument('--output', type=Path, required=True, help='Artifact parent; named delivery files and working subdirectories are created here')
parser.add_argument('--overwrite', action='store_true', help='Replace this helper\'s existing output files')
args = parser.parse_args()
ARTIFACTS = args.output.expanduser().resolve()
ROOT = args.render.expanduser().resolve()
if ARTIFACTS.is_relative_to(ROOT):
    parser.error('--output must be outside the input render directory')
if not (ROOT / 'manifest.json').is_file():
    parser.error('--render must contain manifest.json')
OUT = ARTIFACTS
WORK = OUT / 'resonant-body-v2-mobile'
existing = [p for p in [WORK, OUT / 'resonant-body-v2-comparison.html', OUT / 'resonant-body-v2-before-after.flac', OUT / 'resonant-body-v2-before-after-tracks.txt'] if p.exists()]
if existing and not args.overwrite:
    parser.error('Existing output: ' + ', '.join(str(p) for p in existing) + '; use --overwrite to replace it')
WORK.mkdir(parents=True, exist_ok=True)
manifest = json.loads((ROOT / 'manifest.json').read_text())
cases = manifest['cases']
by_id = {c['id']: c for c in cases}
groups = OrderedDict()
for c in cases:
    groups.setdefault(c['comparisonGroup'], []).append(c)
assert len(cases) == 24 and len(by_id) == 24
assert manifest['baselineVersion'] == 1 and manifest['candidateVersion'] == 2

def sha(data):
    return hashlib.sha256(data).hexdigest()

def command(args):
    return subprocess.run(args, check=True, capture_output=True).stdout

paths = {c['matched']: ('matched', c['matchedSha256']) for c in cases}
raw_cases = [c for c in cases if c['comparisonGroup'] in ('default-burst', 'default-sustained') and c['settings']['body'] in (0, 1)]
paths.update({c['raw']: ('raw', c['rawSha256']) for c in raw_cases})
paths.update({s['path']: ('source', s['sha256']) for s in manifest['sources'] if s['id'] in ('burst', 'harmonic')})
prior_file = WORK/'delivery-record.json'
prior_assets = json.loads(prior_file.read_text()).get('assets', {}) if prior_file.exists() else {}
before = {path: sha((ROOT / path).read_bytes()) for path in paths}
assert all(before[path] == expected for path, (_, expected) in paths.items())

def encode(item):
    path, (kind, expected) = item
    source = (ROOT / path).resolve()
    assert source.is_relative_to(ROOT.resolve())
    target = WORK / (path.replace('/', '--').removesuffix('.wav') + '.flac')
    prior = prior_assets.get(path)
    if prior and prior['inputSha256'] == expected and target.is_file() and sha(target.read_bytes()) == prior['outputSha256']:
        return path, prior
    command(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-i', str(source), '-map_metadata', '-1',
             '-c:a', 'flac', '-sample_fmt', 's32', '-bits_per_raw_sample', '24', '-compression_level', '12', '-metadata_header_padding', '0', '-threads', '1', str(target)])
    decoded = command(['ffmpeg', '-nostdin', '-v', 'error', '-i', str(target), '-f', 's24le', '-c:a', 'pcm_s24le', 'pipe:1'])
    if kind == 'matched':
        with wave.open(str(source), 'rb') as wav:
            assert wav.getsampwidth() == 3 and wav.getnchannels() == 1 and wav.getframerate() == 44100
            original_pcm = wav.readframes(wav.getnframes())
        assert decoded == original_pcm, f'Matched PCM changed: {path}'
    else:
        original_pcm = command(['ffmpeg', '-nostdin', '-v', 'error', '-i', str(source), '-f', 's24le', '-c:a', 'pcm_s24le', 'pipe:1'])
        assert decoded == original_pcm, f'Float-to-PCM24 conversion differs: {path}'
    return path, {'path': target.name, 'kind': kind, 'inputSha256': expected, 'outputSha256': sha(target.read_bytes()),
                  'bytes': target.stat().st_size, 'gainApplied': 1, 'resampled': False,
                  'exactSavedPcm24': kind == 'matched', 'float32QuantizedToPcm24': kind != 'matched',
                  'decodedPcm24Sha256': sha(decoded)}

with ThreadPoolExecutor(max_workers=4) as pool:
    assets = dict(pool.map(encode, paths.items()))

def uri(path):
    return 'data:audio/flac;base64,' + base64.b64encode((WORK/assets[path]['path']).read_bytes()).decode()

def player(path, label):
    return '<audio controls preload="none" aria-label="' + escape(label, quote=True) + '" src="' + uri(path) + '"></audio>'

# The direct first listen uses original matched PCM24, with complete clips and silent gaps only.
order = []
for group in ('default-burst', 'default-sustained'):
    sequence = [f'{group}-v1-harmonic', f'{group}-v1-bar', f'{group}-v2-harmonic', f'{group}-v2-bar']
    order += sequence + sequence
pcm_parts = []
timeline = []
frames = 0
gap_frames = round(.15 * 44100)
for index, case_id in enumerate(order, 1):
    c = by_id[case_id]
    with wave.open(str(ROOT/c['matched']), 'rb') as wav:
        assert wav.getsampwidth() == 3 and wav.getnchannels() == 1 and wav.getframerate() == 44100
        pcm = wav.readframes(wav.getnframes())
    n = len(pcm)//3
    timeline.append({'number':index,'id':case_id,'startSeconds':frames/44100,'durationSeconds':n/44100,
                     'matchedGain':c['matchedGain'],'matchedFileSha256':c['matchedSha256']})
    pcm_parts += [pcm, bytes(gap_frames*3)]
    frames += n + gap_frames
combined_pcm = b''.join(pcm_parts)
combined_wav = WORK/'before-after-pcm24.wav'
with wave.open(str(combined_wav), 'wb') as wav:
    wav.setnchannels(1); wav.setsampwidth(3); wav.setframerate(44100); wav.writeframes(combined_pcm)
direct = OUT/'resonant-body-v2-before-after.flac'
command(['ffmpeg','-nostdin','-v','error','-y','-i',str(combined_wav),'-map_metadata','-1','-c:a','flac','-compression_level','12','-metadata_header_padding','0',str(direct)])
decoded = command(['ffmpeg','-nostdin','-v','error','-i',str(direct),'-f','s24le','-c:a','pcm_s24le','pipe:1'])
assert decoded == combined_pcm
assert direct.stat().st_size < 500_000, 'Direct listen exceeded the requested small-download target'

def controls(c):
    s = c['settings']
    return f'Reference MIDI {c["rootMidi"]}, BODY {s["body"]:g}, RING {s["ring"]:g}, DAMP {s["damp"]:g}, MIX {s["mix"]:g}'

titles = {'default-burst':'Default noise burst', 'default-sustained':'Default sustained source',
          'c3-impulse':'Diagnostic: C3 impulse, long ring', 'long-ring-undamped':'Diagnostic: long ring, zero damping',
          'c5-impulse':'Diagnostic: C5 impulse, long ring'}
style = '''body{max-width:1000px;margin:1.3rem auto;padding:0 1rem 3rem;background:#151917;color:#e8eae2;font:16px/1.5 system-ui}h1,h2,h3{line-height:1.2}.pair{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.8rem}article{min-width:0;border:1px solid #475040;padding:.8rem;border-radius:6px}audio{display:block;width:100%;min-width:0;margin:.7rem 0}small{display:block;color:#c2c9bb;overflow-wrap:anywhere}.section{border-top:1px solid #62705c;padding-top:1rem;margin-top:2rem}details{margin:1.2rem 0}summary{cursor:pointer;font-size:1.1rem}code{overflow-wrap:anywhere}button{padding:.6rem .8rem;font:inherit;background:#d4eea4;color:#151917;border:0;border-radius:4px;cursor:pointer}li{margin:.35rem 0}@media(max-width:620px){.pair{grid-template-columns:1fr}}'''
parts = ['<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Resonant body v1 / v2 comparison</title><style>',style,'</style></head><body><h1>Resonant body: v1 / v2</h1><p><b>Old = model 1. New = model 2 candidate.</b> Compare the same body across versions, then compare HARMONIC and BAR within each version. The new candidate has not yet been accepted by listening.</p><p>All cases are fully wet (MIX 1), use the same generated excitation within each comparison, and keep the complete render and tail. Both versions use the same reference tuning, which fixes the first resonance; it does not promise the same perceived note, especially for BAR. This page is self-contained: no ZIP or internet connection is needed after download.</p><p>The main comparisons use the renderer\'s existing level-matched PCM24 files, preserved losslessly in FLAC. Matching gains are shown. The raw sections retain actual output levels and may be quiet; packaging adds no gain, EQ or resampling. Raw/dry float32 playback copies are converted to PCM24 and are not bit-exact float archives.</p>']

def card(c, raw=False):
    version = c['modelVersion']
    label = ('Old — v1' if version == 1 else 'New — v2 candidate')
    path = c['raw'] if raw else c['matched']
    parts.extend(['<article data-case="',escape(c['id'],quote=True),'" data-listen="',('raw' if raw else 'matched'),'"><h3>',escape(label),'</h3><small>',escape(controls(c)),'</small>',player(path, f'{label} {c["id"]} '+('raw original level' if raw else 'level matched')),
                  '<small>',escape(c['id']),'<br>Saved matching gain ×',f'{c["matchedGain"]:.3f}',(' (not applied in this raw player)' if raw else ''),'<br>Raw sample peak ',f'{c["samplePeak"]:.6f}',', raw RMS ',f'{c["rms"]:.6f}','</small></article>'])

for group, members in groups.items():
    parts += ['<section class="section" data-group="',escape(group,quote=True),'"><h2>',escape(titles[group]),'</h2>']
    if group in ('default-burst','default-sustained'):
        source = members[0]['source']
        parts += ['<details><summary>Dry generated source at its original level</summary>',player(source, group+' dry source original level'),'</details>']
        ids = [f'{group}-v1-harmonic',f'{group}-v1-bar',f'{group}-v2-harmonic',f'{group}-v2-bar']
        parts += ['<button type="button" data-sequence="',escape(','.join(ids),quote=True),'">Play four: old H, old BAR, new H, new BAR</button>']
    for body, family in [(0,'HARMONIC · BODY 0'),(.5,'Blend · BODY 0.5'),(1,'BAR · BODY 1')]:
        pair = [c for c in members if c['settings']['body'] == body]
        if not pair: continue
        assert len(pair)==2 and {c['modelVersion'] for c in pair}=={1,2}
        parts += ['<h3>',escape(family),'</h3><div class="pair">']
        for c in sorted(pair,key=lambda c:c['modelVersion']): card(c)
        parts += ['</div>']
    if group in ('default-burst','default-sustained'):
        old_bar = next(c for c in members if c['modelVersion']==1 and c['settings']['body']==1)
        new_bar = next(c for c in members if c['modelVersion']==2 and c['settings']['body']==1)
        bar_delta_db = 20*math.log10(new_bar['rms']/old_bar['rms'])
        parts += ['<details><summary>Raw output levels: old / new, without matching gain</summary><p>These players preserve the original output levels. In this source, new BAR raw RMS is ',f'{abs(bar_delta_db):.1f} dB ',('lower' if bar_delta_db<0 else 'higher'),' than old BAR. The matching gain above compensates for level, so use these raw players to judge the gain tradeoff.</p>']
        for body, family in [(0,'HARMONIC · BODY 0'),(1,'BAR · BODY 1')]:
            parts += ['<h3>',escape(family),'</h3><div class="pair">']
            for c in sorted([c for c in members if c['settings']['body']==body],key=lambda c:c['modelVersion']): card(c,raw=True)
            parts += ['</div>']
        parts += ['</details>']
    parts += ['</section>']

parts += ['<section class="section"><h2>Direct audio order</h2><p>The separate small first-listen FLAC plays the default burst group twice, then the default sustained group twice. Each group is old HARMONIC, old BAR, new HARMONIC, new BAR, with 0.15-second silent gaps. It uses the same matched clips shown above.</p><ol>']
for t in timeline:
    parts += ['<li>',f'{t["startSeconds"]:.2f}s — ',escape(t['id']),'</li>']
parts += ['</ol></section><p><small>No capture, phone benchmark or candidate listening verdict has been added. Implementation SHA-256: <code>',escape(manifest['implementationSha256']),'</code>.</small></p>']
script = '''let serial=0,queued=null;const all=()=>Array.from(document.querySelectorAll('audio'));document.addEventListener('play',e=>{if(e.target.tagName!=='AUDIO')return;if(e.target!==queued){serial++;queued=null}all().forEach(a=>{if(a!==e.target)a.pause()})},true);document.querySelectorAll('button[data-sequence]').forEach(b=>b.addEventListener('click',async()=>{let token=++serial;all().forEach(a=>a.pause());for(const id of b.dataset.sequence.split(',')){if(token!==serial)return;const a=document.querySelector('article[data-case="'+id+'"][data-listen="matched"] audio');queued=a;a.currentTime=0;try{await a.play()}catch{queued=null;return}const completed=await new Promise(resolve=>{const done=ok=>{a.removeEventListener('ended',ended);a.removeEventListener('pause',paused);resolve(ok)},ended=()=>done(true),paused=()=>{if(!a.ended)done(false)};a.addEventListener('ended',ended);a.addEventListener('pause',paused)});if(!completed||token!==serial){if(token===serial){serial++;queued=null}return;}await new Promise(resolve=>setTimeout(resolve,150))}queued=null}));'''
parts += ['<script>',script,'</script></body></html>']
page = OUT/'resonant-body-v2-comparison.html'
page.write_text(''.join(parts))
assert page.stat().st_size < 1_000_000, 'Standalone page exceeded requested small-download target'

lines = ['Resonant body v1 / v2 first listen', 'Matched PCM24 retained losslessly. Packaging adds only .15-second silent gaps.', 'Order per source: v1 HARMONIC, v1 BAR, v2 HARMONIC, v2 BAR; repeat once.', 'Default noise burst first; default sustained C3 source second.', 'Matching is for comparison: raw output levels, including the quieter new BAR, are available in the labelled HTML.', '']
for t in timeline:
    lines.append(f'{t["startSeconds"]:.3f}s  {t["number"]:02d}. {t["id"]} (saved matching x{t["matchedGain"]:.3f})')
track_file = OUT/'resonant-body-v2-before-after-tracks.txt'
track_file.write_text('\n'.join(lines)+'\n')
assert before == {path:sha((ROOT/path).read_bytes()) for path in paths}, 'Original render files changed'
record = {'schema':1,'sourceManifestSha256':sha((ROOT/'manifest.json').read_bytes()),'implementationSha256':manifest['implementationSha256'],
          'experimentMetadataIsHistorical':True,'baselineVersion':1,'candidateVersion':2,'candidateHeard':False,'candidateAccepted':False,'phoneMeasured':False,'allSourceMaterialGenerated':True,
          'packagingGain':1,'resampled':False,'matchedPcm24Lossless':True,'floatPlaybackConversion':'raw and dry float32 files converted to PCM24 with no extra gain; not bit-exact float archives',
          'sourceFilesUnchanged':True,'matchedCaseCount':24,'rawCaseCount':len(raw_cases),'drySourceCount':2,'assets':assets,
          'directAudio':{'file':direct.name,'bytes':direct.stat().st_size,'sha256':sha(direct.read_bytes()),'durationSeconds':frames/44100,'decodedPcm24Sha256':sha(decoded),'exactSavedMatchedClipsAndGaps':True,'timeline':timeline},
          'html':{'file':page.name,'bytes':page.stat().st_size,'sha256':sha(page.read_bytes()),'embeddedAssetCount':len(assets),'externalDependencies':False},
          'trackList':{'file':track_file.name,'bytes':track_file.stat().st_size,'sha256':sha(track_file.read_bytes())}}
(WORK/'delivery-record.json').write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps({'directAudio':record['directAudio']|{'timeline':'see record'},'html':record['html'],'matchedCases':24,'rawCases':len(raw_cases),'encodedAssets':len(assets)},indent=2))
