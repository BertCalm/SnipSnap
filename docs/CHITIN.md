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

## Candidate voices: HUSK, STING, GLINT, HUM, PINCER (round chitin-voices-1, 2026-11-20)

Status: **built, measured, unheard**. The melodic gate asks for six voices and CHITIN has two. Five candidates
are appended to `ChitinVoice` after WING, so saved names and ordinals do not move. `Chitin.KEPT_VOICES` is the two
voices players are offered; the candidates have no preset or kit, and the kept renders (defaults across TUNE and
touch, and all twelve candidate colours) are pinned byte for byte in `ChitinCandidateVoicesTest`, the first hash
pin CHITIN has had. The owner needs to keep at least four. The code is `ChitinCandidates.kt`.

SHELL and WING both mix the synced pair, low-pass it and add the phaser's correction. Each candidate reads a part
of that model the kept voices leave unused.

- **HUSK** (C2 to C4) keeps only what the phaser changes. The bare root sine carries the note and the phaser's
  correction path (which is high-passed above three times the root) is all that is heard of the synced source.
  With PHASE at zero it is a plain sine.
- **STING** (C3 to C5) multiplies the synced pair instead of mixing it, and is heard through the filter's band tap
  as well as its low tap. At BITE 0 the product is a round parabola; as the ratio rises it narrows into a buzz.
- **GLINT** (C3 to C5) lets the synced slave bend the phase of the root sine. The slave is windowed to zero at each
  master reset, so the wave stays continuous and the note stays on the root. It is struck: the bend is deepest at
  the hit and the note dies away inside the gate.
- **HUM** (C2 to C4) moves the master pulse's width with the shared excursion, from a square to about a quarter;
  the slave's ratio stays put. The moving width shifts each harmonic's phase, which gives the tone its chorus.
- **PINCER** (C1 to C3) is the spare: SHELL's pulse-and-slave source with the slave leading, plucked, with the
  excursion run backwards (open at the pluck, closing at PACE).

All five keep the seven controls, the frame plan, the 4x render, the house finish and the loudness target of the
kept voices. HOLD is the same finite gate (no seamless loop for any CHITIN voice yet). Touch moves the filter's
resting place by up to .9 octave as well as the kept attack and excursion depth, so soft and hard differ in tone
at equal loudness. The candidates skip the kept voices' whole-note mean subtraction and rely on the 20 Hz
high-pass alone: a lopsided wave (a narrow pulse, a product) picks up a standing offset in the drive, and
subtracting its mean would open the note with a step. The held part of HUSK, STING and HUM carries a slow seeded drift on the excursion (.17 Hz and
.41 Hz together, up to 16 %), so a long note is not a fixed loop of the phaser; renders stay deterministic. This
is the one place a candidate departs from the kept rule of no modulation beyond the excursion.

Measured (Kotlin, `ChitinCandidateVoicesTest`; nothing here is heard): finite, under the .99 ceiling, no offset,
phaser energy bound kept, deterministic and silent at zero velocity at all 32 corners of BITE, COLOR, PHASE, MORPH
and PACE; every corner stays in the melodic classes except PINCER at COLOR 0 with MORPH and PACE at 1, a dark
fast pluck the classifier calls a tom. First sample closed and the last 20 ms closed at the bottom,
middle and top of each range and three touches. Root within 15 cents everywhere: HUSK, GLINT and PINCER within 1
cent, STING 2 to 6 cents flat, HUM 2 to 7 cents sharp while its width is moving (largest at C2). At least 3.7 dB
from every other voice wherever two ranges meet (C2, C3, C4): the nearest pair is STING and WING at 4.1 dB (C3)
and 4.2 dB (C4); next are HUM and PINCER at 5.3 dB (C2) and PINCER and SHELL at 5.4 dB (C2). Level-matched touch
steps: HUSK 4.1 and 2.7 dB, STING 2.3 and 2.1 dB, GLINT 5.3 and 3.8 dB, HUM 4.3 and 2.5 dB, PINCER 3.6 and 3.1 dB
(the kept SHELL moves 1.7 dB from soft to hard, WING 5.0 dB).

Known weak spots, for the listening round: STING is the closest to a kept voice (WING); HUSK's body is a bare sine,
which may be too plain under the moving band; GLINT is close to a sine once the strike has passed (root share .79).
