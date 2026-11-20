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
