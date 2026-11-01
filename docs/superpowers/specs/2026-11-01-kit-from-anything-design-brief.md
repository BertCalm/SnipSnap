# KIT FROM ANYTHING: design brief

Status: concept, prototyped in Python with the repository's own classifier as the judge. Nothing
in Kotlin, nothing heard.
Repository truth: commit `dfc8fd0a`, read 2026-11-01. APIs grepped before being named:
`Classifier` (the rules quoted below, `Classifier.kt:139-206`), `FeatureExtractor`
(`Features.kt`: bands under 200 Hz, under 2 kHz, 2 kHz and up; decay is the time to −20 dB),
`AutoPlace` (the conventional layout and `HAT_MUTE_GROUP = 1`), `Balance.TARGETS`, `KitPad`
(`slot`, `drumClass`, `colorHex`, `level`, `tuneCoarse`, `muteGroup`, `oneShot`, `source`,
`recipe`, `velocityLayers`), `KitAssembler.assemble` / `assembleArranged` / `ArrangedPad`,
`Scale` (`CHROMATIC MAJOR MINOR MAJOR_PENTATONIC MINOR_PENTATONIC`), `Tuner.inKey`,
`Chopper.byTransients`, `Stretch`, `Cleanup`, `Desample`, `Body`, `PadFromAnything`,
`ThumpPresets` (the BOOM BAP and HOUSE rows), `Treatments`, `Eras.names`
(`sp1200 mpc60 tape phone`), `GrooveFeel`, `Kit.tempoBpm`, `ChopCommand.SURE_CONFIDENCE`,
and the CLI's `classify`.

Prototype: [`2026-11-01-kit-from-anything-prototype.py`](2026-11-01-kit-from-anything-prototype.py),
results in [`2026-11-01-kit-from-anything-prototype-results.json`](2026-11-01-kit-from-anything-prototype-results.json).
The renders are Python; the verdicts on the drum slots are the real Kotlin classifier, run
through `cli/build/libs/snipsnap.jar classify` on the written WAVs. "Measured" below means that.

This is not an engine. It is a kit assembler, and its gesture is the kit's: **one sound → sixteen
pads that play together in one key and one style**.

## Where it came from, and what is already decided

Josh's idea: any captured sound becomes four bass notes, four tones, a kick, a snare, a closed
and an open hat, a ride, a crash, a clap and a siren; the user picks a key and a genre; the app
chops, stretches, compresses and treats the source into a playable kit. Decisions taken in
conversation: **purist by default** (every pad is the source, transformed), the synth is a
**blend fallback, never a replacement**, the tonal half of that fallback is the QUARRY engine
([its brief](2026-10-31-quarry-engine-design-brief.md), merged), and the first three genres are
**house, techno and boom bap**.

## What it is

Input: one captured `Snip` (or a longer capture the chopper can cut), a key (`rootSemitone` and a
`Scale`), a genre. Output: a kit folder through `KitAssembler.assembleArranged`, sixteen
`ArrangedPad`s in a fixed layout, each tagged, levelled by `Balance`, chokes set, tempo written to
`Kit.tempoBpm`, provenance in each pad's `source` map, and a recipe that can rebuild the pad.

The layout is fixed by the slot list, so `AutoPlace` is not used; its conventions are kept
(kick A01, snare A02, closed hat A03, open hat A04, hats choke each other, nothing else does).

| Pad | Slot | Made from the source by | Tag (`DrumClass`) | Probe |
|---|---|---|---|---|
| A01 | KICK | the source's forced cycle sung at the genre's kick pitch with a pitch drop, driven then low-passed steeply; the source's own first 8 ms as the click | KICK | classifier says KICK |
| A02 | SNARE | the source's own noise (below) decaying, brightened per genre, over the forced cycle at the genre's snare tune | SNARE | classifier says SNARE |
| A03 | HAT CLOSED | the source read faster (the sampler trick: faster is higher) for the first 30 ms, over its noise read faster, high-passed at 9 kHz, short | HAT_CLOSED | classifier says HAT_CLOSED |
| A04 | HAT OPEN | the same, long | HAT_OPEN | classifier says HAT_OPEN |
| A05 | CLAP | four bursts of the source's noise 12 ms apart, rising, then its tail | CLAP | classifier says CLAP |
| A06 | RIDE | the source read 4× faster, band-passed 4 to 11 kHz, long, with a short cycle ping as the bell | HAT_OPEN (no ride class exists) | centroid over 4 kHz, decay over 300 ms |
| A07 | CRASH | the source read 3× faster, paulstretched into a wash, high-passed at 3 kHz, capped under the LOOP line | HAT_OPEN | under 1.5 s, decay over 500 ms |
| A08 | SIREN | the source's cycle along a pitch trajectory: a drop (boom bap), a rise and fall (house), a climb (techno) | PERC | the trajectory's range in semitones |
| A09 to A12 | BASS | QUARRY at the key's root, third, fifth, octave from C1 plus root, low-passed per genre | TONAL | root within 20 cents |
| A13 to A16 | TONE | QUARRY an octave and a half up, grain field open, longer | TONAL | root within 20 cents |

**The source's own noise** is the one new primitive: the source divided by its own exact long-term
magnitude spectrum (two passes, floored at −50 dB), its arrival paulstretched to the length
needed with a short window, and read faster until at least half its power sits above 2 kHz. It is
still only the source. The floor is the purist limit: what cannot be flattened within it is not
forced, and that slot falls back to the blend.

**Found before made.** When `Chopper.byTransients` finds two or more hits in the capture, every
slice is classified first, and a slice the classifier files as the slot's class at or above
`ChopCommand.SURE_CONFIDENCE` is used as the pad, treated by the genre's landing chain only. A
break is the purist's best case and should never be synthesized over.

## The genre table

Every number is "shape" until a listen. The drum numbers are translated from the house's own
THUMP preset rows: BOOM BAP from `DUSTY BOOM` to `TAPE THUMP` (kick TUNE .16 to .34, DECAY .37 to
.53, CLICK low), `DEEP ROOM` to `BACKBEAT`, `RAGGA TICK` to `FAST TAP`; HOUSE from `FOUR FLOOR`
to `PEAK TIME` (TUNE .33 to .51, SWEEP .48 to .68, DECAY .14 to .30, CLICK .38 to .62),
`CRACKED CLAY` to `PICCOLO`, `CLUB TICK` to `PEAK TAP`; TECHNO from HOUSE's `WAREHOUSE` and
`PEAK TIME` corner pushed (the shortest decay, the most click, the most drive). The repo has no
techno rows, so that one is the author's extrapolation and is marked so.

| | BOOM BAP | HOUSE | TECHNO |
|---|---|---|---|
| Tempo written | 92 | 124 | 132 |
| Kick: pitch, drop, decay, click, low-pass | 52 Hz, ×2.2 over 40 ms, 280 ms, .25, 110 Hz | 48 Hz, ×3 over 25 ms, 240 ms, .45, 120 Hz | 50 Hz, ×4 over 18 ms, 170 ms, .6, 115 Hz |
| Snare: tune, decay, brightness | 190 Hz, 230 ms, .45 | 220 Hz, 170 ms, .6 | 240 Hz, 120 ms, .7 |
| Hats: closed, open, read speed | 70 ms, 380 ms, 5× | 90 ms, 480 ms, 6× | 50 ms, 300 ms, 7× |
| Clap decay | 180 ms | 220 ms | 150 ms |
| Ride decay, crash length | 700 ms, 1.3 s | 800 ms, 1.4 s | 600 ms, 1.2 s |
| Siren | drop, 7 semitones over .45 s | rise and fall, 12 over .8 s | climb, 12 over 1 s |
| Bass: decay, low-pass; tone decay | 450 ms, 900 Hz; 800 ms | 280 ms, 1.2 kHz; 900 ms | 160 ms, 700 Hz; 500 ms |
| Landing chain (proposed, not prototyped) | `Eras` sp1200 at .6, SQUASH | `Eras` tape at .3, small ROOM | VALVE drive, SQUASH, dry |
| Groove (proposed) | `GrooveFeel.generated`, swung 16ths | straight, open hat on the offbeat | straight |

The landing chains are `FxChain` recipes over existing sections and the existing eras, applied
per pad after the slot is made, exactly as `Treatments` already applies a named character.

## The blend rule

Per slot, the probe in the table is the gate. When the purist render fails it, the pad becomes a
blend, and the rule has three parts. The synth half is chosen **to sound like the purist render**:
for a drum slot `Desample` finds the nearest THUMP patch to the purist render (not to the raw
source), so the synth kick is the kick this bark almost was; for a bass or tone slot QUARRY is
already the source's own material, and until it ships `Body` ringing the key's chord tones on
the hit is the bridge. The share is `1 − confidence` of the purist render against its probe,
**capped at 0.5**, so the source is never less than half the pad. The pad's `source` map says
so in words (`made: purist` or `made: blend, synth THUMP KICK 0.4`) and the pad sheet shows it;
nothing is silent about it.

## What the prototype measured

Four synthesized stand-in sources (a pluck, a sine-sweep kick, a two-formant bark, a snare) ×
three genres = twelve kits, 192 pads, every one judged or probed. Key: A minor.

**Drum slots judged by the real classifier (5 slots × 12 kits = 60):**

| Pass | Agreed | What the numbers changed |
|---|---|---|
| 1 | 19 / 60 | kick bodies read centroid 250 to 850 Hz (TOM, PERC, TONAL): the forced cycle at 50 Hz is only 2.3× slower than the excerpt, so the source's formants stay in the mids. Claps and snares failed the flatness gates. Open hats tiled a sped-up 60 ms source and read as 12 ms closed hats. |
| 2 | 26 / 60 | drive before a 4-pole low-pass at 110 to 130 Hz, shorter kick decays: kicks 11 / 12. Noise from whitening alone still carried a pluck's harmonic lines. |
| 3 | 52 / 60 | exact (unsmoothed) whitening, "read faster until bright", the hat noise read at the hat's speed: hats 24 / 24, kicks 12 / 12. |
| 4 | **56 / 60** | two-pass whitening at a −50 dB floor: snares 11 / 12, claps 9 / 12. |

The four that remain all come from the sine-sweep kick source: its snare (boom bap) and its three
claps read PERC, because a single swept sine has almost no noise to quarry. That is the fallback
set, by design, and it is the right one: a source with nothing in it should blend, not be forced.

**Bass and tones (96 pads):** 78 within 20 cents of the target note, the worst of those 12.2
cents; 18 read exactly one or two octaves, or an octave and a fifth, below the target at
confidence 1.0. Every one of those 18 is an exact sub-harmonic, which a period-based detector
gives on any periodic signal when the note is shorter than its window (techno's 160 ms bass
dominates the list). It is a measurement ambiguity, not a tuning error; the Kotlin probe should
use `Pitch.detect`'s first-peak rule inside the note's first 150 ms.

**Untagged slots:** all 192 pads land under the 1.5 s LOOP line. The classifier files the siren
PERC twelve times, the ride PERC nine and SNARE three, the crash across four classes, and 94 of
96 bass and tone pads PERC. The assembler tags these itself; the classifier is the judge only
where a class exists for the slot.

## Risks

1. **Identity.** Whitening and paulstretching push "the source's noise" toward any noise; the
   −50 dB floor and the two-pass limit bound it, and the "read faster" rule keeps the source's
   own transient in every hat. Whether a bark's kit still sounds like the bark is a listen, and
   the audition page should play each pad beside the raw source.
2. **The classifier is the judge for five slots only.** Ride, crash and siren have no class;
   their tags are the assembler's, and `Balance.TARGETS` and the pad colour follow the tag.
3. **Kicks from a source with no low end** are manufactured by the forced cycle; the prototype's
   twelve kicks all passed, but a real phone capture with the mic's roll-off has not been tried.
4. **Sub-harmonic pitch readings** (above). A test that asserts the root must measure early and
   take the first peak.
5. **The LOOP line.** A crash wants to be long; it is capped at 1.45 s so the kit stays a one-shot
   kit. HOLD is not part of this feature.
6. **Render time**, proposed: sixteen offline chains per kit; the whitening STFT and paulstretch
   on under a second of material are cheap, the eight QUARRY notes cost tens of milliseconds each
   with its cache. The Python harness renders a kit in under two seconds with nothing cached. A
   genre change re-renders all sixteen; a key change re-renders eight.
7. **Recipes.** These pads are derived audio, neither a raw capture (whose recipe is an
   `FxChain`) nor a synth patch. The deterministic promise ("a kit folder rebuilds its WAVs from
   the sidecar") needs each pad to carry `{slot, genre, key, source hash, version}` and the
   source WAV kept in the folder. This is the same decision QUARRY's brief files as R0, and it
   should be made once for both.

## Build rounds

- **R0** the recipe decision above, shared with QUARRY; two real capture fixtures (one tonal, one
  drum) under a second each, checked into test resources.
- **R1** the drum eight, purist only, in `:kit` (a `KitFromAnything` object returning
  `List<ArrangedPad>` into `KitAssembler.assembleArranged`), the genre table as data, the
  landing chains; the Kotlin test runs the real classifier over the five judged slots on both
  fixtures and asserts the agreements the prototype reached, with the fallback set reported,
  not hidden. CLI: `kitfrom <wav> --key Am --genre house --out <dir>`.
- **R2** bass and tones through `Body` as the bridge, then QUARRY when its R1 lands; `InKey` is
  not needed since the notes are rendered at pitch.
- **R3** the blend rule with `Desample`, the `source` map wording, found-before-made via the
  chopper.
- **R4** the pad sheet door, the audition page (each pad beside its source, three genres, two
  keys), docs.

## Verified / measured / proposed / heard

| Claim | Status | Evidence |
|---|---|---|
| The classifier's rules and bands are as quoted | verified | `Classifier.kt:44-206`, `Features.kt:57-125` |
| `KitAssembler.assembleArranged` takes arranged pads | verified | `KitAssembler.kt:85` |
| 56 of 60 judged drum slots agree with the real classifier | measured | results JSON `score` |
| The four misses are all from the sine-sweep source | measured | results JSON `kits` rows with `agrees: false` |
| 78 of 96 bass and tone pads within 20 cents, worst 12.2 | measured | results JSON, `pitch_hz` rows |
| The 18 misses are exact sub-harmonics | measured | summary run, all at 1200, 1902 or 2400 cents |
| All 192 pads under the LOOP line | measured | `under_loop_line` |
| The landing chains and grooves suit their genres | proposed | not prototyped |
| A bark's kit sounds like the bark | heard? | not heard; audition pad-beside-source |
| The techno numbers | proposed | extrapolated from HOUSE's corner; no techno rows exist in the repo |

## Why it is built this way

- **The classifier is the referee, not a filter.** Each drum pad is made to satisfy the rules the
  repo already uses to tell a kick from a tom, so what lands on A01 is a kick by the app's own
  definition. That is also why the design went through four passes: the numbers told it where
  the mids were hiding in the kick and why a pluck would not become a clap.
- **Three tricks make every drum from any sound.** Read it faster for hats (higher and shorter),
  sing its cycle at a drum pitch for kicks and bodies, and whiten its arrival into noise for
  snares and claps. All three are the source, so a kit made from a cough is still the cough.
- **The genre is a table, not a brain.** Three rows of plain numbers, each traced to a preset
  the repo already ships, so a fourth genre is a row and a listen, not a new mechanism.
- **The fallback is bounded and spoken.** When the source has nothing to give for a slot (a pure
  sine has no noise), the synth blends in at no more than half and the pad says so. Forcing a
  clap out of a sine would have been a lie in the kit folder.
- **Found beats made.** A capture that already contains a snare should put that snare on A02.
  Synthesizing over a real hit is the one thing this feature must never do.

## What would sharpen the next prompt

Two real captures of yours (a tonal one and a drum-like one, a second or less, 44.1 kHz), the
one genre to build first, and whether the four bass notes should be root-third-fifth-octave as
here or a walking shape (root, flat seventh, octave, fifth) for boom bap.
