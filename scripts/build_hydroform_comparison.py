"""Prepare explicit Site assets after native rendering and raw parity verification.
Usage: python3 scripts/build_hydroform_comparison.py native-output /path/to/site/dist
Native audio keeps the house output; matched files change gain only for comparison.
"""
from pathlib import Path
import importlib.util,json,shutil,sys,zipfile
import numpy as np
from scipy.io import wavfile
root=Path(__file__).resolve().parents[1]
s=importlib.util.spec_from_file_location('ref',root/'reference/hydroform/prototype.py');p=importlib.util.module_from_spec(s);s.loader.exec_module(p)
native=Path(sys.argv[1]);dist=Path(sys.argv[2]);target=dist/'native-r6';target.mkdir(parents=True,exist_ok=True)
rows=json.loads((native/'native-manifest.json').read_text())
for row in rows:
 source=native/row['file'];dest=target/row['file'];dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,dest)
 rate,pcm=wavfile.read(source);assert rate==44100
 a=pcm.astype(float)/32768;gain=min(.11/max(p.weight(a),1e-12),.92/max(np.max(abs(a)),1e-12))
 matched=target/'matched'/source.name;matched.parent.mkdir(exist_ok=True)
 p.wav(matched,a*gain)
 row['file']=str(dest.relative_to(dist));row['matched_file']=str(matched.relative_to(dist));row['comparison_gain']=gain
 if row['recipe']:
  source=native/row['recipe'];dest=target/row['recipe'];dest.parent.mkdir(exist_ok=True);shutil.copy2(source,dest);row['recipe']=str(dest.relative_to(dist))
comparison=json.loads((native/'port-comparison.json').read_text())
(dist/'native-r6-manifest.json').write_text(json.dumps({'revision':'R6','entries':rows,'parity':comparison,
 'scope':'Native Kotlin model 1 at house output; comparison WAVs receive gain only using the Python weighted-RMS proxy. Raw parity and completed test reports do not establish native listening acceptance.'},indent=2))
shutil.copy2(native/'port-comparison.json',target/'port-comparison.json')
with zipfile.ZipFile(dist/'hydroform-native-r6.zip','w',zipfile.ZIP_DEFLATED) as z:
 for f in target.rglob('*'):
  if f.is_file():z.write(f,str(f.relative_to(dist)))
 z.write(dist/'native-r6-manifest.json','native-r6-manifest.json')
print('Prepared',len(rows),'native/Python comparison pairs')
