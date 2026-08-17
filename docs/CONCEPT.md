# SnipSnap — Concept

## Problem

Sampling on a phone is a chore. You hear something worth grabbing — in a video,
in a track, in the room — and by the time you've opened a recorder, found the
right screen, and hit the button, the moment is gone. Then getting the result
onto an MPC means a computer, a file manager, and a format you have to learn.

## Product

An Android app that does three things well:

1. **Retroactive capture.** A rolling buffer is always running, so you snip
   *after* you hear the thing.
2. **Kit building.** Snips land on a 4×4 pad grid that mirrors an MPC bank.
3. **Native export.** Out comes an MPC drum program folder — `.xpm` plus WAVs —
   written straight to the SD card the MPC boots from.

### Why the rolling buffer is the whole product

Nobody hits record in time. A 60-second ring buffer running behind a foreground
service means the user taps *Snip* after the fact and drags handles backward
through a waveform to find the moment. Everything else in the app is downstream
of that one decision.

### Why Android specifically

Android can hear other apps. `AudioPlaybackCapture` (API 29+) plus a floating
overlay button means you can capture from inside YouTube without ever leaving
YouTube. iOS has no equivalent. This is not a port of an iOS idea — it is an
app that can only exist on Android.

See [`ANDROID_CAPTURE.md`](ANDROID_CAPTURE.md) for the mechanics and the
significant caveats.

## Core flows

### Capture

```
Start a Snip Session          → one MediaProjection consent dialog
  foreground service starts   → 60s ring buffer running, ongoing notification
  floating bubble appears     → overlays every app
User opens any source app     → YouTube, Bandcamp, a DAW, a video file
Hears something → taps bubble → last 60s is already in memory
```

Sources, in priority order:

1. **Other apps' playback** — the headline feature, via MediaProjection
2. **Microphone** — always works, no caveats, genuinely useful for found sound
3. **Files** — shared audio/video in via the share sheet; also the fallback path
   for apps that opt out of playback capture

### Trim

Waveform view over the captured buffer. Drag in/out handles. Snap to zero
crossings. Preview on loop. Everything after this point assumes a clean one-shot.

Automatic cleanup on commit:

- trim leading/trailing silence below a threshold
- DC offset removal
- normalize to a target peak/LUFS
- 5 ms fade in/out (kills clicks — non-negotiable for pad triggering)
- resample to 44.1 kHz, quantize to 24-bit PCM

### Build the kit

4×4 grid, one bank, matching an MPC's pad layout. Per pad: sample, name, colour,
level, pan, tune, mute group, one-shot vs note-off. Hold to preview. Drag to
reorder. Long-press to clear.

### Export

Write a program folder to the MPC's SD card or USB stick via the Storage Access
Framework. See [`MPC_EXPORT.md`](MPC_EXPORT.md).

## MVP cut

Ship this and nothing else:

1. Foreground service + MediaProjection capture into a 60 s ring buffer
2. Floating overlay snip button + Quick Settings tile
3. Waveform trim editor
4. 4×4 grid, drag-to-assign, low-latency preview
5. Cleanup DSP → 24-bit / 44.1 kHz WAV
6. XPM writer + direct-to-SD export

One bank. One velocity layer per pad. No auto-anything.

That is a complete loop from "I heard something" to "it's on my MPC," and
everything below is additive on top of a thing that already works.

## Deliberately v2

- **Auto-chop UI** — the detection and slicing themselves are done and tested
  (`Transients`, `Chopper` in `:audio`); what's left is the gesture and the
  review screen
- **Auto-place** — on-device classifier (kick / snare / hat / clap / perc /
  tonal / loop) drops snips onto sensible default slots
- **BPM + key detection** on loop-length snips, written into the filename
- **Velocity layers and round robins**
- **Banks B–D**
- **Expansion-format export** (browsable in the MPC's Expansion tab)
- **Keygroup programs**

## Architecture

| Layer | Choice | Note |
|---|---|---|
| Language / UI | Kotlin + Jetpack Compose | the 4×4 grid is trivial in Compose |
| minSdk | 29 | forced by `AudioPlaybackCapture` |
| Capture | `AudioRecord` + `AudioPlaybackCaptureConfiguration` | inside a `mediaProjection` foreground service |
| Ring buffer | fixed circular `FloatArray` | 60 s stereo @ 44.1 k ≈ 21 MB, acceptable |
| Pad playback | Oboe / AAudio | `SoundPool` triggering is too loose to feel good |
| Import | `MediaExtractor` / Media3 | demux audio out of shared MP4s |
| Storage | kits **are** folders on disk + a JSON sidecar | makes export a rename-and-render, not a conversion |
| XPM writer | pure-Kotlin module, zero deps, golden-file tested | factor out as its own library from day one |
| Export | SAF `ACTION_OPEN_DOCUMENT_TREE` | zip/share only as fallback |

### Storage shape

Modelling a kit as a directory from the start means the app's working format and
its export format are nearly the same thing:

```
<app files>/kits/<kit-id>/
├── kit.json          ← names, colours, per-pad params, source provenance
├── pad_00.wav        ← already 24-bit / 44.1 kHz, already cleaned
├── pad_01.wav
└── ...
```

Export becomes: render `.xpm` from `kit.json`, copy WAVs with user-facing
filenames. No format conversion at export time, so export is fast and can't fail
halfway with a half-written kit on the user's SD card.

## Risks

| Risk | Mitigation |
|---|---|
| Apps opt out of playback capture (Spotify, Chrome) | Detect silent capture at runtime, surface the screen-recording fallback as a first-class path, ship an honest compatibility list in onboarding |
| Android 14+ requires consent per capture session | Design around long-lived sessions rather than per-snip capture; one dialog per session, not per snip |
| Play Store policy | Never market as recording a named service. Frame as "sample your own sources." No URL downloader, ever. No root/Xposed capture-policy overrides. |
| XPM format drift across firmware | Golden-file tests per target device; target the MPC 2-era format as the common denominator |
| Scope creep into keygroups / expansions / layers | Three separate rabbit holes, all explicitly v2 |

## Open questions

- Does the floating bubble need an in-bubble waveform scrub, or is
  snip-now-trim-later enough? (Leaning: trim later. Keep the bubble one tap.)
- Ring buffer length — 60 s is a guess. Might want 30 s default, 120 s option.
- Is mic capture worth a separate mode in the UI, or just another source in the
  same session?
