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
| `--clean` | the Capture Doctor on the whole capture **before the first slice** (see `clean` below): measured hum notched, clicks and dropouts repaired, a hissy floor gently gated — each move gated by its own detector, so a clean file passes through untouched and says so. `--denoise` upgrades the floor leg to the spectral deep clean (hiss pulled from under the drums). `dig --chop` forwards both |
| `--groove` | embed the capture's own rhythm as a clip in the native exports (`xtd`/`xpj`) — timing as captured, velocities from the hits' own dynamics; needs a confident tempo. Saves the standard four patterns **plus the fill**: the last bar of every four densifies into the turn — beat 3 rolls 16ths, beat 4 rolls 32nds on the kit's own snare (clap or perc standing in), hat eighths underneath, velocities ramping into the downbeat, seeded jitter keeping it human. The fill rides the `.xpj`'s sequences; the `.xtd` keeps its four-slot budget with the original four. A sixth pattern adds the **ghost-note grammar**: the "e" before and the "a" after beats 2 and 4 whisper on the kit's own snare at ghost velocity (≤0.32, so `--ghosts` soft zones actually voice them), never piling onto a 16th that already plays. A kit with nothing to roll or whisper on honestly skips them |
| `--swing PCT` | with `--groove`: the tight pattern swings instead, the way the hardware does it — quantize to 16ths, then push every even ("and") 16th late by `(pct−50)/50` of a 16th. 50 straight, 66 triplet feel, panel range 50–75 |
| `--fit-tempo BPM` | repitch LOOP pads from the detected tempo to BPM, SP-style (`TempoFit`): resample by the ratio, pitch rides along — the revered lo-fi move, and the semitone cost is printed. One-shots untouched; stems and `kit.json` restamp to the new tempo; refused past double/half speed, and an honest error when no source tempo was heard |
| `--ghosts` | darker soft velocity zones under every one-shot pad — quiet hits sound soft, not just quiet |
| `--break-pad` | one **extra** pad carrying the whole break as a chain whose slice boundaries are the chop's own cuts (cycle = slice count) — tap through the break in order on one pad, the workflow MPC users build by hand in Sample Edit. The pad's WAV starts at the first hit so slice one is frame 0; class LOOP, provenance stamped like every slice; the preview steps through it and the MPC 3 metadata cycles it (hardware audibility rides HH1.4's slice map, like every chain). Also rides `dig --chop` |
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
timestamp it was dug from (`--break-pad` rides along, so the dug break
can land tap-through-able on one pad too; `--clean` rides along the
same way, scrubbing each dug capture before its first slice).

**`--unearth`** is the Split pointed at the crate: the dig scores —
and `--chop` chops — the song's *percussive layer* instead of the raw
mix, so a break that never plays alone is found and extracted from
under the bass and keys (the test's strongest case: a song whose
chord never stops reads "no break heard" to the plain dig, and
unearths cleanly). Pads carry `unearthed` provenance, and `--air`
cuts its textures from the *music* layer instead — every dig yields
two crates, now genuinely disentangled.

`--air` is the inverse dig — every dig yields two crates. The same
window scores selected the other way: non-silent, *low* break score
(≤ 0.30, safely under the break floor) and tonal (flatness ≤ 0.12 —
notes and pads, not drums or wash), merged into sections and trimmed
against every break candidate so the air can never overlap the break.
The best stretch is cut on a grid of 4 long parts (a texture wants
sustained material in source order, not hit-chopped shards) into a
companion kit named "`<Song> Air`", every pad classed LOOP by
declaration with the song/timestamp provenance stamped. A drums-only
file honestly says "no air heard". A song of pads says "no break heard" rather
than inventing one; unreadable files are named and skipped, never
fatal. Deterministic: the same song always yields the same dig.

### `split <song.wav>` — the Split at song scale

Median-filter mask separation (Fitzgerald's HPSS): in a spectrogram,
notes draw horizontal lines and hits draw vertical ones; a median
across time keeps the horizontals, a median across frequency keeps
the verticals, and the two become soft masks that sum to one — so
"`<Song> Drums.wav`" + "`<Song> Music.wav`" **sum back to the song**,
separation that can prove it lost nothing. The summary names the
verdict with measured shares ("drums 78% / music 22% — mostly
drums"); when a masked half overshoots full scale both halves are
scaled by one stated factor rather than letting the WAV boundary clip
silently. One honest physics note: a boomy kick's sub is a *held
tone* and rightly leans harmonic — the vertical promise is about
attacks and noise. Chop the drums, `keys` the music, `dig --air` the
music's calmest stretch.

### `dissect <wav> | <kit-dir> <pad>` — the anatomy lesson

Fuzzy STN (sines / transients / noise, after Fierro & Välimäki, in
its single-resolution telling): the ratio of the time-median to the
median pair says what each bin *is* — strongly horizontal is sines,
strongly vertical is transients, and the in-between is noise, with
raised-cosine ramps between named thresholds. One sound lands as a
"`<Name> Dissected`" kit: **Sines** (the body, TONAL), **Transient**
(the attack, PERC), **Air** (the noise, LOOP) — the three parts sum
back to the whole, each pad carries `dissectedFrom` provenance and a
recipe, and each layer then mutates, eras, robins or sculpts on its
own. The layers of a hit, finally on separate pads.

### `beat <song.wav>` — the whole ritual as one verb

One song in, a release folder out. Six steps, each the real verb run
in order, its output printed as it happens: **the dig** (break + air,
chopped with groove, ghosts and the break pad, provenance stamped),
**the doctor** (`--fix`, before any pad becomes a chain — a chained
pad refuses rewrites, that's its boundary promise), **the robins**
(subtle takes on the plain kick and snare), **the answer** (with the
band, when the material named its key), **the arrangement** (`arrange
--mixdown`: the song as sequences and as one WAV), and **the
inserts** (liner notes + J-card). Every skip is *named* — no break
aborts honestly, no key sits the Answer out, nothing to roll on gets
no turn — and `--seed N` steers every seeded step, so the same song
and seed build the same release.

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

### `crate <root>` — the library as a collection

Your whole output treated as one crate: an index of every kit pad's
feature vector, class and classifier confidence, cached in
`.crate-index.json` keyed by file + mtime + size — a second pass over
an unchanged library measures **nothing** (the summary says how many
came from the index). On top of it: a class census, `--dupes` (Similar
distance ≈ 0 across different files — the same kick saved twice),
`--pick KICK --top N` (the best of a class across everything,
classifier confidence ranking), and `--build NAME` — the strongest pad
of every class assembled into a fresh kit through the same model the
app uses, auto-place colours and mute groups riding along. A torn
index rebuilds silently; it is a cache, not a record.

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

**`sfz`** is the escape hatch: `<Kit> SFZ/<Kit>.sfz` + `Samples/`,
loadable in nearly any DAW or free sampler. Pads land on keys 36 up
(the MPC 3's own pad map), velocity layers become `lovel`/`hivel`,
mute groups become `group`/`off_by` chokes, shape and humanize ride
their own opcodes (the preview's documented approximations; pan
randomization has no SFZ opcode and is honestly skipped). And because
SFZ has **native round robin** (`seq_length`/`seq_position` with
`offset`/`end` windows into the one chain WAV), chains and grids
export *fully* — takes actually cycle, zones actually switch — richer
than the MPC 2's own fallback.

**`ds`** is the same door into DecentSampler (free, everywhere):
`<Kit> DecentSampler/<Kit>.dspreset` + `Samples/`. One group per pad —
or per grid zone, since `seqLength` lives on the group — with
`seqMode="round_robin"` cycling chain takes through `start`/`end`
windows, `loVel`/`hiVel` velocity ranges, tag chokes for mute groups,
level/pan/tuning and the amp shape carried; one-shot pads ring out via
a sample-length release (the DS drum idiom). Filter shape and humanize
have no per-sample DS home and are honestly skipped.

### `import <file>` — the receive half, both directions

Dispatches by content, never extension. An `.xpn` archive unpacks into a
kit folder — ours or a vendor's (either instrument-numbering base, samples
found by bare name anywhere in the archive). An `.sfz` instrument comes
home too: regions land on pads by key (36 = A01), `lovel`/`hivel` become
velocity layers, our own writer's chains and grids reassemble into
chains and grids (boundaries from the regions' `offset`/`end` windows),
opcode inheritance from `<global>`/`<master>`/`<group>` is honoured, and
unknown opcodes are ignored — that IS the sfz rule. Anything that can't
become a pad — a multi-key region, a missing or non-WAV sample, a chain
that doesn't reassemble — is named and skipped, never fatal. A native MPC 3 drum track
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

### `learn <beat.wav> --into <kit-dir>` — the Ear: bite a beat

Everything else chops audio into *sounds*; the Ear hears a recording
as a *performance*. The same onset detection the chopper trusts finds
the hits, the same classifier chop runs on its slices classifies each
inter-onset window, and the transcription plays back on **your** kit:
hits land on the pads whose classes match (with the preview's own
stand-ins — snare↔clap, the hats for each other, perc on a hat),
timing kept raw — that's the feel — and velocities from the hits' own
dynamics. The result becomes the kit's groove with the standard
variations, ready for every native export.

Honest throughout: no confident tempo refuses (the ear needs a grid
to write onto); hits under the confidence bar are counted and left
out — *marked, never invented*; classes the kit has no pad for are
named, not guessed around. Deterministic: same audio, same hearing.
Scope is beats, not mixes — a song with bass and vocals over the
drums is not what this ear is for, and the classifier's confidence
gate is what says so.

`--pocket x.pocket` bottles the recording's **feel** instead of (or
beside) its notes: the confident hits through `GrooveFeel.extract`
into a `.pocket` file — a real drummer's timing and accents, straight
off the record, no pads needed. Apply it anywhere `feel --from`
takes a pocket; `pack` ships pockets already. A rendered swing
survives the round trip: the off-8ths come back leaning by the same
push that was played.

### `euclid <kit-dir>` — Bjorklund patterns as a groove

The T-1 half of Torso's DNA: `--kick 3,8 --snare 2,8,2 --hat 7,16` —
each spec is k,n with an optional rotation, and k hits are spread as
evenly as the integers allow across n steps of one bar (Toussaint's
telling of Bjorklund: E(3,8) is the tresillo `x..x..x.`, E(5,8) the
cinquillo `x.xx.xx.` — textbook forms, onset first). No spec at all
plays the house pattern: tresillo kick, backbeat snare, driving
16th-grid hats. Hits land on the kit's own pads through the Ear's
stand-in map (a clap covers a missing snare); a kit with nothing to
play a class names the skip. Accents are structural, not random — the
downbeat leads, quarter-anchored onsets sit just under it, the rest
speak — and the clip goes through the standard groove door, so
tight/half/sparse variations ride along and the native exports carry
it. Deterministic: the same spec always writes the same groove.

### `feel <kit-dir> --from <donor>` — steal the feel, not the notes

Groove transfer, the MPC's own legendary feature. The donor — another
kit's groove, or any `.mid` — gives up its pocket: how late or early
each 16th-position lands, how hard it hits relative to the rest. The
kit's patterns are rewritten with it: notes snap to the grid, then take
the donor's timing offsets and accent shape (the standard four
variations re-derive from the felt pattern). A position the donor never
plays stays straight — no data, no opinion.

Feels are tradeable artifacts too: `--save x.pocket` bottles this
kit's own pocket — sixteen timing offsets, sixteen accents, a name —
as a small `.pocket` file, and `--from x.pocket` applies one as-is
(silent positions stay `null` in the file exactly as in the template,
so a saved-then-applied pocket moves a kit the same way its donor
would have). `pack` ships each groove-carrying kit's pocket under
`[Pockets]/` automatically — the feel travels with the kit.

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

### `sculpt <wav> | <kit-dir> <pad>` — hits become matter

The Sculptor: the S-4-inspired granular engine pointed at anything —
a WAV or one pad of a kit — growing textures from it. Modes, not
knobs:

- **cloud** (default) — dense grains hovering just past the attack
  (position 0.35, ±5% wander): a hit becomes weather.
- **scrub** — the read position crawls the whole source over the
  render: the break as a slow landscape, opening where the source
  opens and closing where it closes.
- **swarm** — a cloud detuned across ±7 semitones at higher density:
  the thickener.

Four seeded takes (seed, seed+1, …) land as a "`<Name> Sculpt`" kit,
every pad LOOP by declaration with `sculptedFrom` provenance and a
regenerable recipe — **the same seed always grows the same texture**,
byte for byte, because every random draw comes from the seed in
schedule order. Output level is honest against the source's own peak:
a cloud of a quiet sound is a quiet cloud. `--seconds N` (default 8),
`--seed N`, `--out`, `--name`, `--overwrite`. The result is a real
kit: it previews, exports, eras, and resamples like any other — sculpt
a texture, age it through tape, put the Answer under it.

### `stretch <wav>` — the slow-motion wash

Paulstretch, the honest way: big Hann windows (93 ms) analyzed along
the source at 1/factor of the synthesis pace, every frame's **phases
replaced with seeded random ones** while every magnitude is kept.
Phase carries *when*; magnitude carries *what* — throw the when away
and a 200 ms hit becomes half a minute of evolving wash that still
sounds like itself, with no grain artifacts and no chipmunk. `--by N`
(2–100, default 8) sets the factor; `--clear` swaps the random phases
for PGHI-reconstructed ones — a sine stays a narrow line instead of
becoming the wash, the surgical stretch beside the atmospheric one;
`--freeze [--at sec]` is the same
move with the analysis position nailed down — one instant of the
source held forever (`--seconds N`, default 8), defaulting to the
source's own loudest moment when `--at` is not given. Left and right
draw different phases from the same seed, so the twin is a
decorrelated stereo field; the peak comes home to the source's own.
Lands beside the source as "`<stem> Stretched.wav`" /
"`<stem> Frozen.wav`" (`--out`, `--overwrite`). A kit pad is a WAV in
a folder — point stretch straight at it, then `sculpt` or `chop` the
result: the Sculptor's verbs compose.

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

`--humanize H` (0..1) rides the MPC 3 layer's real per-hit
randomization fields (`pitchRandom`/`VolumeRandom`/`PanRandom`, all
zero on every commercial layer) — the format's "no two hits alike"
dice. (GG4's first verdict — "no round robin anywhere" — was wrong:
round robin exists as chain-based **Slice Motion**, `sliceIncrement`
stepping through a chain WAV per hit; see `chop --break-pad` and
`robin`. Humanize is the *other* mechanism: randomization, not
rotation.)
Humanize scales them conservatively (pitch ×0.05, volume ×0.2, pan
×0.1) and the hardware rolls the dice on every hit. MPC 2 exports
have no such fields and honestly ignore it. Bench row: humanize 0.5
on the Live III — audible variation at a sane amount.

### `mutate <kit-dir> <pad> --with <src>[,<src>…]` — one hit from many parents

**`--morph [--amount 0..1]`** is the fourth move, the Séance's:
both parents' magnitude spectrograms, transient-aligned, interpolated
bin by bin at the given amount, with PGHI re-inventing the phases — a
sound *between* the parents, not a crossfade of them (one onset, both
voices in one hit; length and level interpolate too). Amount 0 is the
pad, 1 is the parent, and the amount rides the recipe like every
mutate parameter.

Sound design by **recombination** — where `treat` and `era` transform a
single sound, mutate breeds a new one. Parents are pad refs (`A03`),
another kit's pads (`path/to/kit:B02`), or bare `.wav` files; mono
parents widen, foreign rates resample, and every layer is
**transient-aligned** (onset-trimmed) so nothing flams. Three moves:

- default **stack** — parents summed at the loudest parent's own level,
  with an honest polarity check: a layer whose first ~46ms measurably
  cancels against the pad is flipped, and the output says so;
- `--splice [--at ms]` — the classic mash: the pad's attack up to the
  split (default 40ms), a 10ms equal-power handover, then the parent's
  body *from the same time position*, so its decay continues as if both
  had been hit together;
- `--split [--hz N]` — the pad below the crossover (default 200 Hz),
  the parent above: sub from this kick, crack from that snare.

Bin-backed through the same door as every treatment (`--undo` restores
byte-identical); the recipe — mode, parents, split, flips — rides the
pad so the sound stays regenerable; and provenance stamps the parents,
so `lineage` shows a hit with two of them. Deterministic: same
parents, same recipe, same bytes.

`--roulette [--seed N] [--root DIR]` lets the **crate deal the
parent**: Similar ranks every pad under the root (the cached crate
index does the measuring), dupes are excluded — a copy isn't a
partner — and a seeded spin lands on one of the 8 nearest; `--wild`
spins across the whole crate instead. Never the pad itself, named in
the output, the spin recorded in the recipe beside the parent it
dealt. Deterministic per (crate, seed); combines with `--splice` and
`--split` like any parent.

### `retime <wav> --to BPM` — the other tempo move

Where `--fit-tempo` repitches SP-style (the revered lo-fi trade),
`retime` changes the tempo and **not** the pitch: PGHI time-scale
modification — the magnitude spectrogram resampled along time, phases
reconstructed from its own gradients. The source tempo is heard from
the material (`--from BPM` when it won't say), refused past double or
half speed, and `chop --fit-tempo BPM --keep-pitch` does the same to
a kit's loops. An honest engineering note recorded in the code: the
famous Driedger hybrid (split, vocoder the notes, OLA the hits) was
built and then *retired by measurement* — PGHI alone kept a kick's
attack at the original's 33 ms rise while the hybrid's unaligned sum
smeared it to 51 ms. Modern phase reconstruction ate the reason the
hybrid existed.

### `robin <kit-dir> <pad>` — round robin, rendered

The PSK corpus trick aimed the other way. Commercial kits chain N
*recorded* takes per pad and let MPC 3 **Slice Motion** step through
them (`sliceIncrement`/`sliceCycleLength` on the layer); our pads have
one take, so `robin` renders the missing ones: `--takes N` (default 3,
2..8) seeded variants — micro level (±8%), micro pitch (±10 cents,
repitch-style so length rides along), up to 2ms of start jitter — the
differences real drummers can't help making, concatenated into one
chain WAV. Take one is the **untouched original, deliberately first**:
the MPC 2 generation has no Slice Motion, its export windows the pad to
slice 0, so older hardware plays the pristine hit. The MPC 3 cycles a
take per hit and so does the preview. `--seed S` rerolls
deterministically; the same (takes, seed) always renders the same
chain. `--undo` pulls the single take back out of the bin
byte-identical and clears the chain. While a pad is chained, the other
audio doors (`treat`, `era`, `doctor --fix`) refuse it — a rewrite
would orphan the slice boundaries — undo the robin first. Bench row:
a robin'd pad on the Live III audibly alternates takes (pending the
HH1.4 slice-chunk capture; until then the hardware plays take one, the
declared fallback).

`--zones Z` (2..4) renders the **full velocity × round-robin grid**
from that one take — the PSK corpus scheme. The chain becomes
dynamics-graded, soft→hard: each zone gets `--takes` takes of a graded
render (soft zones both *quieter* — level from 0.55 up to unity — and
*darker*, via the ghost layers' own soften depths; the top zone's
anchor is the untouched original), with the robin jitter inside every
zone. The `.xtd` writes one layer per zone the PSK way (its own base
`sliceIndex` and cycle); the `.xpm` velocity-switches the zones'
anchor takes through slice windows — real dynamics on the MPC 2, no
robin, that generation's ceiling. The preview picks the zone by
velocity and cycles takes within it: quiet hits sound soft *and*
never repeat a take.

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

### `arrange <kit-dir>` — songs, not loops

The Arranger's structure grammar lays the kit's **own** variations into
a song: intro (sparse) → theme (captured) → variation (ghosted, or
tight when nothing whispers) → the turn (fill) → reprise → outro
(half), the classic beat-tape proportions, short patterns stretched by
repeats. Every clip derives fresh from the stored base groove; `--seed
N` rerolls the grammar's choices deterministically. The plan lands as
**numbered switchable sequences** in an `.xpj` — flip `01` upward on
the hardware and that's the song — with the song slot named for the
arrangement (steps stay bench-blocked, GG3.2). Honest refusals and
skips: no groove refuses, nothing to roll on drops the turn.

`--mixdown` also renders the song as **one WAV**: every section
through the kit's own preview engine (ring-outs included — that's the
room), a **pull-up** spinning into the turn and a **tape stop**
ending the outro (SIDE A's own transitions), and the Answer's bass
riding under the body sections when the kit has one — its root
re-detected from the stored note the OneNote way, the bed skipped
honestly when nothing detects. Deterministic: same plan, same song.

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

### `album <root> --title NAME` — the crate's release

The label's endgame: every kit under the root with a groove is
arranged into a song (the Arranger's own grammar, `--seed` steered)
and mixed down, and the songs land as tracks across **SIDE A** and
**SIDE B** — per-track WAVs in the side folders, plus each side as one
continuous tape with leader gaps between tracks, the cassette way.
`tracklist.txt` lists every track with its length — and its **catalog
number** when the root runs a label (the album triggers the label's
own stable-numbering pass, so new kits get cataloged on the way).
A cover tile wears the title. Kits without a groove are *left off and
named* in the tracklist itself, the honest sleeve note. Deterministic:
same crate, same seed, same album.

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

### `clean <wav-or-kit-dir>` — the Capture Doctor

The doctor's sibling with the other patient: `doctor` treats the
**mix**, `clean` treats the **capture** — the phone-mic, room-recorded,
ground-loop reality the app's whole premise invites. Three visits, in
order, each gated by its own detector so clean audio comes back
byte-identical and is told so:

- **hum** — Goertzel probes at 50 and 60 Hz against their ±4 Hz
  neighbors; only a tone that *stands out* 4× and clears the floor is
  hum. What's found is notched (narrow biquad, Q 30) at the fundamental
  and every standing harmonic — the drums' own low end never qualifies,
  so kicks keep their sub.
- **clicks and dropouts** — dropouts (dead-zero runs with live
  neighbors) are bridged first, then clicks: per-block derivative
  outliers that *don't follow through* (a real hit sustains after its
  attack; a click doesn't) and stand isolated over their surround.
  Repairs interpolate only the flagged frames and are counted honestly.
  Wall-to-wall damage is **distortion, not clicks** — `clean` refuses
  rather than sand off a sound that *is* broken transients.
- **the floor** — measured from the quietest tenth of the capture's
  50 ms windows. Under −60 dBFS the capture is clean and nothing
  happens; above it, a downward expander (2:1 below the floor's
  margin, 12 dB depth cap, instant attack) makes the hiss recede
  between hits without ever slamming shut.

**The deep clean, `--denoise`:** the floor leg upgraded from the
expander to spectral gating (never both — they'd double-dip on the
same hiss). The noise fingerprint is the average spectrum of the
capture's own quietest tenth of frames; every STFT bin then gates
downward against fingerprint × 2 with a −12 dB depth cap, gains
smoothed across neighboring bins and eased shut over time (opening is
instant — a transient is never dulled by its own gate). The power the
expander doesn't have: the expander can only duck the gaps *between*
hits, while hiss lives in different bins than the drums — so the deep
clean pulls it out from **underneath** them, mid-band hiss receding
even while a cymbal keeps the frame loud. Too few quiet frames to
learn from, or a floor already clean, and nothing happens.

**The tail knee, `--deroom`** (kits only): de-reverb's honest first
step — not the room undone (blind deconvolution stays a research
project), but the room's *tail* found and shown out, per one-shot.
The post-peak envelope is fit as two lines hunting the knee where the
hit's steep decay hands off to a measurably shallower, still-decaying
room tail; a valid knee (≥ 18 dB under the peak, hit at least twice
as steep, tail alive and still falling — a flat tail is a noise
floor, the de-noiser's patient) gets the hit's own slope continued as
a fade capped at −24 dB, never a cut. A dry hit is single-slope by
construction and is left alone; LOOP pads are skipped by name — a
texture's tail is content.

**The declip leg, `--declip`:** clipped samples are *missing data
with a known bound* — the ceiling tells you the truth was at least
that loud, in that direction. Detection trusts only **flat-top runs**
(digital clipping repeats the very same value; even a low sine's
crest bends by orders of magnitude more), and the rebuild is
SPADE-style consistent sparsity (Kitić et al.): per clipped frame,
the sparsest spectrum that matches every reliable sample and clears
the ceiling at every pinned one, sparsity relaxing until it fits.
Reliable samples come through byte-identical; only the pinned runs
are rebuilt, and the `repairClicks` distortion refusal now *refers*
its clipping patients here. Honest expectations, measured: sustained
tonal material earns ~3 dB and rebuilt peaks; clipped noise slivers
have no sparse structure to infer and earn little — the flat-top
*buzz* goes either way. The literature's +10 dB headlines ride Gabor
dictionaries and clipped-samples-only metrics; that upgrade is below
the line.

A WAV gets its findings printed and a cleaned twin beside it
(`<name> Clean.wav`; `--in-place` overwrites, `--out DIR` redirects,
`--overwrite` replaces an existing twin). A kit dir sends every plain
pad through the treatment door — bin-backed, `clean` recipe stamped
(with `denoised` and `deroomKneeMs` when those legs acted),
velocity-layered and chained pads skipped **by name** — and
`--undo` pulls every cleaned pad back out of the bin byte-identical.
`--dry` reports without touching anything, doctor-style. To scrub a
capture *before* it becomes a kit, `chop --clean [--denoise]`
(forwarded by `dig`) runs the same pipeline ahead of the first slice.

### `label <root>` — run your own imprint

A crate root with a `label.json` is a label. `--init NAME [--prefix
XYZ]` starts the imprint (the prefix defaults to the name's initials —
`DF` for Dusty Fingers — and is set once); every run, init included,
catalogs any new kits with the next numbers in **name order**
(`XYZ-001`, `XYZ-002`, …) and rewrites `catalog.txt`, the
human-readable ledger. The stable-numbering promise is the feature:
an existing number never moves and is never reused — re-running
changes nothing, new kits only append, and a kit that leaves the
crate keeps its entry marked `(gone)`, the way a real catalog keeps
deleted releases. Once the crate is labeled, the kit's inserts wear
the number: the J-card **spine** leads with it, the liner notes'
identity line starts with it, and the expansion export and `pack`'s
`[J-Cards]/` carry it too.

### `lineage <kit-dir>` — the crate's family tree

Every verb already stamps provenance; this walks it into genealogy.
Kit-to-kit edges come from `resampledFrom` (the ritual's generation
counter) and `mergedFrom` (merge now stamps both parents onto the pads
it carries); parents are resolved **by kit name** among the kits under
`--root` (default: the kit's own parent folder, where its siblings
live), so the walk crosses folders. Where the chain bottoms out, the
terminal origins print: `dug from <song> at <time>`, `chopped from
<file>`, `imported from <archive>`, `captured from <app>` — or `made
from scratch`. Inherited stamps ride every resample generation, so
origins are shown only at the roots rather than repeated per level;
a parent that left the crate is still shown, labeled `(not in the
crate)`; cycles and diamonds print once. Deterministic — the same
crate always draws the same tree. `--png` renders the tree as a card
beside the kit (`--out DIR` to aim it elsewhere).

### `notes <kit-dir>` — liner notes

The kit's story as prose, written from what it already tracks: dug
from which song at what timestamp (or chopped from which file, or
which generation of which bounce, or imported from where — the most
specific origin wins), the miles on the tape, key and tempo, the
patterns it plays and its B-side, then every pad with its class and
what's been done to it ("through the sp1200", "shaped", "humanized").
Printed and written as `liner-notes.txt` beside the kit; expansion
exports drop it next to the J-card — the insert you read, beside the
one you look at. Deterministic: same kit, same words.

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
