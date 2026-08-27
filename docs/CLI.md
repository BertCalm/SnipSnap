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
| `--groove` | embed the capture's own rhythm as a clip in the native exports (`xtd`/`xpj`) — timing as captured, velocities from the hits' own dynamics; needs a confident tempo. Saves the standard four patterns **plus the fill**: the last bar of every four densifies into the turn — beat 3 rolls 16ths, beat 4 rolls 32nds on the kit's own snare (clap or perc standing in), hat eighths underneath, velocities ramping into the downbeat, seeded jitter keeping it human. The fill rides the `.xpj`'s sequences; the `.xtd` keeps its four-slot budget with the original four. A kit with nothing to roll on honestly skips it |
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

### `dig <file-or-folder>` — breaks found inside full songs

The crate-digging ritual from the top: `chop` assumes you hand it a
break, but the ritual starts with *songs*. The Dig scores each second
of a track on three honest measures — onset density (a break hits
steadily and often), spectral flatness (drums are broadband, notes are
peaky), and low-band pulse (kicks make the sub pump; sustained bass
just sits there) — merges scoring windows into candidate sections, and
names where the breaks live with timestamps and scores (`--top N`).
`--chop` sends each song's best section straight through the chop
pipeline; every pad's provenance then says which song and at what
timestamp it was dug from. A song of pads says "no break heard" rather
than inventing one; unreadable files are named and skipped, never
fatal. Deterministic: the same song always yields the same dig.

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

### `similar <target> <library-root>` — find me another one like this

Nearest-neighbour over the classifier's own features: the measurements
it already hears (centroid, rolloff, flatness, band ratios, duration,
decay) become a normalized vector, and distance is "does it sound
alike" — level left out on purpose, because a quiet snare is still a
snare. The target is a `.wav`, or a kit folder plus `--pad A02`; the
library is any folder of kit folders, walked recursively. Matches come
back ranked and named well enough to go grab them — kit, pad, display
name, class, distance. The target is never its own best match, and the
order is total and deterministic.

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

### `resample <kit-dir>` — the ritual

The most MPC gesture there is: bounce what you have and chop it again.
The kit renders its own groove — treatments and eras already live in
its files, wear applies at render time (`--no-wear` skips it, `--wear
W` forces a level) — and the bounce re-enters the chop pipeline as
source material. Out comes a **new** kit (`<Name> Gen 2` by default,
`--name` overrides), every pad stamped with `resampledFrom` and a
generation counter that climbs on each pass while the name stays
rooted (`Origin Gen 3`, not `Origin Gen 2 Gen 3`). The source kit is
never touched. Generation loss is the point — stack it with the Time
Machine and the tape gets a history you can hear.

### `remix <kit-dir>` — evil twins

Bank B becomes seeded FX re-treatments of bank A — reversed, crushed,
slapback, washed, punched — one twin per pad, colour and choke group kept
so the hats still cut each other in bank B. Reroll with `--seed N`.

### `answer <kit-dir>` — chop a break, get the B-side

The kit already knows its key (KeyGuess), its groove, its feel; this
derives the complement: an S5 bassline (the Velvet BASS engine tuned to
the key's root) playing a **counter-pattern in the groove's gaps** — the
pocket inverted. Three rules: never on a strong hit (a 16th carrying
≥60% of the groove's peak velocity is the kit's statement), in the key
(scale degrees off the root in the bass register, weighted hard toward
root and fifth), and following the feel (the donor's timing/accent
template, its lean generalised by 16th parity into the gaps it never
played). Deterministic per `--seed`, rerollable. The result persists
beside the kit (`answer.json` + the rendered bass note) and `project`
lands it automatically: a keys track playing its clip, in the same
`.xpj` as the break — the magic-moment demo in two commands.

`--band` grows the answer into sidemen, all off the same seed: Tonewheel
**stab triads** (root, the scale's own third, the fifth) on gaps the
bass leaves open too — favouring the and-of-the-beat, at most one per
few steps — and a Velvet CHIP **shaker tick** on the off-16ths the
groove leaves completely free. Both follow the feel and both refuse
honestly when the groove leaves them no room (a wall of hats gets no
shaker). Asking for the band never rewrites the bass: same seed, same
answer, sidemen added. `project` lands each as its own keys track.

### `feel <kit-dir> --from <donor>` — steal the feel, not the notes

Groove transfer, the MPC's own legendary feature. The donor — another
kit's groove, or any `.mid` — gives up its pocket: how late or early
each 16th-position lands, how hard it hits relative to the rest. The
kit's patterns are rewritten with it: notes snap to the grid, then take
the donor's timing offsets and accent shape (the standard four
variations re-derive from the felt pattern). A position the donor never
plays stays straight — no data, no opinion.

### `era <kit-dir> <machine>` — the Time Machine

The whole kit rendered through the specific math of a specific machine —
not a "lo-fi" knob. Four eras: `sp1200` (12 bits truncated at 26.04 kHz,
decimated with no anti-alias filter and brought back zero-order-hold,
because that folding *is* the sound), `mpc60` (µ-law-style companding
around a 12-bit quantizer, gentle top-end roll), `tape` (soft saturation,
slow deterministic wow, dulled highs, a whisper of seeded hiss), and
`phone` (the 300–3400 Hz band, 8-bit µ-law, an 8 kHz rate trip).
`--amount 0.6` interpolates from transparent toward full character;
`--pads A01,B03` ages a subset. Velocity layers age with their pads.
Originals go to the bin and every pad records its recipe, so
`--undo` brings the present back byte-identical.

### `shape <kit-dir> <pad>` — pad shape as metadata

Attack, decay, filter cutoff and resonance (`--attack/--decay/
--cutoff/--res`, all 0..1) land in the exported programs' **own
fields** — `VolumeAttack`/`VolumeDecay`/`Cutoff`/`Resonance` in the
MPC 2 `.xpm`, the amp envelope and first filter slot in the MPC 3
`.xtd` — and the *hardware* renders them. The audio on disk never
changes; a tighten is one number, and undo is `--reset` (null means
"the format's own default", which is also why unshaped kits keep
exporting byte-identical). The shape survives the round trip through
both native containers, and the preview approximates it so you can
hear a tightened pad before the card. Bench row: decay 0.3 audibly
shortens a pad on the Live III in both generations.

### `wear <kit-dir>` — the kit as a living tape

The product pretends to be a tape deck; this makes the metaphor real.
Opt a kit in with `--on` and its plays and saves accrue **mileage** in a
wear ledger; its *renders* — previews, mixdowns, exports — age by
`w = 1 − exp(−mileage/K)`. That curve is the feature: patina physics,
fast at first, asymptotic at well-worn, never ruined. Hard caps at full
wear: flutter ≤ ±6 cents, hiss ≤ −48 dBFS, the HF shelf never below
8 kHz, dropouts rare and **never on a strong hit** (the envelope
protects them structurally). The audio on disk is never rewritten —
wear is a render-time recipe over pristine WAVs — so `--reset` is a
genuinely new tape. `--plays N` logs mileage by hand (the deck the app
drives); `--off` pauses aging with the mileage remembered; `--k N`
retunes the curve. On `export`, `--no-wear` renders the pristine kit
and `--wear W` forces a level — even past the earned ceiling, because
chosen destruction is a treatment while earned patina is capped.

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

### `sidea <kit-dir>... --title NAME` — the beat tape

The output stops being kits and becomes a finished artifact. Each kit
plays its patterns for a few bars (`--bars`, default 8), rotating
through its stored grooves exactly where the hardware's sequence
switcher would flip them, chained with the two transitions every beat
tape knows: the **tape stop** (a repitch ramp to zero — the reel
dragging to silence, in place, so the next track starts exactly where
this one would have ended) and the **pull-up** (the spinback: the last
moments rewound fast, pitch rising, falling away before the next beat
drops). One folder comes out: the continuous WAV, `tracklist.txt` with
sample-accurate timestamps, a cover wearing the tape's title, and the
whole session as an `.xpj`. Deterministic end to end.

### `pack <kit-dir>... --title NAME` — N kits, one expansion

The commercial-pack shape: a catalog of programs under one tile.
Programs under `Programs/`, each kit's WAVs in `Samples/<Kit>/` (bare-name
references keep colliding stems apart, the way every harvested pack does
it), a preview per kit in `[Previews]/`, one cover wearing the pack's
title (`--art`/`--no-art` as with exports), `Expansion.xml` + on-card
manifest. `--xpn` also zips the lot into one shareable file — manifest
excluded, byte-stable — and `import` of that archive brings back **every**
kit inside. A kit preflight refuses is skipped and named, backup-style.

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

### `doctor <kit-dir>` — the mix doctor

Preflight's musical sibling: it checks the **sound**, not the format.
Every finding is a measurement with pad names and numbers: two
sustained sub-heavy pads fighting for the low end, open and closed
hats outside one mute group, a pile of bright pads where brightness
isn't the pad's job (hats, snares, claps and percussion never count —
top end *is* their trade), a pad ≥4× the kit's median loudness, DC
offset the speaker pays for. `--fix` applies only the safe subset:
the sub carve hands the low end to its rightful owner (a real
high-pass on the less-committed pad — a shelf can't un-sub a sub),
the level trim lands a screamer just above the median, hats get one
mute group, DC gets removed — audio edits bin-backed with recipes,
the rest metadata-only. Taste stays advice. Exit 0 healthy, 1 while
findings remain, so it scripts like a check.

### `jcard <kit-dir>` — every kit gets its cassette insert

Everything a J-card needs is already tracked, so the kit renders its
own: one fold-ready PNG in cassette proportions — **front** (the
waveform in class colours, the name, key/tempo), **spine** (name, key,
tempo, and the wear ledger's mileage on one strip — a new tape says
so), **back** (the pad list with class chips, names and sources in up
to two columns of sixteen, and the groove folded to a 16-step notation
row, brightness riding velocity). Amber hairlines mark the folds.
KitArt-family: same LCD surface, same pixel type, deterministic to the
byte. `--width PX` scales it; expansion exports drop `J-Card.png`
beside the artwork, and `pack` lands every kit's insert under
`[J-Cards]/` — inside the `.xpn` twin too.

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
