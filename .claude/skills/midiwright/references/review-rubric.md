# Review rubric for MIDI code, specs and surfaces

Six lenses, a findings format, and the realtime audit that every MIDI review runs first. A
review's job is real findings, ranked, each with its why; if the subject is good, say what
makes it good in two lines and give the one real finding.

## 0. Read in this order

1. The KDoc or header comment: what the code claims (`MidiInput` and `MidiWire` claim a lot,
   precisely; hold them to it).
2. The thread the code runs on, line by line: which callback, which handler, which ring.
3. The timestamp's journey: where it is born, where it is dropped, where it becomes a frame.
4. The lifecycle: open, remove, close, close-after-open, a cable pulled mid-note.
5. The tests, and whether `:app` code has any proof beyond a compiler.
6. The docs that describe it (`app/README.md` phone notes, `docs/MIDI_SYNC.md`,
   `docs/BENCH.md`), for drift.

## 1. The lenses

**A. Transport and lifecycle (WIRE)**
- Port direction named from the device's side, with the comment.
- Enumeration by transport on 33+ and the deprecated pair below; de-duplication by id.
- Asynchronous open handled after close; the Java `MidiDevice` closed on release.
- The Android 10 to 12 input<output hang guarded or timed out.
- BLE: the opened device held for the session; identity by name and UUID; SysEx chunked.
- A device going away silences what it turned on, before the list shrinks.
- Failures reach the user in words.

**B. Timing (CLOCK)**
- Every event carries CLOCK_MONOTONIC nanoseconds from the edge to the frame.
- Commands carry a frame offset and the callback honours it mid-block.
- The ns-to-frame mapping is sampled off the audio thread (Oboe's pre-R rule) and smoothed.
- Sequencer events are in beats or ticks and become frames per block from the tempo.
- Clock out is computed from the audio clock, never a sleeping thread; clock in has an
  estimator, an offset and a separate holdover.
- Which of the four clocks the change touches, and that it adds no fifth.
- The bench items it depends on are named as questions, not invented as procedures.

**C. Surface (GLASS)**
- Sound on down, tracked by pointer id, cancel is a note-off.
- Historical samples used for slides; timestamps in the monotonic domain.
- Gesture exclusion, immersive mode, arbitrary aspect ratios.
- Velocity mode is a pure JVM function; calibration exists for device-dependent inputs.
- Expression has deadzones, smoothing and rounding; scale lock defaults on.
- Accessibility mode exists and TalkBack does not break the pads.

**D. Expression and events (EXPRESS)**
- The event set carries what the feature needs; `MidiWire` still never allocates or stalls.
- MPE zones honoured in and out; member channels allocated round-robin and released late.
- Per-note sources are first-class in the voice; a realtime-synth assumption calls luthier.
- Sensors gated, smoothed, calibrated, rate-limited per transport.

**E. Controllers and learn (CONTROL)**
- Profiles are data; no vendor name in screen code.
- Bytes sourced to a programmer's reference with its date.
- LED updates on the MIDI-out thread, rate-limited, mirroring screen state.
- Learn records with mode, takeover and feedback; values resent after load.

**F. Honesty and reuse**
- The ledger labels present: Verified, Measured on device, Cited (with date), Proposed,
  Untestable here.
- No invented API, measurement, license, control or SysEx byte.
- Reuse credited; copyleft sources read-only under a permissive license; the LICENSE flag
  raised once.

## 2. The realtime audit (run first on anything that touches the callback)

On every line that executes inside `onAudioReady` or anything it calls:

| Forbidden | Why |
|---|---|
| `new`, `malloc`, `std::vector::push_back` on a vector that may grow, `std::string` | allocation can take a lock in the allocator and page in memory |
| `std::mutex`, `synchronized`, any lock | priority inversion with the UI thread stalls audio |
| JNI calls, `__android_log_print` | the JVM may suspend the thread; logging takes a lock |
| `AMidiInputPort_send` | documented to block |
| `AudioStream::getTimestamp` before Android R | Oboe's own warning; minSdk 29 includes Q |
| Blocking I/O, `sleep`, waiting on a condition | the callback has about one burst to return |
| Unbounded loops over a ring (`while (pop)`) without a cap | a flood from a producer starves rendering; cap the drain per block |

Allowed: `SpscRing` push and pop, atomics with acquire and release, arithmetic, reads of
data the UI thread published whole (the `Bank` generation pattern), `AMidiOutputPort_receive`.

## 3. Findings format (always)

- **Fix now**: a correctness or realtime violation, with the line, the thread, and the
  consequence a player would hear (a stuck note, a late note, a dropout).
- **Try next**: an improvement with a measurable effect and the bench row that would show it.
- **Big idea**: a design change that would move the roadmap, named by its roadmap number.
- **Disclosed, not fixed**: a known limitation the code already documents (MIDI_SYNC.md's
  survey is the model); do not re-report it as new.
- **What I measured, what I only read**: the ledger. If you ran nothing, say "read only,
  commit <hash>".

Each finding carries the lens letter, the observation with a line reference or a number,
why it matters for the player or the architecture, and what to try. Never pad.

## 4. Reviewing a spec with no code

Run the minimum grep set in snipsnap-midi-state.md §7 and list it as the measured line.
Then the MIDI_SYNC.md checks: does the spec name APIs that exist, does it prescribe timing
it cannot have measured, does it add a clock, does it write bench procedures for hardware
it has not touched. Then the mechanics on their own terms. A spec that starts follower code
before S1 to S3 are answered gets a Fix now, not a Try next.

## 5. Reviewing a surface

Hold the phone in your head: a four-finger chord, a fast roll with two thumbs, a slide off
the edge, a palm resting on the bezel, a cable pulled mid-chord, TalkBack on. Each is a
test case; each should have an answer in the code or a disclosed limitation.
