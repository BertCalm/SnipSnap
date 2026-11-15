from pathlib import Path
import subprocess,json,hashlib,os
p=Path('/workspace/halobar-fresh');r=Path('/workspace/SnipSnap');j=Path('/workspace/halobar-jdk-location.txt').read_text().strip();cp=Path('/workspace/halobar-native-classpath.txt').read_text().strip();root=Path('/home/agent/.gradle/caches/modules-2/files-2.1')
source=(r/'synth/src/main/kotlin/com/snipsnap/synth/Halobar.kt').read_text();head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=r,text=True).strip()
(p/'inputs.json').write_text(json.dumps(dict(sourceCommit=head,sourceSha256=hashlib.sha256(source.encode()).hexdigest(),compiledClasspath=cp,jdk=j,profileOnly=True,productionChanged=False),indent=2)+'\n')
# The external copy measures stage boundaries; no audio expression or control flow changes.
s=source.replace('object Halobar {','object Halobar {\n    val profileNanos = LongArray(6)\n')
for index,name in enumerate(['bars','echoes','down','phase','haloConvolution','finish']):
 start=s.index('    private fun '+name+'(');body=s.index('{',start);depth=1;end=body+1
 while depth:
  depth+=(s[end]=='{')-(s[end]=='}');end+=1
 s=s[:end-1]+'\n        } finally { profileNanos['+str(index)+'] += System.nanoTime()-profileStarted }\n    '+s[end-1:]
 s=s[:body+1]+'\n        val profileStarted=System.nanoTime()\n        try {\n'+s[body+1:]
f=p/'timed/Halobar.kt';f.parent.mkdir(exist_ok=True);f.write_text(s)
jars=[]
for g,a in [('org.jetbrains.kotlin','kotlin-compiler-embeddable'),('org.jetbrains.kotlin','kotlin-stdlib'),('org.jetbrains.kotlin','kotlin-script-runtime'),('org.jetbrains.kotlin','kotlin-reflect'),('org.jetbrains.intellij.deps','trove4j'),('org.jetbrains','annotations'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm')]:jars.append(str(sorted((root/g/a).rglob('*.jar'))[-1]))
subprocess.run([j+'/bin/java','-Xmx1g','-cp',':'.join(jars),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-module-name','synth','-Xfriend-paths='+str(r/'synth/build/classes/kotlin/main'),'-classpath',cp,'-d',str(p/'timed/classes'),str(f)],check=True)
subprocess.run([j+'/bin/javac','-cp',str(p/'timed/classes')+':'+cp,str(p/'StageBench.java')],check=True)
cmd=[j+'/bin/java','-Xmx1g','-XX:+UseG1GC','-XX:ActiveProcessorCount=4','-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1','-cp',str(p/'timed/classes')+':'+cp+':'+str(p),'StageBench']
with (p/'stage-times.tsv').open('w') as f:subprocess.run(cmd,stdout=f,check=True,preexec_fn=lambda:os.sched_setaffinity(0,{min(os.sched_getaffinity(0))}))
print('Stage timings complete',flush=True)
