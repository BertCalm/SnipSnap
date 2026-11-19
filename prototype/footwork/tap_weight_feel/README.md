# Tap weight and connected timing

Owner feedback: taps are too weak and lack human feel (the wording was interpreted
as strike naturalness and/or rhythm, with clarification requested). This revision
addresses both as hypotheses. It does not claim measured human performance or realism.
Street and Industrial remain exactly the retained phrase studies.

The previous tap refinement's toe high-pass at 480 Hz and extra damping removed
much of the native sole/floor response. This revision keeps the native toe/heel
contact body, removes only deep rumble, adds broad low-mid support, and retains the
delayed plate response. Heel gain is fixed across velocities/takes. Brushes keep
more lower contact texture. No sub oscillator, new recording, reverb, limiter or
per-hit normalization is added. Landing forces are firmer while the source still
uses three velocity layers and four repeat takes with continuous gain.

Timing is explicitly authored over the prior phrase, not calibrated to a dancer:
brush/spank anticipates the landing; the step and heel settle slightly later. A
shared phrase-level push/release connects the event offsets, with a small stable
left/right bias. Each foot/contact cycles its own four takes rather than using the
index of all events. The comparison on the old grid uses exactly the same source
contacts, forces and take choices; only onset timing changes. The event ledger
records exact times and offsets. No biomechanics claim follows from its timing checks.

Why it is built this way:

- Shoe weight comes from retaining contact body as well as the plate tick.
- Firmer accented landings give quieter movements a meaningful contrast.
- Related timing offsets connect brush, spank and landing instead of jittering each hit.
- Per-foot/contact repeat counters preserve each foot's pattern while varying strikes.
- Grid and previous-study comparisons separate weight, accents and timing for listening.

Run `python3 prototype/footwork/tap_weight_feel/build.py /path/to/footwork-native-v3 prototype/footwork/tap_refinement/report.json /new/output`.
Thirty-six phrase/roll/build cells and 48 contact cells pass the source assertions.
The checks cover finite output/headroom/DC/tail, deterministic phrase replay,
silence at zero, four distinct phrase takes, increasing dynamics, ordered onsets,
single-foot gaps over 45 ms and PCM16 round-trip error below 5e-5. These are software
checks, not evidence that the result sounds human. Native source version 3 and
kit downloads remain unchanged. Native integration, full export/device acceptance
and the MPC 3 slice-map blocker remain open.

The page's previous/new comparisons use average-energy matching only for audition;
full-dynamics strips retain fixed gains. `weight-comparison.json` measures the
hard-velocity take-0 toe/heel bands, not a perceptual-strength score. Retained
Street/Industrial WAV hashes are checked before publication.
