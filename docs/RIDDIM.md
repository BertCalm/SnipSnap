# RIDDIM: dancehall drum kits (round 0, 2026-11-18)

`RIDDIM` is a working name. The owner asked for a dancehall-inspired collection of drum kits, built as synth
versions of 18 reference loops they shared, with drum and percussion sounds mixed on the same kits. Round 0 is a
Python voice palette and three sketch grooves on a listening page:
<https://claude.ai/artifact/AgrD8n14pMhb9aRYkVVj4b>. Python harness, not Kotlin. **Nothing has been heard.**

Prototype: [`prototype/riddim/`](../prototype/riddim/).

## The references are targets, not sources

The 18 loops are commercial sample-pack loops. Their audio is not copied, sliced or fitted: their own resonances
would otherwise ship in the app (CALABASH fitted only CC0 recordings for this reason). Instead each loop was
measured, and the voices were synthesized to those numbers. `reference_targets.json` holds the numbers; no audio
is committed. To reproduce, put the loops in one folder and run `measure_targets.py` (below).

## What the references measured (grid analysis, `measure_groove.py`)

Bar-averaged onset energy per sixteenth step in three bands. Loops in a mix overlap, so the "kick" row can carry
a bass line, and decay figures on a full mix are upper bounds.

| Group | Loops | Tempo | Measured |
|---|---|---|---|
| Clave dancehall | killawani, favelastomp groove, reasons | 102 to 123 | kick on steps 1, 4, 7, 11, 13 (3-2 son clave), straight |
| Dembow | lofi dancehall 95, fandango 120 | 95 to 120 | lofi: kick on every beat, snare on steps 4, 7, 12, 15; fandango: 3+3+2 snares and a rolling boom |
| Long boom | nasty 808 | 100 | sustained 43.3 Hz (F1, −13 cents) |
| Swung Brazilian funk | embaixodemim, ting, ratchet top, samba | 105 to 145 | off-beat sixteenths 17 ms late at 145 BPM (about 58% swing) |
| Swung West African | Dakar dusk, tama touba | 103 to 105 | pickup kick before each beat, about 61% swing |
| Wood and metal percussion | wood mallet G♭ minor, woody haze, gamelan metals D, Ruffa, Montego Bay, hello | 95 to 118 | quiet, high crest factor (20 to 31 dB), pitched, very wide (side/mid 1.3 to 1.6) |

SnipSnap renders mono, so the percussion loops' width cannot survive. Round 0 moves that motion into the sound
itself (the bronze pair beats) and into the arrangement.

## Round 0 voices (`riddim_proto.py`)

| Voice | Family | Target (reference) | Measured (Python harness) |
|---|---|---|---|
| KICK | electronic | 73 → 60 → 40 Hz over 80 ms (killawani, favelastomp) | 69 → 53 → 42 → 40 Hz at 0/30/80/200 ms |
| DEMBOW_KICK | electronic | 58 → 40 → 28 → 23 Hz (lofi dembow) | 52 → 38 → 30 → 27 Hz |
| BOOM | electronic | 64 → 50 → 43 Hz, settles on F1 (nasty 808) | 64 → 52 → 45 → 44 Hz |
| SLIDE | electronic | 76 → 67 → 55 → 43 Hz (fandango) | 73 → 65 → 56 → 47 Hz |
| RIMSHOT | electronic | near 1.0 to 1.1 kHz, −20 dB in about 32 ms | −20 dB at 42 ms |
| CLAP | electronic | 1.05 to 1.35 kHz, −20 dB in 32 to 56 ms | −20 dB at 76 ms |
| SNARE | electronic | body 207/260/295 Hz, −20 dB in about 65 ms | −20 dB at 62 ms |
| HAT_CLOSED, HAT_OPEN | electronic | tops −20 dB in 24 to 33 ms | 32 ms, 166 ms |
| WOOD | new percussion | F#3 to A4, −20 dB in 43 to 60 ms | −20 dB at 50 ms |
| METAL | new percussion | rings about 280 ms to −20 dB | −20 dB at 94 ms in its band (the high modes die first) |
| SHAKER, CLAVE, STICKS, COWBELL, CONGA, BONGO_HIGH, TAMA | CALABASH, reused | approved 2026-11-15 | unchanged |

Pitch tracks use a 93 ms window, so the "0 ms" figure averages the first 93 ms in both columns.

Velocity and round robin, measured on the new voices: the soft dembow kick and booms carry 1.7 to 3.7 dB less 100 Hz to
1 kHz energy than hard ones over 300 ms; soft hats are about 900 Hz darker in centroid; soft metal has 36 dB
less above 2 kHz. No two takes of a new voice are near-copies (the closest are WOOD and METAL at 27 to 28 dB apart;
a copy is above 40). The KICK's soft and hard 100 Hz to 1 kHz shares differ by only 0.1 dB: its velocity lives in
the click and the sweep start, and that may be too subtle. Disclosed, not fixed.

## Round 0 heard (2026-11-18)

The owner heard the round-0 page: "I think the sounds are pretty good so far". They chose the wider collection
and three kits for the first round. The three are the dancehall core; swung Brazilian funk, swung West African and
a wood and metal kit follow in the next round.

## Round 1: three kits (`kits.py`, 2026-11-18)

Listening page: <https://claude.ai/artifact/LKkSW3LNNGt25XyFZvTJTg>. Python harness; not yet heard.

| Kit | BPM | Character | A16 |
|---|---|---|---|
| Clave Dancehall | 102 | punch kick and rimshot on the clave, tight hats, shaker, claves, cowbell, four woods in F# minor | rim roll: eighths, sixteenths, 32nds, rising 3 semitones |
| Dembow | 95 | deep driven kick, snare and clap 2 semitones down, darker hats, conga, bongo, talking drum, a low metal | snare roll: two beats of sixteenths, two of 32nds |
| Boom Riddim | 100 | six tuned booms F1 to Eb2 (one a slide) in one cut-off group, bronze pairs in F minor | hat riser: sixteenths to sextuplet 32nds, rising 9 semitones |

Layout: kick A01, snare family A02, closed hat A03, open hat A04 (one choke group), A16 the roll. Each pad renders
three velocity zones (0.35, 0.65, 1.0) by four takes. The grooves play the kits the way the app deals them: velocity
picks the zone, takes cycle per zone, chokes cut with a 5 ms fade. BOOM's TUNE now spans 12 semitones each way so
one voice covers the bass line; the round-0 grooves were rescaled to keep their notes.

Measured (Python harness, `checks.json`):

- **Round robin.** No pad's takes are near-copies (a copy is above 40 dB). The closest two takes on any pad are
  36.1 dB apart (Dembow's low rim), then 34.6 dB (Clave Dancehall's rimshot); noise voices sit below 0 dB. The first
  render found the wood pads' takes identical (218 dB apart): only the mallet width varied per take, and two takes
  rounded to the same width. Each take now moves the strike point (upper modes re-weighted by up to ±2.5 dB, pitch
  by a cent or two, decay by 4%); the woods are now 27 to 33 dB apart, the metals 8 to 16 dB.
- **Velocity.** Every new voice gets darker at each softer zone (its energy share above a voice-specific split,
  first 300 ms). The punch kick's velocity was strengthened: a soft hit now starts its sweep lower and drives less
  (share above 150 Hz: −14.5, −11.3, −8.3 dB soft to hard; round 0 differed by 0.1 dB). Disclosed, not changed:
  five reused CALABASH voices (shaker, claves, cowbell, high bongo) do not darken on this measure. Their velocity is
  CALABASH's fit of real soft and hard recordings, which the owner approved by ear on 2026-11-15.

## Open, for the owner

- The kit layouts, the three rolls and the groove balance, by ear.
- Then round 2: the Kotlin port and the shelf (velocity and round robin through rendering, saving, playback and
  export), and the wider kits.

## Ledger

- **Verified:** read on `afb0fa98`: the gates (`docs/ENGINE_ACCEPTANCE_GATES.md`, `docs/INSTRUMENT_ACCEPTANCE_GATES.md`),
  CALABASH's prototype API (`calabash_proto.render`), and that no dancehall, dembow or baile kit exists.
- **Measured:** the tables above, Python harness.
- **Proposed:** every constant in `riddim_proto.py` is a shape value until heard.
- **Heard:** round 0's palette, "pretty good so far" (2026-11-18). Round 1 not yet.

## Why it is built this way

- **Electronic drums are synthesized from scratch.** Dancehall has been drum-machine music since the mid-1980s, so a
  swept-sine kick is the real instrument here, not a stand-in for an acoustic one.
- **The kick's falling pitch is the punch.** It starts near 190 Hz and falls to about 40 Hz within 80 ms. Remove the
  sweep and it becomes a sine-wave hum.
- **Drive puts the boom on a phone.** A 43 Hz note is below what phone speakers play; soft clipping adds harmonics at
  87 and 130 Hz that they can play.
- **The percussion reuses what the owner already approved.** CALABASH's fitted shaker, claves, bells and hand drums
  are mixed onto the dancehall kits; only wood and metal are new.
- **The risky decision** is mono. The percussion references get much of their character from stereo width, and the
  metal pair's beating is round 0's answer to that.
