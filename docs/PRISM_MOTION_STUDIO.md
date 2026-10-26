# PRISM motion studio

Repository baseline: `fd791f583579dda3dcab3d2ea9e34f9975b07a12`.
Status: implementation and coordinated verification in progress. Phone and
physical MPC interaction remain separate checks; no new listening approval is claimed.

The characteristic gesture is **lower body → upper colour → return to body → repeat**.
OPEN keeps the accepted **body → colour → finite release** gesture.
This is amplitude movement between two saved spectra, with a stable carrier pitch.
It does not simulate orbital mechanics or reconstruct a donor's original articulation.

## Controls and saved sound

The main six macros plus HOLD stay in place. An expandable MOTION panel adds
recipe editing without adding another row of permanent synthesis macros.

| Control | Default | What it changes |
| --- | --- | --- |
| Amount | 100% | Travel from the saved starting blend toward B (`evolve`). Zero stays at the starting blend. |
| Time | 0.8 s on a newly enabled opening | One-way travel in OPEN; a complete A→B→A cycle in REPEAT. Range 0.1–6 s. |
| OPEN / REPEAT | OPEN | Finite smoothstep opening or a smooth, periodic raised-cosine trajectory. |
| Source | Current saved tone | Select a named factory source, prepare its spectral endpoint once, and derive the lower-body opening from it. |
| Source / Static / Moving preview | Current recipe | Hear the original source, destination-only PRISM recipe, or the edited movement. Previewing does not replace the working recipe. |

Enabling motion on a static patch derives B from its original unmasked template
and A from the accepted body mask. Existing motion recipes retain their authored
A/B, start position, travel amount, group lag and duration until edited. The four
factory openings retain their original recipes and 0.8-second OPEN gesture.

Source choices cover the eight sounds from the earlier palette: two electric-key
tones, wood and paired wire, soft breath and bowed friction, and two saturated
bass tones. These are source choices, not eight newly approved factory presets.
Changing source retains the current PRISM note, velocity, carrier template and
motion controls. Both endpoint arrays are stored in the saved recipe; future
rendering does not need the donor or its analysis cache.

The optional `sourceId` enables an original-source preview. Unknown IDs leave
the saved instrument playable because the coefficients belong to the recipe.
Source identity is descriptive; it does not affect phase seeding or audio.

## Signal flow and cost

```text
factory source recipe ──render once──▶ spectral envelope analysis
                                         │
                              [cache: complete source JSON + 32 carrier Hz]
                                         │
                                saved B + derived body A
                                         │
note + velocity + template + macros ──▶ shared carrier bank
                                         │
                  OPEN or periodic A/B weights × five group contours
                                         │
                   4× synthesis → band-limit → decimate → shared output stage
```

The source cache holds at most sixteen coefficient arrays and no donor PCM.
Its interruptible lock prevents duplicate simultaneous preparation. Analysis
runs off the UI thread; amount/time edits never rerender or re-analyze a donor.
Late source completions transfer only their endpoint data into the latest
working recipe, preserving edits made while preparation was running.

The analyzer supplies broad fixed-Hz colour from 1024-point windows. It does
not accurately recover low-register harmonic detail, friction, attack, beating
or the donor's complete sound. Once saved, changing TUNE transposes the measured
coefficients with the PRISM note rather than running another donor analysis.

JVM render and source-preparation costs are recorded in the generated audition.
They are not measurements of Android response. Preview jobs are cancellable;
phone responsiveness still needs a device check, particularly at long cycle times.

## True repeating HOLD

`PrismMotion.repeat` is opt-in. Missing/false keeps the old finite arithmetic
and JSON shape. OPEN continues to keep HOLD below the loop threshold. REPEAT
allows the full HOLD range; below the threshold it repeats inside a finite
note's existing attack and release. At the threshold it exports a held loop.

Repeating motion uses `0.5 - 0.5*cos(2*pi*phase)` with the existing start blend,
amount and group offset. Held rendering fits whole carrier periods to integer
output frames while moving the root by less than 0.1 cent. Dyadic ratios in
factory glass and compatible custom templates retain their overtone spacing.
Other custom inharmonic ratios use the existing nearest-cycle-bin held policy.
Closely spaced arbitrary custom ratios may therefore converge to the same bin.

The audible cycle can differ from the requested time when pitch and spacing
need a longer compatible period. `Prism.motionCycleSeconds` reports the fitted
duration without rendering audio, and the editor displays it for held repeats.

Fast HOLDs export enough genuinely synthesized complete motion cycles to exceed
1.5 seconds. This keeps the sample in the ordinary loop-classification region
on re-import. The exported file length and the colour-cycle duration are separate
quantities. Carrier, group-envelope and spectral-motion clocks all close over
the exported period; control blocks split at the relevant boundaries.

Rendering includes a bounded preroll, an independently generated preceding
export cycle, the kept export cycle, and postroll for filtering. The same DC
offset and output gain apply to both evidence and kept cycles. Their measured
seam must pass `Keys.MAX_SEAM_ERROR`; repeating the kept buffer is not the seam
proof. No end-to-start crossfade disguises a nonperiodic trajectory.

## Verification and listening

```text
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.Prism*'
./gradlew --no-daemon :synth:generatePrismMotionStudioAudition
```

The bounded studio matrix contains 28 source, static, OPEN, finite REPEAT,
held REPEAT, amount and time comparisons. Six HOLD cases also provide eight
consecutive exported loops. Native and gain-matched audio have complete recipes,
hashes, replay checks, fitted-cycle measurements and preceding-cycle evidence.

Compatibility tests compare finite openings and ordinary finite/held sounds
with an independent frozen copy of the baseline renderer on the same JVM.
Platform-specific floating-point audio hashes are not treated as universal.
Workflow tests cover save, bin restore, drafts, kit reopen, replay and factory
promotion with repeating state and source identity intact.

## Why it is built this way

- Saving the two spectra keeps a chosen sound stable when the source bank changes.
- The source picker borrows colour while PRISM keeps responsibility for pitch and articulation.
- A separate detail panel makes movement editable while keeping the familiar main controls.
- A loop shares one closing clock across its states, so returning to the body does not create a click.
- Longer exported files can carry fast colour cycles while retaining reliable loop classification.
