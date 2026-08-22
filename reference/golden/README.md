# Golden files — what's here and where it came from

Provenance matters more than content in this folder. A field's meaning is only
as trustworthy as the thing that wrote it, so every file records what produced
it.

## `expansion/` and `mpc3-track/` — Akai-authored shipping content

**Not device exports.** These came out of macOS installers for commercial MPC 3
expansions:

| Pack | Installer | Content |
|---|---|---|
| Classic Drum Machines | `Classic Drum Machines-1.0.3.pkg` | 72 drum tracks (`.xtd`) |
| F9 Gemini Future Classic Synths | `F9 Gemini Future Classic Synths-1.0.4.pkg` | 137 instrument tracks (`.xty`) |
| Producer Kit Essentials | `Producer Kit Essentials-1.0.2.pkg` | 40 drum tracks (`.xtd`) |
| Acoustic Drum Tools | `Acoustic Drum Tools-1.0.2.pkg` | 20 drum tracks (`.xtd`) |
| Percussion Tools | `Percussion Tools-1.0.3.pkg` | 82 drum tracks (`.xtd`) |

Each is a flat `xar` package whose `assets.pkg/Payload` is a gzip'd cpio
archive. Nothing was installed to extract them:

```sh
xar -xf "<pack>.pkg" -C out assets.pkg/Payload
cd out && gzip -dc assets.pkg/Payload | cpio -id '*.xtd' '*.xty' '*.xml'
```

They install to `/Library/Application Support/Akai/MPC 3/`, in two halves:

```
Akai/MPC 3/
├── Expansions/
│   ├── com.akaipro.mpc.expansion.classicdrummachines.xml   ← registry entry
│   └── com.akaipro.mpc.expansion.classicdrummachines.jpg
└── Content/com.akaipro.mpc.expansion.classicdrummachines/
    ├── Expansion.xml                          ← second copy, can be stale
    ├── Kit-SFM 808 Classy 124.xtd             ← the program
    ├── Kit-SFM 808 Classy 124_[TrackData]/    ← its WAVs, a sibling folder
    ├── <pack>.xpj + <pack>_[ProjectData]/     ← a demo project
    └── [Previews]/
```

The `_[TrackData]/` folders are pure factory audio and are deliberately not
harvested — same reasoning as the `.gitignore` here, and the same reasoning the
[reference README](../README.md) gives for `.xpm` files without their WAVs.

### `mpc3-track/`

The MPC 3 program container, eight real examples across six exporter builds and
two host OSes. Each is gzip; decompressed, five lines of ACVS header precede the
JSON body:

| File | Header lines 1–5 |
|---|---|
| `Kit-SFM 808 Classy 124.xtd` | `ACVS` / `3.6.0.106` / `SerialisableTrackData` / `json` / `Windows` |
| `Kit-SFM 909 Crisp 123.xtd` | `ACVS` / **`0.1.0.992`** / `SerialisableTrackData` / `json` / `Windows` |
| `Inst-Bass-F9 J8 Subsonic 130.xty` | `ACVS` / `0.1.0.1020` / `SerialisableTrackData` / `json` / `Windows` |
| `Inst-808-F9 Matrix 808 130.xty` | `ACVS` / **`0.1.0.999`** / `SerialisableTrackData` / `json` / `Windows` |
| `Phonk-Kit-91V Dirt C#m 70.xtd` | `ACVS` / **`0.1.0.983`** / `SerialisableTrackData` / `json` / `Windows` |
| `RnB-Kit-91V Grove G#m 85.xtd` | `ACVS` / **`0.1.0.983`** / `SerialisableTrackData` / `json` / `Windows` |
| `Kit-PSK 009 Hip Hop Kit.xtd` | `ACVS` / **`3.7.0.42`** / `SerialisableTrackData` / `json` / `OSX` |
| `Bass-60s Precision-Clean.xty` | `ACVS` / **`3.9.0.31`** / `SerialisableTrackData` / `json` / `OSX` |
| `Inst-Bass-NI Bass Artisan.xty` | `ACVS` / **`3.4.1.96`** / `SerialisableTrackData` / `json` / `Windows` |
| `Acoustic-Kit-MPCe-DFH Drum Tools.xtd` | `ACVS` / **`3.6.0.129`** / `SerialisableTrackData` / `json` / `Windows` |

The two Producer Kit Essentials tracks are here for evidence no other file
carries: **`triggerMode: 2` on filled drum pads** (6 of them in the Phonk kit —
vocals, synth and cowbell, while every kick, snare and hat stays at `0`), and a
**third permutation of the `0.50393…` pan value**, which sits on
`program.mixable.pan` here and on `data.pan` in the F9 files.

```sh
python3 -c "import gzip,sys; sys.stdout.write(gzip.open(sys.argv[1],'rt').read())" \
  "mpc3-track/Kit-SFM 808 Classy 124.xtd" | tail -n +6 > track.json
```

Pairs from the same pack are kept deliberately — diffing two near-identical
files is the fastest way to isolate a field. See
[`docs/MPC3_FORMAT.md`](../../docs/MPC3_FORMAT.md) for what they establish.

Line 2 is the **exporter build**, and it is not a vendor signature. The two
Classic Drum Machines kits above come from one Akai product and were written by
two different builds (`3.6.0.106` and `0.1.0.992`), with different `samples[]`
shapes to match. A third Akai pack, Producer Kit Essentials, is entirely
`0.1.0.98x` — the same build family as F9's.

So treat a difference between any two files as possibly a **build** difference
until the same build has written both. See
[One format, several exporter builds](../../docs/MPC3_FORMAT.md#one-format-several-exporter-builds).

### `expansion/`

Eight `Expansion.xml` files — first-party and third-party, both dialects. This
is what the [reference README](../README.md) asked for under "Also useful, if
easy", several times over.

| File | Which copy | Dialect |
|---|---|---|
| `classicdrummachines-Expansions-registry.xml` | top-level `Expansions/<id>.xml` | desktop |
| `classicdrummachines-Content-Expansion.xml` | in-folder `Content/<id>/Expansion.xml` | desktop |
| `f9gemini-Expansions-registry.xml` | top-level `Expansions/<id>.xml` | desktop |
| `ambientbox-standalone-Expansion.xml` | flat expansion folder | **standalone** |
| `producerkitessentials-Expansions-registry.xml` | top-level `Expansions/<id>.xml` | desktop |
| `acousticdrumtools-Expansions-registry.xml` | top-level `Expansions/<id>.xml` | desktop |
| `masada-xpn-Expansion.xml` | root of a `.xpn` | **standalone** |
| `platinumpercussion-xpn-Expansion.xml` / `-standalone-` | same pack, both copies | **standalone** |
| `prostudiokit-xpn-Expansion.xml` | root of a nested `.xpn` | **standalone** |
| `uprightbass-standalone-Expansion.xml` | standalone folder | **standalone** |
| `2stepgarage-mpc2desktop-Expansion.xml` | `Akai/MPC/` — an **MPC 2-era** desktop installer | desktop |
| `timelessglow-mpcdesktop-Expansion.xml` | `Akai/MPC/` — a pack shipping **both** generations | desktop |

Keep both Classic Drum Machines copies. They are byte-identical except for
`<version>`, and the disagreement is the finding: the in-folder copy says
`1.0.1.0` while the registry copy says `1.0.3.0`, which matches the installer.
The registry copy is the one that tracks releases. F9 ships **no** in-folder
`Expansion.xml` at all — so the in-folder copy is optional and the registry
entry is the file that counts.

## `keygroup/` — a standalone-format expansion

Source: `The Ambient Box - Standalone.zip`, a commercial expansion by
**soundspremium.com**, files dated March–April 2026. Not an installer — a plain
ZIP of the expansion folder as it goes onto an SD card, which is why it carries
the standalone dialect of `Expansion.xml` while the two `.pkg` packs carry the
desktop one.

```sh
unzip "The Ambient Box - Standalone.zip" -d out -x "*.wav"
```

Its 20 programs are **all MPC 2-era XML `.xpm`** — every one starts `<?xml`,
none is an MPC 3 `.xtd`. A pack authored in 2026 for standalone MPCs, stamped
`buildVersion="3.7.1.34"`, still ships XPM. That is the single strongest piece
of evidence that `:xpm` is a real shipping path and not a legacy detour.

| Harvested | Type | Size | Why this one |
|---|---|---|---|
| `Singing Bowl-TAB Infinity.xpm` | Keygroup | 64 KB | the small end — one keygroup |
| `Kalimba-TAB Magic.xpm` | Keygroup | 331 KB | the large end — ~12 keygroups |
| `Bass-TAB Deep Resonance.xpm` | Keygroup | 65 KB | a second vendor take at the small end, for diffing |
| `Bass-MPC3 AM Upright Bass.xpm` | Keygroup | 2.1 MB | 8-way velocity split, chromatic zones |
| `Inst-Bass-NI Bass Artisan.xpm` | Keygroup | 63 KB | **the MPC 2 half of a cross-generation twin** |

`Inst-Bass-NI Bass Artisan` is the same program as
`mpc3-track/Inst-Bass-NI Bass Artisan.xty` — same vendor, same samples, same
instrument, one written as MPC 2 XML and one as MPC 3 JSON. Timeless Glow ships
all 102 of its programs that way, with the `.xpm` tucked inside the `.xty`'s own
`_[TrackData]/` folder next to the WAVs. It is the cleanest reference available
for how one instrument maps across the generation split.

Not harvested, and why:

- **The 12 drum `.xpm` programs** are ~4 MB each. The size is not an embedded
  blob — it is 4096 `<ModLink>` and 4096 `<QuadrantEnabled>` elements, a fully
  expanded 128-pad × 32-slot modulation matrix in verbose XML. Nothing in there
  is worth 4 MB of repo.
- **`.mpcpattern`** (19 of them) is plain, ungzipped JSON starting
  `{"pattern": …` — a sequence format distinct from both MPC 2's `.sxq` MIDI
  files and MPC 3's embedded clip JSON. Noted, not needed.
- **`.cache-mpc3.json` / `.tagsCache-mpc3.json`** are MPC-generated browser
  indexes, regenerated on any device. They do reveal that MPC 3 catalogues this
  MPC 2-format pack, and that they use MPC 3's `value0` indexed-dict idiom.
- **WAVs and `[Previews]/`.** The pack's `<description>` carries an explicit
  "not allowed to sell these sounds in isolation" clause; the audio stays out,
  as it does everywhere in this folder.

## `drum/` — inside a real `.xpn`

Source: `Masada Cycle kit vol.4 all bundle`, three ZIPs from **May 2017**, each
containing a `.xpn` expansion archive. `Expansion.xml` inside says
`buildVersion="1.9.6.1"` — MPC 1.9 era, nine years older than The Ambient Box,
which is exactly what makes the pair useful.

`masada-xpn-listing.txt` is the finding, not the bytes. The `.xpn` is 45 MB and
is not committed; its **structure** is:

```
Masada Cycle kit v.4 pt.1.xpn        ← PK\x03\x04, an ordinary ZIP
├── Expansion.xml                    ← root
├── part-1.jpg                       ← root artwork
├── hiphop-Drum-kit-Mck4 01…08.xpm   ← root
├── load all.xpj                     ← a project rides along
└── 445 × .wav                       ← root
```

Masada is **fully flat** — zero entries containing `/`. That is one layout, not
the layout. Three listings are kept here so the spread is visible:

| Listing | Archive | Entries | Nested |
|---|---|---|---|
| `masada-xpn-listing.txt` | Masada Cycle (2017) | 456 | 0 |
| `platinum-xpn-listing.txt` | Platinum Percussion (2026) | 283 | 5 — a `[Previews]/` folder |
| `prostudiokit-mpc3-xpn-listing.txt` | Pro Studio Kit MPC3 (2026) | 191 | 189 — `Drum Kits/`, `Drum Kits/Samples/`, `[Previews]/`, `[MIDI Patterns]/` |

**The one invariant is `Expansion.xml` at the archive root, 4 of 4.** Nesting is
free, and Pro Studio Kit proves a foldered archive ships and sells.

Two cautions this cost. An earlier revision of this file claimed Platinum had
zero nested entries — it has five, and the miscount came from a shell one-liner
splitting on whitespace, which dropped filenames containing spaces. And the
`Samples/` subfolder in Pro Studio Kit does **not** change how programs
reference audio: `<SampleFile>` is still empty and `<SampleName>` still bare.
See
[`docs/MPC_EXPORT.md`](../../docs/MPC_EXPORT.md#xpn--implemented-and-two-of-our-three-layout-choices-are-wrong).

Platinum Percussion also ships **the same pack twice** — once as the `.xpn`,
once as a standalone folder — and both `Expansion.xml` copies are in
`expansion/`. They differ only in `buildVersion` (`3.7.0.42` vs `2.11.2.2`) and
`<version>` (`2.5.0.0` vs `2.5.0`), which is the cleanest possible proof that
neither field is load-bearing.

Two of the eight programs are harvested (~920 KB each), for different reasons:

| File | Carries |
|---|---|
| `hiphop-Drum-kit-Mck4 01.xpm` | **real pad colours** — 21 of 128 slots populated, `universalPad` `0x007F00` |
| `hiphop-Drum-kit-Mck4 03.xpm` | **non-zero `TuneCoarse`** — and a different `universalPad`, `0x7F0000` |

Colour is decoded in
[`docs/XPM_STRUCTURE.md`](../../docs/XPM_STRUCTURE.md). The tune values matter
because every other file in this whole corpus leaves tune at `0`: across the
pack's eight programs `TuneCoarse` runs `-4 … +2` against 5,056 zeroes, which
is the only evidence anywhere that the field is a **signed semitone integer**
rather than something scaled.

That kit 01 has colours and no tune, while 03 has tune and different colours,
is the general shape of this corpus — vendors vary one thing per file, and you
need several files to see any of it.

Worth noting for scale: a 2017 drum program is 921 KB and a 2026 one is 4 MB,
same 128 pads. The format inflated roughly 4× in nine years, all of it in
per-pad modulation elements.

## `mpc3-project/` — projects, both generations

Source: `dirty-drummer-complete.zip` (Dirty Drummer Collection, files dated
2022–2026). Its "MPC Sample Edition" half ships 60 `.xpj` projects with
`_[ProjectData]/` sample folders — the container
[`docs/MPC3_FORMAT.md`](../../docs/MPC3_FORMAT.md) documented from a third-party
write-up and had never seen.

| File | What it is |
|---|---|
| `DD1 Chamber 92bpm.xpj` | `ACVS` / `1.2.1.2` / `SerialisableProjectData` / `json` / `Linux`. 4 tracks, `tracks[0].program.type 0`, 128 instruments, 53 filled layers. |
| `Kit-DD1 Combo 87bpm.mpcsample` | `ACVS` / `3.8.0.11` / **`SerialisableAC50ExportData`** / `json` / `OSX`. 1.2 KB, holds only `muteGroups`, `sequences`, `simultPlayTargets`. |

These confirm `Mpc3Project`'s `data.tracks[]` model is correct **for projects**
— it is only track files it cannot read — and that `padNoteMap` sits on
`program` in projects too, not `program.drum`.

`2 Step Garage Demo Project.xpj` is the other half of the pair: an **MPC 2**
project, `<?xml` with a `<Project>` root, from a desktop installer that lands in
`Akai/MPC/Content/` (no "3"). Keeping both makes the point the format docs
assert — same extension, different container, detect by content.

`Snapshot-PSK Jungle Kit.sxq` is an MPC 2 sequence: a standard MIDI file
(`MThd`, format 1, division `0x03C0` = 960 PPQ), which ships beside its `.xpm`
under the same stem.

One file in the Dirty Drummer pack is **corrupt**: `DDA Lofi Mix 70bpm.xpj` is 50,178 bytes
of which the first 28,672 (`0x7000`) are zero, followed by high-entropy data
with no gzip header. Not harvested, but worth recording — commercial packs
contain files matching neither `1F 8B` nor `<?xml`, so a reader has to degrade
gracefully rather than assume one of the two.

## Still wanted

Nothing here is a hardware save, so none of it closes the items the
[reference README](../README.md) lists:

- **A program saved by a Live III** — these files show what Akai's *authoring
  tools* write. They are strong evidence for what the firmware writes, not
  proof. Goes in `liveiii-36/`.
- ~~**An MPC 2 keygroup `.xpm`**~~ — three are in `keygroup/`. What they can't
  supply is firmware provenance: they are one vendor's output, same evidence
  class as the `.xtd` files, and a vendor can be idiosyncratic without being
  wrong.
- **MPC 2 drum `.xpm` from hardware** — [backlogged](../README.md#backlog-mpc-2)
  along with the rest of MPC 2 device verification.
