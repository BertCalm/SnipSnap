# CONSORT: design brief

Status: concept with a Python prototype. Nothing is built in Kotlin. The owner heard round 1
("sounds pretty good"), then round 2 (room approved at its default, takes inaudible); round 3
below rebuilds the takes and awaits a listen.
Repository truth: read at `3117e454`. Grepped and found: `Bore.Player` (`Bore.kt:935`, its
`tick` at 1012 takes `pressureScale`, `pitchCents`, `turbulenceScale`), `Strings.Loop.reflected`
/ `inject`, `MacroSpec` (`Thump.kt:40`), `Velocity.macroSpecsFor` and `touchedRender`
(`Velocity.kt:203,425`), `Keys.MAX_SEAM_ERROR` (`Keys.kt:159`), `EnsemblePlayers.kt`,
`PatchTypes.PROTOTYPES`. No engine or doc uses the name CONSORT.

The characteristic gesture is **a breath starts under the note → the lead settles and the
section lands around it a few milliseconds late → the lead ornaments (trill, flutter, bend)
while the section holds → a harder breath cracks or growls the whole section → the breath
lets go and the pipes ring out**.

## What it is

A woodwind section in one pad. One lead instrument and up to three harmony players, each a
separate blown pipe with its own seed, timing and tuning drift. Each harmony player is a fixed
interval from the lead: octave down, unison, fifth, minor seventh, major seventh, octave up.
Any of six instruments can sit in any chair: FLUTE, SAX, CLARINET, OBOE, BASSOON, DUDUK.
Velocity is breath effort, so a harder hit is brighter and airier and, past a threshold,
overblown: the flute cracks up an octave, the reeds growl. A stepped GESTURE knob bakes one
ornament into the note: plain, scoop, trill, flutter-tongue, fall. A "consort" is the old word
for a small band of winds; a mixed one was called a broken consort.

## What the 24 references measured (Python, monophonic pitch tracker; stacks read roughly)

| Quantity | Range across the references | Used for |
|---|---|---|
| Vibrato rate | 3.0 to 6.1 Hz (clarinet 3.0, sax 4.0 to 4.9, oboe 4.2, duduk 5.1, bassoon 5.1 to 5.6) | each voice's own vibrato rate |
| Vibrato depth | 6 to 22 cents (duduk deepest at 22, clarinet and stacks shallowest) | each voice's depth; MOTION scales it |
| Pitch range | C2 (bassoon) to F#6 (overblown flute) | the six root notes |
| Brightness (spectral centroid) | 771 Hz (dark melody) to 4354 Hz (bright reed); bassoon 795 to 1330, duduk and bawu 970 to 1030, oboe 1230 to 1570, sax 1920 to 3620 | the order of brightness between voices |
| Noisiness (spectral flatness) | 0.00 (pure flute pad) to 0.25 (beatbox flute); overblown flutes 0.15 to 0.18 | breath noise range, and that overblow adds air |

Several references are stacks, processed or reverberant, so these are guides to order and
range, not targets to match.

## Why it earns a pad

Nearest siblings: **BORE** (one player, FLUTE and SAX, overblow on the flute only) and
**OBBLIGATO** (a jet and a reed in an invented duet with a relationship knob). Neither can
voice a chord: there is no way to say "this player a fifth above, that one an octave below",
neither has clarinet, oboe, bassoon or duduk, and neither trills. The rack's ENSEMBLE section
makes copies of one finished sound, so its players are always the same instrument at the same
pitch. CONSORT's one new thing is **a voiced section of different instruments following one
lead's breath and gesture**.

## Signal flow

```
note, velocity, macros, section (lead + up to 3 × (instrument, interval))
        │
        ▼
[Player plan, one per chair]──(curves @ control rate: mouth pressure, pitch cents,
   seeded per chair              breath noise, jet travel time)
   lead: all gestures                │
   others: breath, scoop, fall       ▼
                          [Pipe: Bore.Player-style loop @176.4 kHz]
                            CONE reed  (sax, oboe, bassoon): full-period loop
                            CYL reed   (clarinet, duduk): half-period, sign flipped
                            JET        (flute): jet line, travel time halves to overblow
                                     │   [cache: pitch pin per (instrument, note, effort)]
                                     ▼
                          [Rasp: memoryless tanh bend, drive rises with effort]
                                     ▼
                          [Body: bell tilt + 2 or 3 fixed formants per instrument]
                                     ▼
        [Section mix: lead at unit level, others at BLEND]──▶
        [Output: Tide.bandLimit → Dsp.decimate → mean, 20 Hz high-pass → Dsp.levelTo → Dsp.fadeTail]
```

## Macro contract (proposed)

| Control | Default | What it changes in the model | Required interaction | Test |
|---|---|---|---|---|
| TUNE | 0.5 | the lead's note, two octaves in semitones; every chair moves with it | none | root within 10 cents per chair rendered alone |
| BREATH | 0.6 | mouth pressure, breath noise, bell opening, rasp drive | with velocity: effort = 0.5·BREATH + 0.6·velocity, overblow above 0.85 | centroid rises with BREATH and with velocity |
| GESTURE | 0 (PLAIN) | which ornament the plan writes: PLAIN, SCOOP, TRILL, FLUTTER, FALL (stepped) | with MOTION | each step differs from PLAIN |
| MOTION | 0.5 | how much of the gesture: vibrato depth, scoop depth, trill speed, flutter depth, fall length | with GESTURE | extremes differ on every step |
| BLEND | 0.6 | harmony players' level, 14 dB under the lead up to level with it | none | level of a harmony partial moves |
| LOOSE | 0.35 | harmony players' lateness (up to 35 ms) and tuning drift (2 to 14 cents) | with BLEND (inaudible at low BLEND) | onset spread and pitch spread grow |
| HOLD | 0.45 | breath length 0.3 to 4 s; top step a seamless loop | LOOP threshold agrees with `drumClassFor` | seam under `Keys.MAX_SEAM_ERROR` |

Six macros plus HOLD is the house maximum. The section itself (who sits in chairs 2 to 4 and at
which interval) is patch data, not a macro: three stepped detail controls per patch, CHAIR 2,
CHAIR 3, CHAIR 4, each an instrument and an interval or OFF. How those are stored
(`MacroSpec` steps or fields on the patch) is the open architecture decision for the Kotlin round.

## The three directions

**Strong: section of pipes (COUPLE, one-way, control rate).** Every chair is a real blown pipe;
the lead's plan is the conductor and the others follow its breath, scoop and fall with their own
lateness and drift. Gesture clarity high, novelty against the roster high, risk medium (six
pipes must speak across two octaves), cost up to four pipes per render. Wins because trills and
overblow happen inside the pipe: a trill is the pipe changing length with the wave still in it,
and the flute's crack is a real regime change, not a crossfade.

**Alternate A: BORE through pitch-shifted copies (CROSS of BORE and ENSEMBLE).** Render one
BORE note and shift copies to the intervals. Cheapest, lowest risk, but every chair is the same
instrument, formants move with the shift (a shifted sax reads as a smaller sax), and the players
ornament in lockstep. It would be a rack recipe, not an engine.

**Alternate B: additive section (PORT, analogy).** Harmonic partial banks under formant
envelopes with scripted gestures. Never fails to speak, exact pitch, very cheap. But a trill is
a crossfade between two spectra and overblow is a scripted octave jump; the roster already has
REGISTERS and VOX in that mould. Kept as the fallback for any single voice whose pipe will not
behave in a register.

## What the prototype measured (`docs/prototypes/consort/consort_proto.py`, Python harness, not Kotlin)

| Question | Result |
|---|---|
| Do all six pipes speak on pitch across two octaves? | 18 renders (6 voices × low, middle, high): worst error 3.6 cents, lowest pitch confidence 0.95, all finite, DC 0.0, peaks under 0.64 |
| Does a hard hit overblow the flute? | soft and medium read 0.6 and 0.8 cents from the note; hard reads 1198.4 cents (an octave), confidence 1.00 |
| Is velocity a tone change, not only level? | centroid soft → medium → hard, after levelling: FLUTE 844 → 1081 → 1454 Hz, SAX 922 → 1263 → 1607, CLARINET 555 → 678 → 862, OBOE 967 → 1149 → 1324, BASSOON 369 → 436 → 517, DUDUK 506 → 572 → 664 |
| Are the instruments distinct on paper? | clarinet and duduk keep odd harmonics (even ones 42 to 62 dB down); sax, oboe and bassoon have the full series with different formants |
| Does each gesture change the sound? | log-spectral distance from PLAIN: 3.1 to 12.1 dB across FLUTE, SAX, DUDUK |
| Does each macro move? | BREATH 10.1 dB, BLEND 5.3, MOTION 2.6, LOOSE 2.4 (this metric barely sees timing and tuning changes, so LOOSE and MOTION need their own tests) |
| Aliasing | energy above the output Nyquist before band limiting, brightest corner: 0.0 |
| Cost | 0.31 s of Python per player-second. Says nothing about Kotlin; BORE's one pipe is the reference there |

## Round 2: humanization and room (after the owner's first listen)

The owner's verdict on round 1: "Sounds pretty good. I think we need to push the humanization a
bit further and maybe add some room (this was a game changer on the horns engine)." I did not
find a horns engine or its room in the repo at `3117e454` or on the default branch, so the room
here is modelled on the rack's ROOM (`Room.kt`: `SPEED_OF_SOUND`, `MAX_RT60 = 0.9`).

**Humanization.** LOOSE now means how human the players are, default 0.5, and the lead gets
60 % of it. Per player and per take it draws: a tuning offset (up to 12 cents for a harmony
chair, 4 for the lead), attack speed (up to 45 % either way), who lets go first (up to 70 ms),
how far under the note they start, when vibrato enters, and how much air leaks. Through the
note: vibrato speed wanders up to 9 % and its depth up to 35 %, and it shakes the breath as well
as the pitch; the pressure wanders 1 to 9 %; over the last third the breath tapers up to 14 %
and the pitch sags up to 9 cents; trill fingers are uneven and each finger move dips the tone.

**Takes.** A `take` number reseeds everything a player does differently and nothing about the
instrument. This is the proposed round-robin source.

**Room.** A ROOM control, default 0.4. Each chair has a position in a 9 x 7 x 3.6 m room: its
own first and second wall bounces (shoebox mirror images), its own distance, delay and level.
All chairs then share one wash, 0.35 to 0.9 s, 16 to 5 dB under the players. The Python wash is
a convolution with decaying seeded noise, a stand-in for `Room.kt`'s feedback network.

| Question | Result (Python harness) |
|---|---|
| Do the voices stay on pitch with the default humanization and room? | 18 renders: worst 7.6 cents, lowest confidence 0.96. The duduk's deep vibrato reads 7 to 10 cents flat in a 0.25 s window |
| Does the flute still overblow? | hard hit reads 1193 cents |
| Does velocity still move tone? | soft to hard centroid: FLUTE 795 to 1491 Hz, SAX 834 to 1461, CLARINET 441 to 638, OBOE 988 to 1207, BASSOON 369 to 521, DUDUK 562 to 805 |
| Are takes different? | waveform correlation between takes 0.33 to -0.12 (identical takes would read 1.0); sax solo takes detected at 262.21, 261.43, 261.76 Hz, a spread of 5 cents |
| Do the macros move? | BREATH 9.5 dB, BLEND 6.7, ROOM 3.4, LOOSE 3.1, MOTION 2.9 |
| Cost | the whole audition set, 90 files, in 82 s of Python |

**Open decisions this round adds.**
- ROOM makes seven macros plus HOLD, one over the house maximum of six. Options: land the
  engine with the rack's ROOM in its recipe (the house rule that an engine renders dry, as BORE
  lands with TAPE) and keep only the per-chair seating inside the engine; or fold BLEND and
  ROOM into one "distance" control. The per-chair bounces cannot come from the rack, which sees
  one finished mix.
- A room tail lengthens every render by up to 1.2 s. A pad with HOLD under about 0.3 would cross
  the classifier's 1.5 s LOOP line on its tail alone at high ROOM; `drumClassFor` must count the tail.
- A HOLD loop with a room in it needs the wash to be periodic too, or the room applied after the loop.
- The room measures darker (it adds low-mid energy), on a prototype that was already dark.

## Round 3: takes that can be heard (after the owner's second listen)

The owner's verdict on round 2: "Room is good at default, but I can't hear the takes
differences." ROOM 0.4 is therefore heard and accepted as the default.

Round 2's takes re-rolled small random numbers (a few cents, a few milliseconds). They measured
as different waveforms and were inaudible as different performances: a correlation number is
not evidence that an ear can tell two takes apart. Round 3 makes each take a named way of
playing, spread on purpose across what an ear catches first, with level and pitch centre held:

| Take | What changes |
|---|---|
| centred | the reference |
| soft | lower effort, slow attack, weak tongue, more air, late slow vibrato |
| bitten | higher effort, fast attack, hard tongue, less air, early quick vibrato |
| leaning | comes up into the note from 110 cents under, deeper vibrato |

Chairs are staggered (take + chair) so a section does not all bite together, LOOSE scales how
far a take strays, and a take may not push a note across the overblow line.

| Measured on solo notes (Python harness) | centred | soft | bitten | leaning |
|---|---|---|---|---|
| SAX time to half level | 42 ms | 136 ms | 26 ms | 50 ms |
| OBOE time to half level | 26 ms | 56 ms | 16 ms | 26 ms |
| DUDUK time to half level | 62 ms | 148 ms | 28 ms | 84 ms |
| SAX pitch at 70 ms against the held note | -4 cents | -1 | -1 | -42 |
| SAX held pitch against the target | +3.9 cents | +0.2 | -1.9 | 0.0 |

Disclosed: on SAX the bitten take's held note measures duller than centred (897 against
1119 Hz centroid) although its attack is the brightest (888 against 813); the cause is not
traced. Whether four takes read as one player repeating a note, or as four articulations, is a
listening question, and the audition page now has an eight-hit row with takes cycling beside
the same row with one take.

## Risks and disclosed limits

- **Too dark against the references.** Mid-velocity centroids are 440 to 1260 Hz; the
  references sit at 770 to 4350 Hz. Some of that is production on the references, but the body
  and rasp values are unheard guesses and are the first thing a listen should correct.
- **Reeds do not truly overblow.** The flute's crack is a real regime change. A reed with no
  register hole stays in its low register (BORE's own finding), so hard reeds growl instead. A
  reed squeal up a twelfth or an octave would be a scripted pipe-length jump: an analogy, and
  it should be labelled as one if added.
- **Oboe, sax and bassoon share one pipe.** Their difference here is reed stiffness, rasp and
  fixed formants. Whether that reads as three instruments is a listening question.
- **Render time.** Four pipes cost about four BORE notes. The pitch pin is a precompute keyed by
  (instrument, note, effort bucket) and should be a table in Kotlin, not a render-time search.
- **The acceptance gates are all open.** Six voices exist only in Python. Presets, keygroups,
  kit, chord-tool compatibility, HOLD loop, and velocity and round robin through rendering,
  saving, playback and export have no evidence yet. Round robin has a natural source here (each
  take reseeds every chair's drift, lateness and breath noise) but that is a proposal.
- **Chord tool plus a section stacks intervals.** A seventh chord played on a patch that already
  has a seventh chair is dense. The kit should probably carry both solo and section pads.
- The repo has no LICENSE file. The reed table is Smith's (as in STK's clarinet, already
  credited in `Bore.kt`); nothing new is taken from outside.

## Cheapest proof, and what comes next

Done: the prototype above, two rounds. Next: the owner listens to the audition page and says which
voices read as their instrument, which gestures are worth keeping, and whether the section
sounds like players or like one instrument copied. Only then a full spec and Kotlin, reusing
`Bore.Player` where its pressure, pitch and turbulence inputs already fit.

## Why it is built this way

1. **Each player is a plan first and a sound second.** The plan is four curves: how hard they
   blow, where the pitch sits, how much air leaks, where the flute's jet aims. A trill, a fall
   and an overblow are just different shapes in those curves, so adding a new ornament later
   means writing a new shape, not a new synthesizer. Remove this split and every gesture needs
   its own special case inside the pipe.
2. **The pipe is a loop with a valve, because that is what makes a wind note alive.** The wave
   travels down the pipe, comes back, and the reed or jet decides how much of it bounces. That
   feedback is why a trill has a tiny glitch at each finger move and why a hard-blown flute
   suddenly jumps an octave. A recorded sample or a plain oscillator cannot do either.
3. **The body is fixed while the note moves.** An oboe sounds nasal on every note because its
   strong region stays near 1.1 kHz whatever is played. That is also why harmony players are
   separate pipes and not pitch-shifted copies: shifting a copy drags that region with it and
   the instrument seems to change size.
4. **Velocity is effort, not volume.** Every note is levelled to the same loudness at the end,
   so what a harder hit changes is tone: more harmonics, more air, and past a threshold the
   overblow. This is also what your velocity gate asks for.
5. **The risky decision is building six real pipes instead of a safer additive model.** It buys
   believable transitions and costs render time and tuning effort. The prototype says all six
   speak and stay on pitch; it cannot say they sound right. That is your call on the first listen.

## Ledger

| Claim | Status | Evidence |
|---|---|---|
| Repo APIs named above exist | verified | grep at `3117e454` |
| Reference ranges (vibrato, brightness, range) | measured | Python scan of the 24 WAVs, monophonic tracker |
| Six pipes on pitch, flute overblows, velocity moves tone | measured | `consort_proto.py` report, Python harness |
| Every constant in the prototype | proposed | "shape" values, none tuned by ear |
| Macro set, chair storage, round-robin source | proposed | not implemented |
| Round 1 overall | heard | owner, 2026-11-19: "sounds pretty good", wants more humanization and a room |
| Round 2 room at default 0.4 | heard | owner, 2026-11-19: "Room is good at default" |
| Round 2 takes | heard, rejected | owner: "I can't hear the takes differences" |
| Round 2 humanization amount | not answered | owner has not said |
| Round 3 takes | not heard | awaiting the owner |

## As built: Kotlin round K1 (2026-11-19)

The owner heard round 3 ("This is better, continue"). K1 is the engine, its patch, registration
and probes; [CONSORT](../../CONSORT.md) has the numbers. Nobody has heard the Kotlin.

What changed against this brief:

- **The pipe is `Strings.Loop` directly, not `Bore.Player`.** `Bore.Player` is tied to
  `BoreVoice` (two voices, LIP as the reed offset) and has no closed cylinder. `Strings.Loop`
  already takes `roundTrip = 0.5` for one.
- **The section is patch data**: `ConsortPatch.chairs`, with `velocity` and `take`, as
  `ObbligatoPatch` carries its note. This settles the brief's open "chair storage" question.
- **ROOM stayed a macro**: eight controls with HOLD. The brief's alternative (land with the
  rack's ROOM) cannot seat each chair. The wash is `Room.bloom`, scaled to unit energy.
- **Two defects the port found.** The cylinder's sign flip was applied on the way back and again
  on the way in, which made a non-inverting half loop: CLARINET and DUDUK played an octave high
  (+1181 to +1190 cents) until the second flip was removed. And 12 of 180 short renders filed as
  SNARE (flatness 0.26 to 0.36 against the classifier's 0.25) because white breath noise at the
  internal rate was lifted by the body's tilt; a 3 kHz low-pass on the noise alone clears them
  (8 kHz still misfiled 2, 5 kHz none) and leaves the audible breath at its level.
- **Pitch pins are six constants**, one a voice (-5 to +6.5 cents), measured on three notes each,
  not the prototype's per-note search.
- **Not built:** a HOLD loop, presets, kit, keygroups, picker entry, round-robin pads and export,
  and the render-time work. Every acceptance gate is open.

| Claim | Status | Evidence |
|---|---|---|
| Round 3 takes | heard | owner, 2026-11-19: "This is better, continue" |
| Kotlin mechanics (pitch, overblow, odd harmonics, classes, JSON, determinism) | measured | `ConsortTest`, 16 tests; the audition's `metrics.csv` |
| Kotlin sound | not heard | awaiting the owner |

## As built: Kotlin round K2, render time (2026-11-20)

The owner heard the K1 audition and approved it: "Sounds good to me", and asked directly whether
that was after listening, "Yes, I listened; it sounds good". K1's render is the accepted baseline.

K1's audition measured a solo note at a median 109 ms and a section at 324 ms. I had written
that the player's plan was the cost; timing each stage said otherwise (plan 6 ms of a 53 ms dry
note; the body 18, the band limit 16). Kept:

- the room's size search and click response are held per ROOM value: about 12 ms a render;
- a section's players are blown side by side and seated in chair order, as BREAKER's fill does;
- the plan's slow curves are worked out every 64 samples and drawn as lines between.

Tried and undone: running the body after the decimator. Its filters are linear and under
10 kHz, and it saved 13 ms a note, but it was not the same sound. Measured on all six voices,
2 to 5 kHz fell by 0.2 (DUDUK) to 1.9 dB (SAX) against 100 to 500 Hz. The owner's approval came
before that change was published, on the K1 render, so the filters went back.

Measured on the 100-clip audition (4 cores): solo median 78 ms, section median 116 ms. Against
the K1 renders, regenerated from commit `5e6290bd`: 99 clips differ by -80 dB or less relative to
the signal and one (the SAX trill) by -50.7 dB, where the block-rate trill curve moves a finger
change by a fraction of a millisecond. No clip is byte identical. Nobody has listened for that
difference; the numbers say there is nothing to hear.

| Claim | Status | Evidence |
|---|---|---|
| Kotlin K1 sound | heard | owner, 2026-11-20: "Sounds good to me"; "Yes, I listened; it sounds good" |
| K2 render against K1 | measured | 100 clips, worst -50.7 dB, median -88 dB re signal |
