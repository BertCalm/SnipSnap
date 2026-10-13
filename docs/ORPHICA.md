# ORPHICA microsound-harp prototype

ORPHICA adapts the XOmnibus microsound-harp idea to SnipSnap's deterministic
offline rendering pipeline. A tuned plucked string supplies each note's own
recent history. Short windowed grains revisit that history while the initial
attack stays clear. This is a first listening prototype; sonic acceptance
remains the owner's decision.

## Render contract

`Orphica.render(OrphicaConfig(...))` returns stereo 44.1 kHz audio. The three
materials, NYLON, STEEL and CRYSTAL, change exciter and feedback brightness.
Velocity changes contact and excitation brightness as well as level. The
string uses the existing phase-corrected `Pluck.ks` core at four times the
output rate, then decimates before the microsound stage.

Each note owns 250 ms of history. A grain copies only samples written before
its onset; reverse playback also uses this historical slice. CLEAR bypasses
the grain stage. STUTTER repeats a recent slice for an 80 ms block; SCATTER
varies its position; REVERSE reverses the sampled details. FREEZE snapshots
populated history at `captureSeconds`, then continues drawing from that
snapshot. No later note enters another note's memory.

The first 25 ms remain exactly dry. The wet balance ramps over the next 70 ms.
FRACTURE zero is an exact dry bypass. Actual summed Hann windows normalize
overlap without raising sparse-grain levels. Gentle seeded grain panning
preserves stereo; the dry source is centered. Dry and processed renders share
an 80 ms final release. There is no added reverb, shimmer or LOW/HIGH FX bus
in model 1.

## Saving and editing

`OrphicaPatch` saves the complete config and model version. The seed is a
decimal string, preserving every bit of a signed 64-bit integer. Patches,
`PadRecipe`, factory presets and user preset promotion preserve the same
renderer settings. Unknown models and invalid controls are refused.

| Macro | Meaning |
|---|---|
| TUNE | Semitone offset, ±12 around the saved MIDI root; 0.5 is neutral |
| PLUCK | Exciter brightness and contact position |
| FRACTURE | Dry-to-cloud balance; zero is dry |
| SIZE | Grain length, 8–120 ms |
| DAMP | String damping; higher darkens and shortens the source |

An omitted macro retains its saved config value. MIDI is limited to 21–108;
TUNE saturates at those endpoints. Grain rate is 2–100 Hz, finite notes are
0.2–12 seconds, and capture time is 0.03–1 second before the note ends.

The provisional roster contains Clear Harp, Gentle Scatter, Shattered Scatter,
Reverse Glass and Captured Sustain. CLI rendering is available, for example:

```sh
./gradlew :cli:run --args="synth ORPHICA NYLON --preset 2 --midi 60 --seed 42 --out /tmp/orphica"
```

This prototype is not yet in the phone SYNTH picker. MAKE INSTRUMENT, seamless
held loops, register-dependent effects and a live chord latch are future
integration work. FREEZE currently produces a finite captured sustain.

## Reproduce the listening pack

```sh
./gradlew :synth:generateOrphicaAudition
```

Published audition: https://snipsnap-orphica-audition.bertcalm.chatgpt.site

The task writes `testkit/orphica-audition/`: the listening page, 35 stereo clips, `manifest.json`,
`summary.json`, raw and matched WAVs, and replayable recipes. The collection
includes six core examples, six dry/processed pairs, FRACTURE travel, C3/C4/C5,
materials, velocity, capture times, an arpeggio and a chord beneath new notes.

Matched files use the house loudest-200-ms level measure. Raw files all receive
one common safety gain, recorded in the manifest, preserving relative levels.
Every delivered WAV is decoded and compared with its float render. Single-note
patches replay through the normal patch dispatcher; musical phrase recipes
contain an explicit schedule of saved note patches. Replay must reproduce the
same native samples, including the audition's common final fade.

The listening page plays only on request and provides matched/raw selection,
Stop all, individual WAV/recipe downloads, and browser-local review export.
Numerical and browser checks do not replace listening or phone/MPC validation.
