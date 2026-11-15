from pathlib import Path
import subprocess,json
p=Path('/workspace/halobar-finish/release');r=Path('/workspace/SnipSnap');j=Path('/workspace/halobar-jdk-location.txt').read_text().strip();cp=Path('/workspace/halobar-next/study-classpath.txt').read_text().strip();root=Path('/home/agent/.gradle/caches/modules-2/files-2.1');jars=[]
for g,a in [('org.jetbrains.kotlin','kotlin-compiler-embeddable'),('org.jetbrains.kotlin','kotlin-stdlib'),('org.jetbrains.kotlin','kotlin-script-runtime'),('org.jetbrains.kotlin','kotlin-reflect'),('org.jetbrains.intellij.deps','trove4j'),('org.jetbrains','annotations'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm')]:jars.append(str(sorted((root/g/a).rglob('*.jar'))[-1]))
subprocess.run([j+'/bin/javac','-cp',cp,'-d',str(p),str(p/'ExportPolicyOracle.java')],check=True)
mutations=[('optin','shell','HalobarPadMaker.kt','midi, layers, cycleLayers = true, mpcVelocityCycle = true)','midi, layers, cycleLayers = true, mpcVelocityCycle = false)','HALOBAR six-strike export guard'),('persist','kit','InstrumentStore.kt','mpcVelocityCycle = zone.mpcVelocityCycle,','mpcVelocityCycle = false,','HALOBAR persisted export policy guard'),('native','mpc3','Mpc3TrackWriter.kt','values.toList().asReversed().flatten()','values.toList().flatten()','HALOBAR native window order guard'),('filter','synth','Dsp.kt','buf[i - 1] = w2','buf[i] = w2','paired filter output order guard')]
mutations=[item for item in mutations if item[0] != 'filter']
rows=[]
for name,module,filename,old,new,diagnostic in mutations:
 normal=subprocess.run([j+'/bin/java','-Xmx1g','-cp',cp+':'+str(p),'ExportPolicyOracle',name],capture_output=True,text=True);assert normal.returncode==0,normal.stderr
 source=(r/module/'src/main/kotlin/com/snipsnap'/module/filename).read_text();assert source.count(old)==1,(name,source.count(old));dest=p/'mutations'/name;dest.mkdir(parents=True,exist_ok=True);f=dest/filename;f.write_text(source.replace(old,new))
 subprocess.run([j+'/bin/java','-Xmx1g','-cp',':'.join(jars),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-module-name',module,'-Xfriend-paths='+str(r/module/'build/classes/kotlin/main'),'-classpath',cp,'-d',str(dest/'classes'),str(f)],check=True)
 mutant=subprocess.run([j+'/bin/java','-Xmx1g','-cp',str(dest/'classes')+':'+cp+':'+str(p),'ExportPolicyOracle',name],capture_output=True,text=True)
 assert mutant.returncode!=0 and 'AssertionError: '+diagnostic in mutant.stderr,(name,mutant.stderr)
 rows.append(dict(guard=name,retainedExitCode=normal.returncode,mutantExitCode=mutant.returncode,diagnostic=diagnostic,mutantStderr=mutant.stderr.strip()))
 print(name,'guard falsified independently',flush=True)
(p/'guard-validation.json').write_text(json.dumps(dict(state='passed',productionMutated=False,rows=rows),indent=2)+'\n')
