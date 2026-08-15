# XPM drum program structure

What the `:xpm` module writes, where the structure came from, and which parts
are still guesses.

## Provenance

The structure was recovered from the template in
[BlackCursive/mpckitcreator](https://github.com/BlackCursive/mpckitcreator), a
tool for building MPC programs from a folder of WAVs.

That template is a program **saved by a standalone MPC**, not a desktop export —
its header says so:

```xml
<Version>
  <File_Version>2.1</File_Version>
  <Application>MPC-V</Application>
  <Application_Version>2.9.1.2</Application_Version>
  <Platform>Linux</Platform>
</Version>
```

`MPC-V` on `Linux` is the standalone OS. Firmware 2.9.1.2 is MPC 2-era, which is
[exactly the compatibility target](MPC_EXPORT.md) for MPC One, Live II and
Live III.

We did not vendor that file. It's an unlicensed repo, so it was read as
documentation of Akai's format and the writer was built fresh from what it shows.

**This is still second-hand.** It has not been loaded on hardware yet. See
[Unverified](#unverified) below.

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

### SliceEnd is the sample's frame count

Not bytes, not milliseconds — frames. `WavInfo` exists to get this right; a
wrong value truncates or over-runs the pad.

### Four layers, always

Every instrument carries `<Layer number="1">` through `4`. We only fill layer 1
(velocity layers and round robins are a later feature), but the empty three are
still emitted.

### The ProgramPads blob

A JSON document, XML-escaped, embedded as element text:

```json
{
    "ProgramPads": {
        "Universal":    { "value0": true },
        "Type":         { "value0": 1 },
        "universalPad": 32512,
        "pads":         { "value0": 0, …, "value127": 0 },
        "UnusedPads":   { "value0": 1 }
    }
}
```

All 128 pad entries are `0` in a fresh program. Pad **colours** live in here,
which is why this has to stop being a constant when per-pad colour ships.

Note the element is `<ProgramPads>` in this firmware. Forum posts also mention
`<ProgramPads-v2.10>`; that is presumably a different firmware line. If a
generated kit is rejected outright, this element name is a prime suspect.

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

## Unverified

Ranked by how much damage each would do.

### 1. Instrument numbering base — 0 or 1?

We emit **0-based**. The `mpckitcreator` script emits 1-based (`count+1`) and
reportedly loads fine. Both cannot be right, and if the wrong one is chosen every
sample lands one pad off.

`XpmWriter(instrumentBaseIndex = 1)` flips it. This is the first thing to check
on hardware, and the cheapest to spot — a kit shifted by exactly one pad.

### 2. Do gaps need a placeholder instrument?

We emit an instrument for every pad slot up to the highest occupied one, so an
empty pad in the middle still gets a (sample-less) instrument and later pads stay
aligned. `mpckitcreator` emits only the occupied ones in sequence, which would
shift everything after a gap.

Ours is the safer reading, but if the MPC dislikes sample-less instruments the
alternative is padding gaps with a silent WAV.

### 3. Version header honesty

We claim `MPC-V 2.9.1.2 / Linux` because that is what the reference program
claimed and the format is version-sensitive. Whether a Live III on 3.6 minds
being handed a 2.9-stamped program is untested — MPC 3 reads MPC 2 content, so
it should be fine, and if not, the header is a one-line change.

### 4. Element name `<ProgramPads>` vs `<ProgramPads-v2.10>`

See above.

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
