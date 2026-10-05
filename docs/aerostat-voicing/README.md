# Aerostat air / tube voicing candidate

The first listening verdict described the voice as a beep or kazoo, with too little tube attack, little audible Lift and insufficient airy steam on release. This candidate changes the audio model while retaining the controls, mechanics, preset recipes and saved patch format.

- Replace the high-gain saturating whistle delay with a smooth flow-controlled fundamental, restrained second and third harmonics, and band-limited turbulent air. Quick speaks sooner and brighter; Heavy blooms more slowly and darker.
- Shorten the contact excitation, strengthen its mix, and lengthen tube resonance. Align the whistle's starting phase with the contact pulse so the attack does not cancel its own fundamental.
- Map vessel rise more strongly into radiation and breath at high Lift while preserving the 25-cent motion limit.
- Give the held buffer a deterministic, periodic air texture; retain seam validation and the existing no-opening-strike contract.

This is a listening candidate, not an accepted new factory roster. Preset identities will be addressed after the listener judges the basic voice. Existing recipes regenerate with the candidate voicing on this branch; there is no new saved patch version or legacy-voice selector.

## Listening comparison

`./gradlew :synth:generateAerostatVoicingComparison` renders 14 recipes under `testkit/aerostat-voicing/candidate/`. `-PcomparisonVersion=original` changes the output directory only, not the DSP version. The hosted original samples were rendered using the same harness with Aerostat's source from `d1a48edf19fe5e979a0178d066483b95a9c9339f` before the audio edits. The paired recipe metadata is identical. Both sides use the existing AuditionLevel loudness target.

The small gate covers C3/C4/C5, isolated tube/flow/banks, soft/medium velocity, Lift 0/1, long release, Drifting Steam and the held loop. The original full audition remains available. Comparison verdicts use a separate phase and do not overwrite round-one feedback.

## Validation

All Aerostat tests cover mechanics, pitch, deterministic renders, patch round trips, velocity, factory kit filing and held-loop closure across 25 semitones. Added gates require a non-tonal held-air residual, a ringing tube beyond contact, silent zero-velocity one-shots and finite bounded control extremes.

The pitch helper now interpolates FFT log magnitude rather than power, avoiding the latter's systematic flat bias for a Hann-window sinusoid. The stronger pitched knock can be identified as TOM by the generic audio-only classifier. Filing tests use Aerostat's explicit class contract, which SynthKits already applies; the real 16-pad kit test remains in place. The generic classifier was not modified.

Listening acceptance is still needed. Measurements and tests cannot establish whether the candidate sounds like the intended thwap, flute and steam whistle.
