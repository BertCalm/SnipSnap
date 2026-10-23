# Bluetooth LE MIDI on Android

Usable, fragile, and wildly hardware-dependent. Facts dated 2026-10-22; the bug list is
2022 to 2024 and may have moved on newer builds. SnipSnap has none of this wired yet
(no scan, no Bluetooth permissions, no `openBluetoothDevice`).

Sources: MMA "MIDI over Bluetooth Low Energy" (from Apple's 2014 spec, [midi.org specs](https://midi.org/specs));
[AOSP BluetoothMidiDevice.java](https://android.googlesource.com/platform/frameworks/base/+/master/media/packages/BluetoothMidiService/src/com/android/bluetoothmidiservice/BluetoothMidiDevice.java);
[Wang, Mulder, Wanderley, "Practical Considerations for MIDI over BLE", NIME 2019](https://www.nime.org/proceedings/2019/);
[Nordic DevZone, optimizing BLE MIDI timing](https://devzone.nordicsemi.com/nordic/nordic-blog/b/blog/posts/optimizing-ble-midi-with-regards-to-timing-1293631358);
[android-midi group, stuck BLE connections, Dec 2023](https://groups.google.com/g/android-midi/c/RdxL54PFJuw);
[JUCE forum, Android 13 BLE issues](https://forum.juce.com/t/android13-support-bluetooth-midi-pairing-issue/54468);
[TouchDAW manual, BLE](https://xmmc.de/touchdaw/man_midi_ble.htm);
[MobileSheets forum, Tab S7 on Android 13](https://zubersoft.com/mobilesheets/forum/thread-9334.html);
[CME, "The truth about Bluetooth MIDI"](https://www.cme-pro.com/the-truth-about-bluetooth-midi/) (vendor);
[Adafruit Learn, BLE MIDI](https://learn.adafruit.com/web-ble-midi/ble-midi).

## 1. The spec in four lines

- Service UUID `03B80E5A-EDE8-4B33-A751-6CE34EC4C700`; one characteristic
  `7772E5DB-3868-4112-A1A9-F2669D106BF3` with write-without-response and notify.
- Each packet begins with a header byte carrying the high bits of a 13-bit millisecond
  timestamp, then timestamp-low bytes before each status byte, so the receiver can rebuild
  the sender's timing inside a packet.
- Running status is allowed inside a packet; a packet never splits a message except SysEx.
- Packet size is the MTU minus the ATT and header overhead. AOSP's `BluetoothMidiDevice`
  subtracts 5 bytes from the negotiated MTU; the default MTU of 23 gives 18 bytes. Android's
  maximum MTU is 517.

## 2. The Android flow

1. Permissions: Android 12+ needs `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` (runtime); Android
   10 and 11 need `ACCESS_FINE_LOCATION` for scanning. SnipSnap's manifest has none; add them
   with the `neverForLocation` flag on `BLUETOOTH_SCAN` for 12+.
2. Scan with `BluetoothLeScanner.startScan(filters, settings, callback)` and a `ScanFilter`
   on the service UUID. Show results in SnipSnap's own list; do not send users to system
   Bluetooth settings, which pairs but does not make the device a MIDI device.
3. Call `MidiManager.openBluetoothDevice(bluetoothDevice, listener, handler)`. The system's
   `BluetoothMidiService` APK hosts the GATT connection and publishes a `MidiDevice` that
   every app can then see and open like a USB one.
4. **Hold the returned `MidiDevice`.** When the opener closes it, the device disappears for
   every app. Keep it in a foreground service for the session.
5. After that, `MidiInput`'s existing path works unchanged: the BLE device appears in
   `getDevicesForTransport(TRANSPORT_MIDI_BYTE_STREAM)` and `onDeviceAdded` fires.
6. Android is BLE **central** only through this API. A phone cannot advertise itself as a BLE
   MIDI peripheral (what an iPad can do) without a custom GATT server implementing the
   service; that is a P3 item.

## 3. Measured latency and jitter

| Setup | Latency | Jitter or SD | Source |
|---|---|---|---|
| BLE MIDI, minimal connection interval | 7.5 ms | SD 1.8 ms | NIME 2019 (McGill IDMIL) |
| BLE MIDI, custom firmware, longer interval | 19.1 ms | SD 2.7 ms | NIME 2019; paper reports "under 10ms under certain conditions" |
| Nordic nRF, 7.5 ms interval, unsynchronized | 2 to 9.5 ms, mean about 6.5 | one connection interval | Nordic DevZone |
| CME WIDI to WIDI, BLE 5 | 3 ms best, 4 to 6 realistic | n/a | CME (vendor claim) |
| Two MacBooks | about 26 ms | up to 10.4 ms | Gearspace (informal) |
| Rule of thumb | 10 to 20 ms BLE vs about 3 ms USB | n/a | Adafruit |

The shape of it: latency is a connection interval or two; jitter is up to one interval. The
phone cannot force a short interval; the peripheral requests it. Scheduling by the in-packet
timestamps (Nordic's method) adds a little latency and removes most of the jitter.

House rule: for live pad playing over BLE, play immediately (musicians forgive steady delay,
not jitter, but they forgive late more than they forgive dropped). For clock over BLE, use
the timestamps and smooth them; G-Stomper's manual warns BLE clock has "variable timing
characteristics of wireless transmission" and SnipSnap should say the same in words.

## 4. The bug list, by version

| Android | Symptom | Reference | Workaround |
|---|---|---|---|
| 13 (TQ1A builds) | Cannot disconnect; device stays listed after physical disconnect; recovery needs a restart | Issue Tracker 254101893, 246412046; android-midi Dec 2023 (Galaxy A33) | Reconnect by name and UUID, not MAC; tell users which build fixed it (TQ2A reportedly) |
| 13 and earlier | Cannot send SysEx or anything over 18 bytes in central mode | Issue Tracker 246412047; TouchDAW manual ("broken on Android versions before 14") | Chunk outgoing SysEx to the MTU; warn on 13 and earlier; request a larger MTU after connect |
| 13 | BLE MIDI broken outright in early builds, reported fixed | Issue Tracker 246166737 | Diagnostics screen reports build id |
| 12 to 13 | Cannot reconnect after app restart | Issue Tracker 234561550 | Unbond and rescan from the in-app list |
| various | Immediate "device removed" right after opening | Issue Tracker 182811259 | Retry once with backoff; show the event in the monitor |
| 13 (Tab S7) | Connects but passes no data | MobileSheets forum, Dec 2022 | Cancel the pairing-code prompt |
| all | The OS pairing screen "does not support BLE-MIDI… it can actually interfere"; the pairing app must keep running or the connection may time out | Phil Burk (Google), android-midi Dec 2023 | In-app scanner and foreground service; never route users to Settings |
| all | Randomized Bluetooth addresses make a known device look new | TouchDAW manual | Match on name plus service UUID |
| all | "Don't let anything go to sleep" | TouchDAW manual | Foreground service, wake lock during a session, battery-optimization exemption guidance |

## 5. SnipSnap rules for BLE (copy into any WIRE spec)

1. An in-app scanner with its own list, filtered on the MIDI service UUID, naming devices by
   `BluetoothDevice.name` and showing RSSI so a dead device is visibly dead.
2. The opened `MidiDevice` lives in a process-wide holder pinned by a foreground service for
   the whole session. Type it `connectedDevice` (its only prerequisite is a granted
   `BLUETOOTH_CONNECT`, which the feature holds anyway, and it has no Android 15 timeout);
   add `mediaPlayback` with a `|` only when background playback is actually in scope. The
   manifest currently declares only `microphone|mediaProjection`. Never let a screen's
   lifecycle own the device: KEYS and PLAY open and close their `MidiInput` on `ON_START`
   and `ON_STOP`, and a BLE device vanishes when its last client closes.
3. Reconnect on `onDeviceRemoved` with exponential backoff and a visible state: SEARCHING,
   CONNECTED, LOST. Stop after a bounded number of tries and say so.
4. Persist `(name, serviceUuid)` as the identity, never the MAC.
5. Outgoing SysEx chunked to the MTU; on Android 13 and earlier refuse long SysEx in words.
6. Per-endpoint input offset: BLE gets a larger default than USB in the scheduler
   (timing-and-scheduling.md §3), and the setting says why.
7. Diagnostics: build fingerprint (`Build.DISPLAY`), MTU negotiated, packets per second,
   last timestamp delta, so a user report is a paste, not a conversation.

## 6. What a BLE deliverable may claim

The numbers in §3 are cited from 2019 and from vendors. Nothing here has been measured on a
phone Josh owns. A spec says "cited, NIME 2019, 7.5 ms at the minimum interval" and adds a
bench row: BLE round trip on the device in hand, with OboeTester's MIDI latency test or a
loopback through a second device.
