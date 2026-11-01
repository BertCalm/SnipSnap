# Skin

SKIN is the second drum engine, alongside THUMP, and the modal one. The
characteristic gesture is a struck object ringing at several inharmonic modes at
once, the higher ones dying first, which is what reads as a hit on a body rather
than a synth tone. Voices take one of three paths:

```
KICK, SNARE, TOM, STICK:  sum of decaying sine partials at inharmonic ratios
                          (+ beater/stick noise; SNARE + high-passed noise wires)
HAT_CLOSED, HAT_OPEN,     continuous noise ──> bank of tuned resonant SVFs
RIDE:                     (6 modes for the hats, 8 for the ride) ──> envelope
SHAKER:                   continuous noise ──> one wide, non-resonant SVF ──> envelope
then, every voice:        normalize ──> PUNCH (oversampled) ──> peak limit ──> Snip
```

Partials are sines with their own envelopes rather than an impulse into a
resonant filter, because `Dsp.TptSvf`'s damping floor caps how long a filter can
ring on its own well short of DECAY's top (`Skin.modalBody`'s KDoc). The cymbals
drive their filters with noise the whole time for the same reason. Renders run
at 4x and decimate. The roadmap row is S6.

## Voices

| Voice | Model | Its own knobs | TUNE range |
|---|---|---|---|
| KICK | fundamental plus partials at 1.59x and 2.14x, beater click | TONE, CLICK, DROP | 45-75 Hz |
| SNARE | two-partial head against high-passed noise wires | SNAP, RATTLE, SIZZLE | 170-260 Hz |
| HAT_CLOSED | noise through six tuned filters, short | TONE, RING | 5.2-8.2 kHz |
| HAT_OPEN | the same bank, longer | TONE, RING | 5.2-8.2 kHz |
| TOM | fundamental plus a partial at 1.63x, no click by default | TONE, DROP, CLICK | 90-220 Hz |
| RIDE | noise through eight tuned filters, long | TONE, SIZZLE | 3.2-5.2 kHz |
| SHAKER | noise through one wide band | TONE, SWELL, GRAIN | none (no TUNE) |
| STICK | one high partial | BODY, CLICK | 1.8-3.4 kHz |

Every voice has DECAY and PUNCH; every voice but SHAKER has TUNE. TUNE is
continuous here, not snapped: it tunes a drum, it does not pick a note.

## Knobs

- **TUNE** maps exponentially across the voice's range above.
- **DECAY** sets the ring. The ride stops at a 1.0 s T60 so it stays under the
  classifier's 1.5 s loop floor.
- **PUNCH** is the shared transient shaper (`Punch`): onset saturation and an
  attack boost, rescaled to the hit's own loudness. At 0 it does nothing.
- **TONE** is brightness, per voice: the overtones' level on KICK and TOM, the
  spread of the modes on the hats and RIDE, the band's centre (3.5-9 kHz) on
  SHAKER.
- **CLICK** is the strike noise: KICK's beater (0.5 is the old fixed click, below
  it softer, above it louder and brighter), a stick on TOM, a wood crack on STICK.
- **DROP** is the head's pitch dip when struck hard, every mode together: up to
  90% sharp on KICK, 60% and slower on TOM.
- **SNAP** mixes SNARE's head against its wires. Below 0.20 the classifier files
  the snare as PERC.
- **RATTLE** scales the wires' ring against the head's, 0.4 to 3.5 times.
- **SIZZLE** on SNARE is the wires' high-pass, 1.2 to 8 kHz, level-matched so it
  moves shape, not loudness. On RIDE it is the rivets: a bright buzz outlasting
  the wash.
- **RING** is the hat bank's resonance. Its default sits at 0.8 because most of
  the travel is below the old sound; the trashy end stops where closed hats would
  start reading as SNARE.
- **SWELL** is SHAKER's attack, 1 ms to 80 ms.
- **GRAIN** gates SHAKER's noise into separate beads, about 250 a second.
- **BODY** adds a shell partial at 0.22x under STICK, ringing 2.5 times as long.

## Kit, audition, presets

- **Presets:** twenty per voice in `SkinPresets.kt`, 160 in all: four each for
  BOOM BAP, HOUSE, JUNGLE and DUB, then four BOLD. Their values were placed
  inside each voice's classifier-safe region, which was mapped first (the table
  in that file's KDoc). Not authored by ear.
- **Starter kit:** `SkinKits.classic()`, all eight voices on sixteen pads, every
  pad named by a preset so the values have one home. It is the SKIN starter kit
  in the app (`StarterKits.kt`). No gradle task renders it to `testkit/`.
- **Audition page:** `./gradlew :synth:generateSkinAudition` runs
  `SkinRosterAuditionGenerator`, the shared `RosterAudition` page. It writes
  `testkit/skin-audition/` with the classic kit, then each voice's default, every
  knob at both ends, TUNE at three tunings, and every preset, at one loudness.

## Ledger

- **Verified:** `SkinTest`, `SkinPresetsTest`, `SkinSoundDesignTest` and
  `SkinKitsTest` cover the voices' classes, the roster's classifier regions, the
  exact-default sound-design macros and the kit's layout. `FirstGenAuditionTest`
  checks the audition spec has a note for every voice and every knob but TUNE.
- **Not heard:** no SKIN preset, kit pad or knob end has had the owner's listen on
  record.
- **Open:** RIDE's BELL macro was built and dropped (any audible bell classified
  as SNARE); SIZZLE took its place. The classic kit declares RIDE as PERC so the
  closed hat does not choke it, while `Classifier` hears it as HAT_OPEN.
