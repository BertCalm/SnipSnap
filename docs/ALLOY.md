# Alloy in SnipSnap

Alloy is an original attack-driven phase-modulation instrument. A generated wooden
strike, tine/string projection or breath/contact burst is heard directly and also
changes the phase of a four-operator tonal body. It uses no external samples or
Yamaha operator/sample data. The supplied [engineering specification](ALLOY_ENGINE_SPEC.md)
describes a proposed hybrid gesture, rather than a reconstruction of the SY77.

The [factory audition](https://snipsnap-alloy-audition.bertcalm.chatgpt.site/factory/)
contains 24 dry factory presets across BASS, KEY, PLUCK, BRASS, STRING and SOFT.
Each voice has its existing first-preset reference and three distinct roles with
explicit operator, routing and envelope configurations. The owner heard the
[role comparison](https://snipsnap-alloy-audition.bertcalm.chatgpt.site/roles/),
reported that it was much better, and authorized integration of that bank.
The original 36-preset gallery and selected-voice release remain frozen references.

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

The CLI accepts complete Alloy JSON through `--patch`, including retained held
recipes and authored factory configurations. `--midi` and `--velocity` override
only the saved root and energy; they keep the complete material, routing and
envelope data. `--all` and numbered `--preset` selections use the active four
factory patches per voice.

Generate the current factory listening files with:

```sh
./gradlew --no-daemon :synth:generateAlloyFactoryAudition
```

The output is `testkit/alloy-factory/`: rendered WAVs, a manifest with exact recipes
and raw diagnostics, patch JSONs, native kit downloads and a static listening page.
Generated audio is ignored by Git. The page groups presets, voice/register/velocity
coverage, five-point sweeps, interaction grids, FUSE ablation, settled HOLD and
extremes. Playback is explicit; held loops use Web Audio for gapless repetition.
Keep/revise/skip decisions and notes stay in the browser and can be exported as JSON.
The factory page uses a separate browser storage key. Historical pages and notes
remain intact; preset IDs use names rather than positions in the roster.

`alloy-patches.zip` contains the complete 24-patch library;
`alloy-mpc-kit.zip` contains the sixteen-pad starter XPM program and PCM24 samples.
`alloy-factory-preset-bank.zip` contains all 24 presets in three eight-pad programs,
paired as Bass/Key, Pluck/Brass and String/Soft. Each program includes native
recipes and exporter sample fingerprints. Use `-PalloyFactoryOutput=...` for a
different destination. Run `-PalloyFactoryRefreshPage` separately to refresh only
the player without rendering audio. The older audition tasks remain available;
their generators read the current registry, while hosted historical packs stay frozen.
The publication contains all 24 presets and 275 curated diagnostic cases. Add
`-PalloyFactoryFull` to expand macro sweeps and grids across every voice for a
larger development pack.

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
selection to new patches and the patchless app preview. Factory references keep
that selection; the three additional roles per voice carry their own explicit
configurations. Editing their macros preserves those configurations.

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
The frozen publication contains the preceding 36 factory presets, a matching sixteen-pad MPC kit,
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

The voice choice has been heard and approved. This historical release pack was the
earlier preset listening gate; it does not assert physical phone or MPC acceptance. Rendering
performance still needs phone profiling. Host timing measurements are not phone
latency measurements, and attack-plus-loop export remains a separate host feature.

## Distinct factory roles

The earlier macro-only presets shared one configuration per voice. Their openings
changed while their sustained bodies often converged. Gain-normalized waveform
tests proved that signals differed, which did not establish useful musical variety.
The replacement bank uses the exact complete patches from the approved comparison,
including their macros, MIDI roots, velocity and model-1 configuration. The sole
display-name adjustment is Elastic Contact to Elastic Touch to fit the existing
14-character preset strip; it changes no synthesis input.
Approved JSON fixtures protect that correspondence. No oscillator arithmetic,
selected voice defaults, patch schema or missing-config legacy fallback changes.

The starter kit uses a Long Prism KEY pentatonic walk, then Hollow Rail, Reed
Switch, Open Chime, Bright Stamp, Beating Glass and Velvet Rise. A15/A16 keep
Held Filament and Endless Ember. All six earlier held references are available
through `AlloyPresets.heldReferences()` outside the active 24-preset picker bank.
Saved retired presets remain complete, renderable recipes.

The 18 authored roles avoid the air/noise material path at their saved settings.
Their wood and tine projections intentionally match in the approved inputs, so
MATERIAL from 0 to .5 selects the same projection for those roles; above .5 it
moves toward the inherited breath source. Default voice studies retain distinct
wood, tine and breath projections. This integration preserves the auditioned
recipes rather than retuning their controls. Beating Glass uses slightly offset
upper partials; its root measurements and exact approved patch are retained.

Picked Fibre at C2 is a second explicit classifier limit: the short low note has
a stable pitched root, but the generic bass/decay heuristic calls it TOM. The
`picked_fibre_c2` study records both raw and decoded listening-file classifications;
engine and MPC metadata remain TONAL. The factory patch is unchanged from the
approved comparison. A longer tail would change that sound merely to fit a
heuristic, so the limitation remains visible instead.

The original 36-preset corpus retains all 108 strict short-capture register and
classification checks through frozen complete-input fixtures. The new roles add
full-event feature checks with only the classifier's duration shortcut disabled:
cropping slow swells before they reach their body gives a false short decay.
Every new role still receives the same pitch and numerical bounds; the named C2
picked-string case asserts its observed generic class and its pitched export class.

Why it is built this way:

- Operator balance and routing change the persistent body, beyond transient color.
- Envelope differences provide short, ringing and slowly opening musical roles.
- Exact accepted recipes avoid introducing a new sound-design pass during integration.
- Separate historical pages and explicit saved configurations keep earlier sounds reproducible.

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
engine and distinct-role comparison have been heard, and the owner authorized this
bank. Phone/device behavior and hardware MPC listening remain open. Further
listening notes can still guide refinements; numerical checks do not replace them.
