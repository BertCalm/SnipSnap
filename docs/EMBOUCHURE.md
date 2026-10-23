# EMBOUCHURE

EMBOUCHURE is a dry, deterministic mono prototype of a virtual player supplying
energy to one pitched resonator. Model 2 widens contact, articulation and
spectrum changes after the first audition found the controls too subtle.
All six finite default voices remain sample-identical to model 1. Eight
starting presets are available; the proposed larger roster waits for listening
approval of the revised controls.

The opt-in [BRASS GROW prototype](engineering/brass-grow-prototype.md) adds two
separate model-3 presets with a four-control panel. It leaves the original
eight presets and starter kit unchanged. The legacy model-1/model-2 controls
and saved-sound contracts described below still apply to ordinary patches;
the linked prototype notes describe the new single-source GROW path.

The source brief is [Embouchure engine engineering specification](engineering/embouchure-engine-spec.md).
That brief refers to a supplied edited Gyre integration contract, but no such
separate contract accompanied the attachment. The port follows the interfaces
actually present in SnipSnap, especially TESSERA's relative TUNE and
PITCHWHEEL/CISTERN's explicit note, velocity and renderer-model recipes.

## Controls and source identity

Five timbral controls are EFFORT, REED, SHAPE, GRAIN and LENGTH. HOLD selects a
finite gesture or settled loop; TUNE is the host's note control. EFFORT selects
the intensity of the saved playing gesture. Velocity supplies input energy
within that character. HOLD requires continuing player energy and is separate
from a finite note's passive release.

The voices are REED, BRASS, BASS, WHISTLE, BOW and SOFT. Each voice uses one driver
calibration, fixed by voice and renderer model. REED, BASS, WHISTLE and SOFT use a
reed-like driver, BRASS a lip-like driver and BOW a friction driver. WHISTLE is
an air/reed-derived clear-tone approximation; it is not an accurate jet-edge
model. These are original reduced models inspired by pressure-driven physical
synthesis, with no VL1 algorithm or factory-data compatibility claim.

The saved gesture is one of SOFT_ENTRY, LEANING_SWELL, OVERPRESSURE,
PRESSURE_RELEASE or BOWED_CAPTURE. Named segment profiles are versioned with
the renderer. Model 1 is frozen, including its complete loop policy, so existing
saved recipes retain their sound. Ordinary new patches and the original factory presets use model 2.
Both versions use the original voice/note seed for direct comparisons.

Model 2 makes EFFORT change the player trajectory and capture speed as well as
input work. REED changes the contact operating point and tuned loss; SHAPE
changes resonator geometry, loss and body radiation. GRAIN adds bounded,
player-linked flow or friction roughness before the pitched resonator. These
are source and resonator changes rather than effects added to the output.
LENGTH retains its finite gesture-duration role. HOLD uses narrower contact
ranges to maintain capture, with bounded preroll extending to seven periods
when necessary; the seam and full-state convergence limits are unchanged.

## Recipes and note semantics

`EmbouchurePatch` stores the engine/version, name, voice, macro map, renderer
model, MIDI base, velocity, fixed driver name and selected gesture. The macro map
contains TUNE and the six musical controls. Defaults fill omitted macros;
missing optional fields select the voice's initial configuration. An absent or
null renderer model in saved JSON selects model 1 for backward compatibility.
Unknown
renderer models, gestures, conflicting drivers and out-of-range macro or note
values are rejected. Every render starts fresh and derives its seed from the
voice/note infrastructure. `withMacros`, `Patches.edited`, `PadRecipe` and the
user-preset shelf preserve the saved configuration.

TUNE snaps from 0 to 24 semitones above the saved MIDI base. At the default 0.5,
the played note is twelve semitones above that base. Each voice's supported
register is `Embouchure.midiRange(voice)`; both saved base and resulting played
note must fit. The phone's TUNE slider restricts its range for a loaded preset
whose base is above the voice's minimum. The CLI's `--midi` selects the played
note directly and resets the relative TUNE offset to zero. This prevents the
usual default offset from moving an explicit note into another octave.

The initial supported registers are BASS C2–C4 (MIDI 36–60), WHISTLE C4–C6
(60–84), and REED/BRASS/BOW/SOFT C3–C5 (48–72). These are prototype bounds,
subject to capture probes and owner listening. HOLD at or above 0.999 selects
the loop; lower positions use the finite gesture.

Native velocity rendering retains the selected gesture, contact macros and
model. Existing velocity-zone generation peak-matches the resulting take for
the MPC's own velocity curve. Finite notes route as TONAL and HOLD routes as
LOOP through `Embouchure.drumClassFor`, preserving the source's pitched identity.
The generic audio classifier remains a separate diagnostic.

Classifier guards use short onset/body spectral windows with the actual finite
note's full release measurement. An arbitrary crop of a still-rising BASS can
read as TOM when the crop invents an early release. Saved host recipes retain
their explicit TONAL/LOOP routing.

## Factory presets and kits

The factory bank contains 36 Model 2 presets: 8 REED leads, 6 BRASS, 6 BASS,
4 WHISTLE, 6 BOW and 6 SOFT. Each catalog entry includes its saved player gesture,
musical role and description. `EmbouchurePresets.initial()` retains the accepted
first eight below, with their original names, order and exact recipes;
`all()` and `forVoice()` expose the expanded bank.

| Preset | Voice | Saved gesture |
|---|---|---|
| Leaning Reed | REED | LEANING_SWELL |
| Muted Bell | BRASS | PRESSURE_RELEASE |
| Breath Pocket | BASS | Voice default |
| Soft Entry | SOFT | SOFT_ENTRY |
| Short Overblow | REED | OVERPRESSURE |
| Relaxed Bow | BOW | BOWED_CAPTURE |
| Narrow Air | WHISTLE | Voice default |
| Held Effort | SOFT | LEANING_SWELL, HOLD 1 |

`SynthKits.embouchure()` places eight REED notes from the minimum register in
C minor pentatonic on A01–A08 and these eight presets on A09–A16. It is dry,
fixed and independent per pad, with the complete recipe on every pad. Held
Effort is a loop; the remaining pads are tonal finite notes. The FRESH TAPE
starter has ID `embouchure` and the SYNTH picker includes all six voices,
factory presets, editing, SCRAMBLE, saving and SEND TO PAD.

`SynthKits.embouchureFactoryBanks()` supplies three additional dry sixteen-pad
banks: Reeds and Air, Brass and Bass, and Bow and Soft. Each has twelve distinct
factory presets plus four complementary C-minor-pentatonic notes. Across the
three banks every factory preset appears exactly once, with the complete native
recipe retained. These exports do not change the original FRESH TAPE starter.

## Audition and export

Generate the listening pack with:

```sh
./gradlew :synth:generateEmbouchureAudition
```

The pack lives under `testkit/embouchure-audition/`. Its manifest and patch JSON
identify the actual note, velocity, macros and player profile; audio comparisons
include raw and matched renders so loudness does not conceal excitation changes.
The [private audition page](https://embouchure-audition.bertcalm.chatgpt.site)
hosts all 36 presets, per-preset soft/forte comparisons, the revised control
matrix, historical original/revised comparisons and all four kit downloads;
[pack notes](EMBOUCHURE_AUDITION.md)
describe the loudness rules, measurements and reproduction task.

Selecting a macro value plays the take from its attack. A/B compares the voice
baseline with that selection; Original/Revised compares the same note, velocity
and settings across renderer models. Listening notes are stored separately for
each model and loudness mode.

Render factory presets through the normal CLI path:

```sh
./gradlew :cli:run --args="synth EMBOUCHURE REED --all --out /tmp/embouchure-reed"
./gradlew :cli:run --args="synth EMBOUCHURE BASS --midi 48 --velocity .4 --out /tmp/embouchure-bass"
```

Save the FRESH TAPE starter and use SnipSnap's existing kit/export paths for
MPC WAV/program output. The patch is carried by `PadRecipe`; export is handled
by the existing host, including its velocity-layer conventions. The current
kit/drum-pad format has no attack-plus-sustain-loop field: Held Effort exports
as one settled loop-length WAV and a drum pad plays it once. Continuous repeat
on the audition page tests its wrap. Enable looping over each held WAV in the
sampler for continuing sustain. A sustained keygroup instrument is a
separate host workflow and is not implied by a LOOP classification.

The owner accepted the six voices and revised controls before authorizing the
expanded bank. The new presets have a separate listening verdict; this release
does not retune the accepted renderer. Numerical tests cover reproducibility,
bounds, root capture, macro activity, feedback dependence, source removal,
serialization, routing, the original eight recipes, complete bank coverage and
every exact held preset's convergence and seam metrics. Physical Android and
MPC playback still requires a device check.
They do not decide whether the voices have distinct musical identities or
whether a held transition sounds seamless. Listening, Android-device testing,
MPC-device playback and final naming remain acceptance work.
