"""Rebuild fixed-gain playback probes from saved Kotlin wet PCM (Python 3, NumPy, SciPy and ffmpeg); no DSP/model change."""
import argparse
from pathlib import Path
import hashlib
import json
import math
import subprocess
import wave

import numpy as np
from scipy.io import wavfile

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
OUT = ARTIFACTS / 'resonant-body-v2-level-probe' / 'raw-gain'
existing = [p for p in [OUT] if p.exists()]
if existing and not args.overwrite:
    parser.error('Existing output: ' + ', '.join(str(p) for p in existing) + '; use --overwrite to replace it')
OUT.mkdir(parents=True, exist_ok=True)
manifest_path = ROOT / 'manifest.json'
manifest = json.loads(manifest_path.read_text())
selected = [c for c in manifest['cases'] if c['modelVersion'] == 2
            and c['comparisonGroup'] in ['default-burst','default-sustained']
            and c['settings']['body'] in [0,1]]
assert len(selected) == 4

def sha(data):
    return hashlib.sha256(data).hexdigest()

def run(args):
    return subprocess.run(args,check=True,capture_output=True)

source_hashes = {c['wet']:sha((ROOT/c['wet']).read_bytes()) for c in selected}
references = {c[key]:sha((ROOT/c[key]).read_bytes()) for c in selected for key in ['source','wet']}
records=[]
for c in selected:
    path=ROOT/c['wet']
    assert source_hashes[c['wet']] == c['wetSha256']
    assert references[c['source']] == c['sourceSha256']
    rate,x=wavfile.read(path)
    assert x.dtype==np.float32 and rate==c['rate']
    channels=1 if x.ndim==1 else x.shape[1]
    assert channels==c['channels'] and len(x)==c['outputFrames']
    wet=x.astype(np.float64)
    assert np.isfinite(wet).all() and np.all(wet[-1]==0)
    for gain_db in [0,6,12,18]:
        linear=10**(gain_db/20)
        unguarded=wet*linear
        peak=float(np.max(np.abs(unguarded)))
        attenuation=min(1,.98/peak) if peak else 1
        delivered=unguarded*attenuation
        assert np.isfinite(delivered).all() and np.all(delivered[-1]==0)
        assert float(np.max(np.abs(delivered)))<=.98+1e-15
        # Explicit deterministic listening-copy quantization. Do not renormalize each clip.
        quantized=np.clip(np.rint(delivered*8388608),-8388608,8388607).astype('<i4')
        pcm=quantized.reshape(-1).view(np.uint8).reshape(-1,4)[:,:3].tobytes()
        label=f'{c["id"]}-plus-{gain_db:02d}db'
        wav=OUT/(label+'.wav')
        with wave.open(str(wav),'wb') as w:
            w.setnchannels(channels);w.setsampwidth(3);w.setframerate(rate);w.writeframes(pcm)
        flac=OUT/(label+'.flac')
        run(['ffmpeg','-nostdin','-v','error','-y','-i',str(wav),'-map_metadata','-1',
             '-c:a','flac','-compression_level','12','-metadata_header_padding','0',str(flac)])
        decoded=run(['ffmpeg','-nostdin','-v','error','-i',str(flac),'-f','s24le','-c:a','pcm_s24le','pipe:1']).stdout
        assert decoded==pcm,label
        playback=quantized.astype(np.float64)/8388608
        quantization_error=float(np.max(np.abs(playback-delivered)))
        assert quantization_error<=1/16777216+1e-15
        assert np.all(playback[-1]==0)
        probe=json.loads(run(['ffprobe','-v','error','-show_streams','-of','json',str(flac)]).stdout)['streams'][0]
        assert int(probe['sample_rate'])==rate and probe['channels']==channels
        assert int(probe['bits_per_raw_sample'])==24
        records.append({'caseId':c['id'],'comparisonGroup':c['comparisonGroup'],'body':c['settings']['body'],
                        'modelVersion':2,'gainDb':gain_db,'gainLinear':linear,
                        'effectiveGainDb':20*math.log10(linear*attenuation),'attenuation':attenuation,
                        'attenuationDb':20*math.log10(attenuation),'path':flac.name,
                        'bytes':flac.stat().st_size,'flacSha256':sha(flac.read_bytes()),'pcm24Sha256':sha(pcm),
                        'wetFile':c['wet'],'wetFileSha256':source_hashes[c['wet']],
                        'sourceFile':c['source'],'sourceFileSha256':c['sourceSha256'],
                        'sampleRate':rate,'channels':channels,'frames':len(wet),'durationSeconds':len(wet)/rate,
                        'peakBeforeGuard':peak,'peakAfterGuardBeforeQuantization':float(np.max(np.abs(delivered))),
                        'playbackPeak':float(np.max(np.abs(playback))),'playbackRms':float(np.sqrt(np.mean(playback*playback))),
                        'playbackMean':float(np.mean(playback)),'maxQuantizationError':quantization_error,
                        'decodedPcmEqualsComputedQuantizedSamples':True,'savedMatchingApplied':False})
        wav.unlink()

assert source_hashes=={name:sha((ROOT/name).read_bytes()) for name in source_hashes}
assert references=={name:sha((ROOT/name).read_bytes()) for name in references}
record={'schema':1,'kind':'Python-derived fixed-gain playback copies of actual saved Kotlin model-2 wet PCM',
        'implementationSha256':manifest['implementationSha256'],'sourceManifestSha256':sha(manifest_path.read_bytes()),
        'modelVersion':2,'implementedModel3':False,'dspChanged':False,'originalFilesUnchanged':True,
        'gainDbChoices':[0,6,12,18],'savedMatchingApplied':False,'resampled':False,
        'guard':'One common attenuation min(1,.98/max(abs(gain*wet))) across all frames/channels; no per-sample clipping.',
        'quantization':'Float64 scalar processing then nearest-even rounding to signed PCM24; no per-clip level matching. Tiny last-bit differences from a hypothetical Kotlin rerender are not ruled out.',
        'flacMetadataPadding':0,'guardTriggeredCases':sum(r['attenuation']<1 for r in records),
        'assetCount':len(records),'totalFlacBytes':sum(r['bytes'] for r in records),'assets':records,
        'originalReferencedFileHashes':references}
(OUT/'record.json').write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps({k:record[k] for k in ['assetCount','totalFlacBytes','guardTriggeredCases','implementationSha256']},indent=2))
for r in records:print(r['caseId'],r['gainDb'],r['path'],r['bytes'])
