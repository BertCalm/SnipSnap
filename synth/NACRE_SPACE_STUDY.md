# Nacre: timed venue returns

This is a Luthier REVIEW followed by a bounded testkit prototype, based on source e238e7ca80a166f39e94727d1098299b831985f8. It changes no production renderer, default, preset, macro contract, or saved model. The full local Luthier bundle is now available and its resource checker passes. The prototype renders the frozen source once and reuses its stems for both returns. Any later interactive implementation should cache that source by saved model, voice, controls, MIDI, velocity and seed; a venue-only edit can then rebuild only the small return stage. The manifest reports source and return-stage timing; no production render-budget claim is made.

## Fix now

[A, D] The owner heard no difference in the three gentle venue clips. Python analysis of the exported PCM24 WAVs measured candidate-minus-original RMS at −26.62 dB for coral and −20.23 dB for seaweed relative to original. Correlations were 0.99914 and 0.99601. The files were distinct, but a waveform difference did not establish perceptible contrast.

[A] The original model-1 gesture reaches 99% energy by 0.772 s, while CURRENT 0.20 runs at about 0.539 Hz. Its short reflections overlap the foreground and most useful energy occurs within half a current cycle. Raising that quiet return by 1.8× did not create the promised changing space in the owner's listening.

## Try next

Keep the original model-1 harp, then replace only its venue contribution with one of two finite feed-forward returns. The gesture is paired pluck → delayed echo or moving passage → finite decay. Coral separates softened reflections in time. Seaweed opens and closes later transmission paths near 2 Hz. These are complete alternative venue designs, so this three-clip study does not isolate geometry from motion.

The common return-send gain targets a substantially more exposed response. A separate space-only preview helps inspect the mechanism. Neither that exposure target nor a signal-distance test proves audibility or pleasantness.

Run `./gradlew --no-daemon :synth:generateNacrePresenceAudition`. Output lives in ignored `testkit/nacre-presence-audition/`. The single ZIP contains three full mixes, three space-only WAVs, a short preview sequence, recipes, measurements and source snapshots.

## Why it is built this way

- The preferred model-1 excitation, loops, bodies, sympathetic stems and event schedule stay fixed so a new response can be judged against that instrument.
- Fixed delays and absorption filters separate a reply from the initial pluck without brighter excitation or a longer string decay process.
- Smooth nonnegative apertures expose motion in the finite response without moving the string tuning or creating a feedback loop.
- Full mixes share one playback gain. Space-only files share their own common preview gain, explicitly separate from mix wetness.
- The return is an output-rate prototype acting on already conditioned model-1 audio. It uses no sharp gate, nonlinear waveshaper, new oscillator, or feedback. It is an analogy for venue transmission rather than a complete underwater acoustic simulation.

## Disclosed, not fixed

A production patch alone reproduces the original source. New return delays, filter corners, aperture law, return gain, exact Float bus replacement and playback gains belong to the audition recipe wrapper. CURRENT 0.20 remains a frozen source setting; the approximately 2 Hz venue motion is a separate diagnostic process. No production macro or venue behavior has been promoted.

The original signal remains unchanged before playback gain; candidates preserve the original nonvenue contributions within the documented final Float mix rounding. The additional return may alter measured pitch confidence, loudness and energy timing, which are reported. The short comparison sequence trims and fades preview cuts only; the full six-second files are canonical.

Focused prototype and export checks are appropriate to this listening hypothesis. No full JVM regression pass is claimed. Experimental source stays in a local commit with exact snapshots in the published study. Before a later SnipSnap repository push or PR, Steward's full JVM test gate applies.

## What I measured / what I only read

**Heard by owner:** model 1 preferred; model 2 abrasive and overbearing; the gentle venue study had no audible difference.

**Measured:** prior exported WAV differences and correlations above. New prototype measurements, source hashes and invariants are in the generated manifest and diagnostics.

**Verified in source:** explicit saved-model dispatch preserves model 1; candidates do not modify its excitation or state. The new return is acyclic. Actual source commit and files are recorded in the export.

**Read:** original spec's shared-current and venue requirements; local Luthier conventions, review rubric, DSP catalog and inventory. The four-venue/HOLD design remains incomplete.

**Proposed:** the return shape, send level and timing are listening hypotheses. Listening acceptance is pending. A protected live-fetch check of the prior Site returned an HTTP error, so live browser/CDN behavior was not verified. New audio filenames include content hashes to avoid reusing prior audio URLs.
