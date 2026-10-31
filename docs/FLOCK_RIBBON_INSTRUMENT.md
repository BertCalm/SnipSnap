# FLOCK RIBBON: a playable captured sustain

Repository baseline: `6990bfde`. Luthier CONCEIVE implementation, 2026-10-23.
This extends the existing RIBBON voice; it does not add a new engine or factory preset.

The characteristic gesture is **a scattered opening → a moving pitched cloud → a
captured recurring body → release when the player lets go**.

## What is being adapted

The flock is literal: the existing 24-particle neighbor simulation supplies each
oscillator's pitch and energy. The recurring sustain is a sampler construction.
The equations do not converge to an exactly periodic orbit. The sample player
repeats a selected stretch of the cloud and applies its release after note-off.
The source remains dry, mono, 44.1 kHz, with four-times internal rendering.

The owner accepted the revised finite FLOW control and said the separate RIBBON
sustain study sounded pretty good. That supports taking the study into a playable
instrument. It does not establish acceptance of every register, velocity, phone
or MPC playback path.

Nearest siblings are RESIN, whose held oscillator ladder does not use a changing
flock graph, and ORPHICA, whose captured grain tails do not carry RIBBON's coupled
pitch trajectories. The lightest useful landing is a held mode on `FlockPatch`
and a `FlockPadMaker` through the existing instrument package writer and player.

| Direction | Gesture, novelty, risk and cost |
| --- | --- |
| PORT the accepted capture into RIBBON | Clear attack/body/release; adds a playable use to an existing voice; known finite loop recurrence; one short source render per root. Selected. |
| MUTATE the equations into a periodic orbit | Could remove the splice, but changes the accepted motion; high tuning and convergence risk; unbounded solve cost without a new model. Deferred. |
| STACK several staggered captures | Could mask recurrence, but adds a new mix gesture and multiple voices; higher memory and export complexity. Deferred. |

## Signal flow

```text
saved note, velocity, seed, five character macros, source model
                         |
                         v
     shared FLOCK simulation @420 Hz -> shared carriers @176.4 kHz
                         |
                         v
                  shared four-times FIR decimation
                         |
                         v
  two-second introduction + nominal six-second source capture
                         |
                         v
  endpoint search +/-100 ms -> final 500 ms correlation-aware blend
                         |
                         v
       capture-only common gain -> attack + loop PCM [cached]
                         |
                         v
 existing phone / MPC sample player -> note-off release, 0.1–1.5 s
```

The capture shares source and endpoint-search code with the accepted study. It
does not render the study's 31.5-second reference, expanded repeated playback or
full analysis on each edit. A small source continuation protects the symmetric
decimator at the latest possible capture endpoint. Capture-only leveling uses
the short production source; the study's pair gain remains a separate contract.
No new source filter or DC-removal stage is inserted into this established
renderer. The finite goldens protect its output path, and capture DC is measured
in the numerical probes and handoff report.

## Controls and saved state

| Control | RIBBON default | Model quantity / interaction | Proof |
| --- | ---: | --- | --- |
| SPREAD | 0.62 | Initial pitch span and separation bounds; interacts with PULL | Existing finite macro probes; held register report |
| PULL | 0.28 | Restoring force toward the played pitch; interacts with SPREAD | Narrow-cloud root probe at low, middle and high notes |
| FLOW | 0.78 | Neighbor alignment and model-2 simulation speed; interacts with SCATTER | Existing FLOW comparisons; held capture changes |
| SCATTER | 0.52 | Initial disturbance scaled by saved velocity | Soft/hard source comparison and zero-velocity silence |
| EDGE | 0.46 | Bounded second/third harmonic carrier weights | Existing carrier/macro probes |
| HOLD | 0.72 | Finite duration, two to six seconds | Existing finite goldens; ignored by held capture and its cache |
| FINITE / HELD | FINITE | Explicit delivery choice; HELD is RIBBON only | Legacy decode and saved-state round-trip |
| RELEASE | 0.60 s | Sample-player envelope after note-off | Reference-player note-off tests before/inside/near the loop |

Old JSON without playback state stays finite, including HOLD=1. Existing model-1
and model-2 finite arithmetic and all nine factory recipes stay reproducible.
Held state retains the same source model rather than silently upgrading model 1.
The exact signed 64-bit seed remains a canonical decimal string. Capture version
is saved independently from the source model so later loop changes cannot silently
reinterpret a saved held patch. Release survives switching back to finite mode.

Saved patch velocity changes the source disturbance and amplitude. Keyboard or
pad touch velocity in this first exported instrument applies playback gain to one
sample layer; it does not run a fresh flock or select a physically distinct layer.
New source performances require editing the saved patch velocity or seed in the
single-layer instrument. The later optional [touch-layer builder](FLOCK_RIBBON_TOUCH.md)
captures three source disturbances and selects them by key touch; this document
records the original single-layer handoff.

## Instrument and app contract

Nine rendered roots are spaced three semitones apart in a two-octave window
inside MIDI 36–84. The window shifts near the limits while retaining the saved
root and its pitch-class spacing. Default C4 covers C3–C5. Zone ranges split at
midpoints and stop at the sampled span. Interior playback transposes at most one
semitone, limiting the change in flock-motion speed that sample transposition
necessarily introduces.

Each sample contains its own opening and a positive loop start at two seconds.
The exclusive loop end is the file end. The WAV SMPL writer converts that to the
format's inclusive final frame. There is no baked release or fade at this file
end. Phone and MPC instrument playback continue the loop while releasing.

SYNTH uses a pre-rendered native sample for press/release audition. Rendering runs
away from the UI thread with cancellation. MAKE INSTRUMENT renders zones serially
and uses the existing `OneNote.writePackage` path for SnipSnap, MPC 2 and MPC 3.
The complete source recipe accompanies the playable sample manifest. SEND TO PAD
remains a one-shot destination with a delivery-only end fade recorded in
`PadRecipe`; reopening it retains the editable held patch. The held instrument
and its audition are dry.

## Render and memory budget

The production source is about 8.1 seconds plus filter context, independent of
how long the player holds a key. Rendering happens before playing a note.
Sample playback and note-off never invoke the flock simulation. Character edits,
note, velocity, seed or model changes require a new capture. Name, finite HOLD
and release changes reuse the capture.

The bounded cache keys the exact audible macro values, note, velocity, full seed,
source model, capture version and fixed capture settings. Cached PCM must not be
mutable through a returned buffer. Cancelled renders must not enter the cache.
Cold and warm timings, retained PCM size and exported bank size are reported by
the reproducible handoff generator. Host measurements are not target-phone latency
measurements; the tens-of-milliseconds edit target remains a hardware check.

## Risks and verification

- A selected endpoint can reduce blend cancellation without eliminating it at
  every recipe. Measure actual sliding blend power and the source-context wrap.
- A broad cloud can have weak pitch confidence. Report that observation; use a
  narrow-SPREAD, strong-PULL fixture for a meaningful tuning gate.
- FLOW=0 can retain initial gathering in this early capture. Repetition freezes
  later evolution; the accepted uninterrupted reference exposes that trade.
- Root spacing bounds ordinary transposition, but an MPC's interpolation and
  envelope still need physical playback checks.
- Short captures cost seconds of synthesis on some hosts. Serialize bank renders,
  cancel obsolete edits, and retain only a bounded cache.

The test gate includes finite WAV goldens, strict held decoding and recipe replay,
cache hit/miss/mutation/cancellation checks, actual capture diagnostics, low/middle/
high note tuning, source velocity, package boundaries and sample-player release.
The handoff includes playable files, source state, a manifest, checksums and
reference-player listening WAVs. The existing private finite/sustain auditions
remain the listening record for those earlier stages.

## Evidence ledger

| Claim | Status | Evidence |
| --- | --- | --- |
| Shared source, sample loop boundaries and native release paths exist | Verified | `Flock`, `FlockRibbonSustain`, `InstrumentEngine`, `KeyHit`, `InstrumentPlayer`, `OneNote`, read at `6990bfde` |
| Revised finite FLOW is audible | Heard by owner | Owner feedback on the published comparison |
| Sustained RIBBON is worth making playable | Heard by owner | Owner feedback on the separate sustain audition |
| All registers, touch response and phone/MPC playback are accepted | Open | Requires playing the new instrument on hardware |

Implementation measurements and gate results belong in the handoff report and
the pull request, with the exact tested revision.

## As built

The first Kotlin handoff run measured 363–446 ms per new native zone and 2.42 ms
for the cached root copy on this worker. Each source used 357,226 output frames
(8.10036 seconds including filter context). The cache is capped at 12 entries and
16 MiB of retained PCM; the nine-zone bank is rendered serially.

The default bank selected loop periods from 5.90143 to 6.09499 seconds. Its worst
50 ms blend dip was -3.95 dB and largest crest was +2.38 dB relative to the partner
power reference. Reloaded PCM24 peak stayed at or below 0.6322 and absolute mean
at or below 0.0000171. Nine reference-player clips, including source and keyboard
velocities, reached silence after note-off. These are worker measurements of
this capture, not a claim that every recipe is accepted or every splice inaudible.

The 43 focused FLOCK tests passed, including the existing finite WAV goldens.
The Android APK and device-test code compiled, and both host native test harnesses
passed. Full-suite and CI results are recorded on the implementation pull request;
device-test compilation is not device execution. Regeneration records its own
timings and exact committed source snapshot in the exported manifest.

## Why it is built this way

1. The opening is retained so each key starts as a performance instead of fading
   into an anonymous loop.
2. The blend uses nearby source context so the wrap keeps a natural waveform
   continuation. It does not pretend the flock itself became periodic.
3. Release happens in the player so letting go responds immediately and changing
   release does not rebuild eight seconds of sound.
4. Close rendered roots keep pitch and movement tied together. Wide transposition
   would speed or slow both and weaken the instrument's identity.
5. Held is explicit saved state. This protects finite presets and lets the same
   seed and model be edited and regenerated later.
