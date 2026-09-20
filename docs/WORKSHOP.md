# WORKSHOP — the developer's bench, inside the app

*The spec for the admin mode. Status: the door and every tool on the
list are built — WS1–WS4 inside the workshop (SEND TO BENCH, CONFIRM ALL
and the cut rating, LABEL THIS HIT, BENCH NOTES) and WS5, SAVE AS
PRESET, outside it, where the spec said it belonged. What remains is in
Open. One tester today — the phone's owner — and the decisions below are
shaped by that.*

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
- `notes.jsonl` — every bench note (WS4, below) as the phone kept it,
  when any exist. The one file in the zip a person typed.
- `presets.json` — the player's saved presets (WS5, below), byte for byte
  as the phone keeps them, when any exist. Knob settings and names, never
  audio.
- `manifest.txt` — the stamp, the totals (labels split into corrections
  and confirmations, ratings, notes, and presets), one row per kit with
  its share (a binned kit shows as `.bin/<name>-<stamp>`), every note as
  a `→` line ready to paste into `docs/BENCH.md`, every saved preset as
  the roster line that promotes it, and the two lines a person at a desk
  needs: where to drop the files and what to run. Plain prose in its own
  case; it is read off a laptop, never off the phone.

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

**Refusals, in words.** Nothing logged and TEACH on: *NOTHING TO SEND.
NOTHING IS LOGGED YET. CORRECT OR CONFIRM A CHIP ON CHOP, OR TAKE A NOTE,
FIRST.* Nothing logged and TEACH off: *NOTHING TO SEND. TEACH THE MACHINE
IS OFF, SO NO CHIP IS LOGGED. TURN IT ON ABOVE, OR TAKE A NOTE.* No app on
the phone takes a zip: the same NOWHERE TO SEND IT BACKUP says. The
landing toast counts what is in the file — *14 LABELS, 3 CUT RATINGS AND
2 NOTES FROM 2 KITS ON ONE FILE. PICK WHERE IT GOES.*, naming only what
the file holds, and the kits only when something came from one — and
never says SENT, because the chooser opening is not the file leaving.

**The consent line stays true.** SETUP's consent row promises *FEATURES
ONLY, NEVER AUDIO. NOTHING LEAVES THE PHONE.* Both halves hold: the zip
carries feature vectors, labels, ratings, knob settings and the tester's
own typed words, and nothing that can be played back, and TEACH THE
MACHINE itself still sends nothing — this button, pressed
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

## BENCH NOTES — built

WS4. `docs/BENCH.md` is the list of everything only a phone in a hand
can answer, and every answer on it was written on a laptop from memory,
hours later — which is how one session answered three rows and forgot
the rest. The phone knows the context a note is about: which screen,
which kit, which pad, and when. Now it writes that part itself.

**In the hand.** With the WORKSHOP open, the title bar — the one row
every screen shares — ends in a **NOTE** chip. Tap it and a slip drops
from the bar: BENCH NOTE, the context line (`PLAY · Break Kit`, or
`KIT · Break Kit · A03` with a pad sheet up, or just `SHELF`), a field
with the keyboard already up, and CANCEL beside KEEP. Type what you
heard, KEEP: *NOTED ON PLAY. SEND TO BENCH CARRIES IT.* KEEP is dim
until there is a word to keep; Back or the scrim is CANCEL. The chip is
on the title bar rather than on each screen because a note is about
whatever is under it, and a chip per screen would be one more thing per
screen to keep in step.

**The stamp is read at the tap**, not at KEEP: the tap is the moment the
note is about, and the typing takes a while. It is read once, into one
value the slip shows and KEEP writes, so the line you see and the line
that lands can never disagree. The time is the same `yyyy-MM-dd HHmm`
stamp the hand-outs carry in their names.

**On disk.** One line per note, appended to `Bench/notes.jsonl` beside
the kits — a folder with no `kit.json`, so the shelf never lists it, and
not inside any kit, because a note on the shelf has no kit and a note on
PLAY is not the kit's business. Whitespace typed into the field, a
newline included, folds to one space on the way in: the `→` line it
becomes at the desk is one line too. Never audio, and never a log the
machine wrote — these are the tester's words, and the only thing in the
bench zip a person typed.

**SEND TO BENCH carries it.** The notes ride in the same zip as a third
file, and the manifest renders every one as a line ready to paste under
the `docs/BENCH.md` row it answers:

```
bench notes, ready to paste under the docs/BENCH.md row each answers:
→ 2026-09-19 2107 · PLAY · Break Kit: hats feel late at 92, maybe the choke fade
→ 2026-09-19 2112 · KIT · Break Kit · A03: the open hat rings past the bar
```

A shelf with nothing logged but a note is still something to send; the
landing toast counts the notes beside the labels and the ratings, and
names the kits only when something came from one (*1 NOTE ON ONE FILE.
PICK WHERE IT GOES.*). The refusals name the note as the other remedy:
*…CORRECT OR CONFIRM A CHIP ON CHOP, OR TAKE A NOTE, FIRST.*

**At the desk.** Open the manifest, paste. That is the whole of it, and
it settles the question this spec left open: the notes are not a file
beside the corpus, because nothing scores them — they are answers, and
`docs/BENCH.md` is where the answers go. `notes.jsonl` is in the zip so
nothing is lost between two sends, not for a reader.

Code: `BenchNotes` in `:shell` (`Note`, `Stamp`, `oneLine`, the jsonl
round trip, `render`), the third file and the manifest lines in
`BenchExport`, the copy in `Copy`, held by `BenchNotesTest` and
`BenchExportTest`; the chip on `TitleBar`, the slip in `BenchNoteDialog`,
`openBenchNote` / `keepBenchNote` and the `sendToBench` change in `App.kt`.

## SAVE AS PRESET — built, outside the workshop

WS5, and the one tool on the list that is not a bench tool. SYNTH loads a
factory preset, the player wrecks it into something of their own, and
until now the only place that sound could go was a pad: SEND TO PAD
writes the recipe into one kit, and the next kit starts from the factory
row again. Saving a preset is a thing every player would use, so there is
no knock in front of it: it is on SYNTH for everyone, as the spec said it
should be. Only the promotion path — getting a preset off the phone and
into the shipped roster — is bench business, and that rides SEND TO
BENCH.

**In the hand.** SYNTH's action row is three buttons now: SCRAMBLE,
**SAVE PRESET ▸**, SEND TO PAD ▸. The new one needs no kit open, because
a preset is the shelf's, not a kit's. It drops a slip — SAVE AS PRESET,
the engine and the voice, a field with the keyboard already up — opened
on a placeholder to type over (`KICK 1`, `HAT CLOSED 2`: the voice and
the first number nothing holds). The caption under the field is the
rule the name fails, or what the save will cost, and the button reads
SAVE or REPLACE to match:

- blank → *A PRESET NEEDS A NAME.*, dim;
- longer than fourteen letters (the factory rule the strip was built
  for) → the note, in the warn colour, dim;
- a name the factory ships on this voice → *THE FACTORY HAS DUSTY BOOM.
  PICK ANOTHER NAME.*, dim — a chip must never mean two things;
- one of your own on this voice → *REPLACES YOUR MY KICK. THE OLD
  SETTINGS ARE NOT KEPT.*, and the button reads REPLACE;
- anything else → SAVE.

A save lands under the factory row as a second strip, **YOURS**, with the
same chips and the same highlight rule: *MY KICK SAVED. IT IS UNDER THE
FACTORY ROW ON EVERY KIT.* The strip exists only for a voice that has
one. Tap a chip and it loads like a factory one; move a slider and the
highlight lets go, as U1's own rule has it — a preset is a starting point
to wreck. Saving under one of your names writes over it in place, which
is how a sound is iterated: save, wreck, save again.

**On disk.** One file, `presets.json` at the shelf root beside the kit
folders, holding every saved patch as the same JSON a pad's recipe
carries (a preset *is* a named patch — U1 built the format), with when
it was saved. Written whole and atomically, so a killed save leaves the
old file; an entry this build cannot read — a newer phone's engine — is
skipped on read and carried through a save untouched; a file that is not
the store's at all is never written over, so the save refuses in words
rather than erase what it could not read. The exit test holds in
`UserPresetsTest`: a saved preset read back after a restart is equal, and
renders the same bytes.

**FORGET → BIN, and the way back.** Hold one of your chips and a slip
asks first — *FORGET MY KICK? IT WAITS IN DELETED PRESETS FOR 30 DAYS.* —
with CANCEL beside FORGET → BIN in the bin's red, the dress every delete
in the app wears. The preset leaves the strip for a `binned` list in the
same file, stamped with when: *MY KICK IS OFF THE STRIP. 30 DAYS TO
CHANGE YOUR MIND.* — the same sentence every bin tells, pinned to the
one promise by `ReversalTest`, and the same sweep on launch. The door
back is **DELETED PRESETS ▸ N WAITING** under the strips, shown only
while the bin holds something, the way the shelf's DELETED KITS row is:
one row per forgotten preset with its engine, voice, when it went and
the days it has left, and RESTORE. A restored preset lands at the end of
YOURS under its own name, or — if that name was saved again meanwhile —
under the first `MY KICK 2` nothing holds, trimmed to fit the fourteen
letters; the toast says which. The save slip's note says how a preset
leaves, so the moment a sound is kept is the moment you learn to let it
go.

There is no EMPTY THE BIN NOW here, unlike the bins that hold WAVs and
kits. A preset is a few hundred bytes, so nothing is bought by emptying
early, and every site that cannot be undone is one `ReversalTest` counts
as evidence for an undo stack; this bin adds none. The sweep takes each
row when its days run out.

**What is not built.** BACKUP does not carry the file: it packs kits as
`.xpn` archives, and a preset that was sent to a pad is in that pad's
recipe already; the named chips are not. That is under Open.

**Promotion stays a code change.** A preset worth every player having is
pasted into its engine's table by hand, where `ThumpPresetsTest` and its
siblings judge it: identity (a kick still classifies as a kick), the
trademark blocklist (no `808` on the phone's say-so), and spread (it is
not a sixteenth name for one sound). No phone-side act can add to the
roster, and none should: the tests are the point. SEND TO BENCH carries
`presets.json` as a fourth file and renders every preset in the manifest
as the line its table is written in, with every macro's exact value:

```
saved presets, as roster lines for synth/src/main/kotlin/com/snipsnap/synth/ - one worth every player having is pasted under its table's rows by hand, and that engine's PresetsTest judges it:
ThumpPresets.kt
  p(ThumpVoice.KICK, "MY KICK", "TUNE" to 0.3427f, "SWEEP" to 0.45f, "DECAY" to 0.37f, "CLICK" to 0.32f, "DRIVE" to 0.35f),
```

`UserPresetsTest` holds that the line names a table and a helper that
exist for every registered engine, and that every factory preset's own
line reads back to its exact floats. One thing to know at the desk: the
count law (`ThumpPresetsTest` asks for sixteen a voice) means a promoted
preset replaces a factory row or raises that number on purpose; the
spec's exit line, "passes `ThumpPresetsTest` unchanged", is the former.

**SEND TO BENCH's words grow by one noun.** Its note reads *EVERY LABEL,
CUT RATING, NOTE AND SAVED PRESET ON THIS PHONE, IN ONE FILE FOR THE
BENCH. FEATURES, LABELS, RATINGS, KNOB SETTINGS AND YOUR OWN WORDS ONLY,
NEVER AUDIO…*; the landing toast counts presets beside the rest; a shelf
with nothing but a preset still sends. A presets file the build cannot
read is left out of the zip rather than handed out as if it were read.

Code: `UserPresets` in `:shell` (`Saved`, `Check`, `normalize`, `check`,
`suggest`, `save`, `read`, `rosterLine`, `renderAll`; the bin's `Binned`,
`forget`, `unforget`, `bin`, `sweepBin`, `freshName`), the fourth file
and the manifest lines in `BenchExport`, the copy in `Copy`, held by
`UserPresetsTest`, `BenchExportTest` and `ReversalTest`'s bin pin; SAVE
PRESET ▸, `PresetNameDialog`, the YOURS strip and its hold,
`PresetForgetDialog` and `DeletedPresetsOverlay` in `SynthScreen`, the
shelf root, the `sendToBench` change and the launch sweep in `App.kt`.

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
| WS4 | ✓ **BENCH NOTES** — above | S | `docs/BENCH.md` is answered on a laptop from memory; the phone knows the context the note is about | a note taken on PLAY names PLAY, the kit, and the time, in the manifest |
| WS5 | ✓ **SAVE AS PRESET on SYNTH** — above, outside the workshop | M | fast authoring on the phone, honest shipping on the desk — and this is the one that is really a product feature, so it should land *outside* the workshop once it works | a saved preset survives a restart and re-renders the same bytes; a promoted one passes `ThumpPresetsTest` unchanged |

The list is built. WS5 never lived in the workshop: it landed on SYNTH
for every player from the start, and only its promotion path is bench
business.

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
- **A note's stamp is read at the tap**, once, and shown before a word
  is typed: the tap is the moment the note is about, and one read means
  the line shown and the line written cannot disagree.
- **Notes are pasted, not filed.** The manifest renders every note as a
  `→` line for `docs/BENCH.md`, and that is where they land; there is no
  notes file beside the corpus because nothing scores a note. The zip
  carries `notes.jsonl` so nothing is lost between sends, not for a
  reader.
- **The NOTE chip is on the title bar**, the one row every screen
  shares, and only while the WORKSHOP is open: a note is about whatever
  is under it, and a chip per screen is one more thing per screen to
  keep in step.
- **SAVE AS PRESET is not behind the knock.** Every player would use it,
  and a thing every player would use does not belong behind a
  developer's door; the workshop gets only the promotion path, in the
  bench hand-out.
- **Promotion is a code change, judged by tests.** A phone can never add
  to the shipped roster; it can only hand the desk the line, and the
  spread, blocklist and identity tests decide.
- **A saved name replaces in place; a factory name is refused.** One
  chip, one meaning; and iterating on a sound is save, wreck, save again
  under the same name, not a trail of `KICK 1`…`KICK 9`.
- **A forgotten preset goes to the bin, and the bin's door is on SYNTH.**
  The same file, the same thirty days, the same sweep; DELETED PRESETS
  sits under the strips rather than inside DELETED KITS, because a
  preset is SYNTH's and the door belongs where the thing was lost. No
  EMPTY THE BIN NOW: nothing to gain, and one fewer site with no way
  back.

## Open

- **BACKUP carrying `presets.json`.** The restore side has to merge it
  into a shelf that may hold presets of its own, which is why it is not
  a one-line addition to the zip. Until then a preset sent to a pad is
  in that pad's recipe, which BACKUP does carry; the named chips are not.
