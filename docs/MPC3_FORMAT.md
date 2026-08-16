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
| Companion folder | `.xpm`, `.xal`, `.sxq`, `.mcn` | WAV files only |
| Sequences | standard MIDI files (`.sxq`, 960 PPQ) | embedded JSON events |
| Firmware range | 2.0 – ~2.12.x | 3.0 – 3.7.0.56 confirmed |

Both generations use the `.xpj` extension for projects, so **detection must be
content-based** — check magic bytes, never the extension.

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
container is generic across MPC 3 object types, not specific to projects. See
[The one real unknown](#the-one-real-unknown).

## Drum program schema

Path: `tracks[n].program.drum`. Schema version 28, 128 pads, 8 layers per pad.

```json
{
  "program": {
    "version": 4,
    "name": "HipHop Kit",
    "type": 0,
    "programPads": {},
    "mixable": {},
    "drum": {
      "version": 2,
      "drumVersion": 12,
      "padNoteMap": { "noteForPad": { "value0": 36, "value1": 37 } },
      "instruments": [ /* always 128 slots */ ],
      "poliphony": 6,
      "coarseTune": 0,
      "fineTune": 0,
      "padGroup": { /* value0..value127 */ }
    }
  }
}
```

### What maps across from our MPC 2 model

| SnipSnap `Pad` | MPC 2 XPM | MPC 3 JSON |
|---|---|---|
| sample | `<SampleName>` (no extension) | `layersv[i].sampleName` (**with** extension) |
| length | `<SliceEnd>` | `layersv[i].sampleEnd` |
| mute group | `<MuteGroup>` | `whichMuteGroup` (0 = none, 1-32) |
| one-shot | `<OneShot>` bool | `triggerMode` int — 0 One Shot, 1 Note Off, 2 Note On |
| tune | `<TuneCoarse>` / `<TuneFine>` | `coarseTune` / `fineTune` |
| pad→note | `<PadNoteMap>`, 1-based `<PadNote number>` | `padNoteMap.noteForPad`, **0-based** `value0..value127` |

The model in `DrumProgram.kt` survives the transition nearly intact. That was
worth getting right.

### Two gotchas already visible

1. **`sampleName` carries the extension in MPC 3** and did not in MPC 2. Easy to
   get backwards, and it fails as a missing sample rather than an error.
2. **The pad note map is 0-indexed here** (`value0` = pad 1) where MPC 2 used
   1-based `<PadNote number="1">`. Same table, different base — exactly the class
   of off-by-one that put `instrumentBaseIndex` in the MPC 2 writer.

### Sample pool

MPC 3 adds a track-level `samples[]` pool alongside the per-layer references:

```json
{
  "version": 1,
  "name": "SS_Kick_01",          // no extension
  "path": "SS_Kick_01.wav",      // with extension
  "loadImpl": 0,
  "metadata": { "tempo": 300.0, "rootNote": 60, "tune": 0.0, "key": "A# Natural Minor" }
}
```

Observed conventions worth copying: `rootNote` 60 (C4) for one-shots, and a
`tempo` of `30.0` or `300.0` as the sentinel for "not tempo-aware" — which is
what every drum hit we export is. `metadata.key` is where BPM/key detection
output would eventually land.

### Serialization patterns

The firmware's C++ serializer leaves fingerprints a writer must reproduce:

| Pattern | Example |
|---|---|
| indexed-dict — arrays as `valueN` keyed objects | `"noteForPad": {"value0": 36, ...}` |
| enum wrapper | `"EnumCerealisationWrapper(behaviour)": "Off"` |
| ADSR wrapper | `"Attack": {"value0": 0.0}` |
| pan centre is not 0.5 | `0.5039370059967041` |
| colour as packed int | `16711680` = `(R<<16)\|(G<<8)\|B` |
| **`poliphony`** | misspelled in the schema; must be preserved exactly |

Pad colour being a plain packed integer here is a genuine improvement over
MPC 2, where it was buried in an escaped-JSON blob. The colour feature gets
much cheaper on this target.

## The one real unknown

Everything above documents the **project** container (`.xpj`). Nobody has
publicly documented what MPC 3 writes when you save a *standalone program* to
disk. Three candidates:

1. **ACVS + gzip + JSON**, with an object type like `SerialisableProgramData` on
   header line 3 — most likely, given the container is clearly generic.
2. **MPC 2-style XML `.xpm`**, kept for interoperability — plausible, since
   exporting `.xpm` is Akai's documented path for moving material between MPC 2
   and MPC 3.
3. Something else.

**This is blocking, and only a Live III can answer it.** Nothing about a native
MPC 3 writer can be built until we know which container a saved program uses.

### The check (two minutes on the Live III)

1. Build a drum program with a few pads filled.
2. Save it to SD or USB.
3. Look at the first two bytes of the resulting file:
   - `1F 8B` → gzip → candidate 1. Decompress, read the 5-line header, and the
     object type on line 3 tells us everything.
   - `<?xml` → candidate 2, and the existing `:xpm` writer is already most of
     the way there.

Drop the file in `reference/golden/liveiii-36/` either way.

## Why the MPC 2 writer survives

Not wasted work, for three reasons:

1. **MPC 3 loads MPC 2 content.** Exporting `.xpm` is Akai's own documented
   route for moving programs across the 2/3 split.
2. **The One and a 2.x Live II need it.** A native MPC 3 program will not load
   on them.
3. **It is the fallback** if the MPC 3 program container turns out to be
   impractical to write from a phone.

So `:xpm` becomes the compatibility path and a native MPC 3 writer becomes the
primary — rather than the MPC 2 writer being replaced.

## Sources

- [kurtjcu/MPC-project-file-definitions](https://github.com/kurtjcu/MPC-project-file-definitions)
  — the `.xpj` 3.7+ knowledge base this document draws on.

Its own caveat applies here too, and should be taken seriously: reverse-engineered
from 18 project files and the MPC 3.7 user guide, "likely to contain errors,
incorrect assumptions, and significant omissions." Fields are individually
confidence-rated in that repo; consult the ratings before depending on any one
of them. No LICENSE file, so it is read as documentation and not vendored.

**Akai publishes no format specification.** See
[`KIT_BEST_PRACTICES.md`](KIT_BEST_PRACTICES.md#documentation-status).
