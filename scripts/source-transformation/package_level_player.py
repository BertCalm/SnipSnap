"""Embed the sibling raw-gain and listening-master records in the compact level comparison (Python 3). Owner-feedback metadata is historical; no new acceptance is inferred."""
import argparse
from pathlib import Path
from html import escape
import base64
import hashlib
import json

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True, help='Artifact parent; named delivery files and working subdirectories are created here')
parser.add_argument('--overwrite', action='store_true', help='Replace this helper\'s existing output files')
args = parser.parse_args()
ARTIFACTS = args.output.expanduser().resolve()
OUT = ARTIFACTS
ROOT = OUT / 'resonant-body-v2-level-probe'
existing = [p for p in [OUT / 'resonant-body-v2-level-comparison.html', ROOT / 'delivery-record.json'] if p.exists()]
if existing and not args.overwrite:
    parser.error('Existing output: ' + ', '.join(str(p) for p in existing) + '; use --overwrite to replace it')
master = json.loads((ROOT/'listening-master/record.json').read_text())
raw = json.loads((ROOT/'raw-gain/record.json').read_text())
assert master['implementationSha256'] == raw['implementationSha256']
assert master['processingChanged'] is False and raw['dspChanged'] is False
records = []

def player(path, label):
    path = Path(path)
    data = path.read_bytes()
    records.append({'label': label, 'file': str(path), 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)})
    return '<audio controls preload="none" aria-label="'+escape(label,quote=True)+'" src="data:audio/flac;base64,'+base64.b64encode(data).decode()+'"></audio>'

css = '''body{max-width:800px;margin:1.4rem auto;padding:0 1rem 3rem;background:#151917;color:#e8eae2;font:16px/1.5 system-ui}h1,h2,h3{line-height:1.2}section{border-top:1px solid #62705c;margin-top:1.5rem;padding-top:.6rem}.pair{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(260px,100%),1fr));gap:1rem}audio{width:100%;max-width:360px;display:block;margin:.5rem 0}article{padding:.6rem 0;border-bottom:1px solid #36432f}small{color:#c2c9bb}summary{cursor:pointer;font-weight:600}details{padding:.8rem 0}code{overflow-wrap:anywhere}'''
parts = ['<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Preferred body sounds: level comparison</title><style>',css,'</style></head><body><h1>Preferred body sounds: level comparison</h1><p>You preferred the new timbres and found them easier to distinguish. These are the same model 2 sounds, with playback gain changed.</p><p><b>Keep your device volume the same while comparing.</b> First try Current versus Louder below. The short hits gain 1.5 dB; the held notes gain 12 dB because those files have more spare peak level.</p><p>This is a listening preview. It does not change the renderer, its defaults, or the body profiles. All audio is embedded; no ZIP or connection is needed after download.</p>']

for group, title in [('default-burst','Short noise burst'),('default-sustained','Held harmonic source')]:
    parts += ['<section><h2>',title,'</h2>']
    for item in master['cases']:
        if item['sourceGroup'] != group: continue
        family = 'HARMONIC' if item['caseId'].endswith('harmonic') else 'BAR'
        parts += ['<article data-master-case="',escape(item['caseId'],quote=True),'"><h3>',family,'</h3><div class="pair"><div><b>Current listening copy</b>',player(ROOT/'listening-master'/item['current']['file'],family+' '+group+' current listening level'),'</div><div><b>Louder listening copy: +',f'{item["previewGainDb"]:g}',' dB</b>',player(ROOT/'listening-master'/item['louder']['file'],family+' '+group+' louder listening level'),'</div></div><small>The same fixed gain is used for both bodies within this source group. No compression, limiter or individual loudness matching has been added.</small></article>']
    parts += ['</section>']

parts += ['<details id="raw-options"><summary>Original processor output: fixed gain trials</summary><p>This is a separate comparison. These clips start at the quieter original processor level, without audition matching. Try 0, +6, +12 and +18 dB and choose a comfortable level.</p><p>Each boost is fixed and keeps the relative HARMONIC/BAR levels. One gain will not balance every source equally. These particular clips stay below the sample limit; a full-scale resonant input can invoke the existing output guard.</p>']
for item in master['cases']:
    cid=item['caseId']
    family='HARMONIC' if cid.endswith('harmonic') else 'BAR'
    title=('Noise burst' if item['sourceGroup']=='default-burst' else 'Held source')+' — '+family
    parts += ['<article data-raw-case="',escape(cid,quote=True),'"><h3>',escape(title),'</h3><div class="pair">']
    for asset in sorted((a for a in raw['assets'] if a['caseId']==cid),key=lambda a:a['gainDb']):
        assert asset['attenuation']==1
        label=('Original level' if asset['gainDb']==0 else '+'+str(asset['gainDb'])+' dB')
        parts += ['<div><b>',label,'</b>',player(ROOT/'raw-gain'/asset['path'],title+' raw '+label),'<small>Sample peak ',f'{asset["playbackPeak"]:.4f}','; no matching.</small></div>']
    parts += ['</div></article>']
parts += ['</details><details><summary>How this preview was made</summary><p>The model 2 filter and decay profiles are unchanged. Current listening clips already have the earlier audition matching applied; this page adds only the labelled fixed source-group playback gain. The raw trials have no audition matching. Full clips and tails are preserved.</p><p>FLAC playback is PCM24. Raw float32 copies and gain-scaled clips are quantized to PCM24 for playback, without resampling. Original WAVs are retained. The maximum boosted listening-copy sample peak is 0.93812; the independent 4× true-peak estimate is 0.93862. Those measurements concern these files, not arbitrary new recordings or phone performance.</p><p>No new renderer gain policy or model 3 is implemented. Timbre direction is preferred; level calibration remains open.</p><small>Renderer implementation SHA-256: <code>',master['implementationSha256'],'</code>.</small></details><script>document.addEventListener("play",e=>{if(e.target.tagName==="AUDIO")document.querySelectorAll("audio").forEach(a=>{if(a!==e.target)a.pause()})},true)</script></body></html>']
target = OUT/'resonant-body-v2-level-comparison.html'
target.write_text(''.join(parts))
assert target.stat().st_size < 1_000_000
record={'experimentMetadataIsHistorical':True,'modelVersion':2,'rendererChanged':False,'implementationSha256':master['implementationSha256'],'ownerFeedback':'It is quiet, they are easier to distinguish, I prefer them','timbreDirectionPreferred':True,'levelAccepted':False,'quietPlaybackCategorySpecified':False,'audioControls':len(records),'masterCases':4,'rawGainCases':4,'assets':records,'html':{'path':str(target),'bytes':target.stat().st_size,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()},'directAudio':master['directAudio']}
(ROOT/'delivery-record.json').write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps({'html':record['html'],'audioControls':len(records),'masterCases':4,'rawAssets':len(raw['assets']),'directAudio':master['directAudio']},indent=2))
