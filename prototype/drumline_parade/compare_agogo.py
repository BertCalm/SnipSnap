"""Recorded agogo controls at matched attack levels; fit-set error, not realism."""
import argparse,json
from pathlib import Path
import numpy as np
from reference_compare import load,bands
import snipkit as sk

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--references',type=Path,required=True);ap.add_argument('--previous',type=Path,required=True);ap.add_argument('--current',type=Path,required=True);a=ap.parse_args()
 dest=a.current/'references';dest.mkdir(exist_ok=True);rows=[];cards=[]
 for name,level in [('agogo_low',2),('agogo_high',3)]:
  ref=load(a.references/f'{name}-v{level}.wav');old=load(a.previous/'samba-bateria'/f'{name}.wav');new=load(a.current/'samba-bateria'/f'{name}.wav')
  target=bands(ref);mask=target>target.max()-45
  def err(x):return float(np.mean(np.abs(bands(x)-target)[mask]))
  control=err(ref*.3/np.sqrt(np.mean((ref[:6615]*.3)**2)));assert control<1e-8
  rows.append({'pad':name,'previous_error_db':err(old),'new_error_db':err(new),'scaled_reference_control_db':control})
  gain=.9/max(np.max(np.abs(x)) for x in [ref,old,new]);players=[]
  for label,x in [('Recorded reference',ref),('Previous synthesis',old),('Revised synthesis',new)]:
   filename=f'{name}-{label.split()[0].lower()}.wav';sk.write_wav(str(dest/filename),x*gain)
   players.append(f'<p>{label}</p><audio controls preload="none" src="references/{filename}?revision=7"></audio>')
  cards.append('<article><h3>'+name.replace('_',' ').title()+'</h3>'+''.join(players)+'</article>')
 report={'scope':'Fitting-reference third-octave envelope error, first150ms; not held-out validation or realism','results':rows}
 (dest/'agogo-report.json').write_text(json.dumps(report,indent=2)+'\n')
 (dest/'NOTICE-agogo.txt').write_text((Path(__file__).parent/'NOTICE-agogo.txt').read_text())
 page=a.current/'index.html';s=page.read_text();i=s.index('<section>');s=s[:i]+'<section><h2>Agogô recorded references</h2><p>Attack-level matched controls. The other Samba instruments use modeled gestures and have not been fitted to matching recordings.</p><p><a href="references/NOTICE-agogo.txt">Recording credits</a></p><div class="grid">'+''.join(cards)+'</div></section>'+s[i:];page.write_text(s)
 print(json.dumps(report))
if __name__=='__main__':main()
