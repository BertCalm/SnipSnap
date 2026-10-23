# Controller profiles, LED feedback, MIDI learn

Bidirectional hardware: the screen grid and the hardware grid as one instrument, knobs that
map themselves, lights that tell the truth. Facts dated 2026-10-22; every SysEx byte below
is from the named vendor document and must be re-read from that document before it is
hard-coded (vendors revise programmer references).

Sources: [Launchpad X Programmer's Reference Manual (Focusrite/Novation PDF)](https://fael-downloads-prod.focusrite.com/customer/prod/s3fs-public/downloads/Launchpad%20X%20-%20Programmers%20Reference%20Manual.pdf);
[Launchpad Mini MK3 Programmer's Reference](https://manualzz.com/doc/53273609/novation-launchpad-mini-mk3-manual);
[Launchpad Pro MK3 Programmer's Reference](https://www.manualslib.com/manual/1830110/Novation-Launchpad-Pro-Mk3.html?page=12);
[Launchpad Pro (original) Programmer's Reference 1.01](https://fael-downloads-prod.focusrite.com/customer/prod/s3fs-public/downloads/Launchpad%20Pro%20Programmers%20Reference%20Guide%201.01.pdf);
[Novation, controlling the Launchpad X surface](https://userguides.novationmusic.com/hc/en-gb/articles/24001475492498-Controlling-the-Launchpad-X-surface);
[Ableton Push 2 MIDI and Display Interface Manual](https://github.com/Ableton/push-interface/blob/main/doc/AbletonPush2MIDIDisplayInterface.asc);
[CDM on the Push 2 release](https://cdm.link/2016/03/ableton-just-released-every-last-detail-of-how-push-2-works/);
[push2_display README](https://github.com/Simon-Claudius/push2_display/blob/master/README.md);
[live-mix PR #66 (Push 3 keeps the protocol, adds MPE)](https://github.com/kieranklaassen/live-mix/pull/66);
[dvhdr/launchpad-pro open firmware](https://github.com/dvhdr/launchpad-pro).

## 1. Profiles are data

A controller profile is a JSON file per device, not a branch in screen code:

```json
{
  "id": "novation-launchpad-x",
  "match": { "vendorId": 4661, "productId": 275, "nameContains": "Launchpad X" },
  "init": ["F0 00 20 29 02 0C 0E 01 F7"],
  "teardown": ["F0 00 20 29 02 0C 0E 00 F7"],
  "grid": { "rows": 8, "cols": 8, "noteAt": "11 + row*10 + col", "topRowIsRow7": true },
  "leds": { "static": {"channel": 1}, "flash": {"channel": 2}, "pulse": {"channel": 3}, "rgb": "sysex" },
  "palette": { "off": 0, "red": 5, "green": 21, "blue": 45, "white": 3 },
  "controls": { "topRow": [91, 92, 93, 94, 95, 96, 97, 98], "sideColumn": [89, 79, 69, 59, 49, 39, 29, 19] }
}
```

The ids above are illustrative; the vendor and product ids come from `MidiDeviceInfo`
properties on the device in hand, and the note formula from the programmer's reference. The
profile loader validates the shape; the screen asks the profile for "note for (row, col)"
and "message to light (row, col) with colour c in mode m".

## 2. Novation Launchpad (Programmer mode)

- **SysEx header**: `F0 00 20 29 02 <dev>` where `<dev>` is `0C` for Launchpad X, `0D` for
  Mini MK3, `0E` for Pro MK3, `10` for the original Pro, `18` for MK2.
- **Programmer/Live mode switch** (X and Mini MK3): `F0 00 20 29 02 0C|0D 0E <mode> F7`,
  `01` = Programmer, `00` = Live. Send on connect and restore Live on disconnect so the
  device is usable with other software afterwards.
- **LEDs by note-on**: channel 1 static, channel 2 flashing, channel 3 pulsing; the velocity
  selects a palette index (128 colours). A SysEx message sets RGB per pad (the "LED
  lighting" message, with colour type 3 taking three 7-bit components). Multi-pad updates go
  in one SysEx to avoid 64 note-ons per frame.
- **Grid notes in Programmer mode**: pads are `11` to `88` reading as `row*10 + col` with
  row 1 at the bottom; the top row of buttons and the right column are CCs.
- **Pro MK3 and the original Pro**: polyphonic aftertouch per pad (`A0`), which is the
  pressure source for note repeat and MPE-style pads.
- **Open firmware**: dvhdr/launchpad-pro is an idea source for standalone grid behaviour
  (self-contained sequencers on the device); read-only unless its license is checked.

## 3. Ableton Push 2 (and Push 3)

- Ableton published the whole MIDI and display interface (Ableton/push-interface, 2016).
- **SysEx**: header `F0 00 21 1D 01 01 <cmd> ... F7`. Commands include Set LED Color Palette
  Entry (`03`), Reapply Palette (`05`), Set LED Brightness (`06`), Set MIDI Mode (`0A`; User
  mode `01` so Push stops expecting Live). LED animations (blink, pulse) sync to incoming
  MIDI clock, so sending clock to Push makes its lights breathe in time.
- **Pads**: 8x8, notes 36 to 99 in User mode, with polyphonic aftertouch; encoders send
  relative CCs; the touch strip sends pitch bend.
- **Display**: 960x160 pixels over a separate USB bulk endpoint, 16-bit RGB565 frames with a
  signature header, XOR-masked. On Android this is `UsbDeviceConnection.bulkTransfer` on the
  display interface, which is USB host code beside the MIDI API, not inside it. A P3 item.
- **Push 3**: a 2026 open-source driver PR reports the protocol is kept and MPE added.

## 4. APC, MPK, Launchkey, Maschine

- **Akai APC mini** (and APC40): pads are notes; LED colour is the note-on velocity (off,
  green, green blink, red, red blink, yellow, yellow blink on the original; the mk2 has a
  128-colour palette and RGB SysEx). Faders are CCs 48 to 56.
- **Akai MPK mini**: pads on channel 10 by default (notes 36 to 51 across two banks), eight
  knobs as CCs, pad LEDs reflect their own state only on some models (VERIFY per model).
- **Novation Launchkey**: pads light by note-on velocity in its "DAW mode" (InControl),
  entered by a SysEx or a note on channel 16 (VERIFY per generation in the programmer's
  reference).
- **NI Maschine**: the controller is HID, not class-compliant MIDI, on most generations;
  Maschine MK3 and Mikro MK3 have a MIDI mode. Treat as unsupported until a device is in
  hand.

Each becomes a profile; the screen code never learns a vendor's name.

## 5. MIDI learn

A mapping is a record, not screen state:

```
(endpoint fingerprint, kind: NOTE | CC | CC14 | NRPN | PITCH | PRESSURE | PROGRAM,
 channel or ANY, number)
  -> (target: a parameter path, curve, min, max,
      mode: ABSOLUTE | RELATIVE_TWOS | RELATIVE_BINOFFSET | TOGGLE | MOMENTARY | TRIGGER,
      takeover: JUMP | PICKUP | SCALE,
      feedback: NONE | VALUE | LED)
```

- **Learn-all overlay**: every learnable control on screen lights up; tap one, move a knob,
  done; the last received message is shown live so a wrong channel is visible.
- **Profiles**: per project and global; a global profile is the user's controller, a project
  profile is a song's tricks.
- **Relative encoders**: two's complement (`01` up, `7F` down) and binary offset (`41` up,
  `3F` down) are both common; detect from the first few values or let the profile say.
- **14-bit CC**: MSB on n, LSB on n+32; NRPN through 99/98/6/38.
- **Soft takeover** (pickup): a control does nothing until it crosses the parameter's current
  value, so loading a project does not jump every knob.
- **Feedback**: resend every mapped value to the controller after a project loads and after
  a bank change, so motorized faders and LED rings show the truth.
- **Storage**: DataStore or Room in `:app`, with the mapping model in `:shell` so the rules
  (relative decoding, takeover, curves) are JVM-tested.

## 6. The mirror rule

The on-screen 8x8 and a connected Launchpad are the same instrument: same layout, same
colours (the profile palette maps the app's scheme colours to the nearest palette index),
same state (a pad lit on screen is lit on the device, a playhead sweeping on one sweeps on
the other). The profile's LED update is driven from the same state the screen draws, on the
MIDI-out thread, rate-limited to the device's comfort (a full 64-pad refresh at 60 Hz over
USB is fine; over BLE it is not).

## 7. What a CONTROL deliverable must contain

- The profile JSON for the named device with every byte sourced to the programmer's
  reference (cite the document and the date read).
- The init and teardown sequences and what happens on a cable pull mid-session.
- The LED update path (which thread, what rate, what state it mirrors).
- The learn record for any mapped controls and the takeover mode.
- A bench row: the device in hand, in Programmer mode, pad press shows on screen and screen
  state shows on the device.
- "Why it is built this way" for Josh.
