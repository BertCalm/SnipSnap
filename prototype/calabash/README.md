# CALABASH round 0 (Python prototype)

Four drum kits for Afrobeat, Afrobeats, Mbalax and Afro-Cuban jazz. Design notes and every measured number:
[docs/CALABASH.md](../../docs/CALABASH.md). Python harness, not Kotlin; nothing here has been heard.

```sh
python3 prototype/calabash/calabash_proto.py OUT         # one WAV per voice at its defaults
python3 prototype/calabash/gen_audition.py OUT           # 4 kits x 16 pads x 3 zones x 4 takes, strokes, sweeps, checks
python3 prototype/calabash/build_page.py OUT             # OUT/site/index.html and its FLAC and MP3 audio
```

- `calabash_proto.py`: the model (36 voices, the strokes, the room, the era chain). Imports EMBER's prototype as its
  toolkit (membranes, implements, fitted hats, the 1962 chain) rather than copying it.
- `fitted.py`: the fitted voices' renderer (a recording's own modes struck again, plus its residual).
- `kits.py`: the four kits, their grooves (sixteenth grid, swing) and chokes.
- `fit_calabash.py`, `hands.json`, `NOTICE.txt`: the hand drums, bells, claves, shaker and claps fitted to CC0
  recordings (VCSL). Only the fitted numbers are here; NOTICE.txt lists the 52 files and their sha256.
- `check_fit.py`: each fit against its recording and a real second take.
- To put each fitted voice beside its recording on the page, decode the files in NOTICE.txt to mono 44.1 kHz float
  WAV and run the generator with `CALABASH_REF_WAVS=<dir>`.

Needs numpy, scipy, ffmpeg (for the page's audio) and the Luthier `snipkit.py` (found through EMBER's prototype).
