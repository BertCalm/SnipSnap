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

## `.xpn` — implemented, and two of our three layout choices are wrong

Earlier revisions called `.xpn` desktop-only and out of scope. The XO_OX XPN
toolchain (BertCalm/XO_OX-XOmnibus) revised that: it ships "MPC-loadable"
`.xpn` archives with a documented internal structure, so `XpnPackager` in
`:kit` writes one — deterministic, preflight-gated, with pack-relative `<File>`
sample paths (Rex Rule #5 from that toolchain).

**Four real commercial `.xpn` archives** have now been examined, from three
vendors across 2017–2026. They confirm the container — `PK\x03\x04`, an ordinary
ZIP — and establish exactly one structural rule.

> **Correction.** An earlier revision of this section claimed real `.xpn`
> archives are "completely flat, zero nested entries," from two samples. That
> was wrong twice over: Pro Studio Kit ships two deeply foldered archives, and
> the Platinum count was produced by a shell one-liner splitting on whitespace,
> which silently dropped five entries in a `[Previews]/` folder. Re-measured
> with a real ZIP reader:

| Archive | Entries | Nested | `Expansion.xml` |
|---|---|---|---|
| Masada Cycle (2017) | 456 | 0 | root |
| Platinum Percussion (2026) | 283 | 5 | root |
| Pro Studio Kit MPC2 (2026) | 232 | 230 | root |
| Pro Studio Kit MPC3 (2026) | 191 | 189 | root |

**The invariant is `Expansion.xml` at the archive root — 4 of 4.** Nesting is
free. Pro Studio Kit MPC3 organises content properly:

```
Expansion.xml                        ← root, always
pro-studio-3-mpc3-edition.png        ← root
Drum Kits/*.xpm                      ← 17 programs
Drum Kits/Samples/*.wav              ← a Samples/ folder
Drum Kits/[Previews]/                ← per-program previews
Drum Kits/[MIDI Patterns]/
MPCe Kits/…                          ← a parallel second set
```

So `XpnPackager` nesting `Programs/` and `Samples/` is **not** a defect — real
packs do exactly that. What it gets wrong is narrower, and the flat archives
below still make the same point about paths.

```
Masada Cycle kit v.4 pt.1.xpn
├── Expansion.xml            ← root, not Expansions/
├── part-1.jpg               ← root artwork, no fixed name
├── hiphop-Drum-kit-Mck4 01.xpm … 08.xpm    ← root, not Programs/
├── load all.xpj             ← a project rides along
└── 445 × .wav               ← root, not Samples/<program>/
```

**`<SampleFile>` is never populated, and this holds even when samples *are* in
a subfolder.** Across every real program examined — three packs, 2017 and 2026,
drum and keygroup — **zero non-empty `<SampleFile>` elements, zero `<File>`
elements, and not one `<SampleName>` containing a path separator.** Pro Studio
Kit MPC3 keeps its WAVs in `Drum Kits/Samples/` and *still* references them by
bare name. MPC resolves samples by searching, not by path, exactly as
[Tier 1](#tier-1--bare-program-folder-mvp) describes for loose folders.

So `XpnPackager` had two real defects and one non-defect — **both defects
are now fixed**, and `XpnPackagerTest` pins the corrected layout:

| What it did | Verdict | Now |
|---|---|---|
| `Expansions/Expansion.xml` | **Wrong.** All four real archives put `Expansion.xml` at the root. | `Expansion.xml` at the archive root; nothing nests under `Expansions/`. |
| `XpmWriter(samplePathPrefix = "Samples/$programStem")`, populating `<SampleFile>` | **Wrong.** No real program does this, in any layout. | The path mechanism is deleted from both writers — bare `SampleName`s, empty `SampleFile`, no `<File>`. |
| `Programs/<Kit>.xpm` + `Samples/<Kit>/*.wav` subfolders | **Fine.** Real packs fold content into subfolders, `Samples/` included. | Kept. |

One more convention, also now matched: real previews live in a `[Previews]/`
folder, named for the program plus a second extension —
`[Previews]/Percussion-Skins 1.xpm.wav`. `XpnPackager` writes
`[Previews]/<Kit>.xpm.wav` (it used to write `Programs/<Kit>.wav`). The
plain-text manifest stays out of the archive — none of the real ones carries
one; it belongs to the on-card `Expansions/` folder layout.

### What this does and doesn't prove

**MPC accepts both layouts.** That was listed here as unresolvable from files;
it isn't. Three vendors ship commercial products spanning fully flat (Masada),
flat-plus-`[Previews]/` (Platinum), and deeply foldered (Pro Studio Kit), and
all of them are sold as working expansions. Archive organisation is free.

So the XO_OX toolchain's nested structure was never the problem — its one
substantive error is putting `Expansion.xml` under `Expansions/` instead of at
the root, which no real archive does.

The acceptance test still **runs today**: import `testkit/SnipSnap_Factory.xpn`
on the Live III — regenerated with the corrected layout (root `Expansion.xml`,
bare sample names, `[Previews]/`). "Nothing loads, no error, just silence"
remains the failure mode to expect if something else is still wrong.

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

## Tier 2 — expansion folder (implemented)

Makes the kit browsable in the MPC's Expansion tab. Copy the whole instrument
folder into an `Expansions` folder on the drive. Implemented as
`ExpansionWriter` in `:kit`; `./gradlew :synth:generateExpansionPack` builds
the acceptance pack under `testkit/Expansions/`.

> **Provenance (confirmed):** the lowercase `<expansion>` form came from the
> XO_OX XPN toolchain's packager (`xpn_packager.py`) — "running code that
> ships packs", one step short of a diff against a real pack. That step is
> now taken. A commercial 2026 standalone expansion
> ([`reference/golden/expansion/ambientbox-standalone-Expansion.xml`](../reference/golden/expansion/ambientbox-standalone-Expansion.xml))
> emits the same root attributes, the same elements, in the same order.
> **This writer is correct.** The hardware check still stands as an
> end-to-end test, but the schema is no longer the risk.

### Two dialects, and we write the right one

`Expansion.xml` comes in two forms, and they are not two generations — they
are two deployment targets. Five real files split cleanly:

| | Standalone / SD | Desktop-installed |
|---|---|---|
| Where | a flat expansion folder | `Library/Application Support/Akai/MPC 3/` |
| Root | `version="2.0.0.0"` **+ `buildVersion`** | `version="1.0"`, no `buildVersion` |
| `<directory>` | absent | present |
| `<local/>` `<priority>` `<description>` | present | absent |
| Examples | The Ambient Box, **ours** | Classic Drum Machines, F9 Gemini |

`buildVersion` is the discriminator — present on every standalone file, absent
from every desktop one. We target standalone, which is right for a Live III.

**The axis is deployment target, not generation**, and the case that could have
falsified it now exists. 2 Step Garage is an **MPC 2-era** desktop installer —
it lands in `Akai/MPC/Content/` (no "3"), and ships `.xpm` programs with `.sxq`
sequences, not `.xtd`. Its `Expansion.xml` is nonetheless the *desktop* form,
identical in shape to the MPC 3 packs':

```xml
<expansion version="1.0">                    <!-- no buildVersion -->
  …
  <directory>com.akaipro.mpc.expansion.2stepgarage</directory>
  <separator>-</separator>
</expansion>                                 <!-- no local/priority/description -->
```

So MPC 2 content in a desktop installer uses the desktop dialect, and MPC 2
content in a standalone folder (The Ambient Box, Masada) uses the standalone
dialect. Where the pack is installed decides the schema; which generation of
program it contains does not.

**`buildVersion` is not something to worry about**, and an earlier revision of
this section wrongly flagged our hardcoded `2.10.0.0` at
`ExpansionWriter.kt:146` as stale. Platinum Percussion settles it by shipping
*the same pack twice*, with a different value in each copy:

| Copy | `buildVersion` | `<version>` |
|---|---|---|
| inside `MPC Software Installer.xpn` | `3.7.0.42` | `2.5.0.0` |
| in the standalone folder | `2.11.2.2` | `2.5.0` |

Both load. `buildVersion` records whichever MPC build wrote that file, nothing
more, and `2.11.2.2` sits directly beside our `2.10.0.0`. The same pair also
shows `<version>` is not strictly four-part — `2.5.0` ships fine.

**`<separator>`** is optional: Masada, Platinum Percussion and Akai's desktop
files include it, The Ambient Box omits it, all ship. We emit it.

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

`Expansion.xml` carries identifier, title, manufacturer, a four-part
version, type ("instrument"), priority, an `img` artwork reference and a
description, in a lowercase `<expansion version="2.0.0.0">` root; a
plain-text `manifest` (Name=/Version=/Author=/Description=) rides alongside.
Artwork is a square PNG named for the pack.

Akai's own **MPC Expansion Builder** (free, installs with the MPC software)
remains a useful desktop conformance check — see
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

**The sampler sheet.** Our WAVs carry no metadata chunks — except the
`smpl` chunk a pitched or looped sample asks for (`WavWriter.write(…, smpl =
SmplChunk(root, loop))`). Every zone a one-note or multisample package
writes carries its root note and, when the zone loops, its sustain loop,
read back from the program itself; the program file stays the source of
truth when the sample loads *inside* the program, and the sheet is there
for the day it is loaded on its own, from the card's browser, outside any
program. A write without a sheet is byte-identical to before. Whether the
Live III honours the sheet on a standalone load is a bench row (wave AAA);
the chunk follows the RIFF sampler spec every other sampler reads.

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
