# Libration: two-touch held delivery

The characteristic gesture is soft or hard key-on → the existing orbital attack → recurring sustain → the player's key-up fade.

This extends the three current voices, GATHER, TWINS and DEPARTURE. The roadmap does not schedule a fourth voice. Source models 1–3, held model 1, the finite kernel and the twelve presets stay unchanged. The two captures expose articulation already present in source model 3.

## Capture and gain contract

Saved TOUCH is the hard excitation anchor. Soft uses 35% of that velocity; hard uses 100%. These are musical capture positions, not calibrated forces. Lower velocity changes the exciter's upper partials and attack smoothing before the modal bank. Both captures retain their complete attacks and independent validated sustain loops.

The native held renderer contains a velocity^.7 loudness law. Divide each capture by its excitation velocity relative to the hard anchor, raised to .7. This removes the relative capture-level difference once; it does not match the waveforms' loudness. A shared peak guard of .94 may lower both captures together. The instrument player still applies live note velocity gain. Authored TOUCH remains a source parameter and reference level. No new filter, physical trajectory, pulse scheduler or oscillator is introduced.

The delivery windows are MIDI 1–63 and 64–127. Each sampled root has two separate WAVs and loop markers. Native roots keep their existing clipped minor-third span, with no full-keyboard extrapolation. Generation renders one root and its two captures at a time, reusing one bounded physical table; time and retained package audio grow to roughly twice a single-layer instrument. Cancellation is checked between captures and inside the existing renderer. Cloud timings are not phone timings.

## Compatibility and integration

Single-layer instrument recipes retain version 1 and their exact JSON shape. Two-touch recipes use version 2, require touchModel=1 and touchLayers=true, and keep the complete source patch and release. Older versions cannot silently discard a touch marker. Single-sample assembly/export entry points reject layered specs.

SYNTH → MAKE INSTRUMENT defaults to TOUCH LAYERS ON for Libration, independently of other engines. OFF retains the old one-capture path. PREVIEW selects soft, then hard, through two wraps and release; progress counts both samples per root. Touch-layer choice does not edit the source patch. Phone InstrumentStore v2, KeyHit and both MPC writers already support velocity windows, so this round uses their existing shared selection path.

## Reproduction and listening

The dedicated generator `:shell:generateLibrationTouchAudition` uses ROUND KEYS, FINE KEYS and SPLIT PATH, producing three nine-root instruments, 54 PCM24 samples and 12 listening files. It reopens the exported samples through InstrumentEngine, records each exact source recipe and sample gain, audits sampler markers and writes byte-checked ZIPs. The listening page has long soft/hard holds, the 62/63/64/65 boundary and alternating keyboard phrases. Earlier finite and single-touch listening artifacts remain intact.

On phone, make the same recipe with TOUCH LAYERS ON/OFF, cancel one generation and retry. On MPC, load the complete program and TrackData folder, play around 63/64, hold notes beyond two wraps and check key-up release. Listen for an articulation change without an excessive boundary jump. New layered listening/hardware acceptance is pending.

## Why it is built this way

- Existing excitation velocity already changes the attack and spectrum; capturing it avoids adding a second timbre mechanism.
- Relative level compensation keeps the layer switch from multiplying both baked and live velocity dynamics.
- Each layer keeps its own verified loop and complete replay recipe.
- Versioned delivery metadata preserves existing source recipes and single-layer packages.
- Serial capture reuses physical work and keeps cancellation responsive on a phone.

## Evidence ledger

Verified by source inspection at base 8b6914927: the phone's existing instrument sidecar and key-hit resolver support layered samples. Three DSP cases pass articulation after level matching, root and recurrence for both touches, exact recipe/gain replay, zero touch and cancellation. The two package cases pass versioned recall, both MPC exports, the phone sidecar, 63/64 sample selection and player release through actual PCM24 files; the final shell test command exits zero. Matched articulation residual is 5.40–7.10% with hard/soft body RMS ratios 1.0027–1.0076 in the three fixtures. The six existing DSP/version/preset files remain byte-identical to the base, including the frozen finite kernel. Android compilation, the UI check and full regression follow on this source. No listening or phone timing claim is made.
