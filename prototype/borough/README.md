# BOROUGH (Python harness)

Design, measurements and results: [docs/BOROUGH.md](../../docs/BOROUGH.md).
Needs numpy, scipy, soundfile; `build_page.py` also needs ffmpeg.

```sh
python3 -I extract.py REFS_DIR targets.json      # reference loops -> numbers (refs are not in the repo)
python3 fit.py targets.json fitted.json [loop ...] # ~40 min on 4 cores; resumes from fitted.json
python3 build.py fitted.json OUT                  # 792 WAVs, grooves, kit+stack demos (~20 s)
python3 verify.py OUT targets.json fitted.json    # probes and the fit report
python3 build_page.py OUT                         # OUT/page: the listening page with MP3 demos
```

`targets.json` and `fitted.json` are committed: numbers only, with each reference file's SHA-256.

Round 1 (acoustic voices, `acoustic.py`, on EMBER's models in `../ember`) runs the same scripts with
`BOROUGH_MODEL=acoustic` and its own fit file:

```sh
BOROUGH_MODEL=acoustic BOROUGH_ITER=28 python3 fit.py targets.json fitted_r1.json [loop ...]  # ~80 min on 4 cores
BOROUGH_MODEL=acoustic python3 build.py fitted_r1.json OUT_R1   # ~4 min
python3 verify.py OUT_R1 targets.json fitted_r1.json
python3 build_page.py OUT_R1 OUT                                 # OUT (round 0) adds before players
```
