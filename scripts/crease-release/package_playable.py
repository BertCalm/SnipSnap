#!/usr/bin/env python3
"""Verify delivered reference PCM and package ordinary WAV performances for review."""
import json
from pathlib import Path
import subprocess
import wave
import zipfile
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import playable as b

out=b.ROOT/'testkit/crease-playable-release'
m=json.loads((out/'manifest.json').read_text())
entries={}
for clip in m['clips']:
    wav=out/clip['wav'];flac=out/clip['audio']
    b.p.require(b.sha(wav.read_bytes())==clip['wavSha256'],'WAV hash '+clip['id'])
    b.p.require(b.sha(flac.read_bytes())==clip['flacSha256'],'FLAC hash '+clip['id'])
    with wave.open(str(wav),'rb') as f:
        b.p.require((f.getnchannels(),f.getsampwidth(),f.getframerate(),f.getnframes())==(1,2,44100,clip['frames']),'WAV shape')
        pcm=f.readframes(f.getnframes())
    decoded=subprocess.check_output(['ffmpeg','-v','error','-nostdin','-i',str(flac),'-f','s16le','-acodec','pcm_s16le','-'])
    b.p.require(pcm==decoded and b.sha(pcm)==clip['pcmSha256'],'PCM round trip')
    b.p.require(np.frombuffer(pcm,dtype='<i2')[-5292:].max()==0 and np.frombuffer(pcm,dtype='<i2')[-5292:].min()==0,'silent ending')
    entries['WAV/'+wav.name]=wav.read_bytes()

def audio(mode):
    clip=next(c for c in m['clips'] if c['holdSeconds']==.137 and c['mode']==mode)
    with wave.open(str(out/clip['wav']),'rb') as f: x=np.frombuffer(f.readframes(f.getnframes()),dtype='<i2').astype(np.float64)/32767
    keyup=round(clip['keyUpSeconds']*44100)
    return x[keyup:keyup+round(.03*44100)]
exact=audio('exact');cached=audio('cached');clock=np.arange(len(exact))*1000/44100
fig,axes=plt.subplots(2,1,figsize=(9,5.6),sharex=True,constrained_layout=True)
axes[0].plot(clock,exact,color='#20231e',lw=1.2,label='Exact unfold')
axes[0].plot(clock,cached,color='#ef5a2a',lw=.9,alpha=.9,label='Cached unfold')
axes[0].set_ylabel('Amplitude');axes[0].legend(frameon=False,loc='upper right')
axes[1].plot(clock,cached-exact,color='#ef5a2a',lw=1,label='Delivered PCM difference')
axes[1].axhline(.025,color='#687749',ls='--',lw=1,label='Preregistered peak-error limit')
axes[1].axhline(-.025,color='#687749',ls='--',lw=1)
axes[1].set_ylabel('Cache − exact');axes[1].set_xlabel('Milliseconds after key-up')
axes[1].legend(frameon=False,loc='upper right',fontsize=8)
for ax in axes: ax.grid(alpha=.15);ax.set_xlim(0,30)
fig.suptitle('Reed Mark C4: a 137 ms tap, delivered PCM16\nA small early waveform mismatch remains')
for suffix in ['png','pdf']: fig.savefig(out/f'137ms-transition.{suffix}',dpi=160)
plt.close(fig)

for name in ['manifest.json','report.json','README.txt','reader-verification.json','137ms-transition.png','137ms-transition.pdf']:
    entries[name]=(out/name).read_bytes()
entries['playable-protocol.md']=Path(__file__).with_name('playable-protocol.md').read_bytes()
zip_path=out/'crease-playable-reference-wavs.zip'
with zipfile.ZipFile(zip_path,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for name,data in sorted(entries.items()):
        info=zipfile.ZipInfo(name,date_time=(2026,10,31,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
        info.external_attr=0o644<<16;z.writestr(info,data)
with zipfile.ZipFile(zip_path) as z:
    b.p.require(z.testzip() is None,'ZIP CRC')
    for name,data in entries.items(): b.p.require(z.read(name)==data,'ZIP entry changed')
delivery={'result':'passed','renderSource':m['renderSource'],'files':15,
          'wavFlacPcmIdentity':True,'silentEndings':True,'zipEntriesExact':True,
          'archive':zip_path.name,'bytes':zip_path.stat().st_size,'sha256':b.sha(zip_path.read_bytes()),
          'scope':'Fixed mono 44.1 kHz PCM16 performances, not a release-aware instrument bank'}
b.write(out/'delivery.json',delivery)
print(json.dumps(delivery))
