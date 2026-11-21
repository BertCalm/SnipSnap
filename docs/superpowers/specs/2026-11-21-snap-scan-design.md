# SNAP SCAN: a photo read as movement, a portrait and a fingerprint

Version 0.1 · 2026-11-21
Status: design plus a Python prototype (round 0). No Kotlin is written and no gate is passed.
Owner decisions (2026-11-21): upgrade SNAP in place rather than add a voice; calibrate on the
owner's own photos; support both camera and gallery; two shots of one scene should differ
**subtly to moderately**.
Repository truth: read at `c68e4c17c`. APIs grepped: `Snap.MACROS` (`Snap.kt:90`), `Snap.look`
(`:184`), `Snap.macrosFrom` (`:267`), `Snap.table` (`:299`), `Snap.read` (`:395`), `Snap.cycle`
(`:408`), `Snap.synthesize` (`:459`), `Snap.MAX_SECONDS` (`:81`, 1.45 s), `SnapPatch` with its
optional `envelope` (`Patches.kt:437`), `Patches.validateMacros` (`Patches.kt:201`: refuses unknown
keys, accepts missing ones), `PhotoField.cellTable` and `SOFT_SWING` (`PhotoField.kt:70`, `:50`),
`Velocity.macroSpecsFor` and `touchedRender` arms for `SnapPatch` (`Velocity.kt:273`, `:585`),
`Dsp.seedFor` (`Dsp.kt:163`), `Classifier.LOOP_MIN_SECONDS` (`Classifier.kt:81`, 1.5 s),
`PatchTypes.ROSTERLESS` (contains SNAP), `SnapScreen` camera (`SnapScreen.kt:380`,
`TakePicturePreview`) and `MAX_PHOTO_SIDE` (`:143`, 512 px). No `exifinterface` dependency exists
in the build.
Prototype: `prototype/snap-scan/` (Python harness, not Kotlin).

## 1. Summary

SNAP today reads one line through a photo (every column averaged top to bottom) into one 256-point
cycle that stays the same for the whole note, and turns four photo numbers into four knobs. This
design keeps all of that and adds three layers:

1. **SCAN**: sixteen horizontal strips, one cycle each, blended through over the note. The
   picture's top-to-bottom structure becomes movement inside one hit.
2. **PORTRAIT**: more readings of the photo, each tied to one sound quantity and stored on the
   pad, with every reading rank-mapped against a calibration set so photos use the whole range.
3. **FINGERPRINT**: a seed from the capture time and the pixels, plus a few literal metadata
   mappings, making small bounded differences. It also supplies SNAP's round robin.

The characteristic gesture is **the top of the picture strikes → the tone travels down through
the scene → it settles into the bottom of the frame as it fades**.

## 2. What the measurements say

All numbers come from the Python harness. None are from a Kotlin test, and nobody has heard
anything yet.

### 2.1 Synthetic photos (120 images with natural-image statistics)

| Measure | Result |
|---|---|
| Harmonic slope of today's HORIZON cycle (a sawtooth is −1.00) | median −1.12; 10 to 90% between −1.38 and −0.83 |
| Timbre distance, two different photos (dB rms over 40 harmonics) | 9.9 |
| Same, one scene shot twice (±6 px framing, ±8% exposure, sensor noise) | 1.6 |
| Same pair with a 16-strip read | 9.4 different, 4.4 re-shot |

Reading: averaging a line across a natural photo nearly always gives a ragged sawtooth. Photos
differ in the ripple, not the family. One scene shot twice comes out almost identical.

### 2.2 The owner's photos

The owner uploaded 100 images: 99 unique, 69 camera photos and 30 screenshots. They are not in the
repository; `prototype/snap-scan/calib_report.json` keeps only derived numbers.

| Measure | Result | Consequence |
|---|---|---|
| Today's BRIGHT on camera photos, 10 to 90% | 0.38 to 0.59 | the photos use about a fifth of the knob |
| Today's TUNE | 19 of 25 semitones used, but 27% of all images on one semitone | warm indoor hues crowd the low end |
| Worst level dip blending neighbouring strips, unaligned | −10.9 dB; 53% of images dip below −3 dB | the blend risk is real |
| Same, each strip aligned to its neighbour | −2.3 dB; 0% below −3 dB | the alignment fix works |
| Timbre distance, today's read, knobs fixed | 5.84 dB different photos; 5.42 dB for 14 pairs shot within 2 minutes | |
| Timbre distance, SCAN read, fingerprint 0.5 | 7.08 dB different photos; 6.40 dB within-2-minute pairs; 3.46 dB round-robin takes | SCAN separates photos more, and takes stay below both |
| Round-robin takes at fingerprint 0.35 / 0.5 / 0.7 | 2.69 / 3.46 / 4.27 dB | 0.5 is the "subtle to moderate" setting |
| Clipped pixels (≤ 2 or ≥ 253 of 255), 90th percentile | photos 0.016; screenshots 0.391 | a clipping reading would make every screenshot harsh unless screenshots are handled apart |
| Camera EXIF present | 59 of 69 photos have exposure, ISO, focal length, flash and f-number; **57 have GPS** | metadata is there; location must never be read |

Timbre distance for the owner's set is energy per third-octave band, frame by frame over the first
400 ms. The within-2-minute pairs mix true re-shots with different scenes taken minutes apart, so
treat that column as indicative. Some photos from the phone's camera carry a burned-in black frame
with printed shot data; their count was not measured.

## 3. Sibling check

| Engine | What it does | What it cannot do |
|---|---|---|
| CONTOUR | two-layer wavetable over generated harmonic families with SCAN | read a picture; its tables are generated |
| SCANNED CHAIN | a 128-point shape moved by a mass-spring simulation | take its shape or its motion from a photo |

Nothing in the roster turns a picture's spatial structure into motion. The lightest landing that
keeps the gesture is SNAP itself. That is the owner's choice too.

## 4. Three directions

| Direction | Operator | Gesture | Novelty | Risk | Cost | Verdict |
|---|---|---|---|---|---|---|
| **A. SCAN + PORTRAIT + FINGERPRINT** | STACK | clear: strike, travel, settle | high | low to medium (blend dips, measured and fixed) | one engine upgrade plus an app capture change | **Strong** |
| B. PALETTE CHORD inside one pad | SPLICE (PhotoChord into the voice) | a chord whose colours are the photo's | medium | consonance and mud | small, after A | round 3 of A |
| C. PHOTO BODY: the table excites a modal resonator tuned by the picture | COUPLE | strike into a body | high | the photo stops being audible | high; overlaps the modal engines | not now |

A wins because the photo stays the audible source and the movement is the photo's own geometry,
not a mapping someone chose.

## 5. Signal flow

```
photo (≤ 512 px long side, EXIF-rotated, uniform border cropped)
  │
  ├─► READ ──► 16 strip cycles ──► align each to its neighbour ──┐   (stored on the pad)
  │      └──► PORTRAIT readings ──► rank-map (calibration) ──────┤
  │                                                              │
  └─► capture time + pixel hash ──► Dsp.seedFor ──► FINGERPRINT ─┤
      EXIF: exposure, ISO, flash, focal length ──────────────────┤
                                                                 ▼
 scan position(t) ← SCAN, light direction, exposure ──► strip blend (smoothstep) ──► tilt (warmth)
   ──► SVF low-pass (BRIGHT, follows the envelope) ──► drive (GRIT + clipping) ──► envelope (DECAY)
   ──► house output stage (Tide.bandLimit → Dsp.decimate → DC block → level → fadeTail)
```

Everything left of the arrow into the render runs once per photo. The render reads stored numbers
only, so a pad rebuilds bit for bit from its sidecar, as SNAP does today.

## 6. Controls and stored readings

### 6.1 Macros (5, inside the house 3 to 6)

| Macro | Default | Model quantity | Required interaction | Test that proves it moves |
|---|---|---|---|---|
| TUNE | from hue (unchanged) | note, 24 semitones from A2 | none new | root pitch within 50 cents |
| BRIGHT | from brightness (unchanged) | SVF cutoff | with SCAN: the cutoff follows the envelope while the strips move | spectral centroid rises monotonically |
| DECAY | from colourfulness (unchanged) | T60 and length | with SCAN: the scan settles over the note's own length | render length tracks T60 |
| GRIT | from detail (unchanged) | drive gain | with clipping: a clipped photo raises the drive floor | crest factor falls |
| **SCAN** | 0.6 for a new photo read (shape) | how far the note travels through the strips (0 to 15), and how much of the strip cycle replaces today's whole-picture HORIZON cycle (mix = SCAN) | with light direction: which end it starts from | frame-to-frame spectral flux rises with SCAN; SCAN 0 equals today's render byte for byte |

At SCAN 0 the oscillator reads only the stored HORIZON `table`, which is today's sound. Above 0 it
reads `(1 − SCAN) × table + SCAN × strip(t)`, and the strip position travels `SCAN × 15` strips over
the note. The round-0 prototype did not have this mix: its SCAN 0 parks on the first strip. Its
SCAN renders at 0.6 and 1.0 are the design's, apart from the missing 40% and 0% HORIZON share.

**Backward compatibility.** `Patches.validateMacros` accepts missing keys, so old pads load. A pad
with no stored strips renders exactly as today and SCAN has nothing to move. Decide (§11, D1)
whether SCAN is hidden or greyed on such pads; the "every macro changes the sound" probe runs on
fresh reads only. A DRAWN line has no strips, so `macrosFor(DRAWN)` keeps today's four.

### 6.2 PORTRAIT readings (stored, not knobs)

| Reading | How it is measured | Sound quantity | Why this pairing |
|---|---|---|---|
| 16 strip cycles | each horizontal strip column-averaged, `Snap.resample`, through `PhotoField.cellTable`'s soft swing | the scanned wavetable | the photo's own geometry becomes motion |
| Light direction | mean luma of the top third minus the bottom third | scan direction and envelope: a bright top strikes then darkens; a bright bottom swells | the scan already travels top to bottom |
| Warmth | mean of red minus blue | a fixed tilt: warm rounds the top, cool adds air | matches how warm and cool sound is described |
| Symmetry | mirror correlation of luma, left against right | slow pitch drift depth: symmetric is steady, lopsided drifts a few cents | balance becomes stability |
| Clipping | share of pixels at ≤ 2 or ≥ 253 of 255 | raises GRIT's floor (camera photos only, see §8) | a clipped photo clips |
| Grain | Immerkær noise estimate on luma | breath-noise layer level | a grainy photo sounds grainy |
| Second colour (round 3) | 3-colour clustering of a 64 × 48 copy | a quiet second voice at an octave, fifth or fourth | colour pairs become intervals |

### 6.3 Rank mapping (calibration v1)

Each reading passes through its own 9-point percentile table before it meets a sound quantity, so a
typical photo lands mid-range and the extremes reach the ends. Today's linear stretches stay as the
fallback for a pad saved before this change.

Calibration v1 (owner's 99 unique images, 0th to 100th percentile in steps of 12.5; shape until a
larger set exists):

| Reading | 0 | 12.5 | 25 | 37.5 | 50 | 62.5 | 75 | 87.5 | 100 |
|---|---|---|---|---|---|---|---|---|---|
| luminance | 0.1455 | 0.2900 | 0.3750 | 0.4301 | 0.4794 | 0.5107 | 0.5461 | 0.6310 | 0.9471 |
| saturation | 0.0000 | 0.0937 | 0.1519 | 0.1858 | 0.2280 | 0.2608 | 0.3095 | 0.3948 | 0.8077 |
| detail | 0.0075 | 0.0364 | 0.0460 | 0.0574 | 0.0643 | 0.0708 | 0.0818 | 0.0980 | 0.1374 |
| light | −0.4816 | −0.1627 | −0.0780 | −0.0244 | 0.0215 | 0.1343 | 0.2033 | 0.3283 | 0.6006 |
| warmth | −0.1810 | −0.0143 | 0.0003 | 0.0232 | 0.0341 | 0.0745 | 0.1092 | 0.1538 | 0.5468 |
| symmetry | −0.2796 | 0.0219 | 0.1482 | 0.2406 | 0.3852 | 0.4682 | 0.5994 | 0.7159 | 0.9804 |
| clipped | 0.0000 | 0.0000 | 0.0000 | 0.0001 | 0.0009 | 0.0059 | 0.0397 | 0.2088 | 0.8669 |
| grain | 0.0005 | 0.0033 | 0.0045 | 0.0054 | 0.0067 | 0.0090 | 0.0109 | 0.0162 | 0.0291 |

Bias disclosed: one owner, one phone, many indoor and pet photos. A second set would move these
values; the table is versioned on the patch (`portraitVersion`) so a recalibration never changes a
saved pad.

## 7. FINGERPRINT

**Seed.** `Dsp.seedFor(captureTimeSeconds, pixelHash)`. The capture time comes from EXIF
`DateTimeOriginal` when there is a file, otherwise the phone clock at the shutter. The pixel hash is
taken over the 512 px copy, so a screenshot or an old gallery image still gets a stable seed. The
seed is stored on the patch, as NACRE does, never recomputed.

**What it moves at strength 0.5 (the owner's "subtle to moderate").**

| Quantity | Range at 0.5 | Never |
|---|---|---|
| Fine tune | ±3 cents | the note or the key |
| Length feel (T60) | ±6% | the drum class: the render stays under 1.45 s |
| Scan start | up to half a strip in | the scan direction |
| Oscillator start phase | anywhere in the cycle | |
| Drift LFO phase | anywhere | its depth (that is symmetry's) |

**Round robin.** Take *k* uses seed + *k*. Measured: takes sit 3.46 dB apart against 7.08 dB for
different photos (§2.2). Follow the existing ACCORD path (`AccordRoundRobinKits.kt`,
`kit/XpnRobin.kt`) for saving and export.

**Literal metadata mappings (round 2, needs a file).**

| EXIF field | Sound | Why |
|---|---|---|
| ExposureTime | longer than 1/30 s softens the attack and slows the scan | a long exposure is a smear |
| ISO | adds to the grain reading | literal sensor noise |
| Flash fired | a 2 ms click at the start | |
| FocalLengthIn35mmFilm | wide lens: a ROOM landing; above 70 mm: dry | wide shots feel spacious |
| GPS | **never read** | the pad's sidecar travels into exported kits and expansions |

## 8. Screenshots, frames and flat strips

- **Screenshots.** An image with no camera EXIF is classed as a graphic. Its clipping reading is
  dropped, because UI white and black are not overexposure (90th percentile 0.391 against 0.016 for
  photos). Every other reading still applies.
- **Burned-in frames.** Crop any uniform border (a run of rows or columns whose luma spread is
  under 3 levels) before reading. Otherwise a black frame becomes the first and last strip and the
  light-direction reading.
- **Flat strips.** A strip with no swing (clear sky, a UI bar) goes through
  `PhotoField.cellTable`'s blend toward a sine. The prototype divided by zero here before it borrowed
  that guard.

## 9. Render budget

| Step | When | Cost |
|---|---|---|
| Readings, 16 strips, alignment (16 circular cross-correlations of 256 points), percentile lookups | once per photo, off the main thread like today's `Snap.look` | one pass over ≤ 512 px plus trivial work; proposed, not timed in Kotlin |
| Render | every knob turn | about twice today's per-sample work (two strips read, one smoothstep blend); still tens of ms; proposed, not timed |
| Storage | per pad | 16 × 256 bytes as sidecar digits, roughly 12 to 16 KB of text against 1 KB today; disclosed |

## 10. Rounds

| Round | Scope | Lands |
|---|---|---|
| 1 | `:synth` only: strip read, alignment, scan, PORTRAIT readings with calibration v1, fingerprint from the pixel hash and the phone clock, round robin, all probes | SNAP renders, sidecar, velocity and RR through render, save, playback, export |
| 2 | `:app`: `TakePicture` to a cache file plus `PickVisualMedia` for the gallery; `androidx.exifinterface` reads only the fields in §7; the file is deleted after reading; only derived numbers stored | literal metadata mappings |
| 3 | PALETTE second voice | direction B |

### Round 1 tests (house probe set plus these)

- SCAN 0 on a pad with strips renders byte for byte what today's render gives for the same HORIZON table.
- A pad saved before this change renders byte for byte as it did (golden).
- Strip alignment: the worst neighbour blend dip on a fixed set of generated photos stays above −3 dB. Revert the alignment alone and watch it fail.
- Every PORTRAIT reading changes the render, and a flat strip never yields a non-finite sample.
- Determinism: the same photo and seed give identical bytes; take *k* and take *k* + 1 differ.
- Fingerprint bounds: over 1,000 seeds, the pitch stays within ±3 cents of the note and the length under 1.45 s.
- The classifier keeps every render out of the drum set; JSON round trip including strips, seed and `portraitVersion`.

## 11. Open decisions for the owner

- **D1.** On an old pad without strips, should SCAN be hidden or shown greyed? Recommendation: greyed, with a note that a new photo enables it.
- **D2.** Should the default SCAN for a new read be 0.6 (clear movement) or 0.4 (gentler)? Decide by listening to the round-0 set.
- **D3.** Should the fingerprint strength be fixed at 0.5 or offered as a sixth knob? Recommendation: fixed; six knobs crowds the screen.

## 12. Ledger

- **Verified (grep at `c68e4c17c`):** every API in the header; SNAP is rosterless; the camera path returns no file and no EXIF; `validateMacros` accepts missing keys.
- **Measured (Python harness):** §2 in full; the harness is `prototype/snap-scan/`.
- **Proposed:** every pairing in §6.2 and §7, the SCAN law, the default 0.6, the fingerprint ranges, the render budget, the border crop.
- **Heard:** nothing yet. The round-0 listening set (four photos × today, SCAN, take 2, full SCAN) went to the owner on 2026-11-21.

## 13. Why it is built this way

1. **Movement over more knobs.** A single cycle is a still image; blending through sixteen strips turns the photo's structure into change over time, which is what makes a pad sound like *this* photo.
2. **Readings live on the pad.** Stored strips and readings keep the controls at five plain words while the photo carries the detail. Remove the storage and every re-render would need the photo, which SNAP deliberately never keeps.
3. **Rank mapping before mapping.** Phone photos cluster; ranking each reading against real photos spreads them over the whole knob. Without it, most pads would sound alike again even with more readings.
4. **The risky decision is the blend.** Unaligned strips cancel mid-blend (−10.9 dB worst). Aligning each strip to its neighbour once, at read time, holds the worst case to −2.3 dB at no render cost.
5. **The fingerprint is small on purpose.** It can change how a note is played, never which note or how long the pad is, so it can add variety without breaking a kit.
