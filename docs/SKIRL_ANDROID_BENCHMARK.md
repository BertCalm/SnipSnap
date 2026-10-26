# Skirl render measurement on Android

`SkirlRenderBenchmark` is an opt-in Android instrumentation diagnostic. It calls
the production `Skirl.render` API on the selected device and saves a JSON report.
It needs no benchmark library, activity or app picker entry. Ordinary on-device
test runs skip it unless `skirlBenchmark=true` is supplied.

The implementation was prepared in the cloud checkout without an Android SDK,
`adb`, emulator or attached phone. It has **not been Android-built or device-run**
there. The existing JVM render measurements are not Android results.

## Run on a phone

On a machine with JDK 17+, the configured Android SDK/NDK, and an authorized USB
debugging device, first inspect the device list:

```sh
adb devices -l
```

Choose the physical phone's serial in the commands below. Run from the repository
root. The debug app currently has application ID `com.snipsnap.app.brassgrow`, so
it installs beside the normal app. These paths and IDs match `app/build.gradle.kts`
at the Skirl milestone; update them if the app build configuration changes.

```sh
skirl_device=REPLACE_WITH_DEVICE_SERIAL
skirl_source_commit=$(git rev-parse HEAD)
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
adb -s "$skirl_device" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$skirl_device" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$skirl_device" shell am force-stop com.snipsnap.app.brassgrow
adb -s "$skirl_device" shell am instrument -w -r \
  -e class com.snipsnap.app.SkirlRenderBenchmark \
  -e skirlBenchmark true \
  -e skirlSourceCommit "$skirl_source_commit" \
  com.snipsnap.app.brassgrow.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$skirl_device" shell run-as com.snipsnap.app.brassgrow \
  cat files/skirl-render-benchmark.json > /tmp/skirl-phone-render-benchmark.json
python3 -m json.tool /tmp/skirl-phone-render-benchmark.json
```

The instrumentation result must finish with `OK (1 test)`. A skip does not measure
anything; a failed test can leave an older report on the phone. Check the report's
timestamps, source commit and model before accepting it. Use a clean committed
source tree: the supplied commit identifies `HEAD`, not uncommitted changes.
Keep the report with a note that the selected target was a physical phone, its
power/charging state, and whether other work was running. Device serials are not
included in the report.

You can also use the existing Gradle-connected runner after selecting one device:

```sh
ANDROID_SERIAL="$skirl_device" ./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.SkirlRenderBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.skirlBenchmark=true \
  -Pandroid.testInstrumentationRunnerArguments.skirlSourceCommit="$skirl_source_commit"
```

## What the numbers mean

For each voice, the report covers the exact C3 default, C2/C6 pitch changes,
EDGE/PLATE edits, LEAN/BRAKE/COLLAPSE edits, HOLD `.5`, and HOLD `1`. Each workload
records its first observed render, then three discarded warmups and seven measured
renders. Every measured sample carries its full replay recipe, model, frame count,
peak, declared class and SHA-256 of the float samples in little-endian byte order.
The render call ends before finite checks, fingerprinting, recipe serialization
or file writing, so the timer measures production rendering rather than reporting.

The report records medians and ranges against the proposed 50 ms target; it does
not fail the test for missing that target. No per-engine diagnostic internals are
exposed to Android solely for this harness.

Production caches remain enabled. The first observation is **not a cache-cleared
measurement**. Edit workloads cycle through a bounded set, so later repeats may
reuse cached state. Pitch and acoustic edits test whether work independent of
mechanics can be reused. Mechanics edits test different physical inputs, but
their individual records must be inspected rather than labeled universally cold.
The earlier JVM benchmark explicitly cleared the mechanics cache after warming
the JVM; that is a different experiment.

This is a debug-build, fixed-order diagnostic with short warmup and a small sample
count. It measures neither cold-process startup, Compose knob handling, disk
export, playback nor end-to-end touch latency. An emulator run is useful for
compilation and smoke checks but cannot establish phone performance. For a final
latency claim, repeat on the target phone after cooling, check recorded thermal
status and retain a separate optimized release measurement. No release benchmark
configuration or result is supplied by this diagnostic.
