# A keyboard controller, end to end: the MiniLab 3 as the archetype

A plan to make one common small controller, the Arturia MiniLab 3, work fully with SnipSnap,
built so that every other class-compliant controller gets the same features for free. Read
against the tree at `9c10bab1` on 2026-10-24. The device facts come from Arturia's MiniLab 3
DAW integration scripts (FL Studio 1.0.1, Reason 1.0, both dated 2023-12-01), the Live user
guide (2022-10-18) and the firmware 1.2.0 image's `info.json` (2024-12-16). Nothing here was
run on a phone or on either device. Section 6 adds a second test device of the opposite
shape, the KORG nanoKEY Fold. Section 7 says what each claim rests on.

## 1. Why one archetype, and why this one

A plan for "any keyboard" has no test device and no finish line. A plan for one keyboard
risks special cases in screen code. This plan does both on purpose: the MiniLab 3 is the
device the work is proved on, and the rule is that **no screen ever learns its name**. Every
MiniLab-specific byte lives in one profile file; everything else is generic and is proved on
the MiniLab first.

The MiniLab 3 is a good archetype because it has one of every control a small controller
has: 25 velocity keys, 8 pads in two banks (16 notes), 8 knobs, 4 faders, a clickable main
encoder, pitch and mod touch strips, a sustain jack, RGB pad lights and a two-line display.
It is USB class compliant (vendor id `0x1C75`), USB-C, and sits near $109 new (June 2026
guide pricing). The second most common choice, an Akai MPK Mini, uses the same pad notes
(36 to 51 on channel 10, per `controller-profiles.md`), so the generic layers land on both.

## 2. What works today, and what the MiniLab would send that SnipSnap ignores

| MiniLab 3 control | What it sends | SnipSnap at `9c10bab1` |
|---|---|---|
| Keys | note on and off, channel 1 (Reason script `<100x>? yy zz`) | Played on KEYS through `MidiKeys`; every channel is heard |
| Sustain jack | CC 64 (Reason `b0 40 xx`) | Held by `MidiKeys` |
| Pads, 2 banks | notes 36 to 51 (FL script `FPC_MAP` keys) | Played on the kit screen through `MidiPads`, **but** the screen opens on `Map.MPC` every time (`KitScreen.kt:383`, `remember(entry.dir)`); under it notes 39, 41, 50 land outside the first 16 pads and A04, A14, A16 are unreachable. `Map.IN_ORDER` fits exactly |
| Pitch strip | pitch bend (Reason `e? xx yy`) | Dropped by `MidiWire` (`MidiWire.kt:202`, `else -> null`) |
| Mod strip | CC 1 (Reason `b0 01 xx`) | Parsed as `Event.Control`, then ignored |
| Pad pressure | aftertouch (type not in the scripts) | Dropped by `MidiWire` |
| 8 knobs (DAW program) | CC 86, 87, 89, 90, 110, 111, 116, 117 (Reason script lines 242 to 249; FL `KNOB_ID`) | Parsed, ignored |
| 4 faders (DAW program) | CC 14, 15, 30, 31 (Reason lines 237 to 240) | Parsed, ignored |
| Main encoder | CC 29 as a relative delta (Reason `b? 1d`, the `songPos` delta item) | Parsed, ignored |
| Knobs and faders (Arturia program) | not in the scripts; the FL script's `ANALOGLAB_KNOB_ID` lists 9, 16 to 19, 71, 74, 76, 77, 82, 83, 85, 93, 112 to 115 as the candidates | Parsed, ignored |
| Pad lights, display | SysEx **to** the device (section 4) | Impossible at `9c10bab1`: no `MidiInputPort` was opened anywhere. #661 adds `MidiOut`, which opens every device's input ports; the profile (step 6) is what is left |

The device exposes several ports. The Live guide names "MiniLab 3 MIDI", "MiniLab DIN THRU",
"MiniLab MCU" and "MiniLab ALV", and tells Live users to disable the last three.
`MidiInput.openDevice` opens every output port with its own `MidiWire`, which is safe, but a
control that sends on two ports would arrive twice. Bench row ML2 settles it.

## 3. The work, in order

Each step names where it lands, how we know it is done, and what it needs first. Steps 1 to
4 help every controller and need no MIDI output. Steps 5 and 6 are the MiniLab's lights and
display. Step 7 proves the design generalises. Rows that already exist in
[`MIDI_GAPS_2026_10.md`](MIDI_GAPS_2026_10.md) are cited by number rather than re-specified.

| # | Work | Lands | Proof | Needs |
|---|---|---|---|---|
| 1 | **Diagnostics screen** (gaps row 7): every endpoint with its fingerprint and ports, a live monitor of every event with port name and arrival delta, a copyable report. This is also the tool that reads the Arturia-program CC numbers off the device (bench ML3). Landed: `MidiScreen` behind the gear (SETUP · HELP · MIDI), `MidiMonitor` and `MidiReport` in `:shell` (`MidiMonitorTest`, `MidiReportTest`), `MidiInput(tapRaw = true)` with `Listener.onRaw` and `onRefused`, `MidiInput.endpoints` with port names, and CLOCK CHECK for bench S1. Not in it: `latencyMillis` (the screen runs no engine) and burst size. Compiled by `android-build` only; not yet run on a phone | `:app`, a pure model in `:shell` | the report pastes into an issue; the MiniLab's ports and each control's message are visible | nothing |
| 2 | **Pad map remembered per device.** Store the chosen `MidiPads.Map` keyed by `MidiFingerprint.key`, restored on connect. A profile (step 6) may set the default; with no profile, keep `MPC` so an MPC-shaped controller keeps working. Landed: `MidiPadMaps` (`MidiPadMapsTest`) is what KIT uses — the most recently connected hardware controller, panic first, saved to `PREFS` when the chip is tapped, and SNIPSNAP IN never stores. `MidiPadMemory` (`MidiPadMemoryTest`) keeps the same `midi.padmap.` prefix. Not in it: a profile default (step 6). Not yet run on a phone | `:shell` rule beside `MidiPads`, a small store in `:app`, `KitScreen.kt` | `MidiPadsTest` for the rule; reconnecting the MiniLab keeps "A01 = C1" | nothing |
| 3 | **Pitch bend, channel pressure and poly pressure in `MidiWire`**: new `Event.Channel` cases with the timestamp, length rules unchanged. Hostile fixtures first (`scripts/wire_fixtures.py`): running status across bend, bend split across deliveries, real-time bytes inside it. Landed: `Event.Expression` carries pitch bend (LSB first, -8192..8191, `BEND_CENTRE`), channel pressure and poly pressure; `Event.ProgramChange` is emitted too; `reachesScreens()` keeps expression and real-time bytes off the main thread; `MidiKeys` and `MidiPads` pass over expression and program change. Not run on a phone | `MidiWire.kt`, `MidiWireTest` | `:kit:test`; `MidiKeys` and `MidiPads` still ignore the new cases until step 4 says otherwise | nothing |
| 4 | **MIDI learn** (roadmap P1.1). The mapping record from `controller-profiles.md` §5: `(fingerprint, kind, channel, number) -> (target, curve, min, max, mode, takeover)`. Absolute, two relative encodings, toggle, momentary. Pickup takeover by default so a fader does not jump a value on load. A learn overlay: tap a target, move a control, the last message shows live. First targets, deliberately few: KEYS master gain, kit master gain, SURFACE controls already exposed through `NativeSurface.control`, KEYS bend range, and the screen's MIDI MAP and OCT buttons. First half landed: `MidiBinding`, `MidiLearn` and `MidiLearnStore` in `:shell` (`MidiLearnTest` 21): one-to-one learning, absolute with pickup or jump, both relative encodings, toggle, momentary, trigger, bend, pressure and program change, per-device or any-device sources, the panics never learnable, a forgiving versioned JSON store. Second half, on SURFACE: a MIDI panel learns X, Y, Z and HOLD (`SurfaceMidi`, `SurfaceMidiTest` 8); knobs move the puck, a key or pad on HOLD is the finger down, a finger always wins and the knobs pick up after it; bindings kept per screen under one preference key; `MidiInput.Listener.onEvent` names the sending device; bend and pressure now reach the screens. KEYS: a LEARN panel puts a knob on a new player volume (square law, held notes glide, a jump from full rather than pickup) and keys or pads on OCT − and OCT + (`KeysMidi`, `KeysMidiTest`); both screens share one `MidiLearnPanel`. KEYS bend range waits for step 5, since nothing bends yet. Not yet: kit targets, 14-bit CC, NRPN, curves, feedback | model and decoding in `:shell`; store and overlay in `:app` | JVM tests for each mode, relative decoding and pickup; on device a MiniLab knob moves a SURFACE control and a fader picks up without a jump | step 1 to see what arrives; step 3 for bend and pressure as sources |
| 5 | **Bend and mod play the notes.** Bend changes the speed of held KEYS voices within a bend range; mod is a learnable source like any CC. Held voices need a live pitch change, and `PadCommand::Type` has only `NoteOn`, `Stop`, `AllOff`, `SetGain` (`PadEngine.h:38`), so this adds a `SetPitch` command with a glide, the same shape as `SetGain`. Luthier reviews the resampling at the new rates | `PadEngine.{h,cpp}`, `jni.cpp`, `NativePads.kt`, `MidiKeys.kt` | an engine host test that a held voice changes rate without a click; on device the strip bends a held chord | step 3 |
| 6 | **MIDI output** (gaps row 4; landed with #661 as `MidiOut`), then **the controller profile model** and **the MiniLab 3 profile**. Output: open each device's input ports, one MIDI-out thread fed by a queue, never from the audio callback. Profile: a JSON file per device, matched by vendor and product id, holding an optional connect and disconnect message, a pad map default, a control map that pre-fills MIDI learn, pad LED ids and a colour rule, and a display rule. The MiniLab profile mirrors the kit: a loaded pad dim in its colour, a hit pad bright, an empty pad off; the display shows the kit name, and a moved knob's target and value | output: `MidiOut.kt` beside `MidiInput`; profile model in `:shell`; `app/src/main/assets/controllers/minilab3.json` | JVM tests that the profile parses and builds the exact bytes in section 4; on device the pads light as the screen does and the display follows a knob | step 4 for the control map; bench ML5 to ML8 |
| 7 | **A second profile** to prove the model, probably the MPK Mini or the Launchpad of gaps row 10. Done when it needs no change outside its JSON file and the profile model | one more JSON file | the second device works with no screen code change | step 6 |

Bluetooth (gaps row 8, `MIDI_BLE_SPEC_2026_10.md`) is not in this plan: the MiniLab 3 is
USB only. It stays where the gaps list ranks it.

## 4. The MiniLab 3 bytes the profile needs

All SysEx to the device shares one header, from the FL script's `send_to_device`
(`MiniLab3Dispatch.py:76`) and every Reason message: `F0 00 20 6B 7F 42 ... F7`
(`00 20 6B` is Arturia's manufacturer id). After the header:

| Purpose | Body | Source | Status |
|---|---|---|---|
| Host connect | `02 02 40 6A 21` | FL `DAWConnexion`, Reason `remote_prepare_for_use` | seen in both scripts |
| Host disconnect | `02 02 40 6A 20`, then `02 02 40 6A 10` | FL `DAWDisconnection` and `ArturiaDisconnection`, Reason `remote_release_from_use` | seen in both; the meaning of `10` is not documented (ML8) |
| Pad colour | `02 02 16 <pad> <r> <g> <b>` with each colour 0 to `7F`; pads `34` to `3B` (bank A) and `44` to `4B` (bank B) | FL `PAD_MATRIX`, Reason lines 880 and 1033 to 1040 | seen in both; FL sometimes adds a trailing `7F` |
| Display, two lines | `04 01 60 01 <line 1 ASCII> 00 02 <line 2 ASCII> 00` | Reason `ArturiaMessage`, FL `TestArturia` | seen in both; the richer `04 02 60 1F ...` page forms are left out of the first profile |
| Device report | `21 11 40 5D 00 <x> 00` arriving **from** the device | Reason line 802, sets a `MEMORY` flag | meaning not documented; the profile ignores it until ML9 |

The Live guide says the user selects the DAW program with Shift + Pad 3 and the knob and
fader CCs in section 2 belong to that program. Whether the connect message selects it by
itself is unknown (ML5). The first profile therefore asks the user, in words on the screen,
to pick the DAW program, and works without the profile in the Arturia program through step
4's learn.

The scripts are Arturia's code under their own copyright. The profile takes facts from them
(message layouts, ids, CC numbers); no script code is copied into the repo. The repo has no
LICENSE file, which matters as soon as third-party code is considered.

## 5. Bench rows (need the device and a phone)

| Row | Question | How |
|---|---|---|
| ML1 | Does the owner's phone detect and power it over one USB-C cable? | plug in; step 1's screen, or the `MidiInput` logcat tag before it exists; retry through a powered hub if dark |
| ML2 | Which ports appear, and does any control send on more than one? | step 1's endpoint list and monitor |
| ML3 | What CCs do knobs, faders and strips send in the Arturia program? | monitor, one control at a time; record into this file |
| ML4 | Are the DAW-program knobs absolute 0 to 127 (as the Reason script reads them) or relative? | monitor while turning past the ends |
| ML5 | Does `02 02 40 6A 21` switch it to the DAW program, or only announce a host? | send from step 6 with the device in the Arturia program |
| ML6 | Do the pad colour messages light the pads in both programs? | send all 16 from step 6 |
| ML7 | How many characters fit per display line? (the Reason script cuts at 31) | send a long line |
| ML8 | What does the trailing `02 02 40 6A 10` restore? | disconnect with and without it |
| ML9 | Is pad pressure poly or channel aftertouch, and what is `21 11 40 5D`? | monitor while pressing a pad; while changing program |

## 6. A second test device: the KORG nanoKEY Fold

The owner has bought a nanoKEY Fold too, on purpose: it is the other common shape of small
controller. The MiniLab 3 has pads, knobs, lights and a screen; the Fold has 25 key
*buttons*, two touch sliders and nothing else. A plan that only ever met the MiniLab could
hard-wire assumptions the Fold breaks, so the Fold is the check on steps 1 to 4 that the
MiniLab cannot be. Facts below are from KORG's *nanoKEY Fold MIDI Implementation* 1.00
(2024-09-18), the owner's manual (E2) and the 1.09 updater's release note, all read, not run.

| Control | What it sends | SnipSnap at `9c10bab1` |
|---|---|---|
| 25 key buttons | note on `9n kk VV`, note off `8n kk 40`, on the global channel | Played on KEYS and the kit. **Velocity is fixed**: one value (1 to 127) for every key, set on the unit with EDIT + velocity and keys 1 to 13, or as the global "Const Velocity Value" |
| Keyboard modes | Normal, Scale Guide, Easy Scale and Chord send notes; CC/PC sends a CC or program change per key | Notes play; CC/PC mode is step 4's learn, and program change is dropped by `MidiWire` |
| Sustain button | a CC, number configurable (scene byte 250), momentary or toggle, with a *switch speed* that glides from the off value to the on value instead of jumping | Held by `MidiKeys` if the number is 64: it reads `value >= MidiWire.CC_SWITCH_ON`, so a glide crosses once and switches once |
| PITCH slider | pitch bend `En`, with a configurable return speed; or touch-keyboard notes; or a CC in Touch CC mode | Bend dropped until step 3; touch-keyboard notes play as notes |
| MOD slider | a CC, number set in KORG KONTROL Editor | Ignored until step 4 |
| Arpeggiator | notes on the global channel, plus **timing clock `F8`** (clock source Auto, Internal or External-USB) | Notes play. `MidiInput` keeps the clock off the main thread and `MidiMonitor` counts it rather than listing it |
| Identity | answers the universal Identity Request `F0 7E 7F 06 01 F7` with `F0 7E 0g 06 02 42 6A 01 01 00 ...` (KORG, family `016A`, then the firmware version) | Not asked: SnipSnap sends no MIDI yet |

Power and connection: USB-C, bus-powered, no battery and no Bluetooth. A USB adapter must
give 5 V at 550 mA or more. It also has a MIDI out jack, which still needs USB power.

### What the Fold adds to the plan

1. **Step 4 must not assume velocity is expressive.** A learned target such as KEYS gain
   should not be tied to velocity alone. The diagnostics report should say "every note
   arrived at velocity N" when that is true, so a quiet KEYS is explained, not reported.
2. **Step 3 must handle bend that springs back at a set speed**, which a touch slider does
   and a MiniLab strip does not. That is a stream of `En` values, not one, and the held
   voices' `SetPitch` glide (step 5) must not lag behind it audibly.
3. **A clock source is not a tempo master.** The Fold sends `F8` whenever its arpeggiator
   runs on its internal clock. When the clock follower arrives (roadmap P1.8), a keyboard's
   arpeggiator clock must not take over SnipSnap's tempo unless the user picks that device.
4. **A generic identity lookup.** Once MIDI output exists (step 6), the MIDI screen can send
   the universal Identity Request to every device and print the manufacturer, family and
   firmware it answers with. That works for the Fold, and for any device that implements the
   universal message, with no profile at all.
5. **Step 7's second profile may not need to be the Fold.** Its SysEx (scene dumps, parameter
   change `41`) configures the keyboard; it does not light or label anything. A Fold profile
   would be worth writing only to set a known scene on connect, which is a later question.

### Bench rows for the Fold

| Row | Question | How |
|---|---|---|
| NF1 | Does the owner's phone detect and power it over one USB-C cable? | plug in; MIDI screen; retry through a powered hub if dark |
| NF2 | What velocity do the keys send out of the box? | monitor, one key |
| NF3 | Which CC does the sustain button send, and what values does a press produce at each switch speed? | monitor while pressing; change the speed with KONTROL Editor |
| NF4 | Which CC does the MOD slider send by default? | monitor |
| NF5 | How many bend messages does one PITCH slide and its return produce, and how far apart? | monitor's arrival deltas |
| NF6 | Does the arpeggiator send `F8` with the arp off, on, and latched? | monitor's clock count |
| NF7 | With the octave shifted down one, does the lowest key send note 36, so "A01 = C1" reaches all 16 pads? | monitor, lowest key |

## 7. Ledger

- **Verified in the repo at `9c10bab1`**: the `MidiWire` drop of bend and pressure; `MidiInput`
  opening output ports only; `KitScreen` starting on `Map.MPC`; `PadCommand::Type`'s four
  commands; `MidiFingerprint`'s fields and `key`; the absence of `app/src/main/assets`.
- **Read in the uploaded vendor files, not run**: every byte and CC number in sections 2 and 4,
  with its file named. They are DAW-program behaviour as two scripts use it, not a published
  specification.
- **Cited**: the MPK Mini pad notes (`controller-profiles.md` §4, marked VERIFY per model);
  pricing from a June 2026 buying guide.
- **Proposed**: the step order, the first learn targets, `SetPitch`, the profile shape, the
  LED mirror rule, keeping `MPC` as the default with no profile.
- **Read in KORG's nanoKEY Fold documents, not run**: every message and parameter in section
  6, with the document named.
- **Untestable here**: ML1 to ML9 and NF1 to NF7.

## 8. Why it is built this way

1. **Generic first, MiniLab second.** Steps 1 to 4 work for any keyboard, so a user with a
   different controller gets learnable knobs and a remembered pad map the day they land. If
   the order were reversed, SnipSnap would light one brand's pads and still ignore every
   other brand's knobs.
2. **The diagnostics screen comes first** because it is how the unknowns in section 5 get
   answered, and how a user with any other controller can say what went wrong. Without it,
   every bug report is "it does not work".
3. **One profile file per device** keeps vendor bytes out of screen code. The test that this
   held is step 7: a second controller that needs only its own file. If a screen ever checks
   a vendor id, that test fails.
4. **MIDI learn instead of a fixed knob list**, because Arturia-program CCs are user-editable
   and other brands differ. The profile only pre-fills learn, so a wrong guess there costs a
   re-learn, never a dead knob.
5. **The risky decision** is `SetPitch` in the native engine (step 5): it is the only step
   that touches the audio thread. It reuses `SetGain`'s glide shape and the existing ring,
   adds no new path onto the callback, and gets a host test before any device test.
