"""Quick portable-reference checks. Does not integrate mechanics or render WAVs."""
from pathlib import Path
import hashlib
import importlib.util
import json
import sys
import wave

sys.dont_write_bytecode=True

import numpy as np
import scipy
import matplotlib

ROOT=Path(__file__).resolve().parent


def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    module=importlib.util.module_from_spec(spec)
    sys.modules[name]=module
    spec.loader.exec_module(module)
    return module


def main():
    manifest=json.loads((ROOT/'SOURCE_MANIFEST.json').read_text())
    checked=[]
    for record in manifest['files']:
        path=ROOT/record['bundled_path']
        digest=hashlib.sha256(path.read_bytes()).hexdigest()
        assert digest==record['bundled_sha256'], f'Changed snapshot: {path}'
        checked.append(record['bundled_path'])
    audio=[]
    for path in sorted((ROOT/'reference').rglob('*.wav')):
        with wave.open(str(path),'rb') as w:
            expected=8.9 if path.parent.name=='musicality' else 10.2
            assert (w.getnchannels(),w.getsampwidth(),w.getframerate(),w.getnframes())==(1,2,44100,round(expected*44100)),path
            audio.append(dict(path=str(path.relative_to(ROOT)),seconds=w.getnframes()/w.getframerate(),sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    assert len(audio)==6
    assert not (ROOT/'reference'/'tine-options'/'reference.wav').exists(), 'Archive should store approved TINE only once'
    ref=ROOT/'reference'
    sys.path.insert(0,str(ref/'mechanics'))
    sys.path.insert(0,str(ref/'refinement'))
    load('mechanics',ref/'mechanics'/'mechanics.py')
    load('accord_portable_validate',ref/'mechanics'/'validate.py')
    musicality=load('accord_portable_musicality',ref/'musicality'/'render.py')
    controls=load('controls',ref/'refinement'/'controls.py')
    refinement=load('accord_portable_refinement',ref/'refinement'/'render.py')
    tine=load('accord_portable_tine',ref/'tine-options'/'render.py')
    tine_qa=load('accord_portable_tine_qa',ref/'tine-options'/'qa.py')
    approved=ref/'musicality'
    assert refinement.APPROVED.resolve()==approved
    assert tine.APPROVED.resolve()==approved
    assert tine_qa.APPROVED.resolve()==approved/'tine.wav'
    assert Path(tine.approved.__file__).resolve()==approved/'render.py'
    for voice in ['brush','wood','tine']:
        c=controls.config_for(voice,48,.75)
        assert c['tongue'][0][0]==1 and c['body'][0][0]==1
    fixture=np.load(ref/'mechanics'/'gathered.npz')
    events=np.asarray(json.loads((ref/'mechanics'/'gathered-events.json').read_text())['events'])
    assert np.array_equal(events,fixture['events'])
    assert np.isfinite(fixture['state']).all()
    # Exercise real relative fixture loads and deterministic seeded roughness.
    loaded=musicality.load_inputs()
    event_seconds=loaded[0]
    kept=event_seconds[(event_seconds[:,0]>=4)&(event_seconds[:,0]<12)]
    option_report=json.loads((ref/'tine-options'/'report.json').read_text())
    assert len(kept)==528
    assert hashlib.sha256(kept.tobytes()).hexdigest()==option_report['event_history_sha256']
    approved_sha=hashlib.sha256((approved/'tine.wav').read_bytes()).hexdigest()
    assert approved_sha==option_report['reference_file_sha256']==manifest['omitted_duplicate']['sha256']
    print(json.dumps(dict(status='passed',snapshot_files_checked=len(checked),audio=audio,
                    imports='mechanics, validation, musicality, controls, refinement, TINE renderer and QA',
                    portable_tine_reference='reference/musicality/tine.wav',
                    selected_exact_contact_events=len(kept),
                    versions=dict(python=sys.version.split()[0],numpy=np.__version__,scipy=scipy.__version__,matplotlib=matplotlib.__version__),
                    work_not_run='No mechanical solver, audio renderer, broad control sweep, Kotlin tests or listening'),indent=2))


if __name__=='__main__':
    main()
