# OVEN in SnipSnap

OVEN is a first audition prototype of a struck keyboard instrument: a velocity-sensitive
hammer excites nearly harmonic stiff strings, heard through dark fixed-frequency body
coloration. Restrained companion strings beat as they decay. The
[XOven architecture](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/oven-architecture.md)
supplies the musical starting point: a heavy ring, felt-to-hard contact, bloom and
resonance. This implementation uses original synthesized material and no external samples.

The [listening page](https://snipsnap-oven-audition.bertcalm.chatgpt.site) is the first
sonic gate. GRAND, FELT, FORGED and BLOOM are articulations of the same core. Ten
small starter recipes await the owner's listening verdict.

## Preset revision 2 — 14 October 2026

The first listening verdict accepted the overall sound but found Quiet Room and
Felt Ledger too similar. Common-condition renders exposed further overlap in the
first roster: several recipes had nearly the same attack and decay, while their
different saved notes made those similarities less obvious.

Nine factory recipes now use wider existing macro settings; Open Lid and the DSP
model stay unchanged. Felt Ledger is a compact muted strike, while Quiet Room is
a clearer lingering note. The roster also separates an immediate dark body,
bright medium note, warm rounded rise, hard weighted strike, bright dry hit,
halo after-ring and a broad slow swell. Saved first-roster recipes retain their
original sound; the revision changes factory choices rather than their model.

The page's Same note section plays all ten recipes at C4, velocity .65 and neutral
TUNE. Before / after uses those conditions for each changed recipe, with Felt
Ledger and Quiet Room first. Original takes preserves the first published preset
recordings at their saved note and velocity. The generator verifies their raw
and matched WAV hashes against the tracked `oven-roster-r1.json` snapshot.

The regression screen compares relative upper-harmonic power, attack timing and
decay, before and after output leveling. It also checks gain/padding invariance,
the named felt pair and Afterglow's independently stored halo tail. These metrics
support the next listen; they do not establish universal audible distinctness.
Earlier browser reviews follow their exact waveform hashes and remain exportable.
Revised media uses fresh URLs to avoid replaying cached first-roster audio.

The updated MPC starter retains the first eight scale pads and includes Quiet
Room beside Felt Ledger in its upper eight recipes.

## Controls and signal

| Control | Behavior |
| --- | --- |
| TUNE | Saved MIDI root ±12 semitones, neutral at .5 |
| HAMMER | Longer dark contact → shorter bright contact |
| BODY | Fixed resonances, spectral loading and upper-mode loss |
| RING | Finite independent string decay; upper modes disappear first |
| BLOOM | Immediate attack → swelling entrance |
| HALO | Per-note baked harmonic resonance |

The primary string root remains exactly on the requested frequency. Higher modes
use `n * sqrt((1+B*n*n)/(1+B))`; stiffness varies with register. Up to 48 candidate
partials are excluded above the output bandwidth. The first sixteen receive a
restrained companion string. Each mode uses an exact damped rotation and an
independent T60. Modes receive a finite smooth force pulse; higher velocity changes
both energy and contact spectrum. Velocity zero is silent.

Every render starts fresh, runs at four times 44.1 kHz and decimates to mono 44.1 kHz.
Seeds depend on the saved model, articulation and effective note, so comparisons
do not acquire an unrelated random performance when a macro changes. Natural
duration accounts for the slowest enabled string, body or halo, bounded at 8.5 seconds.
The terminal fade protects the finite file. These sounds have no sustain loop.

This is a musical model, not measured cast-iron or Steinway data. The smooth hammer
pulse is a contact proxy, not a solved Hunt–Crossley collision. Stiff-string dispersion
describes the strings; body frequencies and losses are calibrated design choices.
Cast-iron impedance alone is not used to derive sustain or darkness.

## Saving and integration

`OvenPatch` retains articulation, macros, exact MIDI root, event velocity and model
version. Patches, factory presets, native velocity variants and `SynthKits.oven()`
use the same renderer. The sixteen-pad dry starter has a playable C-minor-pentatonic
scale and examples of the articulations; its recipes regenerate through `PadRecipe`.

This first gate covers the offline core, not a new phone synth-picker or MAKE
INSTRUMENT entry. The existing phone keygroup player uses gain for velocity and
would need sample-layer selection for faithful soft/hard keyboard playing.
Shared resonance reacting to notes played together and physical damper/pedal
transitions would also require additional playback DSP. The current HALO is saved
inside each note. Offline phrases mix independent rendered notes and make no claim
of reactive inter-note coupling.

## Reproduce the audition

```sh
./gradlew --no-daemon :synth:generateOvenAudition
./gradlew --no-daemon :synth:generateOvenKit
./gradlew --no-daemon test
```

Generated audio, exact recipes, diagnostic measurements and the manifest live under
`testkit/oven-audition/`, which is ignored by Git. Raw audition WAVs use the unlevelled
engine probe and one shared PCM gain across the pack, preserving strike energy.
Matched WAVs use `AuditionLevel`: .03 RMS in the loudest 200 ms window, with a peak
guard. The ordinary patch renderer uses the shared melodic output target instead;
the audition's raw and matched files are deliberately identified by their level rule.

Start with the sounds, then compare low/middle/high registers and soft/medium/hard
strikes. Five-position HAMMER, BODY, RING, BLOOM and HALO sweeps keep the other
controls fixed. Body and halo ablations retain the same string excitation; their
recipes store the probe flags. Phrase recipes store their event scores. The playable
page keyboard selects individually rendered soft, medium or hard samples for C4–C5.
Review notes stay in the browser and can be exported as JSON.

The downloadable MPC starter uses `SynthKits.oven()` through the existing native
`.xtd` and compatibility `.xpm` exporters, sharing sixteen WAVs and a regenerable
`kit.json`. The generator checks both program files through export read-back.
This new kit still needs listening on the MPC; format read-back is not hardware acceptance.

Numerical gates check deterministic replay, actual root support, bounded raw samples,
velocity/hammer brightness, body coloration, bloom arrival, tail extension, halo
causality, saved state and recipe regeneration. Those checks support the listening
decision. Acoustic-grand identity, phone playability and MPC hardware acceptance
remain open.
