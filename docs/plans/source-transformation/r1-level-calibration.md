# Model 2 level review

Luthier REVIEW, 2026-11-05. The tested candidate is the existing model 2 at implementation-content SHA-256 `8f9884d5cebb2c269e94ae42ed656bc2c3c1ae5f2e32b65655f85523765a293b`, on the working tree based at `1994c619ebcccff932cf0d69d5b7b61d16862651`.

**Heard, owner:** "It is quiet, they are easier to distinguish, I prefer them". This is favourable feedback on the model 2 timbres and their distinction. Output level remains unresolved. Which playback section felt quiet, and whether speakers or headphones were used, were not specified. A clarification distinguishes original-volume, level-matched, and both; no answer is assumed to be an approval of a new gain law.

## Fix now

- **[A/D] Retain the preferred spectra and decay profiles.** The remaining complaint is level, not a request to change the bodies again. The original generation receipts remain historical; record this later feedback separately instead of rewriting their `heard` or acceptance fields.
- **[D] Separate listening-master gain from processor wet gain.** The previous matched master targets `Loudness.of` .1, while the house melodic reference is .1834. That is about 5.27 dB apart in that measure; it is not a LUFS or measured-phone comparison. Held matched notes have considerable sample-peak headroom, while some short matched clips already reach .9. Boosting the complete earlier file by a common +6 dB would exceed the existing sample ceiling.

## What I measured

NumPy measurements and gain-only calculations use the saved Kotlin-rendered float PCM. They do not rerender or change the model. The detailed report is retained as `resonant-body-v2-level-probe/gain-report.json` alongside the delivery artifacts.

There are 12 v2 cases in the before/after corpus. Their maximum raw sample peak is .098637. On **these sampled fixtures**, fixed wet gains +6/+12/+18 dB stay below the .98 sample ceiling at MIX 1 and reconstructed MIX .5. The maximum pure-wet peaks are approximately .197/.393/.783, with no delivery attenuation required. This is not universal headroom for arbitrary inputs.

Common gain does not balance every body/source combination. At every common gain, BAR remains 5.79 dB below HARMONIC on the default burst and 16.61 dB below on the sustained fixture. At MIX .5, the baseline wet/dry RMS ratios are approximately −26.85/−32.64 dB for burst HARMONIC/BAR, versus −3.30/−19.91 dB for sustained HARMONIC/BAR. With +18 dB wet gain, burst BAR still sits 14.64 dB below dry while sustained HARMONIC sits 14.70 dB above dry. One fixed gain cannot remove that source dependence.

A separately labelled Python equation stress, checked against saved Kotlin wet PCM within 3.73×10⁻⁹, drives a full-scale resonant BAR mode. At +12 dB it invokes the same linked attenuation rule: about −12.16 dB attenuation at MIX 1 and −6.53 dB at MIX .5. The modal state remains bounded, but the final guard is essential and the dry signal can be attenuated too. These are numerical stress results, not owner listening or a new Kotlin model.

## Try next

Use two explicit delivery-only comparisons:

1. **A louder listening master.** The four existing preferred v2 matched endpoints receive one fixed gain per source group: +1.5 dB for burst clips and +12 dB for sustained clips, identical for both bodies within that group. This retains relative levels within each source and tests a louder presentation without redefining the processor. Selected peaks must remain below .98; no hidden limiter, compression or per-clip renormalization is used. It is not a universally safe mastering setting for other files.
2. **Raw wet gain choices.** The same four raw v2 endpoints are presented at 0/+6/+12/+18 dB. Gains are fixed, fully wet, and do not equalize the bodies individually. These illustrate an output-level choice; they do not implement model 3 or change v2.

Preserve full clips/tails and source hashes. Playback FLAC is PCM24: existing matched PCM24 can be scaled and requantized for the new listening copies; raw float32 copies are converted to PCM24 for playback, not retained as bit-exact float archives. No resampling, spectral processing, changed body profiles, per-event normalization, or source capture is needed.

The owner should compare comfortable level at the same device volume. Numerical headroom cannot choose the preferred gain. A new renderer gain policy, default promotion, or additional control would be a separate explicit implementation, with its own version/recipe and validation implications.

### Delivered gain-only comparison

`resonant-body-v2-louder-listen.flac` is **192,678 bytes / 7.58576 seconds**. It plays CURRENT then LOUDER for each case, in this order: burst HARMONIC, burst BAR, sustained HARMONIC, sustained BAR. Complete clips are separated by 150 ms zero gaps. Fixed gain is +1.5 dB for both burst bodies and +12 dB for both sustained bodies. The direct file includes a trailing silent gap.

The maximum boosted listening-copy sample peak is .93812. An independent 4× SciPy estimate gives true peak .93862. All eight current/louder per-case FLACs and the combined file decode exactly to their expected stored/scaled-rounded PCM24. There is no compression, limiter, resampling or per-clip normalization, and 104 original files checked by the packager remain unchanged.

`resonant-body-v2-level-comparison.html` is **643,526 bytes** and self-contained. It has four current/louder pairs, with the sixteen 0/+6/+12/+18 dB unmatched trials in a separate collapsible section. Every embedded audio hash and label was checked against the asset records. All sixteen unmatched trials remain finite, preserve full duration and zero endings, and require no guard attenuation on these selected files. Their PCM24 FLACs decode exactly to the gain/quantization calculations.

Chromium playback, 393-pixel mobile layout, switching players, opening the unmatched section and the direct FLAC duration passed, with no page errors or external requests. Browser checks used temporary localhost HTTP; no managed file-access policy was changed. Physical phone playback remains unmeasured. The listening preview and renderer gain are explicitly distinguished on the page.

## Disclosed, not fixed

**Heard, owner, 2026-11-07:** "I can't tell the difference in short tones, but do in the longer" after the CURRENT → LOUDER delivery. The short clips received +1.5 dB and the sustained clips +12 dB, so these were different-sized gain comparisons. The assistant explained that distinction and asked whether the louder long-note level was comfortable and useful. No answer to that question was received before the owner requested this handoff. This feedback does not choose a renderer gain, establish a duration-dependent gain rule, or reverse the earlier timbre preference.

The exact quiet playback section remains unspecified. No gain choice has owner listening acceptance yet. Timbre preference does not close level calibration, real-capture use, or Nothing Phone (3) performance gates. The renderer and its existing v1/v2 controls remain unchanged during this review.

## What I only read

The existing versioned tables/decay laws, fixed makeup 4, linear dry/wet mix and linked attenuation-only guard were inspected. The last renderer validation remains the passing 5,947-test JVM gate plus 73 v1 compatibility and 24 comparison replays. This review changes documentation and gain-only listening artifacts; no new Kotlin implementation or fresh full-suite result is claimed.
