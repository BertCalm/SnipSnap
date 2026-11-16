"""Verify unaffected grid WAVs and add matched-attack before/after auditions."""
import argparse, hashlib, json
from pathlib import Path
import numpy as np
from scipy.io import wavfile
from scipy import signal
import study
import snipkit as sk

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--previous',type=Path,required=True);ap.add_argument('--current',type=Path,required=True);a=ap.parse_args()
 out=a.current/'comparison';out.mkdir(exist_ok=True);rows=[];cards=[];unchanged=0
 def read(p):
  rate,x=wavfile.read(p);assert rate==sk.RATE
  return x.astype(float)/32768
 def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
 for kit,(_,pads) in study.KITS.items():
  for i,p in enumerate(pads):
   name,fam=p[:2]
   if kit!='second-line' or name not in ('bass_open','bass_muted','snare_center','snare_ghost','snare_drag','snare_flam','press_buzz','snare_roll','turnaround'):
    for v in [.3,.6,1.]:
     for take in range(3):
      path=f'{kit}/{i+1:02}_{name}_v{v}_t{take}.wav'
      assert digest(a.previous/path)==digest(a.current/path),path
      unchanged+=1
    continue
   before=read(a.previous/kit/f'{name}.wav');after=read(a.current/kit/f'{name}.wav')
   n=round(.15*sk.RATE)
   before/=max(np.sqrt(np.mean(before[:n]**2)),1e-12);after/=max(np.sqrt(np.mean(after[:n]**2)),1e-12)
   gain=.9/max(np.max(np.abs(before)),np.max(np.abs(after)))
   for label,x in [('before',before),('after',after)]:sk.write_wav(str(out/f'{kit}-{name}-{label}.wav'),x*gain)
   def tail_ratio(x):
    return float(10*np.log10(max(np.mean(x[round(.3*sk.RATE):round(.7*sk.RATE)]**2),1e-20)/max(np.mean(x[:n]**2),1e-20)))
   def attack_ratio(x):
    # Dimensionless attack/body contrast survives the comparison gain match.
    return float(10*np.log10(max(np.mean(x[:round(.012*sk.RATE)]**2),1e-20)/max(np.mean(x[round(.025*sk.RATE):round(.10*sk.RATE)]**2),1e-20)))
   def low_tail(x):
    low=signal.sosfilt(signal.butter(2,120,fs=sk.RATE,output='sos'),x)
    return float(10*np.log10(max(np.mean(low[round(.15*sk.RATE):round(.45*sk.RATE)]**2),1e-20)/max(np.mean(x[:n]**2),1e-20)))
   rows.append({'kit':kit,'pad':name,'before_late_early_db':tail_ratio(before),'after_late_early_db':tail_ratio(after),
    'before_attack_body_db':attack_ratio(before),'after_attack_body_db':attack_ratio(after),
    'before_low_tail_db':low_tail(before),'after_low_tail_db':low_tail(after)})
   cards.append(f'<article><h3>{kit.replace("-"," ").title()} · {name.replace("_"," ").title()}</h3><p>Before</p><audio controls preload="none" src="comparison/{kit}-{name}-before.wav"></audio><p>Revised</p><audio controls preload="none" src="comparison/{kit}-{name}-after.wav"></audio></article>')
 report={'unchanged_grid_wavs':unchanged,'changed_pads':len(rows),'comparisons':rows}
 (out/'report.json').write_text(json.dumps(report,indent=2))
 page=a.current/'index.html';s=page.read_text();pos=s.index('<section>')
 s=s[:pos]+'<section><h2>Second Line snare and bass revision</h2><p>Matched-level comparisons. The complete kits below use the revised sounds.</p><div class="grid">'+''.join(cards)+'</div></section>'+s[pos:];page.write_text(s.replace('.wav"', '.wav?revision=6"').replace('.zip"', '.zip?revision=6"'))
 print(json.dumps(report))
if __name__=='__main__':main()
