# CLOCK CHECK: a probe that answers bench S1 on the owner's phone

Engineering spec for an implementing session. Job: CLOCK. It builds the one tool bench row S1
in [`BENCH.md`](BENCH.md) needs and does not have: a way to see whether the phone's reported
playback position tells the truth. The owner chose on 2026-10-26 that SnipSnap leads the
clock ([`MIDI_GAPS_2026_10.md`](MIDI_GAPS_2026_10.md)), so MIDI clock out is next on the clock
road, and it is specced against the pad engine's Oboe stream. S1 has to be answered for that
stream first.

Read at `ccfc18eb1` (2026-10-26). Every name below was found by grep unless marked **(new)**.

## 1. What exists, and what the gap is

- `PadEngine` (C++, `app/src/main/cpp/PadEngine.{h,cpp}`) owns `std::shared_ptr<oboe::AudioStream>
  stream_`, opened by `openStereoFloatOutput` in `start()` and closed in `stop()`, both on the
  UI thread. Batch A gave it `framesRendered()`: an atomic count of every frame the callback
  has written since construction.
- Oboe 1.9.0 (pinned in `app/build.gradle.kts` and the host harness's CMake) declares
  `virtual ResultWithValue<FrameTimestamp> getTimestamp(clockid_t clockId)` with
  `FrameTimestamp { int64_t position; /* frames */ int64_t timestamp; /* ns */ }`. Its header
  says not to call it from a data callback before Android R. minSdk is 29, so that applies.
  Verified in `oboe/AudioStream.h:353` and `oboe/Definitions.h:871`.
- Nothing in the app calls `getTimestamp`. `BENCH.md` S1: "Nothing displays or logs the value."
- The MIDI screen (`ui/MidiScreen.kt`, behind the gear, then MIDI) runs no audio engine on
  purpose and has a COPY REPORT button that puts `MidiReport.text` on the clipboard.
- `PadEngine.kt` (Kotlin) has `start()`, `close()`, `sampleRate()`, `isShared()`, `needsRestart()`,
  `latencyMillis()`, `framesRendered()`, `loadSnips(snips)` (which replaces the bank and clears
  the built-in click) and `hitLayers(...)`. `DrumSynth.click(rate, accent)` renders a click.

The gap: no call reads the hardware position, and no screen shows the result.

## 2. What to build

### 2.1 The native read (`PadEngine.{h,cpp}`, `jni.cpp`, `NativePads.kt`)

`bool PadEngine::hardwarePosition(int64_t* framePosition, int64_t* timeNanos)` **(new)**: UI
thread only, never from the callback. If `stream_` is null, return false. Otherwise call
`stream_->getTimestamp(CLOCK_MONOTONIC)`; on `Result::OK` write `value().position` and
`value().timestamp` and return true; on any error return false.

`int32_t PadEngine::framesPerBurst() const` **(new)**: `stream_ ? stream_->getFramesPerBurst() : 0`.

`int32_t PadEngine::audioApi() const` **(new)**: `stream_ ? static_cast<int32_t>(stream_->getAudioApi()) : -1`.
(Oboe's `AudioApi` enum: Unspecified 0, OpenSLES 1, AAudio 2.)

JNI: `Java_com_snipsnap_app_NativePads_hardwarePosition(JNIEnv*, jobject, jlong handle, jlongArray out)
-> jboolean`, writing `[framePosition, timeNanos]` into a two-element array the caller owns,
and `framesPerBurst`/`audioApi` returning `jint`. Kotlin: matching `external fun`s, and in
`PadEngine.kt` `@Synchronized fun hardwarePosition(out: LongArray): Boolean` (false when not
open), `framesPerBurst(): Int`, `audioApi(): Int`.

Host test (`engine_tests.cpp`): a never-started engine's `hardwarePosition` returns false and
writes nothing; `framesPerBurst()` is 0; `audioApi()` is -1. The harness cannot start a stream,
so that is the whole of the native proof.

### 2.2 The analysis (`shell/src/main/kotlin/com/snipsnap/shell/ClockProbe.kt` **(new)**)

Pure, tested in `:shell`. Input: one `Sample` per display frame.

```kotlin
data class Sample(
    val wallNanos: Long,        // System.nanoTime() when the main thread read it
    val framesWritten: Long,    // PadEngine.framesRendered()
    val hwFrame: Long?,         // getTimestamp position, null when the call failed
    val hwNanos: Long?,         // getTimestamp time, null when the call failed
)
```

`fun analyze(samples: List<Sample>, sampleRate: Int): Result` **(new)**. One `Result`
carries every figure, because a run can start badly and then track well, and a clock has to
survive both:

```kotlin
data class Result(
    val verdict: Verdict,            // UNAVAILABLE, TOO_SHORT, STALLED, SLOW_START, TRACKED
    val samples: Int,
    val validSamples: Int,           // samples with a hardware position
    val startupGapMs: Double?,       // first sample with framesWritten > 0 -> first sample whose
                                     // hwFrame differs from the first valid hwFrame; a failed
                                     // read counts as not moving. null if it never moved.
    val longestStallMs: Double,      // after the first movement only; 0 when none
    val stallAtMs: Double?,          // where that stall began, from the first sample
    val rateErrorPpm: Double?,       // least-squares slope of hwFrame on hwNanos, after the
                                     // first movement, against sampleRate; null below 2 s of it
    val lagMs: Double?,              // mean of framesWritten - hwFrame in ms: context, coarse
)
```

**Start-up and stalls are measured separately.** The first 800 ms S1 names may arrive as
failed reads, not as zeros (AAudio commonly fails `getTimestamp` until the first burst is
consumed), so the start-up gap treats a failed read and an unmoving position alike. After the
first movement, a **stall** is a span where the hardware frame moves by no more than one burst
while `framesWritten` advances by more than 100 ms of audio at `sampleRate`, counting failed
reads as not moving; only stalls of at least `STALL_FLOOR_MS` count.

**The verdict**, in this order: `UNAVAILABLE` when `validSamples == 0`; `TOO_SHORT` when less
than 2 s of samples follow the first movement; `STALLED` when `longestStallMs >= STALL_FLOOR_MS`;
`SLOW_START` when `startupGapMs > SLOW_START_MS`; `TRACKED` otherwise. A `STALLED` or
`SLOW_START` result still carries `rateErrorPpm` and `lagMs` for the span after it.

**What each number is for.** `rateErrorPpm` is the precise one: it is fitted on `hwNanos`,
the hardware's own clock, so main-thread read jitter does not reach it. `lagMs` is context for
bench S2, not a measurement of output delay: `framesWritten` and the hardware pair are read at
slightly different moments, so it carries about a display frame of skew. `describe` prints it
as "ABOUT N MS" and never with a ± figure.

Proposed constants, named in the companion, each with a KDoc line saying "proposed": one burst
of movement, `MOVING_AUDIO_MS = 100`, `STALL_FLOOR_MS = 200`, `SLOW_START_MS = 250`,
`MIN_TRACKED_MS = 2_000`.

Tests on synthetic sample lists (`ClockProbeTest`):
- an honest 48 kHz stream: `TRACKED`, `rateErrorPpm` under 50, `startupGapMs` under 50;
- position fixed at 0 for the first 800 ms while frames are written: `SLOW_START`,
  `startupGapMs` ≈ 800, and `rateErrorPpm` still reported;
- failed reads for the first 800 ms, then honest: the same `SLOW_START`, ≈ 800;
- honest for 2 s, then frozen for 500 ms, then honest: `STALLED`, `longestStallMs` ≈ 500,
  `stallAtMs` ≈ 2000;
- a 150 ms freeze after start-up: `TRACKED` (below the floor);
- every read failed: `UNAVAILABLE`; 1.5 s after first movement: `TOO_SHORT`;
- a 44.1 kHz stream tracks (no rate is ever hardcoded);
- ±8 ms of jitter on `wallNanos` alone leaves `rateErrorPpm` unchanged to within 1 ppm.

`fun describe(result: Result): String` **(new, pure, tested)** returns the one screen line, built
from `Copy` constants, e.g. "POSITION TRACKED. RATE OFF 12 PPM. ABOUT 23 MS BEHIND.",
"POSITION STOOD STILL 820 MS AT THE START, THEN TRACKED. RATE OFF 9 PPM.", "POSITION STOPPED
FOR 500 MS, 2.0 S IN.", "THIS PHONE REPORTS NO POSITION FOR THIS STREAM.", "TOO SHORT. RUN IT
AGAIN."

### 2.3 The screen (`ui/MidiScreen.kt`, `Copy`)

One button below the device list: `Copy.MIDI_CLOCK_CHECK` ("CLOCK CHECK"). On tap:

1. Construct a `PadEngine` at the device's preferred rate (the way `PadHost` does), `start()`
   it; if `start()` fails, show `Copy.MIDI_CLOCK_NO_STREAM` and stop.
2. `loadSnips(listOf(DrumSynth.click(engine.sampleRate(), accent = false)))` and hit sample 0
   once per second with `hitLayers`, so the player hears the check running.
3. For 6 seconds, in a `withFrameNanos` loop on the main thread, read `framesRendered()` and
   `hardwarePosition(out)` into a `Sample`. If `needsRestart()` turns true, stop and show
   `Copy.MIDI_CLOCK_ROUTE_CHANGED` ("THE AUDIO ROUTE CHANGED. RUN IT AGAIN.").
4. `close()` the engine. Also close it in the screen's `onDispose` if the check is still running.
5. Show the line from `ClockProbe.describe(result)`. If `isShared()` was true, add
   `Copy.MIDI_CLOCK_SHARED` ("SHARED STREAM: ANOTHER STREAM WAS PLAYING, MAYBE SNIPSNAP'S OWN
   BEAT. STOP EVERYTHING AND RUN IT AGAIN."): a shared stream may not be the configuration clock out would get. The check
   does not refuse to run.
6. Add a CLOCK section to `MidiReport.text` when a check has run: the line, the counts,
   `startupGapMs`, `longestStallMs`, `stallAtMs`, `sampleRate()`, `isShared()`, `framesPerBurst()`, `audioApi()` as a word,
   `latencyMillis()`, `Build.MODEL` and `Build.VERSION.SDK_INT`, and the output route as a
   word (speaker, wired, Bluetooth, USB) from `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`'s
   first active type. And the sentence: "Answers S1 for the pad engine's Oboe stream, the one
   clock out uses. The AudioTrack path is not probed."

Every user-visible string is a `Copy` constant; labels go in `PersonalityTest`'s `notASentence`
set with a comment.

### 2.4 The bench row (`docs/BENCH.md`)

Under S1, replace the empty `→` with a "How, now" paragraph: the gear, then MIDI → CLOCK CHECK,
once on the speaker and once on wired or Bluetooth headphones; paste the two lines from COPY
REPORT. Say that the bench's September pause is lifted for S1 only, at the owner's request.

## 3. What this does not do

- It does not answer S2 (output delay needs an acoustic reference) or S3/S4.
- It does not probe the `:loop` `AudioTrack` path. With SnipSnap leading, clock out runs on the
  pad engine; the report says so.
- It changes nothing on the audio thread. `hardwarePosition` is UI-thread only.
- It does not build the nanoseconds-to-frames fit clock out will use. That fit is next, and
  it waits on this answer.

## 4. Ledger

- **Verified** (grep and header read, `ccfc18eb1`): section 1; the Oboe signature and struct.
- **Measured on device**: nothing.
- **Proposed**: the thresholds; the 6-second run; the once-a-second click; every `Copy` line.
- **Untestable here**: what any phone reports. That is the point of the probe.

## 5. Why it is built this way

1. **The fit uses the hardware's own timestamps.** The main thread reads once a display frame
   and that read wobbles by milliseconds. Fitting on `hwNanos` measures the phone, not
   Compose.
2. **Four outcomes, not pass and fail.** "No position" and "a position that lies" call for
   different clock designs. Folding them together would hide the one that matters.
3. **The engine opens only for the check.** The MIDI screen ran no audio on purpose; a probe
   that left a stream open would change what other screens measure.
4. **The risky decision** is the stall threshold. One burst of movement while 100 ms of audio
   is written is a guess at "sitting still". The report's BURST USED and POSITION UPDATES EVERY
   lines let the owner, or a later session, re-read a run with another threshold: the first
   says what "one burst" was, the second whether the position is coarse or stalled.

## 6. As built (2026-10-26)

Landed in `7bb2f82e5` to `924979517`. Proof for each
is a host test or a compile; nothing was run on a phone.

- `7bb2f82e5` **Bend and pressure on the wire.** `MidiWire` reads pitch bend (LSB first,
  -8192..8191), channel pressure and poly pressure as `Event.Expression`. `reachesScreens()`
  keeps them, and real-time bytes, off the main thread, so a pressure pad does not post one
  lambda per byte until something plays them. `scripts/wire_fixtures.py` extended. Proof:
  `MidiWireTest`, 30 `@Test`.
- `dbd4c8087` **Pad map per controller.** `MidiPadMaps` in `:shell`. PLAY restores the map
  remembered for the most recently connected controller (panic first, so a note held under
  the old map cannot stick), and saves it per connected controller when the map chip is
  tapped. SNIPSNAP IN never stores. Proof: `MidiPadMapsTest`, 8 `@Test`; `:app` compiled.
- `bc7f750b2` **The native read.** `PadEngine::hardwarePosition` (Oboe
  `getTimestamp(CLOCK_MONOTONIC)`, UI thread only, never from the callback), `framesPerBurst`
  and `audioApi`, with JNI and Kotlin. Host tests cover the not-started case. The host
  `stub/jni.h` gained `jlongArray`. Proof: `engine_tests.cpp` and `jni_tests.cpp` under ctest.
- `3f856ed1a`, `a0e5bdf42`, `3a684ae6d` **The analysis.** `ClockProbe.analyze` and
  `describe`, with the verdicts UNAVAILABLE, never-moved STALLED, TOO_SHORT, STALLED,
  SLOW_START and TRACKED. The start-up gap and later stalls are measured apart. The rate is
  fitted on the hardware's own timestamps. The proposed thresholds are named constants.
  Proof: `ClockProbeTest`, 23 `@Test`.
- `924979517` **The screen.** CLOCK CHECK on the MIDI screen: 6 s, a click a second, abort on
  a route change (`needsRestart()`), stop on leaving the screen, a shared-stream warning, and
  a CLOCK section in COPY REPORT. Proof: `MidiReportTest`, 11 `@Test`; `PersonalityTest`;
  `:app:compileDebugKotlin`.
- `fe3d726eb`, `3c4e65eb7` and the docs commit after them: **final-review fixes.**
  `RATE_WRONG` joins the verdicts between STALLED and SLOW_START: with `MIN_TRACKED_MS` run
  after the first movement, a missing rate (the hardware timestamps spanned under 2 s or did
  not advance) or one over `RATE_LIMIT_PPM = 1000` leads with "DON'T TRUST THIS CLOCK", never
  TRACKED. `Result` also carries the timestamp age against the wall clock (printed, not
  judged), the burst used, the median spacing between position updates and a `neverMoved`
  flag; COPY REPORT prints TIMESTAMP AGE, BURST USED and POSITION UPDATES EVERY. The screen
  reads the output route again after the run and returns RouteChanged if it moved (no
  auto-retry), and counts `TYPE_BLE_HEADSET` as Bluetooth. Proof: `ClockProbeTest` (30
  `@Test`), `MidiReportTest`; `:app:compileDebugKotlin`.

**What changed against the spec.**

- A position that never moves while at least `STALL_FLOOR_MS` of audio is written is
  STALLED (`startupGapMs` null, no rate or lag), not TOO_SHORT. The spec's order would have
  told the owner to run again on the worst result S1 can find. Never moved with less audio
  stays TOO_SHORT.
- `BURST_FLOOR_MS = 10`: the inferred burst is never less than 10 ms of audio, so one odd
  tiny step cannot set a threshold a creeping stall could beat.
- The rate sentence names its sign: RATE n PPM FAST or SLOW. Positive means the hardware
  counts more frames per second than the stream's rate.
- A lag below zero is reported as AHEAD, not BEHIND.
- A slow start is named in the STALLED line when a later stall also happened.
- The probe stops on `ON_STOP` as well as on leaving the screen, so a backgrounded app does
  not keep a stream open.

**Disclosed, not fixed.**

- The success path of `hardwarePosition` cannot be tested on the host: the harness cannot
  start a stream. The tests prove the not-started case only.
- Nothing was run on a phone. The thresholds are proposals.
- A phone whose timestamp updates come 100 ms or more apart can read STALLED under the stall
  rule. The report's POSITION UPDATES EVERY line tells coarse from stalled; the rule was not
  softened without data.
- A clock whose hardware timestamps are derived from its frames at the claimed rate, while the
  hardware really runs at another rate, is self-consistent and still reads TRACKED. The
  TIMESTAMP AGE mean and MAX lines show it: over a six-second run an 8% rate error moves the
  age by about half a second. Nothing judges that yet; a coarse fit of position against the
  wall clock would.
- A run whose written-frame count stays at zero while the position moves reads TRACKED. That
  needs a broken frame counter, a code bug, not a phone behaviour; the report would show a
  large and growing AHEAD figure.

**Measured on device:** nothing.
