# ORIGAMI — native spectral synth

ORIGAMI adapts XO-Origami's spectral-paper concept into SnipSnap's deterministic
offline synth pipeline. Generated partials are reflected, translated and warped
relative to the played root. Their phases continue through those changes; a
separate fundamental anchors the note. This is frequency relocation,
not time-domain waveshaping or a bank of spectral amplitude masks.

The first milestone is a dry audition of three finite voices. CRANE stays as
first heard. Model 2 reduced FAN/STILL's bright and crowded spectra, but feedback
also found their unfolded sources harsh. Model 3 established the softer source
palette the owner preferred. Model 4 preserves those sources and refines their
folded color. The owner accepted this warmer direction for the native integration.

| Voice | Source and listening question |
| --- | --- |
| CRANE | Struck harmonic material. Does the reflection create a useful metallic key without losing the attack or note? |
| FAN | Rounded sustained harmonic material with a clear fundamental and restrained upper body. Is the source useful before folding, and does folding add a useful color? |
| STILL | A soft harmonic body with sparse, quiet detuned companions and a gradual attack. Does it stay gentle before and after folding? This source is not broadband noise. |

## Listen

```sh
./gradlew --no-daemon :synth:generateOrigamiAudition
./gradlew --no-daemon :synth:test --tests '*Origami*'
```

The generator writes `testkit/origami-audition/index.html`, dry raw/matched WAVs,
recipes and `manifest.json`. Open the page directly or through a local web
server. The page embeds its manifest and uses the browser's audio player,
started by an explicit play action, for desktop, mobile and embedded playback.

For a self-contained page with its audio embedded:

```sh
./gradlew --no-daemon :synth:generateOrigamiAudition \
  -PorigamiEmbedAudio
```

This also writes `start-here.html`, a compact standalone page containing six C4
references. `portable.html` contains the complete listening matrix.

To include the previous/revised comparisons, use the archived model-3 published
pack, including its original manifest, source snapshots and WAVs:

```sh
./gradlew --no-daemon :synth:generateOrigamiAudition \
  -PorigamiBaseline=/absolute/path/to/origami-audition-r3 \
  -PorigamiEmbedAudio
```

The comparison page opens on revised FAN's folded sound at C4. Previous model-3
and revised model-4 FAN/STILL folds use the same accepted source, note and controls,
with matched playback enabled by default. Source + fold lets the listener compare
the unchanged source with its transformed version. The full pack also includes
C3/C5 before/after comparisons; all 70 current musical cases remain available.
Original audio is copied unchanged from the pinned
archive; all 24 CRANE cases are checked against the original waveform and both
raw/matched WAV hashes. All nine SOURCE cases also require identical waveforms
and raw/matched WAVs. The compact page contains the six current C4 source/fold
references, one pair for each voice, and opens on FAN's folded sound.

Start with the unfolded/folded pairs, then compare CREASE and FOLD sweeps with
MOTION stopped. Use the separate root/body taps to judge whether the anchor
merely hides a weak or unusable transformed spectrum. Diagnostic taps keep the
combined reference's gain; they are not individually boosted to full loudness.

Matched clips target .12 on SnipSnap's loudest-200-ms
perceived-level meter, with a .90 sample-peak ceiling. The manifest retains
pre-gain measurements and actual matching gains. Numerical evidence cannot
establish the perceived pitch or musical quality of an inharmonic sound.

## Controls and saved recipes

| Control | Role |
| --- | --- |
| TUNE | Semitone-stepped C3–C5 when no explicit MIDI note is supplied |
| CREASE | Reflection position relative to the root |
| FOLD | Unchanged source to the geometrically transformed upper spectrum |
| TURN | Upper-partial translation; .5 is neutral |
| WARP | Upper-partial spacing; .5 is neutral |
| MOTION | Axis movement in STEADY; fast-to-slow gesture duration in BLOOM/UNFOLD |

FOLD zero bypasses the geometry exactly, including TURN, WARP and MOTION.
TOUCH expression can still reduce the upper-bank balance at zero FOLD; the
source frequencies and phases stay the same.
At intermediate FOLD values, the original upper spectrum and the transformed
upper spectrum share a linear amplitude budget; partial frequencies do not
slide toward one another as a side effect of the depth control.
Source ratios and start phases depend on the voice/model/seed, not the macros,
so comparisons do not substitute a different source. Crease control scaling
and the coordinate system used for reflection are distinct: the actual fold
is linear in root-relative frequency. Out-of-band contributions are rejected
with a transition instead of wrapping around Nyquist into the bass.

`OrigamiPatch` saves voice, macros, optional absolute MIDI note, velocity,
64-bit seed and model version. An explicit MIDI note overrides TUNE. The seed
uses decimal JSON text to preserve all its bits. `Patches`, `PadRecipe` and
the existing velocity adapter recognize the patch; recipes can regenerate
their generated source without an external recording.

New patches use model 4. Explicit models 1, 2 and 3 replay their frozen
renderers; older patches without a model field select model 1. Saved sounds keep
their original audio. In STEADY, CRANE delegates to the first renderer in every
model; explicit model-4 gestures use the new renderer with the same source.

## Fold gestures

Model-4 patches offer STEADY, BLOOM and UNFOLD above the six controls.
STEADY keeps the accepted sound and the original MOTION axis movement. BLOOM
starts with the open source and folds into the selected color; UNFOLD starts
folded and opens back into the source. FOLD sets the maximum reflected share.
In the two new modes, MOTION sets gesture time from fast to slow, with the
duration shown in seconds. The gesture plays once; it does not also run the
axis LFO. Switching modes keeps all numeric controls.

The change is a complementary amplitude contour between the source and its
actual relocated partials, before audio filtering. Their frequencies stay fixed
at the selected geometry, and the separate root keeps the played pitch. The
accepted amplitude attack and finite note length remain independent of this
color change. STILL starts its gesture later so its soft attack can expose it.

The new gesture has its own version 1, stored only when BLOOM or UNFOLD is
selected. An omitted gesture means STEADY, preserving existing JSON and audio.
Models 1–3 keep their original motion and do not accept the new modes. Loading
a current factory preset makes gestures available without changing an old saved
recipe. The six original reference presets remain STEADY until deliberately edited.

FAN/STILL MAKE INSTRUMENT keeps the complete gesture before the sustain loop.
The settled body then repeats without restarting the gesture. RELEASE still
controls the fade after key-up. CRANE uses the new gestures in finite notes.

```sh
snipsnap synth ORIGAMI FAN --gesture BLOOM --macros FOLD=.75,MOTION=.65 --out /path/to/notes
snipsnap synth ORIGAMI STILL --gesture UNFOLD --macros MOTION=.65 --instrument --release .6 --out /path/to/instruments
./gradlew --no-daemon :shell:generateOrigamiGestureAudition
```

The audition writes a self-contained nine-clip `start-here.html` and a fuller
listening pack under `testkit/origami-gestures`. New sound acceptance is a
listening decision. The design and its verification ledger are in the
[fold-gesture brief](superpowers/specs/2026-10-23-origami-fold-gestures.md).

## Velocity expression

Model-4 sounds offer **VELOCITY: LEVEL / FOLD / TOUCH** beside GESTURE.
LEVEL keeps the original volume response. FOLD retains the published law:
softer hits use less of the selected folded color, with pitch, source-bank
balance and movement timing unchanged. TOUCH keeps that depth law and also
reduces the complete upper bank on softer hits, including its strong octave.
Here, upper bank means every non-anchor source/reflected component, even a
reflection below the root; this is not a low-pass sweep. The separate raw
fundamental is unchanged. This balance remains different
when UNFOLD has returned to zero FOLD, so touch can affect the settled source
as well as the moving fold. No partial frequency, start phase, gesture time
or sustain-loop position changes, and hard hits add no brighter material.

For actual performed velocity `v`, TOUCH uses:

```text
depthScale = .25 + .75 * v^1.25       (the existing FOLD law)
upperScale = .1 + .9 * clamp(v / .85, 0, 1)^2
raw note = root + upperScale * upper bank, then one normal output level stage
```

At `v >= .85`, upperScale is exactly one and TOUCH delegates to the unchanged
FOLD rendering. This preserves the factory hard sound at .85 and the old FOLD
sound at 1. The curve uses absolute velocity: a saved ceiling below .85 may
never reach unity at its own FULL setting. It is not rescaled to make every
saved ceiling equally bright. The six factory ceilings remain .85, making
SOFT/MID/FULL actual velocities .255/.510/.850 and upper scales .181/.424/1.

In SYNTH, SOFT/MID/FULL select temporary relative strengths .3/.6/1 beneath
the saved note's velocity. **They render quietly. Tap AUDITION to hear the
selection.** If rendering is still busy, AUDITION queues the latest result.
Changing touch or controls does not itself start playback. Preview strength
does not change the saved ceiling, preset, navigation recipe or SEND reference.
In BLOOM the selected fold depth is the arrival color; in UNFOLD it is the
starting color. STEADY retains its established axis movement. No macro is added.

For ordinary pads, SEND writes the reference note and complete recipe. Apply
**SOFT HITS** to that pad for per-hit color; SEND does not create those layers
automatically. Softer renders remain below the saved main velocity and are
peak-matched before player gain, keeping a quiet reference from acquiring
harder soft samples. Existing processed-pad fallback behavior is unchanged.

FAN/STILL MAKE INSTRUMENT writes three touch layers per sampled root for
FOLD or TOUCH. Soft/mid/full occupy MIDI velocities 1–42, 43–84 and 85–127.
Each sample keeps its own attack and loop; the player supplies velocity gain
once and the chosen key-up release. The instrument sheet's PREVIEW plays all
three touches. LEVEL keeps nine samples; FOLD/TOUCH use 27, with additional
preparation time, storage and decoded sample memory.

Expression remains an optional independent model-1 object, with a new explicit
`TOUCH` mode alongside unchanged `LEVEL` and `FOLD`. Missing or null expression
means LEVEL. Existing LEVEL/FOLD JSON and audio retain their original behavior;
TOUCH does not redefine FOLD. Models 1–3 reject the expressive modes. Source,
geometry, gesture and held model versions are unchanged, as are the six original
LEVEL references and the sixteen-pad starter.

```sh
snipsnap synth ORIGAMI FAN --gesture BLOOM --expression TOUCH --velocity .35 --out /path/to/notes
snipsnap synth ORIGAMI STILL --gesture UNFOLD --expression TOUCH --instrument --release .6 --out /path/to/instruments
./gradlew --no-daemon :shell:generateOrigamiExpressionAudition
```

The expression audition task retains the earlier FOLD study under
`testkit/origami-expression`; the performance-bank task below produces the
current TOUCH recipes. The [original expression brief](superpowers/specs/2026-10-28-origami-velocity-expression.md)
records FOLD's contract. The [TOUCH brief](superpowers/specs/2026-11-05-origami-touch-response.md)
records the Nov 5 feedback, new response and evidence limits. A geometric fold
can move partials downward; neither expression mode promises more brightness.

## Fourth listening round: folded color (model 4)

The accepted model-3 source spectra, phases, amplitude budgets, fundamentals and
envelopes stay unchanged. FAN's centered fold reflects its third harmonic to
1.5 times the root, adding a fifth while leaving the octave stable. STILL's
two reflections place its third and fourth harmonics together at 2.5 times the
root, giving an upper chord color and removing the earlier close beating pair.

The crease maps continuously around these centers. Motion now moves only the
fold axes by a small amount; it no longer translates the whole upper bank.
Manual TURN and WARP still translate and stretch the partials. FOLD keeps its
linear dry/reflected amplitude split, and FOLD zero replays the accepted source
exactly. New FAN/STILL patches start with CREASE at the useful center, .5.

All 30 renderer/recipe checks pass. The full audition verifies unchanged
waveforms and WAV exports for all nine source references and 24 CRANE cases.
In C4/C5 center-fold probes, the fifth holds about 10.6% of FAN's upper-bank
power and the tenth about 7.1% of STILL's. Level-normalized body spectra change
from both the source and model-3 fold; the upper bank remains audible and its
higher-frequency power stays below the source reference.

Default moving FAN/STILL probes read within one cent through autocorrelation
across C3–C5, with root FFT peaks within .04 cents. The fixed audition folds
read within 1.4 cents. These are numerical checks of specific recipes; the
listening comparison decides whether the new colors are musically useful.

## Third listening round: source voicing (model 3)

FAN starts with a rounded low-harmonic body. Its upper-bank amplitude budget
falls from .67 to .18, with a steeper harmonic rolloff and a 35 ms attack.
STILL uses 12 harmonic carriers and six faint detuned companions; its upper
budget falls from .75 to .13 and its attack increases to 480 ms. The fundamental
amplitudes stay at .12 and .09 respectively. Loudness matching remains the same,
so the audition compares a changed tone at the same perceived-level target.

The source comparisons bypass folding. This lets the listener judge whether
the voices work on their own before exploring the transformed versions.
FAN/STILL's model-3 crease range is 1.8–6 times the root frequency, placing
reflection among the lower harmonics that now supply their body. Earlier
models and CRANE keep their original geometry.

All 26 renderer/recipe checks pass, including frozen earlier-model audio,
unchanged CRANE, source-only harmonic balance and genuine audible relocation.
At C4, the upper source bank's level-normalized power above 3.5 times the root
falls from 22.3% to 5.1% for FAN and 17.2% to 2.3% for STILL. That distribution
changes even when the root is excluded, so this is more than a root boost or
master-volume reduction. The full source's corresponding fractions fall to
2.1% and 1.1%. Musical acceptance still requires listening.

The C4 source references read within .3 cents through autocorrelation, and the
frozen folded references within 4 cents. Default moving FAN still reads roughly
100 cents high through that detector despite a correctly placed, stronger root.
Those observations concern different recipes; source clarity does not establish
the pitch of every moving-geometry setting.

## Second listening round (model 2)

FAN gives low harmonics more weight and restores some even-harmonic body.
STILL replaces the crowded inharmonic source with 18 harmonic carriers and
12 quiet detuned companions. Both apply a gentle note-relative pickup weight
to the transformed partials. The geometry, envelopes, controls, root amplitude
and loudness matching remain the same.

The renderer and recipe suite passes 22 checks, including exact legacy replay,
unchanged CRANE and actual frequency relocation. In motion-off C4/C5 upper-bank
probes, level-normalized 4–12 kHz power falls by 79–97% for FAN and 92–99% for
STILL. Off-harmonic upper-bank power also falls substantially. Those checks
establish a changed spectral balance; the matched before/after audition is
the test of whether the revision is musically better.

With default motion, autocorrelation reports FAN roughly 120 cents above the
requested note, despite its correctly placed fundamental. Playable perceived
pitch therefore remains a listening question.

## Native integration

In SYNTH, select ORIGAMI and choose CRANE, FAN or STILL. The six controls are
TUNE, CREASE, FOLD, TURN, WARP and MOTION. TUNE spans C3–C5; an explicit note
in a recalled recipe takes precedence until TUNE is deliberately edited.

The factory roster starts with two reference presets per voice: Paper Key, Light Crease,
Warm Fan, Soft Fifth, Tenth Bloom and Quiet Paper. Each uses model 4 and an
explicit seed, velocity and complete control map. The reference preset for
each voice uses the accepted audition settings. Six additional performance presets
pair BLOOM/UNFOLD with velocity TOUCH, bringing the total to four per voice.
Their names, macros, seed and .85 velocity are unchanged from the Nov 1 FOLD
family; the new expression mode is explicit in each current recipe. Native
output uses the normal SnipSnap render gain. The performance player now opens
in Native mode; Matched is an optional comparison of tone at equal level.

SEND adds the sound to a pad or replaces its audio and saves the complete
ORIGAMI recipe. Authored pitch supplies root metadata and keyed placement,
avoiding spectral pitch-detector errors during SEND and later IN KEY actions.
Only explicitly authored, valid roots bypass detection. Replacement clears stale
source and tuning information. User presets, SEND TO BENCH, recipe replay, velocity layers
and CLI export preserve the model, seed, note and velocity. Sparse older recipes
retain the defaults of the model that originally rendered them. Leaving and
reopening SYNTH retains the full edited ORIGAMI recipe in its navigation state,
even after the preset highlight clears.

The ORIGAMI starter kit contains sixteen dry tonal one-shots: eight CRANE
notes, the six original factory references and unfolded FAN/STILL references. The kit's
notes use C minor pentatonic. It carries replayable recipes and source roots.

## Held keys

Model-4 FAN and STILL offer **MAKE INSTRUMENT ▸** in SYNTH. Set RELEASE
between 0.1 and 1.5 seconds (default 0.6), then MAKE. Open the saved instrument
in MAKE's INSTRUMENTS list and play KEYS: press to sustain, release to fade.
PREVIEW plays middle C for three seconds followed by the chosen release.
The six sound controls stay the same; the instrument sheet adds no attack
control because the sample retains the voice's authored onset.

Each instrument has nine sampled roots, three semitones apart, from C3–C5.
The shared exporter extends the first and last zones to MIDI 0 and 127, so
keys beyond the sampled register transpose its nearest edge sample. The audition
and root checks cover the sampled C3–C5 register. The played zone overrides the source recipe's TUNE and explicit
MIDI; its seed, velocity and spectral settings remain intact. Keyboard velocity
adds the normal player gain. The mono 44.1 kHz samples carry an attack followed
by a periodic sustain region. Phone metadata, WAV sampler markers and both
MPC keygroup formats share the same loop and note-off release.

Held renderer model 1 is versioned separately from finite model 4. It fits
oscillator cycles and geometry motion to one repeat period, integrating moving
frequencies before closing phase. In STEADY, FAN's motion period is about 2.86 seconds;
STILL uses about ten seconds, with its slower motion at roughly 0.10/0.20 Hz.
These held motion rates are fitted rather than preserving finite STILL's
0.10/0.17 Hz pair. The original finite attack leads into that periodic field;
the loop has no end fade. Tests compare independently synthesized cycles,
not a duplicated tail that can conceal a phase reset.

Every export also writes `<name>.origami-keys.json`, containing the unchanged
finite patch recipe, release and held model. It is separate from the phone's
playback sidecar. On the desk:

```sh
snipsnap synth ORIGAMI FAN --instrument --release 0.6 --out /path/to/instruments
snipsnap synth ORIGAMI STILL --patch /path/to/finite-patch.json --instrument --out /path/to/instruments
```

CRANE and models 1–3 keep their finite renderer. SEND TO PAD remains a finite
sound for every voice; MAKE INSTRUMENT supplies held playing through KEYS.

## Performance bank

The six performance presets expose BLOOM/UNFOLD with TOUCH as complete factory
choices. Choices one and two per voice retain the original reference names,
controls and audio; choices three and four are the current performance pair.
There are still twelve factory presets and sixteen starter pads.

| Voice | BLOOM | UNFOLD | Playing use |
| --- | --- | --- | --- |
| CRANE | Paper Bloom | Paper Unfold | Struck finite keys. |
| FAN | Fifth Bloom | Fifth Unfold | Finite notes or held fifth-colored keys. |
| STILL | Soft Bloom | Soft Unfold | Finite swells or slow held pads. |

Select SOFT, MID or FULL, then tap AUDITION for each comparison. FAN/STILL
complete their once-only gesture before the sustain region. On Android, select
a factory and use MAKE INSTRUMENT to put its layered instrument in MAKE's
INSTRUMENTS list. The saved factory hard sound is unchanged; the softer source
and folded balance is the intentional TOUCH revision and awaits owner listening.

```sh
./gradlew --no-daemon :shell:generateOrigamiPerformanceBank \
  -PorigamiPerformanceOutput=/path/to/origami-performance-bank
snipsnap synth ORIGAMI FAN --preset 3 --instrument --release .6 --out /path/to/instruments
snipsnap synth ORIGAMI STILL --preset 4 --instrument --release .6 --out /path/to/instruments
```

The default output is `testkit/origami-performance-bank`. The generator writes
six exact recipes and 28 native/matched takes: 18 singles, six phrases/chords
and four sequential soft/mid/hard held performances. The self-contained starter
embeds all 18 Native singles; the full page opens in Native and offers Matched
for equal-level tone comparisons. Matching the whole take retains the relative
note levels within phrases and held sequences. Browser audio starts from an
explicit play action through native HTML audio.

Four MPC programs each contain nine sampled roots, MIDI 48–72 in three-semitone
steps, and three velocity layers: 27 mono 44.1 kHz PCM24 WAVs per program.
Playback zones extend to MIDI 0–127 by transposing the edge samples. Every layer
has its own loop markers and the default .6-second key-up release. The long held
takes play each of the three layers through at least three actual wraps; their
manifest records selected samples, timings and wrap counts.

Keep each `.xty` beside its `_[TrackData]` directory containing the `.xpm` and
WAVs. Preserve `.instrument.json` for playback and `.origami-keys.json` for the
complete authoring recipe. These downloads target MPC; Android does not import
the keygroup ZIPs. Physical MPC playback remains unverified.

To reproduce the earlier FOLD family separately:

```sh
./gradlew --no-daemon :shell:generateOrigamiPerformanceBank \
  -PorigamiPerformanceOutput=/path/to/legacy-fold-bank \
  -PorigamiPerformanceLegacyFold=true
```

The generator's direct main also accepts `--legacy-fold`.
`OrigamiPresets.performance()` supplies the current TOUCH family;
`foldPerformance()` retains the Nov 1 FOLD recipes. The original
[performance-bank brief](superpowers/specs/2026-11-01-origami-performance-bank.md)
is historical; the [Nov 5 TOUCH brief](superpowers/specs/2026-11-05-origami-touch-response.md)
records why the response changed. Numerical separation and healthy files do
not establish that the revised feel is musically preferable.

## Current limits

ORIGAMI renders samples offline. Live spectral processing and externally
recorded sources remain future work. A deterministic finite recipe alone does
not establish periodic phase/modulation state at a loop boundary.

The first comparisons establish whether the direct-partial method is useful.
STFT processing remains a possible later route for genuine broadband noise or
external spectral sources; it is not required to analyze a source whose
frequencies the engine already knows.

## First-round numerical evidence (model 1)

The first renderer and recipe checks passed before producing the audition.
The largest raw sample peak in the default/all-high C3–C5 probes was .479,
before any loudness gain. Default root-only RMS was roughly .75 of combined
RMS for all three voices; separate upper-bank clips remain available so the
listener can assess that balance directly. A fractional-crease reflection
moved measured off-harmonic energy from below .001% to 5.49% for CRANE and
12.85% for FAN, supporting actual frequency relocation rather than a gain mask.

Measured root peaks were within .052 cents of the requested note in the
default C3/C4/C5 cases. The unrestricted autocorrelation pitch detector read
CRANE's C3 about 38 cents high and STILL's C3 about 66 cents high, despite those
correctly placed roots. Inharmonic material has ambiguous periodicity. Root
support, detector results and perceived pitch are distinct evidence; the
audition is needed to decide whether these sounds feel playable.
