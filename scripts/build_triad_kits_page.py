#!/usr/bin/env python3
"""Publish the checked MPC 3 kit trial alongside the accepted auditions."""
import hashlib, io, json, pathlib, shutil, subprocess, sys, zipfile
import numpy as np
from scipy.io import wavfile

def read_audio(path):
    if path.suffix=='.flac':
        raw=subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-f','f32le','-acodec','pcm_f32le','-'])
        return np.frombuffer(raw,dtype='<f4'),44100
    rate,x=wavfile.read(path)
    assert x.dtype==np.int32
    return (x.astype(np.float64)/2147483648).astype(np.float32),rate

root = pathlib.Path(sys.argv[1] if len(sys.argv)>1 else 'testkit/triad-kits')
site = pathlib.Path(sys.argv[2] if len(sys.argv)>2 else '/workspace/sites/triad-audition/dist')
# Original native WAVs are delivered in this existing ZIP. Remove only exact,
# unlinked standalone duplicates to keep the combined site below the host's 256 MiB cap.
legacy_archive=site/'triad-native.zip'
if legacy_archive.exists():
    pages=''.join(p.read_text() for p in site.rglob('*.html'))
    with zipfile.ZipFile(legacy_archive) as z:
        for p in site.glob('*-native.wav'):
            assert p.name not in pages and p.read_bytes()==z.read(p.name)
            p.unlink()
dest = site/'kits'
dest.mkdir(exist_ok=True)
roles = ['Thud','Sub','Clap','Open hat','Closed hat','Shaker','Conga','Bongo','Rim','Low conga','High bongo','Clap roll','Snare roll','Shaker riser','Full groove','Percussion break']
profiles = [('techno','Warehouse Techno',128),('deep_house','Submerged House',118),('minimal','Fracture Minimal',126)]
assert (root/'verification.csv').read_text().count('verified')==48
shutil.copy2(root/'verification.csv',dest/'verification.csv')
manifest=[]
grid_measurements=[]
sections=[]
for slug,title,bpm in profiles:
    src=root/slug
    out=dest/slug
    out.mkdir(exist_ok=True)
    shutil.copy2(src/'kit-source.json',out/'kit-source.json')
    saved=json.loads((src/'kit-source.json').read_text())
    trackdata=next((src/'mpc').glob('*_[[]TrackData]'))
    for pad in saved['pads']:
        audio=trackdata/pad['sample']
        x,rate=read_audio(audio)
        assert rate==44100 and x.ndim==1 and np.isfinite(x).all()
        assert hashlib.sha256(audio.read_bytes()).digest()==hashlib.sha256((src/'native'/pad['sample']).read_bytes()).digest()
        edges=pad['chain']['boundaries']+[len(x)]
        assert len(edges)==13
        energies=[]
        for z in range(3):
            hashes=[]; energy=[]
            for t in range(4):
                i=z*4+t; a=x[edges[i]:edges[i+1]]
                assert len(a)>0 and np.max(np.abs(a))<1 and abs(np.mean(a))<.005
                hashes.append(hashlib.sha256(a.tobytes()).hexdigest())
                energy.append(float(np.mean(a.astype('float64')**2)))
                grid_measurements.append(dict(genre=slug,pad=pad['slot'],zone=z,take=t,frames=len(a),peak=float(np.max(np.abs(a))),dc=float(np.mean(a)),sha256=hashes[-1]))
            assert len(set(hashes))==4, (slug,pad['slot'],z,'duplicate takes')
            energies.append(sum(energy)/4)
        assert energies[0]<energies[1]<energies[2], (slug,pad['slot'],'velocity energy',energies)

    for wav in sorted(src.glob('*.wav')):
        x,rate=read_audio(wav)
        assert rate==44100 and x.ndim==1 and np.isfinite(x).all() and np.max(np.abs(x))<1
        flac=out/(wav.stem+'.flac')
        subprocess.run(['ffmpeg','-v','error','-y','-i',str(wav),'-c:a','flac',str(flac)],check=True)
        y,sr=read_audio(flac)
        assert sr==rate and np.array_equal(x,y)
        manifest.append(dict(file=str(flac.relative_to(dest)),sha256=hashlib.sha256(flac.read_bytes()).hexdigest(),frames=len(x),peak=float(np.max(np.abs(x))),dc=float(np.mean(x)),lossless=True))
    # Ordinary independent ZIPs, each below hosting's individual-asset cap.
    # Extract every part together: no split-ZIP software is required.
    files=sorted((src/'mpc').rglob('*'))
    groups=[]; current=[]; size=0
    for f in files:
        if not f.is_file(): continue
        probe=io.BytesIO()
        with zipfile.ZipFile(probe,'w',zipfile.ZIP_DEFLATED) as z: z.write(f,f.relative_to(src/'mpc'))
        compressed=len(probe.getvalue())
        assert compressed<23*1024**2
        if size+compressed>23*1024**2 and current:
            groups.append(current);current=[];size=0
        current.append(f);size+=compressed
    if current:groups.append(current)
    downloads=[]
    for i,group in enumerate(groups):
        name=f'{slug}-mpc3-{i+1}.zip'
        with zipfile.ZipFile(dest/name,'w',zipfile.ZIP_DEFLATED) as z:
            for f in group:z.write(f,pathlib.Path('Triad '+title)/f.relative_to(src/'mpc'))
        assert (dest/name).stat().st_size<25*1024**2
        downloads.append(f'<a download href="{name}?v=2">Part {i+1} of {len(groups)} · {(dest/name).stat().st_size/1024**2:.1f} MB</a>')
    def player(file):return f'<audio controls preload="none" src="{slug}/{file}.flac?v=2"></audio>'
    cards=[]
    for i,name in enumerate(roles,1):
        extra=''
        if i == 13 and (out/'hand-roll-v1.flac').exists():
            extra=f'<details open><summary>Compare the previous hand roll</summary><p>Previous alternating conga/bongo roll; the main player above is the new snare roll.</p>{player("hand-roll-v1")}</details>'
        if i in (7,8): extra=f'<details><summary>Three touch levels × four takes</summary><p>Four soft hits, four medium hits, four hard hits. One strike each second.</p>{player(f"touch-{i}")}</details>'
        cards.append(f'<article><span class="pad">A{i:02}</span><h3>{name}</h3>{player(f"pad-{i}")}{extra}</article>')
    sections.append(f'''<section id="{slug}"><div class="label">{bpm} BPM · 16 pads · 3 velocity zones · 4 takes per zone</div><h2>{title}</h2>
<p class="listen">Start here: a pattern played from the saved kit samples.</p>{player('playback')}
<div class="download"><strong>MPC 3 kit</strong><div>{' '.join(downloads)}</div><p>Download every part and extract them into the same folder. Copy the resulting “Triad {title}” folder to your MPC storage, then load its track. All parts are ordinary ZIP files.</p></div>
<div class="grid">{''.join(cards)}</div></section>''')
files=['synth/src/main/kotlin/com/snipsnap/synth/TriadKits.kt','synth/src/main/kotlin/com/snipsnap/synth/TriadHands.kt','kit/src/main/kotlin/com/snipsnap/kit/KitAssembler.kt','shell/src/test/kotlin/com/snipsnap/shell/TriadKitProof.kt','docs/TRIAD_KITS.md']
source=dest/'source';source.mkdir(exist_ok=True)
for f in files:shutil.copy2(f,source/pathlib.Path(f).name)
(dest/'grid-proof.json').write_text(json.dumps(grid_measurements,indent=2)+'\n')
(dest/'measurements.json').write_text(json.dumps(manifest,indent=2)+'\n')
page='''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>TRIAD · Kit trial</title><style>
*{box-sizing:border-box}body{background:#111714;color:#eeeae0;font:16px/1.55 system-ui,sans-serif;margin:0}main{max-width:1100px;margin:auto;padding:46px 24px}a{color:#d5efa8}h1{font-size:clamp(48px,8vw,88px);line-height:1;letter-spacing:-.04em;margin:20px 0}h2{font-size:34px;line-height:1.2}h3{margin:5px 0 14px;font-size:20px}header>p{max-width:770px;font-size:19px;color:#bac6bb}.label,.pad{color:#a6c785;font:12px ui-monospace,monospace;letter-spacing:.08em;text-transform:uppercase}nav{display:flex;gap:16px;flex-wrap:wrap;margin:26px 0}section{border-top:1px solid #3c4a40;padding:38px 0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(270px,1fr));gap:16px}article,.download{background:#1d2720;border:1px solid #3c4a40;border-radius:12px;padding:18px}.download{margin:24px 0}.download a{display:inline-block;margin:8px 18px 0 0}.download p,details p{font-size:14px;color:#bbc8bd}audio{display:block;width:100%;margin:8px 0 14px}summary{cursor:pointer;color:#d5efa8}footer{border-top:1px solid #3c4a40;color:#bac6bb;padding:24px 0}html{scroll-behavior:smooth}a:focus-visible,summary:focus-visible{outline:2px solid #e5f4b3;outline-offset:4px}</style></head><body><main>
<header><div class="label">SnipSnap · TRIAD · Kit completion trial</div><h1>Three rigs.<br>Now under your fingers.</h1>
<p>Revision 2: A13 now plays an accelerating snare roll. Expand its comparison to hear the previous hand roll. The accepted individual conga and bongo pads are unchanged. Test soft strikes, cycling takes, accelerating rolls and complete shared-bus phrases.</p>
<nav><a href="#techno">Warehouse Techno</a><a href="#deep_house">Submerged House</a><a href="#minimal">Fracture Minimal</a><a href="../">Accepted rig comparison</a></nav>
<p>Pads 1–14 are dry voices and authored builds. Pads 15–16 print the whole ensemble through the rig, preserving how overlapping drums drive its effects. Every pad is finite; the two phrase pads choke each other.</p></header>'''+''.join(sections)+'''
<footer><strong>What to test on the MPC</strong><p>Play conga and bongo softly, then harder. Repeat each level to check the four takes. Trigger the rolls and riser, then switch between the two phrase pads. Save, reload and repeat.</p>
<p>Software previews use saved samples with fixed audition headroom; they are not recordings of MPC playback.</p><p>Automated checks verified all 576 slices after saving and export, playback selection across two complete cycles per velocity zone, and MPC 3 velocity/slice fields. Hardware playback and listening to these new kits remain pending. This download is MPC 3; MPC 2 does not retain the full velocity-and-round-robin grid.</p>
<p>The focused kit tests passed. The full repository regression is separate and its status is recorded on <a href="https://github.com/BertCalm/SnipSnap/pull/1005">the PR</a>. No Android build or browser playback check is claimed.</p>
<p><a href="verification.csv">Export verification</a> · <a href="measurements.json">Audio measurements</a> · <a href="grid-proof.json">576-slice proof</a> · <a href="source/TRIAD_KITS.md">Design and gate notes</a></p></footer></main>
<script>document.addEventListener('play',function(e){if(e.target.tagName==='AUDIO')document.querySelectorAll('audio').forEach(function(a){if(a!==e.target)a.pause()})},true)</script></body></html>'''
(dest/'index.html').write_text(page)
index=site/'index.html'
s=index.read_text()
if 'kits/index.html' not in s:
    s=s.replace('<main>','<main><aside style="border:1px solid #a6c785;padding:16px;margin-bottom:28px"><strong>New: the playable kit trial</strong> · <a href="../kits/index.html">Three genre kits, touch tests, rolls and MPC 3 downloads →</a></aside>',1)
index.write_text(s)
print(json.dumps(dict(players=len(manifest),kits=3,pads=48,verified_slices=576,assets_bytes=sum(f.stat().st_size for f in dest.rglob('*') if f.is_file()))))
