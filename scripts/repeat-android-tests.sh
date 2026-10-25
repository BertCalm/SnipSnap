#!/bin/sh
# EXPERIMENT (not for merge): install the debug app and its tests once, then run
# the named instrumentation tests ROUNDS times, keeping each round's output and
# logcat under app/build/repeat/. Exits non-zero if any round failed or crashed.
set -u
TESTS="${REPEAT_TESTS:?set REPEAT_TESTS to Class#method,Class#method}"
ROUNDS="${REPEAT_ROUNDS:-12}"
OUT=app/build/repeat
mkdir -p "$OUT"
./gradlew --no-daemon :app:installDebug :app:installDebugAndroidTest || exit 2
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
bad=0
i=1
while [ "$i" -le "$ROUNDS" ]; do
  adb logcat -c
  timeout 300 adb shell am instrument -w -r -e class "$TESTS" \
    com.snipsnap.app.brassgrow.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/round-$i.txt" 2>&1
  adb logcat -d > "$OUT/round-$i-logcat.txt" 2>&1
  grep -A12 'SnipSnapThreadCheck' "$OUT/round-$i-logcat.txt" | head -40; grep -o 'SnipSnapResume.*' "$OUT/round-$i-logcat.txt" | sort | uniq -c
  if grep -q -E 'Process crashed|FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg' "$OUT/round-$i.txt"; then
    bad=$((bad + 1)); echo "round $i: FAILED"; grep -m3 -E 'shortMsg|Exception|Error' "$OUT/round-$i.txt"
  else
    echo "round $i: ok ($(grep -c 'INSTRUMENTATION_STATUS_CODE: 0' "$OUT/round-$i.txt") passed)"
  fi
  i=$((i + 1))
done
echo "repeat: $bad of $ROUNDS rounds failed"
[ "$bad" -eq 0 ]
