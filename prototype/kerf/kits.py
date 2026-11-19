"""Pick 16 pads per kit from the fitted chops, rebuild the loops from synthetic hits, write an audition page."""
import sys, os, json, base64, io, wave, zipfile
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import chop, resynth as rs
out = sys.argv[1]; R = json.load(open(os.path.join(out, 'fits.json'))); SR = 44100
cache = {}
def src(r):
    if r['path'] not in cache: cache[r['path']] = chop.read(r['path'])[0].mean(1)
    return cache[r['path']][r['a']:r['b']]
def loopmax(lp): return max(q['peak'] for q in R if q['loop'] == lp)
BY = {(r['loop'], r['idx']): r for r in R}
def nb(r):
    """The hits either side of this one in its own loop: what a real chop would leak. The loop wraps, as a loop does."""
    idxs = sorted(i for (l, i) in BY if l == r['loop']); k = idxs.index(r['idx'])
    return BY[(r['loop'], idxs[k-1])]['patch'], BY[(r['loop'], idxs[(k+1) % len(idxs)])]['patch']
# one kit per source family: loops whose file names share the text before the first underscore
FAM = {pre: chr(65+i) for i, pre in enumerate(dict.fromkeys(r['loop'].split('_')[0] for r in R))}
WANT = [('KICK', 4), ('SNARE', 4), ('GHOST', 2), ('HAT', 3), ('HAT_OPEN', 1), ('STACK', 1), ('PERC', 1)]
def feat(r): return np.array([np.log2(r['centroid']+50), 3*r['sub'], 3*r['low'], 3*r['mid'], 3*r['pres'], 3*r['air'], np.log2(r['dur']), 0.5*np.log2(r['t60'])])
def pick(rows, n):
    """Loudest first, then whichever remaining hit is least like the ones already chosen (loops repeat hits; this skips the repeats)."""
    if not rows: return []
    rows = sorted(rows, key=lambda r: -r['peak']); got = [rows[0]]
    while len(got) < min(n, len(rows)):
        d = [min(np.linalg.norm(feat(r)-feat(g)) for g in got) for r in rows]; i = int(np.argmax(d))
        if d[i] < 0.05: break
        got.append(rows[i])
    return got
kits = {}
for pre, kid in FAM.items():
    rows = [r for r in R if r['loop'].split('_')[0] == pre and r['dur'] >= 0.08]; pads = []
    for c, n in WANT: pads += pick([r for r in rows if r['cls'] == c], n)
    for c in ['HAT', 'HAT_OPEN', 'GHOST', 'PERC', 'STACK', 'SNARE', 'KICK']:           # fill any class the pack lacks
        if len(pads) >= 16: break
        extra = pick([r for r in rows if r['cls'] == c], 12); pads += [r for r in extra if r not in pads][:16-len(pads)]
    order = {c: i for i, (c, _) in enumerate(WANT)}; pads = sorted(pads[:16], key=lambda r: order[r['cls']])
    kits[kid] = pads
def wav_bytes(x):
    b = io.BytesIO(); w = wave.open(b, 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes((np.clip(x, -1, 1)*32767).astype('<i2').tobytes()); w.close(); return b.getvalue()
import subprocess
def uri(x):
    # mp3 only so the page stays small enough to open; the WAVs in the zip are the uncompressed truth
    mp3 = subprocess.run(['ffmpeg', '-loglevel', 'error', '-f', 'wav', '-i', 'pipe:0', '-c:a', 'libmp3lame', '-b:a', '192k', '-f', 'mp3', 'pipe:1'], input=wav_bytes(np.pad(x, (0, 2205))), capture_output=True, check=True).stdout
    return 'data:audio/mpeg;base64,'+base64.b64encode(mp3).decode()
# --- kits: WAV folders (synthetic only) + patch JSON (numbers only)
BLEED, SLOP = 1.0, 0.5; stats = []
zf = zipfile.ZipFile(os.path.join(out, 'synthetic-chop-kits.zip'), 'w', zipfile.ZIP_DEFLATED); kit_json = {}; html_kits = []; summary = []
for kid, pads in kits.items():
    cells = []; kit_json[kid] = []
    for i, r in enumerate(pads):
        g = 0.9/max(1.0, loopmax(r['loop'])); x = src(r)*g; y = rs.render(r['patch'])*g
        pv, nx = nb(r); z, info = rs.chopped(r['patch'], pv, nx, bleed=BLEED, slop=SLOP); z = np.clip(z*g, -1, 1)
        name = f"Kit{kid}_{i+1:02d}_{r['cls']}"; zf.writestr(f"Synthetic Chop Kit {kid}/{name}.wav", wav_bytes(z)); zf.writestr(f"clean (no bleed, no slop)/Kit {kid}/{name}.wav", wav_bytes(y))
        kit_json[kid].append(dict(pad=i+1, cls=r['cls'], patch=r['patch'], prev=pv, next=nx, bleed=BLEED, slop=SLOP, chop=info))
        k8 = int(.008*SR); bl = 20*np.log10(np.sqrt((z[:max(8, int(max(info['start_ms'], 0)*SR/1000))]**2).mean()+1e-12)/np.abs(z).max()) if info['start_ms'] > 0.3 else None
        stats.append((info['start_ms'], info['over_ms'], bl))
        st = (f"{info['start_ms']:.1f} ms early" if info['start_ms'] > 0 else f"{-info['start_ms']:.1f} ms late")
        cells.append(f"<div class=pad><b>{i+1:02d} {r['cls'].replace('_',' ')}</b><span>cut {st} · end runs {info['over_ms']:.0f} ms into the next hit</span>"
                     f"<button data-a='{uri(x)}'>your chop</button><button class=syn data-a='{uri(y)}'>clean</button><button class=syn data-a='{uri(z)}'>bled + sloppy</button></div>")
    e = np.array([r['metrics']['band_err_db'] for r in pads]); summary.append((kid, len(pads), float(np.median(e)), float(e.max()), dict((c, sum(r['cls'] == c for r in pads)) for c, _ in WANT)))
    html_kits.append(f"<h3>Kit {kid}</h3><div class=grid>{''.join(cells)}</div>")
zf.writestr('patches.json', json.dumps(kit_json, indent=1)); zf.close()
json.dump(kit_json, open(os.path.join(out, 'kit_patches.json'), 'w'))
# --- loops rebuilt from synthetic hits at the measured onsets
html_loops = []; loop_stats = []
for lp in dict.fromkeys(r['loop'] for r in R):
    rows = [r for r in R if r['loop'] == lp]; m = cache[rows[0]['path']]; n = len(m); y = np.zeros(n)
    for r in rows: y[r['a']:r['b']] += rs.render(r['patch'])[:r['b']-r['a']]
    g = 0.9/max(1.0, np.abs(m).max(), np.abs(y).max()); cm = rs.compare(m, y); loop_stats.append((lp, len(rows), cm))
    k = min(n, int(5.4*SR))
    html_loops.append((lp, f"<div class=pad><b>{len(html_loops)+1:02d} · loop, {len(rows)} hits</b><span>band err {cm['band_err_db']:.1f} dB · envelope match {cm['env_corr']:.2f}</span>"
                           f"<button data-a='{uri(m[:k]*g)}'>original</button><button class=syn data-a='{uri(y[:k]*g)}'>synthetic rebuild</button></div>"))
    chop.write(os.path.join(out, lp+'_SYNTH_REBUILD.wav'), y*g)
# --- controls demo on one kick, one snare, one hat of kit B
demo = []
for c in ['KICK', 'SNARE', 'HAT']:
    r = next(r for r in kits['B'] if r['cls'] == c); g = 0.9/max(1.0, loopmax(r['loop'])); p = r['patch']; btn = []
    pv, nx = nb(r)
    for label, kw in [('clean', dict(bleed=0, slop=0)), ('BLEED low', dict(bleed=.5, slop=0)), ('BLEED mid', dict(bleed=1, slop=0)), ('BLEED high', dict(bleed=2, slop=0)), ('SLOP low', dict(bleed=0, slop=.25)), ('SLOP mid', dict(bleed=0, slop=.5)), ('SLOP high', dict(bleed=0, slop=1)), ('both mid (the kit setting)', dict(bleed=1, slop=.5)), ('both high', dict(bleed=2, slop=1))]:
        btn.append(f"<button class=syn data-a='{uri(np.clip(rs.chopped(p, pv, nx, **kw)[0]*g, -1, 1))}'>{label}</button>")
    demo.append(f"<div class=pad wide><b>{c}</b>{''.join(btn)}</div>")
page = f"""<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>Synthetic chops audition</title>
<style>:root{{--bg:#f6f4ef;--fg:#1c1b19;--mut:#6b675f;--card:#fff;--line:#d9d4c8;--acc:#b4451f}}
@media(prefers-color-scheme:dark){{:root{{--bg:#171614;--fg:#eee9df;--mut:#a09a8e;--card:#22201d;--line:#3a3732;--acc:#f08a5d}}}}
body{{background:var(--bg);color:var(--fg);font:15px/1.45 system-ui,sans-serif;margin:0;padding:16px;max-width:1100px;margin:auto}}
h1{{font-size:22px}}h2{{margin-top:32px;font-size:18px}}h3{{margin:20px 0 8px}}p{{color:var(--mut);max-width:70ch}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:8px}}
.pad{{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:10px}}.pad b{{display:block}}.pad span{{display:block;color:var(--mut);font-size:12px;margin:2px 0 8px}}
button{{font:inherit;padding:8px 10px;margin:2px 4px 2px 0;border-radius:6px;border:1px solid var(--line);background:transparent;color:var(--fg);cursor:pointer}}
button.syn{{border-color:var(--acc);color:var(--acc)}}button.on{{background:var(--acc);color:var(--bg)}}</style>
<h1>Synthetic chops: A/B audition</h1>
<p>Plain buttons play your audio. Orange buttons play a synthetic render built only from fitted numbers (sine oscillators plus shaped noise); no source audio is inside them. Numbers are from a Python harness. Nobody has listened to any of this yet: you are the first.</p>
<h2>1. The four kits</h2><p>Three versions of each pad: your chop, the clean synthetic twin, and the twin with bleed and a sloppy cut. The line under each name says where the knife landed for that pad.</p>{''.join(html_kits)}
<h2>2. BLEED and SLOP on their own</h2><p>Three pads from Kit B. BLEED is how loud the previous hit keeps ringing underneath. SLOP is how far off the two cut points are.</p><div class=grid style="grid-template-columns:1fr">{''.join(demo)}</div>

<h2>3. Whole loops, rebuilt hit by hit</h2><p>Every hit in the loop is replaced by its synthetic twin at the same position. First 5.4 seconds of each.</p><div class=grid>{''.join(h for _, h in html_loops)}</div>
<script>let cur,btn;document.addEventListener('click',e=>{{const b=e.target.closest('button');if(!b)return;if(cur){{cur.pause();btn.classList.remove('on')}}cur=new Audio(b.dataset.a);btn=b;b.classList.add('on');cur.onended=()=>b.classList.remove('on');cur.play()}})</script>"""
open(os.path.join(out, 'synthetic-chops-audition.html'), 'w').write(page)
print('page MB', round(len(page)/1e6, 1))
for s in summary: print('Kit', *s)
st = np.array([(a, b) for a, b, _ in stats]); bl = [c for _, _, c in stats if c is not None]
print('start offset ms p10/50/90', np.percentile(st[:, 0], [10, 50, 90]).round(1), 'late starts', int((st[:, 0] < 0).sum()), 'of', len(st)); print('end overrun ms p10/50/90', np.percentile(st[:, 1], [10, 50, 90]).round(1)); print('pre-roll level re pad peak dB median', np.median(bl).round(1), 'n', len(bl))
for lp, n, cm in loop_stats: print(f"{lp[:40]:40s} hits {n:3d} band_err {cm['band_err_db']:.1f} env_corr {cm['env_corr']:.3f} rms {cm['rms_db']:+.1f} centroid {cm['centroid_syn']/cm['centroid_src']:.2f}x")
