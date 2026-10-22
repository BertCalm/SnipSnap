# The app landscape and the device reality (late 2026)

What the leading Android music apps do with MIDI, where users complain, which phones and
OS versions behave, and what that means for SnipSnap. The app table is a survey of official
manuals and forum posts from 2025 to 2026, not a systematic review; the device material is
Google's own data (March 2021) plus community reports. Facts dated 2026-10-22.

Sources: [Koala manual, settings](https://manual.koalasampler.com/mobile/8-settings/),
[MIDI Hub FAQ on Koala](https://trueblueinteractive.com/midihub/faq/),
[FL Studio Mobile manual, controllers](https://www.image-line.com/fl-studio-learning/fl-studio-mobile-online-manual/html/plugins/FL%20Studio%20Mobile_Controllers.htm),
[FL Studio Mobile settings](https://www.image-line.com/fl-studio-learning/fl-studio-mobile-online-manual/html/plugins/FL%20Studio%20Mobile_HomePanel_Settings.htm),
[Image-Line forum, BLE option missing](https://forum.image-line.com/viewtopic.php?t=303328),
[G-Stomper Studio](https://www.planet-h.com/g-stomper-studio/), [G-Stomper what's new](https://www.planet-h.com/g-stomper-studio/gst-whats-new/),
[Audio Evolution Mobile on Loopy Pro forum](https://forum.loopypro.com/discussion/42441/audio-evolution-mobile-studio/p6),
[n-Track manual](https://ntrack.com/help/manual.html),
[Beepstreet forum, Drambo clock and Link](https://forum.beepstreet.com/discussion/2725/allow-drambo-to-send-midi-clock-when-ableton-link-sync-is-enabled),
[Oboe wiki, quirks and bugs](https://github.com/google/oboe/wiki),
[Android version history, Wikipedia](https://en.wikipedia.org/wiki/Android_16).

## 1. How the leading apps handle MIDI

| App | Publishes a virtual port? | Clock and Link | MIDI learn | BLE MIDI |
|---|---|---|---|---|
| Koala Sampler | not documented; opens other apps' ports (MIDI Hub FAQ) | clock SEND or RECEIVE plus Link; RECEIVE cannot combine with Link ("there can only be one clock source"); "Sync latency compensation" setting | mapping switch with learn; pads respond to notes only | yes |
| FL Studio Mobile | not documented | no Android clock or Link documented; MMC transport | move a screen control, then the hardware | "Scan Bluetooth MIDI devices"; a v4.3.2 user could not find the option |
| G-Stomper Studio | uses other apps' ports; opt-in startup scan; ships a "MIDI fallback driver" (fixed for Android 14) | clock in and out; Link since 4.0.0; warns BLE clock has variable wireless timing | channel, CC and key learn | host on Android 12+; needs Play Services |
| Caustic 3 | no | none documented | pitch bend and CC mappings | USB only |
| Audio Evolution Mobile | not documented | Android clock not confirmed; Link "perhaps at some point" (2021) | remote control setup; learn for key range | scan added 2026 (v5.5.5.9) |
| n-Track Studio | not documented | not documented | long-press, then "MIDI Learn" | pair in the OS first |
| Drambo (Android) | unknown | iOS users report clock out stops while Link is on | iOS v2.40 (August 2025) added mapping profiles and feedback | unknown on Android |

Interpretation: none of these documents publishing a `MidiDeviceService`. Absence of
documentation is not proof of absence (unverified.md), but it means no groovebox on Android
sells itself as "other apps can play me". **That is the gap SnipSnap can fill first**: a
sound module for other apps and a controller for them, with the virtual port named on the
Play Store listing. The second lesson is G-Stomper's: a mature app keeps a fallback USB
driver beside `android.media.midi` because the platform path fails on some devices.

## 2. Device and OS reality

- **OS versions**: Android 12 lost support on March 3, 2025 and Android 13 on December 1,
  2025. Android 16 shipped June 10, 2025; Android 17 (API 37) June 16, 2026. No MIDI API
  changes were found in 16 or 17 (unverified.md). SnipSnap's minSdk 29 covers Android 10
  and up: AMidi everywhere; Executor callbacks and USB UMP on 33+; virtual UMP on 35+; the
  USB open hang on 29 to 32 users (android-midi-api.md §2).
- **Devices**: Pixels have historically led on audio latency (Pixel 3A "the first 10ms
  Android phone", mid 2019); Samsung devices account for most USB MIDI detection reports
  and the USB audio routing quirk; Oboe's wiki "Quirks and Bugs" page lists Pixel, Samsung
  LSI (Exynos), Samsung Mobile, Huawei and emulator issues. **No reliable 2026 device latency
  ranking was found**; measure with OboeTester on the device in hand and put the number in
  the bench.
- **ChromeOS**: Android apps get MIDI through the container; Issue Tracker 140057001 notes
  `BluetoothGatt` problems. Test explicitly if ChromeOS matters.
- **USB-C audio plus MIDI**: one port means a hub; some Samsung routing interferes; a
  class-compliant audio interface that also carries MIDI is the cleanest single-cable setup.
- **Power management**: Doze, App Standby and OEM battery killers drop BLE and background
  audio. A foreground service during sessions (`connectedDevice` to hold devices), plus a one-time prompt to
  exempt SnipSnap from battery optimization with a link to dontkillmyapp.com for the OEM's
  steps.
- **Touch rates**: 120 Hz and 240 Hz touch sampling exist on flagships; most mid-range
  panels sample at 60 to 120 Hz. The touch latency figure (about 20 ms typical) is from
  2021 and has likely improved on flagships; measure, do not assume.

## 3. What users complain about (forum clusters, not a review study)

1. Device not detected (USB), with the Samsung routing workaround passed around.
2. BLE device not found, or found and stuck, or found and silent.
3. A Bluetooth option that the manual says exists and the settings screen does not show.
4. Clock drift and "no sync" between apps on the same phone.
5. No way to route one app into another.

Every one of these is answered by a diagnostics screen (endpoint list, live monitor, latency
test, copyable report), a virtual port, an in-app BLE scanner, and Link or a single
transport. The roadmap orders them that way.
