# Crease playable release protocol

Date: 2026-10-31. Job: Luthier CONCEIVE, the one recommended direction.
Repository baseline: `fde661771` (full hash is captured in the render ledger).
Status: preregistered before bank measurements. Nothing in this round is heard.

The characteristic gesture is **press from rest → actual onset and held motion →
key-up at any frame → a cached approximation of that state’s unfolding → silence**.

## Scope and comparison

Reed Mark, MIDI 60, saved authoring velocity, model 3, mono 44.1 kHz and the
existing 4x conditioned Kotlin circuit. This extends Crease; GLINT has no fading
deformation memory and BELLOWS uses different pressure-driven sources. It adds
no engine, voice or factory preset. The source circuit is literal; material
language is analogy, and no passive acoustic body is simulated.

The Java harness reuses the merged research circuit adapter. The entire held
trajectory must equal public `CreaseKeys.probe(normalize=false)` exactly.
Every exact release copies all circuit/conditioning state and clocks, with zero
fork error. It withdraws the pressure source and applies the same 1.2-second
linear external fade as the previous experiment. The exact continuation is the
reference; the held loop under that same fade is the comparison.

Candidate caches use 16, 32 or 64 positions per gesture period. They include
the actual non-recurring onset/settling prefix, rather than treating every tap
as an already-held note. A common denser initial grid captures the fast onset.
All candidates are subsets of the same 64-position capture grid. Selection is
the smallest cache that passes every gate, not the best single listening cut.

At note-off, choose the two neighbouring captured states immediately. Align
each tail’s carrier phase with a signed shift of at most half a root period,
using four-point interpolation over a real held lead-in. Blend their samples
linearly in gesture time. A fixed 5 ms smooth transition from the continuing
held sample avoids inserting a new jump. The volume fade starts at key-up, with
no wait for a bank boundary. No solver or new simulation runs during playback.
The phase shift, interpolation and crossfade are approximations, all measured
against the exact continuation. Include an unaligned nearest-tail canary.

Use one common gain across held audio, every cached tail and references.
Measure the actual quantized PCM16 cache, not only ideal float samples. A
lossless gzip container may transpose/delta-code the PCM matrix for delivery;
its decoded integer samples must match exactly. It must not discard quiet tails.

## Predeclared gates

- Exact public held prefix and full-state snapshot error: zero. All reference
  samples finite and bounded. Repeat selected exact releases bit-identically.
- Deterministic test positions include taps during the onset, off-grid settling
  times, steady phases, both sides of a wrap and long holds. Publish every case,
  all candidates and failures. No case is removed after seeing its result.
- Actual PCM cache versus exact release: full 0–1.2 s error RMS divided by
  `max(reference RMS, .005)` at most .031623 (30 dB), absolute peak error at most
  .025 full scale, and maximum normalized harmonic-power TV at most .02 in the
  fixed 40–250 ms / 250–500 ms windows. The .005 floor handles near-silence;
  also report the un-floored reference RMS and absolute error.
- Added discontinuity at key-up at most .001 full scale, measured against the
  reference step. Report the largest sample error in the first 10 ms separately.
- No clipping, non-finite samples or stuck voices. Zero-velocity playback is
  silent. The cache is shared across voices, not copied for each note.
- No phase-bin wait: algorithmic wait is zero frames. Browser tests report
  command-to-processor response and frame quantum separately from unmeasured
  speaker/device latency. Do not label a software timing check phone latency.
- Selected uncompressed PCM cache including held audio is at most 32 MiB for
  this one sound/note/velocity; selected compressed delivery at most 12 MiB.
  Report actual build, decode, playback costs. No claim this budget scales to
  a multisampled instrument. Build/cache is explicit, never a knob side effect.
- Browser: real pointer and keyboard hold/release, short taps, long holds,
  release during loading, repeated presses, cancel/blur/hidden-page cleanup,
  mode switch, missing/corrupt cache and no-autoplay all pass at desktop/mobile
  widths. Exercise actual audio processing, not just button appearance.

Phone integration is conditional on all approximation and browser gates. If a
gate fails, publish the honest experiment and exact/fade references, document
the limiting case, and keep production player/schema/export unchanged. Passing
these tests does not establish listening acceptance or MPC hardware support.

## Cache contract and cost

Identity includes all saved macros, voice, model, actual pitch, exact velocity,
circuit/output source and JAR hashes, sample rate, bank grid, interpolation and
fade versions. Capture the clean source commit used for rendering. Post-render
documentation commits cannot relabel old audio as newly rendered.

```text
actual Kotlin circuit from rest → exact state captures → conditioned tails
                           └──→ actual onset + held PCM
              [offline cache: full patch + versions + grid]
press → held PCM reader → key-up → phase-aligned neighbouring tails
                           → 5 ms bounded blend → common 1.2 s fade → output
```

Existing sound macros remain fixed at the saved patch values. Controls are
press/release (hold duration), Fade only/Unfold (pressure-tail selection), and
Stop (short cleanup fade). Each is tested through the sample transport. No
new sound macros are authored for this proof.

## Why it is built this way

1. Exact circuit continuations tell us whether the shortcut preserves the
   unfolding, instead of merely producing a smooth but different ending.
2. Onset captures make short taps a real test, not a steady-loop demonstration.
3. Carrier alignment prevents otherwise similar tails from cancelling or
   clicking because their oscillators point in different directions.
4. Immediate selection avoids the 1.45–2.10 second boundary-wait failure of
   the tested gesture loops; the short blend is an explicit approximation.
5. A bounded, shared PCM cache keeps playback free of offline simulation work;
   measuring its size exposes the cost before changing the phone format.
