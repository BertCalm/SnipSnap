#!/usr/bin/env bash
# M0 exit test — browse kits, tap pads, hear WAVs, flip schemes.
# Run against a booted emulator or a connected phone.
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
OUT="${1:-/tmp/m0}"
mkdir -p "$OUT"

echo "== install =="
# Gradle needs Java, not Node — no fnm/nvm switch here. (The brief's draft
# of this script had `eval "$(fnm env)" && fnm use 20` on this line; under
# `set -euo pipefail` that kills the run on any machine without fnm
# installed, for a reason unrelated to the app. Removed.)
./gradlew :app:testDebugUnitTest :app:installDebug

echo "== launch =="
adb logcat -c
adb shell am force-stop com.snipsnap.app
adb shell am start -n com.snipsnap.app/.MainActivity

# The factory kit is named, not guessed. An earlier draft took the first
# folder alphabetically — which the script's own manual step 2 breaks the
# moment you create a tape sorting before it.
SEED="SNIPSNAP KIT 01"

# Poll rather than sleep. Rendering sixteen pads measured ~24s on an
# emulator (cold ART interpreting float DSP), so a fixed `sleep 10` failed
# a genuine first run and wasted time on every other.
echo "== waiting for the factory kit =="
for _ in $(seq 1 150); do
  n=$(adb shell "run-as com.snipsnap.app ls \"files/kits/$SEED\" 2>/dev/null | wc -l" 2>/dev/null | tr -d '\r')
  [ "${n:-0}" -ge 17 ] && break
  sleep 1
done

echo "== the shelf has a tape =="
adb shell run-as com.snipsnap.app ls files/kits
adb exec-out screencap -p > "$OUT/1-shelf.png"

echo "== the kit folder is real =="
adb shell "run-as com.snipsnap.app ls \"files/kits/$SEED\"" | tr -d '\r' | tee "$OUT/kit-listing.txt"
grep -q 'kit.json' "$OUT/kit-listing.txt" || { echo "FAIL: no kit.json"; exit 1; }
test "$(grep -c '\.wav$' "$OUT/kit-listing.txt")" -eq 16 || { echo "FAIL: expected 16 WAVs"; exit 1; }

echo "== filenames are ASCII =="
# BSD grep (macOS) has no -P, and would exit 2 on it — with `&&` that
# failure would short-circuit and let a bad filename through silently.
if LC_ALL=C grep -q '[^ -~]' "$OUT/kit-listing.txt"; then
  echo "FAIL: non-ASCII filename in the kit folder"; exit 1
fi

echo "== pad log (report only — no pad has been opened yet, so this can read 0) =="
adb logcat -d -s SnipSnapPad | tee "$OUT/pad.log"
grep -c 'loaded (sample' "$OUT/pad.log" || true

cat <<'CHECKS'

Automated checks passed. Screenshots and logs are in the output directory.

Remaining checks a script cannot make — do these on the device:

  1. Tap the kit, then tap a pad.
     You HEAR it, and this prints a non-zero stream id:
       adb logcat -d -s SnipSnapPad | grep 'hit ->'

  2. NEW BLANK TAPE -> type a name -> ROLL IT.
     The tape appears on the shelf, and:
       adb shell run-as com.snipsnap.app ls files/kits
     Try "BAD/NAME" first: it must be refused inline, not create a folder.

  3. Open a second kit, tap its pads: you hear THAT kit, not the first one.

  4. Menu -> the gear -> pick each of the six schemes.
     Chrome recolours; the LCD stays dark in all six.

  5. Force-stop and relaunch: the chosen scheme is still set, and the
     shelf still has every tape (no re-seed).
CHECKS
