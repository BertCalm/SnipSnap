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

## Ta-ee metallic response

Owner clarified the gesture as “ta-ee ta-ee like a tiny horseshoe clap.” `metal_contact.py` preserves the dry primary contact and adds a delayed, brighter plate response: four broad resonant noise bands, a low-level inharmonic ring and a tiny independently excited tick. Small/open variants use 12/21 ms onset and 18/30 ms plate decay. These are proposed perceptual parameters, not measured shoe geometry. Run `python3 prototype/footwork/reference_reset/metal_contact.py /path/to/output`.

All 24 Python velocity/take cells pass deterministic, finite, bounded, DC/end-fade, silence, variation and velocity checks. The page includes repeated phrases to judge the owner's two-syllable gesture. Full-kit gates remain open.

Why it is built this way: the delayed bright response supplies the ee; broad resonances keep it from becoming a pure bell; short damping keeps the metal small and dry; independent excitation and deterministic variation avoid a repeated-copy echo.

## Owner listening checkpoint

After the ta-ee audition, the owner reported that it sounds like tap, although not exactly like the reference. Record this as recognizable tap character, not exact reference matching or full-kit acceptance. Small/open preference was not specified. Preserve both candidates; no sound changes were made at this checkpoint. Other voices, native integration, complete kits, articulation and velocity/round-robin delivery gates remain open.

## Dance gesture family

Gesture: toe contact → heel support → forward/back brush → shuffle or flap → resolving heel. `dance_gestures.py` extends the owner-recognized metallic contact; the new gestures have not been heard by the owner. Small-metal is the initial design choice, not a claimed owner preference; both previous candidates remain available.

Run `OPENBLAS_NUM_THREADS=1 python3 prototype/footwork/reference_reset/dance_gestures.py /path/to/output`. Eight roles (toe, heel, forward brush, back brush, shuffle, flap, roll, riser) each render three velocities and four independent takes. A fixed family gain preserves dynamics. Shuffle combines forward/back brushes; flap combines a forward brush and toe landing. The roll alternates toe/heel, and the riser accelerates and gains force into a resolving heel. The original demonstration phrase runs at 116 BPM.

The brush pressure trajectory, abrasive band and chatter are authored perceptual hypotheses, not measured spatial shoe/floor geometry. All new raw excitation is generated at 176.4 kHz and bandlimited before decimation. Finite gestures are composed at 44.1 kHz. Kotlin integration, shelf kits, native saving/playback, MPC/SFZ export and hardware gates remain open. Python timing is diagnostic only; a native port needs cached filter/envelope coefficients.

Why it is built this way:
- Toe retains the recognizable ta-ee contact rather than changing the accepted foundation.
- Heel adds short low-frequency floor support and dampens the upper plate response so it has a different role.
- Two brush pressure trajectories give the shuffle contrasting motions.
- Flap ends on a discrete toe strike; shuffle ends on a reverse brush, so they differ in event structure.
- The authored roll and riser use independently rendered contacts and a definite ending.

## Weight and power revision

Owner requested more heft and power. Render with `OPENBLAS_NUM_THREADS=1 python3 prototype/footwork/reference_reset/dance_gestures.py /path/to/output 1`. The default weight remains zero to preserve the previous family. Weight adds short, damped 155/390 Hz support and a broad 120–1800 Hz impact under the existing contact. Toe receives 70% of the heel support; brushes receive 25% late in the sweep. These are authored perceptual weights, not a physical shoe measurement. All composites inherit their constituent contact weights.

Why it is built this way: the short low body supplies mass; the low-mid knock helps carry impact on small speakers; the accepted metallic contact remains separate; finite damping avoids a long bass or room tail. The phrase comparison uses one whole-phrase RMS scalar, not per-hit normalization or a perceptual loudness claim. The heavier family remains unapproved and full delivery gates remain open.

## Selected direction and native continuation

The owner selected full dynamics. The frozen Python source remains the comparison reference. `native_coefficients.py` regenerates Kotlin coefficient data (no reference PCM); `native_parity.py` compares native saved Tap Jazz contacts against Python. The source uses different deterministic Gaussian draws and the house decimator, so approval of the Python direction is not claimed as native sound approval. See `docs/FOOTWORK.md` for the velocity-anchor compensation law and all native delivery gates.
