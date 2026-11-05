# CHITIN finite-note prototype

CHITIN has two voices: SHELL is a pulse-root bass, WING a brighter saw-root lead.
A shared excursion changes hard-sync harmonics, filter opening, preamp drive and
six-stage phaser motion. The phaser's correction is high-passed on both sides to
preserve the root. Its normalized lattice and feedback have an energy bound.

Controls are TUNE, BITE, COLOR, PHASE, MORPH, PACE and HOLD. Higher PACE makes the
excursion faster. HOLD extends a finite gate; .98 through 1 are the same long
note. Long files can receive the classifier's LOOP label, but have no seamless
loop region. The engine is outside the factory preset roster pending listening.

The owner approved the first core audition. Six candidate colors per voice now
remain outside the factory roster until their focused audition is heard.

Render candidate presets and macro edits:

```sh
./gradlew :cli:run --args='synth CHITIN SHELL --preset 1 --macros BITE=.7,PHASE=.6 --out /tmp/chitin'
./gradlew :synth:generateChitinBank
./gradlew :synth:generateChitinAudition
python3 scripts/probe_chitin_sources.py testkit/chitin-audition
```

The generated HTML page includes voice registers, macro sweeps, pairs, velocity
and source/phaser ablations. Recipes, control traces, diagnostics and source-only
4x/16x references accompany the WAVs. The source probe requires NumPy/SciPy.
The focused bank includes twelve recipes, sixteen pad previews and a downloadable
MPC program folder. The first eight pads ascend C minor pentatonic from C1 to F2;
the remaining eight compare colors, with long notes last. `generateChitinBank`
creates both download ZIPs. The older control lab remains available at `/lab/`.
The candidates have not yet received a listening verdict.

The original implementation baseline was `2b3e168`; this round merges the current
base `482c55a`. The picker now includes both voices, candidate presets and note-root
editing. Maximum HOLD is labelled LONG NOTE and does not expose seamless sustain.
Android compilation and device behavior require the SDK-backed CI jobs.

The 192-corner regression grid found that maximum-speed WING sync could become
broadband percussion in the first FFT window. Starting the shared morph at 80 ms
preserves a pitched attack; the full corner grid passes with this correction.
The engine equations and phaser topology are otherwise retained.

Focused validation: 14 Kotlin checks passed, including presets, real classifier
results, replayable kit recipes, native-rate sample headers and disabled sample
loops. Hardware loading/listening, phone performance, seamless HOLD and promotion
of candidate presets into the factory roster remain separate gates. See the
engineering spec's As built entry for the final repository test result.

## Why it is built this way

- Hard sync moves the slave's spectrum while the master fixes the note.
- One morph envelope makes the synth and phaser follow a common gesture.
- A protected correction path keeps cancellation away from the bass root.
- Finite notes provide a complete audition before periodic-state loop research.
