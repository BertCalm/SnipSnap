# CHITIN finite-note prototype

CHITIN has six voices: SHELL is a pulse-root bass, WING a brighter saw-root lead, and HUSK, STING, HUM
and PINCER were kept in chitin-voices-1 (below); GLINT is a candidate.
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

## New voices: HUSK, STING, HUM, PINCER kept; GLINT a candidate (round chitin-voices-1, 2026-11-20)

Status: **four kept by ear, one to tweak**. Round chitin-voices-1 (20 Nov 2026): HUSK, STING, HUM and PINCER KEEP,
on both the range clip (four notes over the voice's two octaves at full touch) and the touch clip (the middle note
soft, medium, hard), no notes. GLINT: TWEAK, with the note "Missing glassiness" on both of its clips. CHITIN now
has six voices: SHELL, WING, HUSK, STING, HUM and PINCER.

The five are appended to `ChitinVoice` after WING, so saved names and ordinals do not move. `Chitin.KEPT_VOICES`
is the six kept voices, and the app's voice picker reads it; GLINT stays a candidate, with no preset or kit, and
is not offered. `Chitin.MIXED_VOICES` is SHELL and WING, the voices that mix the synced pair and carry the twelve
colours. Each kept new voice has one preset, named after it: its default controls (TUNE .5, the middle of its two
octaves) at full touch, the middle note of its heard range clip and the hard note of its touch clip. There is no
new starter; the CHITIN kit is unchanged. The heard renders (four range notes plus the soft and medium touches of
each voice, 24 in all) are pinned in `chitin/kept-voices-v1.sha256`, and SHELL and WING (defaults across TUNE and
touch, and all twelve colours) are pinned against the build before the round, both in `ChitinCandidateVoicesTest`.
The code is `ChitinCandidates.kt`.

**No seamless HOLD for any CHITIN voice.** HOLD is a finite gate on all six (and on GLINT): its top step is one
long note, not a loop. A seamless held CHITIN is still open.

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

Measured before the round (Kotlin, `ChitinCandidateVoicesTest`): finite, under the .99 ceiling, no offset,
phaser energy bound kept, deterministic and silent at zero velocity at all 32 corners of BITE, COLOR, PHASE, MORPH
and PACE; every corner stays in the melodic classes except PINCER at COLOR 0 with MORPH and PACE at 1, a dark
fast pluck the classifier calls a tom. First sample closed and the last 20 ms closed at the bottom,
middle and top of each range and three touches. Root within 15 cents everywhere: HUSK, GLINT and PINCER within 1
cent, STING 2 to 6 cents flat, HUM 2 to 7 cents sharp while its width is moving (largest at C2). At least 3.7 dB
from every other voice wherever two ranges meet (C2, C3, C4): the nearest pair is STING and WING at 4.1 dB (C3)
and 4.2 dB (C4); next are HUM and PINCER at 5.3 dB (C2) and PINCER and SHELL at 5.4 dB (C2). Level-matched touch
steps: HUSK 4.1 and 2.7 dB, STING 2.3 and 2.1 dB, GLINT 5.3 and 3.8 dB, HUM 4.3 and 2.5 dB, PINCER 3.6 and 3.1 dB
(the kept SHELL moves 1.7 dB from soft to hard, WING 5.0 dB).

Weak spots named before the round (the owner's only note was GLINT's): STING is the closest to a kept voice (WING); HUSK's body is a bare sine,
which may be too plain under the moving band; GLINT is close to a sine once the strike has passed (root share .79).

## GLINT tweak: glassiness (after chitin-voices-1, unheard)

Status: **built, measured, unheard**; GLINT stays a candidate until the owner hears it. The note was "Missing
glassiness": the heard GLINT was close to a sine once the strike had passed, because its synced slave can only
make harmonics and its bend died with the strike.

The fix stays inside GLINT's mechanism, the slave bending the root sine's phase. At the strike the slave is synced,
as before: a harmonic ping. Released from sync, it runs on at 3.6 times the root (`ChitinCandidates.GLASS`) and
keeps bending the root for the whole note, so the first sidebands sit at 2.6 and 4.6 times the root and the second
at 6.2 and 8.2: inharmonic, near a wine glass's ring modes (about 1, 2.4 to 2.6, 4.2 to 4.6, 6.6 to 7). A second
copy 1.3 Hz faster (a glass's split mode pair) makes the ring shimmer slowly. A harder touch bends further and adds
a 35 ms flash of upper partials at the hit. Around it: the synced bend's sustained part is smaller, the onset is
3 to 5 ms (was 5 to 12), the drive is gentler (it was washing the partials together), the filter rests higher so the
glass passes at any COLOR, the ring time is .70 of the gate (was .60) and the default HOLD is .50 (was .30), so a
note lasts about 1.7 s (was 1.4 s).

Measured (Kotlin; C4, partials at 2.6 and 4.6 times the root against the root, 200 ms windows): before 0.00 at
.15, .5 and .9 s; after .41, 1.12 and .84, and .95 at 1.3 s, above harmonics 2 to 5 from .5 s on. Root within 1
cent at C3, C4 and C5, root share .81. Touch steps 5.2 and 3.5 dB. At least 25.8 dB from every other voice. SHELL,
WING and the four kept voices are unchanged (their pins pass).

## GLINT touch (round fix-glint-1, 2026-11-20)

Verdicts: the reworked GLINT across its range (CX1) **KEEP**; soft, medium and hard at C4 (CX2) **TWEAK**, "All
sound the same". The measured steps there were 5.2 and 3.5 dB (level-matched, 8.7 dB soft to hard), because touch
only nudged the bend, the flash and the filter.

Full touch is the kept GLINT, byte for byte: every new touch term is a factor of exactly 1 (or a term of exactly 0)
at full touch, so CX1's notes, CX2's hard note and the future preset do not change. Below full touch GLINT now
falls away steeply: the synced bend (the ping) scales with touch to the power 1.5 (`GLINT_TOUCH_BEND`), the glass
ring and its flash with touch to the power 2.5 (`GLINT_TOUCH_GLASS`), the filter rests up to 2 octaves darker
(`GLINT_TOUCH_DARK`, times one minus touch) and the onset rounds out to about 11 ms at touch .35. Soft is a round,
nearly pure ring with a trace of glass and no flash; hard is the kept bright glass strike. Every touch still plays
at the house loudness: the tone change was enough, so GLINT needs no level difference either.

Measured (Kotlin, C4, level-matched): touch steps 23.9 and 18.1 dB (was 5.2 and 3.5), 40.1 dB soft to hard (was
8.7). Glass partials against the root at .9 s: soft .04, medium .26, hard .84 (was .58, .68, .84); upper partials
in the first 50 ms: .04, .47, 3.59 (was .91, 1.82, 3.59). The touch test's floor for GLINT is now 12 dB (others .5).
Unheard: clip CY1 plays the old soft, medium and hard, then the new ones.
