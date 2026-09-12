# CHOP CONTROLS — the CUT bench, MERGE and SPLIT, live markers

**Status: round two built.** Round one: the plain controls (HITS, EAR,
CUT, GRID), the two local moves (MERGE, SPLIT), and the markers that
show what any of them did. Round two (§8): ON THE GRID and FOLD DOUBLES.

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
relabelled chips. One to one, nearest pairs first: a chip lands on at
most one fresh slice, so when FINE reveals a ghost a hair from a slice
the user relabelled, the ghost does not inherit the label too. A slice
that moved further is a different slice and takes the classifier's
word. RE-CHOP itself still clears everything: that button means "start
over".

AUTO on a tape the detector hears nothing in is a refusal (NO HITS ON
THIS TAPE TO COUNT. TRY GRID, OR TRIM CLOSER TO THE SOUND.), never a
count of one.

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
| AUTO on a tape with no hits | NO HITS ON THIS TAPE TO COUNT. TRY GRID, OR TRIM CLOSER TO THE SOUND. |
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
- AUTO on four hits of a kind is four; on silence it is no count.
- A bench re-chop carries corrected chips onto the slices that stayed,
  through a CUT nudge and through fewer hits, one chip to one slice;
  RE-CHOP clears them.
- MERGE joins end to end and keeps the other chips; SPLIT on the joined
  slice puts the cut back near the hat and loses nothing; SPLIT on a
  single hit refuses; the tape reference rides through both.
- The counts are 1..64 by the mode's own law.

## 7. What the phone should judge

- Whether FINE's thresholds (1.2× the local novelty, 1.5 dB floor,
  15 ms gap) hear the ghosts on a real break without hearing the room,
  and whether COARSE (2.6×, 7 dB, 80 ms) still hears a fast snare
  roll's first hit. On a synthetic break the three ears differ by one
  ghost hat; a real room, with novelty everywhere, is where the
  threshold factor does its work, and that has not been heard yet.
- Whether EARLY's 384 frames (8.7 ms) is enough for a soft kick's rise.

## 8. Round two: ON THE GRID and FOLD DOUBLES

### ON THE GRID

A row on the CUT bench (BY HITS only): **OFF · 8TH · 16TH · 32ND**,
under a line that reads the pulse it would snap to (`ON THE GRID · CUTS
ON THE PULSE · 94 BPM`), or that none was heard. Every cut moves to the
nearest line of that division of the source's own tempo
(`ChopMode.ByHits.grid`, `GridSnap`), so the slices play back in time on
the pads by construction and the density control becomes musical:
eighths, sixteenths, thirty-seconds of *this* break.

Three decisions, each with a reason:

- **The grid is anchored on the first cut, and its spacing is fitted to
  the hits.** The tempo estimator gives a period, not a phase
  (autocorrelation has no downbeat), and captures rarely start on the
  one; the groove clip already anchors on the first hit for the same
  reason. And the estimate is only a seed: it decides which line each
  hit is nearest to, then the spacing is fitted to the hits by least
  squares, twice. Found the hard way: an estimate a percent off (118.8
  for a break at 120) drifts past the later hits within four bars, and
  every one of them then reads as "early" and keeps its cut. The pulse
  of this take is the hits themselves. A fit further than 10 % from the
  seed is not trusted and the seed stands (`GRID_FIT_TOLERANCE`).
- **It moves the cut, never the audio, and a cut never lands after the
  attack the detector found.** The detector's cut already sits a backoff
  before the attack; the snap takes the nearest line *or that cut,
  whichever is earlier*. A hit that pushed early keeps its cut and its
  click; a hit that dragged late gets a cut a hair early and a little
  air in front, which is harmless. Shaving a transient to make a number
  round is the one thing this must never do.
- **Two hits on one line become one slice**, the stronger hit's; the
  audio between joins it. A flam on an 8th grid is one slice. The count
  drops and the header says so.

The tempo is measured once per source and shared by every model cut from
it (a re-chop, a merge, a split), on IO, so the bench never measures
twice and never on the main thread. A tape with no pulse keeps its cuts
where the hits were; the header reads `ON THE 16TH (NO TEMPO)` and the
row's tap says NO TEMPO HEARD ON THIS TAPE. THE GRID NEEDS A PULSE. A
tempo under 0.3 confidence counts as none (`TEMPO_CONFIDENCE`), the
same bar the groove clip uses.

### FOLD DOUBLES

A third segment beside CLASSIC and MELODIC. A sixteen-slice break is
usually five sounds played over and over; FOLD lands one pad per sound
with the repeats cycling under it as a round-robin chain (the pad's WAV
every take end to end, `ChainInfo` stepping a take per hit — the same
chain ROBIN renders from one take, here made of the drummer's own).
Tap the snare pad four times and you hear the four snares they played.

- **"The same sound"** is `Similar.distance` over the classifier's own
  features within 0.05 (`FOLD_WITHIN`, DOUBLES' opening ring), single
  linkage. Level is left out of the distance on purpose, so a ghost
  snare folds with the snare — that is the point of a chain.
- **Never across classes.** A slice the user relabelled TOM is a TOM and
  folds with toms, whatever it sounds like: the chip is the human's word.
- **Never wider than a chain can cycle** (8, `Robin.MAX_TAKES`); a
  longer run becomes two folds.
- **Folds in capture order, led by their first slice**; takes in capture
  order, so take one is what the drummer played first. The lead's chip,
  provenance and RE-TRIM keys are the pad's, plus `folded = N`.
- The rows say what folded: the lead reads `×4 TAKES`, a take reads
  `TAKE 2 OF 4 · = 3` (the slice it folded under). The strip reads
  `FOLD: 16 SLICES → 5 PADS. TAP A PAD, HEAR ITS TAKES IN TURN.` and
  SEND says `16 SLICES FOLDED ONTO 5 PADS.`

A folded pad is a chain pad, and chain pads refuse the treatments,
RE-TRIM, STACK and SPLICE (they are single-zone, and every audio rewrite
refuses them); ROBIN's undo pulls the single take back out of the bin.
That is the trade, and the pad sheet already says so.

### Laws the tests hold

- Eight bars at 120 have a tempo near 120; on a 16th grid no hit is
  lost, every cut is on the pulse or exactly where the detector left it,
  a cut never lands after the detector's, the late hits moved and the
  early one kept its click; a flam on an 8th grid is one slice; the
  tempo is one measurement per source.
- Two kicks, two snares (one soft) and a hat fold to kicks, snares, hat,
  in capture order, led by their first slices, with the right tags; a
  relabelled chip never folds; the landing is one chain pad per fold of
  many — both takes end to end in the WAV, the boundaries at the lead's
  length, the lead's provenance plus `folded` — and a fold of one is a
  plain pad.

### What the phone should judge

- Whether 0.05 is "the same sound" on a real break: too tight and a
  drummer's louder snare stays its own pad; too loose and the rim folds
  into the snare. One constant, and DOUBLES' dial is the same number.
- Whether a folded kit wants the chain's cycle order to follow loudness
  rather than capture order, so the top of the cycle is the hardest hit.
- Whether the grid wants an anchor other than the first cut — a downbeat
  the user taps — for captures that start on a pickup.
