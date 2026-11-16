# SPLINTER: a breakcore kit

Status: built in Kotlin and on the shelf as `splinter-breakcore`; every software gate passes; the groove passed the owner's first listen (2026-11-15), pads not yet judged one by one.
No owner listening verdict is recorded, and no phone or MPC has played it. Read from `2036a8da1`.

The characteristic gesture is **sped-up break hit → hard chop → retrigger that shrinks into a pitch → clipped kick lands**.

## What it is

Eighteen pads at 200 BPM. Entirely synthesized; no recording is loaded. It is a kit, not an engine:
`Splinter` is a source grid with saved recipes, like `SporeSyntheticKits`, with no Patch and no picker entry.

Breakcore is not a drum sound. It is what a sampler does to a break: run it too fast, cut it on the grid,
retrigger it until the roll becomes a tone, clip the kick. The shelf already has four jungle and breaks
kits (Cosmic Breaks, Cosmic Jungle, Spore Dry Jungle, Spore Orbital Breaks). All of them are single drum
hits. None of them contains an edit. SPLINTER's drums are ordinary on purpose (the owner-approved Spore
kick and snare, SKIN hats and tom, the Spore ride, a TINES zap); the new part is the stage after them.

## Three directions

| Direction | Operator | Gesture / novelty / risk / cost |
|---|---|---|
| Spore Dry Jungle through a distortion chain | STACK | Weak gesture; the gates rule it out ("different effect strengths on the same kit are not sufficient"); lowest cost |
| **Built: the edit is the instrument** | SPLICE | Clear gesture; adds chop, retrigger, speed and stretch, which no shelf kit has; risk is that baked edits fix the tempo; one small source file |
| A generated break, sliced into 16 pads | MUTATE | Strongest "chopped break" identity; a slice has no single role, so velocity layers and takes per slice are hard to make honest; a second build round |

The second wins because every pad stays a playable drum or a playable fill, and it still borrows the
third's best idea: two pads (Slice One, Slice Two) are one-beat phrases of a break with the neighbouring
hits left in.

## The edit stage

```
approved kick / snare, SKIN, ride, zap   (struck at velocity v, take t)
      │
      ├─ SPEED    read faster: a 137 BPM performance run at 200 BPM, pitch +6.55 semitones
      ├─ CHOP     cut on the grid, 1.5 ms fade
      ├─ RETRIGGER  one voice: each new hit cuts the one before it
      ├─ STRETCH  fixed grains replayed in place (grain rate audible)
      ├─ CLIP     near-hard clipper at 4x → band limit → decimate
      ▼
mean removal → 20 Hz high-pass → fixed gain → 4 ms tail fade   (no per-hit levelling)
```

- **SPEED is literal.** The slices, the rush and the riser are composed at 137 BPM and then read 1.46
  times faster, so they land on the 200 BPM grid and rise 6.55 semitones in one operation
  (`Splinter.SPEED_SEMITONES`). Nothing is time-corrected.
- **CLIP is oversampled**, the house rule for any nonlinearity. Measured on a 5 kHz tone at drive 8: third
  harmonic present, folded 5th and 7th harmonics 51 dB under the fundamental. The band limit rings on the
  flat tops (peak 1.32 for a full-scale square), so every clipped pad carries a gain under 1/1.32.
- **Not simulated:** no real break, no specific sampler's converters or stretch algorithm. STRETCH is the
  general "replay fixed grains" method, and its buzz is deliberate.

## Pads

| Slot | Pad | What it is | Class |
|---|---|---|---|
| A01 | Clip Kick | approved kick, read 3 semitones fast, clipped | KICK |
| A02 | Gabber Kick | full-body kick driven 12x: the decay holds as a square until it falls under the clip | KICK |
| A03 | Crack Snare | approved snare at break speed, light clip | SNARE |
| A04 | Ghost Snare | a light, low-wire stroke at half force, at break speed | SNARE |
| A05 | Ping Rim | high rim stroke, two semitones above break speed | SNARE |
| A06 | Stretch Snare | the snare stretched 3x in 11 ms grains (a 91 Hz buzz) | PERC |
| A07 | Tight Hat | short SKIN hat, choke group 1 | HAT_CLOSED |
| A08 | Open Hat | SKIN open hat, choke group 1 | HAT_OPEN |
| A09 | Speed Ride | the Spore ride at break speed, cut to 0.6 s | PERC |
| A10 | Crush Tom | SKIN tom, clipped, then CRUNCH (fewer bits, lower hold rate) | TOM |
| A11 | Slice One | one beat of break: kick and hat, hat, ghost snare; chopped at the beat | PERC |
| A12 | Slice Two | one beat: snare and hat, ghost, hat, pickup kick; chopped at the beat | PERC |
| A13 | Bounce Stutter | Slice Two replayed from its start: 2 half beats, 4 quarters, 8 eighths, each repeat a semitone faster | PERC |
| A14 | Bit Zap | TINES zap through heavy CRUNCH | PERC |
| A15 | Suck Back | stretched snare, reversed: swells for two beats into a cut | PERC |
| A16 | Half Drop | kick and snare together, read at half speed, clipped | PERC |
| B01 | Snare Rush | four beats; the gap halves each beat (16ths to 128ths) while force and tuning climb | LOOP |
| B02 | Mash Riser | four beats of kicks, 8ths to 64ths, low to high, into the clipper | LOOP |

Every pad has three velocities (0.30, 0.65, 1.00) and three takes: 162 cells. A softer strike is a
different strike (the sources change with force) and reaches the clipper lower, so it clips less.
A take is a fresh strike of every source in the pad. The rush's sixty hits and the riser's twenty are
each struck fresh. The Bounce Stutter's repeats are one buffer replayed: that is what a stutter is, and
its three takes are three different buffers.

## Gates

| Gate | Status | Evidence |
|---|---|---|
| Melodic voices, keygroups, chord tools | Not applicable | A drum kit. |
| Articulation: HOLD, STRUM | Not applicable | Finite hits, no strings. |
| Percussion: authored roll and riser | Pass in software | B01 and B02; event structure, length and build checked (`SplinterTest`). |
| Drums: genre kit with authored roles | Pass in software | Own pad map, own tempo, own edits; no pad is another shelf kit's pad with an effect turned up. |
| Drums: multiple genre kits | Met by the family | SPLINTER reuses the Spore sources, whose shelf already holds Dry Jungle, Dub Breaks and Orbital Breaks. SPLINTER on its own is one kit. |
| Velocity: rendering | Pass in software | Every pad: peaks rise with force, the normalised shape changes, zero force is silent (`SplinterTest`). |
| Round robin: rendering | Pass in software | Every pad's three takes differ (`SplinterTest`). |
| Saving and playback | Pass in software | All 162 cells: saved recipe → rerender → PCM on disk within 3 steps of 24-bit → `PadHit` selection, including the wrap to take 1 on the fourth repeat. Frozen takes and unchoked hats each change playback (`SplinterDeliveryTest`). |
| Export: SFZ | Pass in software | 162 regions, re-imported; PCM and selection match. |
| Export: MPC 3 | Pass in software | Three layers per pad, cycle length 3, packed PCM read back, with the eight-bar clip. |
| Export: MPC 2 | Not applicable | The owner's 2026-11-14 ruling: a pad with both velocity layers and round robin is judged on MPC 3. No MPC 2 archive is written. |
| Shelf | Pass in software | `splinter-breakcore` in `StarterKits.ALL` and once in the skins group. |
| Determinism | Pass | `DeterminismTest` canary; recipes replay to the sample. |
| Sound | **Groove heard, fine** | Owner, 2026-11-15, headphones. A "small radio" impression was traced to the headphones, not the kit. Per-pad verdicts not given. |
| Phone and MPC playback | **Not verified** | No device has loaded it. |

**Overall: incomplete.** Software gates are in place. The sound and the hardware gates are the owner's.

## Verified, measured, proposed, heard

- **Verified** (read on `2036a8da1`): `SporeKick`, `SporeSnare`, `SporeRide`, `Skin`, `Tines`, `Crunch`,
  `Valve.upsample`, `Tide.bandLimit`, `Dsp.decimate`, `Resampler.resample`, `ArrangedPad.velocityTakes`,
  `StarterKits`, `StarterShelf`, `PadHit`, `KitPreview`, `SfzWriter`, `Mpc3Exporter`.
- **Measured** (Kotlin, on a Mac): full-force pad peaks 0.30 (Ghost Snare) to 0.88 (Gabber Kick);
  clipper aliases −51 dB; the starter's 162 cells render in 15.4 s; the Snare Rush is the slowest cell at
  0.95 s, because each of its sixty hits is a full approved-snare render. Listening files: groove peak
  −0.9 dBFS, RMS −19.1 dBFS, 10.2 s.
- **Proposed** (shapes, no listen behind them): every drive, gain, grain size and event curve; the pad
  levels; 137 → 200 BPM as the speed ratio; the eight-bar groove.
- **Heard:** the owner heard the groove on 2026-11-15 and called it fine. A first "small radio" impression turned out to be his headphones; an equal-loudness A/B (as built, no clip, no speed and no clip) was made to check, and he kept the as-built version. Per-pad verdicts are still open.

## Sonic risks to listen for

1. The Clip Kick may be thin after the speed-up. The Gabber Kick carries the low end; if the pair does
   not cover a bar on its own, the Clip Kick's speed comes down.
2. The Snare Rush's last beat is a 107 Hz buzz. It should read as the roll turning into a tone, not as a
   fault.
3. The baked gestures are 200 BPM. At 170 the rush and riser finish early. The single hits and slices
   are short enough to play at any tempo.
4. Soft hits of clipped pads are much quieter than hard ones (Clip Kick 0.18 against 0.85). That is the
   touch range, but it may be too wide under a finger.

## Reproduce

```
./gradlew --no-daemon :shell:generateSplinterKit            # testkit/splinter-breakcore/
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.SplinterTest'
./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.SplinterDeliveryTest'
```

The generator writes `audio/splinter-groove.wav` (eight bars using every pad, played from the saved kit),
`audio/splinter-touch.wav` (kick, snare and slice at three forces, three repeats each),
`audio/splinter-pad-reel.wav` (all 18 pads in order at their mixer levels), one WAV per pad, and a
Native, SFZ and MPC 3 archive.

## Why it is built this way

- **The drums are borrowed and the edits are new.** The kick and snare are the pair the owner already
  approved, so a bad listen points at the edit stage, which is the only new thing here.
- **Speed is one resample, not a pitch knob.** Composing at 137 and reading at 200 gives the timing and
  the pitch from the same operation, which is why the slices sound like a sped-up break and not like
  high-tuned drums. Remove it and the kit is the Jungle kit again.
- **Retrigger cuts the previous hit.** Overlapping hits would smear into a wash. Cutting them is what
  makes a fast roll turn into a pitch.
- **The clipper runs at four times the rate.** Clipping makes harmonics above what the file can hold; at
  the normal rate they fold back as wrong notes. This keeps the dirt that belongs and drops the rest.
- **The one risky decision is baking the tempo into six pads.** An MPC cannot re-time a printed rush. The
  kit takes that trade because the rush is the genre.
