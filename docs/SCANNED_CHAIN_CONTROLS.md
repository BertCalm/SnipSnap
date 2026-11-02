# Scanned Chain: shape the note

The control pass makes the existing instrument easier to shape. It exposes the
saved attack and release settings and gives gentle damping and short envelope
times more room on a slider. The mass-and-spring model, physical ranges,
factory shapes and nine existing presets retain their definitions.

The characteristic gesture remains: a saved shape speaks at the chosen note,
its shape moves, optional force develops a later body, and the finite sample
ends under its saved envelope.

## What each control does

| Control | What to listen for | Useful interaction |
| --- | --- | --- |
| MOTION | Movement within the pitched sound. Zero holds the initial shape still; full travel uses the voice's existing mechanical speed. | At zero, DAMP, BEND and BLOOM cannot change the frozen shape. The scanner still plays the chosen note. |
| DAMP | How quickly the shape's movement settles. This removes mechanical velocity rather than imposing an ordinary volume decay. | More MOTION also advances the damping clock faster. Try gentle damping before deciding that a long sound needs more release. |
| BEND | A change in how the moving shape develops, from ordinary springs to bounded spring hardening. | Its effect depends on source shape and MOTION. It does not bend the scanner's requested note. |
| BLOOM | A later body emerging after the strike. The force follows the source's scale. | It needs physical movement and time to develop. A long release on a short sample can leave a very short force window. |
| ATTACK | A sharper onset or a slower entrance. | It fades in the rendered result while the physical simulation already moves. A slow attack can reveal a later state of the shape. |
| RELEASE | An ending that stays present longer or fades across more of the finite note. | Increasing it starts the ending fade earlier. It also shortens BLOOM's existing force window. LENGTH remains the total sample duration. |

ATTACK and RELEASE were already part of complete pad recipes and factory
presets. They are now editable directly. They are non-macro envelope settings;
the four physical macros remain MOTION, DAMP, BEND and BLOOM.

## Slider travel and saved values

Slider travel is an editor choice. Saved patches continue to carry the same
physical macro values and envelope times. The renderer receives those saved
values exactly as before.

| Slider travel | DAMP macro | Damping at full MOTION | Attack time | Release time |
| --- | --- | --- | --- | --- |
| 0% | 0 | 0 per second | 0 ms | 0 ms |
| 25% | 0.0625 | 0.375 per second | 62.5 ms | 125 ms |
| 50% | 0.25 | 1.5 per second | 250 ms | 500 ms |
| 75% | 0.5625 | 3.375 per second | 562.5 ms | 1125 ms |
| 100% | 1 | 6 per second | 1000 ms | 2000 ms |

The laws are `DAMP = travel²`, `attackSeconds = travel²`, and
`releaseSeconds = 2 × travel²`. MOTION, BEND and BLOOM retain linear travel.
This gives more physical travel to the lower part of DAMP's existing range and
to short envelope times. It does not increase the force, spring stiffness,
damping ceiling or maximum time.

Merely opening a pad, displaying a slider, or moving between views does not apply
a conversion back into its patch. The inverse is used only to display a saved
value; a user gesture supplies a new value. No saved model or recipe version
changes. Recalled model-one patches also retain their render model until the
owner explicitly chooses **USE UPDATED CONTROLS**.

## Envelope and BLOOM timing

Attack and release are smooth fades over the same finite sample. Attack starts
at the beginning. Release counts backward from the end. They can overlap; the
result is their product. Existing times longer than a short sample remain
valid and are preserved on recall.

BLOOM's force uses the existing window
`clamp(length − release, 0.05 s, 2 s)`. This is a force window, rather than a
promise that an audible swell ends at that time. At MOTION zero the physical
shape is frozen, including the force response. At low MOTION the force is
weaker as well as slower because its scale contains MOTION squared.

For example, a 0.8-second sample with a 0.65-second release leaves a
0.15-second force window. A three-second sample with a 0.4-second release uses
the two-second maximum window. Changing BLOOM alone in these two contexts is
therefore a different musical experiment.

## What the preliminary renders show

A numerical study of 63 factory-context production renders found finite output
throughout, with sample peaks from 0.247 to 0.508 and maximum absolute DC below
`2.2e-9`. It used previously compiled model-two production classes; all eight
Scanned Chain source files were byte-identical to the current base, and the
retained provenance records the dependency differences. This was a control
study, not a fresh build or phone-performance measurement.

BLOOM's largest changes arrive later in the note. With the factory BLOOM
context, moving the physical value from 0 to 0.25 left a scalar-matched
waveform residual of 0.020 in the 0.1–0.5 second window, 0.620 at 0.8–1.4
seconds, and 0.813 at 1.4–2.0 seconds. Moving from 0.75 to 1 left smaller
residuals of 0.020, 0.096 and 0.077 in the same windows. This residual removes
one best-fit gain; it remains sensitive to changing phase as well as spectrum.
It is a numerical difference measure, not a percentage of audible change.

These measurements support listening to BLOOM after the strike and show that
its response is concentrated toward the lower part of the range. BLOOM keeps
linear travel in this pass so the new listening study can establish a
preference before another calibration is introduced. BEND already changes
low and middle settings, so it also keeps its current travel.

DAMP did not produce a simple monotonic volume decay. It changes the moving
shape's settling and spectral balance; some measured body windows grew before
falling as damping increased. Its squared travel provides more hand movement
for gentle damping without promising that greater DAMP always means a quieter
sample.

## Reproduce the listening study

```sh
./gradlew --no-daemon :shell:generateScannedChainControlsAudition \
  -PscannedChainControlsOutput=/tmp/scanned-chain-controls
```

The generator renders the actual production engine at slider travel
0%, 25%, 50%, 75% and 100% for each of the six controls. It writes 30 native
mono 44.1 kHz PCM24 WAVs, 30 RMS-matched copies, a complete `PadRecipe` for
every step, and a manifest with exact settings, file hashes and measurements.

Within each group, only the named audible control changes; the patch's display
name identifies the recording step. Every note is C4 at velocity
0.8 for three seconds, with the saved seed and factory source shape fixed.
MOTION, DAMP, ATTACK and RELEASE use PLUCK; BEND uses METAL; BLOOM uses its
factory BLOOM source. Contexts differ between groups. The default comparison
reference is the 50% travel step, except BLOOM uses its force-off step at 0%.
The complete contexts are in the manifest, rather than being inferred from
the labels.

**Native** means the production renderer's ordinary finished audio, including
its existing output gain. It does not mean solver samples before finishing.
**RMS-matched** applies one additional constant gain to the whole clip, aiming
at whole-clip unweighted RMS 0.12 with a sample-peak ceiling of 0.90. The applied
gain and whether the peak ceiling prevented the target are recorded per clip.
This numerical matching does not establish equal perceived loudness and does
not change the envelope inside a clip.

The generator checks that each exported recipe decodes to the same patch and
rebuilds bit-identical Float PCM after a different completed render evicts the
one-entry audio cache. It also reads back every PCM24 WAV, checks quantization
error, and records finite samples, peak, DC, RMS and two 250 ms pitch-detection
windows. Weak or missing detector evidence is reported as such. Hashes of the
renderer, editor helper and generator sources identify the implementation used
and are checked again after generation.

## Why it is built this way

- Envelope editing exposes behavior already present in the accepted instrument,
  including the shorter pluck and slower bloom preset gestures.
- Editor travel is separate from saved physics so a recalled sound can keep its
  exact recipe and audio while the controls become easier to move.
- Damping gets more room near gentle settings because its physical attenuation
  is exponential; the upper range stays available.
- One changed field per group makes the comparison interpretable. The matched
  copy is an additional numerical view, while native audio preserves delivery.
- The existing spring law and presets remain stable. Changing their musical
  identity needs listening evidence beyond the choice of a slider curve.

## Verification ledger

- **Verified in source:** the two envelope settings already belong to complete
  recipes; editor conversions preserve the existing physical ranges; the
  generator renders model two and changes one field per group.
- **Measured by the generator:** per-clip output levels, two pitch windows,
  complete recipe replay, WAV quantization error and file/source hashes. The
  exported manifest is the measurement record.
- **Proposed:** squared travel gives more useful hand movement for gentle
  damping and short envelopes. Numerical differences do not prove that it is
  the preferred musical calibration.
- **Heard:** the owner reports that the existing hardware workflow is fine.
  No listening verdict for this new control study has been recorded.
