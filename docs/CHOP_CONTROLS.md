# CHOP CONTROLS — the CUT bench, MERGE and SPLIT, live markers

**Status: round three built.** Round one: the plain controls (HITS, EAR,
CUT, GRID), the two local moves (MERGE, SPLIT), and the markers that
show what any of them did. Round two (§8): ON THE GRID and FOLD DOUBLES.
Round three (§9): GHOST CHOP.

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
  `FOLD: 16 SLICES → 5 PADS. TAP A PAD, HEAR ITS TAKES IN TURN.`, SEND
  says `16 SLICES FOLDED ONTO 5 PADS.`, and ONTO an existing kit's bank
  lands the same chains (`landArranged` carries the takes) and says
  `'KIT' BANK B: 16 SLICES FOLDED ONTO 5 PADS.`
- ON THE GRID on a tape with no pulse still records the choice: the cuts
  stay where the hits were, the header reads `(NO TEMPO)`, and the tap
  says why nothing moved.

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

## 9. Round three: GHOST CHOP

The chopper only ever keeps the hits. The tape between them — the
decays, the room, the floor — is thrown away, and it is a texture kit
nobody has heard yet. GHOSTS is the third segment on the CUT bench's
mode row (BY HITS · GRID · GHOSTS): the rows flip from the hits to the
spaces between them, each named for the hit it follows (`AFTER SNARE
3`), and they land as **gate pads** — hold the pad, hold the room; let
go, it stops.

- **Where a ghost starts** (`Chopper.ghosts`): the hit's peak in its
  first 50 ms, then the first 5 ms window whose RMS has fallen 18 dB
  below that peak (`GHOST_DROP_DB`) — where the hit has died down and
  the room is what is left. It runs to the next hit's cut; the last to
  the end of the tape. DUST reads the same tails for its room; here the
  whole tail is the pad.
- **A ghost shorter than 60 ms is not a ghost** (`GHOST_MIN_SEC`). A
  tight, gated break has none, and the strip says NOTHING BETWEEN THE
  HITS ON THIS TAPE. A GATED BREAK HAS NO GHOSTS. rather than showing
  sixteen pads of scraps.
- **A ghost is texture, not a drum.** The rows are classed LOOP with
  full confidence — never sent to the drum classifier, which would call
  every one NOT SURE — and their features still ride along, so FOLD
  works on ghosts too. The chip can still be relabelled.
- **Named for the hit before it**, as the chip would have read that hit
  (`AFTER HAT CL 2`); the name is the pad's name on the grid
  (`ArrangedPad.displayName`, carried through SEND and ONTO alike) and
  rides in provenance as `ghost`.
- **Balanced on landing.** Ghosts are quiet by nature; a ghost chop
  goes through the kit-level balancer (`Balance`), which sets the
  mixer level per pad and never touches the WAVs — the same balancer
  the CLI's chop applies. A chop of hits lands as it always has.
- **The bench reaches the hits underneath.** Count, EAR, CUT and ON THE
  GRID all act on the hits chop the ghosts are the spaces of
  (`ChopMode.Ghosts(hits)`, `hitsOf`, `withHits`); the readout says both
  (`4 GHOSTS · 8 HITS`). MERGE and SPLIT work on ghosts; a split ghost's
  second half is still the space after the same hit. Every ghost carries
  RE-TRIM's keys, so a ghost pad can go back to its tape like any slice.
- **A second page.** Chop the hits, SEND; switch to GHOSTS, ONTO the
  same kit's empty bank B: the hits on A, their rooms on B.

### Laws the tests hold

- Four hits give four ghosts, named AFTER KICK 1 … AFTER HAT OP 4,
  classed LOOP and never NOT SURE; each starts after its hit's attack
  and runs exactly to the next cut, at least 60 ms long.
- The bench reaches the hits underneath (three hits, three ghosts; the
  header carries CUT EARLY); a grid becomes the hits.
- The landing is gate pads named for their hits, through the balancer,
  with RE-TRIM's keys and `ghost` in provenance, through SEND and ONTO
  alike.
- A gated break's hits are heard and its ghosts are none.
- MERGE and SPLIT keep the names.

### What the phone should judge

- Whether 18 dB below the peak is where a ghost should start: earlier
  and the pad carries the tail of the drum, later and short rooms are
  lost. One constant.
- Whether the balancer's LOOP target (0.85) brings ghosts up enough on
  a phone speaker, or whether a ghost chop wants its own target.
- Whether the SPLIT engine's air separation over each ghost (the room
  without the tonal ring) is worth a second pass — the richer version
  the idea named, left for when the plain one has been heard.

## 10. Round four: HUM THE CHOP

You beatbox the pattern you want over the break, and your mouth decides
both where the cuts fall and what they are called. HUM is the fourth
segment on the CUT bench's mode row (BY HITS · GRID · GHOSTS · HUM). Tap
it and the tape plays while the mic listens; you go "boom tss ka tss"
along with it; tap again (or let the tape run out) and the rows re-cut
to match: a cut at every sound you made, labelled by what your mouth
said. Sounds you did not make are not kept. You chop by performing the
chop — CATCH A HIT's claim, made with the mouth instead of a finger, and
the first of the two to inherit the other's parts: the tape's hits are
CATCH's own list (`CatchModel.hitsOf`, every hit, not a chop's sixteen).

### In the hand

- **HUM** on the mode row. With the mic not armed it refuses and says
  which door arms it (`ARM THE MIC FIRST. HUM LISTENS THROUGH IT.`); the
  hum comes off the same sixty-second ring GRAB and HOLD use, so nothing
  starts or stops recording here. Armed, the source plays from its top
  through the same voice that auditions a row, the readout reads
  `HUMMING…`, and the toast says what to do: `HUM ALONG. HEADPHONES ON,
  OR THE MIC HEARS THE TAPE TOO. TAP HUM AGAIN TO STOP.`
- **Tap HUM again**, or let the tape run out, and the hum is read
  against the tape. The rows become the hits the mouth landed on, each
  chip the mouth's word (`YOU ✓` where it differs from the tape's own),
  the header `6 SLICES — HUMMED`, the readout `6 HUMMED`, and the toast
  `HUMMED: 6 CUTS, YOUR MOUTH'S WORDS ON THEM. 2 SOUNDS FOUND NO HIT.`
- **Nothing landed** (`NOTHING YOU HUMMED LANDED ON A HIT. HEADPHONES
  ON, AND HUM WITH THE BEAT.`) leaves the chop as it was.
- The bench's count, ear, cut and grid do not reach a hummed chop: the
  count is the mouth's. BY HITS, GRID or GHOSTS leave it, as they leave
  each other. MERGE and SPLIT, FOLD, MELODIC, SEND and ONTO all work on
  it as on any chop; a hummed chop's pads carry RE-TRIM's keys like any
  other.

### Underneath (`shell/Hum.kt`)

Pure: two snips in, cuts out. The screen owns the mic and the deck.

- **Onsets on the hum**, the same detector the tape gets. Each mouth
  onset is matched to the nearest tape hit within `MATCH_SEC` (120 ms).
- **The lag is measured, not guessed.** The whole hum is late by the
  same amount — the phone's output latency, the ear, the mouth — so the
  median offset between the mouth's onsets and their nearest hits, over
  those within `LAG_MAX_SEC` (200 ms), is taken out before matching. A
  hum 70 ms late reads as on time. A hum with no hit within 200 ms of
  any of its sounds has no lag to measure and lands nothing, and the
  toast says so — on a half-second grid a hum 300 ms late is also
  200 ms early for the next hit, and reads as that; only a hum further
  than 200 ms from every hit is past reach. The screen adds no latency
  constant of its own; the stop instant is fixed before anything that
  waits, and the ring's audio since the stop is dropped, so the window
  ends where the finger did.
- **One hit, one sound.** Two mouth sounds on one hit: the nearer keeps
  it, the other is a miss. A mouth sound with no hit within reach is a
  miss. Misses are counted and said, never landed: the mouth's timing
  is the point, and a cut where the tape has no hit is not a chop.
- **What the mouth said** is the classifier's reading of the mouth
  sound itself, from its onset to the next (at most `MOUTH_MAX_SEC`,
  300 ms): a "boom" reads kick-like, a "tss" hat-like. Unsure (under
  `NOT_SURE_BELOW`), or not a drum (LOOP, UNKNOWN, or TONAL — a held
  vowel is a note, not a hit), the tape slice's own classification
  stands. The classifier has never heard beatbox;
  the teach log already records corrections, and a hummed chip
  corrected by hand is one.
- **`ChopMode.Hummed(cuts, labels)`**: exactly those cuts of the source,
  each INSTANT KIT's own cut of its hit (to the next tape hit, not the
  next kept one — the hits between are not kept), the mouth's words as
  the chips' overrides. RE-CHOP of a hum re-applies the mouth's words;
  the hum is not carried through `carryingOverrides` on its way in,
  since the mouth's word is fresher than a chip corrected before it.
- **The mouth's own rate.** The mic ring records at its rate, the tape
  is at its own; onsets are read across in tape frames.
- **The ring's last minute.** GRAB and HOLD's ring holds sixty seconds;
  a hum longer than that keeps its last minute, and the reader is told
  where on the tape that window starts (`offsetFrames`), so the sounds
  it holds still land on the right hits. The mic has to be armed, and
  armed as the mic: the INSIDE's ring is other apps' playback, not a
  mouth, and HUM refuses it in words.
- **Nothing interrupts a hum.** The bench, RE-CHOP, MERGE and SPLIT,
  SEND and ONTO, and a row's audition are all off while the hum runs:
  the source playing is what the mouth is following.
- **The beat you sang** rides along: the reading keeps every mouth
  onset on the tape with the lag out (`pattern`), and the mode keeps,
  per cut, where the mouth's sound sat and how loud it was among the
  hum's own (`Hummed.beat`, `Hum.VELOCITY_FLOOR` 0.3 the quietest a
  sound reads as a note). See below.

### The beat you sang

The extension the idea named: with a tape, the hum's timing is also a
pattern, so the beat you sang is played by the pads you cut. SEND of a
hummed chop writes it as the new kit's groove (`Hum.groove`,
`Hum.landGroove`): one note per sound the mouth made, on the pad its cut
landed on (the CLASSIC placement SEND used), where the mouth put it, as
loud as the mouth made it, on the source's own pulse — the standard
variations off it, the way READ AS GROOVE lands on a kit with none. The
toast says so: `SENT. THE BEAT YOU SANG IS ON THE GRID: 2 BARS. GROOVE
HAS IT.` KIT opens as after any SEND; GROOVE is one tap away.

Nothing is written when there is nothing honest to write, and SEND
says what it always said: the chop is not a hum; its slices were edited
since (a MERGE or SPLIT moves the cuts off the beat the mouth made);
the source has no confident tempo (a clip needs a grid — the bench
already reads NO TEMPO HEARD); the layout is FOLD or MELODIC (their
pads are not the rows'); or ONTO, which lands on a kit that may have a
groove of its own already. Sounds the mouth made that met no hit are
not in the groove either: they have no pad.

### What it says


- `ARM THE MIC FIRST. HUM LISTENS THROUGH IT.` · `THE INSIDE IS ARMED, NOT THE MIC. HUM NEEDS THE MIC.`
- `HUM ALONG. HEADPHONES ON, OR THE MIC HEARS THE TAPE TOO. TAP HUM AGAIN TO STOP.`
- `HUMMING…` (the readout) · `6 HUMMED` · header `6 SLICES — HUMMED`
- `THE MIC HEARD NOTHING. ARM IT, THEN HUM AGAIN.`
- `NOTHING YOU HUMMED LANDED ON A HIT. HEADPHONES ON, AND HUM WITH THE BEAT.`
- `HUMMED: 6 CUTS, YOUR MOUTH'S WORDS ON THEM. 2 SOUNDS FOUND NO HIT.`
- `SENT. THE BEAT YOU SANG IS ON THE GRID: 2 BARS. GROOVE HAS IT.`
- HELP: `· HUM ON CHOP: BEATBOX ALONG. THE CUTS AND LABELS FOLLOW YOUR MOUTH.`

### Laws the tests hold (`HumTest`)

- On a break of kick, hat, snare and open hat, a boom on the kick and a
  tss on the snare, both 70 ms late, cut the kick and the snare and no
  other, INSTANT KIT's own cut of each; the lag reads 70 ms; the kick's
  chip is KICK and the snare's is HAT CL, `YOU ✓`, the tape's own word
  SNARE still underneath; a boom between the hits is one miss; the
  pattern has every sound; the header reads HUMMED; the bench's hit
  controls don't reach it; SEND lands the two; RE-CHOP keeps the
  mouth's words.
- Two mouth sounds on one hit: the nearer keeps it, the other is a
  miss. A bar the classifier can't clear leaves every chip the tape's
  own word. A hum 250 ms from every hit lands nothing, and the lag
  reads zero.
- A hum whose window starts a second into the tape is read from there;
  a held vowel over the kick never becomes a TONAL chip.
- A hum recorded at half the tape's rate reads in the tape's frames,
  its lag with it.
- On an eight-hit break with a pulse, boom-tss-boom-tss over the kick
  and snare, the second boom quieter: the groove has four notes, each
  where the mouth put it on the source's pulse, on the pad its cut
  landed on, as loud as the mouth made it (the quieter boom quieter);
  a merge since the hum, or a chop by hits, writes no groove; landed on
  the kit, the sung clip is among its grooves.

### What the phone should judge

- Whether 120 ms is the right reach: a loose beatboxer wants more, a
  busy break wants less. One constant.
- Whether 200 ms is enough lag to carry on a slow phone with a Bluetooth
  headset (their latency can run past it); if hums keep landing
  nothing with headphones on, this is the constant.
- Whether the classifier's word on beatbox is worth having at all before
  the teach log has heard some, or whether round one should have left
  every chip the tape's own word and let the mouth only choose.
- Speaker bleed: with the tape out loud the mic hears the break and
  every hit matches. Round one asks for headphones in words; a bleed
  check (a hum whose onsets match every hit, and classify like the tape
  did) could refuse instead.

