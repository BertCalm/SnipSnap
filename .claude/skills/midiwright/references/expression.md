# Expression: MPE, MIDI 2.0 per-note, sensors, hardware paradigms on glass, microtuning

What a note can say beyond "on" and "off", and how to carry it from a keyboard, a finger or
a tilt into a voice and back out to hardware. Facts dated 2026-10-22.

Sources: [MMA, MIDI Polyphonic Expression (M1-100-UM v1.1)](https://midi.org/mpe-midi-polyphonic-expression),
[MPE spec PDF](https://d30pueezughrda.cloudfront.net/campaigns/mpe/mpespec.pdf),
[svgeesus, MPE final spec notes](https://github.com/svgeesus/MPE-notes/blob/master/MPE-final-spec-notes.md),
[francoisgeorgy/mpe-tester](https://github.com/francoisgeorgy/mpe-tester),
[moForte MPE notes](https://www.moforte.com/mpe/), [motifkit MPE](https://motifkit.com/mpe/);
[Wikipedia, MIDI 2.0](https://en.wikipedia.org/wiki/MIDI_2.0) and the MMA specs in glossary.md;
[ODDSound MTS-ESP](https://oddsound.com/devs.php), [github.com/ODDSound/MTS-ESP](https://github.com/ODDSound/MTS-ESP),
[Surge XT tuning guide](https://surge-synthesizer.github.io/tuning-guide/);
NIME and ICMC mobile-instrument work cited in touch-instruments.md §5.

## 1. MPE (MIDI Polyphonic Expression)

The idea: one channel per note, so pitch bend, pressure and a timbre controller apply to
each note separately on ordinary MIDI 1.0 hardware.

- **Spec**: M1-100-UM v1.1, replacing RP-053 v1.0 (2018) with editorial changes only.
- **Zones**: the Lower Zone's master is channel 1 with members counting up from 2; the Upper
  Zone's master is channel 16 with members counting down from 15. Configure with the MPE
  Configuration Message: RPN 6 on the master channel, data entry MSB = member count
  (`mm`), 0 turns the zone off. A device may run one zone (up to 15 members) or two.
- **Per note, on its member channel**: note-on velocity, pitch bend (default ±48 semitones
  per note, ±2 on the master; change with RPN 0 per channel), CC74 (the "slide" or timbre
  axis), channel pressure, release velocity. The final spec removed 14-bit CC74 (CC106 LSB)
  and the other per-note CCs the drafts had.
- **Master channel messages** (program change, CC, master pitch bend) apply to every note
  in the zone; sum master bend with the note's own.

### Output (SnipSnap as an MPE controller)

1. Allocate member channels round-robin, reusing the least recently released; cap polyphony
   at the member count (15 for one zone).
2. Send pitch bend and CC74 **before** the note-on so the note starts at its position.
3. Send pressure and bend as the finger moves, rate-limited to about 100 to 200 messages per
   second per channel on DIN or BLE, every change on USB.
4. On note-off, release the channel after the release tail has had its last pressure
   message; do not reassign it in the same block.
5. Send the MCM (RPN 6) on connect so the receiver configures itself.

### Input (SnipSnap as an MPE synth)

1. Honour the MCM; without one, treat the device as MPE only if the user says so (a
   non-MPE keyboard on channel 2 is not a member channel).
2. Route each member channel's bend, CC74 and pressure to the voice that owns that channel's
   sounding note; combine master-channel messages with every note in the zone.
3. Convert internally to MIDI 2.0 per-note controllers (UMP message type 4: per-note pitch
   bend, per-note controller, note-on with attribute), so the voice sees one shape whether
   the source was MPE, UMP or a finger. `MidiWire` drops pitch bend and aftertouch today;
   the event set grows first, with hostile-input tests.
4. Make Note Pitch, Note Timbre, Note Pressure, Note Velocity and Note Release first-class
   modulation sources in the voice's parameter set. For SnipSnap's sample voices that is
   pitch ratio, gain, loop window and (in the SURFACE) corner parameters; call luthier for
   anything deeper.

A MIDI 2.0 MPE Profile also exists for UMP devices; it maps the same idea onto groups and
per-note messages.

## 2. MIDI 2.0 per-note expression (UMP)

What MIDI 2.0 adds to expressive design, and why the internal model should be UMP even
while every connected device is MIDI 1.0:

- 16-bit velocity on note-on and note-off (65,536 steps against 127).
- 32-bit controllers, RPN and NRPN as single messages.
- Per-note pitch bend and per-note controllers (registered: modulation, breath, pitch 7.25,
  volume, balance, pan, expression, sound controllers; assignable: 256 per note).
- Note-on attributes: manufacturer-specific, profile-specific, **pitch 7.9** (a 7-bit note
  number plus a 9-bit fraction, for microtonal notes without bend).
- JR timestamps (jitter reduction): the sender stamps packets with a 16-bit clock in
  1/31,250-second units; the receiver reconstructs the sender's timing. Android exposes a
  `_AND_JRTS` protocol flag.

Design consequence: a per-voice modulation matrix with per-note sources is the same whether
the note arrived by MPE, UMP or touch. Translate at the edges; model once.

## 3. Hardware paradigms worth porting to glass

| Device | The expressive idea | What it becomes on a phone |
|---|---|---|
| LinnStrument | isomorphic fourths, per-note X (pitch) Y (timbre) Z (pressure), pitch quantize | the isomorphic grid with slide rails and rounding |
| ROLI Seaboard, Lightpad | strike, glide, slide, press, lift as five named dimensions | MPE pads: position velocity, horizontal glide, vertical slide, area press, lift speed |
| Haken Continuum | continuous pitch with adjustable rounding | a fretless ribbon with a rounding knob |
| Expressive E Osmose | key depth as a third axis, lateral wiggle for vibrato | vertical position as depth; a small horizontal wobble as vibrato depth |
| Sensel Morph, Joué, Erae Touch | swappable overlays, zone scenes | a user zone editor: pads, sliders, XY fields, ribbons on one screen |
| Ableton Push 3, Maschine | pressure-driven note repeat, in-key grid, sample names on pads | pressure note repeat; the kit's names on the pads |
| Novation Circuit | scale grid plus macros | eight macro knobs each driving several targets |
| Teenage Engineering OP-1, OP-Z | playful constraints, per-step components | per-step conditional, ratchet and random modifiers (sequencing.md) |
| Dato Duo | two-player sequencer | a split-screen collaborative mode |
| Artiphon Orba | tap, press, slide, tilt, shake as one gesture vocabulary | the per-pad gesture set with the phone's own sensors |
| Arturia MicroFreak | flat capacitive keys with poly aftertouch | proof that flat keys can be expressive; area-based aftertouch |
| Soma Ether, Playtron | the world as a controller | the `:audio` OUTSIDE idea's sibling: the mic as a modulation source |

Name the hardware in research and profile text; never in a preset, voice or engine name.

## 4. Sensor-driven modulation

The phone has what no grid controller has: accelerometer, gyroscope, rotation vector,
magnetometer, barometer, proximity, ambient light, a microphone and a camera.

- **History**: Essl and Rohs's ShaMus (ICMC 2007) used accelerometer and magnetometer for
  performance on the phone alone; MobileSTK (ICMC 2006) was the first full synthesis
  environment on a phone; Smule's Ocarina made blowing into the mic an instrument.
- **Mapping layer**: each sensor axis is a modulation source with calibration (rest pose),
  smoothing (one-pole or the 1 euro filter, which adapts cutoff to speed), a curve, a
  deadzone and a latch. Treat it exactly like a MIDI CC source in the learn system.
- **Tilt engage**: the sensor only moves a parameter while a finger holds a modifier pad or
  while a note is sounding. Without a gate, picking the phone up is a performance.
- **Output**: internal modulation first; outward as 7-bit CC, 14-bit CC, MPE pressure or
  CC74, or UMP per-note controllers. Rate-limit to 100 to 200 messages per second on DIN and
  BLE and send only on meaningful change (a threshold on the smoothed value).
- **Mic as controller**: an envelope follower for "blow" or "hum to play"; onset detection
  from `Transients` for beatbox-to-pads; pitch from `Pitch` for voice-to-synth with MPE bend
  (sequencing.md §8).
- **Camera**: hand tracking is a P3 idea; latency and battery say so.

## 5. Tuning and microtonality

- **MTS** (MIDI Tuning Standard): SysEx bulk dumps and single-note retuning; most hardware
  ignores it, some synths honour it.
- **MTS-ESP** (ODDSound, **0BSD**, the most permissive license there is): a client library
  that "parses incoming MIDI SysEx data and identifies all message formats defined in the
  MTS standard" and keeps a local tuning table. On desktop it also connects to a master
  (MTS-ESP Master or Surge XT) over shared memory; on Android there is no shared master
  between apps, so use the local table and the SysEx parsing.
- **Scala** (`.scl` scale and `.kbm` keyboard mapping) import is essential; Surge's tuning
  library is the readable reference implementation.
- **Per-note pitch outward**: MPE pitch bend or UMP pitch 7.9 for external gear; direct
  frequency per voice inside (a sample voice is just a pitch ratio, so microtonality is free
  on the sampler side).
- **Microtonal grids**: 19-EDO and 31-EDO on an isomorphic layout are a few parameters once
  the row offset is a parameter; the `:audio` `Scales` tables can grow a tuning column.

## 6. What an EXPRESS deliverable must contain

- The event model change (what `MidiWire` and the UMP queue gain) with hostile-input tests.
- The modulation sources added to the voice and what each moves in a sample voice.
- The MPE zone handling, in and out, with channel allocation rules.
- The sensor gate and smoothing constants, labelled proposed, with a calibration flow.
- Rate limits per transport.
- A luthier handoff line when a voice-level change is needed.
- "Why it is built this way" for Josh.
