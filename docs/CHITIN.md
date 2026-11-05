# CHITIN finite-note prototype

CHITIN has two voices: SHELL is a pulse-root bass, WING a brighter saw-root lead.
A shared excursion changes hard-sync harmonics, filter opening, preamp drive and
six-stage phaser motion. The phaser's correction is high-passed on both sides to
preserve the root. Its normalized lattice and feedback have an energy bound.

Controls are TUNE, BITE, COLOR, PHASE, MORPH, PACE and HOLD. Higher PACE makes the
excursion faster. HOLD extends a finite gate; .98 through 1 are the same long
note. Long files can receive the classifier's LOOP label, but have no seamless
loop region. The engine is outside the factory preset roster pending listening.

Render provisional starting points and macro edits:

```sh
./gradlew :cli:run --args='synth CHITIN SHELL --preset 1 --macros BITE=.7,PHASE=.6 --out /tmp/chitin'
./gradlew :synth:generateChitinAudition
python3 scripts/probe_chitin_sources.py testkit/chitin-audition
```

The generated HTML page includes voice registers, macro sweeps, pairs, velocity
and source/phaser ablations. Recipes, control traces, diagnostics and source-only
4x/16x references accompany the WAVs. The source probe requires NumPy/SciPy.
These are generated Kotlin audio, with no listening approval implied.

The implementation baseline is commit `2b3e168`. See the engineering spec's
As built entry for measured validation and limits. Android picker, target-phone
performance, seamless HOLD and factory presets require their separate gates.

## Why it is built this way

- Hard sync moves the slave's spectrum while the master fixes the note.
- One morph envelope makes the synth and phaser follow a common gesture.
- A protected correction path keeps cancellation away from the bass root.
- Finite notes provide a complete audition before periodic-state loop research.
