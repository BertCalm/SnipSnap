# ONSET — four-voice percussion engine

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
pads share a mute group. Each sample also carries a typed ONSET recipe in
`kit.json`: the voice, all six controls and the explicit renderer version.
The exporter checks that the saved recipe regenerates the delivered raw
PCM exactly. MPC plays the rendered WAVs; the controls are editable in
SnipSnap. Format checks do not establish hardware playback acceptance.

Standalone XTD and XPM exports preserve editable history for ordinary
kits. Imports validate the adjacent sidecar's pad/sample mapping and
available WAV hashes, while retaining the program's playback controls.
Chained kits keep their existing program/sample export behavior and do
not carry this sidecar: restoring their slice scheduling is separate work.

## Editing a pad in SnipSnap

ONSET is registered in SYNTH after SKIN. Choose KICK, SNARE, CLOSED HAT or
OPEN HAT, then adjust TUNE, DECAY, BLEND, SNAP, BODY and EDGE. The twelve
initial presets are the audition's dry endpoints and midpoint. SCRAMBLE
moves near those settings without adding another voice family.

SEND TO PAD writes the sound and its complete recipe. SAVE AS PRESET keeps
an editable sound on the shelf. REOPEN PAD selects a recognized synth pad
from the open kit and restores its engine, voice, controls and processing
recipe. UPDATE PAD writes the edited sound back to that pad; SEND TO PAD
can place a copy elsewhere. Loading a new preset starts a fresh recipe.

The saved renderer version is required and remains unchanged during
editing, including for the original two-voice models. Recipe controls own
an immutable snapshot, so a later slider change cannot mutate a saved
sound. The processing chain, treatment name, amount and explicit alias
choice also survive reopening and regeneration.

For the device acceptance pass, import the downloaded kit, reopen a
midpoint pad for each voice, move BLEND, update the pad, reopen it again,
and export the kit. On MPC, compare the raw samples at full velocity and
check open-hat tails and closed/open-hat muting. Android compilation and
JVM checks are separate from this listening pass.

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

The initial integration covers the four existing voices, saved patch and
pad recipes, the SYNTH picker and pad editing, and the sample export path.
The remaining voice families and live voice interaction are subsequent
work. Finished blends and motion rendered inside a hit follow SnipSnap's
sample export pipeline; interaction between separate rearrangeable voices
requires a shared runtime or a rendered phrase.
