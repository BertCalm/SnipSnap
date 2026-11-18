# FOOTWORK brush and movement reset

The owner rejects the native brushes as unnatural and describes everything as
stiff/mechanical. Model 4 is not sound-approved. Passing delivery checks do not
resolve this feedback. Base read: `1523fa02b19227db9b04c536751d083f66b612fe`.

## Why it is built this way

- Prior brushes use stationary noise under a fixed swell and a bright, shifted
  toe filter. This experiment excites a response closer to the contact spectrum
  with finite signed microcontact pulses and quieter rubbing noise. This is an
  authored contact analogy, not calibrated friction physics.
- Surface irregularities are placed along modeled travel distance. Their times
  follow an asymmetric speed curve, so acceleration/deceleration changes their
  spacing. Pressure varies through entry, loading and unloading. No recording
  PCM is played back or stored in this experiment.
- The phrase also has small shared timing, force and travel changes, rather than
  independent jitter on every event. These are authored curves; they do not prove
  human coordination or recreate the supplied dancers. Mechanical feel remains
  an explicit listening risk.
- Three equal-energy comparisons isolate new brush at old timing, then new brush
  with connected timing/force, against the previous Python source. Total energy
  is matched with a shared headroom gain, not perceptual loudness calibration.
- Toe/heel use the preceding Python contact renderer. The new brush study stays
  outside the native kit until the source and gesture are convincing to the owner.

Forty-eight Python cells cover forward/return brushes, shuffle and phrase at
three strengths and four repeats. Checks cover deterministic rerender, increasing
velocity energy, distinct repeats, zero velocity, finite output, DC/tail and
PCM16 save/read error below 5e-5. Peak is 0.623. New native rendering, native save/
playback/export and browser decoding are not established by these source checks.

All existing native kit, source identity, three-kit distinction and hardware gates
remain open, including the MPC 3 chain-WAV slice-map blocker. This study is a new
listening hypothesis. The native model-4 downloads are retained as rejected
candidates, clearly marked on the page.

```sh
python3 prototype/footwork/brush_motion/build.py /tmp/footwork-brush-motion
```

The private audition offers isolated strokes, all brush dynamics/repeats, and
the three-way source/movement comparison for shuffle and phrase. Native model
versions are unchanged. Older page audio remains available; one superseded long
dynamics strip is archived locally if needed for hosting capacity.
