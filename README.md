# SnipSnap

An Android app for grabbing sound off your phone — from other apps, from video,
from the mic — trimming it into one-shots, laying them out on a 4×4 pad grid,
and exporting a drum kit your Akai MPC can load.

> You heard it. You snipped it. It's on pad A03.

**Status:** a tested pure-Kotlin core — capture buffer, cleanup DSP, the
synth engines, and writers for both MPC generations, **hardware-verified on
an MPC Live III** (native `.xtd` and compatibility `.xpm` kits load and
play) — and the Android app over it (`:app`: capture, the tape, the kit,
PLAY / KEYS / SURFACE on the native engine, synth, and export). What still
needs a phone or the Live III is [`docs/BENCH.md`](docs/BENCH.md). Where
the docs disagree with each other, start at [`docs/README.md`](docs/README.md).

## The loop

```
capture (rolling buffer)  →  trim  →  assign to 4×4 grid  →  export .xpm + WAVs  →  MPC
```

## Targets

| | |
|---|---|
| Platform | Android only, minSdk 29 |
| Hardware | Akai MPC Live III — the only device in hand and the only one tested against. The One and Live II should load the compatibility format, but that is [unverified and backlogged](reference/README.md#backlog-mpc-2). |
| Primary format | MPC 3 native (gzip + ACVS header + JSON) via `Mpc3TrackWriter` (`.xtd` + `_[TrackData]/`) — **hardware-verified on the Live III**: loads, plays, class colours, choke, embedded groove clip |
| Compatibility format | MPC 2-era `.xpm` drum program + 44.1 kHz WAVs, as a folder — **hardware-verified on the Live III** (diag kit: one beep on A01, 0-based numbering confirmed) |
| One-file sharing | `.xpn` ZIP archives — implemented via `XpnPackager`, pending the hardware import check |

## Modules

Nine of the modules are plain Kotlin/JVM with no Android APIs, so the fiddly
parts are unit tested on a normal JVM. `:app` is the Android shell over them
and joins the Gradle build only when an SDK is present (`settings.gradle.kts`).
It has no host unit-test source set; its Compose suites live in
`app/src/androidTest`, and the native engines have host tests under
`app/src/main/cpp/test`.

```
./gradlew test    # the nine JVM modules
```

### `:audio`

The capture and conditioning core.

- **`RingBuffer`** — the rolling capture buffer that makes retroactive snipping
  work. Fixed capacity, oldest frames overwritten, `snapshot()` hands back the
  most recent N frames in order. 60 s stereo @ 44.1 kHz ≈ 21 MB.
- **`Cleanup`** — the commit-time chain: DC offset → trim silence → normalize →
  fades. Order is deliberate; see the source.
- **`WavWriter`** — 16/24-bit PCM at 44.1 kHz, and it refuses a non-MPC rate
  rather than writing a file that loads wrong.
- **`Transients`** — energy-based onset detection with an adaptive threshold.
  Finds *hits*, not notes, which is the right bias for a drum sampler. Cuts land
  ~3 ms before the attack, never after.
- **`Chopper`** — turns one captured bar into a bank of pads, either following
  the hits or dividing evenly.
- **`Classifier`** — tags a snip as kick / snare / clap / hat / tom / perc /
  tonal / loop from cheap spectral and envelope features. Rule-based, so a
  wrong answer is inspectable instead of mysterious.
- **`AutoPlace`** — puts those on the conventional layout (kick A01, snare A02,
  closed hat A03, open hat A04) and mute-groups the hats so one chokes the
  other.
- **`Pitch` / `Scales` / `Tuner`** — in-key sampling: autocorrelation pitch
  detection, key/scale note grids (root on A01), and the retune that lands a
  captured tonal snip on the nearest in-key note using the tune fields an
  MPC pad already has. Unpitched material is never "corrected".
- **`Retune`** — the spectral retune: every partial on its own, talked into
  the key through the `Spectral` door with phases reinvented by `Pghi`, so an
  inharmonic clang lands in the scale instead of merely moving as a block.
  A drum is refused as unpitched, in words.
- **`Body`** — a bank of tuned resonators struck by the hit: the key's chord
  tones over three octaves, the root loudest, DECAY their ring.
- **`Eternal`** — the attack kept bit for bit, the tail slowed hyperbolically
  toward a frozen instant with `Pghi` phases, TAIL the knob.
- **`Desample`** (`:synth`) — the nearest THUMP patch to a captured hit, off a
  pre-rendered macro grid by `Similar`'s distance, the distance always told
  (`docs/DESAMPLE.md`).
- **`Loudness`** — perceived level (peaks aren't loudness), feeding kit-wide
  balance.
- **`Outside`** — the world as an effect (the Outsidify idea): the phone
  plays a pad or a sweep out of its speaker or jack and listens to what
  comes back; `align` finds *when* it came back by FFT cross-correlation
  (polarity flips reported), `reamp` cuts the return at the arrival and
  keeps the tail while it sounds, `impulse` deconvolves a sweep's return
  into the room's impulse response for ROOM OF ITSELF. The trip's latency
  is measured, never assumed. The pad sheet's OUTSIDE card (`OutsideSheet`
  in `:shell`) is the door; refusals — a clipped return, a silent room —
  come in words.

```kotlin
val buffer = RingBuffer.ofSeconds(60f)          // running in the capture service
// ... user taps snip ...
val snip = Snip(buffer.snapshotSeconds(8f), channels = 2, sampleRate = 44_100)
WavWriter.write(File(kitDir, "SS_Kick_01.wav"), Cleanup.process(snip))

// ...or chop the whole bar, classify each piece, and lay it out playably
val slices = Chopper.byTransients(snip, maxSlices = 16, cleanup = Chopper.SLICE_CLEANUP)
val classified = slices.map { it.snip to Classifier.classify(it.snip).drumClass }

AutoPlace.arrange(classified, padCount = 16) { it.second }   // kick -> A01, snare -> A02, ...
```

### `:kit`

The product pipeline: a kit **is** a folder (WAVs + a `kit.json` sidecar), and
this module owns that folder's whole life.

- **`Kit` / `KitPad`** — the model; `kit.json` round-trips it byte-stable.
  Each pad can carry a **recipe** — an opaque JSON block this module stores
  verbatim and `:synth` reads as a typed `PadRecipe` (engine patch + FX
  chain). A synth pad regenerates from scratch; a captured pad re-treats its
  raw audio. Tested promise: a kit folder rebuilds its own WAVs bit-for-bit
  from nothing but the sidecar.
- **`Project` / `ProjectStore` / `ProjectMigration`** — the unit of work
  ([`docs/DIRECTION_2026_10.md`](docs/DIRECTION_2026_10.md) §2): a folder
  under `Projects/` beside the kits that holds kits **by folder name**, one
  of them active, with its own beat under `beat/` in `groove.json`'s
  format. Kits stay where they are and are shared by every project; the
  migration adopts every unheld kit into a project of its own name,
  idempotently, copying its grooves across.
- **`KitAssembler`** — arranged snips in, kit folder out: the last step of the
  auto-chop pipeline.
- **`Preflight`** — the export wizard's checklist as real checks. WARNs export;
  FAILs block, because "exported but broken" is the worst thing a tool that
  writes to someone's SD card can do.
- **`KitExporter`** — kit folder → MPC program folder (`.xpm` + WAVs), names
  sanitized consistently between the folder and the program.
- **`Balance` / `InKey`** — kit-wide loudness balance (kick forward, hats
  tucked, WAVs untouched — only the program levels move) and kit-wide
  in-key retuning of tonal pads.
- **Velocity layers** — a pad can carry up to four velocity zones
  (`KitLayer`), written through to the XPM's per-layer velocity windows, so
  soft hits sound soft on hardware, not just quiet.
- **`MidiGroove` / `MidiWire`** — MIDI on disk and on the wire. The first
  reads and writes Standard MIDI Files for grooves; the second reads the
  live byte stream a keyboard sends (running status, real-time bytes
  mid-message, a message split across deliveries) into typed note and
  controller events, which `:shell`'s `MidiKeys` and `MidiPads` turn into
  what KEYS and PLAY do with them.
- **`XpnPackager`** — the same expansion as a single deterministic `.xpn`
  ZIP, for one-file kit sharing, in the layout real commercial archives use:
  `Expansion.xml` at the archive root, bare sample names (no real program
  populates `<SampleFile>`), previews in `[Previews]/`. See
  [`docs/MPC_EXPORT.md`](docs/MPC_EXPORT.md#xpn--implemented-and-two-of-our-three-layout-choices-are-wrong).
- **`ExpansionWriter`** — the tier-2 export: the same kit wrapped as a
  browsable expansion (`Expansions/<Title>/` with `Expansion.xml`, a
  1000×1000 tile, `Programs/`, `Samples/`) so it shows up in the MPC's
  Expansion tab, with a plain-text `manifest` alongside. The `Expansion.xml`
  schema is **confirmed correct** against commercial packs — we emit the
  standalone dialect, element for element; see
  [`docs/MPC_EXPORT.md`](docs/MPC_EXPORT.md#two-dialects-and-we-write-the-right-one).

```kotlin
val kit = KitAssembler.assemble("Break Kit", arranged, kitDir)
if (!Preflight.check(kit, kitDir).blocked()) {
    KitExporter.exportProgramFolder(kit, kitDir, sdCardRoot)
}
```

### `:synth`

CHAMBER's first listening prototype strikes a membrane, bar, cowbell and triangle
together in independently assigned wood/glass or concrete/aquarium regions.
HYBRID and AQUARIUM share geometric reflections, a late field and passive delayed
returns into the instruments. Six provisional recipes and complete saved rooms
work through the CLI and kit renderer, outside the phone picker pending listening.
Run `./gradlew :synth:generateChamberAudition` for raw/matched comparisons and
replayable diagnostics. [Implementation notes](docs/CHAMBER.md) distinguish the
finite prototype from the proposed HOLD, room editor and larger library. The
[published private audition](https://chamber-snipsnap-audition.bertcalm.chatgpt.site)
contains 114 clips with raw/matched playback and downloadable recipes.

CONFLUENCE's first recorded-source proof renders a small gathering from supplied
vocal regions: one to eight persistent performers, complete phrase responses,
staggered sustained vowels and a shared mono room. Run
`./gradlew :synth:generateConfluenceAudition` for attributed human-source,
raw/matched listening comparisons and portable prototype recipes. The
[audition](https://confluence-snipsnap-audition.bertcalm.chatgpt.site) awaits the
owner's listening verdict; [implementation notes](docs/CONFLUENCE.md) distinguish
this finite renderer from the later phone editor, larger crowd and HOLD stages.

THUMP, the analog-style drum voice engine — S1 of
[`docs/SYNTH_ROADMAP.md`](docs/SYNTH_ROADMAP.md), real. Eight voices (kick,
snare, closed/open hat, clap, tom, cowbell, rim), every macro normalized 0..1
and mapped onto bounded musical ranges, so SCRAMBLE is a uniform roll that
can't land on garbage. Patches serialize to JSON; `ThumpKits.classic()`
renders a full kit that flows through assemble → preflight → export like any
captured audio. The classifier is the test harness: a factory kick isn't done
until analysis calls it a KICK.

```kotlin
val snip = Thump.render(ThumpVoice.KICK, mapOf("TUNE" to 0.2f, "DRIVE" to 0.7f))
val rolled = Thump.render(ThumpVoice.SNARE, Thump.scramble(ThumpVoice.SNARE, Random(7)))
val aged = Crunch.process(snip, mapOf("BITS" to 0.7f, "RATE" to 0.6f))   // 1987 in a knob
```

CRUNCH is the character processor from the roadmap's S2: input grit,
zero-order hold at a lowered rate (the aliasing is the sound), bit-depth
quantization, and a steep three-pole output tone filter, with output peak
matched to input so character never masquerades as loudness. It works on
captured snips exactly as on synthesized ones, and identity survives it —
a crunched kick still classifies as a kick.

TINES is S3: FM percussion, deliberately small — two operators, a RATIO
macro snapped to seven characters (never a mistuning), BRIGHT driving the
modulation index, the bite baked in. Five voices (bell, chime, block, zap,
toy) that fill the factory kit's top row, so `ThumpKits.classic()` is now
sixteen pads from two engines.

S3.5 makes the grid musical: PLUCK is Karplus-Strong — a noise burst in a
tuned feedback loop, four body voices (kalimba, nylon, harp, koto), DAMP as
the knob that always sounds good — and TONEWHEEL is additive with the handle
people have loved for 90 years: eight drawbars, a PERC click register,
WARBLE, DIRT. Both snap TUNE to semitones, so pads get notes;
`SynthKits.melodic()` renders two octaves of A-minor-pentatonic plucks with
a row of organ stabs — the keys-on-pads bet, playable before any keygroup
work exists.

VELVET is subtractive, the playability king — a naive
saw/pulse unison pair over a sub, into a resonant SVF swept by its own
envelope. SHAPE walks saw → square → PWM on one knob; SQUEEZE is resonance
and envelope amount together, so the top of the knob is instant acid. Four
stab voices (bass, brass, squelch, chip), TUNE snapped to semitones. And the
roadmap's free bonus is cashed: `SynthKits.chip()` renders VELVET squares
and THUMP/TINES drums through one CRUNCH converter — the chip kit, maximum
kitsch, zero new DSP.

FATHOM is the bass engine, and it exists for the three things VELVET
structurally can't do: GLIDE, a pitch envelope travelling between notes and
available on every voice because a slide is a gesture rather than a timbre;
GRIND, two saws detuned by SPREAD and left to beat over a long tail, where the
hollowness *is* the interference; and GLASS, 2-op FM tuned for the bottom with
RATIO snapped so the knob can't land on a mistuning. DRIVE is owned by the
engine and sits before the filter — saturation makes harmonics and the filter
has to be downstream to shape them, which is why bass through an FX rack
distortion sounds like a blanket.

THAW is a pitched ice-plate engine: powered copper runners warm the contact
layer, changing brittle friction into a smoother sustain; delayed liquid
channels load and couple four plates, and cooling can release small stored
stress events. BRITTLE, RUNNER, MELT, CHANNEL, FROST and SHEET share that
material model. TUNE spans C3–C5; CONTACT, HEAT, FREEZE, CHANNELS and THICKNESS
shape it, and HOLD extends the gesture before its top step becomes a settled
sustain loop. Twelve presets, a dry sixteen-pad kit and the phone synth picker
use the same deterministic engine. Run
`./gradlew :synth:generateThawAudition` for raw and matched clips, diagnostics
and a listening page under `testkit/thaw-audition/`. The sound still awaits
the owner's listening verdict; [implementation notes](docs/THAW.md) describe
the model and its limits.

UNDERTOW is a pitched suction-shell engine: four weighted leather flaps
share one piston-driven reservoir, so an opening or seal changes the air
available to its neighbors. Ceramic rim catches excite the same hollow
object as the tuned breath. KNOCK, BREATH, FLUTTER, SEAL, HOLLOW and SURGE
share TUNE (C3–C5), DRAW, FLAP, WEIGHT, SPIRAL, LEAK and HOLD. Twelve presets
and `SynthKits.undertow()` use the deterministic dry engine; HOLD's top step
returns settled powered suction material. Run
`./gradlew :synth:generateUndertowAudition` for raw and matched clips,
pressure/contact diagnostics and a listening page under
`testkit/undertow-audition/`. The owner's sonic verdict remains pending;
[implementation notes](docs/UNDERTOW.md) describe the model, probes and limits.

SUTURE is a pitched bronze vessel whose opening ring stretches elastic cords
through wooden eyelets. Bounded stitchers close the gaps against vibration,
changing the cavity loading and bringing the seams into contact. BLOOM,
THREAD, CLOSE, MURMUR, STRAIN and SHELL share TUNE (C3–C5), GAP, STITCH, CORD,
SEAM, CAVITY and HOLD. Twelve presets, a dry sixteen-pad kit, FRESH TAPE and
the synth picker use the same deterministic renderer. Run
`./gradlew :synth:generateSutureAudition -PsutureFirstListen` for the smaller
listening set, or omit the property for the full raw/matched and diagnostic
gate. [Implementation notes](docs/SUTURE.md) explain the model and HOLD's
settled-loop format. The sound awaits the owner's listening verdict.

SPOOL is a returning recording machine: three original synthesized string,
choir, flute or brass performances share a loaded transport and overlapping
forward/backward pickup heads. Bowed harmonics, vowel formants, flute airflow and
bright brass overtones retain their character through the blend. Clean settings
keep both layers on the requested
note together with gentle tape wow/flutter. STRAIN shapes their journey,
RETURN sets the swelling backward layer,
IMPRINT leaves delayed traces in neighboring strips and SPLICE
reorders short sections. Thirty-six dry presets, a sixteen-pad starter, the synth
picker and CLI use the same serialized machine. HOLD loops an explicitly
frozen imprint snapshot. Run `./gradlew :synth:generateSpoolAudition` for the
full preset and musical audition; [implementation notes](docs/SPOOL.md) explain its
export semantics. The original six sounds have dry sonic approval; the expanded
bank adds 30 recipes while preserving those originals and the starter kit.

RELAY makes a compact timbral phrase inside one pitched note: a rounded
pluck hands off to harmonic body, bell detail and soft tone, with accents
scheduled independently of the order. KEY, BASS, PLUCK, ORGAN, CHIME and FLOW
share ORDER, PULSE, BLEND, COLOR, LENGTH and HOLD, plus the host's TUNE
control. Thirty-six dry recipes retain the six earlier seeds unchanged:
eight KEY, six BASS, six PLUCK, six ORGAN, four CHIME and six FLOW presets.
The SYNTH panel exposes native VELOCITY and note names. Complete phrase recipes,
sixteen distinct starter pads, FRESH TAPE and the synth picker use the same renderer;
explicit kit selections keep the starter at sixteen pads as the bank grows.
Run `./gradlew :synth:generateRelayPerformanceAudition` for the 76-clip page:
the full bank, six musical performances, three velocities per voice and every
starter pad, with raw/matched WAVs under `testkit/relay-performance-audition/`.
The earlier `generateRelayAudition` engineering matrix remains available.
[Implementation notes](docs/RELAY.md) explain its clock and loop behavior.
The expanded bank is a listening and playability candidate; hands-on device
verification and the owner's listening verdict remain open.

TESSERA is a pitched mixed-material engine: wooden bars, paired string courses
and tubes share a frame inside a chamber whose returning paths change with
the gesture. Six voices, eight presets and a dry sixteen-pad kit use MATERIAL,
HAMMER, SCALE, FOLD and MOTION, with C3–C5 TUNE and settled HOLD loops. Run
`./gradlew :synth:generateTesseraAudition` for its raw/matched listening page
and causal probes under `testkit/tessera-audition/`. The
[private audition](https://tessera-snipsnap-audition.bertcalm.chatgpt.site)
contains 297 dry clips and diagnostics. The sound awaits the owner's listening
verdict; [implementation notes](docs/TESSERA.md) describe the render, saving
and export contracts.

STOOP is an original groove module with six families: BASS, TINE, CLAV,
ORGAN, LEAD and CHORD. TOUCH, COLOR, SHADOW, WEAR and LENGTH shape dry,
preset-first sounds; HOLD exports settled loops. Recipes save an explicit
MIDI root, velocity, source configuration, independently articulated
shadow and explicit chord intervals. A provisional 48-preset roster and
dry sixteen-pad starter kit share the phone and CLI renderer. Run
`./gradlew :synth:generateStoopAudition` for presets, phrases, macro
comparisons and repeated-loop listening. The owner's listening verdict
is pending; [implementation notes](docs/STOOP.md) record the contracts.

PITCHWHEEL is a pitched wooden wheel whose teeth catch flexible fingers while
resin filaments stretch, bow and release. CLUNK, PLUCK, DRAW, RECOIL, THAWED
and TURN share PUSH, TOOTH, ADHESION, HEAT, BODY and HOLD. Patches retain an
explicit MIDI root (24–96), strike velocity and model version; TUNE is a
relative ±12-semitone offset, neutral at 0.5, so the effective note spans
12–108 without clamping back to the saved-root range. Seven provisional presets
and a dry sixteen-pad C minor pentatonic kit are included, with PITCHWHEEL among
FRESH TAPE's 41 starters. Run `./gradlew :synth:generatePitchwheelAudition`
for the compact first listening set, or add `-PpitchwheelFull` for the broader
dry probes. HOLD's top step returns settled loop-only material with a
deterministic attachment cycle and capped adhesion. The owner heard R1 as six
similar struck sounds distinguished mainly by rhythm. Model 2 strengthened
wooden body and TOOTH character, shaped contact rise, and gave finger friction
and filament slip separate transfers into the same resonator. The owner's
v2 review marked five voices Revise and found PLUCK most different; no voice
received Keep. Provisional model 3 changes contact projections and individual
mode losses to retain different body colours through decay. DRAW and THAWED
have slower contact rise; THAWED's release balance follows temperature. PLUCK
retains its model-2 release calibration. The new audition includes actual
bowing, reverse encounters, warming and powered-loop probes alongside the
release-only hits.
New patches use model 3; saved models 1 and 2 retain their respective V1 and
V2 renderers. JSON with an absent or null model selects V1. Velocity variants
preserve the stored model. The revised dry audition awaits approval; the
seven-preset scope stays fixed until then.
[The audition page](https://pitchwheel-audition.bertcalm.chatgpt.site) opens with
six release-only resonator hits and includes the real moving mechanisms and
preserved models 1 and 2 for comparison.
[Implementation notes](docs/PITCHWHEEL.md) record the provisional model and limits.

LACQUER is a layered-tone engine for finished keys, glass, synthetic brass,
bass, plucks and broad sustains. RELIEF separates independent body, edge and
trail articulation; SHEEN, BODY, CONTOUR and GLUE shape their common finish.
Thirty-six recipes, six per voice, retain explicit layer models, MIDI root and
velocity. The original eight remain unchanged as the reference bank.
HOLD's top step supplies settled loop material, with periodic layer contours
preserving RELIEF after the initial arrivals have passed. Run
`./gradlew :synth:generateLacquerAudition` for native/matched samples, layer solos,
macro sweeps and interaction grids. [Implementation notes](docs/LACQUER.md)
describe the contracts. The [published audition](https://snipsnap-lacquer-audition.bertcalm.chatgpt.site)
is private to the owner's account and includes three twelve-preset MPC bank
programs plus the original dry sixteen-pad starter. The expanded recipes are
available for listening review; physical device validation remains a bench step.

WICK is a first listening prototype: one pitched complex source feeds three
recovering low-pass gates, and actual releases ignite later articulations.
PLUCK, WOOD and BUBBLE share SPARK, FEED, CASCADE, TIMBRE, RECOVERY and HOLD, with
saved MIDI 24–84 roots and velocity. Thirteen provisional recipes stay outside
the phone picker and factory roster pending listening. Run
`./gradlew :synth:generateWickAudition` for dry native/matched clips, three-voice
musical phrases, BUBBLE tone comparisons, control sweeps, held loops and causal traces. The
[private audition](https://wick-snipsnap-audition.bertcalm.chatgpt.site) and
[implementation notes](docs/WICK.md) describe this first round; the remaining
three proposed voices and full preset library follow the listening verdict.

TANGLE is an experimental pitched engine whose two oscillators influence,
catch and release each other. BASS, LEAD, HOLLOW, PULSE, METAL and KNOT share
TANGLE, CHASE, CATCH, BITE, WEIGHT and HOLD, with explicit MIDI 24–84 roots
and velocity. Thirty-six provisional presets and a dry sixteen-pad C minor
pentatonic kit are registered in the picker, preset/recipe pipeline and
FRESH TAPE. Run `./gradlew :synth:generateTangleAudition` for its dry clips
and capture/control diagnostics. The
[private audition](https://tangle-snipsnap-audition.bertcalm.chatgpt.site)
awaits the owner's listening verdict; [implementation notes](docs/TANGLE.md)
describe the six controls, saved format and held-loop constraints.

CREASE's ROUND, PINCH and RUBBER voices bend and recover through positive
phase maps. The six approved model-3 presets are registered in the SYNTH picker,
native patch/preset pipeline, CLI and a dry sixteen-pad FRESH TAPE starter.
Saved patches retain model, MIDI root and playing velocity; finite notes file as
TONAL and only the top HOLD step files as LOOP. The
[private audition](https://snipsnap-crease-audition.bertcalm.chatgpt.site) provides
listening references, native patch downloads and reproducible source.
[Implementation notes](docs/CREASE.md) cover controls and model compatibility.

OVERLAP is a finite six-delay resonator: a strike rings
through selectable self, three-cycle, reflected-pair or six-cycle routing.
KNOT, PULSE, LOCK, GLOW, RING and finite HOLD shape its response. The picker
exposes four topologies, ROOT and VELOCITY; edits, saved presets and pads retain
the recipe's model. The twelve approved `OverlapMusicalBank` recipes enter the
factory roster through `OverlapFactoryPresets`: four plucks, four finite textures
and four breaths. FRESH TAPE's fixed OVERLAP starter preserves those twelve on
A01–A12, then adds octave-lower copies of the four plucks on A13–A16. All sixteen
are dry TONAL one-shots with complete finite tails.

Run `./gradlew :synth:generateOverlapKit` for `testkit/SnipSnap Overlap Kit/`
and `testkit/SnipSnap-Overlap-Kit.zip`: an MPC 3 track, its MPC 2 twin and a
Source Kit with editable recipes and native mono 44.1 kHz PCM24 WAVs. Run
`./gradlew :synth:generateOverlapMusicalAudition` for fifteen dry clips, including
three scheduled musical studies, under `testkit/overlap-musical-audition/`.
Native presets preserve regular renderer leveling and velocity; native studies
use a common `.25` mix gain. Matched clips use the `.03` audition target. The
bank ZIP contains native WAVs, recipes and study scores with complete pad recipes
and frame offsets. This is audition revision 3 on unchanged engine model 2;
the first, second and musical auditions retain their audio. The
[private audition](https://overlap-snipsnap-audition.bertcalm.chatgpt.site) also
provides the kit handoff. ROOT is a geometry reference; strongest perceived pitch
can follow another family. HOLD stays finite and breathing has no tempo sync;
held-note support is a separate future step. Android compile/device CI and real
phone validation of this integration remain pending. [Implementation notes](docs/OVERLAP.md)
include the export layout, verification plan and manual phone pass.

FLOCK is a finite oscillator-flock engine: twenty-four oscillators
scatter and respond through changing local neighborhoods around a played note.
GATHER, RIBBON and SPLIT share SPREAD, PULL, FLOW, SCATTER, EDGE and HOLD;
HOLD is two to six seconds, with no loop mode. SYNTH exposes nine factory
recipes with saved ROOT, VELOCITY, exact 64-bit seed and rendering model.
FRESH TAPE adds a dry sixteen-pad starter: eight C minor pentatonic GATHER
notes and eight preset contrasts. Run `./gradlew :synth:generateFlockKit`
for a shared-audio MPC 3/MPC 2 kit with complete editable pad recipes.
Run `./gradlew :synth:generateFlockAudition` for real raw/matched WAVs,
fixed/independent comparisons and one-particle disturbance measurements.
The [private audition](https://flock-snipsnap-audition.bertcalm.chatgpt.site)
and [implementation notes](docs/FLOCK.md) describe the proof and its limits.
After the first listen, model 2 gives FLOW a slow-to-fast clock for the flock;
original model-1 recipes and each voice's default sound remain exact. The
audition includes five Previous/Revised FLOW pairs, with its other clips retained.
The owner confirmed the revised FLOW difference is audible; phone and MPC
hardware checks remain open.

EMBOUCHURE models a virtual player feeding a pitched resonator through reed,
lip or bow contact. Six voices share EFFORT, REED, SHAPE, GRAIN and LENGTH,
with relative TUNE, native velocity, saved playing gestures and settled HOLD
loops. Thirty-six dry factory presets are available in SYNTH and the CLI;
FRESH TAPE keeps the original sixteen-pad C minor pentatonic starter. Run
`./gradlew :synth:generateEmbouchureAudition` for the complete listening page,
control matrix and four MPC 2/3 kits under `testkit/embouchure-audition/`.
The [private audition page](https://embouchure-audition.bertcalm.chatgpt.site)
contains the revised control matrix, original/revised comparisons and the
downloadable starter and three factory banks covering all 36 presets. Model 2
strengthens the controls while preserving all six default voices; saved model 1
recipes retain their original render. The owner accepted the voices and revised
controls; the expanded presets now have their own listening review.
[Implementation notes](docs/EMBOUCHURE.md) describe the prototype registers,
driver approximations, recipes and held-sample export limits.

TRIO brings three plucked performers and changing alliances into a finite pad
performance. TRES, BERIMBAU, CHARANGO and ENSEMBLE share TOUCH, DRAMA, SIDES,
MOTION, SPACE and DAMP, with saved root, velocity, tempo and phrase length.
The sixteen-preset bank is also the TRIO starter kit. Husbands make one strike
per trigger and ring out while the wives retain their strum. Rebuild the
musical audition and MPC 3/XPM kit with `./gradlew :synth:generateTrioMusicalBank`.
[Implementation and listening notes](docs/TRIO.md)

MURMUR combines four-operator FM with a moving formant bank across BASS, KEY,
PLUCK, LEAD, CHOIR and METAL. MOUTH, EDGE, BREATH, MOTION, BODY and HOLD shape
the dry voice; MIDI root (36–96), velocity and the complete model configuration
remain saved with each patch. Thirty-six dry presets and a sixteen-pad
C minor pentatonic FRESH TAPE starter are included. Run
`./gradlew :synth:generateMurmurAudition -PmurmurFull` for the dry listening
matrix and standalone page. [Implementation notes](docs/engines/MURMUR.md)
describe the approved six-voice sound, settled HOLD loops and the expanded bank's
listening review.

OSPREY adapts XOsprey into a finite SWELL articulation. The original sound was
positively received, with a request for slightly more distinct voices. R2 was
rejected as harsh and for losing that character. Model 3 retains the original
ocean, calls, envelopes and defaults, with a small coast-specific adjustment to
the existing modal body balance. SHORE, SEA, MOTION, DEPTH, SPACE and finite HOLD
shape the gesture. Fifteen dry presets, a sixteen-pad starter, the SYNTH picker
and CLI share saved note/velocity/seed/duration/model recipes. Models 1 and 2
remain frozen for existing pads. Run `./gradlew :synth:generateOspreyGentleAudition`
for paired R1/R3 renders and creature-muted body comparisons. The original
46-clip study remains preserved. After the [private audition](https://snipsnap-osprey-audition.bertcalm.chatgpt.site),
the owner said, “We are in a better spot audio wise.” R3 is the working audio
baseline. The owner subsequently confirmed, “Sounds good in app, let's proceed.”
In-app listening is confirmed; save/reopen, recipe replay and MPC playback checks
remain open. The [MPC listening pass](docs/OSPREY_MPC_PASS.md) is next. Run
`./gradlew :shell:generateOspreyHandoff` for the existing sixteen-pad starter as
a SnipSnap working folder, native MPC 3 export and compatibility twin, with raw
renders, recipes and checks. [Implementation notes](docs/OSPREY.md) describe the
three models, audition and hardware handoff.

OPENSKY adapts the XOpenSky concept into four stereo voices: ANTHEM, SOAR,
HALO and CRYSTAL. RISE, WIDTH, GLOW and AIR shape the source and its octave
room; eight accepted Round 3 recipes retain their complete timbre, root,
velocity, gate and wet state when saved. The SYNTH picker, a sixteen-pad
C minor pentatonic starter, CLI rendering and HALO held-instrument export
share these production recipes. [Integration notes](docs/OPENSKY_AUDITION.md)
record the audition baseline and the remaining phone listening checks.

REVEL is a pitched circular ensemble: friction gestures, pressure-bent
articulation, alternating rolls and broad pulses share a passive floor. One
to three moving microphones hear the same performance through distinct
bounded acoustic paths, summed to mono. PLAY, SKIN, ORBIT, WEAVE and REACH
shape the ensemble; the host TUNE control spans C3–C5 and HOLD exports settled
loop material. Ten provisional presets and a dry sixteen-pad kit retain
microphone configuration, phrase timing, seed and velocity in their recipes.
Run `./gradlew :synth:generateRevelAudition` for the complete raw/matched
listening matrix, or add `-Pquick` for a first listening set. The
[private audition](https://snipsnap-revel-audition.bertcalm.chatgpt.site)
is the owner's sonic acceptance gate;
[implementation notes](docs/REVEL.md) describe the model and validation limits.

REGISTERS adds PIPE, MUSETTE and TRANSISTOR to the phone's SYNTH picker.
PIPE keeps the selected model-3 chiff and soft sustained airflow; MUSETTE has
three beating reeds and a bellows contour. TRANSISTOR adds square-wave octave
ranks, immediate keying, electronic key-click, vibrato and transistor drive.
Its latest body revision adds a rounded same-pitch foundation while retaining
the opening click and all original model-4 recipes.
Nine starting settings, saved recipes, native velocity and CLI rendering share
the same renderer. MAKE INSTRUMENT preserves the finite attack before a verified
held loop, then writes the existing phone, MPC 2 and MPC 3 keygroup package.
[The audition](https://snipsnap-registers-audition.bertcalm.chatgpt.site?study=depth&voice=TRANSISTOR#listen)
compares Original / More body TRANSISTOR alongside the working PIPE/MUSETTE
baseline and retains the original chiff and airflow comparisons. TRANSISTOR
awaits listening review;
[implementation notes](docs/REGISTERS.md) describe compatibility and export behavior.

PRISM is a preset-first additive engine: 32 partials in five groups make keys,
plucks, organs, basses, glass and sustained veils whose harmonics develop at
different rates. GRAIN, DRIFT, SHAPE, STRIKE and BODY shape the dry note; HOLD
exports settled periodic loops. Thirty-six factory presets, a dry sixteen-pad kit,
patch recipes, CLI and the phone picker share the Kotlin renderer. Run
`./gradlew :synth:generatePrismAudition` for raw/matched voices, macro sweeps,
interaction grids, velocity and loop probes. The
[published audition](https://prism-snip-snap-audition.bertcalm.chatgpt.site)
includes the complete factory bank and the original/revised GLASS comparisons.
[Implementation notes](docs/PRISM.md) describe the controls and contracts.

SHOAL separates resonant bodies from their movement: WOOD, BOWL and WIRE are
animated by GLASS or RIPPLE energy transport. Velocity changes contact and
excitation as well as level. ROOT and VELOCITY remain independent of DENSITY,
CURRENT, COHESION, STRAY, GLOW and HOLD. Model 3 supplies six dry material
recipes, two harmonic HOLD recipes, a sixteen-pad starter, saved patches, CLI
`--material` selection and the phone material picker. The material bodies play
finite notes with passive tails; HARMONIC retains the moving HOLD approximation.
Saved model-1 and model-2 recipes retain their sound. Run
`./gradlew :synth:generateShoalMaterialAudition` for the playable comparison, or
`generateShoalAudition` for the preserved model-2 listening matrix.
[Implementation notes](docs/SHOAL.md) describe the material and loop contracts.

OVEN is a struck keyboard instrument: velocity-sensitive
stiff strings, restrained paired-string beating, dark fixed body coloration and
a baked resonance halo. GRAND, FELT, FORGED and BLOOM share TUNE, HAMMER, BODY,
RING, BLOOM and HALO. Ten approved factory recipes and a dry sixteen-pad kit use
the same finite mono renderer as the phone SYNTH picker. ROOT and VELOCITY select
the note and strike; edits, saved presets and pad recipes retain both and the model.
MAKE INSTRUMENT and live shared resonance remain later work.
Run `./gradlew :synth:generateOvenAudition` for raw/matched notes, soft/medium/hard
keyboard samples, five-point sweeps and causal comparisons. The
[published audition](https://snipsnap-oven-audition.bertcalm.chatgpt.site) is the
approved listening baseline. [Implementation notes](docs/OVEN.md) describe its scope and limits.

ALLOY is an original attack-driven four-operator PM engine: generated wood,
tine/string and breath/contact attacks enter the tonal body through a bounded
modulation port. BASS, KEY, PLUCK, BRASS, STRING and SOFT share MATERIAL, FUSE,
EDGE, BODY, LENGTH, TUNE and HOLD. Thirty-six factory presets, saved operator and
material recipes, native velocity and a dry sixteen-pad kit use the same renderer.
Run `./gradlew :synth:generateAlloyAudition` for voice/register/velocity coverage,
five-point sweeps, interactions, FUSE ablation and settled loop listening. The
[published audition](https://snipsnap-alloy-audition.bertcalm.chatgpt.site) includes
six presets per voice. The owner heard the initial twelve and authorized this
expansion on the same engine tuning; the new presets await listening review.
[Implementation notes](docs/ALLOY.md) describe the render and export contracts.

CONTOUR is an original wavetable instrument: a stable body carries the note while
a related edge takes a different spectral path. BASS, KEY, PLUCK, LEAD, HOLLOW
and SWELL share SCAN, OFFSET, MOTION, FILTER, DRIVE and HOLD, plus host TUNE.
Eight generated table families, nine provisional dry recipes, a sixteen-pad kit,
excitation-sensitive velocity and settled HOLD loops use the existing pipeline;
held keygroup export (MAKE INSTRUMENT, `--instrument`) does not route CONTOUR yet.
The existing CONTOUR rack filter keeps its saved effect recipes. Run
`./gradlew :synth:generateContourAudition` for the listening matrix; the proposed
36-preset expansion waits for sonic approval. Listen on the
[published audition page](https://snipsnap-contour-audition.bertcalm.chatgpt.site)
(owner-private). [Implementation notes](docs/CONTOUR.md)
cover the patch format, controls and verification.

SKITTER is a pitched electronic circuit inspired by the EDP Wasp: two oscillators
depart and recover at unequal speeds, while their edges and seeded noise provoke
a nonlinear filter with signal history and bounded flutter. BASS, LEAD, HOLLOW,
FLUTTER, CHIRP and FRAY share BUZZ, SKITTER, AGITATE, VENT, STATIC and HOLD.
Patches save an explicit MIDI root (24–84), excitation velocity and seed.
Thirty-six provisional dry presets, a sixteen-pad kit, FRESH TAPE, CLI rendering
and the phone synth picker use the same engine. Run
`./gradlew :synth:generateSkitterAudition` for the listening matrix, raw/matched
WAVs and diagnostic evidence under `testkit/skitter-audition/`.
The [audition page](https://skitter-snipsnap-audition.bertcalm.chatgpt.site)
supports the owner's listening verdict; [implementation notes](docs/SKITTER.md)
describe the coupled model and HOLD approximation. Sonic approval and device
validation remain open.

SKIN is a second drum engine, S6 of the roadmap: where THUMP is built from
oscillators shaped by envelopes, SKIN is modal — KICK, SNARE, and TOM sum
decaying sine partials at inharmonic ratios, the textbook recipe for a
struck membrane ringing at a few independent modes, while STICK is the
single-partial limit of that same
idea, one short high mode standing in for a rimshot click; HAT_CLOSED,
HAT_OPEN, and RIDE run continuous noise through a bank of tuned resonant
filters instead of an impulse (a single-hit excitation can't outlast a
filter's own damping floor at these frequencies, so the noise has to keep
feeding it); SHAKER runs noise through one deliberately wide, non-resonant
filter — a band, not a tone. Eight voices, classifier-verified against
THUMP's own `DrumClass` gates wherever a dedicated class exists, PUNCH on
every voice — SKIN shares that stage with THUMP alone; it isn't a
cross-engine macro.

RESIN is the ladder engine, S8 of the roadmap: four one-poles in a row, the
last fed back to the first and soft-clipped inside the loop (`Dsp.Ladder`,
measured before it was used — 24 dB an octave, a passband that thins by
1/(1+r) as resonance rises, a top that sings). Three oscillators feed it
and STACK is their mixer on one knob; CONTOUR is the filter envelope's
amount and speed together; CREAM is the feedback. BASS, LEAD, BRASS.
CONTOUR is also a rack section — the same filter swept from a captured
hit's onset — because the synth's job is to sit with what was captured.

RESIN also holds. `MAKE INSTRUMENT ▸` on SYNTH (and `snipsnap synth RESIN
<VOICE> --instrument` on the desk) renders the sound as a nine-zone keys
instrument that sounds for as long as a key is down, with its own ATTACK
and RELEASE: the Organ's whole-period loop, generalized. The detuned square
is snapped so each loop holds exactly one beat, and the pitch moves by a
fraction of a cent so the loop is whole frames, so every seam measures at
floating-point noise. Held, CREAM stops at the self-oscillation threshold,
the one place a loop cannot close.

RESIN also drones. `DRONE TO LOOP ▸` on SYNTH (and `snipsnap synth RESIN
<VOICE> --drone` on the desk) puts one long note on the next empty loop-grid
track, breathing through the ladder (MOTION, up to ±2 octaves, 1, 2 or 4
BREATHS per loop). The track holds a recipe, not audio: LOOP renders it at
its own tempo, spanning as many intervals as it takes to keep the note
within 3 cents, with every oscillator and every breath completing whole
cycles in the loop, so the wrap repeats to the bit. A tempo change re-slices
and re-renders it, and a drone still rendering is silence on its own track,
never a stall on the other five.

The string machine is two halves, and the rack has the second: a saw stack
under a filter is the source, and ENSEMBLE (below) is the chorus that makes
it a section. VELVET BRASS's STRING MACHINE and THIN STRINGS and RESIN
BRASS's WIDE STRINGS and DARK STRINGS are the source half, and they carry
the other half with them — SEND TO PAD lands one with ENSEMBLE already in
its recipe (stereo, regenerated bit for bit like any synth pad), the panel
auditions it through the same chain, and `snipsnap synth VELVET BRASS
--preset 13` writes the same stereo file. The chain follows the sound, not
its name: move a slider and it is your sound and lands dry. They are short,
decaying pads, about a second, because a VELVET or RESIN one-shot always
decays; SPREAD, MAKE INSTRUMENT and DRONE TO LOOP still make them dry.

TIDE is the West Coast engine, S9 of the roadmap, and RESIN's opposite:
where the ladder cuts harmonics out of a rich wave, TIDE builds them into a
plain one. A sine is phase-modulated (WARP — phase, not frequency, so the
note never moves), bent through a wavefolder (FOLD — a triangle core into a
sine shaper, so FOLD 0 is a clean sine and each unit of drive adds a fold),
and closed by a low-pass gate: one control on a VCA and a low-pass
together, keyed to the note and slowing as it falls, so brightness and
level close at once. That coupling is the "bongo"; GLOW sets how much
brightness outlives the level, from the classic dark knock to a tail that
keeps its harmonics. Past the line of tidy, the edge is built in: the
modulator dives into place in the strike (a zap), the fold feeds back into
the modulator (a snarl, held short of noise), the fold's symmetry turns
over across the note, and small seeded jitters keep a held note moving.
Under it all sits the oomph: every strike thumps (it starts sharp and
drops onto the note in a few milliseconds) and a clean sine carries the
note itself under the fold. BONGO and DRIP add CLICK, the stick on the
skin, at 0 until you turn it up.
WANDER nudges fold, decay and WARP per note from a seed the recipe itself
carries, so a kit still regenerates to the bit.
BONGO and DRIP are struck hand percussion, PERC by the classifier; on GONG
and FLARE a long DECAY holds the gate open first, so they are notes, and
TONAL.

SIREN is the dub siren, and the first engine in the picker that is a
*movement* rather than a strike: a square-wave tone whose pitch a slow
oscillator throws up and down, in the log domain so DEPTH reads as
semitones, phase-continuous so RATE can go as fast as it likes without a
click. A voice is the oscillator's shape — WAIL (triangle, the air-raid
rise and fall), TRILL (square, two tones), LASER (a falling ramp) and BIRD
(a rising one) — and SWEEP is the button-press gesture, the note diving
or climbing in from up to two octaves away. HOLD is how long the button
is down, because a siren is gated, not struck; its top is LOOP, one
seamless loop of whole LFO periods with the centre pitch fitted under a
cent so the pulse closes on whole cycles, cut at a zero crossing — the
render the SURFACE holds under a finger, pitched by where the finger is,
released into the surface's own echo on lift, and `→ SURFACE ▸` on SYNTH
lands a LOOP and opens the surface on it in one tap. SEND TO PAD lands a
one-shot siren with the rack's ECHO in its recipe and a LOOP dry, and
the toast says which. Forty presets, one LOOP per voice, and
`SynthKits.siren()` is the kit (`./gradlew :synth:generateSirenKit`).
Design: `docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md`.

SIREN also holds. `MAKE INSTRUMENT ▸` on SYNTH renders it as the same
nine-zone keys instrument RESIN's own MAKE INSTRUMENT builds, with only a
RELEASE to dial — the LOOP render already closes on itself exactly, so
there is no ATTACK to settle and no per-zone seam to cut. A keygroup
layer's loop start of `0` means *no loop* rather than *loop from the
start*, so the file is the loop rendered once and then doubled: two
bit-identical copies, with the loop marker at the second copy's own start,
so the player's one pass through copy one gives way to copy two repeating
forever — the same audio the LOOP render alone produces, through a marker
the format can express.

SIREN also drones. `DRONE TO LOOP ▸` on SYNTH puts the wail on the next
empty loop-grid track, no new knob needed: RATE and DEPTH are already the
patch's own speed and swing, unlike RESIN's MOTION and BREATHS, which the
drone invents because RESIN's held macros carry none. RATE's own Hz is
snapped onto the nearest whole cycle count the grid's loop can hold — the
same "further is longer" snap `DroneFit` already applies to pitch, applied
here to speed — and the carrier snaps the way the LOOP render's own
`planLoop` does, just run at the grid's own loop length. A tempo change
re-slices and re-renders it, the same as a RESIN drone's.

FORK is the modal electric piano — every other engine here is a strike;
this one is what a magnetic pickup does *after* one. A struck bar (TINE, a
cantilever like a real tine, whose overtones sit far from the fundamental
and die in tens of milliseconds; or BAR, the free-free shape a vibraphone
uses) rings through four tuned modes, and the pickup reads that ringing
through `v / (1 - x)^2` — the standard reluctance model, an asymmetric
curve that adds even harmonics on top of the resonator's own odd ones. The
curve gets less curved as the tine's swing decays, so the "bark" thins out
on its own with no envelope faking it — TINES fakes exactly this with its
BITE envelope; here it is physics. STIFF morphs the four modes from a
harmonic string, through the voice's own bar, to a stretched, rigid one;
BARK is the pickup's own closeness, the identity knob; STRIKE is the
hammer, moving its cutoff, its own decay and how hard the tine swings
together, and directly boosts the higher modes' own share the harder it
gets — real hammer-strike physics, not only a filter's own rolloff.
Sixteen presets, and a captured snip's own first 20 ms can stand in for
the hammer (kept in the recipe, so a struck kit still regenerates from
`kit.json`). Design:
`docs/superpowers/specs/2026-09-27-fork-electric-piano-engine-design.md`.

FLOTILLA is pitched emitters on a circular pool: RIPPLE, KNOCK, HOLLOW,
CROSSWAVE, DRIFT and GATHER, with PULSE, CROSSING, FLOTILLA, VESSEL,
SURFACE, SKIN and HOLD. The note is the source. The pool is a few damped
modes, the vessels float on it, and a warm dome sits over the top. HOLD at
the top is a stationary loop (the slider reads LOOP), not a claim the
hulls sailed home. Fourteen presets and a sixteen-pad kit. Design:
`docs/superpowers/specs/2026-10-04-flotilla-engine-design.md`.

CISTERN is a pitched membrane below a finite suspended droplet field:
FIRST, DRIP, CASCADE, POOL, RIPPLE and RECOVERY. The strike releases
droplets; their delayed landings excite that same surface, add local wet
load, and can release further droplets. STRIKE, SUSPENSION, DROP, SKIN
and DRAIN shape the gesture; HOLD supplies explicit powered circulation.
MIDI 36–84 is a separate note parameter, and velocity is strike energy.
Twelve provisional presets and `SynthKits.cistern()` provide sixteen dry
pads. Run `./gradlew :synth:generateCisternAudition` and open
`testkit/cistern-audition/index.html` for raw and matched listening clips,
macro sweeps, interaction grids and causal diagnostics. Listening and
release naming checks are pending. Design and implementation notes:
[`docs/superpowers/specs/2026-10-05-cistern-engine-design.md`](docs/superpowers/specs/2026-10-05-cistern-engine-design.md).
The [published audition](https://cistern-snipsnap-audition.bertcalm.chatgpt.site)
includes the full matrix and before/after comparisons for the revised voices.

CIRCUIT is an invented moving ensemble: three rooted breath resonators,
stone rattle, paired wood, clay vessel and synthesized coordination voice.
The current listening candidate gives them one authored four-pulse figure,
with shared breath, a slow rise and settle across the phrase, stable player
identity and quiet overlapping support. ROOT adds a firmer pitched foundation
only at faster playing rates.
DIAMETER, ORBIT and PACE independently set formation, movement and playing;
CANYON supplies asymmetric mono paths and bounded replies at authored answer
opportunities. A common energy scale keeps accepted replies in the phrase.
Six voices, twelve dry presets, a sixteen-pad kit and raw/matched listening
clips. HOLD at .99 returns its settled procession without an opening gesture.
The owner heard R4 (stone, wood and breath overpowered the sound) and R5
(“I think it's still a bit too abrupt”); the current candidate, R6, eases
force onset and release. The completed-pack evidence in docs/CIRCUIT.md is
R4's, which it marks historical: it does not verify R6. R6 calibration and
verification are in progress, and the owner's listening verdict remains pending.
Contract and audition commands:
[`docs/CIRCUIT.md`](docs/CIRCUIT.md).

MURK is a pitched grove: one bat-to-axe strike excites tuned wood, pressure
travels between four trees, and synthesized owls answer the disturbances
that reach them. CLUNK, THWACK, FRONT, HOOT, GROVE and ALARM share STRIKE,
TRUNK, FOG, AGITATION and GROVE, with TUNE and HOLD. Fog loads the original
tree as well as carrying returning sound. Calls have refractory times and
finite budgets; HOLD supplies repeated strikes. Fourteen dry presets and a
sixteen-pad pitched kit. The accessible listening page starts silent and
is regenerated with `./gradlew :synth:generateMurkAudition`; see
[`docs/MURK.md`](docs/MURK.md). Listening and device acceptance remain open.

NIMBUS is a pitched stack of six suspended cymbals: Body, Bell, Paper, Dark,
Flex and Wire answer at the same requested root. RING, SHIMMER, GATHER,
THROAT, CONTACT and SUSPEND change the initial excitation and response while
retaining the full stack. EXCITE, SPACING, HEIGHT, FIELD and FUNNEL shape
contact, sympathetic transfer, motion and chamber loading; HOLD supplies
explicit powered sustain. Nine dry presets, a sixteen-pad pitched kit and
physical velocity join the existing patch and recipe pipeline. Audition:
`./gradlew :synth:generateNimbusAudition`. The
[published listening page](https://snipsnap-nimbus-audition.bertcalm.chatgpt.site)
compares voices, notes, velocities, controls, isolated cymbals and held loops.
The first C4 defaults concentrated nearly all early/body energy in their
shared root. Revised sixteen-mode cymbals change excitation and upper-mode
losses to expose rounded rings, thin shimmer, wavering metal, dark chamber
tone, rim contact and supported sustain. Optional preserved-source pairs
support direct listening comparisons; normal regeneration needs no
preserved baseline. See [NIMBUS](docs/NIMBUS.md) for the contract, the
clip counts, the comparison command and the listening procedure.
Listening and device acceptance remain open.

COROLLA is a mechanical flower of coupled metal petals around a powered
magnetic core and a shared chamber. TONGUE, BLOSSOM, CHOIR, CHATTER, ORBIT
and HUSK share TUNE, PULL, BLOOM, FIELD, CONTACT, CHAMBER and HOLD. It
renders dry; finite notes route as TONAL for IN KEY, and HOLD's top step
returns settled LOOP material. Twelve
presets and `SynthKits.corolla()` expose the first implementation, awaiting
the owner's listening review. Audition: `./gradlew :synth:generateCorollaAudition`.
Individual presets can also be rendered with
`./gradlew :cli:run --args="synth COROLLA TONGUE --all --out /tmp/corolla"`.

ORPHICA is a first-listen microsound-harp prototype: each tuned pluck disperses
into grains of its own recent history. CLEAR, STUTTER, SCATTER, REVERSE and
captured FREEZE are deterministic stereo renders, with dry attacks and no
added reverb. Run `./gradlew :synth:generateOrphicaAudition` for the raw/matched
listening pack. The owner gave the finite sound a positive first-listening verdict. The phone
picker now provides material, mode, root and HOLD controls; MAKE INSTRUMENT
exports attack-preserving stereo captured-sustain keygroups. Original finite
recipes retain their sound. See [ORPHICA](docs/ORPHICA.md).

`Velocity` renders the darker soft-zone variants (a soft strike excites
fewer partials — one filter, physics does the design), `Groove` makes a kit
play itself (the expansion preview, the pre-export audition, and the best
moment in the app), and `Shuffle` is slot-machine kit design: dice-rolled
kits the classifier audits so a roll can't break them, plus a remix bank
that doubles any kit onto pads 17–32 through seeded FX.

ONSET, SCANNED CHAIN, VOX, FORK, FLOTILLA, CISTERN, CIRCUIT, OBBLIGATO, THAW, MURK, NIMBUS, COROLLA, UNDERTOW, SUTURE,
TESSERA, PITCHWHEEL, REVEL, PRISM, ALLOY, CONTOUR, STOOP, LACQUER, SPOOL, SKITTER, TANGLE, CREASE, SHOAL, RELAY, EMBOUCHURE, BELLOWS, MURMUR, REGISTERS, OSPREY, OPENSKY, OVEN, FLOCK, VENT, OVERLAP, TRIO, ORIGAMI, OHM and GRAINS round out the lineup —
fifty-three engines in the `Engine` picker; GRAINS is a separate processor, outside the picker's scope
since it has no voice enum and works on a source snip instead of picking
one. VOX is formant vocal synthesis — the shopping-mall-keyboard choir,
proudly: a VOWEL knob morphs
continuously through A→E→I→O→U over CHOIR/ROBOT/GHOST throats. It sings
through a vocal-cord pulse with vibrato; CHOIR is seven singers in
sections, in stereo; a long DECAY holds; SIZE scales the throat from
chipmunk to giant, and GLIDE moves the vowel during the note. ONSET
opens the note on a consonant ("mah", "bah", "dah", "hah", "tah", "sah"),
BEATBOX is a mouth doing a drum kit: a kick, three snares, three
hats and a tongue click, and THROAT is overtone singing: a low drone
with a whistled melody on its harmonics, a growl an octave down and a
yodel. WRAITH is sine-wave speech: three pure tones trace a word's
formants, ghostly whistling you half hear as "why" or "hello", gliding
or stepping in key. SWARM is a crowd: up to sixteen people shouting a
word together, talking over each other or talking in hushed voices. SPEAK
counts one to eight in a voice that runs from an 80s speech chip, dead on
the note, to a person whose pitch rises and falls: child to giant, hushed
to shouted, stretched into chops or stuttered. GRAINS is
the engine that eats captures: granular resynthesis that rebuilds any
source snip — a capture, a synth render — as a cloud (SIZE, SMEAR, DRIFT,
snapped PITCH, SHINE), deterministic per seed, honest enough that a
texture classifies as the LOOP it is. `SynthKits.cloud()` is the
atmosphere kit both of them make together.

SNAP is the other half of the name: a photo becomes a pad. A picture is
already mathematical data — three numbers per pixel — so the engine only
decides which numbers to read and in what order. One
line through the photo is one cycle of a wavetable (HORIZON reads across,
each column averaged so it is the picture's silhouette rather than one
noisy row; PLUMB reads down; ORBIT walks a circle round the centre, which
closes on itself so that cycle has no seam), and the photo's summary
numbers set the knobs: dominant hue → TUNE (red low, violet high, a grey
photo on the centre detent), brightness → BRIGHT, colourfulness → DECAY,
fine detail → GRIT. The pad stores the line's 256 brightness values and
the macros — never the photo — so it regenerates from `kit.json` like any
synth pad, with the picture's line in the sidecar as plain digits. Like
GRAINS it is outside the `Engine` picker (it needs a `Photo`, not a
voice), and has its own tab, SNAP, where TAKE PHOTO asks the system
camera. The one refusal comes in words: a line with no swing in it — a
plain wall read top to bottom — has no waveform to play.

DRAW is the same engine with a finger for a pen (the Fairlight's Page 6,
1982): a fourth chip beside the three photo lines opens a surface where
the cycle is drawn — over the photo's line, over a starting SINE, TRIANGLE,
SAW, SQUARE or PULSE, or from a blank — and heard as it is drawn. A second
tab draws the note's volume the same way (HOLD, FALL, PLUCK, SWELL, BOUNCE
to start from), which a pad carries as an optional `envelope` beside its
`table`; DECAY then sets only the length. `Draw` is the pen — strokes onto
a table, the starting shapes, a SMOOTH for a shaky hand — and every
function returns a new array, so a draft and a committed line never share
storage.

PHOTO FIELD is the whole picture under a finger. SNAP reads one line; a
photo has two dimensions, and the GRAIN FIELD screen already plays a
scatter of grains from wherever a finger is, off nothing more than a
sample and a list of positions. `PhotoField.build` cuts the picture into a
16×12 grid, renders each cell as its own short SNAP grain (its line as the
cycle, its colours as the knobs, its brightness as the level, a flat cell a
pure tone rather than a refusal), and lays the grains out where their
cells are — so FIELD on the SNAP screen opens the grain field over the
photo and sky and brick sound as different as they look, with no new
real-time code. CLOUD hands the field to GRAINS and lands the picture on a
pad as a texture, a LOOP by the classifier's measure. PRINT captures the
performance: from the tap to STOP PRINT, everything the field's voice
mixed lands on TAPE as a snip — `GrainVoice`'s own tap into the block it
was about to write, preallocated once at `start()` and never in the
render loop — so a drag across the field ends up on pads through the
same door as a capture, for the pad sheet's own field as much as a
photo's. DUET hands the cursor to the armed mic: a hum's brightness
(spectral centroid) moves it right, its loudness moves it up, the same
`GrainField.Projector` interface a sample field's own PCA fit already
implements — a photo field has no grains to fit one from, so it carries
a fixed `AxesProjector` instead, brightness read straight off the mic's
own fingerprint and loudness mapped on by DUET's tick itself, since that
one feature is deliberately left out of the fingerprint (`Similar.vector`
— "a quiet snare is still a snare"). TILT is a second automatic cursor
beside DUET's: the phone's own roll and pitch, dead-banded and smoothed,
stand in for a finger, so holding the phone up to the photo it was taken
of and tilting plays the picture hands-free — exclusive with DUET, one
automatic cursor at a time.

A KIT FROM ONE PHOTO turns the whole idea into a kit, not one pad.
`PhotoKit.build` cuts the picture into the MPC's own 4×4 instead of PHOTO
FIELD's 16×12, renders each cell as a full SNAP pad (a note, not a grain),
and lands all sixteen at once with the picture's own geometry: the
top-left cell is A13, the bottom-left A01, `KitScreen`'s own grid. Every
pad's colour is its cell's mean RGB, so the MPC lights up as the photo
did — `AutoPlace` never runs, and a photo kit says so on its landing
toast, since the layout is the picture's, not the drum convention's. KIT
▸ on the SNAP screen builds it off the main thread and lands it as a
brand-new kit on the shelf, named PHOTO KIT, never overwriting whatever
kit is open.

PATH walks the picture in time instead of reading one line through it.
DRAW's overlay gains a third tab over a photo: draw a path across it and
`PhotoPath.sample` resamples the stroke by arc length to a fixed step
count — a fast drag and a slow one over the same line give the same
walk, and a line that lingers over one patch gives it no more steps than
one that crossed it in a blink. Each step is the cell under it, the same
grid `PhotoKit` cuts, so a melody follows where the line goes and a
rhythm follows where it stays. Two landings: LOOP PAD ▸ renders the
walked cells as one gapless loop, cut to the tempo's sixteenth grid, and
lands it like CLOUD's texture; RING ▸ needs the kit above — the walked
cells become a `PatternOrbit` naming its pads directly, a fresh ring on
ORBIT that plays live and is editable afterwards like any other.

SPECTRUM is the literal reading: a photo *is* already a picture of
sound if you read it that way — columns as time, rows as frequency
(the top row highest, the bottom ~40 Hz, log-spaced between, since
linear would put nine tenths of the picture above 2 kHz), brightness as
level. `Spectrogram.read` builds a magnitude grid off the photo and
hands it to `Pghi.invert` (the same phase-gradient reconstruction
`Pghi.stretch` already uses elsewhere) to turn it into audio, floored
and power-curved so a photograph reads as a picture rather than hiss.
SPECTRUM ▸ lands it like CLOUD's own texture — recipe-less audio,
needing only the open photo, not FIELD's own grid.

LIVE ▸ needs no photo at all yet — it is where one comes from. The
camera's own preview, read every frame into a table and a reading, plays
through a continuous wavetable voice (`LiveSnapEngine`, native, the same
control-ring shape as `SurfaceEngine`'s) whose table cross-fades to each
new frame over ~50 ms and whose TUNE/BRIGHT/GRIT glide toward each new
reading — point the phone at the world and hear it change as it pans.
DECAY has nothing to decay away in a voice that never stops sounding, so
it is shown, never sent. FREEZE takes the frame on screen as the photo,
through the same door TAKE PHOTO already opens, so DRAW, FIELD and SEND
TO PAD all work on a live capture exactly as they would on a snapshot.

Effects are the same trick as CRUNCH, generalized: pads are one-shots
rendered offline, so an effect is a pure `Snip → Snip` pass, baked into the
WAV like it's 1993 — you sampled the reverb, you didn't rack it. PITCH
(the transport itself: a varispeed head snapped to semitones, native at
the centre, running before everything else so the whole rack — SWELL's
stretched head and REVERSE's flip included — sees the pitched sound), EQ
(three
plain-word bands — BASS shelf, MID bell, AIR shelf — RBJ cookbook biquads
with 0.5 as the flat detent), CONTOUR (RESIN's ladder swept from the
hit's onset, on a pad that was never a synth), WAH (a resonant band-pass
swept between a heel and a toe by three moves held as weights, TOUCH from the
hit's own envelope, AUTO a sine with a flat top (it rests at the toe for 39
percent of each cycle at AMT 100 and 14 percent at AMT 70) and ROCK one
heel-toe-heel gesture, with SPEED and Q beside them; the first pedal on the board, in front of
the compressor and the amp), SQUASH
(lookahead compressor: fast clamp is glue, slow clamp is punch), VALVE (a
tube amp and its speaker: DRIVE into an asymmetric curve, SAG the supply
giving way under it on loud passages, TONE after the tube, CAB from no
cabinet to a closed wall — run four times over inside so a hot tube folds
back far less), TAPE (the
cassette the whole app is dressed as: wow/flutter via a modulated
fractional delay, hysteresis-flavored drive, head-wear HF loss — a
physics-lite nod to ChowDSP's AnalogTapeModel), ENSEMBLE (three taps off one
delay line swimming 120° apart on two slow sines, each through its own
tone, summed with no dry — the string machine's back half on any pad, and
the one section that widens a mono pad to stereo, by choice, with the pair
weighted so the phone's mono fold hears every tap the same; its SECTION
macro swaps the locked taps for six independent players, each with their own
pitch drift, vibrato, onset, tone and level, which is the difference between
one instrument swirled and several playing), PHASE (four allpasses
swept by one slow sine and summed back with the dry, the notches sliding
up and down the spectrum), ECHO (one delay line and
one filter, repeats darkening as they fade), SPRING (a Schroeder network,
1962), REVERSE, SMEAR (the attack taken out, the wash kept: the STN
transient mask from `Separate` scaled by one AMOUNT knob, so a snare
becomes the room it was struck in; a FLOOR makes it banded, so a kick
loses its click and keeps its thump), GHOST (the tone gone too, the
breath alone), SPIKE (the attack leaned into instead of taken away: two
envelope followers at different speeds, their gap gained as the
transient), RING (the sound multiplied by a sine — metal, bells, radio;
deliberately no longer the hit it was, the exemption GHOST already
carries), DUB (generation loss: a dub of a dub, twelve deep), VINYL (the
record under the hit, as TAPE is the cassette: rumble, groove hiss and
clicks, scaled to the hit's own peak, seeded once per pad), SWELL
(the sound arrives before it strikes) and MOTION (the tape stop and the
tape start, baked); beside the rack, WOBBLE sweeps the same filter on
the kit's own grid, a note division at its tempo — ROLL (the hit's own
head struck again) and GATE (the hit chopped into a square envelope)
share that same grid, so all three land on the same beats. `FxChain` fixes the order — pitch → swell → reverse → smear → ghost →
spike → eq → contour → wah → squash → valve → crunch → ring → dub → vinyl →
tape → ensemble → phase → echo → spring → motion — owns
the total tail budget so stacked reverbs can't turn a hit into a phrase,
and serializes per-pad next to the WAV.

**WIDE** is the first editable compound board: TAPE → EQ → ENSEMBLE → SPRING
in its own order. On the pad sheet, AMT is staged until KEEP, original/result
previews preserve stereo, and REMOVE/UNDO retain the original audio and recipe.
See [`docs/WIDE.md`](docs/WIDE.md).

Identity is tested: a kick through the whole default rack still classifies
KICK.

That writeup is the engines on SYNTH's picker (`Engine` in `SynthScreen`):
THUMP, SCANNED CHAIN, SKIN, TINES, VELVET, VOX, PLUCK, TONEWHEEL, REGISTERS, FATHOM, RESIN, TIDE,
GLINT, SIREN, FORK, FLOTILLA, CISTERN, CIRCUIT, OBBLIGATO, ALLOY, THAW, MURK, NIMBUS, COROLLA, UNDERTOW,
SUTURE, TESSERA, PITCHWHEEL, REVEL, PRISM, CONTOUR, STOOP, LACQUER, SPOOL, SKITTER, TANGLE, CREASE, SHOAL, RELAY, EMBOUCHURE, BELLOWS, MURMUR, OSPREY, OPENSKY, OVEN, VENT, OVERLAP, TRIO, plus GRAINS, SNAP and DRAW, which sit beside the
picker. `:synth` also holds engines that picker does not show. BORE, ARCO,
MERCURY, TREMOR and AEROSTAT have preset rosters, so `snipsnap synth` renders them.
ALLOY, CISTERN, FLOTILLA, TESSERA, PITCHWHEEL, REVEL, PRISM, REGISTERS, CONTOUR, STOOP, LACQUER, SPOOL, SKITTER, TANGLE, CREASE, OVERLAP, SHOAL, RELAY, EMBOUCHURE, BELLOWS, MURMUR, TRIO, OSPREY and OPENSKY
are in the picker and the preset roster, and each but FLOTILLA and REGISTERS has a starter kit;
the CLI's `--midi` and `--velocity` options set their note and strike energy directly
(each one's `--midi` range is in [`docs/CLI.md`](docs/CLI.md)'s `synth` section).
TERRA, SILK, GYRE and MAGNET are in the module, with kits or voices, and
no roster entry. VENT, the Rössler-attractor synth (separate from the TapeOS
scheme of that name), brings clean model-2 PULSE, COIL and PLUME to the picker
and factory roster. FRESH TAPE's VENT kit has twelve dry pads in C minor
pentatonic: a bass, short note, percussive hit and long texture per voice,
leaving four capture slots free. The CLI renders those same four recipes per
clean voice; ROUND, WIRE and TEAR retain their CLI-only model-1 comparisons.
The voice selects its model, and
`--midi`, `--velocity` and `--seed` set their note, strike energy and
starting trajectory ([`docs/VENT.md`](docs/VENT.md)).
Each file's own KDoc names its spec.

The filters got a generational upgrade from the DSP literature: `Dsp.TptSvf`
is a topology-preserving (trapezoidal) state-variable filter after Andy
Simper's Cytomic papers — stable to Nyquist where the Chamberlin design
went unstable past ~7 kHz. VELVET's CUTOFF, once capped at 5.2 kHz for
stability, now opens to 12 kHz with the envelope sweeping to 16 kHz.

OHM is a dry coupled-string instrument inspired by XOmnibus's
family dynamics: MEDDLING introduces two companions, and COMMUNE moves
their energy through a shared resonating bridge. The finite solo, argument,
communion and gathering examples, complete recipes and control comparisons
are generated by `./gradlew :synth:generateOhmAudition`. Its five auditioned
recipes are available in the synth picker, factory roster and CLI. Model 2
keeps the plucked foreground present under the companions and broadens
control variation; saved model-1 recipes retain the original sound. The
audition includes direct original/revised middle-range comparisons. See
[`docs/OHM.md`](docs/OHM.md) and the
[private audition](https://ohm-snipsnap-audition.bertcalm.chatgpt.site).

BELLOWS is a pressure-driven reed-and-wheel prototype inspired by XOleg's
Sacred Bellows. CHAMBER, COUNTER, FOLK and WHEEL render chambered reed ranks,
contrasting push/pull colors, asymmetric folk reeds, and tracking drones
with pressure-gated bridge chatter. Twenty-four dry presets, saved player
gestures, a sixteen-pad starter kit, CLI and the synth picker share the same
renderer. MAKE INSTRUMENT adds C3–C5 attack/sustain keygroups with three
velocity timbres; WHEEL's fixed tonic companion stays separate from melody.
Run `./gradlew :synth:generateBellowsAudition` for the library, phrases,
chords and raw/matched control comparisons, and
`./gradlew :cli:generateBellowsInstruments` for the phone/MPC package.
The owner accepted the four dry voice identities and parameter differences.
The [private audition](https://bellows-snipsnap-audition.bertcalm.chatgpt.site)
now supports the playable review; [implementation notes](docs/BELLOWS.md)
and the [playtest guide](docs/BELLOWS_PLAYTEST.md) describe the remaining
phone/MPC checks.

ORIGAMI brings three note-relative spectral-folding voices to the SYNTH picker:
CRANE, FAN and STILL. Six presets and a sixteen-pad starter kit use the accepted
model-4 sound, with editable TUNE, CREASE, FOLD, TURN, WARP and MOTION controls.
SEND saves a replayable recipe and authored pitch for kit tuning. See the
[ORIGAMI notes](docs/ORIGAMI.md) for native workflow and listening history.

### `:mpc3`

Reads **and writes** the MPC 3 container: gzip + five-line ACVS header + JSON.
`MpcFormats.detect` tells the generations apart by content (both use `.xpj`),
`Acvs.read` opens a container, and `Mpc3Project` gives tolerant accessors over
projects and standalone tracks alike — checked against 59 real projects and 13
real track files in `reference/golden/`.

**`Mpc3TrackWriter`** is the native writer — the primary-format target, real:
a [`DrumProgram`] becomes a standalone `.xtd` drum track, templated
field-for-field from commercial content. All 128 instrument slots fully
formed, 8 layers each, velocity zones loudest-first, length in
`sliceInfo.End` (never `sampleEnd`), the sample named twice per layer and
mirrored 1:1 in the deduplicated `samples[]` pool, the 0-based chromatic pad
note map, `poliphony` misspelled exactly where the format misspells it, and
per-pad class colours as plain packed ints. The test that keeps it honest is
the same one the keygroup writer earned: **no key path we emit may be absent
from every real drum track** — invention, not omission, is how MPC files fail
silently. `Mpc3Exporter` in `:kit` drives it from the same pipeline as every
other export, and **`Mpc3ProjectWriter`** goes one further: the whole
session — kit, instruments, groove on the timeline, mixer — as one `.xpj`
the Live III opens directly; see [`docs/MPC3_FORMAT.md`](docs/MPC3_FORMAT.md).

### `:json`

The tiny strict JSON reader/writer under both `kit.json` and ACVS payloads.
Zero dependencies, byte-stable output, hostile to malformed input.

### `:xpm`

Pure Kotlin/JVM, zero dependencies — writes MPC drum programs. No Android APIs,
so it runs in a plain JVM test and can be consumed by the app as-is.

```kotlin
val info = WavInfo.read(File(kitDir, "SS_Kick_01.wav"))

val program = DrumProgram(
    name = "SnipSnap Kit 01",
    pads = listOf(
        Pad("SS_Kick_01", info.frameCount),
        Pad("SS_Snare_01", 24_110L),
        Pad("SS_HatClosed_01", 6_301L, muteGroup = 1),
        Pad("SS_HatOpen_01", 31_884L, muteGroup = 1),
    ),
)

XpmWriter().writeTo(kitDir, program)   // -> kitDir/SnipSnap Kit 01.xpm
```

```
./gradlew :xpm:regenerateGolden   # only for deliberate format changes
```

`KeygroupWriter` renders multisampled **keygroup** programs (the keys-on-pads
door): note-ranged instruments with velocity layers (8 slots, as real
keygroups carry), real per-zone root notes, `KeyTrack=False`, and the
maps-then-`Keygroup*` program tail — the shape corrected line-by-line against
the commercial keygroup programs in `reference/golden/keygroup/`
(see [`docs/XPM_STRUCTURE.md`](docs/XPM_STRUCTURE.md#what-keygroupwriter-gets-wrong)).
`testkit/SnipSnap Keys` is the remaining on-hardware acceptance check.

### `:cli`

The product loop minus live capture, as one runnable jar — see
[`docs/CLI.md`](docs/CLI.md):

```
./gradlew :cli:snipsnapJar    # -> cli/build/libs/snipsnap.jar
java -jar snipsnap.jar chop break.wav --balance --export folder,xtd,xpj
```

`chop` reads any WAV, estimates tempo, chops at the hits (or on a grid),
classifies every slice, auto-places the kit, and fans out to any export
format the writers speak. `classify` prints class + confidence + the
features behind the verdict — the calibration tool for tuning thresholds
on real captures. `export` runs the format fan-out over any existing kit
folder. It means kits can be made from a desktop today, and it's the first
place the classifier meets real audio instead of synthetic test material.

### `:shell`

The app's brain: every screen's state machine as tested pure Kotlin, so the
Android app is Compose bound to proven logic instead of logic written in a
UI layer. What lives here:

- **`Schemes`/`Type`/`Layout`/`Motion`** — the ten TapeOS scheme token
  tables as data (verbatim from the design system; VENT and OPENSKY, the
  two MODERN ones, from the audition pages `docs/DIRECTION_2026_10.md`
  names — OPENSKY the first paper scheme, which reopened the August cut
  of light shells for the modern look only), pad-label ink tables
  for dark schemes and CLEAR, and the layout/motion constants from the
  handoff. The two-surface rule — the LCD stays dark in every scheme — is
  a unit test now.
- **The four-place shape's models**
  ([`docs/SCREEN_PLAN_2026_10.md`](docs/SCREEN_PLAN_2026_10.md) §6) —
  `Navigation` (four places, the stage rows, benches, one back stack, and
  the per-place state a stage switch keeps), `DockModel` (the transport,
  the capture light, the line and its standing door), `ShelfModel`
  (projects above kits, the migration on open), `PadStrip` (the tap door
  under the grid, built on the pad sheet's own closed-box words),
  `ProjectSessionModel` (OUT's session card over the project exporter),
  `Places` (the rows' words, the dock's labels, HELP by place) and
  `Scheme.look` (CLASSIC or MODERN, the second axis beside the scheme).
  Built ahead of the SDK session that draws them, so that session binds
  rather than designs; `UatSimTest`'s J11 walks the plan's journeys on
  `Navigation` and counts them.
- **`PeaksPyramid`** — min/max waveform mips with *exact* queries at any
  zoom; what makes the tape deck's canvas flat-cost at 60 fps.
- **`TapeDeckModel`** — the trim screen's transport physics, ported
  coefficient-for-coefficient from the prototype that already feels right:
  drag/flick/coast/glide, spin-up, onset snap, IN/OUT swap semantics, loop
  preview, the pencil rewind, the odometer.
- **`VoiceAllocator`** — play mode's choke/steal/note-off decisions behind
  an interface, so the Oboe layer stays thin and dumb.
- **`ChopReviewModel` / `KitBuilderModel` / `ExportWizardModel`** — chop
  chips with tap-to-cycle overrides and NOT SURE honesty, the 4×4 grid's
  assign/move/clear/edit over a kit folder, and the export wizard's
  stage machine driving the same `Exporters` fan-out the CLI uses.
- **`Personality`/`Delight`/`Copy`** — `docs/PERSONALITY.md` as executable
  data: the four laws gate for real (OFF silences everything; deck sounds
  hard-mute while capture is armed), all shipped copy, and the eggs.
- **`Workshop` / `BenchExport` / `CutRatings` / `LabelledHits` /
  `BenchNotes`** — the developer's bench inside the app
  (`docs/WORKSHOP.md`): the knock that opens it, CHOP's CONFIRM ALL and
  cut rating (the teach log learns when the machine was *right*, and
  which bench settings cut well), the pad sheet's LABEL THIS HIT (a
  capture into the calibration corpus, by ear), the title bar's NOTE (a
  bench note stamped with the screen, the kit, the pad and the time,
  rendered by the manifest as a line for `docs/BENCH.md`), and the two
  hand-outs — SEND TO BENCH for the logs, the notes and the saved
  presets, SEND HITS TO BENCH for the audio — for `reference/calibration/`.
- **`UserPresets`** — SAVE AS PRESET on SYNTH (`docs/WORKSHOP.md`, WS5,
  the one tool on that list that is for every player): the player's own
  presets in `presets.json` beside the kits, the name rules (fourteen
  letters, never a factory name, a saved name replaces in place), the bin
  a held chip forgets one into (the same thirty days every bin promises,
  DELETED PRESETS on SYNTH as the way back), and the roster line that
  promotes one into `:synth`'s tables by hand — a code change the spread,
  blocklist and identity tests still judge; and the merge a backup's
  presets go through when the zip comes home (the shelf's own stay, a
  twin is skipped, a namesake lands under a fresh name).

### `:loop` — ORBIT

The circular sequencer ([`docs/ORBITS.md`](docs/ORBITS.md)): a bar taped
end to end into a ring, a longer snip taped into a bigger ring around it,
one needle speed driving them all so the inner ring comes round first. Each
ring is a pad pattern or a snip from the shelf, any length from 1 to 64
steps. **SPAN FREE** keeps one needle speed, so a 16-step ring against a
20-step ring is 4/4 against 5/4. **SPAN ½ BAR** through **4 BARS** makes
one turn of the ring that many laps of the bar, so three steps across one
bar is a triplet. `OrbitClock` is the arithmetic, every position a function of one
frame count; `OrbitEngine` plays it through the same `AudioSink` the loop
grid uses; `OrbitStore` keeps `orbits.json` beside the kit. Reached from
the ORBIT menu item, and from GROOVE's **ORBIT ▸**. **CLIP ▸ KIT** writes
the set into the kit's `groove.json`, and the native export takes that
clip to the MPC. [`docs/ORBITS.md`](docs/ORBITS.md) is the arithmetic.

### `:app`

The Android shell: Compose bound to `:shell`, kit storage, capture
services, and the native library (`SurfaceEngine`, `PadEngine`,
`LiveSnapEngine`). How to build it, and the per-feature phone notes, are
in [`app/README.md`](app/README.md). CI compiles it in `android-build`.

## Docs

- [`docs/README.md`](docs/README.md) — which document is current, and which are dated notes
- [`docs/CONCEPT.md`](docs/CONCEPT.md) — product shape, the original MVP cut, architecture
- [`docs/APP_PLAN.md`](docs/APP_PLAN.md) — milestone record of the Android app, and what is still open
- [`docs/BENCH.md`](docs/BENCH.md) — the phone and Live III checks, in the order to run them
- [`docs/FEATURE_PLAN.md`](docs/FEATURE_PLAN.md) — the six product features ranked by ROI, each planned to done with owners and exit tests
- [`docs/ANDROID_CAPTURE.md`](docs/ANDROID_CAPTURE.md) — how capture actually works and where it breaks
- [`docs/CLI.md`](docs/CLI.md) — the SnipSnap CLI: chop a file into a kit from any desktop
- [`docs/ORBITS.md`](docs/ORBITS.md) — ORBIT, the circular sequencer: polymeter and polyrhythm as rings of different lengths
- [`docs/MPC_EXPORT.md`](docs/MPC_EXPORT.md) — folder layouts and export paths
- [`docs/MPC3_FORMAT.md`](docs/MPC3_FORMAT.md) — the MPC 3 container, drum and keygroup schemas, verified against real Akai content
- [`docs/XPM_STRUCTURE.md`](docs/XPM_STRUCTURE.md) — the MPC 2 format, its provenance, and what's still unverified
- [`docs/KIT_BEST_PRACTICES.md`](docs/KIT_BEST_PRACTICES.md) — pad layout, mute groups, naming, and what Akai does and doesn't document
- [`docs/UI_DESIGN.md`](docs/UI_DESIGN.md) — the TapeOS visual language (90s desktop × cassette) and the mockup artboards in [`design/`](design/)
- [`docs/PERSONALITY.md`](docs/PERSONALITY.md) — the delight system: voice, the four laws, gag catalog, easter eggs
- [`docs/WORKSHOP.md`](docs/WORKSHOP.md) — the WORKSHOP: the developer's tools behind a knock on SETUP, SEND TO BENCH first, and the list after it
- [`docs/SYNTH_ROADMAP.md`](docs/SYNTH_ROADMAP.md) — THUMP/CRUNCH/TINES/VELVET: generate kits, not just capture them
- [`reference/README.md`](reference/README.md) — harvesting reference programs off hardware

## Next step

**The headline acceptance passed (2026-08-23).** On a Live III:
`SnipSnap MPC3 Kit.xtd` — the native-format factory kit — **loaded and
played**, pads on their assigned slots, lit in class colours, A03 choking
A04, with the embedded "SnipSnap Groove" clip playing from the clip list.
The diag kit (MPC 2 `.xpm` path) loaded too, and A01 gave **one beep**:
0-based instrument numbering confirmed, no shift. Both export generations
are proven shipping paths.

Remaining hardware checks, in value order: the keygroup programs
(`SnipSnap MPC3 Keys.xty` native and `SnipSnap Keys` MPC 2 — do keys play
in tune chromatically), the `.xpn` expansion import, the Expansion-browser
tile, velocity-layer feel, and bank B. And one save the corpus can never
supply: build any drum program **on** the Live III, save it, and drop the
file in `reference/golden/liveiii-36/` — the last word on what firmware
itself writes. Procedure in [`reference/README.md`](reference/README.md).

MPC 2 hardware verification stays [backlogged](reference/README.md#backlog-mpc-2)
— nobody here owns an MPC One or a 2.x Live II. Both writers are shipping
paths the Live III has already loaded, and MPC 3 loads MPC 2 content.
