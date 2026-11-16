"""Package actual JVM renders into a portable keyboard audition and compare R0/R1.
Run after :synth:generateBristleAudition: python3 prototype/bristle/kotlin_audition.py OUTPUT
Uses the accepted Python renderer only as a source comparison, never as a JVM substitute.
"""
from pathlib import Path
import base64, json, sys, hashlib
import numpy as np
from scipy import signal
import bristle_proto as baseline
sk,pr=baseline.sk,baseline.pr

def main(directory):
    out=Path(directory)
    manifest=json.loads((out/'manifest.json').read_text())
    bank={}
    peaks=[]; means=[]
    for entry in manifest['renders']:
        name=entry['name']; data=(out/(name+'.wav')).read_bytes()
        assert hashlib.sha256(data).hexdigest()==entry['sha256']
        bank[name]='data:audio/wav;base64,'+base64.b64encode(data).decode()
        x,_=sk.read_wav(str(out/(name+'.wav')))
        peaks.append(float(np.max(np.abs(signal.resample_poly(x,4,1)))))
        means.append(float(np.mean(x)))
    results={}
    players=[]
    for route in ('A','B'):
        raw=np.fromfile(out/(route+'_raw.f32'),dtype='<f4').astype(float)
        ref=baseline.render(route,{},36)
        error=pr.rms(raw-ref)/pr.rms(ref)
        assert error<.001
        results[route]={'raw_relative_error_db':float(20*np.log10(error)),
                        'raw_max_abs_error':float(np.max(np.abs(raw-ref)))}
        for version in ('Python','Kotlin'):
            if version=='Python': x=sk.finish(ref)
            else: x,_=sk.read_wav(str(out/f'{route}_36_2.wav'))
            x=sk.level_to(x)
            name=f'{route}_{version}_matched'
            sk.write_wav(str(out/(name+'.wav')),x)
            data='data:audio/wav;base64,'+base64.b64encode((out/(name+'.wav')).read_bytes()).decode()
            players.append(f'<p>{route} · {version}, matched RMS <audio controls src="{data}"></audio></p>')
        for label in ('phrase','slide'):
            p=out/f'{route}_{label}.wav'
            data='data:audio/wav;base64,'+base64.b64encode(p.read_bytes()).decode()
            players.append(f'<p>{route} · {label} <audio controls src="{data}"></audio></p>')
        soft,_=sk.read_wav(str(out/f'{route}_36_0.wav'))
        hard,_=sk.read_wav(str(out/f'{route}_36_2.wav'))
        results[route]['soft_hard_centroid_ratio']=pr.spectral_centroid(soft)/pr.spectral_centroid(hard)
        ratios=[]
        level_order=True
        for midi in range(24,49):
            layers=[sk.read_wav(str(out/f'{route}_{midi}_{v}.wav'))[0] for v in range(3)]
            ratios.append(pr.spectral_centroid(layers[0])/pr.spectral_centroid(layers[2]))
            level_order &= pr.rms(layers[0]) < pr.rms(layers[1]) < pr.rms(layers[2])
        results[route]['range_soft_hard_centroid_ratio']=[min(ratios),max(ratios)]
        results[route]['all_25_notes_rms_rises_with_touch']=bool(level_order)
        assert level_order
    results['workstation_median_ms']=manifest['medianRenderMs']
    results['saved_recipe_replay']=manifest['savedRecipeReplay']
    results['wav_4x_interpolated_peak_max']=max(peaks)
    results['wav_max_abs_dc']=max(map(abs,means))
    (out/'comparison.json').write_text(json.dumps(results,indent=2))
    page='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Bristle Kotlin audition</title>
<style>body{max-width:950px;margin:35px auto;padding:20px;font:17px system-ui;background:#121a25;color:#e8edf3}h1{color:#f0b765}button,select{padding:12px;margin:4px;font:inherit}#keys{display:flex;flex-wrap:wrap;margin:24px 0}#keys button{min-width:58px}audio{vertical-align:middle;max-width:100%}p{line-height:1.5}</style>
<h1>Bristle · Kotlin round 1</h1><p>Both accepted routes, now rendered by Kotlin. A: drive then filter. B: filter then drive.</p>
<label>Route <select id="route"><option>A</option><option>B</option></select></label>
<label>Touch <select id="touch"><option value="0">Soft</option><option value="1">Medium</option><option value="2" selected>Hard</option></select></label>
<label>Octave <select id="octave"><option value="24">C1–C2</option><option value="36" selected>C2–C3</option></select></label>
<button id="stop">Stop</button><p id="status" role="status"></p><div id="keys"></div><p>Play with the buttons or A W S E D F T G Y H U J K. Each press triggers a finite note; key-up does not change its baked release. Touch chooses an independently rendered layer. This browser keyboard is an audition, not phone or MPC playback evidence.</p>
<h2>Source comparison and phrases</h2><p>Matched clips compare the accepted Python tone to Kotlin at equal RMS. Output conditioning differs. The phrases and keyboard use the native Kotlin output level. Start at a comfortable listening volume.</p>'''+''.join(players)+'''
<p>Listen for the accepted weight and rasp, then whether soft touch is sufficiently different. No sustained loop, round robin or MPC keygroup has been added in this round.</p><script>
const bank=BANK;let current=null,ctx=null,serial=0;const decoded=new Map();const names=['C','C♯','D','D♯','E','F','F♯','G','G♯','A','A♯','B','C'];
function stopKeys(){serial++;if(current){let t=ctx.currentTime;current.gain.gain.setValueAtTime(current.gain.gain.value,t);current.gain.gain.linearRampToValueAtTime(0,t+.005);current.source.stop(t+.006);current=null}}
function stop(){stopKeys();document.querySelectorAll('audio').forEach(a=>a.pause())}
async function play(i){stop();const token=serial;let midi=Number(document.querySelector('#octave').value)+i;let id=document.querySelector('#route').value+'_'+midi+'_'+document.querySelector('#touch').value;
try{ctx=ctx||new (window.AudioContext||window.webkitAudioContext)();await ctx.resume();
if(!decoded.has(id)){const bytes=Uint8Array.from(atob(bank[id].split(',')[1]),c=>c.charCodeAt(0));decoded.set(id,ctx.decodeAudioData(bytes.buffer))}
const buffer=await decoded.get(id);if(token!==serial)return;
const source=ctx.createBufferSource(),gain=ctx.createGain();source.buffer=buffer;source.connect(gain);gain.connect(ctx.destination);current={source,gain};source.start();source.onended=()=>{source.disconnect();gain.disconnect();if(current?.source===source)current=null};
}catch(err){document.querySelector('#status').textContent='Audio could not start. Open this file in a browser with audio playback enabled.'}}
const keys=document.querySelector('#keys');names.forEach((n,i)=>{let b=document.createElement('button');b.textContent=n;b.onclick=()=>play(i);keys.appendChild(b)});
document.querySelector('#stop').onclick=stop;document.addEventListener('keydown',e=>{if(e.repeat||['SELECT','INPUT'].includes(e.target.tagName))return;let i='awsedftgyhujk'.indexOf(e.key.toLowerCase());if(i>=0){e.preventDefault();play(i)}});
document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>{stopKeys();document.querySelectorAll('audio').forEach(b=>{if(a!==b)b.pause()})}));window.addEventListener('blur',stop);
</script></html>'''
    page=page.replace('BANK',json.dumps(bank))
    (out/'listen.html').write_text(page)
    print(json.dumps(results,indent=2))

if __name__=='__main__': main(sys.argv[1])
