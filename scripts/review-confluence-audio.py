#!/usr/bin/env python3
"""Numerically review an exported Confluence audition without a listening claim.

Optional analysis dependencies: NumPy and SciPy (``python -m pip install numpy
scipy``). They are not runtime dependencies of SnipSnap or its renderer.

Run from any working directory::

    python scripts/review-confluence-audio.py
    python scripts/review-confluence-audio.py --audition /path/to/audition \
        --output /path/to/review.json

The default audition folder is testkit/confluence-audition next to this script's
repository. The default output is source-signal-review.json inside that folder.
The report records the exact manifest SHA-256. Checks cover exported PCM format,
levels, complete source-region event boundaries, seeded roster retention, and
SPACE schedule parity. Solo pitch observations use independent autocorrelation;
they do not establish intelligibility, naturalness, or distinct human identity.
"""

import argparse
import json
import math
import hashlib
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from scipy.io import wavfile
from scipy.signal import correlate, find_peaks, lfilter

DEFAULT_AUDITION = Path(__file__).resolve().parents[1] / 'testkit' / 'confluence-audition'


def read(path):
    rate, pcm = wavfile.read(path)
    if pcm.dtype == np.int32:
        y = pcm.astype(np.float64) / 2147483648
    elif pcm.dtype == np.int16:
        y = pcm.astype(np.float64) / 32768
    else:
        y = pcm.astype(np.float64)
    return rate, y


def loudness(y, rate):
    a = 1 - math.exp(-2 * math.pi * 120 / rate)
    low = lfilter([a], [1, -(1 - a)], y)
    filtered = y - .7 * low
    squares = np.concatenate(([0.], np.cumsum(filtered * filtered)))
    width, hop = int(.2 * rate), int(.05 * rate)
    starts = np.arange(0, len(y), hop)
    ends = np.minimum(starts + width, len(y))
    # Kotlin stops at the first window whose end is the final frame.
    first_last = np.flatnonzero(ends == len(y))
    if len(first_last):
        starts, ends = starts[:first_last[0] + 1], ends[:first_last[0] + 1]
    return float(np.sqrt(np.max((squares[ends] - squares[starts]) / (ends - starts))))


def metrics(path):
    rate, y = read(path)
    return {'sampleRate': rate, 'channels': 1 if y.ndim == 1 else y.shape[1],
            'frames': len(y), 'finite': bool(np.isfinite(y).all()),
            'peak': float(np.max(np.abs(y))), 'dc': float(y.mean()),
            'rms': float(np.sqrt(np.mean(y * y))),
            'loudestFiltered200msRms': loudness(y, rate)}


def pitch_at(y, rate, seconds):
    size = int(.10 * rate)
    center = int(seconds * rate)
    if center - size // 2 < 0 or center + size // 2 >= len(y):
        return None
    x = y[center - size // 2:center + size // 2].copy()
    x -= x.mean()
    energy = np.dot(x, x)
    if np.sqrt(energy / len(x)) < .001:
        return None
    c = correlate(x, x, method='fft')[len(x) - 1:]
    # Pairwise energy normalization reduces the long-lag bias.
    sq = np.concatenate(([0.], np.cumsum(x*x)))
    lo, hi = int(rate / 700), int(rate / 65)
    for lag in range(lo - 1, hi + 2):
        c[lag] /= np.sqrt(sq[len(x) - lag] * (sq[-1] - sq[lag]) + 1e-30)
    peaks, _ = find_peaks(c[lo:hi])
    peaks += lo
    if not len(peaks):
        return None
    peaks = peaks[c[peaks] >= .90 * np.max(c[peaks])]
    lag = int(peaks[0])
    confidence = float(c[lag])
    if confidence < .70:
        return None
    denominator = c[lag - 1] - 2*c[lag] + c[lag + 1]
    delta = .5 * (c[lag - 1] - c[lag + 1]) / denominator if abs(denominator) > 1e-12 else 0
    return rate / (lag + np.clip(delta, -.5, .5)), confidence


def pitch_compare(root, clip, diagnostics, recipe):
    _, source = read(root / recipe['sourcePaths'][0])
    rate, rendered = read(root / clip['matchedPath'])
    regions = recipe['recipe']['regions']
    r = regions[0]
    source = source[r['startFrame']:r['endFrame']]
    events = diagnostics['engine']['events']
    audible = clip.get('evidence', {}).get('isolatedPerformer')
    if audible is None:
        audible = 0
    starts = [e for e in events if e['performerId'] == audible and e['state'] == 'ENTER']
    if not starts:
        return None
    e = starts[0]
    ratio = e['durationRatio']
    values = []
    for t in np.arange(.12, len(source)/rate - .12, .04):
        a = pitch_at(source, rate, t)
        b = pitch_at(rendered, rate, e['startFrame']/rate + t*ratio)
        if a and b:
            values.append((t, 1200*math.log2(a[0]), 1200*math.log2(b[0]), a[1], b[1]))
    if len(values) < 3:
        return {'pairedVoicedWindows': len(values), 'insufficient': True}
    v = np.asarray(values)
    residual = v[:, 2] - v[:, 1] - e['pitchSemitones']*100
    return {'pairedVoicedWindows': len(values), 'sourceContourRangeCents': float(np.percentile(v[:,1],95)-np.percentile(v[:,1],5)),
            'pitchContourPearsonR': float(np.corrcoef(v[:,1],v[:,2])[0,1]),
            'medianPitchDeltaCents': float(np.median(v[:,2]-v[:,1])),
            'expectedEventPitchDeltaCents': e['pitchSemitones']*100,
            'medianResidualCents': float(np.median(residual)),
            'medianAbsoluteResidualCents': float(np.median(np.abs(residual))),
            'p95AbsoluteResidualCents': float(np.percentile(np.abs(residual),95)),
            'windowsOver100CentsResidual': int(np.count_nonzero(np.abs(residual)>100)),
            'method':'Independent normalized autocorrelation, 100ms windows every40ms; source time mapped through recorded duration ratio and event entrance. No phoneme or perceptual inference.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--audition', type=Path, default=DEFAULT_AUDITION,
                        help='Audition directory containing manifest.json (default: repository testkit/confluence-audition)')
    parser.add_argument('--output', type=Path,
                        help='Review JSON destination (default: AUDITION/source-signal-review.json)')
    args = parser.parse_args()
    root = args.audition.resolve()
    output = args.output.resolve() if args.output else root / 'source-signal-review.json'
    manifest = json.loads((root/'manifest.json').read_text())
    clips = {c['id']: c for c in manifest['clips']}
    assessments = {}
    issues = []
    diagnostics_by_id = {}
    for id, clip in clips.items():
        raw = metrics(root/clip['rawPath'])
        matched = metrics(root/clip['matchedPath'])
        diagnostic = json.loads((root/clip['diagnosticsPath']).read_text())
        diagnostics_by_id[id] = diagnostic
        check = {'raw':raw, 'matched':matched}
        if any(m['channels']!=1 or m['sampleRate']!=44100 or not m['finite'] or m['peak']>1 for m in [raw,matched]):
            issues.append({'case':id,'issue':'Audio format, finiteness, or peak failure'})
        if abs(matched['loudestFiltered200msRms']-.03) > 2e-6:
            issues.append({'case':id,'issue':'Matched level differs from documented0.03 target','value':matched['loudestFiltered200msRms']})
        engine=diagnostic['engine']
        if 'events' in engine:
            recipe=json.loads((root/clip['recipePath']).read_text())
            r=recipe['recipe']
            regions={q['assetHash']:q for q in r['regions']}
            grouped={}
            for e in engine['events']:
                if not(0<=e['startFrame']<=e['endFrame']<=clip['frames']):
                    issues.append({'case':id,'issue':'Event outside finite render','event':e})
                if e['state'] in ['ENTER','SING','RELEASE']:
                    grouped.setdefault((e['performerId'],e['eventIndex']),{})[e['state']]=e
            failures=[]
            intervals=[]
            for key,states in grouped.items():
                if set(states)!=set(['ENTER','SING','RELEASE']):
                    failures.append({'event':key,'reason':'missing state'});continue
                enter,sing,release=[states[k] for k in ['ENTER','SING','RELEASE']]
                region=regions[enter['sourceHash']]
                n=(region['sustainEndFrame']-region['sustainStartFrame']) if r['profile']=='DRONE' else region['endFrame']-region['startFrame']
                expected=int(np.floor(n*enter['durationRatio']+.5))
                if enter['endFrame']!=sing['startFrame'] or sing['endFrame']!=release['startFrame'] or abs(release['endFrame']-enter['startFrame']-expected)>1:
                    failures.append({'event':key,'reason':'state continuity or whole-region duration','expectedDuration':expected,'actualDuration':release['endFrame']-enter['startFrame']})
                intervals.append((key[0],enter['startFrame'],release['endFrame']))
            for performer in set(x[0] for x in intervals):
                own=sorted((x[1],x[2]) for x in intervals if x[0]==performer)
                if any(own[i][1]>own[i+1][0] for i in range(len(own)-1)):
                    failures.append({'performer':performer,'reason':'same performer overlaps own phrases'})
            points=sorted([(a,+1) for _,a,b in intervals]+[(b,-1) for _,a,b in intervals],key=lambda p:(p[0],p[1]))
            count=maximum=0
            for _,delta in points:
                count+=delta;maximum=max(maximum,count)
            check['trace']={'rosterCount':len(engine['performers']),'distinctSingingPerformerIds':sorted(set(x[0] for x in intervals)),
                            'completeRegionEvents':len(intervals),'maximumSimultaneousFromExactEvents':maximum,
                            'declaredMaximumSimultaneous':engine['maximumSimultaneousPerformers'],'chronologyFailures':failures}
            if failures:issues.append({'case':id,'issue':'Event chronology failure','details':failures})
            if id in ['crowd_1','identity_solo_0','phrase_solo']:
                check['sourcePitchComparison']=pitch_compare(root,clip,diagnostic,recipe)
        assessments[id]=check
    roster={}
    previous=[]
    for n in [1,2,4,8]:
        id=f'crowd_{n}';e=diagnostics_by_id[id]['engine'];ps=e['performers'];ids=assessments[id]['trace']['distinctSingingPerformerIds']
        stable=ps[:len(previous)]==previous
        roster[id]={'declared':e['activePerformers'],'actualEventIds':ids,'priorRosterRetained':stable}
        if len(ids)!=n or e['activePerformers']!=n or not stable:issues.append({'case':id,'issue':'Participation or stable roster failure'})
        previous=ps
    space_ids=[id for id in clips if id.startswith('response_space_')]
    space={}
    if len(space_ids)==2:
        a,b=space_ids
        ae,be=[diagnostics_by_id[k]['engine'] for k in [a,b]]
        space={'caseIds':space_ids,'eventsSampleIdentical':ae['events']==be['events'],'performersIdentical':ae['performers']==be['performers']}
        if not space['eventsSampleIdentical'] or not space['performersIdentical']:issues.append({'issue':'SPACE comparison changes source schedule or identities'})
    engine_cases={k:v for k,v in assessments.items() if 'trace' in v}
    review={'status':'numerical review; perceptual listening verdict remains open','scope':'Exported24bit WAV metrics, recorded event boundaries and independent source/solo pitch observations; cannot verify word intelligibility from event traces.',
            'reviewedUtc':datetime.now(timezone.utc).isoformat(),'auditionManifestSha256':hashlib.sha256((root/'manifest.json').read_bytes()).hexdigest(),
            'clipPairs':len(clips),'issues':issues,'crowdParticipation':roster,'spaceComparison':space,
            'matchedLevelRange':[min(c['matched']['loudestFiltered200msRms'] for c in assessments.values()),max(c['matched']['loudestFiltered200msRms'] for c in assessments.values())],
            'maximumAbsoluteRawDc':max(abs(c['raw']['dc']) for c in assessments.values()),'maximumExportedRawPeak':max(c['raw']['peak'] for c in assessments.values()),
            'maximumAbsoluteEngineRawDc':max(abs(c['raw']['dc']) for c in engine_cases.values()),
            'completeRegionEventCount':sum(c['trace']['completeRegionEvents'] for c in engine_cases.values()),
            'cases':assessments}
    serialized=json.dumps(review,indent=2)+'\n'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(serialized)
    print(json.dumps({k:v for k,v in review.items() if k!='cases'},indent=2))
    for id,c in assessments.items():
        if 'sourcePitchComparison' in c:print(id,json.dumps(c['sourcePitchComparison']))
    return 1 if issues else 0


if __name__=='__main__':
    raise SystemExit(main())
