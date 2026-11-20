# ARCO: bowed string

ARCO is SnipSnap's bowed-string engine: CELLO, ERHU and SARANGI. Its design,
measurements and every earlier listening round are in the design record,
[2026-09-29-arco-bowed-string-engine-design.md](superpowers/specs/2026-09-29-arco-bowed-string-engine-design.md),
with the research pass in [2026-11-04-arco-research.md](superpowers/specs/2026-11-04-arco-research.md).
This page records the owner's verdicts from 20 Nov 2026 on.

## A player on held notes (round fix-arco-1, 20 Nov 2026: KEEP)

**The note.** On 20 Nov 2026 the owner marked every ARCO catch-up clip (BD1 to
BD4: the presets, the kit row and the held keys) with the same note: "Holds are
unnatural, they need more humanization." Measured on those clips, a held note's
level stayed inside 0.05 to 0.3 dB for the whole hold, its pitch had no movement
but the vibrato, and the vibrato's swings differed by 9 to 10 percent on a
pattern that came round every two seconds.

**The change.** A held bow now carries a player (`Arco.HUMAN`, `Arco.played`,
built from `Humanize.Wander`): the bow's speed drifts, so the level swells and
eases (about 1.1 to 1.2 dB over a held key), with a quicker unsteadiness of the
arm on top; the pitch leans a few cents and comes back (the mean stays within
0.7 cents); the note is found from just under its pitch; and the vibrato's depth
and rate wander more, so no two swings are alike. Every movement repeats exactly
with the loop it sits in. A held key's loop is four seconds instead of two
(`Humanize.HELD_LOOP_SECONDS`), so the movement takes four seconds to come
round, and a one-shot's period is four seconds too. Notes with 0.6 s of bow or
less get no player, so CELLO SHORT STAB, GRIT BOW, DRY SCRAPE and ERHU THIN
SCRAPE and TWO STRING are unchanged. LULL's bowed layer asks for the old path
(`Humanize.OFF`) and is unchanged.

**The round.** Clips BP1 to BP4: the held note before then after for each voice
(BP1); two strengths, modest (what ships, `Humanize.MODEST`) then stronger
(1.8 times, `Humanize.STRONGER`) (BP2); the held keys soft then hard with the
player (BP3); and eight presets as they now render (BP4).

**Verdict.** BP1, BP2, BP3 and BP4: KEEP, no notes. The modest strength stays
as shipped.

**Pins.** `HoldHumanizePinsTest` pins every factory preset, each voice's default
note, default LOOP and a three-second note as they ship. The hashes from before
the change still hold with the player off (`ArcoBuzzTest`, `ArcoFrozenR1cTest`).

## Candidate voices (round arco-voices-1, 20 Nov 2026: kept, touch reworked)

**Why.** The melodic gate asks for six voices; ARCO has three. Four candidates
are built, one of them a spare, appended to `ArcoVoice` after SARANGI so saved
names never move. `Arco.KEPT_VOICES` is what players are offered; a candidate
has no preset, kit or starter, and the held keys maker offers kept voices only.
Each is a different bowed instrument using a part of the friction model the
kept three leave unused (`ArcoCandidates.kt`); the stroke, GRIP's window, HOLD,
the stop, the LOOP, the held key and the player are the engine's own, so a kept
candidate needs only to be added to `KEPT_VOICES`.

| Voice | Instrument | What is new in the model | Range |
|---|---|---|---|
| WHEEL | hurdy-gurdy | a wheel bows at one speed (no bite); a chien knocks a 1.8 kHz resonance once a period | D3 to D5 |
| BAR | bowed vibraphone bar | a banded waveguide (tuned-bar modes 1, 4.02, 10.7, 18.1) under the string's friction table | F3 to F5 |
| GLASS | rubbed glass | the banded bow on a glass's modes (1, 2.32, 4.25, 6.63), a wetter grip | C4 to C6 |
| VIOL (spare) | bass viol | bowed further from the bridge (0.15), gut (darker corner, floored at 3.2 times the note), a flat box | C3 to C5 |

**Measured** (`ArcoCandidateVoicesTest`): every candidate within 4 cents of the
note at its root, middle and top except VIOL's C3 (+11 cents); the nearest
third-octave distance to any other voice is WHEEL 4.2 dB (ERHU at A4), BAR and
GLASS 10.5 dB (each other at C4), VIOL 3.8 dB (SARANGI at D3), against the
3.7 dB bar; soft, medium and hard differ by 0.7 to 1.6 dB of level-matched
shape; a held note's level moves 0.7 to 1.4 dB with the player on; every held
key and LOOP closes. The kept voices render bit for bit as before (24 renders
pinned against 6a1c248a7). Nobody has heard any of them.

**Risks.** VIOL sits closest to the kept voices and its lowest notes lock less
cleanly (C3 +11 cents); it is the spare. BAR and GLASS are near-sine voices and
may read as one idea twice. The classifier files a low bowed note whose loudest
moment comes late as a KICK; the kept CELLO and SARANGI do it on the same grid,
and no candidate does it more.

**The round.** Clips DK (WHEEL), DL (BAR), DM (GLASS), DN (VIOL): four notes
across the range, then soft, medium and hard; DO1 is the kept three for
reference.

## Verdict of 20 Nov 2026 (round arco-voices-1) and the candidates' touch

**Verdict.** Every range clip (DK1, DL1, DM1, DN1): **KEEP**, no notes, so
WHEEL, BAR, GLASS and VIOL are kept as sounds and ARCO will have seven voices.
Every soft, medium, hard clip (DK2 to DN2): **TWEAK**, "All sound the same".
Touch moved only the bow, the engine levelled every note to one loudness, and
soft and hard were 1.2 to 2.7 dB apart by level-matched shape.

**The touch** (`CandidateTouch`, `ArcoCandidates.TOUCH`; the same kind of answer
LATTICE's candidates got). A candidate bowed under the default BOW (0.5) is a
lighter, slower stroke further from the bridge, by depth u = how far under, as
a share of the default: it loses 40u dB above 2.5 times the note (a blend toward
its own two-pole low-pass), plays 14u dB quieter and rises over 0.8u s. At the
default BOW and above nothing changes, so the hardest touch is the kept sound,
byte for byte: the range clips re-render to the heard SHA-256 (4 of 4, and DO1),
and the candidates' default renders are pinned (`KEPT_CANDIDATES`, equal to the
build before). Only a one-shot is touched; the LOOP and the held keys are not yet.

Measured at the clips' touches 0.3, 0.7, 1 (`ArcoCandidateVoicesTest`): soft to
hard 9.4 to 13.3 dB of level-matched third-octave shape (was 1.2 to 2.7), each
step 4.4 dB or more (was 0.8 to 1.8); soft 8.4 to 10.7 dB and medium 4.2 to
6.1 dB quieter than hard (was 0); soft reaches half its level 80 to 150 ms
later. Round touch-ab-1 plays each heard clip, then the new touch (EA1 to ED1).
