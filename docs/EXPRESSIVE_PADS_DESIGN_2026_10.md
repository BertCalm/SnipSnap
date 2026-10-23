# Making PLAY feel played: velocity, press, repeat, and a melodic grid on the 4x4

Job: GLASS (the surface) ending in EXPRESS (what a held finger can say), with one SEQUENCE
item (note repeat) because an MPC player will reach for it the moment the pads feel right.
Read only, no code run, SnipSnap at commit `b429ec2` (2026-10-21, "Merge pull request #613").

The reading I took, since the prompt did not say: "PLAY" means the pad grid as it is today,
which is a mode of KIT (window 4x4, long-press opens PAD SHEET) plus FULL (landscape, both
banks, no long-press). The instrument work goes into FULL first, because that grid already
says "PLAY is for playing" in its own KDoc and has no gesture to fight. The window gets the
same surface behind a PERFORM chip as the second step. Nothing in this design touches the
exported kit, the MPC note map, or the synth engines; everything plays through the engine
that passed bench A1.

## 0. Why it feels flat (verified, in the code)

1. **One velocity for most hits.** `PadHit.velocityAt(y, height)` is the only estimator: top of
   the pad is 0.20, bottom is 1.0, so a thumb landing in the middle of a pad gives about 0.6
   every time (`shell/src/main/kotlin/com/snipsnap/shell/PadHit.kt:88`). A player aims at the
   centre of a pad, so the range is there but nobody reaches it. The MPC reads force; glass
   reads nothing, and position is a stand-in that only rewards a deliberate aim at an edge.
2. **Nothing after the finger lands.** The pad gesture in `PadGrid.kt:285-301` reads one thing
   after the down: `change.pressed`. No position, no pressure, no history. A held finger is
   indistinguishable from a resting one. `KeysScreen` and the window's `PadCell` are the same.
3. **A gap is a lost hit.** Each pad owns its own `pointerInput`; `Layout.PAD_GAP = 8` dp between
   pads belongs to nobody, and a slide from one pad onto the next does nothing, because the
   pointer is bound to the pad it went down in.
4. **The light does not say how hard.** FULL flashes at `0.40f * g` with `g` snapped to 1 and
   decayed over `Motion.PAD_GLOW_MS = 180` regardless of velocity (`PadGrid.kt:366`,
   `KitScreen.kt:294-300`). The MPC's pad lights brighter for a harder hit; the eye reads
   that as "it heard me".
5. **No repeat, no roll, no 16 levels.** The three things an MPC player does with a held pad
   do not exist. Grep found no note repeat anywhere outside `docs/` (`Spread.kt` is the
   kit-building "16 levels of tune", not a live mode).
6. **The window grid cannot hold a note.** `KitScreen.hit` forces `oneShot = true` because the
   tap has no release (`KitScreen.kt:318`), and its 480 ms long-press opens PAD SHEET, so any
   hold-based expression on that grid would collide with the editor.

Not on the list: latency. Bench A1 (2026-09-08) read 8 to 11 ms in the header, with no
SHARED after it, so the exclusive path opened; that figure is Oboe's `latencyMillis`,
output-side only, though the bench row calls it round-trip. The phone's owner called it
"tight enough to play". The chain is about 35 ms touch-to-sound in fastest mode
(`scripts/latency_budget.py touch --rate 48000 --burst 96 --buffer 2 --measured output=10`:
20 ms touch cited from Google, March 2021; 1 ms block wait; 4 ms buffer at two bursts of
96 frames, the burst size being my assumption since the device is not named; 10 ms output
measured). That is what a native Android instrument gets. What makes it feel flat is items
1 to 6, not the milliseconds.

## 1. The shape of the change

Six pieces, in the order they pay back. Each is shippable on its own.

### A. One pointer surface per bank, not one per pad

Replace the per-pad `pointerInput` in FULL with one `pointerInput` on the bank's `Column`
(`PlayBank` in `PadGrid.kt`), which hit-tests every pointer in `event.changes` by position,
the way `SurfaceScreen.kt:1597-1600` already walks every change for the SURFACE. The pads
keep their composables for drawing and for their `semantics` nodes; they lose their gesture.

- A pure function in `:shell`, `PadGeometry.slotAt(x, y, width, height, rows, gap)`, maps a
  point to a slot. The gap belongs to the nearest pad: a miss is a wrong pad at worst,
  never silence. Tested with the real `Layout.PAD_GAP` and the `windowRows` order so bank B
  is laid out exactly as A.
- The surface emits a small event set to the screen: `Down(slot, velocity, x, y, t)`,
  `Move(slot, x, y, pressure, t)`, `Up(slot, t)`, `Cancel`. Every pointer is tracked by
  `change.id`, as the house already does; a cancel is an up for every pointer.
- Sound stays on the first down. `awaitFirstDown(requireUnconsumed = false)` then
  `awaitPointerEvent` in a loop, no gesture detector, no slop wait. Same pattern as today,
  one level up.
- Historical samples: Compose hands the surface each `PointerInputChange` with
  `historical` positions; the press and slide estimators read them so a fast movement
  between frames is not a staircase.
- Timestamps: `change.uptimeMillis` already travels through `onHit`; keep that shape. The
  nanosecond version (`MotionEvent.getEventTimeNanos`, API 34) can replace it when roadmap
  P0.2 gives the engine a frame to put it on; the callback shape does not change.

This one piece is what makes B to F possible: two-thumb rolls across the gap, a finger that
slides, a press that is measured, and later a Launchpad that mirrors the same slot maths.

### B. Velocity modes, one curve, all pure functions

Beside `PadHit`, a `PadVelocity` object with one function per mode, each from its inputs to
0..1, and one curve over the result. `PadHit.velocityAt` stays exactly as it is (the
`ConventionTest` law and `PadTouchTest` pin it); it becomes one mode of several.

| Chip | Mode | What it reads | Honest note |
|---|---|---|---|
| POS | position (default) | touch Y through `PadHit.velocityAt` | deterministic, learnable, accessible; the reason centre hits all sound alike |
| FULL LVL | fixed 1.0 | nothing | the MPC's FULL LEVEL; proves to the ear what POS was doing (not "FULL", which is already the fullscreen chip in `KitScreen`) |
| STRIKE | accelerometer spike | peak change in acceleration in the 12 ms around the down | the one that feels like a pad; needs the phone to move a little; see the risk in §7 |
| AREA | contact size at onset | the first event's pressure or touch size, calibrated | vendor-dependent; many panels synthesise pressure from area, which is fine since only the relative value is used |
| 16 LV | the MPC's 16 Levels | bank B becomes the selected pad at 16 velocities, A01 softest to A16 loudest | the most playable "velocity" a flat pad has ever had; zero estimation |

The curve: `PadVelocity.curve(v, shape, sensitivity)` with LINEAR, SOFT (`v^0.6`), HARD
(`v^1.6`), and a sensitivity gain, matching what the MPC's pad settings offer. One curve
editor in SETTINGS, applied after whichever mode produced the number, so the modes stay
comparable.

STRIKE, in detail, because it is the one that earns the word "instrument":

- Listener at `SENSOR_DELAY_FASTEST` on the accelerometer, opened while FULL is up and
  closed with it. On Android 12+ rates above 200 Hz need the normal permission
  `HIGH_SAMPLING_RATE_SENSORS`; at 200 Hz a 12 ms window holds two or three samples, which
  is thin. The mode reports its own sample rate in the diagnostics line and refuses itself
  in words ("STRIKE NEEDS 100 HZ, THIS PHONE GIVES 50") below 100 Hz.
- The estimator (pure, tested with synthetic sample runs): high-pass the magnitude with a
  one-pole at about 5 Hz to remove gravity and slow tilt, take the peak of the absolute
  value in `[down - 4 ms, down + 12 ms]`, map through the calibrated soft and hard taps.
- No added latency. The note fires on the down at the POS velocity (the provisional); when
  the peak is known, about 10 ms later, the gain is corrected through the existing
  `PadEngine.setGain` with a 4 ms glide, bounded to plus or minus 12 dB. The first 10 ms of
  a drum hit are transient and a small gain ramp there is inaudible. What cannot be
  corrected is the velocity layer: a stacked pad picks its take at note-on, from the
  provisional. A LAYERS WAIT option delays every hit by a fixed 12 ms in STRIKE mode so
  layered pads pick from the real strike; constant delay is the thing players adapt to,
  jitter is the thing they cannot (Google's WALT and the NIME literature agree).
- The idea is Apple's US patent 8,884,888 (filed 2010, granted 2014), so it is live until
  about 2030. That is Josh's call for an open-source app, not mine; I am flagging it, not
  judging it.

### C. PRESS: something like aftertouch, on glass

An MPC's aftertouch is force after onset. Glass has two things that change after onset: how
much finger is on the panel (the pad flattens as you push) and where the finger is. The
design treats "press" as one 0..1 modulation source with two possible readers, picked per
device by a calibration that takes ten seconds:

- **CONTACT**: the change in `PointerInputChange.pressure` relative to its value at the down,
  so a heavy first touch does not start at full press. Through a deadzone of 8 percent of the
  calibrated span and a one-pole smoother (`TouchSurface.Smoother`, already in `:shell`;
  `coefficient(12f, rateHz)` at the touch report rate, about 0.47 at 120 Hz). Compose
  exposes `pressure`; it does not expose `touchMajor`, which is the physical quantity. If
  his panel reports a constant pressure (some do), the fallback is the platform
  `MotionEvent`'s `getTouchMajor` through the repo's equivalent of an interop read, which
  the repo does not use anywhere today (grep found no `pointerInteropFilter` and no
  `motionEvent` in `app/`). The calibration screen ("TAP SOFT, TAP HARD, HOLD AND PUSH")
  decides which reader this phone gets and stores the span.
- **SLIDE**: vertical movement after onset, relative (0 at the down), through the system touch
  slop (`viewConfiguration.touchSlop`, already read in `TapeScreen.kt:1752`) as the
  deadzone, then 0..1 over 40 dp of travel upward. This works on every panel ever made and
  is what MPE calls relative CC74; it is the guaranteed floor.
- AUTO picks CONTACT where calibration finds a usable span and SLIDE otherwise. Only one
  reader runs at a time so a finger does not double-count.

What PRESS moves, in order of cost (the engine's native voice only hears gains, frames and
speed; `PadHit.kt:7-12`):

| Target | Engine path | Cost |
|---|---|---|
| LEVEL | `PadEngine.setGain(voiceId, l, r, glideMs = 8f)`, at most one per frame per voice | none, `SetGain` exists and glides |
| PITCH | a new `PadCommand::Type::SetPitch` that glides `Voice::inc` the way `SetGain` glides the gains (a `targetInc` and a `pitchRamp`, applied per frame in `render`, clamped at `kMaxSpeed`) | one native command, realtime-safe, host-tested |
| REPEAT velocity | no engine change; the repeat scheduler in D reads the press | none |
| FILTER | there is no filter in `PadEngine`; `KitPad.cutoff` is metadata the MPC renders | luthier's, not this design's |

Default routing: LEVEL in DRUMS mode, PITCH in GRID mode (a vibrato by pushing, the Osmose
and Orba idea), REPEAT velocity whenever NOTE REPEAT is on. Range for PITCH: plus or minus 2
semitones, exposed.

### D. NOTE REPEAT and the roll

A chip row: OFF, 1/8, 1/16, 1/32, 1/16T, 1/32T. While a pad is held, the screen re-fires
`playHeld(slot, v)` on each subdivision; the first hit is always the finger's own.

- Tempo: `kit.tempoBpm ?: KitPreview.DEFAULT_BPM` (92), the value `BeatPlayer` already uses.
  When the beat is playing, repeats align to its grid through `BeatPlayer.anchorNanos` and
  `anchorPos`, which exist for exactly this kind of placement; when it is stopped, the grid
  starts at the first down.
- Velocity of each repeat: the press (C) when PRESS is on, which is how the hardware behaves
  with a pad held under note repeat as I remember it (verify on the Live III), otherwise the
  first hit's velocity. A ROLL variant ramps the velocity across the hold.
- Clock honesty: version one runs on `withFrameNanos`, the same display clock `BeatPlayer`
  uses, so each repeat lands within one frame (8 to 16 ms) of where it should. That is the
  jitter `docs/MIDI_SYNC.md` names and it will be audible on a 1/32 roll. The fix is not in
  this design: roadmap P0.2 (a `frameOffset` on `PadCommand`, honoured mid-block) and P0.3
  (the ns-to-frame mapping) let the repeat be scheduled a block ahead on the audio clock.
  This design is written so that only the scheduler changes when they land; the surface,
  the velocities and the chips do not.

### E. GRID: a melody on a kit

Pick a pad, tap GRID, and bank B becomes that pad at sixteen pitches while bank A stays the
drum kit: drums under the left thumb, a tuned sound under the right, one engine, one choke
behaviour, one voice count. In portrait (the window, step 2) the whole 4x4 becomes the
spread.

- Layouts share one code path: `PadLayout(columns = 4, rowOffset, scale)` in `:shell` with
  three presets. IN KEY (default, scale lock on, `Scales.layout(scale)` exactly as
  `KeysLayout` and `Spread` already use it), CHROMATIC (rows of +4, which is what the house
  layout gives on a four-wide grid), FOURTHS (rows of +5, the LinnStrument shape, chord grips
  the same in every key). `KeysLayout.noteFor` becomes a call into this; its four scale
  chips stay. The kit's `key: KeySpec?` seeds the scale and root when present.
- OCT minus and plus, `KeysLayout.OCTAVE_MIN..OCTAVE_MAX`, same chips KEYS has.
- Pitch is the sampler's own: the swap seam `PadEngine.hit(pad, v, id) { hit ->
  hit.copy(pitchRatio = hit.pitchRatio * 2.0.pow(semis / 12.0)) }`, which is the decorator
  AUDITION already uses and is pure arithmetic over the resolved hit, as its KDoc demands.
  A kick an octave up is half as long and bright; that is the "16 levels of tune" sound
  Josh is asking for, not a bug. Range plus or minus 24 semitones, well under `kMaxSpeed`.
- Voices: the allocator is keyed by slot, so each grid slot is its own note and releases
  on its own lift (`VoiceAllocator.noteOff(padSlot)`). The source pad's mute group is
  ignored in GRID mode by default, or every note would choke the last; a MONO chip opts
  back in, which with the pad's gate flag is the mono bass line the synth roadmap describes.
  The pad's own `oneShot` flag is honoured: a one-shot plays out, a gate stops on lift.
- Glissando: a finger that slides onto another pad retriggers (a new note per pad crossed,
  with hysteresis of 4 dp into the new pad so a boundary does not chatter); the previous
  note releases if it was a gate. Pitch-bend glissando waits for `SetPitch` (C) and is a
  later chip, since bend across a scale-locked grid is a slide with rounding, the
  LinnStrument's pitch quantize, and that is a second piece of maths worth its own tests.
- SPREAD THIS: one button writes what you are hearing into bank B through
  `Spread.plan` and `Spread.apply`, so a grid you liked becomes a kit the MPC loads. The
  live mode and the kit-building mode share `PadLayout`, so they cannot disagree about which
  pad is which note.

### F. Light and feel at the touch point

- The flash snaps to `0.4f + 0.6f * v` instead of 1, then decays over `PAD_GLOW_MS`. A hard
  hit is a bright pad, a soft one a dim one, the MPC's own convention.
- A held pad keeps a floor (`PAD_HELD_ALPHA = 0.20f` already exists for the window's hold);
  PRESS adds up to 0.3 on top of it, so the eye sees the push.
- In GRID mode the pad label is the note name (`Scales.nameOf`), root pads tinted with
  `Spread.rootTint`, which is the convention SPREAD already paints.
- Haptics stay as they are: `Haptics.pad(v)` already scales amplitude and length with
  velocity and is behind the HAPTICS setting. Actuators add a few ms of their own, which
  is why the setting exists; nothing new here.

## 2. Android specifics (the plumbing the surface needs)

- **Back gesture off the pads**: FULL is landscape and bank A's left column sits on the
  back-gesture edge. Exclude the grid's edge strips with the platform call
  `View.setSystemGestureExclusionRects` (Compose foundation wraps it in a modifier I recall
  as `Modifier.systemGestureExclusion()`; unverified here, see §7), remembering the 200 dp
  per edge limit,
  which a 4x4 bank's height exceeds; so exclude the top 200 dp of each edge and keep the
  bottom row's outer pads 16 dp in from the edge.
- **Immersive while performing**: `WindowInsetsControllerCompat.hide(systemBars())` with
  transient bars by swipe, entered with FULL and left with it, the same `DisposableEffect`
  that flips orientation today (`KitScreen.kt:498-503`).
- **Aspect ratios**: `BankRow` already measures and stacks B under A when two banks will not
  fit at `MIN_HIT_TARGET`; the surface inherits that. Targeting API 36 on wide screens will
  ignore the portrait lock, so the surface's hit test must take the real size every event,
  which it does, since it reads `size` inside the pointer scope.
- **Palm rejection**: no API. Where the `MotionEvent` is read (the CONTACT fallback), a
  contact with `touchMajor` more than three times the calibrated finger size is ignored. On
  the Compose-only path there is no size to read, so there is no heuristic; disclose it.
- **No prediction for sound**: `MotionEventPredictor` is not in the build
  (`androidx.input` is absent from `app/build.gradle.kts`) and would only be for drawing a
  slide, never for triggering.

## 3. Accessibility is a mode, not a casualty

- Every pad keeps its `semantics(mergeDescendants = true)` with `contentDescription` and
  `onClick(label = "PLAY")` at `PadHit.CENTER`, which `ConventionTest` requires. Moving the
  gesture to the bank does not move the semantics.
- In GRID mode the description is the note: "PAD B05: C#3, KICK".
- TALKBACK mode pins the velocity mode to POS or FULL LVL and PRESS to OFF, so no expression
  depends on fine force; NOTE REPEAT works from the synthesized click as a timed hold toggle.
- Velocity is also shown, not only lit: the LCD strip's status line gets the last hit's
  value ("VEL 97") beside VOICES, which is also the ten-second check that a mode is doing
  what it claims.

## 4. Where it lands

| Piece | Module and file | Proof |
|---|---|---|
| `PadGeometry` (slot from point, gap ownership, hysteresis) | `shell/.../PadGeometry.kt` beside `PadHit` | `:shell` JVM test against `Layout.PAD_GAP` and `windowRows` |
| `PadVelocity` (modes, curve, strike estimator, calibration maths) | `shell/.../PadVelocity.kt` | JVM tests per mode; synthetic accelerometer runs for STRIKE; the `ConventionTest` law still finds `PadHit.velocityAt(` in `PadGrid.kt` |
| `Press` (contact and slide readers, deadzone, smoother, AUTO pick) | `shell/.../Press.kt`, reusing `TouchSurface.Smoother` | JVM tests: a heavy first touch starts at zero; a wobble inside the deadzone moves nothing |
| `PadLayout` (row offset, scale, octave, note names) | `shell/.../PadLayout.kt`; `KeysLayout.noteFor` delegates | `KeysLayoutTest` numbers stay pinned; new table tests for FOURTHS and CHROMATIC |
| `Repeat` (subdivision maths, anchor alignment, roll ramp) | `shell/.../Repeat.kt` using `BeatClock.stepsPerSecond` | JVM tests: the first repeat after a down lands on the next subdivision, never before the hit |
| `PadSurface` modifier (the bank-level pointer loop, event emission) | `app/.../ui/PadGrid.kt` | compiler plus the phone; a Compose suite in `app/src/androidTest` sending two pointers across the gap |
| STRIKE sensor glue, calibration screen, exclusion rects, immersive | `app/.../ui/` beside `KitScreen` | phone |
| `SetPitch` command | `app/src/main/cpp/PadEngine.{h,cpp}`, `NativePads.kt`, `PadEngine.kt` | `app/src/main/cpp/test/engine_tests.cpp`: a voice's `inc` glides from 1.0 to 2.0 over N frames and never exceeds `kMaxSpeed` |
| GRID wiring (`playHeld` with a source pad and a swap) | `app/.../ui/KitScreen.kt` | phone; the `MidiPadsTest` shape for the hit and release verbs |

The realtime audit for `SetPitch`, since it is the only line that touches the callback: it
is applied from `apply()` like `SetGain` (a loop over 32 voices, arithmetic, no allocation,
no lock, no JNI), and `render()` adds one multiply-add per frame while `pitchRamp > 0`. The
drain in `onAudioReady` is still `while (commands_.pop(c)) apply(c);` with no cap; a PRESS
that sends one `SetPitch` per frame per held finger is at most ten commands per 8 ms, far
under the 256-deep ring, but the cap the review rubric asks for is worth adding in the same
change.

## 5. The constants (all proposed, none measured)

| What | Value | Why this and not another |
|---|---|---|
| Flash by velocity | `0.4 + 0.6 v` | a soft hit still visibly lights; a hard one is the current full flash |
| Press deadzone, CONTACT | 8 percent of calibrated span | below it panels jitter by themselves |
| Press deadzone, SLIDE | `viewConfiguration.touchSlop` | the system's own "this is a move" |
| Slide full travel | 40 dp upward | about half a FULL pad's height on a 360 dp tall landscape phone |
| Press smoother | one-pole, 12 Hz at the touch rate (`k` about 0.47 at 120 Hz) | calm enough to not zipper, fast enough to follow a push |
| Press to engine | one `setGain` or `SetPitch` per frame per voice, 8 ms glide | the fader precedent uses 30 ms; a press is faster than a fader |
| STRIKE window | `[down - 4 ms, down + 12 ms]` | the patent's "about 20 ms"; the lower bound keeps the correction inside the transient |
| STRIKE correction | `setGain`, 4 ms glide, plus or minus 12 dB bound | inaudible as a ramp inside a drum's attack; bounded so a bus shake is not a hit |
| LAYERS WAIT | 12 ms fixed | constant, so it is adaptable; off by default |
| GRID hysteresis | 4 dp into the new pad | a finger resting on a boundary does not chatter |
| PITCH press range | plus or minus 2 semitones | a vibrato, not a dive; exposed |
| GRID range | plus or minus 24 semitones | under `kMaxSpeed = 64`; past two octaves a drum is noise |

## 6. Order of work, and what each unblocks

1. **Surface and light** (A, F, §2): the bank-level pointer loop, gap ownership, flash by
   velocity, held floor, exclusion rects, immersive. Roadmap P0.6 done for FULL. This alone
   changes the feel: no lost hits, two-thumb rolls, and a light that answers.
2. **Velocity modes** (B): POS, FULL LVL, 16 LV, the curve, then STRIKE with calibration. Roadmap
   P1.6, the velocity half. STRIKE is the risk and ships behind its own chip.
3. **PRESS and REPEAT** (C, D, `SetPitch`): CONTACT and SLIDE readers, LEVEL and PITCH
   targets, repeat rates on the display clock. P1.6's note-repeat half; P2.5's sensor
   gating pattern, in miniature. Depends on P0.2 and P0.3 for tightness, named in the chip's
   help text until then.
4. **GRID** (E): `PadLayout`, the swap-seam pitch, glissando by retrigger, SPREAD THIS. P2.3's
   grids with one code path; the FOURTHS table is the Launchpad mirror's future.
5. **The window's PERFORM chip**: the same surface on the in-window 4x4 with long-press off
   while it is on, so portrait playing gets all of it.

Bench rows this adds to `docs/BENCH.md`, section A (the phone alone), as questions:

- A8: Tap-to-tone on this phone with OboeTester, so the 20 ms touch figure stops being a
  2021 citation and becomes a number for the device in hand.
- A9: Does the panel report pressure that moves under a push? (The calibration screen
  answers it in ten seconds and should print the span.)
- A10: STRIKE: does a hard tap read harder than a soft one, phone in hand and phone on a
  table? What rate does the accelerometer give?
- A11: A 1/32 roll for four bars on NOTE REPEAT: is the unevenness audible? (Expected yes
  until P0.2; write down how much.)
- A12: GRID: a slide across five pads in IN KEY; a two-thumb line, drums left and grid
  right, for a minute. Would you play it?

## 7. Ledger

- **Verified** (read at `b429ec2`): everything in §0; the gesture code in `PadGrid.kt`,
  `KitScreen.kt:1445-1476`, `KeysScreen.kt:291-308`; `PadCommand`'s four types and the
  top-of-callback drain (`PadEngine.cpp:362`); `SetGain`'s glide; the swap seam in
  `PadEngine.hit`; `VoiceAllocator`'s slot-keyed release; `TouchSurface.Smoother`;
  `Scales.layout`, `KeysLayout`, `Spread`; `BeatPlayer.anchorNanos`; the haptics gate; no
  exclusion rects, no immersive mode, no `MotionEvent` read, no `androidx.input` in the
  build; `MidiWire` drops aftertouch and `MidiInput` drops the timestamp (not touched here;
  a hardware pad controller's aftertouch reaching PRESS is roadmap P2.1 and a two-line
  addition to `MidiPads.handle` once the event exists).
- **Measured on device**: 8 to 11 ms `latencyMillis` (output-side), exclusive path, bench
  A1, 2026-09-08, device not named in `BENCH.md`.
- **Cited**: touch latency 10 to 35 ms, 20 typical (Google, March 2021, five years old);
  the accelerometer velocity idea (US 8,884,888, 2014); consistency over minimum (WALT,
  2016, and the NIME surveys in the skill's references).
- **Proposed**: every number in §5; the AUTO rule for PRESS; ignoring the mute group in GRID;
  provisional-and-correct for STRIKE.
- **Unverified API names**: Compose's `PointerInputChange.pressure` and `historical`, and the
  foundation gesture-exclusion modifier. No Android SDK or Compose artifact is on this
  machine to grep; the implementing session should confirm them in the BOM 2024.12.01
  sources before writing against them. The platform names (`MotionEvent.getTouchMajor`,
  `View.setSystemGestureExclusionRects`, `WindowInsetsControllerCompat`,
  `HIGH_SAMPLING_RATE_SENSORS`) are from the Android reference.
- **Untestable here**: whether his panel's pressure moves; his accelerometer's rate and the
  `SensorEvent.timestamp` clock domain on his phone (historically not always
  `elapsedRealtimeNanos`; the STRIKE window is matched by bounded proximity to the down for
  that reason); how a 1/32 roll sounds on the display clock; whether STRIKE feels like
  anything on a phone lying on a table.

## 8. What I declined, in one line each

- An 8x8 grid in portrait: 360 dp across eight columns is 37 dp a pad with gaps, under
  `Layout.MIN_HIT_TARGET = 48`, which the repo calls non-negotiable; the 4x4 is settled in
  `docs/SYNTH_ROADMAP.md` and this design keeps it.
- Pressure as force: Android's `pressure` is not force on most panels; the design uses it
  relatively and says CONTACT, not FORCE.
- MPE or any MIDI output of the expression: there is no MIDI output in the repo (roadmap
  P0.4); the internal source model here is shaped so that when there is, PRESS becomes
  channel pressure or CC74 without the surface changing.
- A filter under aftertouch: the native voice has no filter; that is luthier's decision.
- A piano keyboard view: ruled out by the house's own settled design.

## 9. Why it is built this way

1. **The bank owns the touch, not the pad.** Everything expressive (slides, presses, rolls
   across a gap, a Launchpad mirror later) needs to see every finger over the whole grid.
   Per-pad gestures cannot, and that single structural change is what the other five
   pieces stand on. Remove it and PRESS and GRID glissando cannot exist.
2. **The semantics live on the JVM, the voice only hears numbers.** Velocity modes, press
   readers, layouts and repeat maths are pure functions in `:shell` with `./gradlew test`
   behind them, exactly where `PadHit` and `MidiPads` already are. The native change is one
   command that copies a pattern already in the file. If the app-side glue is wrong, a test
   says so; if the native side is wrong, it is five lines next to five lines that work.
3. **Nothing waits.** Sound stays on the first down; STRIKE corrects the gain after the
   fact instead of delaying the note; PRESS glides instead of retriggering; repeats inherit
   the display clock the beat already has rather than inventing a fifth clock. The feel
   research is unambiguous that steady beats fast and that jitter is the enemy; this design
   adds no jitter source and removes none, and it names the one (P0.2) that would.
4. **Every mode is honest about what it reads.** The chip says POS, CONTACT, SLIDE, STRIKE,
   and the status line prints the velocity it produced, so Josh can tell on the phone in ten
   seconds whether a mode is doing anything on his panel, instead of trusting a design
   written without the phone.
5. **The risky decision** is STRIKE: it depends on an accelerometer rate and a clock domain
   that vary by phone, it is covered by a live patent, and it may feel like nothing on a
   table. It ships behind its own chip with its own refusal text, and bench A10 decides it.

## 10. What would have changed this (for next time)

The phone model and Android version (the panel, the accelerometer rate and the API 34
nanosecond timestamps all hang on it); whether he plays FULL in landscape or the window in
portrait, since that changes which step ships first; whether he wants this as a spec for an
implementing session or as the Kotlin for step 1 directly; and, if he has Koala or a touch
test app installed, whether a held finger moves its pressure readout, which would settle
CONTACT versus SLIDE before any code is written.
