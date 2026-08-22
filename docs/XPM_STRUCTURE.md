# XPM program structure

What the `:xpm` module writes, where the structure came from, and which parts
are still guesses. Covers both drum programs (`type="Drum"`) and keygroup
programs (`type="Keygroup"`) — the two share the outer shape and diverge in
layer count and a few per-instrument fields, noted where it matters.

## Provenance

The structure was recovered from the template in
[BlackCursive/mpckitcreator](https://github.com/BlackCursive/mpckitcreator), a
tool for building MPC programs from a folder of WAVs.

That template's header:

```xml
<Version>
  <File_Version>2.1</File_Version>
  <Application>MPC-V</Application>
  <Application_Version>2.9.1.2</Application_Version>
  <Platform>Linux</Platform>
</Version>
```

We did not vendor that file. It's an unlicensed repo, so it was read as
documentation of Akai's format and the writer was built fresh from what it shows.

**Correction:** the original version of this doc took `MPC-V` on `Linux` as
proof the template was "saved by a standalone MPC," not a desktop export. The
real corpus below shows that reasoning doesn't hold — a vendor-authored,
standalone-targeted pack (Ambient Box, 2026) stamps the identical `MPC-V` /
`Linux` pair, and a different vendor pack (Masada, 2017) stamps `MPC-V` on
`Windows`. A third pack (Platinum Percussion, Pad Pimps) stamps `MPC-V` on
`OSX`. `Application` is always `MPC-V` regardless of who or what wrote the file;
`Platform` names the desktop OS the *authoring tool* ran on — all three of
`Windows`, `OSX` and `Linux` occur in vendor-authored content. Neither field is
evidence of hardware provenance.

### The real corpus

Two commercial packs, harvested into `reference/golden/`, both **vendor-authored
— not hardware saves**. Same evidence-class caveat as everywhere else in this
repo: strong evidence for what Akai's format accepts and what real tools write,
not proof of what a Live III itself would write.

- **Masada Cycle kit vol.4** (May 2017). `Expansion.xml` says
  `buildVersion="1.9.6.1"`, shipped as a `.xpn`. Harvested:
  [`reference/golden/drum/hiphop-Drum-kit-Mck4 01.xpm`](../reference/golden/drum/hiphop-Drum-kit-Mck4%2001.xpm)
  (`type="Drum"`), plus the pack's `Expansion.xml` and a full file listing.
  Header: `File_Version 1.7`, `Application_Version 1.9.6.1`, `Platform
  Windows`.
- **The Ambient Box** (soundspremium.com, files dated March–April 2026).
  `Application_Version 3.7.1.34`, a standalone SD-card folder, not an
  installer. Harvested: three `type="Keygroup"` programs in
  [`reference/golden/keygroup/`](../reference/golden/keygroup/) plus its
  `Expansion.xml`. Header: `File_Version 2.1`, `Application_Version 3.7.1.34`,
  `Platform Linux`.

Nine years apart, both still XML `.xpm` — see
[`reference/golden/README.md`](../reference/golden/README.md) for why that
matters ("the single strongest piece of evidence that `:xpm` is a real
shipping path"). Full provenance and extraction recipe there.

**This is still second-hand relative to hardware.** Nothing here has been
loaded on a Live III yet. See [Unverified](#unverified) below.

## Shape

```xml
<?xml version="1.0" encoding="UTF-8"?>
<MPCVObject>
  <Version>…</Version>
  <Program type="Drum">
    <ProgramName>SnipSnapRef16</ProgramName>
    <ProgramPads>…escaped JSON…</ProgramPads>
    …program-level params: Volume, Pan, Program_Polyphony…
    <Instruments>
      <Instrument number="0">
        …per-pad params: Volume, Pan, TuneCoarse, MuteGroup, OneShot, envelopes…
        <Layers>
          <Layer number="1">
            <SampleName>SS_Kick_01</SampleName>
            <SampleFile></SampleFile>
            <SliceStart>0</SliceStart>
            <SliceEnd>18522</SliceEnd>
          </Layer>
          …layers 2-4, empty…
        </Layers>
      </Instrument>
      …
    </Instruments>
    <PadNoteMap>
      <PadNote number="1"><Note>37</Note></PadNote>
      …128 entries…
    </PadNoteMap>
  </Program>
</MPCVObject>
```

### Samples are referenced by name, not path

`<SampleName>` is the WAV filename **without extension**; `<SampleFile>` is
empty. The MPC resolves the name against the folder the `.xpm` sits in. Keep the
program and its WAVs adjacent and it just works — which is why the app models a
kit as a directory.

**Confirmed, not just inferred, now.** Checked directly across all four
harvested real programs (one drum, three keygroup): every `<SampleFile>` is
empty — 624 elements, zero non-empty, in the locally-harvested files. A wider
scan of the full 20-program Ambient Box pack (not harvested; too large) is
reported at 1,640 `<SampleFile>` elements, still zero non-empty, and zero
`<File>` elements anywhere in that pack. No `<SampleName>` in the harvested
files contains a path separator either. So the writer's optional
`samplePathPrefix` mode — emitting `<File>prefix/name.wav</File>`, attributed
to "Rex Rule #5" — has no real-world support in this corpus; the prefix-less
default is the one to trust.

### SliceEnd is the sample's frame count

Not bytes, not milliseconds — frames. `WavInfo` exists to get this right; a
wrong value truncates or over-runs the pad.

### Four layers, always — in drum programs

Every drum instrument carries `<Layer number="1">` through `4`. We only fill
layer 1 (velocity layers and round robins are a later feature), but the empty
three are still emitted.

**Keygroup programs use 8, not 4.** Confirmed against all three harvested
Ambient Box programs (`type="Keygroup"`): every instrument carries
`<Layer number="1">` through `8`, unanimously, filled or not. `KeygroupWriter`
now writes 8 (it assumed 4 until the corpus corrected it). See
[What `KeygroupWriter` gets wrong](#what-keygroupwriter-gets-wrong) below.

### The ProgramPads blob

A JSON document, XML-escaped, embedded as element text. **Pad colour is now
decoded** — this was an open question ("colours live in here, presumably")
and it is closed.

```json
{
    "ProgramPads": {
        "Universal":              { "value0": false },
        "Type":                   { "value0": 5 },
        "universalPad":           32512,
        "pads":                   { "value0": 0, …, "value127": 0 },
        "UnusedPads":             { "value0": 1 },
        "PadsFollowTrackColour":  { "value0": false }
    }
}
```

(Shown with real values from the Masada 2017 file below, not a fresh-program
zero blob — see [Provenance](#provenance) for where it came from.)

- **`Universal.value0`** — `false`/`true`: switch between "every pad follows
  `universalPad`" and "each pad uses its own `pads.valueN` entry."
- **`universalPad`** — the fallback/default colour, packed int (below).
- **`pads.value0`…`value127`** — per-pad colour, packed int, `0` = unset.
  These are **not** always zero — see decoded values below.
- **`UnusedPads`** — present in both eras, meaning not yet decoded.
- **`PadsFollowTrackColour`** — present **only** in the 2026 file. Confirmed
  absent from the 2017 file (grepped directly, not inferred). Not a renamed
  field — the 2026 JSON has both `UnusedPads` and this as separate keys, so
  it's an added key, not a swap.
- **`Type.value0`** — `5` in the Masada drum program, `4` in the Ambient Box
  keygroup programs. Present in both eras; meaning not decoded. Do not assume
  it is a program-type discriminator without more evidence — it does not match
  drum-vs-keygroup 1:1 across only two data points.

**Colour encoding: 24-bit packed `0xRRGGBB`, `0` = unset.** Same encoding in
both the 2017 and 2026 files. Decoded from
[`reference/golden/drum/hiphop-Drum-kit-Mck4 01.xpm`](../reference/golden/drum/hiphop-Drum-kit-Mck4%2001.xpm)
(Masada, 2017), which has 21 of 128 pad entries non-zero:

| Raw int | Hex | Colour |
|---|---|---|
| `32512` (`universalPad`) | `0x007F00` | green |
| `8323072` | `0x7F0000` | dark red |
| `127` | `0x00007F` | dark blue |
| `1134440` | `0x114F68` | teal |
| `8336128` | `0x7F3300` | brown/orange |

The harvested Ambient Box keygroup file (`Kalimba-TAB Magic.xpm`) shows the
other half of the `Universal` story: `Universal.value0=true`, all 128
`pads.valueN` are `0`, and `universalPad=6238976` (`0x5F3300`) carries the
colour instead — exactly what "every pad follows the universal colour" should
look like. A larger unharvested 2026 drum program (`Drums-TAB Boom Bap`, not
in this repo — 4 MB) reportedly has `Universal.value0=false`,
`universalPad=15526948`, and 32 of 128 pads non-zero, consistent with the same
encoding but not independently re-checked here.

Since this is a real, decoded format, the blob is no longer a constant:
`XpmWriter` takes an optional packed `0xRRGGBB` per pad (`Pad.color`), flips
`Universal` off when any pad carries one, and the kit exporters feed it the
class colours the app already assigns — so a SnipSnap kit lands on the MPC
wearing the same colour language as the app's pad grid. A program with no
colours still emits the fresh-program constant the golden file pins.

### Element name: `<ProgramPads>` vs `<ProgramPads-v2.10>` — resolved

This used to be an open question with two candidates and a guess that they
were alternates. The corpus answers it: **it's generational, not either/or.**

| Pack | Year | Element name |
|---|---|---|
| Masada | 2017 | `<ProgramPads>` |
| Ambient Box | 2026 | `<ProgramPads-v2.10>` |

Unanimous within each pack — every harvested file from a given era uses that
era's name, no mixing. Our writer emits the bare `<ProgramPads>` form, which
matches the 2017-era file and **not** the file stamped with current-generation
firmware (`Application_Version 3.7.1.34`). If a generated kit loads pads but
rejects colour, or is rejected outright on current firmware, this element name
— along with the `PadsFollowTrackColour` key gap noted above — is the first
thing to check.

### PadNoteMap

128 entries, pad number 1-based, mapping pad to MIDI note. Bank A is the classic
MPC layout:

| Pad | A01 | A02 | A03 | A04 | A05 | A06 | A07 | A08 |
|---|---|---|---|---|---|---|---|---|
| Note | 37 | 36 | 42 | 82 | 40 | 38 | 46 | 44 |

| Pad | A09 | A10 | A11 | A12 | A13 | A14 | A15 | A16 |
|---|---|---|---|---|---|---|---|---|
| Note | 48 | 47 | 45 | 43 | 49 | 55 | 51 | 53 |

Kick on A02, closed hat on A03, snare on A06 — the layout MPC users have muscle
memory for. The full table is in `PadNoteMap.DEFAULT`. It is a lookup table, not
a formula; don't "tidy" it.

### A real scale-lock pattern (keygroup zone layout)

Relevant to this repo's in-key sampling features. `Kalimba-TAB Magic.xpm`
(Ambient Box) is scale-locked to a 7-note diatonic kalimba and its zone layout
shows how a vendor built that in keygroup zones, not in `PadNoteMap`:

Seven keygroups, `<LowNote>`/`<HighNote>` zones tiled contiguously with no
gaps, each zone's own sample rooted a step or two inside it — read directly
from the file:

| Instrument | Zone (MIDI) | Root | Zone width |
|---|---|---|---|
| 1 | 0–60 | 61 | everything below the root, plus a semitone above |
| 2 | 61–62 | 63 | root sits one semitone *above* this zone |
| 3 | 63–64 | 65 | root sits one semitone above this zone |
| 4 | 65 | 66 | single semitone, root one above |
| 5 | 66–67 | 68 | root one semitone above |
| 6 | 68–69 | 70 | root one semitone above |
| 7 | 70–127 | 72 | root at the bottom, everything above |

Every zone plays a sample rooted *outside or at the edge of* its own range —
each root is the nearest diatonic step above the zone it serves, except the
bottom zone (root inside range) and top zone (root at the very bottom of a
wide range). A key that isn't one of the seven diatonic notes plays its
nearest in-scale neighbour, pitch-shifted by the small interval between the
key played and that sample's root — not re-triggered at a different pitch
class, and not silent. That's the scale-lock trick: overlapping key ranges and
per-zone roots do the mapping, not `PadNoteMap`.

Five other slots exist (`Instrument 8`–`12`, matching the file's 12 total
slots against `KeygroupNumKeygroups=7`) with `LowNote=0`/`HighNote=127` and no
sample — see [Do gaps need a placeholder instrument?](#2-do-gaps-need-a-placeholder-instrument)
below for what that means for trailing empty slots.

## Unverified

Ranked by how much damage each would do. One item (element name) moved out of
this list entirely — see "Element name" under
[The ProgramPads blob](#the-programpads-blob) above — because the corpus
resolved it outright.

### 1. Instrument numbering base — 0 or 1?

We emit **0-based**. The `mpckitcreator` script emits 1-based (`count+1`) and
reportedly loads fine.

**Still open, but the corpus adds real evidence pointing at 1-based.** Every
`<Instrument number="…">` in all four harvested real programs — one drum
program, three keygroup programs, both packs — starts at `1` and counts
upward, no exceptions. That does **not** close the question: these are
vendor-authored files (see [Provenance](#provenance)), which show what a
vendor's tool writes and what the MPC's *parser* accepts, not what it requires
of a file our own writer produces. A parser can easily accept both 0-based and
1-based input. Still, "every real file we've seen is 1-based" is a stronger
prior than the doc had before, and worth weighting when this finally gets a
hardware check.

`XpmWriter(instrumentBaseIndex = 1)` flips it. This is the first thing to check
on hardware, and the cheapest to spot — a kit shifted by exactly one pad.

### 2. Do gaps need a placeholder instrument?

We emit an instrument for every pad slot up to the highest occupied one, so an
empty pad in the middle still gets a (sample-less) instrument and later pads stay
aligned. `mpckitcreator` emits only the occupied ones in sequence, which would
shift everything after a gap.

**Drum programs: reportedly all 128 slots, unconditionally.** Consistent with
"ours is the safer reading" — not independently re-verified in this pass
beyond confirming the harvested Masada drum program has exactly 128
`<Instrument>` elements (it does; that doesn't distinguish "all slots always"
from "this kit happened to fill all 128").

**Keygroup programs leave trailing empty slots, and this is now
repo-checkable.** `Kalimba-TAB Magic.xpm` writes **12** `<Instrument>` slots
for `<KeygroupNumKeygroups>7</KeygroupNumKeygroups>` — 7 real zones (`LowNote`/
`HighNote` tiled 0–127, see [the scale-lock example](#a-real-scale-lock-pattern-keygroup-zone-layout))
followed by 5 slots with `LowNote=0`/`HighNote=127` and an empty `SampleName`.
Why 12 and not, say, 8 or 16, is unexplained — no visible pattern (not a power
of two, not `numKeygroups` rounded to a boundary that's obvious from one
example). Needs a second keygroup file with a different `numKeygroups` to see
if the padding is a fixed constant, a rounding rule, or leftover from a GUI
session.

### 3. Version header honesty

We claim `MPC-V 2.9.1.2 / Linux`. The corpus adds real data but also a
correction to how this doc reasoned about it — see the
[Provenance](#provenance) rewrite above: `Application`/`Platform` are not
evidence of hardware origin, vendor tools stamp them too.

What the corpus actually shows: `File_Version` is a real schema version that
**does** move — `1.7` in the 2017 Masada file (`Application_Version 1.9.6.1`,
`Platform Windows`) versus `2.1` in all three 2026 Ambient Box files
(`Application_Version 3.7.1.34`, `Platform Linux`). But `2.1` is stable across
a wide firmware range: it's what the original `mpckitcreator` reference
claimed at `2.9.1.2`, and it's what the 2026 corpus claims at `3.7.1.34` — nine
years and multiple firmware generations apart, same `File_Version`. So our
writer's `File_Version 2.1` looks right, and `Application_Version 2.9.1.2` is
cosmetic — a specific firmware string inherited from the one reference file,
not verified as load-bearing. Whether a Live III on 3.7 mints its own file
starting from `2.1` too is still untested; MPC 3 reads MPC 2 content, so it
should be fine either way, and the header is a one-line change if not.

### `<KeyTrack>` is not the pitch control

`KeygroupWriter` emitted `<KeyTrack>True</KeyTrack>` on every layer (now
fixed — it writes `False`). **No real program does.** Across 65 XPM files
from five vendors spanning 2017–2026:

```
filled layers: 13,083     <KeyTrack>False</KeyTrack>: 13,083     True: 0
```

An earlier revision left this open on the grounds that the corpus was all
ambient or scale-locked content, where `False` might be a deliberate choice.
That objection is now answered: `Bass-MPC3 AM Upright Bass.xpm` is a
chromatically sampled acoustic bass — zones three semitones wide, roots at 37,
40, 43, 46, 49, 52, 55 — and it ships `KeyTrack=False` on all 114 filled
layers. A bass that plays in tune across its range, with the flag off.

So transposition from `<RootNote>` and the zone happens **regardless of this
element**, and whatever `<KeyTrack>` gates, it is not base pitch tracking.
Write `False`; it is the only value that occurs in nature.

Do not carry this reasoning to MPC 3. Its `keyTrackEnable` is a genuine
per-zone toggle that really does vary — `true` on 82 Percussion Tools tracks —
see [`MPC3_FORMAT.md`](MPC3_FORMAT.md). Same name, different field.

### Velocity layers

Also resolved, and the reason keygroups carry 8 layer slots.
`Bass-MPC3 AM Upright Bass.xpm` splits every zone eight ways:

```
L1 111–127   L2 95–110   L3 79–94   L4 64–78
L5 48–63     L6 32–47    L7 16–31   L8 0–15
```

**Layer 1 is the loudest**, ranges descend, and they tile `0–127` with no gaps
and no overlaps. Note the zone shape while you are here: instrument 1 covers
`35..37` with root `37`, instrument 2 covers `38..40` with root `40` — the root
sits at the **top** of its zone, the same layout the Kalimba uses.

The alternative use of the same slots is round-robin — identical `0–127` ranges
on every layer, differentiated by `SliceIncrement` / `SliceIncrementRngSeed` /
`SliceCycleLength`. One mechanism, two idioms; see
[MPC3_FORMAT.md](MPC3_FORMAT.md) for the drum-side examples, which use
vendor-chosen uneven bands rather than this even division.

## What `KeygroupWriter` gets wrong

Checked directly against
[`xpm/src/main/kotlin/com/snipsnap/xpm/KeygroupWriter.kt`](../xpm/src/main/kotlin/com/snipsnap/xpm/KeygroupWriter.kt)
line-by-line against the three harvested Ambient Box keygroup programs. Split
by confidence.

> **Status: every definite defect below is fixed in the writer.** The table
> is kept as the evidence record; the "what it emits" column describes the
> writer as audited, not as it stands. The fixes also brought the identity
> `PadNoteMap`/all-zero `PadGroupMap` (present in every harvested keygroup
> program, and keygroups are chromatic on pads), the float
> `KeygroupPitchBendRange`, 8 layer slots, and per-zone real root notes —
> `KeygroupWriterTest` pins all of it. Loading `testkit/SnipSnap Keys` on
> hardware is the remaining acceptance check.

### Definite — confirmed against the corpus

| `KeygroupWriter.kt` | What it emits | What real files do | Fix |
|---|---|---|---|
| line ~98, instrument level | `<Active>True</Active>` | No `<Active>` at instrument level in any harvested file — grepped the full pre-`<Layers>` block of every instrument, absent every time. It exists only **per layer** (`KeygroupWriter.kt` already gets this right at line ~123). | Delete the instrument-level line. |
| lines ~101–102, instrument level | `<Tune>0</Tune>`, `<Transpose>0</Transpose>` | Neither element exists anywhere in the corpus (0 occurrences, either level). Real files carry `<TuneCoarse>`/`<TuneFine>` at **both** instrument and layer level. | Delete `<Tune>`/`<Transpose>`; the writer already emits `TuneCoarse`/`TuneFine` at layer level, so add them at instrument level too. |
| lines ~116–118, instrument level | `<RootNote>0</RootNote>`, `<KeyTrack>True</KeyTrack>`, `<OneShot>False</OneShot>` | None of the three exists at instrument level in any harvested file. `RootNote` and `KeyTrack` exist only per layer (already emitted there separately at lines ~133–134). `OneShot` doesn't exist at all — keygroup instruments carry `<TriggerMode>` (an int) instead. | Delete all three instrument-level lines. |
| line ~133, layer level | `<RootNote>0</RootNote>` on every layer, filled or not | Filled Kalimba layers carry real MIDI roots — `61, 63, 65, 66, 68, 70, 72` — never `0`. Only the 68 confirmed-empty layers (empty `<SampleName>`) show `RootNote=0`. `KeygroupWriter` treats `0` as "auto-detect," but real filled layers never use it. | Write the zone's actual root note on filled layers; reserve `0` for empty ones. (That `0` on a filled layer transposes playback down to MIDI note 0 is an inference from how `RootNote` behaves elsewhere in the format, not directly observed — but it's consistent with every real "auto-detect"-style default we've seen resolve to an explicit value once a sample is assigned.) |
| lines ~76–79, program tail | `<KeygroupNumKeygroups>` / `<KeygroupPitchBendRange>` emitted right after `<ProgramName>`, before `<Instruments>` | All three harvested files put them **after** `</Instruments>`, `</PadNoteMap>`, and `</PadGroupMap>` — confirmed by direct line-number check against `Kalimba-TAB Magic.xpm` (`ProgramName` at line 11, `<Instruments>` at 242, `</PadGroupMap>` at 7439, `KeygroupNumKeygroups` at 7441). | Move the two elements to the end of the program body, after `PadGroupMap`. |
| instrument level | duplicate `<Resonance2>` element (appears twice, verbatim, back to back) | Present in the harvested file exactly as described — an apparent authoring bug in the vendor's own writer. Not something to reproduce; noted because it means **a reader must tolerate duplicate/malformed elements**, which is a constraint on any XPM parser this repo writes, not just the writer. | No writer fix needed; if `:xpm` ever grows a reader, don't fail hard on an unexpected repeat element. |

**Sixth definite defect: `<KeyTrack>True</KeyTrack>` at layer level.** Real
content is `False` 13,083 times out of 13,083 — see
[`<KeyTrack>` is not the pitch control](#keytrack-is-not-the-pitch-control).
Write `False`.

Also confirmed while checking the above: no layer-level `<Loop>` (bool) or
`<Mute>` element exists anywhere in the corpus — looping is `<SliceLoop>` (int
`0`/`1`) at layer level, and `<Mute>` only exists at **instrument** level
(ordinary mixer mute, `False` on every checked instrument — unrelated to
looping).

### Needs verification — plausible but not directly checked, or checked on too thin a sample

Both items that used to sit here have been resolved by a wider corpus. They are
written up above — see [`<KeyTrack>` is not the pitch control](#keytrack-is-not-the-pitch-control)
and [Velocity layers](#velocity-layers). Nothing currently sits in this
category.

## Verifying on hardware

The `:xpm` module's golden test only proves the writer is self-consistent. It
cannot prove the MPC accepts the file. That takes a device:

1. `gradle :xpm:test` — proves no accidental drift
2. Generate a 16-pad kit with real WAVs beside it
3. Load it on the **MPC One** (the acceptance target — if it loads there it
   loads everywhere)
4. Confirm: all 16 pads fire, each on the pad it was assigned to, at the right
   pitch, with the hat mute group choking

Step 3 is where the instrument-numbering question gets settled.

A reference program exported off your own hardware — see
[`../reference/README.md`](../reference/README.md) — turns every item in
[Unverified](#unverified) into a diff instead of a debate. It is no longer
blocking, but it is still the fastest way to close them out.
