# :app — the Android shell (M0, pre-written)

This tree is APP_PLAN.md's **M0 walking skeleton**, written ahead of time
by the cloud session — which **cannot compile it**: that environment has
no Android SDK and its network policy blocks `dl.google.com`. Every other
module on this branch is tested; this one is carefully written, reviewed
Kotlin that has **never been through a compiler**. Treat it accordingly.

## Building (desktop session or any machine with an Android SDK)

1. Point the build at an SDK — any one of:
   - `local.properties` at the repo root with `sdk.dir=/path/to/Android/sdk`
   - `ANDROID_HOME` / `ANDROID_SDK_ROOT` in the environment

   `settings.gradle.kts` includes `:app` only when it can locate an SDK,
   so the pure-JVM modules (and the cloud session) build exactly as
   before whether or not one is present.

2. `./gradlew :app:assembleDebug`

3. Fix what the compiler finds. The likely nit categories, in honesty
   order: Compose API drift against the pinned BOM (2024.12.01), an
   import the blind write missed, a modifier-order surprise. The
   architecture is deliberately boring — no algorithm lives here, every
   screen binds to `:shell`/`:kit` code that is already tested.

4. Install: `adb install app/build/outputs/apk/debug/app-debug.apk`
   (or copy the APK to the phone and tap it).

## M0's exit test (from docs/APP_PLAN.md)

Browse kits on a phone, tap pads, hear WAVs (interim `SoundPool`), flip
schemes in Tape Properties. The FRESH TAPE menu (six starters from
`StarterKits`, rendered by the `:synth` engines on-device) makes the
shelf useful before capture (M1) exists.

## What's here

| Piece | Source |
|---|---|
| Scaffold | `build.gradle.kts` (AGP 8.7.3, Kotlin 2.0.21 + Compose plugin, minSdk 29, foundation-only Compose — no Material; TapeOS draws itself) |
| Theme | `theme/` — `:shell`'s `Schemes`/`Type`/`Layout`/`Motion` tables bound to Compose; bevel/LCD/desk modifiers; OILSLICK sweep |
| Window | `ui/Chrome.kt` — SNIPSNAP.EXE titlebar, 9-item menu row, 3-cell status bar with `Copy` quips, toast overlay |
| Screens | KITS (shelf + FRESH TAPE), KIT (4×4 bank A, MPC geometry: A13 top-left, A01 bottom-left), SETUP (live scheme picker + PERSONALITY), HELP, honest stubs naming M2–M5 |
| Data | `KitShelf` over `KitStore` (kits under app files/Kits); `PadPlayer` (SoundPool interim — choke/velocity belong to M4's Oboe allocator) |
| Native | `src/main/cpp/` — one library, two engines under Oboe (prefab, `com.google.oboe:oboe`, C++17): the SURFACE engine (a control ring, per-sample `ParameterSmoother`s, the `PrintBuffer` resample tap) and M4's `PadEngine` (32 sample voices, a command ring in and an endings ring out, the kit's bank adopted whole). `OboeOutput.h` opens every stream (Exclusive, then Shared). `NativeSurface`/`SurfaceEngine.kt` and `NativePads`/`PadEngine.kt` own them from Kotlin. The NDK is pinned in `build.gradle.kts` and AGP fetches it. `src/main/cpp/test/` drives both callbacks by hand on the host (`cmake -S app/src/main/cpp/test -B build/native-tests && cmake --build build/native-tests && ctest --test-dir build/native-tests`); CI's `native-tests` job runs it |
| Fonts | `res/font/` — VT323, Silkscreen, Michroma, Permanent Marker, committed |

## Things to verify on first run (beyond "does it compile")

- **SoundPool vs the kit WAVs**: starters render standard PCM WAVs;
  confirm depth/rate decode cleanly. If any pad is silent, check the
  logcat `SoundPool` line first.
- **Scheme flip repaint**: every colour flows from `LocalScheme`, so a
  SETUP flip should repaint the whole window instantly; a stale surface
  means a colour got captured outside the composition local.
- **First FRESH TAPE dub time** on a real phone (the synth render is
  seconds on desktop JVM; status bar shows DUBBING… meanwhile).
- **IMPORT (share sheet)**: share a WAV, an MP3 and a screen-recorded
  MP4 into SnipSnap from another app. Each should land on TAPE with a
  "TAPED FROM OUTSIDE" toast naming its length; a share while the app is
  already open must land in the same window (`singleTask` +
  `onNewIntent`), not a second one. Then run the decode-contract twins
  (`reference/fixtures/decode/README.md`) through `MediaDecode.decode`
  in an instrumentation test and assert `DecodeContract.verify` passes
  for all six — that is F3.3's exit test, and the first proof the codec
  loop reads the output format correctly on this phone.
- **SHARE / BACKUP / a kit landing**: on KIT, SHARE should open the
  chooser with `<Kit>.xpn`; on KITS, BACKUP with `SnipSnap Shelf <date>
  .zip`. Send either to yourself (Drive, a messenger) and share it back
  into SnipSnap: the kit lands beside the original as "NAME 2" (a backup
  lands every kit) and KIT opens on it. Then zip an MPC-saved `.xtd`
  with its `_[TrackData]` folder and share that in: it should land too,
  while the bare `.xtd` alone refuses in words. If the chooser never
  appears, check the `FileProvider` authority (`<applicationId>.files`)
  against `res/xml/share_paths.xml`.
- **The landing's message box**: share in a ZIP holding one good `.xpn`
  and one folder with a broken `kit.json`. No toast: a message box opens,
  titled "1 KIT LANDED ON THE SHELF. 1 SKIPPED.", with the skipped folder
  and the door's reason in the warn colour first and "LANDED · <NAME>"
  after; FINE (or the scrim) closes it. Share in a text file: the box
  reads "NOTHING LANDED.", the file's name, then the refusal in words.
  Break one kit's WAV and BACKUP: after the chooser closes, the box names
  that kit with preflight's reason and lists the packed ones. A clean
  landing and a full backup still get their two-second toast.
- **PLAY (native, M4)**: open a kit, tap PLAY. Pads should feel tight
  enough to drum on — that is the milestone's exit test. VOICES should
  count down as one-shots end (the engine reports endings; nothing is
  timed), a closed hat should cut an open one with no click, a gate pad
  should stop on release, PANIC should fade everything in 20 ms, and
  swapping kits mid-roll should go silent rather than crash. "NO STREAM"
  in the header means the device refused every open; check logcat's
  `PadEngine` line. KIT's own grid still plays through SoundPool until
  this has been heard (EEE4 moves it).
- **SURFACE**: open a kit, tap SURFACE. A finger on the pad should loop
  the first pad with pitch across and filter up; XYZ's second finger
  should open the drive with the pinch; MORPH's corners should sound
  like four different pads. PRINT, play, STOP PRINT: the toast names
  the length and TAPE has the print. Then the two native checks — pull
  the headphones mid-gesture (the stream should come back on its own),
  and watch logcat's `SurfaceEngine` line: "exclusive openStream
  failed - trying shared" means the device refused the exclusive
  path and the shared fallback is playing (the toast says so too).
  Then PAD ◄ ► through the kit, find a sound in XYZ, SET A, three more,
  switch to MORPH and morph; leave the screen and come back - the
  corners and the pad are in `surface.json` beside the kit.
- **OUTSIDE (pad sheet)**: `OutsideSession` records and plays at once —
  a `MODE_STATIC` float `AudioTrack` against a float `AudioRecord` at the
  pad's rate. Verify on a phone: the speaker into the room reamps a pad
  with the room on it and the toast names the trip in ms; a wired jack
  into a pedal and back reamps through the pedal; the mic route stays
  UNPROCESSED/VOICE_RECOGNITION (a source with echo cancellation would
  remove exactly the send). If the return is silent, check that ARM has
  granted RECORD_AUDIO and that no armed session holds the mic — the
  card refuses both in words before playing.
- **Pad Sheet v2 (the boxes)**: open a pad's sheet — below GHOSTS sit
  five group boxes (TREATMENT, SHAPE, MUTATE, OUTSIDE, MAKE), all closed,
  each strip reading what the pad carries or UNTOUCHED; the whole sheet
  fits one screen. Tap a strip: it opens, the others stay closed, EJECT
  scrolls away but ◄ ► stay pinned at the bottom. Press A03 ►: the same
  box is open on the next pad. Open OUTSIDE and SEND a ROOM trip: the
  box's strip turns cyan and two reels turn on an LCD strip beside
  LISTENING…, then SENDING…; SEND reads the stage dimmed.
- **ROOMS on the shelf**: after KEEP ROOM, THE SHELF grows a ROOMS
  section under INSTRUMENTS, one row per room with its measurement and
  length. Hold a row: it presses and shows FORGET → BIN; tap it and the
  toast says the bin keeps it 30 days; the row is gone from the shelf and
  `Rooms/.bin/` holds the pair. Tap the room's name again to let go.
  Under the live rooms an IN THE BIN list appears with the forgotten room,
  its days left on an LCD (the last two days in the warn colour), and
  RESTORE; tap it and the room is back among the live rows with the toast
  "…IS BACK ON THE SHELF", and the bin list shrinks or disappears.
- **ANOTHER KIT (MUTATE)**: with two kits on the shelf, open a pad on
  one and the MUTATE card grows an ANOTHER KIT · PICK ITS PAD row under
  ROOMS: one chip per other kit, none for this one. Tap a kit and its
  pads appear as A01…-style chips, four to a row; tap one and the partner
  line reads "SOUL A03". MUTATE ▸ MORPH: the child's lineage names the
  other kit's pad the way a deal would ("Soul:A03") and the recipe carries
  the other kit's name. A kit whose folder no longer loads is simply not
  offered. UNDO puts the parent back byte for byte.
- **A FILE (MUTATE)**: on the MUTATE card, under ANOTHER KIT, A FILE ▸
  PICK ONE OFF THE PHONE opens the system picker on audio. Pick a WAV or
  an MP3: the button reads "A FILE ▸ CLAP.WAV" and it is the partner;
  MUTATE ▸ STACK and the child's lineage names "clap.wav", as the CLI's
  `--with clap.wav` would. Cancel the picker and nothing changes. Pick a
  silent file or one too big for the tape and the toast reads NOT A
  PARENT with the reason. UNDO puts the parent back byte for byte.
- **KEEP ROOM (pad sheet)**: after a ROOM trip the OUTSIDE card's KEEP
  ROOM button lights; tap it and the toast names the room ("FUNK ROOM IS
  ON THE SHELF…"), a `Rooms/FUNK ROOM.wav` + `.json` pair appears beside
  the kits, and the MUTATE card grows a ROOMS row with that room already
  the partner. Open another kit's pad, pick the room, MUTATE ▸ ROOM: the
  pad plays inside it with no trip. A REAMP trip leaves KEEP ROOM dim.

## Fonts / licensing

The four faces were fetched from Google Fonts (open licenses — OFL /
Apache 2.0 families). Fine for development and sideloaded test builds;
before any public release, confirm each family's license on its Google
Fonts page and bundle the license texts.
