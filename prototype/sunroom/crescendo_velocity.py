"""Author soft/medium cymbal damping over the approved R10 phase reconstructions.
No new physical-model claim: upper-mode weights are an authored excitation analogy.
Preserve the measured gesture, sample length, take phase and all hard WAV bytes.
Requires numpy, scipy and soundfile. Run from any directory; no site input needed.
"""
from pathlib import Path
import gzip, hashlib, io, json
import numpy as np
import soundfile as sf
from scipy import signal

ROOT = Path(__file__).resolve().parents[2]
BANK = ROOT / 'synth/src/main/resources/sunroom'
OLD = BANK / 'v1'
OUT = BANK / 'v2'
SR = 44100
N = 2048
HOP = 256

def decode(path):
    raw = gzip.decompress(path.read_bytes())
    x, rate = sf.read(io.BytesIO(raw)); assert rate == SR
    return raw, x

def spectrum(x):
    f, p = signal.welch(x, SR, nperseg=4096)
    return {'centroidHz': float(np.sum(f*p)/np.sum(p)),
            'upperBandFraction': float(np.sum(p[f >= 6000])/np.sum(p)),
            'rms': float(np.sqrt(np.mean(x*x))), 'peak': float(np.max(np.abs(x)))}

def render(x, zone):
    f, _, st = signal.stft(x, SR, nperseg=N, noverlap=N-HOP)
    energy = np.sqrt(np.sum(abs(st)**2, axis=0))
    # Smooth the excitation estimate to avoid frame-rate pumping. The quiet buildup
    # suppresses upper modes more than the peak; original phase/timing never changes.
    activity = signal.savgol_filter(energy, 31, 2).clip(0)
    activity = np.sqrt(activity / max(float(activity.max()), 1e-12))
    upper = (f / 2800)**2 / (1 + (f / 2800)**2)
    depth = [11.0, 4.5][zone]  # authored dB, not a measured material parameter
    db = -depth * upper[:, None] * (1.2 - .35*activity[None, :])
    _, y = signal.istft(st * 10**(db/20), SR, nperseg=N, noverlap=N-HOP)
    y = y[:len(x)]
    # Match each old layer's energy so the new timbre cannot pass on level alone.
    y *= np.linalg.norm(x) / np.linalg.norm(y)
    y[-220:] *= np.linspace(1, 0, 220)
    return y

def main():
    (OUT/'psych').mkdir(parents=True, exist_ok=True)
    manifest = json.loads((OLD/'manifest.json').read_text())
    manifest['version'] = 2
    manifest['approval'] = 'R10 sound and hardware approved; v2 soft/medium crescendo pending listening'
    manifest['audition'] = 'https://sunroom-drums.bertcalm.chatgpt.site/crescendo.html'
    rows = []
    for z in range(3):
        for take in range(3):
            name = f'psych/12-{z}-{take}.wav.gz'
            raw, x = decode(OLD/name)
            if z < 2:
                y = render(x, z)
                stream = io.BytesIO(); sf.write(stream, y, SR, format='WAV', subtype='PCM_16')
                raw = stream.getvalue(); (OUT/name).write_bytes(gzip.compress(raw, mtime=0))
                saved, _ = sf.read(io.BytesIO(raw))
            else: saved = x
            old_stats, stats = spectrum(x), spectrum(saved)
            assert len(saved) == len(x) and saved[-1] == 0
            assert stats['peak'] < .9 and abs(saved.mean()) < .005
            assert abs(stats['rms']/old_stats['rms']-1) < .002
            env = lambda a: np.sqrt(np.mean(a[:len(a)//441*441].reshape(-1,441)**2, axis=1))
            corr = float(np.corrcoef(env(x), env(saved))[0,1])
            assert corr > .99
            row = dict(zone=z,take=take,original=old_stats,revised=stats,envelopeCorrelation=corr,
                       sha256=hashlib.sha256(raw).hexdigest(),frames=len(saved))
            rows.append(row)
            for entry in manifest['samples']:
                if entry['file'] == name: entry['sha256'] = row['sha256']
    for take in range(3):
        group = [r for r in rows if r['take']==take]
        for a,b in zip(group,group[1:]):
            assert a['revised']['centroidHz'] < b['revised']['centroidHz']*.9
            assert a['revised']['upperBandFraction'] < b['revised']['upperBandFraction']*.8
            assert a['revised']['rms'] < b['revised']['rms']
    provenance = {'revision':2,'method':'authored, energy-dependent upper-mode damping of R10 magnitude resynthesis; unchanged phase and timing; hard layers unchanged',
                  'parameters':{'nfft':N,'hop':HOP,'kneeHz':2800,'depthDb':[11,4.5,0]},
                  'listening':'pending for revised soft and medium layers','rows':rows}
    (OUT/'manifest.json').write_text(json.dumps(manifest,separators=(',',':')))
    (OUT/'crescendo-provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
    print(json.dumps([{'zone':z,'centroidHz':round(np.mean([r['revised']['centroidHz'] for r in rows if r['zone']==z])),
                       'upperBandFraction':round(np.mean([r['revised']['upperBandFraction'] for r in rows if r['zone']==z]),4)} for z in range(3)],indent=2))

if __name__ == '__main__': main()
