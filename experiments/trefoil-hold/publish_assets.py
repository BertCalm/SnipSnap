"""Package the reproducible held audition into the existing Site checkout."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

import prototype as t


def main(audio,site):
    destination=site/'dist/held'; destination.mkdir(parents=True,exist_ok=True)
    for file in (Path(__file__).parent/'web').iterdir(): shutil.copy2(file,destination/file.name)
    report=json.loads((audio/'report.json').read_text())
    if not report['all_checks_pass']: raise ValueError('Numerical gates did not pass')
    records=report['cases']; common=min(r['gain'] for r in records.values())
    manifest={'model':t.MODEL,'common_gain':common,'cases':{},'checks':report['check_count']}
    for name,r in records.items():
        entry=dict(period=r['period_seconds'],off=r['noteoff_seconds'],stop=r['power_stops_seconds'],
                   recipe=r['recipe'],gain=r['gain'],returns=r['returns'],audio={})
        for kind in ('loop','returns','release'):
            src=audio/f'{name}-{kind}.wav'; shutil.copy2(src,destination/src.name)
            entry['audio'][kind]={'matched':src.name,'fixed_scale':common/r['gain']}
            (destination/f'{name}-{kind}-fixed.wav').unlink(missing_ok=True)
        manifest['cases'][name]=entry
    # Preserve and link the exact approved finite reference.
    finite=site/'dist/playable/audio/weave-2.wav'
    manifest['finite']={'url':'../playable/audio/weave-2.wav',
                        'sha256':hashlib.sha256(finite.read_bytes()).hexdigest()}
    report['packaging']={'fixed_gain':common,'fixed_gain_applies_to':'all six candidates, loops and releases',
                         'fixed_wavs_derived_from':'browser derives PCM24 fixed-gain copies with one scalar; no per-event processing',
                         'listening_packs':'browser generates both ZIPs on demand to avoid hosting duplicate audio'}
    report['source']={'base_commit':'2f909395feee9962e15fcf60ea0feee0c16f1615',
                      'commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=t.ROOT,text=True).strip(),
                      'branch':'claude/trefoil-held-proof','distribution':'local source commit; complete replay source in download',
                      'production_kotlin_changed':False}
    report['published_wav_sha256']={f.name:hashlib.sha256(f.read_bytes()).hexdigest()
                                  for f in sorted(destination.glob('*.wav'))}
    (destination/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    (destination/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    shutil.copy2(Path(__file__).parent/'README.md',destination/'DESIGN.md')
    # Include source under its original paths so ROOT resolution works after unzip.
    with zipfile.ZipFile(destination/'trefoil-hold-source.zip','w',zipfile.ZIP_DEFLATED) as z:
        for f in sorted(Path(__file__).parent.rglob('*')):
            if f.is_file() and '__pycache__' not in f.parts:
                z.write(f,f.relative_to(t.ROOT))
        for name in ('snipkit.py','probes.py'):
            f=t.ROOT/'.claude/skills/luthier/scripts'/name; z.write(f,f.relative_to(t.ROOT))
    for archive in ('trefoil-hold-listening.zip','trefoil-hold-fixed-gain.zip'):
        (destination/archive).unlink(missing_ok=True)
    expanded=sum(f.stat().st_size for f in (site/'dist').rglob('*') if f.is_file())
    if expanded>256*1024**2: raise ValueError('Site exceeds 256 MiB expanded archive budget')
    print(json.dumps({'destination':str(destination),'wav_count':len(report['published_wav_sha256']),
                      'common_gain':common,'checks':report['check_count']}))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('audio',type=Path);p.add_argument('site',type=Path)
    a=p.parse_args();main(a.audio,a.site)
