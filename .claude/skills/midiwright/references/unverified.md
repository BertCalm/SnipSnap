# What the research could not confirm (as of 2026-10-22)

Check an item here before asserting it. Each line says how to check it, so the check is a
task and not a doubt. Shrink this file as things get confirmed; add to it when a new claim
rests on one source.

| Claim | Status | How to check |
|---|---|---|
| Android implements UMP 1.1 Stream messages (endpoint discovery, function blocks) | questioned in a midi.org comment | read `MidiUmpDeviceService` docs for the current API level and test with a UMP peer (ktmidi's tools) |
| Android 16 and 17 made no MIDI API changes | nothing found | read the API diff reports for API 36 and 37 under `android.media.midi` and `amidi` |
| A maintained open-source RTP-MIDI library exists for Android | none verified | search GitHub for "AppleMIDI Android" or "RTP-MIDI Kotlin"; check last commit dates |
| Drambo's Android MIDI features (virtual port, clock, BLE) | unknown | read the Android manual or ask on the Beepstreet forum |
| libremidi's license is BSD-style | general knowledge | read its LICENSE file on the day |
| kshoji USB-MIDI-Driver's license | not read | read its repo |
| dvhdr/launchpad-pro's license | not read | read its repo |
| Any of Koala, FL Studio Mobile, G-Stomper, Caustic, Audio Evolution, n-Track publishes its own `MidiDeviceService` | absence of documentation only | install each and list `getDevicesForTransport` from a test app; or decompile the manifest for `BIND_MIDI_DEVICE_SERVICE` |
| The Android 10 to 12 USB open hang is detectable from `MidiDeviceInfo` port counts before the open hangs | inferred from RtMidi's descriptor read | test on an Android 10 to 12 device with a 1-in-3-out controller |
| Touch latency has improved on 2024 to 2026 flagships beyond Google's 2021 "20 ms typical" | likely, unmeasured | OboeTester Tap-to-Tone on the device in hand |
| A 2026 device latency ranking | not found | OboeTester round trip on each device Josh has; put the numbers in `docs/BENCH.md` |
| Ableton grants free Link licenses to free apps | from their site, terms unverified | read ableton.com/link's licensing page and write to them |
| Launchkey DAW-mode entry sequence per generation | varies | the programmer's reference for the generation in hand |
| MPK mini pad LED feedback per model | varies | the model's manual |
| Push 3 keeps the Push 2 protocol and adds MPE | one 2026 open-source PR | read Ableton's updated interface document if published, or the PR's test notes |
| CME's 3 ms BLE figure | vendor claim | measure, or cite as vendor claim |
| The Gearspace 26 ms MacBook-to-MacBook BLE figure | informal forum post | cite as informal or drop |
| `MidiGroove` writes SMF type 0 or type 1 | not read in detail | `grep -n "MThd\|format" kit/src/main/kotlin/com/snipsnap/kit/MidiGroove.kt` |
| `Arp.kt` in `:loop` is a MIDI-style arpeggiator usable from a sequencer | file exists, shape not read | read it |
| Whether a `PartyKeys` "tested latency" table is real | marketing, excluded | ignore |

## Dated facts that will age

- Google's audio and touch latency figures: March 2021.
- The BLE bug list: Issue Tracker reports from 2022 to 2023, forum posts to December 2023.
- The USB open hang: fixed in Android 13 (2022); the RtMidi workaround is October 2026.
- Windows MIDI Services GA: February 17, 2026; the ACX driver regression: March 2026.
- ktmidi 0.12.0 on Maven Central with Kotlin 2.3.21: dates differ between trackers.
- Android 17 (API 37): June 16, 2026.
- SnipSnap's own state: commit `b429ec2`, 2026-10-22.
