"""Owner-selected C sources through the existing R0 atmosphere/amp. No source retuning."""
from pathlib import Path
import sys, json, hashlib, time
import numpy as np
import velour as v
import dry_round2 as drums
import snipkit as sk
import probes as pr

def groove():
    old=v.render
    def selected(voice,macros=None,velocity=1.):
        return old(voice,macros,velocity) if voice=='hat' else drums.render(voice,'C',velocity)
    v.render=selected
    try: return v.groove(v.DEFAULTS)
    finally: v.render=old

def main(dest):
    dest=Path(dest); (dest/'audio').mkdir(parents=True,exist_ok=True)
    start=time.perf_counter()
    dry,send,onsets=groove()
    air,amp,diag=v.treatment(dry,send,onsets,v.DEFAULTS)
    bloom=v.DEFAULTS|dict(SCATTER=.65,SPACE=.6,DRIVE=.5)
    _,more,_=v.treatment(dry,send,onsets,bloom)
    matched,target=v.match_group([v.clean(dry),air,amp,more])
    names=['r3_01_dry','r3_02_atmosphere','r3_03_amp','r3_04_bloom']
    renders=list(zip(names,matched)); checks={}; fingerprints={}
    for voice in ['kick','snare']:
        source=drums.render(voice,'C')
        fingerprints[voice]=hashlib.sha256(source.tobytes()).hexdigest()
        checks[voice+'_source_identical']=np.array_equal(source,drums.render(voice,'C'))
        hit=np.pad(source,(0,int(3.15*sk.RATE)))
        a,b,_=v.treatment(hit,hit*(.12 if voice=='kick' else 1),[0],v.DEFAULTS)
        group,_=v.match_group([hit,a,b])
        renders+=list(zip([f'r3_{voice}_{stage}' for stage in ['dry','atmosphere','amp']],group))
    # The new dry bus before presentation gain must equal the selected round-2 groove.
    previous=drums.groove('C')
    checks['selected_c_groove_identical']=np.array_equal(v.clean(dry[:len(previous)]),previous)
    _,repeat,_=v.treatment(dry,send,onsets,v.DEFAULTS)
    checks['chain_deterministic']=np.array_equal(amp,repeat)
    silent=np.zeros(4*sk.RATE)
    a,b,_=v.treatment(silent,silent,[0],v.DEFAULTS)
    checks['chain_silent_input']=pr.peak(a)==0 and pr.peak(b)==0
    rows=[]
    for name,x in renders:
        assert np.isfinite(x).all() and pr.peak(x)<.93 and abs(pr.dc(x))<.005
        sk.write_wav(str(dest/'audio'/f'{name}.wav'),x)
        decoded,rate=sk.read_wav(str(dest/'audio'/f'{name}.wav'))
        assert rate==44100 and len(decoded)==len(x)
        rows.append(dict(file=name+'.wav',seconds=len(x)/sk.RATE,peak=pr.peak(decoded),
                         rms=sk.rms(decoded),dc=pr.dc(decoded)))
    assert all(checks.values()),checks
    report=dict(status='C selected by owner for kick and snare; effects audition pending',
        implementation='Python prototype, not Kotlin',source=drums.SETTINGS['C'],
        source_sha256=fingerprints,default_effects=v.DEFAULTS,bloom_effects=bloom,
        diagnostics=diag,checks=checks,renders=rows,rms_target_groove=target,
        render_and_check_seconds=time.perf_counter()-start,
        matching='Whole-file RMS matched within equal-duration groups, common peak-safe target; delivery gain only.')
    (dest/'round3-report.json').write_text(json.dumps(report,indent=2))
    print(json.dumps({'checks':checks,'files':len(rows),'seconds':report['render_and_check_seconds']},indent=2))

if __name__=='__main__':main(sys.argv[1] if len(sys.argv)>1 else Path(__file__).resolve().parents[1]/'round3')
