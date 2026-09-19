# WORKSHOP — the developer's bench, inside the app

*The spec for the admin mode. Status: the door, SEND TO BENCH, WS2 —
CONFIRM ALL and the cut rating — and WS3 — LABEL THIS HIT — are built;
the rest is the list at the end, in value order. One tester today — the
phone's owner — and the decisions below are shaped by that.*

## Why this exists

The question was whether the app should grow an admin mode where the
phone's owner could tune or create presets, work through the listening
tasks that only a phone in a hand can answer, and rate how well CHOP cuts
and how well the classifier names what it cut — so the rules improve.

Reading the code before answering it changed the shape of the answer.
Two of the three asks were already half-built, and the third is a product
feature wearing an admin badge:

- **Rating the classifier already exists and its data never leaves the
  phone.** SETUP has TEACH THE MACHINE, off by default. With it on, every
  chip corrected on CHOP is logged as a feature vector plus the human's
  label into that kit's own folder (`overrides.jsonl`, `TeachLog`). The
  harness that scores those lines lives in `reference/calibration/`
  (`TeachLogTest`) and had never received one: BACKUP packs kits as
  `.xpn` archives, which carry a program and its samples and nothing
  else. The last mile was missing, and that is the whole of what SEND TO
  BENCH adds.
- **The classifier is rules, not a model, on purpose**
  (`docs/CALIBRATION.md`). A rating does not train anything; it becomes a
  number a threshold gets moved by, and `CalibrationCorpusTest` fails
  under 60% on real captures. That is the cheaper and more honest loop:
  no training pipeline, every change to a threshold made by hand with a
  comment naming the line that motivated it.
- **Presets are Kotlin tables** (`ThumpPresets` and the six beside it),
  held by tests for spread, a name blocklist and classifier identity.
  SYNTH loads them and has no save; a knob moved becomes a pad's recipe in
  `kit.json`, never a named preset. Authoring one on the phone is a real
  want, but *saving* a preset is a thing every player would use, and a
  thing every player would use does not belong behind a developer's door.
- **The listening tasks are `docs/BENCH.md`**, paused since 2026-09-11,
  answered by hand on a laptop. AUDITION, the blind A/B on the bar line,
  already shipped as a product feature.

So: not a mode. A section, at the foot of SETUP, holding tools that add
and never hide, opened by a knock, and remembered.

## The door

**Seven taps on SETUP's own title, each inside two seconds of the last.**
The gesture Android puts on its build number, chosen for the same reasons
Android chose it: it is discoverable by poking at the machine, which is
`docs/PERSONALITY.md`'s law 4 in one line, and it is never tripped by an
idle thumb. From three taps to go the knock counts down in a toast, as
Android's does — a knock that never spoke would be a secret, and one that
counted from the first tap would be a button. The opening toast says where
the section is, because SETUP scrolls and nothing else on screen does.

The state is a preference beside the scheme (`PREF_WORKSHOP`), so the
section stays open across launches until its own CLOSE THE WORKSHOP is
pressed — whose toast says how to get back in, since a door that closes
without saying so is a door lost (law 3). The tap count itself is not
remembered: a knock is a gesture, not a setting.

What it is not, and why:

- **Not a thirteenth tab.** The menu row's own comment says a menu of
  twelve fits no phone, and a tab promises every player that the screen
  behind it is for them.
- **Not `BuildConfig.DEBUG`.** A debug APK is the only build anyone
  installs today, so a build-type gate would be a gate that is always
  open. When a release path exists this can be revisited; the knock costs
  nothing to keep either way.
- **Not on HELP.** The proposal was seven taps on a version line there.
  HELP has no version line, and adding one to carry a hidden gesture is
  the wrong reason to add one.
- **Never a gate on function.** Law 3, held by `WorkshopTest`: the
  section's one sentence about itself promises it adds and never hides.
  A phone that never knocks plays exactly as it did.

Code: `Workshop` in `:shell` (the knock as a tested state machine,
`Workshop.Knock`), the copy in `Copy`, the title's `tapeClick` and the
section in `PropertiesScreen`, the preference and the toasts in `App.kt`.

## SEND TO BENCH — built

The first tool, and the one that unblocks the classifier work that has
waited since Wave TT.

**On the phone.** One button under the WORKSHOP heading. It walks the
whole shelf — every kit folder, and the bin under `.bin/`, where a deleted
kit sleeps thirty days with its log — reads every `overrides.jsonl` it
finds, and packs them as one zip, handed to the same chooser BACKUP uses:
Drive, a messenger to yourself, a cable. The status bar reads PACKING…
while it runs.

**In the zip**, `SnipSnap Bench <date> <time>.zip`, the same stamp BACKUP
puts on its own:

- `overrides.jsonl` — every log merged, in path order, re-serialized
  through `TeachLog` so a torn last line from a killed append is dropped
  on the way out. It is byte-for-byte the shape the harness reads, so it
  drops straight into `reference/calibration/` with no editing.
- `cuts.jsonl` — every cut rating (WS2, below) merged the same way, when
  any exist; a file that would be empty is left out rather than written
  empty.
- `manifest.txt` — the stamp, the totals (labels split into corrections
  and confirmations, and ratings), one row per kit with its share (a
  binned kit shows as `.bin/<name>-<stamp>`), and the two lines a person
  at a desk needs: where to drop the files and what to run. Plain prose
  in its own case; it is read off a laptop, never off the phone.

Two packs of an unchanged shelf are the same bytes (fixed entry time,
fixed order — `XpnPackager`'s trick), so a re-send is a re-send and not a
new file to diff.

**At the desk.** Unzip, copy `overrides.jsonl` (and `cuts.jsonl`) into
`reference/calibration/`, run

```
./gradlew :shell:test --tests '*TeachLogTest*' --tests '*CutRatingsTest*'
```

and read what they print: every label the current rules still disagree
with, with the feature numbers a threshold gets moved by; a line counting
corrections and confirmations apart and how many of both the rules now
agree with — which, with confirmations in the file, is the rules'
accuracy on real material; and the cut ratings summed by the bench's
settings, best first. The folder's own README says the rest: a threshold
moved because of this corpus is moved in `Classifier` with a comment
naming the line that motivated it.

**Refusals, in words.** Nothing logged and TEACH on: *NO CORRECTION IS
LOGGED YET. CORRECT A CHIP ON CHOP FIRST.* Nothing logged and TEACH off:
*TEACH THE MACHINE IS OFF, SO NO CORRECTION IS LOGGED. TURN IT ON ABOVE.*
No app on the phone takes a zip: the same NOWHERE TO SEND IT BACKUP says.
The landing toast counts what is in the file — *14 LABELS AND 3 CUT
RATINGS FROM 2 KITS ON ONE FILE. PICK WHERE IT GOES.*, naming only what
the file holds — and never says SENT, because the chooser opening is not
the file leaving.

**The consent line stays true.** SETUP's consent row promises *FEATURES
ONLY, NEVER AUDIO. NOTHING LEAVES THE PHONE.* Both halves hold: the zip
carries feature vectors and labels and nothing that can be played back,
and TEACH THE MACHINE itself still sends nothing — this button, pressed
on purpose behind a knock, is the only way a log leaves, and the note
under it says exactly that. Any future tool that carries audio (LABEL
THIS HIT, below) gets its own button and its own words, so this promise
is never quietly widened.

**What it deliberately does not do.** It does not upload anywhere, ever;
the chooser is the whole of its reach. It does not include per-kit raw
copies of the logs — provenance is in the manifest, and a second copy of
the same lines is a second thing to keep in step. And it never carries
audio: the labelled hits (WS3, below) leave by their own button, under
their own note, so this one's promise is never widened by a tool beside
it.

Code: `BenchExport` in `:shell` (`gather` walks, `pack` writes, `manifest`
says), held by `BenchExportTest` — which also checks that the folder and
the harness the manifest names actually exist, so the instruction inside
the zip cannot go stale without a test saying so. The button and the
`sendToBench` action mirror BACKUP's shape in `PropertiesScreen` and
`App.kt`.

## CONFIRM ALL and the cut rating — built

WS2. Before it, the teach log held errors only: a chip the human
corrected was a line, a chip the human left alone was nothing, so the
file could count misses and never accuracy. And nothing anywhere measured
the *cuts* — where the detector found the hits and how many — even though
the CUT bench's every knob (HITS, EAR, CUT, SNAP) moves exactly that.

**In the hand.** With the WORKSHOP open, CHOP grows one row above the
slices, under the CLASSIC / FOLD / MELODIC row: **CONFIRM ALL**, and five
stars. A note under the row says what they do and when they are written.

- **CONFIRM ALL** vouches for every chip the classifier named and the
  human left alone, in one tap. It presses in and reads SELECTED, the
  toast counts what it vouched for — *12 CHIPS CONFIRMED: THE MACHINE HAD
  THEM RIGHT. LOGGED WHEN YOU SEND.* — and it stays pressed until the
  chop changes. It refuses, dim, when there is nothing left alone to
  vouch for, and when TEACH THE MACHINE is off.
- **The stars** rate the cuts, one to five. *CUTS RATED 4 OF 5. LOGGED
  WHEN YOU SEND, WITH THE BENCH'S SETTINGS.* A second star replaces the
  first; a re-chop or a MERGE or SPLIT clears it, because those are
  different cuts.
- **Both write at SEND**, beside the corrections, into the kit the chop
  became — SEND TO PADS and ONTO alike. A chop confirmed and never sent
  logs nothing: a line is something a human decided *and kept*.
- **With TEACH THE MACHINE off** the whole row is dim and the note names
  the switch. The consent on SETUP now says what ON logs in full: the
  chips you correct *or confirm*, and the star you give the cuts.

**A confirmation is the same line.** `TeachLog` did not grow a field: a
confirmation is an example whose label *is* the machine's verdict
(`Example.confirmation`), so every reader that predates it still reads
the file, and the harness counts the two apart. The picker's own
agreement — choosing the class the machine already chose, which the model
already reads as agreement rather than correction — is now logged the
same way without CONFIRM ALL: a human decided it.

**What is never confirmed.** A ghost's LOOP and a rung's LOOP are given
by construction, not by the classifier. CONFIRM ALL skips them, because
vouching for a verdict the classifier never gave would teach it a lie.

**The rating line** (`CutRatings`, `cuts.jsonl` beside `overrides.jsonl`):
the stars; the bench's settings that produced the cuts — mode, HITS or
parts, EAR, CUT, SNAP; the slice count; and what it took to get there —
how many chops ran on the source before this one (every RE-CHOP, AUTO,
EAR, CUT, SNAP and HITS step counts a try), how many cuts were then moved
by hand (MERGE and SPLIT count themselves; a re-chop starts that count
over), how many chips were corrected and how many confirmed; the source's
length and, when the bench trusts it, its tempo. No audio and no
features: a rating is a verdict on a configuration, and the configuration
is the whole of the record.

**At the desk**, `CutRatingsTest` sums the file by setting, best first:

```
cut ratings: 5 chops rated, 3.6 stars on average
  4.0 stars  n=3   HITS ×16 · FINE · CUT ON · SNAP OFF     tries 1.3  hand edits 0.7  corrected 1.0 of 14.0 chips
  3.0 stars  n=2   GRID ×16                                tries 0.0  hand edits 2.0  corrected 3.0 of 16.0 chips
```

A setting that rates well after many tries says the *defaults* are
wrong even when the destination is right; one that rates well with hand
edits says the detector is close and the cut placement is not. Those are
the two numbers `Transients.Config` and `Ear` get moved by.

Code: `ChopReviewModel.confirmAll` / `labeledConfirmations` /
`teachHarvest` and the `tries` / `merges` / `splits` record, `CutRatings`,
the copy in `Copy`, held by `CutRatingsTest`; the row and the two SEND
sites in `ChopScreen`, the switch's fuller consent line in
`PropertiesScreen`.

## LABEL THIS HIT — built

WS3, and the one tool in the WORKSHOP that carries audio.
`reference/calibration/` scores the classifier against real captures
labelled by ear (`CalibrationCorpusTest`: per-file verdicts, the
confusion matrix, a 60% floor), and had held a README and no data since
the corpus was named. BENCH A5 has asked for a dozen real hits from the
start. Nothing on the phone could put a labelled hit anywhere a desk
could reach.

**In the hand.** With the WORKSHOP open, every pad's sheet grows a sixth
group box at its foot, **BENCH**, its strip reading `NOT LABELLED` or
`LABELLED KICK`. Open it: a note, and the corpus's nine classes as chips
on two rows — the same chips CHOP uses, without NOT SURE.

- **Tap a class** and a copy of the pad's WAV lands in `Calibration/`
  beside the kits, named the way the corpus reads names — the label
  before the first underscore, then the provenance:
  `kick_Break Kit_A01.wav`. *A01 LABELLED KICK. IN THE CALIBRATION
  FOLDER; SEND HITS TO BENCH CARRIES IT.* The chip lights in the pad's
  colour and stays lit on the next visit.
- **Tap another class** and the pad is relabelled: one file per pad,
  the old one gone, so two labels never contradict each other.
- **Tap the lit class** and the copy comes back out: *A01 UNLABELLED. ITS
  COPY IN THE CALIBRATION FOLDER IS GONE; THE PAD ITSELF IS UNTOUCHED.*
  A copy, never a move — the pad keeps its file whatever happens here.
- **A render refuses.** A pad whose recipe carries a synth patch — SYNTH's
  SEND TO PAD, a starter, a DE-SAMPLEd capture — dims the chips, the
  note turns amber, and a tap says why: *THAT PAD IS A RENDER, NOT A
  CAPTURE. THE BENCH KNOWS ITS RENDERS ALREADY.* Every threshold was
  tuned on renders; the corpus README asks for captures.
- **Treated captures are captures.** CRUSH on a snip is still the snip
  the ear heard; the WAV on disk is what gets labelled, as it sounds.

**SEND HITS TO BENCH** sits under SEND TO BENCH on SETUP, with its own
note: *THE HITS YOU LABELLED ON THEIR PAD SHEETS, AS AUDIO, THEIR LABELS
IN THEIR NAMES. YOUR OWN CAPTURES AND NOTHING ELSE ON THE PHONE. THIS IS
THE ONLY BUTTON THAT SENDS SOUND.* It packs the folder as `SnipSnap Hits
<stamp>.zip` — the WAVs under `Calibration/`, a manifest counting them by
class — for the same chooser. The landing toast says AUDIO because this
one is: *3 HITS ON ONE FILE, AS AUDIO. PICK WHERE IT GOES.* Byte-stable
per folder and stamp, like the other hand-outs.

**At the desk.** Unzip, copy the WAVs into `reference/calibration/`, run

```
./gradlew :audio:test --tests '*CalibrationCorpusTest*'
```

and read the report: every hit classified against its label, the
features behind every miss, the confusion matrix, and the accuracy line
that fails under 60%. A threshold moved because of it is moved in
`Classifier` with a comment naming the file.

**Two promises, kept apart.** SEND TO BENCH's note still reads *FEATURES,
LABELS AND RATINGS ONLY, NEVER AUDIO*, and it is still true: the hits
never ride in that zip. They have their own button because they are the
one thing here that could be played back, and a person pressing it should
be told so in the words under it, every time.

**The names agree with the harness by law.** `LabelledHitsTest` reads
`CalibrationCorpusTest`'s source and holds that every word this door
writes is one the harness maps, that both hat spellings are, and that the
harness still reads the label before the first underscore — so a renamed
label fails a test before the corpus fills with files nothing scores.

Code: `LabelledHits` in `:shell` (`label` / `unlabel` / `labelOf` /
`list`, `isRender`, `pack` and the manifest), the copy in `Copy`, held
by `LabelledHitsTest`; the BENCH box and its chips in `PadSheetScreen`,
the second button in `PropertiesScreen`, `sendHitsToBench` in `App.kt`.

## The list — next tools, in value order

Sizes as `docs/APP_PLAN.md` uses them (S under a day, M a few days).
Every tool's logic lands in `:shell` with tests and `:app` only binds
Compose to it — the repo's one architectural rule, and here it matters
twice: `:app` has no test source set, and a cloud session cannot compile
it.

| # | Tool | Size | What it answers | Exit test |
|---|---|---|---|---|
| WS1 | ✓ **SEND TO BENCH** — above | S | gets the teach log to the harness | a phone's corrections show up in `TeachLogTest`'s report |
| WS2 | ✓ **CONFIRM ALL and the cut rating** — above | S–M | the log held errors only, so it could measure misses but never accuracy; nothing measured the *cuts* | a confirmed-and-corrected chop replays through the feature path with its accuracy counted; a rating line names the setting that earned it |
| WS3 | ✓ **LABEL THIS HIT** — above | S | `reference/calibration/` had zero real captures; BENCH A5 has asked for a dozen since the corpus was named | a labelled hit from the phone lands in `CalibrationCorpusTest`'s confusion matrix |
| WS4 | **BENCH NOTES** — a free-text note stamped with the screen, the open kit and the time, packed with the zip | S | `docs/BENCH.md` is answered on a laptop from memory; the phone knows the context the note is about | a note taken on PLAY names PLAY, the kit, and the time, in the manifest |
| WS5 | **SAVE AS PRESET on SYNTH** — a user preset store (`presets.json` beside the kits), listed under the factory rows; promotion into the Kotlin roster stays a code change guarded by the spread, blocklist and identity tests | M | fast authoring on the phone, honest shipping on the desk — and this is the one that is really a product feature, so it should land *outside* the workshop once it works | a saved preset survives a restart and re-renders the same bytes; a promoted one passes `ThumpPresetsTest` unchanged |

WS5 last because it is the one that should not stay here.

## Decisions

- **One tester.** No tester id in any log, no build flavour, the knock
  as the only gate. The manifest names the phone's contribution by kit,
  which is all one tester needs. When a second phone joins, the log line
  grows a `device` field and the manifest a header — the reader ignores
  unknown keys already, so nothing here has to be undone.
- **A zip with a manifest, not a bare `.jsonl`.** A bare file would be
  one fewer step at the desk and would travel as an octet stream some
  receivers refuse; the zip is what BACKUP already sends, every messenger
  takes it, and the manifest is where provenance lives without touching
  the line format `TeachLogTest` reads.
- **The bin is included.** A correction made on a kit that was later
  deleted is still a correction; the log goes to the bin with the kit and
  comes back out in the zip while it sleeps there. What is lost is a log
  whose kit was emptied from the bin — a shelf-level copy at write time
  would close that, and is not worth doing until WS2 makes the log worth
  more.
- **The knock is on SETUP's title**, not HELP's, for the reason above.
- **Confirmations are explicit.** A chip left alone is logged only when
  CONFIRM ALL is pressed (or when the picker was used to agree), never by
  default whenever TEACH is on. Default logging would have made the
  accuracy number arrive with no extra gesture, but every line in the log
  is something a human decided, and that has to stay literally true or
  the number it produces is worth less than the gesture it saved.
- **Both write at SEND**, not at the tap, so the log is tied to the kit
  the chop became and a chop that was abandoned logs nothing.
- **A hit is labelled at the tap, not at SEND**, unlike the chips: there
  is no send to wait for on a pad sheet, and the file it writes is a copy
  the pad never depends on. One label per pad; a render is refused.
- **Audio leaves only by its own button.** The hits zip and the bench
  zip are two files with two notes, never one, so the promise under SEND
  TO BENCH stays literally true.

## Open

- Where WS4's notes should land at the desk — appended to
  `docs/BENCH.md`'s `→` lines by hand, or kept as their own file beside
  the corpus. Undecided until there are notes.
