# CHOP CONTROLS — the CUT bench, MERGE and SPLIT, live markers

**Status: round one built.** The plain controls (HITS, EAR, CUT, GRID),
the two local moves (MERGE, SPLIT), and the markers that show what any
of them did. Round two — FOLD DOUBLES and ON THE GRID — waits on a
short spec each, and on the phone's verdict on round one.

## 1. What was wrong

CHOP had one control on the phone: RE-CHOP, which did the same chop
again. The command line had `--slices N`, `--grid N` and an automatic
count that finds the knee where the real hits end; none of it reached
the screen. A break that came out as forty slices, or six, or sixteen
with two in the wrong place, had no answer but "try another tape".

## 2. The CUT bench

A `GroupBox` under the tape strip on CHOP, closed to one summary line
(`12 HITS · BY HITS · FINE`) so the review is what it was; open, four
rows. Every change is a fresh chop off the main thread through
`ChopReviewModel.rechopKeeping`, and the markers move with it.

| Control | What it does | Where it lives |
|---|---|---|
| **BY HITS / GRID** | follow the hits, or divide evenly | `ChopMode.ByHits` / `ChopMode.Grid` |
| **◀ N ▶** | one hit fewer or more than the chop has now (BY HITS), one part fewer or more (GRID); 1..64 | `ByHits.maxSlices`, `Grid.parts`, `MAX_HITS` |
| **AUTO** | the count the tape wants: the knee in the sorted loudness curve where the real hits end and the detector's scraps begin (the CLI's own rule) | `autoCount` → `Chopper.autoSliceCount` |
| **EAR · COARSE / NORMAL / FINE** | how hard the detector listens: FINE lowers the bar a hit must clear and lets hits sit closer, so ghost notes and fast hats come through; COARSE raises it and keeps them apart | `Ear.config` → `Transients.Config` (threshold factor, floor, minimum gap) |
| **CUT · EARLY / ON / LATE** | where each cut lands against the attack: the detector lags by up to a hop and backs every cut off to make up for it (ON); EARLY backs off more, for a kick that lost its click; LATE not at all, for a tail bleeding into the next slice's front | `Cut.backoffFrames` (384 / 128 / 0) |

The stepper steps from the count the chop *has*, not the cap it was
given, so a step is always visible when the tape allows it. HITS ▶ on a
tape with no more hits at this ear says so — ONLY 6 HITS HEARD AT THIS
EAR. FINE HEARS MORE. — rather than doing nothing.

**Corrected chips survive the bench.** A re-chop from the bench carries
every override onto the fresh slice that starts within 512 frames of
the slice it was on (`carryingOverrides`, `CARRY_TOLERANCE_FRAMES`),
so nudging CUT or stepping HITS by one does not throw away ten
relabelled chips. A slice that moved further is a different slice and
takes the classifier's word. RE-CHOP itself still clears everything:
that button means "start over".

## 3. MERGE and SPLIT

A global count is the wrong tool when fourteen of sixteen cuts are
right. Under a slice's chip — the panel that opens to relabel it, which
is already the slice's own bench — two more buttons:

- **MERGE WITH n+1.** The slice and the next become one; the cut
  between them is gone. The joined slice is cut from the tape again so
  its cleanup is one slice's, not two fades meeting in the middle. It
  keeps the first slice's chip; every other row keeps its own. On the
  last slice: NOTHING AFTER THE LAST SLICE TO MERGE IT WITH.
- **SPLIT AT ITS NEXT HIT.** The slice is cut in two at its own
  strongest inner hit: the detector at the FINE ear over just this
  slice, any hit at least 30 ms in from either end (a hit on the edge is
  the slice's own attack or the next one's), the strongest taken, the
  cut snapped to the zero crossing before it as every chop cut is. The
  first half keeps the chip; the second takes the classifier's word. A
  slice with one hit in it: NO SECOND HIT INSIDE SLICE 3. NOTHING TO
  SPLIT.

Both mark the model **EDITED** (the header says so: `15 SLICES — BY
HITS · EDITED`), since the slices are no longer exactly what the mode
would cut. Both keep the tape reference, so RE-TRIM still works on
every pad that lands.

## 4. Live markers

A strip under the header draws the whole tape with a marker at every
cut, redrawn whenever the model changes — the bench, MERGE, SPLIT,
RE-CHOP. Until now the only way to see what a re-chop did was to scroll
the rows and count.

## 5. What it says

| When | Line |
|---|---|
| HITS ▶ found no more at this ear | ONLY 6 HITS HEARD AT THIS EAR. FINE HEARS MORE. |
| AUTO landed | AUTO: 8 HITS, WHERE THE REAL ONES END AND THE SCRAPS BEGIN. |
| MERGE landed | SLICES 3 AND 4 ARE ONE NOW. |
| MERGE on the last slice | NOTHING AFTER THE LAST SLICE TO MERGE IT WITH. |
| SPLIT landed | SLICE 3 IS TWO NOW. |
| SPLIT found nothing | NO SECOND HIT INSIDE SLICE 3. NOTHING TO SPLIT. |
| a chop is running | CUTTING… (in the count's place) |

## 6. Laws the tests hold (`ChopReviewTest`)

- FINE hears at least what NORMAL hears, which hears at least what
  COARSE hears, and FINE hears ghost hats COARSE does not; COARSE keeps
  the four hits that carry the beat.
- The same hits cut no later at EARLY and no earlier at LATE than at
  ON, and the nudge is real.
- The header names only what is off its default, and EDITED after a
  hand edit; the cut markers are the slice starts, in order.
- AUTO on four hits of a kind is four.
- A bench re-chop carries corrected chips onto the slices that stayed,
  through a CUT nudge and through fewer hits; RE-CHOP clears them.
- MERGE joins end to end and keeps the other chips; SPLIT on the joined
  slice puts the cut back near the hat and loses nothing; SPLIT on a
  single hit refuses; the tape reference rides through both.
- The counts are 1..64 by the mode's own law.

## 7. Round two, and what the phone should judge

- **FOLD DOUBLES**: group near-identical slices and land one pad per
  group with the repeats as a round-robin chain. **ON THE GRID**: snap
  cuts to the nearest 16th of the detected tempo. Each needs a short
  spec: how similar is "the same sound"; whether ON THE GRID moves the
  audio or only the cut.
- Whether FINE's thresholds (1.2× the local novelty, 1.5 dB floor,
  15 ms gap) hear the ghosts on a real break without hearing the room,
  and whether COARSE (2.6×, 7 dB, 80 ms) still hears a fast snare
  roll's first hit. On a synthetic break the three ears differ by one
  ghost hat; a real room, with novelty everywhere, is where the
  threshold factor does its work, and that has not been heard yet.
- Whether EARLY's 384 frames (8.7 ms) is enough for a soft kick's rise.
