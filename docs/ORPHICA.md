# ORPHICA microsound-harp prototype

ORPHICA adapts the XOmnibus microsound-harp idea to SnipSnap's deterministic
offline rendering pipeline. A tuned plucked string supplies each note's own
recent history. Short windowed grains revisit that history while the initial
attack stays clear. On 2026-10-13 the owner heard the published first audition
and said, "Sounds pretty good to me". That finite sound is the approved working
baseline; captured HOLD sustain and device behavior are separate validation work.

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
| HOLD | Zero retains the approved finite sound; intermediate values blend into captured sustain; the top step returns loop-only material |

An omitted macro retains its saved config value. MIDI is limited to 21–108;
TUNE saturates at those endpoints. Grain rate is 2–100 Hz, finite notes are
0.2–12 seconds, and capture time is 0.03–1 second before the note ends.

The provisional roster contains Clear Harp, Gentle Scatter, Shattered Scatter,
Reverse Glass and Captured Sustain. CLI rendering is available, for example:

```sh
./gradlew :cli:run --args="synth ORPHICA NYLON --preset 2 --midi 60 --seed 42 --out /tmp/orphica"
```

ORPHICA is available in the phone SYNTH picker after PLUCK, with NYLON, STEEL
and CRYSTAL materials, five grain modes, explicit root controls and six macros.
Loading and editing a saved sound preserves its mode, capture time, physical
grain settings and full-precision seed. Stereo previews retain the same channel
image as the rendered sample. SCRAMBLE keeps the saved config and stays below
the loop-only HOLD step.

## Captured sustain and MAKE INSTRUMENT

HOLD defaults to zero. Existing finite recipes without HOLD still use the
original renderer, and their saved JSON fields remain unchanged. Sustained
recipes carry a separate `sustainModel` version, currently 1. HOLD below 0.99
crossfades the finite note toward a captured sustain after capture; HOLD at or
above 0.99 renders one complete steady cycle for a looping pad.

`Keys.orphicaPad` preserves the pluck attack and places a nonzero loop marker
after the capture transition has settled. The held renderer averages written
periods of the same string into a periodic carrier, limits its harmonics below
Nyquist, and uses a seeded circular grain schedule. Carrier and grain pattern
both close on the exported cycle; this is an explicit frozen-sustain
approximation rather than continuing the original string physics indefinitely.
At very low notes it waits for at least three written periods. If a late
capture has decayed below numerical usefulness, it retains an earlier
populated capture of the same note. It never captures a chord played live.

MAKE INSTRUMENT renders stereo zones across the selected audible root plus
or minus 12 semitones, bounded to MIDI 21–108. Minor-third roots and boundary
samples limit playback transposition to one semitone. The release control spans
0.1–1.5 seconds. Export uses the established `.xty`, `.xpm`, looped WAV and
phone-sidecar package, preserving frame-based loop markers in both generations.
Making an instrument does not turn the saved finite patch into a held patch.
At full HOLD both modes revisit a fixed captured source: FREEZE repeats one
captured phase offset, while SCATTER varies the offset between grains.

```sh
./gradlew :cli:run --args="synth ORPHICA NYLON --preset 2 --midi 60 --seed 42 --instrument --release 0.6 --out /tmp/orphica-instrument"
```

Register-dependent effects and a live chord latch remain future work.
Phone audio and MPC hardware validation still require the owner's devices.

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


## Integration validation — 2026-10-13

74 focused JVM tests passed across the renderer, held cycles, patch/recipe
compatibility, keygroup export, CLI, user presets and stereo frame playback.
Five pre-change native float fingerprints and all 70 published audition WAV
SHA-256 fingerprints remained exact. Independently evaluated sustain cycles,
actual per-channel wrap steps/slopes, 24-bit readback, tuning, late/low capture,
zero velocity and cancellation are covered.

The debug APK assembled for all four native ABIs, its signature verified, and
Android instrumentation test sources compiled. The three ORPHICA UI tests have
not run on a phone or emulator. Real phone audio and MPC loading/long-hold/release
checks remain outstanding.

A generated Gentle Scatter example covers C3–C5 in nine stereo zones with a
0.6-second release. The complete package includes the native `.xty`, the `.xpm`
twin, looped WAVs and the phone sidecar. Generated listening/instrument files
stay out of source control; the render commands recreate them.

Independent analysis of all nine delivered zones found correct stereo frame
counts and WAV loop metadata, wrap steps/slopes below ordinary waveform
maxima, and fundamental error below 0.012 cents by FFT and 0.097 cents by
unconstrained YIN. Stereo side/mid RMS remains 3.54–4.48%. These are waveform
and format checks, not a phone or MPC listening verdict.


## Current-host integration — 2026-10-19

The integration preserves SnipSnap's 42-engine picker, its current MAKE
navigation and the other held-instrument exporters. ORPHICA keeps a complete
per-material recipe in the navigation settings so mode, root, capture and seed
survive leaving the lab. SPREAD uses the resolved saved MIDI note, including
TUNE, across the full supported range instead of guessing the pitch.

104 focused JVM tests passed, including the current registry, patch decoding,
recipe replay and velocity contracts. All 70 approved audition WAV fingerprints
remain exact. The debug APK and Android test sources compile; six screen tests
cover preset editing, navigation, low/high SPREAD roots and held export. CI
emulator results are tracked on [PR #584](https://github.com/BertCalm/SnipSnap/pull/584).
Physical phone audio and MPC listening checks remain open.
