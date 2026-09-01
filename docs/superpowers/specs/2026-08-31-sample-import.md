# IMPORT — bringing outside samples into SnipSnap

**Status:** spec, not yet planned into tasks.
**Why it exists:** Splice has no public API and never has — its DAW
integrations are private partnerships, and reverse-engineering its client
would be unauthorized, fragile, and against its terms. But Splice
*downloads land as ordinary WAVs on the device*, as do Loopcloud's,
Noiiz's, Arcade's, and a friend's USB stick's. The right integration is a
folder importer, not a vendor deal — it works with every library at once
and depends on nobody's uptime.

**Licensing note (holds for the design, not legal advice):** a subscriber
importing sounds they licensed, into their own kits, is the ordinary use
those licences contemplate. The importer never redistributes anything: it
reads files the user already has and writes kits into the app's own
storage. No sample leaves the phone.

## What already exists (this is why the build is small)

| Need | Engine | State |
|---|---|---|
| read WAV | `audio/WavReader`, `Cleanup.toMono` | tested |
| read compressed (mp3/m4a) | `audio/DecodeContract` defines the contract; the app-side `MediaExtractor` decoder is NOT built | **gap** |
| what kind of drum is this | `audio/Classifier.classify(snip)` | tested |
| which pad does it go on | `audio/AutoPlace` | tested |
| slice a loop | `audio/Chopper.byTransients` | tested |
| tempo of a loop | `audio/Tempo.estimate` (folds 70–180, has `label` = "92bpm") | tested |
| key of tonal material | `audio/KeySpec`, `Pitch`, `Scales` | tested |
| build a kit folder | `kit/KitAssembler.assembleArranged` | tested |
| put one sound on one pad | `shell/KitBuilder.assign(slot, snip, drumClass, displayName)` | tested |
| review + correct the classifier | CHOP screen | shipped |
| cleanup chain (trim/normalise) | `audio/Cleanup` | tested |

The importer is mostly **wiring**, plus one real new piece (compressed
decode) and one screen.

## Scope

### IN
1. **Pick a folder or files** via Android's Storage Access Framework
   (`ACTION_OPEN_DOCUMENT_TREE` / `OPEN_MULTIPLE_DOCUMENTS`). No storage
   permissions, works with Splice's own folder wherever the user keeps it,
   and survives scoped storage. SAF gives `Uri`s, not `File`s — see
   Constraints.
2. **Triage each file** by what it is:
   - **One-shot** (short, single transient) → classify → auto-place on a pad.
   - **Loop** (long, rhythmic, tempo confident) → offer CHOP: hand it to
     the existing chop flow rather than importing it whole.
   - **Tonal one-shot** → classify TONAL, detect pitch/key, note it.
   The long/short boundary and "is it a loop" should reuse whatever
   `Chopper.autoSliceCount` / `Tempo.estimate().confidence` already
   imply — do not invent new heuristics.
3. **Filename metadata**, because every library bakes it in and it's free:
   parse `92bpm`, `Am`, `C#min`, `140`, key/scale words from the stem, and
   prefer them over detection when present and plausible (detection stays
   the fallback and the cross-check). `Tempo.label` already writes this
   format, so the parser is the inverse of code that exists.
4. **Review screen before commit** — the import equivalent of CHOP's
   "YOU ✓": a list of what was found, what class each got, which pad it's
   heading to, with the same chip-tap correction. Nothing is written until
   the user says so.
5. **Land as a new kit** (via `KitAssembler`) or **into the open kit's
   empty pads** (via `KitBuilder.assign` — the SYNTH lab's own door).
6. **Teach the classifier**: corrections here feed `TeachLog` exactly as
   CHOP's do, gated by the same X4.4 consent toggle.

### OUT (this pass)
- Any vendor API or account linking. There isn't one; there won't be.
- Redistribution, sharing, or cloud sync of imported audio.
- Watching folders / background sync. Manual, user-initiated import only.
- Deep tagging or a browser. The kit is the unit of organisation.

## Constraints and the honest hard parts

1. **SAF gives `Uri`, the engines take `File`.** Every engine signature in
   the table above is `File`-based. Either copy the picked bytes into app
   storage first (simple, doubles disk for the import moment, keeps every
   engine untouched) or teach `WavReader` a stream overload (cleaner, wider
   blast radius). **Recommendation: copy-first.** The user's library stays
   untouched, the app owns what it imported, and nothing in `:audio`
   changes.
2. **Compressed formats are a real gap.** Splice serves WAV, so a
   WAV-only v1 is genuinely useful — but users will drop mp3s in.
   `DecodeContract` already specifies exactly how a device decoder must
   behave and how to verify it; building the `MediaExtractor` path is its
   own task with its own instrumentation test. **v1: WAV only, and say so
   in the picker's empty state.** mp3/m4a is a fast follow, unblocked by
   this spec.
3. **Volume.** A Splice folder can hold thousands of files. Import must be
   cancellable, must not decode everything up front (classify from a
   short head-window where possible), and must never block the composition
   thread — the standing rules apply.
4. **Sample rates vary.** 48k/44.1k mixed in one folder is normal. Kits
   are built at a single rate; `Resampler` exists — decide the kit's rate
   from the majority or the open kit's existing rate, and resample the rest
   rather than refusing.
5. **Names.** Library filenames are long and full of characters MPC export
   won't take. `Names.isMpcSafe`/`sanitizeStem` already exist and must be
   applied at kit-write time, while the review screen shows the original
   name so the user recognises their own sample.

## The shape of the work (rough tasks, for a later plan)

1. `:kit` or `:shell` — `SampleImport`: pure logic. Given a list of
   (name, Snip), triage → classify → propose placements → return a
   reviewable model. Fully JVM-testable, no Android.
2. Filename metadata parser (`:audio`, next to `Tempo`) — bpm/key/scale
   from a stem, with tests over real-world library naming.
3. `:app` — SAF picker + copy-into-app-storage, feeding (1).
4. `:app` — IMPORT review screen (CHOP's idiom, chip corrections, teach
   gating), landing via `KitAssembler` or `KitBuilder.assign`.
5. Menu entry + routing. `AppScreen` gains IMPORT.
6. *(follow-up, separate)* compressed decode via `MediaExtractor` against
   `DecodeContract`, with the device instrumentation test it specifies.

## Answered (user, 2026-08-31)

- **Where:** on the FRESH TAPE menu — IMPORT is a starter, the seventh
  entry alongside the six generated ones. A kit begins either by the
  machine inventing sound or by the user bringing it; the shelf is where
  both belong. The review list therefore lives in a full-screen flow
  launched *from* that menu, not in the sheet itself.
- **Loops:** offer BOTH. A detected loop presents two actions — CHOP IT
  (hands the loop to the existing chop flow) or KEEP IT WHOLE (imports as
  one LOOP-class pad, which the design already has: LOOP `#3F8CF0`, and
  W12's mini-waveforms already decay slower for loops). Default the
  highlight to CHOP IT, since slicing is what a sampler is for, but never
  decide for the user.
- **Overwriting:** never. Import writes to empty pads or a brand-new kit,
  full stop. If a target kit has no room, say so plainly and offer a new
  kit instead. Replacing a pad's sound is the PAD SHEET's job and stays
  there — an importer that can silently overwrite is an importer nobody
  trusts with a thousand-file folder.
