# On-screen instruments: touch handling, velocity, pads, grids, feel, accessibility

How a pane of glass becomes something a person plays. Half of this is Android plumbing, half
is fifteen years of NIME research on phones and tablets. Facts dated 2026-10-22.

Sources: [Apple patent US 8,884,888, "Accelerometer determined input velocity" (filed 2010-08-30, granted 2014-11-11)](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/10163428);
[Wang, Essl, Penttinen, "Do Mobile Phones Dream of Electric Orchestras?", 2008](https://www.semanticscholar.org/paper/Do-Mobile-phones-Dream-of-Electric-Orchestras-Wang-Essl/7e5dd565f1938d01c8ab9823c16d5c682d996564);
[Oh et al., SoundBounce, NIME 2010](https://mcd.stanford.edu/publish/files/2010-nime-soundbounce.pdf);
[Schwarz, survey of 39 practitioners, NIME 2020](https://www.nime.org/proceedings/2020/nime2020_paper38.pdf);
Google latency figures in timing-and-scheduling.md §1; Android `MotionEvent`,
`MotionEventPredictor` (androidx.input), `setSystemGestureExclusionRects` documentation.

## 1. Android touch handling (the plumbing)

- **Pointers**: handle `ACTION_DOWN`, `ACTION_POINTER_DOWN`, `ACTION_MOVE`, `ACTION_POINTER_UP`,
  `ACTION_UP`, `ACTION_CANCEL`. Track a note by `getPointerId(index)`, never by index; indices
  shift when another finger lifts. In Compose, `awaitFirstDown` then `awaitPointerEvent` and
  match `change.id` (what `KeysScreen` does). `ACTION_CANCEL` is a note-off for every pointer.
  Put the pointer loop on the whole bank or grid, not on each pad: slides across a gap,
  presses and two-thumb rolls need every finger over the whole surface, and a gap that
  belongs to the nearest pad turns a miss into a wrong pad instead of silence.
- **Compose equivalents**: `PointerInputChange.pressure` and `PointerInputChange.historical`
  (a list of `HistoricalChange` with position and time) are the Compose faces of
  `getPressure` and the historical samples; `touchMajor` has no Compose face and needs a
  `MotionEvent` read (`pointerInteropFilter`). The repo compiles against the Compose BOM in
  `app/build.gradle.kts`; confirm both names in that BOM's sources before writing against
  them, since no SDK is on a cloud machine to grep.
- **Batched samples**: a `MotionEvent` carries history. Read `getHistoricalX/Y/Pressure/Size`
  and `getHistoricalEventTime` so slides are smooth and correctly timed between frames.
- **Clock domain**: `getEventTimeNanos()` (API 34) is CLOCK_MONOTONIC, the same domain as
  MIDI timestamps; below 34, `getEventTime()` in `uptimeMillis` times 1e6. `BeatPlayer`
  already compares a touch's `uptimeMillis` with the frame loop's nanoseconds.
- **Pressure and size**: `getPressure()` and `getSize()` vary by vendor and many panels
  synthesize pressure from contact area; `getTouchMajor()` and `getTouchMinor()` are the
  physical quantities and are usually more useful. Calibrate with a "tap softly, tap hard"
  wizard per device and store the range.
- **Prediction**: `MotionEventPredictor` (androidx.input) reduces perceived visual lag of a
  slide. Never trigger audio from a predicted position; draw with it, sound with the real one.
- **Edge gestures**: `View.setSystemGestureExclusionRects` keeps the back gesture off the
  pads, with a limit of 200 dp per edge on the vertical extent (the limit lifts while the
  navigation bar is stickily hidden). Use immersive mode through
  `WindowInsetsControllerCompat` during performance and keep critical pads off the extreme
  side edges anyway.
- **Large screens**: for apps targeting API 36 on screens 600 dp and wider the system ignores
  orientation and resizability restrictions, and API 37 removes the opt-out. Pad and keyboard
  layouts must survive any aspect ratio; a 4x4 grid in a square window is a different
  layout from one in a 21:9 strip.
- **Palm rejection**: no reliable API. Ignore contacts whose `touchMajor` is far above the
  calibrated finger size or that land outside the instrument zone; add a lock toggle for the
  non-pad chrome.
- **No disambiguation delay**: a pad sounds on down. Never wrap pads in a `GestureDetector`
  or Compose `detectTapGestures` that waits to tell a tap from a scroll; the house pattern
  (`awaitFirstDown(requireUnconsumed = false)`) is right.
- **WebView is not an instrument**: about 300 ms tap-to-audio against about 70 ms native on
  the same phone (Chromium bug).

## 2. Velocity from a touch

Glass does not measure strike force. Five estimators, from the most honest to the most
expressive:

1. **Fixed**: one velocity, a curve editor over it, and the MPC-style **16 levels** mode that
   spreads one sample across 16 pads by velocity, filter or tune (the most playable "velocity"
   a flat pad has ever had).
2. **Position** (the house default): where on the pad or key the finger lands. `PadHit.velocityAt`
   makes the top softest with floor `PadHit.SOFTEST = 0.20f`. Deterministic, learnable,
   accessible.
3. **Contact area growth**: the rate at which `touchMajor` grows over the first one to three
   events; a hard tap flattens fast. Needs per-device calibration; costs one or two frames.
4. **Initial pressure or size**: the first event's `pressure` or `touchMajor` through a
   per-device curve. Cheap, vendor-dependent.
5. **Accelerometer spike** (the Apple patent): the peak change in acceleration within about
   20 ms of touch-down estimates how hard the device was struck. Use a high-rate listener
   (`HIGH_SAMPLING_RATE_SENSORS` for above 200 Hz on Android 12+). Waiting for the peak costs
   about 10 ms; either delay onset by that much or start at a provisional velocity and
   correct the gain in the first milliseconds (a sampler can do the second: the first 10 ms
   of a drum hit are mostly transient and a gain ramp there is inaudible at small ratios).

Ship Fixed, Position, Area, Accelerometer and a Hybrid with one curve editor. Position stays
the default; accelerometer is the expressive premium mode a user turns on. Every mode has a
JVM-testable function from inputs to 0..1, beside `PadHit`.

## 3. Expression after onset

- **Aftertouch**: change in `touchMajor` or `pressure` after onset, relative to the onset
  value, through a deadzone and a one-pole smoother, to channel pressure, poly aftertouch or
  MPE pressure. Relative, so a heavy first touch does not start at full pressure.
- **Pitch by horizontal slide**: "pitch rails" (GeoShred's model) with a rounding option
  that pulls to the nearest note when the finger slows or stops (LinnStrument's "Pitch
  Quantize"). MPE's default per-note range is ±48 semitones; a glass instrument should
  default to something playable like ±2 or ±12 and expose the range.
- **Timbre by vertical slide**: CC74 in relative mode (64 at touch-down) or absolute mode
  (position is value), the two modes the MPE spec allows.
- **Glissando**: retrigger (piano style, a new note-on per key crossed) or bend (string
  style, one note bent), per instrument.
- **Scale lock and chord mode**: show or allow only in-scale notes; one finger plays a
  diatonic chord. The `:audio` module's `Scales` already holds key and scale grids with root
  on A01 (README); reuse the tables.
- **Release velocity**: lift speed from the last few `touchMajor` samples; rarely worth it,
  `MidiKeys` already ignores it from hardware.

## 4. Pads, grids, layouts

- **4x4 MPC pads**: 16 per bank, banks A to H, no dead zones between pads (a miss is a lost
  hit, which is worse than a wrong pad), note repeat with the rate tied to pressure (Maschine
  and MPC), roll, 16 levels. `MidiPads.Map.MPC` keeps the hardware's note map so the screen,
  the keyboard and the exported kit agree.
- **8x8 grid**: mirror the Launchpad's layout and colours exactly so a connected Launchpad
  and the screen are the same instrument with the same state.
- **Isomorphic layouts, one code path**: row offset is a parameter.

  | Layout | Across | Up | Notes |
  |---|---|---|---|
  | Fourths (LinnStrument default) | +1 | +5 | guitar-like, same shape in every key |
  | Chromatic +3 or +4 | +1 | +3 or +4 | minor or major third rows, compact |
  | Wicki-Hayden | +2 | +7 up-right | whole tones across, fifths diagonally |
  | Harmonic Table | hexagonal | thirds and fifths | chords are triangles |
  | In-key (Push, Ableton Note) | scale degree | +scale octave or fourth | no wrong notes |

- **Drum rack**: a 4x4 block of sounds in one corner, sequencer steps in the rest of the
  grid (Push and Launchpad X drum mode).
- **Visual feedback**: light the pad in the same frame as the touch; draw playheads from the
  audio clock through the ns-to-frame mapping, with `Choreographer`, not from the beat
  loop's own state.
- **Haptics**: optional, very short (`EFFECT_TICK`), off by default in performance; actuators
  add perceptible latency and drain battery.

## 5. What makes a glass instrument feel good (research and user findings)

1. **Consistency beats minimum.** Musicians adapt to a steady delay and cannot adapt to
   jitter. Timestamp every touch and schedule it at a fixed offset (timing-and-scheduling.md
   §3) rather than racing the main thread.
2. **Respond at the touch point immediately.** Touch alone is about 20 ms; audio adds its
   share; the eye forgives neither if nothing lights up.
3. **Use the phone's own affordances.** Tanaka (NIME 2010) argued for mappings that embrace
   touch plus accelerometer; the Stanford Mobile Phone Orchestra (2008, 2010) showed phones
   work as intimate instruments on their own sensors and speakers. The phone is not a worse
   Launchpad; it is a different instrument with tilt and shake built in.
4. **General-purpose devices are acceptable expressive instruments.** Schwarz's survey of 39
   practitioners (NIME 2020) found they favoured tablets, trackpads and graphics tablets
   over dedicated expressive controllers, accepting the loss of some input dimensions. That
   is a mandate to treat the phone as a primary instrument, not a remote.
5. **Physical metaphors help.** SoundBounce's "sound as a ball" (NIME 2010): a note that can
   be thrown, caught and bounced is understood without a manual. The SURFACE's pool and
   droplets are already in this family.
6. **Expression must be optional and forgiving.** Pitch rounding on, deadzones on, scale
   lock on by default, quantize-on-record with a strength setting. A beginner must not be
   punished for a wobbling finger.
7. **Hardware mirrors the screen.** Same layout, same colours, same LED state; a user who
   buys a grid controller should recognise it.
8. **What users praise and complain about** in the leading apps (forum posts, not a
   systematic review): praise for fast sample-to-pad flow (Koala), deep modular sequencing
   (Drambo), classic step workflows (G-Stomper); complaints cluster on connectivity (BLE
   devices not found, missing options, clock sync gaps), not on sound.

## 6. Accessibility

- Every pad and key has a `contentDescription`; a TalkBack mode uses larger pads and
  double-tap to play, because raw touch conflicts with TalkBack's explore-by-touch
  (`KeysScreen` already synthesizes a click at `PadHit.CENTER`).
- Fixed or position velocity so expression never requires fine force control.
- Colour-blind-safe palettes with shape or label cues; high contrast; scalable pad sizes.
- Every function controllable by MIDI, by a switch interface, or by a keyboard; MIDI learn
  is itself an accessibility feature.

## 7. What a GLASS deliverable must contain

- The pointer model (down, move, up, cancel, pointer ids) and where it lives in Compose.
- The velocity mode set and the JVM function for each.
- The post-onset expression set with deadzones and smoothing constants, labelled proposed.
- The layout parameterization and the hardware mirror it maps to.
- Gesture exclusion, immersive mode, aspect ratio handling.
- The accessibility mode.
- A bench row for tap-to-tone on the device in hand (OboeTester).
- "Why it is built this way" for Josh.
