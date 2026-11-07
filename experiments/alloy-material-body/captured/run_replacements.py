import json,subprocess,time
from model import R
jobs=json.loads((R/'jobs.json').read_text())
part=[j for j in jobs if j['id'].startswith('rounded_plug_') and
      ('_m025' in j['id'] or '_m0250' in j['id'] or '_dense_' in j['id'])]
assert len(part)==14,len(part)
tsv=R/'replacement-jobs.tsv'
tsv.write_text('\n'.join('\t'.join([j['id'],j['patch'],str(j['oversample']),j['seconds'],str(j['native']).lower()]) for j in part)+'\n')
cp=str(R)+':'+(R/'classpath.txt').read_text().strip()
started=time.monotonic()
with (R/'replacement.log').open('w') as log:
    result=subprocess.run(['/workspace/temurin-jdk-17/bin/java','-Xmx768m','-cp',cp,'MaterialProbe',str(tsv),str(R/'measurements')],stdout=log,stderr=subprocess.STDOUT)
report=dict(actualExit=result.returncode,elapsedSeconds=time.monotonic()-started,
            reason='Bass lower-half originalWeight=(2*MATERIAL)^2; endpoints and other roles unchanged',
            jobs=[j['id'] for j in part])
(R/'replacement-exit.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report))
raise SystemExit(result.returncode)
