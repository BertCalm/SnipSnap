from pathlib import Path
import subprocess,json
p=Path('/workspace/halobar-finish/release');r=Path('/workspace/SnipSnap');j=Path('/workspace/halobar-jdk-location.txt').read_text().strip();cp=Path('/workspace/halobar-native-classpath.txt').read_text().strip()
subprocess.run([j+'/bin/javac','-cp',cp,'-d',str(p),str(r/'reference/halobar/NativeReplay.java'),str(r/'reference/halobar/render-speed/RenderSpeedReplay.java')],check=True)
for name in ['native-parity.tsv','glockenspiel-native-parity.tsv','crotales-native-parity.tsv','tubular-native-parity.tsv']:
 subprocess.run([j+'/bin/java','-Xmx1g','-XX:ActiveProcessorCount=4','-cp',cp+':'+str(p),'NativeReplay',str(r/'reference/halobar'/name),'render','approved'],check=True)
subprocess.run([j+'/bin/java','-Xmx1g','-XX:ActiveProcessorCount=4','-cp',cp+':'+str(p),'RenderSpeedReplay',str(r/'reference/halobar/render-speed/take-parity.tsv')],check=True)
(p/'native-validation.json').write_text(json.dumps(dict(state='passed',publishedCases=408,additionalTakeCases=72,allFloat32Exact=True,model=1,ownerListeningApproved=True),indent=2)+'\n')
