# Model-2 HOLD evidence

This folder separates acoustic capture evidence from the original finite mechanics.
Repeated captures assume externally replenished sound. The unchanged finite work budget
does not fund infinite repetition. Native listening acceptance remains open.

- `tests.json`: focused native gate and the initial 36-case held matrix. The final full
  gate is recorded separately; its whole-cycle/phase test and classifier checks supersede
  the earlier focused assertion set.
- `listening.json`: exact 18-clip pack, source/compiler and audio hashes, independent
  method replay, sampler metadata, ZIP paths/CRC and browser QA.
- `capture-length-proof.json`: bounded `.6/.9/1.2 s` and note-dependent alternatives,
  including 30 transition renders and unchanged finite audio. No candidate was a clear
  improvement across notes, so production retains the original approximately `.6 s` capture.
- `phase-pitch-analysis.json` and `phase_pitch_analysis.py`: explain short-window detector
  refusal with the existing autocorrelation threshold and full-cycle Fourier lines. A
  known-target local-peak lookup diagnoses refusal; it is not an unconstrained detector.
- The adjacent Java experiment drivers reproduce the bounded capture comparisons against
  separately compiled helper overlays. The production Kotlin helper was never changed.
- `android-harness.json`: current API compilation against compile-only Android stubs;
  no SDK build, device execution or phone result.

Regenerate listening artifacts with `:synth:generateSkirlHoldAudition`; generated WAVs and
ZIPs live under ignored `testkit/skirl-hold-audition/`. The pack keeps refused estimates:
35/36 fixed-window estimates, 12/12 whole-loop estimates and 18/24 soft-C2 phase estimates
return a root. A successful detector window does not guarantee all windows or substitute
for listening. The initial acceptance limit is 50 cents, not concert tuning.

WAVs include their root and forward loop in RIFF `smpl` metadata. Exact method descriptors
distinguish `renderHeld` and derived eight-pass listening sequences from ordinary patch
rendering. The offline page embeds its manifest; browser HTTP playback was exercised.
Direct file navigation was blocked by the browser's administrator policy, so only the
portable structure was verified for that mode.
