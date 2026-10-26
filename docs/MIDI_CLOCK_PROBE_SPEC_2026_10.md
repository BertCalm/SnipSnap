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
- The MIDI screen (`ui/MidiScreen.kt`, behind SETUP · HELP · MIDI) runs no audio engine on
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
   `Copy.MIDI_CLOCK_SHARED` ("SHARED STREAM: SOMETHING ELSE HELD THE AUDIO. RUN IT WITH NOTHING
   ELSE PLAYING."): a shared stream may not be the configuration clock out would get. The check
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

Under S1, replace the empty `→` with a "How, now" paragraph: SETUP · HELP · MIDI → CLOCK CHECK,
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
   is written is a guess at "sitting still". The raw counts in the report let the owner, or a
   later session, re-read a run with another threshold without running it again.
