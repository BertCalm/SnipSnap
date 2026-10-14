# ONSET — listening experiment

ONSET tests a continuous blend between two percussion sources. The current
listening scope is KICK, SNARE, HAT_CLOSED and HAT_OPEN. Five positions per
voice and three copies of the same two-bar beat form the listening gate.
The middle should earn its place by sound.

## Rendering

`Onset.render` works at 4× the 44.1 kHz output rate. The electronic source
uses unfinished THUMP voices. The algorithmic hat uses SKIN's resonant noise;
the revised physical kick uses ONSET's circular-membrane bank. Model 1
retains the original THUMP/SKIN pairing. Their public render paths keep
their existing mappings and finishing. ONSET blends the bodies with
cosine/sine gains and adds one shared transient. A common body shelf and
PUNCH shaping precede a single decimation and final safety/tail handling.

TUNE resolves to the same 45–62 Hz fundamental for both kick sources.
Hats have different natural excitation and resonance frequencies; TUNE
applies the same relative ±4-semitone shift to both. Both sources in each
voice receive the same decay T60. The new snare shares a 170–240 Hz body
root and blends a THUMP membrane body with a TINES two-operator FM body,
under one shared wire layer. Its FM index decays more slowly than the
amplitude so the body character can survive the attack. Open hat extends
the square-cluster/resonant-noise pairing with its own longer envelope.
SNAP, BODY and EDGE author their
respective attack, low-body and spectral character. BLEND is the only
parameter changed within each first-listen comparison row.

Fixed source calibration and output headroom preserve the blend's native
level behavior. Equal-power coefficients do not guarantee constant
perceived loudness when the sources share correlated fundamentals.
The audition supplies both native output and copies matched by the RMS
of their loudest 200 ms window. Matching applies one gain to a complete
clip, including the full beat.

THUMP is an analog-style oscillator/envelope engine; this experiment
uses that electronic character rather than claiming detailed circuit
simulation. The listening passes measure the musical value of the blend
before expanding its sources.

## Kick revision 2

The owner's first verdict was that the kick sounded the same at both ends.
The delivered first-pass PCM supported that observation: after 25 ms,
both bodies put nearly all their energy below 100 Hz. From 100–200 ms,
their normalized correlation was approximately 0.999995. The membrane's
upper-mode decay scales of 0.35 and 0.18 removed its material structure
quickly, leaving the shared fundamental and pitch trajectory.

Model 2 changes the physical kick's contact projection and independently
decaying membrane modes. Upper-mode structure can persist through the
body while the fundamental keeps the shared root, trajectory and decay.
This is authored modal percussion, rather than a measured acoustic drum.
The electronic kick and both hat endpoints retain their first-pass audio.

The old electronic, midpoint and physical kick WAVs appear under
**Previous kick / first pass**. Those references preserve the original
bytes, levels, settings and measurements. New kick and beat filenames
include the renderer revision so browser caches cannot substitute the old
sounds. Notes on the three preserved endpoint and midpoint kicks stay
with their original reference sounds.

The owner accepted revision 2 as the reference kick. Model 3 preserves
that kick and the closed hat byte for byte, while adding the snare and
open hat for a separate listening verdict. Measured separation and stable
rendering do not establish sonic acceptance for the new voices.

## Four-voice pass

The new beat adds snare and open hat to the same 104 BPM listening format.
A closed hat fades an overlapping open hat out in the rendered phrase.
This is baked audio, not a live coupling system. New beat IDs keep notes
on the previous two-voice beat attached to the sounds they described.
Accepted kick, closed-hat and first-pass reference IDs retain their notes.

The optional 16-pad MPC sample kit uses four blend rows (0%, 25%, 50%,
100%) and four voice columns (kick, snare, closed hat, open hat). It includes
a native XTD track, an XPM program twin and the resolved settings. The hat
pads share a mute group. This is a sample kit; ONSET does not yet have a
registered editable pad recipe in SnipSnap. Format checks do not establish
hardware playback acceptance.

## Reproduce the audition

```sh
./gradlew :synth:generateOnsetAudition
```

A fresh build needs the accepted model-2 audition, including its preserved
first-pass kick references:

```sh
./gradlew :synth:generateOnsetAudition -PonsetReferenceDir=/path/to/model-2-audition
```

Subsequent runs reuse the accepted baseline and first-pass references
retained in the output folder. The downloadable audition bundle also
carries these references.

Output: `testkit/onset-audition/`, containing twenty dry hit comparisons,
three identical-pattern four-voice beats at 104 BPM, three first-pass kick references,
raw/matched 24-bit WAVs, the listening page, a manifest, resolved settings,
source hashes, an MPC sample kit and a ZIP. All audio comes from the JVM renderer.

The page begins with level matching enabled, plays only on request,
supports stopping all audio, and keeps listening notes in the browser.

## Acceptance and next scope

Numerical checks cover reproducibility, bounded finite output, coherent
source controls and the existing drum engines. Sonic acceptance belongs
to the listening review: useful endpoints, a cohesive midpoint and
audible control over the transition.

This is a renderer and audition prototype. Android picker registration,
typed pad recipes, the remaining voice families and live voice interaction
remain subsequent integration work. Finished blends and motion rendered
inside a hit can follow SnipSnap's sample export pipeline; interaction
between separate rearrangeable voices requires a shared runtime or a
rendered phrase.
