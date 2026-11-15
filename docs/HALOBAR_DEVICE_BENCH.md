# HALOBAR Android render diagnostic

The [final export build](HALOBAR_FINISH.md) retains every HALOBAR strike in
MPC keygroups and version-5 phone sidecars. The historical build-time evidence
below retains its original measurements and execution scope.

The owner subsequently confirmed **"Everything passes"** on 2026-11-14.
The [acceptance ledger](HALOBAR_ACCEPTANCE.md) records the listening sign-off
and owner-reported hardware pass. The build-time execution record and remaining
evidence table below predate that confirmation; production-export integration
and measured latency retain their separate technical status.

This opt-in diagnostic measures the production renderer on the selected Android
runtime. It does not play audio. The six approved voice banks, audio model,
recipes, factory registry and MPC export behavior are unchanged by this work.

The previous cache follow-up was merged in [PR #1072](https://github.com/BertCalm/SnipSnap/pull/1072).
Its desktop measurements still miss the fresh-render target. A device measurement
is the next evidence needed before choosing a larger optimization. Passing this
diagnostic means its renders and fresh replays agree; it does not mean the
50 ms target or the owner's instrument gates pass.

## What it measures

For each of vibraphone, marimba, xylophone, glockenspiel, crotales and tubular
bells, the test records eight cases at the default note and full velocity:

| Case | Cache treatment |
|---|---|
| FRESH | Default recipe; clear all four audio caches before every call. |
| LONG | HOLD and RETURN at 1; clear before every call. |
| DRY | SPACE at 0; clear before every call. |
| RECALL | Seed the complete default render, then recall it. |
| RATE | Seed defaults, then edit across six actual delay divisions with unique raw values. |
| RETURN | Seed defaults, then change return amount. |
| MOTION | Seed defaults, then change motion. Vibraphone must rebuild its source motor. |
| SPACE | Seed defaults, then change the positive wet mix. |

Each case has three untimed warmups and, by default, three measured calls.
Only `Halobar.render` is inside the monotonic timer. Cache clearing, assertions,
hashing, file access and independent fresh replays are outside it. Immutable
halo spectra and class loading/JIT can be warm: FRESH means fresh audio, not a
cold process. Edit measurements retain caches throughout their sequence, then
verify the captured outputs against separately cleared renders on that device
using little-endian Float32 SHA-256. They do not compare Android math to JVM
goldens. Reference comparisons run after the sequence so they cannot destroy
the reuse being measured.

Reports contain every measurement and recipe, median and maximum, build/device
identity, and a descriptive `medianWithin50Ms` field. Small samples do not
support a p95 claim. Debug-build results exclude UI debounce, preview transfer,
audio-output latency and export. They are not release-build timings or sampled
peak-memory measurements. An emulator is functional evidence only.

## Run the supplied APK pair

Use Python 3.9 or newer and Android platform-tools. Select a device deliberately
with `adb devices -l`; unlock it and authorize USB debugging. Run from the
downloaded diagnostic folder, replacing `DEVICE_SERIAL`:

```sh
python3 run_halobar_device_bench.py \
  --serial DEVICE_SERIAL \
  --app-apk HALOBAR-DeviceBench-debug.apk \
  --test-apk HALOBAR-DeviceBench-androidTest.apk \
  --install \
  --output phone-render.json
```

`--install` updates the existing `com.snipsnap.app.brassgrow` development app
and its test package with `adb install -r`, retaining app data. Without that
flag, both supplied APKs must already be installed. Before starting, the runner
pulls the installed APKs and checks their SHA-256 against the supplied files.
It preserves existing report files, requires a successful instrumentation
completion, and retrieves a uniquely identified report with all 48 cases.
A failed test cannot become a pass merely because `adb` exits zero. Use
`--adb /path/to/adb` if it is not on PATH; `--runs 1` through `--runs 9` controls
sample count. Keep the device idle during measurement. The full diagnostic can
take several minutes, especially on a slower phone.

The output JSON carries the APK fingerprints and device report. Its adjacent
`.instrumentation.log` preserves runner output. Neither file records physical
playback or listening approval. The runner rejects a likely emulator unless
`--allow-emulator` is supplied, and labels those results as functional evidence.

## Build and run from source

With an SDK and the repository's pinned JDK/NDK available:

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class com.snipsnap.app.bench.HalobarRenderBenchmark \
  -e halobarProfile true -e halobarRuns 3 -e halobarRunId manual \
  com.snipsnap.app.brassgrow.test/androidx.test.runner.AndroidJUnitRunner
adb -s DEVICE_SERIAL exec-out run-as com.snipsnap.app.brassgrow \
  cat files/benchmarks/halobar-manual.json > phone-render.json
```

The Python runner is preferred because it also checks installed bytes and
report identity. Without `halobarProfile=true`, the test opts out of routine
Compose runs. It uses the existing unshrunk debug `clearCache$synth` test hook
through reflection; no production cache-management API was added.

## Delivery validation

App and test-APK assembly passed with Gradle exit code zero. Both APKs have
verified v2 signatures from the same debug certificate; their package IDs,
instrumentation target and packaged diagnostic/cache hook were checked.
The manifest in the delivered folder identifies the source and APK hashes.
The host runner passed six isolated simulated-adb scenarios. The external
desktop stage-timed copy matched 24 published Float32 fixture cases.

No Android device was connected, so the instrumentation was compiled and
packaged but not executed. The full JVM suite was not rerun for this diagnostic
round; production sources and build settings are unchanged by the patch.

## Remaining owner and hardware evidence

| Gate | Evidence still required |
|---|---|
| Fresh-render playability | Actual phone timings, followed by any further optimization the result warrants. The desktop 50 ms target remains unmet. |
| Android playback | Play the saved keygroups on a physical phone, including velocity, take cycling, chords and key-up release. Render timing alone cannot close this. |
| MPC velocity × round robin | Run the existing velocity-only, cycle-only and combined diagnostic programs on the target MPC/firmware. Production combined keygroup mode remains disabled. |
| Performance examples | Owner verdict on the premade kit, chords, releases, rolls and risers. Voice-bank approval is already recorded separately. |

The [private performance review](https://halobar-native-audition.bertcalm.chatgpt.site/tubular-bells/performance/review/)
and [MPC diagnostic ZIP](https://halobar-native-audition.bertcalm.chatgpt.site/tubular-bells/performance/HALOBAR-MPC-Velocity-RR-Bench.zip)
are already published. Record the device model, MPC model/firmware and the
actual observed result. A first-take export fallback is not a round-robin pass.
