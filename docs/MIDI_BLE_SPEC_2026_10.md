# Bluetooth MIDI keyboards on KEYS and PLAY

Engineering spec for an implementing session. Job: WIRE (a BLE keyboard becomes an endpoint the two screens already know how to play). No CLOCK work is in scope; the one timing rule that applies is "do not discard the timestamp", and that is the only change on the event path.

Repo read at `b429ec2` (2026-10-21, "Merge pull request #613"), read-only. Every path below is relative to the repo root. Names marked **(new)** do not exist in the tree today; every other name was found by grep.

## 1. What exists, and what the gap is

Verified at `b429ec2`:

- `app/src/main/kotlin/com/snipsnap/app/MidiInput.kt` opens every device `MidiManager` lists (transport-aware on 33+, the deprecated pair below), one `MidiWire` per output port, parses on the service thread, posts typed events to the main thread. `Receiver.onSend` drops the `timestamp` argument (line 186). `onDeviceRemoved` fires before the shorter list is announced. `close()` is idempotent. Its own KDoc (lines 21 to 24) says why BLE does not work: a Bluetooth keyboard is not in the list until some app calls `openBluetoothDevice` after a scan, with permissions this app does not hold.
- `KeysScreen.kt` (line 115) and `KitScreen.kt` (line 383, the screen PLAY lives on) each construct their own `MidiInput`, open it on `ON_START`, close it on `ON_STOP`. `:shell`'s `MidiKeys.handle(event)` and `MidiPads.handle(event, map, onHit, onRelease, onPanic)` are the tested rules and need no change.
- The readout line is `midiLine()` in `KeysScreen.kt` (line 239), reused by `KitScreen.kt` (line 1010); the invitation is `NO_MIDI_DEVICE = "NO MIDI KEYBOARD · PLUG ONE IN OVER USB"`.
- `app/src/main/AndroidManifest.xml`: no Bluetooth permission of any kind; `android.software.midi` declared `required="false"`; one foreground service, `MicSessionService`, typed `microphone|mediaProjection`; `FOREGROUND_SERVICE` and `POST_NOTIFICATIONS` already declared.
- `App.kt` (line 994) already requests two runtime permissions in one `RequestMultiplePermissions` dialog; `PREFS = "tapeos"` (line 194) is the app's `SharedPreferences`. `Copy` in `shell/src/main/kotlin/com/snipsnap/shell/Personality.kt` holds every string the UI says. SETUP is `PropertiesScreen.kt`. `ShareOut.send(context, file, mime, title)` is the door for a file off the phone; the app uses no clipboard anywhere.
- `app/build.gradle.kts`: minSdk 29, targetSdk 35, compileSdk 35. No Bluetooth or MIDI library; none is needed.

The gap is exactly one thing: nobody scans for the keyboard and nobody calls `openBluetoothDevice`. Once some process on the phone has done that and keeps the returned `MidiDevice` open, the system's `BluetoothMidiService` publishes the keyboard as an ordinary `MidiDeviceInfo` of type `TYPE_BLUETOOTH`, `MidiInput.onDeviceAdded` fires, and KEYS and PLAY play it with no change to their event path.

## 2. What to build

Five pieces. The first three are the feature; the last two are what makes a bug report a paste instead of a conversation.

### 2.1 Permissions and manifest (`app/src/main/AndroidManifest.xml`)

```xml
<!-- Bluetooth MIDI (BleMidiLink): the scan that finds a keyboard and the
     open that makes it a MidiManager device. 12+ asks "Nearby devices";
     10 and 11 have no such permission and gate scanning on location. -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />

<service android:name=".MidiLinkService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice" />
```

`neverForLocation` needs `tools:targetApi="s"` or the lint suppression the project prefers; it is what lets Android 12+ scan without any location grant. Runtime request, in one dialog through the existing `RequestMultiplePermissions` pattern: on 31+ `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` (plus `POST_NOTIFICATIONS` on 33+ for the service's notification, the way `requestArm` already bundles it); on 29 and 30 `ACCESS_FINE_LOCATION`, and then a check that location services are switched on, because a scan with them off returns nothing and no error.

Foreground service type: `connectedDevice`, not `mediaPlayback`. The midiwright reference recommends `mediaPlayback`; this spec departs from it because SnipSnap silences every screen on `ON_STOP` by design, so there is no background playback to claim, and `connectedDevice`'s one runtime prerequisite (a granted `BLUETOOTH_CONNECT`) is something this feature holds anyway. Cited: the Android foreground-service-types page, read 2026-10-22, lists `connectedDevice` as "interactions with external devices that require a Bluetooth, NFC, IR, USB, or network connection", with no Android 15 timeout.

### 2.2 The link: `app/src/main/kotlin/com/snipsnap/app/BleMidiLink.kt` (new)

A process-wide singleton installed from `SnipSnapApplication.onCreate` the way `AudioFocus.install(this)` is. It is the only code in the app that talks to `BluetoothLeScanner` or `MidiManager.openBluetoothDevice`. It owns:

1. **The scan.** `BluetoothLeScanner.startScan(filters, settings, callback)` with one `ScanFilter` on the BLE MIDI service UUID `03B80E5A-EDE8-4B33-A751-6CE34EC4C700`, `SCAN_MODE_LOW_LATENCY`, stopped after 20 s or when the scanner sheet closes. Results are published as a `StateFlow<List<Found>>` of `(name, rssi, BluetoothDevice)`, de-duplicated by address for the life of one scan only (the address is never persisted). Android throttles an app to five scan starts per 30 s and a throttled start returns nothing silently; the link enforces the budget itself and the sheet's SEARCH AGAIN chip greys out while it is spent.
2. **The open.** `MidiManager.openBluetoothDevice(device, listener, mainHandler)`. A null in the listener is a refusal in words. The returned `MidiDevice` is held for the whole session: the system removes a Bluetooth MIDI device when its last client closes, so the link's own handle is what keeps the keyboard alive while KEYS and PLAY open and close their screen-scoped `MidiInput`s on `ON_STOP` and `ON_START`. (Read from AOSP `MidiService.Device.removeDeviceConnection` in memory; the fetch of the current file was blocked today. The implementing session should read that method once and confirm "Bluetooth devices are ephemeral, removed when no clients exist" is still the rule.)
3. **The state**, driven by a pure `:shell` class (2.3): `OFF`, `SEARCHING`, `CONNECTING`, `CONNECTED(name)`, `LOST(name)`. Published as a `StateFlow` the screens and SETUP read.
4. **Reconnection.** The link registers its own `DeviceCallback` and watches for `onDeviceRemoved` of the info whose `PROPERTY_BLUETOOTH_DEVICE` matches the held device. On removal: the screens' existing `MidiInput.onDeviceRemoved` has already let every note go (keep that; it needs nothing from the link), the state goes `LOST`, and the link retries in two tiers. Within a session it calls `openBluetoothDevice` again on the same `BluetoothDevice` object it already holds (no scan, no throttle; a randomized address rotates on a scale of minutes, so an object found seconds ago is still good). Across launches it scans for the remembered `(name, serviceUuid)` and opens the first match. Backoff 3, 6, 12, 24 s, four tries, then it stops and says so. Proposed numbers; the scan budget is the constraint that shaped them.
5. **The identity** it remembers in `PREFS`: `midi.ble.name` and nothing else. Never the address (randomized on most peripherals; the reference's rule), never the display name alone as a key for USB (that is a different fingerprint and out of scope here).
6. **The service hand-off.** On `CONNECTED` it starts `MidiLinkService`; on a user DISCONNECT or after the retries give up it stops it and closes the `MidiDevice`.

`MidiLinkService.kt` (new) is twelve lines of foreground service: `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` in `onStartCommand`, a notification titled `BLUETOOTH MIDI · <NAME>` with one DISCONNECT action, `stopSelf` on the eject intent. It holds no state; it pins the process so Doze and the recents swipe do not take the link with them. Start it only from a foreground context (the scanner tap), which Android 14+ requires.

### 2.3 The rules on the JVM: `shell/src/main/kotlin/com/snipsnap/shell/BleLink.kt` (new)

A pure state machine so `./gradlew :shell:test` proves the part a phone cannot: `BleLink(clock)` with `found(name)`, `opened()`, `refused()`, `removed()`, `tick(nowMillis)` returning the next action (`Scan`, `ReopenHeld`, `GiveUp`, `Nothing`), the backoff table, the bounded try count, `matches(name, serviceUuid)` for identity, and the words for each state drawn from `Copy`. `BleLinkTest` covers: a removal inside 100 ms of an open retries once immediately (the "immediate device removed" bug, Issue Tracker 182811259); four failed tries end in `GiveUp`; a remembered name matches by name and UUID and never by address; the scan budget never exceeds five starts in any 30 s window.

### 2.4 `MidiInput.kt`: three small changes, no new path

- `Listener.onEvent(event: MidiWire.Event, timestampNanos: Long)`. `Receiver.onSend` passes `timestamp` through. KEYS and PLAY ignore the second argument today and keep playing on the main thread; the point is that the edge no longer throws the number away (roadmap P0.1, and the midiwright clock rule). `MidiWire.Event` itself is unchanged, so `:kit` and its tests are untouched.
- A monitor tap: the receiver also pushes `(timestampNanos, deviceId, eventSummary)` into a fixed-size ring (last 64) that SETUP reads. Same thread rules as today: the binder thread copies and returns.
- `deviceNames` grows a sibling `endpoints: List<Endpoint(name, transport, inPorts, outPorts)>` where transport comes from `MidiDeviceInfo.getType()` (`TYPE_USB`, `TYPE_BLUETOOTH`, `TYPE_VIRTUAL`; from the platform reference, no usage in the repo yet). The readout line stays `MIDI · <NAME>`; the transport is for SETUP and the report. Update the KDoc paragraph that says BLE is not wired.

Nothing here touches `PadEngine`, `SpscRing` or the Oboe callback. The realtime audit is empty for this spec by construction.

### 2.5 The screens and the scanner sheet

- `KeysScreen.kt` and `KitScreen.kt`: the invitation becomes `NO MIDI KEYBOARD · USB, OR TAP BLUETOOTH` with a BLUETOOTH chip beside it (hidden on a phone with no `FEATURE_BLUETOOTH_LE`, the way the whole line is hidden with no `FEATURE_MIDI`). While connected the chip reads the link state (`BT · SEARCHING`, `BT · <NAME>`, `BT · LOST`) so a dead keyboard is visibly dead on the screen being played. The event path (`midiKeys.handle`, `MidiPads.handle`) is unchanged.
- `app/src/main/kotlin/com/snipsnap/app/ui/MidiScanSheet.kt` (new): an in-app `Popup` (the pattern `KitScreen`'s FULL uses at line 1033) listing found keyboards by name with RSSI, a SEARCH AGAIN chip under the budget, the remembered name with CONNECT and FORGET, and the refusal lines below. Never a link to system Bluetooth settings: pairing there does not make a MIDI device, and the OS pairing screen can interfere with the GATT session (cited, android-midi group, Dec 2023).
- `PropertiesScreen.kt` (SETUP) gains a MIDI section: the endpoint list with transport and port counts, the link state, the last 64 monitor rows with arrival deltas, `Build.MODEL`, `Build.DISPLAY`, `SDK_INT`, and SEND MIDI REPORT, which writes a text file through `ShareOut` the way SEND TO BENCH does. The formatter is `shell/.../MidiReport.kt` (new), tested on the JVM.

New `Copy` strings, stated as fact in the house style:

| When | Line |
|---|---|
| no BLE hardware | `NO BLUETOOTH LE ON THIS PHONE` |
| adapter off | `BLUETOOTH IS OFF · TURN IT ON` (chip fires `ACTION_REQUEST_ENABLE`) |
| 12+ permission denied | `NEARBY DEVICES WAS REFUSED · ALLOW IT IN SETTINGS TO SCAN` |
| 10, 11 permission denied | `ANDROID 10 AND 11 NEED LOCATION TO SCAN FOR BLUETOOTH · NOTHING IS LOCATED` |
| 10, 11 location services off | `LOCATION IS OFF · TURN IT ON TO SCAN` |
| scan empty at 20 s | `NOTHING FOUND · IS THE KEYBOARD IN PAIRING MODE?` |
| open returned null | `<NAME> WOULD NOT OPEN · TRY AGAIN` |
| removed right after open | `<NAME> DROPPED · TRYING AGAIN` |
| retries exhausted | `<NAME> LOST · TAP TO SEARCH AGAIN` |
| scan budget spent | `SEARCHED TOO OFTEN · WAIT HALF A MINUTE` |
| Android 12 and 13, always under the list | `IF A PAIRING CODE POPS UP, CANCEL IT` |

## 3. What breaks on which Android version

| Android | API | What happens | What this spec does |
|---|---|---|---|
| 10, 11 | 29, 30 | Scanning needs `ACCESS_FINE_LOCATION` granted and the system location toggle on; off means zero results, no error | Location grant on these two only (`maxSdkVersion="30"`), a location-services check, both refusals in words. **Untestable here**: Josh has no 10 or 11 device and emulators have no BLE radio. Ships compiled, marked untested in `app/README.md` |
| 12 (the Galaxy Tab) | 31 | `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` become runtime permissions; `neverForLocation` removes the location requirement. Reconnect after an app restart can fail (Issue Tracker 234561550, 12 to 13). Long outgoing SysEx is broken (not relevant: nothing is sent). Samsung's pairing-code prompt has been reported to leave a link connected but silent (MobileSheets forum, Dec 2022, a Tab S7 on 13) | The permission flow above; the FORGET chip and a fresh scan as the documented workaround for a failed cross-launch reconnect; the "cancel the pairing code" line shown on 12 and 13. Expect Samsung's battery management to kill the process on a long idle: the foreground service is the defence, and SETUP should link the dontkillmyapp steps once |
| 13 | 33 | Early builds could not disconnect and left the device listed after power-off (Issue Tracker 254101893, 246412046; TQ2A reportedly fixed it). Executor-based `registerDeviceCallback`, which `MidiInput` already uses | Report `Build.DISPLAY` in the MIDI report so a stuck-device complaint names its build. No device in hand |
| 14 | 34 | Foreground service types enforced for targetSdk 34+; starting one from the background is refused | `connectedDevice` declared and typed at `startForeground`; started only from the scanner tap |
| 15 (the Pixel 8) | 35 | No BLE MIDI changes found; the 13 and 14 fixes are in. `connectedDevice` has no 15 timeout (the timeouts are for `dataSync` and `mediaProcessing`) | The reference path. Everything in section 5 is measured here first |
| 16, 17 | 36, 37 | No MIDI API changes found in either (unverified; re-read the API diff for `android.media.midi` on each release) | Nothing |

Two things no version fixes: the phone cannot set the BLE connection interval (the peripheral asks for it), and the address randomizes, so identity is name plus service UUID or nothing.

## 4. Threading and timing, stated once

- BLE bytes arrive on the same binder thread as USB bytes, in bursts of one connection interval, through the same `MidiReceiver`. `MidiWire` parses them per port exactly as today; nothing new allocates or blocks there.
- The timestamp now travels with each event to the listener. Whether the system's `BluetoothMidiService` maps the packet's 13-bit millisecond timestamps onto the nanosecond value it hands `onSend`, or stamps receive time, is unverified. The monitor's arrival-delta column answers it on the first held chord: deltas near one connection interval mean receive time, sub-millisecond spreads inside a burst mean the packet timestamps survived.
- Cited latency for the player's expectations, all old: 7.5 ms at the minimum interval with 1.8 ms SD (NIME 2019); 10 to 20 ms BLE against about 3 ms USB (Adafruit's rule of thumb); jitter up to one interval. Steady delay is forgiven, jitter is not; a BLE note still plays immediately on the main thread like a USB one, and a per-endpoint input offset belongs to the CLOCK job, not this one.

## 5. How we would know it works

**JVM, in CI (`./gradlew :shell:test :kit:test`):** `BleLinkTest` (section 2.3), `MidiReportTest` (a fixed endpoint list and monitor ring produce a byte-stable report), `MidiKeysTest`, `MidiPadsTest` and `MidiWireTest` unchanged and still green. `:app:assembleDebug` compiles the glue; `:app` has no host test source set, so that is the compiler's proof and the phone's.

**Pixel 8, Android 15 (the acceptance pass; each row goes in `docs/BENCH.md` as a new section "D · The phone and a Bluetooth keyboard", written as questions with a `→` line, in that file's own format):**

| Row | Pass looks like |
|---|---|
| D1 scan | Keyboard in pairing mode appears in the sheet within 5 s with its name and an RSSI that moves when the keyboard moves |
| D2 play on KEYS | The line reads `MIDI · <NAME>`; keys play at the keyboard's own velocity (a soft key is quiet); sustain holds let-go notes; the controller's panic silences all |
| D3 play on PLAY | Same keyboard, same session, the MPC map: note 36 fires A02, 38 A06, 42 A03; the IN ORDER chip walks C1 up the slots |
| D4 screen switch | KEYS to PLAY and back without a rescan; the link state never leaves `CONNECTED` |
| D5 lock cycle | Lock the phone 30 s mid-session, unlock: still `CONNECTED`, the first key plays, no rescan |
| D6 power-off mid-chord | Hold a chord, switch the keyboard off: nothing sticks, the line returns to the invitation, the chip reads `BT · LOST`; power on within 20 s: back to `CONNECTED` without touching the phone |
| D7 give up | Keyboard off for a minute: the chip reads `LOST · TAP TO SEARCH AGAIN`, the service's notification is gone |
| D8 relaunch | Swipe the app from recents, relaunch, open the sheet: the remembered name is offered; CONNECT works |
| D9 the report | SEND MIDI REPORT produces a file naming the keyboard, `TYPE_BLUETOOTH`, 1 in 1 out, the build, and 64 monitor rows with deltas |
| D10 key to sound | **Measured on device, method chosen by whoever holds it** (BENCH.md's own rule): one phone recording both the key's mechanical click and the phone's output is enough for a first number; the row records the device, the keyboard, the method and the spread, not just the mean |

**Galaxy Tab, Android 12:** D1 to D9 again. D8 is expected to fail on first try (Issue Tracker 234561550); FORGET and a rescan must then pass, and the `→` line records which. If D2 connects but plays nothing, the pairing-code line is the first thing to try, and the result goes on the row either way. D10 once, for the USB-versus-BLE comparison on a slower tablet.

**What would make the spec wrong:** if the Pixel's `MidiInput` never sees `onDeviceAdded` after the link reports `CONNECTED`, the ephemeral-device assumption in 2.2 is inverted and the screens' `MidiInput` must stop closing the Bluetooth device on `ON_STOP` (only its ports). That is a ten-line change inside `release`, and D4 and D5 are the rows that catch it.

## 6. Ledger

- **Verified** (grep, `b429ec2`): everything in section 1; no Bluetooth permission, no `openBluetoothDevice`, no `TYPE_BLUETOOTH`, no clipboard use, no LICENSE file anywhere in the tree.
- **Measured on device**: nothing. Every number in section 4 is cited and every row in section 5 is open.
- **Cited**: the foreground-service-types page (Android developers, read 2026-10-22); the BLE scan rules and the five-per-30-s throttle (Punch Through, read 2026-10-22); the NIME 2019 latency figures and the Issue Tracker bug list (2022 to 2023, old; the Pixel on 15 is past them, the Tab on 12 is inside them).
- **Proposed**: the backoff schedule, the 20 s scan window, the 64-row monitor, `connectedDevice` over `mediaPlayback`, every file name marked new, every `Copy` line.
- **Untestable here**: Android 10 and 11 behaviour, the Samsung pairing-prompt interaction, whether `BluetoothMidiService` preserves packet timestamps, key-to-sound latency. Two source fetches were blocked today (`developer.android.com/reference/android/media/midi/MidiManager` and AOSP `MidiService.java`); the one claim resting on memory of the second is flagged in 2.2.
- **License**, once: the repo has no LICENSE file. Nothing in this spec vendors code, so it does not block the work; it will block the first deliverable that does.

## 7. Why it is built this way

1. **The link lives in the process, not in a screen.** KEYS and PLAY each open and close their own `MidiInput` with the lifecycle, and that is right for USB because a USB keyboard is still there when the screen comes back. A Bluetooth keyboard exists only while some client holds it open, so if a screen held it, every lock cycle and every KEYS-to-PLAY hop would mean a rescan, and on the Tab possibly a failed reconnect. One holder, screens unchanged.
2. **The screens' event path does not change.** The whole point of `MidiManager`'s Bluetooth design is that after the open, a BLE keyboard is an ordinary device; `MidiInput`, `MidiKeys` and `MidiPads` already know what to do with one. Adding a BLE-specific path would be a second place for a note to get stuck.
3. **The rules are on the JVM.** The state machine, the backoff, the identity match and the report format are the parts that go wrong quietly and the parts a phone cannot prove in CI. `:shell` tests them; `:app` only wires them, the house split that `MidiKeys` and `PadHit` already follow.
4. **Refusals are words.** Every BLE complaint in the app landscape (not found, found and silent, found and stuck, missing option) is a ten-second read with an in-app scanner, state chips, and a report a user can send. That is why the diagnostics section ships with the feature and not after it.
5. **The risky decision** is `connectedDevice` instead of the reference's `mediaPlayback`. It is the honest type for what the service does, and its prerequisite is a permission the feature already needs. If a future CLOCK deliverable makes SnipSnap play in the background, the service grows a second type then; the manifest attribute takes a `|`.

If the first pass on the Pixel passes D1 to D9, the next roadmap items this unblocks are P0.1 (the timestamp is now kept, so a monitor can show deltas) and P1.5 is closed; P0.7 (the full diagnostics screen) is half done by the SETUP section.

One thing that would have sharpened this: the keyboard's make. A native BLE keyboard, a WIDI-style dongle on a DIN keyboard, and a keyboard that asks for a pairing code each hit a different row in section 3, and the implementing session should write the model into D1's `→` line.
