"""Offline whole-groove compressor comparison; does not alter accepted pad banks."""
import json, math, sys, wave
from pathlib import Path
import numpy as np
root=Path(sys.argv[1])
settings={'glue':(-20.,3.5,8.,.012,.140),'squeeze':(-26.,8.,6.,.003,.090)}
report={}
for kit in ('cuttings-pocket','cuttings-night','cuttings-circuit'):
 p=root/kit; manifest=json.loads((p/'manifest.json').read_text())
 with wave.open(str(p/'audio/groove.wav'),'rb') as w:
  assert w.getsampwidth()==2 and w.getnchannels()==1 and w.getframerate()==44100
  x=np.frombuffer(w.readframes(w.getnframes()),'<i2').astype(float)/32768
 versions={'finished':x}; reductions={}
 for name,(threshold,ratio,knee,attack,release) in settings.items():
  # Soft-knee peak detector, smoothed gain reduction in dB. Slow attack
  # retains the front of the hit; release brings up tails between hits.
  over=20*np.log10(np.maximum(np.abs(x),1e-9))-threshold
  desired=np.where(over < -knee/2,0,np.where(over>knee/2,over*(1-1/ratio),(over+knee/2)**2/(2*knee)*(1-1/ratio)))
  a=math.exp(-1/(44100*attack));r=math.exp(-1/(44100*release));env=0.;gr=np.empty(len(x))
  for i,v in enumerate(desired):
   c=a if v>env else r;env=c*env+(1-c)*v;gr[i]=env
  versions[name]=x*10**(-gr/20);reductions[name]=float(np.max(gr))
 # Equal full-file RMS, including identical silence, with common headroom.
 rms={k:float(np.sqrt(np.mean(v*v))) for k,v in versions.items()}
 target=min(rms['finished'],*(.88*rms[k]/np.max(np.abs(v)) for k,v in versions.items()))
 report[kit]={}
 for name,v in versions.items():
  gain=target/rms[name];y=v*gain
  assert np.isfinite(y).all() and np.max(np.abs(y))<=.880001
  if name=='finished':manifest['grooves'][name]={'file':'audio/groove.wav','gain':gain}
  else:
   file=f'audio/groove_{name}.wav'
   with wave.open(str(p/file),'wb') as w:
    w.setnchannels(1);w.setsampwidth(2);w.setframerate(44100);w.writeframes(np.rint(y*32768).astype('<i2').tobytes())
   manifest['grooves'][name]={'file':file,'gain':1}
  report[kit][name]={'rms':float(np.sqrt(np.mean(y*y))),'peak':float(np.max(np.abs(y))),'maxReductionDb':reductions.get(name,0)}
 (p/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
html=(root/'index.html').read_text()
if 'id="compression"' not in html:
 html=html.replace('<div class="transport">','<div class="control" style="margin:12px 0"><label for="compression">Groove compression</label><select id="compression"><option value="finished">Original</option><option value="glue" selected>Glue · punch and body</option><option value="squeeze">Squeeze · stronger pumping</option></select><small>Level-matched groove comparison. Pads stay original. Choose a setting, then press Play groove.</small></div><div class="transport">')
 (root/'index.html').write_text(html)
js=(root/'app.js').read_text()
js=js.replace("const g=manifest.grooves[stage+($('context').checked?'':'_no_context')]", "const g=manifest.grooves[$('compression').value]")
js=js.replace('${stage} · authored dynamics / cycling takes','${$(\'compression\').value===\'finished\'?\'Original\':$(\'compression\').value} · authored dynamics / cycling takes')
if "$('compression').onchange" not in js:
 js+="\n$('compression').onchange=()=>{stop();status('Compression selected. Press Play groove to compare.');};\n"
(root/'app.js').write_text(js)
(root/'compression-report.json').write_text(json.dumps({'method':'Full-file RMS matched, original groove untouched; compression only on groove bus','settings':settings,'measurements':report},indent=2)+'\n')
print(json.dumps(report,indent=2))
