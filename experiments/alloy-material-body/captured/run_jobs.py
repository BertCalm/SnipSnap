import concurrent.futures,json,subprocess,time
from model import R
cp=str(R)+':'+(R/'classpath.txt').read_text().strip()
subprocess.run(['/workspace/temurin-jdk-17/bin/javac','-cp',cp,str(R/'MaterialProbe.java'),str(R/'EncodePredecessors.java')],check=True)
def worker(i):
    args=['/workspace/temurin-jdk-17/bin/java','-Xmx768m','-cp',cp,'MaterialProbe',str(R/f'jobs{i}.tsv'),str(R/'measurements')]
    with (R/f'worker{i}.log').open('w') as log:
        result=subprocess.run(args,stdout=log,stderr=subprocess.STDOUT)
    return result.returncode
started=time.monotonic()
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    exits=list(pool.map(worker,[0,1]))
report=dict(actualExits=exits,elapsedSeconds=time.monotonic()-started)
(R/'render-exit.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report))
raise SystemExit(0 if exits==[0,0] else 1)
