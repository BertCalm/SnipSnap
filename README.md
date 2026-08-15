# SnipSnap

An Android app for grabbing sound off your phone — from other apps, from video,
from the mic — trimming it into one-shots, laying them out on a 4×4 pad grid,
and exporting a drum kit your Akai MPC can load.

> You heard it. You snipped it. It's on pad A03.

**Status:** design, plus a working `.xpm` writer. No Android app yet.

## The loop

```
capture (rolling buffer)  →  trim  →  assign to 4×4 grid  →  export .xpm + WAVs  →  MPC
```

## Targets

| | |
|---|---|
| Platform | Android only, minSdk 29 |
| Hardware | Akai MPC One, MPC Live II, MPC Live III |
| Export format | MPC 2-era `.xpm` drum program + 44.1 kHz WAVs, as a folder |
| Not supported | `.xpn` expansion installers (desktop MPC Software only — irrelevant here) |

## Modules

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
gradle :xpm:test               # unit + golden-file tests
gradle :xpm:regenerateGolden   # only for deliberate format changes
```

## Docs

- [`docs/CONCEPT.md`](docs/CONCEPT.md) — product shape, MVP cut, architecture
- [`docs/ANDROID_CAPTURE.md`](docs/ANDROID_CAPTURE.md) — how capture actually works and where it breaks
- [`docs/MPC_EXPORT.md`](docs/MPC_EXPORT.md) — folder layouts and export paths
- [`docs/XPM_STRUCTURE.md`](docs/XPM_STRUCTURE.md) — the format, its provenance, and what's still unverified
- [`reference/README.md`](reference/README.md) — harvesting reference programs off hardware

## Next step

Load a generated kit on an **MPC One** and confirm all 16 pads fire on the pads
they were assigned to. The golden test proves the writer is self-consistent; only
hardware proves the MPC accepts it. See
[`docs/XPM_STRUCTURE.md#unverified`](docs/XPM_STRUCTURE.md#unverified) for the
specific open questions — the big one is whether instrument numbering is 0- or
1-based, which shows up as a kit shifted by exactly one pad.
