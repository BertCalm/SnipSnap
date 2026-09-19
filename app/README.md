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
schemes in Tape Properties. The FRESH TAPE menu (eight starters from
`StarterKits`, rendered by the `:synth` engines on-device) makes the
shelf useful before capture (M1) exists.

## What's here

| Piece | Source |
|---|---|
| Scaffold | `build.gradle.kts` (AGP 8.7.3, Kotlin 2.0.21 + Compose plugin, minSdk 29, foundation-only Compose — no Material; TapeOS draws itself) |
| Theme | `theme/` — `:shell`'s `Schemes`/`Type`/`Layout`/`Motion` tables bound to Compose; bevel/LCD/desk modifiers; OILSLICK sweep |
| Window | `ui/Chrome.kt` — SNIPSNAP.EXE titlebar, 9-item menu row, 3-cell status bar with `Copy` quips, toast overlay |
| Screens | SHELF (the shelf + FRESH TAPE), KIT (4×4 bank A, MPC geometry: A13 top-left, A01 bottom-left), SETUP (live scheme picker + PERSONALITY), HELP, honest stubs naming M2–M5 |
| Data | `KitShelf` over `KitStore` (kits under app files/Kits). Every screen that makes a sound is on `PadEngine` now — the SoundPool interim is gone, and choke and velocity belong to `VoiceAllocator` beside it |
| Native | `src/main/cpp/` — one library, two engines under Oboe (prefab, `com.google.oboe:oboe`, C++17): the SURFACE engine (a control ring, per-sample `ParameterSmoother`s, the `PrintBuffer` resample tap) and M4's `PadEngine` (32 sample voices, a command ring in and an endings ring out, the kit's bank adopted whole). `OboeOutput.h` opens every stream (Exclusive, then Shared). `NativeSurface`/`SurfaceEngine.kt` and `NativePads`/`PadEngine.kt` own them from Kotlin. The NDK is pinned in `build.gradle.kts` and AGP fetches it. `src/main/cpp/test/` drives both callbacks by hand on the host (`cmake -S app/src/main/cpp/test -B build/native-tests && cmake --build build/native-tests && ctest --test-dir build/native-tests`); CI's `native-tests` job runs it |
| Fonts | `res/font/` — VT323, Silkscreen, Michroma, Permanent Marker, committed |

## Things to verify on first run (beyond "does it compile")

For an actual session with the phone in hand, work from
[`../docs/BENCH.md`](../docs/BENCH.md): it orders everything below
against the hardware checks and gives each one a line to answer on. The
list here stays the per-feature detail — what to look at, and which
logcat tag to grab when something is wrong.

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
  chooser with `<Kit>.xpn`; on SHELF, BACKUP with `SnipSnap Shelf <date>
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
  enough to drum on — that is the milestone's exit test, and the header
  now carries the device's own round-trip latency beside VOICES to put a
  figure next to the judgement (`— MS` = the device declined to measure;
  a trailing `SHARED` = it refused the exclusive path). VOICES should
  count down as one-shots end (the engine reports endings; nothing is
  timed), a closed hat should cut an open one with no click, a gate pad
  should stop on release, PANIC should fade everything in 20 ms, and
  swapping kits mid-roll should go silent rather than crash. "NO STREAM"
  in the header means the device refused every open; check logcat's
  `PadEngine` line.
- **KIT's grid (native, EEE4)**: open a kit and tap pads on the 4×4
  grid — same engine PLAY uses, so it should feel just as tight, not
  the SoundPool preview's decode lag. A closed hat should still cut a
  ringing open one (the mute group chokes here too); tapping the same
  pad rapidly should retrigger cleanly rather than layering forever;
  editing a pad on PAD SHEET and coming back to KIT should play the
  edited audio, not a stale cache.
- **KEYS (native)**: open an instrument from the shelf. A held note
  should sustain through its loop and let go over the instrument's
  release; eight notes at once, a ninth steals the oldest; OCT ± and a
  layout change mid-note should go silent, never stick. The zones load
  off the main thread, so the first key after opening may be silent for
  a moment on a big instrument.
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
  corners and the pad are in `surface.json` beside the kit. Then flip
  the print destination to → PAD: STOP PRINT opens the slot chooser;
  an empty pad gets the print, a taken pad is replaced with the
  original in the bin, CANCEL sends the print to TAPE instead. LATCH,
  lift: the loop should hold where the finger left it. With LATCH off,
  lift fully and tap again after a pause: the loop should retrigger
  from its head, like a drum hit, not continue from wherever it had
  drifted to while released. With a kit that has a tempo, BARS to 2
  and PRINT: the print should stop itself on the bar (5.2 s at 92 BPM)
  and the toast should say so. Then PAD2 ►, PAD3 ► and PAD4 ► to pick
  three more pads: the finger should now blend all four continuously by
  its own position - PAD loudest near the top, PAD2 near the bottom-left,
  PAD3 near the bottom-right, PAD4 near the bottom-centre (directly under
  PAD), with no dead spot anywhere on the pad - while pitch/filter/mode
  keep moving exactly as PAD alone did. With only PAD2 loaded, the
  bottom-right corner should still sound (PAD/PAD2 blended, no PAD3
  silence-hole); with nothing but PAD loaded, the whole pad should sound
  exactly like before PAD2/PAD3/PAD4 existed. Sweep the finger slowly
  across the bottom-centre seam (where the pad's two half-triangles
  meet, directly under PAD) - PAD4 should fade in and out smoothly with
  no click at the seam. This is stage 2 of `design/surface-vector`'s
  concept - the sample side of the sketch, now actually touch-driven.
  Then switch to VECTOR (stage 3, the effects side): the corner bars
  should appear exactly as they do in MORPH - SET A..D still works, and
  morphing the corners should sound the same as it does in MORPH - while
  the readout's SMPL line (with PAD2/PAD3/PAD4 loaded) keeps moving too:
  one finger, two independent blends, neither one visibly affecting the
  other's numbers. Then stage 4b, the named preset library: ARM B (it
  should dim in and the others stay lit), then PRESET ► - the readout
  should read "LBP +", then "ECHO +" on the next press, continuing through
  "ECHO -"/"LBP -"/"CRUSH +"/"CRUSH -"/"GLITCH +"/"GLITCH -"/"SPRING +"/
  "SPRING -" and wrapping back to "LBP +"; morph toward B and it should
  sound like whichever preset is showing. PRESET ◄ ► with no corner armed should do
  nothing and the row should read "ARM A CORNER". Leave the screen and
  come back - a stepped corner is stored exactly like a captured one (the
  same `corners` field in `surface.json`), so it is still there, though
  the PRESET row itself starts unarmed again (arming doesn't persist).
  Then stage 5, a real CRUSH and ECHO in the engine itself: PRESET ►
  through to "ECHO +" or "ECHO -" (now genuinely wet) and morph a corner
  toward it - repeats should be audible roughly a fifth of a second
  behind the dry sound, and should keep ringing on their own for a
  moment after you lift off the pad, fading rather than cutting off
  with the touch. SET A..D still captures crush/echo the same way it
  captures pitch/cutoff/resonance/drive when the mode is MORPH or
  VECTOR - blend toward a crushed/echoing corner, SET a fresh one, and
  the fresh capture should carry the same crush/echo the blend was
  playing. CLEAN/DARK/LOW/HOT and LBP +/LBP - stay untouched (no crush,
  no echo) - the whole pad should sound exactly as before this stage
  existed until a corner actually carries a nonzero crush or echo.
  Then stage 6, two more library pairs built on the same crush/echo:
  PRESET ► on to "CRUSH +" should sound audibly gritty with no repeats,
  "CRUSH -" grittier still (more of the sample-and-hold texture, darker);
  neither should have any echo tail after release. "GLITCH +" and
  "GLITCH -" should sound like CRUSH's grit *and* ECHO's repeats at once -
  "GLITCH -" the more extreme, chaotic end of the pair (pitched down
  slightly, heavier on both macros) - a texture neither LBP nor ECHO nor
  CRUSH alone can reach.
  Then stage 7, a real SPRING (reverb) in the engine itself: PRESET ►
  through to "SPRING +" and morph a corner toward it - a diffuse, smeared
  tail should bloom after the dry sound rather than a single discrete
  repeat, and should keep decaying on its own for roughly half a second
  or so after you lift off the pad, fading smoothly to silence rather
  than looping or cutting off with the touch. "SPRING -" should sound
  darker and further stacked than "SPRING +", with neither carrying any
  crush or echo. SET A..D still captures spring the same way it captures
  the other macros when the mode is MORPH or VECTOR. CLEAN/DARK/LOW/HOT
  and every corner from stages 4b/5/6 stay untouched (no spring) - the
  whole pad should sound exactly as before this stage existed until a
  corner actually carries a nonzero spring.
  Then GRAIN, a fifth mode: tap it and a SIZE/DENSITY/SPRAY row appears
  under PAD ◄ ►. A held finger should give a cloud of short grains rather
  than the loop - sliding left and right scrubs *where* in the pad's
  sample they come from (POSITION), and sliding up and down steps the
  pitch through the kit's key, in discrete notes, never a glide (the
  readout's KEY names the key; a kit with none steps in semitones). With
  a tonal pad (a sustained note) at the middle of the pad, the cloud
  should sound *in tune* even if the pad itself was a little flat or
  sharp - the snap retunes as well as quantises. Tap the row's first
  button to cycle SIZE → DENSITY → SPRAY and ◄ ► to step the one showing:
  SIZE down to 0% is a buzz of clicks, up to 100% a smear of
  quarter-second grains; DENSITY down to 0% is two grains a second with
  silence between, up to 100% a continuous wash; SPRAY at 0% freezes the
  cloud on the exact spot under the finger, at 100% it scatters across
  the whole sample. Loudness should stay roughly level as SIZE and
  DENSITY move (each grain is scaled by its overlap), and a tap should
  sound at once, not after a wait, even at DENSITY 0%. Tilt still sets
  resonance here. Switch back to XY: the loop should be back, the cloud
  gone. Leave and return: the three knobs are in `surface.json` with the
  pad and the corners. The host harness proves the arithmetic
  (`grain_*` and `surface_engine_grain_*` in `test/engine_tests.cpp`,
  the bridge in `jni_tests.cpp`); what it cannot prove is how it feels.
  Then MOD, the modulators: on the MOD row (any mode), with MOD A showing
  and the field button reading TARGET, ◄ ► should walk through the seven
  macros and then SIZE/DENSITY/SPRAY/POSITION; tap the field button to
  DEPTH and ► up to 40% or so with CUTOFF as the target and SINE the
  shape - a held finger (or LATCH) should now breathe open and shut once
  a bar at the kit's tempo, around wherever the finger holds the filter,
  in XY and in MORPH alike. RATE at 1/16 BAR is a flutter, 4 BARS a slow
  tide; RAMP climbs and drops, RANDOM jumps once a bar and holds - the
  same jumps every time the bar comes round. In GRAIN, MOD B on POSITION
  as a 4-bar RAMP at 100% should walk the cloud through the sample with
  no finger movement at all. PRINT while a modulator runs: the print
  moves the way the surface did. Both slots are in `surface.json`; a kit
  with no tempo runs them at GROOVE's own default. TARGET also lists X
  and Y, the finger itself: in XY, RANDOM on X at 50% with LATCH on
  should land the loop on a new pitch every bar with no finger on the
  pad, and the puck should jump to show where; in MORPH, RANDOM on X
  (MOD A) and Y (MOD B) at 100% should hop between the corners on the
  bar - a sequencer without a sequencer; SET A while the puck is being
  nudged should capture where your finger actually is (touch, hold, SET
  A, then morph to A: the corner is the finger's sound, not the wobble's).
  Then SHAPE to FOLLOW with nothing listening: the toast should name both
  doors on SHELF and the readout should read ROOM where the rate was.
  LISTEN · MIC on SHELF, back to SURFACE, MOD A on CUTOFF, FOLLOW, DEPTH
  60%, a finger held low on the pad: a clap should open the filter at
  once and it should close again over about a quarter of a second, and a
  quiet room should sound exactly like DEPTH 0%. Then APP AUDIO with a
  track playing in another app, DUCK on CUTOFF at 60%: the surface should
  dip under every kick like a sidechain. Back at DEPTH 0% the surface
  should sound exactly as before the MOD row existed. Five buttons and a readout on one row is the densest line on
  this screen - check it fits at 390dp.
  Then RING, the capture ring as a voice: with nothing listening, a tap
  on RING should name both doors on SHELF (LISTEN · MIC, APP AUDIO) and
  load nothing. LISTEN · MIC on SHELF, talk or clap for a few seconds,
  back to SURFACE, RING: the toast says how many seconds of the mic it
  froze (up to 4, less if the ring has held less), the PAD readout reads
  "RING" with that length, RING is lit, and a finger on the pad
  should loop those seconds with pitch across and filter up exactly as a
  pad would - GRAIN over them too, and if the freeze was a held note the
  pitch axis should snap it into the kit's key. RING again should take a
  fresh few seconds (say something different first). Then STOP on SHELF,
  APP AUDIO ▸ RECORD AN APP, play a video in another app, back to SURFACE,
  RING: the video's last 4 s under the finger - the toast names APP
  AUDIO this time. PAD ► should bring a pad back and unlight RING; so
  should leaving the kit and coming back (a freeze is not in
  `surface.json`). RING in the first moment after LISTEN, before the ring
  holds anything, should say "HEARD NOTHING YET" rather than load
  silence. Six buttons on the PAD row now - check that one at 390dp too.
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
