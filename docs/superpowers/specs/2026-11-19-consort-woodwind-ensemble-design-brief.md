# CONSORT: design brief

Status: concept with a Python prototype. Nothing is built in Kotlin. Nobody has heard the
prototype yet; the owner's first listen is the next gate.
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

Done: the prototype above. Next: the owner listens to the three audition pages and says which
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
| Any statement about how it sounds | not heard | awaiting the owner |
