"""Fit a small synthesis patch to each chop, render it, and measure how close it lands.

The model (why each part exists):
  PARTIALS  up to 4 sine oscillators under 500 Hz, each with a pitch glide and an exponential
            decay. This is the drum's body: the kick's falling thump, the snare's shell notes,
            and also the *previous* hit still ringing under this one (it starts at full level).
  NOISE     deterministic noise shaped by 22 frequency bands; each band has a peak level, a
            decay time and a FLOOR. The floor is the part that never dies inside the slice:
            hat wash, room, tape hiss. That floor is most of what makes a chop sound chopped.
  CUT       the render stops where the next hit began, with a 1 ms fade, however loud it still is.
No audio from the source is stored in a patch: only these numbers."""
import sys, os, json
import numpy as np
from scipy import signal
SR = 44100; N = 256; H = 32
FB = np.fft.rfftfreq(N, 1/SR)
# Bands are groups of FFT bins: single bins up to ~1.4 kHz, then widening by about a third of an octave.
# Built from bins so no band is ever empty (an empty band fits as silence and drags its neighbours down).
_e = [1]
while _e[-1] < len(FB)-1: _e.append(min(len(FB)-1, max(_e[-1]+1, int(round(_e[-1]*1.26)))))
BINS = [(a, b) for a, b in zip(_e[:-1], _e[1:])]; BINS[-1] = (BINS[-1][0], len(FB)); NB = len(BINS)
EDGES = np.array([FB[a]-FB[1]/2 for a, _ in BINS]+[SR/2])

def _hp(x, f): return signal.sosfilt(signal.butter(2, f, 'high', fs=SR, output='sos'), x)

def lcg_noise(n, seed):
    # same recurrence shape as the house Dsp.Noise (an LCG); a seed reproduces bit for bit
    s = np.uint64(seed); out = np.empty(n); a = np.uint64(6364136223846793005); c = np.uint64(1442695040888963407)
    with np.errstate(over='ignore'):
        for i in range(n):
            s = s*a + c; out[i] = float(s >> np.uint64(11))/float(1 << 53)*2-1
    return out

def stft(x):
    return signal.stft(x, SR, nperseg=N, noverlap=N-H, boundary='zeros', padded=True)[2]

def band_power(Z):
    P = np.abs(Z)**2
    return np.stack([P[a:b].sum(0) for a, b in BINS])

def fit_partial(x, f0, wide, narrow=False):
    lo, hi = (0.62*f0, 1.9*f0) if wide else (0.78*f0, 1.28*f0)
    if narrow: lo, hi = 0.975*f0, 1.025*f0
    pad = 2048; xp = np.pad(x, pad)
    sos = signal.butter(2, [max(15, lo), min(hi, 20000)], btype='band', fs=SR, output='sos')
    z = signal.hilbert(signal.sosfiltfilt(sos, xp))[pad:-pad]
    a = np.abs(z); n = len(a); t = np.arange(n)/SR
    sm = max(8, int(0.003*SR)); a_s = np.convolve(a, np.ones(sm)/sm, 'same')
    ip = int(np.argmax(a_s[:max(1, n-sm)])); A = float(a_s[ip])
    if A < 1e-5: return None
    ok = (t > t[ip]) & (a_s > A*0.03) & (np.arange(n) < n-sm)
    if ok.sum() > 40:
        slope = np.polyfit(t[ok], 20*np.log10(a_s[ok]), 1)[0]; t60 = float(np.clip(-60/min(slope, -1e-3), 0.02, 6.0))
    else: t60 = 0.05
    ph = np.unwrap(np.angle(z)); f = np.diff(ph)*SR/(2*np.pi); f = np.append(f, f[-1])
    w = (a_s > 0.1*A).astype(float)*a_s; w[:16] = 0; w[-sm:] = 0
    best = None
    for tau in [None]+([] if narrow else list(np.geomspace(0.004, 0.25, 14))):
        if tau is None: M = np.ones((n, 1))
        else: M = np.stack([np.ones(n), np.exp(-t/tau)], 1)
        c = np.linalg.lstsq(M*w[:, None], f*w, rcond=None)[0]
        err = float((w*(M@c-f)**2).sum()) * (1.0 if tau is None else 1.05)   # small penalty: only glide when it pays
        if best is None or err < best[0]: best = (err, tau, c)
    _, tau, c = best
    fe = float(np.clip(c[0], 20, 1200 if not narrow else 20000)); d = float(c[1]) if tau is not None else 0.0
    d = float(np.clip(d, -0.5*fe, 6*fe)); tau = float(tau or 0.02)
    fm = fe + d*np.exp(-t/tau); phs = 2*np.pi*np.cumsum(fm)/SR
    phi0 = float(np.angle(z[ip]) - phs[ip])
    # where this oscillator really begins: the last moment before its peak that it was still under a tenth of it.
    # A tone that enters late (a second drum in the slice) must not ramp up from the start of the slice.
    below = np.nonzero(a_s[:ip] < 0.1*A)[0]; start = float(t[below[-1]]) if len(below) else 0.0
    return dict(hz_end=fe, glide_hz=d, glide_s=tau, amp=A, attack_s=float(t[ip]), start_s=start, t60=t60, phase=phi0)

def render_partial(p, n):
    t = np.arange(n)/SR
    fm = p['hz_end'] + p['glide_hz']*np.exp(-t/p['glide_s']); ph = 2*np.pi*np.cumsum(fm)/SR + p['phase']
    atk = p['attack_s']; st = min(p.get('start_s', 0.0), atk)
    if atk - st > 0.0015 or st > 0: env = np.where(t < atk, np.clip((t-st)/max(atk-st, 5e-4), 0, 1), 10**(-3*(t-atk)/p['t60']))
    else: env = 10**(-3*np.maximum(t-atk, 0)/p['t60'])
    return p['amp']*env*np.cos(ph)

T60S = np.geomspace(0.008, 4.0, 16)
from scipy.optimize import nnls

def find_events(Ex, tfr):
    """Later hits hiding inside one slice (a hat landing on a kick's tail, a flam, a ghost note the chopper missed).
    A hit is a moment where several bands jump up together; the first hit of the slice is not listed."""
    ref = Ex.max(); k = np.ones(5)/5
    L = 10*np.log10(np.stack([np.convolve(r, k, 'same') for r in Ex]) + ref*1e-5)
    lag = 6; rise = np.maximum(L[:, lag:]-L[:, :-lag], 0); rise[rise < 4] = 0
    d = np.concatenate([np.zeros(lag), rise.sum(0)]); out = []
    for t in range(1, len(d)-1):
        if tfr[t] < 0.02 or d[t] < 30 or d[t] < d[t-1] or d[t] <= d[t+1]: continue
        if out and tfr[t]-out[-1] < 0.025: continue
        out.append(float(tfr[t] - lag*H/SR*0.5))
    return out

def fit_band(e, tfr, events=()):
    """One band of the noise, as a sum of LAYERS. Each layer is a hit: a fast crack plus a slower ring,
    starting at its own time. Under them sits one floor that never dies inside the slice.
    Without layers a band has a single peak and can only fall afterwards, so a second sound in the
    same slice (the hat on the kick's tail) had nowhere to go and vanished."""
    lim = len(e) if not events else max(2, int(np.searchsorted(tfr, events[0])))
    ip = int(np.argmax(e[:lim])); pk = float(e.max())
    empty = dict(snap=0.0, snap_t60=0.01, level=0.0, t60=0.05, floor=0.0, attack_s=0.0, layers=[])
    if pk < 1e-13: return empty
    y = e[ip:]; tt = tfr[ip:]-tfr[ip]; wgt = 1/(y+pk*0.01); best = None
    starts = [0.0]
    for ev in events:   # each later layer starts where this band actually peaks just after the event
        a = int(np.searchsorted(tfr, ev)); b = min(len(e), a+int(0.012*SR/H)+1)
        if a >= len(e)-1 or a <= ip: continue
        starts.append(float(tfr[a+int(np.argmax(e[a:b]))]-tfr[ip]))
    def col(t60, st): return np.where(tt >= st, 10**(-6*np.maximum(tt-st, 0)/t60), 0.0)
    for i in range(len(T60S)):
        for j in range(i, len(T60S)):
            cols = [col(T60S[i], st) for st in starts] + ([col(T60S[j], st) for st in starts] if j > i else []) + [np.ones_like(tt)]
            M = np.stack(cols, 1); c, _ = nnls(M*wgt[:, None], y*wgt)
            err = float((((M@c-y)*wgt)**2).sum())*(1.0 if j == i else 0.97)
            if best is None or err < best[0]: best = (err, i, j, c)
    _, i, j, c = best; K = len(starts)
    fast = c[:K]; slow = c[K:2*K] if j > i else np.zeros(K)
    if j == i: fast, slow = slow, fast   # a single decay is the ring, not the crack
    out = dict(snap=float(fast[0]), snap_t60=float(T60S[i]), level=float(slow[0]), t60=float(T60S[j]), floor=float(c[-1]), attack_s=float(tfr[ip]), layers=[])
    for k in range(1, K):
        if fast[k]+slow[k] > 0: out['layers'].append(dict(at_s=float(tfr[ip]+starts[k]), snap=float(fast[k]), level=float(slow[k])))
    return out

def band_env(b, tfr):
    tt = tfr - b['attack_s']; tp = np.maximum(tt, 0)
    dec = b['snap']*10**(-6*tp/b['snap_t60']) + b['level']*10**(-6*tp/b['t60']) + b['floor']
    if b['attack_s'] > 1e-4:
        ramp = np.clip(tfr/b['attack_s'], 0, 1)**2
        dec = np.where(tt < 0, (b['snap']+b['level']+b['floor'])*ramp, dec)
    for ly in b.get('layers', []):
        tl = tfr - ly['at_s']; on = np.clip(1 + tl/0.0015, 0, 1)**2        # 1.5 ms rise into each later layer
        dec = dec + on*(ly['snap']*10**(-6*np.maximum(tl, 0)/b['snap_t60']) + ly['level']*10**(-6*np.maximum(tl, 0)/b['t60']))
    return dec

def fit(x, seed, layered=True):
    n = len(x)
    # body partials: greedy, strongest first, subtracting each before looking for the next
    res = x.copy(); partials = []; nfft = 1 << 17
    f = np.fft.rfftfreq(nfft, 1/SR); full = np.abs(np.fft.rfft(x*np.hanning(n), nfft)); ref = full.max()
    tried = []
    maxp, top = (7, 1500) if layered else (4, 500)
    for k in range(maxp+3):
        if len(partials) == maxp: break
        S = np.abs(np.fft.rfft(res*np.hanning(n), nfft)); S[(f < 30) | (f > top)] = 0
        for p in partials+tried: S[(f > p['hz_end']/1.22) & (f < p['hz_end']*1.22)] = 0
        i = int(np.argmax(S))
        if S[i] < ref*10**(-26/20): break
        p = fit_partial(res, float(f[i]), wide=(k == 0 and f[i] < 140))
        if p is None or p['amp'] < 0.004: break
        tried.append(dict(p))
        # Keep only the part of this oscillator that really lines up with the signal (a projection).
        # A band-passed patch of noise also looks like a sine to the fitter; its projection is small, so it is dropped
        # and left for the noise bands, instead of being counted twice.
        y = render_partial(p, n); g = float(res @ y)/float(y @ y + 1e-20)
        if g < 0.35: continue
        p['amp'] *= min(g, 1.2); partials.append(p); res = res - render_partial(p, n)
    # METAL: a cymbal's ring is a cluster of thin, steady tones between 2.5 and 15 kHz. Rendered as noise they turn
    # into hiss, and hiss under a kick is masked; thin tones are not. Take the spectral lines that stand clear of
    # their surroundings and give each its own oscillator (same projection test as the body, so noise is refused).
    metal = []
    if layered:
        nf = 1 << 16; fm = np.fft.rfftfreq(nf, 1/SR); S = np.abs(np.fft.rfft(res[:nf]*np.hanning(min(n, nf)), nf))
        sel = (fm > 2500) & (fm < 15000); w = int(600/(fm[1]-fm[0])) | 1
        med = signal.medfilt(S[::8], (w//8) | 1); med = np.interp(np.arange(len(S)), np.arange(len(S))[::8], med) + 1e-12
        pk = np.nonzero(sel[1:-1] & (S[1:-1] > S[:-2]) & (S[1:-1] >= S[2:]) & (S[1:-1] > 2.8*med[1:-1]))[0]+1
        for i in pk[np.argsort(-S[pk])]:
            if len(metal) >= 16: break
            if any(abs(np.log(fm[i]/q['hz_end'])) < 0.03 for q in metal): continue
            p = fit_partial(res, float(fm[i]), wide=False, narrow=True)
            if p is None or p['amp'] < 1e-4: continue
            y = render_partial(p, n); g = float(res @ y)/float(y @ y + 1e-20)
            if g < 0.35: continue
            p['amp'] *= min(g, 1.2); metal.append(p); res = res - render_partial(p, n)
    partials = partials + metal
    body = sum((render_partial(p, n) for p in partials), np.zeros(n))
    Zx = stft(x); Zb = stft(body)
    tfr = np.arange(Zx.shape[1])*H/SR; nfr = int(np.ceil(n/H))
    Ex = band_power(Zx)[:, :nfr]; Eb = band_power(Zb)[:, :nfr]
    # What the noise must supply is "source minus body", band by band. Subtract smoothed band energies
    # (not bin by bin: clamping thousands of small negatives to zero leaves a +4 dB low-mid bias),
    # then rescale each band so body + noise carries the source's total energy in that band.
    k = np.ones(9)/9; sm = lambda E: np.stack([np.convolve(r, k, 'same') for r in E])
    E = np.where(Eb.sum(1, keepdims=True) > 0.02*Ex.sum(1, keepdims=True), np.maximum(sm(Ex)-sm(Eb), 0), Ex)
    events = find_events(Ex, tfr[:nfr]) if layered else []
    bands = [fit_band(E[b], tfr[:nfr], events) for b in range(NB)]
    for b, bd in enumerate(bands):
        got = float(band_env(bd, tfr[:nfr]).sum()); want = max(float(Ex[b].sum()-Eb[b].sum()), 0.0)
        g = want/got if got > 0 else 0.0
        for key in ('snap', 'level', 'floor'): bd[key] *= g
        for ly in bd['layers']: ly['snap'] *= g; ly['level'] *= g
    patch = dict(seconds=n/SR, seed=int(seed), partials=partials, bands=bands, events=events, ticks=[], colour=None)
    return refine(x, patch) if layered else patch

NFINE = 2048
def fine_spectrum(x):
    """Long-window power spectrum (21 Hz steps, averaged over the slice) and the same thing blurred to a third of an octave.
    Their ratio is the fine grain of the tone: 1 everywhere for plain noise, spikes where something rings."""
    x = np.pad(x, (0, max(0, NFINE*2-len(x))))
    f, P = signal.welch(x, SR, nperseg=NFINE, noverlap=NFINE*3//4)
    cs = np.concatenate([[0], np.cumsum(P)]); k = np.arange(len(P))
    lo = np.maximum((k/1.12).astype(int), 0); hi = np.minimum((k*1.12).astype(int)+1, len(P))
    return f, P, (cs[hi]-cs[lo])/(hi-lo) + 1e-20

def colour_curve(col, freqs):
    """The resonator bank as a gain curve: a base level plus one bell per resonance (centre, height, width)."""
    c = np.full(len(freqs), col['base'])
    for hz, g, bw in col['res']: c += g/(1+((freqs-hz)/(bw/2))**2)
    return c

def refine(x, patch):
    """Two things the windowed noise cannot do by itself, both measured against the source and stored as numbers.

    COLOUR  A cymbal does not ring as steady sine tones (those were tried: the fitter refused them, because each
            tone's phase wanders). It rings as noise squeezed through many very narrow resonances. The coarse bands
            are a third of an octave wide and blur that into hiss. So: find the narrow spikes in the source's fine
            spectrum and store them as a bank of resonances that the noise is passed through before it is shaped.
    TICK    The stick landing: about 1 ms, far shorter than the 6 ms windows the noise is shaped in."""
    n = len(x); body = sum((render_partial(p, n) for p in patch['partials']), np.zeros(n)); res = x - body
    f, P, C = fine_spectrum(res); D = P/C; sel = (f > 1500) & (f < 16000)
    base = float(np.clip(np.median(D[sel]), 0.3, 1.0)); cand = []
    for i in np.nonzero(sel[1:-1] & (D[1:-1] > D[:-2]) & (D[1:-1] >= D[2:]) & (D[1:-1] > 2.2))[0]+1:
        half = base + (D[i]-base)/2; l = i; r = i
        while l > 1 and D[l-1] > half: l -= 1
        while r < len(D)-2 and D[r+1] > half: r += 1
        cand.append((float(D[i]-base)*float(P[i]), float(f[i]), float(D[i]-base), float(max(r-l+1, 1.5)*(f[1]-f[0]))))
    cand.sort(reverse=True)
    patch['colour'] = dict(base=base, res=[[hz, g, bw] for _, hz, g, bw in cand[:48]])
    patch['ticks'] = []
    y = render(patch, edge=False); hx, hy = _hp(x, 2000), _hp(y, 2000); ax = np.abs(hx); top = ax.max(); w = int(0.003*SR); gap = int(0.015*SR)
    order = np.argsort(-ax); taken = []
    for i in order[:4000]:
        if ax[i] < 0.25*top or len(taken) >= 6: break
        if any(abs(i-j) < gap for j in taken): continue
        taken.append(int(i)); yp = np.abs(hy[max(0, i-w):i+w]).max()
        if ax[i] < 1.25*yp: continue
        seg = hx[i:i+64]; zc = max(1, int((np.diff(np.sign(seg)) != 0).sum())); hz = float(np.clip(zc*SR/(2*max(len(seg), 2)), 1500, 12000))
        patch['ticks'].append(dict(at_s=i/SR, amp=float(np.sqrt(ax[i]**2-yp**2)), hz=hz, want=float(ax[i])))
    for _ in range(2):     # check the result: a tick may not push the top end past the source's own spike
        hy = _hp(render(patch, edge=False), 2000)
        for tk in patch['ticks']:
            i = int(tk['at_s']*SR); got = np.abs(hy[max(0, i-w):i+w]).max()
            if got > tk['want']: tk['amp'] *= max(0.0, 1-(got-tk['want'])/max(tk['amp'], 1e-9))
    return patch

def render(patch, cut=1.0, floor=1.0, decay=1.0, tune=0.0, extend=0.0, edge=True, layers=True, colour=True):
    """cut/floor/decay/tune are the engine-style controls; all 1.0/0.0 reproduces the fit."""
    n = int(round((patch['seconds']*cut+extend)*SR)); r = 2**(tune/12)
    body = np.zeros(n)
    for p in patch['partials']:
        q = dict(p, hz_end=p['hz_end']*r, glide_hz=p['glide_hz']*r, t60=p['t60']*decay); body += render_partial(q, n)
    nz = lcg_noise(n+N, patch['seed']); npow0 = float((np.abs(stft(nz))**2).mean())
    col = patch.get('colour')
    if col and col['res'] and colour:
        F = np.fft.rfft(nz); ff = np.fft.rfftfreq(len(nz), 1/SR); c = colour_curve(col, ff/r)
        for a, b in BINS:                                    # keep every coarse band's energy where the band fit put it
            m = (ff >= FB[a]-FB[1]/2) & ((ff < FB[b]-FB[1]/2) if b < len(FB) else True)
            if m.any(): c[m] /= c[m].mean()
        nz = np.fft.irfft(F*np.sqrt(c), len(nz))
    Zn = stft(nz); tfr = np.arange(Zn.shape[1])*H/SR
    npow = npow0
    centres = np.array([np.sqrt(FB[a]*FB[b-1]) for a, b in BINS]); G = np.zeros((len(FB), Zn.shape[1]))
    dens = []
    for b, bd in enumerate(patch['bands']):
        nb = BINS[b][1]-BINS[b][0]
        bd2 = dict(bd, t60=bd['t60']*decay, snap_t60=bd['snap_t60']*min(1.0, decay), floor=bd['floor']*floor, layers=[ly for ly in bd.get('layers', []) if layers])
        dens.append(band_env(bd2, tfr)/nb)
    dens = np.array(dens)  # power per bin, per band, per frame
    logc = np.log(centres*r); lf = np.log(np.maximum(FB, 1))
    for j in range(Zn.shape[1]):
        G[:, j] = np.exp(np.interp(lf, logc, 0.5*np.log(dens[:, j]+1e-30), left=-40))
    G[0] = 0
    y = signal.istft(Zn*G/np.sqrt(npow), SR, nperseg=N, noverlap=N-H, boundary=True)[1][:n]
    out = body + y
    for k, tk in enumerate(patch.get('ticks', [])):
        i = int(tk['at_s']*SR); m = min(96, n-i)
        if m < 8: continue
        t = np.arange(m)/SR; f = tk['hz']*r
        # two cycles of a damped tone at the tick's own brightness, signed by the seed: sharp, short, and not a sample of anything
        burst = np.sin(2*np.pi*f*t)*np.exp(-t/0.0006) + 0.5*lcg_noise(m, patch['seed']+17+k)*np.exp(-t/0.0004)
        out[i:i+m] += tk['amp']*burst/np.abs(burst).max()
    fade = int(0.001*SR)
    if edge: out[-fade:] *= np.linspace(1, 0, fade)   # the chop's edge
    return out

def compare(x, y):
    n = min(len(x), len(y)); x = x[:n]; y = y[:n]
    Ex = band_power(stft(x)); Ey = band_power(stft(y))
    # also a low band the 22 bands do not cover
    lo = lambda s: (np.abs(stft(s)[:1])**2).sum(0)[None]
    Ex = np.vstack([lo(x), Ex]); Ey = np.vstack([lo(y), Ey])
    k = np.ones(9)/9; sm = lambda E: np.stack([np.convolve(r, k, 'same') for r in E])   # ~6 ms smoothing: noise is random, compare envelopes
    Ex, Ey = sm(Ex), sm(Ey); ref = Ex.max()
    dx = 10*np.log10(Ex/ref+1e-12); dy = 10*np.log10(Ey/ref+1e-12); m = (dx > -40) | (dy > -40)
    lsd = float(np.abs(np.clip(dx, -50, 0)-np.clip(dy, -50, 0))[m].mean())
    w = int(0.005*SR); ex = np.sqrt(np.convolve(x**2, np.ones(w)/w, 'same')); ey = np.sqrt(np.convolve(y**2, np.ones(w)/w, 'same'))
    cen = lambda s: float((np.fft.rfftfreq(len(s), 1/SR)*np.abs(np.fft.rfft(s))**2).sum()/((np.abs(np.fft.rfft(s))**2).sum()+1e-20))
    return dict(band_err_db=lsd, env_corr=float(np.corrcoef(ex, ey)[0, 1]), rms_db=float(20*np.log10((np.sqrt((y**2).mean())+1e-9)/(np.sqrt((x**2).mean())+1e-9))),
                centroid_src=cen(x), centroid_syn=cen(y), peak_db=float(20*np.log10((np.abs(y).max()+1e-9)/(np.abs(x).max()+1e-9))))



def slop_draw(seed):
    """The three numbers that decide where a pad's knife lands. Stored beside each pad in the kit file, so the
    Kotlin renderer (which has a different random generator) chops every heard pad the same way."""
    return [float(u) for u in np.random.default_rng(seed & 0xFFFFFFFF).random(3)]

def chopped(patch, prev=None, nxt=None, bleed=0.5, slop=0.5, draw=None, **kw):
    """A hit as a hand-made chop: the neighbours leak in, and the two cut points are a little off.

    BLEED  the previous hit keeps ringing under this one. It is that hit's own patch, rendered past
           the point where it was cut, and it dies away (half a second to fall 60 dB) the way a tail does.
    SLOP   where the knife landed, drawn once per pad from the pad's seed, so a pad always chops the same way:
             start early -> a few ms of the previous hit's tail sit in front of the attack
             start late  -> the first couple of ms of the attack are shaved off (it starts with a tick)
             end late    -> the slice catches the front of the NEXT hit before it stops (the little flam)
           and the end fade shrinks from 1 ms towards nothing, so the cut can click.
    bleed=0, slop=0 gives exactly render(patch)."""
    u_start, u_end, u_which = draw if draw is not None else slop_draw(patch['seed'])
    start_ms = slop*(-3.0 + 15.0*u_start) if u_which > 0.25 else -slop*3.0*u_start   # mostly early (pre-roll), sometimes late (shaved)
    over_ms = slop*36.0*u_end**1.5 if nxt is not None else 0.0                        # most ends are near clean, a few run well into the next hit
    k = int(over_ms*SR/1000)
    hit = render(patch, edge=False, extend=k/SR, **kw)   # this hit keeps ringing under the next one's attack
    if k > 0 and nxt['seconds']*SR >= k: hit[-k:] += render(nxt, edge=False)[:k]
    pre = int(max(start_ms, 0)*SR/1000) if prev is not None else 0
    shave = int(max(-start_ms, 0)*SR/1000)
    out = np.concatenate([np.zeros(pre), hit[shave:]])
    if prev is not None and (bleed > 0 or pre > 0):
        np_ = int(round(prev['seconds']*SR)); need = len(out)
        tail = render(prev, extend=need/SR+0.01, edge=False)[np_-pre:np_-pre+need]
        t = (np.arange(need)-pre)/SR
        g = np.where(t < 0, 1.0, bleed*10**(-3*np.maximum(t, 0)/0.5))   # the pre-roll is the real tail at full level; under the hit it is BLEED
        out[:len(tail)] += tail*g[:len(tail)]
    fade = max(2, int((1.0-0.9*slop)*0.001*SR)); out[-fade:] *= np.linspace(1, 0, fade)
    return out, dict(start_ms=float(start_ms), over_ms=float(over_ms), fade_ms=fade*1000/SR)
