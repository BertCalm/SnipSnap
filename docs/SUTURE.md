# SUTURE

SUTURE ports the supplied [engineering specification](SUTURE_SPEC.md) into
SnipSnap as a dry pitched instrument. Its proposed gesture is a bronze bloom,
stretching cords and wooden eyelets, resisted closure, and a murmuring seam.
The material constants describe an imaginary reduced instrument and require
listening; they are not measurements of bronze or a physical prototype.

## Playing and saving

Six voices share one vessel model: BLOOM, THREAD, CLOSE, MURMUR, STRAIN and
SHELL. Each has two initial factory presets. Every exported pad starts with
its own vessel state. Independently played pads do not share cord tension,
opening position or stitch travel.

| Control | Behavior |
| --- | --- |
| TUNE | Requested root, snapped in semitones from C3 to C5 |
| GAP | Opening distance, bending excitation, release energy and changing geometry |
| STITCH | Bounded closure force, speed and linkage compliance |
| CORD | Elastic links, stretched harmonics and sliding eyelet texture |
| SEAM | Edge clearance, contact roughness and dissipative loading; zero disables contact |
| CAVITY | Enclosure scale, resonances, absorption and aperture response |
| HOLD | Finite tail/drive allowance; the top step supplies a powered repeating cycle |

Velocity supplies finite release energy for a one-shot and changes actual
travel and force in a powered HOLD cycle. It is passed through `Velocity`
and is not an extra saved macro. Patches save the voice and macro map with
JSON version 1. DSP model revision 3 does not change that schema: existing
SUTURE recipes retain their controls and requested note and render through
the revised model. `PadRecipe` retains the optional `FxChain`.
`SynthKits.suture()` supplies sixteen dry pads, and FRESH TAPE includes a
SUTURE starter.

SnipSnap's pad sample API has no attack-plus-loop-region field. A held
SUTURE buffer contains settled powered material, omitting the initial spread
and release. A long one-shot can also receive the host's duration-based LOOP
classification; that routing is separate from a certified repeating HOLD
buffer.

## Control mappings from model revision 2

The owner heard distinct voices but weak parameter changes in the first
audition. Analysis of its matched C4 sweeps found that the requested root
typically held 94–99% of spectral energy during the first 1.2 seconds.
Most sound energy decayed before the later closure and seam events. Controls
could therefore change the mechanics and weak upper bands while leaving the
main audible note almost unchanged. Those observations describe revision 1,
not acceptance measurements for the revised renderer.

Revision 2 revises the material and envelope mappings within the same vessel.
The table uses normalized control values and the mean aperture of the two
modeled gaps. Voice weights, modal falloff and geometry still shape the final
response; these coefficients are not final output-gain controls.

| Control | Revised mapping |
| --- | --- |
| GAP | Upper release projection uses `0.18 + 2.20 × GAP`, then the entire projection is normalized to its finite energy allowance. Aperture radiation uses `0.20 + 2.80 × aperture` for answering and bending modes, while the root uses `0.75 + 0.35 × aperture`. BLOOM and STRAIN multiply the primary root pickup by `2.50` to balance their broad inharmonic fields. |
| STITCH | Finite closure targets `0.16 + 1.70 × STITCH` speed and caps force at `0.70 + 6 × STITCH`. Mounting load divides plate decay times: `1 + 3 × STITCH × (1 − aperture)` for the primary root and `1 + 4.50 × STITCH × (1 − aperture)` for other plate modes. A firmer closing linkage passively absorbs more vibration. |
| CORD | Cord pickup uses `0.035 + 1.25 × CORD`, wooden pickup `0.10 + 1.10 × CORD`, and cord decay starts from `0.20 + 1.80 × CORD`. Plate decay also divides by tension load `(0.60 + 1.60 × CORD) / (0.60 + 1.60 × voice default CORD)`, increasing mounting loss as links tighten while keeping each voice's reference loading. |
| SEAM | Clearance uses `0.012 + 0.240 × SEAM`, and contact loss rate uses `8 + 160 × SEAM + 220 × SEAM²`, through gap- and vibration-dependent contact. The primary root's loss projection is `0.10` for finite gestures, `0.05` on MURMUR, and `0.015` in HOLD. Responding fundamentals and upper bending modes retain their stronger contact loading. Zero removes contact. |
| CAVITY | Primary root decay starts from `(1 + 6.20 × CAVITY) / (1 + 0.25 × aperture)`, bending decay from `0.55 + 3.40 × CAVITY`, and cavity decay from `0.10 + 1.60 × CAVITY`, before voice, mode, aperture and plate mounting/tension factors. MURMUR multiplies its primary root decay time by `1.20` to retain a tonal tail under contact. Stronger upper-mode loading and cavity pickup expand enclosure color; the root bridge retains its pitch-scaled bound. |

The answering bronze fundamentals already carry coherent 2×, 3× and 4×
root ratios in the coupled plate network. Their pickup starts from `1.60`
on BLOOM and `0.95` on the other voices, instead of revision 1's `0.48`,
with the same seeded mounting variation. On every voice that answering
pickup also multiplies by `1 + clamp((GAP − 0.40) / 0.60, 0, 1)²`.
The factor stays one through the neutral opening and reaches two at full
opening, progressively exposing the coherent bronze field without supplying
additional acoustic work or changing modal frequencies.
This exposes existing vibration through changing aperture radiation;
the inharmonic bending pickup remains unchanged. The revised SEAM loss
curve broadens its early travel and raises its maximum rate from `163`
to `388`.

Finite note-on release also reaches the preloaded cords. Three modal weights
`0.30 / 0.62 / 0.38` multiply CORD, voice elasticity and an even/odd link
factor `1 / 0.50`, then normalize together with the plate projection into
one shared gesture allowance. The work-limited sliding friction increasingly
projects into upper cord modes. Cord fundamentals retain a fixed `1.0`
stretch factor; upper modes use
`1 + sensitivity × max(0, strain − (0.10 + 0.26 × CORD))`.
Sensitivity is `0.10` except for revision 3's STRAIN calibration below.
The primary reciprocal cord bridge uses angular rate `0.125 + 0.45 × CORD`,
bounding its unloaded per-mode shift below `1.3` cents at C3. Keeping the
harmonic reference fixed and its bridge weak limits near-root phase
interference, which can cancel components and shift their mixed spectral
peak even when their individual frequencies remain close to the root.

TUNE, velocity, HOLD format, voice roster and factory recipe values retain
their existing contracts. Saved recipes keep their settings but can sound
different under the current revision. Compare raw and matched clips of the same inputs
to assess changes in color, timing and decay independently of level.

## STRAIN calibration in model revision 3

The owner heard STRAIN as chaotic across different controls and preferred
the Combined option in the [focused comparison](https://snipsnap-suture-audition.bertcalm.chatgpt.site/strain-review/).
The wider cord response in revision 2 exposed competing fixed and stretched
harmonics, plus strong tones from grain-modulated sliding, as the bronze
decayed. The selected calibration changes two acoustic sensitivities on
STRAIN:

| Sensitivity | Revision 2 | Revision 3 |
| --- | ---: | ---: |
| Upper-cord stretch per unit excess strain | `0.10` | `0.045` |
| Surface-speed grain modulation | `0.56` | `0.12` |

The other five voices retain their previous values. The full elastic strain
still supplies linkage reaction and contact loading; the contact-rate grain
factor remains `0.24`. Opening, carriage travel, finite work reserves,
reciprocal coupling and the fixed cord fundamentals keep their existing laws.
The closing-travel burst remains part of the gesture. This calibration
reduces competing pitch movement and grain tones; it does not uniformly
soften the amplitude envelope.

The selected preview at default C4 reduced the upper pair's separation from
about 27 Hz to 14 Hz during 0.18–0.40 seconds. Its defined 180–210 Hz grain-band
power share fell from 8.29% to about 0.23%. These measurements describe that
capture. The unchanged range and pitch checks remain required across the
broader control matrix.

## Reduced vessel model

The acoustic network has 36 modes: four bronze plates with four modes each,
four cords with three modes each, four short wooden eyelet modes and four
cavity modes. The dominant plate bears the requested root; answering plate
fundamentals use ratios 2, 3 and 4. Upper plate ratios and mounting weights
vary deterministically by voice and requested note.

Modes use normalized displacement and velocity coordinates. Damped rotations
contract their energy norm, reciprocal bridges redistribute it, and implicit
relative-velocity exchanges dissipate it. Geometry changes smoothly alter
the modal coefficients. Changing frequency in these normalized coordinates
does not add a stiffness impulse. The raw network has no feedback limiter or
state-recovery reset.

Two linked gap coordinates run near 2 kHz. A finite note-on spread and release
opens them; a force-limited stitcher closes them. Ringing increases a bounded
dashpot that opposes closing travel, allowing stronger notes to close later.
Finite gestures use a peak-following measured-energy load with a 180 ms
relaxation time: closing resistance is capped at `24` and scales by `1800`.
HOLD uses instantaneous measured acoustic energy, a `650` scale and a `10`
cap. Both paths act only against closing velocity. Ringing load is included
in complete-state and loop-convergence comparisons.
Cord strain follows gap geometry and preload and reacts on the linkage.
The slow linkage receives the positive contact-force envelope through a
20 ms projection, retaining actual catch resistance across mechanical
update rates.
The note-on projection shares a finite energy allowance between plate
bending and preloaded cord modes. A firmer closing mount and tighter cords
increase passive plate loss without introducing another energy source.
An internal take-up coordinate follows the actual powered carriage; its
material displacement and gap-driven sliding traverse the same seeded
surface grain. Mechanical geometry, material position and tension interpolate
on the audio clock. A reduced regularized Stribeck contact supplies friction;
there is no separate elastic bristle state. Sliding drives the same cord and
eyelet modes, while closure changes the cavity's resonances, losses and
reciprocal acoustic loading. Aperture also changes plate radiation, making
the measured closing trajectory audible. Near the
closed position, compliant edge coupling and dissipative relative motion
create seam activity. SEAM zero removes those contacts.

The audio and nonlinear contacts run at 176.4 kHz, then use the shared
band-limited decimator to 44.1 kHz mono. Public renders use
`MELODIC_LOUDNESS_TARGET`; diagnostics retain raw branch levels and separate
stored acoustic energy, passive loss, friction input and actuator work.
These are normalized model quantities: the acoustic energy balance is not
a claim of complete physical energy conservation for the slow mechanism.
Friction debits bounded work reserves supplied by measured opening or stitch
work. Measured work is delivered across each control interval at the audio
rate, and material motion interpolates actual mechanism endpoints. Pending
delivery belongs to the complete state and recorded work reserves. Separate
acoustic-input ledgers identify the finite note gesture and powered closure;
unused reserves relax passively. Stationary cords retain
their passive damping while producing no new motion-driven excitation.
Finite gestures end within the host's six-second ceiling. HOLD supplies
periodic mechanical work to the same vessel and uses bounded preroll with
separate acoustic, gap, cord, coefficient and work-store state comparisons.
Held diagnostics also report mechanical work and acoustic input per cycle.
The loop is captured after its complete state settles, with periodic guards
on both sides of the shared decimator; the renderer does not crossfade or
reset the mechanism at the cut.
Held voices begin at a shared late-opening point in the settled cycle.
Carriage and take-up use the same phase offset, giving the host pitch
detector a clearer starting window while retaining the later cord chirps
and the same repeating material. The root-bearing cavity link has a bounded,
pitch-scaled footprint; upper cavity modes retain their stronger loading.

## Verification

Revision 3 verification completed on 2026-10-15 for `Suture.kt` SHA-256
`3fec28599401f9d475947dae1a9effd6086017ad1c8e7ed455da5d433de91319`.
The focused engine/patch gate freshly executed all 31 tests with exit 0.
The complete SDK-free `./gradlew --no-daemon test` exited 0: 4,786 tests
freshly executed and 148 prior/cached results, with no failures, errors or
skips. Its build continued through session disconnects; a detached observer
captured the original wrapper's actual kernel exit status and retained that
recovery provenance. Android `./gradlew --no-daemon :app:assembleDebug`
also exited 0 and produced the debug APK. Both gates retained the same
frozen source, tests, build inputs and fixtures. Final documentation updates
were recorded separately. Android compilation and packaging do not establish
device audio, timing or Compose behavior.

The added range check covers all six voices and five timbral controls at
C4 with strong finite gesture energy and neutral companions. Its 30 endpoint
comparisons and 120 adjacent quarter-step comparisons use fixed targets.
Endpoints require spectral-energy L1 of at least `0.16` or envelope-energy
L1 of at least `0.45`; each quarter requires at least `0.02` or `0.06`
respectively. Both spectral windows and 50 ms envelope bins operate on the
finished, unnormalized 44.1 kHz output. Independently normalizing their energy
prevents a gain-only change from satisfying these checks. The same 150 C4
renders must retain the house pitch detector's 50-cent root limit.
The 120 public-render endpoint cases cover all six voices,
five timbral controls and their zero/one endpoints at C3 and C5 with neutral companions:
the requested-root FFT must stay within 10 cents and the house detector
within 50 cents. Existing requested-root, public-routing, passive-energy and
convergence limits remain unchanged. These descriptors
establish measurable travel, while the owner determines whether the range
sounds useful.

The selected STRAIN C3 sound changes one short-capture classification.
Its 0.85-second dry observation is now `TOM`: cleaning up the cord sound
reduces its measured peak-to−20 dB decay from about 580 ms to 464 ms,
crossing the generic classifier's 500 ms sustained-bass boundary. Extending
the observation to 1.49 seconds gives the same result. This is a known
classification ambiguity in a short crop; the public full export remains
`LOOP`. The first focused gate recorded this change as a failure. The
revised check explicitly expects `TOM` only for STRAIN at C3 and additionally
requires short-crop pitch detection within 50 cents, confidence at least
`0.8`, and root spectral share at least `0.45`. All other short-capture drum
exclusions and all public-export routing and pitch checks remain in place.

Two STRAIN spectrum checks retain the selected voicing at default C4.
Over the first 1.2 seconds, energy from 180–240 Hz must stay below `0.06`
of the requested-root band's energy. From 0.40–0.75 seconds, the sounding
upper peak must stay 20–50 cents above the second harmonic while its band
retains at least 5% of measured spectral energy. Both use the finished raw
signal and Hann windows, so turning down the whole output cannot satisfy
them. Restoring either acoustic sensitivity alone fails its corresponding
guard in an isolated source-revert probe.

The historical before/after analysis pairs all 150 preserved revision 1 sweep recipes
with final revision 2 at C4, strong gesture energy and HOLD off. Whole-note
total variation is half the L1 distance between independently normalized
energy distributions, so a gain change cannot satisfy it. Each table entry
spans the smallest and largest zero/one endpoint change across the six voices.

| Control | Spectral energy redistributed, revision 1 → 2 | 50 ms envelope energy redistributed, revision 1 → 2 |
| --- | ---: | ---: |
| GAP | 10.4–18.5% → 12.5–28.8% | 35.4–38.5% → 11.5–14.9% |
| STITCH | 1.5–3.0% → 3.7–11.9% | 14.9–15.7% → 32.2–38.1% |
| CORD | 1.5–4.2% → 7.8–28.1% | 7.8–17.4% → 35.0–46.8% |
| SEAM | 3.7–8.3% → 1.7–6.7% | 1.2–2.5% → 22.1–48.5% |
| CAVITY | 1.0–2.0% → 3.1–13.4% | 1.5–5.9% → 54.0–59.1% |

STITCH, CORD and CAVITY widen in both measures on every voice. SEAM gains
timing range on every voice; its whole-note color response narrows on BLOOM,
CLOSE and STRAIN. GAP color expands on THREAD, CLOSE, MURMUR and SHELL,
stays near its prior range on BLOOM, and narrows on STRAIN to retain the
requested root. Its delayed-bloom envelope range is narrower on every voice.
These whole-note descriptors are less sensitive to narrow partial movement
than the fixed Hann-window gate and need not give the same numerical values.
Passing that gate establishes activity, not a wider range for every control.
Neither descriptor establishes audibility.
The table records revision 2's range expansion; STRAIN's revision 3 voicing
is described separately above.

The checks cover requested roots, matched-level macro activity, passive
acoustic balance, bounded powered input, finite tails, deterministic renders,
saved recipes and routing. HOLD checks compare the complete acoustic,
mechanical, coefficient and work-store state, alongside the audible seam.
Mechanical-clock and audio-oversampling comparisons check that stronger
contact branches do not reveal numerical cadence or unresolved contacts.

The original approximately 1 kHz mechanical rate did not meet the sound
convergence limits. Production therefore uses 88 oversampled ticks per
mechanical update (about 2 kHz); the convergence tests compare 4 kHz and
8 kHz with the same acceptance thresholds. These diagnostics cover
normalized acoustic energy, not the total physical energy of the slow mechanism.

Physical convergence uses the complete-state and seam limits independently
of signal level. A smooth, low-motion HOLD can be very quiet while still
forming a valid cycle; shared loudness processing leaves signals below its
threshold unamplified. Normal and factory acceptance checks separately
retain their audibility, pitch and powered-work requirements.

## Audition

The [hosted SUTURE audition](https://snipsnap-suture-audition.bertcalm.chatgpt.site)
is owner-only. Revision 3 is published in the main audition and
[first listen](https://snipsnap-suture-audition.bertcalm.chatgpt.site/first-listen/).
The main audition compares revision 2 and revision 3 at identical sweep
recipes. The [focused STRAIN review](https://snipsnap-suture-audition.bertcalm.chatgpt.site/strain-review/)
defaults to Combined and labels the earlier voice Before; all four original
comparison recordings remain unchanged. The verified local packs are also
installed at the canonical `testkit` paths, with the earlier packs preserved.
Audio remains lossless mono PCM-24 at 44.1 kHz, transported as seek-indexed
FLAC; hosted diagnostic CSVs use deterministic gzip. The published bundle
contains 986 canonical FLACs, 112 exact gzip traces and 144 preserved review
FLACs. Static and portable Chromium checks passed for playback, comparison,
position preservation, seeking, held-buffer wrapping, notes and mobile layout
against those exact bytes. Native Sites deployment succeeded from Site source
commit `7ea1aa3a3cab9f4366add60c29df79ce7ed02026`, preserving the existing
audience. Authenticated production playback and Android device performance
are separate from these local browser and compilation checks.

```sh
./gradlew --no-daemon test
./gradlew :synth:generateSutureAudition
./gradlew :synth:generateSutureAudition -PsutureFirstListen
./gradlew :synth:generateSutureAudition -PsutureSections=SWEEPS -PsuturePrevious=/path/to/preserved/suture-audition
./gradlew :cli:run --args='synth SUTURE BLOOM --all --out /tmp/suture-bloom'
```

Open `testkit/suture-audition/index.html`, or the smaller first-listen page
at `testkit/suture-first-listen/index.html`. The generator supplies raw and
matched-loudness clips, voice/register/velocity comparisons, five-step macro
sweeps with neutral companions, the five specified interaction grids,
isolated branches, disabled mechanisms, passive tails and difficult held
cases. Generated audio and pages are ignored by Git and can be regenerated.
The manifest identifies the DSP source by SHA-256 and records JVM render
cost and memory observations. Those measurements describe the generator,
not Android device performance.

The generation matrix contains 325 full-gallery listening cases from 320
renders: 650 current clips and 300 preserved comparison sweep clips, with
112 mechanical CSV traces. First listen selects 18 cases and 36 clips.
Revision 3 export validation passed: all 150 paired recipes match their
inputs and all 300 revision 2 comparison WAVs retain their original bytes.
The selected Combined reproduction matches 48 capture/trace/recipe files,
six final-state records and 36 preview WAVs exactly. All 546 current WAVs
and 85 mechanical CSVs from the other five voices are unchanged. All 26
held captures converged without quiet-output flags; their largest complete
state error was `0.00074304` and largest normalized wrap step `0.000040524`.
These numerical and browser checks do not replace listening judgments.

The focused parameter panel keeps the voice and companion controls fixed
while playing 0, .25, .5, .75 and 1. It can play the complete range or alternate
its endpoints, with RAW or MATCHED selected. Choose each of GAP, STITCH,
CORD, SEAM and CAVITY to judge the range within a voice.

Preserve a previous full audition in a separate directory before generating
new clips into `testkit/suture-audition`. Passing `-PsuturePrevious` copies
its unchanged sweep audio into the new pack only when case ID, voice,
controls, velocity, loop mode and diagnostic settings match the current
input recipe. The generator records
both source hashes. The panel then offers Previous and Current versions
and a Previous → current comparison at the selected parameter step.
This compares the same settings across DSP revisions; it does not
substitute newly rendered clips for the earlier evidence. Earlier notes stay
associated with their original listening round; revised sources or recipes
open a fresh round. Parameter-range notes also identify the DSP hash.

The first-listen task selects all twelve factory presets and six neutral C4
voices, each with raw and matched versions. Generated manifests report
actual case counts, held convergence, peaks, costs and optional raw
audibility flags for that export. Low model output can be physically
converged while still falling below the shared loudness processor's
amplification threshold.

The owner selected Combined for STRAIN. Continue listening for one connected object:
the bronze pulls the cords, eyelets answer actual travel, vibration delays
the stitchers, and closing changes the cavity and seam. Numerical evidence
cannot establish that identity or exclude an audible change in motion at
the wrap. Names and preset settings remain provisional.

## Deferred scope

The reduced model does not include full plate deformation, literal knots,
tearing, physical robotics, stereo propagation, shared phrase state or
external recordings.
The diagnostic surface stays outside the product macros.
