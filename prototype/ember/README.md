# EMBER round 0 (Python prototype)

A live jazz and soul drum kit in a small club in 1962, from physical models. Design notes and every measured
number: [docs/EMBER.md](../../docs/EMBER.md). Python harness, not Kotlin; nothing here has been heard.

```sh
python3 prototype/ember/ember_proto.py OUT          # one WAV per voice at its defaults
python3 prototype/ember/gen_audition.py OUT         # four kits x 16 pads x 3 zones x 4 takes, sweeps, checks (~15 min)
python3 prototype/ember/build_page.py OUT           # OUT/index.html, the listening page
```

- `ember_proto.py`: the model (26 voices, the membrane, cymbal bands, threshold parts, implements, the club, the chain).
- `kits.py`: the four kits, their grooves (triplet grid) and the players' feel.
- `measures.py`: CLUTTER's house distance measures, copied so the numbers mean the same thing.
- `gen_audition.py`, `build_page.py`: the listening page (CLUTTER's layout and pad grid).

Needs numpy, scipy and the Luthier `snipkit.py` (found through `.claude/skills/luthier/scripts`).
