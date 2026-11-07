"""Render an actual exported C4 across a loop and key lift, without a Gradle rebuild."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[2]


def main(output,java_home=None):
    java_home=java_home or os.environ.get('JAVA_HOME')
    javac=str(Path(java_home)/'bin/javac') if java_home else shutil.which('javac')
    java=str(Path(java_home)/'bin/java') if java_home else shutil.which('java')
    if not javac or not Path(javac).is_file():raise RuntimeError('Select a full JDK with JAVA_HOME or --java-home')
    jars=[]
    for module in ('shell','synth','kit','audio','xpm','mpc3','json','loop'):
        options=list((ROOT/module/'build/libs').glob('*.jar'))
        if not options:raise RuntimeError('Build the JVM modules before rendering the sampler proof')
        jars.append(str(sorted(options)[-1]))
    cache=Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle'))/'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib'
    libraries=sorted(cache.glob('*/*/kotlin-stdlib-*.jar'))
    if not libraries:raise RuntimeError('Kotlin standard library missing from Gradle cache')
    jars.append(str(libraries[-1]));classpath=os.pathsep.join(jars)
    with tempfile.TemporaryDirectory(prefix='trefoil-sampler-preview-') as folder:
        source=Path(__file__).with_name('NativeSamplerPreview.java')
        subprocess.run([javac,'-cp',classpath,'-d',folder,str(source)],check=True)
        result=subprocess.check_output([java,'-cp',folder+os.pathsep+classpath,'NativeSamplerPreview',str(output.resolve())],text=True)
        report=json.loads(result.strip())
    (output/'sampler-loop-proof.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('output',type=Path);p.add_argument('--java-home');a=p.parse_args();main(a.output,a.java_home)
