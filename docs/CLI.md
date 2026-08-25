# The SnipSnap CLI

The whole product loop minus live capture, runnable anywhere a JVM runs:
point it at an audio file and out comes a kit — chopped at the hits,
classified, laid out on the pads people expect, exported in any format the
writers speak.

```
./gradlew :cli:snipsnapJar          # -> cli/build/libs/snipsnap.jar
java -jar snipsnap.jar chop break.wav --balance --export xtd
```

It exists for three reasons:

1. **Kits can be made from a desktop today**, before the Android app ships.
2. It is the first place the classifier meets **real audio** rather than
   synthetic test material — the calibration pass `CONCEPT.md` asks for.
   `snipsnap classify` prints the features next to every verdict for
   exactly this purpose.
3. When the app misbehaves later, this is the same pipeline with no phone
   in the way.

## Commands

### `chop <input.wav>` — file in, kit out

Read (any PCM/float WAV, any rate — resampled to 44.1 kHz), estimate the
tempo, chop, classify each slice, auto-place onto the conventional layout
(kick A01, snare A02, hats A03/A04, mute-grouped), and write a kit folder —
`kit.json` plus cleaned 24-bit WAVs, the app's own working format.

| Option | Meaning |
|---|---|
| `--name NAME` | kit name (default: the input's file name, sanitized) |
| `--out DIR` | output root (default `snipsnap-out`) |
| `--slices N` | chop at the N strongest hits. Default: **auto** — every onset is ranked by the peak level behind it and the count is cut at the knee in that curve, where the real hits end and the detector's table scraps begin (bounded 2–64). An 8-hit break asks for 8; give N only when you want to overrule the audio |
| `--grid N` | chop into N equal parts instead of following hits |
| `--place` / `--no-place` | force auto-placement on or off. Default: on when following hits, off on a grid — a grid's order is usually the point |
| `--balance` | per-pad levels via `Balance` so the kit sits right as a mix |
| `--groove` | embed the capture's own rhythm as a clip in the native exports (`xtd`/`xpj`) — timing as captured, velocities from the hits' own dynamics; needs a confident tempo |
| `--swing PCT` | with `--groove`: the tight pattern swings instead, the way the hardware does it — quantize to 16ths, then push every even ("and") 16th late by `(pct−50)/50` of a 16th. 50 straight, 66 triplet feel, panel range 50–75 |
| `--fit-tempo BPM` | repitch LOOP pads from the detected tempo to BPM, SP-style (`TempoFit`): resample by the ratio, pitch rides along — the revered lo-fi move, and the semitone cost is printed. One-shots untouched; stems and `kit.json` restamp to the new tempo; refused past double/half speed, and an honest error when no source tempo was heard |
| `--ghosts` | darker soft velocity zones under every one-shot pad — quiet hits sound soft, not just quiet |
| `--key SPEC` | retune tonal pads into a key via `InKey`/`Tuner`: `Am`, `C`, `F#m`, `Eb major`, `Dminpent` — or **`auto`**: a pitch-class histogram over the pitched slices names the key itself (`KeyGuess`), erroring honestly when the material has none. Even without `--key`, a confident guess is remembered in `kit.json` — metadata only, nothing retunes uninvited |
| `--export LIST` | comma-separated formats, see below |
| `--preview` | render the kit playing its own beat (`KitPreview`) into the `expansion`/`xpn` exports as `[Previews]/<Kit>.xpm.wav` — the real packs' pairing convention, so the MPC browser auditions the kit before loading it. Uses the kit's saved groove; with none, an honest default: kick/snare/hat backbone when classes are known, a pad walk when they aren't |
| `--art STYLE` / `--no-art` | expansion/`xpn` exports carry a procedural browser tile (`KitArt`) **by default** — the prototyping loop's verdict made `waveform` the standard look, with `rings` the runner-up (`grid` and `slices` also available). `--no-art` skips it |
| `--overwrite` | replace same-named output |

More than 16 hits doesn't drop slices: placement rounds up to whole banks,
the core classes claim their bank-A pads, and the rest overflow upward.

The pad table marks any classification under 0.5 confidence with a `?` —
the same threshold behind the app's dashed **NOT SURE** treatment.

### `chop-all <folder>` — the crate-digging verb

Every `.wav` in the folder through the whole chop pipeline, folder first
then any chop options (applied to every file). Kits are named after
their files, so `--name` is refused. A file that fails is **named,
never fatal**; one summary table shows what landed
(`file -> kit (pads) ~tempo`). Exit 1 only when nothing succeeded.
Doubles as the calibration corpus's mass-run tool.

### `classify <wav...>` — the calibration tool

One line per file: class, confidence, duration, spectral centroid, decay,
and the low/mid/high band split. When the classifier is wrong about a real
capture, this is where the wrongness becomes a number you can move a
threshold by.

### `export <kit-dir>` — the fan-out over an existing kit folder

Takes any folder with a `kit.json` (one this CLI chopped, or one synced off
a phone) and writes the chosen formats. `--preview` works here too.

### `import <file>` — the receive half, both directions

Dispatches by content, never extension. An `.xpn` archive unpacks into a
kit folder — ours or a vendor's (either instrument-numbering base, samples
found by bare name anywhere in the archive). A native MPC 3 drum track
(`.xtd` with its `_[TrackData]/` beside it) imports too — **kits the MPC
itself saved become editable kit folders**, levels, tunes, mute groups,
velocity layers and pad colours intact. A whole **`.xpj` project**
imports too: every drum track inside becomes its own kit folder, non-drum
tracks skipped and named. Either way the landed folders are editable and
re-exportable like any other kit.

A **`.mid` file** is a groove looking for a kit: `import beat.mid --into
<kit-dir>` reads it (format 0 or 1, any division, running status handled),
rescales to 960 PPQ, and makes it that kit's patterns — the standard four
variations included — so the next native export carries the DAW beat.

### `remix <kit-dir>` — evil twins

Bank B becomes seeded FX re-treatments of bank A — reversed, crushed,
slapback, washed, punched — one twin per pad, colour and choke group kept
so the hats still cut each other in bank B. Reroll with `--seed N`.

### `feel <kit-dir> --from <donor>` — steal the feel, not the notes

Groove transfer, the MPC's own legendary feature. The donor — another
kit's groove, or any `.mid` — gives up its pocket: how late or early
each 16th-position lands, how hard it hits relative to the rest. The
kit's patterns are rewritten with it: notes snap to the grid, then take
the donor's timing offsets and accent shape (the standard four
variations re-derive from the felt pattern). A position the donor never
plays stays straight — no data, no opinion.

### `merge <a> <b>` — bank B, earned not invented

A **new** kit folder: A's bank A stays put, B's bank A lands on pads
17–32 with everything carried (colours, mute groups, tuning, velocity
layers, recipes, provenance) and every sample copied byte-identical
under a re-prefixed stem (`A03_Snare_01` arrives as `B03_Snare_01`).
A brings its identity — key, tempo, `groove.json`. Both sources stay
untouched. An occupied bank B refuses unless `--replace` says to swap
it out; `--name`/`--out` place the result (default: `<A> AB` beside A).
The complement of `remix`, which invents its bank B.

### `keys <note.wav> [more.wav …]` — notes in, keyboard out

MPC keygroups pitch the sample themselves, so one pitched capture plus its
detected root is a full-range chromatic instrument — and several captures
become a real **multisample**: each note a zone at its detected root,
zones tiled at the midpoints. Lands the dual-generation layout (`.xty`
beside `_[TrackData]/` with the `.xpm` twin). Unpitched material is
refused by file name; two files detecting the same root refuse by both
names — you pick, it doesn't. `--loop` cuts **sustain loops**: a
whole-period loop found in each note's sustain (crossfaded when the raw
seam isn't clean), trimmed to the loop-to-end idiom both formats share —
held pads sing forever. A note with no honest sustain plays unlooped.

### `project <kit-dir>... ` — whole session, one `.xpj`

N kit folders (plus an optional `--keys a.wav,b.wav` multisampled
instrument) become one project: kits on tracks in their colours, every
kit's grooves as **switchable sequences** (sequence k plays each kit's
k-th pattern), mixer wired, samples pooled per-kit-prefixed in
`_[ProjectData]/`. `--mixdown` also renders the whole session — every
kit playing its groove, summed and peak-limited — as `<Name>.wav`
beside the `.xpj`: the beat as a file you can send anywhere.

### `backup <kits-root>` / `restore <backup.zip>` — everything on one file

Every kit under a root packed as its own `.xpn` inside a single archive;
restore feeds them back through the importer. A kit preflight refuses to
pack is skipped **and named with the reason** — backups never pretend.

### `art <kit-dir>` — procedural cover tiles

Cover art drawn from the kit itself — its waveforms, its class colours,
its name in the built-in 5×7 pixel face — on the scheme's dark LCD.
Deterministic: same kit, same parameters, same bytes.

Four styles: `waveform` (all the pads end to end, each in its class
colour), `grid` (the 4×4 bank-A grid, lit by class), `slices` (one bar
per pad), `rings` (seeded arcs — `--seed N` reshuffles). `--scheme`
picks any of the six TapeOS schemes (`chrome`…`clear`), `--size PX`
sets the square edge (default 600), `--out DIR` says where the PNGs
land. **No `--style` renders every style side by side** — the
prototyping loop is one command per look. The winning direction becomes
the expansion/`.xpn` export default (Z6.3).

### `diff <a> <b>` — the corpus guard as a bench tool

A structured key-path diff of two MPC files, **either generation** —
detection is by content, never extension (gzip magic = MPC 3 ACVS, XML
declaration = MPC 2; bare JSON like a `kit.json` also works). Reports
paths only in A, paths only in B, and (with `--values`) every concrete
path where the values disagree, `A -> B`.

The comparison is schema-aware the way the writer tests are: array
indices collapse to `[*]` and pad-table `valueN` keys to `value*`, so a
16-pad kit against a 128-pad kit isn't hundreds of lines of noise; and
it is sentinel-tolerant — two INT64_MAX-ish numbers are the same
"forever", floats match to a relative 1e-6.

Exit 0 when the files agree, 1 when they differ — scriptable. This is
the whole "why won't this file load" workflow:

```
$ java -jar snipsnap.jar diff ours.xtd firmware-save.xtd --values
```

…and the deltas are the answer.

## Export formats

All exports land under `<out>/card/`; copy its contents onto the MPC's SD
card or USB drive as-is.

| Format | What lands | Notes |
|---|---|---|
| `folder` | `<Kit>/<Kit>.xpm` + WAVs | MPC 2-era program folder — loads on every generation |
| `expansion` | `Expansions/<Kit>/` | browsable in the Expansion tab, tile + manifest |
| `xpn` | `<Kit>.xpn` | one-file archive for sharing |
| `xtd` | `<Kit>.xtd` + `<Kit>_[TrackData]/` | MPC 3 native drum track — the hardware-verified primary format |
| `xpj` | `<Kit>.xpj` + `<Kit>_[ProjectData]/` | a whole MPC 3 project with the kit on track 1 |
| `mid` | `<Pattern>.mid` per stored groove | Standard MIDI Files — every DAW, and the MPC's own browser. 960 PPQ, drum channel, tempo meta from the kit. No groove? The honest default beat exports instead |

## A real run

The factory kit's rendered demo groove, chopped back into a kit:

```
$ java -jar snipsnap.jar chop "SnipSnap Factory Kit.wav" --name Regroove --balance --export xtd
read SnipSnap Factory Kit.wav: 11.43s, 1 ch @ 44100 Hz
tempo: ~92bpm (confidence 0.97)
chopped at 16 detected hits
balanced pad levels

pad  class       conf   source     length
A01  KICK         0.92    1.138s    0.815s
A02  SNARE        0.88    0.649s    0.326s
A03  HAT_CLOSED   0.53    3.584s    0.163s
...
A15  LOOP         0.90    9.778s    1.657s

kit folder: snipsnap-out/Regroove (16 pads)

exports (copy the contents of snipsnap-out/card onto the card):
  xtd        snipsnap-out/card/Regroove.xtd  (+ Regroove_[TrackData]/, MPC 3 native)
```

The groove really is 92 BPM; the kick really does land on A01. Every
number in that table is the classifier being auditable in public.
