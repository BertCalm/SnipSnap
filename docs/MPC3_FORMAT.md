# MPC 3 format

**Target decision: MPC 3 native, MPC Live III as the acceptance device.**

This supersedes the earlier "target MPC 2-era XPM everywhere" call. The MPC 2
writer in `:xpm` stays — see [Why the MPC 2 writer survives](#why-the-mpc-2-writer-survives).

## MPC 3 is not XML

The headline: MPC 3 abandoned XML. It is a different container, a different
serialization, and a different program model.

| Aspect | MPC 2.x | MPC 3.x |
|---|---|---|
| Container | plain XML text | gzip-compressed, ACVS header + JSON |
| Magic bytes | `3C 3F 78 6D 6C` (`<?xml`) | `1F 8B` (gzip) |
| Programs | separate `.xpm` XML files | **embedded in the `tracks[]` JSON array** |
| Standalone program | `.xpm` | `.xtd` (drum) / `.xty` (instrument) |
| Companion folder | `.xpm`, `.xal`, `.sxq`, `.mcn` | `<name>_[TrackData]/` — WAVs, and optionally the MPC 2 twin |
| Sequences | standard MIDI files (`.sxq`, 960 PPQ) | embedded JSON events |
| Firmware range | 2.0 – ~2.12.x | 3.0 – 3.7.0.56 confirmed |

Both generations use the `.xpj` extension for projects, so **detection must be
content-based** — check magic bytes, never the extension. Both halves of that
are now verified against real files rather than asserted:

| Generation | `.xpj` first bytes | Root |
|---|---|---|
| MPC 2 (2 Step Garage) | `3C 3F 78 6D 6C` `<?xml` | `<Project>` |
| MPC 3 (Dirty Drummer) | `1F 8B` gzip | `ACVS` / `SerialisableProjectData` |

Both are in [`reference/golden/mpc3-project/`](../reference/golden/mpc3-project/).

The MPC 2 companion-file convention is confirmed too: a program ships beside a
same-stem sequence — `Garage-Kit-Agent Kit 128.xpm` next to
`Garage-Kit-Agent Kit 128.sxq` — and `.sxq` really is a standard MIDI file,
`MThd`, format 1, with division `0x03C0` = **960 PPQ** exactly as documented.

### The ACVS header

After gzip decompression, five lines of text precede the JSON payload:

```
ACVS                        ← magic identifier
3.7.0.56                    ← firmware version
SerialisableProjectData     ← object type name
json                        ← serialization format
Linux                       ← platform (standalone hardware)
```

```python
import gzip, json
raw = gzip.decompress(open("project.xpj", "rb").read()).decode("utf-8")
lines = raw.split("\n", 5)
header, data = lines[:5], json.loads(lines[5])["data"]
```

Line 3 being an **object type name** is the interesting part — it implies the
container is generic across MPC 3 object types, not specific to projects. It
is, and that is now confirmed rather than inferred:

| Line 3 | Written to | Seen in |
|---|---|---|
| `SerialisableProjectData` | `.xpj` | 59 real projects; [`reference/golden/mpc3-project/`](../reference/golden/mpc3-project/) |
| `SerialisableTrackData` | `.xtd`, `.xty` | [`reference/golden/mpc3-track/`](../reference/golden/mpc3-track/) |
| `SerialisableAC50ExportData` | `.mpcsample` | [`reference/golden/mpc3-project/`](../reference/golden/mpc3-project/) |

`.mpcsample` is a small sidecar (~1.2 KB) carrying only `muteGroups`,
`sequences` and `simultPlayTargets` — not audio, despite the name.

**A reader must survive files that are none of these.** A shipping commercial
pack (Dirty Drummer Collection) contains a `.xpj` whose first 28,672 bytes —
exactly `0x7000` — are zero, followed by high-entropy data with no gzip header.
It is corrupt, not a third container, but it is *in the box*: real content
includes files that match neither `1F 8B` nor `<?xml`, so detection must return
"unknown" rather than assume or throw.

Lines 2 and 5 describe the **exporter build and its host OS**, not the format
and not the vendor. Observed builds span `0.1.0.979` … `0.1.0.1020`, `3.4.0.105`
and `3.6.0.106`, on `Windows` and `Linux` — and a single pack can contain two of
them. Neither line is a firmware version, and a reader must not gate on either.
See [One format, several exporter builds](#one-format-several-exporter-builds).

## Drum program schema

Path: `tracks[n].program.drum`. 128 pads, 8 layers per pad — both confirmed
against real files. Corrected against
[`reference/golden/mpc3-track/`](../reference/golden/mpc3-track/):

```json
{
  "program": {
    "version": 4,
    "name": "Kit-SFM 808 Classy 124",
    "type": 0,
    "padNoteMap": { "noteForPad": { "value0": 36, "value1": 37 } },
    "programPads": {},
    "mixable": {},
    "drum": {
      "version": 8,
      "drumVersion": 12,
      "instruments": [ /* always 128 slots */ ],
      "poliphony": 0,            // see below — meaning unestablished
      "coarseTune": 0,
      "fineTune": 0,
      "padGroup": { /* value0..value127 */ }
    }
  }
}
```

Three corrections from the previous, prose-sourced version of this block:

- **`padNoteMap` sits on `program`, not `program.drum`.** One level up, and
  checked in all four harvested files: present on `program` every time, absent
  from `program.drum` every time. It is always a full 128-entry map — `36, 37,
  … 51, …` on a drum track, and an inert identity map (`value0: 0, value1: 1`)
  on an instrument track, which addresses pitch by zone key range instead.
- **`drum.version` is 8**, not 2 — and it reads 8 on instrument tracks too.
- **"Schema version 28" exists — at project level.** An earlier revision
  removed it entirely ("no `version` field in any harvested file holds that
  value") after checking track files, which carry `data.version: 5`. The DD1
  Chamber **project** carries `data.version: 28`, vindicating the community
  write-up on this one: the value is real, it just belongs to
  `SerialisableProjectData`, not to tracks.

**Do not gate a reader on any version integer.** `program.version` is `4` in
one Classic Drum Machines kit and `2` in another from the same pack, and
`drum.drumVersion` is present (`12`) in the first and **absent entirely** from
the second. Akai's own content would fail a strict schema check.

### What maps across from our MPC 2 model

| SnipSnap `Pad` | MPC 2 XPM | MPC 3 JSON |
|---|---|---|
| sample | `<SampleName>` (no extension) | `layersv[i].sampleName` (no extension) **and** `layersv[i].sampleFile` (with extension) |
| length | `<SliceEnd>` | `layersv[i].sliceInfo.End` |
| mute group | `<MuteGroup>` | `whichMuteGroup` (0 = none, 1-32) |
| one-shot | `<OneShot>` bool | `triggerMode` int — see [below](#triggermode-all-three-values-observed) |
| tune | `<TuneCoarse>` / `<TuneFine>` | `coarseTune` / `fineTune` |
| pad→note | `<PadNoteMap>`, 1-based `<PadNote number>` | `program.padNoteMap.noteForPad`, **0-based** `value0..value127` |

The model in `DrumProgram.kt` survives the transition nearly intact. That was
worth getting right.

### Three gotchas, two of them corrections

1. **A sample is named twice, in two fields.** `sampleName` is bare
   (`Vintage-Kick-SFM 808 Kicks1`) and `sampleFile` carries the extension
   (`Vintage-Kick-SFM 808 Kicks1.wav`). Both are populated on every used layer,
   and both must match the track-level pool — `sampleName` to `samples[].name`,
   `sampleFile` to `samples[].path`. Earlier revisions of this document claimed
   the extension lived on `sampleName`; it does not, and the MPC 2 semantic
   survived unchanged under a second field name.
2. **`sampleEnd` is not the length field.** It reads `0` on all 16 populated
   layers of both harvested kits; the real end offset is `sliceInfo.End` (e.g.
   `3187907` for a kick, `8722` for a closed hat). This is the worst kind of
   wrong to ship: `sampleEnd` exists, accepts a value, and is ignored, so a
   writer that populates it produces kits that play at full sample length with
   no error anywhere.
3. **The pad note map is 0-indexed here** (`value0` = pad 1) where MPC 2 used
   1-based `<PadNote number="1">`. Same table, different base — exactly the class
   of off-by-one that put `instrumentBaseIndex` in the MPC 2 writer.

### `triggerMode`: all three values observed

The enum was documented from prose as `0` One Shot / `1` Note Off / `2` Note On.
All three now appear on **filled** pads in real drum programs, and what they are
used for confirms the labels:

| Value | Filled pads | Used for |
|---|---|---|
| `0` One Shot | 595 | kicks, snares, hats — the whole sample fires and ends |
| `1` Note Off | 34 | **rolls** — `Snare 14 Roll 01` plays while held, stops on release |
| `2` Note On | 68 | vocals, loops, keys, guitar, sustained chops |

Counts from Pro Studio Kit 3, which is the only pack of eleven carrying `1`.
Earlier revisions of this document recorded `1` as never observed; it is now in
[`reference/golden/mpc3-track/Kit-PSK 009 Hip Hop Kit.xtd`](../reference/golden/mpc3-track/).

This is a **per-pad musical choice, not a program-type default.** One drum
program routinely mixes all three. Empty slots carry `0` — and, in this pack,
sometimes `1`, so `triggerMode` is not usable as a fill test either.

### Empty vs. filled pads

Answered by the harvested kits, and it closes an open question in
[`reference/README.md`](../reference/README.md).

**Drum programs emit all 128 slots fully formed** — 7 of 7 harvested, no
exceptions. An unused pad is not omitted, null, or truncated; it carries the
same key set as a used one, with plausible defaults (`highNote: 127`,
`polyphony: 3`, `mixable.pan: 0.5`). The whole difference sits in `layersv[0]`:

| Field | Used pad | Empty pad |
|---|---|---|
| `sampleName` / `sampleFile` | `"…808 Kicks1"` / `"….wav"` | `""` / `""` |
| `sliceIndex` | `0` | `128` (sentinel) |
| `sliceInfo.End` | `3187907` | `0` |
| `sliceInfo.LoopCrossfadeLength` | `-1` | `0` |

Read `layersv[0].sampleName == ""` as the emptiness test.

**Keygroup programs are not consistent about this, so a reader must not assume
128.** Most write the full array too, but some write exactly `numKeygroups`:

| `numKeygroups` | `instruments` length | Files |
|---|---|---|
| 5, 6, 7, 8, 9, 13, 23, 25 | 128 | all |
| 1 | 128 | 38 |
| 1 | **1** | 48 |

It is not a vendor or build rule — **Timeless Glow ships both shapes inside one
pack**, 19 single-slot files beside 38 padded ones, same `numKeygroups`, same
exporter. So the array length is simply not load-bearing: MPC accepts either
for identical content.

For a writer, 128 is the safe choice — it is what every drum program does and
what most keygroups do. For a reader, take the length from the array.

### Sample pool

MPC 3 adds a track-level `samples[]` pool alongside the per-layer references:

```json
{
  "version": 1,
  "name": "Vintage-Kick-SFM 808 Kicks1",          // no extension
  "path": "Vintage-Kick-SFM 808 Kicks1.wav",      // with extension
  "loadImpl": 0,
  "metadata": { "tempo": 85.5199966430664, "rootNote": 60, "tune": 0.0, "key": "G# Major" }
}
```

The pool is **not optional bookkeeping**. In every harvested file the set of
`samples[].name` equals the set of layer `sampleName`s exactly, and
`samples[].path` equals the set of `sampleFile`s — a strict 1:1, deduplicated
(one Classic Drum Machines kit has 16 used pads referencing 12 distinct
samples, because hats repeat). `path` is a bare filename with no directory
component: the WAVs live flat in the sibling `<name>_[TrackData]/` folder.

`rootNote` 60 (C4) for one-shots holds — all 12 pool entries in the harvested
kit use it. **The `30.0` / `300.0` "not tempo-aware" sentinel does not.** Real
values are `20.0` for percussion and hats but `85.5199966430664` and
`109.16100311279297` for kicks and snares — plausible detector output, not a
flag. Treat `metadata.tempo` as an estimate to fill in, not a sentinel to match.

### Serialization patterns

The firmware's C++ serializer leaves fingerprints a writer must reproduce:

| Pattern | Example | Status |
|---|---|---|
| indexed-dict — arrays as `valueN` keyed objects | `"noteForPad": {"value0": 36, ...}` | confirmed, pervasive |
| ADSR wrapper | `"Attack": {"value0": 0.0}` | confirmed |
| colour as packed int | `2733428` = `0x29B574` = `(R<<16)\|(G<<8)\|B` | confirmed |
| **`poliphony`** | misspelled at `program.drum.poliphony` | confirmed — but see below |
| bitmap-as-string | `noteRange.data` = 132 `"1"` characters | new |
| enum wrapper | `"EnumCerealisationWrapper(selectedModifierType)": "Tuning (coarse)"` | confirmed, **but only inside sequence events** |

Two of these need their scope pinned down, because the previous revision
generalised both too far.

**The enum wrapper is not a program-level convention.** Its only occurrences in
any harvested file are inside sequence note-modifier data. Every enum-ish field
in the program schema — `filterType`, `oscillatorType`, `triggerMode`,
`behaviour` (77 occurrences) — is a plain integer, and `tempoSync` is a plain
string. Do not wrap program enums.

**`poliphony` is one typo, not a convention.** The misspelling is real at
`program.drum.poliphony`, and directly beside it `instruments[i].polyphony` is
spelled correctly. They are different fields at different paths. Reproduce both
spellings exactly; do not normalise either toward the other.

Its *value* is a separate question. Both drum tracks carry `poliphony: 0,
monophonic: false`; both instrument tracks carry `poliphony: 1, monophonic:
true` on the same vestigial block, while their zones say `monophonic: false`.
So `0` is what Akai writes for a working kit, but whether it means "unlimited"
or "unset" is unestablished — copy it, don't reason from it. The earlier value
of `6` in this sample was prose-sourced and appears nowhere in real content.

### `0.5039370059967041` is not a pan constant

The previous revision recorded this as "pan centre is not 0.5" and told writers
to emit it. That would have put every pad slightly right of centre forever.

The value is `float32(64 / 127)` — the normalisation of **MIDI 64**, the centre
of a 0–127 range. It shows up wherever a MIDI-domain value sits at its midpoint,
and **which field carries it moves around by exporter build**:

| Build | `data.pan` | `program.mixable.pan` | `0.50393…` also seen as |
|---|---|---|---|
| `3.6.0.106`, `0.1.0.992` | `0.5` | `0.5` | a note `velocity` |
| `0.1.0.979` | `0.5` | **`0.50393…`** | — |
| `0.1.0.999` / `.1020` | **`0.50393…`** | `0.5` | automation event values |

An earlier revision claimed `program.mixable.pan` is exactly `0.5` in all
harvested files. Producer Kit Essentials falsifies that — there it is
`0.50393…`, with `data.pan` at `0.5`, the exact inverse of the F9 packs.

So there is no rule about which field holds it. Both values mean centre; real
off-centre pans are ordinary floats (`0.39`, `0.59`, `0.61`). **Write `0.5` for
centre, read either as centre.**

### Pad colour: cheaper, but maybe not per-pad

Colour as a plain packed integer is a genuine improvement over MPC 2, where it
was buried in an escaped-JSON blob — `data.colour = 2733428` is RGB(41, 181,
116), the first real colour value this project has seen.

The catch: it is **track-level**. Across 128 instrument slots in both harvested
kits there is no per-pad `colour` key at all, even though
`padsFollowTrackColour: false` implies one should exist. Two files from one pack
cannot separate "absent from the schema" from "never set by these kits", so the
per-pad colour question stays open — but plan for track-level colour and treat
per-pad as unproven rather than cheap.

## The standalone program container — answered

This section used to describe "the one real unknown": what MPC 3 writes when a
program is saved on its own, with three candidates and a note that only a Live
III could settle it. **Candidate 1 was right**, established from Akai-authored
shipping content rather than hardware — see
[what this does and doesn't settle](#what-this-does-not-settle).

```
$ xxd -l 2 "Kit-SFM 808 Classy 124.xtd"
00000000: 1f8b                          ← gzip

$ gzip -dc "Kit-SFM 808 Classy 124.xtd" | head -5
ACVS
3.6.0.106
SerialisableTrackData          ← not SerialisableProgramData
json
Windows
```

Same ACVS container, generic as predicted. The refinement is line 3: MPC 3's
unit of saving is a **track**, not a program. That is consistent with programs
living at `tracks[n].program` inside a project — a standalone save is one
element of that array hoisted into its own file, carrying the program plus the
track's name, colour, mixer state, sample pool, and sequence clips.

Two extensions share the container:

| Extension | Contents | `program.type` |
|---|---|---|
| `.xtd` | drum track | `0` |
| `.xty` | instrument / keygroup track | `1` |

A program never ships alone. Each sits beside a sibling folder of its WAVs:

```
Kit-SFM 808 Classy 124.xtd
Kit-SFM 808 Classy 124_[TrackData]/      ← flat, one WAV per samples[].path
```

### Shipping both generations from one folder

Timeless Glow shows how a vendor covers MPC 2 and MPC 3 in a single pack, and it
revises the "WAVs only" claim above. Every one of its 102 programs exists in
both containers, name for name — 57 keygroups as `.xty` + `.xpm`, 45 drum kits
as `.xtd` + `.xpm` — and **the MPC 2 twin lives inside the MPC 3 program's own
data folder**:

```
Keygroups/
├── Inst-Bass-NI Bass Artisan.xty                    ← MPC 3 program
└── Inst-Bass-NI Bass Artisan_[TrackData]/
    ├── Bass D# Artisan.WAV                          ← the samples
    └── Inst-Bass-NI Bass Artisan.xpm                ← MPC 2 twin, beside them
```

One asset folder, two programs pointing at it. MPC 3 opens the `.xty`; an MPC 2
machine browses into `_[TrackData]/` and finds a `.xpm` sitting with its samples,
which is exactly the bare-folder arrangement [Tier 1](MPC_EXPORT.md) describes.

This is a repeated pattern, not one vendor's quirk: Infinite Escape does the
same thing, 79 programs, 79 twins, 79 `.xpm` inside `_[TrackData]/`, from the
same build family (`3.4.1.96` / `3.6.0.134`).

But it is still a *choice* — Classic Drum Machines' `_[TrackData]/` folders hold
WAVs and nothing else, while Timeless Glow's hold 777 WAVs and 102 `.xpm`. A
reader must not assume either.

The pair in [`reference/golden/`](../reference/golden/) is the same program in
both formats: `keygroup/Inst-Bass-NI Bass Artisan.xpm` (63 KB) and
`mpc3-track/Inst-Bass-NI Bass Artisan.xty` (10 KB) — the cleanest available
reference for how one instrument maps across the generation split.

### The reader

`:mpc3` reads both containers. `MpcFormats.detect` dispatches on content, never
extension, so `.xtd`/`.xty` need no special case, and `Acvs.read` accepts any
object type on header line 3 — both were built right from the start.

`Mpc3Project` models the **project** shape, and that model is confirmed against
real files rather than a third-party write-up: 59 `.xpj` projects from the Dirty
Drummer Collection all carry `data.tracks[]` (4 tracks each), with
`tracks[n].program.type` and `padNoteMap` on `program`.

It reads **track** files too, as of the commit that added this paragraph. A
`.xtd`/`.xty` has no `tracks` key — `data` *is* the track, carrying the same
name, colour, `samples` pool and `program` a project's array element does — so
it surfaces as a one-track list, which is structurally what it is. Detection is
by shape (`data.program` present) rather than by header line 3, so a file with
an unexpected object type still reads.

Before that, `drumPrograms()` returned an empty list for a file with 128
populated instrument slots: silent, not a crash, which is the worse failure.
`Mpc3ProjectTest` now reads real files out of
[`reference/golden/`](../reference/golden/) rather than only synthetic
fixtures — which is what caught the keygroup slot-count variance documented
above, since the old fixture asserted a shape no real file had.

### What this does *not* settle

These files were written by **authoring tools** — Akai's and F9 Audio's — not by
a standalone MPC. That is strong evidence for what the firmware writes, not
proof, and this document has been wrong before by trusting second-hand structure.

**The reading half is now settled on hardware (2026-08-23):** a Live III
loaded and played our generated `.xtd` — the container, the schema this
document describes, and our writer's output are all inside what real
firmware accepts.

What stays open is the *writing* half: does the hardware save the same
container? Build a drum program on the Live III, save it, and confirm the
first two bytes are `1F 8B` and header line 3 reads `SerialisableTrackData`.
Drop it in `reference/golden/liveiii-36/` — that save is also the last word
on any field this corpus can't show (firmware-authored defaults, per-pad
colour dialects, `fineTune` units).

## One format, several exporter builds

An earlier revision of this section called this "two authors, two dialects" and
split the differences by vendor — Akai versus F9 Audio. **That was wrong.** The
variation tracks the **exporter build on header line 2**, and two different
builds turn up inside a single Akai pack:

| Build | `samples[]` | `program.base.*` | `data.pan` | `program.mixable.pan` | Seen in |
|---|---|---|---|---|---|
| `3.6.0.106` | `version` + `metadata` | 32 keys | `0.5` | `0.5` | Classic Drum Machines |
| `0.1.0.979` | neither | none | `0.5` | **`0.50393…`** | Producer Kit Essentials |
| `0.1.0.992` | neither | none | `0.5` | `0.5` | Classic Drum Machines |
| `0.1.0.999` / `.1020` | neither | none | **`0.50393…`** | `0.5` | F9 Gemini |

Classic Drum Machines — one Akai product — ships `Kit-SFM 808 Classy 124.xtd`
stamped `3.6.0.106` beside `Kit-SFM 909 Crisp 123.xtd` stamped `0.1.0.992`, with
different `samples[]` shapes. Producer Kit Essentials is Akai-published and
entirely `0.1.0.98x`, i.e. the build family that was previously labelled "F9's".

Two consequences for a writer:

1. **Do not infer a house style from a vendor name.** The only thing that
   predicts these fields is the build stamp, and one vendor uses several.
2. **The richer output is still the safer target.** Emitting `samples[].version`
   and `metadata` is what the newest-numbered build does, and every other build
   omitting them loads fine — so the omission is clearly tolerated and the
   inclusion is clearly tolerated.

The keygroup schema below no longer carries a single-build caveat. It was first
read from `0.1.0.999`/`.1020` files; Electric Bass 3 supplies 52 more instrument
tracks from build **`3.9.0.31`** on OSX, and the structure holds unchanged —
zones in `program.drum.instruments`, `program.keygroup` with no `instruments`
key, 8 `layersv` slots, dual `filterData`/`lfoData` at `value0`/`value1`. What
that pack *did* change is the `numKeygroups` rule, below.

## The project writer

`Mpc3ProjectWriter` writes the whole session as one `.xpj` beside a flat
`<name>_[ProjectData]/` of WAVs. Content tracks come from
`Mpc3TrackWriter`'s builders — a project's `tracks[]` element is a hoisted
track file minus `solo`, confirmed key-for-key — plus the
mixer-infrastructure tracks every harvested project carries (`Submix 1`
type 8, `Out N/N` type 9: the track shell around a program with no drum or
keygroup block). The ~sixty boilerplate keys around them (mixer, QLink
assignments, pad-perform settings, 32 empty song slots) are the DD1 Chamber
project's own defaults, carried verbatim as a resource skeleton with the
content-specific parts scrubbed — verbatim beats reconstruction.

Drum clips become the project's **sequences** (probed 2026-08-25 for the
multi-sequence work): `data.sequences` is a keyed list of
`{key: N, value: sequence}` entries — key 0-based, `data.currentSequence`
picks by key — the same list idiom as `sharedClipMap`'s keys 1..4. Each
sequence value carries `version 5`, its own `name` ("Sequence 01" in the
harvested project), `bpm`, `lengthBars` + `lengthPulses`, loop bounds in
both units, a `timeSignatureTrack`, six empty `locators`, an empty
`seqEventList` (length INT64_MAX), and `trackClipMaps`: an outer
single-element list wrapping a map keyed by **track name** — every track
mapped, the groove's notes on the content track, empty clips on the
mixer-infrastructure tracks (`Out 1/2`, `Out 3/4`, `Submix 1`). Sequence
clip values carry `startPulses`/`endPulses`/`loop`/`legato`/`launch`/
`colour`/`perClipParameterValues` and omit `midiBankAndProgramNumber`.
`data.songs` is 32 `{name: "(unnamed)", ignoreTempo, items: []}` slots.

Our writer generalises that shape: sequence *k* holds every drum track's
*k*-th clip, so a kit's four groove variations arrive as four named,
switchable sequences (`MAX_SEQUENCES` caps at 32, far under the
hardware's 128). One caveat the corpus can't retire: the harvested
project carries a single sequence, so "several entries in the keyed
list" rests on the list idiom plus the hardware bench (AA1.3), not on a
multi-sequence golden file — when one lands, `snipsnap diff` closes the
question. `Mpc3ProjectWriterTest` guards key paths against the project
corpus, with `tracks[*]` paths also legitimised by the track corpus under
the hoisting equivalence, and runs the same guard through `MpcDiff` for
the multi-sequence payload.

`testkit/SnipSnap Session.xpj` is the acceptance artifact
(`./gradlew :synth:generateSessionProject`): the factory kit, all four S5
instruments, and the demo groove on the timeline — the entire SnipSnap
session in one file.

## Meter — the corpus probe (YYY10)

Probed 2026-09-12, before any writer code, because `Mpc3Clip` hardcodes a
3840-pulse bar and `Mpc3ProjectWriter` hardcodes `beatsPerBar 4` /
`beatLength 960` into every sequence it emits. The rule this repo learned
from `keyTrackEnable` applies again: read the whole corpus, then say what
it shows *and* what it cannot.

**Key paths.** Meter lives in two places under two different names.

```
$.data.sequences[N].value.timeSignatureTrack.timeSignatures[]   ← project (.xpj)
      └ { beatsPerBar: 4, beatLength: 960, barStart: 0 }

$.data.arrangementClipMap[N].value.timeSignatureList.timeSignatures[]  ← track (.xtd/.xty)
      └ { beatsPerBar: 4, beatLength: 960, barStart: 0 }
```

Same entry shape, different container key — `timeSignatureTrack` on a
sequence, `timeSignatureList` on a clip. A grep for one will not find the
other.

### `beatLength` is a pulse count, not a denominator

The entry has no `4/4` in it anywhere, and `beatLength: 960` is easy to
misread as "quarter note = the 960 PPQ constant, therefore decorative".
The harvested project settles it arithmetically, inside one file, without
needing a non-4/4 example:

```
sequences[0].value.lengthBars    = 2
sequences[0].value.lengthPulses  = 7680
beatsPerBar 4 × beatLength 960   = 3840 pulses per bar
2 × 3840                         = 7680   ✓ equals lengthPulses
```

So `beatsPerBar` is the numerator (beats in a bar) and `beatLength` is the
length of one beat **in pulses** — the denominator expressed as a duration
against the 960 PPQ clock, not as a `2 4 8 16`-style exponent the way a
standard MIDI file's `FF 58` carries it. 6/8 would therefore be
`beatsPerBar 6, beatLength 480`, and 3/4 `beatsPerBar 3, beatLength 960`
— *by construction from the arithmetic, not from an observed file*; see
the limits below.

`timeSignatures` is an array and each entry carries `barStart`, which is
the shape of a meter *track* — a list of changes keyed by the bar they
take effect at. Only ever one entry, `barStart 0`, has been observed.

### Build-gated on the track side

The clip-level field is not something every track file has. It arrives with
the clip object's own `version`, which tracks the exporter build on ACVS
header line 2 — the same build-gating this document records for
`samples[]` and `program.base.*`:

| Build | clip `version` | `timeSignatureList` | Files |
|---|---|---|---|
| `0.1.0.968` – `0.1.0.1020` | 1 | no | 7 |
| `3.4.0.105`, `3.4.1.96` | 1 | no | 2 |
| `3.6.0.106`, `3.6.0.129` | 2 | no | 2 |
| `3.9.0.31` | **3** | **yes** | 2 |

Both `3.9.0.31` files (`Kit-PSK 009 Hip Hop Kit.xtd`,
`Bass-60s Precision-Clean.xty`) carry it on all 128 `arrangementClipMap`
slots, every one `{beatsPerBar 4, beatLength 960, barStart 0}`. No file at
version 1 or 2 has the key at all. The versions are a clean ladder: v2 adds
`perClipParameterValues` over v1, v3 adds `timeSignatureList` over v2, and
nothing in the corpus breaks that order.

One of those 128 slots is a real clip in each file — key 8 with 78 note
events in the PSK kit, key 20 with 97 in the bass — so the field has been
seen on played music, not only on empty lanes. Those two clips repeat the
arithmetic cross-check on the track side: `endPulses` 15360 and 7680 are
exactly 4 and 2 × (`beatsPerBar` × `beatLength`).

The harvested project fits that ladder exactly: its `sequences[0]` is
sequence `version 5` and carries `timeSignatureTrack`, while the clip values
inside its `trackClipMaps` are clip `version 2` — they have
`perClipParameterValues` and no meter of their own, which is what v2 means.
Its header build is `1.2.1.2`, a number that does not sit anywhere on the
track exporters' `0.1.0.x` / `3.x` scale, so it gets no row in the table
above; the clip version, not the build, is what the field actually follows.

**Consequence for our writer.** `Mpc3TrackWriter` emits clip `version 1`
(`clipValue`, `arrangementClips`). Adding `timeSignatureList` there would
produce a byte shape no real file has: a version-1 clip with a version-3
field. Meter on the track path means moving that clip version first, which
is a much larger change than meter — and the project path, where the field
sits on the sequence rather than on a clip, is where a meter can go today.

A second finding fell out of the same probe, recorded here rather than
fixed: `Mpc3ProjectWriter` builds its `trackClipMaps` values with the same
`clipValue`, so the sequences it writes carry clip `version 1` and no
`perClipParameterValues`, where the harvested project's carry `version 2`
and do. Our output is a *lower* version than the corpus, not an invented
one — every key it writes exists in a real file — so the key-path guard
passes and this has never shown up. Whether a Live III minds is untested.

### What the corpus cannot show

Stated plainly, because this is exactly where the `keyTrackEnable` mistake
was made:

- **Zero non-4/4 examples.** Across all 14 ACVS files, the only values that
  ever appear are `beatsPerBar 4`, `beatLength 960`, `barStart 0` — no
  other value of any of the three, anywhere. The `.sxq` standard MIDI file
  agrees from the MPC 2 side: one `FF 58` meta event, 4/4, division 960.
  The MPC 2 `.xpj` carries `<BPM>` and no meter at all (its sequences live
  in a separate `.xal` that was never harvested).
- **Zero multi-entry examples.** That `timeSignatures` is an array keyed by
  `barStart` says mid-sequence meter changes are *representable*; it is not
  evidence that the hardware reads more than the first entry.
- **One project.** `DD1 Chamber 92bpm.xpj` is still the only real MPC 3
  project in `reference/`, so everything project-side is n=1.

The honest summary: the field's **shape and semantics are confirmed** (the
arithmetic cross-check is not weakened by n=1 — it is internally
consistent within the file). The field's **range is unverified** — that a
Live III accepts `beatsPerBar 3` has not been shown by any file and can
only be settled on the hardware bench or by a capture of a non-4/4 project.

### Grep trap: `chopTimeSignature`

`$.data.program.drum.instruments[*].chopProperties.chopTimeSignature: 2`
appears 128× per file in every `3.6.x`/`3.9.x` track (and in no `0.1.0.*`
or `3.4.x` one). It is a chop-editor property — how a sliced sample's grid
is subdivided — not the sequence meter, and its `2` is not a numerator.
A case-insensitive grep for `timesignature` finds it first and in bulk.

### What the writer does with this (YYY10, shipped)

The probe's conclusion turned out to be sharper than "we could write some
metadata". A 3/4 ORBIT set's clip was being written into 4/4 bars —
`OrbitClip` rounded a 24-step section up to two bars of 3840 pulses and
`endPulses`/`loopEndPulses` followed — so the exported loop played eight
sixteenths of silence the set never plays. Declaring the meter is what
sizes the container to the music, so this is a fix and not an annotation.

What ships:

- **`Mpc3Clip.pulsesPerBar`**, defaulted to `PULSES_PER_BAR`. Every clip
  from a donor groove, an import or the step editor is unchanged and never
  had to learn about it. Constrained to whole 960-pulse beats, because
  `beatsPerBar` × `beatLength` is all the format can say.
- **A project sequence declares the meter** and takes every length from it:
  `timeSignatureTrack`, `lengthBars`/`lengthPulses`, the loop bounds, and
  each clip's `endPulses` are one number, so the corpus's own cross-check
  holds by construction rather than by two expressions agreeing. Two
  meters in one sequence is refused — a sequence has one
  `timeSignatureTrack`, so there is no winner to pick.
- **A track file still pads to whole 4/4 bars.** Its clips are version 1;
  `timeSignatureList` is version 3. Writing it there would invent a shape
  no real file has.
- **A lap the format cannot spell keeps its 4/4 container.** `lapSteps` is
  only required to be `1..MAX_STEPS` and `OrbitStore` reads it straight
  from JSON, so 13 is reachable even though no control makes one — and a
  bar of 3120 pulses is not whole quarters. Those sets keep exactly the
  behaviour they have always had rather than crashing on a bar that cannot
  be written.

**What is still unverified, and it is the important part.** No file in the
corpus carries a `beatsPerBar` other than 4, so the first non-4/4 `.xpj`
this writes is the first one a Live III has ever been asked to read. The
arithmetic is confirmed and the shape is confirmed; the *range* is not.
The failure mode if the hardware rejects it is worse than the padding it
fixes — a project that will not open rather than a loop with a silent beat
— so this wants one bench check: export a 3/4 set and open it on the
device. That is the only evidence the corpus could never supply.

## Embedded sequences

Undocumented until now, and directly relevant since the repo already ships a
demo groove. A track file carries its own clips:

```
data.sharedClipMap[]                     ← pattern clips, populated (one had 127 events)
data.arrangementClipMap[]                ← 128 entries, arrangement lanes, empty in these files
  └ value.eventList.events[]
      { time: 14640,                     ← pulse offset
        type: 3,                         ← note event
        note: { note: 36, velocity: 0.5039370059967041, length, probability,
                ratchet, articulation, modifierValue0..15,
                "EnumCerealisationWrapper(selectedModifierType)": "Tuning (coarse)" } }
```

Velocity is a normalised float, not a 0–127 int — which is where the
`0.50393…` constant actually comes from. `probability` and `ratchet` per note
are MPC 3 additions with no MPC 2 equivalent.

## Keygroup program schema

Read from the two F9 `.xty` files in
[`reference/golden/mpc3-track/`](../reference/golden/mpc3-track/) — the first
real keygroup programs this project has seen, after `KeygroupWriter` was built
entirely from second-hand vocabulary. Everything here carries the F9-dialect
caveat above.

### Zones live in `program.drum.instruments`

The counter-intuitive part, and the one most likely to cost a day:

```
program.type            = 1              ← the discriminator: 0 drum, 1 keygroup
program.drum.instruments[0..127]         ← THE ZONES. lowNote, highNote, layers
program.keygroup                         ← global synth state ONLY, no zones
```

`program.keygroup` contains **no** `instruments` key. It holds `numKeygroups`,
pitch-bend ranges, wheel/aftertouch routing, a 32-slot mod matrix
(`modlinksData.value0..value31`), unison, harmoniser, and four
`*EnvelopeGlobal` booleans. The playable zones are in the same 128-slot
`program.drum.instruments` array a drum kit uses.

A writer that puts zones under `program.keygroup` produces a well-formed file
with a correct `numKeygroups` and nothing to play.

`program.drum`'s own top-level fields are vestigial on an instrument track —
`monophonic: true, poliphony: 1` sits there while every actual zone says
`monophonic: false`. Emit the block (both real files carry it in full), but do
not wire controls to it.

### Zone and layer model

| Field | Path | Example |
|---|---|---|
| zone count | `program.keygroup.numKeygroups` | `13` |
| key range | `instruments[i].lowNote` / `.highNote` | `0` / `37`, inclusive, tiled without gaps |
| root note | `instruments[i].layersv[j].rootNote` | `37` — **per layer, not per zone** |
| velocity range | `layersv[j].velocityStart` / `.velocityEnd` | `0` / `127` |
| sample | `layersv[j].sampleName` / `.sampleFile` | as in drum programs |
| trigger | `instruments[i].triggerMode` | `2` (Note On) on every zone |

Conventions a writer must match:

- **`numKeygroups` is a declared zone count, not a count of zones with
  samples.** An earlier revision claimed it equals the populated slot count and
  that populated slots are the first N contiguous from 0. Electric Bass 3
  falsifies both halves, identically across all 52 of its instruments:

  ```
  numKeygroups          25          ← declared
  slots with a sample   20          ← indices 4–23
  slots 0,1,2,3 and 24  no sample   ← but real zones: lowNote 38, highNote 38
  ```

  The empty ones are fully formed zones with key ranges, just nothing assigned.
  So gaps happen, and they happen *at the front*. Derive nothing from
  `numKeygroups` about which slots are filled — test `layersv[0].sampleName`.
- **8 `layersv` slots per zone**, always — unused ones fully formed with empty
  `sampleName`. Not the 4 that MPC 2 used.
- `triggerMode: 2` on every zone of both harvested instruments. It is a
  reasonable keygroup default, but **not** a drum-vs-keygroup discriminator:
  Producer Kit Essentials uses `2` on 54 filled *drum* pads against `0` on 586,
  so drum programs mix both. Choose it per pad from whether the sound should
  sustain, not from the program type.

### Velocity layers — confirmed, with real examples

This was the longest-standing open question and it is closed. Seven packs
showed only `0..127` everywhere; three more supplied real splits in both
containers.

**The convention: layer 0 is the loudest.** Ranges descend down the layer array
and tile `0..127` with no gaps and no overlaps.

`Acoustic-Kit-BFD Funk Kit 95.xtd` — a drum pad, eight layers, **a different
sample on each**:

| Layer | Velocity | Sample |
|---|---|---|
| 0 | `120–127` | `Acoustic-Kick-B2 DB Hit 02` |
| 1 | `109–119` | `…Hit 07` |
| 2 | `96–108` | `…Hit 06` |
| 3 | `85–95` | `…Hit 05` |
| 4 | `74–84` | `…Hit 04` |
| 5 | `60–73` | `…Hit 03` |
| 6 | `30–59` | `…Hit 01` |
| 7 | `0–29` | `…Hit 08` |

Two things a writer should copy. **Boundaries are vendor-chosen, not
auto-divided** — bands narrow toward the top (8, 11, 13, 11, 11, 14, 30, 30),
spending resolution where playing dynamics land. And **sample order is not layer
order**: Hit 02 is loudest, Hit 08 is quietest. The mapping is by sound, not by
filename.

They are chosen **per pad**, too, not once per program. `Acoustic-Kit-MPCe-DFH
Drum Tools.xtd` — a David Fingers Haynes acoustic library — has 16
velocity-layered pads carrying **12 distinct layouts** between them:

```
pad 0 (kick)   120-127 105-119  76-104  56-75  39-55  21-38   7-20   0-6
pad 1 (snare)  120-127 105-119  83-104  64-82  48-63  34-47  13-33   0-12
pad 2 (snare)  114-127 100-113  80-99   60-79  49-59  31-48  15-30   0-14
```

Each pad's breakpoints follow how *that instrument* responds, so there is no
program-level velocity curve to read or write. Layer count is fixed at 8;
everything else is per pad.

Keygroups use the same mechanism. `Bass-MPC3 AM Upright Bass Advanced.xty`
splits every zone eight ways at `111–127 / 95–110 / 79–94 / 64–78 / 48–63 /
32–47 / 16–31 / 0–15` — an even division, this time — across 23 keygroups.

So the **8 layer slots exist for 8-way velocity switching.** Round-robin (the
Ambient Box pattern, identical ranges differentiated by `SliceIncrement`) is the
same slots used a different way, not a separate mechanism.

### The velocity × round-robin grid — the PSK scheme, decoded

`Kit-PSK 009 Hip Hop Kit.xtd` (saved by MPC 3.9.0.31) combines both uses
of the slots into the full grid, and a 2026-08-28 probe of all 16 pads
pinned the geometry exactly:

- **One chain WAV per pad** — every layer's `sampleName` is the same
  `Chain-…` file; the takes are concatenated inside it.
- **8 layers, loudest first** (the standard convention: L0 = `122–127`
  down to L7 = `0–16`), tiling `0..127`.
- **The base `sliceIndex` grades with intensity**: the softest zone
  anchors at slice 0 and the anchor *rises* with velocity — L7→0, L6→1,
  L5→2, L4→3, L3→4, L2→5, L1→6, L0→8 on a typical pad. The chain is a
  dynamics-graded sequence of takes, soft→hard, and each zone taps in at
  its intensity point.
- **Per-zone robin**: every layer has `sliceIncrement 1` and
  `sliceCycleLength` 2–4 (mostly 3; the top zone often 4), so each hit
  steps to the next take near the zone's anchor.
- **One `sliceIncrementRngSeed` per pad**, shared by all 8 layers (a
  6-digit value, different per pad).
- **`sliceInfo` is NOT a per-slice window in Akai's writing**: on every
  pad the four loud layers carry `Start=0, End=<full chain>` while the
  four soft layers window roughly the last 12% of the chain. Whatever
  the firmware does with that, the per-slice boundaries must come from
  the slice map embedded in the chain WAV (see HH1.4 — not in the
  `.xtd`).

Adjacent zones' cycle windows overlap (base 4 cycling 3 reaches into
base 5's ground), which suggests the firmware treats
`[base, base+cycle)` as a window over shared takes. Our writers use
non-overlapping windows — a stricter instance of the same encoding —
and write each layer's `sliceInfo` as the zone's base-take window, so
firmware without the slice map degrades to honest 4-way velocity
switching. Our grids cap at 4 zones: the MPC 2 `.xpm` has 4 layer
slots, and 4 keeps every zone representable on both generations.

### Synth section

Each zone carries its own `instruments[i].synthSection` (the copy on
`program.keygroup` is dormant — all four `*EnvelopeGlobal` link toggles are
`false`).

- **Four envelopes**: `ampEnvelope`, `filterEnvelope`, `pitchEnvelope`,
  `auxEnvelope`, each with `{"value0": …}` wrappers and curve fields defaulting
  to `0.375`.
- **Two filters**, `filterData.value0` and `.value1`, combined by
  `filterSerialRouting` and `filterBlend`. `filterType` is a plain int.
- **Two LFOs**, `lfoData.value0` / `.value1`; `lfoFilterCutOff` is itself
  `{value0, value1}` so one LFO routes to both filters independently.

Emit both filter and both LFO slots even for a single simple filter — the
dual-slot shape is the schema, not an option.

**`keyTrackEnable` is an ordinary per-zone toggle.** An earlier revision of this
section called it a puzzle — "`false` on every layer, in files whose zones
clearly transpose, so it is *not* base pitch tracking." That was an artefact of
reading one file. Across both harvested instruments it splits cleanly and
unanimously per program:

**`keyTrackEnable` varies, but not with musical intent.** An earlier revision of
this section read two F9 files — a sub-bass at `false`, an 808 bass at `true` —
and concluded "it means what it says." A wider corpus kills that reading:

| Pack | `program.type` | `keyTrackEnable` | Layers |
|---|---|---|---|
| Percussion Tools | drum | **`true`** on all | 5,746 |
| Acoustic Drum Tools | drum | `false` on all | 2,560 |
| Producer Kit Essentials | drum | `false` on all | 640 |
| MPC Upright Bass | keygroup | `false` on all | 114 |
| Electric Bass 3 | keygroup | `false` on all | 2,286 |

**Both chromatically sampled basses — the content that must transpose — are
entirely `false`**, while a percussion pack, which mostly shouldn't, is entirely
`true`. That is the opposite of what a pitch-tracking flag would predict.

The value is **uniform within a program** — every filled layer of a given file
agrees, never mixed — but it does vary between programs, including inside one
pack. MPCe Expressive Kits is 29 files all-`false` and 1 all-`true`, and the
outlier is `Perc-Kit-MPCe-Mixed Perc 95`. (An earlier revision said "uniform per
pack"; that held for the first packs examined and this one breaks it.)

Percussion is the loose correlation — Percussion Tools is `true` throughout, and
so is MPCe's one percussion kit. But percussion is the content least in need of
pitch tracking, and both chromatic basses are `false`, so the correlation runs
opposite to the field's name.

So: `keyTrackEnable` is not always-`false` the way MPC 2's `<KeyTrack>` is, but
its meaning is **not established**, and it is not base pitch tracking. Copy
`false` — the value both real chromatic instruments use — and do not reason from
it. The MPC 2 element is a separate matter again; see
[`XPM_STRUCTURE.md`](XPM_STRUCTURE.md).

`layersv[j].pitch` remains a third pitch field beside `coarseTune`/`fineTune`
with an unresolved unit — `0.0` throughout. Copy the default.

## Why the MPC 2 writer survives

With MPC 2 hardware out of scope, one of the original three reasons is gone —
there is no One or 2.x Live II to support. The other two got stronger:

1. **MPC 3 loads MPC 2 content**, which is Akai's own documented route across
   the 2/3 split. `:xpm` is the **proven-shape** path — its structure comes
   from a real firmware save — while the native writer below is corpus-shaped
   and still awaiting its first hardware load.
2. **It is the fallback** if the MPC 3 track container turns out to be
   impractical to write from a phone — our 16-pad factory kit renders to a
   ~5.8 MB JSON body (194 KB gzipped), which is fine on a handset but worth
   knowing.

So `:xpm` stays the shipping path until the native writer passes acceptance.
What is deprioritised is *verifying `:xpm` against MPC 2 devices* — see
[`reference/README.md`](../reference/README.md#backlog-mpc-2) — not the writer
itself. Its output still has to load on the Live III, and that test runs now.

## The native writer

`Mpc3TrackWriter` in `:mpc3` writes the `.xtd` container this document
describes, and `Mpc3Exporter` in `:kit` drives it through the same
preflight/sanitize pipeline as every other export, emitting
`<Kit Name>.xtd` beside a flat `<Kit Name>_[TrackData]/` of WAVs.

Every load-bearing fact the corpus established is implemented and pinned by
`Mpc3TrackWriterTest`: 128 fully-formed slots with the empty-pad encoding,
8 `layersv` slots with velocity zones loudest-first, `sliceInfo.End` as the
length (a test asserts `sampleEnd` stays 0), dual `sampleName`/`sampleFile`
naming mirrored 1:1 in the deduplicated pool, the 0-based chromatic
`padNoteMap`, both spellings of polyphony at their exact paths, `0.5` pan
centre, per-pad `triggerMode`, and per-pad colours in `program.programPads`
— which, note, turns out to carry the same packed-int `pads.valueN` blob as
MPC 2, just as plain JSON: real kits (SFM 808/909) ship per-pad colours
there, so the "per-pad colour unproven" caveat above is answered for drum
programs.

The generalised guard is the same one the MPC 2 keygroup writer earned:
**no key path the writer emits may be absent from every real drum track**
in `reference/golden/mpc3-track/`. Defaults are copied from
`Kit-SFM 909 Crisp 123.xtd` (build `0.1.0.992`, the most common family)
rather than invented, and floats render with their decimal point the way the
firmware's serializer writes them.

`testkit/SnipSnap MPC3 Kit.xtd` is the acceptance artifact
(`./gradlew :synth:generateMpc3Kit`). Header stamp: `3.7.0.56` / `Linux`,
the standalone-firmware pairing observed on 59 real projects.

**Acceptance passed, 2026-08-23, on a Live III:** the kit loaded and
played, every pad on its assigned slot, pads lit in class colours
(`program.programPads` confirmed live), A03 choked A04
(`whichMuteGroup` confirmed), and the embedded "SnipSnap Groove" clip
appeared in the clip list and played. **MPC 3 native is a proven shipping
path**, no longer just the target.

The writer covers **both track types and the thing only this generation
can do**:

- **Keygroups** (`writeKeygroup` → `.xty`): zones in
  `program.drum.instruments` with per-layer root notes — never under
  `program.keygroup`, which carries only the global block (32 mod-link
  slots, unison/harmoniser, the four dormant `*EnvelopeGlobal` toggles) —
  the identity `padNoteMap`, `triggerMode 2` on every zone, sustained amp
  envelopes, and the vestigial `poliphony 1 / monophonic true` drum pair
  every real instrument track carries. Guarded against the `.xty` corpus
  the same way the drum path is guarded against `.xtd`.
  `testkit/SnipSnap MPC3 Keys.xty` is the acceptance artifact
  (`:synth:generateMpc3Keys`).
- **Embedded clips** (`Mpc3Clip`): a `sharedClipMap` pattern of real note
  events — 960 PPQ pulse offsets, normalised float velocities, the full
  modifier block — shaped like the clip-bearing SFM kits'. The factory
  export uses it to ship the demo groove *inside the kit*, which the MPC 2
  format has nowhere to put.

## Sources

**Primary.** Four real files in
[`reference/golden/mpc3-track/`](../reference/golden/mpc3-track/), harvested
from two commercial MPC 3 expansion installers — two Akai-authored drum tracks
and two F9-authored instrument tracks. Every claim above marked confirmed was
checked against them directly. See
[`reference/golden/README.md`](../reference/golden/README.md) for provenance and
the extraction recipe.

Their limits, stated plainly: four files, two packs, two authoring tools, zero
hardware saves. They cannot show a velocity split, a non-zero tune value, a
per-pad colour, or anything a Live III does differently.

**Secondary.**

- [kurtjcu/MPC-project-file-definitions](https://github.com/kurtjcu/MPC-project-file-definitions)
  — the `.xpj` 3.7+ knowledge base this document originally drew on. The
  harvest contradicted five of its claims as recorded here (`sampleName`
  extension, `sampleEnd` as length, the pan constant, schema version 28, the
  `padNoteMap` path), which is roughly what its own confidence ratings would
  predict — treat the remainder accordingly.

Its own caveat applies here too, and should be taken seriously: reverse-engineered
from 18 project files and the MPC 3.7 user guide, "likely to contain errors,
incorrect assumptions, and significant omissions." Fields are individually
confidence-rated in that repo; consult the ratings before depending on any one
of them. No LICENSE file, so it is read as documentation and not vendored.

**Akai publishes no format specification.** See
[`KIT_BEST_PRACTICES.md`](KIT_BEST_PRACTICES.md#documentation-status).
