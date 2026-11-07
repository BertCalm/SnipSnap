"""Measure gain over saved Kotlin PCM and a Python-only stress equation (Python 3, NumPy and SciPy). The recorded base commit is historical; no DSP/model change."""
import argparse
from pathlib import Path
import hashlib
import json
import math
import numpy as np
from scipy.io import wavfile
from scipy.signal import lfilter

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
OUT = ARTIFACTS / 'resonant-body-v2-level-probe'
existing = [p for p in [OUT / 'gain-report.json'] if p.exists()]
if existing and not args.overwrite:
    parser.error('Existing output: ' + ', '.join(str(p) for p in existing) + '; use --overwrite to replace it')
OUT.mkdir(parents=True, exist_ok=True)
manifest = json.loads((ROOT / 'manifest.json').read_text())
cases = [c for c in manifest['cases'] if c['modelVersion'] == 2]

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def load(path):
    rate, samples = wavfile.read(path)
    if samples.dtype == np.int32:
        samples = samples.astype(np.float64) / 2147483648.0
    elif samples.dtype == np.int16:
        samples = samples.astype(np.float64) / 32768.0
    else:
        samples = samples.astype(np.float64)
    if samples.ndim == 1:
        samples = samples[:, None]
    assert np.isfinite(samples).all()
    return rate, samples

def db(value):
    return 20 * math.log10(value) if value > 0 else None

def stats(x):
    peak = float(np.max(np.abs(x)))
    rms = float(np.sqrt(np.mean(x*x)))
    return {'peak': peak, 'peakDbfs': db(peak), 'rms': rms, 'rmsDbfs': db(rms),
            'mean': float(np.mean(x)), 'absMean': float(abs(np.mean(x)))}

def gain_result(source, wet, gain_db, mix):
    gain = 10 ** (gain_db / 20)
    dry_term = (1-mix) * source
    wet_term = mix * gain * wet
    pre = dry_term + wet_term
    peak = float(np.max(np.abs(pre)))
    attenuation = min(1.0, .98/peak) if peak > 0 else 1.0
    delivered = pre * attenuation
    dry_rms = math.sqrt(float(np.mean(dry_term*dry_term)))
    wet_rms = math.sqrt(float(np.mean(wet_term*wet_term)))
    return {'wetGainDb': gain_db, 'wetGainLinear': gain, 'mix': mix,
            'preGuard': stats(pre), 'postGuard': stats(delivered),
            'attenuation': attenuation, 'attenuationDb': db(attenuation),
            'samplesAboveCeilingBeforeGuard': int(np.count_nonzero(np.abs(pre) > .98)),
            'dryTermRms': dry_rms, 'wetTermRms': wet_rms,
            'wetToDryRmsDb': db(wet_rms/dry_rms) if dry_rms > 0 else None}

files = sorted({c[key] for c in cases for key in ['source','wet','raw','matched'] if c[key] is not None})
before = {name: sha(ROOT/name) for name in files}
rows=[]
for c in cases:
    rate, source = load(ROOT/c['source'])
    wrate, wet = load(ROOT/c['wet'])
    assert rate == wrate == c['rate']
    source = np.pad(source, ((0,len(wet)-len(source)),(0,0)))
    assert source.shape == wet.shape
    rows.append({'id': c['id'], 'group': c['comparisonGroup'], 'settings': c['settings'],
                 'sourceSha256': before[c['source']], 'wetSha256': before[c['wet']],
                 'originalRaw': stats(load(ROOT/c['raw'])[1]),
                 'paddedDrySource': stats(source),
                 'gainTrials': [gain_result(source,wet,gain_db,mix)
                                for gain_db in [0,6,12,18] for mix in [1,.5]]})

summaries=[]
for gain_db in [0,6,12,18]:
    for mix in [1,.5]:
        trials=[t for row in rows for t in row['gainTrials'] if t['wetGainDb']==gain_db and t['mix']==mix]
        summaries.append({'wetGainDb':gain_db,'mix':mix,'cases':len(trials),
                          'attenuationTriggeredCases':sum(t['attenuation']<1 for t in trials),
                          'maxPreGuardPeak':max(t['preGuard']['peak'] for t in trials),
                          'maxPostGuardPeak':max(t['postGuard']['peak'] for t in trials),
                          'maxAbsMeanPostGuard':max(t['postGuard']['absMean'] for t in trials)})

# Separate equation-only full-amplitude resonant stress. This is NOT a new Kotlin render.
def python_model(source,rate,settings):
    root=440 * 2 ** ((round(36+36*settings['tune'])-69)/12)
    t60=.6 if settings['ring']==1 else .05 * 12**settings['ring']
    a=1-math.exp(-math.log(1000)/(t60*rate))
    tail=math.ceil(t60*rate)
    x=np.pad(source.astype(np.float64),(0,tail))
    outputs=[]
    for family in ['HARMONIC','BAR']:
        ratios=[1,2,3,4] if family=='HARMONIC' else [1,2.756,5.404,8.933]
        weights=np.array([.4,.32,.2,.08] if family=='HARMONIC' else [.04,.4992,.288,.1728],dtype=np.float64)
        weights/=np.sum(weights)
        scales=[1,.85,.65,.45] if family=='HARMONIC' else [1,1,.9,.8]
        bank=np.zeros_like(x)
        for ratio,weight,scale in zip(ratios,weights,scales):
            mt=max(.01,t60*scale/(1+1.25*settings['damp']*math.log2(ratio)))
            r=math.exp(-math.log(1000)/(mt*rate))
            theta=2*math.pi*root*ratio/rate
            bank+=weight*lfilter([a*math.sin(theta)],[1,-2*r*math.cos(theta),r*r],x)
        outputs.append(bank)
    wet=4*((1-settings['body'])*outputs[0]+settings['body']*outputs[1])
    fade=math.ceil(.01*rate)
    wet[-fade:]*=np.arange(fade-1,-1,-1)/(fade-1)
    return np.pad(source.astype(np.float64),(0,tail))[:,None],wet[:,None]

stress_settings={'tune':1/3,'body':1,'ring':1,'damp':0,'mix':1}
rate=44100
hz=440*2**((48-69)/12)*2.756
tone=np.sin(2*np.pi*hz*np.arange(rate)/rate).astype(np.float32)
stress_source,stress_wet=python_model(tone,rate,stress_settings)
stress_trials=[gain_result(stress_source,stress_wet,gain_db,mix)
               for gain_db in [0,6,12,18] for mix in [1,.5]]

# Sanity-check that the equation harness represents the unchanged saved candidate.
equation_checks=[]
for c in cases:
    rate,source=load(ROOT/c['source'])
    _,expected_wet=load(ROOT/c['wet'])
    _,rendered=python_model(source[:,0],rate,c['settings'])
    error=float(np.max(np.abs(rendered-expected_wet)))
    assert error < 1e-7,(c['id'],error)
    equation_checks.append({'id':c['id'],'maxAbsoluteErrorAgainstSavedKotlinWet':error})

assert before == {name:sha(ROOT/name) for name in files}
report={'measurement':'NumPy gain/mix/guard reconstruction over saved Kotlin model-2 wet PCM; no DSP changes',
        'experimentMetadataIsHistorical':True,'repoCommit':'1994c619ebcccff932cf0d69d5b7b61d16862651',
        'implementationSha256':manifest['implementationSha256'],
        'manifestSha256':sha(ROOT/'manifest.json'),'originalFilesUnchanged':True,
        'methods':{'gain':'fixed wet scalar 10^(dB/20), applied to stored already-faded pre-guard wet audio',
                   'mix':'(1-MIX)*original source, zero-padded through the tail, plus MIX*gain*wet',
                   'guard':'one common attenuation min(1,.98/max(abs(mixed))) across the complete render and channels',
                   'rms':'all channels and full render including tail; wet/dry ratio uses equally padded arrays',
                   'qualification':'This is a gain-only delivery experiment, not an implemented Kotlin model 3. Scaling saved Float PCM can differ in last bits from rerendering with a new Double makeup gain.'},
        'summary':summaries,'cases':rows,
        'stress':{'implementation':'Python/SciPy lfilter of documented model-2 equation, not Kotlin or hearing evidence',
                  'source':'one-second full-amplitude sine at BAR second-mode resonance',
                  'sourceHz':hz,'settings':stress_settings,'trials':stress_trials,
                  'equationChecksAgainstSavedCandidate':equation_checks},
        'recommendation':{'previewGainDb':[6,12,18],
                          'keepTimbres':'Use constant scalars, with original profiles/decays unchanged. Label each gain and guard reduction.',
                          'commonGainLimit':'Common gain never changes the HARMONIC/BAR RMS ratio on pure-wet clips below the guard.',
                          'headroomLimit':'The tested sources peak well below full scale; no-attenuation results are sampled headroom only, not a global safe-gain guarantee.',
                          'promotion':'Do not change defaults, model version or gain law before evaluating the level choice on real source types.'},
        'fileHashes':before,'heardByAssistant':False}
(OUT/'gain-report.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'summary':summaries,'stress':[{k:t[k] for k in ['wetGainDb','mix','attenuation','attenuationDb']} for t in stress_trials],
                  'equationMaxError':max(c['maxAbsoluteErrorAgainstSavedKotlinWet'] for c in equation_checks)},indent=2))
