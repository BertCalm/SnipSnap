# CREASE held keyboard instruments

The characteristic gesture is pressure onset → delayed deformation → recurring
unfolding while a key is held → a player-controlled release fade.

This extends the approved model-3 engine into the existing sampled-instrument
path. It adds no voice family or synthesis model. Ground truth was inspected at
`6990bfde7867da438a57323f15d45cceeaa3a62b`; the relevant existing entry points are
`Keys.registersPad`, `OpenSkyKeys.render`, `RegistersPadMaker`,
`OpenSkyPadMaker`, `OneNote.writePackage`, and `InstrumentEngine`.

## Scope and landing

The first listening instruments are Paper Keys and Ink Ribbon. All three
existing model-3 voices can use the same builder; models 1 and 2 remain available
for their original finite and HOLD renders. Instrument authoring rejects those
older models rather than silently changing their saved sound.

The pressure/deformation equations run literally. The paper/material language is
an analogy for the phase maps, not a simulation of a physical sheet. The nearest
existing instrument paths are GLINT's periodic formant sustain and REGISTERS'
speaking onset followed by a measured held region. CREASE retains its own coupled
phase-map memory through the onset and the entire settling trajectory.

| Direction | Gesture and novelty | Risk | Cost |
| --- | --- | --- | --- |
| PORT the existing doubled-HOLD wrapper | Repeating Crease pressure immediately; loses the speaking onset | Low implementation risk, weak articulation | Lowest |
| SPLICE finite onset and separately settled HOLD | Keeps an onset and reuses both existing exports | A smooth audio overlap does not establish continuous deformation memory | Moderate |
| COUPLE onset and periodic source in one circuit | One causal deformation history reaches the measured sustain orbit | Longer settling prefix and generation time | Highest; selected |

The selected path gives the keyboard an onset without resetting the model at an
audio splice. It lands as `CreaseKeys`, the `Keys.creasePad` adapter and
`CreasePadMaker`, then uses the existing MAKE INSTRUMENT and CLI authoring flows.

## Signal path

```text
saved model-3 patch + zone MIDI + saved velocity
    → continuous root/gesture clocks
    → onset pressure smoothly becomes periodic pressure
    → one coupled deformation circuit, starting from zero
    → one continuous band-limit / DC / decimator path
    → actual onset and settling prefix + final converged gesture period
    → one shared level/headroom gain
    → KeyNote(loopStartFrame)
    → phone / MPC loop playback and program RELEASE
```

Source controls morph during 0.18–0.50 seconds for ROUND, 0.24–0.65 seconds
for PINCH, and 0.45–1.15 seconds for RUBBER. The amplitude has a smooth speaking
onset and approaches the existing held level. The pressure source then follows
the existing per-voice periodic gesture. All actual prefix audio is retained.
No audio crossfade is used to hide a state discontinuity or repair a loop.

The rational root clock closes over an integer number of output frames, using
the existing per-voice gesture periods of approximately 1.45, 1.65 and 2.10
seconds. The small tuning adjustment must remain below one cent. After the
source morph, successive boundary snapshots include deformation, reciprocal
load, filters, DC and canonical FIR memory. A final independently evolved period
is exported only after recurrence passes the engine's tolerance.

## Controls and metadata

| Control | Held-instrument meaning | Required evidence |
| --- | --- | --- |
| FOLD | Existing baseline phase geometry | Saved value reaches the same circuit |
| PRESSURE | Existing deformation excursion | Pressure and state remain bounded |
| DRAG | Existing deformation following time | Lag persists through onset and sustain |
| SPLIT | Existing difference between the two layers | Coupled state evolves continuously |
| RELAX | Existing deformation recovery time | Recovery stays in the periodic gesture |
| HOLD | Instrument mode holds the source internally | Saved patch is unchanged; key duration belongs to the player |
| ROOT | Center of the sampled register | Actual MIDI roots, bounded zone coverage and measured pitch |
| VELOCITY | Saved excitation and rendered timbre | Changing zone MIDI does not overwrite saved velocity |
| RELEASE | Phone/MPC volume fade after key-up | Export/import and player note-off preserve its duration |

RELEASE defaults to 0.6 seconds, with a 0.1–1.5 second range. There is no separate
ATTACK knob: the onset is already in the waveform. One rendered velocity layer
retains the saved patch's excitation; live keyboard velocity applies the existing
player gain. This does not claim independently rendered soft/hard timbre layers.

The sampler fades its currently playing loop on note-off. It does not recompute
the phase-map circuit in response to key-up, so a new pressure-release trajectory
after note-off is outside this sample-format contract.

## Instrument packaging

Render roots every minor third across two octaves centered on the saved MIDI
note, clipped to MIDI 24–84. Include the clipped endpoints and center explicitly.
Midpoint keygroup boundaries cover that sampled span without extending the outer
zones by an untested octave. Interior roots produce nine zones; edge roots use
the available part of that span.

`KeyNote` carries a nonzero frame index into the final sustain period. The loop
is `[loopStartFrame, frameCount)`, with no duplicated final frame. The official
package writer supplies native `.xty`, compatibility `.xpm`, WAV loop metadata
and the phone instrument sidecar. Generic playback needs no CREASE registry.
The sidecar retains playback metadata, not an editable synth recipe; the
audition includes a separate native Patch JSON for each source instrument.

## Cost and cancellation

Coefficients and filter plans are computed once for each rendered zone, outside
the sample loop. The sample is the offline cache: package assembly, playback and
note release reuse it and do not run the solver. There is no global unbounded
waveform cache. Changing RELEASE alone does not change the DSP source.

There are at most 28 settling cycles and a 64-second sample-length cap. Reaching
the cap without verified recurrence fails rather than silently cutting a seam.
Cancellation is checked during synthesis and between zones. Zones render
serially in the phone authoring flow so memory and progress stay bounded.

Generation time must be measured for both audition instruments and their zones.
The old audition's desktop timings do not establish phone latency. Whole-keyboard
authoring is an explicit bulk operation; this design does not claim it completes
within the ordinary knob-preview budget of tens of milliseconds.

## Verification and listening ledger

- Verified in source: existing finite/HOLD model dispatch, keygroup loop-to-end
  convention, program release and the dual-format package path.
- Required measurements: pitch, independent audio recurrence, full-state
  recurrence, finite sample/state bounds, onset continuity, generation time,
  exact replay and cancellation. Final results belong in the generated report.
- Regression contract: preserve existing model-1/model-2 golden renders and
  approved model-3 finite/HOLD behavior; new instrument transport is separate.
- Integration proof: export and reload real packages, compare loop frame indices
  and release across formats, and exercise sustain wraps plus note-off in the
  existing player. Run the JVM suite, Android build and test-source compilation.
- Listening: the owner judges the two new held instruments. No automated metric
  establishes musical approval.
- Hardware: a physical phone/MPC performance and phone timing remain unverified
  unless an actual device is used. Emulator results must be named separately.

## Why it is built this way

- One continuous circuit makes the onset and sustain share the same deformation
  history. Resetting it at a splice would change the idea being demonstrated.
- A measured periodic tail lets an MPC hold the note indefinitely while the
  changing pressure remains part of the sound.
- Reusing the sampler's release keeps phone and MPC behavior aligned with the
  sample format they already support.
- Sampling near the saved root limits transposition and keeps the high-register
  phase-map constraints meaningful.
- The longest settling prefix is the main risk. It needs measurements and
  cancellation before any claim about responsive phone authoring.
