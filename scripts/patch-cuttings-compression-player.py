"""Install synchronized groove A/B/C playback, retaining original pad playback."""
from pathlib import Path
import re,sys
root=Path(sys.argv[1]);html=(root/'index.html').read_text()
control='<div class="control" style="margin:12px 0"><label for="compression">Groove compression · study 2</label><select id="compression"><option value="finished">Original</option><option value="glue" selected>Glue · tight and dense</option><option value="squeeze">Squeeze · heavy pumping</option></select><small>Play groove, then switch here while it loops. Average levels matched. Pad buttons play the original samples.</small></div>'
if 'id="compression"' in html:
 html=re.sub(r'<div class="control" style="margin:12px 0">.*?</div>',control,html)
else:html=html.replace('<div class="transport">',control+'<div class="transport">')
html=re.sub(r'<script src="app.js[^"]*">','<script src="app.js?v=compression2">',html)
(root/'index.html').write_text(html)
js=(root/'app.js').read_text()
if 'let grooveBus=null;' not in js:js=js.replace("'use strict';","'use strict';\nlet grooveBus=null;")
js=js.replace('function stop(){generation++;','function stop(){grooveBus=null;generation++;')
js=js.replace('s.start(at);return s;','s.auditionGain=g;s.start(at);return s;')
a=js.index('async function groove(){');b=js.index('function attempt(fn)',a)
js=js[:a]+'''async function groove(){
  stop();const ticket=generation;status('Loading synchronized comparison…');await ready();
  const modes=['finished','glue','squeeze'];
  const bs=await Promise.all(modes.map(mode=>buffer(manifest.grooves[mode].file)));
  if(ticket!==generation)return;
  const start=ctx.currentTime+.06;grooveBus={};
  modes.forEach((mode,i)=>{
    const gain=manifest.grooves[mode].gain;
    const s=play(bs[i],mode===$('compression').value?gain:0,start);s.loop=true;
    grooveBus[mode]={source:s,gain};
  });
  status(`Looping · ${manifest.bpm} BPM · ${$('compression').value==='finished'?'Original':$('compression').value} · switch compression while playing`);
}
'''+js[b:]
js=re.sub(r"\n\$\('compression'\)\.onchange=.*?;\};\n?",'\n',js)
js+='''
$('compression').onchange=()=>{
  if(!grooveBus){status('Compression selected. Press Play groove.');return;}
  for(const [mode,bus] of Object.entries(grooveBus))bus.source.auditionGain.gain.setTargetAtTime(mode===$('compression').value?bus.gain:0,ctx.currentTime,.012);
  status(`Looping · ${manifest.bpm} BPM · ${$('compression').value==='finished'?'Original':$('compression').value} · same beat, matched average level`);
};
'''
js=js.replace("fetch(id+'/manifest.json')","fetch(id+'/manifest.json',{cache:'no-store'})")
(root/'app.js').write_text(js)
