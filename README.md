# SnipSnap

An Android app for grabbing sound off your phone — from other apps, from video,
from the mic — trimming it into one-shots, laying them out on a 4×4 pad grid,
and exporting a drum kit your Akai MPC can load.

> You heard it. You snipped it. It's on pad A03.

**Status:** design, plus a tested pure-Kotlin core — capture buffer, cleanup DSP,
WAV writer and `.xpm` writer. No Android layer yet.

## The loop

```
capture (rolling buffer)  →  trim  →  assign to 4×4 grid  →  export .xpm + WAVs  →  MPC
```

## Targets

| | |
|---|---|
| Platform | Android only, minSdk 29 |
| Hardware | Akai MPC One, MPC Live II, MPC Live III |
| Primary format | MPC 3 native (gzip + ACVS header + JSON), Live III as acceptance device |
| Compatibility format | MPC 2-era `.xpm` drum program + 44.1 kHz WAVs, as a folder — implemented |
| Not supported | `.xpn` expansion installers (desktop MPC Software only — irrelevant here) |

## Modules

All plain Kotlin/JVM with no Android APIs, so the fiddly parts are unit tested
on a normal JVM and the Android layer stays a thin shell over proven code.

```
./gradlew test    # 319 tests across six modules
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
- **`KitAssembler`** — arranged snips in, kit folder out: the last step of the
  auto-chop pipeline.
- **`Preflight`** — the export wizard's checklist as real checks. WARNs export;
  FAILs block, because "exported but broken" is the worst thing a tool that
  writes to someone's SD card can do.
- **`KitExporter`** — kit folder → MPC program folder (`.xpm` + WAVs), names
  sanitized consistently between the folder and the program.

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

Effects are the same trick as CRUNCH, generalized: pads are one-shots
rendered offline, so an effect is a pure `Snip → Snip` pass, baked into the
WAV like it's 1993 — you sampled the reverb, you didn't rack it. SQUASH
(lookahead compressor: fast clamp is glue, slow clamp is punch), ECHO (one
delay line and one filter, repeats darkening as they fade), SPRING (a
Schroeder network, 1962), and REVERSE. `FxChain` fixes the order —
reverse → squash → crunch → echo → spring — owns the total tail budget so
stacked reverbs can't turn a hit into a phrase, and serializes per-pad next
to the WAV. Identity is tested: a kick through the whole default rack still
classifies KICK.

### `:mpc3`

Reads the MPC 3 container: gzip + five-line ACVS header + JSON.
`MpcFormats.detect` tells the generations apart by content (both use `.xpj`),
`Acvs.read` opens a container, and `Mpc3Project` gives tolerant accessors over
the documented project schema — built so a real Live III file gets dissected
the moment one lands. Field paths are from the community knowledge base and
carry its caveats; see [`docs/MPC3_FORMAT.md`](docs/MPC3_FORMAT.md).

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

## Docs

- [`docs/CONCEPT.md`](docs/CONCEPT.md) — product shape, MVP cut, architecture
- [`docs/ANDROID_CAPTURE.md`](docs/ANDROID_CAPTURE.md) — how capture actually works and where it breaks
- [`docs/MPC_EXPORT.md`](docs/MPC_EXPORT.md) — folder layouts and export paths
- [`docs/MPC3_FORMAT.md`](docs/MPC3_FORMAT.md) — the MPC 3 container and drum schema, and the one thing blocking a native writer
- [`docs/XPM_STRUCTURE.md`](docs/XPM_STRUCTURE.md) — the MPC 2 format, its provenance, and what's still unverified
- [`docs/KIT_BEST_PRACTICES.md`](docs/KIT_BEST_PRACTICES.md) — pad layout, mute groups, naming, and what Akai does and doesn't document
- [`docs/UI_DESIGN.md`](docs/UI_DESIGN.md) — the TapeOS visual language (90s desktop × cassette) and the mockup artboards in [`design/`](design/)
- [`docs/PERSONALITY.md`](docs/PERSONALITY.md) — the delight system: voice, the four laws, gag catalog, easter eggs
- [`docs/SYNTH_ROADMAP.md`](docs/SYNTH_ROADMAP.md) — THUMP/CRUNCH/TINES/VELVET: generate kits, not just capture them
- [`reference/README.md`](reference/README.md) — harvesting reference programs off hardware

## Next step

**Find out what an MPC 3 saved program actually is.** Build a drum program on the
Live III, save it to SD, and check the first two bytes — `1F 8B` means gzip and
the new container, `<?xml` means it still writes MPC 2-style XPM. Nothing about a
native MPC 3 writer can be built until that's answered. Procedure in
[`docs/MPC3_FORMAT.md`](docs/MPC3_FORMAT.md#the-check-two-minutes-on-the-live-iii).

Then, for the compatibility path: load a generated kit on an **MPC One** and
confirm all 16 pads fire where they were assigned. See
[`docs/XPM_STRUCTURE.md#unverified`](docs/XPM_STRUCTURE.md#unverified) — the big
one is whether instrument numbering is 0- or 1-based, which shows up as a kit
shifted by exactly one pad.
