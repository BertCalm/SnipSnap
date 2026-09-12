# DUST — the tape's own dirt, back under the hits

**Status: round one built** (the engine, the print cache, the DUST chip,
DUST ALL). Round two — CRACKLE and DUST FROM ▸ — waits on the phone's
verdict on the room extraction.

## 1. What it is

CRUSH, TAPE and DIRT are synthetic: the same grit on every kit. A chop
throws away everything between the hits, and that material *is* the
recording — the room answering each hit, and the floor the tape sits on.
DUST reads both back out of the ghosts and lays them under a pad, so a
kit sounds like one recording again rather than sixteen clean slices.

Two ingredients, one **print** per tape (`com.snipsnap.audio.Dust`):

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

Applying dust to a hit: the hit, plus the hit convolved with ROOM after
an 8 ms pre-delay (a room's first reflections arrive after the direct
sound; an impulse on the attack itself thickens the attack), plus a bed
of HISS 30 dB under the hit's peak at full amount, lifted by up to 8 dB
under a quiet hit the way real tape reveals its floor when the music
drops. The bed and the tail fade out over the last 30 ms. When hit plus
dust would crest, the dust alone is scaled down — the hit is never
touched. Pure arithmetic: same hit, print and amount, same bytes.

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
  A synth pad or a mic capture borrows the kit's room.
- **`DUST ALL ▸` on the KIT action row**: the per-pad door on every pad at
  50, from each pad's own tape else the kit's, under one lock, each
  tape's print read once. Layered and chained pads are left as they are
  and counted. Offered only when the kit came off a tape at all.
- **The print is cached beside the tape**, in `snips/.dust/<tape>.hiss.wav`
  and `.room.wav`, remade when the tape is newer (`DustPrints.forTape`).
  Dusting sixteen pads from one tape pays the extraction once.

## 3. What it says

| When | Line |
|---|---|
| neither the pad nor the kit came off a tape | NO TAPE TO TAKE DUST FROM: THIS PAD, AND THIS KIT, NEVER CAME OFF ONE. |
| the tape is named but gone from the shelf | THE TAPE '…' IS GONE FROM THE SHELF. NO DUST TO TAKE. |
| the tape has no room between its hits | NOTHING BETWEEN THE HITS ON THAT TAPE. NO DUST TO TAKE. |
| DUST landed | DUST ON A02. ORIGINAL SLEEPS IN THE BIN. (or the stacked line when nothing is in the bin) |
| AMT 0 on an undusted pad | AMT 0: NOTHING TO DUST. THE PAD STAYS AS IT IS. |
| DUST ALL landed | 12 PADS DUSTED FROM THE KIT'S OWN TAPE. 2 LEFT AS THEY WERE (LAYERED OR CHAINED). |

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
  re-levels to the contract on read.

## 5. Round two

- **CRACKLE.** The Capture Doctor finds clicks and dropouts in order to
  repair them; collected from the ghosts and sprinkled sparsely under the
  attacks, they are vinyl crackle that belongs to this recording.
- **DUST FROM ▸** on the pad sheet: borrow another tape's dust — a clean
  synth kit dusted with the room of a cassette recorded in the kitchen.
  The recipe already carries the tape, so nothing in the model changes.
- The three ingredients probably want different amount curves; the
  phone decides.
