# OBLITERATE editable board

OBLITERATE prints **octave room → octave fuzz → captured fragments → stereo chop** onto a pad. In TREATMENT, choose OBLITERATE, adjust AMT and SHIMMER, then compare HEAR ORIGINAL and HEAR OBLITERATE. Only KEEP saves the candidate. REMOVE restores the shared original, and UNDO reverses the last kept board edit or removal. Every velocity zone renders from its own immutable original; replacing WIDE or RUSH does not feed one board into another.

The integration is based on upstream `12d85af05fbd2effc020cf49049cc5fd569db1d9`. The standalone processors approved in [edition 006](https://snipsnap-obliterate-kotlin.bertcalm.chatgpt.site/shimmer/kotlin/) remain unchanged. Josh's exact feedback, **“Port sounds good, proceed as recommended”**, is recorded in `experiments/obliterate-shimmer/listening-006-2026-11-01.json`. That approves the Kotlin port, without specifying a 15% versus 30% shimmer preference.

## Controls and finite budget

| Control | Initial value | Behavior |
| --- | --- | --- |
| AMT | 35% | Destruction-core amount. Below 35%, the complete processed result blends in from the original. At zero the board returns the exact original, including signed zeros and channels. |
| SHIMMER | 15%, provisional | Independently saved send, 0–50%. The requested send reaches the pre-stage unchanged. Below 35% AMT, the complete result blends with dry at `amount / 0.35f`, so fixed core filters also approach bypass smoothly. This new low-amount mapping has not received a listening verdict. |
| BPM | 120 | Clock for fragments/chop, 40–240; saved explicitly rather than silently following later kit-tempo edits. |
| Division | 1/16 | 1/4, 1/8, 1/16 or 1/32. |
| Octave / Room | Octave | Room retains the same tank without pitched excitation/feedback. |
| Pattern | 20261023 | NEW PATTERN advances the saved unsigned 32-bit seed, wrapping at its maximum. |

CLOCK & PATTERN opens the clock/mode/pattern controls. All changes remain staged. Navigation/reopening loads the complete kept state. SHIMMER zero is the accepted core alone. Active shimmer adds 1.5 seconds; active core adds 0.75 seconds. No loop claim is made. Nonzero board processing accepts one frame through nine seconds of normalized mono/stereo 44.1 kHz input. KEEP preflights all source zones before writing even a source capture; an overlong soft zone refuses the whole operation. Zero AMT can restore an existing board's original even when that source exceeds this effect's active budget.

The AMT value remains a Float, like existing boards. The core receives its exact Double widening; comparison against the public processors uses that same boundary. The `.35f` blend anchor is the exact control value. A convex dry/result blend stays within normalized PCM, without a new limiter or feedback path. The separate standalone Double settings API and its heard comparison are unchanged.

## Saved state and compatibility

WIDE and RUSH still read/write version 1 with exactly `board`, `type`, `amount`, including the same JSON formatting and processing. OBLITERATE uses version 2:

```json
{
  "board": 2,
  "type": "obliterate",
  "amount": 0.3499999940395355,
  "obliterate": {
    "model": "obliterate-board-r1",
    "shimmerModel": "obliterate-shimmer-kotlin-r1",
    "coreModel": "obliterate-core-kotlin-r1",
    "shimmerMix": 0.15,
    "pitched": true,
    "bpm": 120,
    "division": 16,
    "seed": 20261023
  }
}
```

Unknown/missing fields, future model identities, invalid numbers and fractional seeds refuse instead of selecting defaults. The shell's printed wrapper retains this descriptor and immutable source references. Typed `PadRecipe` boards and COPY LAST TREATMENT/PASTE preserve every setting. Imported editable pads copy the entire baseline before the source kit can disappear. Flat XPN/native exports carry the printed WAVs and strip source-dependent editing wrappers, as for the existing boards; ordinary typed recipes remain intact.

## Cache and performance

The board retains one completed pre-stage and one completed full output. Keys include input PCM content, channels/rate, all relevant controls and renderer identities. An owned input snapshot is hashed, so mutating or reusing a caller's `Snip` cannot misidentify a render. Every returned buffer is a copy. No working tank/filter state is shared. Maximum retained Float PCM is **7,673,400 bytes**, excluding temporary render buffers, key/object overhead and the processors' existing immutable tables.

Identical HEAR/KEEP calls reuse the full output. Clock/pattern edits reuse the pre-stage; AMT edits can reuse it when SHIMMER/mode stay unchanged. Cold core renders still perform the accepted 8x processing. Cache work occurs outside its locks; interrupted or failed work cannot publish, and clearing invalidates an already running miss. Interruption is checked at call/stage boundaries, not inside the unchanged DSP loops. The existing UI guards also prevent late playback after pad navigation or backgrounding.

The [initial host receipt](../experiments/obliterate-board/initial-host-profile.json) records three warmups and seven trials on the four retained inputs. Cold-board medians were **261.8–481.2 ms**; repeated-call medians **0.53–2.55 ms**, including snapshot, validation, content hash and owned output copy. Every sample matched the direct public composition. Clock edits reused shimmer but still cost **232–431 ms** on this host. Cold here means an empty board cache after the stated JVM warmups. GC and host load are uncontrolled. These are JVM processor-call timings, not phone, storage or tap-to-audible results; cold interaction remains above the desired tens-of-ms budget.

A possible next optimization is caching the core’s 8x fuzz preparation for clock/pattern edits. Its public `stages = 1` audio is unsuitable as that cache: it already has a fade, peak guard and Float conversion. Any extraction must preserve the original pre-guard Double intermediate and pass the existing reference fixtures. Device measurements should decide whether that additional stage is needed.

Host reproduction:

```sh
./gradlew --no-daemon -I experiments/obliterate-board/board-profile.init.gradle :synth:profileObliterateBoard
```

The helper reads test-only assets whose hashes identify the four listening inputs; no source engine is rerendered. It writes `testkit/obliterate-board/host-profile.json` with source hashes, every trial and current commit/dirty status.

The Android profiling test uses those same test-only assets, records device fingerprint/hardware/build, installed APK hashes, each complete recipe and all trials, verifies processor fidelity, and times the real shell preview and KEEP/save doors including file I/O. It is an unshrunk debug measurement, not a release result. Run it on a connected phone:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.ObliterateDeviceProfileTest
adb shell run-as com.snipsnap.app.brassgrow cat files/obliterate-profile.json > obliterate-profile.json
```

The targeted command is a profiling run, not the complete UI regression gate. CI's full emulator suite includes the functional board UI check. An emulator report does not establish physical-phone performance. No device/emulator was attached to the development environment, so physical profiling remains open.

## Verification ledger

| Ledger | Status |
| --- | --- |
| Verified | Complete strict version-2 state, original version-1 JSON behavior, cache ownership/invalidation, immutable baseline and all-zone preflight |
| Measured | 99 focused JVM tests, including a discriminating near-zero continuity probe; four-source initial host profile with exact public-composition equality |
| Proposed | The low-AMT dry/result blend and 15% production default; further cold-render optimization after device profiling |
| Heard | Positive Kotlin-port approval on edition 006; no amount preference or new board-mapping verdict |

Android compiler, complete JVM regression and CI results are recorded with the final build/PR; a profiling test's existence is not a device measurement.

## Why it is built this way

- A separate shimmer send preserves both heard mixes and keeps AMT focused on destruction.
- Blending the whole result below 35% lets AMT approach the original smoothly while retaining the heard 35% and 70% settings.
- Saving model, clock and pattern state keeps a reopened or pasted sound reproducible.
- Content-keyed completed buffers accelerate repeated auditions while keeping every caller's samples private.
- Preflighting all zones protects layered pads from partial KEEP operations; phone measurements still decide whether fresh renders are fast enough.
