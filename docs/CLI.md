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
| `--slices N` | chop at the N strongest hits (default 16) |
| `--grid N` | chop into N equal parts instead of following hits |
| `--place` / `--no-place` | force auto-placement on or off. Default: on when following hits, off on a grid — a grid's order is usually the point |
| `--balance` | per-pad levels via `Balance` so the kit sits right as a mix |
| `--groove` | embed the capture's own rhythm as a clip in the native exports (`xtd`/`xpj`) — timing as captured, velocities from the hits' own dynamics; needs a confident tempo |
| `--key SPEC` | retune tonal pads into a key via `InKey`/`Tuner`: `Am`, `C`, `F#m`, `Eb major`, `Dminpent` |
| `--export LIST` | comma-separated formats, see below |
| `--overwrite` | replace same-named output |

More than 16 hits doesn't drop slices: placement rounds up to whole banks,
the core classes claim their bank-A pads, and the rest overflow upward.

The pad table marks any classification under 0.5 confidence with a `?` —
the same threshold behind the app's dashed **NOT SURE** treatment.

### `classify <wav...>` — the calibration tool

One line per file: class, confidence, duration, spectral centroid, decay,
and the low/mid/high band split. When the classifier is wrong about a real
capture, this is where the wrongness becomes a number you can move a
threshold by.

### `export <kit-dir>` — the fan-out over an existing kit folder

Takes any folder with a `kit.json` (one this CLI chopped, or one synced off
a phone) and writes the chosen formats.

### `import <file>` — the receive half, both directions

Dispatches by content, never extension. An `.xpn` archive unpacks into a
kit folder — ours or a vendor's (either instrument-numbering base, samples
found by bare name anywhere in the archive). A native MPC 3 drum track
(`.xtd` with its `_[TrackData]/` beside it) imports too — **kits the MPC
itself saved become editable kit folders**, levels, tunes, mute groups,
velocity layers and pad colours intact. Either way the landed folder is
editable and re-exportable like any other kit.

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
