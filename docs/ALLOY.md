# Alloy in SnipSnap

Alloy is an original attack-driven phase-modulation instrument. A generated wooden
strike, tine/string projection or breath/contact burst is heard directly and also
changes the phase of a four-operator tonal body. It uses no external samples or
Yamaha operator/sample data. The supplied [engineering specification](ALLOY_ENGINE_SPEC.md)
describes a proposed hybrid gesture, rather than a reconstruction of the SY77.

The [published audition](https://snipsnap-alloy-audition.bertcalm.chatgpt.site)
is the dry listening gate. Twelve provisional presets cover BASS, KEY, PLUCK,
BRASS, STRING and SOFT. The proposed expansion to 36 presets waits for the owner's
listening verdict.

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
infinite sample looping disabled; pads 15/16 contain settled held WAVs, and
MPC users can enable sample looping on those pads for indefinite HOLD.
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

Listen to FUSE-on and attack-modulation-removed pairs at the same level. The latter
keeps the direct attack path and ordinary PM, so a difference cannot be credited
only to changing attack gain. Compare MATERIAL at ordinary FUSE settings, EDGE with
FUSE zero, and held MATERIAL/FUSE changes after the initial gesture is absent.

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
breath projection. Numerical
checks do not establish sonic approval. Owner listening, phone/device behavior,
hardware MPC listening and naming checks remain open.
