#!/usr/bin/env python3
"""Fit the complete Rime audition into Sites' archive limit, preserving PCM exactly.

Kotlin generators and MPC packages remain WAV-native. Only new browser-preview
assets use FLAC. The injected player helper reconstructs standard PCM24 WAV
when downloaded. Existing models 1–5 and their URLs remain untouched.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('directory',type=Path)
parser.add_argument('--report',type=Path,required=True)
args=parser.parse_args()
root=args.directory.resolve()
manifest=json.loads((root/'manifest.json').read_text())
assert manifest['engine']=='RIME' and manifest['model']==6
assert len(manifest['clips'])==387
jobs=[]
for clip in manifest['clips']:
    if clip['id'].startswith('r6-'):
        for mode in ['raw','matched']:
            source=root/clip['files'][mode]
            if source.suffix=='.wav':jobs.append((clip,mode,source))

def convert(job):
    clip,mode,source=job
    blob=source.read_bytes()
    assert blob[:4]==b'RIFF' and blob[8:12]==b'WAVE'
    chunks={};at=12
    while at+8<=len(blob):
        kind=blob[at:at+4];size=struct.unpack_from('<I',blob,at+4)[0]
        chunks[kind]=blob[at+8:at+8+size];at+=8+size+(size&1)
    assert set(chunks)=={b'fmt ',b'data'},source
    assert struct.unpack_from('<HHIIHH',chunks[b'fmt '])==(1,1,44100,132300,3,24)
    pcm=chunks[b'data']
    target=source.with_suffix('.flac')
    subprocess.run(['ffmpeg','-nostdin','-loglevel','error','-y','-i',str(source),'-map_metadata','-1',
        '-c:a','flac','-compression_level','12','-sample_fmt','s32','-bits_per_raw_sample','24','-threads','1',str(target)],check=True)
    encoded=target.read_bytes()
    assert encoded[:4]==b'fLaC' and (encoded[4]&127)==0
    packed=int.from_bytes(encoded[18:26],'big')
    assert packed>>44==44100 and ((packed>>41)&7)+1==1 and ((packed>>36)&31)+1==24
    assert packed&((1<<36)-1)==len(pcm)//3
    decoded=subprocess.check_output(['ffmpeg','-nostdin','-loglevel','error','-i',str(target),
        '-map_metadata','-1','-f','s24le','-c:a','pcm_s24le','-threads','1','-'])
    assert decoded==pcm,source
    return {'clip':clip['id'],'mode':mode,'wav':source.relative_to(root).as_posix(),
        'flac':target.relative_to(root).as_posix(),'wavBytes':len(blob),'flacBytes':len(encoded),
        'sourceWavSha256':hashlib.sha256(blob).hexdigest(),'pcmSha256':hashlib.sha256(pcm).hexdigest(),
        'flacSha256':hashlib.sha256(encoded).hexdigest(),'frames':len(pcm)//3}

with ThreadPoolExecutor(max_workers=2) as pool:
    rows=list(pool.map(convert,jobs))
# Publish the manifest only after every encode/decode comparison passes.
for row,(clip,mode,source) in zip(rows,jobs):clip['files'][mode]=row['flac']
manifest['previewEncoding']='Lossless FLAC for model-6 previews; native PCM24 WAV download is reconstructed at 44.1 kHz. Earlier WAV assets and all MPC packages are unchanged.'
(root/'manifest.json').write_text(json.dumps(manifest,separators=(',',':')))
shutil.copyfile(Path(__file__).with_name('rime-hosting-download.js'),root/'rime-wav-download.js')
html=(root/'index.html').read_text()
if 'src="rime-wav-download.js"' not in html:html=html.replace('</head>','  <script src="rime-wav-download.js" defer></script>\n</head>')
(root/'index.html').write_text(html)
for _,_,source in jobs:source.unlink()
report={'files':rows,'count':len(rows),'pcmDecodedExact':True,'sampleRate':44100,'channels':1,'bits':24,
    'wavBytes':sum(r['wavBytes'] for r in rows),'flacBytes':sum(r['flacBytes'] for r in rows)}
args.report.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='files'}))
