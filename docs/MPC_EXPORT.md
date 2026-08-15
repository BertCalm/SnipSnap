# MPC export

## Target devices

| Device | Firmware line |
|---|---|
| MPC One | MPC 2.x / 3.x |
| MPC Live II | MPC 2.x / 3.x |
| MPC Live III | ships on MPC 3.6 |

### Format decision: target the MPC 2-era XPM

MPC 3 restructured how programs work — a program is loaded into a track and
becomes part of that track; the global program pool of MPC 2 is gone. MPC 3
*projects* are not backward-compatible with MPC 2.15 desktop software. But
MPC 3 hardware still reads MPC 2 content, and Live III is explicitly compatible
with projects made on MPC 2 firmware.

So the common denominator across all three devices is the **MPC 2-era drum
program**. Writing an MPC 3-native program would strand the One and Live II and
buy nothing.

**The MPC One is the acceptance test.** If it loads there, it loads everywhere.

## What we do *not* build: `.xpn`

`.xpn` is an *installer* produced by Akai's Expansion Builder, aimed at the
desktop MPC Software / MPC Beats on Mac and Windows. Standalone hardware does
not consume `.xpn` files at all — it reads folders off a drive.

Since this project is standalone-only, `.xpn` is out of scope permanently, not
just deferred. This removes a large reverse-engineering effort from the roadmap.

## Tier 1 — bare program folder (MVP)

The whole export. Drop it anywhere on the SD card or USB drive, browse to it on
the MPC, load the `.xpm`.

```
SnipSnap Kit 01/
├── SnipSnap Kit 01.xpm
├── SS_Kick_01.wav
├── SS_Snare_01.wav
└── ...
```

The `.xpm` references samples by name; the MPC resolves them from the same
folder. Keep them adjacent and it just works.

## Tier 2 — expansion folder (v2)

Makes the kit browsable in the MPC's Expansion tab. Copy the whole instrument
folder into an `Expansions` folder on the drive.

```
Expansions/
└── SnipSnap Kit 01/
    ├── Expansion.xml
    ├── SnipSnapKit01.png       ← tile artwork
    ├── Programs/
    │   └── SnipSnap Kit 01.xpm
    ├── Samples/
    │   ├── SS_Kick_01.wav
    │   └── SS_Snare_01.wav
    └── [Previews]/
        └── SnipSnap Kit 01.mp3 ← named to match the .xpm
```

## Sample requirements

| | |
|---|---|
| Format | PCM WAV |
| Sample rate | 44.1 kHz |
| Bit depth | 16 or 24-bit (we write 24) |
| Channels | mono or stereo |
| Filenames | ASCII, no special characters, descriptive — `SS_Kick_01.wav` |

Anything else gets converted by the MPC on load, or fails. We normalise on
capture so export never has to convert.

## XPM anatomy

XML. Broad shape:

```
<MPCVObject>
  <Version>...</Version>
  <Program type="Drum">
    <ProgramName>...</ProgramName>
    <ProgramPads-v2.10>  ← JSON blob: pad→MIDI-note map, pad colours
    <Instruments>
      <Instrument number="0">
        ...envelopes, level, pan, tune, mute group...
        <Layers>
          <Layer number="1">
            <SampleName>SS_Kick_01</SampleName>
            <SliceStart>/<SliceEnd>
```

A drum program addresses up to 128 instruments (8 banks × 16 pads). We use the
first 16 for MVP.

### ⚠️ Do not author the pad map from documentation

The pad→MIDI-note mapping and pad colour encoding live inside the
`ProgramPads-v2.10` JSON blob, and the exact structure is not reliably
documented anywhere public. Deriving it from blog posts or from an LLM's memory
will produce a file that looks right and loads wrong.

**The only correct approach:** export a real 16-pad kit from real hardware, keep
it as a golden template, and have the writer substitute sample names and
parameters into that known-good structure.

See [`../reference/README.md`](../reference/README.md) for how to capture one.

## Test strategy

1. **Golden-file test** — writer output diffs against a byte-stable expected
   file for a fixed input kit. Catches accidental structural drift.
2. **Round-trip on hardware** — generate a 16-pad kit, load it on the MPC One,
   confirm all 16 pads trigger the right sample at the right pitch. Repeat on
   Live II and Live III. This is manual and it is mandatory before shipping.

Automate (1), never skip (2).

## Transfer to the device

Direct SD/USB export via the Storage Access Framework
(`ACTION_OPEN_DOCUMENT_TREE`) with an OTG reader:

```
Export → pick your MPC card → done
```

SAF handles removable FAT32 volumes without broad storage permissions. This
beats "export a zip, find a computer" by a mile and should be the default path,
with share-a-zip as the fallback.
