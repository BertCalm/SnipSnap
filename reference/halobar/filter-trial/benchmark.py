from pathlib import Path
import subprocess,json,os,statistics,math,time
p=Path('/workspace/halobar-finish');r=Path('/workspace/SnipSnap');j=Path('/workspace/halobar-jdk-location.txt').read_text().strip();cp=Path('/workspace/halobar-native-classpath.txt').read_text().strip();head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=r,text=True).strip()
assert not subprocess.check_output(['git','status','--porcelain'],cwd=r,text=True).strip()
assert json.loads((p/'focused-status.json').read_text())['exitCode']==0
subprocess.run([j+'/bin/javac','-cp',cp,'-d',str(p),'/workspace/halobar-speed/confirmation/ConfirmBench.java'],check=True)
results={};cpu=min(os.sched_getaffinity(0))
for index,label in enumerate(['before','after','after','before']):
 (p/'benchmark-progress.json').write_text(json.dumps(dict(round=index,label=label,sourceCommit=head,cpu=cpu,started=time.time())))
 path=p/f'confirmation-{label}-{index}.tsv';prefix=str(p/'baseline-classes')+':' if label=='before' else ''
 with path.open('w') as output:subprocess.run([j+'/bin/java','-Xmx1g','-XX:+UseG1GC','-XX:ActiveProcessorCount=4','-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1','-cp',prefix+cp+':'+str(p),'ConfirmBench'],stdout=output,check=True,preexec_fn=lambda:os.sched_setaffinity(0,{cpu}))
 for line in path.read_text().splitlines():
  voice,measurement,wall,used=line.split('\t');results.setdefault(voice,{}).setdefault(label,[]).append([float(wall),float(used)])
 summary={v:{phase:dict(n=len(a),medianMs=statistics.median(x[0] for x in a),p95Ms=sorted(x[0] for x in a)[math.ceil(.95*len(a))-1],medianCpuMs=statistics.median(x[1] for x in a),samples=a) for phase,a in phases.items()} for v,phases in results.items()}
 (p/'benchmark-results.json').write_text(json.dumps(dict(sourceCommit=head,baselineCommit='6c60fc02e65175de55a172e53821847a54fc350a',warmupsPerVoicePerRound=20,measuredPerVoicePerRound=25,rounds=['before','after','after','before'],cpu=cpu,affinityPinned=True,jdk='21.0.13+9',heap='1g',gc='G1',parallelGcWorkers=1,concurrentGcWorkers=1,freshClearsAllFourAudioCaches=True,immutableHaloSpectraMayBeWarm=True,phoneMeasured=False,results=summary),indent=2)+'\n')
 print('Completed',index,label,flush=True)
assert all(len(a)==50 for phases in results.values() for a in phases.values())
