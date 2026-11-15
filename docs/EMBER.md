# EMBER: design brief, round-0 audition and round-1 Kotlin port

Status: **round 1b, the Kotlin port with the long voices ringing out (2026-11-15).** Round 0e's Python renders
are the sound baseline: the owner heard them on 2026-11-15, said "Much better", then "Proceed". `Ember.kt` ports
all 27 voices. On the round-1 listening page the owner heard "Swell cuts abruptly"; round 1b lets the swell, the
bow and both rolls ring out, in the prototype and in Kotlin. The four kits are not on the shelf yet (round 2). The
owner's listens so far:
- "Cymbals need a lot of work. Sounds like hitting sheet metal." Round 0b rebuilt every cymbal and hat from
  measurements of real cymbals.
- "Sweeps sound like hail hitting a roof." Round 0c rebuilt the brush sweep and the brush roll from
  measurements of real brush stirs and flutters.
- "Bring down the sweep a bit in the groove ... a bit too mid heavy." Round 0d lowers the sweep in the grooves,
  brightens it and eases After Hours' 1962 chain.
- "I meant all the kits." Round 0e brightens every kit.
- On 2026-11-15 the owner heard rounds 0b to 0e (listening page version 12) and said "Much better". This
  records the direction improving. It is not yet an acceptance of the voices or the kits as a baseline.
- "Proceed" (2026-11-15): taken as round 0e kept as the sound, and the Kotlin round (round 1, below) built
  against it.
- "Swell cuts abruptly" (2026-11-15, the round-1 listening page). Round 1b lets the long voices ring out. The
  owner chose a natural ring-out of about 2.5 s for the swell, and the same treatment for the bow and both rolls.

Each round is below. Nothing else changed sound, and nothing else has been heard yet. Of the Kotlin renders, the
owner has commented only on the swell. The engine stays in progress until every applicable owner gate has evidence (see "Gates" below and the gates entry in
[ENGINE_ACCEPTANCE_GATES.md](ENGINE_ACCEPTANCE_GATES.md#ember-round-1-kotlin-2026-11-15-not-heard-shelf-and-hardware-open)).

Prototype: [`prototype/ember/`](../prototype/ember/) (`ember_proto.py` is the model, `kits.py` the four kits
and their grooves, `measures.py` CLUTTER's house distance measures, `gen_audition.py` renders and measures,
`build_page.py` writes the listening page). Every number below is from that Python harness, not from Kotlin.

Listening page: <https://claude.ai/artifact/Xmcj5t8MYSNCQPoTKHBcdw> (private to the owner; published 2026-11-14). It carries a playable 4×4 pad
grid for all four kits with three velocity layers and four round-robin takes per pad, each kit's groove,
velocity ladders and takes for the EMBER kit, all twenty-seven voices, every control on every voice, and (round 0b) each cymbal beside the recording it was fitted to.

Repository truth: read at `8742c5b8` (2026-11-14). APIs named here were grepped: `Modes.ring`,
`Modes.Mode(ratio, gain, t60, pan)`, `Modes.Friction(a).force(...)`, `Dsp.seedFor`, `Dsp.Noise`, `Room`
(`synth/.../Room.kt`), `BreakerChain.spring`/`Tank`, `PadHit.resolve` (`shell/.../PadHit.kt`), and
`Soot.render(voice, macros, velocity, take, model)` / `Clutter.render(voice, macros, velocity, take)` as the
drum-engine shape to copy. `EMBER` is a working name: the word is already a CIRCUIT preset ("EMBER PULSE"),
an ALLOY preset ("Endless Ember") and a name in `shell/.../Names.kt`. It is not an engine name anywhere.

## Round 1b: the long voices ring out (2026-11-15)

**What the owner said:** "Swell cuts abruptly", on the round-1 listening page. Asked how it should end, the owner
chose a natural ring-out of about 2.5 s after the build, and the same treatment for the bowed cymbal and both rolls.

**What was wrong.** Each long voice was cut by the closing 60 ms fade while it was still sounding. The swell's
mallets roll until 2.6 s and the wash peaks there, but the pad ended at 3.0 s, only 2.2 dB under its peak. The bow
ended 12.6 dB under, the press roll 16.1 and the brush roll 15.0 (Python, the last 100 ms before the fade against
the loudest 100 ms). The Python prototype had the same cut; the Kotlin port copied it.

**What changed** (prototype and Kotlin together):
- **The rolls** run 3.0 s (were 2.6). After the accent at 2.3 s they die away on their own: past 48 dB under by
  2.8 s.
- **The bowed cymbal** runs 3.6 s (was 2.4). Once the bow leaves (2.3 s), the cymbal takes an exponential fade
  from 2.4 s, reaching an extra 30 dB down at the end.
- **The swell** runs 5.0 s (was 3.0). The flat ride under it rings far longer than a pad: left alone it is still
  only 17 dB under its peak at 7.2 s. So from 2.8 s, just after the wash peaks, it takes an exponential fade
  reaching an extra 50 dB down at the end. An exponential fade is a straight line in dB, so it sounds like the
  cymbal decaying faster, not like a volume knob. By ear, labelled as such: `TAILS` in the prototype and the
  engine.
- A longer render draws the bow's and the rolls' noise afresh (the generator runs in order), so their takes are
  new draws of the same law. The swell's strokes are drawn before anything that depends on its length.

**Measured.** Over four takes, hard and soft, the last 100 ms before the closing fade now sit 53 to 58 dB under
the peak on the swell, 53 to 80 on the bow, 49 to 55 on the press roll and 54 to 56 on the brush roll (Python).
Kotlin against Python (`compare_kotlin.py`): all 27 voices still inside the spread of Python's own takes.
`EmberTest` adds a test: each of the four voices ends at least 40 dB under its peak, hard and soft, every take.
Render time in the generator's cold run: the swell 1.15 s (was 1.24 s at 3.0 s), the bow 0.52 s, the rolls
0.50 and 0.68 s; no slower than round 1.

`Ember.MODEL_VERSION` stays 1. Nothing has saved an EMBER pad yet: the engine has no presets and no kits, and it
is not on the picker.

## Round 1: the Kotlin port (2026-11-15)

**What the owner said:** "Much better", then "Proceed". Round 0e is the sound the port is held to.

**What was built.** `synth/.../Ember.kt` ports all 27 voices with every round-0e constant: the calf membrane, the
threshold parts, the implements, the fitted cymbals, hats and brushes, the club, the chain and the voicing.
`EmberDsp.kt` holds the stand-ins for the numpy and scipy calls the prototype makes. `EmberPatch` saves velocity,
take (0 to 3) and `MODEL_VERSION` 1. The fits ship as numbers in `synth/src/main/resources/ember/` (`cymbals.json`,
`brushes.json`, `club_tail.f32`, with `NOTICE.txt`); no recording ships. Registered in `Patches`, the two
`Velocity` arms (a velocity re-strikes the voice) and `SynthRoot` (rootless, as the other drum engines);
`PatchTypes.ROSTERLESS` until presets follow the kits; hand-built entries in `PatchesTest`, `PadRecipeTest` and
`SynthRootTest`; a `DeterminismTest` canary through JSON; `EmberTest`. `./gradlew :synth:generateEmberAudition`
writes every voice hard, soft and at its four takes; `prototype/ember/compare_kotlin.py` measures them against the
Python renders.

**The same arithmetic, checked:**
- scipy's `butter` (low, high and band, orders 1 to 4) through its analog prototype and the bilinear transform,
  matched against scipy's coefficients. The band-pass pairs its zeros into sections differently; it is the same
  filter.
- The way in, 44.1 to 176.4 kHz: scipy's `resample_poly(y, 4, 1)` itself, the same 81-tap Kaiser (beta 5)
  low-pass run polyphase. Within 2e-15 of scipy on a test signal.
- **The club's tail is the prototype's own draw**, not a new one. Close up, the tail carries most of a drum's low
  end, and Kotlin's own draw put the rack tom's 250 Hz octave 2.8 dB under what the owner heard.
  `prototype/ember/export_club.py` writes Python's tail at 44.1 kHz (39,690 samples, `club_tail.f32`) and checks
  that it rebuilds the room exactly; Kotlin brings it back to 176.4 kHz.

**What could not be the same arithmetic, disclosed:**
- **Random draws.** Kotlin's `Random` (seeded by `Dsp.seedFor`) in place of numpy's generator, with Box-Muller
  normals and Marsaglia-Tsang gammas. Every strike scatter, noise band, brush grain and loose part is another
  realisation of the same law, so a Kotlin take is a new take of the voice, not a copy of a Python one. This
  includes each cymbal's fixed doublet pairs. Most voices average that out over hundreds of modes; the bowed
  cymbal does not (below).
- **The way out.** The house `Tide.bandLimit` and `Dsp.decimate` in place of scipy's `resample_poly(y, 1, 4)`, as
  every house engine does.
- **The room convolution** uses the house single-precision FFT, where numpy works in double. It is covered by
  the comparison below, not measured on its own.

**Measured against the Python** (`compare_kotlin.py`, Python harness; distances are the house measure):
- **No brightness bias.** Averaged over each voice's four takes, Kotlin's share of energy above 4 kHz is
  0.10 dB under Python's across the 27 voices (median 0.30 dB under). The widest: the bowed cymbal 3.2 dB over,
  the conga 1.7 dB and the brush ride 1.6 dB under.
- **The drums are the same waveform:** correlation 0.978 to 0.996 on the kick, the four toms, the elbow tom and
  the conga, 0.90 on the sticks. The rest are noise-driven or random-struck and correlate as two takes do.
- **25 of 27 hard hits sit inside the spread of Python's own four takes.** The other two sit inside the spread
  of 16 Python takes:
  - **The bowed cymbal.** A narrow bow tone excites only a few modes, so where the doublets fall matters. Its
    2 kHz band swings 6 dB from take to take in both models. Kotlin's nearest Python take is 2.45 dB away;
    Python's 16 takes lie 1.26 to 6.26 dB apart.
  - **The swell.** Its first 150 ms is almost silent, so the 16-bit Kotlin clips' quantisation noise filled the
    top bands. With the Python renders quantised the same way, Kotlin's nearest take is 4.06 dB away and its
    median 6.16 dB. Python's own median is 5.80 dB. CLUTTER's round 1 found the same effect.
- **Velocity and takes in Kotlin** (the comparison script): soft against hard at least 2.12 dB (the bow, whose
  first 150 ms is its swell), the closest two takes of any voice 1.57 dB (the rack tom). Python's own: 1.92 and
  1.38 dB on the kits' 64 pads.

**`EmberTest`** holds the probes on all 27 voices:
- finite, peak-matched, DC under 0.005, exactly `secondsFor` long (the sweep's length follows SPEED);
- every take replays byte for byte, and the closest two of four takes are at least 1 dB apart;
- soft (0.35) against hard (1.0) at least 1.8 dB in shape at matched level (FIRED BRONZE's bar);
- every control moves every voice by at least 1 dB over the whole render, and an unknown control, a NaN control
  or a NaN velocity changes nothing. The foot hat's BUZZ has its own bar of 0.5 dB: the chick is short and its
  plates touch lightly. It measured 0.6 to 1.0 dB over four Python takes too.
- the patch round-trips through JSON and refuses a take, velocity or model it cannot render; every voice files as
  its kit role (past 1.5 s, PERC); no voice carries a maker's name.

The design's claims as tests:
- BUZZ 0 takes the snares off (no wires at all), and the cymbals and hats start at BUZZ 0, as recorded.
- WARMTH 0 is an exact bypass (the chain returns its input).
- The percussion gate: the swell rises at least 20 dB from its first half second to 2 to 2.5 s; the press roll
  and the brush roll grow and run past 2.5 s.
- The club is built once per DISTANCE, and only four rooms and their spectra are kept. A room is 1.3 MB and a
  spectrum 2 to 8 MB, so a DISTANCE sweep must not keep every value it passed.

**Render time** (Kotlin, this machine, JIT warmed): median 191 ms a hit across the 27 voices (two
warm-up renders, then the middle of three). The hats 68 to 73 ms, the drums 88 to 306 ms (the kick slowest), the
stick-struck cymbals 195 to 277 ms, the sizzle and the bow about 435 ms, the brush roll 524 ms and the swell 914 ms.
BREAKER on the same machine: ride 91 ms, crash 181 ms, riser 218 ms. So EMBER's hits run one to four times
BREAKER's, and its long moving voices are the slowest pads it has. Two changes made the port this fast. Neither
moved the comparison with Python: every number above read the same, to 0.1 dB, before and after them.
- **The way in** had been the house resampler, 371 of the ride's 665 ms. scipy's own polyphase filter is both
  the prototype's arithmetic and about a hundred times faster here (4 ms).
- **Strokes accumulate** into one force buffer in the rolls and the swell, instead of a full-length buffer per
  stroke, and dB curves go through `exp` rather than `pow` (the press roll 716 to 389 ms before the resampler change).
What is left is the fitted cymbals' 800 decaying sines (about 120 ms on the ride), their residual band noise
(about 80 ms) and the club's convolution at 176.4 kHz (about 65 ms). Each could be faster only by sounding
slightly different (the room at 44.1 kHz, pruning quiet modes), so each waits for its own listen.

Not heard: no one has listened to the Kotlin renders. The round-1 listening page sets every voice's Kotlin hit
beside the Python hit, with its soft hit and four takes: <https://claude.ai/artifact/21zU3g4oFcDfH8GoV8gKuz>
(private to the owner; published 2026-11-15).

## Round 0e: every kit brighter, by ear (2026-11-14)

**What the owner said:** "I meant all the kits" (the "bit too mid heavy" of round 0d).

**Measured first.** At its default (.5), the 1962 chain took about 3 dB off at 8 kHz and 5 to 6 dB at 16 kHz on
the snare and cymbals, and lifted the low mids by about 2 dB. The FREE groove was the most mid heavy (centroid
2257 Hz), mostly from the bowed cymbal, which locked to the ride's 732 Hz mode and was 41 dB down at 8 kHz.

**Changes, all by ear and labelled as such:**
- **The 1962 chain rolls off less top at every setting.** The ribbon's roll-off now runs 19 kHz down to 10.5 kHz
  (it was 16 kHz down to 6.8 kHz), the tape's 19.5 kHz down to 13 kHz (it was 18 kHz down to 9 kHz), and the low
  lift is .15 (it was .25). The valve and the tape's head bump are unchanged.
- **WARMTH is 0.15 lower everywhere:**
  - the voices' default goes from .5 to .35, which the EMBER kit uses;
  - After Hours goes from .5 to .35;
  - Soul Jazz goes from .75 to .6;
  - Free goes from .4 to .25.
- **Every pad gets the close mic's voicing** (`voicing`): 1.5 dB out around 600 Hz (broad) and a 2 dB high shelf
  from 6 kHz. Measured on an impulse: -1.49 dB at 600 Hz, +0.97 at 6 kHz, +1.86 at 16 kHz.
- **The bowed cymbal sings higher.** It locks to the ride's strongest mode between 900 Hz and 2 kHz (1379 Hz),
  with two more upper harmonics and its bow scratch up to 10 kHz.

**Measured after** (Python, each kit's groove, octave bands re the loudest; 63 Hz to 16 kHz):

| Groove | Centroid before | After | 8 kHz before / after | 16 kHz before / after |
|---|---:|---:|---:|---:|
| EMBER | 3908 Hz | 4802 Hz | -17.7 / -15.8 | -24.4 / -20.9 |
| After Hours | 3780 Hz | 4492 Hz | -6.9 / -4.5 | -16.4 / -12.5 |
| Soul Jazz | 3329 Hz | 4283 Hz | -16.6 / -14.1 | -25.6 / -20.7 |
| Free | 2257 Hz | 2838 Hz | -22.8 / -16.3 | -31.6 / -25.2 |

Free's 500 Hz octave falls from -2.0 to -14.9 dB re its loudest band, and 4 kHz rises from -17.5 to -8.8. The
toms and kicks stay as they were: a mallet on a low calf head is dark.

## Round 0d: the sweep's level and brightness, by ear (2026-11-14)

**What the owner heard** on round 0c: "Bring down the sweep a bit in the groove. It's overbearing. I think we
need to add a bit of brightness into the sound. It is a bit too mid heavy."

**Measured first.** In the After Hours groove the sweep was the loudest voice. Its energy peaked at 1 to 2 kHz
and fell to -9 dB at 8 kHz and -20 dB at 16 kHz. The kit's WARMTH (.65) took another 2 to 5 dB off the top.

**Three changes, all by ear and labelled as such:**
- **The sweep sits 3 dB lower in the grooves.** The groove mix for BRUSH_SWEEP went from .55 to .39 (`kits.MIX_VOICE`).
  It is the pad level a shelf kit should carry.
- **The sweep is brighter than the stirs it was fitted to** (`sweep_air_db`):
  - from 2 kHz up, +3 dB an octave, capped at +6 dB (so +3 at 4 kHz and +6 from 8 kHz);
  - and 1.5 dB less from 400 Hz to 1.3 kHz.

  The fit is unchanged. This is the owner's ear on top of it, not a measurement.
- **After Hours' WARMTH goes from .65 to .5** on all sixteen pads, so the 1962 chain rolls off less of the top.

**Measured after** (Python, the After Hours groove, octave bands re the loudest):

| | 125 | 250 | 500 | 1k | 2k | 4k | 8k | 16k | Centroid |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Before | -3.7 | -0.2 | -4.7 | -0.8 | 0.0 | -1.5 | -8.3 | -18.4 | 3150 Hz |
| After | -3.1 | 0.0 | -7.3 | -5.6 | -3.1 | -2.2 | -6.9 | -16.4 | 3780 Hz |

The sweep alone (A04, velocity .6) now peaks at 2 to 4 kHz, with its centroid at 4074 Hz (it was 3214).

## Round 0c: the brush sweep and roll, refitted (2026-11-14)

**What the owner heard:** "Sweeps sound like hail hitting a roof."

**Why.** Round 0b's sweep was a cloud of separate micro-impulses (5,000 to 11,000 a second), and each one struck
the snare head and the wires: a rain of tiny drum hits, which is hail on a roof. Measured:
- **Impulsive.** The median kurtosis of its top half (above 2 kHz, 20 ms frames) was 5.7 to 7.8. A smooth hiss
  is 3, and real stirs measure 2.9 to 3.0.
- **The wrong spectrum.** It peaked at the head's own pitch (253 to 320 Hz) where real stirs carry a broad hump
  from about 500 Hz to 5 kHz.

The brush roll had the same fault, more mildly: kurtosis 4.1 against a real flutter's 3.1.

**What changed.** A brush sweep is many wires sliding at once, which sums to a smooth noise, not a train of hits.
So the sweep and the roll are now fitted noise, like the closed hats. `prototype/ember/fit_ember_brushes.py`
writes `prototype/ember/brushes.json` from CC0 recordings:
- **Source:** Karoryfer Samples, Swirly Drums (CC0 1.0), `sfzinstruments/karoryfer.swirly-drums` at
  `c40dafe0`, main mic. This is a brush-played kit named for its snare swirls ([KVR listing](https://www.kvraudio.com/product/swirly-drums-by-karoryfer)).
- **What is fitted:** 15 stirs at four dynamic levels, and four flutters. For each, per third-octave band:
  - the mean level;
  - how many dB the band moves per dB of overall level (so the fast middle of a stroke is brighter, as measured);
  - the level's wobble (50 ms) and sway (250 ms).

  dl4's take 1 on the main mic is byte-identical to its skin-mic file in the library, so it is left out.
  `NOTICE.txt` lists the nineteen files and their sha256.
- **BRUSH_SWEEP:** band noise along the stir spectrum for its velocity (soft is dl1's warm middle, hard dl4's
  brighter top), riding EMBER's circle. SPEED is still the circle's speed and length. TUNE slides the spectrum.
  BUZZ now tilts the wire band (2 to 8 kHz) 6 dB either side of the recording.
- **BRUSH_ROLL:** band noise along the flutter spectrum, crescendoing over 2.3 s with a stroke-rate ripple (the
  two hands, rate set by SPEED), into a brush accent on the snare. A soft roll leans toward the soft stir.

**Measured** (Python, dry, 1 s of the stroke's middle against 1 s of the recording):

| | Clickiness (kurtosis) | Centroid | Spectral shape, dB from the recording |
|---|---:|---:|---:|
| Sweep, velocity .3, vs dl1 | 2.9 (was 5.7 to 7.8) | 2974 Hz vs 2661 | 5.07 (two real takes: 3.05) |
| Sweep, velocity .55, vs dl2 | 2.9 | 4261 Hz vs 4444 | 3.23 (2.65) |
| Sweep, velocity .8, vs dl3 | 2.9 | 4805 Hz vs 4487 | 4.94 (3.06) |
| Sweep, velocity 1, vs dl4 | 3.0 | 5487 Hz vs 5508 | 4.02 (3.96) |
| Brush roll vs flutter | 3.0 (was 4.1; real 3.1) | 6157 Hz vs 6525 | 2.36 |

Every other check still holds on all 768 kit renders. SPEED on the brush roll is now the weakest control on the roll,
at 1.26 dB between 0 and 1: it only sets the hands' ripple rate. Disclosed, not fixed. The sweep's controls move
5.6 to 40 dB.

**Not heard.** The page plays each sweep and the roll, then the recording.

## Round 0b: the cymbals, refitted (2026-11-14)

**What the owner heard:** "Cymbals need a lot of work. Sounds like hitting sheet metal."

**Why.** Round 0's cymbals were guessed: 40 to 226 modes placed by a formula, with a top band that grew in
after the strike. Measured against CC0 recordings of a jazz club kit, they sat 11 to 34 dB from the real
cymbals (the house distance), where two takes of one real flat ride sit 6.16 dB apart over the first 150 ms.
Their spectral centroid was half the real one's: 3.8 kHz against 7.3 kHz on the ride. The energy sat in
low-middle modes that rang as separate tones. That is the sheet metal.

**What changed.** Every cymbal and hat is now resynthesized from a recording, the way BREAKER's approved
cymbals are (docs/BREAKER.md, "The cymbals"). `prototype/ember/fit_ember_cymbals.py` runs BREAKER's own fitter
(`scripts/fit_breaker_cymbals.py`, imported, not copied) on cymbals BREAKER does not use, and writes
`prototype/ember/cymbals.json`. `NOTICE.txt` beside it lists the twelve recordings and their sha256.
- **Source:** Virtuosity Drums (CC0 1.0), `sfzinstruments/virtuosity_drums` at `9f04cf9a`, overhead mic.
  The library describes itself as a contemporary jazz kit recorded live, "indicative of a typical setup in a
  live club". Only fitted numbers are in the repository; no recording is.
- **RIDE:** the library's flat ride (no bell), the dry, spread jazz ride. 400 modes. **RIDE_BELL:** its ride
  bell, 240 modes. **SIZZLE (new voice):** a crash played with its rivets in, 396 modes. The rivets come from
  the recording, not a model. **HAT_OPEN:** 300 modes. **HAT_CLOSED, HAT_FOOT:** too short for the 8192-point
  modal fit, so they are each third-octave band's level over time, hard and soft, played as band noise.
- **Velocity:** BREAKER's law. Each mode moves by its band's measured soft-minus-hard difference, and the stick
  moves toward the soft recording's spectrum.
- **A take:** a fresh strike (BREAKER's law): up to 0.3 Hz of detune, a fresh phase, 1.6 dB rms of level scatter.
- **Two additions over BREAKER's renderer:**
  - **Doublets (shape):** a hammered plate's modes come in pairs a few hertz apart, so each partial beats.
    Each fitted mode becomes a pair 0.4 to 3 Hz apart, fixed per cymbal. The fit's 5.4 Hz resolution cannot
    see the split.
  - **The residual (measured):** per band and over time, what the modes and stick leave out of the recording
    (Serra and Smith), played as band noise. It is what makes a real cymbal look diffuse rather than a set of
    lines. It is fitted under 200 Hz but not played there, because the overhead heard the kick and the room.
- **Implements still work:** brush, mallet and bow drive the fitted modes through EMBER's own forces, so the
  brush ride, the bowed cymbal and the mallet swell use the real flat ride's body.
- **BUZZ on the cymbals and hats now starts at 0,** which is the cymbal as recorded. Turning it up adds EMBER's
  modelled loose rivets or plates on top. Kits that piled synthetic rivets on the ride now use SIZZLE:
  - After Hours A06 is Far Sizzle;
  - Soul Jazz A05 is Sizzle Crash;
  - Free's bell and bow lost their synthetic rivets.

**Measured** (Python, each voice dry at DISTANCE 0 and WARMTH 0, against the recording; a take the fit never
saw is marked):

| Voice | Recording | First 150 ms (dB) | Whole render (dB) |
|---|---|---:|---:|
| RIDE | oh_flatride_ride_vl3_rr2 (held out) | 9.27 | 10.77 |
| RIDE | oh_flatride_ride_vl1_rr1 | 5.93 | 11.23 |
| RIDE_BELL | oh_ride_bell_vl3_rr1 | 6.36 | 8.73 |
| SIZZLE | oh_crash_sizzle_vl3_rr1 | 6.53 | 10.42 |
| HAT_CLOSED | oh_hh_closed_vl4_rr1 | 5.6 | 7.54 |
| HAT_FOOT | oh_hh_pedal_vl3_rr1 | 5.88 | 7.22 |
| HAT_OPEN | oh_hh_open_vl4_rr1 | 5.73 | 7.7 |

Two takes of one real flat ride: 6.16 dB over the first 150 ms and 8.57 dB whole. Round 0's cymbals
against the same recordings: 11 to 34 dB. The ride's centroid is now 6.6 kHz against the recording's 7.2 to
7.3 kHz (it was 3.8). The whole-render distances on the ride stay near 11 dB because the fit keeps each mode's
steeper (early) decay, BREAKER's "tight" choice. The overhead recording's slow tail is mostly the room, and
EMBER's room is DISTANCE.

**Not heard.** The numbers say these are now the same kind of cymbal as real ones. Whether they still sound
like metal sheet is the owner's listen: the page plays each EMBER cymbal, then its recording.

## What it is

A drum engine for a live jazz and soul kit as it sounded in a warmly lit, smoky club in 1962: creative, a
bit eclectic, a bit rebellious. The owner asked for it on 2026-11-14, with "realistic" and "alive" as the
two words that matter and the owner's acceptance gates as the bar.

**The characteristic gesture is: a stick, a brush or a hand meets calfskin or thin bronze → the head sags
back to pitch while the snare wires and ride rivets answer on their own threshold → the rest of the kit hums
along → a low-ceilinged room and a warm 1962 chain close around it.**

What "alive" means here, as mechanisms rather than adjectives:

- **Calf heads.** Every drum is a circular membrane (Bessel-zero mode ratios, strike-position weights
  `|J_m(z r)|`). Calf's high modes die far faster than its low ones (t60 ∝ (f/f1)^-1.05 to -1.35 by drum,
  shape), a hard hit starts sharp and sags back to pitch (tension modulation, closed-form phase warp), and the
  second head rings the low modes 2.5% higher at a third of the level, so a tom beats as it sustains.
- **Threshold parts.** Snare wires, ride rivets, hi-hat plates and tambourine jingles move only when the
  surface under them moves harder than their own threshold. A soft stroke is clean, a hard one sizzles.
  Velocity changes what you hear, not only how loud it is. This is CLUTTER's loose-parts idea made
  continuous: the wires and rivets follow the body's ringing, not only the strike.
- **The kit as one instrument.** A kick or a tom sets off the snare wires across the kit (BUZZ); the kick
  also stirs the floor tom.
- **Implements.** Sticks (a short Hertz-like contact, longer and darker when soft), brushes (25 to 65 wires
  landing over 2.5 to 7 ms; or dragged in circles as a cloud of micro-slips), felt mallets, hands, a bass
  bow, an elbow leaning into the floor tom.
- **The player.** Four takes per velocity zone move where the stick lands and how it lies, which wires and
  rivets fly, which listeners are early. The grooves sit on a triplet grid with the ride a hair ahead and the
  snare comping behind.
- **The club.** A low ceiling (~2.6 m), a hard bar, a brick back wall, a soft crowd: a short warm room
  (lows ~0.62 s, highs ~0.32 s, shape) balanced by energy from +16 dB direct over the drum to -4 dB at the
  back (DISTANCE). Then the 1962 chain (WARMTH): a ribbon mic's soft top, a valve preamp driven harder by
  harder hits, tape's head bump. WARMTH 0 is an exact bypass.

## Why it earns a pad (sibling check)

The roster is drum-heavy (SOOT, BREAKER, BACKWASH, SCUFF, CLUTTER, GATECRASH, MIRRORBALL, FIRED BRONZE and the
older THUMP, SKIN, ONSET, TERRA). The two nearest:

- **SOOT BRUSHED NOIR** (`SootGenreKits.kt`, 76 BPM) is a boom-bap kit with "brush-weight" snares: SOOT's stick
  snare with its edge turned off, through a dictaphone. It has no brush, no ride, no sweep.
- **BACKWASH SOUL POCKET** is recorded acoustic percussion over procedural core drums, with cross stick,
  cowbell and tambourine. Its drums are not modelled heads and it has no brushes or riveted ride.

Neither can do the three things that make a 1962 jazz kit itself: a **brush sweep** (a continuous friction
gesture on a coated head, not a strike), a **riveted ride** whose sizzle follows the cymbal and dies before
it, and **calf heads that sag and beat** in a small room. Every other drum engine on the roster is a
processed sample-flip sound (dictaphone, surf amp, crushed bus, phone mic); EMBER is the first that aims at
the acoustic kit as it stood on the bandstand.

## The three directions (round 0 took Strong)

| | Direction | Operator | Gesture | Novelty | Risk | Cost |
|---|---|---|---|---|---|---|
| **Strong** | The bandstand: physical kit, threshold parts, the club and the chain, four kits from one night | STACK + PORT (CLUTTER's loose parts onto a drum kit) | clear | high: no brushes, rivets or calf on the roster | medium: many voices | medium |
| Alternate A | The record: a 1962 jazz record sampled, as SOOT and BREAKER do | JUXTAPOSE | clear | low: four engines already do sample-flip | low | low |
| Alternate B | The player as the engine: one "drummer" voice that plays phrases (comping, bombs, fills) | MUTATE | strong | high | high: a phrase engine is a sequencer, not a pad | high |

Strong wins because "realistic" and "alive" are both answered by mechanisms the roster lacks, and its four
kits can be one night (club set, after hours, soul set, the late free set) rather than four effect settings.
Alternate B survives as the grooves' feel and the authored roll and swell.

## Signal flow

```
voice, macros, velocity, take
    │
    ▼
[implement @176.4 kHz: stick | brush tap | brush sweep (micro-slip cloud) | mallet | hand | bow]
    │   width and amount follow velocity; the take moves the strike radius and angle
    ▼
[body: calf membrane (Bessel modes, calf loss, sag, two heads)
       | cymbal: 240 to 400 modes fitted to a recording, as doublets, plus its residual (round 0b)
       | wood and steel modes (stick, hoop, sticks clicked)]
    │                         ╲
    │                          ──▶ [threshold parts: wires | rivets | hat plates | jingles]
    │                               driven by the body's own motion, one way, scaled to the body
    ▼                                          │
    └──────────────── sum ◀────────────────────┘
    │
    ▼
[DISTANCE: direct + the club's response, by energy [cache: DISTANCE]] ──▶ [WARMTH: ribbon, valve, tape]
    ──▶ band-limit, decimate to 44.1 kHz, DC, cut and fade
```

One-way readouts: the wires and rivets never damp or detune the head or the cymbal. Disclosed; it keeps the
render a fixed cost.

## Controls

Five per voice (four on SNAPS, CLAPS and STICKS), each 0..1, no HOLD (finite hits).

| Control | Default | What it moves | Voices |
|---|---:|---|---|
| TUNE | .50 | head tension or cymbal size, about ±5 semitones (±4 on cymbals, ±6 on kick, toms, conga) | all |
| DAMP | .30 | a felt strip, a wallet on the snare, a finger on a cymbal, the hi-hat pedal pressure | drums, cymbals, hats, tambourine, conga, sticks |
| BUZZ | .50 | how loose the snare wires, rivets, plates and jingles are, and how much the kit hums along. **0 takes the snares off** (exact) | drums, cymbals, hats, tambourine |
| DISTANCE | .30 | the microphone, from over the drum (+16 dB direct) to the back of the club (-4 dB) | all |
| WARMTH | .35 (.50 until round 0e) | the 1962 chain: ribbon, valve, tape. **0 is an exact bypass** | all |
| SPEED | .50 | how fast the brush circles, the roll strokes or the swell's mallets come | sweep, rolls, swell |
| BEND | .50 | how far the elbow presses the floor tom: 0.4 to 5 semitones | elbow tom |
| PRESSURE | .50 | how hard the bow bites: more harmonics, more scratch | bowed cymbal |
| CROWD | .40 | one person to a table of six, each their own hands and a little early or late | snaps, claps |
| SLAP | .30 | open palm tone to a cupped, cracking slap | conga |

## The voices (27)

Drums: KICK (18-inch, calf, open, felt beater, "feathered" when soft), SNARE (14-inch, calf, tuned high),
RIMSHOT, CROSS_STICK, TOM_RACK (12-inch), TOM_FLOOR (14-inch), ELBOW_TOM, MALLET_TOM. Brushes: BRUSH_TAP,
BRUSH_SWEEP and BRUSH_ROLL (fitted to recorded stirs and flutters from round 0c), BRUSH_TOM, BRUSH_RIDE. Cymbals (fitted to recordings from round 0b): RIDE (a flat ride), RIDE_BELL, SIZZLE (riveted),
BOWED_CYMBAL, CYMBAL_SWELL. Hats: HAT_FOOT (the 2-and-4 chick), HAT_CLOSED (stick), HAT_OPEN. Room and hands: SNAPS (the
1962 crowd snapping instead of clapping), CLAPS, TAMBOURINE, CONGA, STICKS (two sticks clicked: the count-in).
Rolls: PRESS_ROLL.

Long voices are declared PERC as BREAKER declares its riser: the rolls (3.0 s since round 1b), the swell (5.0 s),
the bow (3.6 s) and the slowest brush sweeps (a sweep is one full circle, 1.9 s at SPEED 0 down to 0.5 s, plus 0.35 s of
room).

## The kits (four, sixteen pads each)

| Kit | BPM | Night | What it is |
|---|---:|---|---|
| EMBER | 138 | the first set | hard bop on sticks: open feathered kick, high crisp snare, riveted ride, hat foot on 2 and 4, cross stick, rimshot, two toms, snaps, sticks, the elbow tom, press roll (A15), mallet swell (A16) |
| EMBER AFTER HOURS | 66 | 3 a.m. | a ballad on brushes: slow and quick sweeps, brush taps, a brush ride and a riveted sizzle far off, brushed toms, a mallet tom, two people snapping, brush roll (A15), far swell (A16); more room, a little chain |
| EMBER SOUL JAZZ | 104 | the organ-trio set | a shuffle with a fat loose backbeat, tight hats, tambourine, a table clapping, high, low and slap congas, rimshot and cross stick, snare roll (A15), the whole room clapping (A16); close mics, warmest chain |
| EMBER FREE | 80 | the late set | the rebellious one: mallets on toms tuned wide, a bowed cymbal two ways, snares off, an elbow in the floor tom, sticks clicked, loose rattling hats, one person snapping, swell (A15), the floor tom setting the snares off (A16); further back, less chain |

The hi-hat foot closes the open hat on every kit (and the stick hat does too on EMBER). The grooves are two
bars on a triplet grid; the FREE groove is a phrase, not a pattern.

| Pad | EMBER | EMBER After Hours | EMBER Soul Jazz | EMBER Free |
|---|---|---|---|---|
| A01 | Kick (KICK) | Soft Kick (KICK) | Kick (KICK) | Open Kick (KICK) |
| A02 | Snare (SNARE) | Brush Tap (BRUSH_TAP) | Fat Snare (SNARE) | Snares Off (SNARE) |
| A03 | Hat Foot (HAT_FOOT) | Hat Foot (HAT_FOOT) | Hat Tight (HAT_CLOSED) | Mallet Tom High (MALLET_TOM) |
| A04 | Hat Open (HAT_OPEN) | Slow Sweep (BRUSH_SWEEP) | Hat Open (HAT_OPEN) | Mallet Tom Low (MALLET_TOM) |
| A05 | Ride (RIDE) | Brush Ride (BRUSH_RIDE) | Sizzle Crash (SIZZLE) | Bowed Cymbal (BOWED_CYMBAL) |
| A06 | Ride Bell (RIDE_BELL) | Far Sizzle (SIZZLE) | Tambourine (TAMBOURINE) | Bowed, Biting (BOWED_CYMBAL) |
| A07 | Cross Stick (CROSS_STICK) | Quick Sweep (BRUSH_SWEEP) | Hat Foot (HAT_FOOT) | Elbow Tom (ELBOW_TOM) |
| A08 | Rimshot (RIMSHOT) | Soft Cross Stick (CROSS_STICK) | Claps (CLAPS) | Hard Rimshot (RIMSHOT) |
| A09 | Rack Tom (TOM_RACK) | Brushed Tom (BRUSH_TOM) | Conga High (CONGA) | Sticks (STICKS) |
| A10 | Floor Tom (TOM_FLOOR) | Brushed Floor (BRUSH_TOM) | Conga Low (CONGA) | Bell, Open (RIDE_BELL) |
| A11 | Hat Stick (HAT_CLOSED) | Mallet Tom (MALLET_TOM) | Rimshot (RIMSHOT) | Fast Sweep (BRUSH_SWEEP) |
| A12 | Snaps (SNAPS) | Two Snapping (SNAPS) | Cross Stick (CROSS_STICK) | Loose Hats (HAT_OPEN) |
| A13 | Sticks (STICKS) | Hat Open (HAT_OPEN) | Conga Slap (CONGA) | Hat Foot (HAT_FOOT) |
| A14 | Elbow Tom (ELBOW_TOM) | Loose Brush (BRUSH_TAP) | Floor Tom (TOM_FLOOR) | One Snapping (SNAPS) |
| A15 | Press Roll (PRESS_ROLL) | Brush Roll (BRUSH_ROLL) | Snare Roll (PRESS_ROLL) | Swell (CYMBAL_SWELL) |
| A16 | Cymbal Swell (CYMBAL_SWELL) | Far Swell (CYMBAL_SWELL) | The Room Claps (CLAPS) | Floor Tom, Snares On (TOM_FLOOR) |

## Measured (Python, round 0)

All from `gen_audition.py` at round 0 (Python harness, not Kotlin). Distances are the house measure (SCUFF, ETCH,
CLUTTER: the RMS difference of level-matched third-octave spectrograms over the first 150 ms; over the whole render
for the rolls, the swell, the bow and the sweeps). The owner heard ETCH takes at 0.5 to 0.8 dB as one strike.

| Kit | BPM | Soft vs hard, least (dB) | Closest takes (dB) | Closest two pads (dB) |
|---|---:|---:|---:|---|
| EMBER | 138 | 2.94 | 1.61 | 4.53 (A10 / A14) |
| EMBER AFTER HOURS | 66 | 2.16 | 1.65 | 4.1 (A09 / A11) |
| EMBER SOUL JAZZ | 104 | 2.18 | 1.78 | 4.75 (A08 / A16) |
| EMBER FREE | 80 | 1.92 | 1.38 | 7.52 (A11 / A13) |

Between kits, the same pad slot:

| Kits | Same slot, mean (dB) | Same slot, closest (dB) | Identical pads |
|---|---:|---:|---:|
| ember / after-hours | 13.1 | 4.07 (A03) | 0 |
| ember / soul-jazz | 13.4 | 3.8 (A04) | 0 |
| ember / free | 23.8 | 6.67 (A01) | 0 |
| after-hours / soul-jazz | 18.4 | 2.98 (A01) | 0 |
| after-hours / free | 25.0 | 9.96 (A01) | 0 |
| soul-jazz / free | 27.6 | 10.18 (A08) | 0 |

- **Velocity:** on all 64 pads, a soft hit (velocity .35) differs from the hard one at matched level by at least
  1.92 dB. The changes are in the stroke: on the EMBER snare the spectral centroid falls from
  about 2.5 kHz hard to about 1.3 kHz soft because the wires lift less, not because a filter closes.
- **Round robin:** the closest two of any pad's four hard takes are 1.38 dB apart. Round 0
  first measured 0.51 dB on FREE's Snares Off (copies by the house measure); a take now also scatters each head
  mode by 1.5 dB (the stick's angle and bounce), which fixed it.
- **Health:** 768 kit renders, deterministic True, all finite, peak 0.89 (the audition
  match), DC at most 0.00056. An unknown control, a NaN control and a NaN velocity change nothing. WARMTH 0 and
  BUZZ 0 are exact bypasses of their stages.
- **Every control moves every voice:** the weakest are HAT_FOOT.BUZZ 1.07 dB, HAT_CLOSED.BUZZ 1.66 dB, TAMBOURINE.BUZZ 1.83 dB, CONGA.WARMTH 1.99 dB between 0 and 1,
  over the whole render. BUZZ on the hats only adds looseness to the recorded hat, so it moves least.
- **Render time (Python):** median 154.3 ms a hit. The fitted cymbals are the slow ones in Python (ride about
  1.1 s, sizzle 1.9 s, the swell and bow 2 to 5 s), because 800 sines are summed with numpy. BREAKER already renders 400
  fitted modes in Kotlin; round 1 sets EMBER's budget there, and keeps the doublets only if they are heard.
- **Not heard.** None of this says the kit sounds like 1962. That is the owner's listen.

## Gates

| Gate | State | Evidence |
|---|---|---|
| Melodic, keygroup, chord tool | not applicable | a drum engine |
| Articulation (HOLD, STRUM) | not applicable | finite hits; no picked or thumbed strings |
| Percussion: roll or riser | authored, not heard | press roll, brush roll, snare roll and mallet swell on A15/A16 of every kit, rendered in Python; in Kotlin the swell's rise and the rolls' growth are tests (`EmberTest`) |
| Drums: several genre kits on the shelf | authored, not on the shelf | three genre kits beside the EMBER kit, measured in Python; the shelf is round 2 |
| Velocity and round robin: rendering | verified in Kotlin by test (round 1) | `EmberTest`, `DeterminismTest`; see "Round 1" |
| Velocity and round robin: saving, playback, export | open | the kits are not on the shelf yet (round 2); the page's pad grid is a browser stand-in |
| The Kotlin port renders round 0e | measured (Python harness against Kotlin clips) | `compare_kotlin.py`: no brightness bias; 25 of 27 within Python's own four takes, the other two within 16 |
| Sound character | open | round 0e heard as "Much better"; the Kotlin renders not heard |

## Render budget

Kotlin, round 1 (see "Round 1" for the numbers): the median hit takes 191 ms warmed, the swell 0.9 s. The club is
cached for the four most recent DISTANCE values, with its spectrum per FFT size; the fits and the club's tail load
once. The fitted modes are built per hit with a rotating phasor (no `sin` per sample), because every take re-strikes
every mode (a fresh phase, detune and level). The Python medians are in "Measured".

## Sonic risks (where this would sound bad)

1. **The snare wires as hiss.** If the wires read as noise laid over a drum, the threshold law is wrong or
   too hot. Probe: the velocity ladder (soft should buzz less in proportion, not only in level) and the
   page's A02 against A14 on After Hours (loose wires).
2. **The cymbals as sheet metal (heard at round 0).** Round 0b replaces guessed modes with fitted ones. Probe:
   the page's "Cymbals against real cymbals" section, and the distances above; if a cymbal is still heard as
   metal sheet, compare its doublets on and off before anything else.
3. **The sweep as hail (heard at round 0b), or as wind.** Round 0c replaces the impulse cloud with noise fitted
   to recorded stirs; its head and wires are in the recording's spectrum. Probe: the page's sweep comparisons; if
   it now reads as wind, the stroke's speed envelope or the follow is wrong, not the spectrum.
4. **The room as reverb.** At DISTANCE .3 the club should be heard as closeness and air, not a tail. Probe:
   the DISTANCE sweep on KICK and SNARE.
5. **WARMTH as mud.** The chain's head bump and top roll-off stack with calf's darkness. Probe: the WARMTH
   sweep on RIDE and SNARE; Soul Jazz is the warmest kit.
6. **Synthetic crowd.** Snaps and claps are cavity-resonance bursts; if they read as electronic claps they
   should go. Probe: CROWD sweep.

## Next rounds

- **Round 0f:** the owner heard rounds 0b to 0e ("Much better", then "Proceed", 2026-11-15). Still open from it:
  which voices convince, whether these are the right four kits (a Latin jazz kit is the obvious swap for FREE if
  rebellious should mean something else), the WARMTH and DISTANCE defaults.
- **Round 1:** done (above). The Kotlin renders wait for the owner's ear.
- **Round 2:** the four kits on the shelf through the shared robin, `EmberKitTest` (saving, playback through
  `PadHit.resolve`, MPC 3 export read-back), the picker entry with its on-device test.
- **Round 3:** the owner's phone and MPC 3 checks.

## Why it is built this way

- **Every drum shares one membrane.** A kick, a snare, a tom, a conga and a tambourine are the same equation
  with a different size, tension, loss and strike. That keeps them sounding like one kit in one room, and it
  makes the Kotlin port one membrane function plus small voice recipes.
- **The sizzle has a threshold.** Real snare wires and rivets sit still until the surface pushes them hard
  enough. Modelling that threshold, instead of mixing in noise, is what makes a soft stroke a softer player
  and not the hard stroke turned down: the gate's "timbral velocity change" comes from physics, not a filter.
- **Brushes are their own implement.** A brush is not a soft stick: it lands as a spray of wires and it can
  be dragged. The sweep is the one gesture no stick engine can make, and the reason a 1962 kit needs a new
  engine at all.
- **The room and the chain come after the kit.** Everything the club and the tape do is outside the drum, so
  DISTANCE and WARMTH can go to zero for a dry modern kit, and the drums stay realistic without them.
- **The risky decision:** twenty-six voices in round 0 instead of the house's usual two. They share four
  primitives (membrane, cymbal bands, threshold parts, implements), so the risk is breadth of listening, not
  code. The owner's picks decide which ones survive into Kotlin.
