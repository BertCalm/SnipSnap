"""Fit-set matched-attack band-envelope comparison; not a realism verdict."""
import argparse,json,math
from pathlib import Path
import numpy as np
from scipy import signal
from scipy.io import wavfile
import study
import snipkit as sk
ROOT=Path(__file__).resolve().parents[2]

def load(p):
 rate,x=wavfile.read(p)
 x=x.astype(float)
 if x.ndim>1:x=x.mean(axis=1)
 if rate!=44100:
  g=math.gcd(rate,44100);x=signal.resample_poly(x,44100//g,rate//g);rate=44100
 at=max(0,int(np.argmax(np.abs(x)>.05*np.max(np.abs(x))))-22)
 x=x[at:at+round(4.5*rate)];x/=max(np.sqrt(np.mean(x[:6615]**2)),1e-20)
 return x

def bands(x):
 x=np.pad(x,(0,max(0,6615-len(x))));out=[]
 for fc in 200*2.**(np.arange(19)/3):
  y=signal.sosfilt(signal.butter(3,[fc/2**(1/6),fc*2**(1/6)],btype='bandpass',fs=44100,output='sos'),x)
  out.append([10*np.log10(max(np.mean(y[round(a*44100):round(b*44100)]**2),1e-12)) for a,b in [(0,.02),(.02,.08),(.08,.15)]])
 return np.array(out)

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--references',type=Path,required=True);ap.add_argument('--previous',type=Path,required=True);ap.add_argument('--current',type=Path,required=True);ap.add_argument("--holdout",action="store_true");a=ap.parse_args()
 core=ROOT/'synth/src/main/resources/spore-station/v7'
 refs={'cymbal_crash':a.references/'crash_hard.wav','rimshot':core/'snaremic_snare_rimshot_vl10.wav','cross_stick':core/'snaremic_snare_crossstick_vl16.wav','stick_click':a.references/'stick_click-hard-fit.wav'}
 if a.holdout:
  refs={'cymbal_crash':a.references/'crash_alt.wav','rimshot':core/'snaremic_snare_rimshot_vl11.wav','cross_stick':core/'snaremic_snare_crossstick_vl15.wav','stick_click':a.references/'stick_alt.wav'}
  rows=[]
  for name,p in refs.items():
   target=bands(load(p));mask=target>np.max(target)-45
   def error(folder):return float(np.mean(np.abs(bands(load(folder/'second-line'/f'{name}.wav'))-target)[mask]))
   rows.append({'pad':name,'reference':p.name,'previous_error_db':error(a.previous),'measured_error_db':error(a.current)})
  (a.current/'reference-holdout-report.json').write_text(json.dumps(rows,indent=2)+'\n');print(json.dumps(rows));return
 dest=a.current/'references';dest.mkdir(exist_ok=True);cards=[];rows=[]
 for name,path in refs.items():
  ref=load(path);old=load(a.previous/'second-line'/f'{name}.wav');new=load(a.current/'second-line'/f'{name}.wav')
  target=bands(ref);mask=target>np.max(target)-45
  def err(x):return float(np.mean(np.abs(bands(x)-target)[mask]))
  rows.append({'pad':name,'previous_error_db':err(old),'measured_error_db':err(new),'scaled_reference_control_db':err(ref*.3/np.sqrt(np.mean((ref[:6615]*.3)**2)))})
  gain=.9/max(np.max(np.abs(x)) for x in [ref,old,new]);players=[]
  for label,x,title in [('recording',ref,'Recorded reference'),('previous',old,'Previous synthesis'),('measured',new,'Measured-model candidate')]:
   sk.write_wav(str(dest/f'{name}-{label}.wav'),x*gain)
   players.append(f'<p>{title}</p><audio controls preload="none" src="references/{name}-{label}.wav?revision=3"></audio>')
  cards.append(f'<article><h3>{name.replace("_"," ").title()}</h3>'+''.join(players)+'</article>')
 report={'scope':'Fit-set matched-attack band-envelope MAE, 200–12800 Hz, first 150 ms; not held-out realism or alias evidence','results':rows}
 (dest/'report.json').write_text(json.dumps(report,indent=2)+'\n');(dest/'NOTICE.txt').write_text((Path(__file__).parent/'NOTICE-measured.txt').read_text())
 page=a.current/'index.html';s=page.read_text();i=s.index('<section>')
 s=s[:i]+'''<section><h2>Against real acoustic references</h2><p>Recorded reference → previous synthesis → measured-model candidate. Matched attack levels. The recording is a comparison control; the candidate is synthesized.</p><p>The crash, snare rimshot and cross-stick use measured soft/hard data. Stick softness is modeled from one recorded level. Samba rim variants, cymbal choke and touch are adaptations, not instrument-specific recorded fits.</p><p><a href="references/NOTICE.txt">Reference sources and credits</a></p><div class="grid">'''+''.join(cards)+'</div></section>'+s[i:];page.write_text(s)
 print(json.dumps(report))
if __name__=='__main__':main()
