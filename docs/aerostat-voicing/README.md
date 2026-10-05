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

## Round three: steam in the full mix

The round-two verdict rejected both full voices and reported that airflow was audible only in isolation. Raw layer measurements found the cause: tube RMS at C3/C4 was about 8–9 times airflow RMS during 40–150 ms, before whole-sample peak/loudness levelling. The 150–350 ms body was only about 8% of the opening attack RMS.

Balance the tube contribution before levelling (0.78 to 0.06), leaving its excitation and decay intact. Airflow now wins during the catch, while the tube still supplies the opening strike. Pressure controls turbulence explicitly; Lift maps smaller vessel rises into radiation and breath more strongly. The tuning motion limit is retained.

The third gate compares round two (`214ea61c3c130c1951a318fd47c09a5c2f3766f3`) with the remix on identical recipes. It emphasizes the full sound, isolated layers, Pressure/Inertia/Lift/Release endpoints, soft velocity and held sound. The renderer now emits 20 source clips; the focused page selects 14 pairs. `comparisonVersion` only selects the output directory, so the round-two clips were captured before the DSP changes.

New regression checks require airflow to compete during the catch at C3/C4/C5, a body that survives whole-sample levelling, and distinct upper-band energy for Pressure and Lift endpoints at the same strike. These are audibility gates, not listener acceptance of the flute/steam-whistle character. A sonic reference was requested to guide further voicing.

## Round four: paddle contact

The listener asked for a little more contact between the paddle and tube. Add a finite 6–16 ms surface-contact envelope, shaped by Strike and velocity, alongside the short bore-excitation impulse. A low-pass contact filter follows Strike hardness and settles after the paddle leaves. Give the surface contact a separate mix gain and split it between Quick and Heavy; scaling it with the quieted resonant tube had made the contact nearly inaudible.

The tube resonance, mechanical impulse and airflow gain remain as in round three. Raw C3/C4 renders differ only at the head, with identical samples after 40 ms. Airflow and held comparison WAVs are byte-identical to round three. All 34 Aerostat tests pass, including onset/body balance and pitch/loop closure. The small contact audition selects eight matched pairs: full C3/C4, isolated tube, soft/hard touch, Quick bank, plus airflow and held controls.

## Round five: uploaded PVC-paddle reference

Reference: https://youtube.com/shorts/JAXO_EDdULk . The supplied screen recording contains about 14.37 seconds of mono audio and visibly shows paddles striking PVC tube mouths. Its spectrum includes strong near-octave pairs (for example approximately 138/277 and 206/413 Hz), consistent with second-partial energy. Overlapping played notes can also contribute to those peaks. This is a polyphonic performance, not an isolated impulse measurement; it does not establish exact tube dimensions, a unique fundamental for every note, or a measured physical decay curve.

Use the reference to change the tube's odd-only 1/3/5/7/9/11 ladder to 1/2/3/4/5/6. Weight the second partial more strongly and slow upper-mode decay relative to the fundamental, retaining Quick/Heavy differences. The paddle texture and balanced steam remain from the prior candidate. No audio from the reference is sampled into the engine or included in the hosted assets.

In the C3 isolated tube's 20–113 ms early-ring window, second/fundamental power rises from near zero to about 0.144. A regression gate requires a substantial second partial. Airflow and held WAVs remain byte-identical to round four. The focused audition compares eight matched prior/PVC candidates: full C3/C4/C5, isolated tube/Quick/Heavy, soft velocity and hard Strike. Listening acceptance remains pending.
