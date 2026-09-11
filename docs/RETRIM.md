# RE-TRIM: a pad goes back to its own tape

**Status: all three rounds built.** Round 3 added the header chip in the
pad's own colour, a HELP line, and the `TapeRetrim` design board. It answers
one question a phone test asked: *once a recording is chopped and on a
pad, how do I change the length of that chop?* Before this the honest
answer was "you can't, directly"; now it is one tap.

What the build settled beyond the spec below:

- **Pads chopped before this shipped** refuse with their own line,
  `THIS PAD WAS CHOPPED BEFORE TAPES WERE REMEMBERED. RE-CHOP TO FIX
  THAT.` (the open question, answered yes).
- **Pads placed by SNIPS → PAD before this shipped** still open: the
  resolver falls back to the legacy `file` key, which that path always
  wrote and which always meant "this whole snip".
- **A treatment is left with the old file**, never re-applied. The toast
  names it (SMEAR included). A pad with GHOSTS or STACK THE TAKES layers,
  or a round-robin chain, refuses at RE-TRIM ▸ with its own line: the
  layers and the chain's boundaries belong to the old file, and the model
  cannot tell ghosts from stacked takes, so it says "clear them first"
  rather than guess — the same refusal the treatment doors make.
- **BACK ONTO A02 replaces KEEP** while a re-trim is live; there is no
  second KEEP on the row. A plain KEEP is one tap away by leaving TAPE
  and coming back, which drops the request.
- `TapeDeckModel.select(from, to)` already was the `selectRange` the
  spec asked for (DIG's own hand-in), so no new deck method.
- The deck's primary while busy reads `RE-CUTTING…`.

Where it lives: `shell/Retrim.kt` (the resolver, the tag, the cut),
`ChopReviewModel.TapeRef`, `KitBuilderModel.backOnto`, `RetrimRequest` in
`App.kt`, and TAPE's first load rung. Tests: `RetrimTest`,
`ChopReviewTest`, `KitBuilderTest`, `TapeDeckTest`, `SnipStoreTest`.

## What happened before this was built

A pad's audio is a WAV in the kit folder. It has no start and end of its
own; its length is the file's length. Three things bear on "edit the
length":

| Piece | What it does today |
|---|---|
| **SHAPE** on the PAD SHEET | Attack, decay, cutoff, resonance as metadata the hardware renders. Shortens what you *hear* without touching the file. Already the right tool for "it rings too long". |
| **RE-TRIM ▸** on the PAD SHEET | Opens TAPE. But TAPE loads by its own priority — the newest snip on the phone, else the file the last KEEP scrubbed, else the open kit's longest sample — and nothing tells it which pad you came from. The code comment calls this gap out by name. |
| **TAPE → KEEP → CHOP → SEND TO GRID** | KEEP does not write a file; it remembers the file and the IN–OUT range (`TapeCommit`). CHOP loads that range, slices it, and each slice lands on a pad with provenance `origin=chop`, `sourceFrame` (within the *range*, not the file) and `lengthFrames`. The file's name and the range's start are not recorded, so a chopped pad cannot find its way back. |
| **SNIPS → PAD** | The whole snip becomes the pad, tagged `file` and `capturedAtMillis` (`SnipStore.provenanceTag`). The file is known; the cut is the whole file. |
| **GRAB / HOLD off the mic ring** | A live capture never touched a snip file. Nothing to go back to, and nothing dishonest about saying so. |

So the pieces exist: a deck that trims (IN, OUT, snap-to-onset, drag
under a fixed needle), a landing path that replaces a pad and archives
the old one as a take (`KitBuilderModel.assign` → `archiveTake` on
save), and a provenance map on every pad. What is missing is the
thread between them: *which file, and where in it*.

## The proposal in one line

Every pad that came off a tape remembers the file and its cut in it;
RE-TRIM opens TAPE on that file with IN and OUT already sitting on the
cut; and the deck's primary button, while re-trimming, is **BACK ONTO
A02**.

## 1. Provenance: three keys on every pad that has a tape

Added to `KitPad.source` by every landing path that can know them, and
read by nothing else (the existing keys stay for the USED badge, the
liner notes and the provenance line):

| Key | Meaning |
|---|---|
| `tapeFile` | The snip file's name on the SNIPS shelf (bare name, resolved against `snips/`, like every other reference in this app). |
| `tapeIn` | The cut's first frame *in that file*, at the file's own sample rate. |
| `tapeOut` | The cut's end frame, exclusive. |

Who writes them:

- **CHOP → SEND TO GRID** and **INSTANT KIT**: `tapeFile` is the kept
  commit's file; `tapeIn` is the commit range's start plus the slice's
  `sourceFrame`; `tapeOut` is `tapeIn` plus `lengthFrames`. `ChopReviewModel`
  today knows the source *audio* but not the file or the range offset, so
  `arrangedPad` takes them as parameters and `ChopScreen` / TAPE's
  INSTANT KIT pass them from `TapeCommit`.
- **SNIPS → PAD**: `tapeFile` is the snip, `tapeIn` 0, `tapeOut` its
  frame count. `SnipStore.provenanceTag` grows the three keys.
- **BACK ONTO** (below): the new cut.
- **GRAB / HOLD**, imports, MAKE INSTRUMENT, treatments, the tools that
  make a pad from another pad: nothing. A pad without `tapeFile` has no
  tape, and RE-TRIM says so.

Frames, not seconds, and at the *file's* rate: the deck works in frames
of the file it loaded, so the cut lands exactly, and a later resample
never drifts it.

## 2. A pure resolver: `Retrim.of(pad, snipsDir)`

In `:shell`, beside `SnipStore`, so it is tested without a screen:

```kotlin
sealed class Retrim {
    /** The tape is there: open it here. */
    data class Ready(val file: File, val inFrame: Int, val outFrame: Int) : Retrim()
    /** No tape to go back to, and the reason in the app's words. */
    data class Refused(val reason: String) : Retrim()
}
```

Refusals, each its own line of copy:

- no `tapeFile` → `THIS PAD CAME OFF THE MIC (OR AN IMPORT). NO TAPE TO GO BACK TO.`
- the file is gone from `snips/` (deleted past the bin, or never copied
  to this phone) → `THAT TAPE'S GONE. THE PAD KEEPS WHAT IT HAS.`
- `tapeIn`/`tapeOut` missing or not a range → treated as the whole file,
  never a refusal: a pad tagged before this spec still opens its tape.

## 3. TAPE opens on the pad

- **The PAD SHEET's RE-TRIM ▸** resolves `Retrim.of` first. `Refused`
  toasts the reason and stays on the sheet — the button is not
  disabled, since the reason is the useful part. `Ready` hands App a
  `RetrimRequest(kitDir, slot, padLabel, file, cut, colorHex, drumClass)`
  (as built; `cut` is a `Retrim.Cut?`, null for a pre-spec pad's whole
  file) and goes to TAPE.
- **TAPE's load priority gains a first rung**: a pending
  `RetrimRequest` beats `SnipStore.newest`. Today's `tapeOpenOverride`
  sits *below* the newest snip, which is exactly the trap this spec is
  about — a capture made since would shadow the pad's tape. A RE-TRIM is
  the freshest intent there is.
- On load, the deck gets `select(inFrame, outFrame)` (`TapeDeckModel`'s
  existing hand-in, the one DIG uses; the spec first called it
  `selectRange`)
  and seeks to `inFrame`, so the first thing on screen is the pad's own
  cut, IN and OUT flags up, LEN reading the pad's length.
- The header LCD says what is going on: `RE-TRIM A02 · BASS 5.WAV`,
  with the pad's class colour on the chip, for as long as the request
  is live.
- The request dies on: BACK ONTO, KEEP (a plain commit is a different
  intent, and clears it the way a real KEEP clears `tapeOpenOverride`
  today), leaving TAPE by the menu row, or a new capture landing.
- The tape load cap (`TAPE_LOAD_MAX_SEC`) stays. A cut that lies past
  the cap cannot be reached: `THAT CUT SITS PAST WHAT TAPE CAN HOLD.`
  and the deck opens at the top as it does for any long file.

## 4. BACK ONTO A02

While a `RetrimRequest` is live, the deck's primary row reads:

`BACK ONTO A02` · `INSTANT KIT ▸`

and KEEP moves to the row's second slot only when a plain commit is
wanted; the request owns the primary. BACK ONTO, with a selection:

1. Reads IN–OUT from the loaded file (the same `WavReader` path a
   commit's range takes into CHOP; no re-decode of the whole tape).
2. Runs it through the same clean-up a KEEP → CHOP slice gets, and
   nothing more — no treatment, no normalise beyond what the chop path
   already does, so a re-trimmed pad sounds like the chopped one did.
3. `KitBuilderModel.assign(slot, snip, drumClass = the pad's, displayName
   = the pad's, source = the pad's map with the three keys rewritten)`.
   Level, pan, tune, one-shot, choke, SHAPE and GHOSTS are **carried
   over** from the old pad (they are metadata on the pad, not the file).
   A **treatment** is not: it was baked into the old file, and the old
   file goes to the takes and the bin as any replaced pad's does. The
   toast says so when one was there: `A02 RE-CUT. THE CRUSH STAYED WITH
   THE OLD ONE — IT'S IN THE BIN.`
4. Saves the kit (which archives a take, so UNDO on the sheet is the
   TAKES room, as for every other replacement), clears the request, and
   returns to the PAD SHEET on that pad, whose provenance line now reads
   `BASS 5.WAV @ 1.20–1.62s`.

Without a selection: `SET IN + OUT FIRST`, as KEEP says.

## 5. The PAD SHEET's provenance line

`provenanceLine` gains the cut: `BASS 5.WAV @ 1.20–1.62s · 420 ms` when
the three keys are there. It is the only place the numbers show, and it
is what tells you RE-TRIM will land where you expect before you tap it.

## Tests

- `KitBuilderModel.fromChop` writes `tapeFile`, `tapeIn`, `tapeOut` with
  the range offset applied; two slices of one commit get the right
  absolute frames.
- `SnipStore.provenanceTag` writes the three keys for a whole snip.
- `Retrim.of`: ready; no key; file gone; keys absent but file present
  (whole file, not a refusal).
- `TapeDeckModel.select` sets IN and OUT, clamps to the tape, and
  `commitSelection` returns exactly that range.
- Re-assign through `assign` with the old pad's metadata carried and the
  old file archived (a take exists after, the bin holds the old WAV).
- `PersonalityTest` keeps the new lines under the panel width.

## Rounds

1. **Provenance and the resolver** (`:shell`, `:kit`): the three keys on
   every path that can write them, `Retrim.of`, the deck's `select`, the copy.
   Pure, tested, no screen.
2. **TAPE on the pad**: `RetrimRequest` in App, the new first rung in
   TAPE's load priority, the header, BACK ONTO with the metadata carry
   and the take, the PAD SHEET's line. `android-build` is the check.
3. **Polish**: the header chip in the pad's class colour, the HELP line,
   the design board. BACK ONTO from INSTANT KIT stays out of scope until
   asked.

## Open questions (as settled)

- **Pads chopped before this shipped** have no `tapeFile`. Settled: they
  refuse with their own line, `THIS PAD WAS CHOPPED BEFORE TAPES WERE
  REMEMBERED. RE-CHOP TO FIX THAT.`, when `origin=chop` is present and
  `tapeFile` is not (built in round 1).
- **Should BACK ONTO keep the treatment by re-applying it?** Settled: no.
  A re-trim is a cut, and stacking a treatment silently is the kind of
  surprise the SMEAR bug already taught. The treatment stays with the
  old file and the toast names it. A follow-up if ever wanted.
- **ORBIT's BOUNCE ▸ TAPE** snips land on the shelf and go to pads via
  SNIPS → PAD, so they get the keys for free.
