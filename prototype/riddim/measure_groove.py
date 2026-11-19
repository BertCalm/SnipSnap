"""Measure a drum loop: band energy per 16th step (bar-averaged), kick pitch, brightness, swing.
numpy only (no scipy here). Python harness measurements, not Kotlin."""
import sys, wave, re, numpy as np
def load(p):
    w = wave.open(p); n = w.getnframes(); ch = w.getnchannels(); sw = w.getsampwidth(); sr = w.getframerate()
    raw = np.frombuffer(w.readframes(n), np.uint8).reshape(-1, sw)
    b = np.zeros((raw.shape[0], 4), np.uint8); b[:, 4-sw:] = raw
    x = b.view('<i4').ravel().astype(np.float64) / 2**31
    x = x.reshape(-1, ch); return x.mean(1), x, sr
def band(x, sr, lo, hi):
    X = np.fft.rfft(x); f = np.fft.rfftfreq(len(x), 1/sr); X[(f < lo) | (f >= hi)] = 0; return np.fft.irfft(X, len(x))
def env(y, sr, hop=0.005):
    h = int(sr*hop); m = len(y)//h; return np.sqrt((y[:m*h]**2).reshape(m, h).mean(1)), h
BANDS = [("SUB/KICK", 20, 150), ("BODY/SNARE", 150, 2000), ("TOPS/HATS", 5000, 16000)]
def main():
  for p in sys.argv[1:]:
      mono, st, sr = load(p); name = p.split('/')[-1]; bpm = int(re.search(r'_(\d{2,3})_', name).group(1))
      dur = len(mono)/sr; bars = dur/(4*60/bpm)
      side = st[:, 0]-st[:, 1] if st.shape[1] == 2 else 0*mono
      width = np.sqrt(np.mean(side**2))/max(1e-12, np.sqrt(np.mean(mono**2)))
      S = np.abs(np.fft.rfft(mono)); f = np.fft.rfftfreq(len(mono), 1/sr)
      cen = (S*f).sum()/S.sum()
      sub = f < 150; kp = f[sub][np.argmax(S[sub][f[sub] > 25])] if True else 0
      kpk = f[(f > 25) & (f < 150)][np.argmax(S[(f > 25) & (f < 150)])]
      note = 69+12*np.log2(kpk/440); names = "C C# D D# E F F# G G# A A# B".split()
      peak = np.max(np.abs(st)); rms = np.sqrt(np.mean(mono**2))
      print(f"\n== {name}\n   {bpm} BPM, {dur:.2f}s = {bars:.2f} bars | peak {20*np.log10(peak):.1f} dBFS, RMS {20*np.log10(rms):.1f} dBFS, crest {20*np.log10(peak/rms):.1f} dB | centroid {cen:.0f} Hz | side/mid {width:.2f}")
      print(f"   strongest low partial {kpk:.1f} Hz (~{names[int(round(note))%12]}{int(round(note))//12-1}, {100*(note-round(note)):+.0f} c)")
      step = 60/bpm/4; nsteps = int(round(dur/step)); spb = 16
      tot = [(bn, band(mono, sr, lo, hi)) for bn, lo, hi in BANDS]
      for bn, y in tot:
          e, h = env(y, sr); flux = np.maximum(0, np.diff(e, prepend=e[0]))
          grid = np.zeros(nsteps)
          for k in range(nsteps):
              c = int(k*step*sr/h); w = int(0.035*sr/h); grid[k] = flux[max(0, c-2):c+w].max() if c < len(flux) else 0
          nb = nsteps//spb; g = grid[:nb*spb].reshape(nb, spb).mean(0); g = g/max(g.max(), 1e-12)
          sym = ''.join('X' if v > .66 else 'x' if v > .4 else '.' if v > .2 else ' ' for v in g)
          print(f"   {bn:11s}|{sym[0:4]}|{sym[4:8]}|{sym[8:12]}|{sym[12:16]}|")
      # swing: offbeat 16th onset offset in TOPS band
      e, h = env(tot[2][1], sr, 0.001); flux = np.maximum(0, np.diff(e, prepend=e[0]))
      offs = []
      for k in range(1, nsteps, 2):
          c = int(k*step*sr/h); w = int(step*0.45*sr/h); seg = flux[max(0, c-w):c+w]
          if len(seg) and seg.max() > 0.3*flux.max(): offs.append((np.argmax(seg)-min(w, c))*h/sr*1000)
      if offs: print(f"   off-16th hat timing: median {np.median(offs):+.1f} ms vs straight grid ({len(offs)} hits; + = late/swung)")

if __name__=='__main__': main()
