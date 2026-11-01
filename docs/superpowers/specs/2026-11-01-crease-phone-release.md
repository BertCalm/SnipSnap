# Crease: the first native phone release study

Date: 2026-11-01. Luthier REVIEW → one implementation direction. Baseline
`9c10026772232018674a233d4e5108876631faf7`; clean circuit capture
`907a6638699363afc2179df594d8a293ef822b86`.

The characteristic gesture is **press the actual onset → hold its motion →
release whenever desired → follow the cached unfolding → silence**.

Josh accepted the previous browser study's sound with “Works for me, proceed
as recommended.” Preserve Reed Mark C4, model 3, saved velocity .8, circuit,
macros, common gain 1.2239602314709794 and the accepted reader/fade. This is
one sound and one key, not a new engine or a full keygroup implementation.

## Result and evidence

| Cache | Release comparisons | PCM | Largest peak error | Outcome |
| --- | ---: | ---: | ---: | --- |
| Earlier N32 | 198 / 201 | 18.72 MiB | .050213 | Failed |
| First refinement: 2 ms to 280 ms | 717 / 721 | 31.49 MiB | .028739 | Failed |
| Second refinement: 2 ms to 64 ms, 4 ms to 512 ms | 1,628 / 1,628 | 31.9936 MiB | .022191 | Passed |

Every original limit remains: peak error .025 FS, normalized RMS .031623,
harmonic TV .02, key-up error .001, PCM 32 MiB and compressed delivery 12 MiB.
The first new layout fixed the original three failures but missed four fresh
positions around 290–293 ms. The second layout was fixed before collecting
907 new distinct release positions, while retaining all 721 earlier cases.
Those 907 are independent of the second layout's development. Future tweaks
must use another unseen set. See the preregistered
[phone protocol](../../../scripts/crease-release/phone-protocol.md).

The actual compiled Kotlin circuit supplies all audio. Public onset error
and complete-state fork error are zero; 82 repeated captures are bit-exact.
The cache is 309 tails / 33,547,698 PCM bytes / 1,692,344 compressed bytes.
No original circuit equation, voice gain or error threshold changed.

Python versus streaming JS: all 1,628 positions pass, largest sample error
1.1641532182693481e-10. The actual native reader matches the same fixture to
1.16415e-10, including 24 additional 48 kHz streaming references in both
ending modes. Host native/JNI tests check rapid retriggers, coalesced taps,
stale key-up tokens, route closure, Stop, invalid layouts, pending exceptions
and failed JNI reads. Callback allocation/free interception observes zero.
These are host observations, not measurements of a phone's audio driver.

Five Android host unit tests cover predictor orders 0–2, signed wraparound,
hash corruption, truncation, expansion limits, malformed dimensions, padding,
paths and refusal of failed experiments. Four device tests compile for real
touch, keyboard ownership, accessibility preview and background/resume. They
have not run on hardware or an emulator here. Normal device tests also compile.
Repository-wide JVM verification is recorded separately when it completes.

## Phone landing

`creaseStudy` builds a separate `com.snipsnap.app.creasestudy` APK, Android 10+
and arm64 by default. It bundles the verified cache and launches only the
study activity. No import or kit setup is required. It has no capture/MIDI
services, camera/microphone grants or main sampler launcher. Standard builds,
existing shelves and MPC exports keep their contracts. R8 and resource
shrinking apply only to this study; its JNI names are explicitly retained.

Signal path:

```
bundled manifest + gzip cache
  → bounded decode/hash checks on one worker
  → one immutable native PCM16 bank
  → held reader / phase-aligned neighbouring release tails
  → 221-source-frame blend + Float 1.2 s fade
  → .12 live gain → stereo Oboe output
```

The decoder reconstructs byte planes and predictor sums directly into a
ShortArray, verifies the PCM hash, then transfers it once to native memory.
The Java/native transfer temporarily holds two PCM copies; steady playback
owns one 32 MiB bank. There is no per-voice PCM copy, solver, allocation,
blocking I/O or decoder on the callback. The output opens through SnipSnap's
existing exclusive/shared Oboe policy. The study's isolated transport does
not alter the pad engine's bank or command contracts.

An atomic latest gesture replaces a queue for this single-key study. Each
press gets a token; only that token can release it. Down/up between callbacks
releases from onset. Stop and interruptions cannot be refused by a full
queue. Eight fixed voice slots retain the browser study's replacement fade
and maximum voice policy. Starts superseded before a callback are coalesced.

Focus/window loss, pause, stop, teardown and stream error clear the gesture.
Stream start/close, decode and native destruction share one worker. Lifecycle
generations prevent a late load from reviving a stopped activity. Resuming
never restores a held gesture. Accessibility click is a finite 600 ms hold.

## Reproduce

With a full JDK and the repository's Python/NumPy/SciPy/Matplotlib, ffmpeg,
Node and Android SDK/NDK tools available:

```sh
# Commit first: the capture requires a clean named source revision.
./gradlew --no-daemon :synth:jar
python3 scripts/crease-release/playable.py --adaptive --skip-build --output testkit/crease-phone-release-r2
python3 scripts/crease-release/verify_playable.py testkit/crease-phone-release-r2
python3 scripts/crease-release/package_playable.py testkit/crease-phone-release-r2
python3 scripts/crease-release/native_fixtures.py testkit/crease-phone-release-r2
node scripts/crease-release/verify-native.mjs testkit/crease-phone-release-r2
cmake -S app/src/main/cpp/test -B build/native-tests
cmake --build build/native-tests
ctest --test-dir build/native-tests --output-on-failure
python3 scripts/crease-release/verify_phone.py testkit/crease-phone-release-r2
./gradlew --no-daemon :app:testCreaseStudyUnitTest :app:assembleCreaseStudy -PcreaseStudyCache="$PWD/testkit/crease-phone-release-r2"
```

`prepare_phone.py` refuses failed candidates, a receipt for a different cache,
or native sources changed after verification. It checks actual source hashes;
missing/stale receipts cannot silently enable the study. Generated audio stays
outside Git. Study Kotlin/unit tests compile in CI without the external audio
input; assembling the APK deliberately requires it.

To compile/run the dedicated device suite, select
`-PsnipsnapTestBuildType=creaseStudy`. Run only
`com.snipsnap.app.CreaseStudyActivityTest` in that variant; the normal sampler
activity is intentionally absent. An x86_64 emulator build additionally uses
`-PcreaseStudyAbi=x86_64`. This session supplies the arm64 listening APK.

## Disclosed limits and next decision

The accepted sound and the refined cache need the owner's phone listening
pass. Test short taps, long holds, repeated presses, ending choice, background
and headphones. Record phone model, Android version and audio route before
claiming device latency or output quality. No MPC release trigger was added.

This one C4 cache nearly fills the PCM budget. Do not multiply it across a
keyboard or velocity bank. A successful phone pass is the prerequisite for
measuring a smaller representation and a bounded multi-key budget.

## Why it is built this way

1. Captures describe the same accepted circuit; added coverage fixes waveform
   error without changing the musical character to improve a score.
2. The second unseen set tests the interval rule beyond the failures that
   motivated it, and the failed results remain visible.
3. One bundled C4 avoids a premature import/schema promise and makes a native
   gesture installable now, with a distinct identity protecting existing kits.
4. Shared PCM16 and fixed voices bound live work and memory. The almost-full
   32 MiB bank makes broader instrument cost an explicit next question.
5. Token ownership and lifecycle generations prevent late lifts or loads
   from affecting a newer gesture, while Stop remains independent of queues.
