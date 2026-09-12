# DUST — the tape's own dirt, back under the hits

**Status: round two built** (round one: the engine, the print cache,
the DUST chip, DUST ALL; round two: CRACKLE, DUST FROM ▸, and each
ingredient on its own amount curve). The curves are the first guess,
chosen by ear on a desktop; §6 says what the phone should judge.

## 1. What it is

CRUSH, TAPE and DIRT are synthetic: the same grit on every kit. A chop
throws away everything between the hits, and that material *is* the
recording — the room answering each hit, and the floor the tape sits on.
DUST reads both back out of the ghosts and lays them under a pad, so a
kit sounds like one recording again rather than sixteen clean slices.

Three ingredients, one **print** per tape (`com.snipsnap.audio.Dust`):

- **ROOM.** After each hit the envelope falls; from where it has dropped
  18 dB below its peak until the next hit is the room answering. Each
  tail's *noise* part (the STN split — the bass note still ringing and
  the hat still decaying do not come along; that is the difference
  between dusty and muddy) is levelled and averaged into one impulse,
  350 ms at most, its absolute values summing to one so a hit convolved
  with it can never come out louder than it went in. A tail counts only
  while it rings on above −40 dB of the hit for 60 ms or more, and only
  when its noise part is at least a quarter of it: a gated hit's own last
  decay is a note, not a room.
- **HISS.** The quietest 250 ms window of the tape that is not digital
  silence, as a seamless loop (the head crossfaded under the tail, equal
  power) at unit RMS.
- **CRACKLE.** The Capture Doctor finds clicks in order to mend them
  (`CaptureDoctor.findClicks`: derivative outliers, transient-guarded,
  isolated; its follow test keeps a drum's own attack out, since an
  onset's energy persists past the jump and a click's dies). Collected
  instead of mended, each is a 4 ms grain around the click under a
  raised-cosine window, at unit peak; 32 at most. The onset detector is
  not consulted: to it a click *is* a transient. A clean tape
  has none, and a print without crackle is still a print. A tape the
  doctor refuses as distortion (every sample a jump) has none either:
  that is the recording, not crackle.

Applying dust to a hit: the hit, plus the hit convolved with ROOM after
an 8 ms pre-delay (a room's first reflections arrive after the direct
sound; an impulse on the attack itself thickens the attack), plus a bed
of HISS under the hit's peak, lifted by up to 8 dB under a quiet hit the
way real tape reveals its floor when the music drops, plus CRACKLE
sprinkled across the hit and its tail, each grain 24 dB under the hit's
peak (lifted like the hiss), landing where a generator seeded from the
hit's own bytes puts it. The bed and the tail fade out over the last
30 ms. When hit plus dust would crest, the dust alone is scaled down —
the hit is never touched. Pure arithmetic: same hit, print and amount,
same bytes, the sprinkle included.

**One AMT, three curves** (`Dust.Curves`). The three are different kinds
of thing — a level, a floor, a count — and one straight line through all
of them was wrong for two:

| Ingredient | Curve | At AMT 100 | At AMT 50 | At AMT 25 |
|---|---|---|---|---|
| ROOM | linear — a level | 0.25 of the hit's level | 0.125 | 0.0625 |
| HISS | dB-linear — a floor; each step of AMT is heard as the same step | −30 dB under the peak | −39 dB | −43.5 dB |
| CRACKLE | the square — a density; sparse until pushed | 12 per second | 3 per second | 0.75 per second |

The count for a hit is the density over the hit's length plus its tail;
the fraction left over is one more crackle with that probability, so a
short hit at low amount is sometimes clean and sometimes carries its one
click — never always nothing. Round one's hiss was amplitude-linear
(−36 dB at 50); the same pad at the same AMT is now 3 dB quieter there
and identical at 100.

## 2. Where it lives

- **The sixth chip on the treatment card**, beside SMEAR. Per pad, with
  the card's AMT. The recipe is `{"verb":"dust","amount":x,"tape":t}`,
  read by `PadSheet.readDust` the way `readSmear` reads SMEAR's, and
  every door that reads recipes sees it: `unTreatState` (so CRUSH after
  DUST restores first), `Retrim.treatmentLeft` (BACK ONTO's toast),
  `RecipeReplay` (DO IT AGAIN replays it when the shelf still has the
  tape, and refuses by name when it does not), the provenance line
  (`dusted from <tape>`).
- **The tape is the pad's own**, from the RE-TRIM keys every chopped pad
  carries, else the one most of the kit came off (`DustPrints.tapeFor`).
  A synth pad or a mic capture borrows the kit's room. Only a bare file
  name counts as a tape (`DustPrints.isBare`): a hand-edited `kit.json`
  or a pasted recipe naming a path is no tape, so nothing is ever read
  or cached outside SNIPS.
- **`DUST FROM ▸` under the card**: borrow another tape's dust — a clean
  synth kit under the room of a cassette recorded in the kitchen. The
  row reads which tape this pad's dust comes from, or would; the door
  opens the shelf's tapes inline on the bench (newest first, 24 at most,
  the pad's current tape lit), and a pick runs the DUST door at the
  card's AMT with that tape. The recipe carries the tape, so AMT moves
  after that keep the borrowed dust, and nothing in the model changed:
  `dustPad` always took the tape by name.
- **`DUST ALL ▸` on the KIT action row**: the per-pad door on every pad at
  50, from each pad's own tape else the kit's, under one lock, each
  tape's print read once. Layered and chained pads are left as they are
  and counted. Offered only when the kit came off a tape at all.
- **The print is cached beside the tape**, in `snips/.dust/<tape>.hiss.wav`,
  `.room.wav` and `.crackle.wav`, remade when the tape is newer or any
  of the three is missing (`DustPrints.forTape`) — a cache from before
  CRACKLE is simply made again. A clean tape's crackle file is one
  silent frame, shorter than a grain, so it reads back as no grains. The
  cache follows the tape through rename and delete. Dusting sixteen pads
  from one tape pays the extraction once.

## 3. What it says

| When | Line |
|---|---|
| neither the pad nor the kit came off a tape | NO TAPE TO TAKE DUST FROM: THIS PAD, AND THIS KIT, NEVER CAME OFF ONE. |
| the tape is named but gone from the shelf | THE TAPE '…' IS GONE FROM THE SHELF. NO DUST TO TAKE. |
| the tape has no room between its hits | NOTHING BETWEEN THE HITS ON THAT TAPE. NO DUST TO TAKE. |
| DUST landed | DUST ON A02. ORIGINAL SLEEPS IN THE BIN. (or the stacked line when nothing is in the bin) |
| AMT 0 on an undusted pad | AMT 0: NOTHING TO DUST. THE PAD STAYS AS IT IS. |
| DUST ALL landed | 12 PADS DUSTED FROM THE KIT'S OWN TAPE. 2 LEFT AS THEY WERE (LAYERED OR CHAINED). 1 LEFT AS IT WAS: NOTHING BETWEEN THE HITS ON ITS TAPE. |
| DUST FROM ▸ with an empty shelf | NO TAPES ON THE SHELF TO TAKE DUST FROM. RECORD OR IMPORT ONE FIRST. |
| DUST FROM ▸ open | DUST FROM: PICK A TAPE. ITS DUST GOES UNDER THIS PAD AT AMT. |
| DUST FROM ▸ landed | DUST FROM 'KITCHEN' ON A02. ORIGINAL SLEEPS IN THE BIN. (or the stacked line) |

## 4. Laws the tests hold

- A print of a tape with rooms decays, sums to one, and its floor loops
  without a seam; the same tape prints the same bytes (`DustTest`).
- Silence, and a gated tape over digital silence, have no dust.
- Applied dust never touches the attack before the room arrives (only
  the bed is added, about 30 dB down), never clips, never scales the hit,
  carries more room at more amount, and is deterministic; stereo stays
  stereo; a print at another rate is resampled.
- The pad door is bin-backed, restores first (re-dusting is byte-stable),
  comes off at AMT 0, touches an undusted pad not at all at 0, and
  replays through DO IT AGAIN (`DustPrintsTest`).
- The cache round-trips through 24-bit WAV to within 1e-4 per sample and
  re-levels to the contract on read; a two-file cache is remade; a
  clean tape's placeholder reads back as no grains.
- A tape's clicks become one grain each, at unit peak, windowed to
  nothing at the edges, the same bytes twice, and survive a level change
  and a rate change; a clean tape has none.
- The sprinkle: about the density's worth across a two-second hit at
  full, far fewer at 0.3, the same sprinkle twice for the same hit, none
  from a print without crackle, and never over the crackle's own level.
- The curves: the values in the table above, monotonic, and the bed
  under a hit at half amount measures what `hissDb(0.5)` says.

## 5. Round two — built

CRACKLE, DUST FROM ▸ and the curves, as above. `DUST FROM ▸` is a row on
the TREATMENT bench rather than a dialog: the bench is where the pad's
treatment is decided, a pick is one tap, and the column already scrolls.

## 6. What the phone should judge

- Whether 24 dB under the peak is the right height for a crackle, and
  whether 12 per second at full is too busy or not busy enough. Both are
  one constant (`CRACKLE_DB`, `CRACKLE_PER_SEC_AT_FULL`).
- Whether hiss wants its 18 dB of travel or more (`HISS_RANGE_DB`); at
  AMT 25 it is 43.5 dB under the peak, which may already be inaudible
  on a phone speaker.
- Whether the room's linear curve wants to bend: it is the one ingredient
  round one already had, and it has not been heard on a phone yet.
- Whether a borrowed tape's dust should follow the pad through DUST ALL
  (today DUST ALL re-dusts every pad from its own tape else the kit's,
  and a pad dusted from elsewhere is re-dusted from its own).
