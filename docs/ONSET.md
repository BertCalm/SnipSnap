# ONSET — percussion blends

ONSET blends two percussion sources under six shared controls. Its original
four voices are KICK, SNARE, HAT_CLOSED and HAT_OPEN. Native model 4 adds
TOM, PERC_PLUCK, PERC_WOOD and PERC_METAL from the seven Study 009 setups.
The endpoints and the middle should each earn their place by sound.

## Original four-voice rendering

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

## Native extension: model 4

The characteristic gesture is **electronic contact → material body →
frequency-dependent loss → a dry finish**. The extension carries the
Study 009 source and blend laws into ordinary ONSET patches. This is a
port of the listening direction, not a new preset-design round.

| Voice | Study setups | Root range | Decay T60 range | Sources |
|---|---|---|---|---|
| TOM | Floor, Mid, High | 70–240 Hz | .16–.75 s | Synth tom and six-mode membrane |
| PERC_PLUCK | Dry, Wire | 180–900 Hz | .08–.65 s | Electronic pulse and released string |
| PERC_WOOD | Wood Knock | 250–1400 Hz | .10–.85 s | Electronic tap and three damped wood modes |
| PERC_METAL | Metal Ring | 250–1400 Hz | .10–.85 s | Harmonic FM and four material modes |

TUNE and DECAY follow exponential laws. SNAP changes contact or attack;
BODY changes the voice's body geometry, modal balance or pick position,
as well as the shared low-body shelf. EDGE changes excitation detail and
upper-frequency loss. Wood and metal have explicit voice identities:
editing BODY does not switch between their source/blend algorithms.
These are authored physical models, not measurements of acoustic instruments.

The plucks and wood knock use a bounded handoff inside BLEND: the electronic
attack leads, then recedes as the material body develops. Tom and metal
retain Study 009's root-carrier amplitude modulation inside the blend.
Both laws explicitly bypass their interior behavior at BLEND 0 and 1.
There is no feedback between the electronic and material sources. The
string's own passive loop remains separate from this feedforward blend.

Rendering remains mono at 44.1 kHz with 4× internal processing. Extension
sources share one band limit, decimation, DC removal and tail finish.
Fixed gains retain the studied raw levels; no per-patch normalization is
added. A level-matched audition copy is a listening aid, not a recipe gain.
The port preserves the prototype's Double calculations and deliberate
Float conversion points; settings metadata does not round the DSP root.

The factory now contains 33 comparison presets: the original twelve plus
seven extension setups at BLEND 0%, 50% and 100%. All six controls are
saved explicitly. The original twelve remain model 3, including their
names and settings. The extension presets explicitly carry model 4.
`OnsetPresets.extensionStudy()` selects the seven midpoint patches in
Floor, Mid, High, Dry, Wire, Wood, Metal order for the extension sample kit.
This separate kit leaves the original 16-pad acceptance kit intact.

Models 1–3 remain valid for the voices they originally supported. New
voices require model 4. The first four voice enum ordinals are preserved.
Saved patch names never select a DSP branch or enter its noise seed.

The owner's Study 009 response, “sounds better,” established the direction
for native integration. It does not stand in for listening to the native
build. The PCM comparison gate checks all 21 extension renders against
Study 009, plus 40 original-model comparisons and the accepted 16-pad
production bank. Numerical agreement and an Android build remain separate
from phone and MPC acceptance. The detailed contract is in
[the native extension specification](superpowers/specs/2026-11-03-onset-native-extension-design.md).

## Editing a pad in SnipSnap

ONSET is available in SYNTH. Choose a voice, then adjust TUNE, DECAY,
BLEND, SNAP, BODY and EDGE. Its presets are dry study endpoints and
midpoints. SCRAMBLE moves near those saved settings.

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

Ordinary standalone XTD/XPM export can retain the editable recipe in its
adjacent `kit.json`. SnipSnap's local kit history and user-preset backup
also retain metadata. MPC project/session/song exports and ordinary XPN
expansion packaging carry rendered samples without ONSET recipe history.
Keep the editable kit when those delivery formats are needed.

An existing VERSIONS limitation is separate from recipe editing: the automatic
outgoing take created after an audio rewrite can pair an older saved recipe
with the newer WAV. A version archived before the rewrite retains a coherent
recipe and audio pair. The native lifecycle test checks that explicit pre-edit
checkpoint; it does not claim to repair automatic outgoing snapshots. See
[the verified reproduction and scope](ONSET_VERSION_HISTORY_LIMIT.md).

For the device acceptance pass, import the downloaded kit, reopen a
midpoint pad for each voice, move BLEND, update the pad, reopen it again,
and export the kit. On MPC, compare the raw samples at full velocity and
check open-hat tails and closed/open-hat muting. Android compilation and
JVM checks are separate from this listening pass.

## Reproduce the audition

Build the separate seven-pad native extension kit:

```sh
./gradlew :synth:generateOnsetExtensionKit
```

Output: `testkit/onset-extension-native/onset-extension-native-kit.zip`.
It contains the seven raw midpoint samples and complete model-4 recipes,
with XTD and XPM programs. Use
`-PonsetExtensionKitOutput=/path/to/output` to choose a different folder.

The original four-voice audition remains a separate historical comparison:

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

The integration covers the original four voices and the model-4
extensions, saved patch and pad recipes, the SYNTH picker, pad editing
and the sample export path. CLAP, native strike-velocity articulation and
live voice interaction remain separate work. Finished blends and motion
rendered inside a hit follow SnipSnap's sample export pipeline;
interaction between separate rearrangeable voices requires a shared
runtime or a rendered phrase.

## Why it is built this way

- The seven studied setups become voices and presets inside ONSET, so
  the same BLEND gesture works through ordinary pad editing.
- Explicit model versions and complete control snapshots keep saved
  drums from changing when defaults or source algorithms develop.
- Wood and metal select their own algorithms before BODY is applied,
  so moving a body control cannot unexpectedly change the voice family.
- Fixed raw gains preserve the listening comparisons. Matching is kept
  in audition copies so the exported samples retain their native balance.
- A separate extension kit keeps the accepted bank and earlier listening
  references available for comparison.
