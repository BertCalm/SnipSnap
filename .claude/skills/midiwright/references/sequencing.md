# Sequencing and performance techniques driven by MIDI

Per-step data, conditions, generators, groove, repeats, arps, capture, SMF, audio-to-MIDI,
and MIDI as the glue between the sampler and the synth paths. Facts dated 2026-10-22.

Read `docs/ORBITS.md` in the repo first: ORBIT is the circular sequencer that already does
polymeter and polyrhythm as rings of different lengths, with `OrbitFeel`, `OrbitPatterns`,
`OrbitBrush`, `OrbitClip` and `OrbitImport` in `loop/src/main/kotlin/com/snipsnap/loop/`.
`MidiGroove` (`:kit`) reads and writes Standard MIDI Files for grooves as `Mpc3Clip`s.
`BeatPlayer` plays a `GrooveProgram` over the kit's pads off a display-frame clock. Every
feature below is specified against the clock design in timing-and-scheduling.md §3; a step
that fires "immediately" on the frame that crosses it is the limitation MIDI_SYNC.md names.

Sources: Elektron manuals (conditional trigs, parameter locks), Mutable Instruments Grids
and Marbles firmware (MIT), Ableton Live manual (Capture MIDI, groove pool), Akai MPC
manuals (note repeat, 16 levels, swing), Toussaint, "The Euclidean algorithm generates
traditional musical rhythms" (2005), Music Thing Modular Turing Machine (open hardware).

## 1. Per-step data

A step carries: note (or pad), velocity, length (gate), micro-offset (signed, in ticks or a
fraction of a step), probability, condition, ratchet count and ratchet curve, and parameter
locks (any voice parameter overridden for that step only). `Mpc3Note` already carries note,
velocity and timing for export; the sequencer's own model can be richer and flatten on
export.

## 2. Conditional trigs (Elektron's grammar)

| Condition | Plays when |
|---|---|
| `A:B` | on pass A of every B passes (1:2 every other time, 2:4 second of four) |
| `FILL` / `NOT FILL` | the fill button is held / not held |
| `PRE` / `NOT PRE` | the previous conditional step on the track played / did not |
| `NEI` / `NOT NEI` | the neighbouring track's previous conditional step played / did not |
| `1ST` | the first pass only |
| `x%` | probability, combinable with any of the above |

Implement as a pure function `(condition, passIndex, fillHeld, prevPlayed, neighbourPlayed,
rng) -> Boolean` in `:kit` or `:loop` with a table-driven test. Seed the rng from the pattern
and pass so a performance is reproducible when the user wants it (`Dsp.seedFor` is the
house hashing pattern).

## 3. Generators

- **Euclidean**: `E(hits, steps, rotation)` by Bjorklund's algorithm; one row per track;
  Mutable's Grids is the reference for a map of drum patterns interpolated by X and Y.
- **Polymeter and polyrhythm**: per-track length (ORBIT has it), per-track clock divider
  and multiplier, tuplet grids (a track of 12 steps per bar against 16).
- **Turing Machine**: a shift register of bits with a "lock" probability; bits to pitches
  through a scale; the classic generative melody with a knob between frozen and random.
- **Marbles-style**: a random voltage quantized to a scale with a bias and a spread, and a
  "déjà vu" loop length that repeats the last N values.
- **Constrained randomization**: randomize velocity, micro-offset, probability or pitch
  within a user range, per bar, with undo.
- **Sensor sources** (expression.md §4) as generator inputs: tilt sets the Euclidean
  rotation, shake re-rolls the Turing register.

## 4. Groove and feel

- MPC-style swing from 50% to 75% on sixteenths (`GROOVE_SWING_DEFAULT` exists in the app).
- Per-step micro-offset and velocity templates (groove templates); import a groove from a
  MIDI file by measuring each hit's offset from the grid (`MidiGroove.read` gives the
  material).
- Humanize as bounded random micro-offset and velocity, seeded.
- Quantize on record with a strength percentage, never only 100%.

## 5. Note repeat and ratchets

- Note repeat: tempo-synced rates (1/4 to 1/64, triplets) chosen by a held button or by
  pressure (harder is faster: Maschine's and the MPC's rule); velocity from the pad.
- Ratchets: a step subdivided N times with a curve (even, accelerating, decelerating) and a
  velocity ramp; a performance-time variant triggered by pressure.

## 6. Arpeggiator

Modes (up, down, up-down with and without repeated ends, as played, random, chord), octave
range, gate length, swing, latch, step patterns (a 16-step on/off and accent mask over the
arp), rate synced to the transport. Keep the arp a pure function from (held notes, mode,
step index) to a note so it is testable; `loop/src/main/kotlin/com/snipsnap/loop/Arp.kt`
exists, VERIFY its shape before adding to it.

## 7. Macros and live performance

- A macro is one learnable control driving several targets, each with its own range and
  curve; eight per kit is the Circuit's proven count.
- Live MIDI looping: quantized overdub with per-pass undo; loop length set by the first
  pass or by the ORBIT ring.
- Capture the last N bars of played notes even when not recording (Ableton's Capture MIDI):
  keep a rolling buffer of timestamped UMP events exactly as `RingBuffer` keeps audio, and
  turn it into a clip on request. This is the feature a sampler app that already has
  retroactive capture should obviously have for MIDI too.
- Scene and pattern chaining with a next-pattern queue aligned to the bar.

## 8. Audio-to-MIDI and MIDI as glue

- **Pitch tracking**: YIN or pYIN with a confidence gate on the mic, producing note plus MPE
  bend for voice-to-synth. `:audio`'s `Pitch` (autocorrelation) is the existing detector;
  judge its latency and confidence before reuse.
- **Onset to pad**: `Transients` finds hits about 3 ms before the attack on recorded audio;
  live, spectral flux on the mic with 10 to 20 ms of analysis latency triggers pads
  (beatbox-to-drums, table drumming).
- **Slice to note map**: `Chopper` already chops a bar into pads by transients or evenly;
  the MIDI side is a note map (slice n to pad n, or note 36 + n) exported as a MIDI file that
  replays the groove of the original (the MPC chop and ReCycle REX idea). `MidiGroove.write`
  is the exit.
- **MIDI as glue**: the sequencer emits UMP to sampler voices, SURFACE parameters and
  external gear identically; any track targets any engine. This is the architectural reason
  the INTERNAL endpoint exists (timing-and-scheduling.md §7).

## 9. SMF and MIDI clip files

- `MidiGroove` reads and writes SMF (type 0 or 1, VERIFY) for grooves. Extend it rather
  than adding a second writer: tempo map, per-track names, CC lanes, and the MPC's own
  conventions (`docs/MPC3_FORMAT.md` says what the hardware reads).
- MIDI Clip File (SMF2, M2-116-U) is the UMP-native format; nothing consumes it yet. Not a
  priority.

## 10. What a SEQUENCE deliverable must contain

- The step model and how it flattens to `Mpc3Clip` for export.
- Each rule as a pure function with a test table (conditions, Euclidean, arp, groove).
- How steps become frames (ticks to frames per block from the tempo), never "fire now".
- The capture buffer's size and its relation to the transport.
- ORBIT's existing rings named, with what the design adds and what it leaves alone.
- "Why it is built this way" for Josh.
