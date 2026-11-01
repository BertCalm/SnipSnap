"""Render the two revised identities without replacing the first study on disk."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import numpy as np
import apsis_voice_study as a
from apsis_attack_study import render_articulated


def main(baseline, output):
    baseline, output = Path(baseline), Path(output)
    manifest = json.loads((baseline / 'manifest.json').read_text())
    rows = [copy.deepcopy(e) for e in manifest['clips'] if e['voice'] in ('VESSEL', 'CORONA')]
    for folder in ('audio', 'recipes', 'reports'):
        (output / folder).mkdir(parents=True, exist_ok=True)
    native, reports = {}, {}
    for e in rows:
        voice, ident = e['voice'], e['id']
        if e.get('kind') == 'score':
            x = np.zeros(round(9.6 * a.RATE))
            for at, midi, velocity in ((0., 48, .65), (1.8, 55, .45), (3.6, 51, .85)):
                note, _ = render_articulated(voice, midi=midi, velocity=velocity)
                start = round(at * a.RATE)
                x[start:start + len(note)] += note * .35
            info = dict(rawPeak=float(np.max(np.abs(x))), rawRms=a.pr.rms(x), rawDc=a.pr.dc(x))
        else:
            x, r = render_articulated(voice, e['macros'], e['midi'], e['velocity'], e['loop'])
            info = a.measurements(x, e['midi'])
            info.update({k: v for k, v in r.items() if k != 'events'})
            reports[ident] = r
            if e['group'] in ('identities', 'voice-range'):
                assert abs(info['pitchCents']) < 50 and info['pitchConfidence'] >= .3, (ident, info)
            if e['loop']:
                assert r['seamError'] < 1e-3 and r['adjacentCycleRelativeError'] < 1e-5, (ident, r['seamError'], r['adjacentCycleRelativeError'])
        e['metrics'] = info
        e['prototype'] = 'Python/Numba attack study 2'
        e['articulationRevision'] = 2
        e['description'] = ('Strikes draw from a shared recovery budget, changing strength and hardness. The vessel keeps ringing under each new contact.' if voice == 'VESSEL' else
                            'Breath packets vary in strength, edge and duration. The slower resonant body carries a halo into the following encounter.')
        e['gainPolicy'] = 'One gain for the complete take, never per attack. Raw retains the original study monitor gain; touch and control families share a frozen gain.'
        native[ident] = x
        print(ident, 'rendered', flush=True)
    original_raw_gain = manifest['clips'][0]['metrics']['commonRawGain']
    for e in rows:
        ident, voice = e['id'], e['voice']
        x = native[ident]
        if e['group'] in ('voice-touch', 'voice-controls'):
            ref = native[voice.lower() + '_identity_C4']
            family = [native[r['id']] for r in rows if r['voice'] == voice and r['group'] in ('identities', e['group'])]
            gain = min(.12 / a.rms200(ref), .85 / max(np.max(np.abs(y)) for y in family))
        else:
            gain = min(.12 / a.rms200(x), .85 / np.max(np.abs(x)))
        e['metrics']['listeningGain'] = float(gain)
        e['metrics']['commonRawGain'] = original_raw_gain
        for treatment, y in (('raw', x * original_raw_gain), ('matched', x * gain)):
            assert np.max(np.abs(y)) < .85001, (ident, treatment, np.max(np.abs(y)))
            path = output / 'audio' / f'{ident}_{treatment}.wav'
            a.sk.write_wav(str(path), y)
            decoded, rate = a.sk.read_wav(str(path))
            assert rate == a.RATE and decoded.ndim == 1 and np.isfinite(decoded).all()
            assert abs(np.mean(decoded)) < .005
            e[treatment + 'Sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        (output / 'recipes' / f'{ident}.json').write_text(json.dumps(e, indent=2) + '\n')
    for voice in ('VESSEL', 'CORONA'):
        again, _ = render_articulated(voice)
        assert np.array_equal(again, native[voice.lower() + '_identity_C4'])
    summary = dict(runtime='Python/Numba prototypes, not Kotlin', revisedVoices=['VESSEL', 'CORONA'],
                   ownerPreference=dict(favorites=['GRAIN', 'ANCHOR'], acceptable=['TETHER']),
                   revisedClips=len(rows), pcmReadbacks=len(rows)*2, deterministic=True,
                   sourceSha256={n: hashlib.sha256((a.ROOT/'scripts'/n).read_bytes()).hexdigest() for n in
                                 ['apsis_voice_study.py', 'apsis_attack_study.py', 'generate_apsis_attack_study.py']},
                   diagnostics=reports)
    (output/'reports/attack-validation.json').write_text(json.dumps(summary, indent=2)+'\n')
    (output/'manifest.json').write_text(json.dumps(dict(clips=rows, report=summary), indent=2)+'\n')
    print('22 revised clips and 44 PCM readbacks passed', flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('baseline');p.add_argument('output')
    args=p.parse_args();main(args.baseline,args.output)
