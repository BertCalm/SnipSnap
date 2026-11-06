# BORE round two, researched: OVERBLOW, HOLLOW, the held keygroup, RUNS OUT (2026-11-04)

On 2026-11-04 the owner asked for BORE's full round two and chose **OVERBLOW** over VIBRATO for the sixth macro. Later that day
the plan changed to research only. This document is that research. It covers the four things round two would build, in the
order the owner's brief gave them: OVERBLOW (§1), HOLLOW as a voice (§2), the held keygroup with an attack (§3), and RUNS OUT
with the one-shot instrument (§4). For each it gives the finding, a recommendation, the cost and the risk, and it ends with the
owner's decisions, "Why it is built this way" and the ledger.

The parent design is `2026-09-28-bore-woodwind-engine-design.md` ("the design" below); "Round 1.7" is its last section. The repo
was read at `e6ca59ae`. **Nothing here has been heard by anyone.** No number here comes from Kotlin unless it is quoted from the
design. The new numbers come from a Python harness, described next, and they are labelled "harness".

## How it was measured, and how far to trust it

Two Python ports of BORE's own loop, both at the house's 176.4 kHz internal rate, with BORE's constants as at `e6ca59ae`. Neither
has vibrato, the chiff's breath burst, the key thump or the output stage. They render the raw bore wave, which is where the register
question is decided.

- **The jet** (FLUTE): `Bore.Player`'s FLUTE branch on a port of `Strings.Loop` with its tuning budget (the two-tap average, the
  allpass for the fraction, the one-pole bell inside the loop, the 2 Hz DC blocker), and `JET_RATIO`, `JET_REFLECTION`,
  `END_REFLECTION`, `JET_GAIN_PER_P`, the LIP and BREATH maps and `FLUTE_PIN_CENTS` as shipped. **Cross-checks:** at the shipped
  jet ratio 0.5 it plays C4, C5 and C6 at +0.3, +1.3 and +1.1 cents with the fundamental leading and a raw rms of 1.27 (R1's weakest
  FLUTE corner read 0.91). At jet ratio 0.40 it reads +79.9 to +80.5 cents, which is the design's Appendix B8 (+79.6 to +80.1, from
  the Phase 0 Kotlin spike) to the cent.
- **The reed** (SAX and HOLLOW): `Bore.Player`'s reed branch (Smith's table with LIP as its offset, the closing pressure, BREATH on
  the measured window through `thresholdShare` and `THRESHOLD_MARGIN`, the overshoot, the pop, the tongue's seed, the turbulence)
  on STK `BlowHole`'s folded two-segment layout, so that a register vent can sit between the segments. With the vent shut it is
  the single loop BORE ships. SAX is the cone (full period, +0.95, the blocker at f0/25, the +4 cent pin). HOLLOW is Phase 0's
  closed cylinder (half period, -0.95, no blocker). **Cross-checks:** SAX at C3 with the default knobs reaches 80% of its steady
  level in 0.10 s, which is R1's figure (Round 1.4, `TONGUE_SEED`). With the vent shut, HOLLOW plays within -0.2 to +3.4 cents at
  27 corners, sharp and more so up the range, as Phase 0 found (+0.2 to +2.2 cents at 13 corners at 220 Hz).
- **A bug, found and fixed.** The first vent grid ran with the reed-side line one sample too long. That made the notes flat by up
  to 8 cents at A4 and made the full-strength vent look worse than it is. Every vent number below comes from the corrected run.
- **Readings:** a Hann-windowed FFT of the steady part (from 0.45 s on the flute, 0.8 s on the reeds). The register is the strongest
  peak's multiple of f0, and cents are against that multiple. The rms is raw.

The scripts lived in the session's scratchpad and are not in the repo. Each prototype is described closely enough above and
below to rebuild it.

## 1. OVERBLOW

### What it is on the real instruments (verified, sources at the end)

- **Flute.** The jet crosses the mouth hole and must reach the edge in step with the pipe's cycle. A player overblows by blowing
  faster and moving the lips closer to the edge, which shortens the jet's transit time against the period. The pipe is open at both
  ends and has every harmonic, so the jet locks onto the 2nd mode: **an octave** (HyperPhysics; Prairie 2011). Fletcher et al.
  (1980) measured that a higher blowing pressure shortens the transit time and moves the sounding frequency, and that the even
  harmonics depend on the lip's offset from the jet's centre line.
- **Clarinet-like closed cylinder (HOLLOW).** It has odd modes only. The register key opens a small hole near the mouthpiece
  that weakens and detunes the first resonance, so the reed locks onto the 3rd mode: **a twelfth** (UNSW clarinet pages).
  Szwarcberg et al. (2024) found that the *nonlinear* losses in the register hole decide whether the second register sounds.
  A linear model with a realistic hole never produced it.
- **Saxophone.** A cone has every harmonic. The octave key opens one of two register vents (one on the body, one on the neck,
  switched automatically from written A5). The vent weakens and detunes the first impedance peak, and the reed takes the second:
  **an octave** (UNSW saxophone pages, C#5, G#5, A5).

### What open source does (verified)

- **STK `Flute`** (`src/Flute.cpp`, `include/Flute.h`): the jet delay is the bore delay times `jetRatio` (default 0.32, control
  change 2 "Jet Delay"). Overblowing in STK means moving that ratio, the same quantity as BORE's `JET_RATIO`. STK's own 0.32 does
  not carry over: Phase 0 measured 0.32 as "unusable" in BORE's loop (B8), and the sweep below says why.
- **STK `BlowHole`** (`src/BlowHole.cpp`, `include/BlowHole.h`): Scavone and Cook (1998). It is a clarinet with a two-port
  register vent and a three-port tonehole. The vent is a pole-zero filter, `gain (1 + z^-1) / (1 + c z^-1)`, built from a 1.5 mm
  hole. `setVent(0..1)` scales its gain. Computed at 176.4 kHz: c = -0.9814, gain = -0.00929, a corner near 521 Hz, and a DC gain
  of exactly -1, so the vent is an open hole at low frequencies and nearly transparent above its corner. The header warns that
  the "register hole will have variable influence on the playing frequency".
- **STK `Saxofony`**: a "blowed string" with the reed placed between two delay lines by `Blow Position` (control change 11).
  It has no register vent and no octave key.
- **License:** STK's `LICENSE` is an MIT-style permission notice (verified, and already in `reuse-and-licenses.md`). The STK
  headers also carry a patent notice ("possibly subject to patents held by Stanford University, Yamaha, and others") that applies
  to digital waveguides generally, BORE included. Faust's `physmodels.lib` is GPL-2+ per the same table: read it, do not adapt it.

### What BORE's physics does, measured (harness)

**FLUTE, the jet ratio swept** from the shipped 0.5 toward the octave condition 0.25 (BREATH 0.6, LIP 0.5; C4 / C5 / C6):

| jet ratio | register | cents off that register | what the spectrum does |
|---|---|---|---|
| 0.50 (shipped) | 1 | +0.3 / +1.3 / +1.1 | fundamental leads, h3 -14 dB |
| 0.45 | 1 | +39.9 / +40.1 / +40.2 | |
| 0.40 | 1 | +79.9 / +80.2 / +80.5 | (Phase 0's B8, to the cent) |
| 0.36 | 1 | +114.2 / +114.2 / +114.8 | at C6 h2 to h4 within 2 dB of h1 |
| 0.32 | 1 | +151.7 / +151.8 / +152.0 | at C5 h2 is 5 dB louder than h1 |
| 0.30 | 1 / 1 / **2** | +171.0 / +172.0 / -43.9 | |
| 0.28 | 2 | -26.3 / -25.5 / -25.0 | |
| 0.26 | 2 | -8.0 / -7.1 / -6.4 | |
| **0.25** | **2** | **+1.8 / +2.0 / +2.5** | the old fundamental is 47 to 50 dB down |
| 0.22 | 2 | +28.7 / +29.3 / +29.7 | |

- **The break moves the way a player's does:** between 0.30 and 0.28 at C4 and C5, sooner at a harder breath (0.32 to 0.30 at C4,
  BREATH 1) and on a higher note (0.36 to 0.32 at C6, BREATH 1).
- **The region before the break is not a timbre, it is a pitch pull:** about 8 cents per 0.01 of jet ratio, up to 1.7 semitones
  sharp, with the level falling (rms 1.27 to 0.81) and, at C5 and C6, the upper harmonics overtaking the fundamental. Retuning
  the bore by the measured pull does not cancel it: the residual is +6 to +23 cents, under-corrected by about a fifth, and it
  differs with BREATH.
- **Register 2 at 0.25** across five notes (C4 to C6), LIP 0 / 0.5 / 1 and BREATH 0 / 1 is in tune within -2.3 to +9.5 cents at 25 of
  30 corners. **Five corners are silent:** LIP 0 with BREATH 0 at every note (raw rms 0.0005 to 0.0007). At LIP 0 with BREATH 0.2 to
  0.4 the jet speaks on a wrong mode, +231 to +242 cents above the note. The cause is the jet offset at LIP 0 (0.08), which is near
  the symmetric dead point the KDoc of `JET_OFFSET_LOW` warns about. With the jet offset at LIP 0.35's value or more, the octave
  speaks at every BREATH (rms 0.28 to 0.88, +3.5 to +5.9 cents). LIP 0.25 at BREATH 0 is still weak at C4 (rms 0.012).
- **A break mid-note** (the jet switched from 0.5 to 0.25 at 0.6 s): the octave leads after 0.19, 0.15 and 0.02 s at C4, C5 and C6.
  The largest step around the switch is 1.01, 1.39 and 1.34 times the note's own steepest ordinary step: an ordinary step at C4,
  and over the 1.1 that `BoreLoopFuzzTest` allows a LOOP's join at C5 and C6, so a switch ramped over a few milliseconds is worth
  trying. After the break the note sits at +1.8 to +2.5
  cents with rms 0.97.

**The reeds, a register vent** (the vent at a third of HOLLOW's bore, the 3rd mode's pressure node; at a quarter of SAX's, because
BORE's cone is a closed-closed equivalent whose 2nd mode has its node there; half strength; the vent's corner at a multiple of the
note, 2 f0 on HOLLOW and 1.5 f0 on SAX; three notes by three LIPs by three BREATHs):

| | HOLLOW (A2, A3, A4) to the twelfth | SAX (C3, C4, C5) to the octave |
|---|---|---|
| lands on the target register | 27 of 27 | 27 of 27 |
| cents off the register | +0.1 to +3.2 at A2 and A3; +4.2 to +14.0 at A4 | -5.5 to +1.1 |
| level against the same note with the vent shut | 0.54 to 0.94 at A2 and A3; 0.45 to 0.65 at A4 where it speaks | 0.52 to 1.04 where it speaks |
| barely speaks (raw rms under 0.1) | 4 corners, all at A4: LIP 1 at every BREATH, and LIP 0.5 at BREATH 0 | 2 corners at C5 with LIP 1 |

- **The register is a switch, not a slope.** Five percent of the vent already breaks five of the six default notes (SAX C5 breaks at
  ten percent; at five it plays register 1, 19 cents sharp, at half level). There is no region where the note thins and bends
  before it jumps.
- **At half strength the upper register is the only stable state.** With the tongue's seed at the fundamental, at the register,
  or with no seed at all (CHIFF 0), every default note lands on the same register, the same pitch to 0.1 cent and the same level.
  **At full strength the vent makes new modes at the top of each range:** HOLLOW A4 goes to 1.93 f0, SAX C5 to 1.42 f0 (+608 cents),
  and the register then depends on the seed. Strength 0.35 and 0.5, and the two corners tried per voice, all land 27 of 27.
- **STK's own vent, one hole for every note** (a fixed 521 Hz corner), breaks correctly only where 521 Hz falls between the
  fundamental and the register: HOLLOW A2 and A3, SAX C4. It fails at HOLLOW A4 (-270 cents, a wrong mode), SAX C3 (-306) and SAX C5
  (+469). Across a two-octave TUNE range the vent has to be sized to each note. That is an analogy, not a model: a real saxophone
  has two vents and a clarinet one, and the 2024 paper above says a linear model with a realistic hole would not break at all.

### What each voice needs (verified by reading `Bore.kt`; proposed)

- **FLUTE:** a second jet ratio. The jet line in `Bore.Player` already holds a fractional delay. Register 2 needs the jet at 0.25 of
  a period (switched, never swept), a register-2 pin of about -5 cents, and LIP remapped to 0.35..1 so the silent and wrong-mode
  corners are never reached. Where a break happens mid-note, the jet line is read at the new transit time from that sample on.
- **SAX and HOLLOW:** a vent, which BORE's reed does not have. The reed's single round-trip loop has to become two segments with the
  vent between them, STK `BlowHole`'s layout. That is a second loop structure inside `Bore.Player`, kept off the shipped path
  (below). It also needs each vented voice's own pin, and a fix for the top of each range at a tight lip (lift the bell's cutoff
  while overblown, or narrow LIP there).
- **Pitch.** An overblown note sounds an octave (FLUTE, SAX) or a twelfth (HOLLOW, +19 semitones) above TUNE's note. `SynthRoot`
  maps a `BorePatch`'s TUNE straight to a MIDI note (`shell/.../SynthRoot.kt:293`). So does any keygroup built from TUNE: the held
  keygroup, and `LoopPadMaker`, which offers BORE FLUTE and SAX today. Each would put an overblown zone's root 12 or 19 semitones
  away from what it plays, unless the root follows the register.

### The knob's shape: the physics gives two states, so the knob has to come from somewhere else

House rules: each macro is audible at low and middle settings, OVERBLOW 0 is the default and a true no-op, there is no silent
corner, and SCRAMBLE never lands on a broken discontinuity. Measured: neither mechanism has a usable middle. Three shapes fit the
rules to different degrees (proposed):

- **A, the crack (recommended).** OVERBLOW sets *when* the note breaks into the upper register. At 0 it never breaks. Above 0 it
  breaks at a point in the gate that moves from late (a crack near the end of a long note) to the attack itself (the whole note
  overblown) as the knob rises: the gesture "blown harder until it breaks". Every setting above 0 renders only stable states and
  one measured transition (0.02 to 0.19 s on FLUTE), so SCRAMBLE can roll it. For a LOOP and a held keygroup zone, the break is
  clamped before Round 1.7's 1.5 s stretch start, so their loops are always a steady register and the guards stay valid. The break is a
  time inside the gate, not a length, so `renderFrames` and `drumClassFor` do not move.
- **B, a two-state switch.** Below 0.5 the low register, at 0.5 and above the upper one. Cheapest, but the bottom half is a dead
  knob, which breaks "every macro changes the sound" at low settings.
- **C, a harmonic fingering.** The bore is lengthened by the register interval so the overblown note sounds at TUNE's pitch. Then
  OVERBLOW is a timbre knob with a step in it: the same note played on its upper register (purer on FLUTE, the twelfth's tone on
  HOLLOW). This keeps every keygroup root honest. It is still a two-state step, just at constant pitch.

A blend of two renders (register 1 and register 2 mixed by the knob) is rejected: two registers sounding together is an organ
stop, not an overblow.

### Risks

- **The no-op at 0 is guarded already, and hard.** `ObbligatoCompatibilityTest` freezes SHA-256 hashes of OBBLIGATO's air (FLUTE
  `Bore.Player`) and reed (SAX `Bore.Player`, `Bore.condition`) sources and their final PCM_24 export (verified). Any change to the
  default path's float arithmetic fails it. So OVERBLOW and the vent must sit behind a branch that the default never enters, and
  BORE's own presets need a frozen hash test of the same kind, landed *before* the macro (`ArcoFrozenR1cTest` is the pattern).
- **The presets' JSON changes even though their sound does not.** `BorePresetsTest` requires every preset to name every macro
  (verified), so all 16 presets gain `"OVERBLOW": 0`. A saved recipe without the key is filled with 0 by `settled`, so old pads
  render as before.
- **SCRAMBLE.** `Dsp.scrambleNear` maps the seed map in key order (verified, `Dsp.kt:149`). With OVERBLOW last in `macrosFor`, the
  draws for the first five macros are unchanged. Cap it as HOLD is capped, or leave it free under shape A.
- **The vent is an analogy.** It is sized per note, and it breaks too easily compared with a real instrument (five percent). The
  half-strength setting rests on 54 corners and six notes. The top of each range at a tight lip is weak, and the full vent makes
  wrong modes, so a test has to pin the strength.
- **A mid-note vent opening has not been measured** (the flute's jet switch has). Nor has any LOOP join of an overblown note under
  Round 1.7's guards.
- **Keygroup roots** (above): an overblown patch on a key plays 12 or 19 semitones from its root unless the maker shifts it.

### Recommendation and cost (proposed)

| Voice | Recommendation | Cost | Risk |
|---|---|---|---|
| FLUTE | Build it: the jet switched from 0.5 to 0.25, a register-2 pin near -5 cents, LIP floored at 0.35 while overblown, shape A | about a day: `Bore.kt` +60 to 90 lines behind a branch, tests +150 (the frozen preset hashes first, then lands-on-the-octave, in tune, every corner speaks, bounded, the break time, scramble), 16 preset entries, an audition section | low: the octave is in tune within 2.5 cents at LIP 0.5; the silent corner is known and avoidable |
| HOLLOW | Build it with the voice (§2), on the same vent as SAX: it is the instrument a register key belongs to | the vent structure is shared: about 2 to 3 days for both reeds, `Bore.kt` +150 to 250 lines, tests +200 | medium: the twelfth at A4 sits 4 to 14 cents sharp and is weak at a tight lip |
| SAX | Build it with HOLLOW's vent if the owner wants the sax's octave; otherwise leave SAX out of `macrosFor` for OVERBLOW (the list is per voice), so there is no dead knob | the shared cost above, plus SAX's own pin (about +4 cents) | medium: 2 weak corners at C5, and an analogue vent the owner should judge by ear |

If only one round of OVERBLOW is affordable, build FLUTE first: it is the one the design already called cheap, and it needs no new
loop structure.

## 2. HOLLOW as a voice

**What the design and Phase 0 recorded (verified, design "Voices" and Appendix B):** a single reed on a closed cylinder, `roundTrip
0.5`, reflection -0.95, no DC blocker. In the Phase 0 Kotlin spike it held ±2 cents at all 13 speaking corners at 220 Hz, played +0.7,
+1.3 and +1.9 cents at 110, 220 and 440 Hz at LIP 0.5, had odd harmonics 39 to 43 dB over the even ones, a threshold at BREATH 0.35 at
LIP 0.5 and a LIP that darkens monotonically. BREATH's centroid is flat once the note speaks. "The best-measured shape", R2's first
voice, rooted at A2 (MIDI 45, the chalumeau) in the design.

**Measured now (harness, with R1's mappings, which Phase 0 predates):** HOLLOW with the vent shut speaks at all 27 corners (A2, A3,
A4 by LIP 0 / 0.5 / 1 by BREATH 0 / 0.6 / 1) with a raw rms of 0.23 to 1.14 (BoreTest's floor for SAX is 0.12). It plays -0.2 to +3.4
cents, sharp and more so up the range and at a loose lip, with no pin applied. The even harmonics sit 35 to 77 dB under the
fundamental and the 3rd 10 to 22 dB under it: a closed pipe's odd series. LIP tightens it toward a purer tone (h2 at A3 BREATH 0.6:
-46, -53, -62 dB at LIP 0, 0.5, 1). With the tongue's seed it speaks faster than SAX at the same register: A2 in 0.07 s at the
default knobs (SAX at C3: 0.10), A3 0.05, A4 0.04. At BREATH 0 it takes 0.17 s at A2 and 0.06 s at A4. So the design's A2 root is
not ruled out by onset, which is why SAX's root moved up an octave in R1.

**What shipping it needs** (verified against the code at `e6ca59ae`; a checklist, not a design):

1. `BoreVoice.HOLLOW`, appended **last**: `BoreLoopFuzzTest` draws its random corners in `BoreVoice.entries` order, so FLUTE's and
   SAX's draws stay put.
2. `Bore.Player`: a third loop shape (`Strings.tune(..., roundTrip = 0.5)`, `Strings.Loop(..., fb = -LOOP_GAIN, dcBlock = false,
   roundTrip = 0.5)`). `Strings.Loop.retune` already carries `roundTrip` (R0), so the vibrato works unchanged. The FLUTE and SAX
   arithmetic must not move (the OBBLIGATO hashes above).
3. `rootMidi` (an exhaustive `when`, so the compiler finds it), a `tunedHz` pin (about -1.5 cents from the harness, re-measured in
   Kotlin), and `thresholdShare`, which is the cone's fit. The cylinder speaks lower (Phase 0: 0.60 of the closing pressure, where the
   fit says 0.71), so BREATH 0 sits higher above threshold than it must. It still speaks everywhere.
4. **The voicing is the real open question.** `biteBoostDb`, `raspAmount` and `voicingFor` are `if (FLUTE) ... else reed`, so
   HOLLOW silently inherits SAX's tenor-saxophone voicing (fitted to UNSW's tenor recordings, Round 1.3), its bite and its rasp. A
   clarinet radiates differently (the tone-hole lattice's cutoff is lower and the even harmonics come back above it). Either HOLLOW
   ships dry of all three until a clarinet fit is measured the way Round 1.3 measured the tenor, or it borrows SAX's on purpose and
   the audition says whether that is a clarinet.
5. `BorePresets.forVoice` (exhaustive) and presets. `BorePresetsTest` requires exactly eight per voice (verified), so four
   provisional presets mean changing that test, or eight. Like the R1 roster, they are authored blind and marked provisional.
6. `BoreTest`'s per-voice maps (`mapOf(FLUTE to ..., SAX to ...).getValue(voice)` throws for a third voice), `SynthKits.bore()` (a
   fixed 16-pad FLUTE and SAX layout: HOLLOW is not on the kit unless the owner wants it there), an audition section, the
   `DeterminismTest` canary (it iterates `BoreVoice.entries`, so HOLLOW is covered by construction), and a LOOP check: Round 1.7's
   1.5 s floor and 0.9 correlation were measured on FLUTE and SAX only.
7. **The phone shows it at once.** `SynthScreen` lists `BoreVoice.entries` (verified, line 5408), so the next app build puts HOLLOW
   in the picker before anyone has heard it. GLINT's precedent is "a voice that has not passed gets no picker entry", so the app
   session has to filter it, or the owner accepts it unheard.

**Cost:** about a day for the voice with four presets, its tests and an audition section, plus the voicing decision. **Risks:** the
voicing (4); a LOOP that has not been measured; and that a "clarinet" built on a tenor's voicing reads as a muffled sax. That is
the design's own "hollow or nasal" audition question 7.

## 3. The held keygroup, with an attack

**The construction (verified by reading `Bore.kt`; the code is on this branch, uncommitted, see below).** Each zone is the held
note `Bore.renderLoopMeasured` cuts its LOOP from: the one-shot's own blow with its gate stretched to 4.5 s and HOLD at `LOOP_HOLD`
(vibrato about 10 cents), conditioned like the one-shot. The zone plays it from its first sample, so the attack is the note's own.
Round 1.7 closes a LOOP with its blend at the loop's *start* (the first 0.3 s fade in from what follows the stretch's end). That
cannot work after an attack: the first pass from the attack into the loop would jump to the continuation. So the blend sits at the
loop's *end*. The reference block starts where Round 1.7's stretch would (`loopStart`: settled, and no sooner than 1.5 s), the loop
starts 0.3 s later, and the loop's last 0.3 s fade into the 0.3 s just before the loop start.

**Why the guards carry over exactly (verified by reading).** The length search is the same call with the same arguments:
`bestLength(x, s, blend, low, high)` correlates `x[s, s + blend)` with `x[s + L, s + L + blend)` in both. So for the same macros
the zone's loop has the **same length and the same join correlation** as the LOOP preset. The blended samples are the same two
waves under the same weights, shifted by one index (`(i + 1) / blend` here, so the last frame is exactly the loop start's
predecessor). Turned so its blend comes first, the zone's loop is Round 1.7's loop before its rotation onto a crossing, and
`blendDipDb` reads the same shape. In other words, **the zone's loop is the LOOP preset's cycle, rotated to start where the attack
arrives.** Round 1.7's measurement of 1,071 corners therefore applies: the weakest correlation 0.984 against a floor of 0.9, the
deepest dip 0.49 dB against a limit of 1 dB. The refusal is the same `checkJoin`, by name. The wrap is two consecutive frames of the
note: the last frame is `cos(π/2) / k` times the loop's own wave plus `sin(π/2) / k` times the frame before the loop start, with
k = 1 there. So there is no step guard to add, only a test that pins it.

**What does not carry over, and was not measured:**

- **The attack is the stretched one-shot's, not a byte-identical copy of a pad's one-shot.** The raw blow is identical over their
  shared prefix (same seed, same envelope, same vibrato depth). The conditioning differs a little: the rasp's reference loudness and
  the voicing's ceiling are read over the whole note, and so is the output mean. Expected correlation near 1; not measured.
- **The level.** The zone is levelled on its loop, with the attack wherever it falls, under a 0.99 peak ceiling (RESIN's held-pad
  rule). Where the attack overshoots (SAX near C3, Round 1.7's +1.08 dB), the ceiling can pull the loop under the target. Not measured.
- **The soft layer.** Two BREATH layers: the patch's BREATH as the hard layer and 0.45 of it as the soft one. BREATH is mapped onto
  the speaking window, so BREATH 0 is the softest note that speaks and a soft layer can never be a hole. Round 1.7's 1,071 corners
  include BREATH 0 at the cube's corners and random BREATHs in the fuzz, but the full grid (9 zones by 2 layers by 2 or 3 voices) is a
  new measurement. A soft layer speaks slowly at the bottom of SAX's range: 0.31 s at C3, BREATH 0, CHIFF 0.4 (harness). That is how
  a real soft note behaves, but a keyboard player will feel it.
- **The loop starts at 1.8 s or later.** A key held for less than that plays only the note's own audio. That is correct, but it
  means the loop is reached only on long notes.
- **What the guards cannot see** is Round 1.7's open question, unchanged: the breath does not repeat across the two passes, so it
  dips about 3 dB for the 0.3 s of the blend once every 1.9 to 2.1 s, and the same breath comes round every loop. That is the R2
  gate's own question: whether breath repeats audibly at the loop period.

**Cost (estimated, proposed):** 18 renders per instrument (9 zones, 2 layers) at Round 1.7's 327 to 464 ms each comes to 6 to 8 s on
the JVM, and 12 to 33 s on a phone at the DSP review's 2 to 4 times factor (no phone measured). Each sample is about 3.7 to 3.9 s
(1.8 s plus a 1.88 to 2.12 s loop), about 0.5 MB at PCM_24 (the package writer's depth, verified), so about 9 MB per two-layer
instrument. Code: `BoreKeys.kt` about 200 lines (written, uncommitted), a `BorePadMaker` in `:shell` about 150 lines in
`BellowsPadMaker`'s layered shape, and tests about 250 lines.

**Risk:** low on the join, because it is Round 1.7's join. Medium on the feel: the soft layer's onset and the loop starting late.

## 4. RUNS OUT and the one-shot instrument

**RUNS OUT** (the design: an 8 s unlooped zone as an instrument-sheet option):

- **The 8 s gate is past HOLD's ceiling** (`HOLD_MAX_SECONDS` = 4), so it cannot come from the macros. It uses the blow's internal
  `gateSeconds` override, which the LOOP already uses. The vibrato depth has to be chosen: `LOOP_HOLD`'s 10 cents, to match the
  looped zones, or HOLD's top for 20.
- **Size:** 8 s by 44,100 frames by 3 bytes = 1.06 MB per sample (the design's figure, verified as arithmetic). With two layers
  that is about 19 MB per instrument, twice the looped one.
- **Render:** about 1.4 M raw samples a layer, so about 0.65 s by scaling Round 1.7's timing, and about 12 s per instrument on the
  JVM (estimated).
- **Level** it on its held middle, as the looped zones are, not with `levelTo`'s loudest 200 ms (often the attack), or RUNS OUT
  will sit at a different loudness beside them.
- **A literal running-out breath** (the pressure sagging to the threshold at the end) is not recommended for round two. The
  threshold is a bifurcation, and a reed dying there can squawk or drop out in one period. The 80 ms release is the honest first
  version.
- **The phone and the MPC:** `loopStartFrame` 0 means "no loop" in a keygroup layer, and the package writer passes 0 through
  (verified, `OneNote.kt`). But `BellowsPadMaker.assemble` *requires* a loop. A RUNS OUT maker must not copy that check.

**The one-shot instrument** (`Keys.bore` in `Keys.fork`'s shape, and `InstrumentSuite.renderBore`):

- **TUNE needs no inverting:** `Bore.frequencyFor` already lands on `Keys.midiHz` for every semitone of a voice's range. Only the
  pin is applied on top. Zones outside a voice's two octaves are the MPC's transposition (the edge zones reach nine semitones past).
- **`Keys.fork` levels by peak** (`Dsp.normalize`). BORE's house level is loudness (`Dsp.levelTo` to the melodic target), and two
  BREATH layers levelled by peak will not match by ear.
- **`InstrumentSidecar.describe` throws for any program without a recipe** (verified, "no recipe on record"), so `renderBore` needs
  its `RECIPES` entry in the same change. `InstrumentSuite.renderAll` feeds `SessionProjectGenerator`, so a BORE instrument belongs
  in the generator's own list, not in `renderAll`.
- **The design itself calls a keygroup of BORE one-shots a failure on its own** ("a flute that stops under a held key"), so it ships
  only beside the held path, never alone.
- **Tape.** A BORE pad lands with TAPE (`landingChain`), but an instrument's samples are dry. A one-shot instrument could carry TAPE;
  a looped zone cannot, because wow at 1.3 Hz does not ride a loop wrap (the reason a LOOP lands dry). An instrument made from a
  tape-flute preset will sound unlike its pad unless the owner decides otherwise.

## The uncommitted code on this branch

Written for §3 before the plan changed. Left uncommitted on purpose, **not compiled and not tested** (the one build that ran
compiled the tree before these edits):

- `synth/src/main/kotlin/com/snipsnap/synth/Bore.kt` (modified): `renderLoopMeasured`'s held-note render pulled out into
  `heldNote(voice, m)` and `heldUsableEnd(x, m)`, so the LOOP and a keygroup zone are cut from one render path. It is meant to be
  behaviour-preserving: the same calls in the same order.
- `synth/src/main/kotlin/com/snipsnap/synth/BoreKeys.kt` (new): `padMidis`, `layerBreaths` (soft = 0.45 of the patch's BREATH),
  `pad` (two layers, soft first), `padLayer`, `padMeasured`, `zoneMacros`, `cutHeld` (the end-of-loop blend with Round 1.7's guards,
  refusing by name), `assembleTail`, and a loop-region levelling helper.
- A RUNS OUT draft that was never added to the repo.

## Decisions for the owner

1. **OVERBLOW's shape:** A, the crack (a break whose time the knob sets: recommended), B, a two-state switch with a dead bottom
   half, or C, a harmonic fingering at constant pitch.
2. **Which voices get OVERBLOW in round two:** FLUTE only (cheapest, measured in tune), FLUTE plus the reeds' vent (HOLLOW's twelfth,
   SAX's octave: two to three more days, an analogue vent), or FLUTE now and the vent in round three. For a voice without it, the
   knob is left out of that voice's `macrosFor` rather than shipped dead.
3. **Does an overblown note move the pitch** (the physical gesture: +12 or +19 semitones) **or hold it** (shape C)? If it moves,
   `SynthRoot` and every keygroup maker must follow the register, or refuse an overblown patch.
4. **HOLLOW's voicing:** dry until a clarinet is measured, or SAX's tenor voicing on purpose.
5. **HOLLOW's root:** A2 as the design says (the onsets allow it: 0.07 s at the defaults), or D3 (a soprano clarinet's lowest note).
6. **HOLLOW in the phone's picker before its audition**, or filtered until heard (GLINT's precedent).
7. **The soft layer's breath:** 0.45 of the patch's BREATH (proposed), or fixed layer breaths whatever the patch says.
8. **RUNS OUT's vibrato:** the looped zones' 10 cents or HOLD's top 20. Also whether a one-shot instrument carries TAPE.

## Why it is built this way

- **The register is a switch in the physics, so the knob has to be a gesture.** Both mechanisms measured that way: the jet's
  in-between region only pulls the pitch sharp, and the reed breaks at five percent of a vent. A knob that sweeps through either
  would spend most of its travel on a sound nobody wants. Making the knob *when* the note breaks keeps every setting on a stable
  sound and puts the one interesting moment, the crack, under the owner's hand.
- **FLUTE first, because its upper register already exists in the loop.** Halving the jet's transit time is one number in a delay
  line BORE already has, and the octave came out within 2.5 cents. The reeds need a new structure inside the bore. That is where the
  cost and the risk are.
- **The vent is half open and sized to the note, because the full one lies.** A fully open vent made modes no instrument has (1.42
  and 1.93 times the note) and let the attack decide the register. At half strength the register stopped depending on the attack at
  all, which is the property that keeps a render stable and SCRAMBLE safe.
- **The held keygroup reuses Round 1.7's join rather than inventing one.** Moving the blend to the loop's end keeps the attack the
  note's own, and keeping the length search's arguments the same means the 1,071 corners already measured still apply. The risky
  decision is the soft layer, whose slow onset at the bottom of SAX's range is physics a player will feel on a keyboard.
- **Nothing ships to the default path.** OBBLIGATO's frozen hashes already pin FLUTE's and SAX's arithmetic, so OVERBLOW and HOLLOW
  live behind branches that the default never enters, with a BORE preset hash test landed first. That is what makes "OVERBLOW 0
  changes nothing" provable rather than hoped for.

## Ledger

**Verified** (read in the repo at `e6ca59ae`, or from a cited source):
- `Bore.Player`'s jet line and `JET_RATIO` = 0.5; the reed's single round-trip loop with no vent; `SynthRoot.kt:293` maps TUNE to MIDI
  for a `BorePatch`; `SynthScreen` lists `BoreVoice.entries`; `BorePresetsTest` wants eight presets per voice, each naming every
  macro; `Dsp.scrambleNear` maps in key order; `ObbligatoCompatibilityTest` hashes the FLUTE and SAX players and `Bore.condition`;
  `InstrumentSidecar` throws without a recipe; `OneNote` writes PCM_24 and passes a loop start of 0; `BellowsPadMaker` requires a
  loop; `bestLength`'s call is the same for the keygroup zone and the LOOP.
- STK `Flute` (jet delay = bore delay times `jetRatio`, default 0.32, CC 2), `BlowHole` (two-port register vent, 1.5 mm, `setVent`),
  `Saxofony` (blow position, no vent); STK's license is MIT-style.
- The physics of each instrument's overblow (flute: octave by jet speed and distance; clarinet: twelfth by a register hole;
  saxophone: octave by two automatic vents), from the sources below.

**Measured** (the Python harness, not Kotlin):
- FLUTE: the jet-ratio table above; register 2 at 0.25 in tune -2.3 to +9.5 cents at 25 of 30 corners; silent at LIP 0 with BREATH 0;
  a wrong mode at LIP 0 with BREATH 0.2 to 0.4; speaks at LIP 0.35 and above; a mid-note break leads in 0.02 to 0.19 s with a step
  of 1.0 to 1.4 times the steepest ordinary step; pull correction leaves +6 to +23 cents.
- The reeds: the vent table above; the register switch at five percent; seed independence at half strength; wrong modes with the
  full vent; the fixed-corner vent fails at three of six notes.
- HOLLOW with the vent shut: 27 of 27 corners speak, -0.2 to +3.4 cents, odd harmonics, LIP darkens; onsets 0.04 to 0.07 s at the
  defaults, 0.06 to 0.17 s at BREATH 0. SAX's onset at C3 (0.10 s) reproduces R1.

**Proposed:** the knob shapes A, B and C; the register-2 pins (-5 cents on FLUTE, about +4 on SAX, about -1.5 on HOLLOW); the LIP
floor while overblown; the vent's position, strength and per-note corner; 0.45 for the soft layer; every cost and render-time
estimate; HOLLOW's root and voicing options.

**Heard:** nothing. Every question about how any of this sounds, whether the crack is a gesture or a mistake, whether HOLLOW
reads as a clarinet, and whether the breath repeats at the loop period, is the audition's.

## Sources

- HyperPhysics, "Overblowing the Flute": <https://hyperphysics.gsu.edu/hbase/Music/flute4.html>
- N. H. Fletcher et al. (1980), hosted by UNSW Music Acoustics: <https://newt.phys.unsw.edu.au/music/people/publications/Fletcheretal1980.pdf>
- C. Prairie (2011), back pressure and the jet (Flutopedia): <https://www.flutopedia.com/refs/Prairie_2011_BackPressure.pdf>
- UNSW Music Acoustics, clarinet (register holes, G6): <https://www.phys.unsw.edu.au/music/clarinet/G6.html>
- UNSW Music Acoustics, tenor saxophone C#5, G#5, A5: <https://newt.phys.unsw.edu.au/music/saxophone/tenor/Csharp5.html>,
  <https://www.phys.unsw.edu.au/music/saxophone/tenor/Gsharp5.html>, <https://newt.phys.unsw.edu.au/music/saxophone/tenor/A5.html>
- N. Szwarcberg, T. Colinot, C. Vergez, M. Jousserand (2024), "Second register production on the clarinet: nonlinear losses in
  the register hole as the decisive physical phenomenon", arXiv:2404.07540: <https://arxiv.org/abs/2404.07540>
- STK (Cook and Scavone): <https://github.com/thestk/stk>: `src/Flute.cpp`, `include/Flute.h`, `src/BlowHole.cpp`,
  `include/BlowHole.h`, `src/Saxofony.cpp`, `LICENSE`
- G. P. Scavone and P. R. Cook (1998), "Real-time computer modeling of woodwind instruments" (cited by `BlowHole.cpp`; not read here)
