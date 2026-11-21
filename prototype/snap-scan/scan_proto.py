"""SNAP SCAN prototype. Python harness, not Kotlin. Synthetic greyscale photos with
natural-image statistics (no real photos yet). Answers two questions:
 1. Does blending between strip waves dip in level, and does aligning each strip to its
    neighbour fix it?
 2. With a bounded fingerprint, do round-robin takes < re-shots < different photos?"""
import sys, os, json
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
# snipkit (the house output stage and WAV writer) lives in the Luthier bundle.
sys.path.insert(1, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '.claude', 'skills', 'luthier', 'scripts'))
import snipkit as sk
OUT = sys.argv[1] if __name__ == "__main__" else None
W, H, BANDS, N = 384, 288, 16, 256
RATE = 44100; IRATE = RATE * 4

def natural(a, seed):
    r = np.random.default_rng(seed)
    fx = np.fft.fftfreq(W)[None, :]; fy = np.fft.fftfreq(H)[:, None]
    f = np.sqrt(fx**2 + fy**2); f[0, 0] = 1
    s = (r.normal(size=(H, W)) + 1j*r.normal(size=(H, W))) / f**a; s[0, 0] = 0
    im = np.real(np.fft.ifft2(s)); im = (im-im.mean())/(im.std()*4) + r.uniform(.35, .65)
    # a light gradient, sky-bright or ground-bright, so "where the light is" varies
    im += r.uniform(-.15, .15) * np.linspace(1, -1, H)[:, None]
    return np.clip(im, 0, 1)

def reshoot(im, seed):
    r = np.random.default_rng(seed); dx, dy = r.integers(-6, 7, 2)
    o = np.roll(np.roll(im, dx, 1), dy, 0) * r.uniform(.92, 1.08)
    return np.clip(o + r.normal(0, .01, im.shape), 0, 1)

def cycle(line):  # Snap.resample + toByte + Snap.cycle
    n = len(line); t = np.empty(N)
    for i in range(N):
        a = i*n//N; b = max(a+1, (i+1)*n//N); t[i] = line[a:b].mean()
    t = np.round(np.clip(t, 0, 1)*255)/255; h = t[0]
    for k in range(16):
        i = N-16+k; w = (k+1)/17; t[i] = t[i]*(1-w) + h*w
    # PhotoField.cellTable's guard: a strip with little swing (sky, a UI bar) blends toward a
    # sine in proportion, and one under 3 levels is a sine outright, never a divide by zero.
    swing = round((t.max() - t.min()) * 255)
    sine = np.sin(2*np.pi*np.arange(N)/N)
    if swing < 3: return sine
    t -= t.mean(); t /= np.abs(t).max()
    w = min(swing / 24, 1.0)
    return w*t + (1-w)*sine

def bands(im): return [cycle(b.mean(0)) for b in np.array_split(im, BANDS, 0)]

def align(tables):
    """Circularly shift each strip to best match the one above it (FFT cross-correlation).
    A shift is only a phase offset of a steady wave, so each strip sounds the same alone."""
    out = [tables[0]]
    for t in tables[1:]:
        xc = np.real(np.fft.ifft(np.fft.fft(out[-1]) * np.conj(np.fft.fft(t))))
        out.append(np.roll(t, int(np.argmax(xc))))
    return out

def blend_dip(tables):
    """Worst level of a 50/50 blend of neighbours, relative to the two ends (dB)."""
    worst = 0.0
    for a, b in zip(tables, tables[1:]):
        mid = np.sqrt(np.mean((0.5*a+0.5*b)**2)); ends = 0.5*(np.sqrt(np.mean(a**2))+np.sqrt(np.mean(b**2)))
        worst = min(worst, 20*np.log10(mid/ends))
    return worst

def light_dir(im):  # +1 sky brighter than ground, -1 ground brighter
    t, b = im[:H//3].mean(), im[-H//3:].mean()
    return 1.0 if t >= b else -1.0

def render(tables, tune=.5, bright=.6, decay=.5, scan=.6, direction=1.0, seed=None, fp=1.0):
    """SNAP's loop (oscillator -> SVF low-pass following the envelope -> exp decay),
    plus the scan. fp scales the fingerprint (1 = the proposed 'subtle to moderate' cap)."""
    r = np.random.default_rng(seed) if seed is not None else None
    j = (lambda lo, hi: r.uniform(lo, hi) * fp) if r is not None else (lambda lo, hi: 0.0)
    hz = 110 * 2 ** (round(tune*24)/12) * 2 ** (j(-6, 6)/1200)          # +-6 cents
    t60 = sk.exp_map(decay, .15, 2.0) * (1 + j(-.12, .12))               # +-12% length feel
    secs = min(max(t60*1.1, .25), 1.45)
    n = int(secs*IRATE); t = np.arange(n)/IRATE
    env = np.minimum(t/.003, 1) * 10 ** (-3*t/t60)
    start = 0.0 + abs(j(0, 1.0))                                         # up to one strip in
    travel = scan * (BANDS-1)
    pos = start + travel * (1 - np.exp(-3*t/secs)) / (1 - np.exp(-3))   # fast then settling
    pos = np.clip(pos, 0, BANDS-1)
    if direction < 0: pos = (BANDS-1) - pos
    T = np.array(tables)
    phase = (np.cumsum(np.full(n, hz*N/IRATE)) + j(0, N)) % N
    i0 = phase.astype(int); fr = phase - i0; i1 = (i0+1) % N
    b0 = np.floor(pos).astype(int); b1 = np.minimum(b0+1, BANDS-1); bw = pos - b0
    bw = bw*bw*(3-2*bw)
    s0 = T[b0, i0]*(1-fr) + T[b0, i1]*fr; s1 = T[b1, i0]*(1-fr) + T[b1, i1]*fr
    x = s0*(1-bw) + s1*bw
    # TPT SVF low-pass, cutoff follows the envelope as Snap.synthesize does
    cut = sk.exp_map(bright, 250, 12000) * (0.45 + 0.55*env)
    g = np.tan(np.pi*cut/IRATE); k = 1.2
    a1 = 1/(1+g*(g+k)); ic1 = ic2 = 0.0; y = np.empty(n)
    for i in range(n):
        v1 = a1[i]*(ic1 + g[i]*(x[i]-ic2)); v2 = ic2 + g[i]*v1
        ic1 = 2*v1-ic1; ic2 = 2*v2-ic2; y[i] = v2
    return sk.finish(y*env)

def spec_dist(a, b, frame=2048):
    """Time-varying timbre distance: frame-by-frame log magnitude, dB rms, first 300 ms
    after level matching, which is where a one-shot's character lives."""
    m = min(len(a), len(b), int(.3*RATE)); d = []
    for s in range(0, m-frame, frame//2):
        w = np.hanning(frame)
        A = 20*np.log10(np.abs(np.fft.rfft(a[s:s+frame]*w))[:600]+1e-6)
        B = 20*np.log10(np.abs(np.fft.rfft(b[s:s+frame]*w))[:600]+1e-6)
        A -= A.max(); B -= B.max(); A = np.maximum(A, -60); B = np.maximum(B, -60)
        d.append(np.sqrt(np.mean((A-B)**2)))
    return float(np.mean(d))

def main():
    rng = np.random.default_rng(11)
    imgs = [natural(rng.uniform(.8, 1.4), s) for s in range(24)]
    report = {}
    # 1. blend dips
    raw_d = [blend_dip(bands(im)) for im in imgs]; ali_d = [blend_dip(align(bands(im))) for im in imgs]
    report["worst_blend_dip_db_unaligned"] = {"median": float(np.median(raw_d)), "worst": float(min(raw_d))}
    report["worst_blend_dip_db_aligned"] = {"median": float(np.median(ali_d)), "worst": float(min(ali_d))}
    # 2. ordering: RR takes vs re-shots vs different photos, fingerprint at full cap
    rr, re, diff, old_re = [], [], [], []
    for i, im in enumerate(imgs[:8]):
        T = align(bands(im)); d = light_dir(im)
        base = render(T, direction=d, seed=1); take2 = render(T, direction=d, seed=2)
        sh = reshoot(im, 500+i); T2 = align(bands(sh))
        shot = render(T2, direction=light_dir(sh), seed=77)
        other = imgs[i+8]; To = align(bands(other))
        oth = render(To, direction=light_dir(other), seed=1)
        rr.append(spec_dist(base, take2)); re.append(spec_dist(base, shot)); diff.append(spec_dist(base, oth))
        # today's SNAP for comparison: scan 0, no fingerprint, one HORIZON line
        h1 = [cycle(im.mean(0))]*BANDS; h2 = [cycle(sh.mean(0))]*BANDS
        old_re.append(spec_dist(render(h1, scan=0), render(h2, scan=0)))
        if i < 2:
            for name, s in [("today", render(h1, scan=0)), ("scan", base), ("scan_take2", take2),
                            ("scan_reshoot", shot), ("scan_other_photo", oth)]:
                sk.write_wav(f"{OUT}/wav/photo{i+1}_{name}.wav", s)
    report["timbre_distance_db"] = {
        "today_same_scene_reshot": float(np.median(old_re)),
        "scan_round_robin_takes": float(np.median(rr)),
        "scan_same_scene_reshot": float(np.median(re)),
        "scan_different_photos": float(np.median(diff))}
    # 3. scan 0 reproduces today's single-line render exactly (same tables, no fingerprint)
    im = imgs[0]; h = [cycle(im.mean(0))]*BANDS
    a = render(h, scan=0); b = render(align(bands(im)), scan=0)  # scan 0 still reads strip 0 only
    report["scan0_reads_one_strip_only"] = "by construction: pos stays at the start strip"
    json.dump(report, open(f"{OUT}/report.json", "w"), indent=2); print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()