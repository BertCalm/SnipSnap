# TRIAD hand drums: revision 2

Baseline: 4964e24a2. Job: REVIEW and targeted revision.

Owner listening: all three rigs sound great; the conga and bongo are weak and flat. This revision changes those two source lanes. The original audition is retained intact.

## Finding and change

R1 uses TERRA COMPOUND_MEMBRANE for both roles: six nearly harmonic modes, short decays and one repeated source per lane. In the source code, hit velocity changes only playback gain. That is consistent with the owner's report of limited body and articulation, but the code alone does not prove what was heard.

R2 gives each role a finite contact driving nine membrane resonances. The conga has a lower, longer body; the bongo has a higher, shorter body and faster fingertip contact. Upper modes decay faster than the fundamental. A quiet low body mode and brief filtered skin-contact noise support the strike. Harder events excite brighter modes and a shorter contact. Four deterministic contact takes prevent identical texture on every hit. These are authored synthesis choices, not fitted recordings or exact hardware emulations.

`Triad.Settings.handRevision` selects 1 or 2, default 2. Version 1 remains reproducible. `Triad.MODEL_VERSION` and the grain seed stay at 1 because the three bus processors have not changed. All seven other source stems and the entire event list are checked byte for byte. A changed hand-drum signal will naturally change the shared compressor and delay response.

## Signal and bounds

Finite raised-cosine contact + bandpassed noise -> `Modes.ring` head and body banks -> bounded early saturation -> `Tide.bandLimit` -> `Dsp.decimate` -> mean removal and 20 Hz DC block -> peak protection -> tail fade.

The modal poles decay with positive RT60; there is no feedback between banks. Normalizing each bank before mixing compensates the toolkit's pitch/rate gain. Velocity remains an excitation/timbre input and an arrangement gain, not a post-normalization gain claim. All frequencies remain well below Nyquist over the exposed tuning range. Four times processing and output band limiting are present; an eight-times alias-error comparison is not claimed.

## Audition and evidence

`generateTriadHandsAudition` writes twelve native Kotlin comparisons: before/revised conga and bongo solos, plus dry and all three main-rig grooves. Full grooves share one achievable loudness target. Each solo pair shares its own target. Timing and bus settings stay fixed at four bars, 124 BPM, DRIVE .55, SPACE .55.

`TriadHandsTest` checks deterministic renders, tuning/touch extremes, peak/DC bounds, decaying tails, and unchanged non-hand stems/events. `TriadTest` checks the surrounding composer and bus behavior. Measurements and settings accompany the audio. Owner listening of R2 is pending.

## Why it is built this way

- Longer low-mode decay gives the conga body after the first contact.
- Faster contact and more upper-mode excitation give the bongo a distinct fingertip edge.
- Velocity changes the strike itself so accented notes have a different timbre.
- The unchanged rhythm and matched A/B levels let the source change be judged in the approved rigs.

## As built

Seven focused Kotlin tests passed (Gradle exit 0). The generator verified identical event lists and seven unchanged non-hand stems. Independent Python inspection verified all twelve mono PCM24 44.1 kHz WAVs, finite samples, peak below full scale, and absolute DC below .005. All 56 original audio, source and download files were retained byte for byte; the original page is archived as `r1.html`.

For the first isolated stroke, mean energy over 30–100 ms relative to 1–20 ms rose from -15.77 to -6.53 dB for conga, and -23.71 to -9.05 dB for bongo. This measures stronger body relative to contact, independently of audition gain; it is not a claim that the assistant heard an improvement. The comparison pairs are loudness matched. No complete repository-wide regression pass or new browser playback check is claimed.
