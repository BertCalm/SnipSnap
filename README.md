# SnipSnap

An Android app for grabbing sound off your phone — from other apps, from video,
from the mic — trimming it into one-shots, laying them out on a 4×4 pad grid,
and exporting a drum kit your Akai MPC can load.

> You heard it. You snipped it. It's on pad A03.

**Status:** a tested pure-Kotlin core — capture buffer, cleanup DSP, seven
synth engines, and writers for both MPC generations, **hardware-verified on
an MPC Live III** (native `.xtd` and compatibility `.xpm` kits load and
play). No Android layer yet.

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

All plain Kotlin/JVM with no Android APIs, so the fiddly parts are unit tested
on a normal JVM and the Android layer stays a thin shell over proven code.

```
./gradlew test    # 751 tests across eight modules
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
- **`Loudness`** — perceived level (peaks aren't loudness), feeding kit-wide
  balance.

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

VELVET closes the engine lineup: subtractive, the playability king — a naive
saw/pulse unison pair over a sub, into a resonant SVF swept by its own
envelope. SHAPE walks saw → square → PWM on one knob; SQUEEZE is resonance
and envelope amount together, so the top of the knob is instant acid. Four
stab voices (bass, brass, squelch, chip), TUNE snapped to semitones. And the
roadmap's free bonus is cashed: `SynthKits.chip()` renders VELVET squares
and THUMP/TINES drums through one CRUNCH converter — the chip kit, maximum
kitsch, zero new DSP.

`Velocity` renders the darker soft-zone variants (a soft strike excites
fewer partials — one filter, physics does the design), `Groove` makes a kit
play itself (the expansion preview, the pre-export audition, and the best
moment in the app), and `Shuffle` is slot-machine kit design: dice-rolled
kits the classifier audits so a roll can't break them, plus a remix bank
that doubles any kit onto pads 17–32 through seeded FX.

VOX and GRAINS round out the lineup at seven. VOX is three-formant vocal
synthesis — the shopping-mall-keyboard choir, proudly: a VOWEL knob morphs
continuously through A→E→I→O→U over CHOIR/ROBOT/GHOST throats. GRAINS is
the engine that eats captures: granular resynthesis that rebuilds any
source snip — a capture, a synth render — as a cloud (SIZE, SMEAR, DRIFT,
snapped PITCH, SHINE), deterministic per seed, honest enough that a
texture classifies as the LOOP it is. `SynthKits.cloud()` is the
atmosphere kit both of them make together.

Effects are the same trick as CRUNCH, generalized: pads are one-shots
rendered offline, so an effect is a pure `Snip → Snip` pass, baked into the
WAV like it's 1993 — you sampled the reverb, you didn't rack it. EQ (three
plain-word bands — BASS shelf, MID bell, AIR shelf — RBJ cookbook biquads
with 0.5 as the flat detent), SQUASH
(lookahead compressor: fast clamp is glue, slow clamp is punch), TAPE (the
cassette the whole app is dressed as: wow/flutter via a modulated
fractional delay, hysteresis-flavored drive, head-wear HF loss — a
physics-lite nod to ChowDSP's AnalogTapeModel), ECHO (one delay line and
one filter, repeats darkening as they fade), SPRING (a Schroeder network,
1962), and REVERSE. `FxChain` fixes the order — reverse → eq → squash →
crunch → tape → echo → spring — owns the total tail budget so stacked reverbs
can't turn a hit into a phrase, and serializes per-pad next to the WAV.
Identity is tested: a kick through the whole default rack still classifies
KICK.

The filters got a generational upgrade from the DSP literature: `Dsp.TptSvf`
is a topology-preserving (trapezoidal) state-variable filter after Andy
Simper's Cytomic papers — stable to Nyquist where the Chamberlin design
went unstable past ~7 kHz. VELVET's CUTOFF, once capped at 5.2 kHz for
stability, now opens to 12 kHz with the envelope sweeping to 16 kHz.

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

- **`Schemes`/`Type`/`Layout`/`Motion`** — the six TapeOS scheme token
  tables as data (verbatim from the design system), pad-label ink tables
  for dark schemes and CLEAR, and the layout/motion constants from the
  handoff. The two-surface rule — the LCD stays dark in every scheme — is
  a unit test now.
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

## Docs

- [`docs/CONCEPT.md`](docs/CONCEPT.md) — product shape, MVP cut, architecture
- [`docs/APP_PLAN.md`](docs/APP_PLAN.md) — **the remaining work, scoped**: the Android app milestone by milestone, the hardware queue, and the odds and ends
- [`docs/FEATURE_PLAN.md`](docs/FEATURE_PLAN.md) — the six product features ranked by ROI, each planned to done with owners and exit tests
- [`docs/ANDROID_CAPTURE.md`](docs/ANDROID_CAPTURE.md) — how capture actually works and where it breaks
- [`docs/CLI.md`](docs/CLI.md) — the SnipSnap CLI: chop a file into a kit from any desktop
- [`docs/MPC_EXPORT.md`](docs/MPC_EXPORT.md) — folder layouts and export paths
- [`docs/MPC3_FORMAT.md`](docs/MPC3_FORMAT.md) — the MPC 3 container, drum and keygroup schemas, verified against real Akai content
- [`docs/XPM_STRUCTURE.md`](docs/XPM_STRUCTURE.md) — the MPC 2 format, its provenance, and what's still unverified
- [`docs/KIT_BEST_PRACTICES.md`](docs/KIT_BEST_PRACTICES.md) — pad layout, mute groups, naming, and what Akai does and doesn't document
- [`docs/UI_DESIGN.md`](docs/UI_DESIGN.md) — the TapeOS visual language (90s desktop × cassette) and the mockup artboards in [`design/`](design/)
- [`docs/PERSONALITY.md`](docs/PERSONALITY.md) — the delight system: voice, the four laws, gag catalog, easter eggs
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
— nobody here owns an MPC One or a 2.x Live II — but `:xpm` is live regardless,
since it is the only thing producing loadable output today and MPC 3 loads MPC 2
content.
