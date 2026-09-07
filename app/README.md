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
- **OUTSIDE (pad sheet)**: `OutsideSession` records and plays at once —
  a `MODE_STATIC` float `AudioTrack` against a float `AudioRecord` at the
  pad's rate. Verify on a phone: the speaker into the room reamps a pad
  with the room on it and the toast names the trip in ms; a wired jack
  into a pedal and back reamps through the pedal; the mic route stays
  UNPROCESSED/VOICE_RECOGNITION (a source with echo cancellation would
  remove exactly the send). If the return is silent, check that ARM has
  granted RECORD_AUDIO and that no armed session holds the mic — the
  card refuses both in words before playing.

## Fonts / licensing

The four faces were fetched from Google Fonts (open licenses — OFL /
Apache 2.0 families). Fine for development and sideloaded test builds;
before any public release, confirm each family's license on its Google
Fonts page and bundle the license texts.
