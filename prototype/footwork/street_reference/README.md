# Street Breaks source identity reset

The owner rejected Street Breaks 01/02 as perceptually alike and 09–13/16 as
unconvincing source labels. The native model-3 delivery tests remain evidence of
transport integrity, not sound identity. No new native model is promoted here.

This Python study fits separate stomp, heel, hand-clap, chest-thump and finger-snap
recordings from [sfzinstruments/body_percussion](https://github.com/sfzinstruments/body_percussion),
commit `4ac9d8966679c648b62fa10a188179e186b97f24`, CC0-1.0. Source files and the
license were downloaded and inspected. Chest thump is a benchmark alternative to
the requested chest pat, not proof of identical articulation. The source library
does not document the foot surface or footwear; these recordings cannot establish
pavement/shoe specificity.

Training uses takes 1–3; take 4 is held out for comparison. Recorded soft/loud
layers supply spectral shapes where available. Mono analysis/audition copies are
high-passed at 120 Hz for feet and 80 Hz for hands/body to remove strong recorded
floor/handling rumble, trimmed to 300 ms and faded. Do not present these as untouched
recordings. The reference button is energy-matched to the candidate for comparison.

Synthesis uses newly generated band-filtered noise and measured time-varying
amplitude curves in 28 bands, with no waveform samples or phase at runtime.
Envelopes use fine early spacing to retain secondary contacts instead of fitting
all attacks to a single exponential. Velocity changes envelope shape, brightness
and force; snap's single recorded layer uses an authored velocity law. Four seeded
repeat takes vary excitation, timing and band balance. Calibration is fixed per
source family, never per hit. Generation is 4x oversampled and uses the Python
house filtering/DC/tail helpers; this is not Kotlin parity.

Why it is built this way:

- Separate recordings constrain the source identities that the hand-authored models missed.
- Time-varying band energy retains contact evolution without replaying the recording.
- Held-out references expose approximation errors instead of hiding them behind kit context.
- Full-dynamics repeat strips test playability before another native kit rebuild.
- Stochastic phase can still lose hard-impact structure; listening remains necessary.

Thigh Slap (10), Bucket (13) and Board (16) remain rejected/unresolved. Public
Freesound pages identifying suitable thigh and board sources were found, but audio
requests returned HTTP 403. The available body library has neither thigh nor these
objects; belly/shoulder sounds are not silently relabeled as thigh. No replacement
for these three is claimed. Candidate source pages:

- Thigh: https://freesound.org/people/PersonNo.8097648/sounds/636490/ (CC0)
- Bucket performance: https://freesound.org/people/logancircle2/sounds/454637/ (CC0; ensemble field recording, not isolated)
- Board: https://freesound.org/people/alec_mackay/sounds/463651/ (CC0)

Run `python3 prototype/footwork/street_reference/build.py /path/to/body/samples /new/output`.
`report.json` records training/held-out files and measured features. The 60-cell
source assertions check finite output, peak/DC/tail, deterministic rendering,
repeat differences and monotonic dynamics. They do not close saving/playback/export,
source realism, phone/hardware or the existing MPC 3 slice-map gate.
