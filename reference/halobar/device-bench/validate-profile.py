from pathlib import Path
import subprocess,json,collections
p=Path('/workspace/halobar-fresh');r=Path('/workspace/SnipSnap');j=Path('/workspace/halobar-jdk-location.txt').read_text().strip();cp=Path('/workspace/halobar-native-classpath.txt').read_text().strip()
groups=collections.defaultdict(list)
for name in ['native-parity.tsv','glockenspiel-native-parity.tsv','crotales-native-parity.tsv','tubular-native-parity.tsv']:
 for line in (r/'reference/halobar'/name).read_text().splitlines()[1:]:groups[line.split('\t')[1]].append(line)
selected=[]
for voice,rows in groups.items():selected.extend(rows[i] for i in [0,1,len(rows)//2,len(rows)-1])
assert len(selected)==24 and len(set(x.split('\t')[0] for x in selected))==24
fixture=p/'profile-parity.tsv';fixture.write_text('id\tvoice\tvelocity\toversample\tmacros\tframes\tsha256\n'+'\n'.join(selected)+'\n')
subprocess.run([j+'/bin/javac','-cp',cp,'-d',str(p),str(r/'reference/halobar/NativeReplay.java')],check=True)
subprocess.run([j+'/bin/java','-Xmx1g','-XX:ActiveProcessorCount=4','-cp',str(p/'timed/classes')+':'+cp+':'+str(p),'NativeReplay',str(fixture),'render','unchanged published'],check=True)
(p/'profile-validation.json').write_text(json.dumps(dict(state='passed',cases=24,scope='External stage-timed copy versus existing published Float32 fixture subset; six voices',productionChanged=False,androidExecuted=False),indent=2)+'\n')
