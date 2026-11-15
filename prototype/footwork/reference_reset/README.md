# FOOTWORK reference reset: one tap contact

R0 was rejected by the owner: “Doesn't really sound like the real sounds”. This replacement study addresses one tap-shoe contact only. Heel, stamp, brush, clap and thigh still need separate real-world calibration. It is not a revised full kit.

Reference: [tap dancing. by Chinouk211](https://freesound.org/people/Chinouk211/sounds/592705/), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The author identifies tap shoes on tiles in an echoing room. It is not labeled toe versus heel and is not a dry wooden stage. The reference is an interim benchmark pending the owner's preferred performance.

Input is the public HQ MP3 preview decoded to mono 44.1 kHz PCM16 WAV. The output credits record the edits (excerpts, mono conversion, shared level and spacing). The reference is not copied into the source model or loaded by `render()`.

```sh
python3 prototype/footwork/reference_reset/build.py /path/to/reference.wav /path/to/output
```

`build.py` fits twelve broad noise-band power envelopes with five fixed decay components from four isolated training strikes. One additional contact-damping time fits the training median energy duration. Four disjoint strikes are used for comparison and reported spectral-distance measurements; the holdout does not set parameters. The synthesis uses seeded filtered noise, fitted envelope coefficients, timbral velocity laws and fixed source gain. Model JSON contains coefficients, not waveform samples, phases or a spectrogram reconstruction.

Outputs: reference/previous/candidate/touch WAVs, `model.json`, `report.json`, and `CREDITS.txt`. Comparisons have equalized overall source energy followed by one shared display gain; there is no individual take normalization. The reference excerpts contain room response and MP3 encoding artifacts. The broad spectra and envelopes do not fully identify the shoe, floor or contact mechanics.

Numerical checks cover all twelve new velocity/take cells, deterministic replay, timbral velocity, distinct normalized takes, silence at zero force, peak, DC and end fade. A closer spectral distance is a diagnostic, not a pass for perceived realism. Listening, full-voice expansion, native/Kotlin delivery and all remaining kit gates stay open.

Mechanism reference: Luca Turchet, [Footstep sounds synthesis: Design, implementation, and evaluation of foot–floor interactions, surface materials, shoe types, and walkers’ features](https://www.lucaturchet.it/PUBLIC_DOWNLOADS/publications/journals/Footstep_sounds_synthesis.pdf), Applied Acoustics 107 (2016). The paper supports treating contact, shoe and surface together; this script is an independent analysis-by-synthesis experiment, not an implementation of that paper's complete physical model.

## Secondary-contact study

Owner hearing: the calibrated contact is closer, but missing a secondary element. The single monotonic envelope erased local re-attacks. A 1 ms energy envelope of the reference has peaks separated by roughly 5–10 ms in several strikes; this does not distinguish shoe contacts from reflections.

`python3 prototype/footwork/reference_reset/contact_pair.py /path/to/output` creates tight (6.5 ms) and separated (11 ms) hypotheses. Each adds independently seeded, darker noise excitation at 0.65 nominal amplitude with deterministic timing/strength variation. These are proposed contact mechanics, not a recovered physical model. Both retain the same fixed comparison gain as the prior audition; no per-hit normalization or waveform copying occurs. All 24 cells pass the Python checks. Full-kit and listening acceptance remain open.

Why it is built this way: independent excitation avoids a comb-filtered echo; a darker second contact distinguishes landing from the first click; short timing variation avoids identical machine repeats; two spacings let the owner judge the missing gesture without replacing the closer baseline.
