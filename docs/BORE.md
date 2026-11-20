# BORE: woodwind

BORE is SnipSnap's woodwind engine: FLUTE and SAX. Its design, measurements and
every earlier listening round are in the design record,
[2026-09-28-bore-woodwind-engine-design.md](superpowers/specs/2026-09-28-bore-woodwind-engine-design.md),
with the second research pass in [2026-11-04-bore-r2-research.md](superpowers/specs/2026-11-04-bore-r2-research.md).
This page records the owner's verdicts from 20 Nov 2026 on.

## A player on held notes, and SAX's opening (round fix-bore-1, 20 Nov 2026: KEEP)

**The notes.** On 20 Nov 2026 the owner marked every BORE catch-up clip (BE1 to
BE3) with "Holds are unnatural, they need more humanization", and SAX's eight
presets (BE2) with "Opening is a bit harsh for most." Measured on those clips, a
held note's level stayed inside 0.03 to 0.08 dB and its vibrato was one sine:
every swing the same depth to 1 or 2 percent, at 5.20 Hz to the hundredth.

**The player.** A held breath now carries a player (`Bore.HUMAN`, built from
`Humanize.Wander`): the breath drifts, so the level swells and eases and the
hiss in it comes and goes; the pitch leans a few cents and comes back; the note
is found from just under its pitch; and no two swings of the vibrato are alike.
FLUTE's breath drift scales with its BREATH macro, so a FLUTE at BREATH 0 does
not swell. Every movement repeats exactly with the loop it sits in. A held key's
loop is four seconds instead of two (`Humanize.HELD_LOOP_SECONDS`) and a
one-shot's period is four seconds too. OBBLIGATO's players ask for the old path
(`Humanize.OFF`) and are unchanged.

**SAX's opening.** Two things were measured. The output chain took the whole
note's mean off every sample, which left a step on the first sample of a
lopsided reed wave: a click 7 to 20 dB under the note. And on a pad the first
tenth of a second stood 2.2 to 4.3 dB over the note it became. SAX now leaves
the mean to the high-pass that follows and holds the level back by up to 3 dB
until the voicing has opened (`Bore.OPENING_SOFTENED`, `Bore.openingFor`): 0.1
to 2.1 dB over on a pad. The tongue, the seed and the timing are not touched.
FLUTE keeps its opening (`Bore.OPENING_AS_HEARD`).

**The round.** Clips BQ1 to BQ4: the held note before then after for each voice
(BQ1); two strengths, modest (what ships, `Humanize.MODEST`) then stronger
(1.8 times, `Humanize.STRONGER`) (BQ2); the held keys soft then hard with the
player (BQ3); and every SAX preset old opening then new, then three FLUTE
presets after (BQ4).

**Verdict.** BQ1, BQ2, BQ3 and BQ4: KEEP, no notes. The modest strength stays
as shipped, and so does the softened SAX opening.

**Pins.** `HoldHumanizePinsTest` pins every factory preset and each voice's
default note and default LOOP as they ship. The hashes from before the change
still hold with the player off and the old opening (`BoreFrozenSoundTest`).

## Candidate voices (round bore-voices-1, 20 Nov 2026: awaiting listening)

**Why.** The melodic gate asks for six voices; BORE has two. Five candidates are
built, one of them a spare, appended to `BoreVoice` after SAX so saved names
never move. `Bore.KEPT_VOICES` is what players are offered; a candidate has no
preset, kit or starter, and `BorePadMaker.VOICES` is the kept voices. Each is a
different blown instrument using a part of the valve-in-a-pipe model the kept
two leave unused (`BoreCandidates.kt`); the breath, the tongue, the player and
the held cut are the engine's own, so a kept candidate needs only to be added
to `KEPT_VOICES`.

| Voice | Instrument | What is new in the model | Range |
|---|---|---|---|
| HOLLOW | clarinet, low register | the design's closed cylinder (half-period loop, -0.95, no blocker) under the reed: odd harmonics | A2 to A4 |
| SHAWM | shawm or oboe | a double reed (table slope -0.5) on a cone, and the formant hook used for two nasal formants | G3 to G5 |
| SHAKU | end-blown bamboo flute | the jet's edge noise heard outside the pipe, and a 60 cent bend into each note | D4 to D6 |
| PANPIPE | pan flute | the jet on a closed pipe: odd harmonics, a little air | C4 to C6 |
| VESSEL | ocarina | the jet across one Helmholtz cavity in the pipe's place: one resonance | C4 to C6 |

Every candidate lets its note go over 0.25 s with the sound itself faded (the
kept voices' 80 ms release left a still-ringing pipe cut), leaves the mean to
the output's high-pass (no step at the first sample), and has no rasp or bite.
A brass lip valve (STK's brass) was tried and never locked to its note.

**Measured** (`BoreCandidateVoicesTest`): every candidate within 5 cents of the
note at its root, middle and top; the nearest third-octave distance to any
other voice is HOLLOW and PANPIPE 6.8 dB (each other at C4), SHAWM 8.6 dB,
SHAKU 4.0 dB (FLUTE at C5), VESSEL 27 dB, against the 3.7 dB bar; soft, medium
and hard differ by 0.55 to 1.4 dB of level-matched shape; a held note's level
moves 0.34 to 0.78 dB with the player on; every held key and LOOP joins. The
kept voices render bit for bit as before (16 renders pinned against 6a1c248a7).
Nobody has heard any of them.

**Risks.** SHAKU is the nearest to a kept voice (FLUTE); its difference is its
breath. VESSEL is almost a sine. At extreme corners (BREATH 0 with CHIFF 1, a
tight lip at the top) several candidates read as a drum to the classifier; the
filing itself comes from the length, as the kept voices' does.

**The round.** Clips DP (HOLLOW), DQ (SHAWM), DR (SHAKU), DS (PANPIPE), DT
(VESSEL): four notes across the range, then soft, medium and hard; DU1 is the
kept two for reference.
