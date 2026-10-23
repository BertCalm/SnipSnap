# Murmur Luthier follow-up

This follow-up keeps the approved model-1/model-2 render identities and the 36-preset bank. It makes a small render optimization, adds a coherent foldback regression check, and supplies two reversible listening experiments. No candidate becomes a factory sound in this change.

Reviewed base: `84471534a` (the Murmur engine itself is unchanged from the previously delivered `395de6e2`). The complete repaired Luthier bundle passed its resource checker: nine reference documents, three harness scripts and the evaluation JSON.

## Reproduce the listening experiments

```sh
./gradlew --no-daemon :synth:generateMurmurLuthierAudition
```

Output: `testkit/murmur-luthier-audition/`. Pass `-PmurmurLuthierOutput=/absolute/output/path` to choose another destination. The generator writes twelve dry comparisons, raw/matched PCM24 WAVs, exact settings, measured loop plans, hashes and a reproduction README. It checks repeated Float32 PCM determinism and verifies its experimental finishing path against the production probe at both CHOIR notes.

KEY compares MOTION 0, .5, .75 and 1 at C4. The candidate is an ordinary serialized model-2 custom patch: target gains are multiplied by three, capped at the existing limit of three. Its source, center frequencies, bandwidths, ratios, envelope and macros stay the same. The purpose is to test whether stronger resonance reveals the moving mouth without losing the tine character. The measured improvement remains conservative; listening must decide whether it is useful.

CHOIR compares current and longer HOLD plans at C2 and C3. The experimental planner fits one cycle of the requested near-unison beating, then rounds to an even number of root cycles and a whole control-block boundary. The C2 loop grows from about two seconds to about five seconds. This also changes the repeated-noise period and quantized formant-motion rate, so the comparison does not isolate detune alone. Its JSON wrapper includes the extra policy and base patch. Loading only that base patch into the app produces the approved short loop. The longer policy exists only in the development generator.

## Why it is built this way

- Stationary formants retain their initial coefficients, while their filter histories still advance. Repeating the same division adds cost without changing the sound.
- Absent ring sources skip their cosine; duplicate operator reads reuse one cosine. Product and sum order remain unchanged to preserve saved render arithmetic.
- Moving formants and every settling period retain their existing processing. A broader trajectory cache measured slower and was removed.
- The experimental loop policy stays in the listening tool until its sound and extra render cost have been judged. No new saved model or patch migration is introduced.
- Foldback is measured separately from high-frequency brightness. A bright spectrum can contain legitimate harmonics, and a quiet near-Nyquist band alone cannot establish alias rejection.

## Verification ledger

Measured on implementation commit `7b17c3441cd927bc254af0b2baa4ec3425a78234`, with engine SHA-256 `7da9f39cd32f6a98dc615f410acc854d8391354c7aa219c733ea46a941ee5205`:

- All 216 Float32 render comparisons matched the pre-change baseline exactly: six voices, both models, three registers, three MOTION values, finite and held.
- A complete clean-source rerender preserved all 486 published WAV hashes and all 243 recipe values. All 735 existing Site files except the navigation page remain untouched, including the APK/kit downloads and device checklist. This is publication compatibility, not approval of the expanded preset bank.
- `./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.Murmur*' --tests 'com.snipsnap.synth.PadRecipeTest'` exited 0: 64 tests, no failures, errors or skips. Both audition generators exited 0.
- Browser QA passed 48 decoded playback states across desktop and 390-pixel mobile layouts, continuous short/long C2 wraps, KEY natural completion, single-source switching, level switching, notes/reload/export, navigation and preservation of existing bank/device states. Final generated WAVs exactly match the browser-tested bytes.
- `./gradlew --no-daemon test` was started after the focused gate and generators. It remains in progress at publication; the focused result must not be represented as a full-suite pass. The PR also runs repository CI.

### Render cost

The sum of twelve held-cell medians fell from 3840 to 3617 ms, about 5.8%. The stationary subset improved about 7.5%; the moving subset about 4.1%. These are sequential Java 21 cloud JVM measurements, seven repeats after warmup, not Android latency. Some cells are unchanged or slightly slower. HOLD still takes hundreds of milliseconds; the tens-of-milliseconds goal remains open.

| C4, velocity .83, MOTION .5, HOLD | Before | After |
| --- | ---: | ---: |
| BASS | 310 ms | 293 ms |
| KEY | 299 ms | 303 ms |
| PLUCK | 307 ms | 282 ms |
| LEAD | 359 ms | 341 ms |
| CHOIR | 293 ms | 273 ms |
| METAL | 343 ms | 342 ms |

Complete timing method and cells: [performance.json](performance.json).

### Listening experiments

The minimum measured KEY contour distance between adjacent .5/.75/1 MOTION steps increased from .0191 to .0557. This normalized spectral-power distance is mechanical evidence, not perceived improvement. About 94–96% of post-onset power still sits around harmonics one and three; stronger motion remains a hypothesis to judge by listening.

At C2 the current CHOIR loop is 1.957 seconds and its fourth operator becomes unison. The experimental 5.107-second loop retains 5.176 cents of detune, against the requested 5.186 cents. At C3, duration grows from 1.850 to 2.553 seconds and detune moves from 7.139 to 5.176 cents. Maximum candidate root error is .110 cent; adjacent settled-cycle seam error is at most 1.54e-17. Duration-dependent noise/motion changes and extra render cost are disclosed in the manifest. Complete measurements: [experiments.json](experiments.json).

### Foldback validation

Twenty stationary breathless bright LEAD/METAL cases span MIDI 48/60/72/84/96 and two MOUTH positions. A 4x scratch reference matched production PCM exactly in every case. Coherent-period harmonic projections distinguish off-harmonic energy; 8x/16x source references and independent brickwall decimation help identify its cause.

The largest measured off-harmonic power is -32.56 dB relative to total power, in LEAD C5, concentrated near 21.6 kHz. The component below 16 kHz is at most -93.87 dB across these cases. Independent brickwall filtering of the same 4x source reduces the worst case to -113.44 dB full-band; using the shared final decimator at 16x still gives about -33.07 dB. This points to its transition band as the dominant measured cause. The accepted decimator remains unchanged.

Aliases that coincide with genuine harmonic bins are excluded. This check omits breath, moving sidebands, note attacks/releases, custom detuned patches and model 1. It establishes neither a complete alias bound nor audibility. The new test uses measured no-regression limits, not a sonic acceptance threshold. Complete measurements: [aliasing.json](aliasing.json).

Hearing remains unverified until the owner listens. Browser checks verify delivery and interaction, not sonic preference or physical phone/MPC acceptance.
