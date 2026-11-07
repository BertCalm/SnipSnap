# TERRA's hand drums: SLAP, DAYAN and DARBUKA

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
   https://claude.ai/artifact/DaDn9mYg8ajVCjWS6p5nnD.

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

## Ledger

- **Heard:** the three rounds above, each for the Python clips it played; the
  app's dumbek as the same drum; the tabla's setting 3.
- **Not yet heard:** the app's djembe against the prototype at one level
  (round 4's page played it 5.2 dB quieter).
- **Proposed (design shapes):** the strip widths and the finger timings, the
  split range, each drum's T60 and tilt, the bowl's 72 Hz and the shells'
  modes, the noise bursts' bands and lengths, and the mix levels.
- **Frozen guards:** `TerraFrozenTest` holds the four original voices to
  `LegacyTerraBank`. The frozen grid keeps the three pads as they shipped
  before (`TerraCases.RETIRED_HANDS`), so it stays forty cases.
- **Still open:** the other COMPOUND_MEMBRANE pads (Djembe Bass and Open,
  Tabla Tun, Dholak, Dumbek Doum, Bayan) keep the old palm pulse and its
  FORCE. Moving them changes saved pads, which is the owner's call.
