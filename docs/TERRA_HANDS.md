# TERRA's hand drums: SLAP, DAYAN, DARBUKA, DOUM, DJEMBE, DHOLAK and BAYAN

Three TERRA voices built on 2026-11-07, one per pad, to replace the TERRA kit's
djembe slap (A12), tabla tin (A09) and dumbek tek (A11). The code is
`TerraHands.kt`, with `Bessel` for the drumhead's modes; `Terra.kt` routes the
voices there, as it does BOX ([TERRA_BOX.md](TERRA_BOX.md)).

The characteristic gesture: **the hand (or a fingertip) lands near the rim →
its contact drives the head's own modes, hundreds of them → the drum's body
answers (a djembe's bowl, a dayan's loaded harmonics, a darbuka's shell) → the
upper modes die fast → silence.**

## Why

The owner heard all three pads, on COMPOUND_MEMBRANE, as a "Drum machine"
(https://claude.ai/artifact/SuVE4Hed1heDrm4Fa1Gs7s). That is the cajón's
problem again ([TERRA_BOX.md](TERRA_BOX.md)): a few pure sines with the strike
pulse mixed beside them. Measured as spectral flatness over the first 40 ms
(0 is a pure tone, 1 is white noise), the three read 0.03 to 0.08, against
0.06 for the cavity slap the owner called a rimshot. The same page found
FORCE's top end did not sound like a harder hand.

## Three rounds, heard

All three rounds were Python prototypes (the Luthier harness), with every clip
levelled to one loudness.

1. **Round 1** (https://claude.ai/artifact/Byxg2AzHff9vbNJZoRTwyc): each drum
   as about 900 driven modes (A), or about 340 plus the skin's crack (B).
   Heard: "Neither yet" on all three. Asked what was wrong: the djembe "too
   thin, no body" and "synthetic"; the tabla's attack and ringing wrong
   ("like a bell or a synth") and synthetic; the dumbek "missing the ring"
   and synthetic.
2. **Round 2** (https://claude.ai/artifact/NtRAY7NK1ry159EzSAXHKX): a hand
   with a size and a sequence, a head that is not ideal, and the drum's body.
   Three directions per drum. Heard: the djembe's A ("full body"); the tabla
   "None yet - Sharper attack with some resonance"; the dumbek "None yet - C
   but with a bit of resonance".
3. **Round 3** (https://claude.ai/artifact/L4ghYZQ69mjzSP4FoHg8pu): the tabla
   and the dumbek aimed at those notes, and the djembe at three strengths.
   Heard: the dumbek's B (ceramic, more ring); the djembe soft, medium and
   hard "Yes", a softer hand on the same drum; the tabla "C but tighter, more
   forceful snap", built in at three settings for the app's listening page.
4. **The app** (https://claude.ai/artifact/44wk4wFgMPjVxXCkkG84ZU): the three
   Kotlin voices beside the prototypes picked. Heard: the dumbek "Same drum";
   the tabla's setting 3 (FORCE 0.9, CLICK 0.65), now the kit's and the
   voice's defaults; the djembe "Close, but not it"; soft and medium hits
   "Can't tell". That page said every clip played at one loudness, but it did
   not: the app's clips were levelled by `AuditionLevel` and the prototypes by
   the harness's `level_to`, which left the app's 5 to 9 dB quieter (djembe
   5.2, dumbek 7.8, tabla 8.6 dB, as RMS). A quieter clip reads thinner. The
   three tabla settings were levelled alike, so that pick stands. The djembe
   went back to the owner with every clip on one meter (`Loudness.of`):
   https://claude.ai/artifact/DaDn9mYg8ajVCjWS6p5nnD. Heard there: "Sounds
   consistent now". The djembe needed no change; the level did.

## The model

Literal, from physics, then design shapes:

- **The head.** Mode (m, n) sits at `j_mn / j_01` times the (0, 1) mode,
  where `j_mn` is the n-th zero of the Bessel function J_m. TUNE sets the
  (0, 1) mode. `Bessel.j` evaluates J_m by Bessel's integral, which the
  trapezoid rule converges on exponentially. The zeros are bracketed and
  bisected, and cached.
- **Every mode under 12 kHz** is used, m to 30 and n to 18: about 900
  resonators. Each is driven by the contact. Each decays as
  `T60 = DECAY's T60 * (f01 / f)^tilt`, floored at a few ms. A mode runs only
  until it has decayed 133 dB, which makes the bank 5 to 50 times cheaper
  than running every mode to the end.
- **A hand is not a point.** Each contact covers a strip of the head, so a
  mode's weight is its shape `J_m(j_mn r)` averaged over the strip, which
  gives the low modes back. A djembe slap lands the palm's heel at the rim,
  then the fingers 2.5 and 3.6 ms later, further in.
- **A real head is not ideal.** Every (m > 0) mode is a pair, split by uneven
  tension by 0.2 to 0.5%, which beats. The djembe's air loading lowers its
  first three modes by 15, 8 and 4%.
- **The drum's body.**
  - SLAP's bowl answers in air at 72 Hz, driven by the head's low-passed
    motion, and its wood knocks at 410, 760 and 1130 Hz.
  - DARBUKA's ceramic shell rings at 1380, 2310 and 3420 Hz.
  - DAYAN's syahi loads the head into six near-harmonic tones. Each tone is a
    split pair a hair off its harmonic, over the kinar click: the rim
    strike's own dense modes, dead within ~18 ms.
- **FORCE.** The contact is a half-sine of constant area, the same push at
  any FORCE, from 2.6 ms soft to 0.3 ms hard. A harder hand is a shorter
  contact, which reaches higher without losing the low end. COMPOUND_MEMBRANE's
  palm pulse keeps a fixed peak instead, so its harder hand pushes less, which
  is why its FORCE dulls at the top ([STRIKE_VELOCITY.md](STRIKE_VELOCITY.md)).

Analogy, not physics: the hand's smack, the fingertip's snap and the dayan's
tick are band-passed noise of a few ms beside the drum.

Not simulated: air loading beyond the djembe's first three modes, and the
hand's damping after contact.

## Velocity, takes and HIT

- **Velocity** is the contact's own law, as on BOX. A softer hit is a longer
  contact (up to 1.2 ms more) with less push. The noise falls as v^1.5, and
  the bowl and shell are driven in proportion. The modes are not tilted by
  `StrikeVelocity.modeFactors`, because the longer contact already does that.
- **Re-struck takes** re-seed the hand's noise. The head's split pairs are
  the drum's own and stay fixed.
- **HIT** colours the head's modes (SLAP, DARBUKA) or the dayan's tones.
  Under HIT, the stages after the head are scaled by the unstruck drum's
  levels, as on BOX.
- **Output stage:** the house one (`TerraBox.finish`), with no PUNCH.

## The noise bursts are scaled by energy, not peak

The dumbek's snap first came out 2.8 times weaker in Kotlin than in the
prototype. A burst this short is a few dozen samples, so one noise
realisation's peak, against its energy, varies by up to ±40% from seed to
seed (40 seeds measured). Normalising to the peak therefore let the noise
generator's luck set the snap's level, and would have varied it from take to
take. Each burst is now scaled to the RMS of the one the owner heard (smack
0.2045, snap 0.2486, tick 0.2995).

## Measured: the app against the prototype

Each clip is the WAV as heard, trimmed to its onset (the first sample above 1%
of its peak). Flatness is spectral flatness over the first 40 ms (Hann
window); centroid is the magnitude centroid over the first 30 ms (Hann); ring
is the time from the envelope's peak to -30 dB; "first 5 ms" is the share of
the first 5 ms's power above 3 kHz. The table was re-measured this way on
round 4; the first version's numbers came from code that was not kept.

| | Flatness | Centroid | Ring | First 5 ms |
|---|---|---|---|---|
| Djembe, prototype (round 2 A, picked) | 0.027 | 837 Hz | 86 ms | 3.3% |
| SLAP in the app | 0.029 | 840 Hz | 85 ms | 3.2% |
| Dumbek, prototype (round 3 B, picked) | 0.005 | 1593 Hz | 160 ms | 7.9% |
| DARBUKA in the app | 0.006 | 1593 Hz | 162 ms | 6.6% |
| Tabla, prototype (round 3 C, the base) | 0.006 | 743 Hz | 192 ms | 4.5% |
| DAYAN in the app, setting 3 (picked) | 0.010 | 867 Hz | 159 ms | 12.1% |

The noise generators differ (the harness's 64-bit LCG, `Dsp.Noise`'s 31-bit
one), so the app is the same model, not the same bytes. The djembe shows how
little that moves: the prototype's own "first 5 ms" runs from 1.5% to 4.9%
across 24 seeds, and three of the app's re-struck takes read 1.8% to 3.7%.
Band by band (nine octave bands, five windows to 300 ms), the app's djembe
sits within 1.5 dB of the prototype's below 5.6 kHz wherever it is above
-60 dB from its peak. From 5.6 to 11.2 kHz its first 15 ms is 2 to 5 dB
stronger, which is inside the prototype's own spread across seeds (the
hand's smack is noise).

Velocity, centroid at v = 0.25 / 0.6 / 1: SLAP 437 / 580 / 840 Hz, DAYAN
458 / 579 / 867, DARBUKA 1058 / 1182 / 1593. Each is brighter at every step.

## Every stroke: DOUM, DJEMBE, DHOLAK and BAYAN (2026-11-08)

Five more pads left COMPOUND_MEMBRANE: Bayan Drag (A02), Djembe Bass (A03),
Dholak Bass (A05), Dumbek Doum (A06) and Djembe Open (A08). Tabla Tun (A10)
stays until there is a reference for it.

### Rounds 5 to 7, designed blind, heard as a drum machine

Round 5 (https://claude.ai/artifact/J9CToZGzLndCmiD6mhmsjB) put the six on
the hand model above.
- **Heard:** the doum's A; the djembe bass and the dholak "Too soft"; the
  djembe open "Too weak"; the tabla tun "Missing a sharp k"; the djembe's
  three strokes "Different drums".

Round 6 (https://claude.ai/artifact/42b8e42uNWis6HKjb4JE7n) gave each a
harder hand.
- **Heard:** A, B and C alike, the same as last round, and the drums alike.
- **Measured:** the bass drums put 70 to 97% of their energy below 200 Hz in
  near-pure tones, so the differences lived 6 to 16 dB under a hum.

Round 7 (https://claude.ai/artifact/K3SzdXTXGDfANuD37MUMTz) moved the
character above 200 Hz and spread the directions.
- **Heard:** "Why are this all so drum machine rimshot".

A drum machine's rimshot is a few ringing tones with a click of noise laid on
them. The model was that, and each round answered "too soft" or "similar"
with a louder, shorter, brighter click: the measures rose (brightness,
distance between directions) while the sound moved towards what the owner
disliked.

### The owner's references

The owner sent three phone screen recordings: a dholak, a bayan and a djembe.
They are measured only, never shipped. Per stroke, against the model:

| | The references | The model, rounds 5 to 7 |
|---|---|---|
| The head's notes' T60 | 0.2 to 0.6 s (djembe tone); 1 to 4 s (bayan); 1.6 to 2.2 s (djembe bass, 71 Hz) | 0.05 s at 560 Hz |
| Split pairs | about 4% apart (560/582, 768/807 Hz), fast beating | 0.2 to 0.5% |
| Between and above the notes | full: 20 to 50 dB under the strongest | holes 80 to 100 dB deep |
| The attack's noise | flatness 0.004 to 0.03, the notes themselves | separate bursts |
| Bends | dholak 124 to 96 Hz over 200 ms; bayan 198 to 124 Hz in 150 ms, or holding about 91 Hz | dholak a 12% sag in 60 ms; bayan 65 Hz |

### The method: measured partials plus a fitted residual

Each stroke is resynthesised from the reference's partial table: frequency,
level, T60, the bend, and a build-up time for an air (low) note. It is
struck by the house half-sine contact.

The residual fills the spectrum, as in spectral modelling's deterministic
plus stochastic split:
- **Per band.** Third-octave bands from 60 Hz to 9 kHz, each band-limited
  noise with its own level and decay.
- **Fitted.** Each band is solved so its energy in an attack window (0 to
  30 ms) and a body window (30 to 300 ms) makes up what the partials lack
  against the reference.
- **Bounded.** No band may exceed the reference in either window.

Against the references, the third-octave levels went from 23 to 49 dB rms off
(partials only) to 1 to 8 dB.

Three traps, measured and fixed:
- **A 300 ms Hann window under-weights a short partial,** so its level must be
  set as an amplitude.
- **A residual fitted to the reference cancels any change made against it.**
  "More ring" refitted the wash down until nothing moved. Fit once, then change
  on top.
- **In the djembe bass, the reference's thump is the palm's broadband thud** in
  the residual's 80 to 260 Hz bands, not a partial.

### Rounds 8 to 10, heard

- **Round 8** (https://claude.ai/artifact/YQ9NuMTMyiYC4PgDCpwAzR), fitted:
  - the djembe bass "Closer, not there - a bit more bass";
  - the open tone "Closer, not there - a little more ring";
  - the dholak "a bit more pitch drop";
  - the bayan "Both work" (the fall and the held ghe).
- **Round 9** (https://claude.ai/artifact/MsYNJMhoM3VsMNzpDEoa5m):
  - the open tone's B (its notes ringing 1.8x longer);
  - the dholak's B (falling from 1.55x, 7.6 semitones, against the
    reference's 4.6);
  - the djembe bass "None yet" after +3 and +6 dB on its 71 Hz. Asked, "more
    bass" was a deeper note, a bigger boom and more thump, on earbuds.
- **Round 10** (https://claude.ai/artifact/5r6TREqDHQi7s8yed9Sw9q): the
  djembe bass's B:
  - the bowl at 58 Hz, +6 dB, T60 x1.8;
  - the head-air thump at 2.1x;
  - the palm's thud +9 dB.

### In the app

- **`TerraFitted.kt`** (DJEMBE, DHOLAK, BAYAN) runs the picked strokes from
  their exported tables:
  - **The partials.** Each is convolved with the contact analytically: the
    contact's sum at the partial's pole, updated every 64 samples while a
    bend moves.
  - **The residual.** Built at 44.1 kHz through 8-pole Butterworth band-passes
    (the fit's design, checked against scipy's), then upsampled.
  - **The skin.** The head's own Bessel modes above 1.3 kHz.
- **DOUM** is in `TerraHands.kt`. It is round 5's A:
  - DARBUKA's head struck near its centre;
  - the goblet's air at 110 Hz, driven by the head's net volume (`HeadMode.volume`: an (0, n) mode moves `2 J1(j0n) / j0n` of its peak, every m > 0 mode nets zero);
  - the ceramic shell;
  - the fingers' thud.

| Voice | Controls | Pads |
|---|---|---|
| DJEMBE | TUNE, DECAY, FORCE, STROKE (0 bass, 1 open), THUMP | A03 (STROKE 0), A08 (STROKE 1) |
| DHOLAK | TUNE, DECAY, FORCE, DROP (none, 1.55x at 0.5, 2.1x), TREBLE | A05 |
| BAYAN | TUNE, DECAY, FORCE, BEND (0 held, 1 the fall) | A02 (BEND 1) |
| DOUM | TUNE, DECAY, FORCE, AIR, SHELL | A06 |

Measured against the picked prototypes, attack and body, third-octave rms:

| Pad | Attack | Body |
|---|---|---|
| Djembe bass | 4.7 dB | 4.0 dB |
| Djembe open | 3.5 dB | 2.0 dB |
| Dholak | 2.9 dB | 1.1 dB |
| Bayan, held | 4.2 dB | 2.6 dB |
| Bayan, falling | 3.9 dB | 2.3 dB |
| Doum | 0.3 dB | 0.7 dB |

The residual is noise, so a 30 ms window differs between realisations by that
much. A re-struck take draws new noise.

Render cost, warm: 80 to 125 ms for the fitted drums and 138 ms for DOUM.
That sits between DAYAN (75 ms) and DARBUKA (170 ms).

**Length.** The fitted voices are 1.45 s, their last 0.3 s a raised-cosine
fade. The house classifier hears anything over 1.5 s as a LOOP, and the
djembe bass's boom is still at -27 dB there.

**Velocity.** The full hit is the stroke as picked, sample for sample. Below
it:
- **The contact:** 1.2 ms longer at the softest.
- **The head's notes:** fall as v.
- **The skin:** falls as v^2.
- **Each residual band** falls by its own exponent:
  - under 250 Hz, v^0.5 (a soft palm still thuds);
  - above that, climbing with log frequency from v^0.75 to v^3 at 8 kHz.

The first exponents tried scaled the notes as v^1.5, past the residual's mid
bands. A soft open tone then kept more bright wash than a hard one, and rose
only 19%. As shipped, the first 30 ms centroid rises at every step, v = 0.25
to 1:

| | v = 0.25 / 0.5 / 0.75 / 1 | Rise |
|---|---|---|
| Djembe bass | 156 / 183 / 215 / 254 Hz | +63% |
| Djembe open | 746 / 934 / 1080 / 1209 Hz | +62% |
| Dholak | 299 / 398 / 489 / 581 Hz | +94% |
| Bayan | 163 / 203 / 251 / 289 Hz | +77% |
| Doum | 232 / 276 / 307 / 360 Hz | +55% |

**Tried and dropped: an immediate thud.** The picked bass strokes start
softer than the references. Their first 10 ms against the next 90 reads:

| | The picks | The references |
|---|---|---|
| Djembe bass | 0.39 | 1.12 to 1.43 |
| Dholak | 0.40 | 0.82 to 1.21 |
| Bayan | 0.72 | 0.42 to 1.97 |

The cause is that the residual's narrow low bands ring up over ~16 ms. Two
fixes were tried:
- **Running each band's filter on noise first, refitted.** This overshot to
  7x.
- **A time-domain refit**, the reference and the synthesis through the same
  band-passes in 0 to 10, 10 to 40 and 40 to 300 ms windows, two decays per
  band. This brought the onsets into the references' range. But it moved the
  bodies 5 to 25 dB from the picks.

The owner picked these strokes as they were, so they ship as picked.

**Tests.**
- **TerraTest.** Its "real attack" check (the first 10 ms 1.5x the next 90)
  fails on the references' own bass strokes. For the fitted voices it asks for
  a hit's shape instead: the loudest 10 ms within the first 80 ms, and the last
  100 ms 20 dB under it.
- **RestrikeTest.** The root check skips BOX and the hand drums, as
  StrikeVelocityTest's does: the fitted open tone's fixed 559/582 Hz pair read
  39 cents apart between takes.

## Ledger

- **Heard:** the three rounds above, each for the Python clips it played; the
  app's dumbek as the same drum; the tabla's setting 3; the app's djembe,
  at one level, as consistent with the prototype.
- **Not yet heard clearly:** soft and medium hits in the app ("Can't tell"
  on round 4's page, whose levels were off).
- **Proposed (design shapes):** the strip widths and the finger timings, the
  split range, each drum's T60 and tilt, the bowl's 72 Hz and the shells'
  modes, the noise bursts' bands and lengths, and the mix levels.
- **Frozen guards:** `TerraFrozenTest` holds the four original voices to
  `LegacyTerraBank`. The frozen grid keeps the eight pads as they shipped
  before (`TerraCases.RETIRED_HANDS`), so it stays forty cases.
- **Heard (every stroke):** rounds 5 to 10 above, for the Python clips each
  played; the app's four new voices not yet.
- **Measured:** the references' partial tables, bends and residual bands;
  the app against the picks (above).
- **Not yet heard clearly:** soft and medium hits, asked twice at one
  loudness ("Can't tell" both times). The velocity laws stand as measured.
- **Still open:** Tabla Tun (A10) keeps COMPOUND_MEMBRANE until there is a
  reference for it. Saved pads on COMPOUND_MEMBRANE are untouched; the kit's
  pads moved to new voices.
