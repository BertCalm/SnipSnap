# Crease compact C4 study: handoff

Prepared 2026-11-07 UTC. Implementation and publication are complete. The next
step is the owner's listening and response check on the compact phone build.

## Start here

- Repository: [BertCalm/SnipSnap](https://github.com/BertCalm/SnipSnap).
- Default branch: `claude/mobile-mpc-drum-sampler-t58x74`.
- [Implementation PR #932](https://github.com/BertCalm/SnipSnap/pull/932) **merged**
  at `2026-11-07T02:45:37Z`; all eight reported CI jobs/checks are green,
  including Android assembly, native tests, JVM shards/aggregate and emulator tests.
- Merge commit: `c1e8cc0257c1792715201f9c9d5d891468179f80`.
- Implementation snapshot: `5faa5c8811789ff5256365310d7253a654c6baf0`.
- Default-branch head checked for this handoff:
  `ae4bd8d114f8e046cbcdb2f82aa1323324eacdfd`. Crease study/research files and
  the native source hashes still match the released implementation.

Start new development from the current default branch. The former feature
branch, `claude/crease-shared-release-cache`, is already merged. For an exact
rebuild of the published study, use the pinned implementation snapshot below.

Read [AGENTS.md](../../AGENTS.md), [Luthier](../../.claude/skills/luthier/SKILL.md)
for further DSP work, and [Steward](../../.claude/skills/steward/SKILL.md) for PR/CI work.
The canonical Luthier bundle is in the repository; no old chat attachment is needed.

## What is finished

The separately installed Crease Study plays Reed Mark C4, model 3, at one
rendered touch level, velocity .8. The gesture is **press the onset, hold its
motion, release anywhere, follow the unfolding to silence**. Fade only and
Unfold share the existing volume fade; Unfold also follows the recorded
recovery of the circuit.

The accepted full-tail cache stored 309 endings. The compact representation
stores the same held PCM plus **32 shared PCM16 waveforms and Float32 mixing
weights**. Integer carrier-phase alignment makes those endings shareable.
The native and browser readers interpolate the required basis samples as
they play. The native callback allocates/frees nothing and never expands
all 309 tails, decompresses data or runs the Kotlin circuit.

| Measurement | Result |
| --- | ---: |
| Previous full-tail PCM | 33,547,698 bytes / 31.994 MiB |
| Compact PCM | 4,138,366 bytes |
| Accounted compact resident cache | **4,188,582 bytes / 3.995 MiB** |
| Reduction against previous PCM | about **87.5%** |
| Compressed audio payload | 2,122,394 bytes, previously 1,692,344 |
| Circuit comparisons | **3,251/3,251 pass** |

The resident account includes held/basis PCM, weights, coordinate tables and
an 8 KiB allowance covering the fixed native reader/voices. Whole-app memory
also includes UI, runtime and stream allocations. A same-code optimized
Android 10 emulator pair reduced native heap allocated from 51,059 KiB to
22,129 KiB. That is one emulator measurement, not physical-phone timing.

The held PCM, patch, gain and gesture are preserved. This remains a one-note
study. A keyboard range, multiple velocities and a normal SnipSnap instrument
landing remain future work. The existing MPC instrument exports retain their
previous behavior; the shared-basis reader is a SnipSnap/browser transport.

## Published artifacts

- [Audition and Android download](https://snipsnap-crease-audition.bertcalm.chatgpt.site/#phone-study).
- [Build and validation record](https://snipsnap-crease-audition.bertcalm.chatgpt.site/playable/phone-build.json).
- [Source patch, build helper and receipts](https://snipsnap-crease-audition.bertcalm.chatgpt.site/playable/downloads/crease-shared-source-5faa5c881.zip).
- [Full measurements, gzip](https://snipsnap-crease-audition.bertcalm.chatgpt.site/playable/report.json.gz).

The Site remains **owner-private**. Open downloads in the owner's authenticated
browser. The published APK supports Android 10+ on arm64, has application ID
`com.snipsnap.app.creasestudy`, and uses the same debug signing certificate as
the accepted phone study so it can update that installation.

| Artifact identity | Value |
| --- | --- |
| APK size | 9,683,532 bytes |
| APK SHA-256 | `7e2bfef72c27e9b6402bdabff2236f37d89373bd7cfa37863195ab292636b9ac` |
| Application source snapshot | `3da84771fce963fcca69e95541ad7d966a23f6b9` |
| Cache manifest SHA-256 | `82ef3655332d7f601ec474626ad72915a9aaedf984328b3e48916093772459cf` |
| Decoded compact PCM SHA-256 | `a7338538d51d9b1394b48a51665a5cd54e5128be68a3ff0131e0bfca48898387` |
| Source ZIP SHA-256 | `dbf59594358e0eab51a8233e0963c148edde890a232c2c3393b07cad1746dca2` |
| Exact circuit capture source | `907a6638699363afc2179df594d8a293ef822b86` |

The APK application snapshot precedes the final browser compatibility change
and validation records. Its actual bundled manifest/cache and signing identity
were verified. Live private downloads of both the APK and final source ZIP
were hash-checked, and the deployed AudioWorklet produced audio and ended on release.

## Validation and acceptance ledger

The rank and quantized data were locked in commit `259778f91` before capturing
fresh ground truth. Of the 3,251 cases, 1,628 are known development cases and
1,623 are fresh positions. Lower candidate ranks failed; 32 was the first
passing choice. No rank, gain or error limit was retuned on fresh cases.

| Check | Observed result |
| --- | --- |
| Maximum normalized RMS error against actual Kotlin | .009128788; limit .031623 |
| Maximum absolute peak error against actual Kotlin | .024206594; limit .025 |
| Maximum harmonic TV | .000249064; limit .02 |
| Key-up step / finite / clipping gates | pass under original limits |
| JS and C++ versus quantized-basis reconstruction oracle | zero Float32 sample error at all 3,251 positions |
| Native versus JS at 48 kHz | 24 cases, both ending modes, pass |
| Native host suite | six executables pass; callback allocations/frees zero |
| Kotlin cache decoder | six tests pass |
| Build admission | eight tests pass normally and under Python `-O` |
| Removed-guard checks | manifest, coefficient and 8 MiB guards each fail the named test when removed alone |
| Study device tests | four unchanged tests pass on Android 10 x86_64 software emulator |
| Optimized APK smoke | startup, actual touch/hold/release, ending choice and background/resume pass |
| Local full JVM gate | **8,763 tests**, nine modules, zero failures/errors/skips; exit 0 |
| PR CI | all reported checks green on implementation head `5faa5c881` |

The local JVM gate took 1 h 36 m 50 s. Android/native checks are separate from
that nine-module gate. Browser behavior passed at 1440 px and 390 px, including
touch/keyboard, sustain wrapping, cleanup, corrupt-cache refusal/retry and
reference downloads. A compatibility regression confirms that a legacy
full-tail manifest's extra metadata cannot select the shared reader: selection
follows the declared format tag.

**Heard:** the owner accepted the earlier voice and reported that the previous
full-cache phone study works. Acceptance and physical-device timing of this
compact build have not been reported. Numerical checks and emulator operation
do not establish musical preference or speaker latency.

## Where the implementation lives

| Area | Entry points |
| --- | --- |
| Native playback / JNI | [CreaseStudyReader.h](../../app/src/main/cpp/crease/CreaseStudyReader.h), [CreaseStudyJni.cpp](../../app/src/main/cpp/crease/CreaseStudyJni.cpp), `CreaseStudyEngine.*` beside them |
| Android loader / gesture / lifecycle | [CreaseStudyCache.kt](../../app/src/creaseStudy/kotlin/com/snipsnap/app/CreaseStudyCache.kt), [CreaseStudyActivity.kt](../../app/src/creaseStudy/kotlin/com/snipsnap/app/CreaseStudyActivity.kt), `NativeCreaseStudy.kt` beside them |
| Canonical browser reader / decoder / worklet | [scripts/crease-release/player](../../scripts/crease-release/player) |
| Candidate fitting / fresh capture / export | [shared_cache.py](../../scripts/crease-release/shared_cache.py), [CreaseSharedHoldouts.java](../../scripts/crease-release/CreaseSharedHoldouts.java), [shared_export.py](../../scripts/crease-release/shared_export.py) |
| Admission / parity / delivery | `prepare_phone.py`, `verify_phone.py`, `verify-reader.mjs`, `native_fixtures.py`, `verify-native.mjs`, `package_playable.py` under [scripts/crease-release](../../scripts/crease-release) |
| Protocol and results | [Shared protocol](../../scripts/crease-release/shared-protocol.md), [design/result notes](../superpowers/specs/2026-11-06-crease-shared-cache.md), [all case results](../superpowers/specs/2026-11-06-crease-shared-cache-results.json), [candidate lock](../superpowers/specs/2026-11-06-crease-shared-cache-candidate.json), [fresh positions](../superpowers/specs/2026-11-06-crease-shared-cache-positions.json) |

The wire format is `crease-shared-pcm-v1`. Its lossless container holds held
PCM followed by frame-major basis PCM. The manifest supplies padded basis
dimensions, integer offsets, row-major Float32 weights and integrity hashes.
Legacy `crease-playable-pcm-v1` remains supported. Native creation uses a
separate `createShared` JNI entry point. Loader validation runs off the audio thread.

Keep the accepted common gain `1.2239602314709794`, carrier alignment,
neighbor interpolation, cubic reads, 221-source-frame blend, Float fade,
live gain .12 and 5 ms retirement intact when comparing representations.

## Rebuild the released APK without recapturing the circuit

Download the signed APK and final source ZIP from the private audition, then
extract the ZIP. It includes `prepare-study-from-apk.py`, the exact source
patch and verification receipts. The helper recovers the immutable audio
assets from the APK and the admission records from the repository result file.

For an exact release-source checkout, create a detached worktree at
`5faa5c8811789ff5256365310d7253a654c6baf0`. Use JDK 21, Android SDK 35,
NDK `27.2.12479018` and CMake `3.22.1`:

```sh
python3 /path/to/source-bundle/prepare-study-from-apk.py \
  --repo /path/to/SnipSnap-release \
  --apk /path/to/snip-snap-crease-study.apk \
  --output /path/to/new-study-input

cd /path/to/SnipSnap-release
./gradlew --no-daemon :app:assembleCreaseStudy \
  -PcreaseStudyCache=/path/to/new-study-input
```

The output directory must be new. Default study ABI is arm64; add
`-PcreaseStudyAbi=x86_64` for an emulator. For Compose instrumentation, also
use `-PcreaseStudyDeviceTests=true` and
`:app:connectedCreaseStudyAndroidTest`. Instrumentation uses the unshrunk
test shape; check the optimized listening APK separately.

Admission binds the whole manifest, coefficient identity and native source
hashes. A changed native reader requires current verification receipts;
do not edit the receipts to bypass refusal. Ordinary builds require no study
cache. The study-only packaging excludes unused factory vocal resources.

## Retained cloud material

These paths are environment-local; the raw research captures are ignored by Git:

- Source worktree: `/workspace/SnipSnap-crease-playable`.
- Separate Android build worktree: `/workspace/SnipSnap-android-check`.
- Site source: `/workspace/sites/crease-audition`.
- Accepted baseline: `testkit/crease-phone-release-r2/` in the source worktree.
- Compact experiment: `testkit/crease-shared-cache-r1/` in that worktree,
  including the locked basis, fresh captures, fixtures and parity receipts.
- Signed APK copy: `/workspace/scratch/crease-shared-arm64-final.apk`.
- Detailed local receipts: `/workspace/scratch/crease-shared-*-proof.json`,
  `/workspace/scratch/crease-shared-final-publication-receipt.json` and build logs.

Preserve both research directories. Generation intentionally refuses an
existing output/fresh-validation directory. Exact-reference recapture also
requires JAR hashes matching the accepted capture receipt; a later Gradle
build can replace those JARs. The APK-based rebuild avoids recapture entirely.

All owned emulator, full-test and preview-server processes were stopped at the
end of the build session. Ephemeral credentials are not part of the handoff.

## Publication details and operational traps

- Existing Site ID: `appgprj_6ad25eb5f538819195e42001aa15c2fd`.
- Published Site-source commit: `30af1eec27c5b3bec3fe5ad70347d373991a2907`.
- Read the Sites skill and open this existing project before future edits;
  preserve its current audience and obtain fresh credentials through the tools.
- The archive limit is **268,435,456 uncompressed TAR bytes**, including headers
  and padding. The final archive is 268,154,880 bytes: **280,576 bytes headroom**.
  A sum of file sizes is insufficient. Normalized TAR metadata was needed.
- Earlier recordings, instruments and the accepted full-cache evidence remain
  available. One older raw JSON measurement is losslessly gzipped; its decoded
  hash is checked. Preserve those artifacts when publishing later studies.
- Generate decoder/worklet bundles from canonical sources. Private hosting
  requires the page to fetch the self-contained worklet and load its Blob URL;
  worklet-internal HTTP imports may lose authorization. Version module URLs
  and revalidate manifests/payloads for returning visitors.
- The build once retained deleted ZIP entry space, producing a ~22 MB APK
  after resource exclusions. Removing only the generated APK and rebuilding
  produced the verified 9,683,532-byte file. Check the actual published bytes.
- Keep Android builds in a separate worktree from the full JVM gate. Avoid
  overlapping an idle multi-GB Kotlin compiler, emulator and large fixtures;
  the Node fixture verifier now streams one case at a time.
- One early device run hit a confirmed System UI ANR dialog, causing keyboard
  injection refusal. Recovering the emulator and rerunning all four unchanged
  tests passed. Preserve assertions and diagnose focus/host health first.

## Next work, in order

1. Install/update the compact study and compare Fade only/Unfold with the
   fixed exact references. Try very short taps, long holds, rapid retriggers,
   Stop, app background/resume and the owner's usual audio route. Record the
   actual APK hash, device/Android version, route and what the owner heard.
2. Record acceptance separately from measured timing. If response is a problem,
   measure callback deadlines/underruns and end-to-end latency on the physical
   device before changing the voice or error limits.
3. After the compact C4 result is accepted, define a small pitch-range experiment
   with an explicit aggregate memory/CPU budget. Freeze its candidate before
   fresh captures, preserve every existing case and add genuinely unseen ones.
4. Use that evidence to plan normal SnipSnap keyboard/instrument integration.
   Treat MPC behavior as its own playback/export design question.

No new pitch-range or main-app implementation was started in the compact-cache
experiment. The repository, published study and verification evidence are ready
for the next session.
