#!/usr/bin/env python3
"""Export verified PCM integers/layout for the actual native reader harness."""
import gzip
import json
from pathlib import Path
import sys
import numpy as np
import playable as b
out=Path(sys.argv[1]);m=json.loads((out/'manifest.json').read_text());r=out/'raw'
planes=np.frombuffer(gzip.decompress((out/m['container']['file']).read_bytes()),dtype=np.uint8).reshape(2,-1).T.copy()
pcm=np.frombuffer(planes.tobytes(),dtype='<i2').astype(np.int64)
for _ in range(m['container']['deltaOrder']): pcm=np.cumsum(pcm,dtype=np.int64)
pcm=pcm.astype('<i2');assert b.sha(pcm.tobytes())==m['container']['pcmSha256']
pcm.tofile(r/'native-pcm.i16')
values=[m[k] for k in ['heldFrames','loopStart','loopFrames','rootCycles','leadFrames','tailFrames','crossfadeFrames','releaseFrames']]
(r/'native-layout.txt').write_text(' '.join(map(str,values+[len(m['tailCuts'])]+m['tailCuts']))+'\n')
cases=json.loads((r/'reader-fixtures.json').read_text())
(r/'native-cases.txt').write_text(' '.join(map(str,[len(cases['cuts']),cases['frames']]+cases['cuts']))+'\n')
