# Alloy in SnipSnap

Alloy is an original attack-driven phase-modulation instrument. A generated wooden
strike, tine/string projection or breath/contact burst is heard directly and also
changes the phase of a four-operator tonal body. It uses no external samples or
Yamaha operator/sample data. The supplied [engineering specification](ALLOY_ENGINE_SPEC.md)
describes a proposed hybrid gesture, rather than a reconstruction of the SY77.

The [published audition](https://snipsnap-alloy-audition.bertcalm.chatgpt.site)
contains 36 dry factory presets across BASS, KEY, PLUCK, BRASS, STRING and SOFT.
The owner heard the initial twelve-preset audition and authorized expansion on the
same engine tuning. The agreed roster has six presets per voice, superseding the
uneven allocation proposed in the supplied specification. The original twelve
retain their names and settings; the twenty-four additions await listening review.

## Controls and render contract

TUNE offsets the saved MIDI root by ±12 semitones, neutral at 0.5. Saved roots
span MIDI 24–96, and the effective note can span 12–108; MATERIAL moves continuously through overlapping
wood, tine/string and breath/contact projections. FUSE increases attack-driven
phase modulation, measured in radians, while modestly reducing the detached
direct layer. EDGE changes ordinary PM index and articulation even at FUSE zero.
BODY changes carrier balance, root support and filter loading. LENGTH controls
the finite note and release; in settled HOLD it changes modal projection and
restrained contact depth without adding a second sequencer. HOLD extends the finite gesture before its top step
returns settled loop-only material.

Every render starts fresh at 44.1 kHz mono, with a four-times internal path. Velocity
changes excitation and modulation inside the engine. Finite files retain 120 ms of filter/DC settling after the envelope closes.
Shared melodic loudness conditioning follows synthesis; raw probes remain available for numerical checks.
Material ratios and losses, four operators, bounded acyclic routing, envelopes,
held excitation and filter/FUSE limits are explicit patch data. Saved recipes also
contain MIDI, velocity and model version. Macro overrides preserve that recipe.

The attack modulation port removes DC, bounds amplitude and slope, and limits
bandwidth using a fixed calibrated source reference. The ordinary PM path and
material modes also reduce high-note bandwidth. Requested route depths retain their
relative balance when an incoming-node budget or recursive 20 kHz phase-derivative
budget reduces them; nested custom graphs receive the same safety calibration.
The oscillator integrates only its tuned phase increment: attack audio is a bounded
phase offset, never an unbounded frequency increment. No feedback routing is exposed.

HOLD uses restrained periodic material excitation through the same modulation
port. It does not retrigger the full finite attack. Oscillator and excitation
frequencies fit the selected loop period, and filter/conditioning state settles
during bounded preroll. The renderer retains an actual preceding cycle for the
project's `Keys.seamError` check. The selected cycles are periodic before export;
no crossfade is needed to hide an unsettled source. A held
export contains the material-modified sustain; it omits the opening strike.

## Integration and audition

`AlloyPatch`, `Patches`, `Presets`, native velocity rendering, the phone synth picker,
CLI rendering and `SynthKits.alloy()` use the same engine. A sixteen-pad dry kit
contains a playable scale, voice examples and held material. TONAL and LOOP classes
use the existing melodic export routing. The standard drum-program export keeps
infinite sample looping disabled. Pads 15/16 retain Held Filament and Endless Ember
as settled held WAVs; MPC users can enable sample looping on those pads for
indefinite HOLD.
Actual waveform classifier checks remain
separate from those declared export classes.

Generate the listening files with:

```sh
./gradlew --no-daemon :synth:generateAlloyAudition
```

The output is `testkit/alloy-audition/`: rendered WAVs, a manifest with exact recipes
and raw diagnostics, patch JSONs, native kit downloads and a static listening page.
Generated audio is ignored by Git. The page groups presets, voice/register/velocity
coverage, five-point sweeps, interaction grids, FUSE ablation, settled HOLD and
extremes. Playback is explicit; held loops use Web Audio for gapless repetition.
Keep/revise/skip decisions and notes stay in the browser and can be exported as JSON.
The original preset clip IDs and browser storage key remain stable, so existing
listening notes stay attached after the expansion. New preset IDs are based on
their names rather than their position in the roster.

The default publication pack contains 309 listening clips and 393 WAVs, including
all 36 preset renders and the existing diagnostic studies. `alloy-patches.zip`
contains the complete patch library; `alloy-mpc-kit.zip` contains the sixteen-pad
XPM program and PCM24 samples. The older `alloy-provisional-*.zip` URLs remain
compatibility copies of the current archives. `-PalloyFull` expands the diagnostic
sweeps and grids across all six voices; both modes include the complete preset roster.

Listen to FUSE-on and attack-modulation-removed pairs at the same level. The latter
keeps the direct attack path and ordinary PM, so a difference cannot be credited
only to changing attack gain. Compare MATERIAL at ordinary FUSE settings, EDGE with
FUSE zero, and held MATERIAL/FUSE changes after the initial gesture is absent.

## Voice contrast candidates

The owner's expanded-roster review identified similarity both within and between
voices. A separate [voice contrast audition](https://snipsnap-alloy-audition.bertcalm.chatgpt.site/contrast/)
therefore compares twelve candidate voicing recipes, two per voice. These use
explicit model-1 configurations. The original listening assets and their saved
reviews remain available as a fixed reference.

The listening selection uses the revised KEY, PLUCK, BRASS and STRING voices,
and the original BASS and SOFT voices. `Alloy.defaultConfigFor` supplies that
selection to new patches, factory presets and the patchless app preview. The
36 factory names and macro settings remain the same; the 24 presets in the four
revised voices adopt their selected configuration. Selecting one of those factory
names again therefore uses its revised sound.

Saved patch and pad JSON keeps its explicit configuration, including original
sounds. Model-1 JSON with a missing or null configuration still decodes with the
original recipe through `Alloy.configFor`; low-level diagnostic render defaults
also retain that reference. New code should construct an `AlloyPatch` or pass
`Alloy.defaultConfigFor(voice)` explicitly when making the selected voice.

```sh
./gradlew --no-daemon :synth:generateAlloyContrastAudition
```

`testkit/alloy-contrast/` contains 60 matched-level listening clips: six finite
old/new voice pairs at identical macros, twelve candidate roles at MIDI 36/48/60,
and six old/new settled HOLD pairs. All comparisons use velocity .75; the voice
and held pairs use MIDI 48. Each clip records its complete configuration, raw
measurements and source/compiled-class provenance. Twelve raw PCM24 references,
twelve patch JSONs and a sixteen-pad MPC kit accompany the page. Pads A13–A16 are
loop-only BASS/BRASS/STRING/SOFT candidates; enable whole-sample looping on the MPC
for indefinite HOLD. The new page keeps its listening notes separate from the
released audition. The page marks the selected original/revised voice for each
comparison. The two-role candidates, expanded preset sweep and naming still need
listening review; the voice choice does not approve every proposed preset.

## Selected release pack

The [selected audition](https://snipsnap-alloy-audition.bertcalm.chatgpt.site/selected/)
combines the agreed original BASS/SOFT and revised KEY/PLUCK/BRASS/STRING voices.
It contains the current 36 factory presets, a matching sixteen-pad MPC kit,
and voice, macro, interaction, source-ablation and held-loop studies.
The default publication matrix has 310 clips and 395 listening WAVs; the starter
and three-program factory bank add 52 MPC sample files.

```sh
./gradlew --no-daemon :synth:generateAlloySelectedAudition
```

Output goes to `testkit/alloy-selected/`. Add `-PalloySelectedFull` for all-voice
timbral sweeps and interaction grids, or use `-PalloySelectedRefreshPage` to refresh
only the player. The selected pack resolves each case's complete configuration
once and uses it for both synthesis and recipe export. Explicit patch recipes
take precedence over the selected voice default. Every ordinary clip has an
importable patch at its actual base note and velocity; diagnostic source ablations
also record the separate attack-modulation flag needed to reproduce them.

The starter kit ZIP contains PCM24 samples, an XPM program and native `kit.json`
recipes. Pads A15/A16 are settled STRING/SOFT loops; enable whole-sample looping
on the MPC for indefinite HOLD. The full 36-preset MPC bank supplies three
twelve-pad programs, each with native recipes and exported sample fingerprints.
Its included READMEs identify the held pads. The 36-patch ZIP matches the same bank. The old
gallery and contrast pack remain frozen references with their existing notes;
the selected page uses its own review storage and note export.

Selected control validation found four recipe-response gaps. KEY's wood modes
now persist through the FUSE comparison window, and its held drive is .21 rather
than .17. PLUCK's breath modes also persist longer; its finite-duration coefficient
is 1.0 rather than .65, so LENGTH .25 to .75 extends the phrase by .3 seconds.
The finite pure-tine references at MATERIAL .5 and LENGTH .25/.45 are bit-identical
to their previous configurations. KEY HOLD has stronger material excitation;
that held change is intentional. Existing saved configurations keep their values.

One extreme is disclosed rather than disguised: PLUCK at MIDI 36 with all five
timbral controls at zero has an accurately pitched but short, tom-like body. The
generic waveform classifier calls both its raw and decoded matched export TOM;
engine metadata remains TONAL. The
`pluck_all_low_c2` diagnostic makes that corner available for listening. The
pitched-classifier guards still cover every factory preset and ordinary wood,
tine and breath cases; all extreme cases retain pitch and numerical bounds.

The voice choice has been heard and approved. This release pack is the preset
listening gate; it does not assert physical phone or MPC acceptance. Rendering
performance still needs phone profiling. Host timing measurements are not phone
latency measurements, and attack-plus-loop export remains a separate host feature.

## Validation and remaining acceptance

The numerical gates cover deterministic renders, bounded raw state, velocity,
pitch/root support, macro activity, matched-level causal comparisons, actual pitched
classification, patch/routing serialization and settled-loop seams. The required
repository check is:

```sh
./gradlew --no-daemon test
```

High-register checks include a deterministic 4×/8× body comparison and near-Nyquist
spectral probes. These bound solver divergence in the tested tonal cases; they
do not establish complete alias elimination for every custom recipe or stochastic
breath projection. Numerical checks do not establish sonic approval. The initial
engine audition has been heard; expanded-preset listening, phone/device behavior,
hardware MPC listening and final naming checks remain open.
