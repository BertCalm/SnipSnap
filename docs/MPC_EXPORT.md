# MPC export

## Target devices

| Device | Firmware line |
|---|---|
| MPC One | MPC 2.x / 3.x |
| MPC Live II | MPC 2.x / 3.x |
| MPC Live III | ships on MPC 3.6 |

### Format decision: MPC 3 native, with MPC 2 as the compatibility path

**Primary target: MPC 3, acceptance device MPC Live III.**

MPC 3 is a different file format, not a revision of the old one — gzip-compressed
JSON behind an ACVS header, with programs embedded in the project's `tracks[]`
array rather than living in standalone XML. See
[`MPC3_FORMAT.md`](MPC3_FORMAT.md) for the schema and for the open question that
currently blocks a native writer.

**Compatibility path: the MPC 2-era XPM**, already implemented in `:xpm`. It
stays for three reasons: MPC 3 loads MPC 2 content (exporting `.xpm` is Akai's
own documented route across the 2/3 split), the MPC One and a 2.x Live II can't
load anything else, and it's the fallback if the MPC 3 program container proves
impractical to write from a phone.

So: two writers, one target each. The MPC One remains the acceptance device for
the MPC 2 path — if it loads there, it loads on every 2.x machine.

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

`Expansion.xml` carries Title, Manufacturer, Version (single digit), Identifier
(reverse-domain notation, dots not spaces) and Description. Artwork is a square
1000×1000 PNG or JPEG named to match the identifier.

Akai's own **MPC Expansion Builder** (free, installs with the MPC software) is
the reference implementation. Even though we never ship `.xpn`, pointing it at a
content folder is the closest thing to a conformance check that exists — see
[`KIT_BEST_PRACTICES.md`](KIT_BEST_PRACTICES.md).

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

The full structure — recovered from a program saved by MPC standalone firmware
2.9.1.2, along with the complete 128-entry pad→note map — is documented in
[`XPM_STRUCTURE.md`](XPM_STRUCTURE.md), and implemented in the `:xpm` module.

Do not hand-author the pad map from prose descriptions or from memory. It is a
lookup table, not a formula, and a plausible-looking guess produces a file that
loads but plays the wrong pads.

## Test strategy

1. **Golden-file test** — writer output diffs against a byte-stable expected
   file for a fixed input kit. Catches accidental structural drift. Implemented:
   `gradle :xpm:test`.
2. **Round-trip on hardware** — generate a 16-pad kit, load it on the MPC One,
   confirm all 16 pads trigger the right sample at the right pitch. Repeat on
   Live II and Live III. This is manual and it is mandatory before shipping.

Automate (1), never skip (2). (1) only proves the writer is self-consistent; it
says nothing about whether an MPC will accept the file. See
[`XPM_STRUCTURE.md`](XPM_STRUCTURE.md#unverified) for the specific open
questions (2) settles.

## Transfer to the device

Direct SD/USB export via the Storage Access Framework
(`ACTION_OPEN_DOCUMENT_TREE`) with an OTG reader:

```
Export → pick your MPC card → done
```

SAF handles removable FAT32 volumes without broad storage permissions. This
beats "export a zip, find a computer" by a mile and should be the default path,
with share-a-zip as the fallback.
