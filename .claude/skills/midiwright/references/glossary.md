# Glossary: MIDI terms, specs and links

- **MIDI 1.0**: the 1983 byte-stream protocol; 7-bit data, 16 channels, 31.25 kbaud on DIN.
  Specs at [midi.org/specs](https://midi.org/specs).
- **Running status**: omitting a repeated status byte; data bytes follow the last status.
  `MidiWire` keeps it per stream.
- **Real-time messages**: single bytes that may land anywhere: Clock `F8`, Start `FA`,
  Continue `FB`, Stop `FC`, Active Sensing `FE`, Reset `FF`.
- **System common**: Song Position Pointer `F2` (14-bit, in sixteenths), Song Select `F3`,
  Tune Request `F6`, MTC quarter frame `F1`; these cancel running status.
- **SysEx**: `F0 ... F7`; manufacturer or universal; the vehicle for controller protocols,
  MTS and MIDI-CI.
- **RPN / NRPN**: (Non-)Registered Parameter Numbers via CC 101/100 (RPN) or 99/98 (NRPN)
  then data entry CC 6/38. RPN 0 is pitch bend range; RPN 6 is the MPE Configuration Message.
- **CC**: Control Change, 0 to 127, 7-bit; 14-bit pairs use n and n+32. CC 64 sustain, CC 74
  the MPE timbre axis, CC 120 All Sound Off, CC 123 All Notes Off.
- **Aftertouch**: channel pressure `D0` (one value per channel) or polyphonic key pressure
  `A0` (per note).
- **Pitch bend**: `E0`, 14-bit, centre 8192; range set by RPN 0.
- **MPE**: MIDI Polyphonic Expression, M1-100-UM v1.1; one channel per note inside a zone;
  [midi.org/mpe](https://midi.org/mpe-midi-polyphonic-expression).
- **MCM**: MPE Configuration Message, RPN 6 on a zone's master channel.
- **MIDI 2.0 Protocol**: 16-bit velocity, 32-bit controllers, per-note controllers and
  pitch bend, note attributes; carried in UMP.
- **UMP**: Universal MIDI Packet, 32 to 128-bit packets, 16 groups x 16 channels, carrying
  MIDI 1.0 or 2.0 messages (M2-104-UM).
- **MIDI-CI**: Capability Inquiry, universal SysEx for discovery, Profiles and Property
  Exchange (M2-101-UM). Android has no service for it; a UMP device answers alone.
- **JR Timestamps**: Jitter Reduction timestamps, UMP utility messages carrying a sender
  clock so the receiver rebuilds timing.
- **Pitch 7.9**: a MIDI 2.0 note-on attribute: 7-bit note plus 9-bit fraction, for
  microtonal notes without bend.
- **SMF / SMF2**: Standard MIDI File (type 0 and 1) and the MIDI Clip File (M2-116-U).
  `MidiGroove` reads and writes SMF.
- **MTS**: MIDI Tuning Standard, SysEx bulk dumps and single-note retuning.
- **MTS-ESP**: ODDSound's tuning system and 0BSD client library;
  [github.com/ODDSound/MTS-ESP](https://github.com/ODDSound/MTS-ESP).
- **Scala**: `.scl` scale and `.kbm` keyboard mapping files, the lingua franca of
  microtuning.
- **BLE MIDI**: MIDI over Bluetooth Low Energy, service UUID
  `03B80E5A-EDE8-4B33-A751-6CE34EC4C700`, 13-bit millisecond timestamps per packet.
- **RTP-MIDI / AppleMIDI**: MIDI over UDP and RTP (RFC 6295), with session management;
  what Macs and iPads speak over the network.
- **Network MIDI 2.0**: the MMA's UDP transport for UMP, shipped in Windows MIDI Services.
- **Ableton Link**: LAN tempo, beat, phase and start/stop sync, each peer keeping its own
  timeline; GPLv2+ or commercial; [ableton.github.io/link](https://ableton.github.io/link/).
- **MIDI Clock**: 24 PPQN timing pulses, `F8`; with Start, Continue, Stop and SPP it is the
  classic transport sync.
- **MTC**: MIDI Time Code, SMPTE-style time in quarter-frame messages; a tape and video
  thing, not a beatmaking one.
- **MMC**: MIDI Machine Control, transport commands over SysEx (FL Studio Mobile uses it).
- **PPQN**: pulses (ticks) per quarter note; 24 for clock, 96 to 960 in files and
  sequencers.
- **Isomorphic layout**: a grid where every interval has the same shape in every key
  (fourths, Wicki-Hayden, Harmonic Table).
- **P-lock**: parameter lock, a per-step override of a voice parameter (Elektron).
- **Conditional trig**: a step that plays by rule (A:B, FILL, PRE, NEI, 1ST, probability).
- **Ratchet**: a step subdivided into N retriggers.
- **Euclidean rhythm**: hits spread as evenly as possible over steps (Bjorklund's
  algorithm; Toussaint 2005).
- **Note repeat**: tempo-synced retriggering while a pad is held, rate often by pressure.
- **16 levels**: the MPC mode spreading one sample across 16 pads by velocity, filter,
  tune or attack.
- **Soft takeover / pickup**: a control does nothing until it passes the parameter's
  current value.
- **Tap-to-tone latency**: screen touch to audible sound; OboeTester measures it.
- **Round-trip latency**: output to input through a loopback; what Google's figures report.
- **Burst**: the audio HAL's native block size (`framesPerBurst`); buffers are sized in
  bursts.
- **MMAP / Exclusive**: the AAudio path that maps the HAL buffer directly, the lowest
  latency mode; `Exclusive` sharing requests it.
- **CLOCK_MONOTONIC**: the Linux clock Android's MIDI timestamps, `System.nanoTime()`,
  `MotionEvent` times and Oboe timestamps share.
- **AMidi**: the NDK's native MIDI API, Android 10+, non-blocking receive, blocking send;
  [developer.android.com/ndk/guides/audio/midi](https://developer.android.com/ndk/guides/audio/midi).
- **MidiDeviceService / MidiUmpDeviceService**: Android services that publish a virtual
  MIDI 1.0 (API 23) or UMP (API 35) device.
- **Oboe / AAudio**: Google's C++ audio library over AAudio (and OpenSL ES on old versions);
  [github.com/google/oboe](https://github.com/google/oboe).
- **SPSC ring**: a single-producer single-consumer lock-free queue;
  `app/src/main/cpp/SpscRing.h`.
- **Programmer mode**: a Launchpad's mode where the host owns every pad and LED.
- **MPC note map**: Akai's default drum map (note 36 is pad A02 on the Live III, as
  `MidiPads.Map.MPC` encodes).
