import tempfile,subprocess,json,os,sys
from pathlib import Path
r=Path('/workspace/SnipSnap');p=Path('/workspace/halobar-fresh')
fake='''#!/usr/bin/env python3
import sys,os,json,shutil
from pathlib import Path
args=sys.argv[3:];root=Path(os.environ['FAKE_ROOT']);mode=os.environ['FAKE_MODE']
with (root/'commands.jsonl').open('a') as f:f.write(json.dumps(args)+'\\n')
if args==['get-state']:print('device')
elif args[:2]==['shell','getprop']:
 key=args[2];print({'ro.hardware':'ranchu' if mode=='emulator' else 'testboard','ro.product.model':'Test Phone','ro.build.fingerprint':'example/phone/build','ro.kernel.qemu':'0'}[key])
elif args[0]=='install':print('Success')
elif args[:3]==['shell','pm','path']:print('package:/data/app/'+args[3]+'/base.apk')
elif args[0]=='pull':
 is_test='.test/' in args[1];shutil.copyfile(root/('test.apk' if is_test else 'app.apk'),args[2])
 if mode=='wrong-apk':Path(args[2]).write_bytes(b'wrong installation')
elif args[:3]==['shell','am','instrument']:
 run_id=args[args.index('halobarRunId')+1];runs=int(args[args.index('halobarRuns')+1])
 rows=[dict(voice=v,case=c,runs=[dict(elapsedMs=100)]*runs) for v in ['VIBRAPHONE','MARIMBA','XYLOPHONE','GLOCKENSPIEL','CROTALES','TUBULAR_BELLS'] for c in ['FRESH','LONG','DRY','RECALL','RATE','RETURN','MOTION','SPACE']]
 report=dict(runId='old-report' if mode=='stale' else run_id,status='completed',device=dict(emulatorLikely=False),cases=rows[:-1] if mode=='missing-case' else rows)
 (root/'device.json').write_text(json.dumps(report))
 print('FAILURES!!!' if mode=='test-failure' else 'OK (1 test)')
 print('INSTRUMENTATION_CODE: -1')
elif args[:3]==['exec-out','run-as','com.snipsnap.app.brassgrow']:print((root/'device.json').read_text())
else:raise RuntimeError(args)
'''
rows=[]
for mode in ['success','test-failure','wrong-apk','stale','missing-case','emulator']:
 with tempfile.TemporaryDirectory(prefix='halobar-runner-check-') as folder:
  d=Path(folder);adb=d/'fake-adb';adb.write_text(fake);adb.chmod(0o700)
  for name in ['app','test']:(d/(name+'.apk')).write_bytes(name.encode())
  output=d/'report.json';env=os.environ.copy();env.update(FAKE_ROOT=str(d),FAKE_MODE=mode)
  command=[sys.executable,str(r/'scripts/run_halobar_device_bench.py'),'--adb',str(adb),'--serial','fixture','--app-apk',str(d/'app.apk'),'--test-apk',str(d/'test.apk'),'--install','--runs','2','--output',str(output)]
  result=subprocess.run(command,capture_output=True,text=True,env=env)
  assert (result.returncode==0)==(mode=='success'),(mode,result.stdout,result.stderr)
  assert output.exists()==(mode=='success')
  commands=[json.loads(line) for line in (d/'commands.jsonl').read_text().splitlines()]
  if mode=='emulator':assert not any(c[0]=='install' for c in commands)
  if mode=='wrong-apk':assert not any(c[:3]==['shell','am','instrument'] for c in commands)
  if mode=='success':
   report=json.loads(output.read_text());assert len(report['deviceReport']['cases'])==48
   assert not report['playbackVerified'] and not report['mpcVerified'] and not report['ownerListeningApproved']
  rows.append(dict(scenario=mode,exitCode=result.returncode,reportWritten=output.exists(),stderr=result.stderr.strip()))
  print(mode,'validated',flush=True)
(p/'runner-validation.json').write_text(json.dumps(dict(state='passed',scope='Python runner with isolated simulated adb; no Android execution',scenarios=rows),indent=2)+'\n')
