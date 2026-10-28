# TRIO contact listening experiment

The owner could not distinguish TOUCH .8 / .9 / 1 within the published groups.
The previous cutoff extension does change waveforms and spectra, but that did
not establish a useful perceived difference. The current production mapping,
all saved model-1 through model-6 recipes and the accepted bank remain intact.

This is a listening experiment based on commit 00d74a2b. Its characteristic
gesture is the same strum and single companion contact, followed by a progressively
hollower low/mid color as upper TOUCH moves. The only new production-code surface
is an internal diagnostic seam; ordinary rendering supplies no transform.

The candidate is a contact-profile analogy, not a calibrated finger model.
Each fresh excitation becomes `burst - alpha * delayed(burst)`, with the delay
rounded from one third of that course's physical period and alpha rising from
0 at TOUCH .8 to .8 at 1. The complete delayed tail is retained. This feedforward
filter has gain between .2 and 1.8; it changes no feedback path or string tuning.
It predicts third/root changes of about -6.4 dB at .9 and -17.9 dB at 1 in the
excitation. The body and ensemble determine the final output; hearing remains
the acceptance test. PHIN receives the same profile, rather than ignoring it.

The page begins with the current full-range 0 / .5 / 1 reference. Current and
candidate upper-range scenes then share the root, velocity, seed, gestures,
strums and one contact per companion, at DRAMA 1 and .5. A Tres source view
extracts the same ensemble's contribution. Full notes last 3.5 seconds. Quick
sweeps use their first 1.5 seconds, with 12 ms excerpt fades and .2-second gaps.
Matched playback uses the existing perceived-level proxy at .03, not LUFS.
Candidate data are explicit diagnostic wrappers around a current patch, not
ordinary recipes or a new app mapping.

Rebuild: `./gradlew :synth:generateTrioContactAudition`.
The previous cutoff comparison is retained separately on the private site.

Verified: 62 focused TRIO tests passed, including seven contact tests, with
unchanged score construction, old saved audio and all accepted factory sounds.
Independent pack checks matched all 68 assets and 8 source hashes; all 54 WAVs
are mono PCM24/44.1 kHz. All 14 sweeps reconstruct within one PCM24 LSB.
Current/contact TOUCH .8 files are byte-identical in both playback levels.
Hosted Chromium checks cover 54 selections, seeking, downloads, sweep cues and
320/390/1440 px layouts. Every one of the 347 published files matches locally;
the prior comparison, accepted bank and DRAMA audio remain unchanged.
The full JVM run was interrupted when the workspace paused; it has no verified
exit result. The completed regression evidence is the focused TRIO suite.

Measured: at DRAMA 1, the adjacent gain-fitted waveform residuals increase
from .391/.323 to .501/.404 in the mix, and .152/.187 to .399/.338 for Tres.
These measure contrast beyond overall gain; they cannot establish audibility.
Spectral summaries are mixed: at DRAMA .5 the first adjacent spectral distance
slightly decreases, even though the waveform residual increases. The independent
analytic excitation response and rendered report accompany the pack.
Heard: the owner found the earlier three upper settings too similar. This
candidate awaits listening.

## Why it is built this way

- Low/mid harmonics give the contact change a different target from extra brightness.
- Shaping the fresh force before the strings keeps it part of the strike.
- The original score preserves the accepted strums and single companion hits.
- An exact bypass at and below .8 preserves the existing starting sounds.
- A listening-only wrapper lets the owner judge the color before changing saved mappings.
