# WORKSHOP — the developer's bench, inside the app

*The spec for the admin mode. Status: the door and its first tool, SEND TO
BENCH, are built; the rest is the list at the end, in value order. One
tester today — the phone's owner — and the decisions below are shaped by
that.*

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
- `manifest.txt` — the stamp, the total, one row per kit with how many
  corrections it gave (a binned kit shows as `.bin/<name>-<stamp>`), and
  the two lines a person at a desk needs: where to drop the log and what
  to run. Plain prose in its own case; it is read off a laptop, never off
  the phone.

Two packs of an unchanged shelf are the same bytes (fixed entry time,
fixed order — `XpnPackager`'s trick), so a re-send is a re-send and not a
new file to diff.

**At the desk.** Unzip, copy `overrides.jsonl` into
`reference/calibration/`, run

```
./gradlew :shell:test --tests '*TeachLogTest*'
```

and read what it prints: every correction the current rules still get
wrong, with the feature numbers a threshold gets moved by, and a line
counting how many corrections the rules now agree with. The folder's own
README says the rest: a threshold moved because of this corpus is moved
in `Classifier` with a comment naming the line that motivated it.

**Refusals, in words.** Nothing logged and TEACH on: *NO CORRECTION IS
LOGGED YET. CORRECT A CHIP ON CHOP FIRST.* Nothing logged and TEACH off:
*TEACH THE MACHINE IS OFF, SO NO CORRECTION IS LOGGED. TURN IT ON ABOVE.*
No app on the phone takes a zip: the same NOWHERE TO SEND IT BACKUP says.
The landing toast counts what is in the file — *3 CORRECTIONS FROM 2 KITS
ON ONE FILE. PICK WHERE IT GOES.* — and never says SENT, because the
chooser opening is not the file leaving.

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
the same lines is a second thing to keep in step. It does not carry
confirmations: a chip left alone on CHOP is not logged today, which is the
first gap on the list below.

Code: `BenchExport` in `:shell` (`gather` walks, `pack` writes, `manifest`
says), held by `BenchExportTest` — which also checks that the folder and
the harness the manifest names actually exist, so the instruction inside
the zip cannot go stale without a test saying so. The button and the
`sendToBench` action mirror BACKUP's shape in `PropertiesScreen` and
`App.kt`.

## The list — next tools, in value order

Sizes as `docs/APP_PLAN.md` uses them (S under a day, M a few days).
Every tool's logic lands in `:shell` with tests and `:app` only binds
Compose to it — the repo's one architectural rule, and here it matters
twice: `:app` has no test source set, and a cloud session cannot compile
it.

| # | Tool | Size | What it answers | Exit test |
|---|---|---|---|---|
| WS1 | ✓ **SEND TO BENCH** — above | S | gets the teach log to the harness | a phone's corrections show up in `TeachLogTest`'s report |
| WS2 | **CONFIRM ALL on CHOP** — with TEACH on, one tap logs every chip the user *left alone* as a confirmed example; plus a **CHOP RATING** row (1–5) logged with the bench's own settings (hits, ear, cut, grid, merges, splits) | S–M | today the log holds errors only, so it can measure misses but never accuracy; nothing at all measures the *cuts* | a confirmed-and-corrected chop replays through the feature path with the right accuracy; a rating line names the detector config that earned it |
| WS3 | **LABEL THIS HIT** on the pad sheet — writes the pad's WAV as `<class>_<kit>_<pad>.wav` into a `Calibration/` folder beside the kits, and SEND TO BENCH packs that folder too, **behind its own button and its own note**, because this one carries audio | S | `reference/calibration/` has zero real captures; BENCH A5 has asked for a dozen since the corpus was named | a labelled hit from the phone lands in `CalibrationCorpusTest`'s confusion matrix |
| WS4 | **BENCH NOTES** — a free-text note stamped with the screen, the open kit and the time, packed with the zip | S | `docs/BENCH.md` is answered on a laptop from memory; the phone knows the context the note is about | a note taken on PLAY names PLAY, the kit, and the time, in the manifest |
| WS5 | **SAVE AS PRESET on SYNTH** — a user preset store (`presets.json` beside the kits), listed under the factory rows; promotion into the Kotlin roster stays a code change guarded by the spread, blocklist and identity tests | M | fast authoring on the phone, honest shipping on the desk — and this is the one that is really a product feature, so it should land *outside* the workshop once it works | a saved preset survives a restart and re-renders the same bytes; a promoted one passes `ThumpPresetsTest` unchanged |

WS2 before WS3 because it costs nothing in rights and doubles the value of
every line already logged; WS3 before WS4 because a dozen real kicks is
the single most valuable file this repo could receive; WS5 last because it
is the one that should not stay here.

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

## Open

- Whether WS2's confirmations should be logged by default whenever TEACH
  is on, or only on the explicit tap. Default logging makes the accuracy
  number honest with no extra gesture; the explicit tap keeps "every line
  is something a human decided" literally true. Leaning explicit.
- Where WS4's notes should land at the desk — appended to
  `docs/BENCH.md`'s `→` lines by hand, or kept as their own file beside
  the corpus. Undecided until there are notes.
