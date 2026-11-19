"""Chop drum loops into hits and measure each hit.
Why each step: onsets come from spectral flux (a jump in energy across frequency bands is a hit);
each slice runs onset -> next onset, which is exactly what an MPC chop does and is why a chop
carries the *tail of this hit plus whatever else was ringing*, cut off hard."""
import sys, os, glob, json, wave, hashlib
import numpy as np
from scipy import signal

def read(path):
    w = wave.open(path); n = w.getnframes(); raw = w.readframes(n); ch = w.getnchannels(); sw = w.getsampwidth()
    a = np.frombuffer(raw, dtype=np.uint8).reshape(-1, sw)
    v = (a[:,0].astype(np.int32) | (a[:,1].astype(np.int32)<<8) | (a[:,2].astype(np.int8).astype(np.int32)<<16)) if sw==3 else np.frombuffer(raw,dtype='<i2').astype(np.int32)<<8
    x = v.reshape(-1, ch) / 8388608.0
    return x, w.getframerate()

def write(path, x, sr=44100):
    w = wave.open(path,'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
    w.writeframes((np.clip(x,-1,1)*32767).astype('<i2').tobytes()); w.close()

def onsets(m, sr):
    N, H = 1024, 128
    f, t, Z = signal.stft(m, sr, nperseg=N, noverlap=N-H, boundary=None, padded=False)
    mag = np.log1p(200*np.abs(Z))
    flux = np.maximum(0, np.diff(mag, axis=1)).sum(0)
    flux = np.concatenate([[flux[0]], flux]); flux /= flux.max()+1e-12
    med = signal.medfilt(flux, 31)
    out = []; last = -1e9
    for i in range(1, len(flux)-1):
        if flux[i] > med[i]*1.6+0.06 and flux[i] >= flux[i-1] and flux[i] > flux[i+1] and (i-last)*H/sr > 0.07:
            out.append(i); last = i
    # refine to the sample where the envelope first rises (back off from the flux peak)
    res = []
    for i in out:
        c = i*H + N//2; a = max(0, c-1200); seg = np.abs(m[a:c+200])
        thr = 0.15*seg.max(); k = np.argmax(seg > thr); res.append(max(0, a+k-32))
    return sorted(set(res))

def bands(x, sr):
    X = np.abs(np.fft.rfft(x*np.hanning(len(x))))**2; f = np.fft.rfftfreq(len(x), 1/sr)
    tot = X.sum()+1e-20
    b = lambda lo,hi: float(X[(f>=lo)&(f<hi)].sum()/tot)
    cent = float((f*X).sum()/tot)
    return dict(sub=b(20,120), low=b(120,300), mid=b(300,2000), pres=b(2000,6000), air=b(6000,22050), centroid=cent)

def classify(d):
    lo = d['sub']+d['low']; rest = d['mid']+d['pres']+d['air']
    if lo > 0.6: return 'KICK' if rest < 0.25 else 'STACK'
    if d['centroid'] > 2800 and d['pres']+d['air'] > 0.5 and d['rel'] < 0.6:
        return 'HAT_OPEN' if (d['t60'] > 0.5 and d['dur'] > 0.2) else 'HAT'
    if d['rel'] > 0.5: return 'STACK' if lo > 0.35 else 'SNARE'
    return 'GHOST' if d['mid']+d['low'] > 0.4 else 'PERC'

def main(outdir, files):
    rows = []
    for path in files:
        x, sr = read(path); m = x.mean(1)
        name = os.path.basename(path)[9:-4]
        side = (x[:,0]-x[:,1])/2
        width = float(np.sqrt((side**2).mean())/(np.sqrt((m**2).mean())+1e-12))
        ons = onsets(m, sr); ons.append(len(m))
        d0 = os.path.join(outdir, name); os.makedirs(d0, exist_ok=True); loc = []
        for k in range(len(ons)-1):
            a, b = ons[k], ons[k+1]; s = m[a:b]
            if len(s) < 0.04*sr: continue
            env = np.abs(signal.hilbert(s)); pk = np.abs(s).max()
            if pk < 0.02: continue
            head = s[:min(len(s), int(0.12*sr))]
            d = bands(np.pad(head,(0,max(0,2048-len(head)))), sr)
            # decay: time for the smoothed envelope to fall 60 dB, extrapolated from a line fit over the slice
            e = signal.lfilter([0.005],[1,-0.995], env); ip = int(np.argmax(e)); tail = e[ip:]
            tt = np.arange(len(tail))/sr; db = 20*np.log10(tail/tail[0]+1e-9); ok = db>-30
            slope = np.polyfit(tt[ok], db[ok], 1)[0] if ok.sum()>50 else -600
            d['t60'] = float(min(5.0, -60/min(slope,-1e-3)))
            # how loud the slice still is where the next hit cuts it: the "chop" signature
            endr = float(np.sqrt((s[-int(0.01*sr):]**2).mean())); d['cut_db'] = float(20*np.log10(endr/(pk+1e-12)+1e-9))
            # pre-attack floor: what was ringing before this hit (bleed of the previous one)
            pre = m[max(0,a-int(0.008*sr)):a]; d['bleed_db'] = float(20*np.log10(np.sqrt((pre**2).mean()+1e-20)/(pk+1e-12))) if len(pre) else -120.
            d['crest_db'] = float(20*np.log10(pk/(np.sqrt((s**2).mean())+1e-12)))
            d.update(loop=name, idx=k, start=a/sr, dur=len(s)/sr, peak=float(pk), cls='', width=width, a=int(a), b=int(b), path=path)
            loc.append(d)
        mx = max(r['peak'] for r in loc)
        for d in loc:
            d['rel'] = d['peak']/mx; d['cls'] = classify(d)
            fn = f"{d['idx']:02d}_{d['cls']}.wav"; d['file'] = os.path.join(name, fn)
            write(os.path.join(d0, fn), m[d['a']:d['b']]/max(1.0, mx), sr); rows.append(d)
    json.dump(rows, open(os.path.join(outdir,'chops.json'),'w'), indent=1)
    import collections
    for lp in dict.fromkeys(r['loop'] for r in rows):
        rs = [r for r in rows if r['loop']==lp]
        print(f"{lp[:44]:44s} n={len(rs):3d} width={rs[0]['width']:.2f}", dict(collections.Counter(r['cls'] for r in rs)))
if __name__ == "__main__": main(sys.argv[1], sys.argv[2:])
