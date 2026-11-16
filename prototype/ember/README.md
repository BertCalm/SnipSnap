# EMBER rounds 0 to 0e (Python prototype)

A live jazz and soul drum kit in a small club in 1962, from physical models. Design notes and every measured
number: [docs/EMBER.md](../../docs/EMBER.md). Python harness. The owner heard round 0e as "Much better" (2026-11-15);
it is the baseline the Kotlin port (`synth/.../Ember.kt`, round 1) is measured against.

```sh
python3 prototype/ember/ember_proto.py OUT          # one WAV per voice at its defaults
python3 prototype/ember/gen_audition.py OUT         # four kits x 16 pads x 3 zones x 4 takes, sweeps, checks (~15 min)
python3 prototype/ember/build_page.py OUT           # OUT/index.html, the listening page
```

- `ember_proto.py`: the model (35 voices: 27 approved, and round 2L's eight Latin percussion voices awaiting a listen; the membrane, cymbal bands, threshold parts, implements, the club, the chain).
- `kits.py`: the four kits, their grooves (triplet grid) and the players' feel.
- `fit_ember_cymbals.py`, `cymbals.json`, `NOTICE.txt`: round 0b. The cymbals and hats fitted to CC0 recordings
  (Virtuosity Drums) with BREAKER's fitter; only the fitted numbers are here. To show each cymbal beside its recording on
  the page, decode the recordings listed in NOTICE.txt and run the generator with `EMBER_REF_WAVS=<dir>`.
- `fit_ember_brushes.py`, `brushes.json`: round 0c. The brush sweep and brush roll fitted to CC0 stirs and flutters
  (Karoryfer Swirly Drums); NOTICE.txt lists them. `EMBER_STIR_DIR=<.../Samples/snare_stir>` adds the comparisons to the page.
- `fit_ember_latin.py`, `latin.json`: round 2L. The cowbell, clave, bongos and shaker fitted to CC0 recordings (VCSL),
  and the guiro's measured click spectrum, ridge rate and stroke lengths; NOTICE.txt lists the recordings.
- `measures.py`: CLUTTER's house distance measures, copied so the numbers mean the same thing.
- `gen_audition.py`, `build_page.py`: the listening page (CLUTTER's layout and pad grid).
- `compare_kotlin.py KOTLIN_DIR`: round 1. Measures the Kotlin clips (`./gradlew :synth:generateEmberAudition`) against
  these renders voice by voice: distance to the nearest Python take, Python's own take spread, correlation, centroid,
  decay, soft against hard.
- `export_club.py`: round 1. Writes the club's tail (Python's own seeded draw) to
  `synth/src/main/resources/ember/club_tail.f32` and checks that it rebuilds the room exactly.

Needs numpy, scipy and the Luthier `snipkit.py` (found through `.claude/skills/luthier/scripts`).
